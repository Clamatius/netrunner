#!/bin/bash
# Start both AI Client REPLs (Runner and Corp)
# This enables triple-REPL development with both sides controllable

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/load-env.sh"

echo "🚀 Starting both AI Client REPLs..."
echo ""

# Start Runner on port $CLIENT_1_PORT
echo "Starting Runner client on port $CLIENT_1_PORT..."
"$SCRIPT_DIR/start-ai-client-repl.sh" runner $CLIENT_1_PORT

if [ $? -ne 0 ]; then
    echo "❌ Failed to start Runner client"
    exit 1
fi

echo ""
echo "Waiting 3 seconds before starting Corp client..."
sleep 3

# Start Corp on port $CLIENT_2_PORT
echo "Starting Corp client on port $CLIENT_2_PORT..."
"$SCRIPT_DIR/start-ai-client-repl.sh" corp $CLIENT_2_PORT

if [ $? -ne 0 ]; then
    echo "❌ Failed to start Corp client"
    echo "Runner client is still running. Stop it with: ./dev/stop-ai-client.sh runner"
    exit 1
fi

echo ""
echo "✅ Both AI Clients ready!"
echo ""
echo "Runner REPL:"
echo "  Port: $CLIENT_1_PORT"
echo "  Send commands: ./dev/ai-eval.sh runner $CLIENT_1_PORT '<expression>'"
echo "  View logs: tail -f /tmp/ai-client-runner.log"
echo ""
echo "Corp REPL:"
echo "  Port: $CLIENT_2_PORT"
echo "  Send commands: ./dev/ai-eval.sh corp $CLIENT_2_PORT '<expression>'"
echo "  View logs: tail -f /tmp/ai-client-corp.log"
echo ""
echo "To stop both: ./dev/stop-ai-both.sh"
echo ""
