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

echo "---"
if [[ $fails -eq 0 ]]; then
    echo "peer_status_test: ALL PASSED"; exit 0
else
    echo "peer_status_test: $fails FAILED"; exit 1
fi
