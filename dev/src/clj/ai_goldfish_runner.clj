(ns ai-goldfish-runner
  "Basic 'Goldfish' Runner AI.
   - Strategy: Take credits, draw cards, end turn.
   - Purpose: Baseline opponent for Corp testing."
  (:require [ai-state :as state]
            [ai-basic-actions :as actions]
            [ai-prompts :as prompts]
            [ai-core :as core]))

(defn play-turn
  "Play a simple turn: Start, Take Credits/Draw, End."
  []
  (println "🐟 GOLDFISH - Playing turn...")
  
  ;; 1. Start Turn
  (actions/start-turn!)
  (Thread/sleep 500)
  
  ;; 2. Spend clicks (Take Credit x3, Draw x1)
  (dotimes [_ 3]
    (when (pos? (state/runner-clicks))
      (actions/take-credit!)
      (Thread/sleep 200)))
      
  (when (pos? (state/runner-clicks))
    (actions/draw-card!)
    (Thread/sleep 200))
    
  ;; 3. End Turn
  (actions/smart-end-turn!))

(defn handle-prompts
  "Handle any interrupting prompts (like discard)."
  []
  (when-let [prompt (state/get-prompt)]
    (println "🐟 GOLDFISH - Handling prompt:" (:msg prompt))
    (cond
      ;; Discard: Just pick first card
      (clojure.string/includes? (:msg prompt) "Discard")
      (prompts/discard-to-hand-size!)
      
      ;; Default: Choose first option
      :else
      (prompts/choose! 0))))

(defn loop!
  "Main autonomous loop."
  []
  (println "🐟 GOLDFISH - Starting autonomous loop")
  (loop []
    (try
      (let [game-state @state/client-state
            my-turn? (= "runner" (:active-player (:game-state game-state)))]
        
        ;; Handle Prompts (always priority)
        (when (state/get-prompt)
          (handle-prompts)
          (Thread/sleep 500))
          
        ;; Auto-start turn if valid
        (let [start-check (actions/can-start-turn?)]
          (when (:can-start start-check)
            (println "🐟 GOLDFISH - Auto-starting turn")
            (actions/start-turn!)
            (Thread/sleep 500)))

        ;; Play Turn
        (when (and my-turn? (not (state/get-prompt)))
          (play-turn))
          
        ;; Sleep
        (Thread/sleep 1000))
      (catch Exception e
        (println "❌ GOLDFISH ERROR:" (.getMessage e))
        (Thread/sleep 5000)))
    (recur)))
