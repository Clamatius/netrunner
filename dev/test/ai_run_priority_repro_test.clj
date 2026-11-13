(ns ai-run-priority-repro-test
  "REPRODUCTIONS for the run-automation analysis (B1/B2/B3/A1/A2/A3), before any
   redesign goes to review. Each test asserts the CORRECT behaviour, so a test that
   is red today is a reproduced claim and a test that is green is evidence the
   claim is false (or that the window is already guarded).

   KNOWN-RED BY DESIGN. This namespace is deliberately NOT in the Makefile's
   `make test` list, and it is named in test_registration_test.sh's EXCLUDED so
   that guard stays green. Run it on its own:

       lein test ai-run-priority-repro-test

   When a fix lands, its red tests here go green; move them into the regular
   suite then (and drop the EXCLUDED entry when nothing red is left).

   EVERY FIXTURE IS ENGINE-BUILT. No hand-written board: a real game is driven
   with game.test-framework, each seat's client state is the real serializer's
   output (game.core.diffs/public-states) after a JSON round trip (so values
   arrive as strings — :prompt-type \"select\", :no-action \"runner\" — exactly as
   the client decodes them), and whatever a seat's automation sends is delivered
   back into the engine as that seat's action. The blocking prompts are real
   engine prompts:

   - Runner: Corroder's strength pump paid with the run's bad-publicity credit.
     game.core.costs `handler :credit` shows pick-credit-providing-cards whenever
     [:run :bad-publicity-available] is positive, and with no credit-providing card
     installed its :choices {:card ...} predicate matches nothing — so the wire
     prompt is {:prompt-type \"select\" :selectable [] :choices [Done]
     :offer-bad-pub? N}: the lone-Done select of #296/#301/#302.
   - Corp: Malia Z0L0K4's :on-rez target select (game.cards.assets). An asset may
     be rezzed in any paid-ability window of a run.
   - Corp at an encounter: Brân 1.0 / Ansel 1.0's install-from-HQ-or-Archives
     subroutine, whose `:choices {:card ...}` opens a Corp select while the
     subroutine is resolving.

   SEATS. Two real seats are two REPLs. Here they share one JVM, so the state
   that is per-REPL in real life is swapped in and out per seat: the run-strategy
   atom (the `tank` set lives there) and ai-runs' reported-events. Handler latch
   atoms are already per-side (separate namespaces).

   COMMANDS. Each dispatcher command is emulated by the exact Clojure form
   dev/send_command executes for it (line numbers as of 0a4561690):
     continue            -> (ai-actions/monitor-run!)          send_command:2229
     continue --loop     -> same (the arm strips --loop)       send_command:2223
     monitor-run         -> (ai-actions/monitor-run! ...)      send_command:2258
     continue --single   -> (ai-actions/continue-run! ...)     send_command:2204
     continue-run        -> (ai-actions/continue-run! ...)     send_command:2243
     tank <ice>          -> set-strategy! + (runs/continue-run!) send_command:2333-2339
   ai-actions/monitor-run! and continue-run! are plain (def x runs/x) aliases
   (ai_actions.clj:176,179). dev/send_command itself is NOT run: it talks to the
   live REPLs, which this work must not touch."
  (:require [game.core :as core]
            [game.core.diffs :as diffs]
            [game.test-framework :refer :all]
            [ai-core :as ai-core]
            [ai-runs :as runs]
            [ai-run-corp-decisions :as corp-decisions]
            [ai-run-corp-handlers :as corp-handlers]
            [ai-run-runner-handlers :as runner-handlers]
            [ai-state :as ai-state]
            [ai-websocket-client-v2 :as ws]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer :all]))

;; ===========================================================================
;; Harness
;; ===========================================================================

(def ^:private gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000302"))

(def ^:private loop-timeout-ms
  "Bound on any auto-continue-loop! a command enters. Production is 300s; a red
   test must fail, not hang."
  4000)

(defn- wire-state
  "The seat's client state as the transport delivers it: real serializer, JSON
   round trip, keys keywordized, VALUES as strings."
  [state side]
  (let [gs (get (diffs/public-states state) (if (= side "corp") :corp-state :runner-state))]
    {:connected true
     :side side
     :gameid gameid
     :game-state (json/parse-string (json/generate-string gs) true)}))

;; side -> {:strategy <run-strategy atom value> :reported <reported-events value>}
(def ^:private seats (atom {}))
(def ^:private reported-events-atom @#'runs/reported-events)

(defn- reset-seats! []
  (reset! seats {})
  (runs/reset-strategy!)
  (runner-handlers/reset-state!)
  (corp-handlers/reset-state!)
  (runs/reset-reported-events!)
  (runs/reset-window-grace!))

(use-fixtures :each (fn [t]
                      (reset-seats!)
                      (try (t)
                           (finally (reset-seats!)
                                    (reset! ai-state/client-state {})))))

(defn- seat-in! [side]
  (let [{:keys [strategy reported]} (get @seats side)]
    (reset! runs/run-strategy (or strategy {}))
    (reset! reported-events-atom (or reported {:gameid nil :events #{}}))))

(defn- seat-out! [side]
  (swap! seats assoc side {:strategy @runs/run-strategy
                           :reported @reported-events-atom}))

(defn- seat-strategy [side] (get-in @seats [side :strategy] {}))
(defn- set-seat-strategy! [side m] (swap! seats assoc-in [side :strategy] m))

(defn- as-seat!
  "Run `f` (a dispatcher command's Clojure form) as `side`'s seat: that seat's
   wire in client-state, its strategy/reported-events swapped in, every
   :game/action it sends delivered to the engine as ITS action and its wire
   refreshed after. Returns {:result :sent :commands :out}."
  [state side f]
  (seat-in! side)
  (reset! ai-state/client-state (wire-state state side))
  (let [sent (atom [])
        result (atom nil)
        orig-loop runs/auto-continue-loop!
        out (with-redefs [ws/send-message! (fn [evt {:keys [command args] :as data}]
                                             (swap! sent conj data)
                                             (when (= evt :game/action)
                                               (core/process-action command state (keyword side) args))
                                             (reset! ai-state/client-state (wire-state state side))
                                             true)
                          ;; NO REAL SOCKET. ws/select-card! (and every other
                          ;; safe-action! sender) runs ensure-connected! first, which
                          ;; finds no :socket in this client state, calls it broken,
                          ;; and CONNECTS to ws://localhost:1042 — the live game
                          ;; server on this machine. A first draft of this harness
                          ;; did exactly that. The connection check is answered here
                          ;; and connect! is made to fail loudly instead.
                          ws/ensure-connected! (constantly true)
                          ws/connect! (fn [& _] (throw (ex-info "repro harness: no real connections" {})))
                          runs/auto-continue-loop! (fn [& kvs]
                                                     (apply orig-loop (concat kvs [:timeout-ms loop-timeout-ms])))
                          runner-handlers/approach-pass-confirm-ms 600]
              (with-out-str (reset! result (f))))]
    (seat-out! side)
    {:result @result :sent @sent :commands (mapv :command @sent) :out out}))

;; --- dispatcher command forms (see ns docstring for the send_command lines) ---

(defn- cmd-continue [& flags] #(apply runs/monitor-run! flags))      ; `continue` / `monitor-run`
(defn- cmd-continue-single [& flags] #(apply runs/continue-run! flags)) ; `continue --single`
(defn- cmd-tank
  "Verbatim body of dev/send_command's `tank` arm (2333-2339)."
  [ice-name]
  #(let [current-tank (get (runs/get-strategy) :tank #{})]
     (runs/set-strategy! {:tank (conj current-tank ice-name)})
     (println (format "✅ Authorized tank on: %s" ice-name))
     (runs/continue-run!)))

;; --- observations ---

(defn- tank-signals [sent]
  (filter #(and (= "system-msg" (:command %))
                (str/includes? (str (get-in % [:args :msg])) "indicates to fire"))
          sent))

(defn- continues [sent] (filter #(= "continue" (:command %)) sent))

(defn- corp-sees-signal? [state ice-title]
  (corp-decisions/runner-signaled-let-fire? (wire-state state "corp") ice-title))

(defn- seat-prompt [state side]
  (get-in (wire-state state side) [:game-state (keyword side) :prompt-state]))

(defn- blocking? [state side]
  (ai-state/engine-blocking-prompt? (seat-prompt state side)))

(defn- engine-pass! [state side] (core/process-action "continue" state side nil))

(defn- pump-on-bad-pub!
  "Runner: Corroder's strength pump (ability 1), paid with the run's bad-pub
   credit — leaves the lone-Done credit select open."
  [state]
  (card-ability state :runner (get-program state 0) 1)
  (is (= "select" (:prompt-type (seat-prompt state "runner")))
      "fixture: the pump opened the credit select")
  (is (empty? (:selectable (seat-prompt state "runner")))
      "fixture: no credit-providing card qualifies — :selectable is empty")
  (is (blocking? state "runner") "fixture: engine-blocking-prompt? is true for the Runner"))

(defn- rez-malia!
  "Corp: rez Malia Z0L0K4 mid-run, leaving its on-rez target select open."
  [state]
  (rez state :corp (get-content state :remote1 0))
  (is (= "select" (:prompt-type (seat-prompt state "corp")))
      "fixture: Malia's on-rez select is the Corp's prompt")
  (is (blocking? state "corp") "fixture: engine-blocking-prompt? is true for the Corp"))

;; ===========================================================================
;; B1 (#267/#273) and B2 (#274): tank before the encounter, then drive the run
;; ===========================================================================
;;
;; Server HQ holds Hourglass (3x "the Runner loses [Click]": no end-the-run, so the
;; run survives the fire and the Runner's closing pass is observable). For the
;; movement entry, Ice Wall is the outer ICE and is passed unrezzed.

(defn- tank-entry!
  "Put the run at `entry`, then issue `tank \"Hourglass\"` there exactly as the
   dispatcher does. Returns the tank command's observation."
  [state entry]
  (case entry
    :initiation
    (do (core/process-action "run" state :runner {:server "HQ"})
        (is (= :initiation (get-in @state [:run :phase]))))

    :unrezzed-approach
    (do (run-on state "HQ")
        (is (= :approach-ice (get-in @state [:run :phase])))
        (is (not (:rezzed (core/get-current-ice state)))))

    :rezzed-approach
    ;; Game F: the Corp rezzes at the approach; the Runner's `continue` stops on
    ;; "ICE rezzed", and THEN it types `tank`.
    (do (run-on state "HQ")
        (rez state :corp (get-ice state :hq 0))
        (as-seat! state "runner" (cmd-continue))
        (is (= :approach-ice (get-in @state [:run :phase])))
        (is (not (get-in @state [:run :no-action])) "fixture: nobody has passed the rezzed approach"))

    :movement
    (do (run-on state "HQ")
        (engine-pass! state :runner)
        (engine-pass! state :corp)
        (is (= :movement (get-in @state [:run :phase])) "fixture: past the unrezzed outer Ice Wall")
        (is (= 1 (get-in @state [:run :position])))))
  (as-seat! state "runner" (cmd-tank "Hourglass")))

(defn- drive-to-encounter!
  "Between the tank and the encounter: whenever the Runner has passed, the Corp
   (engine-level, not under test) rezzes an unrezzed Hourglass at its approach and
   passes; whenever the Runner owes the window, the Runner's seat runs `drive`.
   Stops at the Hourglass encounter. Returns the trail."
  [state drive]
  (loop [i 0 trail []]
    (let [run (:run @state)
          ice (core/get-current-ice state)]
      (cond
        (and (= :encounter-ice (:phase run)) (= "Hourglass" (:title ice))) trail
        (or (nil? run) (>= i 10)) (conj trail [:gave-up (:phase run)])
        (= :runner (:no-action run))
        (do (when (and (= :approach-ice (:phase run)) (not (:rezzed ice)))
              (core/process-action "rez" state :corp {:card ice}))
            (engine-pass! state :corp)
            (recur (inc i) (conj trail [:corp-passed (:phase run)])))
        :else
        (let [{:keys [result commands]} (as-seat! state "runner" drive)]
          (recur (inc i) (conj trail [:runner-drove (:phase run) (:status result) commands])))))))

(defn- b1-scenario
  "tank at `entry`, then drive with `drive` to the encounter and once more AT it.
   Returns what the test asserts on."
  [entry drive]
  (do-game
    (new-game {:corp {:hand ["Hourglass" "Ice Wall"] :credits 20}
               :runner {:credits 10}})
    (play-from-hand state :corp "Hourglass" "HQ")
    (when (= :movement entry)
      (play-from-hand state :corp "Ice Wall" "HQ"))
    (take-credits state :corp)
    (let [tank-obs (tank-entry! state entry)
          trail (drive-to-encounter! state drive)
          at-encounter? (= :encounter-ice (get-in @state [:run :phase]))
          at-enc (as-seat! state "runner" drive)
          signalled? (corp-sees-signal? state "Hourglass")
          ;; If the Corp could see a signal, it fires (engine-level here; the
          ;; Corp's own automation is B3), and the Runner's next drive must pass.
          after-fire (when signalled?
                       (fire-subs state (core/get-current-ice state))
                       (as-seat! state "runner" drive))]
      {:tank-obs tank-obs :trail trail :at-encounter? at-encounter? :at-enc at-enc
       :signalled? signalled? :after-fire after-fire
       :runner-strategy (seat-strategy "runner")})))

(defn- assert-b1 [entry drive-name {:keys [tank-obs trail at-encounter? at-enc signalled? after-fire runner-strategy]}]
  (testing (str "tank at " (name entry) ", then " drive-name)
    (is (empty? (tank-signals (:sent tank-obs)))
        "today's fact, not the claim: the tank step itself sends no signal before the encounter")
    (is at-encounter? (str "fixture: the run reached the Hourglass encounter. trail " trail))
    (is (contains? (:tank runner-strategy #{}) "Hourglass")
        (str "B2: the tank authorization must survive `" drive-name "` mid-run. strategy now: "
             (pr-str runner-strategy)))
    (is (seq (tank-signals (:sent at-enc)))
        (str "B1: driving the run AT the encounter with `" drive-name "` must send the tank signal. "
             "status " (:status (:result at-enc)) ", sent " (:commands at-enc)
             "\n--- output ---\n" (:out at-enc)))
    (is signalled? "and the Corp's own predicate (runner-signaled-let-fire?) must see it")
    (when signalled?
      (is (seq (continues (:sent after-fire)))
          (str "after the Corp fires, the Runner's next `" drive-name "` must send its closing pass. sent "
               (:commands after-fire))))))

(def ^:private b1-entries [:initiation :unrezzed-approach :rezzed-approach :movement])

(deftest b1-tank-before-the-encounter-then-continue-single
  ;; `continue --single` / `continue-run` / a second `tank`: continue-run!, which
  ;; never resets the strategy.
  (doseq [entry b1-entries]
    (assert-b1 entry "continue --single" (b1-scenario entry (cmd-continue-single)))))

(deftest b1-tank-before-the-encounter-then-plain-continue
  ;; `continue` (and `continue --loop`, `monitor-run`): monitor-run! ->
  ;; monitor-active-run! -> reset-strategy! (ai_runs.clj:2649 -> :72).
  (doseq [entry b1-entries]
    (assert-b1 entry "continue" (b1-scenario entry (cmd-continue)))))

;; ===========================================================================
;; B2 (#274): which dispatcher commands wipe the run strategy mid-run
;; ===========================================================================

(defn- at-hourglass-encounter
  "Pre-rezzed Hourglass on HQ (no rez event inside the run window); both seats
   pass the approach at engine level; the run is at the encounter, nothing broken,
   nobody has passed it."
  [f]
  (do-game
    (new-game {:corp {:hand ["Hourglass"] :credits 20}
               :runner {:hand ["Corroder"] :credits 10}})
    (play-from-hand state :corp "Hourglass" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (take-credits state :corp)
    (run-on state "HQ")
    (run-continue state :encounter-ice)
    (is (= :encounter-ice (get-in @state [:run :phase])) "fixture: at the encounter")
    (is (nil? (:no-action (core/get-current-encounter state))) "fixture: nobody has passed the encounter")
    (f state)))

(def ^:private b2-commands
  "Every dispatcher command a seat uses to drive a live run, with the form it runs."
  [["continue"          (cmd-continue)]
   ["continue --loop"   (cmd-continue)]
   ["monitor-run"       (cmd-continue)]
   ["continue --single" (cmd-continue-single)]
   ["continue-run"      (cmd-continue-single)]])

(deftest b2-a-mid-run-command-keeps-the-tank
  (doseq [[command f] b2-commands]
    (testing (str "`" command "` at the encounter, after `tank \"Hourglass\"` at the approach")
      (at-hourglass-encounter
       (fn [state]
         (set-seat-strategy! "runner" {:tank #{"Hourglass"}})
         (let [{:keys [result sent commands out]} (as-seat! state "runner" f)]
           (is (contains? (:tank (seat-strategy "runner") #{}) "Hourglass")
               (str "B2: `" command "` wiped the tank mid-run. strategy now: " (pr-str (seat-strategy "runner"))))
           (is (seq (tank-signals sent))
               (str "B2: `" command "` must signal the tank the seat already gave, not re-ask. status "
                    (:status result) ", sent " commands "\n--- output ---\n" out))))))))

(deftest b2-a-mid-run-continue-keeps-full-break
  (testing "`run HQ --full-break`, then plain `continue` at the encounter (Ice Wall, Corroder installed)"
    (do-game
      (new-game {:corp {:hand ["Ice Wall"] :credits 20}
                 :runner {:hand ["Corroder"] :credits 10}})
      (play-from-hand state :corp "Ice Wall" "HQ")
      (rez state :corp (get-ice state :hq 0))
      (take-credits state :corp)
      (play-from-hand state :runner "Corroder")
      (run-on state "HQ")
      (run-continue state :encounter-ice)
      (set-seat-strategy! "runner" {:full-break true})
      (let [{:keys [result commands out]} (as-seat! state "runner" (cmd-continue))]
        (is (:full-break (seat-strategy "runner"))
            (str "B2: plain `continue` wiped --full-break mid-run. strategy now: " (pr-str (seat-strategy "runner"))))
        (is (not= :fire-decision-required (:status result))
            (str "B2: with --full-break standing the Runner breaks, it is not asked to break-or-tank. sent "
                 commands "\n--- output ---\n" out))))))

(deftest b2-a-mid-run-continue-does-not-refire-after-the-fired-at-latch-is-wiped
  (testing "Corp `monitor-run --fire-unbroken` fires Hourglass on the Runner's signal; then Corp `continue --fire-unbroken`
            (monitor-run! again, which wipes :fired-at-encounter) must not fire a second time"
    (at-hourglass-encounter
     (fn [state]
       (as-seat! state "runner" (cmd-tank "Hourglass"))
       (is (corp-sees-signal? state "Hourglass") "fixture: the signal landed")
       (let [first-fire (as-seat! state "corp" (cmd-continue "--fire-unbroken"))]
         (is (some #{"unbroken-subroutines"} (:commands first-fire)) "fixture: the Corp fired once")
         (when (= :encounter-ice (get-in @state [:run :phase]))
           (let [again (as-seat! state "corp" (cmd-continue "--fire-unbroken"))]
             (is (not-any? #{"unbroken-subroutines"} (:commands again))
                 (str "no second fire. sent " (:commands again))))))))))

;; ===========================================================================
;; B3 (#195/#199 residue): does the Corp's fire path fire?
;; ===========================================================================

(defn- b3-runner-done!
  [state how]
  (case how
    :signal-only (as-seat! state "runner" (cmd-tank "Hourglass"))
    :ledger-only (engine-pass! state :runner)          ; `continue --raw`: a plain pass, no signal
    :both (do (as-seat! state "runner" (cmd-tank "Hourglass"))
              (engine-pass! state :runner)))
  (is (= (contains? #{:signal-only :both} how) (corp-sees-signal? state "Hourglass"))
      (str "fixture: the signal is " (if (#{:signal-only :both} how) "" "NOT ") "in the Corp's log"))
  (is (= (contains? #{:ledger-only :both} how)
         (= :runner (:no-action (core/get-current-encounter state))))
      (str "fixture: the Runner's pass is " (if (#{:ledger-only :both} how) "" "NOT ") "on the encounter ledger")))

(deftest b3-corp-fire-unbroken-fires
  (doseq [how [:signal-only :ledger-only :both]]
    (testing (str "Corp `monitor-run --fire-unbroken`, Runner done by " (name how))
      (at-hourglass-encounter
       (fn [state]
         (b3-runner-done! state how)
         (let [clicks-before (get-in @state [:runner :click])
               {:keys [result commands out]} (as-seat! state "corp" (cmd-continue "--fire-unbroken"))]
           (is (some #{"unbroken-subroutines"} commands)
               (str "B3: the Corp must fire. status " (:status result) ", sent " commands "\n--- output ---\n" out))
           ;; Not (:fired sub): when the Runner has passed, the Corp's loop goes on
           ;; to send the closing pass and the engine resets the subs as the
           ;; encounter ends. The clicks Hourglass took are what stays.
           (is (= (max 0 (- clicks-before 3)) (get-in @state [:runner :click]))
               (str "and the engine resolved Hourglass's three subs: Runner clicks "
                    clicks-before " -> " (get-in @state [:runner :click])))))))))

(deftest b3-corp-fire-if-asked-fires-on-a-signal
  (doseq [how [:signal-only :both]]
    (testing (str "Corp `monitor-run --fire-if-asked`, Runner done by " (name how))
      (at-hourglass-encounter
       (fn [state]
         (b3-runner-done! state how)
         (let [{:keys [result commands out]} (as-seat! state "corp" (cmd-continue "--fire-if-asked"))]
           (is (some #{"unbroken-subroutines"} commands)
               (str "B3: a signal is an ask. status " (:status result) ", sent " commands "\n--- output ---\n" out))))))))

;; ===========================================================================
;; A1 (#302): handle-runner-approach-ice passes over the Runner's own prompt
;; ===========================================================================

(defn- runner-bad-pub-game
  "Ice Wall on HQ (rezzed if `rezzed?`), Corp with bad publicity, Runner with
   Corroder installed and no credit-providing card."
  [rezzed? bad-pub f]
  (do-game
    (new-game {:corp {:hand ["Ice Wall"] :credits 20 :bad-pub bad-pub}
               :runner {:hand ["Corroder"] :credits 10}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (when rezzed? (rez state :corp (get-ice state :hq 0)))
    (take-credits state :corp)
    (play-from-hand state :runner "Corroder")
    (f state)))

(deftest a1-runner-approach-does-not-pass-over-its-own-select
  (doseq [[command f] [["continue --single" (cmd-continue-single)] ["continue" (cmd-continue)]]]
    (testing (str "unrezzed approach, Runner holding the lone-Done credit select, `" command "`")
      (runner-bad-pub-game
       false 1
       (fn [state]
         (run-on state "HQ")
         (pump-on-bad-pub! state)
         (let [{:keys [result sent commands out]} (as-seat! state "runner" f)]
           (is (empty? (continues sent))
               (str "A1: a continue went out over the Runner's own open select. status " (:status result)
                    ", sent " commands ", engine :no-action now " (get-in @state [:run :no-action])
                    ", Runner prompt still " (:msg (seat-prompt state "runner"))
                    "\n--- output ---\n" out))))))))

;; ===========================================================================
;; A2 (Corp, derived): Brân 1.0 / Ansel 1.0 install sub fires, Corp's select open
;; ===========================================================================

(defn- break-subs-by-click!
  "Runner breaks the subroutines at `idxs` with the ICE's own bioroid ability
   (one [Click] each), one break per use."
  [state ice-title idxs]
  ;; By label: the engine drops a broken sub from the next break prompt
  ;; (breakable-subroutines-choice), so Brân's two identical "End the run" labels
  ;; are offered one at a time.
  (doseq [i idxs]
    (card-side-ability state :runner (get-ice state :hq 0) 0)
    (click-prompt state :runner (:label (nth (:subroutines (get-ice state :hq 0)) i))))
  (let [subs (:subroutines (get-ice state :hq 0))]
    (is (= (set idxs) (set (keep-indexed #(when (:broken %2) %1) subs)))
        (str "fixture: exactly subs " idxs " of " ice-title " are broken: " (pr-str (map #(select-keys % [:label :broken]) subs))))))

(defn- install-sub-open
  "`ice-title` rezzed on HQ, the Runner breaks every sub except the install sub
   (index `install-idx`), the Runner is done (`runner-done`: :passed or :tanked),
   and the Corp fires: the install sub is resolving, its select is open."
  [ice-title install-idx runner-done f]
  (do-game
    (new-game {:corp {:hand [ice-title "Ice Wall"] :credits 20}
               :runner {:credits 10}})
    (play-from-hand state :corp ice-title "HQ")
    (rez state :corp (get-ice state :hq 0))
    (take-credits state :corp)
    (run-on state "HQ")
    (run-continue state :encounter-ice)
    (break-subs-by-click! state ice-title (remove #{install-idx} [0 1 2]))
    (case runner-done
      :passed (engine-pass! state :runner)
      :tanked (as-seat! state "runner" (cmd-tank ice-title)))
    (fire-subs state (get-ice state :hq 0))
    (f state)))

(defn- a2-corp-view-facts
  "What the Corp's client state looks like at that moment (asked by the brief)."
  [state ice-title install-idx]
  (let [w (wire-state state "corp")
        p (get-in w [:game-state :corp :prompt-state])
        ice (ai-core/encountered-ice w)]
    (testing (str ice-title ": the Corp's client state while the install sub resolves")
      (is (= "select" (:prompt-type p)) (str "Corp prompt: " (pr-str p)))
      (is (re-find #"(?i)install" (str (:msg p))) (str "Corp prompt msg: " (:msg p)))
      (is (= "waiting" (:prompt-type (seat-prompt state "runner"))) "Runner holds a waiting prompt")
      (is (true? (:fired (nth (:subroutines ice) install-idx)))
          (str "the install sub is already :fired while it resolves (ice.clj resolve-subroutine! marks it first): "
               (pr-str (:subroutines ice))))
      (is (empty? (ai-core/fireable-subs ice)) "so the client sees NO fireable sub left")
      (is (ai-state/engine-blocking-prompt? p)))))

(def ^:private a2-shapes
  [["Brân 1.0" 0] ["Ansel 1.0" 1]])

(deftest a2-corp-does-not-continue-over-its-own-install-select
  (doseq [[ice-title install-idx] a2-shapes
          runner-done [:passed :tanked]
          [command f] [["continue --single" (cmd-continue-single)]
                       ["continue" (cmd-continue)]
                       ["monitor-run --fire-unbroken" (cmd-continue "--fire-unbroken")]]]
    (testing (str ice-title ", Runner " (name runner-done) ", Corp's next command `" command "`")
      (install-sub-open
       ice-title install-idx runner-done
       (fn [state]
         (a2-corp-view-facts state ice-title install-idx)
         (let [{:keys [result sent commands out]} (as-seat! state "corp" f)]
           (is (empty? (continues sent))
               (str "A2: a continue went out over the Corp's own install select. status " (:status result)
                    ", sent " commands ", encounter still open? " (boolean (core/get-current-encounter state))
                    ", Corp prompt now " (pr-str (:msg (seat-prompt state "corp")))
                    "\n--- output ---\n" out))))))))

(deftest a2-the-fire-tick-itself-surfaces-the-select
  (testing "control: when the Corp's own --fire-unbroken loop does the firing, the fire tick sees the new prompt
            (fire-unbroken-strategy-result) and stops with :decision-required — the A2 send needs a FURTHER tick"
    (do-game
      (new-game {:corp {:hand ["Brân 1.0" "Ice Wall"] :credits 20}
                 :runner {:credits 10}})
      (play-from-hand state :corp "Brân 1.0" "HQ")
      (rez state :corp (get-ice state :hq 0))
      (take-credits state :corp)
      (run-on state "HQ")
      (run-continue state :encounter-ice)
      (break-subs-by-click! state "Brân 1.0" [1 2])
      (engine-pass! state :runner)
      (let [{:keys [result commands out]} (as-seat! state "corp" (cmd-continue "--fire-unbroken"))]
        (is (= ["unbroken-subroutines"] commands) (str "sent " commands "\n" out))
        (is (= :decision-required (:status result)) (str "status " (:status result) "\n" out))))))

;; ===========================================================================
;; A3: every automated `continue` sender, against a blocking prompt the engine
;; can actually put in that window. Each cell runs twice: CONTROL (no prompt —
;; proves the fixture reaches that sender: the continue goes out and the sender's
;; own line is printed) and BLOCKED (the same state with the seat's own blocking
;; prompt open — must send no continue).
;; ===========================================================================

(defn- runner-cell
  [{:keys [rezzed? bad-pub to-window block strategy]}]
  (fn [blocked? f]
    (runner-bad-pub-game
     rezzed? (or bad-pub 1)
     (fn [state]
       (to-window state)
       (when blocked? (block state))
       (when strategy (set-seat-strategy! "runner" strategy))
       (f state)))))

(defn- corp-cell
  [{:keys [rezzed? to-window strategy hand-extra servers]}]
  (fn [blocked? f]
    (do-game
      (new-game {:corp {:hand (into ["Ice Wall" "Malia Z0L0K4"] hand-extra) :credits 30}
                 :runner {:hand ["Liberated Account"] :credits 10}})
      (play-from-hand state :corp "Ice Wall" "HQ")
      (play-from-hand state :corp "Malia Z0L0K4" "New remote")
      (doseq [[card server] servers] (play-from-hand state :corp card server))
      (when rezzed? (rez state :corp (get-ice state :hq 0)))
      (take-credits state :corp)
      (play-from-hand state :runner "Liberated Account")
      (to-window state)
      (when blocked? (rez-malia! state))
      (set-seat-strategy! "corp" ((or strategy (constantly {})) state))
      (f state))))

(defn- bran-cell
  "Corp at a Brân encounter. CONTROL: all three subs broken, no Corp prompt.
   BLOCKED: the A2 state (install sub resolving)."
  [runner-passed?]
  (fn [blocked? f]
    (do-game
      (new-game {:corp {:hand ["Brân 1.0" "Ice Wall"] :credits 20}
                 :runner {:credits 10}})
      (play-from-hand state :corp "Brân 1.0" "HQ")
      (rez state :corp (get-ice state :hq 0))
      (take-credits state :corp)
      (run-on state "HQ")
      (run-continue state :encounter-ice)
      (break-subs-by-click! state "Brân 1.0" (if blocked? [1 2] [0 1 2]))
      (when runner-passed? (engine-pass! state :runner))
      (when blocked? (fire-subs state (get-ice state :hq 0)))
      (f state))))

(def ^:private a3-cells
  [;; ---- Runner senders ----
   {:site "ai_runs/handle-initiation-auto-pass (ai_runs.clj:1635)"
    :side "runner" :line #"Auto-passing initiation window"
    :cell (runner-cell {:to-window #(core/process-action "run" % :runner {:server "HQ"})
                        :block pump-on-bad-pub!})}
   {:site "runner-handlers/handle-runner-approach-ice (runner_handlers.clj:420)  [A1]"
    :side "runner" :line #"Passed the unrezzed-ICE approach"
    :cell (runner-cell {:to-window #(run-on % "HQ") :block pump-on-bad-pub!})}
   {:site "ai_runs/handle-auto-continue (ai_runs.clj:1476), rezzed approach"
    :side "runner" :line #"Auto-continuing through paid ability window"
    :cell (runner-cell {:rezzed? true :to-window #(run-on % "HQ") :block pump-on-bad-pub!})}
   {:site "ai_runs/handle-stalled-window-self-advance (ai_runs.clj:1723)"
    :side "runner" :line #"self-advancing"
    :redefs {#'runs/self-advance-grace-ms 0}
    :cell (runner-cell {:rezzed? true
                        :to-window (fn [state] (run-on state "HQ") (engine-pass! state :runner))
                        :block pump-on-bad-pub!})}
   {:site "runner-handlers/handle-runner-pass-broken-ice (runner_handlers.clj:1004)"
    :side "runner" :line #"All subs broken on Ice Wall, Runner passing ICE"
    :cell (runner-cell {:rezzed? true :bad-pub 2
                        :to-window (fn [state]
                                     (run-on state "HQ")
                                     (run-continue state :encounter-ice)
                                     (card-ability state :runner (get-program state 0) 0)
                                     (click-prompt state :runner "End the run")
                                     (select-bad-pub state false)
                                     (is (:broken (first (:subroutines (get-ice state :hq 0))))
                                         "fixture: Ice Wall's sub is broken"))
                        :block pump-on-bad-pub!})}
   ;; ---- Corp senders ----
   {:site "corp-handlers/handle-corp-rez-strategy --no-rez (corp_handlers.clj:282)"
    :side "corp" :line #"declining rez on Ice Wall"
    :cell (corp-cell {:to-window #(run-on % "HQ") :strategy (constantly {:no-rez true})})}
   {:site "corp-handlers/handle-corp-rez-strategy rez-failed (corp_handlers.clj:297)"
    :side "corp" :line #"did not take"
    :cell (corp-cell {:to-window #(run-on % "HQ")
                      :strategy (constantly {:rez #{"Ice Wall"} :rez-attempted-at 1})})}
   {:site "corp-handlers/handle-corp-rez-strategy rez-done (corp_handlers.clj:336)"
    :side "corp" :line #"already rezzed, continuing"
    :cell (corp-cell {:rezzed? true :to-window #(run-on % "HQ")
                      :strategy (constantly {:rez #{"Ice Wall"}})})}
   {:site "corp-handlers/handle-corp-fire-if-asked empty window (corp_handlers.clj:724)"
    :side "corp" :action :auto-continue-fire-if-asked
    :cell (corp-cell {:rezzed? true :to-window #(run-on % "HQ")
                      :strategy (constantly {:fire-if-asked true})})}
   {:site "corp-handlers/handle-corp-all-subs-resolved (corp_handlers.clj:799)  [A2], Runner not passed"
    :side "corp" :line #"All subs .* on Brân 1.0.*Corp continuing"
    :cell (bran-cell false)}
   {:site "corp-handlers/handle-corp-all-subs-resolved / -waiting-after-subs-fired (corp_handlers.clj:799/848)  [A2], Runner passed"
    :side "corp" :line #"(All subs .* on Brân 1.0.*Corp continuing)|(Runner passed, Corp continuing past Brân 1.0)"
    :cell (bran-cell true)}
   {:site "corp-handlers/handle-corp-server-upgrade-decision rez-failed (corp_handlers.clj:942)"
    :side "corp" :line #"did not take — likely can't afford it"
    :cell (corp-cell {:hand-extra ["Manegarm Skunkworks"]
                      :servers [["Manegarm Skunkworks" "New remote"]]
                      :to-window (fn [state]
                                   (run-on state "Server 2")
                                   (when-not (= :runner (get-in @state [:run :no-action]))
                                     (engine-pass! state :runner))
                                   (is (= :movement (get-in @state [:run :phase])) "fixture: movement")
                                   (is (zero? (get-in @state [:run :position])) "fixture: position 0")
                                   (is (= :runner (get-in @state [:run :no-action])) "fixture: Runner passed"))
                      :strategy (fn [state]
                                  {:rez #{"Manegarm Skunkworks"}
                                   :upgrade-rez-attempted #{(:cid (get-content state :remote2 0))}})})}])

(defn- run-cell [{:keys [cell side redefs]} blocked?]
  (let [obs (atom nil)]
    (with-redefs-fn (or redefs {})
      (fn []
        (cell blocked?
              (fn [state]
                (let [r (as-seat! state side (cmd-continue-single))]
                  (reset! obs r))))))
    @obs))

(deftest a3-no-automated-continue-over-the-seats-own-blocking-prompt
  (doseq [{:keys [site side line action] :as c} a3-cells]
    (testing (str "A3 cell: " site)
      (let [control (run-cell c false)
            blocked (run-cell c true)]
        (testing " CONTROL (no blocking prompt) reaches this sender"
          (is (seq (continues (:sent control)))
              (str "control sent " (:commands control) "\n--- output ---\n" (:out control)))
          (if action
            (is (= action (get-in control [:result :action])) (str "control result " (:result control)))
            (is (re-find line (:out control)) (str "control output:\n" (:out control)))))
        (testing " BLOCKED (seat's own blocking prompt open) sends no continue"
          (is (empty? (continues (:sent blocked)))
              (str "RED CELL [" site "]: continue sent over " side "'s own blocking prompt. status "
                   (:status (:result blocked)) ", action " (:action (:result blocked))
                   "\n--- output ---\n" (:out blocked))))))))
