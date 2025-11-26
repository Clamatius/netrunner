#!/bin/bash
# Start AI Client REPL
# This maintains a persistent WebSocket connection to the game server
#
# Usage: ./start-ai-client-repl.sh [client_name] [port]
# Example: ./start-ai-client-repl.sh runner 7889

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/load-env.sh"

CLIENT_NAME=${1:-fixed-id}
REPL_PORT=${2:-${CLIENT_1_PORT:-7889}}

echo "Starting AI Client REPL for '$CLIENT_NAME' on port $REPL_PORT..."
echo "This REPL will maintain connection to the game server."
echo ""
echo "To connect to this REPL manually:"
echo "  lein repl :connect localhost:$REPL_PORT"
echo ""
echo "To send commands from scripts:"
echo "  ./dev/ai-eval.sh '$CLIENT_NAME' $REPL_PORT '<expression>'"
echo ""

# Start REPL on specified port
# Use 'with-profile ai-client' - lightweight profile without heavy dev deps
# Starts in ~8s vs ~35s with full dev profile
# AI code loaded via load-file in ai_client_init.clj (not via source-paths)
# Pass client name via environment variable (simpler than JVM prop through lein)
export AI_CLIENT_NAME=$CLIENT_NAME
nohup lein with-profile ai-client run -m nrepl.cmdline \
  --port $REPL_PORT \
  > /tmp/ai-client-${CLIENT_NAME}.log 2>&1 &

REPL_PID=$!

echo "AI Client REPL starting (initial PID: $REPL_PID)"
echo "Waiting for nREPL server to be ready..."

# Wait for nREPL to actually be listening
# ai-client profile starts in ~8s (vs ~35s with dev profile)
MAX_WAIT=30
for i in $(seq 1 $MAX_WAIT); do
    if lsof -i:$REPL_PORT > /dev/null 2>&1; then
        echo "✅ nREPL server is listening on port $REPL_PORT"
        break
    fi
    if [ $i -eq $MAX_WAIT ]; then
        echo "❌ nREPL server failed to start after ${MAX_WAIT}s"
        echo "Last 20 lines of log:"
        tail -20 /tmp/ai-client-${CLIENT_NAME}.log
        exit 1
    fi
    # Show progress every 10 seconds
    if [ $((i % 10)) -eq 0 ]; then
        echo "   Still waiting... (${i}s / ${MAX_WAIT}s)"
    fi
    sleep 1
done

# Find the actual Java process PID listening on the specified port
JAVA_PID=$(lsof -ti:$REPL_PORT)
if [ -n "$JAVA_PID" ]; then
    echo $JAVA_PID > /tmp/ai-client-${CLIENT_NAME}.pid
    echo "Saved Java process PID: $JAVA_PID"
else
    echo "⚠️  Warning: Could not find Java process PID"
    echo $REPL_PID > /tmp/ai-client-${CLIENT_NAME}.pid
fi

# Now load the initialization code via nREPL
echo "Loading AI client initialization..."
if TIMEOUT=15 ./dev/ai-eval.sh $CLIENT_NAME $REPL_PORT '(load-file "dev/src/clj/ai_client_init.clj")'; then
    echo ""
    echo "✅ AI Client REPL ready for '$CLIENT_NAME'!"
    echo ""
    echo "To stop: ./dev/stop-ai-client.sh $CLIENT_NAME"
    echo "To view logs: tail -f /tmp/ai-client-${CLIENT_NAME}.log"
    echo "To send commands: ./dev/ai-eval.sh $CLIENT_NAME $REPL_PORT '<expression>'"
    echo ""
else
    echo ""
    echo "❌ Failed to load initialization code!"
    echo "REPL is running but AI client code is not loaded."
    echo ""
    echo "Troubleshooting:"
    echo "  1. Check REPL log: tail -20 /tmp/ai-client-${CLIENT_NAME}.log"
    echo "  2. Manually load init: ./dev/ai-eval.sh $CLIENT_NAME $REPL_PORT '(load-file \"dev/src/clj/ai_client_init.clj\")'"
    echo "  3. Stop and restart: ./dev/stop-ai-client.sh $CLIENT_NAME && ./dev/start-ai-client-repl.sh $CLIENT_NAME $REPL_PORT"
    echo ""
    exit 1
fi
