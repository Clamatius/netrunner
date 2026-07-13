#!/usr/bin/env bash
# peer_status_test.sh — regression guard for dead-peer detection (heartbeat).
#
# Why this exists: an un-babysat cross-model game silently wedges when a guest
# seat's DRIVER dies (e.g. devin one-shot exit) while its REPL stays alive — from
# the other seat, an empty `wait` is indistinguishable from "opponent thinking".
# send_command now touches a per-side heartbeat on every invocation and reports
# the opponent's last-active age (`peer-status`, and a footer on wait/monitor-run).
# This test locks that logic. `peer-status` needs NO live REPL (reads local files
# only), so the whole flow is testable in isolation.
#
# Heartbeats are isolated to a temp dir via HEARTBEAT_DIR so the test never
# touches the real logs/.heartbeats.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

export HEARTBEAT_DIR
HEARTBEAT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nr-heartbeat-test.XXXXXX")"
trap 'rm -rf "$HEARTBEAT_DIR"' EXIT

fails=0
assert_contains() {  # NAME  HAYSTACK  NEEDLE
    local name="$1" hay="$2" needle="$3"
    if [[ "$hay" == *"$needle"* ]]; then
        echo "ok   [$name]"
    else
        echo "FAIL [$name]: expected to contain '$needle', got:"; printf '%s\n' "$hay" | sed 's/^/    /'
        fails=$((fails+1))
    fi
}
assert_not_contains() {  # NAME  HAYSTACK  NEEDLE
    local name="$1" hay="$2" needle="$3"
    if [[ "$hay" != *"$needle"* ]]; then
        echo "ok   [$name]"
    else
        echo "FAIL [$name]: expected NOT to contain '$needle', got:"; printf '%s\n' "$hay" | sed 's/^/    /'
        fails=$((fails+1))
    fi
}

# 1. Cold start: runner has never acted → corp sees "no heartbeat yet".
OUT=$("$SEND_CMD" corp peer-status 2>&1)
assert_contains "cold-start-no-heartbeat" "$OUT" "opponent (runner): no heartbeat yet"
assert_contains "self-heartbeat-touched"  "$OUT" "you (corp): active"

# 2. Runner acts (peer-status touches its own side) → corp now sees runner alive.
"$SEND_CMD" runner peer-status >/dev/null 2>&1
OUT=$("$SEND_CMD" corp peer-status 2>&1)
assert_contains "fresh-peer-alive" "$OUT" "opponent (runner): active"
assert_not_contains "fresh-peer-not-silent" "$OUT" "SILENT"

# 3. Dead peer: backdate runner heartbeat past the threshold → "SILENT / disconnected".
BACKDATE="$(date -v-6M +%Y%m%d%H%M 2>/dev/null || date -d '6 min ago' +%Y%m%d%H%M)"
touch -t "$BACKDATE" "$HEARTBEAT_DIR/runner"
OUT=$(PEER_STALE_SECS=300 "$SEND_CMD" corp peer-status 2>&1)
assert_contains "stale-peer-flagged"      "$OUT" "SILENT"
assert_contains "stale-peer-disconnected" "$OUT" "likely disconnected"

# 4. Symmetry: from the runner's seat the opponent is the corp.
OUT=$("$SEND_CMD" runner peer-status 2>&1)
assert_contains "symmetry-opponent-is-corp" "$OUT" "opponent (corp)"

# 5. Threshold is honoured: a 6-min-old heartbeat is NOT stale under a 3600s bar.
OUT=$(PEER_STALE_SECS=3600 "$SEND_CMD" corp peer-status 2>&1)
assert_not_contains "threshold-respected" "$OUT" "SILENT"

# 6. clear-heartbeats zeroes the liveness clock (fresh-game path). Without this, a
#    heartbeat left over from a prior game (or a setup touch) reads as "alive" and
#    masks a guest driver that never actually connected — a FALSE-alive at exactly
#    the moment the sensor matters. reset.sh calls this so a new game starts honest.
"$SEND_CMD" corp peer-status >/dev/null 2>&1   # ensure both sides have heartbeats
"$SEND_CMD" runner peer-status >/dev/null 2>&1
[[ -f "$HEARTBEAT_DIR/corp" && -f "$HEARTBEAT_DIR/runner" ]] \
    && echo "ok   [pre-clear-heartbeats-exist]" \
    || { echo "FAIL [pre-clear-heartbeats-exist]: heartbeat files missing before clear"; fails=$((fails+1)); }
CLR=$("$SEND_CMD" clear-heartbeats 2>&1)
assert_contains "clear-reports-done" "$CLR" "cleared"
[[ ! -f "$HEARTBEAT_DIR/corp" && ! -f "$HEARTBEAT_DIR/runner" ]] \
    && echo "ok   [post-clear-heartbeats-gone]" \
    || { echo "FAIL [post-clear-heartbeats-gone]: heartbeat files still present after clear"; fails=$((fails+1)); }
# After a clear, the opponent honestly reads as "not seen acting" (not a ghost alive).
OUT=$("$SEND_CMD" corp peer-status 2>&1)
assert_contains "post-clear-honest" "$OUT" "opponent (runner): no heartbeat yet"
assert_not_contains "post-clear-not-ghost-alive" "$OUT" "opponent (runner): active"

# 7. Wiring: the fresh-game path (reset.sh) actually clears heartbeats, else the
#    primitive above is dead code and the false-alive survives in real games.
assert_contains "reset-wires-clear" "$(cat "$SCRIPT_DIR/../reset.sh")" "clear-heartbeats"

# 8. Wiring: the `wait` handler suppresses the peer footer on a game-over wake, so
#    the "alive, keep waiting" footer can't contradict the "stop acting" game-over
#    line (verified behaviorally against a live game-over state; this guards the
#    suppression against regression-by-deletion since the shell harness has no
#    live in-progress game to drive a wait through).
SEND_SRC="$(cat "$SEND_CMD")"
assert_contains "wait-suppresses-footer-on-gameover" "$SEND_SRC" 'WAIT_OUT" != *"Game over"*'

# ── Keep-alive toucher (park mode, #31 Fix A) ────────────────────────────────
# `monitor-run --persistent` now PARKS for the opponent's whole turn, so one
# invocation can stay open for minutes. The heartbeat is touched at invocation,
# so without a keep-alive an ALIVE parked driver would age past PEER_STALE_SECS
# and be reported SILENT — a false dead-peer verdict telling the opponent to
# abandon a live game. send_command therefore runs a background toucher for the
# life of the invocation.
#
# The DANGEROUS failure mode of that toucher is the mirror image: if it outlives
# its parent, it keeps a DEAD driver's heartbeat fresh forever and the sensor can
# never report a real disconnect. It is bound to the parent via `kill -0`, which
# holds even under SIGKILL (where no EXIT trap runs). Both are asserted here.

# 1. No orphan: once the invocation exits, the heartbeat must STOP advancing.
HEARTBEAT_TOUCH_SECS=1 "$SEND_CMD" corp peer-status >/dev/null 2>&1 || true
before_mt="$(stat -f %m "$HEARTBEAT_DIR/corp" 2>/dev/null || stat -c %Y "$HEARTBEAT_DIR/corp" 2>/dev/null || echo 0)"
sleep 3
after_mt="$(stat -f %m "$HEARTBEAT_DIR/corp" 2>/dev/null || stat -c %Y "$HEARTBEAT_DIR/corp" 2>/dev/null || echo 0)"
if [[ "$before_mt" == "$after_mt" ]]; then
    echo "ok   [no-orphan-toucher-after-exit]"
else
    echo "FAIL [no-orphan-toucher-after-exit]: heartbeat kept advancing after send_command exited"
    echo "     (an orphaned toucher would keep a DEAD driver looking alive forever)"
    fails=$((fails+1))
fi

# 2. A SIGKILLed driver still goes stale: the toucher's parent-liveness guard is
#    `kill -0`, not an EXIT trap, so it must not survive a kill -9 of the parent.
assert_contains "toucher-bound-to-parent-liveness" "$SEND_SRC" 'kill -0 "$_hb_parent"'

# 3. The toucher must NOT hold the caller's stdout pipe open.
#    Nearly every caller reads send_command via command substitution, which blocks
#    until EOF — and EOF needs the LAST writer to close. The first cut of the
#    toucher inherited stdout, so `OUT="$(send_command corp peer-status)"` blocked
#    for the full HEARTBEAT_TOUCH_SECS (measured: 60s) on EVERY command. The unit
#    assertions above still passed — just slowly — so nothing but a TIMING check
#    catches this. Uses the default touch interval on purpose: overriding it to
#    something small is exactly what masked the bug.
cs_start=$(date +%s)
CS_OUT="$("$SEND_CMD" corp peer-status 2>&1)"
cs_elapsed=$(( $(date +%s) - cs_start ))
if [[ $cs_elapsed -lt 10 ]]; then
    echo "ok   [cmd-substitution-not-blocked-by-toucher] (${cs_elapsed}s)"
else
    echo "FAIL [cmd-substitution-not-blocked-by-toucher]: took ${cs_elapsed}s"
    echo "     The heartbeat toucher is holding stdout open — every send_command read"
    echo "     via \$(...) blocks until it exits. Redirect the subshell's stdio."
    fails=$((fails+1))
fi
assert_contains "cmd-substitution-still-works" "$CS_OUT" "you (corp): active"

echo "---"
if [[ $fails -eq 0 ]]; then
    echo "peer_status_test: ALL PASSED"; exit 0
else
    echo "peer_status_test: $fails FAILED"; exit 1
fi
