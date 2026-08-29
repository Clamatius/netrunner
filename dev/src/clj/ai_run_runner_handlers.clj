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
            [ai-state :as state]
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
  "Helper to send continue command and return action-taken result.

   Chokepoint guard (#75): never send while the LIVE state shows our own prompt
   is a 'waiting' prompt — the engine is mid-checkpoint on the OPPONENT and a
   continue from us re-fires that checkpoint, minting duplicate opponent
   prompts (the marquee-g2 wedge, mirrored to the Runner seat). Same guard as
   the ai-runs and ai-run-corp-handlers copies.

   Second guard (#98): if the engine already recorded US as this window's
   passer (:no-action names us), the opponent owes the window — a repeat
   continue is a no-op that only feeds the stuck-detector's false alarm."
  [gameid]
  (cond
    (state/waiting-prompt-type? (:prompt-type (state/get-prompt)))
    {:status :waiting-for-opponent
     :action :continue-suppressed-waiting-prompt
     :message "Own prompt is a waiting prompt — opponent is deciding; continue suppressed (#75)"}

    (core/i-already-passed-run-window? @state/client-state (:side @state/client-state))
    {:status :waiting-for-opponent
     :action :continue-suppressed-already-passed
     :message "You already passed this window (engine :no-action records you) — opponent owes the decision; continue suppressed (#98)"}

    :else
    (let [sent? (boolean (ws/send-message! :game/action
                                           {:gameid gameid
                                            :command "continue"
                                            :args nil}))]
      (Thread/sleep 100)
      ;; :sent — the Corp copy of this sender has carried it since #150; this
      ;; one did not, which is the N-senders-one-command shape again.
      ;; ws/send-message! reports a failed send (reconnect exhausted) as false,
      ;; and BOTH suppression branches above return without sending at all.
      ;; A caller that latches "I have passed here" on :action-taken alone
      ;; therefore latches on a pass the engine never received — and this
      ;; handler's re-entry branch then waits for a Corp reply forever
      ;; (guest panel CRITICAL, #167 review).
      {:status :action-taken
       :action :sent-continue
       :sent sent?})))

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

;; Track WHICH ENCOUNTER the Runner has signaled "let subs fire" on.
;; Keyed by core/encounter-key (the encountered ICE's cid, position only as a
;; fallback), not by :position: a FORCED encounter leaves :position pointing
;; somewhere else entirely — usually 0 — so two forced encounters in one run
;; shared a key and the second was silently treated as already signalled (#160).
(defonce signaled-fire-encounter (atom nil))

;; Track failed ability attempts per ENCOUNTERED CARD to detect unaffordable
;; abilities. Map of core/encounter-key -> count, cleared when the run ends.
;; Keyed by position until #160: two forced encounters both sit at :position 0,
;; so the second ICE inherited the first one's exhausted retry budget and got
;; tanked or paused without its breaker ever being tried (guest panel).
(defonce failed-ability-attempts (atom {}))

;; Track [encounter-key ice-title] where Runner has already sent its
;; pass-continue after all subs resolved (broken or fired). Lets us send continue
;; ONCE and then wait for the Corp's priority pass, instead of re-sending every
;; loop iteration (which mislabelled fired subs as "broken" and tripped the
;; stuck-state guard). Keyed by encounter identity rather than :position for the
;; same reason signaled-fire-encounter is (#160).
(defonce passed-ice-encounter (atom nil))

(defn reset-state!
  "Reset all Runner handler state atoms (called when run ends)."
  []
  (reset! last-waiting-status nil)
  (reset! last-full-break-warning nil)
  (reset! signaled-fire-encounter nil)
  (reset! failed-ability-attempts {})
  (reset! passed-ice-encounter nil))

;; ============================================================================
;; Auto-Select Single Card Prompts
;; ============================================================================

(defn handle-auto-select-single-card
  "Auto-select when there's exactly one selectable card.
   This handles credit source prompts (Overclock, Multithreader, etc.) where
   there's only one alternative credit pool - no need for manual selection.

   Returns nil if:
   - Not a select prompt
   - Multiple selectable cards (player must choose)
   - Zero selectable cards (shouldn't happen)"
  [{:keys [side my-prompt gameid]}]
  (when (and my-prompt
             (= "select" (:prompt-type my-prompt))
             (= 1 (count (:selectable my-prompt))))
    (let [selectable (:selectable my-prompt)
          eid (:eid my-prompt)
          cid-or-card (first selectable)
          card (if (string? cid-or-card)
                 (core/find-card-by-cid cid-or-card)
                 cid-or-card)
          card-title (or (:title card) "card")]
      (when card
        (println (format "✅ Auto-selecting: %s (only option)" card-title))
        ;; Use select-card! which properly formats the selection with eid
        (ws/select-card! card eid)
        (Thread/sleep 100)
        ;; Use :prompt-handled - progress but not run-phase progress
        ;; This avoids triggering stuck detection (which tracks run phase changes)
        {:status :prompt-handled
         :wake-reason :single-selectable
         :action :auto-selected
         :message (format "Auto-selected %s" card-title)
         :card-title card-title}))))

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
            (println (format "   %s"
                             (core/describe-approached-ice ice-title position ice-count))))
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

;; Forward declaration: ice-authorized-for-fire? is defined with the encounter
;; handlers below, but handle-runner-full-break needs it to honor `tank` in
;; full-break mode.
(declare ice-authorized-for-fire?)

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
             ;; at-encounter?, not the phase string: a FORCED encounter is live
             ;; while [:run :phase] reads "success", and the engine is perfectly
             ;; happy to break there (#160).
             (core/at-encounter? state run-phase)
             (:full-break strategy)
             ;; Don't break if there's an on-encounter prompt to handle first
             (not (has-real-decision? my-prompt)))
    (let [run (get-in state [:game-state :run])
          position (:position run)
          ;; The ENCOUNTERED ICE, not the position-derived one — a forced
          ;; encounter breaks the position assumption by construction (#100,
          ;; #152, #160).
          current-ice (core/encountered-ice state)
          subroutines (:subroutines current-ice)
          ;; Check both :broken and :fired flags for actionable subs
          unbroken-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)
          ice-title (:title current-ice "ICE")]
      ;; Also check log in case :fired flag isn't set by server
      (when (and (core/encounter-ice-active? state current-ice) (seq unbroken-subs)
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
              fail-count (get @failed-ability-attempts (core/encounter-key state) 0)
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
                (case (:status result)
                  :success
                  (do
                    ;; Success - clear failure count for this position
                    (swap! failed-ability-attempts dissoc (core/encounter-key state))
                    {:status :ability-used
                     :wake-reason :broke-ice
                     :message (format "Auto-broke %s with %s" ice-title card-name)
                     :ice ice-title
                     :breaker card-name})

                  ;; The break ability fired but spawned a sub-prompt that must be
                  ;; resolved first — most commonly "pay from which credit source?"
                  ;; when some credits are hosted on a card (e.g. Overclock). This is
                  ;; NOT a failure: return nil so the monitor loop auto-resolves the
                  ;; prompt and the break completes. Crucially, do NOT burn an
                  ;; unaffordable-retry on it (use-ability! already printed the
                  ;; "⏳ Ability triggered prompt" line).
                  :waiting-input
                  nil

                  ;; Genuine failure (:error) - increment failure count and return
                  ;; nil to retry. After max-retries, falls through to let-subs-fire.
                  (do
                    (swap! failed-ability-attempts update (core/encounter-key state) (fnil inc 0))
                    (println (format "❌ Ability failed (attempt %d/%d) - may be unaffordable"
                                   (inc fail-count) max-retries))
                    nil))))
            ;; No playable dynamic ability OR too many failures - try manual pump+break fallback
            (if-let [fallback-result (tactics/try-manual-pump-and-break! state current-ice all-programs)]
              fallback-result
              ;; Fallback also failed. If the Runner has authorized tank on this ICE
              ;; (human `tank` or the autonomous heuristic loop), resolve the encounter
              ;; by signaling let-subs-fire. Otherwise PAUSE and let the player decide.
              ;; NOTE: full-break previously ignored the :tank set entirely, so `tank`
              ;; was silently inert on full-break runs and the autonomous loop spun
              ;; forever on an unbreakable encounter.
              (if (and (ice-authorized-for-fire? strategy ice-title)
                       ;; …but never signal a Corp that has already passed this
                       ;; encounter. It is not going to fire, and the signal is a
                       ;; system-msg nobody is reading; returning
                       ;; :waiting-for-corp-fire here parked the seat forever
                       ;; against an opponent who had already left (guest panel
                       ;; CRITICAL, 3rd pass). Falling through reaches
                       ;; handle-runner-pass-broken-ice, which closes it.
                       (not (core/opponent-passed-encounter? state side)))
                (do
                  (when (not= @signaled-fire-encounter (core/encounter-key state))
                    (reset! signaled-fire-encounter (core/encounter-key state))
                    (println (format "📡 Signaling Corp: can't break %s, tank authorized - letting subs fire" ice-title))
                    (let-subs-fire-signal! gameid ice-title))
                  {:status :waiting-for-corp-fire
                   :wake-reason :waiting-for-opponent
                   :message (format "Can't break %s - tank authorized, waiting for Corp to fire subs" ice-title)
                   :ice ice-title
                   :unbroken-count (count unbroken-subs)
                   :position position})
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
                    ;; NOT jack-out: it is a movement-window action, illegal
                    ;; mid-encounter (board.cljs gates the button on
                    ;; phase == "movement"), and taking it here would skip the
                    ;; unbroken subs. `jack-out` now refuses with this same steer.
                    (println "     (you cannot jack out mid-encounter — tank through, then jack out")
                    (println "      at the next movement window if the entry cost was misjudged)")
                    (println "     (or wait for situation to change)"))
                  ;; Return paused status - don't send let-subs-fire signal
                  {:status :paused-cannot-break
                   :wake-reason :player-decision-required
                   :message (format "Can't afford to break %s - waiting for player decision" ice-title)
                   :ice ice-title
                   :unbroken-count (count unbroken-subs)
                   :position position
                   :credits runner-credits
                   :reason (if (seq all-break-abilities) :cant-afford :no-breaker)})))))))))

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
   SAFETY: Only signals if Runner explicitly authorized via --tank or --tank-all.
   Defers to on-encounter prompts (like Funhouse's 'Take 1 tag or end run') so they
   are surfaced as real decisions instead of being steamrolled into tank/jack-out."
  [{:keys [side run-phase state gameid strategy my-prompt]}]
  (when (and (= side "runner")
             ;; at-encounter?, not (= run-phase "encounter-ice"): this handler is
             ;; the ONLY thing that turns a `tank` authorization into the
             ;; system-msg the Corp reads, so a forced encounter (live while the
             ;; phase reads "success") made `tank` set a flag and send nothing —
             ;; both seats then waited on each other (#160).
             (core/at-encounter? state run-phase)
             (not (:full-break strategy))
             ;; An active on-encounter decision must be resolved before we treat
             ;; this as a subroutine fire decision (handle-real-decision handles it).
             (not (has-real-decision? my-prompt)))
    (let [run (get-in state [:game-state :run])
          position (:position run)
          current-ice (core/encountered-ice state)
          subroutines (:subroutines current-ice)
          unfired-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)
          ;; The ENCOUNTER's ledger, always — never the run's.
          ;;
          ;; game.core.runs `continue :encounter-ice` writes only
          ;; [:encounters :no-action]; set-phase resets the run-level key on every
          ;; phase entry and only `continue :initiation` / `continue :movement`
          ;; ever write it. So at a forced encounter the run-level key holds the
          ;; passer of the SUSPENDED outer window, which is not an answer about
          ;; this encounter at all.
          ;;
          ;; This read used to be run-level. That made corp-passed? false for the
          ;; whole of a normal encounter, which an existing test recorded as
          ;; intended behaviour — but the ENGINE disagrees, and the engine wins:
          ;; with the Corp recorded as the encounter's passer, a Runner `continue`
          ;; ends the encounter and the unbroken subs never fire. Proven in
          ;; game.ai-forced-encounter-wire-test for both the normal and the forced
          ;; case (guest panel CRITICAL, #160). Treating that state as "keep
          ;; waiting for the Corp to fire" was a stall in the one situation where
          ;; the Runner had a free pass available.
          no-action (get-in state [:game-state :encounters :no-action])
          no-action-str (normalize-side no-action)
          ;; Corp has passed this encounter: it is not going to fire, and our
          ;; continue closes the window. Fall through to handle-runner-pass-broken-ice,
          ;; which now accepts this state even with subs unbroken.
          corp-passed? (= no-action-str "corp")]
      (when (and (core/encounter-ice-active? state current-ice) (seq unfired-subs) (not corp-passed?))
        (let [ice-title (:title current-ice "ICE")
              sub-count (count unfired-subs)
              authorized? (ice-authorized-for-fire? strategy ice-title)
              enc-key (core/encounter-key state)
              status-key [:waiting-for-corp-fire enc-key ice-title]
              already-printed? (= @last-waiting-status status-key)
              already-signaled? (= @signaled-fire-encounter enc-key)]
          (if (not authorized?)
            ;; NOT authorized - pause and ask Runner to decide
            (do
              (when-not already-printed?
                (reset! last-waiting-status status-key)
                (println (format "⚠️  %s has %d unbroken sub%s - authorization required"
                               ice-title sub-count (if (= sub-count 1) "" "s")))
                (println "   Options:")
                (println (format "   → tank \"%s\"         - let subs fire, continue run" ice-title))
                (println "   → Or break: use-ability \"<breaker>\" <index>")
                (println "              (run 'abilities \"<breaker>\"' to see options)")
                ;; jack-out is deliberately absent — see
                ;; runner-encounter-decline-hint-lines: it is movement-window only
                ;; and would skip these very subroutines.
                (println "   (jack-out is NOT available mid-encounter — it is a movement-window action)"))
              {:status :fire-decision-required
               :wake-reason :decision-required
               :message (format "%s has %d unbroken sub(s) - break it or use 'tank' to let them fire" ice-title sub-count)
               :ice ice-title
               :unbroken-count sub-count
               :position position})
            ;; Authorized - send signal to Corp
            (do
              (when-not already-signaled?
                (reset! signaled-fire-encounter enc-key)
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
  "Priority 2.6: Runner at encounter-ice with nothing left to act on — every
   subroutine resolved (broken or fired), the Corp already gone, or the ICE never
   had a subroutine at all.
   Sends a single continue to pass our priority, then waits for the Corp to pass
   its priority. Re-sending continue every loop iteration (the old behavior) spun
   against the unchanged encounter-ice phase and tripped the stuck-state guard.

   No longer requires `(seq subroutines)` (#167). That guard read as a sanity
   check — an ICE with no subroutines looks like a summary we have not finished
   reading — but a Tour Guide with no rezzed assets HAS none, and the Corp's pass
   handler carried the same guard, so the window was owned by NEITHER seat and
   sat open until the 300s deadline. `encounter-ice-active?` is the discriminator
   the guard was reaching for: it demands a resolvable, engine-active ICE.
   game.ai-zero-sub-encounter-wire-test pins the premise that makes the swap
   sound — on a resolved encounter summary an absent :subroutines key means the
   ICE has none, never that we have not been told."
  [{:keys [side run-phase state gameid my-prompt]}]
  (when (and (= side "runner")
             ;; at-encounter?: a forced encounter ends the same way — both sides
             ;; pass and game.core.runs `continue :encounter-ice` closes it. Gated
             ;; on the phase, the Runner could never send that closing pass and
             ;; the encounter sat open (#160).
             (core/at-encounter? state run-phase)
             ;; Defer to handle-real-decision when a real prompt is pending (e.g. a
             ;; mid-subroutine "Jack out?" window) - passing here would mask it.
             (not (has-real-decision? my-prompt))
             ;; And to the opponent when our own prompt is a WAITING prompt: the
             ;; engine is mid-checkpoint on them and send-continue! will refuse
             ;; the send anyway. Mirroring that chokepoint as an outer guard is
             ;; the Corp handler's #150 rule — "nothing is printed that is not
             ;; going to be sent" — which this handler needed as soon as the
             ;; latch stopped being set before the send (guest panel MAJOR,
             ;; #167 round 3). A waiting prompt carries no choices, so
             ;; has-real-decision? does not cover it.
             (not (state/waiting-prompt-type? (:prompt-type my-prompt))))
    (let [current-ice (core/encountered-ice state)
          subroutines (:subroutines current-ice)
          actionable-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)
          ;; The Corp has passed this encounter, so our continue ENDS it and the
          ;; remaining subs never resolve (game.core.runs `continue
          ;; :encounter-ice`, pinned in game.ai-forced-encounter-wire-test). That
          ;; is a free pass, and refusing it left the Runner with no handler at
          ;; all — the window was nobody's (guest panel CRITICAL, #160).
          ;;
          ;; Taken unconditionally, and that is a judged tradeoff rather than an
          ;; oversight (#160, guest passes 2 and 3). The 2nd pass was right that
          ;; the pass forfeits a break which can be worth more than the tempo —
          ;; Hippo trashes the outermost ICE for a full break with no subroutine
          ;; resolving. The 2nd remediation therefore made this a reported
          ;; DECISION with its own status, and the 3rd pass found two deadlocks
          ;; in that addition: the autonomous loop's :continue action is a no-op
          ;; tick, so the decision status span forever, and --full-break masked
          ;; the new handler and signalled a Corp that had already left.
          ;;
          ;; A twice-patched addition gets removed, not patched again. Passing
          ;; here cannot lose the game: the subs do not fire either way, so the
          ;; worst case is a forgone break-trigger in a card interaction our
          ;; decks do not contain. A deadlock in the un-babysat path is strictly
          ;; worse than that. The forgone value is issue #165, and `status`
          ;; already tells a hand-driven seat it may break first.
          corp-declined? (core/opponent-passed-encounter? state side)]
      ;; No (seq subroutines) here — see the docstring. A zero-subroutine
      ;; encounter is a both-must-pass window like any other (#167).
      ;;
      ;; Passing first cedes the LAST WORD but not the response, and the
      ;; difference was measured against the engine rather than assumed: after
      ;; our pass the Corp can rez an asset, Tour Guide goes 0 -> 1 subroutines,
      ;; and the encounter's :no-action is never reset — but the encounter is
      ;; still open, a break still resolves (probed with Mimic: the new sub
      ;; broke), and handle-runner-encounter-ice above carries no pass-once
      ;; latch, so it re-surfaces the break/tank decision on the next tick.
      ;; What we give up is the tempo of answering first, which is the #165
      ;; ledger — not the ability to answer.
      (when (and (core/encounter-ice-active? state current-ice)
                 (or (empty? actionable-subs) corp-declined?))
        (let [ice-title (:title current-ice "ICE")
              position (get-in state [:game-state :run :position])
              all-fired? (every? :fired subroutines)
              pass-key [(core/encounter-key state) ice-title]
              ;; The latch is only ever evidence, and the LEDGER outranks it
              ;; (guest panel CRITICAL, #167 round 2). ws/send-message! returns
              ;; true when the socket ACCEPTED the message, not when the engine
              ;; processed it — its cursor wait can time out and it still
              ;; returns true — so a continue can be latched and lost. If the
              ;; encounter is still open and its :no-action names the OPPONENT,
              ;; our pass provably never landed: had it landed, their pass would
              ;; have ENDED the encounter and there would be no encounter here
              ;; to read. Re-send rather than wait for a reply to a pass the
              ;; engine never saw — the deadlock this issue exists to remove.
              ;;
              ;; This is also what keeps #163 (encounter-key is a card cid, not
              ;; an encounter identity) from being a hang on this path: a
              ;; Sisyphus re-encounter of the same card inherits the stale key,
              ;; but as soon as the Corp passes it, this override closes it.
              latch-is-stale? (core/opponent-passed-encounter? state side)]
          ;; The LEDGER naming us is as good as the latch, and better: the latch
          ;; is one slot, set by these two handlers only, so a pass sent by any
          ;; other sender (a hand-driven `continue`) or displaced by a second
          ;; encounter leaves it empty while the engine has our pass. Without
          ;; this the else branch printed "Runner passing ICE" and then had the
          ;; send suppressed by the #98 chokepoint — every tick, for up to 300s.
          ;; That is #150's print-then-suppressed burst, relocated to this seat
          ;; by the latch-after-send fix (guest panel MAJOR, #167 round 3).
          ;; Safe beside the override: the ledger cannot name us and the
          ;; opponent at once, so latch-is-stale? and this are exclusive.
          (if (and (or (= @passed-ice-encounter pass-key)
                       (core/i-already-passed-run-window? state side))
                   (not latch-is-stale?))
            ;; Already passed our priority here - wait for Corp, don't re-send.
            (let [status-key [:passed-ice pass-key]]
              (when-not (= @last-waiting-status status-key)
                (reset! last-waiting-status status-key)
                (println (format "⏸️  Passed %s, waiting for Corp to pass priority" ice-title)))
              ;; Return :waiting-for-opponent (a status auto-continue-loop! already
              ;; knows) so the loop stops cleanly with the "Corp should run
              ;; monitor-run" tip, rather than falling into its "unknown status,
              ;; stopping" branch.
              {:status :waiting-for-opponent
               :wake-reason :waiting-for-opponent
               :message (format "Waiting for Corp to pass priority after %s" ice-title)
               :ice ice-title
               :position position})
            ;; First time at this ICE/position - send one continue to pass.
            (do
              (println (cond
                         (and corp-declined? (seq actionable-subs))
                         (format "   → Corp declined to fire on %s — passing ends the encounter with %d sub(s) unresolved"
                                 ice-title (count actionable-subs))
                         ;; "All subs broken" is a claim about state, and with no
                         ;; subroutines at all it is a false one — (every? :fired [])
                         ;; and (every? :broken []) are both true, so the old
                         ;; formats would have reported breaking subs that never
                         ;; existed (#167).
                         (empty? subroutines)
                         (format "   → %s has no subroutines, Runner passing ICE" ice-title)
                         :else
                         (format "   → All subs %s on %s, Runner passing ICE"
                                 (if all-fired? "resolved" "broken") ice-title)))
              ;; Latch AFTER the send, on a send that actually happened. The
              ;; latch used to be set first, so a continue suppressed by
              ;; send-continue!'s own chokepoints — or lost to an exhausted
              ;; reconnect — still recorded us as having passed, and every later
              ;; tick took the branch above and waited for a Corp reply to a pass
              ;; the engine never saw. Same discipline the Corp's
              ;; latch-encounter-pass! has carried since #150.
              (let [r (send-continue! gameid)]
                (when (and (= :action-taken (:status r)) (:sent r))
                  (reset! passed-ice-encounter pass-key))
                r))))))))

(defn handle-runner-pass-fired-ice
  "Priority 2.7: Runner at encounter-ice after subs have fired.

   Passes AT MOST ONCE per [position ice] (#75, review finding): the
   subs-resolved? log heuristic stays true after our pass, so without the
   pass-once guard this handler re-sent continue every loop iteration while the
   Corp's window was still open — the same duplicate-continue spam class that
   minted the g2 Manegarm prompt stack, mirrored to the Runner seat. Shares
   `passed-ice-encounter` with handle-runner-pass-broken-ice: either path
   passing this ICE at this position means our priority here is spent."
  [{:keys [side run-phase state gameid my-prompt]}]
  (when (and (= side "runner")
             (core/at-encounter? state run-phase)
             ;; Defer to handle-real-decision when a real prompt is pending. The
             ;; subs-resolved? log heuristic matches a single "uses <ICE>" line, so
             ;; it fires on the FIRST sub of a multi-sub ICE (e.g. Karunā) while a
             ;; mid-subroutine "Jack out?" decision is still open - masking it.
             (not (has-real-decision? my-prompt))
             ;; Same waiting-prompt chokepoint mirror as its sibling above.
             (not (state/waiting-prompt-type? (:prompt-type my-prompt))))
    (let [current-ice (core/encountered-ice state)
          ice-title (:title current-ice "ICE")
          position (get-in state [:game-state :run :position])
          pass-key [(core/encounter-key state) ice-title]
          log (get-in state [:game-state :log])
          meaningful-log (filter-meaningful-log-entries (reverse log))
          recent-log (take 20 meaningful-log)
          subs-resolved? (some #(re-find (re-pattern (str "(?i)(resolves.*subroutines on|uses) " (java.util.regex.Pattern/quote ice-title)))
                                         (str (:text %)))
                               recent-log)]
      (when (and (core/encounter-ice-active? state current-ice) subs-resolved?)
        ;; Same stale-latch override as the sibling handler: the encounter's own
        ;; ledger naming the OPPONENT proves our pass never landed, because a
        ;; landed pass plus theirs would have ended the encounter.
        (if (and (or (= @passed-ice-encounter pass-key)
                     (core/i-already-passed-run-window? state side))
                 (not (core/opponent-passed-encounter? state side)))
          ;; Already passed our priority here - wait for Corp, don't re-send.
          (let [status-key [:passed-fired-ice pass-key]]
            (when-not (= @last-waiting-status status-key)
              (reset! last-waiting-status status-key)
              (println (format "⏸️  Passed %s (subs fired), waiting for Corp to pass priority" ice-title)))
            {:status :waiting-for-opponent
             :wake-reason :waiting-for-opponent
             :message (format "Waiting for Corp to pass priority after %s" ice-title)
             :ice ice-title
             :position position})
          (do
            (println (format "   → Subs resolved on %s, Runner passing ICE" ice-title))
            ;; Same latch-after-a-real-send discipline as its sibling above.
            ;; These two handlers SHARE passed-ice-encounter, so a dishonest
            ;; latch set here deadlocks the other one just as surely.
            (let [r (send-continue! gameid)]
              (when (and (= :action-taken (:status r)) (:sent r))
                (reset! passed-ice-encounter pass-key))
              r)))))))
