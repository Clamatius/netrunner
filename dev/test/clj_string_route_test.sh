#!/usr/bin/env bash
# Drive the REAL dispatcher with a fake eval backend and pin what each arm that
# #255 touched actually emits. The sweep test is a spelling census; this is the
# behaviour. It compares the WHOLE action log, so an extra send of any shape (a
# second form, a send to the other seat, a positional call) fails the comparison.
#
# Two properties per arm:
#
#   INJECTION — a quote-bearing argument arrives as ONE Clojure string. Before
#   #255, `monitor-run '--x" (ai-actions/end-turn!) "'` emitted
#   `(ai-actions/monitor-run! "--x" (ai-actions/end-turn!) "")`, which ENDS THE
#   TURN, and `discard 'Cerberus "Lady" H1'` broke on a real card title.
#
#   BYTE IDENTITY — correct input emits exactly what it emitted before #255. The
#   five flag arms moved from a hand-rolled loop to `clj_str_args`, and a helper
#   that emitted a LEADING space would have changed `discard`'s bytes. Design
#   review caught that as a plan that contradicted its own requirement; this is
#   the assertion that would have caught it in code.
#
# Everything runs TWICE: once under the interpreter `#!/usr/bin/env bash` finds
# (Homebrew bash 5.x here) and once under /bin/bash, which on macOS is 3.2.57.
# Nothing else in the suite runs send_command under 3.2, so a helper that relies
# on bash 4+ behaviour would pass `make verify` and break on a stock macOS shell.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)" || exit 1
SEND_CMD="$SCRIPT_DIR/../send_command"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-clj-route.XXXXXX")" || exit 1
[[ -n "$TMP" && -d "$TMP" ]] || { echo "FAIL: could not create a temp dir"; exit 1; }
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats" SEND_COMMAND_LOG="$TMP/commands.log"

# Same stub contract as choose_label_route_test.sh: log every eval it is not
# there to answer, in both call forms, and fail CLOSED on an unrecognised
# positional call rather than swallowing it.
cat > "$TMP/eval" <<'STUB' || exit 1
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then
    expr="$(cat)"
    printf '%s %s|%s\n' "${2:-<none>}" "${3:-<none>}" "$expr" >> "$ACTION_LOG"
    printf 'nil\n'
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
SHELL_LABEL=""
check() {
    local name="$1" actual="$2" expected="$3"
    if [[ "$actual" == "$expected" ]]; then
        echo "ok   [$name$SHELL_LABEL]"
    else
        printf 'NOT OK [%s%s]\n expected: %q\n      got: %q\n' "$name" "$SHELL_LABEL" "$expected" "$actual"
        fail=$((fail + 1))
    fi
}

INTERP=""   # empty = the script's own shebang; else an explicit interpreter
actions=""
code=0
run() {
    local side="$1"; shift
    export ACTION_LOG="$TMP/actions.log"
    : > "$ACTION_LOG"
    if [[ -n "$INTERP" ]]; then
        NR_NO_AUTO_PROMPT=1 SHOW_LAST_LOG=0 AI_EVAL="$TMP/eval" \
            "$INTERP" "$SEND_CMD" "$side" "$@" > "$TMP/output" 2>&1
    else
        NR_NO_AUTO_PROMPT=1 SHOW_LAST_LOG=0 AI_EVAL="$TMP/eval" \
            "$SEND_CMD" "$side" "$@" > "$TMP/output" 2>&1
    fi
    code=$?
    actions="$(cat "$ACTION_LOG")"
}

# The payload used for every injection assertion. If escaping fails anywhere, the
# emitted expression gains a SECOND top-level form that ends the turn.
PAYLOAD='--x" (ai-actions/end-turn!) "'
PAYLOAD_ESCAPED='--x\" (ai-actions/end-turn!) \"'
# A real card title, not a crafted payload: 28 titles carry embedded quotes and
# discarding this one is an ordinary move.
LADY='Cerberus "Lady" H1'
LADY_ESCAPED='Cerberus \"Lady\" H1'

all_assertions() {
echo "--- flag arms: a quote-bearing flag is ONE string (#255) ---"
run runner run HQ "$PAYLOAD"
check 'run-flag-injection' "$actions" "runner 7889|(ai-actions/run! \"HQ\" \"$PAYLOAD_ESCAPED\")"
run runner monitor-run "$PAYLOAD"
check 'monitor-run-flag-injection' "$actions" "runner 7889|(ai-actions/monitor-run! \"$PAYLOAD_ESCAPED\")"
run runner continue "$PAYLOAD"
check 'continue-flag-injection' "$actions" "runner 7889|(ai-actions/monitor-run! \"$PAYLOAD_ESCAPED\")"
run runner continue --single "$PAYLOAD"
check 'continue-single-flag-injection' "$actions" "runner 7889|(ai-actions/continue-run! \"$PAYLOAD_ESCAPED\")"
run runner continue-run "$PAYLOAD"
check 'continue-run-flag-injection' "$actions" "runner 7889|(ai-actions/continue-run! \"$PAYLOAD_ESCAPED\")"

echo "--- flag arms: correct input emits exactly the pre-#255 bytes ---"
run runner run HQ
check 'run-no-flags' "$actions" 'runner 7889|(ai-actions/run! "HQ")'
run runner run HQ --patient
check 'run-one-flag' "$actions" 'runner 7889|(ai-actions/run! "HQ" "--patient")'
run runner run HQ --patient --fire-unbroken
check 'run-two-flags' "$actions" 'runner 7889|(ai-actions/run! "HQ" "--patient" "--fire-unbroken")'
run runner monitor-run
check 'monitor-run-no-flags' "$actions" 'runner 7889|(ai-actions/monitor-run!)'
run runner continue
check 'continue-no-flags' "$actions" 'runner 7889|(ai-actions/monitor-run!)'
run runner continue --single
check 'continue-single-no-flags' "$actions" 'runner 7889|(ai-actions/continue-run!)'
run runner continue-run
check 'continue-run-no-flags' "$actions" 'runner 7889|(ai-actions/continue-run!)'
run runner continue-run --fire-unbroken
check 'continue-run-one-flag' "$actions" 'runner 7889|(ai-actions/continue-run! "--fire-unbroken")'

echo "--- accumulator arms: a REAL quoted card title survives ---"
run runner discard "$LADY"
check 'discard-by-name-quoted-title' "$actions" "runner 7889|(ai-actions/discard-by-names! [\"$LADY_ESCAPED\" ])"
run runner discard "$LADY" 'Sure Gamble'
check 'discard-two-names-one-quoted' "$actions" \
    "runner 7889|(ai-actions/discard-by-names! [\"$LADY_ESCAPED\" \"Sure Gamble\" ])"
run runner discard 'Sure Gamble' 'Hedge Fund'
check 'discard-plain-names-bytes' "$actions" \
    'runner 7889|(ai-actions/discard-by-names! ["Sure Gamble" "Hedge Fund" ])'
run runner multi-choose "$LADY"
check 'multi-choose-quoted-title' "$actions" "runner 7889|(ai-actions/multi-choose! \"$LADY_ESCAPED\")"
run runner multi-choose 'Sure Gamble' 'Hedge Fund'
check 'multi-choose-plain-names-bytes' "$actions" \
    'runner 7889|(ai-actions/multi-choose! "Sure Gamble" "Hedge Fund")'
run runner multi-choose 0 2
check 'multi-choose-indices-bytes' "$actions" 'runner 7889|(ai-actions/multi-choose! 0 2)'

echo "--- a % in a title is data, not a printf format ---"
# clj_str_args builds its output with printf; a format string assembled from the
# value instead of from a literal would eat this.
run runner multi-choose '100% Sure'
check 'percent-in-title' "$actions" 'runner 7889|(ai-actions/multi-choose! "100% Sure")'
run runner discard '50%% Off'
check 'percent-in-discard-name' "$actions" 'runner 7889|(ai-actions/discard-by-names! ["50%% Off" ])'

echo "--- tank: the second, WRONG escaping spelling is gone (#255) ---"
# The deleted spelling escaped quotes without escaping backslashes first, so a
# trailing backslash escaped the CLOSING quote and swallowed the rest of the form.
run corp tank 'Ice Wall\'
case "$actions" in
    *'(let [ice-name "Ice Wall\\"'*) echo "ok   [tank-trailing-backslash$SHELL_LABEL]" ;;
    *) printf 'NOT OK [tank-trailing-backslash%s]\n got: %q\n' "$SHELL_LABEL" "$actions"; fail=$((fail + 1)) ;;
esac
run corp tank "$PAYLOAD"
case "$actions" in
    *"(let [ice-name \"$PAYLOAD_ESCAPED\""*) echo "ok   [tank-injection$SHELL_LABEL]" ;;
    *) printf 'NOT OK [tank-injection%s]\n got: %q\n' "$SHELL_LABEL" "$actions"; fail=$((fail + 1)) ;;
esac
run corp tank 'Ice Wall'
case "$actions" in
    *'(let [ice-name "Ice Wall"'*) echo "ok   [tank-plain-name-bytes$SHELL_LABEL]" ;;
    *) printf 'NOT OK [tank-plain-name-bytes%s]\n got: %q\n' "$SHELL_LABEL" "$actions"; fail=$((fail + 1)) ;;
esac

echo "--- lobby arms: all FOUR create-game positionals, select-deck, replay-save ---"
# All four are pass-through positionals with defaults, not a constrained set --
# the plan claimed otherwise and design review corrected it.
run corp create-game "$PAYLOAD" "$PAYLOAD" "$PAYLOAD" "$PAYLOAD"
for field in ':title' ':side' ':gateway-type' ':precon'; do
    case "$actions" in
        *"$field \"$PAYLOAD_ESCAPED\""*) echo "ok   [create-game-$field$SHELL_LABEL]" ;;
        *) printf 'NOT OK [create-game-%s%s]\n got: %q\n' "$field" "$SHELL_LABEL" "$actions"; fail=$((fail + 1)) ;;
    esac
done
run corp select-deck "$PAYLOAD"
case "$actions" in
    *"(conn/select-deck! \"$PAYLOAD_ESCAPED\")"*) echo "ok   [select-deck-injection$SHELL_LABEL]" ;;
    *) printf 'NOT OK [select-deck-injection%s]\n got: %q\n' "$SHELL_LABEL" "$actions"; fail=$((fail + 1)) ;;
esac
run corp replay-save "$PAYLOAD"
case "$actions" in
    *"(state/save-replay! \"$PAYLOAD_ESCAPED\")"*) echo "ok   [replay-save-injection$SHELL_LABEL]" ;;
    *) printf 'NOT OK [replay-save-injection%s]\n got: %q\n' "$SHELL_LABEL" "$actions"; fail=$((fail + 1)) ;;
esac

echo "--- change: \$KEY is a BARE Clojure keyword, so it is gated by SHAPE ---"
# Found by the bare-interpolation census, in an arm no report named. Before the
# gate, this emitted TWO top-level forms and the second ended the turn.
run corp change 'credit) (ai-actions/end-turn!) (comment' 5
check 'change-key-injection-refused-exit' "$code" '1'
check 'change-key-injection-sends-nothing' "$actions" ''
# A refusal that does not say WHAT was wrong is a wedge: a loop gates on the
# boolean and the seat never learns which argument to fix.
case "$(cat "$TMP/output")" in
    *'state key'*'credit) (ai-actions/end-turn!) (comment'*) echo "ok   [change-refusal-names-the-key$SHELL_LABEL]" ;;
    *) printf 'NOT OK [change-refusal-names-the-key%s]\n got: %q\n' "$SHELL_LABEL" "$(cat "$TMP/output")"; fail=$((fail + 1)) ;;
esac
# Every key the engine dispatches on must still be accepted (authority:
# game.core.change-vals/change).
for k in credit click memory hand-size tag bad-publicity agenda-point link; do
    run corp change "$k" 1
    check "change-engine-key-$k" "$actions" "corp 7890|(ai-actions/change! :$k 1)"
done
run corp change credit 5
check 'change-plain-key-bytes' "$actions" 'corp 7890|(ai-actions/change! :credit 5)'
run corp change agenda-point -2
check 'change-kebab-key-signed-delta' "$actions" 'corp 7890|(ai-actions/change! :agenda-point -2)'

echo "--- an ordinary card action is untouched (regression floor) ---"
run runner play 'Sure Gamble'
check 'play-plain-card' "$actions" 'runner 7889|(ai-actions/play-card! "Sure Gamble")'
run runner play "$LADY"
check 'play-quoted-card' "$actions" "runner 7889|(ai-actions/play-card! \"$LADY_ESCAPED\")"
}

echo "=== interpreter: the script's own shebang ($(bash --version | head -1 | sed 's/.*version //;s/ .*//')) ==="
INTERP="" SHELL_LABEL=""
all_assertions

if [[ -x /bin/bash ]]; then
    echo
    echo "=== interpreter: /bin/bash ($(/bin/bash --version | head -1 | sed 's/.*version //;s/ .*//')) ==="
    INTERP=/bin/bash SHELL_LABEL=" @/bin/bash"
    all_assertions
else
    echo "skip [/bin/bash run] (/bin/bash is not executable here)"
fi

# ---------------------------------------------------------------------------
# The enumeration, shell half. Drive EVERY command the dispatcher has with a
# quote-bearing payload and assert the payload's marker is never followed by an
# UNESCAPED quote in anything sent.
#
# Why this exists alongside the source censuses in clj_string_sweep_test.sh: four
# review rounds put their MAJORs on those censuses, and the last one found five
# more shapes they cannot see (a bare positional, a captured execute, a
# Clojure-escaped quote inside a string, a single-quoted expression with shell
# concatenation, and reuse of an already-whitelisted variable name). Those are not
# one defect; they are five members of an open set, because a per-line text scan
# of shell source is being asked a data-flow question. This looks at the OUTPUT
# instead, so quoting style, accumulators, heredocs, printf, positionals and name
# reuse are all invisible to it — in the good sense.
#
# The command list comes from send_command's own `case` arms, so a NEW arm is
# covered the moment it is added. The precise version of this (does the value
# arrive as ONE Clojure string, by identity) lives in the lein suite, which has a
# reader; this half is the fast one and carries the mutation battery.
# ---------------------------------------------------------------------------
MARKER='NRPAYLOAD'
ENUM_PAYLOAD="$MARKER\" (ai-actions/end-turn!) \""

dispatcher_commands() {   # FILE
    # The MAIN case (the second one; the first is a two-line help shortcut), from
    # `case "$COMMAND" in` to its `esac`. An extraction that matched nothing would
    # report a clean sweep over no commands, so the caller checks the count.
    awk '
        /^case "\$COMMAND" in$/ { n++; if (n == 2) { inmain = 1; next } }
        inmain && /^esac$/ { inmain = 0 }
        inmain && /^    [a-z][a-zA-Z0-9|?*_-]*\)$/ { gsub(/[ )]/, ""); print }
    ' "$1" | tr '|' '\n' | grep -vE '^(help|eval)$' | sort -u
}

# `eval` is excluded by NAME above: sending seat text as source is its entire
# purpose. It is witnessed in the lein suite (eval-really-does-send-source), so
# the exception cannot go stale into a hole.

enumerate() {   # enumerate SEND_CMD  -> prints one line per leaking command
    local sc="$1" c out log
    log="$TMP/enum.log"
    for c in $(dispatcher_commands "$sc"); do
        : > "$log"
        ACTION_LOG="$log" NR_NO_AUTO_PROMPT=1 SHOW_LAST_LOG=0 AI_EVAL="$TMP/eval" \
            timeout 25 "$sc" corp "$c" "$ENUM_PAYLOAD" "$ENUM_PAYLOAD" >/dev/null 2>&1
        [[ $? -eq 124 ]] && { printf '%s: TIMED OUT\n' "$c"; continue; }
        if grep -q "$MARKER\"" "$log" 2>/dev/null; then
            printf '%s: the argument reached the eval with its quote UNESCAPED\n' "$c"
        fi
    done
}

echo
echo "--- every command in the dispatcher keeps a quoted argument as data ---"
ENUM_COUNT="$(dispatcher_commands "$SEND_CMD" | wc -l | tr -d ' ')"
if [[ "$ENUM_COUNT" -lt 80 ]]; then
    echo "NOT OK [enumeration-found-the-commands] only $ENUM_COUNT commands extracted —"
    echo "       a broken extraction reports a CLEAN sweep over nothing"
    fail=$((fail + 1))
else
    echo "ok   [enumeration-found-the-commands] ($ENUM_COUNT commands)"
fi
SHELL_LABEL=""
LEAKS="$(enumerate "$SEND_CMD")"
check 'enumeration-no-command-leaks' "$LEAKS" ''

echo "--- and it sees the five shapes the source censuses cannot (mutation tests) ---"
# Each of these was found by a code-review seat against clj_string_sweep_test.sh,
# reproduced, and is clean on BOTH censuses there. The enumeration catches all
# five, which is the whole argument for having it.
enum_mutant() {   # enum_mutant NAME ARM_BODY
    local name="$1" arm="$2" f="$TMP/enum_mutant"
    rm -f "$f"
    if ! python3 - "$SEND_CMD" "$f" "$arm" <<'PYENUM'
import sys
src, dst, arm = sys.argv[1:4]
s = open(src).read()
anchor = "    keep-hand)"
if anchor not in s:
    sys.exit("anchor arm not found in send_command")
open(dst, 'w').write(s.replace(anchor, arm + "\n" + anchor, 1))
PYENUM
    then
        echo "NOT OK [$name] could not build the mutant"
        fail=$((fail + 1)); return
    fi
    chmod +x "$f"
    if cmp -s "$SEND_CMD" "$f"; then
        echo "NOT OK [$name] MUTATION DID NOT APPLY - assertion proves nothing"
        fail=$((fail + 1)); return
    fi
    if [[ -n "$(enumerate "$f")" ]]; then
        echo "ok   [$name]"
    else
        echo "NOT OK [$name] the enumeration reported CLEAN over the injected defect"
        fail=$((fail + 1))
    fi
}

enum_mutant 'enum-mutation-bare-positional' \
    '    nr-probe)
        execute "(f $1)"
        ;;
'
enum_mutant 'enum-mutation-captured-execute' \
    '    nr-probe)
        RESULT="$(execute "(f $1)")"
        echo "$RESULT"
        ;;
'
enum_mutant 'enum-mutation-clojure-escaped-quote' \
    '    nr-probe)
        execute "(f \"pre\\\"$1\\\"post\")"
        ;;
'
enum_mutant 'enum-mutation-single-quote-concatenation' \
    '    nr-probe)
        execute '"'"'(f "'"'"'"$1"'"'"'")'"'"'
        ;;
'
enum_mutant 'enum-mutation-reuse-of-a-whitelisted-name' \
    '    nr-probe)
        N="$1"
        execute "(f $N)"
        ;;
'
# ...and a correctly written new arm must NOT be reported.
CLEAN_ARM="$TMP/enum_clean"
rm -f "$CLEAN_ARM"
python3 - "$SEND_CMD" "$CLEAN_ARM" '    nr-probe)
        execute "(f \"$(clj_str "$1")\")"
        ;;
' <<'PYENUM2'
import sys
src, dst, arm = sys.argv[1:4]
s = open(src).read()
open(dst, 'w').write(s.replace("    keep-hand)", arm + "\n    keep-hand)", 1))
PYENUM2
chmod +x "$CLEAN_ARM"
CLEAN_LEAKS="$(enumerate "$CLEAN_ARM")"
check 'enum-clean-arm-is-not-reported' "$CLEAN_LEAKS" ''

if ((fail)); then
    printf 'FAIL: %d clj-string route assertion(s)\n' "$fail"
    exit 1
fi
echo 'PASS: clj-string per-arm routing'
