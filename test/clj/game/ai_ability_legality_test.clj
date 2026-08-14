(ns game.ai-ability-legality-test
  "Issue #116: `use-ability` at the approach window reported
   `Ability not confirmed in game log (timeout)` for what is a rules/timing
   condition. That sentence describes a HARNESS fault and invites a retry;
   retrying an illegal ability is the duplicate-send pattern that mints phantom
   prompts (#75/#77).

   Salvaged from the superseded PR #122, whose client design (refuse on a missing
   `:playable`) main deliberately did NOT adopt: the flag is a snapshot and can be
   stale-false, and mis-refusing costs a seat a break it was entitled to. Main's
   fix instead REFUSES only on the engine invariant — `break-sub`'s `:break-req`
   requires `(peek (:encounters @state))`, so a break outside an encounter could
   never have worked — and merely DIAGNOSES from `:playable`, still sending.

   The assertions below are pure engine facts and outlive that design choice.
   Both halves of main's fix reason from them, and nothing else pins them at the
   engine level, so this is where they stop drifting away from the wire:

   1. The per-ability `:playable` flag really is absent at `approach-ice` for a
      break ability and present at `encounter-ice`.
   2. It is PER-ABILITY, not per-phase: the same breaker's pump ability keeps
      `:playable` at approach. A blanket 'nothing works before the encounter'
      gate would wrongly block it.
   3. The synthesised `:auto-pump-and-break` ability does not exist in the list
      at all until the encounter — so its index is out of range at approach,
      which is a different refusal than 'present but unplayable'."
  (:require [game.core :as core]
            [game.core.diffs :as diffs]
            [game.test-framework :refer :all]
            [cheshire.core :as json]
            [clojure.test :refer :all]))

(defn- breaker-abilities
  "Corroder's abilities as the seat receives them: privatized, then round-tripped
   through the transport's JSON encoding. public-states alone stops short of the
   boundary that matters (the #114 lesson — a keyword server-side can arrive as a
   string), and `:playable` is exactly the kind of flag a summary change could
   silently drop."
  [state]
  (->> (get-in (diffs/public-states state) [:runner-state :runner :rig :program])
       (filter #(= "Corroder" (:title %)))
       first
       :abilities
       (#(json/parse-string (json/generate-string %) true))))

(defn- by-label [abilities re]
  (first (filter #(re-find re (str (:label %))) abilities)))

(deftest breaker-ability-playability-is-per-ability-and-per-phase
  (do-game
    (new-game {:corp {:hand ["Ice Wall"]}
               :runner {:hand ["Corroder"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (take-credits state :corp)
    (play-from-hand state :runner "Corroder")
    (run-on state "HQ")
    (rez state :corp (get-ice state :hq 0))

    (testing "#116 at approach-ice: the break ability is NOT playable"
      (is (= :approach-ice (:phase (:run @state)))
          "precondition: we are at the approach window, not encountering")
      (is (empty? (:encounters @state))
          "precondition: no encounter has begun")
      (let [abs (breaker-abilities state)
            break-ab (by-label abs #"(?i)break.*subroutine")]
        (is (some? break-ab) "the printed break ability is still listed")
        (is (not (:playable break-ab))
            (str "the engine already says no here — the fact the client reports "
                 "instead of blaming a log timeout; got: " (pr-str break-ab)))))

    (testing "#116: the SAME breaker's pump ability IS playable at approach"
      ;; The gate must be per-ability. A phase-level 'nothing works at approach'
      ;; rule would block a legal action.
      (let [pump-ab (by-label (breaker-abilities state) #"(?i)strength")]
        (is (some? pump-ab) "the pump ability is listed")
        (is (true? (:playable pump-ab))
            (str "pumping outside an encounter is legal; got: " (pr-str pump-ab)))))

    (testing "#116: the dynamic full-break ability does not exist yet at approach"
      ;; So a seat reaching for it by index gets an out-of-range refusal, not an
      ;; 'unplayable' one — the client distinguishes the two.
      (is (nil? (by-label (breaker-abilities state) #"(?i)fully break"))
          "the engine synthesises it only during an encounter"))

    (run-continue state)

    (testing "#116 at encounter-ice: the break ability becomes playable"
      (is (= :encounter-ice (:phase (:run @state))))
      (let [abs (breaker-abilities state)]
        (is (true? (:playable (by-label abs #"(?i)break.*subroutine")))
            "the recovery the client now names ('continue', then retry) works")
        (is (some? (by-label abs #"(?i)fully break"))
            "and the dynamic full-break ability now exists")))))
