#!/bin/bash
# `make check` must never pass by compiling a DIFFERENT checkout's files.
#
# check-ai.sh's fast path requires namespaces inside a long-lived nREPL on port
# 7889. That process is rooted in ONE directory and resolves `(require 'ai-core)`
# against ITS classpath. Run from a second worktree, the check therefore compiled
# the main checkout's sources and printed "✅ All N AI namespaces compiled
# successfully" for code it had never read.
#
# That is the dangerous shape: not an error, a PASS. It reported green on a
# worktree whose files did not compile at all (found while rebasing #113/#111,
# where it sailed through against sources that were not on disk in that tree).
#
# Requires a live REPL to be meaningful — the whole bug is about which REPL
# answers — so it SKIPS when none is listening rather than pretending to pass.

set -u
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
RUNNER_PORT=7889
PASS=0; FAIL=0

ok()   { echo "ok   [$1]"; PASS=$((PASS+1)); }
nope() { echo "NOT OK [$1]"; [ -n "${2:-}" ] && echo "$2" | sed 's/^/       /'; FAIL=$((FAIL+1)); }

if ! command -v bb &>/dev/null || ! nc -z localhost $RUNNER_PORT 2>/dev/null; then
    echo "⏭️  check-ai worktree guard: no REPL on $RUNNER_PORT (or no bb) — skipping"
    echo "   (the fast path this guards is itself inactive without one)"
    exit 0
fi

REPL_ROOT="$(TIMEOUT=15 "$REPO_ROOT/dev/ai-eval.sh" runner $RUNNER_PORT \
             '(System/getProperty "user.dir")' 2>/dev/null \
             | grep -o '"/[^"]*"' | tail -1 | tr -d '"')"

if [ -z "$REPL_ROOT" ]; then
    echo "⏭️  check-ai worktree guard: REPL did not report its root — skipping"
    exit 0
fi
echo "--- REPL is rooted at $REPL_ROOT ---"

# A foreign root: a dev/ dir that is NOT the REPL's checkout. check-ai.sh derives
# its repo root from its own location, so a copy here IS a different checkout as
# far as the script is concerned — exactly the second-worktree situation.
FOREIGN="$(mktemp -d)"
trap 'rm -rf "$FOREIGN"' EXIT
mkdir -p "$FOREIGN/dev"
cp "$REPO_ROOT/dev/check-ai.sh" "$FOREIGN/dev/check-ai.sh"
ln -s "$REPO_ROOT/dev/ai-eval.sh"  "$FOREIGN/dev/ai-eval.sh"
ln -s "$REPO_ROOT/dev/load-env.sh" "$FOREIGN/dev/load-env.sh"

# `1` = cold-start timeout. If the guard works we never reach the cold start;
# if it does not, this keeps the test from hanging on a 60s JVM boot.
OUT="$("$FOREIGN/dev/check-ai.sh" 1 2>&1)"

echo "--- a check run from a foreign root must not claim success ---"
if echo "$OUT" | grep -q "compiled successfully"; then
    nope "foreign-root-not-green" "$(echo "$OUT" | grep -E 'compiled successfully|Using running REPL')"
else
    ok "foreign-root-not-green"
fi

if [ "$(echo "$OUT" | grep -c "✅")" -gt 0 ]; then
    nope "foreign-root-no-tick" "a ✅ from a root the REPL cannot see is the bug itself"
else
    ok "foreign-root-no-tick"
fi

echo "--- and must say WHY, naming both roots ---"
echo "$OUT" | grep -qiE "different (checkout|worktree)|rooted (in|at)" \
    && ok "foreign-root-explains" \
    || nope "foreign-root-explains" "$OUT"
echo "$OUT" | grep -qF "$REPL_ROOT" \
    && ok "foreign-root-names-repl-root" \
    || nope "foreign-root-names-repl-root" "must print where the REPL actually lives"

# Found while fixing the above: the cold-start fallback guarded on
#   if timeout ... lein run ... 2>&1 | tee "$TMPFILE"; then echo "✅ ..."
# A pipeline's status is its LAST command's, so that tested `tee`, which always
# succeeds. Cold start therefore reported success on ANY failure — including a
# FileNotFoundException for the very namespaces it claims to have compiled — and
# left the error branches below it unreachable. Nothing to do with worktrees:
# it hits any machine with no REPL or no Babashka.
echo "--- a failed cold start must exit non-zero, not ride tee's status ---"
"$FOREIGN/dev/check-ai.sh" 1 >/dev/null 2>&1; RC=$?
[ "$RC" -ne 0 ] \
    && ok "cold-start-failure-exits-nonzero" \
    || nope "cold-start-failure-exits-nonzero" "exited 0 after failing to load a single namespace"

echo "--- the REPL's OWN root keeps the fast path (the guard must not cost it) ---"
NATIVE="$(cd "$REPL_ROOT" && ./dev/check-ai.sh 2>&1)"
if echo "$NATIVE" | grep -q "compiled successfully"; then
    ok "native-root-still-fast"
else
    nope "native-root-still-fast" "$(echo "$NATIVE" | tail -5)"
fi
echo "$NATIVE" | grep -q "Using running REPL" \
    && ok "native-root-uses-repl" \
    || nope "native-root-uses-repl" "guard pushed the main checkout onto the slow path"

echo ""
echo "Passed: $PASS   Failed: $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
echo "✅ check-ai worktree guard: all assertions passed"
