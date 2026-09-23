(ns game.ai-encounter-id-wire-test
  "Encounter identity on the wire (#197, #177, #163).

   Every per-encounter latch the AI seat keeps (\"I already passed here\", \"I
   already fired here\", the tank-signal retry) needs to know whether the
   encounter it sees now is the one it acted on. The wire only offered the ICE's
   :cid and :encounter-count, and neither is an identity: Sisyphus Protocol
   re-encounters the same card in one run (same cid), and two consecutive
   encounters are both at depth 1. Three client guards grew up approximating it.

   The engine already HAS one — `encounter-ice` pushes `{:eid eid :ice ice}` onto
   `:encounters`, and both callers mint a fresh eid (`make-phase-eid` for a run
   encounter, `make-eid` for a forced one). The serializer just dropped it.
   `encounters-summary` now publishes that eid's number as `:encounter-id`.

   Asserted against the real engine and serializer, not mocks: the engine
   comment on `encounter-ice` warns it 'deliberately leaves an open eid (the run
   eid)', which read as though every encounter in a run might share one. These
   tests are what says it does not."
  (:require [game.core :as core]
            [game.core.diffs :as diffs]
            [game.test-framework :refer :all]
            [clojure.test :refer :all]))

(defn- wire-encounter
  "The encounter summary a seat receives, from the real serializer."
  [state side]
  (get-in (diffs/public-states state)
          [(if (= side :corp) :corp-state :runner-state) :encounters]))

(defn- encounter-id [state]
  (:encounter-id (wire-encounter state :runner)))

(deftest encounter-id-is-published-to-both-seats
  (do-game
    (new-game {:corp {:hand ["Ice Wall"]}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (take-credits state :corp)
    (run-on state "HQ")
    (rez state :corp (get-ice state :hq 0))
    (run-continue state)
    (is (integer? (encounter-id state)) "a number, not the engine's eid map")
    (is (= (:encounter-id (wire-encounter state :runner))
           (:encounter-id (wire-encounter state :corp)))
        "both seats must key on the same value, or a cross-seat latch cannot agree")))

(deftest consecutive-encounters-get-distinct-ids
  (testing "two ICE in one run: both at depth 1, so :encounter-count cannot tell them apart"
    (do-game
      (new-game {:corp {:hand ["Ice Wall" "Enigma"]}})
      (play-from-hand state :corp "Ice Wall" "HQ")
      (play-from-hand state :corp "Enigma" "HQ")
      (take-credits state :corp)
      (rez state :corp (get-ice state :hq 0))
      (rez state :corp (get-ice state :hq 1))
      (run-on state "HQ")
      (run-continue state)
      (let [outer (wire-encounter state :runner)]
        (is (= "Enigma" (get-in outer [:ice :title])))
        (run-continue-until state :encounter-ice)
        (let [inner (wire-encounter state :runner)]
          (is (= "Ice Wall" (get-in inner [:ice :title])))
          (is (= 1 (:encounter-count outer) (:encounter-count inner)) "premise: depth does not distinguish")
          (is (integer? (:encounter-id inner)))
          (is (not= (:encounter-id outer) (:encounter-id inner))))))))

(deftest sisyphus-re-encounter-of-the-same-card-gets-a-new-id
  (testing "#163: same physical ICE, same cid, second encounter in one run"
    (do-game
      (new-game {:corp {:hand ["Sisyphus Protocol" "Whitespace"]}})
      (play-and-score state "Sisyphus Protocol")
      (play-from-hand state :corp "Whitespace" "HQ")
      (take-credits state :corp)
      (run-on state "HQ")
      (rez state :corp (get-ice state :hq 0))
      (run-continue state)
      (let [first-enc (wire-encounter state :runner)]
        (run-continue state)
        (click-prompt state :corp "Pay 1 [Credit]")
        (let [second-enc (wire-encounter state :runner)]
          (is (some? second-enc) "premise: the runner is encountering Whitespace again")
          (is (= (get-in first-enc [:ice :cid]) (get-in second-enc [:ice :cid])) "premise: cid repeats")
          (is (integer? (:encounter-id second-enc)))
          (is (not= (:encounter-id first-enc) (:encounter-id second-enc))))))))

(deftest nested-encounter-restores-the-outer-id-on-return
  (testing "Konjin forces an Ice Wall encounter inside its own; back at Konjin, the id must be Konjin's again"
    (do-game
      (new-game {:corp {:hand ["Ice Wall" "Konjin"]}})
      (play-from-hand state :corp "Ice Wall" "HQ")
      (play-from-hand state :corp "Konjin" "R&D")
      (take-credits state :corp)
      (rez state :corp (get-ice state :rd 0))
      (rez state :corp (get-ice state :hq 0))
      (run-on state :rd)
      (run-continue state)
      (let [konjin-id (encounter-id state)]
        (click-prompt state :corp "0 [Credits]")
        (click-prompt state :runner "1 [Credits]")
        (is (= konjin-id (encounter-id state)) "stable across the psi game inside one encounter")
        (click-card state :corp (get-ice state :hq 0))
        (let [nested (wire-encounter state :runner)]
          (is (= "Ice Wall" (get-in nested [:ice :title])))
          (is (= 2 (:encounter-count nested)) "premise: nested")
          (is (integer? (:encounter-id nested)))
          (is (not= konjin-id (:encounter-id nested))))
        (run-continue state :encounter-ice)
        (is (= "Konjin" (get-in (wire-encounter state :runner) [:ice :title])) "premise: back at Konjin")
        (is (= konjin-id (encounter-id state))
            "the outer encounter is the SAME encounter resumed, not a new one")))))
