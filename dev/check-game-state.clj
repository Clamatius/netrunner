;; Check detailed game state
(println "\n=== DETAILED GAME STATE ===")

(let [state (ws/get-current-state)]
  (println "\n🎲 Turn Info:")
  (println "  Turn:" (:turn state))
  (println "  Active player:" (:active-player state))
  (println "  Phase:" (:phase state))
  (println "  End turn requested:" (:end-turn state))

  (println "\n🏃 Runner State:")
  (let [runner (:runner state)]
    (println "  Credits:" (:credit runner))
    (println "  Clicks:" (:click runner))
    (println "  Hand size:" (count (:hand runner)))
    (println "  Installed programs:" (count (:program runner)))
    (println "  Installed resources:" (count (:resource runner))))

  (println "\n🏢 Corp State:")
  (let [corp (:corp state)]
    (println "  Credits:" (:credit corp))
    (println "  Clicks:" (:click corp))
    (println "  Hand size:" (count (:hand corp)))
    (println "  Installed ICE:" (count (:servers corp)))
    (println "  R&D size:" (count (:deck corp)))
    (println "  HQ size:" (count (:hand corp))))

  (println "\n🔔 Prompts:")
  (let [runner-prompt (-> state :runner :prompt first)
        corp-prompt (-> state :corp :prompt first)]
    (println "  Runner prompt:" (when runner-prompt (:msg runner-prompt)))
    (println "  Corp prompt:" (when corp-prompt (:msg corp-prompt)))))

:done
