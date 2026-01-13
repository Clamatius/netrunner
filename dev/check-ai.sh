#!/bin/bash
# Quick compilation check for AI client code only
# Uses targeted require instead of full lein check (which compiles entire Jinteki)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Static list of AI namespaces (order matters for dependencies)
AI_NAMESPACES=(
    ai-state
    ai-websocket-client-v2
    ai-auth
    ai-core
    ai-connection
    ai-basic-actions
    ai-prompts
    ai-card-actions
    ai-runs
    ai-display
    ai-hud-utils
    ai-debug
    ai-actions
    ai-goldfish-corp
    ai-goldfish-runner
    ai-heuristic-corp
    ai-heuristic-runner
)

echo "🔍 Checking AI client code compilation..."
echo "   (${#AI_NAMESPACES[@]} namespaces)"

# Build require expression
REQUIRE_EXPR="(do"
for ns in "${AI_NAMESPACES[@]}"; do
    REQUIRE_EXPR="$REQUIRE_EXPR (require '$ns)"
done
REQUIRE_EXPR="$REQUIRE_EXPR (println \"✅ All ${#AI_NAMESPACES[@]} AI namespaces compiled successfully\") (System/exit 0))"

# Run with timeout
TMPFILE=$(mktemp)
trap "rm -f $TMPFILE" EXIT

TIMEOUT_SECS=${1:-45}  # Default 45 seconds, can override with arg

if timeout "$TIMEOUT_SECS" lein run -m clojure.main -e "$REQUIRE_EXPR" 2>&1 | tee "$TMPFILE"; then
    echo -e "${GREEN}✅ AI client code compiles successfully${NC}"
    exit 0
fi

# Check exit code
EXIT_CODE=${PIPESTATUS[0]}

# Check if it was a compilation error
if grep -q "Syntax error\|EOF while reading\|CompilerException\|Unable to resolve\|Cannot find" "$TMPFILE"; then
    echo ""
    echo -e "${RED}❌ Compilation FAILED${NC}"
    echo ""
    echo "Error details:"
    echo "--------------"
    grep -B 2 -A 10 "Syntax error\|EOF while reading\|CompilerException\|Unable to resolve\|Cannot find" "$TMPFILE" | head -30
    exit 1
fi

# Timeout
if [ "$EXIT_CODE" -eq 124 ]; then
    echo -e "${YELLOW}⚠️  Compilation check timed out after ${TIMEOUT_SECS}s${NC}"
    echo "   Try: ./dev/check-ai.sh 90  (for longer timeout)"
    exit 1
fi

# Other failure
echo -e "${RED}❌ Check failed with exit code $EXIT_CODE${NC}"
exit 1
