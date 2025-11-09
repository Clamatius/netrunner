;; Check what we have in full state
(println "\n=== CHECKING STATE STRUCTURE ===")

(let [state (ws/get-current-state)]
  (println "State keys:" (keys state))

  (println "\n--- Runner keys ---")
  (println (keys (:runner state)))

  (println "\n--- Sample of runner data ---")
  (println "Credits:" (get-in state [:runner :credit]))
  (println "Clicks:" (get-in state [:runner :click]))
  (println "Hand type:" (type (get-in state [:runner :hand])))
  (println "Hand first 3 items:" (take 3 (get-in state [:runner :hand]))))

:done
