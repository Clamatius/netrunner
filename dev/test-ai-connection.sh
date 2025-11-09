#!/bin/bash
# Test AI client connection to localhost game

TIMEOUT=30 ./dev/src/clj/nrepl-eval.sh '
(do
  (load-file "dev/src/clj/ai_websocket_client_v2.clj")
  (ai-websocket-client-v2/connect! "ws://localhost:1042/chsk")
  (Thread/sleep 3000)
  (println "\n=== CONNECTION STATUS ===")
  (println "Connected:" (ai-websocket-client-v2/connected?))
  (println "Client state:" @ai-websocket-client-v2/client-state)
  (println "\n=== REQUESTING LOBBY LIST ===")
  (ai-websocket-client-v2/request-lobby-list!)
  (Thread/sleep 2000)
  (ai-websocket-client-v2/show-games))
'
