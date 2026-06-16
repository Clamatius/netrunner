(ns game.core.turns-test
  (:require
   [clojure.test :refer :all]
   [game.test-framework :refer :all]))

(deftest wedge-stale-turn-started-cleared-on-boundary
  (testing "A stale incoming-player :turn-started flag does not wedge the turn boundary"
    (do-game
      (new-game {:corp {:hand ["Hedge Fund"]}})
      ;; Corp turn 1: spend clicks, end -> Runner turn 1 starts.
      (take-credits state :corp)
      (is (= :runner (:active-player @state)) "Runner's turn is active")
      ;; Simulate the wedge: Corp's :turn-started is stuck true (as if Corp's own prior
      ;; end-turn was rolled back on a resync and never re-cleared the flag).
      (swap! state assoc-in [:corp :turn-started] true)
      ;; Runner ends its turn; end-turn-continue sets :end-turn true at the boundary,
      ;; then take-credits issues Corp's start-turn.
      (take-credits state :runner)
      ;; Without the defensive fix, Corp's stale flag makes start-turn a no-op and the
      ;; game freezes with active-player stuck on :runner.
      (is (= :corp (:active-player @state)) "Corp's turn actually started")
      (is (true? (:turn-started (:corp @state))) "Corp :turn-started set by its real start")
      (is (pos? (get-in @state [:corp :click])) "Corp has clicks to spend"))))
