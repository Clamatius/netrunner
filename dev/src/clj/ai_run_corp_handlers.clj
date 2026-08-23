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
  "Helper to send continue command and return action-taken result.

   Chokepoint guard (#75): consult the LIVE state at send time — if our own
   prompt is a 'waiting' prompt, the engine is mid-checkpoint on the OPPONENT
   (e.g. blocked on the Runner's Manegarm Skunkworks 'Choose one' at
   :approach-server) and a continue from us is never legitimate. The engine has
   no in-flight guard there: each duplicate continue re-fires the checkpoint and
   mints a duplicate opponent prompt (marquee g2 wedge — five stacked Manegarm
   prompts). Suppress and report an opponent wait so loops idle instead of spin.

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
      ;; :sent — ws/send-message! reports a failed send (reconnect exhausted)
      ;; as false; callers that LATCH on having passed must key on this, not
      ;; on :action-taken (guest panel, second pass).
      {:status :action-taken
       :action :sent-continue
       :sent sent?})))

;; Track last waiting status to suppress repeated output (Corp-side)
(defonce last-waiting-status (atom nil))

;; {:key [encounter-key ice-cid] :at ms} of the encounter whose closing pass THIS
;; seat has already SENT (#150 guest finding): the engine acks a `continue`
;; through a WebSocket diff, and until it lands [:encounters :no-action] still
;; reads as un-passed, so an ack-based guard alone re-sends (and re-prints)
;; every loop tick in that window — the short burst variant, which can trip the
;; stuck detector. Corp twin of runner-handlers/passed-ice-encounter.
;;
;; TIME-BOUNDED (second guest pass): the latch exists only to cover the ack
;; window, so it is honoured for encounter-pass-latch-ms and then ignored. That
;; turns every stale-latch failure mode — a send the socket dropped, a missed
;; run-boundary reset, a later run meeting the same ICE at the same position —
;; into a bounded idle instead of a stall; after it lapses the worst case is
;; the pre-existing one (a duplicate continue the engine treats as a no-op).
;; Still reset per run (start + end) as belt to the braces.
(defonce passed-encounter-key (atom nil))

(def encounter-pass-latch-ms
  "How long a sent-but-unacked encounter pass suppresses a re-send. Longer than
   the loop's stuck window (5 ticks of quick-delay + 100ms), far shorter than
   the 300s wait it protects."
  15000)

(defn- passed-encounter-recently?
  [pass-key]
  (let [{:keys [key at]} @passed-encounter-key]
    (boolean (and (= key pass-key) at
                  (< (- (System/currentTimeMillis) at) encounter-pass-latch-ms)))))

(defn- latch-encounter-pass!
  "Record that the closing pass for `pass-key` went out — only on a send that
   actually left the socket."
  [pass-key sent?]
  (when sent?
    (reset! passed-encounter-key {:key pass-key :at (System/currentTimeMillis)})))

(defn reset-state!
  "Reset the Corp handler per-run atoms (run start / run end)."
  []
  (reset! last-waiting-status nil)
  (reset! passed-encounter-key nil))

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

        ;; --rez set exists and this ICE is rezzed: just continue. Two honest
        ;; wordings — if WE attempted the rez at this position, this pass is the
        ;; confirmation of a FRESH rez, and "already rezzed" reads like the rez
        ;; was redundant or didn't happen (misleading-output class, marquee
        ;; Opus↔Terra game B). Print once per (position, ice): the continue
        ;; doesn't advance state instantly, so an unguarded print fired ×2.
        (and (:rez strategy) ice-rezzed?)
        (let [status-key [:corp-rez-done position ice-title]
              already-printed? (= @last-waiting-status status-key)]
          (when-not already-printed?
            (reset! last-waiting-status status-key)
            (if (= (:rez-attempted-at strategy) position)
              (println (format "   ✅ Rez confirmed: %s is rezzed — continuing" ice-title))
              (println (format "   ICE %s was already rezzed, continuing" ice-title))))
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

   Both branches keep :fired-at-encounter so the caller still records it (the ICE
   was fired; re-entry must not re-fire). Only :status differs: :decision-required
   pauses the loop to resolve the opened prompt; :action-taken lets it continue.

   A NEW prompt of type \"waiting\" is the OPPONENT's decision, not ours (#151
   item 3): Karunā's first sub hands the Runner 'trash 2 / jack out?', and our
   side sees a fresh eid'd 'Waiting for Runner to make a decision'. Announcing
   that as 'a prompt the Corp must resolve' with choose-value steering was a
   prompt-ownership lie that could have had the Corp poking at the Runner's
   prompt. It is an opponent wait (the loop idles on it), with the fire
   recorded.

   Returns {:lines [str...] :result <status-map>}."
  [ice-title sub-count enc-key new-prompt]
  (let [base {:action :auto-fired-subs
              :ice ice-title
              :sub-count sub-count
              :fired-at-encounter enc-key}]
    (cond
      (and (some? new-prompt) (state/waiting-prompt-type? (:prompt-type new-prompt)))
      {:lines [(format "⏳ A subroutine on %s handed the RUNNER a decision: %s"
                       ice-title (:msg new-prompt))
               "   Nothing to resolve on your side — waiting for the Runner."]
       :result (assoc base
                      :status :waiting-for-opponent
                      :wake-reason :waiting-for-opponent
                      :prompt new-prompt)}

      (some? new-prompt)
      {:lines [(format "⏸️  A subroutine on %s opened a prompt the Corp must resolve: %s"
                       ice-title (:msg new-prompt))
               "   Resolve it (choose-value \"<label>\" / choose-card <N>), then continue the run."]
       :result (assoc base
                      :status :decision-required
                      :wake-reason :sub-opened-prompt
                      :prompt new-prompt)}

      :else
      {:lines []
       :result (assoc base :status :action-taken)})))

(defn handle-corp-fire-unbroken
  "Priority 1.6: Corp fire-unbroken strategy - auto-fire unbroken subs.
   Waits for Runner's signal before firing (model-vs-model coordination)."
  [{:keys [side run-phase strategy state gameid]}]
  (when (and (= side "corp")
             ;; at-encounter?, not the phase string: the Corp twin of the Runner's
             ;; tank gap (#160). A forced encounter reads phase "success", so a
             ;; Corp running `monitor-run --persistent --fire-unbroken` never
             ;; recognised the fire decision and both seats waited on each other.
             (core/at-encounter? state run-phase)
             (:fire-unbroken strategy))
    (let [run (get-in state [:game-state :run])
          position (:position run)
          enc-key (core/encounter-key state)
          ;; Keyed on encounter IDENTITY, not :position: a forced encounter
          ;; leaves :position pointing elsewhere, so a position-keyed latch both
          ;; misses re-entries and blocks a genuinely new encounter (#160).
          already-fired-here? (= (:fired-at-encounter strategy) enc-key)
          current-ice (core/encountered-ice state)
          ice-title (:title current-ice "ICE")
          subroutines (:subroutines current-ice)
          ;; A :fired sub is RESOLVED, not fireable — exclude it, matching the
          ;; engine's resolve-unbroken-subs! and the sibling fire handlers. Without
          ;; this, a post-fire re-entry saw the fired sub as still fireable and
          ;; (when the fired-at latch was stale) re-sent the fire command, firing
          ;; the same sub twice. #71 (Diviner: 2 net damage from one subroutine).
          unbroken-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)
          runner-signaled? (decisions/runner-signaled-let-fire? state ice-title)]
      (cond
        already-fired-here? nil
        (nil? current-ice)
        (do (println "   --fire-unbroken: no ICE is being encountered") nil)
        (empty? unbroken-subs) nil
        (not runner-signaled?) nil
        :else
        (do
          (println (format "   Strategy: --fire-unbroken, firing %d sub(s) on %s (Runner signaled)"
                          (count unbroken-subs) ice-title))
          ;; Note: caller must call set-strategy! to mark fired-at-encounter
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
                                          ice-title (count unbroken-subs) enc-key new-prompt)]
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
          current-ice (core/encountered-ice state)]
      (cond
        ;; Approach-ice with unrezzed ICE - WAKE UP for rez decision
        ;; Unless --rez or --no-rez strategy already handles it
        (and (= run-phase "approach-ice")
             current-ice
             (not (:rezzed current-ice))
             (not (:no-rez strategy))
             (not (:rez strategy)))
        nil  ; Fall through to rez-decision handler

        ;; Any live encounter — the normal phase OR a forced one (#160)
        (core/at-encounter? state run-phase)
        (let [ice-title (:title current-ice "ICE")
              subroutines (:subroutines current-ice)
              unbroken-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)
              runner-signaled? (decisions/runner-signaled-let-fire? state ice-title)
              enc-key (core/encounter-key state)
              already-fired-here? (= (:fired-at-encounter strategy) enc-key)]
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
                                              ice-title (count unbroken-subs) enc-key new-prompt)]
                  (doseq [l lines] (println l))
                  result)))

            ;; Runner hasn't signaled yet - silently wait (poll again)
            :else
            {:status :waiting-for-runner-signal
             :wake-reason :waiting-for-opponent
             :message (format "Waiting for Runner to break or signal on %s" ice-title)
             :ice ice-title
             :position position}))

        ;; movement/pos-0 with an unrezzed upgrade in the attacked server's
        ;; root — the Manegarm window (#94). This is the Corp's LAST chance to
        ;; rez an approach-triggered upgrade (#67); sleep mode's own contract
        ;; is "ALWAYS wakes for rez decisions" and this IS one. Fall through so
        ;; handle-corp-server-upgrade-decision (later in the chain) can
        ;; auto-rez (--rez), or surface the decision. Without this guard the
        ;; empty-window branch below auto-continued through the window — even
        ;; past an explicit --rez "Manegarm Skunkworks" commitment (marquee
        ;; 6d8f4cf8, both seats observed the silent skip independently).
        ;; --no-rez keeps sleeping: declining is exactly what the pass does.
        (and (= run-phase "movement")
             (zero? (or position 0))
             (not (:no-rez strategy))
             (some #(and (= "Upgrade" (:type %)) (not (:rezzed %)))
                   (decisions/attacked-server-content state)))
        nil  ; Fall through to server-upgrade-decision handler

        ;; Other phases with an EMPTY RUN paid-ability window - auto-continue.
        ;; BUT NOT during success/access phases where Runner is active and Corp just waits,
        ;; and ONLY for a "run"-type prompt (mirrors can-auto-continue?): a
        ;; 'waiting' prompt also has empty :choices/:selectable, and continuing
        ;; on one re-fires the checkpoint the engine is blocked on — the #75
        ;; Manegarm duplicate-prompt wedge (this branch was the spam source at
        ;; movement/pos-0, five continues → five stacked Runner prompts).
        (and my-prompt
             (= "run" (:prompt-type my-prompt))
             (empty? (:choices my-prompt))
             (empty? (:selectable my-prompt))
             (not (#{"success" "access"} run-phase)))
        ;; Route through send-continue! so the #75 waiting-prompt chokepoint
        ;; also covers this send (belt to the prompt-type guard above).
        (let [r (send-continue! gameid)]
          (if (= :action-taken (:status r))
            (assoc r :action :auto-continue-fire-if-asked)
            r))

        ;; Default - don't handle, let other handlers run
        :else nil))))

(defn handle-corp-all-subs-resolved
  "Priority 1.74: Corp at encounter-ice when all subs are resolved (broken or fired).

   Passes AT MOST ONCE per window: if :no-action already records the Corp's pass,
   fall through instead of re-sending — the condition (all subs resolved) stays
   true after the pass, so without the guard this handler re-continued every loop
   iteration until the stuck-detector tripped (the frames-248-252 burst of #75).

   The pass it must look at is the ENCOUNTER's (#150): an encounter-ice pass is
   recorded on the current encounter ([:encounters :no-action]), not on the run,
   and the engine never resets it when subs fire. Reading only run-level
   :no-action left the guard blind, so every persistent tick re-entered here,
   printed 'All subs resolved on Tithe, Corp continuing', and THEN had the send
   suppressed by send-continue!'s #98 chokepoint (which does read the encounter)
   — hundreds of lines until the 300s timeout, both Fable/Sol rematch games.
   Same predicate as the chokepoint (core/i-already-passed-run-window?), so the
   handler and the sender can no longer disagree; the waiting-prompt half of the
   chokepoint is mirrored too, so nothing is printed that is not going to be sent.
   Falling through lands in handle-corp-waiting-after-subs-fired's deduped
   'Waiting for Runner to continue past <ice>' idle wait."
  [{:keys [side run-phase state gameid my-prompt]}]
  (when (and (= side "corp")
             (core/at-encounter? state run-phase)
             (not (core/i-already-passed-run-window? state side))
             (not (state/waiting-prompt-type? (:prompt-type my-prompt))))
    (let [current-ice (core/encountered-ice state)
          subroutines (:subroutines current-ice)
          actionable-subs (filter #(and (not (:broken %)) (not (:fired %))) subroutines)
          pass-key [(core/encounter-key state) (:cid current-ice)]]
      (when (and (core/encounter-ice-active? state current-ice) (seq subroutines) (empty? actionable-subs)
                 ;; Pass already SENT for this encounter, ack not yet in the
                 ;; mirror — fall through (idle), don't re-send/re-print.
                 (not (passed-encounter-recently? pass-key)))
        (let [ice-title (:title current-ice "ICE")
              all-broken? (every? :broken subroutines)]
          (println (format "   All subs %s on %s, Corp continuing"
                          (if all-broken? "broken" "resolved") ice-title))
          (let [r (send-continue! gameid)]
            (when (= :action-taken (:status r))
              (latch-encounter-pass! pass-key (:sent r)))
            r))))))

(defn handle-corp-waiting-after-subs-fired
  "Priority 1.75: Corp at encounter-ice after subs have fired.
   If Runner hasn't passed yet, wait. If Runner already passed, continue."
  [{:keys [side run-phase state gameid]}]
  (when (and (= side "corp")
             (core/at-encounter? state run-phase))
    (let [current-ice (core/encountered-ice state)
          ice-title (:title current-ice "ICE")
          log (get-in state [:game-state :log])
          recent-log (take 10 (reverse log))
          subs-resolved? (some #(re-find (re-pattern (str "(?i)resolves.*subroutines on " ice-title))
                                         (str (:text %)))
                               recent-log)]
      (when (and current-ice subs-resolved?)
        (let [recent-entries (take 5 (reverse log))
              runner-passed? (some #(re-find #"(?i)ai-runner has no further action" (str (:text %))) recent-entries)
              position (get-in state [:game-state :run :position])
              pass-key [(core/encounter-key state) (:cid current-ice)]]
          (if (and runner-passed?
                   ;; Same sent-pass latch as handle-corp-all-subs-resolved
                   ;; (second guest pass): in the Runner-first ordering the log
                   ;; still says "has no further action" while our closing pass
                   ;; is in flight, and this branch re-sent off the log every
                   ;; tick — the burst just moved one handler down. While the
                   ;; latch holds, this is an opponent/ack wait, not a send.
                   (not (passed-encounter-recently? pass-key)))
            (do
              (println (format "   Runner passed, Corp continuing past %s" ice-title))
              (let [r (send-continue! gameid)]
                (when (= :action-taken (:status r))
                  (latch-encounter-pass! pass-key (:sent r)))
                r))
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
  "Wake (or auto-rez) at the pre-approach-server window when an unrezzed upgrade in
   the attacked server may matter.

   This window is movement/position-0 — the Corp's LAST chance to rez an
   APPROACH-triggered upgrade (Manegarm Skunkworks: \"whenever the Runner
   approaches this server\") so that its ability actually fires. The engine
   resolves :approach-server on the way out of this window; by the \"success\"
   phase it is too late (proven in game.ai-upgrade-rez-timing-test), which is why
   ai-run-corp-decisions/current-checkpoint no longer treats success as a rez
   window (issue #67).

   --no-rez (#57): a standing 'decline every rez' commitment — fall through
   (return nil) and let the normal empty-run-window auto-pass advance the run,
   exactly like an approach-ice rez window under --no-rez. Without it we'd
   re-present the same 'Server upgrade decision' every iteration (only a raw pass
   advanced it), a wedge risk for `monitor-run --persistent --no-rez`.

   --rez \"<upgrade>\": honour the whitelist HERE the way handle-corp-rez-strategy
   honours it for ICE at approach-ice — auto-rez the listed upgrade so an
   autonomous Corp that committed `--rez \"Manegarm Skunkworks\"` actually rezzes
   it at the only window where the ability fires. Guarded by cid (position is
   always 0 here, so the ICE handler's position key cannot tell a retry from a
   failed unaffordable rez): once we have attempted this cid and it is still
   unrezzed, decline and pass rather than re-sending the rez forever.

   With no whitelist (or an upgrade not on it), surface the decision so a
   human/policy can rez or pass."
  [{:keys [side state strategy gameid]}]
  (when (and (= side "corp")
             (not (:no-rez strategy)))
    (let [decision (decisions/corp-run-decision state)]
      (when (= :server-upgrade (:kind decision))
        (let [card-title (get-in decision [:card :title] "upgrade")
              upgrade (first (filter #(and (= "Upgrade" (:type %)) (not (:rezzed %)))
                                     (decisions/attacked-server-content state)))
              cid (:cid upgrade)
              should-rez? (and (:rez strategy)
                               (contains? (:rez strategy) card-title))
              rez-already-attempted? (and cid (= (:upgrade-rez-attempted strategy) cid))]
          (cond
            ;; --rez listed, already tried this cid, still unrezzed → the rez did
            ;; not take (almost always unaffordable). NEVER re-rez (that is the
            ;; wedge this guard exists to prevent): either pass so the run proceeds,
            ;; or, if it is not yet our priority, hold. This clause MUST precede the
            ;; auto-rez clause so a failed attempt can't fall through to a re-rez.
            (and should-rez? rez-already-attempted?)
            ;; Only pass when the Corp actually holds priority (Runner has passed
            ;; this window). In practice the empty-run-window that reaches this
            ;; handler at movement/pos-0 always implies the Runner has already
            ;; passed (verified live: the Corp gets no run prompt during the
            ;; Runner's active sub-step). But this decline is the one priority-
            ;; ADVANCING action the handler can take, so guard it explicitly:
            ;; without the guard a stale/transient empty-run prompt seen at a fresh
            ;; window (:no-action false/nil) could make the Corp pass on the
            ;; Runner's behalf and skip the Runner's pre-access window. If it is not
            ;; our priority, return nil and let the waiting/priority handlers run.
            (when (= "runner" (normalize-side (get-in state [:game-state :run :no-action])))
              (let [credits (get-in state [:game-state :corp :credit] 0)
                    status-key [:corp-upgrade-rez-failed cid]
                    already-printed? (= @last-waiting-status status-key)]
                (when-not already-printed?
                  (reset! last-waiting-status status-key)
                  (println (format "   ⚠️  Rez of %s did not take — likely can't afford it (Corp has %d). Declining."
                                   card-title credits)))
                (merge (send-continue! gameid)
                       {:action :upgrade-rez-failed-declined
                        :card card-title})))

            ;; --rez listed and not yet attempted → rez it now (the only window
            ;; where an approach-triggered ability fires).
            should-rez?
            (do
              (println (format "   Strategy: --rez, rezzing %s (pre-approach-server window)" card-title))
              (ws/send-message! :game/action
                                {:gameid gameid
                                 :command "rez"
                                 :args {:card (core/create-card-ref upgrade)}})
              {:status :action-taken
               :action :auto-rezzed-upgrade
               :card card-title
               ;; Persisted by the wrapper in ai_runs so a failed (unaffordable)
               ;; rez is detected next pass instead of retried forever.
               :upgrade-rez-attempted cid})

            ;; No whitelist commitment for this upgrade → surface for a decision.
            :else
            (let [status-key [:corp-server-upgrade-decision (:server decision) card-title]
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
               :server (:server decision)})))))))

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
