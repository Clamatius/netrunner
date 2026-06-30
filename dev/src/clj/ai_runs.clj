(ns ai-runs
  "Run mechanics - initiation, automation, and state management"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-core :as core]
            [ai-debug :as debug]
            [ai-prompts :as prompts]
            [ai-basic-actions :as basic]
            [ai-card-actions :as actions]
            [ai-run-tactics :as tactics]
            [ai-run-corp-decisions :as corp-decisions]
            [ai-run-corp-handlers :as corp-handlers]
            [ai-run-runner-handlers :as runner-handlers]))

;; ============================================================================
;; Run Strategy State
;; ============================================================================

;; Atom holding current run strategy flags.
;; Reset when new run starts, inherited by continue-run calls.
;;
;; Structure:
;; {:full-break true/false      ; Runner: auto-break all ICE
;;  :no-rez true/false          ; Corp: don't rez anything
;;  :rez #{\"Ice Wall\" ...}    ; Corp: auto-rez these ICE; pause on other unrezzed ICE
;;  :fire-unbroken true/false   ; Corp: auto-fire unbroken subs
;;  :force true/false}          ; Bypass all smart checks
(defonce run-strategy (atom {}))

;; Track last waiting status to suppress repeated output
(defonce last-waiting-status (atom nil))

;; Debug chat mode - when enabled, announces waits/actions in game chat
;; Enable with: (reset! ai-runs/chat-debug-mode true)
;; Or via env: CHAT_DEBUG=true
(defonce chat-debug-mode
  (atom (= "true" (System/getenv "CHAT_DEBUG"))))

;; Track last chat debug message to avoid spam
(defonce last-chat-debug (atom nil))

;; Prefix for debug chat messages (used to filter in wait functions)
(def debug-chat-prefix "🤖 ")

(defn debug-chat!
  "Send a debug message to game chat (if debug mode enabled).
   Includes timestamp and robot emoji prefix.
   Deduplicates repeated messages.
   Note: Messages start with debug-chat-prefix so wait functions can filter them."
  [msg]
  (when @chat-debug-mode
    (let [timestamp (-> (java.time.LocalTime/now)
                        (.format (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss")))
          ;; Prefix with robot emoji so wait functions can filter these out
          full-msg (str debug-chat-prefix "[" timestamp "] " msg)]
      ;; Only send if different from last message
      (when (not= @last-chat-debug msg)
        (reset! last-chat-debug msg)
        (let [gameid (:gameid @state/client-state)]
          (when gameid
            (ws/send-message! :game/action
                             {:gameid gameid
                              :command "say"
                              :args {:user "AI-debug" :msg full-msg}})))))))

(defn reset-strategy!
  "Clear run strategy (call when run ends)"
  []
  (reset! run-strategy {})
  (reset! last-waiting-status nil)
  (corp-handlers/reset-waiting-status!)
  (runner-handlers/reset-state!))

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
   --tank <ice-name> : Runner pre-authorizes letting subs fire on specified ICE
   --tank-all        : Runner pre-authorizes letting subs fire on ALL ICE (yolo)
   --no-rez          : Corp doesn't rez anything
   --rez <ice-name>  : Corp auto-rezzes named ICE; PAUSES on other unrezzed ICE
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

          (= arg "--tank-all")
          (recur rest-args server (assoc flags :tank-all true))

          ;; --tank <ice-name> (takes argument, can be repeated)
          (= arg "--tank")
          (if (empty? rest-args)
            (do
              (println "⚠️  --tank requires ICE name argument")
              (recur rest-args server flags))
            (let [ice-name (first rest-args)
                  current-tank-set (get flags :tank #{})]
              (recur (rest rest-args)
                     server
                     (assoc flags :tank (conj current-tank-set ice-name)))))

          (= arg "--no-rez")
          (recur rest-args server (assoc flags :no-rez true))

          (= arg "--fire-unbroken")
          (recur rest-args server (assoc flags :fire-unbroken true))

          (= arg "--fire-if-asked")
          (recur rest-args server (assoc flags :fire-if-asked true))

          ;; --return-on-signal: autonomous Corp loop opt-in - surface
          ;; :waiting-for-runner-signal instead of polling it internally.
          (= arg "--return-on-signal")
          (recur rest-args server (assoc flags :return-on-signal true))

          ;; --persistent: keep the defender loop alive across empty
          ;; opponent-priority windows during a run (sleep & recheck instead
          ;; of exiting), so a cross-model seat doesn't re-issue monitor-run
          ;; through every symmetric priority pass. Real decisions still wake it.
          (= arg "--persistent")
          (recur rest-args server (assoc flags :persistent true))

          (= arg "--no-continue")
          (recur rest-args server (assoc flags :no-continue true))

          (= arg "--force")
          (recur rest-args server (assoc flags :force true))

          ;; --since <cursor> (takes integer argument)
          (= arg "--since")
          (if (empty? rest-args)
            (do
              (println "⚠️  --since requires cursor argument")
              (recur rest-args server flags))
            (let [cursor-str (first rest-args)
                  cursor (try
                           (Long/parseLong cursor-str)
                           (catch NumberFormatException _
                             (println "⚠️  --since requires numeric cursor, got:" cursor-str)
                             nil))]
              (recur (rest rest-args)
                     server
                     (if cursor
                       (assoc flags :since cursor)
                       flags))))

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

          ;; --tactics <edn-string> (takes EDN map argument)
          (= arg "--tactics")
          (if (empty? rest-args)
            (do
              (println "⚠️  --tactics requires EDN map argument")
              (recur rest-args server flags))
            (let [tactics-str (first rest-args)
                  ;; Parse EDN outside the recur (can't recur inside try)
                  parsed (try
                           (clojure.edn/read-string tactics-str)
                           (catch Exception e
                             (println "⚠️  --tactics EDN parse error:" (.getMessage e))
                             nil))
                  new-flags (if (map? parsed)
                              (assoc flags :tactics parsed)
                              (do
                                (when (and parsed (not (map? parsed)))
                                  (println "⚠️  --tactics must be a map, got:" (type parsed)))
                                flags))]
              (recur (rest rest-args) server new-flags)))

          ;; --tactics-file <path> (read EDN from file)
          (= arg "--tactics-file")
          (if (empty? rest-args)
            (do
              (println "⚠️  --tactics-file requires file path argument")
              (recur rest-args server flags))
            (let [file-path (first rest-args)
                  parsed (try
                           (clojure.edn/read-string (slurp file-path))
                           (catch java.io.FileNotFoundException _
                             (println "⚠️  --tactics-file not found:" file-path)
                             nil)
                           (catch Exception e
                             (println "⚠️  --tactics-file parse error:" (.getMessage e))
                             nil))
                  new-flags (if (map? parsed)
                              (assoc flags :tactics parsed)
                              (do
                                (when (and parsed (not (map? parsed)))
                                  (println "⚠️  --tactics-file must contain a map, got:" (type parsed)))
                                flags))]
              (recur (rest rest-args) server new-flags)))

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
   --rez <ice-name>  : Corp auto-rezzes named ICE; other unrezzed ICE PAUSES for a rez decision (not auto-declined)
   --fire-unbroken   : Corp auto-fires all unbroken subroutines
   --no-continue     : Don't auto-continue after run initiation (stop at first decision)

   Usage:
   (run! \"hq\")                        ; Auto-continues till decision needed
   (run! \"remote1\" \"--full-break\")   ; Auto-breaks all ICE
   (run! \"hq\" \"--no-continue\")       ; Stop after initiation (rare)
   (run! \"remote1\" \"--rez\" \"Ice Wall\") ; Corp auto-rezzes Ice Wall, pauses on other unrezzed ICE"
  [& args]
  (if (not (core/side= "Runner" (:side @state/client-state)))
    (do
      (println "❌ Only Runner can run on servers")
      {:status :error :reason :wrong-side})
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

      ;; Show server access reminder (helps avoid mistakes like running R&D twice)
      (case normalized
        "R&D" (println "📚 R&D: Access top of Corp deck. Deck does NOT shuffle between runs!")
        "HQ"  (println "🃏 HQ: Access random card from Corp hand.")
        "Archives" (println "📦 Archives: Access all cards (facedown revealed on access).")
        nil)  ; Remote servers - no special reminder needed

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
     :reason "Failed to start turn"})))

;; ============================================================================
;; Continue-Run Helper Functions (Bug #12 Fix)
;; ============================================================================

;; Use core/current-run-ice for ICE lookup (single source of truth)

(defn get-rez-event
  "Find first rez event in log entries, or nil if none"
  [log-entries]
  (first (filter #(clojure.string/includes? (:text %) "rez") log-entries)))

(defn normalize-side
  "Normalize a side value to string. Handles keywords, strings, booleans, and nil."
  [side-value]
  (cond
    (nil? side-value) nil
    (false? side-value) nil  ; false is treated as "no one passed"
    (keyword? side-value) (name side-value)
    (string? side-value) side-value
    :else (str side-value)))

(defn opponent-indicated-action?
  "Check if opponent pressed indicate-action (WAIT button) in recent log.
   The WAIT button signals 'I'm about to do something, don't auto-pass'.
   Useful for both AI-vs-AI coordination and HITL (LLM thinking signal)."
  [state side]
  (let [log (get-in state [:game-state :log])
        opp-side (core/other-side side)
        opp-name (clojure.string/capitalize opp-side)
        ;; Look for "[!] Please pause, {Opponent} is acting."
        indicate-pattern (str "[!] Please pause, " opp-name " is acting")
        ;; Check LAST 5 entries (most recent)
        recent-log (take-last 5 log)]
    ;; Use includes? because log entries may have trailing punctuation
    (some #(clojure.string/includes? (str (:text %)) indicate-pattern) recent-log)))

(defn opponent-passed-priority?
  "Check if opponent passed priority recently (via log).
   Looks for 'AI-{opponent} has no further action' in recent log entries.
   This provides a second source of truth when :no-action state hasn't synced yet."
  [state side]
  (let [log (get-in state [:game-state :log])
        opp-side (core/other-side side)
        ;; Log uses "AI-runner" or "AI-corp" format
        opp-name (str "AI-" opp-side)
        pass-pattern (str opp-name " has no further action")
        ;; Check LAST 5 entries (most recent)
        recent-log (take-last 5 log)]
    ;; Use includes? because log entries may have trailing punctuation
    (some #(clojure.string/includes? (str (:text %)) pass-pattern) recent-log)))

(defn i-passed-priority?
  "Check if I passed priority recently (via log).
   Looks for 'AI-{my-side} has no further action' in recent log entries."
  [state side]
  (let [log (get-in state [:game-state :log])
        my-name (str "AI-" side)
        pass-pattern (str my-name " has no further action")
        ;; Check LAST 5 entries (most recent)
        recent-log (take-last 5 log)]
    ;; Use includes? because log entries may have trailing punctuation
    (some #(clojure.string/includes? (str (:text %)) pass-pattern) recent-log)))

(defn has-real-decision?
  "True if prompt has 2+ meaningful choices (not just Done/Continue),
   or has 1+ selectable cards (for 'select' type prompts like credit sources)."
  [prompt]
  (when prompt
    (let [choices (:choices prompt)
          selectable (:selectable prompt)
          non-trivial (remove (fn [choice]
                               (let [value (clojure.string/lower-case (:value choice ""))]
                                 (or (= value "continue")
                                     (= value "done")
                                     (= value "ok")
                                     (= value ""))))
                             choices)]
      (or (>= (count non-trivial) 2)
          (seq selectable)))))

(defn corp-has-rez-opportunity?
  "True if corp is at a rez decision point (approach-ice with unrezzed ice)"
  [state]
  (let [run-phase (get-in state [:game-state :run :phase])
        corp-prompt (get-in state [:game-state :corp :prompt-state])
        current-ice (core/current-run-ice state)]

    (or
      ;; Approaching unrezzed ICE - ALWAYS a rez opportunity
      (and (= run-phase "approach-ice")
           current-ice
           (not (:rezzed current-ice))
           corp-prompt)

      ;; Corp has explicit rez choices (upgrade/asset rez)
      (when corp-prompt
        (let [choices (:choices corp-prompt)]
          (some #(clojure.string/includes? (:value % "") "Rez") choices))))))

(defn is-waiting-prompt?
  "True if prompt is just a 'waiting' type prompt with no real decisions"
  [prompt]
  (and prompt
       (= (:prompt-type prompt) "waiting")))

(defn has-actionable-prompt?
  "True if we have a real prompt (not just 'waiting')"
  [prompt]
  (and prompt
       (not (is-waiting-prompt? prompt))))

(defn should-i-act?
  "True if it's my turn to act during a run.
   Uses :no-action state as source of truth.

   Priority model during runs:
   - Runner is the active player (acts first in each phase)
   - :no-action tracks who has passed:
     - nil/false: Fresh phase, active player (Runner) should act
     - :runner/\"runner\": Runner passed, Corp should act
     - :corp/\"corp\": Corp passed, Runner should act (or phase advances)

   Returns nil if not in a run."
  [state side]
  (let [run (get-in state [:game-state :run])
        no-action (:no-action run)
        no-action-str (normalize-side no-action)
        active-player "runner"]  ; During runs, Runner is always active player
    (cond
      ;; No run = not applicable
      (nil? run) nil

      ;; State says I already passed → not my turn
      (= no-action-str side) false

      ;; State says opponent passed → my turn
      (= no-action-str (core/other-side side)) true

      ;; Fresh phase (nil or false) → active player acts first
      :else (= side active-player))))

(defn waiting-for-opponent?
  "True if my side is waiting for opponent to make a decision during a run.
   Uses the simple :no-action heuristic for reliability."
  [state side]
  (let [run (get-in state [:game-state :run])]
    (cond
      ;; No active run - not waiting
      (nil? run) false

      ;; CRITICAL: Opponent pressed WAIT button - ALWAYS pause
      (opponent-indicated-action? state side) true

      ;; Use the simple :no-action heuristic
      :else (let [my-turn? (should-i-act? state side)]
              (not my-turn?)))))

(defn waiting-reason
  "Returns human-readable reason for waiting"
  [state side]
  (let [run-phase (get-in state [:game-state :run :phase])
        current-ice (core/current-run-ice state)]

    (cond
      (and (= side "runner") (= run-phase "approach-ice") current-ice)
      (str "Corp must decide: rez " (:title current-ice) " or continue")

      (and (= side "corp") (= run-phase "encounter-ice"))
      "Runner must decide: break subroutines or take effects"

      :else
      "Waiting for opponent action")))

(defn can-auto-continue?
  "True if can safely auto-continue (empty paid ability window, my turn to act).
   Uses should-i-act? for reliable priority detection."
  [prompt run-phase side state]
  (and prompt
       (= (:prompt-type prompt) "run")
       (empty? (:choices prompt))
       (empty? (:selectable prompt))
       ;; Must be my turn to act (not already passed)
       (should-i-act? state side)
       ;; Corp at approach-ice with unrezzed ICE should NOT auto-continue
       ;; (rez decision is too important to auto-pass)
       (not (and (= side "corp")
                 (= run-phase "approach-ice")
                 (let [current-ice (core/current-run-ice state)]
                   (and current-ice (not (:rezzed current-ice))))))))

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
  "Helper to send continue command and return action-taken result.
   Waits briefly for state to sync via WebSocket."
  [gameid]
  (ws/send-message! :game/action
                   {:gameid gameid
                    :command "continue"
                    :args nil})
  ;; Brief wait for WebSocket state update to arrive
  ;; Without this, caller may see stale state on next read
  (Thread/sleep 100)
  {:status :action-taken
   :action :sent-continue})

(defn- send-choice!
  "Helper to send choice command and return action-taken result.
   Waits briefly for state to sync via WebSocket."
  [gameid choice-uuid choice-value]
  (ws/send-message! :game/action
                   {:gameid gameid
                    :command "choice"
                    :args {:choice {:uuid choice-uuid}}})
  ;; Brief wait for WebSocket state update to arrive
  (Thread/sleep 100)
  {:status :action-taken
   :action :auto-choice
   :choice choice-value})

;; Track if we've warned about --force this session
(defonce force-warning-shown (atom false))

(defn handle-force-mode
  "Priority 0: --force flag bypasses ALL checks (but respects run completion)
   ⚠️  WARNING: --force is for AI-vs-AI testing ONLY!
   In HITL games, it will break game state by passing when you should wait."
  [{:keys [strategy gameid state]}]
  (when (:force strategy)
    ;; Show warning once per session
    (when-not @force-warning-shown
      (reset! force-warning-shown true)
      (println "")
      (println "⚠️  WARNING: --force is for AI-vs-AI testing ONLY!")
      (println "⚠️  In HITL games, this WILL break game state by passing")
      (println "⚠️  when you should wait for opponent.")
      (println ""))
    (let [run (get-in state [:game-state :run])]
      (if (nil? run)
        ;; Run is complete, don't send spurious continues
        (do
          (println "✅ Run complete (force mode)")
          {:status :run-complete
           :wake-reason :run-complete})
        ;; Run is active, send continue
        (do
          (println "⚡ FORCE mode - bypassing all checks, sending continue")
          (ws/send-message! :game/action
                           {:gameid gameid
                            :command "continue"
                            :args nil})
          {:status :action-taken
           :action :forced-continue
           :wake-reason :forced})))))

(defn handle-opponent-wait
  "Priority 1: Opponent pressed WAIT button (indicate-action)"
  [{:keys [state side opp-side]}]
  (when (opponent-indicated-action? state side)
    (println "⏸️  PAUSED - Opponent pressed WAIT button")
    {:status :waiting-for-opponent
     :wake-reason :opponent-indicated-action
     :message (str (clojure.string/capitalize opp-side) " pressed WAIT - please pause")}))

(defn handle-run-complete
  "Priority 7: Run complete (run object is nil)"
  [{:keys [state my-prompt]}]
  (let [run (get-in state [:game-state :run])]
    (when (nil? run)
      (println "✅ Run complete")
      {:status :run-complete
       :wake-reason :run-complete})))

(defn handle-no-run
  "Priority 8: No active run"
  [{:keys [state my-prompt]}]
  (let [run (get-in state [:game-state :run])]
    (when (and (nil? run)
               (or (nil? my-prompt)
                   (not= (:prompt-type my-prompt) "run")))
      (println "⚠️  No active run detected")
      {:status :no-run
       :wake-reason :no-run})))

(defn handle-access-display
  "Display accessed cards during run - returns nil to allow auto-continue.
   This handler prints access info but doesn't stop the run automation."
  [{:keys [my-prompt side]}]
  ;; Display access info but always return nil to continue processing
  (when (and my-prompt
             (= side "runner")
             (or (= (:prompt-type my-prompt) "other")
                 (= (:prompt-type my-prompt) "access")))
    (let [msg (:msg my-prompt)
          card-title (get-in my-prompt [:card :title])]
      ;; Check for "You accessed" pattern
      (when (and msg (clojure.string/starts-with? (str msg) "You accessed"))
        (let [status-key [:access-display msg]
              already-printed? (= @last-waiting-status status-key)]
          (when-not already-printed?
            (reset! last-waiting-status status-key)
            (println "")
            (println (format "📋 %s" msg))
            (when card-title
              (println (format "   Card: %s" card-title)))
            ;; Show choices if any (e.g., "Steal", "Pay to trash")
            (when-let [choices (:choices my-prompt)]
              (when (> (count choices) 1)
                (println "   Options:")
                (doseq [[idx choice] (map-indexed vector choices)]
                  (println (format "     %d. %s" idx (:value choice)))))))))))
  ;; Always return nil - let subsequent handlers (auto-choice/auto-continue) handle it
  nil)

(defn handle-auto-choice
  "Priority 5: Auto-handle single mandatory choice.
   For access prompts with only 'Done' as option: show card text (if new), then auto-continue.
   Access prompts with 2+ choices are handled by handle-real-decision (earlier in chain).
   For other single-choice prompts: auto-continue immediately."
  [{:keys [my-prompt gameid]}]
  (when (and my-prompt
             (seq (:choices my-prompt))
             (= 1 (count (:choices my-prompt))))
    (let [choice (first (:choices my-prompt))
          choice-uuid (:uuid choice)
          choice-value (:value choice)
          card-title (get-in my-prompt [:card :title])
          msg (:msg my-prompt)
          is-access-prompt? (and msg (clojure.string/starts-with? (str msg) "You accessed"))]
      ;; For access prompts, ensure card text is shown for first-time cards
      ;; (handle-access-display already printed basic info, but this shows full card text)
      (when (and is-access-prompt? card-title)
        (core/show-card-on-first-sight! card-title))
      ;; All single-choice prompts auto-continue (2+ choice access handled by handle-real-decision)
      (println (format "   → Auto-choosing: %s" choice-value))
      (send-choice! gameid choice-uuid choice-value))))

(defn handle-recently-passed-in-log
  "Priority 5.5: Detect when we've passed via game log (backup for :no-action).
   Only triggers when :no-action is nil/false - prevents stale log entries from blocking."
  [{:keys [side state run-phase]}]
  (let [run (get-in state [:game-state :run])
        no-action (:no-action run)]
    ;; Only check log if :no-action is nil/false (server didn't set it)
    ;; When :no-action has a value, trust it instead of potentially stale log
    (when-not no-action
      (let [log (get-in state [:game-state :log])
            recent-entries (take 3 (reverse log))
            side-name (if (= side "runner") "AI-runner" "AI-corp")
            passed-pattern (re-pattern (str side-name " has no further action"))
            recently-passed? (some #(re-find passed-pattern (str (:text %))) recent-entries)
            opp-side (if (= side "runner") "Corp" "Runner")]
        (when recently-passed?
          (let [status-key [:waiting-after-pass-log run-phase side]
                already-printed? (= @last-waiting-status status-key)]
            (when-not already-printed?
              (reset! last-waiting-status status-key)
              (println (format "⏸️  Waiting for %s paid abilities (%s phase)" opp-side run-phase)))
            {:status :waiting-for-opponent-paid-abilities
             :wake-reason :waiting-for-opponent
             :message (format "Waiting for %s to pass or use paid abilities" opp-side)
             :phase run-phase
             :we-passed true}))))))

(defn handle-auto-continue
  "Priority 6: Auto-continue through paid ability windows where we don't need to act"
  [{:keys [my-prompt run-phase gameid side state]}]
  (when (can-auto-continue? my-prompt run-phase side state)
    (println "   → Auto-continuing through paid ability window")
    (send-continue! gameid)))

(defn handle-real-decision
  "Priority 3: I have a real decision to make"
  [{:keys [my-prompt]}]
  (when (has-real-decision? my-prompt)
    (println "🛑 Run paused - decision required")
    (println (format "   Prompt: %s" (:msg my-prompt)))
    (when-let [card-title (get-in my-prompt [:card :title])]
      (println (format "   Card: %s" card-title))
      ;; Show card text for first-seen cards (especially useful during access)
      (core/show-card-on-first-sight! card-title))
    ;; Display text choices if present
    (let [choices (:choices my-prompt)
          selectable (:selectable my-prompt)]
      (when (seq choices)
        (println (format "   Choices: %d options" (count choices)))
        (doseq [[idx choice] (map-indexed vector choices)]
          (println (format "     %d. %s" idx (:value choice)))))
      ;; Display selectable cards for "select" type prompts
      (when (seq selectable)
        (println (format "   Selectable cards: %d" (count selectable)))
        (doseq [[idx cid] (map-indexed vector selectable)]
          (if-let [card (core/find-card-by-cid cid)]
            (println (format "     %d. %s" idx (:title card)))
            (println (format "     %d. [unknown card: %s]" idx cid))))
        (println "   → Use 'choose-card <index>' to select")))
    {:status :decision-required
     :wake-reason :decision-required
     :prompt my-prompt}))

(defn handle-waiting-for-opponent
  "Priority 3: Waiting for opponent to make a decision"
  [{:keys [state side my-prompt]}]
  (let [run-phase (get-in state [:game-state :run :phase])
        ;; Corp should wait during success phase ONLY if Corp has no prompt at all
        ;; If Corp has any prompt (even trivial "Done"), that needs to be handled first
        corp-has-prompt-with-choices? (and my-prompt (seq (:choices my-prompt)))
        corp-waiting-for-access? (and (= side "corp")
                                      (= run-phase "success")
                                      (not corp-has-prompt-with-choices?)
                                      (not (has-real-decision? my-prompt)))
        ;; If we have a "waiting" type prompt, we're explicitly waiting for opponent
        ;; This handles cases where we can't see opponent's prompt (client isolation)
        has-waiting-prompt? (is-waiting-prompt? my-prompt)]
    ;; A real decision of our own (e.g. an access-trigger ambush like Urtica
    ;; Cipher's "Use ability?") must be surfaced by handle-real-decision, never
    ;; masked as "waiting for opponent" - otherwise self-play deadlocks with both
    ;; sides waiting on each other.
    (when (and (not (has-real-decision? my-prompt))
               (or (waiting-for-opponent? state side)
                   corp-waiting-for-access?
                   has-waiting-prompt?))
      (let [reason (cond
                     corp-waiting-for-access? "Runner resolving access"
                     has-waiting-prompt? (or (:msg my-prompt) "Waiting for opponent decision")
                     :else (waiting-reason state side))
            status-key [:waiting-for-opponent reason]
            already-printed? (= @last-waiting-status status-key)]
        (when-not already-printed?
          (reset! last-waiting-status status-key)
          (println (format "⏸️  Waiting for opponent: %s" reason))
          (debug-chat! (format "WAIT: %s" reason)))
        {:status :waiting-for-opponent
         :wake-reason :waiting-for-opponent
         :message reason}))))

(defn handle-events
  "Priority 4: Pause for important events (rez, abilities, subs, damage)"
  [{:keys [rez-event ability-event fired-event tag-damage-event]}]
  (cond
    rez-event
    (do
      (println "⚠️  Run paused - ICE rezzed!")
      (println (format "   %s" (:text rez-event)))
      (println "   → Use 'continue-run' again to proceed")
      {:status :ice-rezzed :wake-reason :ice-rezzed :event rez-event})

    ability-event
    (do
      (println "⚠️  Run paused - ability triggered!")
      (println (format "   %s" (:text ability-event)))
      (println "   → Use 'continue-run' again to proceed")
      {:status :ability-used :wake-reason :ability-used :event ability-event})

    fired-event
    (do
      (println "⚠️  Run paused - subroutines fired!")
      (println (format "   %s" (:text fired-event)))
      (println "   → Use 'continue-run' again to proceed")
      {:status :subs-fired :wake-reason :subs-fired :event fired-event})

    tag-damage-event
    (do
      (println "⚠️  Run paused - tag or damage!")
      (println (format "   %s" (:text tag-damage-event)))
      (println "   → Use 'continue-run' again to proceed")
      {:status :tag-or-damage :wake-reason :tag-or-damage :event tag-damage-event})))

(defn handle-unexpected-state
  "Fallback: Unknown state - wait and retry rather than give up"
  [{:keys [side run-phase my-prompt opp-prompt]}]
  (let [status-key [:unexpected-state side run-phase]
        already-printed? (= @last-waiting-status status-key)]
    ;; Only print debug info once
    (when-not already-printed?
      (reset! last-waiting-status status-key)
      (println (format "⏳ Waiting (phase: %s, side: %s)..." run-phase side)))
    ;; Return waiting status so loop retries
    {:status :waiting-for-opponent
     :wake-reason :unexpected-state
     :message "Unclear state, waiting for game to advance"
     :prompt my-prompt
     :phase run-phase}))

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
   --rez <ice-name>  : Corp auto-rezzes named ICE; PAUSES on other unrezzed ICE
   --fire-unbroken   : Corp auto-fires unbroken subs
   --force           : Bypass ALL smart checks, just send continue

   🛑 MUST PAUSE (requires decision):
   - Opponent pressed WAIT/indicate-action
   - Corp has rez opportunity (approach-ice with unrezzed ICE) [unless --no-rez, or --rez naming THIS ICE]
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
                  (fn [ctx]  ; Wrapper: mark a rez attempt so a failed (unaffordable) rez isn't retried forever
                    (when-let [result (corp-handlers/handle-corp-rez-strategy ctx)]
                      (when-let [pos (:rez-attempted-at result)]
                        (set-strategy! {:rez-attempted-at pos}))
                      result))
                  corp-handlers/handle-corp-rez-decision
                  ;; fire-if-asked is "sleep mode" - handles fire and empty windows
                  (fn [ctx]  ; Wrapper to update strategy after firing
                    (when-let [result (corp-handlers/handle-corp-fire-if-asked ctx)]
                      (when-let [pos (:fired-at-position result)]
                        (set-strategy! {:fired-at-position pos}))
                      result))
                  (fn [ctx]  ; Wrapper to update strategy after firing
                    (when-let [result (corp-handlers/handle-corp-fire-unbroken ctx)]
                      (when-let [pos (:fired-at-position result)]
                        (set-strategy! {:fired-at-position pos}))
                      result))
                  corp-handlers/handle-corp-fire-decision
                  corp-handlers/handle-corp-all-subs-resolved
                  corp-handlers/handle-corp-waiting-after-subs-fired
                  corp-handlers/handle-corp-server-upgrade-decision
                  corp-handlers/handle-paid-ability-window
                  runner-handlers/handle-auto-select-single-card
                  runner-handlers/handle-runner-approach-ice
                  tactics/handle-runner-tactics
                  runner-handlers/handle-runner-full-break
                  runner-handlers/handle-runner-encounter-ice
                  runner-handlers/handle-runner-pass-broken-ice
                  runner-handlers/handle-runner-pass-fired-ice
                  handle-waiting-for-opponent
                  handle-real-decision
                  handle-events
                  handle-access-display
                  handle-auto-choice
                  handle-recently-passed-in-log
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
;; - Real decision required (:decision-required / :fire-decision-required)
;; - Notable event occurs (:ice-rezzed, :ability-used, :subs-fired, :tag-or-damage)
;;   — EXCEPT in --persistent mode during an active run, where the loop's own
;;   notable events are routine and it keeps owning the run (see #36 below)
;; - Max iterations reached (safety guard)
;; - Timeout reached
;;
;; For :waiting-for-opponent status, the loop waits briefly then retries,
;; allowing the other client to take their action.

(defn- terminal-status?
  "Returns true if this status should stop the auto-continue loop"
  [status]
  (contains? #{:decision-required :fire-decision-required :ice-rezzed :ability-used :subs-fired
               :tag-or-damage :run-complete :no-run
               :tactic-paused :tactic-failed}  ; Tactics system pauses
             status))

(defn- should-pause-for-event?
  "Returns true if this is a notable event we should pause to show the user"
  [status]
  (contains? #{:ice-rezzed :ability-used :subs-fired :tag-or-damage} status))

(defn- get-run-state-key
  "Extract state key for stuck detection: [phase position ice-title]"
  []
  (let [state @state/client-state
        run (get-in state [:game-state :run])
        phase (:phase run)
        position (:position run)
        ice (core/current-run-ice state)
        ;; nil-safe: ice is nil during access phase (no ICE being encountered)
        ice-title (when ice (:title ice))]
    [phase position ice-title]))

(defn- detect-stuck-state
  "Check if we're stuck in the same state.
   Returns true if state-history has same state for threshold consecutive iterations.
   state-history is a vector of recent state keys (newest first)."
  [state-history threshold]
  (when (>= (count state-history) threshold)
    (let [recent (take threshold state-history)]
      (apply = recent))))

(defn- print-while-you-slept!
  "Print material run events that happened during a persistent monitor sleep."
  [start-log-count]
  (let [log (get-in @state/client-state [:game-state :log])
        lines (corp-decisions/summarize-slept-log log start-log-count)]
    (when (seq lines)
      (println "While you slept:")
      (doseq [line lines]
        (println (str "   " line))))))

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
   - stuck in same state (5 consecutive :action-taken with same [phase position ice])
   - max iterations or timeout reached

   Options:
   :max-iterations  - Safety guard (default 500, high because stuck-detection handles loops)
   :timeout-ms      - Max time to loop (default 300000ms = 300s). Matches
                      `wait` because LLM agents take minutes per turn, and
                      monitor-run regularly sits idle while the opponent
                      thinks. 30s (the prior default) bailed prematurely
                      after Runner had just jacked out but before the state
                      diff arrived. If you genuinely need a short bail
                      (e.g., a quick fast-return check), pass it explicitly.
   :wait-delay-ms   - Delay when waiting for opponent (default 200ms)
   :stuck-threshold - Same state iterations before declaring stuck (default 5)
   :pause-on-events - Pause on events like :ice-rezzed (default true)

   Returns the final result from continue-run! plus:
   :iterations - how many times continue-run! was called
   :elapsed-ms - how long the loop ran"
  [& {:keys [max-iterations timeout-ms wait-delay-ms stuck-threshold pause-on-events
             return-on-runner-signal persistent persistent-wait-delay-ms]
      :or {max-iterations 500    ; Raised from 50 - stuck detection handles loops
           timeout-ms 300000     ; Was 30000 — too short for LLM-paced games
           wait-delay-ms 200
           stuck-threshold 5
           pause-on-events true
           ;; When false (hand-driven monitor-run), :waiting-for-runner-signal
           ;; polls internally so a human doesn't have to re-issue the command.
           ;; The autonomous Corp loop sets this true so the wait is surfaced to
           ;; its own per-tick stall tracker (which nudges/bails) instead of being
           ;; swallowed by up to ~100s of internal polling.
           return-on-runner-signal false
           ;; When true (monitor-run --persistent), during an ACTIVE run two
           ;; status families that are terminal for hand-driven monitor-run are
           ;; NOT terminal here, keeping the defender loop alive so a cross-model
           ;; seat doesn't have to re-issue monitor-run:
           ;;   (a) empty opponent-priority windows (:waiting-for-opponent
           ;;       family) — sleep persistent-wait-delay-ms and recheck (idle
           ;;       wait; does NOT advance `iteration`, bounded by timeout-ms);
           ;;   (b) the loop's own notable events (should-pause-for-event?:
           ;;       :ice-rezzed / :ability-used / :subs-fired / :tag-or-damage) —
           ;;       sleep quick-delay and recur, ADVANCING `iteration` so the
           ;;       max-iterations backstop still bounds a degenerate event spin
           ;;       (#36). Only genuine decisions (:decision-required /
           ;;       :fire-decision-required) — which are NOT should-pause-for-
           ;;       event? — plus run-end remain terminal under --persistent, so
           ;;       it "wakes only for a real rez/fire/access decision or run
           ;;       end". Both branches are below; the event branch returns ABOVE
           ;;       terminal-status?.
           persistent false
           persistent-wait-delay-ms 1000}}]
  (let [start-time (System/currentTimeMillis)
        start-log-count (count (get-in @state/client-state [:game-state :log]))
        deadline (+ start-time timeout-ms)]
    (loop [iteration 0
           state-history []]   ; Track [phase position ice] for stuck detection
      (cond
        ;; Safety: max iterations (should rarely trigger with stuck-detection)
        (>= iteration max-iterations)
        (do
          (when persistent
            (print-while-you-slept! start-log-count))
          (println (format "⚠️  Auto-continue stopped: max iterations (%d) reached" max-iterations))
          {:status :max-iterations
           :wake-reason :max-iterations
           :iterations iteration
           :elapsed-ms (- (System/currentTimeMillis) start-time)})

        ;; Safety: timeout
        (> (System/currentTimeMillis) deadline)
        (do
          (when persistent
            (print-while-you-slept! start-log-count))
          (println (format "⚠️  Auto-continue stopped: timeout (%dms) reached" timeout-ms))
          {:status :timeout
           :wake-reason :timeout
           :iterations iteration
           :elapsed-ms (- (System/currentTimeMillis) start-time)})

        :else
        (let [result (continue-run!)
              status (:status result)
              current-state-key (get-run-state-key)
              new-history (cons current-state-key (take (dec stuck-threshold) state-history))]
          (cond
            ;; Persistent mode: a notable-but-routine event the loop produced
            ;; itself (its own ICE rez, an ability/subs firing, tag/damage
            ;; dealt) is NOT a decision — the seat delegated the whole run.
            ;; These statuses are terminal-status? for hand-driven monitor-run
            ;; (where pausing to show the user is the point), but in --persistent
            ;; mode treating them as terminal DROPPED the defender loop after a
            ;; rez commit (#36): the auto-rez returns :action-taken, then the
            ;; next iteration sees "Corp rezzes X" in the log and handle-events
            ;; returns :ice-rezzed → loop exits, leaving the Runner holding
            ;; priority at a window the Corp is no longer watching (soft
            ;; deadlock; the Runner could only recover by jacking out). The
            ;; documented --persistent contract is "wakes only for a real
            ;; rez/fire/access decision or run end", and a notable event is none
            ;; of those, so while the run is still active we keep owning it: the
            ;; event was already printed by handle-events; just continue.
            ;; Genuine decisions (:decision-required / :fire-decision-required)
            ;; are NOT should-pause-for-event? and still terminate below.
            ;; Spin-safe: iteration advances (max-iterations backstop stays
            ;; live), and once the run moves past the event the rez line scrolls
            ;; out of the 3-entry recent-log window — if it doesn't move, the
            ;; next status is :waiting-for-opponent (persistent sleep), not a
            ;; re-fired event.
            (and persistent
                 (should-pause-for-event? status)
                 (some? (get-in @state/client-state [:game-state :run])))
            (do
              (Thread/sleep core/quick-delay)
              (recur (inc iteration) state-history))  ; Keep OLD history; event isn't run-phase progress

            ;; Terminal status - stop loop.
            ;; Bug #3 fix: if the run completed and we burned the last click on
            ;; it, fire check-auto-end-turn! — otherwise the runner never sends
            ;; end-turn and the engine deadlocks on the next turn cycle.
            ;; check-auto-end-turn! has its own (my-turn? / clicks=0 / no prompt
            ;; / not already-ended) guards, so it's safe to call unconditionally
            ;; here, including from monitor-run! on the off-turn side.
            (terminal-status? status)
            (do
              (when persistent
                (print-while-you-slept! start-log-count))
              (when (or (= status :run-complete) (= status :no-run))
                (basic/check-auto-end-turn!)
                ;; Clear per-run runner handler atoms (passed-ice-position,
                ;; signaled-fire-position, etc.) now that the run is over.
                ;; run!/monitor-run! only reset at their START, so a later
                ;; run-event run (Jailbreak/Conduit) that enters via continue-run!
                ;; would otherwise inherit stale [position ice] keys and skip a
                ;; needed pass-continue.
                (runner-handlers/reset-state!))
              (assoc result
                     :iterations (inc iteration)
                     :elapsed-ms (- (System/currentTimeMillis) start-time)))

            ;; Waiting for Runner signal - poll and retry (Corp auto-waiting),
            ;; unless the caller (the autonomous Corp loop) asked to surface it so
            ;; its own stall tracker governs the wait.
            (= status :waiting-for-runner-signal)
            (if return-on-runner-signal
              (assoc result
                     :iterations (inc iteration)
                     :elapsed-ms (- (System/currentTimeMillis) start-time))
              ;; Idle wait for the Runner to break/signal. Like the persistent
              ;; :waiting-for-opponent branch below, DON'T advance `iteration`:
              ;; this is an idle opponent wait, not an action loop, so it must be
              ;; bounded by timeout-ms (LLM-paced, ~300s) — not the action-stuck
              ;; max-iterations guard (~100s). Advancing it made a Corp parked at
              ;; an encounter waiting for a slow Runner bail mid-run with a
              ;; misleading "max iterations reached", defeating monitor-run
              ;; --persistent's whole-run ownership (b7e710d11).
              (do
                (Thread/sleep wait-delay-ms)
                (recur iteration [])))  ; Reset history; idle wait, don't advance iteration

            ;; Waiting for opponent. Normally terminal — the other client needs
            ;; to run their own loop (e.g., Corp runs monitor-run!). In persistent
            ;; mode, while the run is still active, instead sleep and recheck so
            ;; the seat keeps a live defender loop across empty priority windows.
            (or (= status :waiting-for-opponent)
                (= status :waiting-for-corp-rez)
                (= status :waiting-for-corp-fire)
                (= status :waiting-for-opponent-paid-abilities))
            (if (and persistent
                     (some? (get-in @state/client-state [:game-state :run])))
              ;; Idle wait: don't advance `iteration` (timeout governs the bound,
              ;; not the action-stuck max-iterations guard); reset stuck history.
              (do
                (Thread/sleep persistent-wait-delay-ms)
                (recur iteration []))
              (if persistent
                ;; Persistent, but the run object is GONE (nil). The run already
                ;; ended — typically the access boundary, where the run tears down
                ;; while the Runner still resolves access. Marquee game-2 surfaced
                ;; this returning a stale :waiting-for-opponent + "Corp should run
                ;; monitor-run" tip, which then immediately said "No active run to
                ;; monitor" — a self-contradicting double-step. Return a CLEAN
                ;; :no-run terminal so the seat goes straight back to its wait loop.
                (do
                  (print-while-you-slept! start-log-count)
                  (println "✅ Run ended — no further Corp decision. Back to the wait loop.")
                  (assoc result
                         :status :no-run
                         :iterations (inc iteration)
                         :elapsed-ms (- (System/currentTimeMillis) start-time)))
                (do
                  ;; Side-aware tip. This loop just passed THIS seat's window and
                  ;; is now waiting on the opponent. The advice depends on who's
                  ;; running it: a Runner waits on the Corp to defend (monitor-run);
                  ;; a Corp that already passed waits on the Runner to act — telling
                  ;; the Corp to "run monitor-run" there is backwards (it just did).
                  (if (core/side= "Corp" (:side @state/client-state))
                    (println "💡 Waiting for Runner to act (e.g. 'continue' / next run step) — they hold priority.")
                    (println "💡 Tip: Corp should run 'monitor-run' to participate in the run"))
                  (assoc result
                         :iterations (inc iteration)
                         :elapsed-ms (- (System/currentTimeMillis) start-time)))))

            ;; Prompt handled (e.g., credit source auto-select) - continue without stuck tracking
            ;; This is progress but not run-phase progress, so don't add to history
            (= status :prompt-handled)
            (do
              (Thread/sleep core/quick-delay)
              (recur (inc iteration) state-history))  ; Keep OLD history, don't add new entry

            ;; Action taken - check for stuck state, then continue
            (= status :action-taken)
            (if (detect-stuck-state new-history stuck-threshold)
              ;; Stuck! Same state for N iterations despite :action-taken
              (do
                (println (format "⚠️  Stuck in same state for %d iterations: %s" stuck-threshold current-state-key))
                (println "   This usually means a handler is sending continues without making progress")
                {:status :stuck
                 :wake-reason :stuck
                 :state-key current-state-key
                 :iterations (inc iteration)
                 :elapsed-ms (- (System/currentTimeMillis) start-time)})
              ;; Not stuck - continue
              (do
                (reset! last-waiting-status nil)  ; Clear so new waiting messages will print
                (Thread/sleep core/quick-delay)   ; Brief sync pause
                (recur (inc iteration) new-history)))

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

   Flags:
     --no-rez            Auto-decline all rez opportunities
     --rez <ice-name>    Auto-rez named ICE; PAUSE (return a rez decision) on other unrezzed ICE
     --fire-unbroken     Auto-fire unbroken subs when Runner signals
     --fire-if-asked     Sleep mode: auto-fire, auto-continue, wake only for rez
     --persistent        Stay in the loop across empty opponent-priority windows
                         (sleep & recheck instead of exiting on :waiting-for-opponent).
                         For autonomous Corp seats — one monitor-run owns the whole
                         Runner run; wakes only for a real rez/fire/access decision
                         or run end. Eliminates re-issuing through symmetric passes.
     --since <cursor>    Fast-return: immediately return if run ended/started since cursor

   Usage:
     (monitor-run!)                           ; Auto-pass until decision needed
     (monitor-run! \"--no-rez\")              ; Also auto-decline all rez opportunities
     (monitor-run! \"--rez\" \"Tithe\")        ; Auto-rez Tithe; pause on any other unrezzed ICE
     (monitor-run! \"--fire-if-asked\")       ; Sleep until run ends
     (monitor-run! \"--persistent\")          ; Own the whole run; wake only for decisions
     (monitor-run! \"--since\" \"892\")        ; Fast-return if state advanced"
  [& args]
  (let [{:keys [flags]} (if (seq args) (parse-run-flags (vec args)) {:flags {}})
        since-cursor (:since flags)
        current-cursor (state/get-cursor)
        run (get-in @state/client-state [:game-state :run])]
    (cond
      ;; --since fast-return: check if state already advanced
      (and since-cursor (> current-cursor since-cursor) (nil? run))
      (do
        (println (format "⚡ Fast-return: run ended (cursor %d > %d)" current-cursor since-cursor))
        {:status :run-complete
         :wake-reason :run-complete
         :cursor current-cursor
         :fast-return true})

      (and since-cursor (> current-cursor since-cursor) run)
      (do
        (println (format "⚡ Fast-return: new run active (cursor %d > %d)" current-cursor since-cursor))
        {:status :new-run
         :wake-reason :new-run
         :cursor current-cursor
         :fast-return true})

      ;; No active run
      (nil? run)
      (do
        (println "⚠️  No active run to monitor")
        {:status :no-run
         :wake-reason :no-run
         :cursor current-cursor})

      ;; Normal monitoring flow
      :else
      (do
        ;; Reset strategy at start of monitoring (Corp has separate atom from Runner)
        ;; This prevents stale --rez sets from previous runs
        (reset-strategy!)
        ;; Apply new strategy flags. Exclude control flags that govern the loop
        ;; itself (not the rez/fire strategy): :since (fast-return), :persistent
        ;; and :return-on-signal (loop behavior) — so they don't pollute the
        ;; 🎯 Strategy line or the run-strategy atom.
        (let [strategy-flags (dissoc flags :since :persistent :return-on-signal)]
          (when (seq strategy-flags)
            (set-strategy! strategy-flags)
            (println (format "🎯 Strategy: %s"
                            (clojure.string/join ", "
                                                 (map (fn [[k v]]
                                                        (if (set? v)
                                                          (str (name k) " " (clojure.string/join "," v))
                                                          (name k)))
                                                      strategy-flags))))))
        (println "👁️  Monitoring run... (auto-passing boring windows)")
        (when (:persistent flags)
          (println "🔁 Persistent mode — staying in the loop across empty windows (wakes for decisions / run end)"))
        (let [result (auto-continue-loop! :return-on-runner-signal (boolean (:return-on-signal flags))
                                          :persistent (boolean (:persistent flags)))]
          ;; Include cursor in result for caller to track
          (assoc result :cursor (state/get-cursor)))))))

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
