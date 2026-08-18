#!/usr/bin/env bash
# send_command_help_test.sh — the help text must be TEXT, not shell.
#
# Why this exists: the role help blocks are plain double-quoted assignments, so
# an unescaped " inside them CLOSES the string and hands the rest to the shell.
# Adding the line that documents quoting "R&D" (#110 §3) did exactly that: the
# inner quotes ended the assignment, the shell saw a bare &, and `runner help`
# printed
#
#     ./dev/send_command: line 229: D, Archives, remote1, etc.)
#                        QUOTE R: command not found
#
# instead of the Running section. Nothing caught it but running the command by
# hand — the same class of failure as the EDN-tail leak next door, and shipped
# for the same reason: no committed test. This is that test.
#
# It renders every help variant and asserts (a) no shell diagnostics leak into
# the output, and (b) the sections a seat actually needs are present.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

PASS=0
FAIL=0

fail() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }
pass() { echo "  ✓ $1"; PASS=$((PASS + 1)); }

# A rendered help must never contain shell DIAGNOSTICS. The discriminator has to
# be precise, because the help text legitimately QUOTES an error message: the
# R&D note says  'command not found: D'  on purpose. So match the shape the
# shell actually emits — "<word>: command not found" (trailing) — not the phrase
# itself, which appears leading-and-quoted in our documentation.
assert_no_shell_errors() {
    local label="$1" out="$2"
    if [[ -z "$out" ]]; then
        fail "$label: produced NO output (broken invocation, not a pass)"
        return
    fi
    local bad=""
    grep -qE ': command not found' <<<"$out" && bad+="command-not-found "
    grep -qE ': No such file or directory' <<<"$out" && bad+="no-such-file "
    grep -qE 'send_command: line [0-9]+' <<<"$out" && bad+="bash-line-diagnostic "
    grep -qE 'unexpected (EOF|token)' <<<"$out" && bad+="parse-error "
    if [[ -n "$bad" ]]; then
        fail "$label: shell leaked into help output [$bad]"
        echo "$out" | head -8 | sed 's/^/      /'
    else
        pass "$label: renders as text"
    fi
}

assert_contains() {
    local label="$1" out="$2" needle="$3"
    if grep -qF -- "$needle" <<<"$out"; then
        pass "$label: contains '$needle'"
    else
        fail "$label: missing '$needle'"
    fi
}

echo "Rendering every help variant from $SEND_CMD"
echo

for role in runner corp ""; do
    label="${role:-generic}"
    out="$("$SEND_CMD" $role help 2>&1)"
    assert_no_shell_errors "$label help" "$out"
done

echo
echo "Full help (--full)"
full="$("$SEND_CMD" help --full 2>&1)"
assert_no_shell_errors "full help" "$full"

echo
echo "Content the seats depend on"

# #110 §1: continue-run is a real command in the dispatcher allowlist. If help
# doesn't document it, a seat told to run it (as the run-pause hint used to say)
# has no way to learn what it does.
assert_contains "full help" "$full" "continue-run"
assert_contains "full help" "$full" "monitor-run"

# #110 §2: the per-credit payment escape hatch.
assert_contains "full help" "$full" "--all"

# #110 §3: the quoting note must survive rendering in the runner brief — this is
# the exact string whose inner quotes broke the script.
runner_help="$("$SEND_CMD" runner help 2>&1)"
assert_contains "runner help" "$runner_help" '"R&D"'
assert_contains "runner help" "$runner_help" "command not found: D"

echo
echo "─────────────────────────────"
echo "Passed: $PASS   Failed: $FAIL"
[[ $FAIL -eq 0 ]] || exit 1
