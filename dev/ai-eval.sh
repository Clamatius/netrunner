#!/bin/bash
# AI Client nREPL eval script
# Sends commands to the AI Client REPL on port 7889
# Usage: ./ai-eval.sh <clojure-expression>

TIMEOUT=${TIMEOUT:-10}

if [ -z "$1" ]; then
    echo "Usage: $0 <clojure-expression>"
    echo "Example: $0 '(ai-actions/status)'"
    exit 1
fi

# Check if AI client REPL is running
if [ -f /tmp/ai-client-repl.pid ]; then
    PID=$(cat /tmp/ai-client-repl.pid)
    if ! ps -p $PID > /dev/null 2>&1; then
        echo "❌ AI Client REPL not running (stale PID file)"
        echo "   Start it with: ./dev/start-ai-client-repl.sh"
        rm /tmp/ai-client-repl.pid
        exit 1
    fi
else
    echo "⚠️  Warning: AI Client REPL may not be running"
    echo "   Start it with: ./dev/start-ai-client-repl.sh"
    echo "   Attempting connection anyway..."
fi

# Run expression via lein repl :connect to AI client on port 7889
timeout "$TIMEOUT" lein repl :connect localhost:7889 <<EOF 2>&1 | \
    grep -v "^user=>" | \
    grep -v "Connecting to nREPL" | \
    grep -v "REPL-y" | \
    grep -v "Clojure" | \
    grep -v "OpenJDK" | \
    grep -v "Docs:" | \
    grep -v "Source:" | \
    grep -v "Javadoc:" | \
    grep -v "Exit:" | \
    grep -v "Results:" | \
    grep -v "Bye for now" | \
    sed 's/\[[0-9]*[A-Z]//g' | \
    sed 's/\[[0-9]*[a-z]//g' | \
    grep -v "^[[:space:]]*$"
$1
EOF
