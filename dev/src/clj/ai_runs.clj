(ns ai-runs
  "Run mechanics - initiation, automation, and state management"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-core :as core]
            [ai-prompts :as prompts]
            [ai-basic-actions :as basic]))

;; ============================================================================
;; Run Initiation
;; ============================================================================

(defn run!
  "Run on a server (Runner only).
   Auto-starts turn if needed (opponent has ended and we haven't started yet).
   Accepts flexible server names and normalizes them automatically.

   Central servers (case-insensitive):
   - hq, HQ → HQ
   - rd, r&d, R&D → R&D
   - archives → Archives

   Remote servers (flexible formats):
   - remote1, remote 1, r1, server1, server 1 → Server 1
   - remote2, r2, server2 → Server 2

   Usage: (run! \"hq\")      ; Normalized to HQ
          (run! \"R&D\")     ; Already correct
          (run! \"remote1\")  ; Normalized to Server 1
          (run! \"r2\")      ; Normalized to Server 2"
  [server]
  (if (basic/ensure-turn-started!)
    (let [state @ws/client-state
          gameid (:gameid state)
          initial-log-size (count (get-in @ws/client-state [:game-state :log]))
          {:keys [normalized original changed?]} (core/normalize-server-name server)]
      ;; Provide feedback if we normalized the input
      (when changed?
        (println (format "💡 Normalized '%s' → '%s'" original normalized)))

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
                {:status :success
                 :data {:server normalized :log-entry (:text run-entry)}})

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

   STATELESS DESIGN: No recursion, no local state. Uses game state as source of truth.
   Each call examines current state and either:
   - Sends ONE continue command and returns :action-taken
   - Returns :waiting-for-opponent (pause, wait for opp)
   - Returns :decision-required (pause, user must decide)
   - Returns :run-complete (all done)

   🛑 MUST PAUSE (requires decision):
   - Opponent pressed WAIT/indicate-action
   - Corp has rez opportunity (approach-ice with unrezzed ICE)
   - Runner has 2+ real choices (not just Continue/Done)
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
     ; Check status, if :action-taken, call again
     ; If :waiting-for-opponent, wait for game diff, then call again"
  []
  (let [state @ws/client-state
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
      ;; Priority 1: Opponent pressed WAIT button (indicate-action)
      (opponent-indicated-action? state side)
      (do
        (println "⏸️  PAUSED - Opponent pressed WAIT button")
        {:status :waiting-for-opponent
         :message (str (clojure.string/capitalize opp-side) " pressed WAIT - please pause")})

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
