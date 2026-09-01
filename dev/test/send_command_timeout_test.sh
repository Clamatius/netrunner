#!/usr/bin/env bash
# send_command_timeout_test.sh — regression guard for #190 and for the trap that
# fixing #190 opens.
#
# #190: dev/ai-eval.sh read TIMEOUT into a variable that ONLY the lein fallback
# used, and check-ai.sh's warm path is gated on `command -v bb` — the same test
# that selects ai-eval.sh's bb branch. So whenever check-ai.sh was the caller,
# the branch that honours TIMEOUT was unreachable. The bencode read is blocking,
# and a wedged REPL hung `make check` forever with no message.
#
# The trap: the timeout was inert, so nothing enforced that callers' TIMEOUTs
# were sane. `monitor-run --persistent` — the un-babysat marquee workhorse, which
# parks for 300s and can hold an active run for 300s more — ran under
# send_command's 20s default. Honouring TIMEOUT without raising those would have
# killed a HEALTHY monitor after 20 seconds: a worse bug than the hang.
#
# So both halves are pinned here. Neither is visible from the other file.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"
AI_EVAL="$SCRIPT_DIR/../ai-eval.sh"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-timeout-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats"

fails=0
ok()   { echo "ok   [$1] $2"; }
fail() { echo "FAIL [$1]: $2"; fails=$((fails+1)); }

# ---------------------------------------------------------------------------
# Half 1: ai-eval.sh actually bounds the bb branch.
# ---------------------------------------------------------------------------

# A stub REPL that accepts the connection and never answers — the wedge #190 is
# about. `nc -l` holds the socket open with no bencode reply.
PORT=0
for candidate in $(seq 47311 47360); do
    if ! nc -z localhost "$candidate" 2>/dev/null; then PORT="$candidate"; break; fi
done
if [[ "$PORT" == "0" ]]; then
    fail "wedged-repl" "could not find a free port to host the stub REPL"
else
    # A listener that ACCEPTS and never answers. `sleep` holds nc's stdin open so
    # nc does not half-close the socket — an immediate EOF would be a *closed*
    # connection, which bb already errors on, and would test nothing.
    sleep 60 | nc -l "$PORT" > /dev/null 2>&1 &
    NC_PID=$!
    sleep 0.5
    START=$(date +%s)
    OUT=$(TIMEOUT=2 TIMEOUT_GRACE=1 timeout 25 "$AI_EVAL" wedged "$PORT" '(+ 1 1)' 2>&1)
    CODE=$?
    ELAPSED=$(( $(date +%s) - START ))
    kill "$NC_PID" 2>/dev/null; pkill -P "$NC_PID" 2>/dev/null

    # ORDER MATTERS. The outer `timeout 25` stands in for "forever", and it exits
    # 124 too — so a non-zero exit is only evidence of anything once we know the
    # OUTER kill is not what produced it. Checked first, and the exit-code check
    # is skipped when it did, rather than printing a green line for the bug.
    # (2s budget + 1s grace; generous slack for bb start-up.)
    if [[ "$ELAPSED" -ge 20 ]]; then
        fail "wedged-repl" "took ${ELAPSED}s — the outer kill fired, so ai-eval.sh did not bound it"
    else
        ok "wedged-repl" "returned in ${ELAPSED}s (TIMEOUT=2 + 1s grace)"
        if [[ "$CODE" -eq 0 ]]; then
            fail "wedged-repl" "a REPL that never answered returned success (exit 0)"
        else
            ok "wedged-repl" "non-zero exit ($CODE) instead of hanging"
        fi
    fi
    if [[ "$OUT" == *"wedged"* ]]; then
        ok "wedged-repl" "says the REPL is wedged rather than failing silently"
    else
        fail "wedged-repl" "no diagnosis in output. Got: $OUT"
    fi
fi

# The grace is the whole reason a 300s `wait` is not raced by a 300s shell kill.
# Pin that it exists and is added, not that it has a particular value.
if grep -qE '^KILL_AFTER=\$\(\(TIMEOUT \+ TIMEOUT_GRACE\)\)' "$AI_EVAL"; then
    ok "grace" "the shell budget is TIMEOUT + grace, so the REPL's own deadline wins"
else
    fail "grace" "KILL_AFTER is no longer TIMEOUT + TIMEOUT_GRACE — a caller whose
       REPL deadline equals its TIMEOUT is now a coin flip"
fi
if grep -q 'timeout "\$KILL_AFTER" bb -e' "$AI_EVAL"; then
    ok "bb-bounded" "the bb branch is wrapped in timeout"
else
    fail "bb-bounded" "the bb branch is not wrapped in timeout — #190 is back"
fi

# ---------------------------------------------------------------------------
# Half 2: the blocking commands get a budget above their REPL-side ceiling.
#
# Drive the REAL dispatcher with a stub backend that records the TIMEOUT it was
# handed. This is the half that cannot be read off either file: it is a property
# of the case blocks in send_command.
# ---------------------------------------------------------------------------

STUB="$TMP/stub-eval.sh"
cat > "$STUB" <<'STUBEOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then
    expr="$(cat)"
else
    expr="${!#}"
fi
# ensure_connection's probes set their own small TIMEOUT and are not the subject
# here; record only the budget the COMMAND itself ran under.
case "$expr" in
    *ensure-connected!*|*sync-verdict!*) ;;
    *) printf '%s\n%s\n' "${TIMEOUT:-unset}" "$expr" > "$STUB_TIMEOUT_FILE" ;;
esac
printf '"stub"\n'
STUBEOF
chmod +x "$STUB"

# The REPL-side ceiling these must clear. Read from the source rather than
# retyped, so a change to the loop budget fails here instead of drifting.
CEILING=$(grep -oE 'timeout-ms 300000' "$SCRIPT_DIR/../src/clj/ai_runs.clj" | head -1)
if [[ -z "$CEILING" ]]; then
    fail "ceiling" "could not find auto-continue-loop!'s :timeout-ms in ai_runs.clj —
       the 300s figure this test compares against may have moved"
fi
CEILING_SECS=300

timeout_for() {
    STUB_TIMEOUT_FILE="$TMP/timeout.$RANDOM"
    : > "$STUB_TIMEOUT_FILE"
    export STUB_TIMEOUT_FILE
    AI_EVAL="$STUB" "$SEND_CMD" corp "$@" >/dev/null 2>&1
    head -1 "$STUB_TIMEOUT_FILE"
}

expr_for() {
    STUB_TIMEOUT_FILE="$TMP/timeout.$RANDOM"
    : > "$STUB_TIMEOUT_FILE"
    export STUB_TIMEOUT_FILE
    AI_EVAL="$STUB" "$SEND_CMD" corp "$@" >/dev/null 2>&1
    tail -n +2 "$STUB_TIMEOUT_FILE"
}

assert_blocking() {
    local name="$1"; shift
    local t; t=$(timeout_for "$@")
    if [[ -z "$t" || "$t" == "unset" ]]; then
        fail "$name" "the stub never saw the command (dispatch changed?) — got '$t'"
    elif [[ "$t" -le "$CEILING_SECS" ]]; then
        fail "$name" "runs under TIMEOUT=${t}s, but its REPL side can block for
       ${CEILING_SECS}s or more. A healthy invocation would be killed mid-flight.
       Set TIMEOUT=\"\$REPL_BLOCKING_TIMEOUT\" in this command's case block."
    else
        ok "$name" "TIMEOUT=${t}s clears the ${CEILING_SECS}s REPL ceiling"
    fi
}

assert_blocking "continue"     continue
assert_blocking "monitor-run"  monitor-run
assert_blocking "run"          run "HQ"
assert_blocking "find-card"    find-card "Hedge Fund"
assert_blocking "draw-to-card" draw-to-card "Hedge Fund"

# `wait` is the one command whose shell budget legitimately EQUALS its REPL
# deadline: it passes the seat's number both to the REPL and to ai-eval.sh. That
# is a coin flip WITHOUT the grace above and correct WITH it, so what is pinned
# here is the equality (the grace check upstream covers the rest).
WAIT_T=$(timeout_for wait 42)
WAIT_EXPR=$(expr_for wait 42)
if [[ "$WAIT_T" != "42" ]]; then
    fail "wait" "asked for a 42s wait but ran the shell under TIMEOUT=${WAIT_T}s"
elif [[ "$WAIT_EXPR" != *"42"* ]]; then
    fail "wait" "asked for a 42s wait but the REPL was sent: $WAIT_EXPR"
else
    ok "wait" "shell budget matches the deadline handed to the REPL (grace decides the race)"
fi

# ...and the control: a plain read-only command must NOT inherit the long budget,
# or a wedged REPL hangs `status` for half an hour.
STATUS_T=$(timeout_for status)
if [[ -z "$STATUS_T" || "$STATUS_T" == "unset" ]]; then
    fail "status" "the stub never saw 'status' (dispatch changed?)"
elif [[ "$STATUS_T" -gt "$CEILING_SECS" ]]; then
    fail "status" "a non-blocking command runs under TIMEOUT=${STATUS_T}s — the
       backstop has been widened past the commands that need it"
else
    ok "status" "stays on the short budget (${STATUS_T}s)"
fi

if [[ "$fails" -gt 0 ]]; then
    echo "❌ $fails failure(s)"
    exit 1
fi
echo "✅ send_command/ai-eval timeout guards passed."
