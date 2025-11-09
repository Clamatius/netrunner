#!/bin/bash
# AI client creates a game as Corp

TIMEOUT=30 ./dev/src/clj/nrepl-eval.sh '
(do
  (println "=== LOADING AI CLIENT ===")
  (load-file "dev/src/clj/ai_websocket_client_v2.clj")

  (println "\n=== CONNECTING ===")
  (ai-websocket-client-v2/ensure-connected!)
  (Thread/sleep 2000)

  (println "\n=== CREATING GAME ===")
  ;; Send lobby/create message
  (ai-websocket-client-v2/send-message! :lobby/create
    {:title "AI Corp Game"
     :format "system-gateway"
     :room "casual"
     :options {:spectatorhands false
               :turmoil false
               :api-access false
               :save-replay false}
     :precon :beginner
     :allow-spectator true
     :open-decklists true})

  (Thread/sleep 3000)

  (println "\n=== CHECKING LOBBY STATE ===")
  (let [messages (:messages @ai-websocket-client-v2/client-state)
        lobby-states (filter #(= :lobby/state (:type %)) messages)
        latest-lobby (last lobby-states)]
    (if latest-lobby
      (do
        (println "✅ Game created!")
        (let [lobby-data (:data latest-lobby)]
          (println "   Game ID:" (:gameid lobby-data))
          (println "   Title:" (:title lobby-data))
          (println "   Players:" (count (:players lobby-data)))
          (when-let [players (:players lobby-data)]
            (doseq [player players]
              (println "     -" (:side player) ":" (get-in player [:user :username]))))))
      (println "❌ No lobby state received")))

  (println "\n=== INSTRUCTIONS ===")
  (println "Join this game as Runner from the web UI"))
'
