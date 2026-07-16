(ns game.ai-upgrade-rez-timing-test
  "Engine-timing premise behind the AI Corp seat's run-window upgrade decision
   (issue #67). These are engine-level (do-game) tests, not client tests: they
   pin the rule invariant that the AI's `ai-run-corp-decisions/current-checkpoint`
   relies on when it decides WHEN to surface an unrezzed-upgrade rez window.

   Invariant: an APPROACH-triggered upgrade (Manegarm Skunkworks:
   \"whenever the Runner approaches this server\") only fires if it is rezzed at or
   before the movement/position-0 window (the Runner approaching the server).
   By the \"success\" phase the engine has already resolved :approach-server, so a
   rez there is a dead no-op. The AI must therefore surface the rez decision at
   movement/pos-0 and NOT at success."
  (:require [game.core :as core]
            [game.test-framework :refer :all]
            [clojure.test :refer :all]))

(deftest manegarm-rez-at-movement-pos0-fires
  (testing "rez Manegarm at movement/position-0 (approaching server) => ability fires"
    (do-game
      (new-game {:corp {:hand ["Manegarm Skunkworks" "Ice Wall"]}})
      (play-from-hand state :corp "Manegarm Skunkworks" "New remote")
      (play-from-hand state :corp "Ice Wall" "Server 1")
      (take-credits state :corp)
      (let [mane (get-content state :remote1 0)]
        (run-on state "Server 1")
        (run-continue-until state :movement)            ; past the ICE, at the server
        (is (= :movement (:phase (:run @state))))
        (is (zero? (:position (:run @state))))
        (rez state :corp mane)                          ; rez BEFORE approach-server resolves
        (run-continue state)
        (is (= "Choose one" (:msg (first (get-in @state [:runner :prompt]))))
            "Manegarm's approach ability fired (Runner is taxed)")))))

(deftest manegarm-rez-at-success-is-too-late
  (testing "rez Manegarm at success (after approach-server) => ability does NOT fire"
    (do-game
      (new-game {:corp {:hand ["Manegarm Skunkworks" "Ice Wall"]}})
      (play-from-hand state :corp "Manegarm Skunkworks" "New remote")
      (play-from-hand state :corp "Ice Wall" "Server 1")
      (take-credits state :corp)
      (let [mane (get-content state :remote1 0)]
        (run-on state "Server 1")
        (run-continue-until state :success)             ; approach-server already resolved
        (is (= :success (:phase (:run @state))))
        (rez state :corp mane)
        (is (empty? (filter #(= "Choose one" (:msg %))
                            (get-in @state [:runner :prompt])))
            "No approach tax at success — the rez was too late to fire the ability")))))
