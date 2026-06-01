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

(defn stall-key
  "Key identifying the stall point for 'same state N ticks' detection.
   Returns nil when we are NOT in a nudgeable wait (making progress, holding our
   own decision, or no active run) — nil resets the tracker.

   run is the :run map from game-state (nil when no run)."
  [status run]
  (when (and run (waiting-on-opponent? status))
    [(:phase run) (:position run) status]))

(defn update-tracker
  "Pure stall tracker. tracker is {:key <k-or-nil> :count <n>}.
   Resets to count 0 when the key is nil (not stalled) or changes (new stall
   point); increments when the same stall point persists."
  [tracker current-key]
  (cond
    (nil? current-key) {:key nil :count 0}
    (= current-key (:key tracker)) (update tracker :count (fnil inc 0))
    :else {:key current-key :count 1}))

(defn stall-action
  "Pure decision from a persistence count: :none | :nudge | :bail.
   Nudges EXACTLY once (at = nudge-at) so we don't spam chat every tick, then
   bails once the count crosses bail-at."
  [count {:keys [nudge-at bail-at]}]
  (cond
    (and bail-at (>= count bail-at)) :bail
    (and nudge-at (= count nudge-at)) :nudge
    :else :none))

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
