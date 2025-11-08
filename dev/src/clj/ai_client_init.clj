(ns ai-client-init
  "Initialization script for AI Client REPL")

(println "\n=== AI Client REPL Starting ===")
(println "Loading WebSocket client...")

;; Load the websocket client
(load-file "dev/src/clj/ai_websocket_client_v2.clj")

;; Load high-level actions
(load-file "dev/src/clj/ai_actions.clj")

;; Auto-connect to local server
(println "\nConnecting to ws://localhost:1042/chsk...")
(ai-websocket-client-v2/connect! "ws://localhost:1042/chsk")
(Thread/sleep 2000)

(if (ai-websocket-client-v2/connected?)
  (do
    (println "\n✅ AI Client Ready!")
    (println "   UID:" (:uid @ai-websocket-client-v2/client-state))
    (println "\nAvailable commands:")
    (println "  (ai-actions/connect-game! \"game-id\" \"Corp\") - Join a game")
    (println "  (ai-actions/status)                          - Show game status")
    (println "  (ai-actions/keep-hand)                       - Keep hand (mulligan)")
    (println "  (ai-actions/take-credits)                    - Click for credit")
    (println "  (ai-actions/end-turn)                        - End turn")
    (println "  (ai-actions/help)                            - Show all commands")
    (println "\nFor full API, see dev/src/clj/ai_actions.clj"))
  (println "\n❌ Failed to connect to game server"))

(println "\n=== Ready for commands ===\n")
