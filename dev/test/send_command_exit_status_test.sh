#!/usr/bin/env bash
# send_command_exit_status_test.sh — regression guard for #250.
#
# On the corp/runner paths send_command installs a heartbeat EXIT trap whose
# last command succeeds. Under macOS /bin/bash 3.2 that trap OVERWRITES the
# script's exit status, so a `set -u` expansion error (a typo'd variable) exits
# 0 while printing "unbound variable" — automation reads a crash as success.
# bash 5 keeps the status. The fix is the shebang (#!/usr/bin/env bash), so this
# test runs the script THROUGH ITS OWN SHEBANG: a copy with an unbound read
# injected just after the trap must exit non-zero.
#
# It also goes red on a box whose `env bash` is still 3.2 — which is the point:
# that box has the bug.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-exit-status-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats"
export SEND_COMMAND_LOG="$TMP/commands.log"

fails=0
ok()   { echo "ok   [$1] $2"; }
fail() { echo "FAIL [$1]: $2"; fails=$((fails+1)); }

TRAP_LINE="trap 'kill \"\$_hb_toucher\" 2>/dev/null || true' EXIT"
PROBE=': "$NR_250_DEFINITELY_UNSET"'

echo "--- the heartbeat trap is where the test expects it ---"
if grep -qF "$TRAP_LINE" "$SEND_CMD"; then
  ok "trap-present" "send_command still installs the heartbeat EXIT trap"
else
  fail "trap-present" "heartbeat EXIT trap not found; update TRAP_LINE (if the trap is gone, this test's premise is too)"
fi

# The copy lives in its own dir so its SCRIPT_DIR-relative paths point at
# nothing real; everything before the probe only builds path strings.
mkdir -p "$TMP/dev"
awk -v t="$TRAP_LINE" -v p="$PROBE" '{print} index($0, t) {print "    " p}' \
  "$SEND_CMD" > "$TMP/dev/send_command"
chmod +x "$TMP/dev/send_command"

echo "--- the probe was actually injected (else the test is vacuous) ---"
if grep -qF "$PROBE" "$TMP/dev/send_command"; then
  ok "probe-injected" ""
else
  fail "probe-injected" "awk did not insert the unbound read after the trap"
fi

echo "--- an unbound variable after the trap exits non-zero (#250) ---"
unset NR_250_DEFINITELY_UNSET
for side in corp runner; do
  err="$("$TMP/dev/send_command" "$side" status 2>&1 >/dev/null)"; rc=$?
  if ! grep -q 'NR_250_DEFINITELY_UNSET: unbound variable' <<<"$err"; then
    fail "probe-reached-$side" "the probe never ran (rc=$rc); stderr: $err"
  elif [[ $rc -eq 0 ]]; then
    fail "unbound-exits-nonzero-$side" "crashed on an unbound variable but exited 0 — the EXIT trap ate the status (bash: $(head -1 "$SEND_CMD"))"
  else
    ok "unbound-exits-nonzero-$side" "rc=$rc"
  fi
done

echo
if [[ $fails -eq 0 ]]; then
  echo "✅ send_command exit status: all assertions passed"
else
  echo "❌ send_command exit status: $fails assertion(s) failed"
  exit 1
fi
