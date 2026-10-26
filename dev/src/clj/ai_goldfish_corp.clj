(ns ai-goldfish-corp
  "Basic 'Goldfish' Corp AI.
   - Strategy: Take credits x3, End Turn.
   - Purpose: Baseline opponent for Runner testing."
  (:require [ai-state :as state]
            [ai-basic-actions :as actions]
            [ai-loop-sync :as loop-sync]
            [ai-prompts :as prompts]
            [ai-runs :as runs]
            [ai-core :as core]
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
  "Main autonomous loop.

   Options:
     :heartbeat-file - path to THIS side's peer-liveness heartbeat (#286/#290).
                   The loop lives inside the REPL, so the launching
                   send_command invocation's toucher dies the moment the eval
                   returns while we play on — and 300s later the opponent is
                   told we disconnected. Passed by `send_command bot-loop
                   --agent goldfish`; absent when driven by hand, which is a
                   no-op. See ai-core/start-heartbeat-toucher!."
  [& {:keys [heartbeat-file]}]
  ;; Everything below runs under the heartbeat toucher. The body keeps its
  ;; original indentation on purpose: re-indenting the whole loop would bury a
  ;; two-line change in a whitespace diff.
  (let [stop-heartbeat! (core/start-heartbeat-toucher! heartbeat-file)]
   (try
  (println "🐠 GOLDFISH CORP - Starting autonomous loop")
  (loop [resync loop-sync/initial-tracker]
    ;; #144: reach the SAME authority the CLI gate uses before acting. Cheap when
    ;; healthy, it REPAIRS a boardless seat, and it is bounded. It sits OUTSIDE
    ;; the tick body's try so the tracker cannot be reverted by a body exception,
    ;; and so an interrupt raised in here propagates and ends the loop.
    (let [{:keys [action tracker]}
          (loop-sync/report! "goldfish-corp" (loop-sync/ensure-board! resync))
          {:keys [continue?]}
          (if (not= :act action)
            {:continue? (not= :stop action)}
            (try
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

                    {:continue? true})))
              (catch InterruptedException e
                (println "🛑 Loop interrupted — stopping.")
                (.interrupt (Thread/currentThread))
                {:continue? false})
              (catch Exception e
                (println "❌ GOLDFISH CORP ERROR:" (.getMessage e))
                (Thread/sleep 5000)
                {:continue? true})))]
      (when continue?
        (Thread/sleep 1000)
        (recur tracker))))
   ;; A stopped loop must stop claiming to be alive, on EVERY exit path: a
   ;; winner, the stall/error arms, and bot-loop-stop's future-cancel. The
   ;; cancel arrives as an interrupt and can land in three different places —
   ;; inside the tick-body try (caught, flag re-set, {:continue? false}), in
   ;; ensure-board!, or in the between-tick sleep below, and the last two are
   ;; outside that try, so they propagate. `finally` is what makes all of them
   ;; the same for the toucher, which is the only claim needed here.
   ;;
   ;; Two earlier versions of this comment were wrong, both by comparing this
   ;; loop to the heuristic ones (they handle the interrupt identically —
   ;; ai_heuristic_corp.clj:1448-1451, ai_heuristic_runner.clj:504-507) and by
   ;; calling the propagating paths nonexistent. Hence: no comparison, and
   ;; nothing asserted that is not in this fn.
   (finally (stop-heartbeat!)))))
