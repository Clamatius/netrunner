(ns game.ai-forced-encounter-wire-test
  "Engine premise behind the AI seat's forced-encounter handling (#160).

   The client's run automation used to gate every encounter handler on
   `(= run-phase \"encounter-ice\")`. A FORCED encounter — an on-access Archangel,
   a redirect — is a live encounter that the phase string does not name, so at
   that window `tank` sent nothing, the Corp's auto-fire saw no decision, and the
   two seats waited on each other.

   The client fix keys those gates on `ai-core/at-encounter?` instead, mirroring
   the engine's own `continue` dispatch:

       (if (get-current-encounter state) :encounter-ice (:phase (:run @state)))

   Everything the client-side unit tests mock is asserted HERE against the real
   engine and the real serializer, because this project has been burned by a
   suite that stayed green through two fixes while every mock omitted the field
   the bug lived in. If the engine ever stops producing this shape, this file
   goes red and the mocks in ai-forced-encounter-test are known to be fiction.

   The one thing these tests deliberately do NOT pin is the keyword→string
   conversion of `:phase`: `run-summary` leaves it a keyword and the JSON/transit
   hop stringifies it. Every client comparison assumes the string, so the tests
   below `name` it explicitly rather than pretending the client sees a keyword."
  (:require [game.core :as core]
            [game.core.diffs :as diffs]
            [game.core.ice :refer [active-ice?]]
            [game.test-framework :refer :all]
            [ai-core :as ai-core]
            [clojure.test :refer :all]))

(defn- runner-wire-state
  "The client-state map the Runner seat holds, built from the real serializer."
  [state]
  {:side "runner"
   :game-state (:runner-state (diffs/public-states state))})

(defn- corp-wire-state
  [state]
  {:side "corp"
   :game-state (:corp-state (diffs/public-states state))})

(defn- wire-phase
  "The run phase as the client sees it: a string, not the engine's keyword."
  [wire]
  (some-> (get-in wire [:game-state :run :phase]) name))

(defn- force-archangel-encounter!
  "Run HQ, access Archangel, and pay to force the encounter. Leaves the game at
   the forced encounter with one unbroken subroutine."
  [state]
  (take-credits state :corp)
  (run-empty-server state "HQ")
  (click-prompt state :corp "Yes"))

(deftest forced-encounter-is-live-while-the-phase-says-otherwise
  (testing "the premise the whole #160 fix rests on"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Archangel"]}
                 :runner {:hand ["Bank Job"]}})
      (force-archangel-encounter! state)
      (is (= "Archangel" (:title (core/get-current-ice state)))
          "sanity: the engine really is mid-encounter")
      (let [wire (runner-wire-state state)
            phase (wire-phase wire)]
        (is (not= "encounter-ice" phase)
            (str "the phase must NOT name the encounter — that is the bug. got: " phase))
        (is (some? (get-in wire [:game-state :encounters :ice]))
            "but the encounter IS on the wire")
        (is (true? (ai-core/live-encounter? wire)))
        (is (true? (ai-core/at-encounter? wire phase))
            (str "the client gate must fire at phase " phase))))))

(deftest the-encounter-summary-carries-what-the-handlers-read
  (testing "cid, rezzed and subroutines — the fields every handler resolves on"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Archangel"]}
                 :runner {:hand ["Bank Job"]}})
      (force-archangel-encounter! state)
      (let [wire (runner-wire-state state)
            ice (ai-core/encountered-ice wire)]
        (is (= "Archangel" (:title ice)))
        (is (some? (:cid ice)) "encounter-key resolves on :cid")
        (is (seq (:subroutines ice))
            "the unbroken-sub filter reads :subroutines off this map")
        ;; The engine has NOT stamped :broken/:fired on these subs, and
        ;; select-non-nil-keys drops what is absent — so the client's unbroken
        ;; filter must treat ABSENT as unbroken. (not (:broken %)) does; a
        ;; (false? (:broken %)) would not, and the hand-written mocks that said
        ;; :broken false would never have caught the difference.
        (is (not-any? #(contains? % :broken) (:subroutines ice))
            "absent, not false — the filter has to be absence-tolerant")
        (is (= 1 (count (filter #(and (not (:broken %)) (not (:fired %)))
                                (:subroutines ice))))
            "and it counts this sub as pending, which is the whole point")
        (is (= (:cid ice) (ai-core/encounter-key wire)))))))

(deftest the-forced-encounter-ice-is-never-rezzed
  (testing "the guard that nearly made the whole #160 fix a no-op"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Archangel"]}
                 :runner {:hand ["Bank Job"]}})
      (force-archangel-encounter! state)
      (let [wire (runner-wire-state state)
            ice (ai-core/encountered-ice wire)]
        (is (nil? (:rezzed ice))
            "an on-access Archangel is encountered out of HQ — it is never rezzed")
        (is (= [:hand] (:zone ice))
            "and it is not installed anywhere either")
        ;; The engine still treats it as active, because active-ice? reads
        ;; \"installed and rezzed OR is the current encounter\" (game.core.ice).
        (is (true? (active-ice? state (core/get-current-ice state)))
            "engine: this ICE is active")
        (is (true? (ai-core/encounter-ice-active? wire ice))
            "client: and our mirror of that rule agrees")))))

(deftest position-does-not-point-at-a-forced-encounter
  (testing "why encountered-ice exists: the position-derived ICE is not this card"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Archangel"]}
                 :runner {:hand ["Bank Job"]}})
      (force-archangel-encounter! state)
      (let [wire (runner-wire-state state)]
        ;; Archangel is accessed FROM HQ — it is not installed anywhere, so no
        ;; :position can reach it. current-run-ice returns nil here; keying the
        ;; handlers on it meant they had no ICE to act on at all.
        (is (nil? (#'ai-core/current-run-ice wire))
            "position resolves to nothing at a forced encounter")
        (is (= "Archangel" (:title (ai-core/encountered-ice wire)))
            "the encounter summary is the only source that works")))))

(deftest the-forced-encounter-pass-is-recorded-on-the-encounter
  (testing "#160 item 2: the pass ledger is [:encounters :no-action], not [:run :no-action]"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Archangel"]}
                 :runner {:hand ["Bank Job"]}})
      (force-archangel-encounter! state)
      (let [before (runner-wire-state state)]
        (is (nil? (get-in before [:game-state :encounters :no-action]))
            "nobody has passed this encounter yet"))
      ;; The Runner declines to act. This is the same "continue" the seat sends.
      (core/process-action "continue" state :runner nil)
      (let [after (runner-wire-state state)]
        (is (= :runner (get-in after [:game-state :encounters :no-action]))
            "the engine records the passer ON THE ENCOUNTER")
        (is (not= :runner (get-in after [:game-state :run :no-action]))
            "and NOT on the run — reading run-level here answers about another window")))))

(deftest both-seats-see-the-forced-encounter
  (testing "the Corp half of the coordination is on the wire too"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Archangel"]}
                 :runner {:hand ["Bank Job"]}})
      (force-archangel-encounter! state)
      (let [wire (corp-wire-state state)
            phase (wire-phase wire)]
        (is (true? (ai-core/at-encounter? wire phase))
            "the Corp's fire handlers gate on the same predicate")
        (is (= "Archangel" (:title (ai-core/encountered-ice wire)))
            "and must fire the subs of the ENCOUNTERED card")
        (is (seq (filter #(and (not (:broken %)) (not (:fired %)))
                         (:subroutines (ai-core/encountered-ice wire))))
            "with an unbroken sub for it to find")))))

(deftest the-seats-wire-card-ref-can-fire-the-forced-encounters-subs
  (testing "end to end: the Corp's fire command, built the way the seat builds it"
    ;; The seat sends `unbroken-subroutines` with core/create-card-ref of the
    ;; encountered ICE. That ref carries :zone [:hand] here, which is not a
    ;; server — if the engine could not resolve it, every gate fixed above would
    ;; still end in a dropped command.
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Archangel"]}
                 :runner {:hand ["Bank Job"]}})
      (take-credits state :corp)
      (play-from-hand state :runner "Bank Job")
      (run-empty-server state "HQ")
      (click-prompt state :corp "Yes")
      (let [wire (corp-wire-state state)
            card-ref (ai-core/create-card-ref (ai-core/encountered-ice wire))]
        (is (= [:hand] (:zone card-ref)) "sanity: the ref really does say :hand")
        (core/process-action "unbroken-subroutines" state :corp {:card card-ref})
        ;; Archangel's only sub is a trace; both sides bid 0 so it succeeds.
        (click-prompt state :corp "0")
        (click-prompt state :runner "0")
        (click-card state :corp (get-resource state 0))
        (is (nil? (get-resource state 0))
            "the subroutine resolved — the seat-shaped card ref reached the engine")))))

(deftest a-continue-after-the-opponent-passed-ends-the-encounter-with-subs-unfired
  ;; Settles a review disagreement with evidence instead of argument (#160, guest
  ;; panel CRITICAL). The client believed "continue never passes an encounter
  ;; while subs are unbroken" — the #92 rule — and one existing client test
  ;; recorded that as intended even with the Corp already recorded as the
  ;; encounter's passer. The engine says otherwise: `continue :encounter-ice`
  ;; tests the OTHER side's pass first and ends the encounter, without ever
  ;; looking at the subroutines. It is a free pass, and refusing it stalled the
  ;; seat at the one window where it had one.
  (testing "forced encounter: Corp declines, Runner's continue ends it, subs never fire"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Archangel"]}
                 :runner {:hand ["Bank Job"]}})
      (take-credits state :corp)
      (play-from-hand state :runner "Bank Job")
      (run-empty-server state "HQ")
      (click-prompt state :corp "Yes")
      (core/process-action "continue" state :corp nil)
      (is (= :corp (:no-action (core/get-current-encounter state)))
          "the Corp is the recorded passer")
      (is (false? (get-in @state [:run :no-action]))
          "and the RUN ledger says nothing about it — reading that key answers the wrong question")
      (let [wire (runner-wire-state state)]
        (is (true? (ai-core/opponent-passed-encounter? wire "runner"))
            "the client predicate must see it"))
      (core/process-action "continue" state :runner nil)
      (is (nil? (core/get-current-encounter state)) "the encounter is over")
      (is (some? (get-resource state 0))
          "Bank Job survived — the trace subroutine never resolved")))
  (testing "and identically at an ordinary encounter, which is why the fix is not forced-only"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"]}})
      (play-from-hand state :corp "Ice Wall" "HQ")
      (rez state :corp (get-ice state :hq 0))
      (take-credits state :corp)
      (run-on state "HQ")
      (run-continue state)
      (is (= :encounter-ice (get-in @state [:run :phase])))
      (core/process-action "continue" state :corp nil)
      (is (= :corp (:no-action (core/get-current-encounter state))))
      (is (false? (get-in @state [:run :no-action]))
          "run-level stays false for the whole encounter — the old client read")
      (core/process-action "continue" state :runner nil)
      (is (nil? (core/get-current-encounter state)) "encounter ended")
      (is (= :movement (get-in @state [:run :phase])))
      (is (not (:broken (first (:subroutines (core/get-card state (get-ice state :hq 0))))))
          "the sub was never broken and never fired — the Runner simply walked past"))))

(deftest the-encounter-outranks-the-phase-in-the-engine-too
  (testing "our client mirror is not an invention: continue dispatches on the encounter"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Archangel"]}
                 :runner {:hand ["Bank Job"]}})
      (force-archangel-encounter! state)
      ;; If `continue` dispatched on the phase, this would resolve the SUCCESS
      ;; phase and the encounter would be orphaned. It does not: it passes
      ;; encounter priority, which is exactly what the seat's continue must mean
      ;; at this window.
      (is (some? (core/get-current-encounter state)))
      (core/process-action "continue" state :runner nil)
      (is (some? (core/get-current-encounter state))
          "one pass does not end it — the Corp still owes its own")
      (is (= :runner (:no-action (core/get-current-encounter state)))))))
