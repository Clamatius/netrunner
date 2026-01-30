(ns ai-run-corp-handlers
  "Corp-side run handlers - rez decisions, firing subroutines, priority passing.

   Extracted from ai-runs to reduce file size. These handlers are called from
   the handler chain in continue-run!.

   Handler contract:
   - Receives context map with :state, :side, :gameid, :run-phase, :my-prompt, :strategy, etc.
   - Returns nil to fall through to next handler
   - Returns result map {:status ... :action ...} to stop handler chain"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-core :as core]))

;; ============================================================================
;; Shared Helpers
;; ============================================================================

;; Use core/current-run-ice for ICE lookup (single source of truth)

(defn normalize-side
  "Normalize a side value to string. Handles keywords, strings, booleans, and nil."
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

(defn- runner-signaled-let-fire?
  "Check if Runner has signaled they're done breaking on current ICE.
   Looks for the specific system message in recent game log."
  [state ice-title]
  (let [log (get-in state [:game-state :log])
        meaningful (filter-meaningful-log-entries log)
        recent (take-last 20 meaningful)]
    (some #(and (clojure.string/includes? (str (:text %)) "indicates to fire")
                (clojure.string/includes? (str (:text %)) ice-title))
          recent)))

;; Track last waiting status to suppress repeated output (Corp-side)
(defonce last-waiting-status (atom nil))

;; ============================================================================
;; Corp Rez Handlers
;; ============================================================================

(defn handle-corp-rez-strategy
  "Priority 1.5: Corp rez strategy - auto-handle rez decisions based on --no-rez/--rez flags."
  [{:keys [side run-phase my-prompt strategy state gameid]}]
  (when (and (= side "corp")
             (= run-phase "approach-ice")
             my-prompt
             (or (:no-rez strategy) (:rez strategy)))
    (let [current-ice (core/current-run-ice state)
          ice-title (:title current-ice "ICE")
          ice-rezzed? (:rezzed current-ice)
          should-rez? (and (not (:no-rez strategy))
                          (:rez strategy)
                          (contains? (:rez strategy) ice-title)
                          (not ice-rezzed?))]
      (cond
        ;; --no-rez: always decline
        (:no-rez strategy)
        (let [position (get-in state [:game-state :run :position])
              status-key [:corp-no-rez position ice-title]
              already-printed? (= @last-waiting-status status-key)]
          (when-not already-printed?
            (reset! last-waiting-status status-key)
            (println (format "   Strategy: declining rez on %s" ice-title)))
          (merge (send-continue! gameid)
                 {:action :auto-declined-rez
                  :ice ice-title}))

        ;; --rez <ice-name>: rez if in set
        should-rez?
        (do
          (println (format "   Strategy: --rez, rezzing %s" ice-title))
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
              (println (format "   Could not find ICE to rez: %s" ice-title))
              {:status :decision-required
               :prompt my-prompt})))

        ;; --rez set exists but this ICE is already rezzed: just continue
        (and (:rez strategy) ice-rezzed?)
        (do
          (println (format "   ICE %s already rezzed, continuing" ice-title))
          (send-continue! gameid))

        ;; --rez set exists but this ICE not in it: decline
        :else
        (do
          (println (format "   Strategy: --rez (not %s), declining" ice-title))
          (merge (send-continue! gameid)
                 {:action :auto-declined-rez
                  :ice ice-title}))))))

(defn handle-corp-rez-decision
  "Priority 1.7: Corp at approach-ice WITHOUT strategy - pause for human decision."
  [{:keys [side run-phase my-prompt strategy state]}]
  (when (and (= side "corp")
             (= run-phase "approach-ice")
             my-prompt
             (not (:no-rez strategy))
             (not (:rez strategy)))
    (let [current-ice (core/current-run-ice state)]
      (when (and current-ice (not (:rezzed current-ice)))
        (let [ice-title (:title current-ice "ICE")
              position (get-in state [:game-state :run :position])
              status-key [:corp-rez-decision position ice-title]
              already-printed? (= @last-waiting-status status-key)]
          (when-not already-printed?
            (reset! last-waiting-status status-key)
            (println (format "Rez decision: %s (cost %d)" ice-title (get current-ice :cost 0)))
            (println "   Use continue with '--rez <name>' to rez, or '--no-rez' to decline"))
          {:status :decision-required
           :message (format "Corp must decide: rez %s or continue" ice-title)
           :ice ice-title
           :position position})))))

;; ============================================================================
;; Corp Fire Handlers
;; ============================================================================

(defn handle-corp-fire-unbroken
  "Priority 1.6: Corp fire-unbroken strategy - auto-fire unbroken subs.
   Waits for Runner's signal before firing (model-vs-model coordination)."
  [{:keys [side run-phase strategy state gameid]}]
  (when (and (= side "corp")
             (= run-phase "encounter-ice")
             (:fire-unbroken strategy))
    (let [run (get-in state [:game-state :run])
          position (:position run)
          already-fired-here? (= (:fired-at-position strategy) position)
          current-ice (core/current-run-ice state)
          ice-title (:title current-ice "ICE")
          subroutines (:subroutines current-ice)
          unbroken-subs (filter #(not (:broken %)) subroutines)
          runner-signaled? (runner-signaled-let-fire? state ice-title)]
      (cond
        already-fired-here? nil
        (nil? current-ice)
        (do (println "   --fire-unbroken: no ICE at current position") nil)
        (empty? unbroken-subs) nil
        (not runner-signaled?) nil
        :else
        (do
          (println (format "   Strategy: --fire-unbroken, firing %d sub(s) on %s (Runner signaled)"
                          (count unbroken-subs) ice-title))
          ;; Note: caller must call set-strategy! to mark fired-at-position
          ;; We return the position so ai_runs can update it
          (let [card-ref (core/create-card-ref current-ice)]
            (ws/send-message! :game/action
                             {:gameid gameid
                              :command "unbroken-subroutines"
                              :args {:card card-ref}})
            {:status :action-taken
             :action :auto-fired-subs
             :ice ice-title
             :sub-count (count unbroken-subs)
             :fired-at-position position}))))))

(defn handle-corp-fire-decision
  "Priority 1.7: Corp at encounter-ice WITHOUT fire strategy - pause for human decision.
   Returns :decision-required if Runner has signaled, :waiting-for-runner-signal otherwise."
  [{:keys [side run-phase my-prompt strategy state]}]
  (when (and (= side "corp")
             (= run-phase "encounter-ice")
             my-prompt
             (not (:fire-unbroken strategy)))
    (let [current-ice (core/current-run-ice state)
          subroutines (:subroutines current-ice)
          unbroken-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)]
      (when (and current-ice (seq unbroken-subs))
        (let [ice-title (:title current-ice "ICE")
              sub-count (count unbroken-subs)
              position (get-in state [:game-state :run :position])
              runner-signaled? (runner-signaled-let-fire? state ice-title)
              status-key [:corp-fire-decision position ice-title runner-signaled?]
              already-printed? (= @last-waiting-status status-key)]
          (if runner-signaled?
            ;; Runner signaled - Corp must decide NOW
            (do
              (when-not already-printed?
                (reset! last-waiting-status status-key)
                (println (format "Subs unbroken: %s (%d sub%s)"
                               ice-title sub-count (if (= sub-count 1) "" "s")))
                (println "   Runner has signaled 'let subs fire'")
                (println "   fire-subs <name>  - fire the unbroken subs")
                (println "   continue          - pass without firing"))
              {:status :decision-required
               :message (format "Corp must decide: fire %d sub(s) on %s or continue" sub-count ice-title)
               :ice ice-title
               :unbroken-count sub-count
               :position position})
            ;; Runner hasn't signaled - keep waiting (auto-continue loop will poll)
            (do
              (when-not already-printed?
                (reset! last-waiting-status status-key)
                (println (format "⏳ Waiting for Runner to break or signal on %s (%d unbroken sub%s)..."
                               ice-title sub-count (if (= sub-count 1) "" "s"))))
              {:status :waiting-for-runner-signal
               :message (format "Waiting for Runner to break or signal on %s" ice-title)
               :ice ice-title
               :unbroken-count sub-count
               :position position})))))))

(defn handle-corp-all-subs-resolved
  "Priority 1.74: Corp at encounter-ice when all subs are resolved (broken or fired)."
  [{:keys [side run-phase state gameid]}]
  (when (and (= side "corp")
             (= run-phase "encounter-ice"))
    (let [current-ice (core/current-run-ice state)
          subroutines (:subroutines current-ice)
          actionable-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)]
      (when (and current-ice (:rezzed current-ice) (seq subroutines) (empty? actionable-subs))
        (let [ice-title (:title current-ice "ICE")
              all-broken? (every? :broken subroutines)]
          (println (format "   All subs %s on %s, Corp continuing"
                          (if all-broken? "broken" "resolved") ice-title))
          (send-continue! gameid))))))

(defn handle-corp-waiting-after-subs-fired
  "Priority 1.75: Corp at encounter-ice after subs have fired.
   If Runner hasn't passed yet, wait. If Runner already passed, continue."
  [{:keys [side run-phase state gameid]}]
  (when (and (= side "corp")
             (= run-phase "encounter-ice"))
    (let [current-ice (core/current-run-ice state)
          ice-title (:title current-ice "ICE")
          log (get-in state [:game-state :log])
          recent-log (take 10 (reverse log))
          subs-resolved? (some #(re-find (re-pattern (str "(?i)resolves.*subroutines on " ice-title))
                                         (str (:text %)))
                               recent-log)]
      (when (and current-ice subs-resolved?)
        (let [recent-entries (take 5 (reverse log))
              runner-passed? (some #(re-find #"(?i)ai-runner has no further action" (str (:text %))) recent-entries)
              position (get-in state [:game-state :run :position])]
          (if runner-passed?
            (do
              (println (format "   Runner passed, Corp continuing past %s" ice-title))
              (send-continue! gameid))
            (let [status-key [:corp-waiting-after-fire position ice-title]
                  already-printed? (= @last-waiting-status status-key)]
              (when-not already-printed?
                (reset! last-waiting-status status-key)
                (println (format "   Waiting for Runner to continue past %s (subs resolved)" ice-title)))
              {:status :waiting-for-opponent
               :message (format "Waiting for Runner to continue past %s" ice-title)
               :phase run-phase})))))))

;; ============================================================================
;; General Priority Passing
;; ============================================================================

(defn handle-paid-ability-window
  "Priority 1.8: General handler for paid ability windows in ALL phases.
   Detects when we've passed priority but opponent hasn't yet.
   Uses :no-action state as source of truth."
  [{:keys [side run-phase state]}]
  (let [run (get-in state [:game-state :run])
        no-action (:no-action run)
        no-action-str (normalize-side no-action)
        opp-side (core/other-side side)
        we-passed? (= no-action-str side)
        opp-passed? (= no-action-str opp-side)]
    (when (and we-passed? (not opp-passed?))
      (let [status-key [:waiting-for-opponent-paid-ability run-phase side]
            already-printed? (= @last-waiting-status status-key)]
        (when-not already-printed?
          (reset! last-waiting-status status-key)
          (println (format "   Waiting for %s paid abilities (%s phase)"
                          (clojure.string/capitalize opp-side) run-phase)))
        {:status :waiting-for-opponent-paid-abilities
         :message (format "Waiting for %s to pass or use paid abilities" opp-side)
         :phase run-phase
         :we-passed true}))))

;; ============================================================================
;; Utility for ai-runs integration
;; ============================================================================

(defn reset-waiting-status!
  "Reset the last-waiting-status atom (called when run ends or new run starts)."
  []
  (reset! last-waiting-status nil))
