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
    ai-stall
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

# Ask the REPL which checkout it is actually reading.
#
# The port-7889 REPL is a long-lived process rooted in ONE directory, and
# `(require 'ai-core :reload)` resolves against THAT classpath — not against
# whichever tree this script was invoked from. Run from a second worktree, this
# check therefore compiled the OTHER checkout's sources and printed a green tick
# for code it had never read.
#
# A false PASS is worse than an error: an error sends you looking, a tick sends
# you to commit. `pwd -P` on both sides so a symlinked path (/tmp vs /private/tmp)
# does not read as a mismatch.
repl_root() {
    # Test seam: dev/test/check_ai_worktree_test.sh drives the match / mismatch /
    # unverifiable branches against THIS script deterministically. Without it the
    # only way to exercise the match branch is to run whichever script happens to
    # live in the REPL's checkout — which is not the script under test (guest panel).
    if [ -n "${CHECK_AI_REPL_ROOT_OVERRIDE:-}" ]; then
        printf '%s' "$CHECK_AI_REPL_ROOT_OVERRIDE"
        return 0
    fi
    TIMEOUT=15 "$SCRIPT_DIR/ai-eval.sh" "runner" "$1" \
        '(System/getProperty "user.dir")' 2>/dev/null \
        | grep -o '"/[^"]*"' | tail -1 | tr -d '"'
}

# Check if Runner REPL is available (port 7889) and we have Babashka
RUNNER_PORT=7889
REPO_ROOT="$(pwd -P)"
USE_REPL=false

if command -v bb &>/dev/null && nc -z localhost $RUNNER_PORT 2>/dev/null; then
    RAW_REPL_ROOT="$(repl_root "$RUNNER_PORT")"
    REPL_ROOT=""
    if [ -n "$RAW_REPL_ROOT" ] && [ -d "$RAW_REPL_ROOT" ]; then
        # `|| REPL_ROOT=""` because `set -e` is on: if the directory vanishes or
        # loses +x between the test above and this cd, a bare assignment from a
        # failed command substitution returns 1 and kills the script instead of
        # falling back (guest panel).
        REPL_ROOT="$(cd "$RAW_REPL_ROOT" && pwd -P)" || REPL_ROOT=""
    fi

    if [ -z "$REPL_ROOT" ]; then
        # Unverifiable, so unusable. Failing closed costs ~30s; failing open
        # costs a green tick on unread code, which is the bug this guards.
        echo -e "${YELLOW}   REPL on port $RUNNER_PORT did not report its root${NC}"
        echo -e "${YELLOW}   Cannot confirm it reads THIS checkout — using cold start instead${NC}"
    elif [ "$REPL_ROOT" != "$REPO_ROOT" ]; then
        echo -e "${YELLOW}⚠️  REPL on port $RUNNER_PORT is rooted in a different checkout${NC}"
        echo -e "${YELLOW}      REPL reads: $REPL_ROOT${NC}"
        echo -e "${YELLOW}      you are in: $REPO_ROOT${NC}"
        echo -e "${YELLOW}   It would compile the OTHER tree's files and report success for${NC}"
        echo -e "${YELLOW}   code in this one that it never read. Using cold start instead.${NC}"
        echo -e "${YELLOW}   (the REPLs live in the main checkout; \`make test\` is worktree-local)${NC}"
    else
        USE_REPL=true
    fi
fi

if [ "$USE_REPL" = true ]; then
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
# Not necessarily "no REPL": we also land here from a live-but-foreign or
# unverifiable one. Name the tree being compiled instead of guessing the reason —
# that is the fact the caller actually needs.
echo -e "${CYAN}   Cold JVM start — compiling $REPO_ROOT${NC}"

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

# Guard on PIPESTATUS, not on the `if`. A pipeline's status is its LAST command's
# — here `tee`, which succeeds whatever lein did — so `if ... | tee` printed
# "✅ AI client code compiles successfully" directly beneath a
# FileNotFoundException, and made every branch below it unreachable. The line
# after this one was already reaching for PIPESTATUS; the `if` got there first.
timeout "$TIMEOUT_SECS" lein run -m clojure.main -e "$COLD_REQUIRE_EXPR" 2>&1 | tee "$TMPFILE"
EXIT_CODE=${PIPESTATUS[0]}

if [ "$EXIT_CODE" -eq 0 ]; then
    echo -e "${GREEN}✅ AI client code compiles successfully${NC}"
    exit 0
fi

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
