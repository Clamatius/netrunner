#!/usr/bin/env bash
# find_card_test.sh — regression guard for #218's multi-turn search.
#
# find-card spans turns, so it drives BOTH seats, and each seat is its own REPL
# process (runner 7889, corp 7890). The old Clojure find-card! ran the opponent's
# heuristic bot inside its OWN side's REPL; once #218's paren fix made it
# reachable, a live Runner search started "turn 1" eight times in a row and the
# Corp never played. So what is pinned here is WHICH REPL each step reaches, and
# that no turn is started unless a turn BOUNDARY (active player AND the engine's
# :end-turn) says the previous one is over — `:active-player` alone stays on the
# side whose turn it was, so it can never answer "is that turn finished".
#
# Drives the real dispatcher with a stub eval backend that answers from a script.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-findcard-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats"
export SEND_COMMAND_LOG="$TMP/commands.log"

# The stub logs "<port> <step>" for every step that reaches a REPL, records the
# draw expression verbatim, and answers from env:
#   STUB_DRAWS            statuses, one consumed per draw
#   STUB_MY_BOUNDARY      "<active>/<end-turn>" before the opponent's bot-turn
#   STUB_OPP_BOUNDARY     the same after it
#   STUB_CLICKS           what our clicks poll sees after start-turn
#   STUB_PROMPTS_<port>   |-separated prompt messages for that REPL, one per probe
#                         (the last entry repeats); "null" = no prompt open
#   STUB_RUN              whether a run is live when a turn fails to end
#   STUB_START_EXIT       exit code of the start-turn command
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
        printf '%s\n' "$expr" >> "$STUB_DRAW_EXPRS"
        n=$(grep -c ' draw$' "$STUB_LOG")
        printf 'FIND-CARD-STATUS %s\n' "$(echo $STUB_DRAWS | cut -d' ' -f"$n")" ;;
    *discard-to-hand-size!*) step discard; printf '0\n' ;;
    *play-full-turn*)        step bot-turn; printf 'bot played\n' ;;
    *FIND-CARD-BOUNDARY*)
        step boundary
        printf '%s\n' "$expr" > "$STUB_BOUNDARY_EXPR"
        if grep -q ' bot-turn$' "$STUB_LOG"; then b="$STUB_OPP_BOUNDARY"; else b="$STUB_MY_BOUNDARY"; fi
        printf 'FIND-CARD-BOUNDARY %s\n' "$b" ;;
    *FIND-CARD-PROMPT*)
        step prompt-probe
        q="STUB_PROMPTS_$port"; q="${!q:-null}"
        n=$(grep -c "^$port prompt-probe$" "$STUB_LOG")
        # queue entries are |-separated; the last one repeats
        v=$(awk -F'|' -v n="$n" '{print (n <= NF ? $n : $NF)}' <<<"$q")
        printf 'FIND-CARD-PROMPT %s\n' "$v" ;;
    *FIND-CARD-RUN*)    step run-probe; printf '%s\n' "$expr" > "$STUB_RUN_EXPR"; printf 'FIND-CARD-RUN %s\n' "${STUB_RUN:-false}" ;;   # live run, not merely a non-nil :run
    *start-turn!*)      step start-turn; printf 'started\n'; exit "${STUB_START_EXIT:-0}" ;;
    *FIND-CARD-CLICKS*) step clicks; printf 'FIND-CARD-CLICKS %s\n' "${STUB_CLICKS:-4}" ;;
    *)                  step "other:${expr:0:40}"; printf 'nil\n' ;;
esac
STUBEOF
chmod +x "$STUB"

fails=0
OUT=""; CODE=0; LOG=""; DRAW_EXPRS=""
run() {  # SIDE args...
    STUB_LOG="$TMP/log.$RANDOM"; : > "$STUB_LOG"
    STUB_DRAW_EXPRS="$TMP/draws.$RANDOM"; : > "$STUB_DRAW_EXPRS"
    STUB_BOUNDARY_EXPR="$TMP/boundary.$RANDOM"; : > "$STUB_BOUNDARY_EXPR"
    STUB_RUN_EXPR="$TMP/run.$RANDOM"; : > "$STUB_RUN_EXPR"
    export STUB_LOG STUB_DRAW_EXPRS STUB_BOUNDARY_EXPR STUB_RUN_EXPR
    OUT=$(AI_EVAL="$STUB" NR_NO_AUTO_PROMPT=1 "$SEND_CMD" "$@" 2>&1) && CODE=0 || CODE=$?
    LOG=$(cat "$STUB_LOG"); DRAW_EXPRS=$(cat "$STUB_DRAW_EXPRS")
}
check() {  # NAME CONDITION-RESULT(0/1) DETAIL
    if [[ "$2" -eq 0 ]]; then echo "ok   [$1]"
    else echo "FAIL [$1]: $3"; printf '%s\n' "--- output:" "$OUT" "--- steps:" "$LOG" | sed 's/^/    /'; fails=$((fails+1)); fi
}
# A turn both sides finish cleanly, unless a case overrides it.
reset_stub() {
    export STUB_DRAWS="out-of-clicks success" STUB_MY_BOUNDARY="runner/true" \
           STUB_OPP_BOUNDARY="corp/true" STUB_CLICKS=4 STUB_RUN=false STUB_START_EXIT=0 \
           STUB_PROMPTS_7889="Discard down to 5 cards" STUB_PROMPTS_7890="Discard down to 5 cards"
}

# 1. Happy path: runner misses on turn 1, the CORP REPL plays a whole turn
#    (including answering ITS hand-size discard), runner finds it on turn 2.
reset_stub; export STUB_PROMPTS_7889="Discard down to 5 cards|null"
run runner find-card "Sure Gamble"
EXPECTED="7889 draw
7889 prompt-probe
7889 discard
7889 boundary
7890 bot-turn
7890 prompt-probe
7890 discard
7889 boundary
7889 start-turn
7889 clicks
7889 draw
7889 prompt-probe"
[[ "$CODE" -eq 0 && "$LOG" == "$EXPECTED" ]]; check "happy-path-order" $? "exit $CODE; expected steps:
$EXPECTED"
[[ "$OUT" == *"Found Sure Gamble on turn 2"* ]]; check "happy-path-says-found" $? "no found line"
[[ "$OUT" != *"prompt is still open"* ]]; check "no-phantom-prompt-warning" $? "warns about a prompt that is not there"

# 1b. Finding the card usually leaves the hand-size discard owed. Say so, and do
#     NOT answer it: the auto-discard bins the FIRST cards in hand, which after a
#     search is the wrong end of the hand.
reset_stub
run runner find-card "Sure Gamble"
[[ "$CODE" -eq 0 && "$OUT" == *"prompt is still open"* && "$OUT" == *"Discard down to 5 cards"* ]]; check "success-reports-owed-prompt" $? "exit $CODE"
[[ "$(grep -c ' discard$' <<<"$LOG")" -eq 2 ]]; check "success-does-not-discard-the-find" $? "discard steps: $(grep -c ' discard$' <<<"$LOG") (expected the 2 turn-end ones, none after the find)"

# 1c. A prompt that is NOT the end-of-turn discard: stop, do not select cards for
#     it. `discard` bins the first N cards of any over-limit hand, and engine
#     prompts that select from hand are not all discards (delta-review MAJOR).
reset_stub; export STUB_PROMPTS_7889="Choose a card to trash"
run runner find-card "Sure Gamble"
[[ "$CODE" -ne 0 && "$LOG" != *"discard"* && "$OUT" == *"not the end-of-turn discard"* ]]; check "foreign-prompt-not-discarded" $? "exit $CODE"

# 1d. The same on the OPPONENT's side, after its bot-turn.
reset_stub; export STUB_PROMPTS_7890="Choose a card to trash"
run runner find-card "Sure Gamble"
[[ "$CODE" -ne 0 && "$LOG" != *"7890 discard"* && "$OUT" == *"not the end-of-turn discard"* ]]; check "foreign-opponent-prompt-not-discarded" $? "exit $CODE"

# 1e. No prompt open at all: nothing to answer, and no discard is sent.
reset_stub; export STUB_PROMPTS_7889=null STUB_PROMPTS_7890=null
run runner find-card "Sure Gamble"
[[ "$CODE" -eq 0 && "$LOG" != *"discard"* ]]; check "no-prompt-no-discard" $? "exit $CODE"

# 2. The opponent's turn did not END (its discard prompt outlived its bot-turn, the
#    commonest multi-turn path): never start another turn of ours.
reset_stub; export STUB_OPP_BOUNDARY="corp/false"
run runner find-card "Sure Gamble"
[[ "$CODE" -ne 0 && "$LOG" != *"start-turn"* ]]; check "no-start-after-unfinished-opponent" $? "exit $CODE; a runner start-turn was sent although the corp's turn had not ended"
[[ "$OUT" == *"did not end"* ]]; check "unfinished-opponent-says-why" $? "no diagnosis"

# 2b. …and when the reason is a run waiting on us, say THAT, and name the way out.
#     NOT 'auto-pass': outside a live run it wedges the seat outright (#221).
reset_stub; export STUB_OPP_BOUNDARY="corp/false" STUB_RUN=true
run runner find-card "Sure Gamble"
[[ "$OUT" == *"parked on a RUN"* && "$OUT" == *"monitor-run"* ]]; check "parked-run-diagnosed" $? "run not named"
[[ "$OUT" != *"auto-pass"* ]]; check "parked-run-does-not-advise-auto-pass" $? "recommends auto-pass, which wedges a seat with no live run (#221)"

# 3. OUR turn has not ended: do not hand the opponent a second turn in a row.
#    (Active player alone would say "corp", which is also what our own boundary
#    looks like — the distinction is the :end-turn flag.)
reset_stub; export STUB_DRAWS="out-of-clicks" STUB_MY_BOUNDARY="corp/true"
run runner find-card "Sure Gamble"
[[ "$CODE" -ne 0 && "$LOG" != *"bot-turn"* ]]; check "no-opponent-turn-at-own-boundary" $? "exit $CODE; corp bot-turn sent although our turn had not ended"
[[ "$OUT" == *"start-turn"* ]]; check "own-boundary-names-start-turn" $? "does not tell the seat to start its turn"

# 3b. Mid-turn hold (auto-end deferred for a scorable agenda): active player is us,
#     :end-turn is false. Same refusal, not a mistaken complaint about the opponent.
reset_stub; export STUB_DRAWS="out-of-clicks" STUB_MY_BOUNDARY="runner/false"
run runner find-card "Sure Gamble"
[[ "$CODE" -ne 0 && "$LOG" != *"bot-turn"* && "$OUT" == *"your turn has not ended"* ]]; check "own-turn-still-resolving" $? "exit $CODE"

# 4. start-turn refused: stop instead of drawing on a turn we don't have.
reset_stub; export STUB_CLICKS=0
run runner find-card "Sure Gamble"
[[ "$CODE" -ne 0 && $(grep -c ' draw$' <<<"$LOG") -eq 1 ]]; check "stop-when-turn-did-not-start" $? "exit $CODE; drew again without clicks"

# 4b. A gate exit code (game gone / game over is 4) survives instead of becoming 1.
reset_stub; export STUB_CLICKS=0 STUB_START_EXIT=4
run runner find-card "Sure Gamble"
[[ "$CODE" -eq 4 ]]; check "gate-exit-code-kept" $? "exit $CODE, expected 4"

# 5. Corp side routes the opponent to the RUNNER REPL.
reset_stub; export STUB_MY_BOUNDARY="corp/true" STUB_OPP_BOUNDARY="runner/true"
run corp find-card "Hedge Fund"
[[ "$CODE" -eq 0 && "$LOG" == *"7889 bot-turn"* && "$LOG" != *"7890 bot-turn"* ]]; check "corp-opponent-is-runner-repl" $? "exit $CODE"

# 6. A card title with quotes in it reaches the REPL as one readable Clojure string.
reset_stub; export STUB_DRAWS="success"
run runner find-card 'Cerberus "Lady" H1'
[[ "$DRAW_EXPRS" == *'\"Lady\"'* && "$CODE" -eq 0 ]]; check "quoted-title-escaped" $? "expr was: $DRAW_EXPRS"

# 7. The last turn still answers our own discard prompt rather than parking the game.
reset_stub; export STUB_DRAWS="out-of-clicks"
run runner find-card "Sure Gamble" 1
[[ "$CODE" -ne 0 && "$LOG" == "7889 draw
7889 prompt-probe
7889 discard" ]]; check "last-turn-discards" $? "exit $CODE"

# 8. Other draw outcomes stop; max turns bounds it; a bad bound and a missing side
#    are refused before any REPL step.
reset_stub; export STUB_DRAWS="error"
run runner find-card "Sure Gamble"
[[ "$CODE" -ne 0 && "$LOG" == "7889 draw" ]]; check "draw-error-stops" $? "exit $CODE"

reset_stub; export STUB_DRAWS="out-of-clicks out-of-clicks out-of-clicks"
run runner find-card "Sure Gamble" 2
[[ "$CODE" -ne 0 && $(grep -c ' draw$' <<<"$LOG") -eq 2 && $(grep -c 'bot-turn' <<<"$LOG") -eq 1 ]]; check "max-turns-bounds" $? "exit $CODE"

reset_stub
run runner find-card "Sure Gamble" 0
[[ "$CODE" -ne 0 && -z "$LOG" ]]; check "bad-max-turns-refused" $? "exit $CODE"

reset_stub
run find-card "Sure Gamble"
[[ "$CODE" -ne 0 && "$OUT" == *"needs your side"* ]]; check "sideless-refused" $? "exit $CODE"

# ---------------------------------------------------------------------------
# The stub answers by PATTERN, so nothing above can tell whether the Clojure
# find-card generates asks the right question — a probe reverted to `some?` or a
# boundary that stopped reporting :end-turn would keep every case green
# (delta-review MAJOR). So the generated expressions are captured verbatim and
# EVALUATED against fixture states, under babashka, with the poll's sleep
# shortened (the predicate is what is under test, not the 250ms).
# ---------------------------------------------------------------------------
if ! command -v bb > /dev/null; then
    echo "FAIL [semantics]: babashka is not installed, so the generated expressions were never evaluated"
    fails=$((fails+1))
else
    reset_stub; export STUB_OPP_BOUNDARY="corp/false" STUB_RUN=true
    run runner find-card "Sure Gamble"   # captures both expressions
    BOUNDARY_EXPR=$(sed 's|Thread/sleep 250|Thread/sleep 1|' "$STUB_BOUNDARY_EXPR")
    RUN_EXPR=$(cat "$STUB_RUN_EXPR")

    # eval_expr FIXTURE-EDN EXPR -> what the expression prints
    eval_expr() {
        bb -e "(create-ns 'ai-state)
               (intern 'ai-state 'client-state (atom $1))
               (intern 'ai-state 'my-side-kw (fn [s] (keyword (:side s))))
               (println (str \"OUT \" (with-out-str (eval (read-string \"$(printf '%s' "$2" | sed 's/\\/\\\\/g; s/"/\\"/g')\")))))" 2>&1 \
          | sed -n 's/^OUT //p' | tr -d '\n'
    }
    semantic() {  # NAME FIXTURE EXPR EXPECTED
        local got; got=$(eval_expr "$2" "$3")
        got="${got//FIND-CARD-BOUNDARY /}"; got="${got//FIND-CARD-RUN /}"
        [[ "$got" == "$4" ]]; check "$1" $? "the generated expression printed '$got', expected '$4'"
    }

    # The boundary expression must report BOTH the active player and the engine's
    # :end-turn, lowercased — that pair is the whole contract with the shell.
    semantic "semantics-boundary-at-boundary" \
        '{:side "runner" :game-state {:active-player "Runner" :end-turn true}}' "$BOUNDARY_EXPR" "runner/true"
    semantic "semantics-boundary-mid-turn" \
        '{:side "runner" :game-state {:active-player "Runner" :end-turn false}}' "$BOUNDARY_EXPR" "runner/false"
    semantic "semantics-boundary-other-side" \
        '{:side "runner" :game-state {:active-player "Corp" :end-turn true}}' "$BOUNDARY_EXPR" "corp/true"

    # The run probe must answer "is a run LIVE", not "is :run non-nil" — #221
    # leaves {:corp-auto-no-action …} sitting in :run for the rest of the game.
    semantic "semantics-run-live" \
        '{:side "runner" :game-state {:run {:server ["R&D"] :position 0 :phase :approach-ice}}}' "$RUN_EXPR" "true"
    semantic "semantics-run-phantom-221" \
        '{:side "runner" :game-state {:run {:corp-auto-no-action true}}}' "$RUN_EXPR" "false"
    semantic "semantics-run-none" \
        '{:side "runner" :game-state {:run nil}}' "$RUN_EXPR" "false"
fi

if [[ "$fails" -gt 0 ]]; then echo "find_card_test: $fails failure(s)"; exit 1; fi
echo "find_card_test: all passed"
