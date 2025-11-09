(do
  (println "Taking credit with final click...")
  (load-file "dev/src/clj/ai_websocket_client_v2.clj")

  (ai-websocket-client-v2/send-action! "credit" nil)
  (Thread/sleep 2000)

  (println "Now ending turn...")
  (ai-websocket-client-v2/send-action! "end-turn" nil)
  (Thread/sleep 3000)

  (println "Turn ended! Checking for discard prompt...")

  ;; Wait a bit more for the diff to arrive
  (Thread/sleep 2000)

  (let [gs (:game-state @ai-websocket-client-v2/client-state)
        prompt (get-in gs [:corp :prompt-state])]
    (if prompt
      (do
        (println "\nCorp Prompt Found:")
        (println "  Message:" (:msg prompt))
        (println "  Type:" (:prompt-type prompt))
        (when (:selectable prompt)
          (println "  Selectable cards:" (count (:selectable prompt))))
        (when (:eid prompt)
          (println "  EID:" (:eid prompt))))
      (println "\nNo Corp prompt found"))))
