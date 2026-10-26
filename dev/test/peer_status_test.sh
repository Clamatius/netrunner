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
cat > "$BOT_STUB" <<'STUBEOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then expr="$(cat)"; else expr="${!#}"; fi
printf '%s\n' "$expr" >> "$BOT_STUB_LOG"
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
    # `(future (loop-fn ...))` — the resolve must not be the thing inside future.
    if [[ "$BOT_EXPR" != *"(future ((requiring-resolve"* ]]; then
        echo "ok   [resolve-happens-outside-the-future]"
    else
        echo "FAIL [resolve-happens-outside-the-future]: requiring-resolve is inside the"
        echo "     future again, so a bot namespace that fails to load reports a launch."
        fails=$((fails+1))
    fi
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
    assert_not_contains "run-match-passes-no-port" "$CALLS" '7890'
    # send_command exiting 0 is not a launch: the relaunch guard prints and exits
    # 0, so the harness checks for the confirmation line.
    assert_contains "run-match-requires-the-launch-line" "$LOAD_AGENT_FN" 'Autonomous loop launched'
fi

echo "---"
if [[ $fails -eq 0 ]]; then
    echo "peer_status_test: ALL PASSED"; exit 0
else
    echo "peer_status_test: $fails FAILED"; exit 1
fi
