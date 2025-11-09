;; Test script for lobby creation via AI client

(println "\n=== Testing Lobby Creation ===\n")

;; Reload the updated code
(println "Reloading AI client code...")
(load-file "dev/src/clj/ai_websocket_client_v2.clj")
(load-file "dev/src/clj/ai_actions.clj")

(println "\n✅ Code reloaded\n")

;; Check connection status
(println "Connection status:" (if (ai-websocket-client-v2/connected?) "✅ Connected" "❌ Not connected"))
(println "UID:" (:uid @ai-websocket-client-v2/client-state))

;; List current lobbies
(println "\n--- Current Lobbies ---")
(ai-actions/list-lobbies)

;; Create a test lobby
(println "\n--- Creating Test Lobby ---")
(ai-actions/create-lobby! "AI Test Lobby")

;; Wait a moment for the response
(Thread/sleep 2000)

;; Check lobbies again
(println "\n--- Lobbies After Creation ---")
(ai-actions/list-lobbies)

(println "\n=== Test Complete ===\n")
