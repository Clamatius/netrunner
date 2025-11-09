;; Handle mulligan by keeping hand
(println "\n=== HANDLING MULLIGAN ===")

;; Check the prompt
(let [prompt (ws/get-prompt)]
  (println "Prompt:" prompt)

  (if prompt
    (let [keep-choice (first (filter #(= "Keep" (:value %)) (:choices prompt)))]
      (if keep-choice
        (do
          (println "\nKeeping hand...")
          (println "Keep choice:" keep-choice)
          (ws/send-action! "choice" {:choice {:uuid (:uuid keep-choice)}})
          (Thread/sleep 1500)
          (println "✅ Choice sent!"))
        (println "❌ No Keep option found")))
    (println "❌ No prompt")))

:done
