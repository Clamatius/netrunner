;; Install Telework Contract
(println "\n=== INSTALLING TELEWORK CONTRACT ===")

(let [hand (ws/my-hand)
      telework (first (filter #(= "Telework Contract" (:title %)) hand))]

  (if telework
    (do
      (println "Found Telework Contract")
      (println "  Title:" (:title telework))
      (println "  Cost:" (:cost telework))
      (println "  Type:" (:type telework))

      (println "\nBefore:")
      (println "  Credits:" (ws/my-credits))
      (println "  Clicks:" (get-in (ws/get-current-state) [:runner :click]))

      ;; Send play command (install is also "play" command for resources)
      (let [card-ref (select-keys telework [:cid :zone :side :type])]
        (println "\nInstalling...")
        (ws/send-action! "play" {:card card-ref}))

      (Thread/sleep 2000)

      ;; Resync to see result
      (println "\nResyncing to see result...")
      (ws/send-message! :game/resync {:gameid (:gameid @ws/client-state)})
      (Thread/sleep 2000)

      (println "\nAfter install:")
      (println "  Credits:" (ws/my-credits))
      (println "  Clicks:" (get-in (ws/get-current-state) [:runner :click]))
      (println "  Hand:" (count (ws/my-hand)) "cards")

      (println "\n✅ Telework Contract installed!"))
    (println "❌ Telework Contract not found in hand")))

:done
