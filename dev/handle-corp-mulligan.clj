(do
  (println "Checking Corp mulligan prompt...")
  (let [gs (:game-state @ai-websocket-client-v2/client-state)
        prompt (get-in gs [:corp :prompt-state])]
    (if prompt
      (do
        (println "Prompt message:" (:msg prompt))
        (println "Prompt type:" (:prompt-type prompt))
        (when (:choices prompt)
          (println "Choices:")
          (doseq [[idx choice] (map-indexed vector (:choices prompt))]
            (println "  " idx ":" (:value choice) "| UUID:" (:uuid choice))))

        ;; Keep hand (first choice)
        (let [keep-uuid (get-in prompt [:choices 0 :uuid])]
          (when keep-uuid
            (println "\nKeeping hand...")
            (ai-websocket-client-v2/send-action! "choice" {:choice {:uuid keep-uuid}})
            (Thread/sleep 2000)
            (println "Mulligan response sent!"))))
      (println "No Corp prompt found"))))
