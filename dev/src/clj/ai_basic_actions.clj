(ns ai-basic-actions
  "Turn management and basic game actions (credit, draw, end turn)"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-core :as core]))

;; Forward declaration for function used in take-credit! and draw-card!
(declare check-auto-end-turn!)

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
  (let [state @ws/client-state
        gameid (:gameid state)
        my-side (keyword (:side state))
        opp-side (if (= my-side :runner) :corp :runner)
        my-clicks (get-in state [:game-state my-side :click])
        opp-clicks (get-in state [:game-state opp-side :click])
        active-player (get-in state [:game-state :active-player])
        log (get-in state [:game-state :log])
        recent-log (take-last 5 log)
        opp-ended? (some #(clojure.string/includes? (:text %) "is ending")
                        recent-log)
        ;; Turn 0 special case: no end-turn yet, both at 0 clicks (or nil before game starts)
        is-first-turn? (and (or (nil? my-clicks) (= my-clicks 0))
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
        (Thread/sleep 2000)
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

      ;; ERROR: Not the active player (prevents turn stealing)
      ;; Note: This check comes AFTER first-turn checks to allow Corp's first turn
      (and active-player
           (not= (name my-side) active-player))
      (do
        (println "❌ ERROR: It's not your turn")
        (println (format "   Active player: %s (you are %s)" active-player (name my-side)))
        (println "   Wait for opponent to complete their turn")
        {:status :error :reason :not-active-player :active-player active-player})

      ;; OK: All validations passed
      :else
      (do
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "start-turn"
                           :args nil})
        (Thread/sleep 2000)
        (core/show-turn-indicator)
        {:status :success}))))

(defn indicate-action!
  "Signal you want to use a paid ability (pauses game for priority window)"
  []
  (let [state @ws/client-state
        gameid (:gameid state)]
    (ws/send-message! :game/action
                      {:gameid (if (string? gameid)
                                (java.util.UUID/fromString gameid)
                                gameid)
                       :command "indicate-action"
                       :args nil})))

(defn take-credit!
  "Click for credit (shows before/after)"
  []
  (let [state @ws/client-state
        side (:side state)
        before-credits (get-in state [:game-state (keyword side) :credit])
        before-clicks (get-in state [:game-state (keyword side) :click])
        gameid (:gameid state)]
    (ws/send-message! :game/action
                      {:gameid (if (string? gameid)
                                (java.util.UUID/fromString gameid)
                                gameid)
                       :command "credit"
                       :args nil})
    (Thread/sleep 1500)
    (let [state @ws/client-state
          side (:side state)
          after-credits (get-in state [:game-state (keyword side) :credit])
          after-clicks (get-in state [:game-state (keyword side) :click])]
      (core/show-before-after "💰 Credits" before-credits after-credits)
      (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
      (core/show-turn-indicator)
      (check-auto-end-turn!))))

(defn draw-card!
  "Draw a card (shows before/after)"
  []
  (let [state @ws/client-state
        side (:side state)
        before-hand (count (get-in state [:game-state (keyword side) :hand]))
        before-clicks (get-in state [:game-state (keyword side) :click])
        gameid (:gameid state)]
    (ws/send-message! :game/action
                      {:gameid (if (string? gameid)
                                (java.util.UUID/fromString gameid)
                                gameid)
                       :command "draw"
                       :args nil})
    (Thread/sleep 1500)
    (let [state @ws/client-state
          side (:side state)
          after-hand (count (get-in state [:game-state (keyword side) :hand]))
          after-clicks (get-in state [:game-state (keyword side) :click])]
      (println (str "🃏 Hand: " before-hand " → " after-hand " cards"))
      (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
      (check-auto-end-turn!))))

(defn end-turn!
  "End turn (validates all clicks used unless forced).
   Prevents accidental game state corruption from ending turn with clicks remaining.

   Options:
     :force - If true, allows ending turn with clicks remaining

   Usage: (end-turn!)              ; Normal - errors if clicks remain
          (end-turn! :force true)  ; Forced - allows clicks remaining"
  [& {:keys [force] :or {force false}}]
  (let [state @ws/client-state
        side (:side state)
        clicks (get-in state [:game-state (keyword side) :click])
        gameid (:gameid state)]
    (if (and (> clicks 0) (not force))
      ;; ERROR: clicks remaining and not forced
      (do
        (println (format "❌ ERROR: You still have %d click(s) remaining!" clicks))
        (println "   Use all clicks before ending turn, or use --force flag")
        (println "   Example: send_command end-turn --force")
        {:status :error :clicks-remaining clicks})
      ;; OK: either no clicks or forced
      (do
        (when (and (> clicks 0) force)
          (println (format "⚠️  FORCED: Ending turn with %d click(s) remaining" clicks)))
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "end-turn"
                           :args nil})
        (Thread/sleep 2000)
        (core/show-turn-indicator)
        {:status :success}))))

(defn check-auto-end-turn!
  "Proactively check if turn should auto-end after an action.
   Called automatically after clicks-consuming actions.

   Auto-ends when:
   - 0 clicks remaining
   - No active prompts
   - Not already ended (checks recent log)

   This prevents the 'forgot to end-turn' stuck state."
  []
  (let [state @ws/client-state
        side (keyword (:side state))
        clicks (get-in state [:game-state side :click])
        prompt (get-in state [:game-state side :prompt-state])
        log (get-in state [:game-state :log])
        recent-log (take 3 log)
        already-ended? (some #(clojure.string/includes? (:text %) "is ending their turn")
                            recent-log)]

    (when (and (= clicks 0)
               (nil? prompt)
               (not already-ended?))
      (println "")
      (println "💡 Auto-ending turn (0 clicks, no prompts)")
      (end-turn!))))

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
  (let [state @ws/client-state
        side (keyword (:side state))
        clicks (get-in state [:game-state side :click])
        prompt (get-in state [:game-state side :prompt-state])
        hand-size (get-in state [:game-state side :hand-count])
        max-hand-size (get-in state [:game-state side :hand-size :total] 5)
        installed (get-in state [:game-state side :installed])

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
