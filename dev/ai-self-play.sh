#!/bin/bash
# AI Self-Play Automation
# Creates a game and has both AI clients join to play against each other
#
# Prerequisites:
# - Game server running on port 1042
# - Both AI client REPLs running (use ./dev/start-ai-both.sh)
#
# Usage: ./dev/ai-self-play.sh [--check]
#        CORP_DECK=dev/decks/sg-hb-discretion-advised.edn RUNNER_DECK=dev/decks/sg-shaper-planning-ahead.edn ./dev/ai-self-play.sh
#        FORMAT=core CORP_DECK=... RUNNER_DECK=... ./dev/ai-self-play.sh
#        PRECON=worlds-2019-a ./dev/ai-self-play.sh
#
# Which lobby (all through reset.sh / `make reset` too):
#   no env                     System Gateway "Intermediate" (precon gateway decks)
#   CORP_DECK + RUNNER_DECK    a Constructed lobby in FORMAT (default system-gateway);
#                              each deck is checked legal IN THAT FORMAT, seeded into
#                              mongo for its seat (dev/seed-decks.sh) and selected
#   PRECON=<matchup>           a preconstructed lobby (Worlds finals); no decks, and
#                              FORMAT unset or "preconstructed"
# What can be asked for: dev/deck-check --formats / --precons.
# Every refusal happens BEFORE anything is touched (no leave, seed or create).
# --check: validate the request, print the lobby it resolves to, and exit.

set -eo pipefail  # Exit on error, including inside a pipeline (a failed seed piped to tail)

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1
# Where the REPL launcher writes ai-client-<side>.pid (overridable for tests).
PID_DIR="${AI_PID_DIR:-/tmp}"

refuse() { echo "❌ $*"; exit 1; }

# --- Decide and validate the lobby before touching anything. ---
FORMAT="${FORMAT:-}"; PRECON="${PRECON:-}"
if [[ -n "${CORP_DECK:-}" || -n "${RUNNER_DECK:-}" ]]; then
    if [[ -z "${CORP_DECK:-}" || -z "${RUNNER_DECK:-}" ]]; then
        refuse "Constructed needs BOTH CORP_DECK and RUNNER_DECK (got corp='${CORP_DECK:-}' runner='${RUNNER_DECK:-}')"
    fi
    [[ -z "$PRECON" ]] || refuse "PRECON=$PRECON is a preconstructed lobby: it takes no decks (unset CORP_DECK/RUNNER_DECK, or PRECON)"
    LOBBY_FORMAT="${FORMAT:-system-gateway}"; GATEWAY_TYPE="Constructed"; PRECON_ARG=""; WANT=constructed
elif [[ -n "$PRECON" ]]; then
    [[ -z "$FORMAT" || "$FORMAT" == preconstructed ]] \
        || refuse "PRECON=$PRECON is a preconstructed lobby; FORMAT=$FORMAT conflicts (unset FORMAT)"
    LOBBY_FORMAT="preconstructed"; GATEWAY_TYPE="Intermediate"; PRECON_ARG="$PRECON"; WANT=precon
else
    LOBBY_FORMAT="${FORMAT:-system-gateway}"; PRECON_ARG=""
    [[ "$LOBBY_FORMAT" != preconstructed ]] \
        || refuse "FORMAT=preconstructed needs PRECON=<matchup> (list: dev/deck-check --precons)"
    if [[ "$LOBBY_FORMAT" == system-gateway ]]; then GATEWAY_TYPE="Intermediate"; WANT=precon
    else GATEWAY_TYPE="Constructed"; WANT=constructed; fi
fi
# The server accepts ANY format string and an unknown matchup silently becomes a
# Constructed lobby in a format no deck is legal in (web/lobby.clj validate-precon),
# so a typo would otherwise be a lobby that never starts. deck-check --lobby
# mirrors validate-precon offline.
LOBBY=$(./dev/deck-check --lobby "$LOBBY_FORMAT" "$PRECON_ARG" "$GATEWAY_TYPE" 2>&1) || refuse "${LOBBY#❌ }"
[[ "$LOBBY" == "LOBBY $WANT "* ]] || refuse "the request resolved to '$LOBBY', not a $WANT lobby"
if [[ "$WANT" == constructed && -z "${CORP_DECK:-}" ]]; then
    refuse "FORMAT=$LOBBY_FORMAT is a Constructed lobby: it needs CORP_DECK and RUNNER_DECK"
fi
if [[ "$WANT" == constructed ]]; then
    # Offline legality IN THE LOBBY'S FORMAT; seed-decks.sh asks the server again.
    legal=$(./dev/deck-check --format "$LOBBY_FORMAT" "$CORP_DECK" "$RUNNER_DECK" 2>&1) \
        || { echo "$legal"; refuse "deck not legal in the lobby format $LOBBY_FORMAT (above)"; }
fi
if (( CHECK_ONLY )); then
    echo "✅ $LOBBY"
    exit 0
fi

echo "🎮 AI Self-Play Automation"
echo ""

# Check if both REPLs are running
if [ ! -f "$PID_DIR/ai-client-runner.pid" ] || ! ps -p $(cat "$PID_DIR/ai-client-runner.pid") > /dev/null 2>&1; then
    echo "❌ Runner REPL not running"
    echo "   Start both clients with: ./dev/start-ai-both.sh"
    exit 1
fi

if [ ! -f "$PID_DIR/ai-client-corp.pid" ] || ! ps -p $(cat "$PID_DIR/ai-client-corp.pid") > /dev/null 2>&1; then
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

if [[ "$WANT" == constructed ]]; then
    # Seed before creating anything: an illegal list should stop us here, not
    # leave a lobby that silently never starts.
    echo "🃏 Seeding decks ($LOBBY_FORMAT)..."
    CORP_DECK_ID=$(./dev/seed-decks.sh ai-corp "$CORP_DECK" "$LOBBY_FORMAT" | tail -1)
    RUNNER_DECK_ID=$(./dev/seed-decks.sh ai-runner "$RUNNER_DECK" "$LOBBY_FORMAT" | tail -1)
    echo "✅ Corp deck $CORP_DECK_ID ($CORP_DECK), Runner deck $RUNNER_DECK_ID ($RUNNER_DECK)"
    echo ""
fi

# Step 1: Corp creates a lobby
echo "📋 Corp creating game lobby (${LOBBY#LOBBY })..."
TIMEOUT=20 ./dev/send_command corp create-game "AI Self-Play Test" "Any Side" "$PRECON_ARG" "$GATEWAY_TYPE" "$LOBBY_FORMAT"
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

if [[ "$WANT" == constructed ]]; then
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
