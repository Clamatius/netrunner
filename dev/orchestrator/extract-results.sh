#!/bin/bash
# extract-results.sh
# Parses a Netrunner game log to extract match results.
# Usage: ./extract-results.sh <log-file>

LOG_FILE="$1"

if [ -z "$LOG_FILE" ]; then
    echo "Usage: $0 <log-file>" >&2
    exit 1
fi

if [ ! -f "$LOG_FILE" ]; then
    echo "Error: Log file not found: $LOG_FILE" >&2
    exit 1
fi

# Initialize variables
WINNER="unknown"
WIN_CONDITION="unknown"
CORP_SCORE=0
RUNNER_SCORE=0
TURN_COUNT=0

# Extract Winner and Win Condition
# Example: [GAME OVER] Runner wins! ... or "AI-corp wins the game."
if grep -i -q "Runner wins" "$LOG_FILE"; then
    WINNER="runner"
elif grep -i -q "Corp wins" "$LOG_FILE"; then
    WINNER="corp"
fi

# Extract Win Reason
if grep -i -q "concedes" "$LOG_FILE"; then
    WIN_CONDITION="concede"
    # If someone conceded, the other person won.
    # We can infer winner if not already set, or override it.
    if grep -i -q "runner concedes" "$LOG_FILE"; then
        WINNER="corp"
    elif grep -i -q "corp concedes" "$LOG_FILE"; then
        WINNER="runner"
    fi
elif grep -q "flatline" "$LOG_FILE"; then
    WIN_CONDITION="flatline"
elif grep -q "decked" "$LOG_FILE" || grep -q "mill" "$LOG_FILE"; then
    WIN_CONDITION="decking"
elif grep -q "agenda" "$LOG_FILE" || grep -q "points" "$LOG_FILE"; then
    WIN_CONDITION="agenda"
fi

# Extract Scores
# Look for "gains X agenda points" or "gains 1 agenda point" lines in the log
# Example: "AI-corp scores Offworld Office and gains 2 agenda points."
# Example: "AI-runner steals ... and gains 1 agenda point."

# Calculate Corp Score
CORP_SCORE=$(grep -i "AI-corp.*gains.*agenda point" "$LOG_FILE" | sed -E 's/.*gains ([0-9]+) agenda point.*/\1/' | awk '{s+=$1} END {print s+0}')

# Calculate Runner Score
RUNNER_SCORE=$(grep -i "AI-runner.*gains.*agenda point" "$LOG_FILE" | sed -E 's/.*gains ([0-9]+) agenda point.*/\1/' | awk '{s+=$1} END {print s+0}')

# Extract Turn Count
# Look for highest "Turn X" or "started their turn X"
LAST_TURN_LINE=$(grep -i "started their turn" "$LOG_FILE" | tail -1)
if [ -n "$LAST_TURN_LINE" ]; then
    TURN_COUNT=$(echo "$LAST_TURN_LINE" | sed -E 's/.*turn ([0-9]+).*/\1/')
fi

# JSON Output
cat <<EOF
{
  "winner": "$WINNER",
  "win_condition": "$WIN_CONDITION",
  "score": {
    "corp": ${CORP_SCORE:-0},
    "runner": ${RUNNER_SCORE:-0}
  },
  "turns": ${TURN_COUNT:-0},
  "log_file": "$LOG_FILE"
}
EOF
