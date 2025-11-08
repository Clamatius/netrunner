(ns ai-actions
  "High-level AI player actions - simple API for common game operations"
  (:require [ai-websocket-client-v2 :as ws]))

;; ============================================================================
;; Lobby Management
;; ============================================================================

(defn create-lobby!
  "Create a new game lobby
   Usage: (create-lobby! \"My Test Game\")
          (create-lobby! {:title \"My Game\" :side \"Corp\" :format \"startup\"})"
  ([title-or-options]
   (let [options (if (map? title-or-options)
                   title-or-options
                   {:title title-or-options :side "Any Side"})]
     (println "Creating lobby...")
     (ws/create-lobby! options)
     (Thread/sleep 2000)
     (println "✅ Lobby creation request sent")
     (println "Check lobby list with: (list-lobbies)"))))

(defn list-lobbies
  "Request and display the list of available games"
  []
  (println "Requesting lobby list...")
  (ws/request-lobby-list!)
  (Thread/sleep 1000)
  (ws/show-games))

;; ============================================================================
;; Game Connection
;; ============================================================================

(defn connect-game!
  "Join a game by ID
   Usage: (connect-game! \"game-uuid\" \"Corp\")
          (connect-game! \"game-uuid\") ; defaults to Corp"
  ([gameid] (connect-game! gameid "Corp"))
  ([gameid side]
   (println "Joining game" gameid "as" side "...")
   (ws/join-game! {:gameid gameid :side side})
   (Thread/sleep 2000)
   (if (ws/in-game?)
     (do
       (println "✅ Joined game successfully")
       (ws/show-status))
     (println "❌ Failed to join game"))))

;; ============================================================================
;; Status & Information
;; ============================================================================

(defn status
  "Show current game status"
  []
  (ws/show-status))

(defn show-prompt
  "Display current prompt"
  []
  (ws/show-prompt))

(defn hand
  "Show my hand"
  []
  (let [hand (ws/my-hand)]
    (println "\n=== MY HAND ===" (count hand) "cards ===")
    (doseq [[idx card] (map-indexed vector hand)]
      (println (format "  %d. %s [%s]" idx (:title card) (:type card))))
    hand))

;; ============================================================================
;; Mulligan
;; ============================================================================

(defn keep-hand
  "Keep hand during mulligan"
  []
  (let [prompt (ws/get-prompt)]
    (if (and prompt (= "button" (:prompt-type prompt)))
      (let [keep-choice (first (filter #(= "Keep" (:value %)) (:choices prompt)))]
        (if keep-choice
          (do
            (println "Keeping hand...")
            (ws/send-action! "choice" {:choice {:uuid (:uuid keep-choice)}})
            (Thread/sleep 1000)
            (println "✅ Hand kept"))
          (println "⚠️  No 'Keep' option found in prompt")))
      (println "⚠️  No mulligan prompt active"))))

(defn mulligan
  "Mulligan (redraw) hand"
  []
  (let [prompt (ws/get-prompt)]
    (if (and prompt (= "button" (:prompt-type prompt)))
      (let [mulligan-choice (first (filter #(= "Mulligan" (:value %)) (:choices prompt)))]
        (if mulligan-choice
          (do
            (println "Mulliganing hand...")
            (ws/send-action! "choice" {:choice {:uuid (:uuid mulligan-choice)}})
            (Thread/sleep 1000)
            (println "✅ Hand mulliganed"))
          (println "⚠️  No 'Mulligan' option found in prompt")))
      (println "⚠️  No mulligan prompt active"))))

;; ============================================================================
;; Basic Actions
;; ============================================================================

(defn take-credits
  "Click for credit"
  []
  (println "Taking credit...")
  (ws/take-credits!)
  (Thread/sleep 500)
  (println "✅ Credit taken"))

(defn draw-card
  "Draw a card"
  []
  (println "Drawing card...")
  (ws/draw-card!)
  (Thread/sleep 500)
  (println "✅ Card drawn"))

(defn end-turn
  "End the current turn"
  []
  (println "Ending turn...")
  (ws/end-turn!)
  (Thread/sleep 500)
  (println "✅ Turn ended"))

;; ============================================================================
;; Prompts & Choices
;; ============================================================================

(defn choose
  "Make a choice from current prompt
   Usage: (choose 0) ; choose first option
          (choose \"uuid\") ; choose by UUID"
  [choice]
  (let [prompt (ws/get-prompt)]
    (if prompt
      (do
        (println "Making choice:" choice)
        (ws/choose! choice)
        (Thread/sleep 500)
        (println "✅ Choice made"))
      (println "⚠️  No active prompt"))))

(defn auto-keep-mulligan
  "Automatically handle mulligan by keeping hand"
  []
  (println "Waiting for mulligan prompt...")
  (loop [checks 0]
    (when (< checks 20)
      (Thread/sleep 1000)
      (let [prompt (ws/get-prompt)]
        (if (and prompt (= "button" (:prompt-type prompt)))
          (do
            (println "Mulligan prompt detected")
            (keep-hand))
          (recur (inc checks))))))
  (println "Mulligan complete"))

;; ============================================================================
;; Wait Helpers
;; ============================================================================

(defn wait-for-prompt
  "Wait for a prompt to appear (up to max-seconds)
   Returns prompt or nil if timeout"
  [max-seconds]
  (println (format "Waiting for prompt (max %d seconds)..." max-seconds))
  (loop [checks 0]
    (if (< checks max-seconds)
      (if-let [prompt (ws/get-prompt)]
        (do
          (println "✅ Prompt detected")
          prompt)
        (do
          (Thread/sleep 1000)
          (recur (inc checks))))
      (do
        (println "⏱️  Timeout waiting for prompt")
        nil))))

(defn wait-for-my-turn
  "Wait for it to be my turn (up to max-seconds)"
  [max-seconds]
  (println (format "Waiting for my turn (max %d seconds)..." max-seconds))
  (loop [checks 0]
    (if (< checks max-seconds)
      (if (ws/my-turn?)
        (do
          (println "✅ It's my turn!")
          true)
        (do
          (Thread/sleep 1000)
          (recur (inc checks))))
      (do
        (println "⏱️  Timeout waiting for turn")
        false))))

;; ============================================================================
;; High-Level Workflows
;; ============================================================================

(defn simple-corp-turn
  "Execute a simple Corp turn: click for credit 3 times, end turn"
  []
  (println "\n=== SIMPLE CORP TURN ===")
  (dotimes [i 3]
    (take-credits))
  (end-turn)
  (println "=== TURN COMPLETE ===\n"))

(defn simple-runner-turn
  "Execute a simple Runner turn: click for credit 4 times, end turn"
  []
  (println "\n=== SIMPLE RUNNER TURN ===")
  (dotimes [i 4]
    (take-credits))
  (end-turn)
  (println "=== TURN COMPLETE ===\n"))

;; ============================================================================
;; Debugging
;; ============================================================================

(defn inspect-state
  "Show raw game state (for debugging)"
  []
  (clojure.pprint/pprint @ws/client-state))

(defn inspect-prompt
  "Show raw prompt data (for debugging)"
  []
  (clojure.pprint/pprint (ws/get-prompt)))

;; ============================================================================
;; Help
;; ============================================================================

(defn help
  "Show available commands"
  []
  (println "\n=== AI ACTIONS HELP ===\n")
  (println "Lobby Management:")
  (println "  (create-lobby! \"Game Name\")       - Create a new game lobby")
  (println "  (create-lobby! {:title \"Game\"})   - Create with options map")
  (println "  (list-lobbies)                     - List available games")
  (println "\nConnection:")
  (println "  (connect-game! \"game-id\" \"Corp\") - Join a game")
  (println "\nInformation:")
  (println "  (status)                           - Show game status")
  (println "  (show-prompt)                      - Show current prompt")
  (println "  (hand)                             - Show my hand")
  (println "\nMulligan:")
  (println "  (keep-hand)                        - Keep hand")
  (println "  (mulligan)                         - Redraw hand")
  (println "  (auto-keep-mulligan)               - Auto-handle mulligan")
  (println "\nBasic Actions:")
  (println "  (take-credits)                     - Click for credit")
  (println "  (draw-card)                        - Draw a card")
  (println "  (end-turn)                         - End turn")
  (println "\nPrompts:")
  (println "  (choose 0)                         - Choose first option")
  (println "  (wait-for-prompt 10)               - Wait for prompt")
  (println "  (wait-for-my-turn 30)              - Wait for my turn")
  (println "\nWorkflows:")
  (println "  (simple-corp-turn)                 - 3x credit, end")
  (println "  (simple-runner-turn)               - 4x credit, end")
  (println "\nDebugging:")
  (println "  (inspect-state)                    - Show raw state")
  (println "  (inspect-prompt)                   - Show raw prompt")
  (println "  (help)                             - This help\n"))

(println "✨ AI Actions loaded. Type (ai-actions/help) for commands")
