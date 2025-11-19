(ns ai-runs
  "Run mechanics - initiation, automation, and state management"
  (:require [ai-websocket-client-v2 :as ws]
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
          state @ws/client-state
          gameid (:gameid state)
          initial-log-size (count (get-in @ws/client-state [:game-state :log]))
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
                        {:gameid (if (string? gameid)
                                  (java.util.UUID/fromString gameid)
                                  gameid)
                         :command "run"
                         :args {:server normalized}})

      ;; Wait for "make a run on" log entry and echo it
      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (let [log (get-in @ws/client-state [:game-state :log])
                new-entries (drop initial-log-size log)
                run-entry (first (filter #(clojure.string/includes? (:text %) "make a run on")
                                         new-entries))]
            (cond
              run-entry
              (do
                (println "🏃" (:text run-entry))
                ;; Auto-continue unless --no-continue flag set
                (when-not (:no-continue flags)
                  (println "⏩ Auto-continuing...")
                  (Thread/sleep 500)  ; Brief pause for state sync
                  (continue-run!))
                {:status :success
                 :data {:server normalized :log-entry (:text run-entry) :flags flags}})

              (< (System/currentTimeMillis) deadline)
              (do
                (Thread/sleep 200)
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

      ;; CRITICAL: During run, if opponent has ANY prompt, pause and wait
      ;; Opponent may have indicated action or may have paid ability they want to use
      (and during-run?
           opp-prompt
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

        state @ws/client-state
        side (:side state)
        gameid (:gameid state)
        run-phase (get-in state [:game-state :run :phase])
        my-prompt (get-in state [:game-state (keyword side) :prompt-state])
        opp-side (core/other-side side)
        opp-prompt (get-in state [:game-state (keyword opp-side) :prompt-state])
        log (get-in state [:game-state :log])

        ;; Check for new events in recent log (last 3 entries)
        recent-log (take 3 log)
        rez-event (get-rez-event recent-log)
        ability-event (first (filter #(or (clojure.string/includes? (:text %) "uses")
                                          (clojure.string/includes? (:text %) "triggers"))
                                    recent-log))
        fired-event (first (filter #(clojure.string/includes? (:text %) "fire") recent-log))
        tag-damage-event (first (filter #(or (clojure.string/includes? (:text %) "tag")
                                             (clojure.string/includes? (:text %) "damage"))
                                       recent-log))]

    (cond
      ;; Priority 0: --force flag bypasses ALL checks
      (:force strategy)
      (do
        (println "⚡ FORCE mode - bypassing all checks, sending continue")
        (ws/send-message! :game/action
                         {:gameid (if (string? gameid)
                                   (java.util.UUID/fromString gameid)
                                   gameid)
                          :command "continue"
                          :args nil})
        {:status :action-taken
         :action :forced-continue})
      ;; Priority 1: Opponent pressed WAIT button (indicate-action)
      (opponent-indicated-action? state side)
      (do
        (println "⏸️  PAUSED - Opponent pressed WAIT button")
        {:status :waiting-for-opponent
         :message (str (clojure.string/capitalize opp-side) " pressed WAIT - please pause")})

      ;; Priority 1.5: Corp rez strategy - auto-handle rez decisions
      (and (= side "corp")
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
                            (not ice-rezzed?))]  ;; Only rez if not already rezzed
        (cond
          ;; --no-rez: always decline
          (:no-rez strategy)
          (do
            (println (format "🤖 Strategy: --no-rez, declining %s" ice-title))
            (ws/send-message! :game/action
                             {:gameid (if (string? gameid)
                                       (java.util.UUID/fromString gameid)
                                       gameid)
                              :command "continue"
                              :args nil})
            {:status :action-taken
             :action :auto-declined-rez
             :ice ice-title})

          ;; --rez <ice-name>: rez if in set, decline otherwise
          should-rez?
          (do
            (println (format "🤖 Strategy: --rez, rezzing %s" ice-title))
            (if current-ice
              (let [card-ref {:cid (:cid current-ice)
                             :zone (:zone current-ice)
                             :side (:side current-ice)
                             :type (:type current-ice)}]
                (ws/send-message! :game/action
                                 {:gameid (if (string? gameid)
                                           (java.util.UUID/fromString gameid)
                                           gameid)
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
            (ws/send-message! :game/action
                             {:gameid (if (string? gameid)
                                       (java.util.UUID/fromString gameid)
                                       gameid)
                              :command "continue"
                              :args nil})
            {:status :action-taken
             :action :sent-continue})

          ;; --rez set exists but this ICE not in it: decline
          :else
          (do
            (println (format "🤖 Strategy: --rez (not %s), declining" ice-title))
            (ws/send-message! :game/action
                             {:gameid (if (string? gameid)
                                       (java.util.UUID/fromString gameid)
                                       gameid)
                              :command "continue"
                              :args nil})
            {:status :action-taken
             :action :auto-declined-rez
             :ice ice-title})))

      ;; Priority 1.6: Corp fire-unbroken strategy - auto-fire unbroken subs during encounter
      (and (= side "corp")
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
            (let [card-ref {:cid (:cid current-ice)
                           :zone (:zone current-ice)
                           :side (:side current-ice)
                           :type (:type current-ice)}]
              (ws/send-message! :game/action
                               {:gameid (if (string? gameid)
                                         (java.util.UUID/fromString gameid)
                                         gameid)
                                :command "unbroken-subroutines"
                                :args {:card card-ref}})
              {:status :action-taken
               :action :auto-fired-subs
               :ice ice-title}))
          (do
            (println "⚠️  Could not find ICE for fire-unbroken")
            {:status :decision-required
             :prompt my-prompt})))

      ;; Priority 2: CRITICAL BUG FIX #12 - Pause at approach-ice with unrezzed ICE
      ;; Runner's prompt says "Continue to Movement" but that would bypass corp's rez decision!
      ;; Must check game state directly, not trust the prompt text.
      ;; Detection: Check if ICE placeholder exists at position and is not rezzed.
      ;; Note: Runner sees minimal ICE data (placeholder), so we count ICE positions rather than
      ;;       relying on full card data. The :rezzed field only exists when ICE IS rezzed.
      ;; REFINEMENT: Check :run :no-action field - when it's "corp", corp already declined to rez
      ;;             This is more reliable than log parsing since some continues are silent!
      (and (= side "runner")
           (= run-phase "approach-ice")
           (let [run (get-in state [:game-state :run])
                 server (:server run)
                 position (:position run)
                 ice-list (get-in state [:game-state :corp :servers (keyword (last server)) :ices])
                 ice-count (count ice-list)
                 ice-index (- ice-count position)
                 current-ice (when (and ice-list (pos? ice-count) (> position 0) (<= ice-index (dec ice-count)))
                               (nth ice-list ice-index nil))
                 ;; Check if corp already declined to rez by checking :run :no-action field
                 ;; When :no-action is "corp", Corp has already passed the rez window
                 no-action (:no-action run)
                 corp-already-declined? (= no-action "corp")]
             ;; ICE exists at position and is NOT rezzed AND corp hasn't already declined
             ;; (:rezzed current-ice) is nil when unrezzed, true when rezzed
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
         :position position})

      ;; Priority 3: Waiting for opponent to make a decision
      (waiting-for-opponent? state side)
      (let [reason (waiting-reason state side)]
        (println (format "⏸️  Waiting for opponent: %s" reason))
        {:status :waiting-for-opponent
         :message reason})

      ;; Priority 3: I have a real decision to make
      (has-real-decision? my-prompt)
      (do
        (println "🛑 Run paused - decision required")
        (println (format "   Prompt: %s" (:msg my-prompt)))
        (when-let [card-title (get-in my-prompt [:card :title])]
          (println (format "   Card: %s" card-title)))
        (let [choices (:choices my-prompt)]
          (println (format "   Choices: %d options" (count choices)))
          (doseq [[idx choice] (map-indexed vector choices)]
            (println (format "     %d. %s" idx (:value choice)))))
        {:status :decision-required
         :prompt my-prompt})

      ;; Priority 4: Pause for important events (rez, abilities, subs, damage)
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
        {:status :tag-or-damage :event tag-damage-event})

      ;; Priority 5: Auto-handle single mandatory choice
      (and my-prompt
           (seq (:choices my-prompt))
           (= 1 (count (:choices my-prompt))))
      (let [choice (first (:choices my-prompt))
            choice-uuid (:uuid choice)]
        (println (format "   Auto-choosing: %s" (:value choice)))
        (ws/send-message! :game/action
                         {:gameid (if (string? gameid)
                                   (java.util.UUID/fromString gameid)
                                   gameid)
                          :command "choice"
                          :args {:choice {:uuid choice-uuid}}})
        {:status :action-taken
         :action :auto-choice
         :choice (:value choice)})

      ;; Priority 6: Auto-continue through boring paid ability windows
      (can-auto-continue? my-prompt run-phase)
      (do
        (println "   → Auto-continuing through paid ability window")
        (ws/send-message! :game/action
                         {:gameid (if (string? gameid)
                                   (java.util.UUID/fromString gameid)
                                   gameid)
                          :command "continue"
                          :args nil})
        {:status :action-taken
         :action :sent-continue})

      ;; Priority 7: Run complete (no run phase, no prompt)
      (and (nil? run-phase) (nil? my-prompt))
      (do
        (println "✅ Run complete")
        {:status :run-complete})

      ;; Priority 8: No active run
      (and (nil? run-phase)
           (or (nil? my-prompt)
               (not= (:prompt-type my-prompt) "run")))
      (do
        (println "⚠️  No active run detected")
        {:status :no-run})

      ;; Fallback: Unknown state
      :else
      (do
        (println "⚠️  Unexpected run state")
        (println (format "   Side: %s" side))
        (println (format "   Run phase: %s" run-phase))
        (println (format "   My prompt type: %s" (:prompt-type my-prompt)))
        (println (format "   My choices: %d" (count (:choices my-prompt))))
        (println (format "   Opp has prompt: %s" (some? opp-prompt)))
        {:status :unexpected-state
         :prompt my-prompt
         :run-phase run-phase}))))

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
