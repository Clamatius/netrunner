#!/bin/bash
# `make check` must never print a green tick over a failure.
#
# check-ai.sh had two independent ways to do it:
#
#   1. Its fast path requires namespaces inside a long-lived nREPL on port 7889.
#      That process is rooted in ONE directory and resolves `(require 'ai-core)`
#      against ITS classpath, so from a second worktree the check compiled the
#      main checkout's files and reported success for code it never read.
#
#   2. Cold start guarded on `if timeout ... lein run ... | tee "$TMPFILE"`. A
#      pipeline's status is its LAST command's, so that tested `tee` — always 0.
#      Cold start reported success on ANY failure, and every error branch below
#      it was unreachable.
#
# Both are false PASSES, the dangerous direction: an error sends you looking, a
# tick sends you to commit.
#
# Bug 2 has nothing to do with REPLs and its test needs none, so it runs
# unconditionally — gating it behind a listener would skip it in exactly the
# cold-start-only environment where that path is the only one used (guest panel).

set -u
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SCRIPT_UNDER_TEST="$REPO_ROOT/dev/check-ai.sh"
# Overridable so the no-REPL skip path is itself testable; check-ai.sh's own port
# is separate and unchanged.
RUNNER_PORT="${CHECK_AI_TEST_REPL_PORT:-7889}"
PASS=0; FAIL=0

ok()   { echo "ok   [$1]"; PASS=$((PASS+1)); }
nope() { echo "NOT OK [$1]"; [ -n "${2:-}" ] && echo "$2" | sed 's/^/       /'; FAIL=$((FAIL+1)); }

# A foreign root: a dev/ holding a COPY of the script under test. check-ai.sh
# derives its repo root from its own location, so this is a different checkout as
# far as it is concerned — the second-worktree situation, without needing one.
FOREIGN="$(mktemp -d)"
trap 'rm -rf "$FOREIGN"' EXIT
# A missing or unrunnable script exits non-zero and prints no success tick, which
# would satisfy the cold-start assertions for entirely the wrong reason. Establish
# that there IS something under test before concluding anything about it.
if [ ! -x "$SCRIPT_UNDER_TEST" ]; then
    echo "NOT OK [script-under-test-exists]"
    echo "       $SCRIPT_UNDER_TEST is missing or not executable — every assertion"
    echo "       below would pass vacuously."
    exit 1
fi

mkdir -p "$FOREIGN/dev" "$FOREIGN/bin"
cp "$SCRIPT_UNDER_TEST" "$FOREIGN/dev/check-ai.sh"
ln -s "$REPO_ROOT/dev/ai-eval.sh"  "$FOREIGN/dev/ai-eval.sh"
ln -s "$REPO_ROOT/dev/load-env.sh" "$FOREIGN/dev/load-env.sh"

# A lein that always fails, so the cold-start assertion tests OUR exit handling
# rather than a real compile: deterministic, sub-second, and no JVM.
cat > "$FOREIGN/bin/lein" <<'STUB'
#!/bin/bash
echo "stub lein: compilation failed" >&2
exit 1
STUB
chmod +x "$FOREIGN/bin/lein"

# ---------------------------------------------------------------------------
echo "--- bug 2: a failed cold start must exit non-zero, not ride tee's status ---"
# ---------------------------------------------------------------------------
OUT="$(PATH="$FOREIGN/bin:$PATH" "$FOREIGN/dev/check-ai.sh" 5 2>&1)"; RC=$?

[ "$RC" -ne 0 ] \
    && ok "cold-start-failure-exits-nonzero" \
    || nope "cold-start-failure-exits-nonzero" "exited 0 though lein exited 1"

echo "$OUT" | grep -q "compiles successfully" \
    && nope "cold-start-failure-not-green" "$(echo "$OUT" | grep 'compiles successfully')" \
    || ok "cold-start-failure-not-green"

echo "$OUT" | grep -qi "failed\|error" \
    && ok "cold-start-failure-says-so" \
    || nope "cold-start-failure-says-so" "$OUT"

# ---------------------------------------------------------------------------
# The root guard only exists when a REPL does, so these need one. Each drives a
# branch of the script under test via CHECK_AI_REPL_ROOT_OVERRIDE, rather than
# running whichever check-ai.sh happens to live in the REPL's own checkout — that
# script is not the one being changed, and asserting on it proves nothing here.
# ---------------------------------------------------------------------------
if ! command -v bb &>/dev/null || ! nc -z localhost $RUNNER_PORT 2>/dev/null; then
    echo "⏭️  root-guard branches: no REPL on $RUNNER_PORT (or no bb) — skipping those"
    echo ""
    echo "Passed: $PASS   Failed: $FAIL"
    [ "$FAIL" -eq 0 ] || exit 1
    echo "✅ check-ai worktree guard: cold-start assertions passed (root guard skipped)"
    exit 0
fi

echo "--- bug 1: a REPL rooted in another checkout must not be used ---"
OUT="$(cd "$REPO_ROOT" && CHECK_AI_REPL_ROOT_OVERRIDE="/tmp" \
       PATH="$FOREIGN/bin:$PATH" ./dev/check-ai.sh 5 2>&1)" || true

echo "$OUT" | grep -q "compiles successfully" \
    && nope "mismatch-not-green" "used a REPL rooted elsewhere and called it a pass" \
    || ok "mismatch-not-green"
echo "$OUT" | grep -q "Using running REPL" \
    && nope "mismatch-skips-fast-path" "took the fast path against a foreign root" \
    || ok "mismatch-skips-fast-path"
echo "$OUT" | grep -qiE "different (checkout|worktree)|rooted (in|at)" \
    && ok "mismatch-explains" \
    || nope "mismatch-explains" "$OUT"
echo "$OUT" | grep -qF "/tmp" \
    && ok "mismatch-names-repl-root" \
    || nope "mismatch-names-repl-root" "must print where the REPL actually lives"

echo "--- an unverifiable REPL root fails closed (cold start), not open ---"
OUT="$(cd "$REPO_ROOT" && CHECK_AI_REPL_ROOT_OVERRIDE="/nonexistent-$$" \
       PATH="$FOREIGN/bin:$PATH" ./dev/check-ai.sh 5 2>&1)" || true

echo "$OUT" | grep -q "Using running REPL" \
    && nope "unverifiable-skips-fast-path" "trusted a root it could not confirm" \
    || ok "unverifiable-skips-fast-path"
echo "$OUT" | grep -qi "did not report its root\|cannot confirm" \
    && ok "unverifiable-explains" \
    || nope "unverifiable-explains" "$OUT"

echo "--- and the matching case KEEPS the fast path (a guard nobody can afford gets removed) ---"
OUT="$(cd "$REPO_ROOT" && CHECK_AI_REPL_ROOT_OVERRIDE="$REPO_ROOT" ./dev/check-ai.sh 2>&1)" || true

echo "$OUT" | grep -q "Using running REPL" \
    && ok "match-uses-repl" \
    || nope "match-uses-repl" "$(echo "$OUT" | tail -6)"
echo "$OUT" | grep -q "compiles successfully" \
    && ok "match-still-green" \
    || nope "match-still-green" "$(echo "$OUT" | tail -6)"

echo ""
echo "Passed: $PASS   Failed: $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
echo "✅ check-ai worktree guard: all assertions passed"
