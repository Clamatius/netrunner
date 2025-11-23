#!/bin/bash
# Unified REPL launcher with output logging for Claude Code dev loop
# Starts the REPL, error watcher, and captures all output with timestamps + sequence numbers

set -e

REPL_OUTPUT="dev/repl-output.log"
COUNTER_FILE="dev/.repl-counter"
ERROR_WATCHER_PID=""

# Load environment variables and default ports
source "dev/load-env.sh"

# Reset sequence counter on startup
echo "0" > "$COUNTER_FILE"

# Function to add sequence counter + timestamp to each line
add_sequence_and_timestamp() {
  while IFS= read -r line; do
    # Atomically increment counter
    COUNTER=$(awk '{print $1+1}' "$COUNTER_FILE")
    echo "$COUNTER" > "$COUNTER_FILE"

    # Use gdate if available (from brew coreutils), otherwise fall back to date
    if command -v gdate &> /dev/null; then
      TIMESTAMP=$(gdate -u +"%Y-%m-%dT%H:%M:%S.%3NZ")
    else
      TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S")Z
    fi

    printf "[SEQ:%05d] [%s] %s\n" "$COUNTER" "$TIMESTAMP" "$line"
  done
}

# Function to cleanup background processes on exit
cleanup() {
  echo ""
  echo "🛑 Shutting down..."
  if [ ! -z "$ERROR_WATCHER_PID" ]; then
    kill "$ERROR_WATCHER_PID" 2>/dev/null || true
    echo "✅ Stopped error watcher"
  fi
  exit 0
}

trap cleanup SIGINT SIGTERM EXIT

echo "🚀 Starting Netrunner REPL with output logging..."
echo "📝 REPL output (with timestamps): $REPL_OUTPUT"
echo "📝 Server errors: dev/repl-errors.log → CLAUDE.local.md"
echo ""

# Start error watcher in background
if [ -f "dev/watch-errors.sh" ]; then
  ./dev/watch-errors.sh &
  ERROR_WATCHER_PID=$!
  echo "👁️  Error watcher started (PID: $ERROR_WATCHER_PID)"
else
  echo "⚠️  Warning: dev/watch-errors.sh not found"
fi

echo ""
echo "Starting REPL..."
echo "════════════════════════════════════════════════════════════════"

# Start REPL and capture output with sequence numbers + timestamps
# Use script command for proper TTY handling, or direct tee for simpler approach
lein repl :port $GAME_SERVER_PORT 2>&1 | tee >(add_sequence_and_timestamp >> "$REPL_OUTPUT")
