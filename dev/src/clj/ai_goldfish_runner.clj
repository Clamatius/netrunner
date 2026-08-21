(ns ai-goldfish-runner
  "Basic 'Goldfish' Runner AI.
   - Strategy: Take credits, draw cards, end turn.
   - Purpose: Baseline opponent for Corp testing."
  (:require [ai-state :as state]
            [ai-basic-actions :as actions]
            [ai-loop-sync :as loop-sync]
            [ai-prompts :as prompts]
            [clojure.string :as str]))

(defn play-turn
  "Play a simple turn: Start, Take Credits x3, End."
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
      (prompts/choose-by-index! 0))))

(defn loop!
  "Main autonomous loop."
  []
  (println "🐟 GOLDFISH - Starting autonomous loop")
  (loop [resync loop-sync/initial-tracker]
    ;; #144: reach the SAME authority the CLI gate uses before acting. Cheap when
    ;; healthy (no round trip while a board is cached and recently verified), it
    ;; REPAIRS a boardless seat, and it is bounded — a seat that cannot be
    ;; repaired stops with a diagnostic instead of refusing forever.
    ;;
    ;; It sits OUTSIDE the tick body's try so the tracker cannot be reverted by
    ;; a body exception, and so an interrupt raised in here propagates and ends
    ;; the loop rather than being caught by the body's handler.
    (let [{:keys [action tracker]}
          (loop-sync/report! "goldfish-runner" (loop-sync/ensure-board! resync))
          {:keys [continue?]}
          (if (not= :act action)
            {:continue? (not= :stop action)}
            (try
              (let [game-state @state/client-state
                    winner (get-in game-state [:game-state :winner])]
                (if winner
                  (do
                    (println "🐟 GOLDFISH - Game over (Winner:" winner ") - Stopping loop.")
                    {:continue? false})
                  (let [my-turn? (= "runner" (:active-player (:game-state game-state)))]
                    ;; Handle Prompts
                    (when (state/get-prompt)
                      (handle-prompts)
                      (Thread/sleep 500))

                    ;; Auto-start turn
                    (let [start-check (actions/can-start-turn?)]
                      (when (:can-start start-check)
                        (println "🐟 GOLDFISH - Auto-starting turn")
                        (actions/start-turn!)
                        (Thread/sleep 500)))

                    ;; Play Turn
                    (when (and my-turn? (not (state/get-prompt)))
                      (if (pos? (state/runner-clicks))
                        (play-turn)
                        (do
                          (println "🐟 GOLDFISH - 0 clicks, ending turn")
                          (actions/smart-end-turn!))))

                    {:continue? true})))
              ;; bot-loop-stop stops us with future-cancel, i.e. an interrupt.
              ;; Swallowing it would keep a cancelled loop running, and a later
              ;; bot-loop would put TWO loops on one seat (guest 2nd pass).
              (catch InterruptedException e
                (println "🛑 Loop interrupted — stopping.")
                (.interrupt (Thread/currentThread))
                {:continue? false})
              (catch Exception e
                (println "❌ GOLDFISH ERROR:" (.getMessage e))
                (Thread/sleep 5000)
                ;; A tick-body exception is not a failed resync. The tracker is
                ;; bound above, so it rides through this untouched.
                {:continue? true})))]
      (when continue?
        (Thread/sleep 1000)
        (recur tracker)))))
