(ns ai-run-runner-handlers
  "Runner-side run handlers - approach/encounter ICE, breaking, passing.

   Extracted from ai-runs to reduce file size. These handlers are called from
   the handler chain in continue-run!.

   Handler contract:
   - Receives context map with :state, :side, :gameid, :run-phase, :my-prompt, :strategy, etc.
   - Returns nil to fall through to next handler
   - Returns result map {:status ... :action ...} to stop handler chain"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-core :as core]
            [ai-card-actions :as actions]
            [ai-run-tactics :as tactics]))

;; ============================================================================
;; Shared Helpers
;; ============================================================================

;; Use core/current-run-ice for ICE lookup (single source of truth)

(defn- normalize-side
  "Normalize a side value to string."
  [side-value]
  (cond
    (nil? side-value) nil
    (false? side-value) nil
    (keyword? side-value) (name side-value)
    (string? side-value) side-value
    :else (str side-value)))

(defn- send-continue!
  "Helper to send continue command and return action-taken result."
  [gameid]
  (ws/send-message! :game/action
                    {:gameid gameid
                     :command "continue"
                     :args nil})
  (Thread/sleep 100)
  {:status :action-taken
   :action :sent-continue})

(defn- filter-meaningful-log-entries
  "Filter log entries to exclude 'no further action' spam."
  [log-entries]
  (remove #(clojure.string/includes? (str (:text %)) "has no further action") log-entries))

(defn- let-subs-fire-signal!
  "Send system message signaling Runner is done breaking subs on this ICE."
  [gameid ice-title]
  (ws/send-message! :game/action
    {:gameid gameid
     :command "system-msg"
     :args {:msg (str "indicates to fire all unbroken subroutines on " ice-title)}})
  (Thread/sleep 50))

;; ============================================================================
;; State Atoms
;; ============================================================================

;; Track last waiting status to suppress repeated output
(defonce last-waiting-status (atom nil))

;; Track last --full-break warning to avoid repeating
(defonce last-full-break-warning (atom nil))

;; Track position where Runner has signaled "let subs fire"
(defonce signaled-fire-position (atom nil))

;; Track failed ability attempts per position to detect unaffordable abilities
;; Map of position -> count, cleared when position changes or run ends
(defonce failed-ability-attempts (atom {}))

(defn reset-state!
  "Reset all Runner handler state atoms (called when run ends)."
  []
  (reset! last-waiting-status nil)
  (reset! last-full-break-warning nil)
  (reset! signaled-fire-position nil)
  (reset! failed-ability-attempts {}))

;; ============================================================================
;; Runner Approach Handlers
;; ============================================================================

(defn handle-runner-approach-ice
  "Priority 2: Runner waiting for corp rez decision at approach-ice with unrezzed ICE."
  [{:keys [side run-phase state]}]
  (when (and (= side "runner")
             (= run-phase "approach-ice"))
    (let [run (get-in state [:game-state :run])
          position (:position run)
          current-ice (core/current-run-ice state)
          no-action (:no-action run)
          no-action-str (normalize-side no-action)
          corp-already-declined? (= no-action-str "corp")]
      (when (and current-ice (not (:rezzed current-ice)) (not corp-already-declined?))
        (let [ice-title (:title current-ice "ICE")
              ice-count (count (get-in state [:game-state :corp :servers
                                              (keyword (last (:server run))) :ices]))
              status-key [:waiting-for-corp-rez position ice-title]
              already-printed? (= @last-waiting-status status-key)]
          (when-not already-printed?
            (reset! last-waiting-status status-key)
            (println "⏸️  Waiting for corp rez decision")
            (println (format "   ICE: %s (position %d/%d, unrezzed)" ice-title position ice-count)))
          {:status :waiting-for-corp-rez
           :wake-reason :rez-decision
           :message (format "Waiting for corp to decide: rez %s or continue" ice-title)
           :ice ice-title
           :position position})))))

;; ============================================================================
;; Runner Breaking Handlers
;; ============================================================================

(defn- extract-cost
  "Extract numeric cost from cost-label string like '1[c]' -> 1.
   Returns nil if can't parse."
  [cost-label]
  (when cost-label
    (try
      (Integer/parseInt (re-find #"\d+" cost-label))
      (catch Exception _ nil))))

(defn- sort-break-abilities
  "Sort break abilities by cost (cheapest first).
   Abilities with unparseable cost go last."
  [abilities]
  (sort-by (fn [{:keys [cost-label]}]
             (or (extract-cost cost-label) 999))
           abilities))

(defn- has-real-decision?
  "True if prompt has 2+ meaningful choices (not just Done/Continue),
   or has 1+ selectable cards. Used to detect on-encounter prompts that
   must be resolved before breaking."
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

(defn- subs-already-resolved?
  "Check if subroutines have already been resolved on this ICE (via game log).
   Used to detect when we should pass ICE instead of trying to break."
  [state ice-title]
  (let [log (get-in state [:game-state :log])
        recent-log (take 10 (reverse log))]
    (some #(re-find (re-pattern (str "(?i)resolves.*subroutines on " (java.util.regex.Pattern/quote ice-title)))
                    (str (:text %)))
          recent-log)))

(defn handle-runner-full-break
  "Priority 2.4: Auto-break with --full-break strategy.
   Finds the cheapest available break ability and uses it.
   Returns nil if no breaking possible (falls through to handle-runner-encounter-ice).

   IMPORTANT: Defers to on-encounter prompts (like Funhouse's 'Take 1 tag or end run')
   by returning nil when there's a real decision to make.

   Also defers when subs have already fired - lets handle-runner-pass-fired-ice
   take over to continue past the ICE."
  [{:keys [side run-phase state strategy gameid my-prompt]}]
  (when (and (= side "runner")
             (= run-phase "encounter-ice")
             (:full-break strategy)
             ;; Don't break if there's an on-encounter prompt to handle first
             (not (has-real-decision? my-prompt)))
    (let [run (get-in state [:game-state :run])
          position (:position run)
          current-ice (core/current-run-ice state)
          subroutines (:subroutines current-ice)
          ;; Check both :broken and :fired flags for actionable subs
          unbroken-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)
          ice-title (:title current-ice "ICE")]
      ;; Also check log in case :fired flag isn't set by server
      (when (and current-ice (:rezzed current-ice) (seq unbroken-subs)
                 (not (subs-already-resolved? state ice-title)))
        (let [runner-rig (get-in state [:game-state :runner :rig])
              all-programs (get runner-rig :program [])

              ;; Look for dynamic break abilities (server will reject if unaffordable)
              ;; NOTE: Dynamic abilities have playable=null, so don't require it
              breakable-abilities
              (for [program all-programs
                    [idx ability] (map-indexed vector (:abilities program))
                    :when (and (:dynamic ability)
                               (clojure.string/includes? (str (:dynamic ability)) "break"))]
                {:card program
                 :card-name (:title program)
                 :ability-index idx
                 :label (:label ability)
                 :cost-label (:cost-label ability)
                 :dynamic (:dynamic ability)})

              ;; Sort by cost - use cheapest first
              sorted-abilities (sort-break-abilities breakable-abilities)
              ;; Check how many times we've failed at this position
              fail-count (get @failed-ability-attempts position 0)
              max-retries 2]
          ;; If we've failed too many times, skip straight to letting subs fire
          (if (and (seq sorted-abilities) (< fail-count max-retries))
            ;; Use the cheapest available break ability
            (let [{:keys [card-name ability-index label cost-label]} (first sorted-abilities)]
              (reset! last-full-break-warning nil)
              (println (format "🔨 Auto-breaking %s with %s" ice-title card-name))
              (when cost-label
                (println (format "   %s (cost: %s)" label cost-label)))
              (let [result (actions/use-ability! card-name ability-index)]
                (if (= :success (:status result))
                  (do
                    ;; Success - clear failure count for this position
                    (swap! failed-ability-attempts dissoc position)
                    {:status :ability-used
                     :wake-reason :broke-ice
                     :message (format "Auto-broke %s with %s" ice-title card-name)
                     :ice ice-title
                     :breaker card-name})
                  (do
                    ;; Failure - increment failure count and return nil to retry
                    ;; After max-retries, will fall through to let-subs-fire path
                    (swap! failed-ability-attempts update position (fnil inc 0))
                    (println (format "❌ Ability failed (attempt %d/%d) - may be unaffordable"
                                   (inc fail-count) max-retries))
                    nil))))
            ;; No playable dynamic ability OR too many failures - try manual pump+break fallback
            (if-let [fallback-result (tactics/try-manual-pump-and-break! state current-ice all-programs)]
              fallback-result
              ;; Fallback also failed - PAUSE and let player decide (don't auto-tank)
              ;; --full-break means "I want to break" - if we can't, player must choose:
              ;;   tank <ice-name>   - authorize letting subs fire
              ;;   jack-out          - abandon the run
              ;;   (wait)            - maybe Corp won't rez, or situation changes
              (let [warning-key [position ice-title]
                    runner-credits (get-in state [:game-state :runner :credit] 0)
                    all-break-abilities
                    (for [program all-programs
                          [idx ability] (map-indexed vector (:abilities program))
                          :when (and (:dynamic ability)
                                     (when-let [dyn (:dynamic ability)]
                                       (clojure.string/includes? (str dyn) "break")))]
                      {:card-name (:title program)
                       :label (:label ability)
                       :playable (:playable ability)
                       :cost-label (:cost-label ability)})]
                (when (not= @last-full-break-warning warning-key)
                  (reset! last-full-break-warning warning-key)
                  (println "")
                  (println (format "⛔ --full-break PAUSED: Can't break %s" ice-title))
                  (if (seq all-break-abilities)
                    (let [{:keys [card-name label cost-label]} (first all-break-abilities)]
                      (println (format "   %s has: %s (cost: %s)" card-name label (or cost-label "?")))
                      (println (format "   Runner credits: %d¢" runner-credits)))
                    (println "   No icebreaker can break this ICE"))
                  (println "")
                  (println "   Options:")
                  (println (format "     tank \"%s\"   - let subs fire" ice-title))
                  (println "     jack-out        - abandon run")
                  (println "     (or wait for situation to change)"))
                ;; Return paused status - don't send let-subs-fire signal
                {:status :paused-cannot-break
                 :wake-reason :player-decision-required
                 :message (format "Can't afford to break %s - waiting for player decision" ice-title)
                 :ice ice-title
                 :unbroken-count (count unbroken-subs)
                 :position position
                 :credits runner-credits
                 :reason (if (seq all-break-abilities) :cant-afford :no-breaker)}))))))))

;; ============================================================================
;; Runner Encounter Handlers
;; ============================================================================

(defn- ice-authorized-for-fire?
  "Check if Runner has pre-authorized letting subs fire on this ICE."
  [strategy ice-title]
  (or (:tank-all strategy)
      (contains? (get strategy :tank #{}) ice-title)))

(defn handle-runner-encounter-ice
  "Priority 2.5: Runner at encounter-ice with rezzed ICE - wait for Corp's fire decision.
   SAFETY: Only signals if Runner explicitly authorized via --tank or --tank-all."
  [{:keys [side run-phase state gameid strategy]}]
  (when (and (= side "runner")
             (= run-phase "encounter-ice")
             (not (:full-break strategy)))
    (let [run (get-in state [:game-state :run])
          position (:position run)
          current-ice (core/current-run-ice state)
          subroutines (:subroutines current-ice)
          unfired-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)
          no-action (:no-action run)
          no-action-str (normalize-side no-action)
          corp-passed? (= no-action-str "corp")]
      (when (and current-ice (:rezzed current-ice) (seq unfired-subs) (not corp-passed?))
        (let [ice-title (:title current-ice "ICE")
              sub-count (count unfired-subs)
              authorized? (ice-authorized-for-fire? strategy ice-title)
              status-key [:waiting-for-corp-fire position ice-title]
              already-printed? (= @last-waiting-status status-key)
              already-signaled? (= @signaled-fire-position position)]
          (if (not authorized?)
            ;; NOT authorized - pause and ask Runner to decide
            (do
              (when-not already-printed?
                (reset! last-waiting-status status-key)
                (println (format "⚠️  %s has %d unbroken sub%s - authorization required"
                               ice-title sub-count (if (= sub-count 1) "" "s")))
                (println "   Options:")
                (println (format "   → tank \"%s\"         - let subs fire, continue run" ice-title))
                (println "   → jack-out            - end the run")
                (println "   → Or break: use-ability \"<breaker>\" <index>")
                (println "              (run 'abilities \"<breaker>\"' to see options)"))
              {:status :fire-decision-required
               :wake-reason :decision-required
               :message (format "%s has %d unbroken sub(s) - use 'tank' to let fire or 'jack-out'" ice-title sub-count)
               :ice ice-title
               :unbroken-count sub-count
               :position position})
            ;; Authorized - send signal to Corp
            (do
              (when-not already-signaled?
                (reset! signaled-fire-position position)
                (println (format "📡 Signaling Corp: done breaking on %s (tank authorized)" ice-title))
                (let-subs-fire-signal! gameid ice-title))
              (when-not already-printed?
                (reset! last-waiting-status status-key)
                (println (format "⏸️  Waiting for Corp fire decision: %s (%d unbroken sub%s)"
                               ice-title sub-count (if (= sub-count 1) "" "s"))))
              {:status :waiting-for-corp-fire
               :wake-reason :waiting-for-opponent
               :message (format "Waiting for Corp to fire subs on %s or pass" ice-title)
               :ice ice-title
               :unbroken-count sub-count
               :position position})))))))

;; ============================================================================
;; Runner Pass Handlers
;; ============================================================================

(defn handle-runner-pass-broken-ice
  "Priority 2.6: Runner at encounter-ice when all subs are broken."
  [{:keys [side run-phase state gameid]}]
  (when (and (= side "runner")
             (= run-phase "encounter-ice"))
    (let [current-ice (core/current-run-ice state)
          subroutines (:subroutines current-ice)
          actionable-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)]
      (when (and current-ice (:rezzed current-ice) (seq subroutines) (empty? actionable-subs))
        (let [ice-title (:title current-ice "ICE")]
          (println (format "   → All subs broken on %s, Runner passing ICE" ice-title))
          (send-continue! gameid))))))

(defn handle-runner-pass-fired-ice
  "Priority 2.7: Runner at encounter-ice after subs have fired."
  [{:keys [side run-phase state gameid]}]
  (when (and (= side "runner")
             (= run-phase "encounter-ice"))
    (let [current-ice (core/current-run-ice state)
          ice-title (:title current-ice "ICE")
          log (get-in state [:game-state :log])
          meaningful-log (filter-meaningful-log-entries (reverse log))
          recent-log (take 20 meaningful-log)
          subs-resolved? (some #(re-find (re-pattern (str "(?i)(resolves.*subroutines on|uses) " (java.util.regex.Pattern/quote ice-title)))
                                         (str (:text %)))
                               recent-log)]
      (when (and current-ice (:rezzed current-ice) subs-resolved?)
        (println (format "   → Subs resolved on %s, Runner passing ICE" ice-title))
        (send-continue! gameid)))))
