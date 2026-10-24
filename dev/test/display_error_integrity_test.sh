#!/usr/bin/env bash
# display_error_integrity_test.sh — regression guard for #282: a display
# command's ERROR text lost its first and last byte.
#
# Every display command (status, board, hand, log, snapshot, prompt, ...) is
# wrapped in `(with-out-str ...)` so its printed output comes back as a Clojure
# STRING LITERAL, and execute() strips the surrounding quotes. The strip was
# guarded only by a LENGTH check:
#
#     if [[ ${#RESULT} -ge 2 ]]; then
#         LEN=$((${#RESULT}-2))
#         UNQUOTED="${RESULT:1:$LEN}"
#
# `${#RESULT} -ge 2` reads as "is there a quoted string here?" but only answers
# "are there two bytes here?" — and error text always has two bytes, so the
# guard was satisfied on every throw and never protective. On a throw RESULT is
# raw error text with no quotes, and the slice ate the first byte of the message
# plus the last byte of its final line:
#
#     Syntax error compiling ...   ->   yntax error compiling ...
#     ...Compiler$CompilerException ->  ...Compiler$CompilerExceptio
#
# That is the surface a seat reaches for when it is ALREADY confused, and
# show-prompt-if-any (run by after_action after every state-changing command)
# goes through the same path.
#
# Why substring assertions could not catch it: the clipped bytes are the FIRST
# and LAST of the output, so every `assert_contains` on a token in the middle
# still passed. This file asserts the WHOLE output by equality.
#
# Why the existing stub coverage could not catch it: game_gone_gate_test.sh
# already has a THROW stub mode emitting this exact error shape, but points it
# at `sync-verdict!` — not a display expression — so it never reached the
# unquote. The uncovered combination was "a DISPLAY command whose eval THROWS".

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-display-err-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats"

# The error text the backend emits. The leading "S" and trailing "n" are the
# bytes #282 ate, so they are what this test is really about.
ERR_LINE1='Syntax error compiling at (/tmp/form-init1.clj:1:15).'
ERR_LINE2='No such var: ai-actions/show-snapshot'
ERR_LINE3='Exception: class clojure.lang.Compiler$CompilerException'
ERR_LINE4='Root Exception: class clojure.lang.Compiler$CompilerException'

STUB="$TMP/stub-eval.sh"
cat > "$STUB" <<'STUBEOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then
    expr="$(cat)"
else
    expr="${!#}"
fi
case "$expr" in
    *sync-verdict!*)     printf '"SYNC-VERDICT synced"\n'; exit 0 ;;
    *ensure-connected!*) printf '"ok"\n'; exit 0 ;;
esac
# A wrapped display expression: either throw like a real REPL (trace on stderr,
# non-zero exit) or answer with the quoted string with-out-str really returns.
if [[ "$STUB_MODE" == "throw" ]]; then
    printf '%s\n%s\n%s\n%s\n' "$ERR_LINE1" "$ERR_LINE2" "$ERR_LINE3" "$ERR_LINE4" >&2
    exit 1
fi
printf '"Server 1: Palisade (rezzed)\\nCredits: 7\\n"'
STUBEOF
chmod +x "$STUB"
export ERR_LINE1 ERR_LINE2 ERR_LINE3 ERR_LINE4

fails=0

assert_eq() {  # NAME EXPECTED ACTUAL
    local name="$1" want="$2" got="$3"
    if [[ "$want" == "$got" ]]; then
        echo "ok   [$name]"
    else
        echo "FAIL [$name]:"
        echo "  expected:"; printf '%s\n' "$want" | sed 's/^/    |/'
        echo "  got:";      printf '%s\n' "$got"  | sed 's/^/    |/'
        fails=$((fails+1))
    fi
}
assert_code() {  # NAME WANT GOT
    if [[ "$2" == "$3" ]]; then echo "ok   [$1]"
    else echo "FAIL [$1]: expected exit $2, got $3"; fails=$((fails+1)); fi
}

run_display() {  # MODE COMMAND -> OUT / CODE
    local mode="$1"; shift
    OUT=$(STUB_MODE="$mode" AI_EVAL="$STUB" NR_NO_AUTO_PROMPT=1 \
            "$SEND_CMD" corp "$@" 2>&1) && CODE=0 || CODE=$?
}

echo "--- #282: a throwing display command's error text must arrive INTACT ---"

# snapshot is a display command, so execute() wraps it and takes the unquote
# path. The whole output is asserted: a clipped first or last byte fails here
# even though every token in the middle survives.
WANT_ERR="$(printf '%s\n%s\n%s\n%s' "$ERR_LINE1" "$ERR_LINE2" "$ERR_LINE3" "$ERR_LINE4")"

run_display throw snapshot
assert_eq   "snapshot-throw-text-intact" "$WANT_ERR" "$OUT"
assert_code "snapshot-throw-exit-1"      1 "$CODE"

# The same path, reached by a second display command, so the guard is not pinned
# to one spelling.
run_display throw status
assert_eq   "status-throw-text-intact"   "$WANT_ERR" "$OUT"
assert_code "status-throw-exit-1"        1 "$CODE"

echo "--- the control: a genuinely quoted result still unquotes correctly ---"

# This is the half that makes the fix a discrimination rather than a deletion.
# with-out-str really does return a quoted literal with \n escapes, and the seat
# must still get the interpreted text, not the raw literal.
run_display ok snapshot
assert_eq   "snapshot-ok-unquoted" "$(printf 'Server 1: Palisade (rezzed)\nCredits: 7\n')" "$OUT"
assert_code "snapshot-ok-exit-0"   0 "$CODE"

# And the quotes must actually be gone — a fix that simply stopped unquoting
# would pass the integrity assertions above and break every display command.
if [[ "$OUT" == '"'* ]]; then
    echo "FAIL [snapshot-ok-quotes-stripped]: output still starts with a quote: $OUT"
    fails=$((fails+1))
else
    echo "ok   [snapshot-ok-quotes-stripped]"
fi

if [[ "$fails" -eq 0 ]]; then
    echo "✅ display_error_integrity_test: all assertions passed."
    exit 0
fi
echo "❌ display_error_integrity_test: $fails assertion(s) failed."
exit 1
