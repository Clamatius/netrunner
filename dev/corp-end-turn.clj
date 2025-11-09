(do
  (println "Ending Corp turn...")
  (load-file "dev/src/clj/ai_websocket_client_v2.clj")

  (ai-websocket-client-v2/send-action! "end-turn" nil)
  (Thread/sleep 2000)

  (println "Turn ended!")
  (println "Checking for discard prompt...")

  (let [gs (:game-state @ai-websocket-client-v2/client-state)
        prompt (get-in gs [:corp :prompt-state])]
    (if prompt
      (do
        (println "Corp Prompt Found:")
        (println "  Message:" (:msg prompt))
        (println "  Type:" (:prompt-type prompt))
        (println "  Selectable cards:" (count (:selectable prompt))))
      (println "No Corp prompt found"))))
