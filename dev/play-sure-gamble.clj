;; Play Sure Gamble from hand
(println "\n=== PLAYING SURE GAMBLE ===")

;; Get my hand
(let [hand (ws/my-hand)
      sure-gamble (first (filter #(= "Sure Gamble" (:title %)) hand))]

  (if sure-gamble
    (do
      (println "Found Sure Gamble in hand")
      (println "Card:" (:title sure-gamble))
      (println "Cost:" (:cost sure-gamble))
      (println "CID:" (:cid sure-gamble))

      (println "\nCredits before:" (ws/my-credits))
      (println "Playing Sure Gamble...")

      ;; Send play command
      (ws/send-action! "play" {:card sure-gamble})
      (Thread/sleep 1500)

      (println "\nCredits after:" (ws/my-credits))
      (println "✅ Sure Gamble played!"))
    (println "❌ Sure Gamble not found in hand")))

:done
