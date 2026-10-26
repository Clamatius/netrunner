#!/usr/bin/env bash
# peer_status_test.sh — regression guard for dead-peer detection (heartbeat).
#
# Why this exists: an un-babysat cross-model game silently wedges when a guest
# seat's DRIVER dies (e.g. devin one-shot exit) while its REPL stays alive — from
# the other seat, an empty `wait` is indistinguishable from "opponent thinking".
# send_command now touches a per-side heartbeat on every invocation and reports
# the opponent's last-active age (`peer-status`, and a footer on wait/monitor-run).
# This test locks that logic. `peer-status` needs NO live REPL (reads local files
# only), so the whole flow is testable in isolation.
#
# Heartbeats are isolated to a temp dir via HEARTBEAT_DIR so the test never
# touches the real logs/.heartbeats.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

export HEARTBEAT_DIR
HEARTBEAT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/nr-heartbeat-test.XXXXXX")"
trap 'rm -rf "$HEARTBEAT_DIR"' EXIT

fails=0
assert_contains() {  # NAME  HAYSTACK  NEEDLE
    local name="$1" hay="$2" needle="$3"
    if [[ "$hay" == *"$needle"* ]]; then
        echo "ok   [$name]"
    else
        echo "FAIL [$name]: expected to contain '$needle', got:"; printf '%s\n' "$hay" | sed 's/^/    /'
        fails=$((fails+1))
    fi
}
assert_not_contains() {  # NAME  HAYSTACK  NEEDLE
    local name="$1" hay="$2" needle="$3"
    if [[ "$hay" != *"$needle"* ]]; then
        echo "ok   [$name]"
    else
        echo "FAIL [$name]: expected NOT to contain '$needle', got:"; printf '%s\n' "$hay" | sed 's/^/    /'
        fails=$((fails+1))
    fi
}

# 1. Cold start: runner has never acted → corp sees "no heartbeat yet".
OUT=$("$SEND_CMD" corp peer-status 2>&1)
assert_contains "cold-start-no-heartbeat" "$OUT" "opponent (runner): no heartbeat yet"
assert_contains "self-heartbeat-touched"  "$OUT" "you (corp): active"

# 2. Runner acts (peer-status touches its own side) → corp now sees runner alive.
"$SEND_CMD" runner peer-status >/dev/null 2>&1
OUT=$("$SEND_CMD" corp peer-status 2>&1)
assert_contains "fresh-peer-alive" "$OUT" "opponent (runner): active"
assert_not_contains "fresh-peer-not-silent" "$OUT" "SILENT"

# 3. Dead peer: backdate runner heartbeat past the threshold → "SILENT / disconnected".
BACKDATE="$(date -v-6M +%Y%m%d%H%M 2>/dev/null || date -d '6 min ago' +%Y%m%d%H%M)"
touch -t "$BACKDATE" "$HEARTBEAT_DIR/runner"
OUT=$(PEER_STALE_SECS=300 "$SEND_CMD" corp peer-status 2>&1)
assert_contains "stale-peer-flagged"      "$OUT" "SILENT"
assert_contains "stale-peer-disconnected" "$OUT" "likely disconnected"

# 4. Symmetry: from the runner's seat the opponent is the corp.
OUT=$("$SEND_CMD" runner peer-status 2>&1)
assert_contains "symmetry-opponent-is-corp" "$OUT" "opponent (corp)"

# 5. Threshold is honoured: a 6-min-old heartbeat is NOT stale under a 3600s bar.
OUT=$(PEER_STALE_SECS=3600 "$SEND_CMD" corp peer-status 2>&1)
assert_not_contains "threshold-respected" "$OUT" "SILENT"

# 6. clear-heartbeats zeroes the liveness clock (fresh-game path). Without this, a
#    heartbeat left over from a prior game (or a setup touch) reads as "alive" and
#    masks a guest driver that never actually connected — a FALSE-alive at exactly
#    the moment the sensor matters. reset.sh calls this so a new game starts honest.
"$SEND_CMD" corp peer-status >/dev/null 2>&1   # ensure both sides have heartbeats
"$SEND_CMD" runner peer-status >/dev/null 2>&1
[[ -f "$HEARTBEAT_DIR/corp" && -f "$HEARTBEAT_DIR/runner" ]] \
    && echo "ok   [pre-clear-heartbeats-exist]" \
    || { echo "FAIL [pre-clear-heartbeats-exist]: heartbeat files missing before clear"; fails=$((fails+1)); }
CLR=$("$SEND_CMD" clear-heartbeats 2>&1)
assert_contains "clear-reports-done" "$CLR" "cleared"
[[ ! -f "$HEARTBEAT_DIR/corp" && ! -f "$HEARTBEAT_DIR/runner" ]] \
    && echo "ok   [post-clear-heartbeats-gone]" \
    || { echo "FAIL [post-clear-heartbeats-gone]: heartbeat files still present after clear"; fails=$((fails+1)); }
# After a clear, the opponent honestly reads as "not seen acting" (not a ghost alive).
OUT=$("$SEND_CMD" corp peer-status 2>&1)
assert_contains "post-clear-honest" "$OUT" "opponent (runner): no heartbeat yet"
assert_not_contains "post-clear-not-ghost-alive" "$OUT" "opponent (runner): active"

# 7. Wiring: the fresh-game path (reset.sh) actually clears heartbeats, else the
#    primitive above is dead code and the false-alive survives in real games.
assert_contains "reset-wires-clear" "$(cat "$SCRIPT_DIR/../reset.sh")" "clear-heartbeats"

# 8. Wiring: the `wait` handler suppresses the peer footer on a game-over wake, so
#    the "alive, keep waiting" footer can't contradict the "stop acting" game-over
#    line (verified behaviorally against a live game-over state; this guards the
#    suppression against regression-by-deletion since the shell harness has no
#    live in-progress game to drive a wait through).
SEND_SRC="$(cat "$SEND_CMD")"
assert_contains "wait-suppresses-footer-on-gameover" "$SEND_SRC" 'WAIT_OUT" != *"Game over"*'

# ── Keep-alive toucher (park mode, #31 Fix A) ────────────────────────────────
# `monitor-run --persistent` now PARKS for the opponent's whole turn, so one
# invocation can stay open for minutes. The heartbeat is touched at invocation,
# so without a keep-alive an ALIVE parked driver would age past PEER_STALE_SECS
# and be reported SILENT — a false dead-peer verdict telling the opponent to
# abandon a live game. send_command therefore runs a background toucher for the
# life of the invocation.
#
# The DANGEROUS failure mode of that toucher is the mirror image: if it outlives
# its parent, it keeps a DEAD driver's heartbeat fresh forever and the sensor can
# never report a real disconnect. It is bound to the parent via `kill -0`, which
# holds even under SIGKILL (where no EXIT trap runs). Both are asserted here.

# 1. No orphan: once the invocation exits, the heartbeat must STOP advancing.
HEARTBEAT_TOUCH_SECS=1 "$SEND_CMD" corp peer-status >/dev/null 2>&1 || true
before_mt="$(stat -f %m "$HEARTBEAT_DIR/corp" 2>/dev/null || stat -c %Y "$HEARTBEAT_DIR/corp" 2>/dev/null || echo 0)"
sleep 3
after_mt="$(stat -f %m "$HEARTBEAT_DIR/corp" 2>/dev/null || stat -c %Y "$HEARTBEAT_DIR/corp" 2>/dev/null || echo 0)"
if [[ "$before_mt" == "$after_mt" ]]; then
    echo "ok   [no-orphan-toucher-after-exit]"
else
    echo "FAIL [no-orphan-toucher-after-exit]: heartbeat kept advancing after send_command exited"
    echo "     (an orphaned toucher would keep a DEAD driver looking alive forever)"
    fails=$((fails+1))
fi

# 2. A SIGKILLed driver still goes stale: the toucher's parent-liveness guard is
#    `kill -0`, not an EXIT trap, so it must not survive a kill -9 of the parent.
assert_contains "toucher-bound-to-parent-liveness" "$SEND_SRC" 'kill -0 "$_hb_parent"'

# 3. The toucher must NOT hold the caller's stdout pipe open.
#    Nearly every caller reads send_command via command substitution, which blocks
#    until EOF — and EOF needs the LAST writer to close. The first cut of the
#    toucher inherited stdout, so `OUT="$(send_command corp peer-status)"` blocked
#    for the full HEARTBEAT_TOUCH_SECS (measured: 60s) on EVERY command. The unit
#    assertions above still passed — just slowly — so nothing but a TIMING check
#    catches this. Uses the default touch interval on purpose: overriding it to
#    something small is exactly what masked the bug.
cs_start=$(date +%s)
CS_OUT="$("$SEND_CMD" corp peer-status 2>&1)"
cs_elapsed=$(( $(date +%s) - cs_start ))
if [[ $cs_elapsed -lt 10 ]]; then
    echo "ok   [cmd-substitution-not-blocked-by-toucher] (${cs_elapsed}s)"
else
    echo "FAIL [cmd-substitution-not-blocked-by-toucher]: took ${cs_elapsed}s"
    echo "     The heartbeat toucher is holding stdout open — every send_command read"
    echo "     via \$(...) blocks until it exits. Redirect the subshell's stdio."
    fails=$((fails+1))
fi
assert_contains "cmd-substitution-still-works" "$CS_OUT" "you (corp): active"

# ---------------------------------------------------------------------------
# #286: bot-loop must hand the REPL-side loop its own heartbeat path.
#
# The toucher forked above is bound to THIS invocation (`kill -0 $$`), and
# bot-loop launches a detached future in the REPL and RETURNS — so the clock
# stopped while the heuristic bot played on, and 300s later every waiting
# command told the opponent the bot had disconnected and to `conclude`, in the
# same output that showed it had just acted.
#
# Why these assertions live here and not in send_command_clj_string_test.sh:
# that sweep matches "$VAR" literally inside an `execute` line, and LOOP_ARGS is
# assembled separately and interpolated whole, so the sweep is structurally
# blind to this site. These are the only guard, including for the escaping.
BOT_STUB="$HEARTBEAT_DIR/stub-eval.sh"
# BOT_STUB_VERDICT makes the stub answer ensure_connection's sync-verdict probe
# with a chosen verdict. Without it the stub answers every eval with
# STUB-EXECUTED, so ensure_connection's sed finds no SYNC-VERDICT token, the
# verdict parses EMPTY and no case arm matches — which means the gate is never
# reached and a test cannot tell `ensure_connection` from
# `ensure_connection --allow-dead`. A review seat reverted bot-loop's
# --allow-dead and watched this file and game_gone_gate_test.sh both stay green:
# the suite was resting on the absence of the change.
cat > "$BOT_STUB" <<'STUBEOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then expr="$(cat)"; else expr="${!#}"; fi
printf '%s\n' "$expr" >> "$BOT_STUB_LOG"
if [[ -n "${BOT_STUB_VERDICT:-}" && "$expr" == *"sync-verdict!"* ]]; then
    printf 'SYNC-VERDICT %s\n' "$BOT_STUB_VERDICT"
    exit 0
fi
printf 'STUB-EXECUTED\n'
STUBEOF
chmod +x "$BOT_STUB"
export BOT_STUB_LOG="$HEARTBEAT_DIR/bot-stub.log"

# drive_bot_loop [env-prefixed args...] — run bot-loop against the stub and put
# the captured expression in BOT_EXPR.
drive_bot_loop() { drive_bot_loop_side corp "$@"; }

# drive_bot_loop_side SIDE [args...] — the same, for either seat. #290 routes the
# match harness's four launches (two sides x two agent types) through this arm, so
# every assertion below has to be made per side as well as per agent: a corp arm
# that named the RUNNER's namespace, or vice versa, would pass a corp-only test.
BOT_STATUS=0
drive_bot_loop_side() {
    local side="$1"; shift
    : > "$BOT_STUB_LOG"
    BOT_STATUS=0
    AI_EVAL="$BOT_STUB" "$SEND_CMD" "$side" bot-loop "$@" >/dev/null 2>&1 || BOT_STATUS=$?
    BOT_EXPR="$(cat "$BOT_STUB_LOG")"
}

# count_in HAYSTACK NEEDLE — occurrences, so "exactly one launch form" is
# assertable. grep -o -F, not a bash loop: the needle holds Clojure punctuation.
count_in() { printf '%s\n' "$1" | grep -o -F -- "$2" | wc -l | tr -d ' '; }

# bot_form_reached — did the bot-loop form actually get to the eval backend?
#
# This gate exists because a code-review seat ran this file in a sandbox that
# denied `execute`'s mktemp: the stub log then held only ensure_connection's
# probes, and the assertions below reported "a quote in HEARTBEAT_DIR was not
# escaped" and "expected :patient? true" for a failure that had nothing to do
# with either. The misleading-output class, inside the test written to catch
# one. Round 3 then caught that merely PRINTING a precondition failure was half
# a fix — the dependent assertions still ran and still blamed the path — and
# that the check had been copy-pasted into three drifting shapes. So it is one
# function, and every dependent assertion is GATED on it.
bot_form_reached() {
    if [[ "$BOT_EXPR" == *"ai-self-play-future"* ]]; then
        return 0
    fi
    echo "FAIL [$1]: the bot-loop form never reached the eval backend, so this assertion"
    echo "     says NOTHING about :heartbeat-file or its escaping. Something failed between"
    echo "     ensure_connection and execute (a denied mktemp does exactly this). stub log:"
    printf '%s\n' "$BOT_EXPR" | sed 's/^/    /'
    fails=$((fails+1))
    return 1
}

# gated_contains NAME NEEDLE — assert only if the form reached the backend.
gated_contains() {
    bot_form_reached "$1" || return 0
    assert_contains "$1" "$BOT_EXPR" "$2"
}

drive_bot_loop --patient 10
if bot_form_reached "bot-loop-form-reached-the-backend"; then
    echo "ok   [bot-loop-form-reached-the-backend]"
fi
gated_contains "bot-loop-passes-heartbeat-file" ":heartbeat-file"
gated_contains "bot-loop-passes-this-sides-path" "$HEARTBEAT_DIR/corp"
# The existing option must survive beside it (a clobbered LOOP_ARGS would make
# the patient defender impatient and nothing else would notice).
gated_contains "bot-loop-keeps-patient-args" ":patient? true"

# ABSOLUTE, and absolute to the RIGHT path. The REPL resolves this string
# against its own working directory — a different tree — so a relative path has
# bash touching one file while the loop refreshes another: #286 again. Asserting
# only a leading "/" is not enough (round 3): the mutant HB_FILE="/$HB_FILE"
# hands the REPL /rel-hb-N/corp while bash touches $PWD/rel-hb-N/corp and still
# starts with "/". The expected path is known exactly, so assert the string.
REL_DIR="rel-hb-$$"
mkdir -p "$HEARTBEAT_DIR/$REL_DIR" && touch "$HEARTBEAT_DIR/$REL_DIR/corp"
# Expected path comes from `pwd`, not from $HEARTBEAT_DIR: mktemp -d inherits
# TMPDIR's trailing slash and yields ".../T//nr-heartbeat-test.XXXX", while the
# resolution under test uses $PWD, which is normalised. Comparing against the
# raw variable fails on a double slash that is the TEST's artifact, not a defect.
REL_EXPECT="$(cd "$HEARTBEAT_DIR" && pwd)/$REL_DIR/corp"
: > "$BOT_STUB_LOG"
( cd "$HEARTBEAT_DIR" && HEARTBEAT_DIR="$REL_DIR" AI_EVAL="$BOT_STUB" "$SEND_CMD" corp bot-loop >/dev/null 2>&1 ) || true
BOT_EXPR="$(cat "$BOT_STUB_LOG")"
gated_contains "bot-loop-resolves-relative-path-to-the-same-file" \
    ":heartbeat-file \"$REL_EXPECT\""

# Escaping: HEARTBEAT_DIR is a caller-settable seam, so a path containing a
# double quote must not close the Clojure string and execute. #255 is open
# against exactly this shape elsewhere in the dispatcher.
QDIR="$HEARTBEAT_DIR/q\"uote"
mkdir -p "$QDIR" 2>/dev/null || true
touch "$QDIR/corp" 2>/dev/null || true
: > "$BOT_STUB_LOG"
HEARTBEAT_DIR="$QDIR" AI_EVAL="$BOT_STUB" "$SEND_CMD" corp bot-loop >/dev/null 2>&1 || true
BOT_EXPR="$(cat "$BOT_STUB_LOG")"
if bot_form_reached "bot-loop-escapes-quote-in-path"; then
    if [[ "$BOT_EXPR" == *':heartbeat-file "'*'\"uote/corp"'* ]]; then
        echo "ok   [bot-loop-escapes-quote-in-path]"
    else
        echo "FAIL [bot-loop-escapes-quote-in-path]: a quote in HEARTBEAT_DIR was not escaped, got:"
        printf '%s\n' "$BOT_EXPR" | sed 's/^/    /'
        fails=$((fails+1))
    fi
fi

# ---------------------------------------------------------------------------
# #290: --agent routes the PASSIVE (goldfish) bots through this same arm.
#
# The mutant a design seat described, and the reason these assertions are shaped
# the way they are: give the goldfish case its own `execute` line, copied from the
# hand-rolled injection run-match.sh used to carry, with no $LOOP_ARGS. A test
# that only looked for the namespace NAME would pass; peer_status_test's older
# assertions pass too (they drive bare bot-loop, corp only); and the Clojure
# kwarg tests pass because the kwarg exists. The passive seats would then go
# stale exactly as #290 describes, with a green suite.
#
# So for each (side, agent): exactly ONE launch form, carrying BOTH the namespace
# it claims to launch AND that side's heartbeat path — and not the other agent's
# namespace.
assert_launch_form() {   # SIDE  AGENT  [TAG]   (ns/fn and path are derived)
    local side="$1" agent="$2" ns fn other
    case "$side" in
        corp)   fn="start-autonomous!" ;;
        runner) fn="loop!" ;;
    esac
    case "$agent" in
        heuristic) ns="ai-heuristic-$side"; other="ai-goldfish-$side" ;;
        goldfish|passive) ns="ai-goldfish-$side"; other="ai-heuristic-$side" ;;
    esac
    local tag="$side/${3:-$agent}"
    bot_form_reached "launch-form-$tag" || return 0
    assert_contains "launch-form-names-the-agents-ns [$tag]" "$BOT_EXPR" "(quote $ns/$fn)"
    assert_not_contains "launch-form-excludes-the-other-agent [$tag]" "$BOT_EXPR" "$other"
    assert_contains "launch-form-carries-this-sides-heartbeat [$tag]" "$BOT_EXPR" \
        ":heartbeat-file \"$HEARTBEAT_DIR/$side\""
    local n
    n="$(count_in "$BOT_EXPR" ':heartbeat-file')"
    if [[ "$n" == "1" ]]; then
        echo "ok   [exactly-one-launch-form-with-the-kwarg [$tag]]"
    else
        echo "FAIL [exactly-one-launch-form-with-the-kwarg [$tag]]: found $n occurrences of"
        echo "     :heartbeat-file, expected exactly 1. Two forms means a second execute;"
        echo "     zero means this agent's arm emits a form that drops LOOP_ARGS (#290)."
        printf '%s\n' "$BOT_EXPR" | sed 's/^/    /'
        fails=$((fails+1))
    fi
}

for side in corp runner; do
    drive_bot_loop_side "$side"
    assert_launch_form "$side" heuristic no-flag         # the default is unchanged
    drive_bot_loop_side "$side" --agent heuristic
    assert_launch_form "$side" heuristic
    drive_bot_loop_side "$side" --agent goldfish
    assert_launch_form "$side" goldfish
    drive_bot_loop_side "$side" --agent passive          # the harness's own word
    assert_launch_form "$side" passive
done

# The loop fn is picked by SIDE, not by agent, and the goldfish namespaces happen
# to carry the same two names. Pin that, so a future rename cannot quietly make
# both sides launch the same function.
drive_bot_loop_side runner --agent goldfish
gated_contains "goldfish-runner-launches-loop!" "ai-goldfish-runner/loop!"
drive_bot_loop_side corp --agent goldfish
gated_contains "goldfish-corp-launches-start-autonomous!" "ai-goldfish-corp/start-autonomous!"

# A typo must be REFUSED, and refused before anything reaches the REPL. The error
# message says "Nothing was sent to the REPL" — that is a claim about state, so it
# is asserted rather than trusted: the stub log must be completely empty, which is
# only true because the flag is validated AHEAD of ensure_connection (a design
# seat found that validating after it cost a stale seat a board-clearing resync
# before the refusal).
drive_bot_loop_side corp --agent nonsense
if [[ "$BOT_STATUS" -ne 0 ]]; then
    echo "ok   [unknown-agent-is-refused]"
else
    echo "FAIL [unknown-agent-is-refused]: bot-loop --agent nonsense exited 0"
    fails=$((fails+1))
fi
if [[ -z "$BOT_EXPR" ]]; then
    echo "ok   [unknown-agent-reaches-no-repl]"
else
    echo "FAIL [unknown-agent-reaches-no-repl]: the refusal claims nothing was sent, but"
    echo "     the eval backend was called:"
    printf '%s\n' "$BOT_EXPR" | sed 's/^/    /'
    fails=$((fails+1))
fi
# ...and --agent with no value at all is its own arm, so it gets its own check:
# "unknown --agent ''" would be a worse sentence than "needs a value".
drive_bot_loop_side corp --agent
if [[ "$BOT_STATUS" -ne 0 && -z "$BOT_EXPR" ]]; then
    echo "ok   [agent-with-no-value-is-refused-before-the-repl]"
else
    echo "FAIL [agent-with-no-value-is-refused-before-the-repl]: status=$BOT_STATUS, stub log:"
    printf '%s\n' "$BOT_EXPR" | sed 's/^/    /'
    fails=$((fails+1))
fi
EMPTY_ERR="$(AI_EVAL="$BOT_STUB" "$SEND_CMD" corp bot-loop --agent 2>&1 || true)"
assert_contains "agent-with-no-value-says-so" "$EMPTY_ERR" "--agent needs a value"

ERR="$(AI_EVAL="$BOT_STUB" "$SEND_CMD" corp bot-loop --agent nonsense 2>&1 || true)"
assert_contains "unknown-agent-lists-what-is-accepted" "$ERR" "heuristic"
assert_contains "unknown-agent-names-goldfish-too"     "$ERR" "goldfish"

# --patient on a goldfish loop is accepted and does nothing (kwarg destructuring
# drops what it does not name), so it has to SAY so. Silently-ignored flag is the
# class this project files bugs about.
PATIENT_OUT="$(AI_EVAL="$BOT_STUB" "$SEND_CMD" corp bot-loop --agent goldfish --patient 5 2>&1 || true)"
assert_contains "patient-on-goldfish-warns" "$PATIENT_OUT" "no effect on the 'goldfish' loop"
# ...and still launches, rather than refusing: a wedge for no safety gain.
BOT_EXPR="$(cat "$BOT_STUB_LOG")"
gated_contains "patient-on-goldfish-still-launches" "ai-goldfish-corp/start-autonomous!"

# The namespace must be resolved OUTSIDE the future. Inside it, a namespace that
# fails to load has its exception captured by the future and surfaced only on a
# deref nobody performs — after the launch line has printed, so the harness reads
# a successful injection and then times out with no bot (design-panel MAJOR,
# guest-unique). The hand-rolled injection this replaces loaded at the top level
# of its eval, and that property is kept.
drive_bot_loop_side corp --agent goldfish
if bot_form_reached "resolve-happens-outside-the-future"; then
    # The property is the SHAPE of the form, and two earlier versions of this
    # assertion got that wrong in opposite directions: first the absence of one
    # literal spelling, then (after a review finding) the textual ORDER of two
    # tokens — which a `delay` satisfies while deferring the resolve into the
    # future, exactly the defect being guarded against
    # (`(let [loop-fn (delay (or (requiring-resolve ...)))] ... (future (@loop-fn ...)))`).
    # So pin the two pieces the form must have: the binding is a DIRECT resolve,
    # and the future calls the already-bound fn. Whitespace is normalised first so
    # re-indenting the emitted string is not a failure.
    LAUNCH_FORM="$(printf '%s\n' "$BOT_EXPR" | tr '\n' ' ' | tr -s ' ')"
    assert_contains "resolve-is-bound-directly-not-deferred" "$LAUNCH_FORM" \
        '(let [loop-fn (or (requiring-resolve'
    assert_contains "future-calls-the-already-resolved-fn" "$LAUNCH_FORM" \
        '(future (loop-fn '
fi

# A resync-failed verdict must NOT stop the launch (bot-loop takes
# `ensure_connection --allow-dead` since #290). The reason is in send_command's
# own comment: run-match.sh injects both bots immediately after start-game, runs
# under `set -e`, and a board that had not landed yet refused with exit 4 and took
# the whole match run down with it — while the loops repair a boardless seat
# themselves on their first tick (loop-sync/ensure-board!).
#
# The DIFFERENTIAL is the point. A bare "bot-loop still launched" proves nothing
# unless the stub's verdict actually reaches the gate, so the same stub and the
# same verdict are first pointed at `dashboard`, which still takes a plain
# `ensure_connection`: that one MUST be refused. Then bot-loop's success is
# attributable to --allow-dead and not to a verdict that never arrived.
DASH_STATUS=0
: > "$BOT_STUB_LOG"
BOT_STUB_VERDICT=resync-failed AI_EVAL="$BOT_STUB" "$SEND_CMD" corp dashboard >/dev/null 2>&1 \
    || DASH_STATUS=$?
DASH_EXPR="$(cat "$BOT_STUB_LOG")"
if [[ "$DASH_STATUS" -eq 4 ]]; then
    echo "ok   [stub-verdict-reaches-the-gate (dashboard refused with 4)]"
else
    echo "FAIL [stub-verdict-reaches-the-gate]: a gated command got status $DASH_STATUS,"
    echo "     expected 4 (refuse_no_state on resync-failed). Until this holds, the"
    echo "     bot-loop assertion below says NOTHING about --allow-dead — that is how"
    echo "     the un-asserted version of this change shipped green."
    printf '%s\n' "$DASH_EXPR" | sed 's/^/    /'
    fails=$((fails+1))
fi
assert_not_contains "gated-command-sent-nothing" "$DASH_EXPR" "ai-heuristic-corp :as bot"

if [[ "$DASH_STATUS" -eq 4 ]]; then
    RF_STATUS=0
    : > "$BOT_STUB_LOG"
    BOT_STUB_VERDICT=resync-failed AI_EVAL="$BOT_STUB" "$SEND_CMD" corp bot-loop --agent goldfish \
        >/dev/null 2>&1 || RF_STATUS=$?
    BOT_EXPR="$(cat "$BOT_STUB_LOG")"
    if [[ "$RF_STATUS" -eq 0 ]]; then
        echo "ok   [resync-failed-does-not-refuse-bot-loop]"
    else
        echo "FAIL [resync-failed-does-not-refuse-bot-loop]: exit $RF_STATUS. Under"
        echo "     run-match.sh's set -e this ends the whole match run on a board that"
        echo "     simply had not landed yet (#290)."
        fails=$((fails+1))
    fi
    gated_contains "resync-failed-still-launches" "ai-goldfish-corp/start-autonomous!"
    # ...and a GONE game is not special-cased into a refusal either: same arm.
    GG_STATUS=0
    : > "$BOT_STUB_LOG"
    BOT_STUB_VERDICT=game-gone AI_EVAL="$BOT_STUB" "$SEND_CMD" corp bot-loop >/dev/null 2>&1 \
        || GG_STATUS=$?
    BOT_EXPR="$(cat "$BOT_STUB_LOG")"
    if [[ "$GG_STATUS" -eq 0 ]]; then
        echo "ok   [game-gone-does-not-refuse-bot-loop]"
    else
        echo "FAIL [game-gone-does-not-refuse-bot-loop]: exit $GG_STATUS — the loop stops"
        echo "     itself with a diagnostic when it cannot repair a board; the refusal"
        echo "     here only costs the match run."
        fails=$((fails+1))
    fi
    gated_contains "game-gone-still-launches" "ai-heuristic-corp/start-autonomous!"
fi

# ---------------------------------------------------------------------------
# #290 wiring: the match harness must actually CALL this arm.
#
# A working --agent plus a correct emission still leaves #290 live if
# run-match.sh goes on hand-rolling its futures — #109's shape, and the finding a
# guest seat alone made on the #286 cycle. load_agent is not sourceable
# (run-match.sh parses a config file at top level), so this is structural: it
# anchors on the agent-type case labels INSIDE load_agent, so an ADDED arm is
# visible instead of slipping under a pinned count (#260).
RUN_MATCH="$SCRIPT_DIR/../orchestrator/run-match.sh"
LOAD_AGENT_FN="$(awk '/^load_agent\(\) \{/,/^\}/' "$RUN_MATCH")"
if [[ -z "$LOAD_AGENT_FN" ]]; then
    echo "FAIL [run-match-load_agent-found]: could not extract load_agent from $RUN_MATCH —"
    echo "     every assertion below would vacuously pass, so this is the gate."
    fails=$((fails+1))
else
    echo "ok   [run-match-load_agent-found]"
    assert_contains "run-match-launches-via-bot-loop" "$LOAD_AGENT_FN" 'bot-loop --agent "$agent_flag"'
    assert_not_contains "run-match-hand-rolls-no-future" "$LOAD_AGENT_FN" '(future (ai-'
    assert_not_contains "run-match-does-not-bypass-send_command" "$LOAD_AGENT_FN" 'ai-eval.sh'
    # Every agent label the harness accepts must map to an --agent value. The
    # labels are the anchor: a NEW one with no mapping shows up as a miss here.
    for label in heuristic passive goldfish; do
        if [[ "$LOAD_AGENT_FN" == *"\"$label\""* ]]; then
            echo "ok   [run-match-knows-agent-label: $label]"
        else
            echo "FAIL [run-match-knows-agent-label: $label]: load_agent no longer handles '$label'"
            fails=$((fails+1))
        fi
    done
    assert_contains "run-match-maps-passive-to-goldfish" "$LOAD_AGENT_FN" 'agent_flag="goldfish"'
    assert_contains "run-match-maps-heuristic"           "$LOAD_AGENT_FN" 'agent_flag="heuristic"'
    # The side is passed through, not derived twice, and no port is passed: a port
    # argument could only be a second copy of send_command's fixed side->port map.
    assert_contains "run-match-passes-the-side" "$LOAD_AGENT_FN" '"$DEV_DIR/send_command" "$side" bot-loop'
    CALLS="$(grep -n 'load_agent "' "$RUN_MATCH")"
    assert_contains "run-match-calls-corp-arm"   "$CALLS" 'load_agent "corp" "$corp_agent"'
    assert_contains "run-match-calls-runner-arm" "$CALLS" 'load_agent "runner" "$runner_agent"'
    # BOTH call lines, by shape. The earlier version asserted only that "7890" was
    # absent, so reinstating the port on the RUNNER line alone passed it — and my
    # own mutant used 7890, so it confirmed the one case it covered.
    for n in 7889 7890; do
        assert_not_contains "run-match-passes-no-port: $n" "$CALLS" "$n"
    done
    # Occurrences, not lines: both calls sit on ONE line now, inside the failure
    # guard. Counting lines made this read 1 and fail for a correct tree.
    CALL_COUNT="$(count_in "$CALLS" 'load_agent "')"
    if [[ "$CALL_COUNT" == "2" ]]; then
        echo "ok   [run-match-calls-load_agent-exactly-twice]"
    else
        echo "FAIL [run-match-calls-load_agent-exactly-twice]: found $CALL_COUNT calls"
        printf '%s\n' "$CALLS" | sed 's/^/    /'
        fails=$((fails+1))
    fi
    # send_command exiting 0 is not a launch: the relaunch guard prints and exits
    # 0, so the harness checks for the confirmation line.
    assert_contains "run-match-requires-the-launch-line" "$LOAD_AGENT_FN" 'Autonomous loop launched'

    # ...and that assertion is the presence of a STRING, which a review seat
    # showed is not the same as the gate working: replacing the confirmation
    # case's `return 1` with a no-op left this whole file green (reproduced).
    # So DRIVE the function. It cannot be sourced from run-match.sh (that script
    # parses a config file at top level), but the text extracted above is a
    # complete function definition, so write it out, source THAT in a subshell,
    # and point its $DEV_DIR at a stub send_command. No production code changes
    # to make it testable.
    LA_LIB="$HEARTBEAT_DIR/load_agent.sh"
    printf '%s\n' "$LOAD_AGENT_FN" > "$LA_LIB"
    # The extraction is an awk range, so a `}` at column 0 INSIDE the function
    # would truncate it and the driver below would source a fragment. Prove we
    # got a whole function before trusting anything it does: parseable, and the
    # name is actually defined afterwards.
    if bash -n "$LA_LIB" 2>/dev/null \
       && bash -c 'source "$1"; declare -F load_agent >/dev/null' _ "$LA_LIB"; then
        echo "ok   [load_agent-extracted-whole]"
    else
        echo "FAIL [load_agent-extracted-whole]: the awk extraction did not yield a"
        echo "     complete, parseable function, so every assertion below would be"
        echo "     testing a fragment. Extracted:"
        sed 's/^/    /' "$LA_LIB"
        fails=$((fails+1))
    fi
    mkdir -p "$HEARTBEAT_DIR/devstub"
    cat > "$HEARTBEAT_DIR/devstub/send_command" <<'SCEOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SC_ARGS_LOG"
printf '%s\n' "${SC_STUB_OUT:-}"
exit "${SC_STUB_RC:-0}"
SCEOF
    chmod +x "$HEARTBEAT_DIR/devstub/send_command"
    export SC_ARGS_LOG="$HEARTBEAT_DIR/sc-args.log"

    # drive_load_agent SIDE AGENT  — runs load_agent against the stub.
    # LA_STATUS is its return value, LA_OUT its output, LA_ARGS what the stub saw.
    drive_load_agent() {
        : > "$SC_ARGS_LOG"
        LA_STATUS=0
        LA_OUT="$(DEV_DIR="$HEARTBEAT_DIR/devstub" bash -c '
            source "$1"; load_agent "$2" "$3"' _ "$LA_LIB" "$1" "$2" 2>&1)" || LA_STATUS=$?
        LA_ARGS="$(cat "$SC_ARGS_LOG")"
    }

    # 1. A launch that confirms itself succeeds, and the flag is passed through.
    SC_STUB_OUT="🔁 Autonomous loop launched for corp (ai-heuristic-corp/start-autonomous!)"
    export SC_STUB_OUT SC_STUB_RC=0
    drive_load_agent corp heuristic
    if [[ "$LA_STATUS" -eq 0 ]]; then
        echo "ok   [load_agent-accepts-a-confirmed-launch]"
    else
        echo "FAIL [load_agent-accepts-a-confirmed-launch]: returned $LA_STATUS"
        printf '%s\n' "$LA_OUT" | sed 's/^/    /'
        fails=$((fails+1))
    fi
    assert_contains "load_agent-passes-side-and-flag" "$LA_ARGS" "corp bot-loop --agent heuristic"

    # 2. THE GATE. The relaunch guard prints and exits 0; that is not a launch.
    SC_STUB_OUT="⚠️  Autonomous loop already running for corp - use 'bot-loop-stop' first"
    export SC_STUB_OUT SC_STUB_RC=0
    drive_load_agent corp heuristic
    if [[ "$LA_STATUS" -ne 0 ]]; then
        echo "ok   [load_agent-rejects-a-refused-relaunch]"
    else
        echo "FAIL [load_agent-rejects-a-refused-relaunch]: returned 0 for the relaunch"
        echo "     guard's own output. The harness would proceed as if a bot had started"
        echo "     and then time out in 600s with nothing playing that seat."
        fails=$((fails+1))
    fi

    # 3. A non-zero send_command is a failure even if it printed the magic words.
    SC_STUB_OUT="🔁 Autonomous loop launched for corp"
    export SC_STUB_OUT SC_STUB_RC=4
    drive_load_agent corp heuristic
    if [[ "$LA_STATUS" -ne 0 ]]; then
        echo "ok   [load_agent-rejects-a-nonzero-send_command]"
    else
        echo "FAIL [load_agent-rejects-a-nonzero-send_command]: returned 0 on exit 4"
        fails=$((fails+1))
    fi

    # 4. The harness's own vocabulary maps onto --agent, including its two lenient
    #    defaults — which are what make send_command's STRICT --agent arm
    #    unreachable from here, a fact this change leans on in a comment and that
    #    nothing pinned before.
    SC_STUB_OUT="🔁 Autonomous loop launched"
    export SC_STUB_OUT SC_STUB_RC=0
    drive_load_agent corp passive
    assert_contains "load_agent-maps-passive-to-goldfish" "$LA_ARGS" "corp bot-loop --agent goldfish"
    drive_load_agent runner goldfish
    assert_contains "load_agent-maps-goldfish-runner" "$LA_ARGS" "runner bot-loop --agent goldfish"
    drive_load_agent corp banana
    assert_contains "load_agent-corp-default-warns"  "$LA_OUT"  "defaulting to heuristic"
    assert_contains "load_agent-corp-default-is-sent" "$LA_ARGS" "corp bot-loop --agent heuristic"
    drive_load_agent runner banana
    assert_contains "load_agent-runner-default-warns"  "$LA_OUT"  "defaulting to goldfish"
    assert_contains "load_agent-runner-default-is-sent" "$LA_ARGS" "runner bot-loop --agent goldfish"
    # 5. An unknown SIDE is refused by load_agent itself, before anything is sent.
    #    The arm this replaces treated a bad side as the runner and blamed the
    #    AGENT in its warning.
    drive_load_agent bogusside heuristic
    if [[ "$LA_STATUS" -ne 0 && -z "$LA_ARGS" ]]; then
        echo "ok   [load_agent-refuses-an-unknown-side]"
    else
        echo "FAIL [load_agent-refuses-an-unknown-side]: status=$LA_STATUS, stub saw: $LA_ARGS"
        fails=$((fails+1))
    fi
    assert_contains "load_agent-names-the-side-not-the-agent" "$LA_OUT" "unknown side 'bogusside'"
    assert_not_contains "load_agent-does-not-blame-the-agent" "$LA_OUT" "Unknown Runner agent"
    unset SC_STUB_OUT SC_STUB_RC

    # A partial injection (corp launched, runner refused) must not leave a corp
    # loop playing alone in a lobby nobody leaves — a state this change created,
    # since load_agent could not fail before it. The teardown lives in run_game,
    # which is not extractable the way load_agent is (it is 100 lines of lobby
    # bring-up), so this is structural and says so: it pins that the two
    # load_agent calls are inside a failure branch, and that the branch tears
    # both sides down.
    INJECT_BLOCK="$(awk '/# Inject Brains/,/^    # Monitor Loop/' "$RUN_MATCH")"
    if [[ -z "$INJECT_BLOCK" ]]; then
        echo "FAIL [run-match-inject-block-found]: could not extract the injection block"
        fails=$((fails+1))
    else
        echo "ok   [run-match-inject-block-found]"
        # COMMENTS STRIPPED FIRST, for the POSITIVE assertions as much as the
        # negative ones. The comment above the guard names both `bot-loop-stop`
        # and `leave-game` (explaining why they are safe to call there), so a raw
        # text check was satisfied by the prose: a mutant that deleted the whole
        # teardown loop SURVIVED this block until the strip was applied here too.
        # Keyword-matches-prose — found on the negative side first and then left
        # standing on the positive side, which is the same defect twice in one
        # guard.
        INJECT_CODE="$(printf '%s\n' "$INJECT_BLOCK" | grep -v '^[[:space:]]*#')"
        assert_contains "run-match-injection-is-guarded" "$INJECT_CODE" \
            'if ! load_agent "corp" "$corp_agent" || ! load_agent "runner" "$runner_agent"; then'
        assert_contains "run-match-teardown-stops-the-loops" "$INJECT_CODE" 'bot-loop-stop'
        assert_contains "run-match-teardown-leaves-the-lobby" "$INJECT_CODE" 'leave-game'
        assert_contains "run-match-injection-failure-returns" "$INJECT_CODE" 'return 1'
        # The teardown must be INSIDE the failure branch, and nowhere ELSE in the
        # block: run unconditionally it would stop both bots and leave the lobby
        # of every healthy game before the monitor loop.
        #
        # "Everything before the guard" was not that test. A seat (and the judge
        # harness, reproducing it) appended the same loop AFTER the guard's `fi`
        # and both assertions stayed green while their names claimed the
        # opposite. So: take the guard's BODY, and take the block MINUS that
        # body, and assert on each.
        GUARD_BODY="$(printf '%s\n' "$INJECT_CODE" \
            | awk '/if ! load_agent/{inb=1;next} inb && /^    fi$/{inb=0;next} inb')"
        OUTSIDE_GUARD="$(printf '%s\n' "$INJECT_CODE" \
            | awk '/if ! load_agent/{inb=1;print;next} inb && /^    fi$/{inb=0;next} !inb')"
        if [[ -n "$GUARD_BODY" ]]; then
            echo "ok   [run-match-guard-body-extracted]"
        else
            echo "FAIL [run-match-guard-body-extracted]: the failure branch is empty or the"
            echo "     awk range missed it, so the two assertions below say nothing."
            fails=$((fails+1))
        fi
        assert_contains "run-match-teardown-is-in-the-failure-branch"  "$GUARD_BODY" 'leave-game'
        assert_contains "run-match-stop-is-in-the-failure-branch"      "$GUARD_BODY" 'bot-loop-stop'
        assert_not_contains "run-match-teardown-is-not-unconditional" "$OUTSIDE_GUARD" 'leave-game'
        assert_not_contains "run-match-stop-is-not-unconditional"     "$OUTSIDE_GUARD" 'bot-loop-stop'
    fi
fi

echo "---"
if [[ $fails -eq 0 ]]; then
    echo "peer_status_test: ALL PASSED"; exit 0
else
    echo "peer_status_test: $fails FAILED"; exit 1
fi
