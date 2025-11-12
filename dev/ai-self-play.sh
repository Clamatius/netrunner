#!/bin/bash
# AI Self-Play Automation
# Creates a game and has both AI clients join to play against each other
#
# Prerequisites:
# - Game server running on port 1042
# - Both AI client REPLs running (use ./dev/start-ai-both.sh)
#
# Usage: ./dev/ai-self-play.sh

set -e  # Exit on error

echo "🎮 AI Self-Play Automation"
echo ""

# Check if both REPLs are running
if [ ! -f /tmp/ai-client-runner.pid ] || ! ps -p $(cat /tmp/ai-client-runner.pid) > /dev/null 2>&1; then
    echo "❌ Runner REPL not running"
    echo "   Start both clients with: ./dev/start-ai-both.sh"
    exit 1
fi

if [ ! -f /tmp/ai-client-corp.pid ] || ! ps -p $(cat /tmp/ai-client-corp.pid) > /dev/null 2>&1; then
    echo "❌ Corp REPL not running"
    echo "   Start both clients with: ./dev/start-ai-both.sh"
    exit 1
fi

echo "✅ Both REPLs are running"
echo ""

# Step 1: Corp creates a lobby
echo "📋 Corp creating game lobby..."
TIMEOUT=10 ./dev/ai-eval.sh corp 7890 '(ai-actions/create-lobby! "AI Self-Play Test")'
sleep 2

# Step 2: Get the game ID from Corp's state
echo "🔍 Getting game ID..."
GAME_ID=$(TIMEOUT=5 ./dev/ai-eval.sh corp 7890 '(str (:gameid @ai-websocket-client-v2/client-state))' | tail -1 | tr -d '"' | tr -d '\n')

if [ -z "$GAME_ID" ] || [ "$GAME_ID" = "nil" ]; then
    echo "❌ Failed to create game or get game ID"
    exit 1
fi

echo "✅ Game created: $GAME_ID"
echo ""

# Step 3: Runner joins the game
echo "🏃 Runner joining game..."
TIMEOUT=10 ./dev/ai-eval.sh runner 7889 "(ai-actions/connect-game! \"$GAME_ID\" \"Runner\")"
sleep 3

# Step 4: Start the game
echo ""
echo "🎮 Starting game..."
./dev/send_command corp start-game
sleep 2

echo ""
echo "✅ Game started!"
echo ""
echo "Game ID: $GAME_ID"
echo ""
echo "Monitor game state:"
echo "  Runner: ./dev/ai-eval.sh runner 7889 '(ai-actions/status)'"
echo "  Corp:   ./dev/ai-eval.sh corp 7890 '(ai-actions/status)'"
echo ""
echo "View shared HUD: cat CLAUDE.local.md"
echo ""
echo "Next steps (manual):"
echo "  1. Keep/mulligan hands for both players"
echo "  2. Use ai-actions commands to play the game"
echo ""
echo "Example commands:"
echo "  # Keep hands"
echo "  ./dev/ai-eval.sh runner 7889 '(ai-actions/keep-hand)'"
echo "  ./dev/ai-eval.sh corp 7890 '(ai-actions/keep-hand)'"
echo ""
echo "  # Show prompts"
echo "  ./dev/ai-eval.sh runner 7889 '(ai-actions/show-prompt)'"
echo "  ./dev/ai-eval.sh corp 7890 '(ai-actions/show-prompt)'"
echo ""
