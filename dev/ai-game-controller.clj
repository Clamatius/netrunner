(ns ai-game-controller
  "Controller for AI player that runs in the main REPL and maintains state"
  (:require [ai-websocket-client-v2 :as client]))

(defn join-game!
  "Join a game as Corp or Runner"
  [gameid side]
  (println (format "Joining game %s as %s..." gameid side))
  (let [gameid-uuid (if (string? gameid)
                      (java.util.UUID/fromString gameid)
                      gameid)]
    (client/send-message! :lobby/join
                         {:gameid gameid-uuid
                          :request-side side}))
  (Thread/sleep 2000)
  (println "Joined!"))

(defn handle-mulligan!
  "Handle mulligan prompt - always keep for now"
  [side]
  (let [gs (:game-state @client/client-state)
        prompt (get-in gs [(keyword (clojure.string/lower-case side)) :prompt-state])]
    (if (and prompt (clojure.string/includes? (:msg prompt) "Mulligan"))
      (do
        (println "Handling mulligan - keeping hand")
        (client/send-action! "choice" {:choice "Keep"})
        (Thread/sleep 2000)
        (println "Kept hand!")
        true)
      (do
        (println "No mulligan prompt found")
        false))))

(defn start-turn!
  "Start the turn"
  []
  (println "Starting turn...")
  (client/send-action! "start-turn" nil)
  (Thread/sleep 2000)
  (let [gs (:game-state @client/client-state)]
    (println "Turn started!")
    (println "  Turn:" (:turn gs))
    (println "  Active:" (:active-player gs))))

(defn draw-card!
  "Draw a card"
  []
  (println "Drawing card...")
  (client/send-action! "draw" nil)
  (Thread/sleep 2000))

(defn take-credit!
  "Take a credit"
  []
  (println "Taking credit...")
  (client/send-action! "credit" nil)
  (Thread/sleep 2000))

(defn end-turn!
  "End the turn"
  []
  (println "Ending turn...")
  (client/send-action! "end-turn" nil)
  (Thread/sleep 3000))

(defn handle-discard!
  "Handle discard prompt by discarding first N cards"
  [side n]
  (let [gs (:game-state @client/client-state)
        side-key (keyword (clojure.string/lower-case side))
        prompt (get-in gs [side-key :prompt-state])
        hand (get-in gs [side-key :hand])]

    (if (and prompt (= "select" (:prompt-type prompt)))
      (do
        (println (format "Discard prompt found - need to discard %d cards" n))
        (println (format "Hand has %d cards" (count hand)))

        (let [cards-to-discard (take n hand)
              eid (:eid prompt)]
          (println "\nDiscarding:")
          (doseq [[idx card] (map-indexed vector cards-to-discard)]
            (println (format "  %d. %s (CID: %s)" (inc idx) (:title card) (:cid card))))

          ;; Select each card
          (doseq [[idx card] (map-indexed vector cards-to-discard)]
            (println (format "\nSelecting card %d..." (inc idx)))
            (client/send-action! "select"
                               {:card {:cid (:cid card)
                                      :zone (:zone card)
                                      :side (:side card)
                                      :type (:type card)}
                                :eid eid
                                :shift-key-held false})
            (Thread/sleep 1500))

          (println "\nAll cards selected!")
          true))
      (do
        (println "No discard prompt found")
        false))))

(defn show-state
  "Show current game state"
  []
  (let [gs (:game-state @client/client-state)]
    (println "\n=== GAME STATE ===")
    (println "Turn:" (:turn gs))
    (println "Active:" (:active-player gs))
    (println "\nCorp:")
    (println "  Hand:" (get-in gs [:corp :hand-count]))
    (println "  Clicks:" (get-in gs [:corp :click]))
    (println "  Credits:" (get-in gs [:corp :credit]))
    (let [prompt (get-in gs [:corp :prompt-state])]
      (when prompt
        (println "  Prompt:" (:msg prompt))))
    (println "\nRunner:")
    (println "  Hand:" (get-in gs [:runner :hand-count]))
    (println "  Clicks:" (get-in gs [:runner :click]))
    (println "  Credits:" (get-in gs [:runner :credit]))
    (let [prompt (get-in gs [:runner :prompt-state])]
      (when prompt
        (println "  Prompt:" (:msg prompt))))))

(defn full-turn-test!
  "Run a full turn: start, draw 2, credit, end, handle discard"
  []
  (println "\n=== FULL TURN TEST ===")
  (start-turn!)
  (draw-card!)
  (draw-card!)
  (take-credit!)
  (end-turn!)
  (Thread/sleep 2000)
  (when (handle-discard! "Corp" 3)
    (println "\nDiscard handled!"))
  (show-state))

(println "\nAI Game Controller loaded!")
(println "Available functions:")
(println "  (join-game! \"game-id\" \"Corp\") - Join a game")
(println "  (handle-mulligan! \"Corp\")      - Handle mulligan prompt")
(println "  (start-turn!)                   - Start turn")
(println "  (draw-card!)                    - Draw a card")
(println "  (take-credit!)                  - Take a credit")
(println "  (end-turn!)                     - End turn")
(println "  (handle-discard! \"Corp\" 3)      - Handle discard prompt")
(println "  (show-state)                    - Show current game state")
(println "  (full-turn-test!)               - Run full turn with discard")
