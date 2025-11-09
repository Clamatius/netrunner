#!/bin/bash
# Initialize AI client - run this ONCE at the start

NREPL_PORT=$(cat .nrepl-port)

lein repl :connect localhost:$NREPL_PORT <<'EOF'
(do
  (println "=== Initializing AI Client ===")
  (load-file "dev/src/clj/ai_websocket_client_v2.clj")
  (ai-websocket-client-v2/ensure-connected!)
  (load-file "dev/ai-game-controller.clj")
  (println "\n✅ AI Client Ready!")
  (println "Client connected:" (boolean @ai-websocket-client-v2/websocket))
  (println "\nUse ai-action.sh to send commands"))
EOF
