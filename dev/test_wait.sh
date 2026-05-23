#!/bin/bash
# test_wait.sh — integration harness for wait-* commands.
#
# Runs a wait command via send_command, capturing high-resolution start/end
# timestamps + the command's raw output to a unique log file. Lets you verify
# that a wait wakes when (and only when) expected, without running a full game.
#
# Designed to be backgrounded by the caller (& or Bash tool's
# run_in_background). The first line printed to stdout is the absolute path of
# the log file so the caller knows where to tail.
#
# Usage:
#   ./dev/test_wait.sh <side> <wait-cmd> [args...]
#
# Examples:
#   ./dev/test_wait.sh corp wait-my-turn
#   ./dev/test_wait.sh runner wait-for-relevant-diff 30
#   ./dev/test_wait.sh corp wait-run
#
# Backgrounded:
#   ./dev/test_wait.sh corp wait-my-turn &
#   # (now drive state changes from the foreground and watch the log)
#
# Log format:
#   === test_wait start: side=corp cmd='wait-my-turn' at=2026-05-23T12:00:00.123Z ===
#   ... raw stdout/stderr from the wait command ...
#   === test_wait end:   side=corp exit=0 elapsed=12.345s at=2026-05-23T12:00:12.469Z ===

set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SEND_CMD="$SCRIPT_DIR/send_command"

if [[ $# -lt 2 ]]; then
    echo "Usage: $0 <corp|runner> <wait-cmd> [args...]" >&2
    echo "Example: $0 corp wait-my-turn" >&2
    exit 2
fi

SIDE="$1"
shift
WAIT_CMD="$1"
shift

LOG_DIR="/tmp/netrunner-test-wait"
mkdir -p "$LOG_DIR"
TS=$(date +%s)
LOG_FILE="$LOG_DIR/${SIDE}-${WAIT_CMD}-${TS}-$$.log"

# Print log path first so caller can find it even before the wait returns
echo "$LOG_FILE"

{
    echo "=== test_wait start: side=$SIDE cmd='$WAIT_CMD $*' at=$(perl -MTime::HiRes=time -e 'use POSIX qw(strftime); my $t = time; printf("%s.%03dZ", strftime("%Y-%m-%dT%H:%M:%S", gmtime($t)), ($t - int($t)) * 1000)') ==="
    start=$(perl -MTime::HiRes=time -e 'printf("%.6f\n", time)')
    "$SEND_CMD" "$SIDE" "$WAIT_CMD" "$@"
    ec=$?
    end=$(perl -MTime::HiRes=time -e 'printf("%.6f\n", time)')
    elapsed=$(perl -e "printf(\"%.3f\", $end - $start)")
    echo "=== test_wait end:   side=$SIDE exit=$ec elapsed=${elapsed}s at=$(perl -MTime::HiRes=time -e 'use POSIX qw(strftime); my $t = time; printf("%s.%03dZ", strftime("%Y-%m-%dT%H:%M:%S", gmtime($t)), ($t - int($t)) * 1000)') ==="
} >> "$LOG_FILE" 2>&1
