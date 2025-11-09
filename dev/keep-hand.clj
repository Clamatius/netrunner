(do
  (let [gs (:game-state @ai-websocket-client-v2/client-state)
        prompt (get-in gs [:runner :prompt-state])
        keep-uuid (get-in prompt [:choices 0 :uuid])]
    (println "Keeping Runner hand...")
    (ai-websocket-client-v2/send-action! "choice" {:choice {:uuid keep-uuid}})
    (Thread/sleep 2000)
    (println "Done!")))
