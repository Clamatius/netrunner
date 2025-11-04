#!/bin/bash
# Error log watcher for Claude Code dev loop
# Monitors dev/repl-errors.log and updates CLAUDE.local.md when errors occur

set -e

LOG_FILE="dev/repl-errors.log"
CLAUDE_MD="CLAUDE.local.md"

# Ensure log file exists
touch "$LOG_FILE"

# Initialize CLAUDE.local.md if it doesn't exist
if [ ! -f "$CLAUDE_MD" ]; then
  cat > "$CLAUDE_MD" << 'EOF'
# Claude Code Dev Loop - Error Log

**🔔 CHECK THIS FILE FOR RECENT ERRORS** - Updated automatically when errors occur in the REPL.

---

EOF
fi

echo "👁️  Watching $LOG_FILE for errors..."
echo "📝 Updates will be written to $CLAUDE_MD"

# Track the last position in the log file to only show new errors
LAST_POS=0
if [ -f "$LOG_FILE" ]; then
  LAST_POS=$(wc -c < "$LOG_FILE")
fi

# Watch for changes to the log file
fswatch -0 "$LOG_FILE" | while read -d "" event; do
  # Get new content since last check
  CURRENT_POS=$(wc -c < "$LOG_FILE")

  if [ $CURRENT_POS -gt $LAST_POS ]; then
    # Extract new content
    NEW_CONTENT=$(tail -c +$((LAST_POS + 1)) "$LOG_FILE")

    # Parse the error - extract first line (timestamp + message) and exception class
    ERROR_SUMMARY=$(echo "$NEW_CONTENT" | head -20 | grep -E "ERROR|Exception|Error" | head -5)

    if [ ! -z "$ERROR_SUMMARY" ]; then
      # Append to CLAUDE.local.md
      cat >> "$CLAUDE_MD" << EOF

## Error at $(date '+%Y-%m-%d %H:%M:%S')

\`\`\`
$ERROR_SUMMARY
\`\`\`

<details>
<summary>Full stack trace (click to expand)</summary>

\`\`\`
$NEW_CONTENT
\`\`\`

</details>

---

EOF
      echo "🚨 New error logged to $CLAUDE_MD"
    fi

    LAST_POS=$CURRENT_POS
  fi
done
