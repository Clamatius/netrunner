#!/bin/bash
# Stop AI Client REPL

if [ -f /tmp/ai-client-repl.pid ]; then
    PID=$(cat /tmp/ai-client-repl.pid)
    if ps -p $PID > /dev/null 2>&1; then
        echo "Stopping AI Client REPL (PID: $PID)..."
        kill $PID
        rm /tmp/ai-client-repl.pid
        echo "✅ AI Client stopped"
    else
        echo "⚠️  AI Client REPL not running (stale PID file)"
        rm /tmp/ai-client-repl.pid
    fi
else
    echo "⚠️  No PID file found - AI Client may not be running"
    echo "   Looking for lein process on port 7889..."

    # Try to find and kill any lein process listening on 7889
    LEIN_PID=$(lsof -ti:7889 2>/dev/null)
    if [ -n "$LEIN_PID" ]; then
        echo "Found process $LEIN_PID on port 7889"
        kill $LEIN_PID
        echo "✅ Process killed"
    else
        echo "No process found on port 7889"
    fi
fi
