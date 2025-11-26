(ns ai-basic-actions
  "Turn management and basic game actions (credit, draw, end turn)"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-core :as core]))

;; Forward declaration for function used in take-credit! and draw-card!
(declare check-auto-end-turn!)
(declare start-turn!)

;; ============================================================================
;; Auto-Start Turn Helpers
;; ============================================================================

(defn can-start-turn?
  "Check if we CAN legally start our turn right now.

   Returns map with:
   - :can-start (boolean) - whether we can start turn
   - :reason (keyword) - why we can/can't start

   Reasons:
   - :turn-already-started - we already have clicks
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
        recent-log (take-last 5 log)
        my-uid (:uid client-state)
        opp-ended? (some #(and (clojure.string/includes? (:text %) "is ending")
                               (not (clojure.string/includes? (:text %) my-uid)))
                        recent-log)
        is-first-turn? (and (= turn-number 0)
                           (or (nil? my-clicks) (= my-clicks 0))
                           (or (nil? opp-clicks) (= opp-clicks 0))
                           (not opp-ended?))]
    (cond
      ;; Already have clicks - turn already started
      (and my-clicks (> my-clicks 0))
      {:can-start false :reason :turn-already-started}

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
      (not opp-ended?)
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

          ;; Default
          (println "❌ Cannot perform action: Turn not ready"))
        false))))

;; ============================================================================
;; Basic Actions
;; ============================================================================

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
        active-player (get-in client-state [:game-state :active-player])
        turn-number (get-in client-state [:game-state :turn] 0)
        log (get-in client-state [:game-state :log])
        recent-log (take-last 5 log)
        ;; IMPORTANT: Check that OPPONENT ended, not just that someone ended
        ;; This prevents Corp from ending and immediately starting again
        my-uid (:uid client-state)
        opp-ended? (some #(and (clojure.string/includes? (:text %) "is ending")
                               (not (clojure.string/includes? (:text %) my-uid)))
                        recent-log)
        ;; Turn 0 special case: no end-turn yet, both at 0 clicks (or nil before game starts)
        ;; CRITICAL: Must check turn = 0, otherwise Corp ending turn 1 looks like first-turn!
        is-first-turn? (and (= turn-number 0)
                           (or (nil? my-clicks) (= my-clicks 0))
                           (or (nil? opp-clicks) (= opp-clicks 0))
                           (not opp-ended?))]

    (cond
      ;; ERROR: Bug #11 fix - Runner trying to start first turn (Corp always goes first)
      (and is-first-turn?
           (= my-side :runner))
      (do
        (println "❌ ERROR: It's not your turn")
        (println "   Corp always goes first in turn 1")
        (println "   Wait for Corp to start and complete their turn")
        {:status :error :reason :not-your-turn :expected-side "corp"})

      ;; ALLOW: First turn (turn 0) - no prior end-turn exists
      is-first-turn?
      (do
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "start-turn"
                           :args nil})
        (Thread/sleep core/standard-delay)
        (core/show-turn-indicator)
        {:status :success})

      ;; ERROR: Already have clicks (turn already started)
      (> my-clicks 0)
      (do
        (println (format "❌ ERROR: Turn already started (%d clicks remaining)" my-clicks))
        (println "   Complete your turn before starting a new one")
        {:status :error :reason :turn-already-started :clicks my-clicks})

      ;; ERROR: Opponent hasn't ended turn yet
      (> opp-clicks 0)
      (do
        (println (format "❌ ERROR: Opponent still has %d click(s)" opp-clicks))
        (println (format "   Wait for %s to finish their turn first" (name opp-side)))
        {:status :error :reason :opponent-has-clicks :opp-clicks opp-clicks})

      ;; ERROR: Opponent end-turn not in recent log
      (not opp-ended?)
      (do
        (println "❌ ERROR: Opponent hasn't ended their turn yet")
        (println (format "   Recent log doesn't show %s ending turn" (name opp-side)))
        (println "   Wait for opponent to complete their turn")
        {:status :error :reason :opponent-not-ended})

      ;; OK: All validations passed
      ;; Note: We don't check active-player because it doesn't switch until start-turn succeeds.
      ;; After opponent's end-turn, active-player is still opponent (Netrunner priority system).
      ;; The other checks (opp-clicks, opp-ended, my-clicks) are sufficient to prevent turn stealing.
      :else
      (do
        (let [before-hand (count (get-in client-state [:game-state my-side :hand]))]
          (ws/send-message! :game/action
                            {:gameid (if (string? gameid)
                                      (java.util.UUID/fromString gameid)
                                      gameid)
                             :command "start-turn"
                             :args nil})
          (Thread/sleep core/standard-delay)
          (core/show-turn-indicator)
          ;; For Corp, show what was drawn (mandatory draw)
          (when (= my-side :corp)
            (let [after-state @state/client-state
                  hand (get-in after-state [:game-state :corp :hand])
                  after-hand (count hand)
                  new-card (last hand)
                  card-title (get new-card :title "Unknown")]
              (when (> after-hand before-hand)
                (println (str "🃏 Drew: " card-title)))))
          {:status :success})))))

(defn indicate-action!
  "Signal you want to use a paid ability (pauses game for priority window)"
  []
  (let [client-state @state/client-state
        gameid (:gameid client-state)]
    (ws/send-message! :game/action
                      {:gameid (if (string? gameid)
                                (java.util.UUID/fromString gameid)
                                gameid)
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
                        {:gameid (if (string? gameid)
                                  (java.util.UUID/fromString gameid)
                                  gameid)
                         :command "credit"
                         :args nil})
      (Thread/sleep core/medium-delay)
      (let [client-state @state/client-state
            side (:side client-state)
            after-credits (get-in client-state [:game-state (keyword side) :credit])
            after-clicks (get-in client-state [:game-state (keyword side) :click])]
        (core/show-before-after "💰 Credits" before-credits after-credits)
        (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
        (core/show-turn-indicator)
        (check-auto-end-turn!)
        {:status :success
         :data {:before-credits before-credits
                :after-credits after-credits
                :before-clicks before-clicks
                :after-clicks after-clicks}}))
    {:status :error
     :reason "Failed to start turn"}))

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
                        {:gameid (if (string? gameid)
                                  (java.util.UUID/fromString gameid)
                                  gameid)
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
        (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
        (check-auto-end-turn!)
        nil))
    {:status :error
     :reason "Failed to start turn"}))

(defn end-turn!
  "End turn (validates all clicks used unless forced).
   The game engine handles oversized hand by prompting for discard during end-turn.

   Options:
     :force - If true, allows ending turn with clicks remaining

   Usage: (end-turn!)              ; Normal - errors if clicks remain
          (end-turn! :force true)  ; Forced - allows clicks remaining"
  [& {:keys [force] :or {force false}}]
  (let [client-state @state/client-state
        side (:side client-state)
        side-kw (keyword side)
        clicks (get-in client-state [:game-state side-kw :click])
        hand-size (count (get-in client-state [:game-state side-kw :hand]))
        max-hand-size (get-in client-state [:game-state side-kw :hand-size :total] 5)
        gameid (:gameid client-state)]
    (cond
      ;; ERROR: clicks remaining and not forced
      (and (> clicks 0) (not force))
      (do
        (println (format "❌ ERROR: You still have %d click(s) remaining!" clicks))
        (println "   Use all clicks before ending turn, or use --force flag")
        (println "   Example: send_command end-turn --force")
        {:status :error :clicks-remaining clicks})

      ;; OK: all validations passed or forced
      :else
      (do
        (when (and (> clicks 0) force)
          (println (format "⚠️  FORCED: Ending turn with %d click(s) remaining" clicks)))
        (when (> hand-size max-hand-size)
          (println (format "💡 Hand size %d exceeds max %d - game will prompt for discard" hand-size max-hand-size)))
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "end-turn"
                           :args nil})
        (Thread/sleep core/standard-delay)
        (core/show-turn-indicator)
        {:status :success}))))

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
        log (get-in client-state [:game-state :log])
        recent-log (take-last 3 log)
        my-uid (:uid client-state)
        ;; Check if WE already ended (not opponent) - prevents double auto-end
        already-ended? (some #(and (clojure.string/includes? (:text %) "is ending")
                                   (clojure.string/includes? (:text %) my-uid))
                            recent-log)
        ;; Check for scorable agendas (Corp only)
        scorable-agendas (core/find-scorable-agendas)]

    (cond
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
   - 0 clicks remaining
   - No active prompts (already handled mandatory discard, etc.)
   - No visible EOT triggers in installed cards

   ⚠️ PAUSE when:
   - Active prompts (discard, ability choices)
   - Installed cards with end-of-turn effects
   - Credits/cards changed recently (possible EOT trigger)

   Usage: (smart-end-turn!)  ; Auto-end if safe, warn if not"
  []
  (let [client-state @state/client-state
        side (keyword (:side client-state))
        clicks (get-in client-state [:game-state side :click])
        prompt (get-in client-state [:game-state side :prompt-state])
        hand-size (get-in client-state [:game-state side :hand-count])
        max-hand-size (get-in client-state [:game-state side :hand-size :total] 5)
        installed (get-in client-state [:game-state side :installed])

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
      ;; Can't end: clicks remaining
      (> clicks 0)
      (do
        (println "⚠️  Cannot auto-end: you still have clicks")
        (println (format "   %d click(s) remaining - use them or end-turn --force" clicks))
        {:status :clicks-remaining :clicks clicks})

      ;; Pause: active prompt (discard, choices, etc.)
      has-prompt?
      (do
        (println "⚠️  Cannot auto-end: active prompt")
        (println (format "   Prompt: %s" (:msg prompt)))
        (println "   Resolve the prompt first, then end-turn manually")
        {:status :has-prompt :prompt prompt})

      ;; Pause: over hand size (should have discard prompt, but just in case)
      over-hand-size?
      (do
        (println "⚠️  Cannot auto-end: over hand size")
        (println (format "   Hand: %d cards (max %d)" hand-size max-hand-size))
        (println "   Discard cards first")
        {:status :over-hand-size :hand-size hand-size :max max-hand-size})

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
