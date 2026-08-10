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
    (is (not (core/break-ability? {})))))

;; ---------------------------------------------------------------------------
;; break-phase-block — the refusal, and its limits
;; ---------------------------------------------------------------------------

(deftest break-outside-an-encounter-is-refused
  (testing "approach-ice: the exact state the Luna seat hit"
    (let [lines (core/break-phase-block {:run {:phase "approach-ice" :server [:hq]}})]
      (is (seq lines))
      (let [out (str/join " " lines)]
        (is (str/includes? out "approach-ice") "names the phase we are actually at")
        (is (str/includes? out "ENCOUNTER") "names the phase breaking requires")
        (is (str/includes? out "continue") "names the command that gets there")
        (is (not (str/includes? (str/lower-case out) "timeout"))
            "must not blame the detection mechanism")
        (is (not (str/includes? (str/lower-case out) "log"))
            "must not blame the game log"))))

  (testing "movement (the approach-server window is a movement phase, not its own)"
    (is (seq (core/break-phase-block {:run {:phase "movement" :position 0}}))))

  (testing "initiation"
    (is (seq (core/break-phase-block {:run {:phase "initiation"}}))))

  (testing "no run at all"
    (let [lines (core/break-phase-block {})]
      (is (seq lines))
      (is (str/includes? (str/join " " lines) "No run is active"))))

  (testing "encounter-ice is the ONE phase that permits it"
    ;; The over-correction to guard against: refusing a legal break costs a seat
    ;; the encounter it was entitled to fight.
    (is (nil? (core/break-phase-block {:run {:phase "encounter-ice" :position 1}})))))

;; ---------------------------------------------------------------------------
;; ability-failure-lines — diagnosis, keyed on the server's own verdict
;; ---------------------------------------------------------------------------

(deftest failure-diagnosis-follows-the-servers-playable-flag
  (testing "no :playable => the server would refuse; say so and say don't retry"
    (let [out (str/join " " (core/ability-failure-lines {:label "Break 1 Sentry subroutine"}))]
      (is (str/includes? out "not currently usable"))
      (is (str/includes? out "RULES refusal"))
      (is (str/includes? out "Do not just retry")
          "the whole point: a timeout invites the retry that mints phantom prompts")))

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
