#!/bin/bash
# AI Self-Play Automation
# Creates a game and has both AI clients join to play against each other
#
# Prerequisites:
# - Game server running on port 1042
# - Both AI client REPLs running (use ./dev/start-ai-both.sh)
#
# Usage: ./dev/ai-self-play.sh
#        CORP_DECK=dev/decks/sg-hb-discretion-advised.edn RUNNER_DECK=dev/decks/sg-shaper-planning-ahead.edn ./dev/ai-self-play.sh
#
# With BOTH deck vars set the lobby is System Gateway *Constructed* (no precon):
# each deck file is seeded into mongo for its seat (dev/seed-decks.sh) and
# selected before start. The env passes through reset.sh / `make reset`.

set -eo pipefail  # Exit on error, including inside a pipeline (a failed seed piped to tail)

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

GATEWAY_TYPE="Intermediate"
if [[ -n "${CORP_DECK:-}" || -n "${RUNNER_DECK:-}" ]]; then
    if [[ -z "${CORP_DECK:-}" || -z "${RUNNER_DECK:-}" ]]; then
        echo "❌ Constructed needs BOTH CORP_DECK and RUNNER_DECK (got corp='${CORP_DECK:-}' runner='${RUNNER_DECK:-}')"
        exit 1
    fi
    GATEWAY_TYPE="Constructed"
    # Seed before creating anything: an illegal list should stop us here, not
    # leave a lobby that silently never starts.
    echo "🃏 Seeding decks..."
    CORP_DECK_ID=$(./dev/seed-decks.sh ai-corp "$CORP_DECK" | tail -1)
    RUNNER_DECK_ID=$(./dev/seed-decks.sh ai-runner "$RUNNER_DECK" | tail -1)
    echo "✅ Corp deck $CORP_DECK_ID ($CORP_DECK), Runner deck $RUNNER_DECK_ID ($RUNNER_DECK)"
    echo ""
fi

# Step 1: Corp creates a lobby
echo "📋 Corp creating game lobby ($GATEWAY_TYPE)..."
TIMEOUT=20 ./dev/send_command corp create-game "AI Self-Play Test" "Any Side" "" "$GATEWAY_TYPE"
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

if [[ "$GATEWAY_TYPE" == "Constructed" ]]; then
    echo ""
    echo "🃏 Selecting decks..."
    for pair in "corp:$CORP_DECK_ID" "runner:$RUNNER_DECK_ID"; do
        out=$(TIMEOUT=15 ./dev/send_command "${pair%%:*}" select-deck "${pair#*:}" 2>&1) || true
        echo "$out"
        echo "$out" | grep -q "Deck selected" || { echo "❌ ${pair%%:*} deck did not take — the lobby cannot start."; exit 1; }
    done
fi

# Step 4: Start the game
echo ""
echo "🎮 Starting game..."
./dev/send_command corp start-game
sleep 2

echo ""
echo "✅ Game started - Game ID: $GAME_ID"
