(ns ai-display-test
  "Regression tests for ai_display.

   Locks the machine-readable game-over-status output contract that tooling
   (dev/self-play-batch.sh) depends on. The harness previously screen-scraped
   the human status banner and broke when the banner format changed; this
   contract must stay stable."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [test-helpers :refer [mock-client-state with-mock-state]]
            [ai-display :as display]))

(deftest test-game-over-status-decided
  (testing "decided game prints GAME-OVER with lowercased winner and turn"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 18
                                   :winner :corp :reason "Agenda"
                                   :end-time "2026-01-01T00:00:00Z"
                                   :corp {:click 0} :runner {:click 0}})
      (is (= "GAME-OVER winner=corp turn=18"
             (str/trim (with-out-str (display/game-over-status))))))))

(deftest test-game-over-status-tie
  (testing "tie prints winner=tie"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 12
                                   :reason "Both decked"
                                   :end-time "2026-01-01T00:00:00Z"
                                   :corp {:click 0} :runner {:click 0}})
      (is (= "GAME-OVER winner=tie turn=12"
             (str/trim (with-out-str (display/game-over-status))))))))

(deftest test-game-over-status-in-progress
  (testing "running game prints IN-PROGRESS with turn, whose-turn, active clicks"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "runner" :turn 7
                                   :corp {:click 0} :runner {:click 3}})
      (is (= "IN-PROGRESS turn=7 whose-turn=runner clicks=3"
             (str/trim (with-out-str (display/game-over-status))))))))

(deftest test-game-over-status-awaiting-start
  ;; At a clean turn boundary (a player has ended their turn, or both sides are
  ;; at 0 clicks) the active-player wire field still names the player who just
  ;; finished. Reporting `IN-PROGRESS whose-turn=corp clicks=0` there is
  ;; indistinguishable from a corp stall, so the umpire's stall detector can
  ;; false-positive while a slow opponent is thinking about its turn start.
  ;; A distinct AWAITING-START token names who acts next so tooling can apply a
  ;; patient boundary budget instead of the tight mid-turn stall threshold.
  (testing "corp ended turn -> AWAITING-START next-player=runner"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 5
                                   :end-turn true
                                   :corp {:click 0} :runner {:click 0}})
      (is (= "AWAITING-START turn=5 next-player=runner"
             (str/trim (with-out-str (display/game-over-status)))))))

  (testing "both sides at 0 clicks (no end-turn flag yet) -> AWAITING-START"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "runner" :turn 6
                                   :corp {:click 0} :runner {:click 0}})
      (is (= "AWAITING-START turn=6 next-player=corp"
             (str/trim (with-out-str (display/game-over-status))))))))

(deftest test-game-over-status-no-game
  (testing "no game-state prints NO-GAME"
    (with-mock-state {:side "corp" :game-state nil}
      (is (= "NO-GAME"
             (str/trim (with-out-str (display/game-over-status))))))))
