(ns game.ai-end-turn-gate-test
  "#152 deliverable 3 (policy #107): the ONE engine-side gate, for the end-turn
   class. board.cljs is the rules layer and the engine trusts the client, so
   every other enable condition is mirrored client-side. end-turn is the
   exception because a leak is both DERAILING and RACEABLE:
     - off-turn (#133 family): a seat that is not the active player sends
       end-turn → the opponent's turn ends under the sender's name; every
       log-derived consumer then disagrees with :end-turn and the match wedges.
     - duplicate: a second end-turn while the turn has ended (:end-turn) or is
       ending (post-discard window) corrupts the next turn cycle.
   process-actions/guarded-end-turn refuses both with a toast and changes
   nothing. These tests drive the engine directly (core/process-action), as the
   wire would."
  (:require [game.core :as core]
            [game.test-framework :refer :all]
            [clojure.test :refer :all]))

(defn- turn-end-log-count [state]
  (count (re-seq #"is ending their turn" (log-str state))))

(deftest off-turn-end-turn-is-refused
  (testing "the Runner sending end-turn during the Corp's turn changes nothing"
    (do-game
      (new-game {:corp {:hand ["Hedge Fund"]}})
      ;; Corp's turn 1 is live (new-game starts it): active player :corp
      (is (= :corp (:active-player @state)) "precondition: Corp's turn")
      (is (not (:end-turn @state)) "precondition: turn not ended")
      (let [before (turn-end-log-count state)]
        (core/process-action "end-turn" state :runner nil)
        (is (= :corp (:active-player @state)) "active player must not change")
        (is (not (:end-turn @state)) "the Corp's turn must not be ended by the Runner")
        (is (= before (turn-end-log-count state)) "no 'is ending their turn' line was minted")
        (is (= 3 (:click (get-corp))) "the Corp still holds its clicks")))))

(deftest duplicate-end-turn-after-the-turn-ended-is-refused
  (testing "a second end-turn while :end-turn is true is a no-op"
    (do-game
      (new-game {:corp {:hand ["Hedge Fund"]}})
      (core/process-action "end-turn" state :corp nil)
      (is (:end-turn @state) "precondition: the first end-turn ended the turn")
      (let [before (turn-end-log-count state)]
        (core/process-action "end-turn" state :corp nil)
        (is (:end-turn @state) "still ended")
        (is (= before (turn-end-log-count state)) "no second 'is ending their turn' line")))))

(deftest a-legitimate-end-turn-still-works
  (testing "control: the active player with the turn live ends it as before"
    (do-game
      (new-game {:corp {:hand ["Hedge Fund"]}})
      (is (not (:end-turn @state)))
      (core/process-action "end-turn" state :corp nil)
      (is (:end-turn @state) "the gate must not refuse the real thing"))))
