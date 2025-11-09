;; Check runner's prompt in detail
(let [state (ws/get-current-state)
      runner (:runner state)
      prompts (:prompt runner)]
  (println "\n🔔 Runner Prompt Details:")
  (println "Number of prompts:" (count prompts))
  (when (seq prompts)
    (doseq [p prompts]
      (println "\nPrompt:")
      (clojure.pprint/pprint p))))

:done
