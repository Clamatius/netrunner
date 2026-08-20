(ns ai-websocket-error-recovery-test
  "Regression tests for :game/error recovery in ai_websocket_client_v2.

   When the server catches an exception processing a command it rolls its
   state back and sends [:game/error] (web/game.clj). The client must not
   silently keep its now-divergent optimistic state — it must resync so the
   autonomous loop decides against authoritative ground truth."
  (:require [clojure.test :refer :all]
            [test-helpers :refer [mock-client-state with-mock-state mock-websocket-send!]]
            [clojure.string :as str]
            [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-basic-actions]))

(deftest test-game-error-triggers-resync
  (testing "receiving :game/error requests a full resync for the current game"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "corp")
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [gameid (:gameid @state/client-state)]
            (ws/handle-message {:type :game/error :data nil})
            (is (= 1 (count @sent)) "exactly one message sent on error")
            (let [{:keys [type data]} (first @sent)]
              (is (= :game/resync type) "must request a resync")
              (is (= gameid (:gameid data)) "resync must target the current game"))))))))

(deftest test-game-error-clears-stale-state
  (testing "resync path clears cached game-state but preserves gameid"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "corp")
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [gameid (:gameid @state/client-state)]
            (is (some? (:game-state @state/client-state)) "precondition: game-state present")
            (ws/handle-message {:type :game/error :data nil})
            (is (nil? (:game-state @state/client-state)) "stale game-state cleared before resync")
            (is (= gameid (:gameid @state/client-state)) "gameid preserved for resync")))))))

(deftest test-game-error-without-gameid-does-not-send
  (testing "no resync attempted when we have no game to resync"
    (let [sent (atom [])]
      (with-mock-state (assoc (mock-client-state :side "corp") :gameid nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (ws/handle-message {:type :game/error :data nil})
          (is (empty? @sent) "must not send resync with no gameid"))))))

;; ============================================================================
;; #93: lobby teardown invalidation
;; ============================================================================
;; close-lobby! announces itself with a BARE [:lobby/state] (no data). The old
;; handler `(when data ...)` dropped it, so the cached snapshot kept answering
;; every status query for a game the server had already discarded.

(deftest test-bare-lobby-state-marks-lobby-gone
  (testing "bare [:lobby/state] in a STARTED game marks it gone, wakes waiters, probes"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "runner")
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [cursor-before (state/get-cursor)]
            (ws/handle-message {:type :lobby/state :data nil})
            (is (state/lobby-gone?) "lobby-gone flag must be set")
            (is (> (state/get-cursor) cursor-before)
                "cursor must bump so a blocked `wait` wakes and sees the verdict")
            ;; The bare signal is not uniquely teardown (it also answers a
            ;; :lobby/list from an unseated uid while the game lives on for
            ;; the opponent) — so the verdict must be probed: if the server
            ;; still hosts us, the resync reply retracts the flag.
            (is (some #(= :game/resync (:type %)) @sent)
                "must probe with a resync so a false verdict self-corrects")))))))

(deftest test-bare-lobby-state-without-started-game-is-noop
  (testing "bare [:lobby/state] before ever joining a game sets no flag"
    (let [sent (atom [])]
      (with-mock-state (-> (mock-client-state :side "corp")
                           (assoc :gameid nil :game-state nil))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (ws/handle-message {:type :lobby/state :data nil})
          (is (not (state/lobby-gone?))
              "a fresh client with no game has nothing to invalidate")
          (is (empty? @sent) "nothing to probe either")))))
  (testing "bare [:lobby/state] in a waiting-room lobby (gameid, no game-state) sets no flag"
    ;; Pre-game the lobby-membership rules differ (an unstarted lobby DOES
    ;; unseat on disconnect), so a bare lobby/state there is routine, not a
    ;; teardown of a game in progress.
    (let [sent (atom [])]
      (with-mock-state (assoc (mock-client-state :side "corp") :game-state nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (ws/handle-message {:type :lobby/state :data nil})
          (is (not (state/lobby-gone?))
              "an unstarted lobby is a waiting room, not a game to invalidate"))))))

(deftest test-lobby-state-with-data-retracts-lobby-gone
  (testing "fresh lobby data (new/rejoined game) clears a stale lobby-gone verdict"
    (with-mock-state (assoc (mock-client-state :side "corp") :lobby-gone? true)
      (ws/handle-message {:type :lobby/state
                          :data {:gameid "00000000-0000-0000-0000-000000000002"}})
      (is (not (state/lobby-gone?))
          "being seated in a lobby again must retract the gone verdict"))))

;; ============================================================================
;; #114: the deferred auto-end resume must be ON the live state-update path
;; ============================================================================
;; The resume logic itself is unit-tested in ai-basic-actions-test. What that
;; can't show is whether anything ever CALLS it in a real game — and a resume
;; nobody calls leaves the turn orphaned exactly as before. These pin the two
;; message types that carry new state to a seat sitting at 0 clicks behind an
;; opponent-owed decision.

(defn- await-resume-call
  "Run BODY and return true if the deferred resume fired within 2s.
   The hook runs on a future (the diff handler is the websocket receive thread
   and end-turn! sleeps a full second), so this can't be a bare assertion."
  [body-fn]
  (let [called (promise)]
    (with-redefs [ai-basic-actions/resume-deferred-auto-end! (fn [] (deliver called true))]
      (body-fn)
      (= true (deref called 2000 :timeout)))))

(deftest test-game-diff-fires-deferred-auto-end-resume
  (testing "#114: an incoming diff re-checks an armed turn"
    (with-mock-state (assoc (mock-client-state :side "corp")
                            :auto-end-deferred {:turn 10 :side :corp})
      (let [gameid (:gameid @state/client-state)]
        (is (await-resume-call
              #(ws/handle-message {:type :game/diff
                                   :data {:gameid gameid :diff [{} {}]}}))
            "the diff that clears the opponent's prompt is the ONLY event that can end this turn")))))

(deftest test-game-resync-fires-deferred-auto-end-resume
  (testing "#114: a resync counts too — the arm survives a reconnect"
    (with-mock-state (assoc (mock-client-state :side "corp")
                            :auto-end-deferred {:turn 10 :side :corp})
      (is (await-resume-call
            #(ws/handle-message {:type :game/resync
                                 :data {:turn 10 :active-player "corp"}}))
          "otherwise a seat that reconnected waits for a diff the game may never send"))))

(deftest test-game-diff-does-not-fire-resume-when-unarmed
  (testing "#114: no arm, no thread — diffs arrive constantly all game"
    (with-mock-state (mock-client-state :side "corp")
      (let [gameid (:gameid @state/client-state)]
        (is (not (await-resume-call
                   #(ws/handle-message {:type :game/diff
                                        :data {:gameid gameid :diff [{} {}]}})))
            "unarmed clients must not spawn a resume on every diff")))))

;; ============================================================================
;; #142: an IGNORED diff must not be bookkept as an applied one
;; ============================================================================
;; `update-game-state!` now refuses a diff it has no baseline for. The handler
;; around it did the success bookkeeping unconditionally: it retracted the
;; stale/lobby-gone verdicts, stamped :last-diff-time, bumped the wait cursor
;; and printed "✓ Diff applied successfully" — four claims about a state that
;; did not change. The cursor bump is the one with teeth: a parked `wait` wakes
;; for a no-op. (Guest review of the #142 fix; confirmed against source.)

(deftest test-ignored-diff-does-not-bookkeep-as-applied
  (testing "#142: a diff with no baseline changes nothing, and must claim nothing"
    (with-mock-state (assoc (mock-client-state :side "corp")
                            :game-state nil
                            :last-state nil
                            :diff-mismatch true
                            :lobby-gone? true)
      (let [gameid (:gameid @state/client-state)
            cursor-before (state/get-cursor)
            out (with-out-str
                  (ws/handle-message {:type :game/diff
                                      :data {:gameid gameid :diff [{:corp {:credit 6}} {}]}}))]
        (is (= cursor-before (state/get-cursor))
            "THE one with teeth: a parked `wait` must not wake for a diff that did nothing")
        (is (true? (:diff-mismatch @state/client-state))
            "an ignored diff is no evidence the staleness cleared")
        (is (true? (:lobby-gone? @state/client-state))
            "nor that the lobby came back (#93)")
        (is (nil? (:last-diff-time @state/client-state))
            "there was no last SUCCESSFUL diff")
        (is (not (str/includes? out "Diff applied successfully"))
            (str "the message contradicted the warning printed two lines above it. Got:\n" out))))))

(deftest test-applied-diff-still-does-all-the-bookkeeping
  (testing "#142: the live path is untouched — a false refusal here would freeze `wait`"
    ;; The complement, and the one that matters more: gating the bookkeeping on
    ;; a predicate that is wrong in the other direction stops the cursor for
    ;; every real diff, and every `wait` in the game hangs to timeout.
    (with-mock-state (assoc (mock-client-state :side "corp")
                            :diff-mismatch true
                            :lobby-gone? true)
      (let [gameid (:gameid @state/client-state)
            cursor-before (state/get-cursor)
            out (with-out-str
                  (ws/handle-message {:type :game/diff
                                      :data {:gameid gameid :diff [{:corp {:credit 6}} {}]}}))]
        (is (> (state/get-cursor) cursor-before) "a real diff still wakes `wait`")
        (is (nil? (:diff-mismatch @state/client-state)) "and still retracts staleness")
        (is (nil? (:lobby-gone? @state/client-state)))
        (is (some? (:last-diff-time @state/client-state)))
        (is (str/includes? out "Diff applied successfully"))
        (is (= 6 (get-in @state/client-state [:game-state :corp :credit]))
            "and actually applies")))))
