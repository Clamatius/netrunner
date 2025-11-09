(do
  (println "Drawing cards to exceed hand size...")
  (load-file "dev/src/clj/ai_websocket_client_v2.clj")

  ;; Draw 2 cards (we have 3 clicks, use 2 for drawing)
  (println "Click 1: Drawing card...")
  (ai-websocket-client-v2/send-action! "draw" nil)
  (Thread/sleep 2000)

  (println "Click 2: Drawing card...")
  (ai-websocket-client-v2/send-action! "draw" nil)
  (Thread/sleep 2000)

  ;; Check hand count
  (let [gs (:game-state @ai-websocket-client-v2/client-state)
        hand-count (get-in gs [:corp :hand-count])]
    (println "Corp hand count:" hand-count))

  (println "Done drawing!"))
