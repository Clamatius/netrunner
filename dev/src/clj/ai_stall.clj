(ns ai-stall
  "On-stall nudge backstop for the autonomous self-play loops.

   The autonomous loops (ai-heuristic-runner/loop!, ai-heuristic-corp/start-autonomous!)
   busy-poll and re-evaluate every tick, so a healthy handshake resolves on its
   own. But when one side holds a `waiting-for-opponent` status and the opponent
   never produces the expected action (a missed let-subs-fire signal on the lax
   gameserver, an idle/long-thinking opponent, or a handler that masks a decision),
   the loop spins invisibly forever.

   This namespace is the backstop, modelled on what human players do: if you've
   been waiting a while, you say 'you there? your move' in chat (the NUDGE), and
   if there's still no response you call a judge (the BAIL — stop the loop with a
   diagnostic so an unsupervised run leaves an attributable artifact instead of a
   silent hang).

   Pure decision core (tracker + thresholds + text); the IO (send-chat!/send-ping!,
   the diagnostic dump) lives in the loops that call this. The autonomous-vs-human
   distinction stays in the LOOP, never in the shared run handlers."
  (:require [clojure.string :as str]))

;; Statuses that mean 'I have done my part and am waiting on the opponent'.
;; These are the only states a nudge can help with — everything else is either
;; progress (re-call makes headway) or a self-blocked decision (no nudge helps).
(def waiting-statuses
  #{:waiting-for-opponent
    :waiting-for-corp-rez
    :waiting-for-corp-fire
    :waiting-for-opponent-paid-abilities
    :waiting-for-runner-signal
    ;; monitor-run!'s own give-up statuses: the corp side re-enters monitor-run!
    ;; every tick, so a returned :stuck/:max-iterations means it is genuinely
    ;; wedged waiting on the runner.
    :stuck
    :max-iterations})

(def default-thresholds
  "Tick = ~500ms. Nudge early (humans say 'you there?' fast); bail late so a
   legitimately slow opponent (e.g. an LLM thinking on a rez during a run) is
   not killed mid-decision."
  {:nudge-at 10     ; ~5s of no progress while waiting -> chat nudge
   :bail-at 120})   ; ~60s still stuck after nudging -> stop with diagnostic

(defn waiting-on-opponent?
  "True if this continue-run!/monitor-run! status means we are blocked on the
   opponent (vs making progress or holding our own decision)."
  [status]
  (contains? waiting-statuses status))

(def slow-opponent-wait-statuses
  "Subset of waiting-statuses where the OPPONENT could still legitimately act —
   a slow (thinking-model) opponent deliberating its move. Patient mode extends
   the bail window only for these. Deliberately EXCLUDES :stuck/:max-iterations,
   which are monitor-run!'s OWN 'this run is genuinely wedged' conclusions (a
   handler bug, not opponent slowness) — those keep the tight iteration-count
   bail even in patient mode, mirroring the own-turn spin backstop."
  #{:waiting-for-opponent
    :waiting-for-corp-rez
    :waiting-for-corp-fire
    :waiting-for-opponent-paid-abilities
    :waiting-for-runner-signal})

(defn slow-opponent-wait?
  "True when status means we're waiting on the opponent to ACT (so a slow model
   gets the patient wall-clock window), vs monitor-run! concluding the run is
   wedged (:stuck/:max-iterations — bail tight even in patient mode)."
  [status]
  (contains? slow-opponent-wait-statuses status))

(defn stall-key
  "Key identifying the stall point for 'same state N ticks' detection.
   Returns nil when we are NOT in a nudgeable wait (making progress, holding our
   own decision, or no active run) — nil resets the tracker.

   run is the :run map from game-state (nil when no run)."
  [status run]
  (when (and run (waiting-on-opponent? status))
    [(:phase run) (:position run) status]))

(defn update-tracker
  "Pure stall tracker. tracker is {:key <k-or-nil> :count <n> [:since <ms>]}.
   Resets to count 0 when the key is nil (not stalled) or changes (new stall
   point); increments when the same stall point persists.

   The 3-arity also records a wall-clock first-seen stamp (:since) for the
   patient-mode bail (see `patient-bail?`): set to `now` when a stall point
   first appears, PRESERVED across ticks while it persists (so elapsed grows),
   cleared when the wait ends. `now` is supplied by the caller (System/
   currentTimeMillis) so this core stays pure/testable."
  ([tracker current-key]
   (cond
     (nil? current-key) {:key nil :count 0}
     (= current-key (:key tracker)) (update tracker :count (fnil inc 0))
     :else {:key current-key :count 1}))
  ([tracker current-key now]
   (cond
     (nil? current-key) {:key nil :count 0 :since nil}
     (= current-key (:key tracker)) (-> tracker
                                         (update :count (fnil inc 0))
                                         (assoc :since (or (:since tracker) now)))
     :else {:key current-key :count 1 :since now})))

(defn stall-action
  "Pure decision from a persistence count: :none | :nudge | :bail.
   Nudges EXACTLY once (at = nudge-at) so we don't spam chat every tick, then
   bails once the count crosses bail-at."
  [count {:keys [nudge-at bail-at]}]
  (cond
    (and bail-at (>= count bail-at)) :bail
    (and nudge-at (= count nudge-at)) :nudge
    :else :none))

(def default-patient-bail-ms
  "Wall-clock patience for a SLOW (thinking-model) opponent before the run-wait
   backstop bails. The iteration-count `:bail-at` (~tens of seconds) is right for
   fast heuristic self-play but kills a model mid-decision; patient mode swaps it
   for this generous wall-clock window. 10 min: long enough for a model to
   deliberate the hardest run decision, short enough that a genuinely dead /
   unattended game still self-reports an attributable artifact instead of hanging
   forever. The nudge still fires on the iteration count (a cheap 'your move?'),
   only the BAIL waits this long."
  600000)

(defn patient-bail?
  "Wall-clock bail for patient mode (slow/model opponent). True once the CURRENT
   opponent-wait has persisted >= bail-after-ms of wall clock. Reads :since from
   a 3-arity `update-tracker` result; a nil :since (not currently waiting) never
   bails regardless of `now`. `now` is the caller's System/currentTimeMillis."
  [tracker now bail-after-ms]
  (boolean (when-let [since (:since tracker)]
             (>= (- now since) bail-after-ms))))

(defn nudge-text
  "Human/LLM-readable 'your move?' message. stall-key is [phase position status]."
  [my-name opp-name [phase position status]]
  (format "⏳ %s waiting on %s — %s at %s pos %s. Your move?"
          (or my-name "I")
          (or opp-name "opponent")
          (name (or status :wait))
          (or phase "?")
          (str position)))

(defn diagnostic
  "Multi-line diagnostic string for a bail. log is the game log vector
   (newest-last); we show the tail."
  [my-name [phase position status] count log]
  (let [tail (->> log (take-last 12) (map :text) (remove nil?))]
    (str/join "\n"
      (concat
        [(format "🛑 STALL BAIL (%s): no progress for %d ticks" my-name count)
         (format "   waiting status: %s | phase: %s | position: %s"
                 (name (or status :wait)) phase position)
         "   recent log:"]
        (map #(str "     " %) tail)))))

;; ============================================================================
;; Own-turn spin backstop (catch-all) — GitHub issue #19
;;
;; The tracker above only fires on opponent-waits DURING A RUN (`stall-key` is
;; nil otherwise). A self-blocked decision on our OWN turn, outside a run — a
;; heuristic rule re-emitting a :blocked action every tick (e.g. install-for-win
;; into an occupied remote) — registers neither a run nor an opponent-wait, so
;; it spins invisibly. Both 2026-06-01 install-for-win hangs were this shape and
;; needed an external watcher to catch.
;;
;; This catch-all watches one thing: on our own turn with no run active, the
;; heuristic loop spends a click every tick, so a frozen [turn clicks] means we
;; are stuck. Bail-only — no nudge, since nudging the opponent is pointless when
;; WE are the stuck side. Off-turn and in-run liveness stay with the run-side
;; tracker (which nudges a slow opponent before bailing), so an LLM opponent
;; thinking on its turn never trips this.
;; ============================================================================

(def own-turn-spin-bail-at
  "Loop ticks of a frozen own-turn state before bailing. Ticks are ~500ms and
   run FAST during a spin (a rejected action returns immediately). On our own
   turn outside a run there is no legitimate reason to freeze — the heuristic
   acts instantly — so this is tight; ~60 ticks ≈ 30s leaves margin for the
   occasional non-click tick (turn start, EOT discard prompt, instant score)."
  60)

(defn own-turn-key
  "Liveness signature for a self-blocked decision on OUR OWN turn, OUTSIDE a run.
   Returns nil (resetting the tracker) when it is not our turn OR a run is
   active — opponent turns and run handshakes belong to the run-side tracker,
   not this catch-all. game-state is the inner :game-state map; my-side is
   \"corp\"/\"runner\"."
  [game-state my-side]
  (when (and (= my-side (:active-player game-state))
             (not (:run game-state))
             ;; A :waiting prompt parked on our side means we're legitimately
             ;; idle while the OPPONENT resolves something (a cross-turn ability,
             ;; a simultaneous-trigger window) — NOT spinning. Don't accumulate,
             ;; or a slow (LLM) opponent would false-bail. A genuine self-blocked
             ;; spin (a rejected install/advance) raises NO prompt, so it still
             ;; keys here and bails; an UNhandled real decision (non-:waiting)
             ;; also still keys, which is correct — that's a genuine stuck.
             (not= :waiting
                   (get-in game-state [(keyword my-side) :prompt-state :prompt-type])))
    [(:turn game-state)
     (get-in game-state [(keyword my-side) :click])]))

(defn own-turn-spinning?
  "True once the frozen-own-turn count crosses the bail threshold."
  ([count] (own-turn-spinning? count own-turn-spin-bail-at))
  ([count threshold] (>= count threshold)))

(defn own-turn-diagnostic
  "Diagnostic for an own-turn spin bail (vs the run-specific `diagnostic`).
   key is [turn clicks]; log is the game log vector (newest-last)."
  [my-name [turn clicks] count log]
  (let [tail (->> log (take-last 12) (map :text) (remove nil?))]
    (str/join "\n"
      (concat
        [(format "🛑 OWN-TURN SPIN BAIL (%s): no click spent for %d ticks" my-name count)
         (format "   frozen on turn %s with %s clicks — loop re-emitting a rejected action?"
                 turn clicks)
         "   recent log:"]
        (map #(str "     " %) tail)))))
