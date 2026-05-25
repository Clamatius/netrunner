#!/bin/bash
# Game Event Watcher — streams game events to stdout, one per state change.
#
# Designed for use with Claude Code's Monitor tool, but works standalone too.
# Each relevant game event produces output lines; irrelevant opponent actions
# (economy, draw) are filtered by `wait` on the REPL side.
#
# Usage:
#   ./dev/watch_game.sh <side>              # Watch as corp or runner
#   ./dev/watch_game.sh <side> --timeout N  # Per-wait timeout (default: 300s)
#
# With Monitor:
#   Monitor({command: "./dev/watch_game.sh runner", description: "runner game events"})
#
# Output: Human-readable event lines from `wait`, prefixed with
# a separator showing side, cursor, and timestamp per event batch.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SEND_CMD="$SCRIPT_DIR/send_command"

SIDE="${1:?Usage: watch_game.sh <corp|runner> [--timeout N]}"
shift

# Parse optional args
WAIT_TIMEOUT=300
while [[ $# -gt 0 ]]; do
    case "$1" in
        --timeout)
            WAIT_TIMEOUT="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

# Validate side
if [[ "$SIDE" != "corp" && "$SIDE" != "runner" ]]; then
    echo "Error: side must be 'corp' or 'runner', got '$SIDE'" >&2
    exit 1
fi

# Get initial cursor
CURSOR=$("$SEND_CMD" "$SIDE" get-cursor 2>/dev/null | grep -E '^[0-9]+$' | tail -1)
if [[ -z "$CURSOR" ]]; then
    echo "Error: couldn't get initial cursor — is the $SIDE REPL running?" >&2
    exit 1
fi

echo "[$SIDE] watcher started, cursor=$CURSOR, timeout=${WAIT_TIMEOUT}s per wait"

while true; do
    # Block until something relevant happens (or timeout)
    OUTPUT=$("$SEND_CMD" "$SIDE" wait "$WAIT_TIMEOUT" --since "$CURSOR" 2>/dev/null) || true

    # Get new cursor
    NEW_CURSOR=$("$SEND_CMD" "$SIDE" get-cursor 2>/dev/null | grep -E '^[0-9]+$' | tail -1) || true

    if [[ -n "$NEW_CURSOR" && "$NEW_CURSOR" != "$CURSOR" ]]; then
        # State changed — emit the event
        TIMESTAMP=$(date '+%H:%M:%S')
        echo ""
        echo "--- [$SIDE] event at $TIMESTAMP (cursor: $CURSOR -> $NEW_CURSOR) ---"
        if [[ -n "$OUTPUT" ]]; then
            echo "$OUTPUT"
        fi
        CURSOR="$NEW_CURSOR"
    fi
    # If cursor unchanged (timeout with no relevant events), loop silently
done
