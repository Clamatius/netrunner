(ns ai-phase-window-test
  "The seat's two phase-window commands.

   Which wire command closes a window is not a free choice — it mirrors the
   reference client's button exactly (`board.cljs/basic-actions`):

     active player, no consent needed -> \"end-phase-12\" / \"end-post-discard\"
     consent needed (either seat)     -> the \"…-pass-priority\" variant

   Getting this wrong is silent: the engine accepts an unknown command by doing
   nothing (`process-action` returns nil for a miss), so a seat would sit in a
   window it believed it had closed. Hence assertions on the exact command sent.

   The engine side — that these windows never close on their own — is pinned in
   game.ai-phase-windows-test."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-state :as state]
            [ai-basic-actions :as basic]
            [ai-websocket-client-v2 :as ws]))

(defn- state-with
  "Client state for `side` with the given phase-window keys set on game-state."
  [side window-keys]
  {:connected true
   :side side
   :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
   :game-state (merge {:corp {} :runner {}} window-keys)})

(defn- capture
  "Run f with the wire stubbed; return the sent command name (nil if nothing sent)."
  [client-state f]
  (let [sent (atom nil)
        original @state/client-state]
    (try
      (reset! state/client-state client-state)
      (with-redefs [ws/send-message! (fn [_ data] (reset! sent (:command data)) true)]
        (let [result (f)]
          {:command @sent :result result}))
      (finally (reset! state/client-state original)))))

(deftest end-phase-12-mirrors-the-button
  (testing "active player, window is theirs alone -> end-phase-12"
    (let [{:keys [command result]}
          (capture (state-with "corp" {:corp-phase-12 {:active true}})
                   basic/end-phase-12!)]
      (is (= "end-phase-12" command))
      ;; nothing cleared the window in the stub, so the seat is told so honestly
      (is (= :error (:status result)))
      (is (= :window-still-open (:reason result)))))

  (testing "active player, consent required -> pass-priority, not the plain end"
    (let [{:keys [command]}
          (capture (state-with "corp" {:corp-phase-12 {:active true :requires-consent true}})
                   basic/end-phase-12!)]
      (is (= "phase-12-pass-priority" command)
          "the plain end-phase-12 would be ignored by the engine here")))

  (testing "opponent's window, consent required -> we can and must pass too"
    (let [{:keys [command]}
          (capture (state-with "runner" {:corp-phase-12 {:active true :requires-consent true}})
                   basic/end-phase-12!)]
      (is (= "phase-12-pass-priority" command))))

  (testing "opponent's window, no consent required -> not ours to close"
    (let [{:keys [command result]}
          (capture (state-with "runner" {:corp-phase-12 {:active true}})
                   basic/end-phase-12!)]
      (is (nil? command) "nothing is sent")
      (is (= :not-my-window (:reason result)))))

  (testing "already passed -> waiting on the opponent, not an error and not a resend"
    (let [{:keys [command result]}
          (capture (state-with "corp" {:corp-phase-12 {:active true :requires-consent true :corp true}})
                   basic/end-phase-12!)]
      (is (nil? command))
      (is (= :waiting-input (:status result)))
      (is (= :already-passed (:reason result)))))

  (testing "no window open -> says so instead of sending a command into the void"
    (let [{:keys [command result]}
          (capture (state-with "corp" {}) basic/end-phase-12!)]
      (is (nil? command))
      (is (= :no-window-open (:reason result)))))

  (testing "an inactive window is not an open one"
    (let [{:keys [command result]}
          (capture (state-with "corp" {:corp-phase-12 {:active false}}) basic/end-phase-12!)]
      (is (nil? command))
      (is (= :no-window-open (:reason result))))))

(deftest end-post-discard-mirrors-the-button
  (testing "active player, no consent -> end-post-discard"
    (let [{:keys [command]}
          (capture (state-with "corp" {:corp-post-discard {:active true}})
                   basic/end-post-discard!)]
      (is (= "end-post-discard" command))))

  (testing "consent required -> post-discard-pass-priority, from either seat"
    (is (= "post-discard-pass-priority"
           (:command (capture (state-with "corp" {:corp-post-discard {:active true :requires-consent true}})
                              basic/end-post-discard!))))
    (is (= "post-discard-pass-priority"
           (:command (capture (state-with "runner" {:corp-post-discard {:active true :requires-consent true}})
                              basic/end-post-discard!)))))

  (testing "the two windows are not confused for each other"
    (is (nil? (:command (capture (state-with "corp" {:corp-phase-12 {:active true}})
                                 basic/end-post-discard!)))
        "end-post-discard does not close a phase-1.2 window")
    (is (nil? (:command (capture (state-with "corp" {:corp-post-discard {:active true}})
                                 basic/end-phase-12!)))
        "and vice versa")))

(deftest open-phase-window-reports-what-the-seat-needs
  (testing "owner, consent and our own pass state are all reported"
    (let [original @state/client-state]
      (try
        (reset! state/client-state
                (state-with "runner" {:corp-phase-12 {:active true :requires-consent true :runner true}}))
        (let [w (basic/open-phase-window :phase-12)]
          (is (= :corp (:owner w)))
          (is (:requires-consent? w))
          (is (:i-passed? w) "our own pass is read from our own side key"))
        (finally (reset! state/client-state original))))))

;; ============================================================================
;; A sideless client must not be able to pass a consent window (#127 house rule)
;; ============================================================================
;;
;; Found by the off-vendor panel on the #111/#113 merge resolution. Pre-existing
;; in the wire-diff branch rather than introduced by the merge: the derivation
;; was `(keyword (:side @state/client-state))`, and `(keyword nil)` is nil too.
;;
;; The cond in close-phase-window! only refuses a foreign window when it does
;; NOT require consent:
;;
;;   (and (not= owner my-side) (not requires-consent?)) -> :not-my-window
;;
;; so with my-side nil and consent required, both guards fall through: i-passed?
;; is (get w nil) => false, and the refusal arm is disabled by the consent flag.
;; The seat then SENDS a pass-priority the server discards for a spectator, and
;; reports "✅ Passed …" — a confident false claim, the #125 mistake.
;;
;; :side is nil for a spectator on a live game, for a resync that landed a board
;; before matching our uid, and for a REPL that never joined — so this is
;; reachable without anything being broken.

(deftest sideless-seat-cannot-pass-a-consent-window
  (testing "#127: no side means no turn of ours to act on — refuse, do not send"
    (let [{:keys [command result]}
          (capture (state-with nil {:corp-phase-12 {:active true :requires-consent true}})
                   basic/end-phase-12!)]
      (is (nil? command)
          "a client with no seat must not send a pass-priority the server will discard")
      (is (= :error (:status result)))
      (is (= :no-side (:reason result))
          "and must refuse through the same authority every other no-seat path uses")))

  (testing "the post-discard window has the identical hole"
    (let [{:keys [command result]}
          (capture (state-with nil {:corp-post-discard {:active true :requires-consent true}})
                   basic/end-post-discard!)]
      (is (nil? command))
      (is (= :no-side (:reason result)))))

  (testing "and the seat is never told it passed, nor that 'null' owes a pass"
    (let [out (with-out-str
                (capture (state-with nil {:corp-phase-12 {:active true :requires-consent true}})
                         basic/end-phase-12!))]
      (is (not (clojure.string/includes? out "Passed"))
          (str "reported a pass that never happened, got:\n" out))
      (is (not (clojure.string/includes? out "null"))
          (str "formatted a nil opponent into seat-facing text, got:\n" out)))))

;; ============================================================================
;; start-turn! must announce a held phase-1.2 window at BOTH of its send sites
;; ============================================================================
;;
;; Second finding from the same panel. start-turn! sends from two cond arms —
;; the turn-0 arm and the validated later-turn `:else` arm — because the
;; mulligan fix (#131/#135) added the second. The wire-diff branch patched both,
;; so the merge auto-applied one hunk and CONFLICTED on the other; resolving that
;; conflict wrongly (or dropping it) would have left the suite green, since
;; nothing pinned this output.
;;
;; It matters because the notice is the only thing telling a seat that its
;; mandatory draw and every start-of-turn trigger have NOT happened yet: the
;; engine opens 1.2 and closes it again in the same breath unless a card holds
;; it, and 105 card defs carry the flag.

(defn- start-turn-out
  "start-turn! with the wire stubbed; returns printed output."
  [client-state]
  (let [original @state/client-state]
    (try
      (reset! state/client-state client-state)
      (with-out-str
        (with-redefs [ws/send-message! (fn [_ _] true)
                      basic/get-my-username (constantly "me")]
          (basic/start-turn!)))
      (finally (reset! state/client-state original)))))

(deftest start-turn-announces-a-held-phase-12-window-at-both-send-sites
  (testing "turn 0 (Corp's opening turn) — the first send site"
    (let [out (start-turn-out
               {:connected true :side "corp"
                :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
                :game-state {:turn 0
                             :corp {:click 0 :hand []} :runner {:click 0}
                             :log []
                             :corp-phase-12 {:active true}}})]
      (is (clojure.string/includes? out "phase 1.2")
          (str "turn-0 seat was not told a card is holding its 1.2 window, got:\n" out))
      (is (clojure.string/includes? out "end-phase-12")
          (str "and was not given the command that closes it, got:\n" out))))

  (testing "a later turn, after the opponent ended — the second send site"
    (let [out (start-turn-out
               {:connected true :side "corp"
                :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
                :game-state {:turn 4
                             :corp {:click 0 :hand []} :runner {:click 0}
                             :log [{:text "ai-runner is ending their turn 4"}]
                             :corp-phase-12 {:active true}}})]
      (is (clojure.string/includes? out "phase 1.2")
          (str "later-turn seat was not told — this is the arm the merge conflicted on, got:\n" out))
      (is (clojure.string/includes? out "end-phase-12")
          (str "and was not given the command that closes it, got:\n" out))))

  (testing "and stays quiet when no card is holding the window"
    (let [out (start-turn-out
               {:connected true :side "corp"
                :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
                :game-state {:turn 4
                             :corp {:click 0 :hand []} :runner {:click 0}
                             :log [{:text "ai-runner is ending their turn 4"}]}})]
      (is (not (clojure.string/includes? out "phase 1.2"))
          (str "announced a window that is not open — the engine closes an unheld one, got:\n" out)))))
