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
            [ai-run-corp-decisions]
            [ai-display]
            [ai-state]
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

;; ============================================================================
;; #164: a forced encounter with NO :run at all
;; ============================================================================
;; Everything above drives a forced encounter that still sits inside a run, so
;; every client predicate could keep its `is there a run?` guard and still be
;; fixed by #160's phase-string widening. Quest Completed → Ganked! produces the
;; other shape: `force-ice-encounter` has an explicit cleanup branch for
;; `(and (not (:run @state)) (empty? (:encounters @state)))`, so an encounter
;; outliving its run is a state the engine plans for — and on that wire
;; [:encounters :ice] is populated while [:run] is nil.

(defn- force-runless-encounter!
  "Leaves the game at a forced Ice Wall encounter with NO run in progress.
   Recipe lifted from game.core.runs-test's
   `forced-encounters-forced-encounters-outside-of-run`, minus the icebreakers —
   we want the sub left UNBROKEN so the seats have a real decision to own."
  [state]
  (play-from-hand state :corp "Ice Wall" "New remote")
  (play-from-hand state :corp "Ganked!" "Server 1")
  (take-credits state :corp)
  (take-credits state :runner)
  (take-credits state :corp)
  (let [iw (get-ice state :remote1 0)]
    (rez state :corp iw)
    (run-empty-server state :archives)
    (run-empty-server state :rd)
    (click-prompt state :runner "No action")
    (run-empty-server state :hq)
    (click-prompt state :runner "No action")
    (play-from-hand state :runner "Quest Completed")
    (click-card state :runner (get-content state :remote1 0))
    (click-prompt state :corp "Yes")
    (click-card state :corp iw)))

(defn- runless-game []
  {:corp {:deck [(qty "Hedge Fund" 5)]
          :hand ["Ice Wall" "Ganked!"]}
   :runner {:hand ["Quest Completed"] :credits 20}})

(deftest a-forced-encounter-can-outlive-its-run-entirely
  (testing "#164's premise: [:encounters :ice] populated, [:run] nil, on both wires"
    (do-game
      (new-game (runless-game))
      (force-runless-encounter! state)
      (is (some? (core/get-current-encounter state)) "sanity: the engine is mid-encounter")
      (is (nil? (:run @state)) "sanity: and there is no run")
      (doseq [[label wire] [["runner" (runner-wire-state state)]
                            ["corp" (corp-wire-state state)]]]
        (is (nil? (get-in wire [:game-state :run]))
            (str label ": the wire carries no :run map at all"))
        (is (nil? (wire-phase wire))
            (str label ": so there is no phase string for any gate to match"))
        (is (some? (get-in wire [:game-state :encounters :ice]))
            (str label ": but the encounter IS on the wire"))
        (is (true? (ai-core/live-encounter? wire)) (str label ": live-encounter?"))
        (is (true? (ai-core/at-encounter? wire (wire-phase wire)))
            (str label ": at-encounter? answers without a run"))
        (is (= "Ice Wall" (:title (ai-core/encountered-ice wire)))
            (str label ": and names the encountered card"))))))

(deftest the-runless-encounter-has-an-owner
  (testing "#164: run-window-owner / my-run-window? must not require a run"
    (do-game
      (new-game (runless-game))
      (force-runless-encounter! state)
      (let [wire (runner-wire-state state)]
        (is (nil? (get-in wire [:game-state :encounters :no-action]))
            "nobody has passed this encounter yet")
        (is (true? (#'ai-core/my-run-window? wire "runner"))
            "the Runner owes the first pass — `wait` must have a window to own")
        (is (false? (#'ai-core/my-run-window? wire "corp"))
            "and the Corp does not, until the Runner passes"))
      (core/process-action "continue" state :runner nil)
      (is (some? (core/get-current-encounter state))
          "one pass does not end it, exactly as inside a run")
      (let [wire (corp-wire-state state)]
        (is (= :runner (get-in wire [:game-state :encounters :no-action])))
        (is (true? (#'ai-core/my-run-window? wire "corp"))
            "now the Corp owes the closing pass")))))

(deftest the-runner-decision-wake-fires-without-a-run
  (testing "#164: runner-encounter-decision-pending? gated on phase AND position"
    (do-game
      (new-game (runless-game))
      (force-runless-encounter! state)
      (let [wire (runner-wire-state state)]
        (is (nil? (#'ai-core/current-run-ice wire))
            "position resolves to nothing: there is no run to hold a position")
        (is (true? (#'ai-core/runner-encounter-decision-pending? wire "runner"))
            "the Runner is stopped at an unbroken sub — that is a pending decision")
        (is (false? (#'ai-core/runner-encounter-decision-pending? wire "corp"))
            "the Corp's decision is a fire decision, not this one"))
      (core/process-action "continue" state :runner nil)
      (let [wire (runner-wire-state state)]
        (is (false? (#'ai-core/runner-encounter-decision-pending? wire "runner"))
            "once we have passed, we are waiting on the Corp — not still deciding")))))

(deftest the-corp-classifier-sees-a-runless-fire-decision
  (testing "#164: corp-run-decision returned :none on (nil? run) before it looked"
    (do-game
      (new-game (runless-game))
      (force-runless-encounter! state)
      (let [wire (corp-wire-state state)
            decision (ai-run-corp-decisions/corp-run-decision wire)]
        (is (not= :none (:kind decision))
            (str "monitor-run --fire-if-asked sat on its hands here. got: " decision))
        (is (contains? #{:fire-unbroken :waiting-runner-signal} (:kind decision))
            (str "the Corp owes a fire-or-pass decision on Ice Wall. got: " decision))
        (is (= "Ice Wall" (get-in decision [:ice :title]))
            "and it is about the ENCOUNTERED card")
        (is (= 1 (get-in decision [:ice :unbroken-count])))))))

(deftest diagnose-blocker-owns-the-runless-encounter
  (testing "#164: the stuck seat's own command gated its encounter branch on :in-run?"
    (do-game
      (new-game (runless-game))
      (force-runless-encounter! state)
      (let [out (with-out-str
                  (#'ai-display/show-blocker-diagnosis* (runner-wire-state state)))]
        (is (clojure.string/includes? out "Ice Wall")
            (str "the diagnosis must name the ICE the seat is stuck on. got:\n" out))
        (is (not (clojure.string/includes? out "Nothing is blocking"))
            (str "and must not report a clear board. got:\n" out))))))

(deftest prompt-does-not-call-the-runless-encounter-a-paid-ability-window
  (testing "#164: `prompt` keyed its run context on (:phase run), which is nil here"
    (do-game
      (new-game (runless-game))
      (force-runless-encounter! state)
      (let [wire (runner-wire-state state)
            p (get-in wire [:game-state :runner :prompt-state])]
        ;; The engine DOES surface a prompt — force-ice-encounter calls
        ;; show-run-prompts — but it carries no :choices and no :selectable, so
        ;; it lands in show-prompt-detailed's passive/paid-ability arm.
        (is (= :run (:prompt-type p)) "a run prompt, not a choose prompt")
        (is (empty? (:choices p)) "with nothing to choose")
        (is (empty? (:selectable p)) "and nothing to select")
        (let [out (with-out-str (ai-display/show-prompt-detailed wire))]
          (is (clojure.string/includes? out "Ice Wall")
              (str "`prompt` must name the encounter it is stuck at. got:\n" out))
          (is (not (clojure.string/includes? out "run-only"))
              (str "and must NOT say `continue` is run-only — `continue` is the "
                   "only exit from this window. got:\n" out))
          (is (not (clojure.string/includes? out "Paid ability window (no run active)"))
              (str "nor call a live encounter an empty paid-ability window. got:\n" out))
          (is (not (clojure.string/includes? out "Run Phase: \n"))
              (str "and must not print a phase label with no phase. got:\n" out)))))))

(defmacro with-client-state
  "Point the AI client's state atom at a captured wire map for the body."
  [wire & body]
  `(let [prev# @ai-state/client-state]
     (try (reset! ai-state/client-state ~wire)
          ~@body
          (finally (reset! ai-state/client-state prev#)))))

(deftest every-seat-surface-agrees-at-a-runless-encounter
  (testing "#164 (Fable pass, the unaddressed remainder of the issue's own opening
            line — 'everything that asks is-there-a-run before is-there-an-
            encounter'): `status` is the FIRST command a stuck seat runs, and it
            gated its headline, its run section and its compact one-liner on
            :run alone. So it printed a turn-level 'waiting for the Runner' while
            `prompt`, `diagnose` and `wait` all said the window was ours — four
            surfaces, two answers."
    (do-game
      (new-game (runless-game))
      (force-runless-encounter! state)
      (doseq [[label wire] [["runner" (runner-wire-state state)]
                            ["corp" (corp-wire-state state)]]]
        (let [;; show-status has no captured-state arity — it reads the client
              ;; atom — so drive it the way the seat does.
              full    (with-out-str (with-client-state wire (ai-display/show-status)))
              compact (with-out-str (#'ai-display/show-status-compact* wire))
              promptd (with-out-str (ai-display/show-prompt-detailed wire))]
          ;; NOT (includes? full "Ice Wall") — Ice Wall is INSTALLED in Server 1,
          ;; so the board dump names it whether or not `status` knows there is an
          ;; encounter. That assertion passed with the fix reverted, i.e. it
          ;; pinned nothing. Key on the section only the encounter can print.
          (is (clojure.string/includes? full "FORCED ENCOUNTER")
              (str label ": `status` must show the encounter section. got:\n" full))
          (is (clojure.string/includes? compact "Enc:Ice Wall")
              (str label ": and the polled one-liner must not read as idle. got:\n" compact))
          ;; The point is AGREEMENT: `status` must not describe an idle board
          ;; while `prompt` describes a live window.
          (is (clojure.string/includes? promptd "Ice Wall")
              (str label ": prompt names the window. got:\n" promptd))
          ;; The headline must come from run-status-headline (which reads the
          ;; ENCOUNTER's ledger) rather than the turn-level status text. Both of
          ;; its arms name the window explicitly; the turn-level text never does.
          (is (re-find #"Status:.*(Your move|Waiting on|window)" full)
              (str label ": the headline must be the WINDOW's, not the turn's. got:\n" full)))))))
