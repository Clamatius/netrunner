#!/bin/bash
# Test 1: Basic connection to game server

./dev/src/clj/nrepl-eval.sh '
(do
  (println "=== TEST 1: Connection ===\n")

  ;; Load websocket client
  (load-file "dev/src/clj/ai_websocket_client_v2.clj")

  ;; Connect
  (ai-websocket-client-v2/connect! "ws://localhost:1042/chsk")
  (println "Waiting for handshake...")
  (Thread/sleep 3000)

  ;; Check connection status
  (if (ai-websocket-client-v2/connected?)
    (do
      (println "\n✅ CONNECTION TEST PASSED")
      (println "   Connected:" (ai-websocket-client-v2/connected?))
      (println "   UID:" (:uid @ai-websocket-client-v2/client-state))
      (println "   Client ID:" (:client-id @ai-websocket-client-v2/client-state)))
    (do
      (println "\n❌ CONNECTION TEST FAILED")
      (System/exit 1)))

  ;; Disconnect
  (ai-websocket-client-v2/disconnect!)
  (println "\nDisconnected cleanly"))'
