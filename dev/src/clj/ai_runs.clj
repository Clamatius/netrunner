(ns ai-runs
  "Run mechanics - initiation, automation, and state management"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-core :as core]
            [ai-prompts :as prompts]
            [ai-basic-actions :as basic]))

;; ============================================================================
;; Run Strategy State
;; ============================================================================

;; Atom holding current run strategy flags.
;; Reset when new run starts, inherited by continue-run calls.
;;
;; Structure:
;; {:full-break true/false      ; Runner: auto-break all ICE
;;  :no-rez true/false          ; Corp: don't rez anything
;;  :rez #{\"Ice Wall\" ...}    ; Corp: only rez these ICE names
;;  :fire-unbroken true/false   ; Corp: auto-fire unbroken subs
;;  :force true/false}          ; Bypass all smart checks
(defonce run-strategy (atom {}))

(defn reset-strategy!
  "Clear run strategy (call when run ends)"
  []
  (reset! run-strategy {}))

(defn set-strategy!
  "Merge new strategy flags into current strategy"
  [flags]
  (swap! run-strategy merge flags))

(defn get-strategy
  "Get current run strategy"
  []
  @run-strategy)

;; ============================================================================
;; Flag Parsing
;; ============================================================================

(defn parse-run-flags
  "Parse command-line style flags from arguments.
   Returns {:server \"HQ\" :flags {:full-break true :no-continue false ...}}

   Supported flags:
   --full-break      : Runner auto-breaks all ICE
   --no-rez          : Corp doesn't rez anything
   --rez <ice-name>  : Corp only rezzes specified ICE
   --fire-unbroken   : Corp auto-fires unbroken subs
   --no-continue     : Don't auto-continue after run start
   --force           : Bypass all smart checks (for continue-run)

   Usage:
   (parse-run-flags [\"hq\" \"--full-break\"])
   => {:server \"hq\" :flags {:full-break true}}

   (parse-run-flags [\"remote1\" \"--rez\" \"Ice Wall\" \"--fire-unbroken\"])
   => {:server \"remote1\" :flags {:rez #{\"Ice Wall\"} :fire-unbroken true}}"
  [args]
  (loop [remaining args
         server nil
         flags {}]
    (if (empty? remaining)
      {:server server :flags flags}
      (let [arg (first remaining)
            rest-args (rest remaining)]
        (cond
          ;; Server name (first non-flag arg)
          (and (nil? server) (not (clojure.string/starts-with? arg "--")))
          (recur rest-args arg flags)

          ;; Boolean flags
          (= arg "--full-break")
          (recur rest-args server (assoc flags :full-break true))

          (= arg "--no-rez")
          (recur rest-args server (assoc flags :no-rez true))

          (= arg "--fire-unbroken")
          (recur rest-args server (assoc flags :fire-unbroken true))

          (= arg "--no-continue")
          (recur rest-args server (assoc flags :no-continue true))

          (= arg "--force")
          (recur rest-args server (assoc flags :force true))

          ;; --rez <ice-name> (takes argument)
          (= arg "--rez")
          (if (empty? rest-args)
            (do
              (println "⚠️  --rez requires ICE name argument")
              (recur rest-args server flags))
            (let [ice-name (first rest-args)
                  current-rez-set (get flags :rez #{})]
              (recur (rest rest-args)
                     server
                     (assoc flags :rez (conj current-rez-set ice-name)))))

          ;; Unknown flag
          (clojure.string/starts-with? arg "--")
          (do
            (println (format "⚠️  Unknown flag: %s" arg))
            (recur rest-args server flags))

          ;; Extra positional arg (error)
          :else
          (do
            (println (format "⚠️  Unexpected argument: %s (server already set to %s)" arg server))
            (recur rest-args server flags)))))))

;; ============================================================================
;; Forward Declarations
;; ============================================================================

(declare continue-run!)
(declare auto-continue-loop!)

;; ============================================================================
;; Run Initiation
;; ============================================================================

(defn run!
  "Run on a server with optional strategy flags (Runner only).
   Auto-starts turn if needed (opponent has ended and we haven't started yet).
   Accepts flexible server names and normalizes them automatically.
   By default, auto-continues run until a decision is needed.

   Central servers (case-insensitive):
   - hq, HQ → HQ
   - rd, r&d, R&D → R&D
   - archives → Archives

   Remote servers (flexible formats):
   - remote1, remote 1, r1, server1, server 1 → Server 1
   - remote2, r2, server2 → Server 2

   Strategy flags:
   --full-break      : Runner auto-breaks all ICE (no pauses for break decisions)
   --no-rez          : Corp doesn't rez anything (auto-declines all rez opportunities)
   --rez <ice-name>  : Corp only rezzes specified ICE, declines others
   --fire-unbroken   : Corp auto-fires all unbroken subroutines
   --no-continue     : Don't auto-continue after run initiation (stop at first decision)

   Usage:
   (run! \"hq\")                        ; Auto-continues till decision needed
   (run! \"remote1\" \"--full-break\")   ; Auto-breaks all ICE
   (run! \"hq\" \"--no-continue\")       ; Stop after initiation (rare)
   (run! \"remote1\" \"--rez\" \"Ice Wall\") ; Corp only rezzes Ice Wall"
  [& args]
  (if (basic/ensure-turn-started!)
    (let [{:keys [server flags]} (parse-run-flags args)
          _ (when (nil? server)
              (throw (ex-info "No server specified" {:args args})))
          client-state @state/client-state
          gameid (:gameid client-state)
          initial-log-size (count (get-in @state/client-state [:game-state :log]))
          {:keys [normalized original changed?]} (core/normalize-server-name server)]

      ;; Reset and set strategy for this run
      (reset-strategy!)
      (set-strategy! (dissoc flags :no-continue))  ; Store all except :no-continue

      ;; Provide feedback if we normalized the input
      (when changed?
        (println (format "💡 Normalized '%s' → '%s'" original normalized)))

      ;; Show active strategy flags
      (when (seq (dissoc flags :no-continue))
        (println (format "🎯 Strategy: %s"
                        (clojure.string/join ", "
                                           (map (fn [[k v]]
                                                  (if (set? v)
                                                    (str (name k) " " (clojure.string/join "," v))
                                                    (name k)))
                                                (dissoc flags :no-continue))))))

      (ws/send-message! :game/action
                        {:gameid gameid
                         :command "run"
                         :args {:server normalized}})

      ;; Wait for "make a run on" log entry and echo it
      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (let [log (get-in @state/client-state [:game-state :log])
                new-entries (drop initial-log-size log)
                run-entry (first (filter #(clojure.string/includes? (:text %) "make a run on")
                                         new-entries))]
            (cond
              run-entry
              (do
                (println "🏃" (:text run-entry))
                ;; Auto-continue unless --no-continue flag set
                (if (:no-continue flags)
                  {:status :success
                   :data {:server normalized :log-entry (:text run-entry) :flags flags}}
                  (do
                    (println "⏩ Auto-continuing run...")
                    (Thread/sleep core/quick-delay)  ; Brief pause for state sync
                    (let [loop-result (auto-continue-loop!)]
                      (merge {:status :success
                              :data {:server normalized :log-entry (:text run-entry) :flags flags}}
                             {:run-result loop-result})))))

              (< (System/currentTimeMillis) deadline)
              (do
                (Thread/sleep core/polling-delay)
                (recur))

              :else
              (do
                (println "⚠️  Run command sent but no log confirmation (may have failed)")
                {:status :error
                 :reason "Run command sent but no log confirmation"}))))))
    {:status :error
     :reason "Failed to start turn"}))

;; ============================================================================
;; Continue-Run Helper Functions (Bug #12 Fix)
;; ============================================================================

(defn get-current-ice
  "Get the ICE being approached/encountered from game state.
   Position counts from server outward (1 = outermost ICE).
   ICE list is indexed from innermost (0) to outermost.
   So ice-index = (count - position)."
  [state]
  (let [run (get-in state [:game-state :run])
        server (:server run)
        position (:position run)
        ice-list (get-in state [:game-state :corp :servers (keyword (last server)) :ices])
        ice-count (count ice-list)
        ice-index (- ice-count position)]  ; Convert position to array index
    (when (and ice-list (> position 0) (<= position ice-count))
      (nth ice-list ice-index))))

(defn get-rez-event
  "Find first rez event in log entries, or nil if none"
  [log-entries]
  (first (filter #(clojure.string/includes? (:text %) "rez") log-entries)))

(defn opponent-indicated-action?
  "Check if opponent pressed indicate-action (WAIT button) in recent log"
  [state side]
  (let [log (get-in state [:game-state :log])
        opp-side (core/other-side side)
        opp-name (clojure.string/capitalize opp-side)
        ;; Look for "[!] Please pause, {Opponent} is acting."
        indicate-pattern (str "[!] Please pause, " opp-name " is acting.")]
    (some #(= (:text %) indicate-pattern) (take 5 log))))

(defn has-real-decision?
  "True if prompt has 2+ meaningful choices (not just Done/Continue)"
  [prompt]
  (when prompt
    (let [choices (:choices prompt)
          non-trivial (remove (fn [choice]
                               (let [value (clojure.string/lower-case (:value choice ""))]
                                 (or (= value "continue")
                                     (= value "done")
                                     (= value "ok")
                                     (= value ""))))
                             choices)]
      (>= (count non-trivial) 2))))

(defn corp-has-rez-opportunity?
  "True if corp is at a rez decision point (approach-ice with unrezzed ice)"
  [state]
  (let [run-phase (get-in state [:game-state :run :phase])
        corp-prompt (get-in state [:game-state :corp :prompt-state])
        current-ice (get-current-ice state)]

    (or
      ;; Approaching unrezzed ICE - ALWAYS a rez opportunity
      (and (= run-phase :approach-ice)
           current-ice
           (not (:rezzed current-ice))
           corp-prompt)

      ;; Corp has explicit rez choices (upgrade/asset rez)
      (when corp-prompt
        (let [choices (:choices corp-prompt)]
          (some #(clojure.string/includes? (:value % "") "Rez") choices))))))

(defn waiting-for-opponent?
  "True if my side is waiting for opponent to make a decision"
  [state side]
  (let [run-phase (get-in state [:game-state :run :phase])
        my-prompt (get-in state [:game-state (keyword side) :prompt-state])
        opp-side (core/other-side side)
        opp-prompt (get-in state [:game-state (keyword opp-side) :prompt-state])
        during-run? (some? run-phase)]

    (cond
      ;; CRITICAL: Opponent pressed WAIT button - ALWAYS pause
      (opponent-indicated-action? state side)
      true

      ;; Runner waiting for corp rez decision
      (and (= side "runner")
           (= run-phase :approach-ice)
           (not my-prompt)  ; Runner has no prompt
           (corp-has-rez-opportunity? state))
      true

      ;; Corp waiting for runner break decision
      (and (= side "corp")
           (= run-phase :encounter-ice)
           (not my-prompt)
           (has-real-decision? opp-prompt))
      true

      ;; During run, only wait if opponent has a REAL decision
      ;; (not just empty paid ability windows that they'll auto-pass)
      ;; The critical phases (approach-ice rez, encounter-ice break) are handled above
      (and during-run?
           opp-prompt
           (has-real-decision? opp-prompt)
           (not my-prompt))
      true

      ;; Generally waiting if opponent has real decision and I don't
      (and opp-prompt
           (has-real-decision? opp-prompt)
           (not my-prompt))
      true

      :else
      false)))

(defn waiting-reason
  "Returns human-readable reason for waiting"
  [state side]
  (let [run-phase (get-in state [:game-state :run :phase])
        current-ice (get-current-ice state)]

    (cond
      (and (= side "runner") (= run-phase :approach-ice) current-ice)
      (str "Corp must decide: rez " (:title current-ice) " or continue")

      (and (= side "corp") (= run-phase :encounter-ice))
      "Runner must decide: break subroutines or take effects"

      :else
      "Waiting for opponent action")))

(defn can-auto-continue?
  "True if can safely auto-continue (empty paid ability window, no decisions)"
  [prompt run-phase]
  (and prompt
       (= (:prompt-type prompt) "run")
       (empty? (:choices prompt))
       (empty? (:selectable prompt))
       ;; Not a special phase that needs attention
       (not (contains? #{:approach-ice :encounter-ice} run-phase))))

;; ============================================================================
;; Handler Functions for continue-run! Strategy Pattern
;; ============================================================================
;;
;; Each handler examines the context and either:
;; - Returns nil (not handled, try next handler)
;; - Returns a result map {:status ... :action ... ...}
;;
;; Handlers are tried in priority order until one returns non-nil.

(defn- send-continue!
  "Helper to send continue command and return action-taken result"
  [gameid]
  (ws/send-message! :game/action
                   {:gameid gameid
                    :command "continue"
                    :args nil})
  {:status :action-taken
   :action :sent-continue})

(defn- send-choice!
  "Helper to send choice command and return action-taken result"
  [gameid choice-uuid choice-value]
  (ws/send-message! :game/action
                   {:gameid gameid
                    :command "choice"
                    :args {:choice {:uuid choice-uuid}}})
  {:status :action-taken
   :action :auto-choice
   :choice choice-value})

(defn handle-force-mode
  "Priority 0: --force flag bypasses ALL checks"
  [{:keys [strategy gameid]}]
  (when (:force strategy)
    (println "⚡ FORCE mode - bypassing all checks, sending continue")
    (ws/send-message! :game/action
                     {:gameid gameid
                      :command "continue"
                      :args nil})
    {:status :action-taken
     :action :forced-continue}))

(defn handle-opponent-wait
  "Priority 1: Opponent pressed WAIT button (indicate-action)"
  [{:keys [state side opp-side]}]
  (when (opponent-indicated-action? state side)
    (println "⏸️  PAUSED - Opponent pressed WAIT button")
    {:status :waiting-for-opponent
     :message (str (clojure.string/capitalize opp-side) " pressed WAIT - please pause")}))

(defn handle-run-complete
  "Priority 7: Run complete (no run phase, no prompt)"
  [{:keys [run-phase my-prompt]}]
  (when (and (nil? run-phase) (nil? my-prompt))
    (println "✅ Run complete")
    {:status :run-complete}))

(defn handle-no-run
  "Priority 8: No active run"
  [{:keys [run-phase my-prompt]}]
  (when (and (nil? run-phase)
             (or (nil? my-prompt)
                 (not= (:prompt-type my-prompt) "run")))
    (println "⚠️  No active run detected")
    {:status :no-run}))

(defn handle-auto-choice
  "Priority 5: Auto-handle single mandatory choice"
  [{:keys [my-prompt gameid]}]
  (when (and my-prompt
             (seq (:choices my-prompt))
             (= 1 (count (:choices my-prompt))))
    (let [choice (first (:choices my-prompt))
          choice-uuid (:uuid choice)]
      (println (format "   Auto-choosing: %s" (:value choice)))
      (send-choice! gameid choice-uuid (:value choice)))))

(defn handle-auto-continue
  "Priority 6: Auto-continue through boring paid ability windows"
  [{:keys [my-prompt run-phase gameid]}]
  (when (can-auto-continue? my-prompt run-phase)
    (println "   → Auto-continuing through paid ability window")
    (send-continue! gameid)))

(defn handle-real-decision
  "Priority 3: I have a real decision to make"
  [{:keys [my-prompt]}]
  (when (has-real-decision? my-prompt)
    (println "🛑 Run paused - decision required")
    (println (format "   Prompt: %s" (:msg my-prompt)))
    (when-let [card-title (get-in my-prompt [:card :title])]
      (println (format "   Card: %s" card-title)))
    (let [choices (:choices my-prompt)]
      (println (format "   Choices: %d options" (count choices)))
      (doseq [[idx choice] (map-indexed vector choices)]
        (println (format "     %d. %s" idx (:value choice)))))
    {:status :decision-required
     :prompt my-prompt}))

(defn handle-waiting-for-opponent
  "Priority 3: Waiting for opponent to make a decision"
  [{:keys [state side]}]
  (when (waiting-for-opponent? state side)
    (let [reason (waiting-reason state side)]
      (println (format "⏸️  Waiting for opponent: %s" reason))
      {:status :waiting-for-opponent
       :message reason})))

(defn handle-corp-rez-strategy
  "Priority 1.5: Corp rez strategy - auto-handle rez decisions"
  [{:keys [side run-phase my-prompt strategy state gameid]}]
  (when (and (= side "corp")
             (= run-phase "approach-ice")
             my-prompt
             (or (:no-rez strategy) (:rez strategy)))
    (let [run (get-in state [:game-state :run])
          server (:server run)
          position (:position run)
          ice-list (get-in state [:game-state :corp :servers (keyword (last server)) :ices])
          ice-count (count ice-list)
          ice-index (- ice-count position)
          current-ice (when (and ice-list (pos? ice-count) (> position 0) (<= ice-index (dec ice-count)))
                        (nth ice-list ice-index nil))
          ice-title (:title current-ice "ICE")
          ice-rezzed? (:rezzed current-ice)
          should-rez? (and (not (:no-rez strategy))
                          (:rez strategy)
                          (contains? (:rez strategy) ice-title)
                          (not ice-rezzed?))]
      (cond
        ;; --no-rez: always decline
        (:no-rez strategy)
        (do
          (println (format "🤖 Strategy: --no-rez, declining %s" ice-title))
          (merge (send-continue! gameid)
                 {:action :auto-declined-rez
                  :ice ice-title}))

        ;; --rez <ice-name>: rez if in set, decline otherwise
        should-rez?
        (do
          (println (format "🤖 Strategy: --rez, rezzing %s" ice-title))
          (if current-ice
            (let [card-ref (core/create-card-ref current-ice)]
              (ws/send-message! :game/action
                               {:gameid gameid
                                :command "rez"
                                :args {:card card-ref}})
              {:status :action-taken
               :action :auto-rezzed
               :ice ice-title})
            (do
              (println (format "⚠️  Could not find ICE to rez: %s" ice-title))
              {:status :decision-required
               :prompt my-prompt})))

        ;; --rez set exists but this ICE is already rezzed: just continue
        (and (:rez strategy) ice-rezzed?)
        (do
          (println (format "   → ICE %s already rezzed, continuing" ice-title))
          (send-continue! gameid))

        ;; --rez set exists but this ICE not in it: decline
        :else
        (do
          (println (format "🤖 Strategy: --rez (not %s), declining" ice-title))
          (merge (send-continue! gameid)
                 {:action :auto-declined-rez
                  :ice ice-title}))))))

(defn handle-corp-fire-unbroken
  "Priority 1.6: Corp fire-unbroken strategy - auto-fire unbroken subs"
  [{:keys [side run-phase my-prompt strategy state gameid]}]
  (when (and (= side "corp")
             (= run-phase "encounter-ice")
             my-prompt
             (:fire-unbroken strategy))
    (let [run (get-in state [:game-state :run])
          server (:server run)
          position (:position run)
          ice-list (get-in state [:game-state :corp :servers (keyword (last server)) :ices])
          ice-count (count ice-list)
          ice-index (- ice-count position)
          current-ice (when (and ice-list (pos? ice-count) (> position 0) (<= ice-index (dec ice-count)))
                        (nth ice-list ice-index nil))
          ice-title (:title current-ice "ICE")]
      (if current-ice
        (do
          (println (format "🤖 Strategy: --fire-unbroken, firing subs on %s" ice-title))
          (let [card-ref (core/create-card-ref current-ice)]
            (ws/send-message! :game/action
                             {:gameid gameid
                              :command "unbroken-subroutines"
                              :args {:card card-ref}})
            {:status :action-taken
             :action :auto-fired-subs
             :ice ice-title}))
        (do
          (println "⚠️  Could not find ICE for fire-unbroken")
          {:status :decision-required
           :prompt my-prompt})))))

(defn handle-runner-approach-ice
  "Priority 2: Runner waiting for corp rez decision at approach-ice with unrezzed ICE"
  [{:keys [side run-phase state]}]
  (when (and (= side "runner")
             (= run-phase "approach-ice")
             (let [run (get-in state [:game-state :run])
                   server (:server run)
                   position (:position run)
                   ice-list (get-in state [:game-state :corp :servers (keyword (last server)) :ices])
                   ice-count (count ice-list)
                   ice-index (- ice-count position)
                   current-ice (when (and ice-list (pos? ice-count) (> position 0) (<= ice-index (dec ice-count)))
                                 (nth ice-list ice-index nil))
                   no-action (:no-action run)
                   corp-already-declined? (= no-action "corp")]
               (and current-ice (not (:rezzed current-ice)) (not corp-already-declined?))))
    (let [run (get-in state [:game-state :run])
          server (:server run)
          position (:position run)
          ice-list (get-in state [:game-state :corp :servers (keyword (last server)) :ices])
          ice-count (count ice-list)
          ice-index (- ice-count position)
          current-ice (when (and ice-list (pos? ice-count) (> position 0) (<= ice-index (dec ice-count)))
                        (nth ice-list ice-index nil))
          ice-title (:title current-ice "ICE")]
      (println "⏸️  PAUSED at approach-ice - Waiting for corp rez decision")
      (println (format "   ICE position: %d/%d (unrezzed)" position ice-count))
      (println (format "   ICE: %s" ice-title))
      (println "   ⚠️  Runner prompt says 'Continue to Movement' but that would bypass corp rez!")
      (println "   → Waiting for corp to rez or continue")
      {:status :waiting-for-corp-rez
       :message (format "Waiting for corp to decide: rez %s or continue" ice-title)
       :ice ice-title
       :position position})))

(defn handle-events
  "Priority 4: Pause for important events (rez, abilities, subs, damage)"
  [{:keys [rez-event ability-event fired-event tag-damage-event]}]
  (cond
    rez-event
    (do
      (println "⚠️  Run paused - ICE rezzed!")
      (println (format "   %s" (:text rez-event)))
      (println "   → Use 'continue-run' again to proceed")
      {:status :ice-rezzed :event rez-event})

    ability-event
    (do
      (println "⚠️  Run paused - ability triggered!")
      (println (format "   %s" (:text ability-event)))
      (println "   → Use 'continue-run' again to proceed")
      {:status :ability-used :event ability-event})

    fired-event
    (do
      (println "⚠️  Run paused - subroutines fired!")
      (println (format "   %s" (:text fired-event)))
      (println "   → Use 'continue-run' again to proceed")
      {:status :subs-fired :event fired-event})

    tag-damage-event
    (do
      (println "⚠️  Run paused - tag or damage!")
      (println (format "   %s" (:text tag-damage-event)))
      (println "   → Use 'continue-run' again to proceed")
      {:status :tag-or-damage :event tag-damage-event})))

(defn handle-unexpected-state
  "Fallback: Unknown state"
  [{:keys [side run-phase my-prompt opp-prompt]}]
  (println "⚠️  Unexpected run state")
  (println (format "   Side: %s" side))
  (println (format "   Run phase: %s" run-phase))
  (println (format "   My prompt type: %s" (:prompt-type my-prompt)))
  (println (format "   My choices: %d" (count (:choices my-prompt))))
  (println (format "   Opp has prompt: %s" (some? opp-prompt)))
  {:status :unexpected-state
   :prompt my-prompt
   :phase run-phase})

(defn run-first-matching-handler
  "Run handlers in order until one returns non-nil result"
  [handlers context]
  (loop [remaining handlers]
    (if-let [handler (first remaining)]
      (if-let [result (handler context)]
        result
        (recur (rest remaining)))
      ;; No handler matched - shouldn't happen, but return unexpected-state as fallback
      (handle-unexpected-state context))))

(defn continue-run!
  "Stateless run handler - examines current state, takes ONE action, returns.
   Call repeatedly until run completes or decision required.
   Now supports strategy flags via run strategy state.

   STATELESS DESIGN: No recursion, no local state. Uses game state as source of truth.
   Each call examines current state and either:
   - Sends ONE continue command and returns :action-taken
   - Returns :waiting-for-opponent (pause, wait for opp)
   - Returns :decision-required (pause, user must decide)
   - Returns :run-complete (all done)

   Strategy flags (from run! or passed directly):
   --full-break      : Runner auto-breaks all ICE
   --no-rez          : Corp auto-declines all rez opportunities
   --rez <ice-name>  : Corp only rezzes specified ICE
   --fire-unbroken   : Corp auto-fires unbroken subs
   --force           : Bypass ALL smart checks, just send continue

   🛑 MUST PAUSE (requires decision):
   - Opponent pressed WAIT/indicate-action
   - Corp has rez opportunity (approach-ice with unrezzed ICE) [unless --no-rez/--rez]
   - Runner has 2+ real choices (not just Continue/Done) [unless --full-break]
   - Waiting for opponent's decision during run

   ⚠️ WANT to PAUSE (important events):
   - ICE rezzed (show cost and card)
   - Abilities triggered during run
   - Subroutines fired
   - Tags/damage dealt

   ✅ AUTO-CONTINUE (boring):
   - Empty paid ability windows (no choices, no selectables)
   - Not in special phases (approach-ice, encounter-ice)

   Returns:
     {:status :action-taken :action :sent-continue}  - Sent continue, call again
     {:status :waiting-for-opponent :message ...}     - Paused, wait for opp
     {:status :decision-required :prompt ...}         - Paused, user must decide
     {:status :ice-rezzed :event ...}                 - Paused, show rez event
     {:status :ability-used :event ...}               - Paused, show ability
     {:status :subs-fired :event ...}                 - Paused, show subs
     {:status :tag-or-damage :event ...}              - Paused, show tag/damage
     {:status :run-complete}                          - Run finished
     {:status :no-run}                                - No active run

   Usage:
     (continue-run!)  ; Take one step
     (continue-run! \"--force\")  ; Bypass all checks (old continue behavior)
     (continue-run! \"--no-rez\")  ; Auto-decline all rez"
  [& args]
  (let [;; Parse flags if provided, merge with run strategy
        {:keys [flags]} (if (seq args) (parse-run-flags (vec args)) {:flags {}})
        strategy (merge (get-strategy) flags)

        client-state @state/client-state
        side (:side client-state)
        gameid (:gameid client-state)
        run-phase (get-in client-state [:game-state :run :phase])
        my-prompt (get-in client-state [:game-state (keyword side) :prompt-state])
        opp-side (core/other-side side)
        opp-prompt (get-in client-state [:game-state (keyword opp-side) :prompt-state])
        log (get-in client-state [:game-state :log])

        ;; Check for new events in recent log (last 3 entries)
        recent-log (take 3 log)
        rez-event (get-rez-event recent-log)
        ability-event (first (filter #(or (clojure.string/includes? (:text %) "uses")
                                          (clojure.string/includes? (:text %) "triggers"))
                                    recent-log))
        fired-event (first (filter #(clojure.string/includes? (:text %) "fire") recent-log))
        tag-damage-event (first (filter #(or (clojure.string/includes? (:text %) "tag")
                                             (clojure.string/includes? (:text %) "damage"))
                                       recent-log))

        ;; Build context map for handlers
        context {:strategy strategy
                 :state client-state
                 :side side
                 :gameid gameid
                 :run-phase run-phase
                 :my-prompt my-prompt
                 :opp-side opp-side
                 :opp-prompt opp-prompt
                 :log log
                 :rez-event rez-event
                 :ability-event ability-event
                 :fired-event fired-event
                 :tag-damage-event tag-damage-event}

        ;; Handler chain in priority order
        handlers [handle-force-mode
                  handle-opponent-wait
                  handle-corp-rez-strategy
                  handle-corp-fire-unbroken
                  handle-runner-approach-ice
                  handle-waiting-for-opponent
                  handle-real-decision
                  handle-events
                  handle-auto-choice
                  handle-auto-continue
                  handle-run-complete
                  handle-no-run]]

    ;; Run handlers in order until one returns non-nil
    (run-first-matching-handler handlers context)))

;; ============================================================================
;; Auto-Continue Loop
;; ============================================================================
;;
;; The loop calls continue-run! repeatedly until:
;; - Run completes (:run-complete)
;; - Real decision required (:decision-required)
;; - Notable event occurs (:ice-rezzed, :ability-used, :subs-fired, :tag-or-damage)
;; - Max iterations reached (safety guard)
;; - Timeout reached
;;
;; For :waiting-for-opponent status, the loop waits briefly then retries,
;; allowing the other client to take their action.

(defn- terminal-status?
  "Returns true if this status should stop the auto-continue loop"
  [status]
  (contains? #{:decision-required :ice-rezzed :ability-used :subs-fired
               :tag-or-damage :run-complete :no-run :unexpected-state
               :waiting-for-corp-rez}
             status))

(defn- should-pause-for-event?
  "Returns true if this is a notable event we should pause to show the user"
  [status]
  (contains? #{:ice-rezzed :ability-used :subs-fired :tag-or-damage} status))

(defn auto-continue-loop!
  "Runs continue-run! in a loop until run ends or decision required.

   This is the core of run automation - both sides can call this to
   auto-pass through boring paid ability windows.

   Loop continues on:
   - :action-taken - took an action, might need more
   - :waiting-for-opponent - wait briefly, then check again

   Loop stops on:
   - :decision-required - user must make a choice
   - :ice-rezzed, :ability-used, :subs-fired, :tag-or-damage - notable events
   - :run-complete - run finished successfully
   - :no-run - no active run
   - :waiting-for-corp-rez - runner waiting for corp (corp should call their loop)
   - max iterations or timeout reached

   Options:
   :max-iterations  - Safety guard (default 50)
   :timeout-ms      - Max time to loop (default 30000ms = 30s)
   :wait-delay-ms   - Delay when waiting for opponent (default 200ms)
   :pause-on-events - Pause on events like :ice-rezzed (default true)

   Returns the final result from continue-run! plus:
   :iterations - how many times continue-run! was called
   :elapsed-ms - how long the loop ran"
  [& {:keys [max-iterations timeout-ms wait-delay-ms pause-on-events]
      :or {max-iterations 50
           timeout-ms 30000
           wait-delay-ms 200
           pause-on-events true}}]
  (let [start-time (System/currentTimeMillis)
        deadline (+ start-time timeout-ms)]
    (loop [iteration 0]
      (cond
        ;; Safety: max iterations
        (>= iteration max-iterations)
        (do
          (println (format "⚠️  Auto-continue stopped: max iterations (%d) reached" max-iterations))
          {:status :max-iterations
           :iterations iteration
           :elapsed-ms (- (System/currentTimeMillis) start-time)})

        ;; Safety: timeout
        (> (System/currentTimeMillis) deadline)
        (do
          (println (format "⚠️  Auto-continue stopped: timeout (%dms) reached" timeout-ms))
          {:status :timeout
           :iterations iteration
           :elapsed-ms (- (System/currentTimeMillis) start-time)})

        :else
        (let [result (continue-run!)
              status (:status result)]
          (cond
            ;; Terminal status - stop loop
            (terminal-status? status)
            (assoc result
                   :iterations (inc iteration)
                   :elapsed-ms (- (System/currentTimeMillis) start-time))

            ;; Waiting for opponent - brief pause then check again
            (= status :waiting-for-opponent)
            (do
              (Thread/sleep wait-delay-ms)
              (recur (inc iteration)))

            ;; Action taken - immediately continue
            (= status :action-taken)
            (do
              (Thread/sleep core/quick-delay)  ; Brief sync pause
              (recur (inc iteration)))

            ;; Unknown status - treat as terminal
            :else
            (do
              (println (format "⚠️  Auto-continue: unknown status %s, stopping" status))
              (assoc result
                     :iterations (inc iteration)
                     :elapsed-ms (- (System/currentTimeMillis) start-time)))))))))

(defn monitor-run!
  "Corp command to enter auto-continue mode during a run.

   When runner initiates a run, corp can call this to auto-handle
   boring paid ability windows. The loop will pause when:
   - Corp has a real decision (rez opportunity, ability choice)
   - Notable events occur
   - Run ends

   This enables the 'both sides auto-pass' flow where neither player
   has to manually pass empty windows.

   Usage:
     (monitor-run!)                    ; Auto-pass until decision needed
     (monitor-run! \"--no-rez\")       ; Also auto-decline all rez opportunities
     (monitor-run! \"--rez\" \"Tithe\") ; Only rez Tithe, decline others"
  [& args]
  (let [run (get-in @state/client-state [:game-state :run])]
    (if (nil? run)
      (do
        (println "⚠️  No active run to monitor")
        {:status :no-run})
      (do
        ;; Parse and set strategy flags if provided
        (when (seq args)
          (let [{:keys [flags]} (parse-run-flags (vec args))]
            (set-strategy! flags)
            (when (seq flags)
              (println (format "🎯 Strategy: %s"
                              (clojure.string/join ", "
                                                   (map (fn [[k v]]
                                                          (if (set? v)
                                                            (str (name k) " " (clojure.string/join "," v))
                                                            (name k)))
                                                        flags)))))))
        (println "👁️  Monitoring run... (auto-passing boring windows)")
        (auto-continue-loop!)))))

;; ============================================================================
;; Convenience Wrapper
;; ============================================================================

(defn continue!
  "Alias for continue-run with --force flag.
   Bypasses all smart checks and just sends continue command.
   Use for manual control when you know what you're doing.

   This is the old 'continue' primitive behavior - passes priority immediately
   without checking for decisions, opponent actions, or important events.

   Usage:
     (continue!)  ; Just send continue, no checks"
  []
  (continue-run! "--force"))
