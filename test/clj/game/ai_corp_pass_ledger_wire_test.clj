(ns game.ai-corp-pass-ledger-wire-test
  "Issue #169: the Corp's encounter handlers answer from a LOG SIGNAL where the
   engine keeps a LEDGER, so a Corp that owns the closing pass cannot send it.

   At an encounter where the Runner has already passed with subroutines still
   unbroken, `core/run-window-owner` says the Corp owns the window and `wait`
   wakes it — but `corp-run-decision` asks `runner-signaled-let-fire?`, a scan
   for a system message the Runner only emits when it TANKS, and a plain
   `continue` never emits it. So the Corp reported :waiting-runner-signal and
   idled while the Runner waited on the Corp. Mutual stall, and the ownership
   layer and the action layer contradicting each other.

   Driven against the real engine rather than mocks: the whole question is what
   the ENGINE records when the Runner passes an encounter, and this project has
   been burned by mocks that invented the field the bug lived in."
  (:require [game.core :as core]
            [game.core.diffs :as diffs]
            [game.test-framework :refer :all]
            [ai-core :as ai-core]
            [ai-run-corp-decisions :as decisions]
            [ai-run-corp-handlers :as corp-handlers]
            [ai-display]
            [ai-websocket-client-v2 :as ws]
            [clojure.string :as str]
            [clojure.string]
            [clojure.test :refer :all]))

(use-fixtures :each (fn [t] (corp-handlers/reset-state!) (t)))

(defn- wire-state
  "The client-state map a seat holds, built from the real serializer. :phase is
   stringified here because the transport hop does it and every client
   comparison assumes the string."
  [state side]
  (let [gs (get (diffs/public-states state) (if (= side "corp") :corp-state :runner-state))]
    {:side side
     :game-state (cond-> gs
                   (get-in gs [:run :phase]) (update-in [:run :phase] name))}))

(defn- handler-ctx
  [wire & {:keys [strategy]}]
  (let [side (:side wire)]
    {:side side
     :run-phase (get-in wire [:game-state :run :phase])
     :strategy (or strategy {})
     :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000169")
     :my-prompt (get-in wire [:game-state (keyword side) :prompt-state])
     :state wire}))

(defn- sends
  [f]
  (let [sent (atom [])
        out (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
              (with-out-str (f)))]
    {:sent @sent :out out}))

(defmacro with-runner-passed-encounter
  "Ice Wall encountered and rezzed, one unbroken subroutine, and the Runner has
   PASSED it with a plain `continue` — no tank, no signal. The Corp owes the
   closing pass."
  [& body]
  `(do-game
     (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"] :credits 10}
                :runner {:hand ["Bank Job"]}})
     (play-from-hand ~'state :corp "Ice Wall" "HQ")
     (take-credits ~'state :corp)
     (run-on ~'state "HQ")
     (rez ~'state :corp (get-ice ~'state :hq 0))
     (run-continue ~'state)
     (core/process-action "continue" ~'state :runner nil)
     ~@body))

;; ---------------------------------------------------------------------------
;; The premise
;; ---------------------------------------------------------------------------

(deftest a-plain-runner-pass-leaves-the-corp-owning-the-window
  (testing "what the engine records, and what each client layer makes of it"
    (with-runner-passed-encounter
      (is (= :runner (:no-action (core/get-current-encounter state)))
          "engine: the Runner's pass is on the ENCOUNTER's ledger")
      (let [wire (wire-state state "corp")
            ice (ai-core/encountered-ice wire)]
        (is (= 1 (count (filter #(and (not (:broken %)) (not (:fired %)))
                                (:subroutines ice))))
            "and the subroutine is still unbroken — this is not an empty window")
        (is (true? (ai-core/opponent-passed-encounter? wire "corp"))
            "the ledger predicate answers correctly for the Corp…")
        (is (false? (decisions/runner-signaled-let-fire? wire (:title ice)))
            "…while the log signal does not, because a plain pass emits no signal")))))

(deftest the-runners-pass-is-not-a-signal-in-the-log
  (testing "why the heuristic cannot see it: the engine's line is filtered out by design"
    (with-runner-passed-encounter
      (let [log (map (comp str :text) (get-in (wire-state state "corp") [:game-state :log]))]
        (is (some #(re-find #"(?i)has no further action" %) log)
            "the engine DOES log the pass…")
        (is (not-any? #(re-find #"(?i)unbroken subroutines" %) log)
            "…but never as the 'indicates to fire all unbroken subroutines' signal")))))

;; ---------------------------------------------------------------------------
;; The bug
;; ---------------------------------------------------------------------------

(deftest the-corp-classifier-names-the-decision-it-owns
  (testing "#169: corp-run-decision reported :waiting-runner-signal at a window the Corp owns"
    (with-runner-passed-encounter
      (let [d (decisions/corp-run-decision (wire-state state "corp"))]
        ;; NOT "the Runner cannot act again" — they can; the engine does not lock
        ;; a passed Runner out of breaking (measured under #167). What is true is
        ;; that they will never SIGNAL, so waiting on a signal is the stall.
        (is (= :fire-unbroken (:kind d))
            (str "the Runner has passed with subs unbroken: fire-or-pass is the Corp's decision. got: " d))
        (is (= :runner-passed (:authorization d))
            "and the decision records WHICH authorization, because the guidance says it out loud")))))

(deftest fire-if-asked-surfaces-a-pass-as-a-decision
  (testing "#169: --fire-if-asked used to idle forever on a signal that can never arrive. It still does not FIRE on a pass — a pass is not an ask, and the wire cannot tell a covered subroutine from one created after the pass (#177) — but the window now reaches the seat as a decision instead of a wait"
    (with-runner-passed-encounter
      (let [wire (wire-state state "corp")
            ctx (handler-ctx wire :strategy {:fire-if-asked true})]
        (let [{:keys [sent]} (sends #(corp-handlers/handle-corp-fire-if-asked ctx))]
          (is (not-any? #(= "unbroken-subroutines" (:command %)) sent)
              "no auto-fire on a pass alone"))
        ;; …and it falls through to the handler that DOES speak, rather than
        ;; returning the opponent-wait that was the original stall.
        (let [r (atom nil)]
          (with-out-str (reset! r (corp-handlers/handle-corp-fire-decision ctx)))
          (is (= :decision-required (:status @r))
              (str "the Corp owns this window and must be told so, got: " @r))
          (is (= :fire-unbroken (get-in @r [:decision :kind])))
          (is (= :runner-passed (get-in @r [:decision :authorization]))))))))

(deftest fire-unbroken-fires-when-the-runner-has-passed
  (testing "#169: the --fire-unbroken strategy gated on the same log signal"
    (with-runner-passed-encounter
      (let [{:keys [sent]} (sends #(corp-handlers/handle-corp-fire-unbroken
                                    (handler-ctx (wire-state state "corp")
                                                 :strategy {:fire-unbroken true})))]
        (is (some #(= "unbroken-subroutines" (:command %)) sent)
            "same window, same evidence, same answer")))))

;; ---------------------------------------------------------------------------
;; What must NOT change: #90's Runner-still-breaking protection
;; ---------------------------------------------------------------------------

(deftest a-runner-who-has-not-passed-is-still-protected
  (testing "#90: the Corp must not fire on a Runner who is still deciding — the ledger is empty here, so nothing changes"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"] :credits 10}
                 :runner {:hand ["Bank Job"]}})
      (play-from-hand state :corp "Ice Wall" "HQ")
      (take-credits state :corp)
      (run-on state "HQ")
      (rez state :corp (get-ice state :hq 0))
      (run-continue state)
      (is (nil? (:no-action (core/get-current-encounter state)))
          "precondition: nobody has passed this encounter")
      (let [wire (wire-state state "corp")]
        (is (false? (ai-core/opponent-passed-encounter? wire "corp")))
        (is (= :waiting-runner-signal (:kind (decisions/corp-run-decision wire)))
            "with no pass and no signal, waiting is still the right answer")
        (let [{:keys [sent]} (sends #(corp-handlers/handle-corp-fire-if-asked
                                      (handler-ctx wire :strategy {:fire-if-asked true})))]
          (is (not-any? #(= "unbroken-subroutines" (:command %)) sent)
              "firing here would tax a Runner who was about to break — the #90 bug"))))))

(deftest the-corps-own-pass-is-not-the-runners
  (testing "the ledger names ONE side: a Corp that passed first must not read its own pass as the Runner's"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"] :credits 10}
                 :runner {:hand ["Bank Job"]}})
      (play-from-hand state :corp "Ice Wall" "HQ")
      (take-credits state :corp)
      (run-on state "HQ")
      (rez state :corp (get-ice state :hq 0))
      (run-continue state)
      (core/process-action "continue" state :corp nil)
      (is (= :corp (:no-action (core/get-current-encounter state))))
      (let [wire (wire-state state "corp")]
        (is (false? (ai-core/opponent-passed-encounter? wire "corp"))
            "our own pass is not the opponent's")
        (let [{:keys [sent]} (sends #(corp-handlers/handle-corp-fire-if-asked
                                      (handler-ctx wire :strategy {:fire-if-asked true})))]
          (is (not-any? #(= "unbroken-subroutines" (:command %)) sent)
              "and it is certainly not authorization to fire"))))))

;; ---------------------------------------------------------------------------
;; The same shape one handler further on
;; ---------------------------------------------------------------------------

(deftest the-post-fire-pass-does-not-depend-on-the-opponents-NAME
  (testing "#169, third site: handle-corp-waiting-after-subs-fired asked the LOG whether the Runner had passed — with the opponent's username baked into the regex"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Tithe"] :credits 10}
                 :runner {:hand ["Bank Job"]}})
      (play-from-hand state :corp "Tithe" "HQ")
      (take-credits state :corp)
      (run-on state "HQ")
      (rez state :corp (get-ice state :hq 0))
      (run-continue state)
      ;; Runner passes; Corp fires the unbroken sub. The encounter is still open
      ;; and the Corp owes the closing pass.
      (core/process-action "continue" state :runner nil)
      (core/process-action "unbroken-subroutines" state :corp
                           {:card (core/get-current-ice state)})
      (is (some? (core/get-current-encounter state))
          "precondition: Tithe's subroutine does not end the run, so the encounter is open")
      (is (= :runner (:no-action (core/get-current-encounter state)))
          "and the ledger still names the Runner — the engine never resets it")
      (let [wire (wire-state state "corp")
            log (map (comp str :text) (get-in wire [:game-state :log]))]
        ;; The heuristic this replaces looked for a literal "ai-runner has no
        ;; further action". These players are not called that — and neither is a
        ;; human opponent, or any seat someone renames.
        (is (not-any? #(re-find #"(?i)ai-runner has no further action" %) log)
            "the log line exists but does not carry that name")
        (let [{:keys [sent]} (sends #(corp-handlers/handle-corp-waiting-after-subs-fired
                                      (handler-ctx wire)))]
          (is (some #(= "continue" (:command %)) sent)
              "the Corp owes the closing pass and the ledger says so, whatever the opponent is called"))))))

(deftest the-window-actually-closes-end-to-end
  (testing "the claim behind the whole issue: the actions these handlers now send do advance the run"
    ;; Tithe, not Ice Wall: its subroutine does NOT end the run, so the closing
    ;; pass is genuinely owed after the fire. (With an end-the-run subroutine the
    ;; engine tears the run down on the fire and a following `continue` is an
    ;; illegal `continue :default` — which the engine logs as an ERROR. Worth
    ;; saying out loud: the whole point of this issue is a seat sending what the
    ;; window actually takes.)
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Tithe"] :credits 10}
                 :runner {:hand ["Bank Job"]}})
      (play-from-hand state :corp "Tithe" "HQ")
      (take-credits state :corp)
      (run-on state "HQ")
      (rez state :corp (get-ice state :hq 0))
      (run-continue state)
      (core/process-action "continue" state :runner nil)
      ;; The two actions that close this window, in order — what --fire-unbroken
      ;; sends automatically, and what a seat sends by hand after --fire-if-asked
      ;; surfaces the decision. NOT what --fire-if-asked sends itself: on a plain
      ;; pass it deliberately fires nothing (#169).
      (core/process-action "unbroken-subroutines" state :corp
                           {:card (core/get-current-ice state)})
      (is (some? (core/get-current-encounter state))
          "the subroutine resolved and the encounter is still open — the pass is owed")
      (core/process-action "continue" state :corp nil)
      (is (nil? (core/get-current-encounter state))
          "the Corp's pass is the second one, so the engine ends the encounter")
      (is (= :movement (get-in @state [:run :phase]))
          "and the run moves on, instead of both seats waiting on each other"))))

(deftest the-guidance-does-not-claim-the-runner-signalled
  (testing "guidance-text-is-code: a Runner who passed did not signal, and `continue` here ENDS the encounter"
    (with-runner-passed-encounter
      (let [lines (decisions/present-corp-run-decision
                   (decisions/corp-run-decision (wire-state state "corp")))
            text (clojure.string/join "\n" lines)]
        (is (re-find #"(?i)PASSED this encounter" text)
            "say what actually happened")
        (is (not (re-find #"(?i)signaled" text))
            "and not what did not — an absence assertion, because the true line is present either way")
        (is (re-find #"(?i)end the encounter" text)
            "and `continue` here is not a mere priority pass")))))

;; ---------------------------------------------------------------------------
;; What the pass does NOT authorize
;; ---------------------------------------------------------------------------

(defn- assert-names-the-authorization
  [label out]
  (is (re-find #"(?i)Runner passed the encounter" out)
      (str label ": say what actually happened"))
  (is (not (re-find #"(?i)Runner signaled" out))
      (str label ": and not what did not — nobody signalled anything")))

(deftest fire-unbroken-names-the-real-authorization
  (testing "the standing-commitment strategy printed the same false claim"
    (with-runner-passed-encounter
      (assert-names-the-authorization
       "--fire-unbroken"
       (:out (sends #(corp-handlers/handle-corp-fire-unbroken
                      (handler-ctx (wire-state state "corp")
                                   :strategy {:fire-unbroken true}))))))))

(deftest the-corp-prompt-surface-agrees-with-the-automation
  (testing "guest panel CRITICAL: prompt/status still told the Corp that `continue` passes priority, when at this window it ENDS the encounter"
    (with-runner-passed-encounter
      (let [wire (wire-state state "corp")
            run (get-in wire [:game-state :run])
            out (with-out-str
                  (#'ai-display/print-run-window-priority!
                   wire run (:phase run) "corp"))]
        (is (re-find #"(?i)ENDS the encounter" out)
            "the seat must be told what its continue actually does")
        (is (re-find #"(?i)a pass is not a 'let it fire' signal" out)
            "and that the fire on offer is the seat's own call, not something the loop is about to do")
        (is (not (re-find #"(?i)'continue' passes priority here" out))
            "and not the opposite — an absence assertion, because the new line does not displace the old one on its own")
        (is (re-find #"(?i)fire-subs" out)
            "and that firing is still available, since the encounter stays open through a fire")))))

(deftest a-stale-resolution-line-does-not-license-a-pass
  (testing "guest panel MAJOR: the post-fire handler composed encounter ONE's real 'resolves … subroutine' log line with encounter TWO's ledger, and passed while fresh subs were live"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Tithe"] :credits 10}
                 :runner {:hand ["Bank Job"]}})
      (play-from-hand state :corp "Tithe" "HQ")
      (take-credits state :corp)
      (run-on state "HQ")
      (rez state :corp (get-ice state :hq 0))
      (run-continue state)
      (core/process-action "continue" state :runner nil)
      (core/process-action "unbroken-subroutines" state :corp
                           {:card (core/get-current-ice state)})
      ;; The log line is the ENGINE's, not a fabrication — an earlier version of
      ;; this test invented one ("… unbroken subroutine on …" vs the handler's
      ;; plural-only regex) and passed with the guard deleted, which is how the
      ;; mutation run caught it.
      (let [wire (wire-state state "corp")
            resolution-lines (filter #(re-find #"(?i)resolves .*subroutine" (str (:text %)))
                                     (get-in wire [:game-state :log]))]
        (is (seq resolution-lines) "precondition: the engine really logged the resolution")
        ;; Now the state #163 makes reachable: the SAME card encountered again,
        ;; subroutines fresh, while that line is still inside the handler's
        ;; ten-entry window. (Sisyphus Protocol does this for real; the wire is
        ;; edited here rather than staged, and only the sub state is touched.)
        (let [fresh (assoc-in wire [:game-state :encounters :ice :subroutines]
                              [{:label "Do 1 net damage"}])]
          (is (seq (filter #(and (not (:broken %)) (not (:fired %)))
                           (:subroutines (ai-core/encountered-ice fresh))))
              "precondition: this encounter's subroutine is live")
          (let [{:keys [sent]} (sends #(corp-handlers/handle-corp-waiting-after-subs-fired
                                        (handler-ctx fresh)))]
            (is (not-any? #(= "continue" (:command %)) sent)
                "passing here ends the encounter with a live subroutine unresolved — the log says something fired, the SUBS say what is left")))))))


(deftest a-subroutine-created-after-the-pass-is-not-auto-fired
  (testing "guest panel CRITICAL: a pass covers the subroutines that existed when it was made, and a Tour Guide can grow one afterwards. The wire cannot tell the two apart (#177), so the only honest rule is that a pass never auto-fires"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Tour Guide" "NGO Front"] :credits 20}
                 :runner {:hand ["Bank Job"]}})
      (core/gain state :corp :click 10)
      (play-from-hand state :corp "Tour Guide" "HQ")
      (play-from-hand state :corp "NGO Front" "New remote")
      (take-credits state :corp)
      (run-on state "HQ")
      (rez state :corp (get-ice state :hq 0))
      (run-continue state)
      (is (zero? (count (:subroutines (core/get-current-ice state))))
          "precondition: no rezzed assets, so the Runner passes a window with nothing in it")
      (core/process-action "continue" state :runner nil)
      (rez state :corp (get-content state :remote1 0))
      (is (= 1 (count (:subroutines (core/get-current-ice state))))
          "and only THEN does the Corp grow an end-the-run subroutine")
      (let [wire (wire-state state "corp")
            {:keys [sent]} (sends #(corp-handlers/handle-corp-fire-if-asked
                                    (handler-ctx wire :strategy {:fire-if-asked true})))]
        (is (not-any? #(= "unbroken-subroutines" (:command %)) sent)
            "firing here ends the run under a Runner whose own client is re-opening the break decision — two seats disagreeing about one window")))))

(deftest fire-unbroken-is-the-opt-in-that-does-fire-on-a-pass
  (testing "the standing commitment is where firing-on-a-pass lives, deliberately: --fire-unbroken's contract is 'auto-fire unbroken subs', and its operator opted into exactly that"
    (with-runner-passed-encounter
      (let [{:keys [sent out]} (sends #(corp-handlers/handle-corp-fire-unbroken
                                        (handler-ctx (wire-state state "corp")
                                                     :strategy {:fire-unbroken true})))]
        (is (some #(= "unbroken-subroutines" (:command %)) sent)
            "this strategy fires on the ledger where --fire-if-asked defers")
        (is (re-find #"(?i)Runner passed the encounter" out)
            "and names the authorization honestly while doing it")))))
