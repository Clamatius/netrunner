(ns ai-websocket-error-recovery-test
  "Regression tests for :game/error recovery in ai_websocket_client_v2.

   When the server catches an exception processing a command it rolls its
   state back and sends [:game/error] (web/game.clj). The client must not
   silently keep its now-divergent optimistic state — it must resync so the
   autonomous loop decides against authoritative ground truth."
  (:require [clojure.test :refer :all]
            [test-helpers :refer [mock-client-state with-mock-state mock-websocket-send!]]
            [ai-websocket-client-v2 :as ws]
            [ai-state :as state]))

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
  (testing "bare [:lobby/state] while seated marks the game gone and wakes waiters"
    (with-mock-state (mock-client-state :side "runner")
      (let [cursor-before (state/get-cursor)]
        (ws/handle-message {:type :lobby/state :data nil})
        (is (state/lobby-gone?) "lobby-gone flag must be set")
        (is (> (state/get-cursor) cursor-before)
            "cursor must bump so a blocked `wait` wakes and sees the verdict")))))

(deftest test-bare-lobby-state-without-gameid-is-noop
  (testing "bare [:lobby/state] before ever joining a game sets no flag"
    (with-mock-state (assoc (mock-client-state :side "corp") :gameid nil)
      (ws/handle-message {:type :lobby/state :data nil})
      (is (not (state/lobby-gone?))
          "a fresh client with no game has nothing to invalidate"))))

(deftest test-lobby-state-with-data-retracts-lobby-gone
  (testing "fresh lobby data (new/rejoined game) clears a stale lobby-gone verdict"
    (with-mock-state (assoc (mock-client-state :side "corp") :lobby-gone? true)
      (ws/handle-message {:type :lobby/state
                          :data {:gameid "00000000-0000-0000-0000-000000000002"}})
      (is (not (state/lobby-gone?))
          "being seated in a lobby again must retract the gone verdict"))))
