#!/usr/bin/env bash
# client_repl_port_default_test.sh — `start-ai-client-repl.sh corp` must not
# start the corp client on the RUNNER's port (#309).
#
# Why this exists. The whole of the script's port logic was:
#
#     CLIENT_NAME=${1:-fixed-id}
#     REPL_PORT=${2:-${CLIENT_1_PORT:-7889}}
#
# with no corp branch anywhere in the file, so the one-argument form resolved
# to the runner's port for every client name. With the runner already up on
# 7889 the damage is not a failure to start, it is a SUCCESSFUL-LOOKING start:
# the readiness probe (`lsof -i:7889`) is satisfied instantly by the runner's
# own listener, so the script announces "✅ nREPL server is listening", writes
# the runner's java pid into /tmp/ai-client-corp.pid - two pid files now naming
# one process - and load-file's ai_client_init.clj into the RUNNER's REPL under
# the name "corp". No corp REPL was ever started.
#
# Nothing noticed because the only in-tree caller, start-ai-both.sh, passes
# both arguments. The one-argument form is what a human or a model seat types,
# and it is what the script's own usage line and dev/README.md invite.
#
# HOW it tests, and why only a prefix of the script runs. The port decision is
# top-level wiring, so there is no function to call - but the rest of the
# script cannot be run here either: it writes /tmp/ai-client-<name>.log and
# .pid at hard-coded, machine-wide paths (#293) and then drives the real
# dev/ai-eval.sh against the resolved port. With CLIENT_NAME=corp during a live
# game that would truncate the running corp REPL's log and load code into a
# REPL a marquee match is using.
#
# So the test runs the script verbatim UP TO its first side effect (the `nohup`
# that launches lein) and reads the port out of the announcement the script
# prints before it. Everything that decides the port - the argument defaults,
# `source load-env.sh`, and the .env it reads - is real and runs in a real
# bash. The risk of a prefix is that the file gets restructured underneath it
# and the test passes vacuously, so the cut is asserted, not assumed: there
# must be exactly one `nohup` line, the announcement must come before it, and
# the prefix must still contain both the port assignment and the load-env
# source. If any of that stops holding, this fails instead of going quiet.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEV="$(cd "$SCRIPT_DIR/.." && pwd -P)"
REAL_SCRIPT="$DEV/start-ai-client-repl.sh"

PASS=0; FAIL=0
pass() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

[ -f "$REAL_SCRIPT" ] || { echo "client_repl_port_default_test: no $REAL_SCRIPT"; exit 1; }

echo "the cut this test depends on"
nohup_lines=$(grep -c '^nohup ' "$REAL_SCRIPT")
if [ "$nohup_lines" -eq 1 ]; then pass "exactly one 'nohup' line to cut at"
else fail "expected 1 line starting with 'nohup', found $nohup_lines — re-read the script, this test's cut is stale"; fi
cut_at=$(grep -n '^nohup ' "$REAL_SCRIPT" | head -1 | cut -d: -f1)
announce_at=$(grep -n 'Starting AI Client REPL for' "$REAL_SCRIPT" | head -1 | cut -d: -f1)
if [ -n "$announce_at" ] && [ -n "$cut_at" ] && [ "$announce_at" -lt "$cut_at" ]; then
    pass "the port announcement (:$announce_at) precedes the first side effect (:$cut_at)"
else
    fail "announcement at [$announce_at], first side effect at [$cut_at] — the port is no longer announced before the script touches anything"
fi

SANDBOX=$(mktemp -d)
trap 'rm -rf "$SANDBOX"' EXIT
mkdir -p "$SANDBOX/dev"
cp "$DEV/load-env.sh" "$SANDBOX/dev/load-env.sh"
if [ -n "$cut_at" ] && [ "$cut_at" -gt 1 ]; then
    sed -n "1,$((cut_at - 1))p" "$REAL_SCRIPT" > "$SANDBOX/dev/prefix.sh"
else
    : > "$SANDBOX/dev/prefix.sh"
fi
chmod +x "$SANDBOX/dev/prefix.sh"
grep -q 'REPL_PORT=' "$SANDBOX/dev/prefix.sh" \
    && pass "the prefix still contains the REPL_PORT assignment" \
    || fail "the prefix has no REPL_PORT assignment — the decision moved below the cut"
grep -q 'load-env.sh' "$SANDBOX/dev/prefix.sh" \
    && pass "the prefix still sources load-env.sh" \
    || fail "the prefix no longer sources load-env.sh — .env is not being read where this test reads it"

# Run the prefix and report the port it announced, or NONE.
#   $1 = .env contents, or NO_ENV_FILE; $2.. = the script's own arguments
announced_port() {
    if [ "$1" = NO_ENV_FILE ]; then rm -f "$SANDBOX/.env"; else printf '%s\n' "$1" > "$SANDBOX/.env"; fi
    shift
    env -i PATH="$PATH" HOME="$HOME" bash "$SANDBOX/dev/prefix.sh" "$@" 2>/dev/null \
      | sed -n 's/^Starting AI Client REPL for .* on port \([0-9][0-9]*\)\.\.\.$/\1/p' \
      | head -1 \
      | grep . || echo NONE
}

assert_port() {  # label, expected, env-contents, args...
    label="$1"; expect="$2"; shift 2
    got=$(announced_port "$@")
    [ "$got" = "$expect" ] && pass "$label" || fail "$label: announced port $got, expected $expect"
}

echo
echo "no .env — the built-in per-side defaults"
assert_port "corp with no port argument gets the CORP port"     7890 NO_ENV_FILE corp
assert_port "runner with no port argument gets the runner port" 7889 NO_ENV_FILE runner
assert_port "no arguments at all keeps the legacy 7889"         7889 NO_ENV_FILE

echo
echo "an explicit port argument always wins"
assert_port "corp 9999"   9999 NO_ENV_FILE corp 9999
assert_port "runner 9998" 9998 NO_ENV_FILE runner 9998

echo
echo ".env — each side reads its OWN key"
ENV_BOTH='CLIENT_1_PORT=8889
CLIENT_2_PORT=8890'
assert_port "corp reads CLIENT_2_PORT"   8890 "$ENV_BOTH" corp
assert_port "runner reads CLIENT_1_PORT" 8889 "$ENV_BOTH" runner
# The bug's signature: a .env that moves only the runner must not move corp.
assert_port "corp is unmoved by a runner-only .env" 7890 'CLIENT_1_PORT=8889' corp

echo
echo "─────────────────────────────"
echo "client_repl_port_default_test: passed $PASS, failed $FAIL"
[ "$FAIL" -eq 0 ]
