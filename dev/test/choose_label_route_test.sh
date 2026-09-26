#!/usr/bin/env bash
# Exercise the real send_command dispatcher with a fake eval backend. This pins
# the WHOLE eval sequence `choose` emits: the exact Clojure expressions, their
# order, the seat and port each is addressed to, and that there are no others.
#
# What it does NOT prove: that the named var resolves in a live REPL, that the
# engine accepts the choice, or that the seat is told the truth about the
# result. `ai-actions/choose-by-value!` is an alias (ai_actions.clj:136) and
# DELETING that alias leaves this test green - symbol boundness is not covered
# here, and no test covers it for any of the symbols send_command names.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)" || exit 1
SEND_CMD="$SCRIPT_DIR/../send_command"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-choice-route.XXXXXX")" || exit 1
[[ -n "$TMP" && -d "$TMP" ]] || { echo "FAIL: could not create a temp dir"; exit 1; }
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats" SEND_COMMAND_LOG="$TMP/commands.log"

# The stub logs EVERY invocation it is not there to answer, in both call forms.
# `--stdin` is execute()'s form (send_command:861,864) and the positional form
# is the connection preflight's (303-328, 2222) - but the positional form is a
# habit, not a rule: ai-eval.sh accepts both and the `resync` arm at 2226
# already calls positionally. So the split is NOT structural: the stub fails
# CLOSED, logging any positional eval it does not recognise as a preflight.
# Residual gap: a positional eval whose text contains `ensure-connected!` or
# `sync-verdict!` is answered as a preflight and stays unlogged.
cat > "$TMP/eval" <<'STUB' || exit 1
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then
    expr="$(cat)"
    printf '%s %s|%s\n' "${2:-<none>}" "${3:-<none>}" "$expr" >> "$ACTION_LOG"
    printf 'nil\n'
    # Let one case make the backend REFUSE, so the exit-status assertions have
    # something to catch. Only the choose itself fails: failing every eval lets
    # the follow-up prompt read rescue the exit code and the mutation survives.
    [[ "$expr" == *choose-* ]] && exit "${STUB_CHOOSE_STATUS:-0}"
    exit 0
fi
expr="${!#}"
case "$expr" in
    *sync-verdict!*) printf '"SYNC-VERDICT in-sync"\n' ;;
    *ensure-connected!*) printf '"ok"\n' ;;
    *) printf 'POSITIONAL %s %s|%s\n' "${1:-<none>}" "${2:-<none>}" "$expr" >> "$ACTION_LOG"
       printf 'nil\n' ;;
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
# NR_NO_AUTO_PROMPT is deliberately NOT set: a real seat never runs in that
# mode, and suppressing after_action hides the follow-up prompt read that is
# part of what `choose` owes the seat (send_command:955-968). SHOW_LAST_LOG is
# pinned so an exported 1 in the caller's environment cannot change the log.
run_choice() {
    local side="$1" label="$2"
    export ACTION_LOG="$TMP/actions.log"
    : > "$ACTION_LOG"
    SHOW_LAST_LOG=0 AI_EVAL="$TMP/eval" "$SEND_CMD" "$side" choose "$label" > "$TMP/output" 2>&1
    code=$?
    # The WHOLE action log, not a filtered slice: an extra send of any shape
    # (`(ai-actions/end-turn!)`, a second choose, a send to the other seat, a
    # positional call) changes this string and fails the comparison. The
    # expected value is multi-line, so it pins ORDER too.
    actions="$(cat "$ACTION_LOG")"
}
# after_action's prompt read. execute() wraps a display-shaped expression in
# with-out-str (the show-/list-/board capture heuristic), so this is the text
# that actually reaches the backend, not the text at the call site.
PROMPT_READ='|(with-out-str (ai-actions/show-prompt-if-any))'

run_choice runner 'R&D'
check 'label-command-exits' "$code" '0'
check 'label-action-log' "$actions" 'runner 7889|(ai-actions/choose-by-value! "R&D")
runner 7889'"$PROMPT_READ"

run_choice runner 'Cerberus "Lady" H1'
check 'quoted-label-exits' "$code" '0'
check 'quoted-label-is-one-string' "$actions" 'runner 7889|(ai-actions/choose-by-value! "Cerberus \"Lady\" H1")
runner 7889'"$PROMPT_READ"

run_choice runner '1'
check 'numeric-choice-exits' "$code" '0'
check 'numeric-choice-keeps-index-path' "$actions" 'runner 7889|(ai-actions/choose-option! 1)
runner 7889'"$PROMPT_READ"

# The seat the expression is addressed to is part of the contract: the backend
# selects a REPL by this name/port pair, so a label reaching the wrong seat
# presses a button in the opponent's client.
run_choice corp 'Archives'
check 'corp-choice-exits' "$code" '0'
check 'corp-choice-addresses-the-corp-seat' "$actions" 'corp 7890|(ai-actions/choose-by-value! "Archives")
corp 7890'"$PROMPT_READ"

# A backend that REFUSES the choose must not be reported as success. This is
# the only mutation the exit-status assertions above can catch on their own.
STUB_CHOOSE_STATUS=1 run_choice runner 'R&D'
[[ "$code" -ne 0 ]] && code=nonzero
check 'backend-refusal-exits-nonzero' "$code" 'nonzero'

if ((fail)); then
    printf 'FAIL: %d choice routing assertion(s)\n' "$fail"
    exit 1
fi
echo 'PASS: CLI choice routing (offline backend)'
