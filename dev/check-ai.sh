#!/bin/bash
# Quick compilation check for AI client code only
#
# Strategy:
# 1. If Runner REPL (port 7889) is running AND Babashka available, use it (~5s)
# 2. Otherwise, fall back to cold JVM start (~20s warm cache, ~85s cold)
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
echo "   (${NS_COUNT} namespaces + parse-only sweep of dev/src/clj)"

# #184: the list above is hand-maintained, and it had already drifted — 21 names
# for 35 .clj files. Two of the strays are require'd transitively so nothing was
# lost; ai_client_init.clj is not required by ANYTHING, because
# start-ai-client-repl.sh brings it in with `load-file`. So the file that boots
# both AI seats could carry a syntax error through a clean `make check` and only
# blow up later at `make reset`, looking like an environment fault.
#
# Rather than append one name to the list that just proved it drifts, sweep the
# whole directory. The sweep is PARSE-ONLY — every form is read, none evaluated —
# which is what makes it safe to point at ai_client_init.clj at all: loading that
# file reads AI_USERNAME/AI_PASSWORD from the environment and opens a WebSocket to
# localhost:1042, and a static gate that needs the game server up is a flaky gate.
#
# The sweep itself lives in dev/src/clj/check_ai_sweep.clj, NOT inline here, and
# is pinned by dev/test/check_ai_sweep_test.clj against real `load-file`. That is
# deliberate: the first cut WAS fifteen lines of Clojure embedded in this shell
# string, and a review panel found three separate ways it disagreed with real
# loading — two of them false GREENS. A gate that cannot be unit-tested is a gate
# whose defects are found in production. See that namespace's docstring for the
# full list and for what parsing still cannot catch.
#
# AI_NAMESPACES above stays hand-maintained on purpose: some files in this tree
# have load-time side effects (ai_client_init.clj is the example), so "require
# everything" is not available. A NEW ai namespace therefore gets parse-only
# coverage until someone adds it to the list — narrower than compile coverage,
# and stated here rather than left to be discovered.
# `:reload` deliberately, matching REQUIRE_EXPR below. Without it the WARM path
# would keep whatever check-ai-sweep it loaded first: edit the sweep in a live
# session and the gate silently keeps running the old one. Harmless in a cold JVM.
PARSE_EXPR='(do (require (quote check-ai-sweep) :reload)
                (println (str "   parse-only: "
                              ((resolve (quote check-ai-sweep/sweep!)) "dev/src/clj")
                              " files in dev/src/clj read clean"
                              " (syntax only — requires are NOT resolved)")))'

# Build the WARM-path expression: sweep first, then reload every listed namespace.
# `:reload` forces a recompile in the long-lived REPL rather than trusting what it
# happens to have loaded.
#
# This builder was accidentally deleted while the sweep was being added, and
# nothing here noticed: a worktree ALWAYS takes the cold path (the foreign-REPL
# guard below), so `make verify` stayed green while "$REQUIRE_EXPR" silently
# expanded to the empty string and the warm path degraded to a cold fallback on
# every run. Caught by a round-2 review seat, not by the suite. If you touch this
# file from a worktree, remember that half of it is unexecuted there.
REQUIRE_EXPR="(do $PARSE_EXPR"
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
            # Surface the sweep's own line. Without this the warm path prints only
            # the two green ticks and silently drops the caveat that requires are
            # NOT resolved — #184 asked specifically that "✅" not imply more than
            # was checked, and on the fast path it did.
            grep "parse-only:" "$TMPFILE" || true
            echo -e "${GREEN}✅ All ${NS_COUNT} AI namespaces compiled successfully${NC}"
            echo -e "${GREEN}✅ AI client code compiles successfully (REPL check ~fast)${NC}"
            exit 0
        fi
    fi

    # Check for compilation errors in output
    if grep -q "AI-PARSE-ERROR\|Syntax error\|EOF while reading\|CompilerException\|Unable to resolve\|Cannot find\|Exception:" "$TMPFILE"; then
        echo ""
        echo -e "${RED}❌ Compilation FAILED${NC}"
        echo ""
        echo "Error details:"
        echo "--------------"
        cat "$TMPFILE" | grep -B 2 -A 10 "AI-PARSE-ERROR\|Syntax error\|EOF while reading\|CompilerException\|Unable to resolve\|Cannot find\|Exception:" | head -40
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
COLD_REQUIRE_EXPR="(do $PARSE_EXPR"
for ns in "${AI_NAMESPACES[@]}"; do
    COLD_REQUIRE_EXPR="$COLD_REQUIRE_EXPR (require '$ns)"
done
COLD_REQUIRE_EXPR="$COLD_REQUIRE_EXPR (println \"✅ All ${NS_COUNT} AI namespaces compiled successfully\") (System/exit 0))"

# Run with timeout
TMPFILE=$(mktemp)
trap "rm -f $TMPFILE" EXIT

# Default was 60s, chosen when the header still said cold start took ~30s. It
# does not: measured on this tree, a cold JVM is ~20s with a warm lein/JVM cache
# and ~85s without one. 60s therefore sat right in the middle, and the path that
# ALWAYS pays the cold cost is the worktree — the #147 guard above forces cold
# start whenever the REPLs are rooted elsewhere, which is every worktree, which
# is where CLAUDE.md says feature work happens.
#
# So `make verify` failed for worktree sessions by default, with
# "⚠️  Compilation check timed out" and `make: *** [check] Error 1` — a red gate
# that means nothing about the code. A timeout is a backstop against a hang, not
# a performance budget; 180s still catches a hang and stops lying about a
# healthy tree. Override positionally: ./dev/check-ai.sh 60
TIMEOUT_SECS=${1:-180}

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
if grep -q "AI-PARSE-ERROR\|Syntax error\|EOF while reading\|CompilerException\|Unable to resolve\|Cannot find" "$TMPFILE"; then
    echo ""
    echo -e "${RED}❌ Compilation FAILED${NC}"
    echo ""
    echo "Error details:"
    echo "--------------"
    grep -B 2 -A 10 "AI-PARSE-ERROR\|Syntax error\|EOF while reading\|CompilerException\|Unable to resolve\|Cannot find" "$TMPFILE" | head -30
    exit 1
fi

# Timeout
if [ "$EXIT_CODE" -eq 124 ]; then
    echo -e "${YELLOW}⚠️  Compilation check timed out after ${TIMEOUT_SECS}s${NC}"
    echo "   Try: ./dev/check-ai.sh $((TIMEOUT_SECS * 2))  (for longer timeout)"
    exit 1
fi

# Other failure
echo -e "${RED}❌ Check failed with exit code $EXIT_CODE${NC}"
exit 1
