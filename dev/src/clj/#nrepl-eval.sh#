#!/bin/bash
# Quick nREPL eval script for AI client testing
# Usage: ./nrepl-eval.sh <clojure-expression>

TIMEOUT=${TIMEOUT:-10}

if [ -z "$1" ]; then
    echo "Usage: $0 <clojure-expression>"
    echo "Example: $0 '(+ 1 2)'"
    exit 1
fi

# Run expression via lein repl :connect and clean up output
timeout "$TIMEOUT" lein repl :connect localhost:7888 <<EOF 2>&1 | \
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
