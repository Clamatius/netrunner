#!/bin/bash
# Send an action to the AI client
# Usage: ./ai-action.sh <clojure-expression>

if [ -z "$1" ]; then
    echo "Usage: $0 '<clojure-expression>'"
    echo "Example: $0 '(ai-game-controller/show-state)'"
    exit 1
fi

NREPL_PORT=$(cat .nrepl-port)
TIMEOUT=${TIMEOUT:-15}

timeout "$TIMEOUT" lein repl :connect localhost:$NREPL_PORT <<EOF
$1
EOF
