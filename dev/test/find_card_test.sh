#!/usr/bin/env bash
# find_card_test.sh — regression guard for #218's multi-turn search.
#
# find-card spans turns, so it drives BOTH seats, and each seat is its own REPL
# process (runner 7889, corp 7890). The old Clojure find-card! ran the opponent's
# heuristic bot inside its OWN side's REPL; once #218's paren fix made it
# reachable, a live Runner search started "turn 1" eight times in a row and the
# Corp never played. So what is pinned here is WHICH REPL each step reaches, and
# that no turn is started unless the opponent demonstrably took one.
#
# Drives the real dispatcher with a stub eval backend that answers from a script.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-findcard-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats"
export SEND_COMMAND_LOG="$TMP/commands.log"

# The stub logs "<port> <step>" for every step that reaches a REPL and answers
# from env: STUB_DRAWS (space-separated statuses, one consumed per draw),
# STUB_ACTIVE_AFTER_BOT (what the Runner REPL reports as active player once the
# corp bot-turn has been sent; before that it reports STUB_ACTIVE_BEFORE_BOT).
STUB="$TMP/stub-eval.sh"
cat > "$STUB" <<'STUBEOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then
    port="$3"; expr="$(cat)"
else
    port="${2:-}"; expr="${!#}"
fi
step() { printf '%s %s\n' "$port" "$1" >> "$STUB_LOG"; }
case "$expr" in
    *ensure-connected!*) printf '"ok"\n' ;;
    *sync-verdict!*)     printf '"SYNC-VERDICT ok"\n' ;;
    *draw-to-card!*)
        step draw
        n=$(grep -c ' draw$' "$STUB_LOG")
        status=$(echo $STUB_DRAWS | cut -d' ' -f"$n")
        printf 'FIND-CARD-STATUS %s\n' "$status" ;;
    *discard-to-hand-size!*) step discard; printf '0\n' ;;
    *play-full-turn*)        step bot-turn; printf 'bot played\n' ;;
    *FIND-CARD-ACTIVE*)
        step active
        if grep -q ' bot-turn$' "$STUB_LOG"; then a="$STUB_ACTIVE_AFTER_BOT"; else a="$STUB_ACTIVE_BEFORE_BOT"; fi
        printf 'FIND-CARD-ACTIVE %s\n' "$a" ;;
    *start-turn!*)           step start-turn; printf 'started\n' ;;
    *FIND-CARD-CLICKS*)      step clicks; printf 'FIND-CARD-CLICKS %s\n' "${STUB_CLICKS:-4}" ;;
    *)                       step "other:${expr:0:40}"; printf 'nil\n' ;;
esac
STUBEOF
chmod +x "$STUB"

fails=0
OUT=""; CODE=0; LOG=""
run() {  # SIDE args...
    STUB_LOG="$TMP/log.$RANDOM"; : > "$STUB_LOG"; export STUB_LOG
    OUT=$(AI_EVAL="$STUB" NR_NO_AUTO_PROMPT=1 "$SEND_CMD" "$@" 2>&1) && CODE=0 || CODE=$?
    LOG=$(cat "$STUB_LOG")
}
check() {  # NAME CONDITION-RESULT(0/1) DETAIL
    if [[ "$2" -eq 0 ]]; then echo "ok   [$1]"
    else echo "FAIL [$1]: $3"; printf '%s\n' "--- output:" "$OUT" "--- steps:" "$LOG" | sed 's/^/    /'; fails=$((fails+1)); fi
}

# 1. Happy path: runner misses on turn 1, the CORP REPL plays, runner finds it on turn 2.
export STUB_DRAWS="out-of-clicks success" STUB_ACTIVE_BEFORE_BOT=runner STUB_ACTIVE_AFTER_BOT=corp STUB_CLICKS=4
run runner find-card "Sure Gamble"
EXPECTED="7889 draw
7889 discard
7889 active
7890 bot-turn
7889 active
7889 start-turn
7889 clicks
7889 draw"
[[ "$CODE" -eq 0 && "$LOG" == "$EXPECTED" ]]; check "happy-path-order" $? "exit $CODE; expected steps:
$EXPECTED"
[[ "$OUT" == *"Found Sure Gamble on turn 2"* ]]; check "happy-path-says-found" $? "no found line"

# 2. THE #218 BUG: the corp bot did not take a turn. Never start another runner turn.
export STUB_DRAWS="out-of-clicks success" STUB_ACTIVE_BEFORE_BOT=runner STUB_ACTIVE_AFTER_BOT=runner
run runner find-card "Sure Gamble"
[[ "$CODE" -ne 0 && "$LOG" != *"start-turn"* ]]; check "no-start-after-idle-opponent" $? "exit $CODE; a runner start-turn was sent although the corp never became active"
[[ "$OUT" == *"did not take a turn"* ]]; check "idle-opponent-says-why" $? "no diagnosis"

# 3. Our own turn has not started (active player is still the opponent): do not hand
#    the opponent a second turn in a row.
export STUB_DRAWS="out-of-clicks" STUB_ACTIVE_BEFORE_BOT=corp STUB_ACTIVE_AFTER_BOT=corp
run runner find-card "Sure Gamble"
[[ "$CODE" -ne 0 && "$LOG" != *"bot-turn"* ]]; check "no-opponent-turn-at-own-boundary" $? "exit $CODE; corp bot-turn sent at the runner's own turn boundary"
[[ "$OUT" == *"start-turn"* ]]; check "own-boundary-names-start-turn" $? "does not tell the seat to start its turn"

# 4. start-turn refused (exit 0, no clicks): stop instead of drawing on a turn we don't have.
export STUB_DRAWS="out-of-clicks success" STUB_ACTIVE_BEFORE_BOT=runner STUB_ACTIVE_AFTER_BOT=corp STUB_CLICKS=0
run runner find-card "Sure Gamble"
[[ "$CODE" -ne 0 && $(grep -c ' draw$' <<<"$LOG") -eq 1 ]]; check "stop-when-turn-did-not-start" $? "exit $CODE; drew again without clicks"
export STUB_CLICKS=4

# 5. Corp side routes the opponent to the RUNNER REPL.
export STUB_DRAWS="out-of-clicks success" STUB_ACTIVE_BEFORE_BOT=corp STUB_ACTIVE_AFTER_BOT=runner
run corp find-card "Hedge Fund"
[[ "$CODE" -eq 0 && "$LOG" == *"7889 bot-turn"* && "$LOG" != *"7890 bot-turn"* ]]; check "corp-opponent-is-runner-repl" $? "exit $CODE"

# 6. Other draw outcomes stop; max turns bounds it; no side refuses before any REPL step.
export STUB_DRAWS="error"
run runner find-card "Sure Gamble"
[[ "$CODE" -ne 0 && "$LOG" == "7889 draw" ]]; check "draw-error-stops" $? "exit $CODE"

export STUB_DRAWS="out-of-clicks out-of-clicks out-of-clicks" STUB_ACTIVE_BEFORE_BOT=runner STUB_ACTIVE_AFTER_BOT=corp
run runner find-card "Sure Gamble" 2
[[ "$CODE" -ne 0 && $(grep -c ' draw$' <<<"$LOG") -eq 2 && $(grep -c 'bot-turn' <<<"$LOG") -eq 1 ]]; check "max-turns-bounds" $? "exit $CODE"

run runner find-card "Sure Gamble" 0
[[ "$CODE" -ne 0 && -z "$LOG" ]]; check "bad-max-turns-refused" $? "exit $CODE"

if [[ "$fails" -gt 0 ]]; then echo "find_card_test: $fails failure(s)"; exit 1; fi
echo "find_card_test: all passed"
