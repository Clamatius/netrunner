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
# #251's own reading is "a seat that writes `010` means the tenth option". The
# round-2 length cap counted the PADDING against itself, so `00000010` fell
# through to the LABEL branch and #251's own example stopped working. The bound
# now applies to the normalized value, and only where bash arithmetic consumes
# it.
run_choice runner '00000010'
check 'heavily-padded-choice-is-still-an-index' "$actions" 'runner 7889|(ai-actions/choose-option! 10)
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
# would draw 8 cards and spend 8 clicks while printing "Drawing 010 cards".
# `draw 08` never reached the reader at all - bash's OWN arithmetic has the same
# base rule, so `[[ 08 -gt 1 ]]` printed "value too great for base" and
# evaluated FALSE, and the arm drew ONE card saying "Drawing card...". The
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
# num_arg's whole claim is that validation and normalization travel TOGETHER.
# Deleting the validation half left the padding assertions green (mutation
# `num_arg-drops-validation` survived), so pin the refusal: a non-numeric index
# must exit non-zero and send NOTHING - not reach the literal as a symbol.
run_command runner play-index 3x
[[ "$code" -ne 0 ]] && code=nonzero
check 'non-numeric-index-refuses' "$code" 'nonzero'
check 'non-numeric-index-sends-nothing' "$actions" ''
check 'non-numeric-index-says-why' "$(grep -c 'Index must be a number' "$TMP/output")" '1'
run_command corp take-credit lots
[[ "$code" -ne 0 ]] && code=nonzero
check 'non-numeric-count-refuses' "$code" 'nonzero'
check 'non-numeric-count-sends-nothing' "$actions" ''
check 'non-numeric-count-says-why' "$(grep -c 'send_command take-credit' "$TMP/output")" '1'

# num_or's other half: a malformed DISPLAY count falls back to the default
# rather than refusing, because a bad `log` argument should still show a log.
run_command runner log lots
check 'non-numeric-log-count-falls-back' "$actions" 'runner 7889|(with-out-str (ai-actions/show-log 20))'

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

# --- Round 2: what the review panel found that the sweep above missed. ---

# Both seats found these two independently. Their guard is spelled `^-?[0-9]+$`,
# and the optional SIGN is why a sweep anchored on `^[0-9]+$` went blind to them
# - the #242 shape a second time in one change. Dev backdoors, so no seat hits
# them in play, but they are the SCENARIO-STAGING commands: a board staged with
# 8 credits instead of 10 is corrupt test state with a truthful-looking echo.
run_command corp change tag 010
check 'padded-change-delta-is-decimal' "$actions" 'corp 7890|(ai-actions/change! :tag 10)'
run_command corp change credit -010
check 'padded-negative-delta-keeps-its-sign' "$actions" 'corp 7890|(ai-actions/change! :credit -10)'
run_command corp fix-credits 010
check 'padded-fix-credits-is-decimal' "$actions" 'corp 7890|(ai-actions/fix-credits! 10)'
# `fix-credits!` enters DELTA mode only for a signed STRING
# (ai_basic_actions.clj:2211-2213). A bare Clojure `+5` is just 5 and takes the
# ABSOLUTE branch, so this arm's own documented `+5 (add 5)` SET credits to 5 -
# a pre-existing lie in its usage text, not something this change introduced.
# Round 3 found it; the signed form is quoted so the documented behaviour works.
run_command corp fix-credits +05
check 'signed-amount-is-a-string-so-delta-mode-fires' "$actions" 'corp 7890|(ai-actions/fix-credits! "+5")'
run_command corp fix-credits -03
check 'signed-negative-amount-is-also-a-string' "$actions" 'corp 7890|(ai-actions/fix-credits! "-3")'
# An UNSIGNED amount stays a number: that is the absolute-set branch, and
# quoting it would still work but by the string-number path, not the documented
# one. `change!` takes a raw number either way, so its sign is NOT quoted.
run_command corp fix-credits 010
check 'unsigned-amount-stays-a-number' "$actions" 'corp 7890|(ai-actions/fix-credits! 10)'

# fix-credits had NO numeric guard at all, so its argument was interpolated into
# the eval verbatim: this one ENDED THE TURN. `wait --since` was the same hole,
# and the deferral that left it open was wrong - a non-numeric cursor is not a
# symbol that fails loudly, it is an expression the eval RUNS.
run_command corp fix-credits '(do (ai-actions/end-turn!) 0)'
[[ "$code" -ne 0 ]] && code=nonzero
check 'expression-as-amount-refuses' "$code" 'nonzero'
check 'expression-as-amount-sends-nothing' "$actions" ''
run_command runner wait 1 --since '(do (ai-actions/choose-option! 8) 0)'
[[ "$code" -ne 0 ]] && code=nonzero
check 'expression-as-cursor-refuses' "$code" 'nonzero'
check 'expression-as-cursor-sends-nothing' "$actions" ''

# `advance` must REFUSE what its glob catches but the gate rejects. Defaulting
# to 1 turned `0x2` - which the old code advanced TWICE on, via bash's own hex
# reading - into a silent single advance. Refusing loudly is the honest answer.
run_command corp advance 'Offworld Office' 0x2
[[ "$code" -ne 0 ]] && code=nonzero
check 'hex-advance-count-refuses' "$code" 'nonzero'
check 'hex-advance-count-sends-nothing' "$actions" ''

# The digit-LENGTH bound. Stripping zeroes as a string protects the LITERAL, but
# the `-gt 1` switch after it is still bash arithmetic and wraps silently at 64
# bits, so a 19-digit count went negative, failed `-gt 1`, and drew ONE card.
run_command runner draw 9999999999999999999
[[ "$code" -ne 0 ]] && code=nonzero
check 'overlong-count-refuses' "$code" 'nonzero'
check 'overlong-count-sends-nothing' "$actions" ''
# ...and the bound is ONLY on the arithmetic consumers. A big DISPLAY count is a
# big read, not an overflow, so it passes through: bounding it here made
# `log 99999999` silently show 20 lines, and bounding `wait` made a refused
# budget a silent 300-second park (send_command_timeout_test's wait-refusal
# case, which relies on ai-eval.sh's own bound, went red on it).
run_command runner log 99999999
check 'overlong-display-count-passes-through' "$actions" 'runner 7889|(with-out-str (ai-actions/show-log 99999999))'

# Two arms whose fix was unpinned: both mutations survived the round-1 battery.
run_command corp dashboard-compact 010
check 'padded-dashboard-count-is-decimal' "$actions" 'corp 7890|(do
                   (require (quote [ai-heuristic-corp :as bot]))
                   (println (bot/dashboard-compact 10)))'
# bot-loop's minutes go through bash MULTIPLICATION, so the whole-log form would
# pin an unrelated 7-line expression. Pin the computed value, and that exactly
# one expression was sent.
run_command corp bot-loop --patient 010
check 'padded-patient-minutes-are-decimal' "$(grep -c ':patient-ms 600000' "$ACTION_LOG")" '1'
check 'patient-loop-sends-one-expression' "$(grep -c '^corp 7890|' "$ACTION_LOG")" '1'

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
