;; Play Sure Gamble with proper card object
(println "\n=== PLAYING SURE GAMBLE ===")

(let [hand (ws/my-hand)
      sure-gamble (first (filter #(= "Sure Gamble" (:title %)) hand))]

  (if sure-gamble
    (do
      (println "Found Sure Gamble")
      (println "  Title:" (:title sure-gamble))
      (println "  CID:" (:cid sure-gamble))
      (println "  Cost:" (:cost sure-gamble))

      (println "\nCredits before:" (ws/my-credits))
      (println "Clicks before:" (get-in (ws/get-current-state) [:runner :click]))

      ;; Send play command with card object (selecting only needed fields like web client)
      (let [card-ref (select-keys sure-gamble [:cid :zone :side :type])]
        (println "\nSending play command with card:" card-ref)
        (ws/send-action! "play" {:card card-ref}))

      (Thread/sleep 2000)

      (println "\nCredits after:" (ws/my-credits))
      (println "Clicks after:" (get-in (ws/get-current-state) [:runner :click]))
      (println "✅ Sure Gamble played!"))
    (println "❌ Sure Gamble not found in hand")))

:done
