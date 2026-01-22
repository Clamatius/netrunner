#!/bin/bash
# Error log watcher for Claude Code dev loop
# Monitors dev/repl-errors.log and maintains deduplicated error list

set -e

LOG_FILE="dev/repl-errors.log"
REPL_ERRORS="dev/repl-errors.md"
ERROR_STATE="dev/.error-state"
MAX_ERRORS=10

# Ensure log file exists
touch "$LOG_FILE"

# Initialize error state file if needed
if [ ! -f "$ERROR_STATE" ]; then
  echo "{}" > "$ERROR_STATE"
fi

# Initialize if it doesn't exist
if [ ! -f "$REPL_ERRORS" ]; then
  cat > "$REPL_ERRORS" << 'EOF'
# Claude Code Dev Loop - Error Log

**🔔 CHECK THIS FILE FOR RECENT ERRORS** - Updated automatically when errors occur in the REPL.

---

_No errors yet._
EOF
fi

echo "👁️  Watching $LOG_FILE for errors..."
echo "📝 Deduped errors will be shown in $REPL_ERRORS"

# Track the last position in the log file to only show new errors
LAST_POS=0
if [ -f "$LOG_FILE" ]; then
  LAST_POS=$(wc -c < "$LOG_FILE")
fi

# Function to extract error signature from log content
extract_error_info() {
  local content="$1"

  # Extract exception type (first line with Exception/Error)
  local exception_type=$(echo "$content" | grep -E "(Exception|Error)" | head -1 | sed -E 's/.*([A-Za-z]+Exception|[A-Za-z]+Error).*/\1/')

  # Extract file:line from stack trace (first interesting line, skip common noise)
  local location=$(echo "$content" | grep -E "at [a-z]" | grep -v "java.base" | grep -v "clojure.lang" | head -1 | sed -E 's/.*at ([^(]+)\(([^)]+)\).*/\1:\2/' | sed 's/ //g')

  # Extract the error message
  local message=$(echo "$content" | grep -E "ERROR|Exception|Error" | head -1 | sed 's/^[^-]*- //')

  # Create signature (for deduplication)
  local signature=$(echo "${exception_type}:${location}" | md5)

  # Output as TSV: signature, exception_type, location, message
  echo -e "${signature}\t${exception_type}\t${location}\t${message}"
}

# Function to update with deduplicated errors
update_claude_md() {
  local timestamp="$1"
  local sig="$2"
  local exc_type="$3"
  local location="$4"
  local message="$5"
  local full_trace="$6"

  # Read current error state (format: signature|timestamp|exc_type|location|message)
  local errors=()
  if [ -f "$ERROR_STATE" ]; then
    while IFS='|' read -r s t e l m; do
      if [ "$s" != "$sig" ]; then
        errors+=("$s|$t|$e|$l|$m")
      fi
    done < "$ERROR_STATE"
  fi

  # Add new error at the front
  errors=("$sig|$timestamp|$exc_type|$location|$message" "${errors[@]}")

  # Keep only last MAX_ERRORS unique errors
  local keep_count=$MAX_ERRORS
  if [ ${#errors[@]} -lt $MAX_ERRORS ]; then
    keep_count=${#errors[@]}
  fi

  # Write back to state file
  printf "%s\n" "${errors[@]:0:$keep_count}" > "$ERROR_STATE"

  # Rebuild REPL_ERRORS from scratch
  cat > "$REPL_ERRORS" << 'HEADER'
# Claude Code Dev Loop - Error Log

**🔔 CHECK THIS FILE FOR RECENT ERRORS** - Updated automatically when errors occur in the REPL.

This file shows the last 10 unique errors, deduplicated by exception type and location.

---

HEADER

  # Add each error
  local idx=1
  for error_line in "${errors[@]:0:$keep_count}"; do
    IFS='|' read -r s t e l m <<< "$error_line"

    cat >> "$REPL_ERRORS" << EOF

## Error #$idx - Last seen: $t

**${e}** at \`${l}\`

\`\`\`
${m}
\`\`\`

---

EOF
    idx=$((idx + 1))
  done

  if [ $keep_count -eq 0 ]; then
    echo "_No errors yet._" >> "$REPL_ERRORS"
  fi
}

# Watch for changes to the log file
fswatch -0 "$LOG_FILE" | while read -d "" event; do
  # Get new content since last check
  CURRENT_POS=$(wc -c < "$LOG_FILE")

  if [ $CURRENT_POS -gt $LAST_POS ]; then
    # Extract new content
    NEW_CONTENT=$(tail -c +$((LAST_POS + 1)) "$LOG_FILE")

    # Check if it contains an error
    if echo "$NEW_CONTENT" | grep -qE "ERROR|Exception|Error"; then
      # Extract error information
      IFS=$'\t' read -r signature exc_type location message < <(extract_error_info "$NEW_CONTENT")

      if [ ! -z "$signature" ]; then
        TIMESTAMP=$(date -u +"%Y-%m-%d %H:%M:%S UTC")

        # Update the deduplicated error list
        update_claude_md "$TIMESTAMP" "$signature" "$exc_type" "$location" "$message" "$NEW_CONTENT"

        echo "🚨 New error: $exc_type at $location"
      fi
    fi

    LAST_POS=$CURRENT_POS
  fi
done
