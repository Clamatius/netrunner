#!/bin/bash
# Resume AI Test Game
# Bounces clients and reconnects to existing game (preserves game state on server)
#
# Usage:
#   ./dev/resume.sh              # Auto-detect game ID from server
#   ./dev/resume.sh [game-id]    # Explicit game ID
#   ./dev/resume.sh --help       # Show help
#
# Use case: You changed AI code and need to reload REPLs without losing game progress

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$SCRIPT_DIR/../logs"
LOG_FILE="$LOG_DIR/resume-$(date +%Y%m%d-%H%M%S).log"
VERBOSE=false
GAME_ID=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            cat << EOF
🔄 Resume AI Test Game

Bounces both AI clients and reconnects to existing game.
Game state is preserved on server - useful for reloading code changes.

Usage:
  ./dev/resume.sh              # Auto-detect from active games on server
  ./dev/resume.sh [game-id]    # Explicit game ID
  ./dev/resume.sh -v           # Verbose mode

Auto-detection:
  - Queries server for active games
  - If exactly 1 game exists, uses it automatically
  - If multiple games, shows list and asks you to specify
  - If 0 games, suggests using reset.sh instead

Manual game ID:
  ./dev/resume.sh 6e849fda-c813-409b-8c8c-0896ceca4663

On success:
  - Clients reloaded with latest code
  - Reconnected to same game
  - Game state preserved (turn, board, hands, etc.)

Note: Only works with games that still exist on server
      (games may timeout after inactivity)

Log files: ./logs/resume-YYYYMMDD-HHMMSS.log
EOF
            exit 0
            ;;
        -*)
            echo "Unknown option: $1"
            echo "Use --help for usage"
            exit 1
            ;;
        *)
            # Assume it's a game ID
            GAME_ID="$1"
            shift
            ;;
    esac
done

# Create log directory
mkdir -p "$LOG_DIR"

echo "🔄 Resuming AI Test Game" | tee -a "$LOG_FILE"
echo "📝 Logging to: $LOG_FILE"
echo "" | tee -a "$LOG_FILE"

# Auto-detect game ID if not provided
if [ -z "$GAME_ID" ]; then
    echo "🔍 Auto-detecting game ID from server..." | tee -a "$LOG_FILE"

    # First, check if at least one client REPL is running to query
    # If not, we need to start them first
    if ! lsof -i :7889 > /dev/null 2>&1 && ! lsof -i :7890 > /dev/null 2>&1; then
        echo "⚠️  No AI client REPLs running" | tee -a "$LOG_FILE"
        echo "   Starting clients to query server..." | tee -a "$LOG_FILE"
        "$SCRIPT_DIR/start-ai-both.sh" >> "$LOG_FILE" 2>&1 &
        sleep 10  # Wait for startup
    fi

    # Query server for active games
    # Use runner client if available, otherwise corp
    CLIENT_PORT=7889
    if ! lsof -i :$CLIENT_PORT > /dev/null 2>&1; then
        CLIENT_PORT=7890
    fi

    if lsof -i :$CLIENT_PORT > /dev/null 2>&1; then
        # Get list of active game IDs
        # Handle both raw UUIDs and #uuid "..." Clojure format
        GAME_LIST=$(TIMEOUT=10 "$SCRIPT_DIR/send_command" runner list-game-ids 2>/dev/null | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' || true)

        if [ -z "$GAME_LIST" ]; then
            echo "❌ No active games found on server" | tee -a "$LOG_FILE"
            echo "" | tee -a "$LOG_FILE"
            echo "Possible reasons:" | tee -a "$LOG_FILE"
            echo "  • Game timed out due to inactivity" | tee -a "$LOG_FILE"
            echo "  • Server restarted" | tee -a "$LOG_FILE"
            echo "  • Game was completed/abandoned" | tee -a "$LOG_FILE"
            echo "" | tee -a "$LOG_FILE"
            echo "Solutions:" | tee -a "$LOG_FILE"
            echo "  • Start fresh game: ./dev/reset.sh" | tee -a "$LOG_FILE"
            echo "  • Check server: http://localhost:1042" | tee -a "$LOG_FILE"
            exit 1
        fi

        # Count games
        GAME_COUNT=$(echo "$GAME_LIST" | wc -l | tr -d ' ')

        if [ "$GAME_COUNT" -eq 1 ]; then
            GAME_ID=$(echo "$GAME_LIST" | head -1 | tr -d ' \n')
            echo "✅ Found 1 active game: $GAME_ID" | tee -a "$LOG_FILE"
        else
            echo "⚠️  Found $GAME_COUNT active games on server:" | tee -a "$LOG_FILE"
            echo "" | tee -a "$LOG_FILE"

            # Show games with details
            "$SCRIPT_DIR/send_command" runner list-lobbies 2>/dev/null | tee -a "$LOG_FILE"

            echo "" | tee -a "$LOG_FILE"
            echo "Please specify which game to resume:" | tee -a "$LOG_FILE"
            echo "  ./dev/resume.sh [game-id]" | tee -a "$LOG_FILE"
            echo "" | tee -a "$LOG_FILE"
            echo "Available game IDs:" | tee -a "$LOG_FILE"
            echo "$GAME_LIST" | while read -r gid; do
                echo "  $gid" | tee -a "$LOG_FILE"
            done
            exit 1
        fi
    else
        echo "❌ Could not start AI clients" | tee -a "$LOG_FILE"
        echo "   Check server is running: http://localhost:1042" | tee -a "$LOG_FILE"
        exit 1
    fi
fi

# Validate game ID format (UUID)
if ! echo "$GAME_ID" | grep -qE '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'; then
    echo "❌ Invalid game ID format: $GAME_ID" | tee -a "$LOG_FILE"
    echo "   Expected UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" | tee -a "$LOG_FILE"
    exit 1
fi

echo "" | tee -a "$LOG_FILE"
echo "Game ID: $GAME_ID" | tee -a "$LOG_FILE"
echo "" | tee -a "$LOG_FILE"

# Bounce and reconnect
echo "🔄 Bouncing clients and reconnecting..." | tee -a "$LOG_FILE"
if [ "$VERBOSE" = true ]; then
    "$SCRIPT_DIR/ai-bounce.sh" "$GAME_ID" 2>&1 | tee -a "$LOG_FILE"
else
    "$SCRIPT_DIR/ai-bounce.sh" "$GAME_ID" >> "$LOG_FILE" 2>&1
fi

BOUNCE_EXIT=$?
if [ $BOUNCE_EXIT -ne 0 ]; then
    echo "❌ Bounce failed with exit code $BOUNCE_EXIT" | tee -a "$LOG_FILE"
    echo "   Check log: $LOG_FILE" | tee -a "$LOG_FILE"
    exit 1
fi

# Give game state a moment to sync
sleep 2

# Verify reconnection
echo "" | tee -a "$LOG_FILE"
echo "🔍 Verifying reconnection..." | tee -a "$LOG_FILE"

RUNNER_CONNECTED=false
CORP_CONNECTED=false

# Verify each seat by asking for the gameid the SERVER sent us in :game-state, not the
# :gameid the client set locally before any round-trip (#76: the old check compared that
# local value and so passed even when the resync timed out and no state ever arrived).
verify_seat() {
    local side="$1"
    local gid
    gid=$(TIMEOUT=5 "$SCRIPT_DIR/send_command" "$side" eval \
        '(str (:gameid (:game-state @ai-state/client-state)))' 2>/dev/null \
        | tail -1 | tr -d '"' | tr -d '\n' || echo "")
    [ "$gid" = "$GAME_ID" ]
}

if verify_seat runner; then
    echo "✅ Runner reconnected (server state received)" | tee -a "$LOG_FILE"
    RUNNER_CONNECTED=true
else
    echo "❌ Runner did NOT resync - no game state from server" | tee -a "$LOG_FILE"
fi

if verify_seat corp; then
    echo "✅ Corp reconnected (server state received)" | tee -a "$LOG_FILE"
    CORP_CONNECTED=true
else
    echo "❌ Corp did NOT resync - no game state from server" | tee -a "$LOG_FILE"
fi

echo "" | tee -a "$LOG_FILE"

# Show current game state
if [ "$RUNNER_CONNECTED" = true ] || [ "$CORP_CONNECTED" = true ]; then
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" | tee -a "$LOG_FILE"
    echo "✅ Resume Complete - Back in Game!" | tee -a "$LOG_FILE"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" | tee -a "$LOG_FILE"
    echo "" | tee -a "$LOG_FILE"

    # Show status from one connected client
    if [ "$CORP_CONNECTED" = true ]; then
        echo "Current Game State (Corp view):" | tee -a "$LOG_FILE"
        "$SCRIPT_DIR/send_command" corp status 2>/dev/null | tee -a "$LOG_FILE" || echo "   (Status unavailable)" | tee -a "$LOG_FILE"
    elif [ "$RUNNER_CONNECTED" = true ]; then
        echo "Current Game State (Runner view):" | tee -a "$LOG_FILE"
        "$SCRIPT_DIR/send_command" runner status 2>/dev/null | tee -a "$LOG_FILE" || echo "   (Status unavailable)" | tee -a "$LOG_FILE"
    fi

    echo "" | tee -a "$LOG_FILE"
    echo "Quick commands:" | tee -a "$LOG_FILE"
    echo "  Runner: ./dev/send_command runner status" | tee -a "$LOG_FILE"
    echo "  Corp:   ./dev/send_command corp status" | tee -a "$LOG_FILE"
    echo "  Board:  ./dev/send_command [runner|corp] board" | tee -a "$LOG_FILE"
    echo "" | tee -a "$LOG_FILE"
    echo "Log file: $LOG_FILE" | tee -a "$LOG_FILE"

    exit 0
else
    echo "❌ Resume failed - clients not reconnected" | tee -a "$LOG_FILE"
    echo "   Possible reasons:" | tee -a "$LOG_FILE"
    echo "   • Game no longer exists on server (timed out)" | tee -a "$LOG_FILE"
    echo "   • Server connection issues" | tee -a "$LOG_FILE"
    echo "" | tee -a "$LOG_FILE"
    echo "   Try: ./dev/reset.sh (start fresh game)" | tee -a "$LOG_FILE"
    echo "   Check log: $LOG_FILE" | tee -a "$LOG_FILE"
    exit 1
fi
