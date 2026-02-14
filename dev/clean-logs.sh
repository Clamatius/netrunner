#!/bin/bash
# Clean old reset logs
# Keeps last N logs, deletes older ones
#
# Usage:
#   ./dev/clean-logs.sh [keep-count]
#   ./dev/clean-logs.sh 10  # Keep last 10 logs (default: 20)

KEEP_COUNT=${1:-20}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$SCRIPT_DIR/../logs"

if [ ! -d "$LOG_DIR" ]; then
    echo "No logs directory found"
    exit 0
fi

echo "🧹 Cleaning old reset logs..."
echo "   Keeping last $KEEP_COUNT logs"
echo ""

# Count current logs
CURRENT_COUNT=$(find "$LOG_DIR" -name "reset-*.log" | wc -l | tr -d ' ')

if [ "$CURRENT_COUNT" -le "$KEEP_COUNT" ]; then
    echo "✅ Only $CURRENT_COUNT logs found - nothing to clean"
    exit 0
fi

# Delete old logs (keep last N by modification time)
DELETED=0
find "$LOG_DIR" -name "reset-*.log" -type f -print0 | \
    xargs -0 ls -t | \
    tail -n +$((KEEP_COUNT + 1)) | \
    while read -r file; do
        echo "   Deleting: $(basename "$file")"
        rm "$file"
        DELETED=$((DELETED + 1))
    done

echo ""
echo "✅ Cleaned $((CURRENT_COUNT - KEEP_COUNT)) old logs"
echo "   $KEEP_COUNT logs remain"
