(do
  (println "🎯 Responding to mulligan prompt - keeping hand...")

  (require '[ai-websocket-client-v2 :as ws])

  ;; Get current game ID and prompt
  (let [gameid (:gameid @ws/client-state)
        prompt (ws/get-prompt)

        ;; Find the "Keep" choice UUID
        keep-choice (first (filter #(= "Keep" (:value %)) (:choices prompt)))
        keep-uuid (:uuid keep-choice)]

    (if (and gameid prompt keep-uuid)
      (do
        (println "  Prompt:" (:msg prompt))
        (println "  Sending Keep choice...")

        ;; Send choice action
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "choice"
                           :args {:choice {:uuid keep-uuid}}})

        (Thread/sleep 2000)
        (println "✅ Keep hand choice sent!"))

      (do
        (println "❌ Error:")
        (when-not gameid (println "  - No active game"))
        (when-not prompt (println "  - No active prompt"))
        (when-not keep-uuid (println "  - Could not find 'Keep' choice"))))))
