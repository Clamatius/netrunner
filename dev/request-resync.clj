;; Request game resync to get full state
(println "\n=== REQUESTING GAME RESYNC ===")

(let [gameid (:gameid @ws/client-state)]
  (if gameid
    (do
      (println "Requesting resync for game:" gameid)
      (ws/send-message! :game/resync {:gameid gameid})
      (Thread/sleep 2000)
      (println "✅ Resync requested!")
      (println "\nChecking hand after resync...")
      (ai/hand))
    (println "❌ No active game")))

:done
