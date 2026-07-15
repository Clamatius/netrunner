(ns ai-run-corp-handlers
  "Corp-side run handlers - rez decisions, firing subroutines, priority passing.

   Extracted from ai-runs to reduce file size. These handlers are called from
   the handler chain in continue-run!.

   Handler contract:
   - Receives context map with :state, :side, :gameid, :run-phase, :my-prompt, :strategy, etc.
   - Returns nil to fall through to next handler
   - Returns result map {:status ... :action ...} to stop handler chain"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-core :as core]
            [ai-state :as state]
            [ai-run-corp-decisions :as decisions]))

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
          position (get-in state [:game-state :run :position])
          ;; We mark a rez attempt per-position. If we already attempted to rez
          ;; this ICE and it's STILL unrezzed on a later pass, the rez did not
          ;; take — almost always because the Corp can't afford the effective
          ;; cost (e.g. a Tread Lightly run adds +3 to every ICE's rez cost).
          ;; Without this guard the handler re-sends the rez every iteration and
          ;; the run wedges until the stuck-detector trips.
          rez-already-attempted? (= (:rez-attempted-at strategy) position)
          should-rez? (and (not (:no-rez strategy))
                          (:rez strategy)
                          (contains? (:rez strategy) ice-title)
                          (not ice-rezzed?))]
      (cond
        ;; --no-rez: always decline
        (:no-rez strategy)
        (let [status-key [:corp-no-rez position ice-title]
              already-printed? (= @last-waiting-status status-key)]
          (when-not already-printed?
            (reset! last-waiting-status status-key)
            (println (format "   Strategy: declining rez on %s" ice-title)))
          (merge (send-continue! gameid)
                 {:action :auto-declined-rez
                  :ice ice-title}))

        ;; Wanted to rez, already tried, ICE still unrezzed → the rez failed.
        ;; Report once and decline so the run proceeds instead of looping.
        (and should-rez? rez-already-attempted?)
        (let [base-cost (get current-ice :cost 0)
              credits (get-in state [:game-state :corp :credit] 0)
              status-key [:corp-rez-failed position ice-title]
              already-printed? (= @last-waiting-status status-key)]
          (when-not already-printed?
            (reset! last-waiting-status status-key)
            (println (format "   ⚠️  Rez of %s did not take — likely can't afford it (base cost %d, Corp has %d, plus any run surcharge such as Tread Lightly's +3). Declining."
                            ice-title base-cost credits)))
          (merge (send-continue! gameid)
                 {:action :rez-failed-declined
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
               :ice ice-title
               ;; Persisted by the wrapper in ai_runs so a failed (unaffordable)
               ;; rez is detected next pass instead of retried forever.
               :rez-attempted-at position})
            (do
              (println (format "   Could not find ICE to rez: %s" ice-title))
              {:status :decision-required
               :prompt my-prompt})))

        ;; --rez set exists but this ICE is already rezzed: just continue
        (and (:rez strategy) ice-rezzed?)
        (do
          (println (format "   ICE %s already rezzed, continuing" ice-title))
          (send-continue! gameid))

        ;; --rez set exists but THIS unrezzed ICE is not in it: pause for a
        ;; decision rather than silently auto-declining. The old behaviour sent
        ;; `continue` and never handed control back, so on a multi-ICE server a
        ;; Corp that committed `--rez "<outer>"` never got to rez/fire its inner
        ;; ICE (marquee g3: inner Tithe never fired in the two runs that lost the
        ;; game). That violates the --persistent contract — "returns to you for a
        ;; real rez/fire decision": a 2nd unrezzed ICE the Corp hasn't spoken to
        ;; IS a real decision. Surface it like the no-strategy approach-ice path
        ;; (handle-corp-rez-decision); the Corp then rezzes it (plain `rez` /
        ;; re-enter `--rez "<this>"`) or declines for the rest of the run
        ;; (`--no-rez`). Print once per (position, ice) to avoid spam on re-entry.
        :else
        (let [status-key [:corp-rez-strategy-decision position ice-title]
              already-printed? (= @last-waiting-status status-key)]
          (when-not already-printed?
            (reset! last-waiting-status status-key)
            (println (format "   Rez decision: %s — approaching an unrezzed ICE not in your --rez list." ice-title))
            (println (format "      continue --rez \"%s\"   - rez it (keeps owning the run)" ice-title))
            (println           "      continue --no-rez       - decline this and the rest of the run"))
          {:status :decision-required
           :wake-reason :rez-ice
           :ice ice-title
           :position position
           :prompt my-prompt
           :message (format "Corp must decide: rez %s or continue" ice-title)})))))

(defn handle-corp-rez-decision
  "Priority 1.7: Corp at approach-ice WITHOUT strategy - pause for human decision."
  [{:keys [side strategy state]}]
  (when (and (= side "corp")
             (not (:no-rez strategy))
             (not (:rez strategy)))
    (let [decision (decisions/corp-run-decision state)]
      (when (= :rez-ice (:kind decision))
        (let [ice-title (get-in decision [:ice :title] "ICE")
              position (get-in decision [:ice :position])
              status-key [:corp-rez-decision position ice-title]
              already-printed? (= @last-waiting-status status-key)]
          (when-not already-printed?
            (reset! last-waiting-status status-key)
            (doseq [line (decisions/present-corp-run-decision decision)]
              (println line))
            (when-let [run-source (get-in state [:game-state :run :source-card :title])]
              (println (format "   Run started by: %s" run-source))))
          {:status :decision-required
           :wake-reason (:wake-reason decision)
           :decision decision
           :message (format "Corp must decide: rez %s or continue" ice-title)
           :ice ice-title
           :position position})))))

;; ============================================================================
;; Corp Fire Handlers
;; ============================================================================

(defn fire-unbroken-strategy-result
  "Pure: given whether the fired subroutine opened a NEW prompt the Corp must
   resolve (computed by the caller via core/new-prompt? to dodge stale
   prompt-state), decide what the autonomous --fire-unbroken strategy auto-fire
   should print and return.

   This is the strategy-path twin of card-actions' fire-subs-report (issue #24).
   The manual fire-subs path already surfaces a sub-opened prompt honestly; the
   pre-committed --fire-unbroken *strategy* used to send the command and return
   :action-taken unconditionally, so when a fired sub opens a prompt (e.g. Brân
   1.0's \"install an ice from HQ/Archives\" sub) the run loop marched on while
   the Corp sat on an unhandled prompt — the same flow-stall class #23 fixed, on
   the un-babysat path.

   Both branches keep :fired-at-position so the caller still records it (the ICE
   was fired; re-entry must not re-fire). Only :status differs: :decision-required
   pauses the loop to resolve the opened prompt; :action-taken lets it continue.

   Returns {:lines [str...] :result <status-map>}."
  [ice-title sub-count position new-prompt]
  (let [base {:action :auto-fired-subs
              :ice ice-title
              :sub-count sub-count
              :fired-at-position position}]
    (if (some? new-prompt)
      {:lines [(format "⏸️  A subroutine on %s opened a prompt the Corp must resolve: %s"
                       ice-title (:msg new-prompt))
               "   Resolve it (choose-value \"<label>\" / choose-card <N>), then continue the run."]
       :result (assoc base
                      :status :decision-required
                      :wake-reason :sub-opened-prompt
                      :prompt new-prompt)}
      {:lines []
       :result (assoc base :status :action-taken)})))

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
          ;; A :fired sub is RESOLVED, not fireable — exclude it, matching the
          ;; engine's resolve-unbroken-subs! and the sibling fire handlers. Without
          ;; this, a post-fire re-entry saw the fired sub as still fireable and
          ;; (when :fired-at-position was stale) re-sent the fire command, firing
          ;; the same sub twice. #71 (Diviner: 2 net damage from one subroutine).
          unbroken-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)
          runner-signaled? (decisions/runner-signaled-let-fire? state ice-title)]
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
          ;; (result carries it in both branches). We capture the pre-fire prompt
          ;; so that — if a fired sub OPENS a prompt (issue #24) — we surface it as
          ;; :decision-required instead of letting the loop march on (the same
          ;; honest-reporting the manual fire-subs path got in #23).
          (let [card-ref (core/create-card-ref current-ice)
                old-prompt (state/get-prompt)]
            (ws/send-message! :game/action
                             {:gameid gameid
                              :command "unbroken-subroutines"
                              :args {:card card-ref}})
            (Thread/sleep core/medium-delay)
            (let [cur-prompt (state/get-prompt)
                  ;; eid-aware so a stale leftover prompt isn't mistaken for a
                  ;; sub-opened one (see core/new-prompt? rationale).
                  new-prompt (when (core/new-prompt? old-prompt cur-prompt) cur-prompt)
                  {:keys [lines result]} (fire-unbroken-strategy-result
                                          ice-title (count unbroken-subs) position new-prompt)]
              (doseq [l lines] (println l))
              result)))))))

(defn handle-corp-fire-decision
  "Priority 1.7: Corp at encounter-ice WITHOUT fire strategy - pause for human decision.
   Returns :decision-required if Runner has signaled, :waiting-for-runner-signal otherwise."
  [{:keys [side strategy state]}]
  (when (and (= side "corp")
             (not (:fire-unbroken strategy)))
    (let [decision (decisions/corp-run-decision state)]
      (when (contains? #{:fire-unbroken :waiting-runner-signal} (:kind decision))
        (let [ice-title (get-in decision [:ice :title] "ICE")
              sub-count (get-in decision [:ice :unbroken-count] 0)
              position (get-in decision [:ice :position])
              status-key [:corp-fire-decision position ice-title (:kind decision)]
              already-printed? (= @last-waiting-status status-key)]
          (case (:kind decision)
            :fire-unbroken
            (do
              (when-not already-printed?
                (reset! last-waiting-status status-key)
                (doseq [line (decisions/present-corp-run-decision decision)]
                  (println line)))
              {:status :decision-required
               :wake-reason (:wake-reason decision)
               :decision decision
               :message (format "Corp must decide: fire %d sub(s) on %s or continue" sub-count ice-title)
               :ice ice-title
               :unbroken-count sub-count
               :position position})

            :waiting-runner-signal
            (do
              (when-not already-printed?
                (reset! last-waiting-status status-key)
                (doseq [line (decisions/present-corp-run-decision decision)]
                  (println (str "⏳ " line "..."))))
              {:status :waiting-for-runner-signal
               :wake-reason (:wake-reason decision)
               :decision decision
               :message (format "Waiting for Runner to break or signal on %s" ice-title)
               :ice ice-title
               :unbroken-count sub-count
               :position position})))))))

(defn handle-corp-fire-if-asked
  "Priority 1.65: Corp --fire-if-asked strategy - sleeps through run, wakes only for rez.
   Unlike --fire-unbroken which wakes for each fire decision, this:
   1. Silently waits while Runner breaks (no output)
   2. Auto-fires when Runner signals
   3. Auto-continues through empty windows
   4. ALWAYS wakes for rez decisions (rez is a real choice)"
  [{:keys [side run-phase my-prompt strategy state gameid]}]
  (when (and (= side "corp")
             (:fire-if-asked strategy))
    (let [run (get-in state [:game-state :run])
          position (:position run)
          current-ice (core/current-run-ice state)]
      (cond
        ;; Approach-ice with unrezzed ICE - WAKE UP for rez decision
        ;; Unless --rez or --no-rez strategy already handles it
        (and (= run-phase "approach-ice")
             current-ice
             (not (:rezzed current-ice))
             (not (:no-rez strategy))
             (not (:rez strategy)))
        nil  ; Fall through to rez-decision handler

        ;; Encounter-ice - check for unbroken subs
        (= run-phase "encounter-ice")
        (let [ice-title (:title current-ice "ICE")
              subroutines (:subroutines current-ice)
              unbroken-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)
              runner-signaled? (decisions/runner-signaled-let-fire? state ice-title)
              already-fired-here? (= (:fired-at-position strategy) position)]
          (cond
            ;; Already fired at this position - continue
            already-fired-here?
            nil

            ;; No unbroken subs - silently continue
            (or (nil? current-ice) (empty? unbroken-subs))
            nil

            ;; Runner signaled - auto-fire!
            runner-signaled?
            (do
              (println (format "   --fire-if-asked: Runner signaled, firing %d sub(s) on %s"
                              (count unbroken-subs) ice-title))
              ;; Same honest-prompt detection as --fire-unbroken (issue #24): a
              ;; fired sub can open a Corp prompt, which must surface as
              ;; :decision-required rather than letting the loop march on.
              (let [card-ref (core/create-card-ref current-ice)
                    old-prompt (state/get-prompt)]
                (ws/send-message! :game/action
                                 {:gameid gameid
                                  :command "unbroken-subroutines"
                                  :args {:card card-ref}})
                (Thread/sleep core/medium-delay)
                (let [cur-prompt (state/get-prompt)
                      new-prompt (when (core/new-prompt? old-prompt cur-prompt) cur-prompt)
                      {:keys [lines result]} (fire-unbroken-strategy-result
                                              ice-title (count unbroken-subs) position new-prompt)]
                  (doseq [l lines] (println l))
                  result)))

            ;; Runner hasn't signaled yet - silently wait (poll again)
            :else
            {:status :waiting-for-runner-signal
             :wake-reason :waiting-for-opponent
             :message (format "Waiting for Runner to break or signal on %s" ice-title)
             :ice ice-title
             :position position}))

        ;; Other phases with prompt but no real decision - auto-continue
        ;; BUT NOT during success/access phases where Runner is active and Corp just waits
        (and my-prompt
             (empty? (:choices my-prompt))
             (empty? (:selectable my-prompt))
             (not (#{"success" "access"} run-phase)))
        (do
          (ws/send-message! :game/action
                           {:gameid gameid
                            :command "continue"
                            :args nil})
          (Thread/sleep 100)
          {:status :action-taken
           :action :auto-continue-fire-if-asked})

        ;; Default - don't handle, let other handlers run
        :else nil))))

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
               :wake-reason :waiting-for-opponent
               :message (format "Waiting for Runner to continue past %s" ice-title)
               :phase run-phase})))))))

(defn handle-corp-server-upgrade-decision
  "Wake before access when an unrezzed upgrade in the attacked server may matter.

   Respects --no-rez (#57): --no-rez is a standing 'decline every rez' commitment,
   so at a pre-access upgrade window we fall through (return nil) and let the
   normal empty-run-window auto-pass advance the run — exactly like an
   approach-ice rez window under --no-rez. Without it we'd re-present the same
   'Server upgrade decision' every iteration (only a raw pass advanced it), a
   wedge risk for an autonomous Corp seat on `monitor-run --persistent --no-rez`.
   With no decline commitment, still wake so a meaningful pre-access rez (e.g.
   Manegarm Skunkworks, which must be rezzed BEFORE access) is never skipped."
  [{:keys [side state strategy]}]
  (when (and (= side "corp")
             (not (:no-rez strategy)))
    (let [decision (decisions/corp-run-decision state)]
      (when (= :server-upgrade (:kind decision))
        (let [card-title (get-in decision [:card :title] "upgrade")
              status-key [:corp-server-upgrade-decision (:server decision) card-title]
              already-printed? (= @last-waiting-status status-key)]
          (when-not already-printed?
            (reset! last-waiting-status status-key)
            (doseq [line (decisions/present-corp-run-decision decision)]
              (println line)))
          {:status :decision-required
           :wake-reason (:wake-reason decision)
           :decision decision
           :message (format "Corp must decide: rez %s before access or continue" card-title)
           :card card-title
           :server (:server decision)})))))

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
         :wake-reason :waiting-for-opponent
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
