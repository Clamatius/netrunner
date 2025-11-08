#!/bin/bash
# Start AI Client REPL
# This maintains a persistent WebSocket connection to the game server

echo "Starting AI Client REPL on port 7889..."
echo "This REPL will maintain connection to the game server."
echo ""
echo "To connect to this REPL manually:"
echo "  lein repl :connect localhost:7889"
echo ""
echo "To send commands from scripts:"
echo "  ./dev/ai-eval.sh '<expression>'"
echo ""

# Start REPL on port 7889 without loading user.clj
# Use 'with-profile base' to avoid the dev profile which loads user.clj
# This prevents the port 7888 nREPL server from starting
# Use nohup and redirect to keep it running in background
nohup lein with-profile base run -m nrepl.cmdline \
  --port 7889 \
  > /tmp/ai-client-repl.log 2>&1 &

REPL_PID=$!

# Wait a moment for the server to actually start
sleep 2

# Save PID for cleanup
echo $REPL_PID > /tmp/ai-client-repl.pid

echo "AI Client REPL starting (PID: $REPL_PID)"
echo "Waiting for nREPL server to be ready..."

# Wait for nREPL to actually be listening
MAX_WAIT=30
for i in $(seq 1 $MAX_WAIT); do
    if lsof -i:7889 > /dev/null 2>&1; then
        echo "✅ nREPL server is listening on port 7889"
        break
    fi
    if [ $i -eq $MAX_WAIT ]; then
        echo "❌ nREPL server failed to start after ${MAX_WAIT}s"
        echo "Last 20 lines of log:"
        tail -20 /tmp/ai-client-repl.log
        exit 1
    fi
    sleep 1
done

# Now load the initialization code via nREPL
echo "Loading AI client initialization..."
TIMEOUT=15 ./dev/ai-eval.sh '(load-file "dev/src/clj/ai_client_init.clj")'

echo ""
echo "✅ AI Client REPL ready!"
echo ""
echo "To stop: ./dev/stop-ai-client.sh"
echo "To view logs: tail -f /tmp/ai-client-repl.log"
echo "To send commands: ./dev/ai-eval.sh '<expression>'"
echo ""
