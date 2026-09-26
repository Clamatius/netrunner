#!/usr/bin/env bash
# Exercise the real send_command dispatcher with a fake eval backend. This pins
# the ACTION eval send_command emits for `choose`: the exact Clojure expression,
# the seat and port it is addressed to, and that nothing else is sent.
#
# What it does NOT prove: that the named var resolves in a live REPL, that the
# engine accepts the choice, or that the seat is told the truth about the
# result. `ai-actions/choose-by-value!` is an alias (ai_actions.clj); deleting
# that alias leaves this test green. Symbol boundness is not covered here.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)" || exit 1
SEND_CMD="$SCRIPT_DIR/../send_command"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-choice-route.XXXXXX")" || exit 1
[[ -n "$TMP" && -d "$TMP" ]] || { echo "FAIL: could not create a temp dir"; exit 1; }
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats" SEND_COMMAND_LOG="$TMP/commands.log"

# `--stdin` is how execute() reaches the backend (send_command:861,864); the
# connection preflight uses the positional form (303-328, 2222). That is a
# structural split, not a list of expressions to keep up to date, so a NEW
# preflight eval cannot leak into the action log and a new ACTION eval cannot
# hide from it.
cat > "$TMP/eval" <<'STUB' || exit 1
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then
    printf '%s %s|%s\n' "${2:-<none>}" "${3:-<none>}" "$(cat)" >> "$ACTION_LOG"
    printf 'nil\n'
    exit 0
fi
expr="${!#}"
case "$expr" in
    *sync-verdict!*) printf '"SYNC-VERDICT in-sync"\n' ;;
    *ensure-connected!*) printf '"ok"\n' ;;
    *) printf 'nil\n' ;;
esac
STUB
chmod +x "$TMP/eval" || exit 1

fail=0
check() {
    local name="$1" actual="$2" expected="$3"
    if [[ "$actual" == "$expected" ]]; then
        echo "ok   [$name]"
    else
        printf 'FAIL [%s]\n expected: %q\n      got: %q\n' "$name" "$expected" "$actual"
        fail=$((fail + 1))
    fi
}
run_choice() {
    local side="$1" label="$2"
    export ACTION_LOG="$TMP/actions.log"
    : > "$ACTION_LOG"
    NR_NO_AUTO_PROMPT=1 AI_EVAL="$TMP/eval" "$SEND_CMD" "$side" choose "$label" > "$TMP/output" 2>&1
    code=$?
    # The WHOLE action log, not a filtered slice: an extra send of any shape
    # (`(ai-actions/end-turn!)`, a second choose, a send to the other seat)
    # changes this string and fails the comparison.
    actions="$(cat "$ACTION_LOG")"
}

run_choice runner 'R&D'
check 'label-command-exits' "$code" '0'
check 'label-is-the-only-action' "$actions" 'runner 7889|(ai-actions/choose-by-value! "R&D")'

run_choice runner 'Cerberus "Lady" H1'
check 'quoted-label-exits' "$code" '0'
check 'quoted-label-is-one-string' "$actions" 'runner 7889|(ai-actions/choose-by-value! "Cerberus \"Lady\" H1")'

run_choice runner '1'
check 'numeric-choice-exits' "$code" '0'
check 'numeric-choice-keeps-index-path' "$actions" 'runner 7889|(ai-actions/choose-option! 1)'

# The seat the expression is addressed to is part of the contract: the backend
# selects a REPL by this name/port pair, so a label reaching the wrong seat
# presses a button in the opponent's client.
run_choice corp 'Archives'
check 'corp-choice-exits' "$code" '0'
check 'corp-choice-addresses-the-corp-seat' "$actions" 'corp 7890|(ai-actions/choose-by-value! "Archives")'

if ((fail)); then
    printf 'FAIL: %d choice routing assertion(s)\n' "$fail"
    exit 1
fi
echo 'PASS: CLI choice routing (offline backend)'
