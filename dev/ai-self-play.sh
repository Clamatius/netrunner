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

# Step 0: Reap any prior lobby our uids are still seated in (#88).
# :keep-lobbies-on-disconnect? (issue #76) keeps a started lobby alive across the
# bounce, so an abandoned game would otherwise block create-game forever. An
# explicit leave from each seat is the sanctioned teardown — the last player
# out closes the lobby properly (stats/replays flush). No-op when unseated.
echo "🧹 Clearing any prior lobby seats..."
for seat in corp runner; do
    # send_command's exit code doesn't reflect the leave verdict, so read the
    # output: a persisting seat means the create below is doomed — fail fast
    # with the real reason instead of proceeding into a refused create.
    leave_out=$(TIMEOUT=15 ./dev/send_command "$seat" leave-game 2>&1) || true
    echo "$leave_out"
    if echo "$leave_out" | grep -q "Leave did not take"; then
        echo "❌ Could not reap the prior lobby: $seat seat is still held server-side."
        echo "   Reset aborted — clear the lobby manually (server REPL: lobby/close-lobby!) and retry."
        exit 1
    fi
done
echo ""

# Step 1: Corp creates a lobby
echo "📋 Corp creating game lobby..."
TIMEOUT=20 ./dev/send_command corp create-game "AI Self-Play Test"
sleep 2

# Step 2: Get the game ID from Corp's state
echo "🔍 Getting game ID..."
GAME_ID=$(TIMEOUT=5 ./dev/ai-eval.sh corp 7890 '(str (:gameid @ai-state/client-state))' | tail -1 | tr -d '"' | tr -d '\n')

if [ -z "$GAME_ID" ] || [ "$GAME_ID" = "nil" ]; then
    echo "❌ Failed to create game or get game ID"
    exit 1
fi

echo "✅ Game created: $GAME_ID"
echo ""

# Step 3: Runner joins the game
echo "🏃 Runner joining game..."
TIMEOUT=10 ./dev/send_command runner join "$GAME_ID" Runner
sleep 3

# Step 4: Start the game
echo ""
echo "🎮 Starting game..."
./dev/send_command corp start-game
sleep 2

echo ""
echo "✅ Game started - Game ID: $GAME_ID"
