(ns game.ai-pay-all-test
  "Issue #110 §2 (and the open #104 Overclock item): the ENGINE-level contract
   behind `choose-card <N> --all`.

   The per-credit payment prompt (\"Choose a credit providing card (0 of 2
   [Credits])\") re-asks once per credit, so a seat paying 2 from one source
   makes two calls and a seat paying 5 makes five. Two different models reported
   this independently — Fable on Overclock, Luna on Unity — as the tool making
   them spend their turn on bookkeeping.

   Humans never see it. game.core.pick-counters/pick-credit-providing-cards
   defines

     should-auto-repeat? (fn [state side] (get-in @state [side :shift-key-select]))

   with the comment \"this allows holding the shift key while clicking a card to
   keep picking that card while possible ie: taking 5cr from miss bones with one
   click, instead of waiting for 5 server round-trips\". board.cljs sends
   :shift-key-held on shift-click; game.core.actions/select stores it. Our
   client hardcoded :shift-key-held false, so the seat had no shift key.

   These tests pin what the seat's --all now depends on. The wire-level tests in
   dev/test/ai_prompts_test.clj only prove we SEND the flag; nothing there would
   notice if the engine stopped honouring it. This is the other half.

   Note the sibling test `ghost-runner-can-be-used-in-psi-games-issue-1149` in
   resources_test.clj, which pays 2 credits with two consecutive click-cards —
   the friction, already sitting in the engine's own suite."
  (:require [game.core :as core]
            [game.core.card :refer :all]
            [game.test-framework :refer :all]
            [clojure.test :refer :all]))

(defn- select-card-shift!
  "Select `card` on the current select prompt with the shift key HELD — exactly
   the wire payload dev/src/clj/ai_websocket_client_v2.clj select-card! sends for
   `choose-card <N> --all`. The framework's click-card always sends a plain
   click, so it cannot exercise this path."
  [state side card]
  (core/process-action "select" state side
                       {:card card
                        :eid (:eid (get-prompt state side))
                        :shift-key-held true}))

(deftest shift-held-select-pays-the-whole-cost-from-one-source
  ;; A1: the load-bearing assumption behind --all.
  (testing "one shift-held selection settles a 2-credit cost that otherwise
            needs two selections"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)]
                        :hand ["Snowflake"]}
                 :runner {:hand ["Ghost Runner"]
                          :credits 1}})
      (play-from-hand state :corp "Snowflake" "HQ")
      (take-credits state :corp)
      (play-from-hand state :runner "Ghost Runner")
      (run-on state :hq)
      (rez state :corp (get-ice state :hq 0))
      (run-continue state)
      (let [sf (get-ice state :hq 0)
            gr (get-resource state 0)]
        (card-subroutine state :corp sf 0)
        (is (zero? (:credit (get-runner))) "Runner has no pool credits to fall back on")
        (click-prompt state :corp "0 [Credits]")
        (click-prompt state :runner "2 [Credits]")
        (is (= 3 (get-counters (refresh gr) :credit)) "Ghost Runner starts at 3")
        ;; ONE call, where the plain-click test needs two.
        (select-card-shift! state :runner (refresh gr))
        (is (= 1 (get-counters (refresh gr) :credit))
            "both credits came off Ghost Runner in a single selection")
        (is (zero? (:credit (get-runner))) "and none came out of the credit pool")
        (is (not (prompt-is-type? state :runner :select))
            "the payment prompt is resolved — no second call is owed")))))

(deftest plain-select-still-pays-exactly-one-credit
  ;; The control. If this ever passes for 2 credits, the flag stopped being the
  ;; thing that distinguishes the two paths and the test above proves nothing.
  (testing "without the shift flag the prompt still re-asks per credit"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)]
                        :hand ["Snowflake"]}
                 :runner {:hand ["Ghost Runner"]
                          :credits 1}})
      (play-from-hand state :corp "Snowflake" "HQ")
      (take-credits state :corp)
      (play-from-hand state :runner "Ghost Runner")
      (run-on state :hq)
      (rez state :corp (get-ice state :hq 0))
      (run-continue state)
      (let [sf (get-ice state :hq 0)
            gr (get-resource state 0)]
        (card-subroutine state :corp sf 0)
        (click-prompt state :corp "0 [Credits]")
        (click-prompt state :runner "2 [Credits]")
        (click-card state :runner (refresh gr))
        (is (= 2 (get-counters (refresh gr) :credit))
            "a plain click pays ONE credit")
        (is (prompt-is-type? state :runner :select)
            "and the prompt is still open, asking for the second")))))

(deftest shift-held-select-stops-at-the-cost-not-at-the-source
  ;; A4 / the guest reviewer's MINOR: --all does NOT drain the card. The engine
  ;; exits at (<= target-count counter-count), so a source richer than the cost
  ;; keeps its remainder. Our help text must not promise "the full amount".
  (testing "a source with more credits than the cost keeps the difference"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)]
                        :hand ["Snowflake"]}
                 :runner {:hand ["Ghost Runner"]
                          :credits 1}})
      (play-from-hand state :corp "Snowflake" "HQ")
      (take-credits state :corp)
      (play-from-hand state :runner "Ghost Runner")
      (run-on state :hq)
      (rez state :corp (get-ice state :hq 0))
      (run-continue state)
      (let [sf (get-ice state :hq 0)
            gr (get-resource state 0)]
        (card-subroutine state :corp sf 0)
        (click-prompt state :corp "0 [Credits]")
        ;; Cost 1, source holds 3.
        (click-prompt state :runner "1 [Credits]")
        (select-card-shift! state :runner (refresh gr))
        (is (= 2 (get-counters (refresh gr) :credit))
            "paid only what the cost needed; 2 credits stay on the card")))))

(deftest shift-state-does-not-persist-into-the-next-payment
  ;; A3: the assumption I was least sure of. game.core.actions/select re-stamps
  ;; [side :shift-key-select] on EVERY selection, so a later plain choose-card
  ;; must clear it. If it latched, a seat that used --all once would silently
  ;; auto-drain sources for the rest of the game.
  ;; Two SEPARATE runs: Snowflake ends the run whenever the psi bids differ, so
  ;; both payments cannot happen inside one run.
  (testing "a plain selection on a later run pays one credit again"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)]
                        :hand ["Snowflake"]}
                 :runner {:hand ["Ghost Runner"]
                          :credits 1}})
      (play-from-hand state :corp "Snowflake" "HQ")
      (take-credits state :corp)
      (play-from-hand state :runner "Ghost Runner")
      (let [gr (fn [] (refresh (get-resource state 0)))
            psi-game (fn [runner-bid]
                       (run-on state :hq)
                       ;; Rez only on the first pass — it stays rezzed for run 2.
                       (when-not (rezzed? (get-ice state :hq 0))
                         (rez state :corp (get-ice state :hq 0)))
                       (run-continue state)
                       (card-subroutine state :corp (get-ice state :hq 0) 0)
                       (click-prompt state :corp "0 [Credits]")
                       (click-prompt state :runner runner-bid))]
        ;; Run 1: pay 1 with the shift HELD.
        (psi-game "1 [Credits]")
        (select-card-shift! state :runner (gr))
        (is (= 2 (get-counters (gr) :credit)) "first payment took 1")
        (is (true? (get-in @state [:runner :shift-key-select]))
            "sanity: the engine did record the held shift")

        ;; Run 2: a PLAIN click must be back to one-credit-per-click. If the
        ;; stale true latched, this would drain both remaining credits.
        (psi-game "2 [Credits]")
        (click-card state :runner (gr))
        (is (= 1 (get-counters (gr) :credit))
            "the stale shift state did NOT drain the source")
        (is (prompt-is-type? state :runner :select)
            "prompt still open — the plain click paid exactly one")))))
