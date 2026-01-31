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

(defn reset-state!
  "Reset all Runner handler state atoms (called when run ends)."
  []
  (reset! last-waiting-status nil)
  (reset! last-full-break-warning nil)
  (reset! signaled-fire-position nil))

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
           :message (format "Waiting for corp to decide: rez %s or continue" ice-title)
           :ice ice-title
           :position position})))))

;; ============================================================================
;; Runner Breaking Handlers
;; ============================================================================

(defn handle-runner-full-break
  "Priority 2.4: Auto-break with --full-break strategy.
   Returns nil if no breaking possible (falls through to handle-runner-encounter-ice)."
  [{:keys [side run-phase state strategy gameid]}]
  (when (and (= side "runner")
             (= run-phase "encounter-ice")
             (:full-break strategy))
    (let [run (get-in state [:game-state :run])
          position (:position run)
          current-ice (core/current-run-ice state)
          subroutines (:subroutines current-ice)
          unbroken-subs (filter #(not (:broken %)) subroutines)]
      (when (and current-ice (:rezzed current-ice) (seq unbroken-subs))
        (let [ice-title (:title current-ice "ICE")
              runner-rig (get-in state [:game-state :runner :rig])
              all-programs (get runner-rig :program [])

              ;; DEBUG: Log what we see during encounter-ice
              _ (println (format "🔍 DEBUG --full-break: phase=%s ice=%s (rezzed=%s, %d unbroken subs)"
                                run-phase ice-title (:rezzed current-ice) (count unbroken-subs)))
              _ (doseq [p all-programs]
                  (println (format "🔍 DEBUG   %s abilities:" (:title p)))
                  (doseq [[idx ab] (map-indexed vector (:abilities p))]
                    (println (format "🔍 DEBUG     [%d] %s | playable=%s dynamic=%s"
                                    idx (:label ab) (:playable ab) (:dynamic ab)))))

              ;; Look for playable dynamic abilities (auto-pump-and-break)
              breakable-abilities
              (for [program all-programs
                    [idx ability] (map-indexed vector (:abilities program))
                    :when (and (:playable ability)
                               (:dynamic ability)
                               (when-let [dyn (:dynamic ability)]
                                 (clojure.string/includes? (str dyn) "break")))]
                {:card program
                 :card-name (:title program)
                 :ability-index idx
                 :label (:label ability)
                 :dynamic (:dynamic ability)})]
          (if (seq breakable-abilities)
            ;; Use the first available break ability
            (let [{:keys [card-name ability-index label]} (first breakable-abilities)]
              (reset! last-full-break-warning nil)
              (println (format "🔨 Auto-breaking %s with %s" ice-title card-name))
              (println (format "   Using: %s (ability %d)" label ability-index))
              (let [result (actions/use-ability! card-name ability-index)]
                (if (= :success (:status result))
                  {:status :ability-used
                   :message (format "Auto-broke %s with %s" ice-title card-name)
                   :ice ice-title
                   :breaker card-name}
                  nil)))
            ;; No playable dynamic ability - try manual pump+break fallback
            (if-let [fallback-result (tactics/try-manual-pump-and-break! state current-ice all-programs)]
              fallback-result
              ;; Fallback also failed - let subs fire instead of returning nil (which causes infinite loop)
              (let [warning-key [position ice-title]
                    already-signaled? (= @signaled-fire-position position)
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
                  (if (seq all-break-abilities)
                    (let [{:keys [card-name label cost-label]} (first all-break-abilities)]
                      (println (format "⚠️  --full-break: Can't afford to break %s, letting subs fire" ice-title))
                      (println (format "   %s has: %s (%s)" card-name label (or cost-label "cost unknown"))))
                    (println (format "⚠️  --full-break: No icebreaker can break %s, letting subs fire" ice-title))))
                ;; Signal to Corp that we're done breaking (same as tank-authorized path)
                (when-not already-signaled?
                  (reset! signaled-fire-position position)
                  (let-subs-fire-signal! gameid ice-title))
                ;; Return waiting-for-corp-fire so loop pauses correctly
                {:status :waiting-for-corp-fire
                 :message (format "Can't break %s, waiting for Corp to fire subs" ice-title)
                 :ice ice-title
                 :unbroken-count (count unbroken-subs)
                 :position position
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
                (println (format "   → Or break subs with: break \"%s\" <breaker>" ice-title)))
              {:status :fire-decision-required
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
