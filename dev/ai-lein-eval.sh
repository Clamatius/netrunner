#!/bin/bash
# AI Client nREPL eval script
# Sends commands to the AI Client REPL
# Usage: ./ai-eval.sh [client_name] [port] <clojure-expression>
# Old usage still supported: ./ai-eval.sh <clojure-expression>

TIMEOUT=${TIMEOUT:-10}

# Parse arguments - support both old and new usage
if [ $# -eq 1 ]; then
    # Old usage: just expression
    CLIENT_NAME="fixed-id"
    REPL_PORT="7889"
    EXPRESSION="$1"
elif [ $# -eq 3 ]; then
    # New usage: client_name port expression
    CLIENT_NAME="$1"
    REPL_PORT="$2"
    EXPRESSION="$3"
else
    echo "Usage: $0 [client_name] [port] <clojure-expression>"
    echo "Examples:"
    echo "  $0 '(ai-actions/status)'"
    echo "  $0 runner 7889 '(ai-actions/status)'"
    exit 1
fi

# Check if AI client REPL is running
if [ -f /tmp/ai-client-${CLIENT_NAME}.pid ]; then
    PID=$(cat /tmp/ai-client-${CLIENT_NAME}.pid)
    if ! ps -p $PID > /dev/null 2>&1; then
        echo "❌ AI Client REPL '$CLIENT_NAME' not running (stale PID file)"
        echo "   Start it with: ./dev/start-ai-client-repl.sh $CLIENT_NAME $REPL_PORT"
        rm /tmp/ai-client-${CLIENT_NAME}.pid
        exit 1
    fi
else
    echo "⚠️  Warning: AI Client REPL '$CLIENT_NAME' may not be running"
    echo "   Start it with: ./dev/start-ai-client-repl.sh $CLIENT_NAME $REPL_PORT"
    echo "   Attempting connection anyway..."
fi
    # Fallback to lein repl :connect (slower, for compatibility)
    timeout "$TIMEOUT" lein repl :connect localhost:$REPL_PORT <<EOF 2>&1
$EXPRESSION
EOF
