#!/bin/bash
# AI Client Bounce & Reconnect
# Restarts both AI clients and optionally reconnects to an existing game
#
# Usage:
#   ./dev/ai-bounce.sh [game-id]
#
# Examples:
#   ./dev/ai-bounce.sh                           # Just restart clients
#   ./dev/ai-bounce.sh afad1410-c4b9-40c0-a0dc-69bb30b4e0eb  # Restart + resync to game

set -e

GAME_ID="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Load environment variables and default ports
source "$SCRIPT_DIR/load-env.sh"

echo "🔄 AI Client Bounce & Reconnect"
echo ""

# Step 1: Stop both clients
echo "🛑 Stopping both AI clients..."
"$SCRIPT_DIR/stop-ai-both.sh"
echo ""

# Step 2: Wait a bit for cleanup
sleep 2

# Step 3: Start both clients in background
echo "🚀 Starting both AI clients..."
"$SCRIPT_DIR/start-ai-both.sh" &
START_PID=$!
echo ""

# Step 4: Wait for REPLs to be ready
echo "⏳ Waiting for REPLs to become responsive..."
MAX_WAIT=60
WAITED=0

while [ $WAITED -lt $MAX_WAIT ]; do
  # Check if both ports are listening
  if lsof -i :$CLIENT_1_PORT -sTCP:LISTEN -t > /dev/null 2>&1 && \
     lsof -i :$CLIENT_2_PORT -sTCP:LISTEN -t > /dev/null 2>&1; then
    echo "✅ Both REPLs are listening"
    break
  fi

  sleep 1
  WAITED=$((WAITED + 1))

  if [ $((WAITED % 5)) -eq 0 ]; then
    echo "   Still waiting... (${WAITED}s / ${MAX_WAIT}s)"
  fi
done

if [ $WAITED -ge $MAX_WAIT ]; then
  echo "❌ Timeout waiting for REPLs to start"
  exit 1
fi

# Give REPLs a bit more time to fully initialize
echo "⏳ Waiting for REPL initialization..."
sleep 5

# Step 5: Test REPL connectivity
echo "🔍 Testing REPL connectivity..."
if ! TIMEOUT=5 "$SCRIPT_DIR/ai-eval.sh" runner $CLIENT_1_PORT '(+ 1 1)' > /dev/null 2>&1; then
  echo "⚠️  Runner REPL not responding yet, waiting longer..."
  sleep 5
fi

if ! TIMEOUT=5 "$SCRIPT_DIR/ai-eval.sh" corp $CLIENT_2_PORT '(+ 1 1)' > /dev/null 2>&1; then
  echo "⚠️  Corp REPL not responding yet, waiting longer..."
  sleep 5
fi

echo "✅ Both REPLs are responsive"
echo ""

# Step 6: Resync to game if ID provided
if [ -n "$GAME_ID" ]; then
  echo "🎮 Reconnecting to game: $GAME_ID"
  echo ""

  # Resync both clients
  echo "   Resyncing Runner..."
  TIMEOUT=10 "$SCRIPT_DIR/send_command" runner resync "$GAME_ID" || true

  sleep 2

  echo "   Resyncing Corp..."
  TIMEOUT=10 "$SCRIPT_DIR/send_command" corp resync "$GAME_ID" || true

  sleep 2

  echo ""
  echo "✅ Reconnected to game!"
  echo ""
  echo "Check status:"
  echo "  ./dev/send_command runner status"
  echo "  ./dev/send_command corp status"
else
  echo "✅ Clients ready for new game"
  echo ""
  echo "Start a new game:"
  echo "  ./dev/ai-self-play.sh"
fi

echo ""
echo "🎉 Bounce complete!"
