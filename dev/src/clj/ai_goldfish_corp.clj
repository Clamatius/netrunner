(ns ai-goldfish-corp
  "Basic 'Goldfish' Corp AI.
   - Strategy: Take credits x3, End Turn.
   - Purpose: Baseline opponent for Runner testing."
  (:require [ai-state :as state]
            [ai-basic-actions :as actions]
            [ai-loop-sync :as loop-sync]
            [ai-prompts :as prompts]
            [ai-runs :as runs]
            [clojure.string :as str]))

(defn play-turn
  "Play a simple turn: Start, Take Credits x3, End."
  []
  (println "🐠 GOLDFISH CORP - Playing turn...")
  
  ;; 1. Start Turn
  (actions/start-turn!)
  (Thread/sleep 500)
  
  ;; 2. Spend clicks (Take Credit x3)
  (dotimes [_ 3]
    (when (pos? (state/corp-clicks))
      (actions/take-credit!)
      (Thread/sleep 200)))
      
  ;; 3. End Turn
  (actions/smart-end-turn!))

(defn handle-run-response
  "Handle run responses (always pass/no-rez for Goldfish)"
  []
  (let [game-state @state/client-state]
    (when (runs/should-i-act? game-state "corp")
      (println "🐠 GOLDFISH CORP - Passing priority/declining rez in run")
      (runs/continue-run! "--no-rez")
      (Thread/sleep 500))))

(defn handle-prompts
  "Handle any interrupting prompts (like discard)."
  []
  (when-let [prompt (state/get-prompt)]
    (println "🐠 GOLDFISH CORP - Handling prompt:" (:msg prompt))
    (cond
      ;; Discard: Just pick first card
      (str/includes? (:msg prompt) "Discard")
      (prompts/discard-to-hand-size!)

      ;; Run: Handle run response (rez/pass)
      (= (:prompt-type prompt) "run")
      (handle-run-response)
      
      ;; Default: Choose first option
      :else
      (prompts/choose-by-index! 0))))

(defn start-autonomous!
  "Main autonomous loop."
  []
  (println "🐠 GOLDFISH CORP - Starting autonomous loop")
  (loop [resync 0]
    (let [{:keys [continue? resync-next]}
          (try
            ;; #144: reach the SAME authority the CLI gate uses before acting.
            ;; Cheap when healthy (no round trip while a board is cached), it
            ;; REPAIRS a boardless seat, and it is bounded — a seat that cannot
            ;; be repaired stops with a diagnostic instead of refusing forever.
            (let [{:keys [action attempts]}
                  (loop-sync/report! "goldfish-corp" (loop-sync/ensure-board! resync))]
              (if (not= :act action)
                {:continue? (not= :stop action) :resync-next attempts}
                (let [game-state @state/client-state
                      winner (get-in game-state [:game-state :winner])]
                  (if winner
                    (do
                      (println "🐠 GOLDFISH CORP - Game over (Winner:" winner ") - Stopping loop.")
                      {:continue? false})
                    (let [my-turn? (= "corp" (:active-player (:game-state game-state)))]
                      ;; Handle Prompts
                      (when (state/get-prompt)
                        (handle-prompts)
                        (Thread/sleep 500))

                      ;; Handle Run Responses (CRITICAL for unstucking runs)
                      (handle-run-response)

                      ;; Auto-start turn
                      (let [start-check (actions/can-start-turn?)]
                        (when (:can-start start-check)
                          (println "🐠 GOLDFISH CORP - Auto-starting turn")
                          (actions/start-turn!)
                          (Thread/sleep 500)))

                      ;; Play Turn
                      (when (and my-turn? (not (state/get-prompt)))
                        (if (pos? (state/corp-clicks))
                          (play-turn)
                          (do
                            (println "🐠 GOLDFISH CORP - 0 clicks, ending turn")
                            (actions/smart-end-turn!))))

                      {:continue? true}))))) ;; Continue loop
            (catch Exception e
              (println "❌ GOLDFISH CORP ERROR:" (.getMessage e))
              (Thread/sleep 5000)
              ;; An exception is not a failed resync — carry the count, don't spend it.
              {:continue? true :resync-next resync}))] ;; Continue loop on error
      (when continue?
        (Thread/sleep 1000)
        (recur (or resync-next 0))))))
