#!/bin/bash
# Stop AI Client REPL
# Usage: ./stop-ai-client.sh [client_name] [port]
# Old usage still supported: ./stop-ai-client.sh

# Parse arguments - support both old and new usage
if [ $# -eq 0 ]; then
    # Old usage: default client
    CLIENT_NAME="fixed-id"
    REPL_PORT="7889"
elif [ $# -eq 1 ]; then
    # New usage: just client name
    CLIENT_NAME="$1"
    REPL_PORT=""  # Will be derived from lsof
elif [ $# -eq 2 ]; then
    # New usage: client name and port
    CLIENT_NAME="$1"
    REPL_PORT="$2"
else
    echo "Usage: $0 [client_name] [port]"
    echo "Examples:"
    echo "  $0              # Stop default client (fixed-id)"
    echo "  $0 runner       # Stop runner client"
    echo "  $0 corp 7890    # Stop corp client on port 7890"
    exit 1
fi

if [ -f /tmp/ai-client-${CLIENT_NAME}.pid ]; then
    PID=$(cat /tmp/ai-client-${CLIENT_NAME}.pid)
    if ps -p $PID > /dev/null 2>&1; then
        echo "Stopping AI Client REPL '$CLIENT_NAME' (PID: $PID)..."
        kill $PID
        rm /tmp/ai-client-${CLIENT_NAME}.pid
        echo "✅ AI Client '$CLIENT_NAME' stopped"
    else
        echo "⚠️  AI Client REPL '$CLIENT_NAME' not running (stale PID file)"
        rm /tmp/ai-client-${CLIENT_NAME}.pid
    fi
else
    echo "⚠️  No PID file found - AI Client '$CLIENT_NAME' may not be running"
    if [ -n "$REPL_PORT" ]; then
        echo "   Looking for process on port $REPL_PORT..."
        LEIN_PID=$(lsof -ti:$REPL_PORT 2>/dev/null)
        if [ -n "$LEIN_PID" ]; then
            echo "Found process $LEIN_PID on port $REPL_PORT"
            kill $LEIN_PID
            echo "✅ Process killed"
        else
            echo "No process found on port $REPL_PORT"
        fi
    else
        echo "   (No port specified, cannot search for process)"
    fi
fi
