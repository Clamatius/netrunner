#!/bin/bash
# Unified REPL launcher with output logging for Claude Code dev loop
# Starts the REPL, error watcher, and captures all output with timestamps

set -e

REPL_OUTPUT="dev/repl-output.log"
ERROR_WATCHER_PID=""

# Function to add timestamps to each line
add_timestamps() {
  while IFS= read -r line; do
    # Use gdate if available (from brew coreutils), otherwise fall back to date
    if command -v gdate &> /dev/null; then
      echo "[$(gdate -u +"%Y-%m-%dT%H:%M:%S.%3NZ")] $line"
    else
      echo "[$(date -u +"%Y-%m-%dT%H:%M:%S")Z] $line"
    fi
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

# Start REPL and capture output with timestamps
# Use script command for proper TTY handling, or direct tee for simpler approach
lein repl 2>&1 | tee >(add_timestamps >> "$REPL_OUTPUT")
