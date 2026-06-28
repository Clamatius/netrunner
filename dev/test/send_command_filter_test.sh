#!/usr/bin/env bash
# send_command_filter_test.sh — regression guard for the EDN-tail noise filter.
#
# Why this exists: dev/send_command strips the REPL's machine return-map from
# user-facing output (we print human-readable messages instead). This filter has
# regressed twice via too-narrow regexes:
#   - the original only matched maps LEADING with :status   (PR before #34)
#   - #34's `^\{[^}]*:status` stopped at the first nested "}", so a decision map
#     whose :status sits AFTER a nested map (e.g. {:prompt {:eid {...}} :status …})
#     LEAKED — caught live in the cross-model marquee (forum ai-netrunner [125]).
# Each regression shipped because there was no committed test. This is that test.
#
# It runs the captured real-world cases through the ACTUAL regex extracted from
# dev/send_command, so the test cannot silently drift from the source.
#
# Inputs are passed as function ARGUMENTS (not here-strings) so the test does not
# depend on temp-file creation, and every assertion guards that its input is
# non-empty — so a broken input path fails loudly instead of false-passing.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

# Extract the live filter regex from send_command (the pattern inside grep -vE '...').
PATTERN=$(grep -oE "grep -vE '\^\\\\\{[^']*'" "$SEND_CMD" | head -1 | sed -E "s/^grep -vE '//; s/'$//")
if [[ -z "$PATTERN" ]]; then
    echo "FAIL: could not extract EDN filter regex from $SEND_CMD" >&2
    exit 1
fi
echo "Using live filter regex from send_command: $PATTERN"

# Apply the real filter exactly as send_command does. Input arrives on stdin.
filt() { grep -vE "$PATTERN" | grep -v '^nil$' || true; }

fails=0
# assert_empty NAME INPUT  → filter must strip everything to empty
assert_empty() {
    local name="$1" input="$2" out
    if [[ -z "$input" ]]; then echo "FAIL [$name]: test input was empty (broken setup)"; fails=$((fails+1)); return; fi
    out=$(printf '%s\n' "$input" | filt)
    if [[ -n "$out" ]]; then
        echo "FAIL [$name]: expected STRIP, but leaked:"; printf '%s\n' "$out" | sed 's/^/    /'
        fails=$((fails+1))
    else
        echo "ok   [$name] stripped"
    fi
}
# assert_keeps NAME WANT INPUT → filter must preserve exactly WANT non-empty lines
assert_keeps() {
    local name="$1" want="$2" input="$3" out got
    if [[ -z "$input" ]]; then echo "FAIL [$name]: test input was empty (broken setup)"; fails=$((fails+1)); return; fi
    out=$(printf '%s\n' "$input" | filt); got=$(printf '%s\n' "$out" | grep -c . || true)
    if [[ "$got" != "$want" ]]; then
        echo "FAIL [$name]: expected $want preserved line(s), got $got:"; printf '%s\n' "$out" | sed 's/^/    /'
        fails=$((fails+1))
    else
        echo "ok   [$name] preserved $got line(s)"
    fi
}

# --- machine maps: must be stripped ---
assert_empty "nested-map-before-status (#34 regression)" '{:wake-reason :rez-ice, :cursor 153, :prompt {:msg "x", :eid {:eid 124099}}, :status :decision-required, :ice "Tithe"}'
assert_empty "leading-status"        '{:status :success}'
assert_empty "flat-wakereason-status" '{:wake-reason :decision-required, :cursor 12, :status :fire-decision-required}'
assert_empty "nil"                   'nil'

# --- human output and non-status maps: must be preserved ---
assert_keeps "human-decision-block" 3 $'Rez decision: Palisade\n   continue --rez "Palisade"  - rez it\n   continue --no-rez      - decline'
assert_keeps "emoji-human-line"     1 '⏸️  Waiting for corp rez decision'
assert_keeps "edn-map-without-status" 1 '{:server ["remote2"], :position 3, :phase "encounter-ice"}'

echo "---"
if [[ "$fails" -ne 0 ]]; then
    echo "send_command_filter_test: $fails FAILED"; exit 1
fi
echo "send_command_filter_test: ALL PASSED"
