#!/bin/bash
# Quick compilation check for AI client code only
#
# Strategy:
# 1. If Runner REPL (port 7889) is running AND Babashka available, use it (~5s)
# 2. Otherwise, fall back to cold JVM start (~30s)
#
# Uses targeted require instead of full lein check (which compiles entire Jinteki)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

# ANSI color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
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
    ai-run-runner-handlers
    ai-run-corp-handlers
    ai-run-tactics
)

NS_COUNT="${#AI_NAMESPACES[@]}"
echo "🔍 Checking AI client code compilation..."
echo "   (${NS_COUNT} namespaces)"

# Build require expression with :reload to force recompile
REQUIRE_EXPR="(do"
for ns in "${AI_NAMESPACES[@]}"; do
    REQUIRE_EXPR="$REQUIRE_EXPR (require '$ns :reload)"
done
REQUIRE_EXPR="$REQUIRE_EXPR :check-success)"

# Check if Runner REPL is available (port 7889) and we have Babashka
RUNNER_PORT=7889
if command -v bb &>/dev/null && nc -z localhost $RUNNER_PORT 2>/dev/null; then
    echo -e "${CYAN}   Using running REPL (port $RUNNER_PORT) via Babashka${NC}"

    TMPFILE=$(mktemp)
    trap "rm -f $TMPFILE" EXIT

    # Use ai-eval.sh which handles nREPL properly
    if TIMEOUT=30 "$SCRIPT_DIR/ai-eval.sh" "runner" "$RUNNER_PORT" "$REQUIRE_EXPR" > "$TMPFILE" 2>&1; then
        if grep -q ":check-success" "$TMPFILE"; then
            echo -e "${GREEN}✅ All ${NS_COUNT} AI namespaces compiled successfully${NC}"
            echo -e "${GREEN}✅ AI client code compiles successfully (REPL check ~fast)${NC}"
            exit 0
        fi
    fi

    # Check for compilation errors in output
    if grep -q "Syntax error\|EOF while reading\|CompilerException\|Unable to resolve\|Cannot find\|Exception:" "$TMPFILE"; then
        echo ""
        echo -e "${RED}❌ Compilation FAILED${NC}"
        echo ""
        echo "Error details:"
        echo "--------------"
        cat "$TMPFILE" | grep -B 2 -A 10 "Syntax error\|EOF while reading\|CompilerException\|Unable to resolve\|Cannot find\|Exception:" | head -40
        exit 1
    fi

    # REPL check failed for unknown reason, fall through to cold start
    echo -e "${YELLOW}   REPL check inconclusive, falling back to cold start...${NC}"
    [ -n "${DEBUG:-}" ] && echo "   Debug output:" && cat "$TMPFILE"
fi

# Cold start approach
echo -e "${CYAN}   Cold JVM start (no REPL available or Babashka not installed)${NC}"

# Build require expression for cold start (with System/exit)
COLD_REQUIRE_EXPR="(do"
for ns in "${AI_NAMESPACES[@]}"; do
    COLD_REQUIRE_EXPR="$COLD_REQUIRE_EXPR (require '$ns)"
done
COLD_REQUIRE_EXPR="$COLD_REQUIRE_EXPR (println \"✅ All ${NS_COUNT} AI namespaces compiled successfully\") (System/exit 0))"

# Run with timeout
TMPFILE=$(mktemp)
trap "rm -f $TMPFILE" EXIT

TIMEOUT_SECS=${1:-60}  # Default 60 seconds, can override with arg

if timeout "$TIMEOUT_SECS" lein run -m clojure.main -e "$COLD_REQUIRE_EXPR" 2>&1 | tee "$TMPFILE"; then
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
