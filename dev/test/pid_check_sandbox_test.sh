#!/usr/bin/env bash
# pid_check_sandbox_test.sh — a `ps` that cannot RUN is not a dead REPL.
#
# Why this exists: model seats launched via `askmodel --write` run in codex's
# workspace-write sandbox, where macOS seatbelt denies `ps` outright ("operation
# not permitted"). The eval scripts read any `ps -p` failure as "process gone",
# printed "not running (stale PID file)", DELETED the pid file and exited 1 — so
# a live REPL looked dead to the seat, which stopped and pinged the umpire on its
# first command (SG Constructed scouting game, 2026-09-23). Only ps's own
# "no such process" (exit 1) means stale; 126/127 mean ps never ran.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEV="$SCRIPT_DIR/.."
PASS=0; FAIL=0
fail() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }
pass() { echo "  ✓ $1"; PASS=$((PASS + 1)); }

STUB=$(mktemp -d)
NAME="pidtest-$$"
PIDFILE="/tmp/ai-client-${NAME}.pid"
trap 'rm -rf "$STUB" "$PIDFILE"' EXIT

run_with_ps_exit() {   # $1 = ps exit code, $2 = script
    printf '#!/bin/sh\nexit %s\n' "$1" > "$STUB/ps"; chmod +x "$STUB/ps"
    echo 99999 > "$PIDFILE"
    # Port 1: nothing listens, so a script that gets past the pid check fails
    # fast on the connection — we only care what the pid check decided.
    PATH="$STUB:$PATH" TIMEOUT=3 "$DEV/$2" "$NAME" 1 '(+ 1 1)' 2>&1
}

for script in ai-eval.sh ai-lein-eval.sh; do
    echo "$script"
    for code in 126 127; do
        out=$(run_with_ps_exit "$code" "$script")
        if grep -q "stale PID file" <<<"$out"; then
            fail "ps exit $code (could not run): reported a stale PID file"
        else
            pass "ps exit $code (could not run): no stale-PID verdict"
        fi
        [[ -f "$PIDFILE" ]] && pass "ps exit $code: pid file kept" || fail "ps exit $code: pid file DELETED"
    done
    out=$(run_with_ps_exit 1 "$script")
    grep -q "stale PID file" <<<"$out" && pass "ps exit 1 (no such process): still stale" \
                                       || fail "ps exit 1 (no such process): stale verdict lost"
done

echo "─────────────────────────────"
echo "Passed: $PASS   Failed: $FAIL"
[[ $FAIL -eq 0 ]]
