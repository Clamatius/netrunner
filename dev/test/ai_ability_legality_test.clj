(ns ai-ability-legality-test
  "#116 — an ability that the RULES forbid must not be reported as a harness timeout.

   Found by the Luna Runner seat (game d840fc14):

     ./dev/send_command runner use-ability \"Mayfly\" 0
     ❌ Ability failed: Mayfly - Ability not confirmed in game log (timeout).

   'Not confirmed in game log (timeout)' describes our DETECTION MECHANISM — no
   confirmation appeared within N seconds — which is what a lost message or a
   slow server looks like. The actual condition was a phase error: you cannot
   break subroutines until you are encountering the ice. The two call for
   opposite responses, and a retry at the wrong phase is the duplicate-send
   pattern that mints phantom prompts (#75/#77).

   The fix has two halves, deliberately at different confidence levels:

   REFUSE (without sending) only for a break outside an encounter. That rests on
   an engine invariant — game.core.ice/break-sub's :break-req requires
   `(peek (:encounters @state))` — so there is no state of the world where the
   send would have worked.

   DIAGNOSE (still send) when the server's per-ability :playable flag is absent.
   That flag is a snapshot and could be stale-false on a legal ability; refusing
   on it could cost a seat a break it was entitled to."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [ai-core :as core]))

;; ---------------------------------------------------------------------------
;; break-ability? — conservative on purpose
;; ---------------------------------------------------------------------------

(deftest break-ability-detection
  (testing "the labels game.core.ice/break-sub actually generates"
    ;; Read off a live rig (game d840fc14): the engine builds these as
    ;; (str "break " ...), so they are the shapes that matter.
    (is (core/break-ability? {:label "Break up to 2 Barrier subroutines"}))
    (is (core/break-ability? {:label "Break 1 Sentry subroutine"}))
    (is (core/break-ability? {:label "Break 1 Code Gate subroutine"})))

  (testing "the auto-pump-and-break dynamic ability"
    (is (core/break-ability? {:dynamic :auto-pump-and-break})))

  (testing "NOT the pump ability sitting next to it on the same card"
    ;; These carry :playable true outside an encounter, so mistaking one for a
    ;; break would refuse a legal action.
    (is (not (core/break-ability? {:label "Add 1 strength" :playable true})))
    (is (not (core/break-ability? {:label "Add 3 strength" :playable true})))
    (is (not (core/break-ability? {:dynamic :auto-pump}))))

  (testing "conservative: `starts-with`, not `includes`"
    ;; A false negative costs a generic error message; a false positive prints a
    ;; rules claim that is not true. Anything that merely mentions breaking must
    ;; not be treated as a break ability.
    (is (not (core/break-ability? {:label "Prevent the Runner from breaking subroutines"})))
    (is (not (core/break-ability? {:label "Give -1 strength to current piece of ice"})))
    (is (not (core/break-ability? {:label nil})))
    (is (not (core/break-ability? {}))))

  (testing "Boomerang's 'Break 0 subroutines' IS matched here — and that is why"
    ;; game.cards.hardware gives Boomerang a hand-written ability labelled
    ;; "Break 0 subroutines" that is NOT built by break-sub, breaks nothing, and
    ;; is legal outside any encounter. Both guest seats found it independently.
    ;; The label test cannot tell it apart, so the REFUSAL must not rest on the
    ;; label alone — see boomerang-is-not-refused below.
    (is (core/break-ability? {:label "Break 0 subroutines"}))))

;; ---------------------------------------------------------------------------
;; break-phase-block — the refusal, and its limits
;; ---------------------------------------------------------------------------

;; An unplayable break ability — the shape a real breaker has out of encounter,
;; verified live against game d840fc14's rig (Break abilities carry no :playable
;; there while their pump siblings carry :playable true).
(def ^:private unplayable-break {:label "Break 1 Sentry subroutine"})

(deftest break-outside-an-encounter-is-refused
  (testing "approach-ice: the exact state the Luna seat hit"
    (let [lines (core/break-refusal-lines
                  unplayable-break {:run {:phase "approach-ice" :server [:hq]}})]
      (is (seq lines))
      (let [out (str/join " " lines)]
        (is (str/includes? out "approach-ice") "names the phase we are actually at")
        (is (str/includes? out "ENCOUNTER") "names what breaking requires")
        (is (str/includes? out "continue") "names the command that gets there")
        (is (not (str/includes? (str/lower-case out) "timeout"))
            "must not blame the detection mechanism")
        (is (not (str/includes? (str/lower-case out) "game log"))
            "must not blame the game log"))))

  (testing "movement (the approach-server window is a movement phase, not its own)"
    (is (seq (core/break-refusal-lines unplayable-break {:run {:phase "movement" :position 0}}))))

  (testing "initiation"
    (is (seq (core/break-refusal-lines unplayable-break {:run {:phase "initiation"}}))))

  (testing "no run at all"
    (let [lines (core/break-refusal-lines unplayable-break {})]
      (is (seq lines))
      (is (str/includes? (str/join " " lines) "No run is active"))
      (is (not (str/includes? (str/join " " lines) "'continue'"))
          "there is no run to continue")))

  (testing "a keyword phase cannot change the verdict, only the wording"
    ;; The wire sends strings; the engine and our fixtures use keywords. Since
    ;; the refusal is keyed on :encounters, a keyword phase can no longer refuse
    ;; anything it shouldn't — it must only render cleanly.
    (let [out (str/join " " (core/break-refusal-lines
                              unplayable-break {:run {:phase :approach-ice}}))]
      (is (str/includes? out "approach-ice"))
      (is (not (str/includes? out ":approach-ice")) "no stray keyword colon"))))

(deftest a-live-encounter-is-never-refused
  ;; GUEST-PANEL CRITICAL, found independently by both seats. break-sub's
  ;; :break-req requires `(peek (:encounters @state))` — it never mentions the
  ;; run phase. runs/force-ice-encounter calls set-phase only `(when new-state)`,
  ;; and all six card-pool call sites pass four args, so a FORCED encounter
  ;; (Ganked!, Chrysalis) is live while :phase still reads something else — or
  ;; while there is no :run at all, off a Gang Sign breach.
  ;;
  ;; Refusing there blocks a legal and often mandatory break, and the old advice
  ;; ("continue to enter the encounter") is actively harmful during a forced
  ;; encounter: it passes priority and lets the subs fire. The heuristic bot
  ;; routes its breaks through use-ability! too (ai_run_tactics), so this would
  ;; have tanked autonomous encounters that used to work.
  (testing "ordinary encounter"
    (is (nil? (core/break-refusal-lines
                unplayable-break
                {:run {:phase "encounter-ice" :position 1}
                 :encounters {:ice {:cid 1 :title "Palisade"}}}))))

  (testing "forced encounter with the phase still reading 'success'"
    (is (nil? (core/break-refusal-lines
                unplayable-break
                {:run {:phase "success"}
                 :encounters {:ice {:cid 2 :title "Chrysalis"}}}))))

  (testing "forced encounter with NO run at all (breach off Gang Sign)"
    (is (nil? (core/break-refusal-lines
                unplayable-break
                {:encounters {:ice {:cid 3 :title "Ganked!"}}}))))

  (testing "phase says encounter-ice but no encounter is live -> still refused"
    ;; The converse: the phase alone must not authorise a break either.
    (is (seq (core/break-refusal-lines
               unplayable-break {:run {:phase "encounter-ice" :position 1}})))))

(deftest boomerang-is-not-refused
  ;; GUEST-PANEL CRITICAL. Boomerang's hand-written "Break 0 subroutines" is not
  ;; a break-sub ability, breaks nothing, and is legal outside any encounter.
  ;; break-ability? matches it on the label and cannot tell it apart — so the
  ;; refusal additionally requires the server's own :playable to be ABSENT. When
  ;; the ability really is legal the server marks it playable, and we stand down.
  (testing "legal outside a run: server says playable, so no refusal"
    (is (nil? (core/break-refusal-lines
                {:label "Break 0 subroutines" :playable true}
                {}))))

  (testing "the same guard protects any label-shaped false positive mid-run"
    (is (nil? (core/break-refusal-lines
                {:label "Break 0 subroutines" :playable true}
                {:run {:phase "approach-ice"}}))))

  (testing "a genuinely unusable break at the wrong moment is still refused"
    (is (seq (core/break-refusal-lines
               {:label "Break 0 subroutines"} {:run {:phase "approach-ice"}})))))

;; ---------------------------------------------------------------------------
;; ability-failure-lines — diagnosis, keyed on the server's own verdict
;; ---------------------------------------------------------------------------

(deftest failure-diagnosis-follows-the-servers-playable-flag
  (testing "no :playable => the server would refuse; say so, and hedge honestly"
    (let [out (str/join " " (core/ability-failure-lines {:label "Break 1 Sentry subroutine"}))]
      (is (str/includes? out "not usable"))
      (is (str/includes? out "RULES refusal"))
      (is (str/includes? out "Re-check state before retrying")
          "the whole point: a timeout invites the retry that mints phantom prompts")
      ;; Guest-panel: the diff's own rationale calls :playable a snapshot that
      ;; can be stale, so the message must not then assert certainty about it.
      ;; A seat that is genuinely mid-encounter must not be told flatly that the
      ;; rules forbade what it just tried.
      (is (str/includes? out "WHEN WE LAST LOOKED")
          "the claim is scoped to the snapshot it is actually based on")
      (is (str/includes? out "very likely")
          "hedged, because a lost message is still possible")))

  (testing ":playable true => a genuine timeout; leave the generic wording alone"
    ;; If the server said the ability WAS legal and it still didn't confirm, the
    ;; harness explanation is the honest one. Overriding it here would just swap
    ;; one misdiagnosis for another.
    (is (nil? (core/ability-failure-lines {:label "Add 1 strength" :playable true}))))

  (testing "nil ability gets NO rules claim"
    ;; use-ability! passes nil when the index is out of range. A nil map has no
    ;; :playable for the trivial reason that there is nothing there, so a naive
    ;; `(when-not (:playable ability) ...)` fabricates "the server reports this
    ;; as not usable" about an ability that does not exist — the same species of
    ;; misleading output this issue is about. Caught while writing this test.
    (is (nil? (core/ability-failure-lines nil)))
    (is (nil? (core/ability-failure-lines {})) "and an empty map is no better")))
