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
#      Cold start reported success on ANY failure, and the error branches below
#      it were unreachable.
#
# Both are false PASSES, the dangerous direction: an error sends you looking, a
# tick sends you to commit.
#
# HOW THIS TESTS IT. check-ai.sh reaches the outside world through three
# unqualified names — `nc` (is a REPL listening), `bb`, and `lein` — plus
# `$SCRIPT_DIR/ai-eval.sh` (talk to the REPL). Doubling those in a throwaway tree
# drives every branch with no REPL and no JVM, and, unlike an env seam inside
# check-ai.sh, leaves no way to bypass the guard in production. The real query and
# its output parsing run for real against the double.
#
# Stubs can't prove we can still talk to an actual nREPL, so the last section
# re-runs the UNSTUBBED script against the real one when it is up.

set -u
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SCRIPT_UNDER_TEST="$REPO_ROOT/dev/check-ai.sh"
RUNNER_PORT=7889
PASS=0; FAIL=0

ok()   { echo "ok   [$1]"; PASS=$((PASS+1)); }
nope() { echo "NOT OK [$1]"; [ -n "${2:-}" ] && echo "$2" | sed 's/^/       /'; FAIL=$((FAIL+1)); }
die()  { echo "NOT OK [fixture] $1"; exit 1; }

# A missing script exits non-zero and prints no tick, which would satisfy the
# failure assertions for entirely the wrong reason.
[ -x "$SCRIPT_UNDER_TEST" ] || die "$SCRIPT_UNDER_TEST missing or not executable"

# `pwd -P` at creation: check-ai.sh canonicalises both sides, so a raw mktemp path
# (/var/... vs /private/var/...) would not match the root it prints back.
FOREIGN="$(mktemp -d)" || die "mktemp failed"
FOREIGN="$(cd "$FOREIGN" && pwd -P)" || die "cannot canonicalise fixture dir"
[ -n "$FOREIGN" ] && [ -d "$FOREIGN" ] || die "mktemp gave no directory"
trap 'rm -rf "$FOREIGN"' EXIT
mkdir -p "$FOREIGN/dev" "$FOREIGN/bin" || die "mkdir failed"

# The script under test, in a tree that is not the REPL's. check-ai.sh derives its
# repo root from its own location, so this IS a second checkout to it.
cp "$SCRIPT_UNDER_TEST" "$FOREIGN/dev/check-ai.sh" || die "cp failed"

# The REPL double. check-ai.sh asks it two different things; answer both, and
# report whatever root the current assertion wants via STUB_REPL_ROOT.
cat > "$FOREIGN/dev/ai-eval.sh" <<'STUB' || die "stub ai-eval failed"
#!/bin/bash
# args: <client> <port> <expression>
if [[ "${3:-}" == *"user.dir"* ]]; then
    [ -n "${STUB_REPL_ROOT:-}" ] && printf '"%s"\n' "$STUB_REPL_ROOT"
    exit 0
fi
echo ":check-success"      # the compile-check require
STUB
: > "$FOREIGN/dev/load-env.sh" || die "stub load-env failed"

# `lein` that always fails, so the cold-start assertion tests OUR exit handling
# rather than a real compile: deterministic, sub-second, no JVM.
cat > "$FOREIGN/bin/lein" <<'STUB' || die "stub lein failed"
#!/bin/bash
echo "stub lein: compilation failed" >&2
exit 1
STUB
# `nc -z` succeeds and `bb` exists => "a REPL is listening", check-ai.sh's
# precondition for the fast path, without needing one.
printf '#!/bin/bash\nexit 0\n' > "$FOREIGN/bin/nc" || die "stub nc failed"
printf '#!/bin/bash\nexit 0\n' > "$FOREIGN/bin/bb" || die "stub bb failed"

# A second bin holding ONLY the failing lein, for the live-REPL section. The real
# ai-eval.sh talks bencode through `bb`, so the stub `bb` above would gag the very
# query that section exists to exercise — it reported "did not report its root".
mkdir -p "$FOREIGN/bin-leinonly" || die "mkdir bin-leinonly failed"
cp "$FOREIGN/bin/lein" "$FOREIGN/bin-leinonly/lein" || die "cp lein stub failed"

chmod +x "$FOREIGN/dev/ai-eval.sh" "$FOREIGN/bin/lein" "$FOREIGN/bin/nc" "$FOREIGN/bin/bb" \
         "$FOREIGN/bin-leinonly/lein" || die "chmod failed"

PRETEND_REPL_ROOT="$FOREIGN/some-other-checkout"
mkdir -p "$PRETEND_REPL_ROOT" || die "mkdir pretend root failed"

# Run the copy with the doubles in front of PATH. Every run returns output in $OUT
# and the REAL exit status in $RC — `|| true` here would discard the status that
# tells success from failure, which is the entire property under test.
run_foreign() {
    local root="$1"; shift
    OUT="$(cd "$FOREIGN" && STUB_REPL_ROOT="$root" PATH="$FOREIGN/bin:$PATH" \
           "$FOREIGN/dev/check-ai.sh" "$@" 2>&1)"
    RC=$?
}

# ---------------------------------------------------------------------------
echo "--- bug 2: a failed cold start must exit non-zero, not ride tee's status ---"
# ---------------------------------------------------------------------------
run_foreign "" 5
[ "$RC" -ne 0 ] && ok "cold-start-failure-exits-nonzero" \
                || nope "cold-start-failure-exits-nonzero" "exited 0 though lein exited 1"
echo "$OUT" | grep -q "compiles successfully" \
    && nope "cold-start-failure-not-green" "$(echo "$OUT" | grep 'compiles successfully')" \
    || ok "cold-start-failure-not-green"
# Without this the assertions above also pass for failures BEFORE lein (a missing
# `timeout`, a broken fixture) — non-zero, no tick, wrong reason.
echo "$OUT" | grep -q "stub lein: compilation failed" \
    && ok "cold-start-actually-reached-lein" \
    || nope "cold-start-actually-reached-lein" "$OUT"

# ---------------------------------------------------------------------------
echo "--- bug 1: a REPL rooted in another checkout must not be used ---"
# ---------------------------------------------------------------------------
run_foreign "$PRETEND_REPL_ROOT" 5
echo "$OUT" | grep -q "Using running REPL" \
    && nope "mismatch-skips-fast-path" "took the fast path against a foreign root" \
    || ok "mismatch-skips-fast-path"
echo "$OUT" | grep -q "compiles successfully" \
    && nope "mismatch-not-green" "used a REPL rooted elsewhere and called it a pass" \
    || ok "mismatch-not-green"
[ "$RC" -ne 0 ] && ok "mismatch-exits-nonzero" \
                || nope "mismatch-exits-nonzero" "warned, then exited 0 having compiled nothing"
echo "$OUT" | grep -qiE "different (checkout|worktree)|rooted (in|at)" \
    && ok "mismatch-explains" || nope "mismatch-explains" "$OUT"
# The full line, not a bare substring: a loose grep matches the cold-start banner
# or the local path and passes even if the REPL's root is misreported.
echo "$OUT" | grep -qF "REPL reads: $PRETEND_REPL_ROOT" \
    && ok "mismatch-names-repl-root" \
    || nope "mismatch-names-repl-root" "must print where the REPL actually lives"
echo "$OUT" | grep -q "stub lein: compilation failed" \
    && ok "mismatch-falls-back-to-cold" \
    || nope "mismatch-falls-back-to-cold" "warned but never reached the cold start"

# ---------------------------------------------------------------------------
echo "--- an unverifiable root fails closed (cold start), not open ---"
# ---------------------------------------------------------------------------
run_foreign "/nonexistent-$$" 5
echo "$OUT" | grep -q "Using running REPL" \
    && nope "unverifiable-skips-fast-path" "trusted a root it could not confirm" \
    || ok "unverifiable-skips-fast-path"
[ "$RC" -ne 0 ] && ok "unverifiable-exits-nonzero" \
                || nope "unverifiable-exits-nonzero" "exited 0 having compiled nothing"
echo "$OUT" | grep -qi "did not report its root\|cannot confirm" \
    && ok "unverifiable-explains" || nope "unverifiable-explains" "$OUT"

# ---------------------------------------------------------------------------
echo "--- the matching root KEEPS the fast path (a guard nobody can afford gets removed) ---"
# ---------------------------------------------------------------------------
run_foreign "$FOREIGN"
[ "$RC" -eq 0 ] && ok "match-exits-zero" || nope "match-exits-zero" "$(echo "$OUT" | tail -4)"
echo "$OUT" | grep -q "Using running REPL" \
    && ok "match-uses-repl" || nope "match-uses-repl" "$(echo "$OUT" | tail -4)"
# The REPL-specific success line. Plain "compiles successfully" is also printed by
# the cold-start path, so it would pass on a silent fall-through to the slow path.
echo "$OUT" | grep -q "REPL check ~fast" \
    && ok "match-took-fast-path-to-the-end" \
    || nope "match-took-fast-path-to-the-end" "$(echo "$OUT" | tail -4)"
echo "$OUT" | grep -q "Cold JVM start" \
    && nope "match-did-not-cold-start" "fell through to cold start" \
    || ok "match-did-not-cold-start"

# ---------------------------------------------------------------------------
# Integration: the doubles above cannot prove we can still talk to a real nREPL,
# or that its answer parses. Only `lein` stays stubbed, to keep a mismatch from
# costing a 60s compile — the decision under test happens before that.
# ---------------------------------------------------------------------------
if ! command -v bb &>/dev/null || ! nc -z localhost $RUNNER_PORT 2>/dev/null; then
    echo "⏭️  live-REPL integration: nothing on $RUNNER_PORT (or no bb) — skipping"
else
    echo "--- live REPL: the real query parses, and the verdict matches the real root ---"
    REAL_ROOT="$(TIMEOUT=15 "$REPO_ROOT/dev/ai-eval.sh" runner $RUNNER_PORT \
                 '(System/getProperty "user.dir")' 2>/dev/null \
                 | grep -o '"/[^"]*"' | tail -1 | tr -d '"')"
    if [ -z "$REAL_ROOT" ]; then
        nope "live-repl-reports-a-root" "a live REPL answered nothing parseable"
    else
        ok "live-repl-reports-a-root"
        OUT="$(cd "$REPO_ROOT" && PATH="$FOREIGN/bin-leinonly:$PATH" ./dev/check-ai.sh 5 2>&1)" || true
        if [ "$REAL_ROOT" = "$REPO_ROOT" ]; then
            echo "$OUT" | grep -q "Using running REPL" \
                && ok "live-repl-same-tree-uses-fast-path" \
                || nope "live-repl-same-tree-uses-fast-path" "$(echo "$OUT" | tail -4)"
        else
            echo "$OUT" | grep -qF "REPL reads: $REAL_ROOT" \
                && ok "live-repl-other-tree-is-refused" \
                || nope "live-repl-other-tree-is-refused" "$(echo "$OUT" | tail -6)"
            echo "$OUT" | grep -q "Using running REPL" \
                && nope "live-repl-other-tree-not-trusted" "trusted a real foreign REPL" \
                || ok "live-repl-other-tree-not-trusted"
        fi
    fi
fi

echo ""
echo "Passed: $PASS   Failed: $FAIL"
[ "$FAIL" -eq 0 ] || exit 1
echo "✅ check-ai worktree guard: all assertions passed"
