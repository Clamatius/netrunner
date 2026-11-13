(ns ai-run-priority-repro-test
  "REPRODUCTIONS for the run-automation analysis (B1/B2/B3/A1/A2/A3), committed
   red before the fix (470f65b57). Each test asserts the CORRECT behaviour; the
   B and A cells went green with plan v3's two changes (a tank survives a
   mid-run command; ai-run-pass/pass-priority! is the one gate for automated
   passes and fires), whose own tests are the change1-* / change2-* below. The
   namespace is registered in the Makefile's `make test` list.

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
     tank <ice>          -> (runs/tank! <ice>): set-strategy! + one continue-run!,
                            refused when no run and no encounter is live
   ai-actions/monitor-run! and continue-run! are plain (def x runs/x) aliases
   (ai_actions.clj:176,179). dev/send_command itself is NOT run: it talks to the
   live REPLs, which this work must not touch."
  (:require [game.core :as core]
            [game.core.diffs :as diffs]
            [game.test-framework :refer :all]
            [ai-core :as ai-core]
            [ai-runs :as runs]
            [ai-run-pass :as pass]
            [ai-run-corp-decisions :as corp-decisions]
            [ai-run-corp-handlers :as corp-handlers]
            [ai-run-runner-handlers :as runner-handlers]
            [ai-state :as ai-state]
            [ai-websocket-client-v2 :as ws]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt]
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

(def ^:dynamic *refused-commands*
  "Commands the stub socket REFUSES (send-message! returns false, nothing is
   delivered or recorded) — what an exhausted reconnect looks like to a sender."
  #{})
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
                                             (if (contains? *refused-commands* command)
                                               false
                                               (do
                                                 (swap! sent conj data)
                                                 (when (= evt :game/action)
                                                   (core/process-action command state (keyword side) args))
                                                 (reset! ai-state/client-state (wire-state state side))
                                                 true)))
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
  "dev/send_command's `tank` arm: its body is now (runs/tank! <ice>)."
  [ice-name]
  #(runs/tank! ice-name))

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
  ;; monitor-active-run! -> reset-strategy!, which wiped the tank until
  ;; live-tank-to-keep (Change 1) carried the named set across it.
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

(deftest b2-a-mid-run-continue-clears-full-break
  ;; This test used to be `b2-a-mid-run-continue-keeps-full-break` and asserted
  ;; the opposite. That was the wrong claim (plan v3): --full-break is a flag the
  ;; seat typed on ONE command, and a later command that omits it must be able to
  ;; withdraw it. Losing it costs a question, not a wedge: the plain `continue`
  ;; asks the seat to break-or-tank again. Only the named :tank set is kept.
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
        (is (not (:full-break (seat-strategy "runner")))
            (str "plain `continue` clears --full-break. strategy now: " (pr-str (seat-strategy "runner"))))
        (is (= :fire-decision-required (:status result))
            (str "so the seat is asked to break-or-tank again. sent " commands "\n--- output ---\n" out))))))

;; ---------------------------------------------------------------------------
;; Change 1 (plan v3): what survives a mid-run command, and what does not
;; ---------------------------------------------------------------------------

(deftest change1-continue-force-then-plain-continue-leaves-force-unset
  (testing "the strategy `continue --force` leaves, then plain `continue` mid-run: :force is gone, :tank kept"
    (at-hourglass-encounter
     (fn [state]
       ;; monitor-active-run! stores every typed flag; this is what `continue --force` leaves.
       (set-seat-strategy! "runner" {:force true :tank #{"Hourglass"}})
       (as-seat! state "runner" (cmd-continue))
       (is (not (contains? (seat-strategy "runner") :force))
           (str "strategy now: " (pr-str (seat-strategy "runner"))))
       (is (= #{"Hourglass"} (:tank (seat-strategy "runner"))))))))

(deftest change1-no-rez-then-rez-rezzes
  (testing "Corp `monitor-run --no-rez`, then `monitor-run --rez \"Ice Wall\"` at the same approach: Ice Wall is rezzed"
    (do-game
      (new-game {:corp {:hand ["Ice Wall"] :credits 20}
                 :runner {:credits 10}})
      (play-from-hand state :corp "Ice Wall" "HQ")
      (take-credits state :corp)
      (run-on state "HQ")
      (is (= :approach-ice (get-in @state [:run :phase])) "fixture: approach to unrezzed Ice Wall")
      (let [first-cmd (as-seat! state "corp" (cmd-continue "--no-rez"))]
        (is (not-any? #{"rez"} (:commands first-cmd)) (str "fixture: --no-rez declined. sent " (:commands first-cmd))))
      (is (= :approach-ice (get-in @state [:run :phase])) "fixture: still at the approach (the Runner has not passed)")
      (let [{:keys [commands out]} (as-seat! state "corp" (cmd-continue "--rez" "Ice Wall"))]
        (is (not (:no-rez (seat-strategy "corp"))) (str "strategy now: " (pr-str (seat-strategy "corp"))))
        (is (some #{"rez"} commands) (str "sent " commands "\n--- output ---\n" out))
        (is (:rezzed (get-ice state :hq 0)) "and the engine rezzed Ice Wall")))))

(def ^:private tank-refusal-out
  (str "❌ tank refused: no run and no encounter is live, so there is nothing to tank on Hourglass.\n"
       "   To commit a tank up front, start the run with it: run <server> --tank \"<ice>\"\n"))

(deftest change1-tank-with-nothing-live-is-refused
  (do-game
    (new-game {:corp {:hand ["Hourglass"] :credits 20}
               :runner {:credits 10}})
    (play-from-hand state :corp "Hourglass" "HQ")
    (take-credits state :corp)
    (is (nil? (:run @state)) "fixture: no run")
    (let [{:keys [result sent out]} (as-seat! state "runner" (cmd-tank "Hourglass"))]
      (is (= {:status :error :reason :no-live-run} result))
      (is (empty? sent) (str "nothing sent: " (pr-str sent)))
      (is (= tank-refusal-out out))
      (is (nil? (:tank (seat-strategy "runner"))) (str "nothing recorded: " (pr-str (seat-strategy "runner")))))))

(deftest change1-run-tank-then-plain-continue-keeps-it
  (testing "`run hq --tank Hourglass --no-continue`, then plain `continue` to and at the encounter"
    (do-game
      (new-game {:corp {:hand ["Hourglass"] :credits 20}
                 :runner {:credits 10}})
      (play-from-hand state :corp "Hourglass" "HQ")
      (take-credits state :corp)
      (as-seat! state "runner" #(runs/run! "hq" "--tank" "Hourglass" "--no-continue"))
      (is (some? (:run @state)) "fixture: run! started the run")
      (let [trail (drive-to-encounter! state (cmd-continue))]
        (is (= :encounter-ice (get-in @state [:run :phase])) (str "fixture: at the encounter. trail " trail)))
      (is (= #{"Hourglass"} (:tank (seat-strategy "runner")))
          (str "the run's --tank survived plain `continue`. strategy now: " (pr-str (seat-strategy "runner"))))
      (let [{:keys [sent commands out]} (as-seat! state "runner" (cmd-continue))]
        (is (or (seq (tank-signals sent)) (corp-sees-signal? state "Hourglass"))
            (str "and the Corp is told. sent " commands "\n--- output ---\n" out))))))

(deftest change1-single-step-tank-is-stored
  (testing "`continue --single --tank Hourglass --full-break` at the unrezzed approach; Corp rezzes and passes; plain `continue`"
    (do-game
      (new-game {:corp {:hand ["Hourglass"] :credits 20}
                 :runner {:credits 10}})
      (play-from-hand state :corp "Hourglass" "HQ")
      (take-credits state :corp)
      (run-on state "HQ")
      (is (= :approach-ice (get-in @state [:run :phase])) "fixture: unrezzed approach")
      (as-seat! state "runner" (cmd-continue-single "--tank" "Hourglass" "--full-break"))
      (is (= {:tank #{"Hourglass"}} (seat-strategy "runner"))
          (str "only :tank is stored by a single step; --full-break stays one-call. strategy " (pr-str (seat-strategy "runner"))))
      (is (= :runner (get-in @state [:run :no-action])) "fixture: the single step was the approach pass")
      (core/process-action "rez" state :corp {:card (get-ice state :hq 0)})
      (engine-pass! state :corp)
      (is (= :encounter-ice (get-in @state [:run :phase])) "fixture: at the Hourglass encounter")
      (let [{:keys [commands out]} (as-seat! state "runner" (cmd-continue))]
        (is (corp-sees-signal? state "Hourglass")
            (str "plain `continue` signals the tank given on the single step. sent " commands "\n" out))))))

(deftest change1-mid-run-continue-tank-unions
  (testing "`tank Hourglass` mid-run, then `continue --tank \"Ice Wall\"`: both are held"
    (at-hourglass-encounter
     (fn [state]
       (as-seat! state "runner" (cmd-tank "Hourglass"))
       (as-seat! state "runner" (cmd-continue "--tank" "Ice Wall"))
       (is (= #{"Hourglass" "Ice Wall"} (:tank (seat-strategy "runner")))
           (str "strategy now: " (pr-str (seat-strategy "runner"))))))))

(deftest change1-a-new-run-after-a-tanked-run-starts-empty
  (do-game
    (new-game {:corp {:hand ["Hourglass"] :credits 20}
               :runner {:credits 10}})
    (play-from-hand state :corp "Hourglass" "HQ")
    (take-credits state :corp)
    (run-on state "HQ")
    (as-seat! state "runner" (cmd-tank "Hourglass"))
    (is (= #{"Hourglass"} (:tank (seat-strategy "runner"))) "fixture: tanked in run 1")
    (core/process-action "jack-out" state :runner nil)
    (is (nil? (:run @state)) "fixture: run 1 is over")
    (as-seat! state "runner" #(runs/run! "hq" "--no-continue"))
    (is (some? (:run @state)) "fixture: run 2 started")
    (is (nil? (:tank (seat-strategy "runner")))
        (str "run 2 starts with no tank. strategy now: " (pr-str (seat-strategy "runner"))))))

(deftest change1-re-signal-after-the-latch-resets-is-harmless
  ;; The plan asks for this measured, not assumed: plain `continue` resets the
  ;; tank-SIGNAL latch (signaled-fire-encounter) while :tank is kept. Is a second
  ;; signal, or a second look, harmful to the Corp's fire authorization?
  (testing "tank at the encounter, then plain `continue` (latch reset): no second signal; the Corp fires once"
    (at-hourglass-encounter
     (fn [state]
       (let [tank-obs (as-seat! state "runner" (cmd-tank "Hourglass"))]
         (is (= 1 (count (tank-signals (:sent tank-obs)))) "fixture: `tank` at the encounter signals once"))
       (let [again (as-seat! state "runner" (cmd-continue))]
         (is (empty? (tank-signals (:sent again)))
             (str "the log already carries a current signal, so the reset latch re-sends nothing. sent "
                  (:commands again))))
       (let [clicks-before (get-in @state [:runner :click])
             fire (as-seat! state "corp" (cmd-continue "--fire-unbroken"))]
         (is (= 1 (count (filter #{"unbroken-subroutines"} (:commands fire)))) (str "sent " (:commands fire)))
         (is (= (max 0 (- clicks-before 3)) (get-in @state [:runner :click])) "Hourglass resolved once")))))
  (testing "two signal lines in the log (what a re-send after a reset would leave): the Corp fires once"
    (at-hourglass-encounter
     (fn [state]
       (as-seat! state "runner" (cmd-tank "Hourglass"))
       (core/process-action "system-msg" state :runner
                            {:msg "indicates to fire all unbroken subroutines on Hourglass"})
       (is (= 2 (count (filter #(str/includes? (str (:text %)) "indicates to fire") (get-in (wire-state state "corp") [:game-state :log]))))
           "fixture: two signals")
       (is (= :runner-signaled (corp-decisions/fire-authorization (wire-state state "corp") "corp" "Hourglass"))
           "the Corp's authorization is unchanged by the duplicate")
       (let [clicks-before (get-in @state [:runner :click])
             fire (as-seat! state "corp" (cmd-continue "--fire-unbroken"))]
         (is (= 1 (count (filter #{"unbroken-subroutines"} (:commands fire)))) (str "sent " (:commands fire)))
         (is (= (max 0 (- clicks-before 3)) (get-in @state [:runner :click])) "Hourglass resolved once"))))))

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

;; ===========================================================================
;; Change 2 (plan v3): one gate, ai-run-pass/pass-priority!
;; ===========================================================================

(defn- counting-continue-run
  "Run `f` with runs/continue-run! wrapped to count its calls. Returns [n result]."
  [f]
  (let [n (atom 0)
        orig runs/continue-run!]
    (with-redefs [runs/continue-run! (fn [& args] (swap! n inc) (apply orig args))]
      (let [r (f)] [@n r]))))

(def ^:private formerly-red-blocked-states
  "Every state a cell above found a continue sent over the seat's own blocking
   prompt in, as [name side setup] where (setup f) builds it and calls (f state)
   with the seat's strategy in place."
  (into
   [["A1 runner unrezzed approach, lone-Done credit select" "runner"
     (fn [f] (runner-bad-pub-game false 1 (fn [state] (run-on state "HQ") (pump-on-bad-pub! state) (f state))))]]
   (concat
    (for [[ice-title install-idx] a2-shapes
          runner-done [:passed :tanked]]
      [(str "A2 " ice-title " install select, Runner " (name runner-done)) "corp"
       (fn [f] (install-sub-open ice-title install-idx runner-done f))])
    (for [{:keys [site side cell]} a3-cells
          :when (re-find #"\[A1\]|\[A2\]|rez-strategy" site)]
      [(str "A3 " site) side (fn [f] (cell true f))]))))

(def ^:private pass-claim
  "Every sender's \"I passed\" line (the a3-cells :line patterns, and their
   siblings). None may print over a pass that was refused."
  #"passing ICE|[Cc]ontinuing|[Dd]eclining|Passed the unrezzed|Auto-passing|self-advancing|did not take")

(deftest change2-formerly-red-cells-hand-back-on-the-first-tick
  (is (= 11 (count formerly-red-blocked-states)) "fixture: every formerly-red state is enumerated")
  (doseq [[nm side setup] formerly-red-blocked-states]
    (testing (str nm ": five single steps")
      (setup
       (fn [state]
         (let [msg (:msg (seat-prompt state side))
               steps (vec (repeatedly 5 #(as-seat! state side (cmd-continue-single))))]
           (is (empty? (mapcat (comp continues :sent) steps))
               (str "zero continues over five ticks. sent " (mapv :commands steps)))
           (is (every? #(= :decision-required (get-in % [:result :status])) steps)
               (str "every tick hands back a decision: " (mapv #(get-in % [:result :status]) steps)))
           (is (every? #(= msg (get-in % [:result :prompt :msg])) steps)
               (str "naming the seat's own prompt " (pr-str msg)))
           (is (not-any? #(re-find pass-claim (:out %)) steps)
               (str "and no line claims a pass that was refused:\n" (apply str (map :out steps))))))))
    (testing (str nm ": the run loop")
      (setup
       (fn [state]
         (let [msg (:msg (seat-prompt state side))
               [n obs] (counting-continue-run #(as-seat! state side (fn [] (runs/auto-continue-loop!))))]
           (is (empty? (continues (:sent obs))) (str "zero continues. sent " (:commands obs)))
           (is (= 1 n) (str "the loop returned on its FIRST tick; ticks " n))
           (is (= :decision-required (get-in obs [:result :status])) (str "result " (pr-str (:result obs))))
           (is (str/includes? (:out obs) (str "Prompt: " msg))
               (str "with the prompt named. output:\n" (:out obs)))
           (is (not (re-find pass-claim (:out obs)))
               (str "and no line claims a pass that was refused:\n" (:out obs)))))))))

(deftest change2-persistent-corp-loop-over-a-waiting-prompt-keeps-polling
  (testing "Funhouse on-encounter: the Runner chooses, the Corp holds a WAITING prompt; `monitor-run --persistent`"
    (do-game
      (new-game {:corp {:hand ["Funhouse"] :credits 20}
                 :runner {:credits 10}})
      (play-from-hand state :corp "Funhouse" "HQ")
      (rez state :corp (get-ice state :hq 0))
      (take-credits state :corp)
      (run-on state "HQ")
      (run-continue state :encounter-ice)
      (is (= "waiting" (:prompt-type (seat-prompt state "corp"))) "fixture: the Corp holds a waiting prompt")
      (is (= "Choose one" (:msg (seat-prompt state "runner"))) "fixture: the Runner owes the Funhouse choice")
      (let [[n obs] (counting-continue-run #(as-seat! state "corp" (cmd-continue "--persistent")))]
        (is (empty? (continues (:sent obs))) (str "no continue over the opponent's checkpoint. sent " (:commands obs)))
        (is (not= :decision-required (get-in obs [:result :status]))
            (str "not surfaced as the Corp's decision: " (pr-str (:result obs))))
        (is (> n 1) (str "the loop kept polling; ticks " n))))))

(deftest change2-already-passed-then-own-prompt-is-a-decision
  (testing "the Corp passed the encounter, THEN rezzed Malia (select open): pass-priority! surfaces the prompt"
    (do-game
      (new-game {:corp {:hand ["Hourglass" "Malia Z0L0K4"] :credits 30}
                 :runner {:hand ["Liberated Account"] :credits 10}})
      (play-from-hand state :corp "Hourglass" "HQ")
      (play-from-hand state :corp "Malia Z0L0K4" "New remote")
      (rez state :corp (get-ice state :hq 0))
      (take-credits state :corp)
      (play-from-hand state :runner "Liberated Account")
      (run-on state "HQ")
      (run-continue state :encounter-ice)
      (engine-pass! state :corp)
      (is (= :corp (:no-action (core/get-current-encounter state))) "fixture: the Corp's pass is on the ledger")
      (rez-malia! state)
      (let [{:keys [result sent]} (as-seat! state "corp" #(pass/pass-priority! gameid))]
        (is (empty? sent))
        (is (= :decision-required (:status result))
            (str "a decision, not waiting-for-opponent: " (pr-str result)))
        (is (= :awaiting-own-prompt (:action result)))))))

(defn- malia-at-hourglass
  "Hourglass encounter, the Runner's pass on the ledger, the Corp holding Malia's
   on-rez select."
  [f]
  (do-game
    (new-game {:corp {:hand ["Hourglass" "Malia Z0L0K4"] :credits 30}
               :runner {:hand ["Liberated Account"] :credits 10}})
    (play-from-hand state :corp "Hourglass" "HQ")
    (play-from-hand state :corp "Malia Z0L0K4" "New remote")
    (rez state :corp (get-ice state :hq 0))
    (take-credits state :corp)
    (play-from-hand state :runner "Liberated Account")
    (run-on state "HQ")
    (run-continue state :encounter-ice)
    (engine-pass! state :runner)
    (is (= :runner (:no-action (core/get-current-encounter state))) "fixture: the Runner's pass is on the ledger")
    (rez-malia! state)
    (f state)))

(deftest change2-automated-fire-over-own-prompt
  (doseq [[command f] [["continue --single --fire-unbroken" (cmd-continue-single "--fire-unbroken")]
                       ["monitor-run --fire-unbroken" (cmd-continue "--fire-unbroken")]]]
    (testing (str "Corp holds Malia's select, Runner passed, `" command "`")
      (malia-at-hourglass
       (fn [state]
         (let [clicks-before (get-in @state [:runner :click])
               {:keys [result commands out]} (as-seat! state "corp" f)]
           (is (not-any? #{"unbroken-subroutines"} commands)
               (str "no fire sent over the Corp's own prompt (the engine refuses it). sent " commands "\n" out))
           (is (nil? (:fired-at-encounter (seat-strategy "corp")))
               (str "and no fired-at latch. strategy " (pr-str (seat-strategy "corp"))))
           (is (= :decision-required (:status result)) (str "result " (pr-str result)))
           (is (= :awaiting-own-prompt (:action result)))
           (is (= clicks-before (get-in @state [:runner :click])) "fixture check: Hourglass did not fire")))))))

(deftest change2-automated-fire-over-own-waiting-prompt
  (testing "Funhouse on-encounter: the Runner owes its choice (Corp holds a WAITING prompt) and has signalled; Corp `continue --single --fire-unbroken`"
    (do-game
      (new-game {:corp {:hand ["Funhouse"] :credits 20}
                 :runner {:credits 10}})
      (play-from-hand state :corp "Funhouse" "HQ")
      (rez state :corp (get-ice state :hq 0))
      (take-credits state :corp)
      (run-on state "HQ")
      (run-continue state :encounter-ice)
      (core/process-action "system-msg" state :runner
                           {:msg "indicates to fire all unbroken subroutines on Funhouse"})
      (is (= "waiting" (:prompt-type (seat-prompt state "corp"))) "fixture: the Corp holds a waiting prompt")
      (is (seq (ai-core/fireable-subs (ai-core/encountered-ice (wire-state state "corp")))) "fixture: Funhouse has fireable subs")
      (is (= :runner-signaled (corp-decisions/fire-authorization (wire-state state "corp") "corp" "Funhouse"))
          "fixture: the fire is authorized")
      (let [{:keys [result commands out]} (as-seat! state "corp" (cmd-continue-single "--fire-unbroken"))]
        (is (not-any? #{"unbroken-subroutines"} commands)
            (str "no fire sent over a waiting prompt (the engine refuses it). sent " commands "\n" out))
        (is (nil? (:fired-at-encounter (seat-strategy "corp"))) (str "no latch. strategy " (pr-str (seat-strategy "corp"))))
        (is (= :waiting-for-opponent (:status result)) (str "non-terminal, as pass-priority!'s check 1: " (pr-str result)))
        (is (not (str/includes? out "firing")) (str "and not announced:\n" out))))))

(deftest change2-fire-refused-by-the-socket-is-not-latched
  (testing "Runner tanked Hourglass; the socket refuses the Corp's fire: no fired-at latch, so the next tick retries"
    (at-hourglass-encounter
     (fn [state]
       (as-seat! state "runner" (cmd-tank "Hourglass"))
       (is (corp-sees-signal? state "Hourglass") "fixture: the signal landed")
       (let [{:keys [result out]} (binding [*refused-commands* #{"unbroken-subroutines"}]
                                    (as-seat! state "corp" (cmd-continue-single "--fire-unbroken")))]
         (is (nil? (:fired-at-encounter (seat-strategy "corp")))
             (str "latched a fire the socket refused. result " (pr-str result) "\n" out))
         (is (not (str/includes? out "firing")) (str "nor announced it:\n" out))))))
  (testing "the same refusal under the run loop (`monitor-run --fire-unbroken`): the loop stops and says the send failed"
    (at-hourglass-encounter
     (fn [state]
       (as-seat! state "runner" (cmd-tank "Hourglass"))
       (let [{:keys [result out]} (binding [*refused-commands* #{"unbroken-subroutines"}]
                                    (as-seat! state "corp" (cmd-continue "--fire-unbroken")))]
         (is (= {:status :error :reason :send-failed} (select-keys result [:status :reason]))
             (str "the loop reports the failed send, not :stuck. result " (pr-str result) "\n" out))
         (is (str/includes? out "❌ ERROR: Failed to send") (str "and tells the seat:\n" out))
         (is (nil? (:fired-at-encounter (seat-strategy "corp")))))))))

;; ---------------------------------------------------------------------------
;; Structure: a client-sent continue lives only in the gate and force mode
;; ---------------------------------------------------------------------------

(def ^:private send-fns
  "Senders that take the command as their first argument."
  #{"send-action!" "safe-action!"})

(defn- continue-literal? [x]
  (or (= x "continue") (= x :continue) (= x 'continue)))

(defn- continue-send-sites
  "The places in `src` (Clojure source) that send a `continue` in one of the
   LITERAL spellings below, as the name of the enclosing top-level def. READ, not
   grepped: comments are gone and a docstring is a string, so prose that mentions
   a continue is not a site. What it sees: a map literal with :command
   \"continue\" (or :continue / 'continue) — the ws/send-message! payload as
   every sender has written it — and (send-action! \"continue\" …) /
   (safe-action! \"continue\" …). What it CANNOT see: a payload built at
   runtime, e.g. (assoc {:gameid gameid} :command \"continue\"), or a command
   held in a var or computed. It is not exhaustive and is not meant to be made
   so: it catches a copied old sender. The guard for behaviour is the A1/A2/A3 and
   change2-* matrix above, which drives every automated sender on engine-built
   states."
  [label src]
  (let [eof (Object.)
        rdr (rt/indexing-push-back-reader src)]
    (binding [*read-eval* false
              tr/*data-readers* (into {} (map (fn [[tag _]] [tag (fn [v] (tagged-literal tag v))]))
                                      clojure.core/*data-readers*)
              tr/*alias-map* (fn [a] (symbol (str "unresolved." a)))]
      (loop [sites []]
        (let [form (tr/read {:eof eof} rdr)]
          (if (identical? eof form)
            sites
            (let [def-name (when (and (seq? form) (symbol? (second form))) (str (second form)))
                  hit? (some (fn [x]
                               (or (and (map? x) (continue-literal? (:command x)))
                                   (and (seq? x) (symbol? (first x))
                                        (contains? send-fns (name (first x)))
                                        (continue-literal? (second x)))))
                             (tree-seq coll? seq form))]
              (recur (if hit? (conj sites [label def-name]) sites)))))))))

(defn- tree-continue-sites [overrides]
  (set (mapcat (fn [f]
                 (let [label (.getName f)]
                   (continue-send-sites label (get overrides label (slurp f)))))
               (->> (file-seq (io/file "dev/src/clj"))
                    (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))))))

(def ^:private allowed-continue-sites
  #{["ai_run_pass.clj" "pass-priority!"]
    ["ai_runs.clj" "handle-force-mode"]})

(deftest change2-no-literal-continue-sender-outside-the-gate-and-force-mode
  (is (= allowed-continue-sites (tree-continue-sites {})))
  (testing "mutant: a raw continue added to a handler is caught (and its removal is the green above)"
    (let [f (io/file "dev/src/clj/ai_run_corp_handlers.clj")
          mutant (str (slurp f)
                      "\n(defn mutant-handler [gameid] (ws/send-message! :game/action {:gameid gameid :command \"continue\" :args nil}))\n")]
      (is (= (conj allowed-continue-sites ["ai_run_corp_handlers.clj" "mutant-handler"])
             (tree-continue-sites {"ai_run_corp_handlers.clj" mutant})))))
  (testing "mutant: the send-action! spelling is caught too"
    (let [mutant "(ns x) (defn h [] (ws/send-action! \"continue\" nil))"]
      (is (= [["m.clj" "h"]] (continue-send-sites "m.clj" mutant)))))
  (testing "a docstring or comment that SAYS :command \"continue\" is not a site"
    (is (empty? (continue-send-sites "m.clj" "(ns x) (defn h \"sends {:command \\\"continue\\\"}\" [] nil) ;; {:command \"continue\"}")))))

