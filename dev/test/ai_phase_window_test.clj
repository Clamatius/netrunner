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
