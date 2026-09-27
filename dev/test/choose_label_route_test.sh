#!/usr/bin/env bash
# Exercise the real send_command dispatcher with a fake eval backend. This pins
# the WHOLE eval sequence `choose` emits: the exact Clojure expressions, their
# order, the seat and port each is addressed to, and that there are no others.
#
# What it does NOT prove: that the named var resolves in a live REPL, that the
# engine accepts the choice, or that the seat is told the truth about the
# result. `ai-actions/choose-by-value!` is an alias (ai_actions.clj:136) and
# DELETING that alias leaves this test green - symbol boundness is not covered
# here. Some symbols send_command names are called from Clojure tests too
# (ai-actions/take-credit! at ai_actions_test.clj:111), so they are covered by
# accident; nothing covers the set of symbols the DISPATCHER names, as a set.
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
# NR_NO_AUTO_PROMPT is pinned to 0, not merely left unset: a real seat never
# runs with it on, and suppressing after_action hides the follow-up prompt read
# that is part of what `choose` owes the seat (send_command:955-968). An
# exported 1 in the CALLER's environment would otherwise silently put this test
# back in that mode. SHOW_LAST_LOG is pinned for the same reason.
run_command() {
    local side="$1"
    shift
    export ACTION_LOG="$TMP/actions.log"
    : > "$ACTION_LOG"
    NR_NO_AUTO_PROMPT=0 SHOW_LAST_LOG=0 AI_EVAL="$TMP/eval" \
        "$SEND_CMD" "$side" "$@" > "$TMP/output" 2>&1
    code=$?
    # The WHOLE action log, not a filtered slice: an extra send of any shape
    # (`(ai-actions/end-turn!)`, a second choose, a send to the other seat, a
    # positional call) changes this string and fails the comparison. The
    # expected value is multi-line, so it pins ORDER too.
    actions="$(cat "$ACTION_LOG")"
}
run_choice() { run_command "$1" choose "$2"; }
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

run_choice runner '010'
check 'padded-choice-is-decimal' "$actions" 'runner 7889|(ai-actions/choose-option! 10)
runner 7889'"$PROMPT_READ"
run_choice runner '08'
check 'padded-eight-is-valid-decimal' "$actions" 'runner 7889|(ai-actions/choose-option! 8)
runner 7889'"$PROMPT_READ"
run_choice runner '000'
check 'all-zero-choice-is-zero' "$actions" 'runner 7889|(ai-actions/choose-option! 0)
runner 7889'"$PROMPT_READ"

run_command runner choose-card 010 --all
check 'padded-card-index-is-decimal' "$actions" 'runner 7889|(ai-actions/choose-card! 10 true)
runner 7889'"$PROMPT_READ"
run_command runner play-index 010
check 'padded-play-index-is-decimal' "$actions" 'runner 7889|(ai-actions/play-card! 10)
runner 7889'"$PROMPT_READ"
run_command corp install-index 010 'Server 1'
check 'padded-install-index-is-decimal' "$actions" 'corp 7890|(ai-actions/install-card! 10 "Server 1")
corp 7890'"$PROMPT_READ"
run_command runner multi-choose 010 002
check 'padded-multi-indices-are-decimal' "$actions" 'runner 7889|(ai-actions/multi-choose! 10 2)
runner 7889'"$PROMPT_READ"
run_command corp discard 010 002
check 'padded-discard-indices-are-decimal' "$actions" 'corp 7890|(ai-actions/discard-specific-cards! [10 2])'
run_command corp use-ability 'Nico Campaign' 010
check 'padded-ability-index-is-decimal' "$actions" 'corp 7890|(ai-actions/use-ability! "Nico Campaign" 10)
corp 7890'"$PROMPT_READ"
run_command runner use-runner-ability 'Bioroid' 010
check 'padded-runner-ability-index-is-decimal' "$actions" 'runner 7889|(ai-actions/use-runner-ability! "Bioroid" 10)
runner 7889'"$PROMPT_READ"

# #251's class is "a digit string reaching a bare Clojure literal", not just the
# index commands. A COUNT is the same shape with a worse payload: `draw 010`
# would draw 8 cards and spend 8 clicks while printing "Drawing 010 cards", and
# `draw 08` dies in the reader after the click has been committed to. The
# display counts and `wait`'s budget are the same literal, one severity down.
run_command runner draw 010
check 'padded-draw-count-is-decimal' "$actions" 'runner 7889|(ai-actions/draw-card! 10)
runner 7889'"$PROMPT_READ"
run_command runner draw 08
check 'padded-draw-eight-is-decimal' "$actions" 'runner 7889|(ai-actions/draw-card! 8)
runner 7889'"$PROMPT_READ"
run_command corp take-credit 010
check 'padded-credit-count-is-decimal' "$actions" 'corp 7890|(ai-actions/take-credit! 10)
corp 7890'"$PROMPT_READ"

# A padded count of ONE must still take the no-argument branch: the arms switch
# on `-gt 1`, and bash reads `01` as octal too, so normalizing only inside the
# branch would leave the switch itself deciding on the wrong number.
run_command corp take-credit 01
check 'padded-one-credit-takes-the-bare-branch' "$actions" 'corp 7890|(ai-actions/take-credit!)
corp 7890'"$PROMPT_READ"

run_command runner wait 010
check 'padded-wait-budget-is-decimal' "$actions" 'runner 7889|(ai-actions/wait-for-relevant-diff 10)'
run_command runner wait 010 --since 020
check 'padded-wait-since-is-decimal' "$actions" 'runner 7889|(ai-actions/wait-for-relevant-diff {:timeout 10 :since 20})'

# Display counts: a reader error here costs the seat its read of the board, and
# `08` is not valid octal at all.
# `advance` matches its count with a GLOB (`[0-9]*`), not an anchored regex, so
# the sweep that found the other arms could not see it - the #242 shape. Its
# payload is the expensive one: `advance <agenda> 010` spends 8 clicks.
run_command corp advance 'Offworld Office' 010
check 'padded-advance-count-is-decimal' "$actions" 'corp 7890|(ai-actions/advance-card-times! "Offworld Office" 10 {})
corp 7890'"$PROMPT_READ"
run_command corp advance 'Offworld Office' 08 --overadvance
check 'padded-advance-eight-is-decimal' "$actions" 'corp 7890|(ai-actions/advance-card-times! "Offworld Office" 8 {:overadvance true})
corp 7890'"$PROMPT_READ"
# A padded ONE must still take the single-advance branch, and `--overadvance`
# must survive that branch choice.
run_command corp advance 'Offworld Office' 01 --overadvance
check 'padded-one-advance-keeps-overadvance' "$actions" 'corp 7890|(ai-actions/advance-card! "Offworld Office" {:overadvance true})
corp 7890'"$PROMPT_READ"

run_command runner log 010
check 'padded-log-count-is-decimal' "$actions" 'runner 7889|(with-out-str (ai-actions/show-log 10))'
run_command runner log-compact 08
check 'padded-log-compact-count-is-decimal' "$actions" 'runner 7889|(with-out-str (ai-actions/show-log-compact 8))'
run_command runner snapshot 010
check 'padded-snapshot-count-is-decimal' "$actions" 'runner 7889|(with-out-str (ai-actions/show-snapshot 10))'

# The seat the expression is addressed to is part of the contract: the backend
# selects a REPL by this name/port pair, so a label reaching the wrong seat
# presses a button in the opponent's client.
run_choice corp 'Archives'
check 'corp-choice-exits' "$code" '0'
check 'corp-choice-addresses-the-corp-seat' "$actions" 'corp 7890|(ai-actions/choose-by-value! "Archives")
corp 7890'"$PROMPT_READ"

# A backend that REFUSES the choose must not be reported as success. This is
# the only mutation the exit-status assertions above can catch on their own.
# The action log is asserted here too: a refusal is exactly when a dispatcher
# is most tempted to "recover" by sending something else, and a status-only
# check cannot see that. Note there is no prompt read - after_action does not
# run on the refusal path.
STUB_CHOOSE_STATUS=1 run_choice runner 'R&D'
[[ "$code" -ne 0 ]] && code=nonzero
check 'backend-refusal-exits-nonzero' "$code" 'nonzero'
check 'backend-refusal-sends-nothing-else' "$actions" 'runner 7889|(ai-actions/choose-by-value! "R&D")'

if ((fail)); then
    printf 'FAIL: %d choice routing assertion(s)\n' "$fail"
    exit 1
fi
echo 'PASS: CLI choice routing (offline backend)'
