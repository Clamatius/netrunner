#!/bin/bash
# Start both AI Client REPLs (Runner and Corp)
# This enables triple-REPL development with both sides controllable

echo "🚀 Starting both AI Client REPLs..."
echo ""

# Start Runner on port 7889
echo "Starting Runner client on port 7889..."
./dev/start-ai-client-repl.sh runner 7889

if [ $? -ne 0 ]; then
    echo "❌ Failed to start Runner client"
    exit 1
fi

echo ""
echo "Waiting 3 seconds before starting Corp client..."
sleep 3

# Start Corp on port 7890
echo "Starting Corp client on port 7890..."
./dev/start-ai-client-repl.sh corp 7890

if [ $? -ne 0 ]; then
    echo "❌ Failed to start Corp client"
    echo "Runner client is still running. Stop it with: ./dev/stop-ai-client.sh runner"
    exit 1
fi

echo ""
echo "✅ Both AI Clients ready!"
echo ""
echo "Runner REPL:"
echo "  Port: 7889"
echo "  Send commands: ./dev/ai-eval.sh runner 7889 '<expression>'"
echo "  View logs: tail -f /tmp/ai-client-runner.log"
echo ""
echo "Corp REPL:"
echo "  Port: 7890"
echo "  Send commands: ./dev/ai-eval.sh corp 7890 '<expression>'"
echo "  View logs: tail -f /tmp/ai-client-corp.log"
echo ""
echo "To stop both: ./dev/stop-ai-both.sh"
echo ""
