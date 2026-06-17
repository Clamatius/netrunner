(ns ai-basic-actions
  "Turn management and basic game actions (credit, draw, end turn)"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-core :as core]
            [clojure.string :as str]))

;; Forward declaration for function used in take-credit! and draw-card!
(declare check-auto-end-turn!)
(declare start-turn!)
(declare turn-started-since-last-opp-end?)
(declare get-my-username)

;; ============================================================================
;; Auto-Start Turn Helpers
;; ============================================================================

(defn- get-my-username
  "Get the username for the current side from game state.
   Falls back to UID if game state/username is missing.
   Crucial for correctly identifying own log messages."
  []
  (let [client-state @state/client-state
        side (:side client-state)
        uid (:uid client-state)]
    (if (and side (:game-state client-state))
      (or (get-in client-state [:game-state (keyword side) :user :username])
          uid)
      uid)))

(defn- opponent-mulligan-pending?
  "True when our OWN prompt is the opening-mulligan 'waiting for opponent to keep
   hand or mulligan' window.

   The Corp can keep + start-turn before the Runner finishes its opening
   mulligan; the engine then grants the Corp clicks but bounces every action off
   the still-pending mulligan prompt — a wedged, half-started turn. seat-corp.md
   makes start-turn step 0, so a fast Corp racing ahead of a slow Runner trips
   this in real agent-vs-agent play. Detected from our own prompt-state (the
   server tells us directly), so no fog-of-war peek at the opponent is needed."
  [client-state]
  (let [p (get-in client-state [:game-state (keyword (:side client-state)) :prompt-state])]
    (boolean
      (and p
           (= "waiting" (str (:prompt-type p)))
           (re-find #"(?i)mulligan|keep hand" (str (:msg p)))))))

(defn can-start-turn?
  "Check if we CAN legally start our turn right now.

   Returns map with:
   - :can-start (boolean) - whether we can start turn
   - :reason (keyword) - why we can/can't start

   Reasons:
   - :turn-already-started - we already have clicks
   - :turn-already-played - we already started/played this turn (checked via logs)
   - :opponent-restarted - opponent started a new turn after ending (we missed window)
   - :opponent-mulligan - opponent hasn't finished their opening mulligan yet
   - :not-first-player - Runner trying to start first turn (Corp goes first)
   - :first-turn - Corp can start first turn
   - :opponent-has-clicks - opponent still has clicks remaining
   - :opponent-not-ended - opponent hasn't ended turn (not in recent log)
   - :ready - all checks passed, can start turn"
  []
  (let [client-state @state/client-state
        my-side (keyword (:side client-state))
        opp-side (if (= my-side :runner) :corp :runner)
        my-clicks (get-in client-state [:game-state my-side :click])
        opp-clicks (get-in client-state [:game-state opp-side :click])
        turn-number (get-in client-state [:game-state :turn] 0)
        log (get-in client-state [:game-state :log])
        recent-log (vec (take-last 100 log))
        my-username (get-my-username)

        ;; Use extracted log analysis helpers
        opp-end-indices (core/find-end-turn-indices recent-log my-username)
        last-opp-end-idx (last opp-end-indices)

        opp-start-indices (core/find-start-turn-indices recent-log :exclude-username my-username)
        last-opp-start-idx (last opp-start-indices)

        ;; Check if opponent started AGAIN after ending (they're playing again, we missed window)
        opp-restarted? (and last-opp-end-idx
                            last-opp-start-idx
                            (> last-opp-start-idx last-opp-end-idx))

        is-first-turn? (and (= turn-number 0)
                            (or (nil? my-clicks) (= my-clicks 0))
                            (or (nil? opp-clicks) (= opp-clicks 0))
                            (empty? opp-end-indices))

        ;; Check if we effectively already played this turn
        already-played? (turn-started-since-last-opp-end?)]

    (cond
      ;; Already have clicks - turn already started
      (and my-clicks (> my-clicks 0))
      {:can-start false :reason :turn-already-started}

      ;; Already played this turn (0 clicks but log shows we started)
      already-played?
      {:can-start false :reason :turn-already-played}

      ;; Opponent hasn't finished their opening mulligan — starting now races
      ;; ahead of mulligan resolution and wedges the turn (clicks granted, but
      ;; every action bounces off the pending-mulligan prompt).
      (opponent-mulligan-pending? client-state)
      {:can-start false :reason :opponent-mulligan}

      ;; Opponent started a new turn after ending the previous one
      opp-restarted?
      {:can-start false :reason :opponent-restarted}

      ;; First turn for Runner - can't start (Corp goes first)
      (and is-first-turn? (= my-side :runner))
      {:can-start false :reason :not-first-player}

      ;; First turn for Corp - can start
      is-first-turn?
      {:can-start true :reason :first-turn}

      ;; Opponent still has clicks
      (and opp-clicks (> opp-clicks 0))
      {:can-start false :reason :opponent-has-clicks}

      ;; Opponent hasn't ended
      (empty? opp-end-indices)
      {:can-start false :reason :opponent-not-ended}

      ;; All checks passed
      :else
      {:can-start true :reason :ready})))

(defn ensure-turn-started!
  "Check if turn is started, and if not but we CAN start, auto-start it.

   This implements auto-start-turn behavior:
   - If turn already started (we have clicks), returns true
   - If turn not started but we CAN start (opponent ended), auto-starts and returns true
   - If turn not started and we CAN'T start, prints error and returns false

   Returns:
   - true if ready to proceed with action (turn is started)
   - false if cannot proceed (turn not started and can't auto-start)"
  []
  (let [client-state @state/client-state
        my-side (keyword (:side client-state))
        my-clicks (get-in client-state [:game-state my-side :click] 0)
        can-start-result (can-start-turn?)]
    (cond
      ;; Already have clicks - turn started, ready to go
      (> my-clicks 0)
      true

      ;; Can start turn - auto-start it
      (:can-start can-start-result)
      (do
        (println "")
        (println "💡 Auto-starting turn (opponent has ended, you haven't started yet)")
        (let [result (start-turn!)]
          (if (= (:status result) :success)
            (do
              (println "✅ Turn started successfully")
              true)
            (do
              (println "❌ Auto-start failed")
              false))))

      ;; Cannot start turn - show specific error
      :else
      (do
        (println "")
        (case (:reason can-start-result)
          :opponent-has-clicks
          (println "❌ Cannot perform action: Opponent still has clicks remaining\n   Wait for their turn to end first")

          :opponent-not-ended
          (println "❌ Cannot perform action: Opponent hasn't ended their turn yet\n   Wait for opponent to complete their turn")

          :not-first-player
          (println "❌ Cannot perform action: Corp goes first\n   Wait for Corp to start and complete their turn")

          :opponent-mulligan
          (println "❌ Cannot perform action: Opponent hasn't finished their opening mulligan\n   Wait until they keep/mulligan, then start your turn")

          ;; Default
          (println "❌ Cannot perform action: Turn not ready"))
        false))))

(defn- extract-turn-from-log
  "Extract turn number from log text like 'started their turn 5'"
  [text]
  (when text
    (let [match (re-find #"turn (\d+)" text)]
      (when match
        (Integer/parseInt (second match))))))

(defn turn-started-since-last-opp-end?
  "Check if we have effectively started our turn since the last time the opponent ended theirs.
   Uses robust log index and turn number comparison to handle:
   - Corp/Runner turn structure asymmetry
   - Async log ordering race conditions"
  []
  (let [client-state @state/client-state
        my-side (keyword (:side client-state))
        log (get-in client-state [:game-state :log])
        recent-log (vec (take-last 100 log))
        my-username (get-my-username)

        ;; Use extracted log analysis helpers
        opp-end-indices (core/find-end-turn-indices recent-log my-username)
        last-opp-end-idx (last opp-end-indices)
        last-opp-end-turn (when last-opp-end-idx
                            (core/extract-turn-number (:text (get recent-log last-opp-end-idx))))

        my-start-indices (core/find-start-turn-indices recent-log :include-username my-username)
        last-my-start-idx (last my-start-indices)
        last-my-start-turn (when last-my-start-idx
                             (core/extract-turn-number (:text (get recent-log last-my-start-idx))))]

    (cond
      ;; No opponent end found (e.g. Game Start, Corp Turn 1)
      (nil? last-opp-end-idx)
      (boolean last-my-start-idx)

      ;; No start found at all?
      (nil? last-my-start-idx)
      false

      ;; Normal case: Start is after End
      (> last-my-start-idx last-opp-end-idx)
      true

      ;; Async/Edge case: Start is before End (in logs)
      (< last-my-start-idx last-opp-end-idx)
      (if (nil? last-my-start-turn)
        false
        (cond
          ;; I started a later turn (Async race: Start T2 logged before Opp End T1)
          (> last-my-start-turn last-opp-end-turn)
          true

          ;; Same turn numbers
          (= last-my-start-turn last-opp-end-turn)
          (if (= my-side :runner)
            true  ; Runner: Corp End T1 -> I Start T1. My Start T1 is "since" Corp End T1.
            false) ; Corp: I Start T1 -> Runner End T1. My Start T1 is NOT "since" Runner End T1.

          :else
          false)))))


(defn start-turn!
  "Start your turn (gains clicks, Corp draws mandatory card).
   Validates that opponent has finished their turn to prevent desync.

   Validates:
   - It's actually your turn (checks :active-player)
   - Opponent has 0 clicks remaining
   - Opponent's end-turn appears in recent log
   - You don't already have clicks (prevents double-start)

   Returns {:status :error} if validation fails, {:status :success} if successful."
  []
  (let [client-state @state/client-state
        gameid (:gameid client-state)
        my-side (keyword (:side client-state))
        opp-side (if (= my-side :runner) :corp :runner)
        my-clicks (get-in client-state [:game-state my-side :click])
        opp-clicks (get-in client-state [:game-state opp-side :click])
        turn-number (get-in client-state [:game-state :turn] 0)
        log (get-in client-state [:game-state :log])
        recent-log (take-last 50 log)
        ;; IMPORTANT: Check that OPPONENT ended, not just that someone ended
        ;; This prevents Corp from ending and immediately starting again
        my-username (get-my-username)
        opp-ended? (some #(let [text (:text %)]
                            (and text
                                 (str/includes? text "is ending")
                                 (or (nil? my-username)
                                     (not (str/includes? text my-username)))))
                        recent-log)
        ;; Upstream's two-phase end-turn pauses on :corp-post-discard / :runner-post-discard
        ;; when a card sets :force-post-discard-{self,opponent}. While active, end-turn-continue
        ;; has not run, so starting our turn would desync. Narrower than the removed prompt
        ;; guard (keyed on a specific state flag, not any prompt) so autoresolve-fisk-ftt is
        ;; unaffected.
        post-discard-active? (or (get-in client-state [:game-state :corp-post-discard :active])
                                 (get-in client-state [:game-state :runner-post-discard :active]))
        ;; Opponent's opening mulligan still pending (our own prompt is the
        ;; "waiting for opponent to keep/mulligan" window). Starting now wedges
        ;; the turn — see opponent-mulligan-pending?.
        opp-mulligan-pending? (opponent-mulligan-pending? client-state)
        ;; Turn 0 special case: no end-turn yet, both at 0 clicks (or nil before game starts)
        ;; CRITICAL: Must check turn = 0, otherwise Corp ending turn 1 looks like first-turn!
        is-first-turn? (and (= turn-number 0)
                           (or (nil? my-clicks) (= my-clicks 0))
                           (or (nil? opp-clicks) (= opp-clicks 0))
                           (not opp-ended?))]

    (cond
      ;; ERROR: Post-discard consent phase still active — opponent (or we) haven't acknowledged
      ;; the end-of-turn pause yet, so end-turn-continue hasn't run.
      post-discard-active?
      (do
        (println "❌ ERROR: Previous turn still resolving post-discard phase")
        (println "   A card requires both players to pass priority before the turn truly ends")
        (println "   Use 'wait' until the post-discard pause clears")
        (core/with-cursor {:status :error :reason :post-discard-pending}))

      ;; ERROR: Bug #11 fix - Runner trying to start first turn (Corp always goes first)
      (and is-first-turn?
           (= my-side :runner))
      (do
        (println "❌ ERROR: It's not your turn")
        (println "   Corp always goes first in turn 1")
        (println "   Wait for Corp to start and complete their turn")
        (core/with-cursor {:status :error :reason :not-your-turn :expected-side "corp"}))

      ;; ERROR: Opponent hasn't finished their opening mulligan yet. Starting
      ;; now races ahead of mulligan resolution and wedges the turn.
      opp-mulligan-pending?
      (do
        (println "❌ ERROR: Opponent hasn't finished their opening mulligan yet")
        (println "   Starting now would race ahead of mulligan resolution and wedge your turn")
        (println "   Use 'wait' until they keep/mulligan, then start-turn")
        (core/with-cursor {:status :error :reason :opponent-mulligan}))

      ;; ALLOW: First turn (turn 0) - no prior end-turn exists
      is-first-turn?
      (let [before-hand (count (get-in client-state [:game-state my-side :hand]))
            sent? (ws/send-message! :game/action
                                    {:gameid gameid
                                     :command "start-turn"
                                     :args nil})]
        (if-not sent?
          (do
            (println "❌ ERROR: Failed to send start-turn (server unreachable?)")
            (println "   Check the game server is running, then retry")
            (core/with-cursor {:status :error :reason :send-failed}))
          (do
            (Thread/sleep core/standard-delay)
            (core/show-turn-indicator)
            ;; For Corp, show what was drawn (mandatory draw) with card text
            (when (= my-side :corp)
              (let [after-state @state/client-state
                    hand (get-in after-state [:game-state :corp :hand])
                    after-hand (count hand)
                    new-card (last hand)
                    card-title (get new-card :title "Unknown")]
                (when (> after-hand before-hand)
                  (println (str "🃏 Drew: " card-title))
                  (core/show-card-on-first-sight! card-title))))
            (core/with-cursor {:status :success}))))

      ;; ERROR: Already have clicks (turn already started)
      (> my-clicks 0)
      (do
        (println (format "❌ ERROR: Turn already started (%d clicks remaining)" my-clicks))
        (println "   Complete your turn before starting a new one")
        (core/with-cursor {:status :error :reason :turn-already-started :clicks my-clicks}))

      ;; ERROR: Opponent hasn't ended turn yet
      (> opp-clicks 0)
      (do
        (println (format "❌ ERROR: Opponent still has %d click(s)" opp-clicks))
        (println (format "   Wait for %s to finish their turn first" (name opp-side)))
        (core/with-cursor {:status :error :reason :opponent-has-clicks :opp-clicks opp-clicks}))

      ;; ERROR: Opponent end-turn not in recent log
      (not opp-ended?)
      (do
        (println "❌ ERROR: Opponent hasn't ended their turn yet")
        (println (format "   Recent log doesn't show %s ending turn" (name opp-side)))
        (println "   Wait for opponent to complete their turn")
        (core/with-cursor {:status :error :reason :opponent-not-ended}))

      ;; OK: All validations passed
      ;; Note: We don't check active-player because it doesn't switch until start-turn succeeds.
      ;; After opponent's end-turn, active-player is still opponent (Netrunner priority system).
      ;; The other checks (opp-clicks, opp-ended, my-clicks) are sufficient to prevent turn stealing.
      :else
      (let [before-hand (count (get-in client-state [:game-state my-side :hand]))
            sent? (ws/send-message! :game/action
                                    {:gameid gameid
                                     :command "start-turn"
                                     :args nil})]
        (if-not sent?
          ;; Send failed (e.g. server unreachable). Don't print the stale
          ;; "Ready to start your turn" indicator — that falsely looks like success.
          (do
            (println "❌ ERROR: Failed to send start-turn (server unreachable?)")
            (println "   Check the game server is running, then retry")
            (core/with-cursor {:status :error :reason :send-failed}))
          (do
            (Thread/sleep core/standard-delay)
            (core/show-turn-indicator)
            ;; For Corp, show what was drawn (mandatory draw) with card text
            (when (= my-side :corp)
              (let [after-state @state/client-state
                    hand (get-in after-state [:game-state :corp :hand])
                    after-hand (count hand)
                    new-card (last hand)
                    card-title (get new-card :title "Unknown")]
                (when (> after-hand before-hand)
                  (println (str "🃏 Drew: " card-title))
                  (core/show-card-on-first-sight! card-title))))
            (core/with-cursor {:status :success})))))))

(defn indicate-action!
  "Signal you want to use a paid ability (pauses game for priority window)"
  []
  (let [client-state @state/client-state
        gameid (:gameid client-state)]
    (ws/send-message! :game/action
                      {:gameid gameid
                       :command "indicate-action"
                       :args nil})))

(defn take-credit!
  "Click for credit (shows before/after).
   Auto-starts turn if needed (opponent has ended and we haven't started yet)."
  []
  (if (ensure-turn-started!)
    (let [client-state @state/client-state
          side (:side client-state)
          before-credits (get-in client-state [:game-state (keyword side) :credit])
          before-clicks (get-in client-state [:game-state (keyword side) :click])
          gameid (:gameid client-state)]
      (ws/send-message! :game/action
                        {:gameid gameid
                         :command "credit"
                         :args nil})
      (Thread/sleep core/medium-delay)
      (let [client-state @state/client-state
            side (:side client-state)
            after-credits (get-in client-state [:game-state (keyword side) :credit])
            after-clicks (get-in client-state [:game-state (keyword side) :click])]
        (core/show-before-after "💰 Credits" before-credits after-credits)
        (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
        ;; Show turn indicator only if we won't auto-end (which shows its own)
        (when (> after-clicks 0)
          (core/show-turn-indicator))
        (check-auto-end-turn!)
        (core/with-cursor
          {:status :success
           :data {:before-credits before-credits
                  :after-credits after-credits
                  :before-clicks before-clicks
                  :after-clicks after-clicks}})))
    (core/with-cursor {:status :error :reason "Failed to start turn"})))

(defn draw-card!
  "Draw a card (shows before/after).
   Auto-starts turn if needed (opponent has ended and we haven't started yet)."
  []
  (if (ensure-turn-started!)
    (let [client-state @state/client-state
          side (:side client-state)
          before-hand (count (get-in client-state [:game-state (keyword side) :hand]))
          before-clicks (get-in client-state [:game-state (keyword side) :click])
          gameid (:gameid client-state)]
      (ws/send-message! :game/action
                        {:gameid gameid
                         :command "draw"
                         :args nil})
      (Thread/sleep core/medium-delay)
      (let [client-state @state/client-state
            side (:side client-state)
            hand (get-in client-state [:game-state (keyword side) :hand])
            after-hand (count hand)
            after-clicks (get-in client-state [:game-state (keyword side) :click])
            ;; Get the newly drawn card (last card in hand)
            new-card (last hand)
            card-title (get new-card :title "Unknown")]
        (println (str "🃏 Hand: " before-hand " → " after-hand " cards"))
        (println (str "   Drew: " card-title))
        (core/show-card-on-first-sight! card-title)
        (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
        (check-auto-end-turn!)
        (core/with-cursor {:status :success :card-drawn card-title})))
    (core/with-cursor {:status :error :reason "Failed to start turn"})))

(defn- burn-clicks-for-credits!
  "Spend all remaining clicks taking credits. Used by force-end-turn.
   Returns the number of clicks burned."
  [gameid clicks]
  (when (> clicks 0)
    (println (format "💰 Burning %d click(s) for credits..." clicks))
    (dotimes [_ clicks]
      (ws/send-message! :game/action
                        {:gameid gameid
                         :command "credit"
                         :args nil})
      (Thread/sleep core/quick-delay)))
  clicks)

(defn- already-ended-this-turn?
  "True if our recent log contains 'is ending' from us — guard against
   sending a second end-turn message (which corrupts engine state)."
  [client-state]
  (let [log (get-in client-state [:game-state :log])
        recent-log (take-last 3 log)
        my-username (get-my-username)]
    (boolean
      (some #(let [text (:text %)]
               (and text
                    (str/includes? text "is ending")
                    my-username
                    (str/includes? text my-username)))
            recent-log))))

(defn- opponent-turn-underway?
  "Durable signal that our turn genuinely ended and the OPPONENT has taken over.
   Used so the end-turn self-heal won't re-send merely because our 'is ending'
   log line scrolled out of the recent window (vs. was actually rolled back):
   the same flurry of opponent log lines that could push our line out is itself
   proof the opponent is playing. True when the opponent has clicks, is the
   active player, or has a 'started their turn' line in the recent log."
  [client-state]
  (let [my-side (keyword (:side client-state))
        opp-side (if (= my-side :runner) :corp :runner)
        opp-clicks (get-in client-state [:game-state opp-side :click])
        active-player (get-in client-state [:game-state :active-player])
        log (get-in client-state [:game-state :log])
        recent (take-last 6 log)
        my-username (get-my-username)
        opp-started? (some #(let [t (:text %)]
                              (and t
                                   (str/includes? t "started their turn")
                                   (or (nil? my-username)
                                       (not (str/includes? t my-username)))))
                           recent)]
    (boolean
     (or (and opp-clicks (pos? opp-clicks))
         (and active-player (= active-player (name opp-side)))
         opp-started?))))

(defn end-turn-self-heal-decision
  "Decide what smart-end-turn! should do once it has re-read state after a short
   settle. The 'X is ending their turn' line can be an OPTIMISTIC client entry
   the server rolls back on a :game/error resync — observed when an end-turn is
   auto-fired during the unsettled window right after a last-click action whose
   resolution is still settling (a just-finished run/access, or an event like
   Wildcat Strike that forces the opponent to choose).

   Given the post-settle re-read, ANY of these means the turn really ended:
     :line-present?       our 'is ending' line still stands
     :opponent-underway?  turn moved on (our line may have scrolled out of window)
     :turn-advanced?      :turn incremented past where we started the end-turn
   If ALL are false the end-turn was rolled back (turn still open at 0 clicks) ->
   :resend, or the match deadlocks. :confirmed-ended must win on ANY evidence the
   turn ended, since a needless re-send is the double-end that corrupts engine
   state (per end-turn!'s Bug #2 guard)."
  [{:keys [line-present? opponent-underway? turn-advanced?]}]
  (if (or line-present? opponent-underway? turn-advanced?) :confirmed-ended :resend))

(defn recheck-end-turn-state
  "Re-read client state after a short settle and return the signals the self-heal
   decision needs: whether our end-turn log line still stands, whether the
   opponent has visibly taken over their turn, and whether :turn advanced past
   `entry-turn` (the turn number when we attempted the end). Pulled out as a
   public seam so smart-end-turn!'s self-heal branch is testable without racing
   real wire timing."
  [entry-turn]
  (Thread/sleep core/standard-delay)
  (let [cs @state/client-state]
    {:line-present? (already-ended-this-turn? cs)
     :opponent-underway? (opponent-turn-underway? cs)
     :turn-advanced? (boolean (and entry-turn
                                   (> (get-in cs [:game-state :turn] 0) entry-turn)))}))

(defn end-turn!
  "End turn (validates all clicks used unless forced).
   The game engine handles oversized hand by prompting for discard during end-turn.

   Options:
     :force - If true, burns remaining clicks for credits then ends turn
              (keeps game state consistent, unlike skipping clicks)

   Usage: (end-turn!)              ; Normal - errors if clicks remain
          (end-turn! :force true)  ; Forced - burns clicks, then ends"
  [& {:keys [force] :or {force false}}]
  (let [client-state @state/client-state
        side (:side client-state)
        side-kw (keyword side)
        clicks (get-in client-state [:game-state side-kw :click])
        hand-size (count (get-in client-state [:game-state side-kw :hand]))
        max-hand-size (get-in client-state [:game-state side-kw :hand-size :total] 5)
        gameid (:gameid client-state)]
    (cond
      ;; Bug #2 guard: refuse to double-end the turn. The engine treats a
      ;; second end-turn message as state corruption and deadlocks the next
      ;; turn cycle (Run #4 1:11:32). Detected via our own recent log line.
      (already-ended-this-turn? client-state)
      (do
        (println "⚠️  Turn already ended — refusing duplicate end-turn (engine-corruption guard).")
        (println "   If the game is NOT advancing and you're still at 0 clicks, the prior")
        (println "   end-turn may have been rolled back — use smart-end-turn, which self-heals.")
        (core/with-cursor {:status :error :reason :already-ended}))

      ;; ERROR: clicks remaining and not forced
      (and (> clicks 0) (not force))
      (do
        (println (format "❌ ERROR: You still have %d click(s) remaining!" clicks))
        (println "   Use all clicks before ending turn, or use --force flag")
        (println "   Example: send_command end-turn --force")
        (core/with-cursor {:status :error :clicks-remaining clicks}))

      ;; FORCE: burn remaining clicks as credits first
      (and (> clicks 0) force)
      (do
        (burn-clicks-for-credits! gameid clicks)
        (Thread/sleep core/standard-delay)
        ;; Re-fetch state after burning clicks
        (let [client-state @state/client-state
              hand-size (count (get-in client-state [:game-state side-kw :hand]))]
          (when (> hand-size max-hand-size)
            (println (format "💡 Hand size %d exceeds max %d - game will prompt for discard" hand-size max-hand-size)))
          (ws/send-message! :game/action
                            {:gameid gameid
                             :command "end-turn"
                             :args nil})
          (Thread/sleep core/standard-delay)
          (core/show-turn-indicator)
          (core/with-cursor {:status :success :clicks-burned clicks})))

      ;; OK: all clicks used
      :else
      (do
        (when (> hand-size max-hand-size)
          (println (format "💡 Hand size %d exceeds max %d - game will prompt for discard" hand-size max-hand-size)))
        (ws/send-message! :game/action
                          {:gameid gameid
                           :command "end-turn"
                           :args nil})
        (Thread/sleep core/standard-delay)
        (core/show-turn-indicator)
        (core/with-cursor {:status :success})))))

(defn check-auto-end-turn!
  "Proactively check if turn should auto-end after an action.
   Called automatically after clicks-consuming actions.

   Auto-ends when:
   - 0 clicks remaining
   - No active prompts
   - Not already ended (checks recent log)
   - No scorable agendas (Corp only)

   Note: Oversized hand is OK - game engine will prompt for discard during end-turn.
   This prevents the 'forgot to end-turn' stuck state."
  []
  (let [client-state @state/client-state
        side (keyword (:side client-state))
        clicks (get-in client-state [:game-state side :click])
        prompt (get-in client-state [:game-state side :prompt-state])
        hand-size (count (get-in client-state [:game-state side :hand]))
        max-hand-size (get-in client-state [:game-state side :hand-size :total] 5)
        active-player (get-in client-state [:game-state :active-player])
        my-turn? (= (name side) active-player)
        ;; Check if WE already ended (not opponent) - prevents double auto-end.
        ;; Shared guard (see already-ended-this-turn?).
        already-ended? (already-ended-this-turn? client-state)
        ;; Active-run guard: never end turn while a run is in progress, even
        ;; with clicks=0 (paid abilities, breaker pumps, etc. can occur).
        active-run? (some? (get-in client-state [:game-state :run]))
        ;; Turn-started guard: prompt resolvers (mulligan keep-hand, etc.)
        ;; can fire this hook before our turn actually starts; firing end-turn
        ;; then is meaningless at best and confuses the engine at worst.
        turn-started? (turn-started-since-last-opp-end?)
        ;; Check for scorable agendas (Corp only)
        scorable-agendas (core/find-scorable-agendas)]

    (cond
      ;; Not our turn — never auto-end (issue #16: a forced prompt during the
      ;; opponent's turn must not trigger our end-turn).
      (not my-turn?)
      nil

      ;; Active run — never auto-end mid-run (defensive: callers should also
      ;; gate on this, but the check makes it impossible to leak).
      active-run?
      nil

      ;; Turn hasn't actually started — silent skip (don't even print).
      ;; This is the mulligan/pre-turn case from Run #5 take 3.
      (not turn-started?)
      nil

      ;; Have scorable agendas - DON'T auto-end!
      (seq scorable-agendas)
      (do
        (println "")
        (println "⚠️  Cannot auto-end turn: Agenda(s) may be scorable!")
        (doseq [agenda scorable-agendas]
          (println (format "   🎯 %s (%d/%d counters - SCORABLE!)"
                          (:title agenda)
                          (:counters agenda)
                          (:requirement agenda))))
        (println "💡 Review agendas and score if able, then manually end turn")
        (flush))

      ;; Has prompt blocking - notify user
      (and (= clicks 0)
           prompt
           (not already-ended?))
      (do
        (println "")
        (println "⚠️  Cannot auto-end turn: Active prompt must be resolved first")
        (println (format "   Prompt: %s" (:msg prompt)))
        (println "💡 Use 'prompt' command to see choices, or 'choose' to respond")
        (flush))

      ;; Safe to auto-end
      (and (= clicks 0)
           (nil? prompt)
           (not already-ended?))
      (do
        (println "")
        (when (> hand-size max-hand-size)
          (println (format "💡 Hand size %d exceeds max %d - game will prompt for discard" hand-size max-hand-size)))
        (println "💡 Auto-ending turn (0 clicks, no prompts)")
        (flush)
        (end-turn!)))))

(defn smart-end-turn!
  "Smart end-turn that checks if it's safe to end turn automatically.

   ✅ AUTO END-TURN when:
   - Turn has actually started (prevents premature end before start)
   - 0 clicks remaining
   - No active prompts (already handled mandatory discard, etc.)
   - No visible EOT triggers in installed cards

   ⚠️ PAUSE when:
   - Turn hasn't started yet
   - Active prompts (discard, ability choices)
   - Installed cards with end-of-turn effects
   - Credits/cards changed recently (possible EOT trigger)

   Usage: (smart-end-turn!)  ; Auto-end if safe, warn if not"
  []
  (let [client-state @state/client-state
        side (keyword (:side client-state))
        clicks (get-in client-state [:game-state side :click])
        prompt (get-in client-state [:game-state side :prompt-state])
        hand-size (or (get-in client-state [:game-state side :hand-count]) 0)
        max-hand-size (or (get-in client-state [:game-state side :hand-size :total]) 5)
        installed (get-in client-state [:game-state side :installed])

        ;; Check if we've actually started our turn
        turn-started? (turn-started-since-last-opp-end?)

        ;; Turn number at entry, so the self-heal re-read can detect a real
        ;; transition (:turn advanced) vs a rolled-back end-turn.
        turn (get-in client-state [:game-state :turn] 0)

        ;; Check if WE already ended (not opponent) - prevents double auto-end.
        ;; Shared guard so the initial check and the self-heal re-read agree.
        already-ended? (already-ended-this-turn? client-state)

        ;; Check for EOT-related conditions
        has-prompt? (some? prompt)
        over-hand-size? (> hand-size max-hand-size)

        ;; Simple heuristic: check if any installed card text contains "end of"
        ;; This is a rough approximation - not all cards are in client state with full text
        has-eot-trigger? (some (fn [card-list]
                                 (some (fn [card]
                                        (when-let [text (:text card)]
                                          (clojure.string/includes?
                                           (clojure.string/lower-case text)
                                           "end of")))
                                      card-list))
                              (vals installed))]

    (cond
      ;; Can't end: Turn hasn't started yet
      (not turn-started?)
      (do
        (println "⚠️  Cannot auto-end: Turn hasn't started yet")
        (core/with-cursor {:status :turn-not-started}))

      ;; Looks already-ended. But the "is ending" line can be an OPTIMISTIC entry
      ;; the server rolls back on a :game/error resync (end-turn auto-fired during
      ;; the unsettled priority window after a last-click opponent-decision event
      ;; like Wildcat Strike). Re-read after a short settle: a genuine line
      ;; persists (-> nothing to do); a rolled-back one vanishes (-> re-send, or
      ;; the agent-vs-agent match deadlocks with us stuck at 0 clicks and the
      ;; opponent correctly waiting on an end-turn that never landed).
      already-ended?
      (case (end-turn-self-heal-decision (recheck-end-turn-state turn))
        :confirmed-ended
        (do
          (println "ℹ️  Turn already ended — nothing to do.")
          (core/with-cursor {:status :already-ended}))
        :resend
        (do
          (println "↻ Prior end-turn was rolled back (resync) — re-sending end-turn.")
          (end-turn!)))

      ;; Can't end: clicks remaining
      (> clicks 0)
      (do
        (println "⚠️  Cannot auto-end: you still have clicks")
        (println (format "   %d click(s) remaining - use them or end-turn --force" clicks))
        (core/with-cursor {:status :clicks-remaining :clicks clicks}))

      ;; Pause: active prompt (discard, choices, etc.)
      has-prompt?
      (do
        (println "⚠️  Cannot auto-end: active prompt")
        (println (format "   Prompt: %s" (:msg prompt)))
        (println "   Resolve the prompt first, then end-turn manually")
        (core/with-cursor {:status :has-prompt :prompt prompt}))

      ;; Over hand size with no active discard prompt: end the turn anyway.
      ;; end-turn! triggers the engine's discard-to-hand-size prompt (see its
      ;; docstring), which the caller's prompt handler then resolves. Refusing
      ;; here deadlocks the autonomous loop, since the discard prompt only
      ;; appears AFTER end-turn is sent (the has-prompt? branch above already
      ;; pauses once that prompt exists).
      over-hand-size?
      (do
        (println (format "ℹ️  Over hand size (%d > %d) - ending turn to trigger discard prompt"
                         hand-size max-hand-size))
        (end-turn!))

      ;; Warn: possible EOT trigger
      has-eot-trigger?
      (do
        (println "⚠️  Possible end-of-turn effect detected")
        (println "   Installed cards may have EOT triggers")
        (println "   Proceeding with end-turn (effects will resolve)")
        (end-turn!))

      ;; Safe: auto end-turn
      :else
      (do
        (println "✅ Auto-ending turn (0 clicks, no prompts)")
        (end-turn!)))))

;; Keep old function names for backwards compatibility
(defn take-credits []
  (take-credit!))

(defn draw-card []
  (draw-card!))

(defn end-turn []
  (end-turn!))

;; ============================================================================
;; Tag and Virus Actions
;; ============================================================================

(defn remove-tag!
  "Runner action: Pay $2 + click to remove a tag.
   Returns {:status :success} or {:status :error :reason ...}"
  []
  (if (ensure-turn-started!)
    (let [client-state @state/client-state
          side (:side client-state)]
      (if (not= (clojure.string/lower-case (or side "")) "runner")
        (do
          (println "❌ Only Runner can remove tags")
          (core/with-cursor {:status :error :reason "Only Runner can remove tags"}))
        (let [tags (get-in client-state [:game-state :runner :tag :base] 0)
              credits (get-in client-state [:game-state :runner :credit] 0)
              clicks (get-in client-state [:game-state :runner :click] 0)]
          (cond
            (< tags 1)
            (do
              (println "❌ No tags to remove")
              (core/with-cursor {:status :error :reason "No tags to remove"}))

            (< credits 2)
            (do
              (println "❌ Need $2 to remove tag (have $" credits ")")
              (core/with-cursor {:status :error :reason "Need $2 to remove tag"}))

            (< clicks 1)
            (do
              (println "❌ Need 1 click to remove tag (have " clicks ")")
              (core/with-cursor {:status :error :reason "Need 1 click to remove tag"}))

            :else
            (let [gameid (:gameid client-state)]
              (ws/send-message! :game/action
                                {:gameid gameid
                                 :command "remove-tag"
                                 :args nil})
              (Thread/sleep core/medium-delay)
              (let [new-state @state/client-state
                    new-tags (get-in new-state [:game-state :runner :tag :base] 0)
                    new-credits (get-in new-state [:game-state :runner :credit] 0)]
                (println (str "🏷️  Removed tag: " tags " → " new-tags " tags ($" credits " → $" new-credits ")"))
                (check-auto-end-turn!)
                (core/with-cursor {:status :success :tags-before tags :tags-after new-tags})))))))
    (core/with-cursor {:status :error :reason "Failed to start turn"})))

(defn purge-viruses!
  "Corp action: Spend 3 clicks to purge all virus counters.
   Returns {:status :success} or {:status :error :reason ...}"
  []
  (if (ensure-turn-started!)
    (let [client-state @state/client-state
          side (:side client-state)]
      (if (not= (clojure.string/lower-case (or side "")) "corp")
        (do
          (println "❌ Only Corp can purge viruses")
          (core/with-cursor {:status :error :reason "Only Corp can purge viruses"}))
        (let [clicks (get-in client-state [:game-state :corp :click] 0)]
          (if (< clicks 3)
            (do
              (println "❌ Need 3 clicks to purge (have " clicks ")")
              (core/with-cursor {:status :error :reason "Need 3 clicks to purge"}))
            (let [gameid (:gameid client-state)]
              (ws/send-message! :game/action
                                {:gameid gameid
                                 :command "purge"
                                 :args nil})
              (Thread/sleep core/medium-delay)
              (println "🧹 Purged all virus counters")
              (check-auto-end-turn!)
              (core/with-cursor {:status :success}))))))
    (core/with-cursor {:status :error :reason "Failed to start turn"})))

(defn trash-resource!
  "Corp action: Pay $2 + click to trash a tagged runner's resource.
   Requires runner to be tagged. Creates a prompt to select which resource.
   Returns {:status :success} or {:status :error :reason ...} or {:status :waiting-input}"
  []
  (if (ensure-turn-started!)
    (let [client-state @state/client-state
          side (:side client-state)]
      (if (not= (clojure.string/lower-case (or side "")) "corp")
        (do
          (println "❌ Only Corp can trash resources")
          (core/with-cursor {:status :error :reason "Only Corp can trash resources"}))
        (let [runner-tagged? (> (get-in client-state [:game-state :runner :tag :base] 0) 0)
              credits (get-in client-state [:game-state :corp :credit] 0)
              clicks (get-in client-state [:game-state :corp :click] 0)]
          (cond
            (not runner-tagged?)
            (do
              (println "❌ Runner must be tagged to trash resources")
              (core/with-cursor {:status :error :reason "Runner not tagged"}))

            (< credits 2)
            (do
              (println "❌ Need $2 to trash resource (have $" credits ")")
              (core/with-cursor {:status :error :reason "Need $2 to trash resource"}))

            (< clicks 1)
            (do
              (println "❌ Need 1 click to trash resource (have " clicks ")")
              (core/with-cursor {:status :error :reason "Need 1 click to trash resource"}))

            :else
            (let [gameid (:gameid client-state)]
              (ws/send-message! :game/action
                                {:gameid gameid
                                 :command "trash-resource"
                                 :args nil})
              (Thread/sleep core/medium-delay)
              ;; This creates a prompt to select which resource to trash
              (let [new-state @state/client-state
                    prompt (first (get-in new-state [:game-state :corp :prompt]))]
                (if prompt
                  (do
                    (println "🗑️  Select resource to trash:")
                    (println (str "   " (:msg prompt)))
                    (core/with-cursor {:status :waiting-input :prompt prompt}))
                  (do
                    (println "🗑️  Trashed resource")
                    (check-auto-end-turn!)
                    (core/with-cursor {:status :success})))))))))
    (core/with-cursor {:status :error :reason "Failed to start turn"})))

;; ============================================================================
;; Emergency Game State Fix (CHEATING - Use Only for Broken States!)
;; ============================================================================

(defn fix-credits!
  "⚠️  CHEATING: Manually adjust credits for either side.

   THIS IS ONLY FOR FIXING ACCIDENTALLY BROKEN GAME STATES!
   Using this during normal play is cheating.

   Usage:
     (fix-credits! 5)       ; Set YOUR credits to 5
     (fix-credits! -2)      ; Subtract 2 from YOUR credits (delta mode)
     (fix-credits! +3)      ; Add 3 to YOUR credits (delta mode)
     (fix-credits! \"corp\" 10)   ; Set Corp's credits to 10
     (fix-credits! \"runner\" 5)  ; Set Runner's credits to 5

   Args:
     amount - Target credit value OR delta (+N/-N as string)
     side   - Optional: \"corp\" or \"runner\" (defaults to your side)"
  ([amount]
   (fix-credits! nil amount))
  ([side-arg amount]
   (println "")
   (println "⚠️  ============================================================")
   (println "⚠️  WARNING: CHEATING - MANUAL CREDIT ADJUSTMENT")
   (println "⚠️  This command is ONLY for fixing accidentally broken game states!")
   (println "⚠️  Using this during normal play is cheating.")
   (println "⚠️  ============================================================")
   (println "")
   (let [client-state @state/client-state
         gameid (:gameid client-state)
         my-side (:side client-state)
         ;; Determine target side
         target-side (cond
                      (nil? side-arg) my-side
                      (string? side-arg) (clojure.string/lower-case side-arg)
                      :else my-side)
         ;; Get current credits for target
         current-credits (get-in client-state [:game-state (keyword target-side) :credit] 0)
         ;; Parse amount - could be absolute or delta
         [_ delta-amount]
         (cond
           ;; String starting with + or - is delta mode
           (and (string? amount) (re-matches #"[+-]\d+" amount))
           [true (Integer/parseInt amount)]
           ;; Number is absolute mode (calculate delta)
           (number? amount)
           [false (- amount current-credits)]
           ;; String number is absolute
           (string? amount)
           [false (- (Integer/parseInt amount) current-credits)]
           :else
           [false 0])]
     (println (format "   Target: %s (currently %d credits)" target-side current-credits))
     (println (format "   Change: %s%d credits" (if (pos? delta-amount) "+" "") delta-amount))
     (println (format "   Result: %d credits" (+ current-credits delta-amount)))
     (println "")
     ;; Send the change command
     (ws/send-message! :game/action
                       {:gameid gameid
                        :command "change"
                        :args {:key :credit
                               :delta delta-amount}})
     ;; Wait for state update
     (Thread/sleep 500)
     (let [new-credits (get-in @state/client-state [:game-state (keyword target-side) :credit] 0)]
       (println (format "✅ Credits adjusted: %d → %d" current-credits new-credits))
       {:status :success
        :side target-side
        :old-credits current-credits
        :new-credits new-credits
        :delta delta-amount}))))

;; ============================================================================
;; Debug Helpers for Testing Discard Pile Interactions
;; ============================================================================

(defn discard-card!
  "DEBUG HELPER: Trash a card from hand to discard pile.
   Useful for testing effects that interact with Archives/Heap.

   Usage: (discard-card! \"Hedge Fund\")

   Returns {:status :success :card-discarded <name>} or {:status :error ...}"
  [card-name]
  (let [client-state @state/client-state
        side (:side client-state)
        side-kw (keyword side)
        hand (get-in client-state [:game-state side-kw :hand])
        card (first (filter #(= card-name (:title %)) hand))
        gameid (:gameid client-state)]
    (if card
      (do
        (ws/send-message! :game/action
                          {:gameid gameid
                           :command "trash"
                           :args {:card card}})
        (Thread/sleep core/medium-delay)
        ;; Verify it moved
        (let [new-state @state/client-state
              in-discard? (some #(= card-name (:title %))
                               (get-in new-state [:game-state side-kw :discard]))]
          (if in-discard?
            (do
              (println (format "🗑️  Discarded: %s" card-name))
              (core/with-cursor {:status :success :card-discarded card-name}))
            (do
              (println (format "⚠️  Trash command sent but card may not have moved: %s" card-name))
              (core/with-cursor {:status :error :reason "Card did not move to discard"})))))
      (do
        (println (format "❌ Card not in hand: %s" card-name))
        (core/with-cursor {:status :error :reason (str "Card not found: " card-name)})))))

(defn draw-to-card!
  "DEBUG HELPER: Draw cards until a specific card appears in hand.
   Returns error if card not found after drawing entire deck or running out of clicks.
   Max 45 draws as safety limit.

   Usage: (draw-to-card! \"Hedge Fund\")

   Returns {:status :success :card <name> :draws N} or {:status :error ...}"
  [card-name]
  (let [max-draws 45]
    (loop [draws 0]
      (let [client-state @state/client-state
            side (:side client-state)
            side-kw (keyword side)
            hand (get-in client-state [:game-state side-kw :hand])
            found (first (filter #(= card-name (:title %)) hand))
            ;; Use :deck-count - server doesn't expose actual deck contents
            deck-size (get-in client-state [:game-state side-kw :deck-count] 0)
            clicks (get-in client-state [:game-state side-kw :click] 0)]
        (cond
          found
          (do
            (println (format "✅ Found %s after %d draws" card-name draws))
            (core/with-cursor {:status :success :card card-name :draws draws}))

          (>= draws max-draws)
          (do
            (println (format "❌ Max draws (%d) reached, %s not found" max-draws card-name))
            (core/with-cursor {:status :error :reason "Max draws reached"}))

          (= deck-size 0)
          (do
            (println (format "❌ Deck empty, %s not found" card-name))
            (core/with-cursor {:status :error :reason "Deck empty"}))

          (<= clicks 0)
          (do
            (println (format "⏸️  Out of clicks after %d draws, %s not found (deck has %d cards)"
                           draws card-name deck-size))
            (core/with-cursor {:status :out-of-clicks :reason "Out of clicks" :draws draws :deck-remaining deck-size}))

          :else
          (do
            (draw-card!)
            (recur (inc draws)))))))

(defn find-card!
  "DEBUG HELPER: Multi-turn search for a card. Loops through turns until card is found.
   Pattern: draw until out of clicks -> discard -> opponent bot-turn -> repeat

   Usage: (find-card! \"Overclock\")

   Works for both sides:
   - Runner: Uses Corp bot for opponent turns
   - Corp: Uses Runner bot (ai-heuristic-runner) for opponent turns

   Max 10 turns as safety limit."
  [card-name]
  (let [max-turns 10
        my-side (:side @state/client-state)
        is-runner? (= "runner" (clojure.string/lower-case (str my-side)))]
    (println (format "🔍 Searching for %s as %s..." card-name my-side))
    (loop [turn 0]
      (println (format "\n🔍 Turn %d: Drawing for %s..." (inc turn) card-name))
      (let [result (draw-to-card! card-name)]
        (cond
          (= :success (:status result))
          (do
            (println (format "✅ Found %s after %d turn(s)" card-name (inc turn)))
            result)

          (>= turn max-turns)
          (do
            (println (format "❌ Max turns (%d) reached, %s not found" max-turns card-name))
            (core/with-cursor {:status :error :reason "Max turns reached"}))

          (= :out-of-clicks (:status result))
          (do
            ;; Discard to hand size
            (println "   Discarding to hand size...")
            (try
              (require '[ai-prompts :as prompts])
              ((resolve 'ai-prompts/discard-to-hand-size!))
              (catch Exception e
                (println (format "   ⚠️  Discard error: %s" (.getMessage e)))))
            (Thread/sleep 500)

            ;; Opponent takes a turn
            (if is-runner?
              (do
                (println "   Corp bot-turn...")
                (try
                  (require '[ai-heuristic-corp :as bot])
                  ((resolve 'ai-heuristic-corp/play-full-turn))
                  (catch Exception e
                    (println (format "   ⚠️  Corp bot error: %s" (.getMessage e))))))
              (do
                ;; No Runner bot yet - just spend clicks on credits
                (println "   Runner simple-turn (credits)...")
                (try
                  (require '[ai-heuristic-runner :as bot])
                  ((resolve 'ai-heuristic-runner/play-full-turn))
                  (catch Exception _
                    ;; Fallback: manually take credits
                    (dotimes [_ 4]
                      (take-credit!)
                      (Thread/sleep 100))
                    (end-turn!)))))
            (Thread/sleep 500)

            ;; Start new turn
            (println (format "   Starting new %s turn..." my-side))
            (start-turn!)
            (Thread/sleep 300)

            (recur (inc turn)))

          :else
          (do
            (println (format "❌ Unexpected error: %s" (:reason result)))
            result)))))))
