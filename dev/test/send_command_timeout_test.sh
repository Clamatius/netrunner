#!/usr/bin/env bash
# send_command_timeout_test.sh — regression guard for #190 and for the trap that
# fixing #190 opens.
#
# #190: dev/ai-eval.sh read TIMEOUT into a variable that ONLY the lein fallback
# used, and check-ai.sh's warm path is gated on `command -v bb` — the same test
# that selects ai-eval.sh's bb branch. So whenever check-ai.sh was the caller,
# the branch that honours TIMEOUT was unreachable. The bencode read is blocking,
# and a wedged REPL hung `make check` forever with no message.
#
# The trap: the timeout was inert, so nothing enforced that callers' TIMEOUTs
# were sane. `monitor-run --persistent` — the un-babysat marquee workhorse, which
# parks for 300s and can hold an active run for 300s more — ran under
# send_command's 20s default. Honouring TIMEOUT without raising those would have
# killed a HEALTHY monitor after 20 seconds: a worse bug than the hang.
#
# So both halves are pinned here. Neither is visible from the other file.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"
AI_EVAL="$SCRIPT_DIR/../ai-eval.sh"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-timeout-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats"
# ~110 probe invocations would otherwise each append a line to the repo's real
# logs/commands.log (round-4 guest MINOR).
export SEND_COMMAND_LOG="$TMP/commands.log"

fails=0
ok()   { echo "ok   [$1] $2"; }
fail() { echo "FAIL [$1]: $2"; fails=$((fails+1)); }

# ---------------------------------------------------------------------------
# Half 1: ai-eval.sh actually bounds the bb branch.
# ---------------------------------------------------------------------------

# A stub REPL that accepts the connection and never answers — the wedge #190 is
# about. `nc -l` holds the socket open with no bencode reply.
PORT=0
for candidate in $(seq 47311 47360); do
    if ! nc -z localhost "$candidate" 2>/dev/null; then PORT="$candidate"; break; fi
done
if [[ "$PORT" == "0" ]]; then
    fail "wedged-repl" "could not find a free port to host the stub REPL"
else
    # A listener that ACCEPTS and never answers. `sleep` holds nc's stdin open so
    # nc does not half-close the socket — an immediate EOF would be a *closed*
    # connection, which bb already errors on, and would test nothing.
    sleep 60 | nc -l "$PORT" > /dev/null 2>&1 &
    NC_PID=$!
    sleep 0.5
    START=$(date +%s)
    OUT=$(TIMEOUT=2 TIMEOUT_GRACE=1 timeout 25 "$AI_EVAL" wedged "$PORT" '(+ 1 1)' 2>&1)
    CODE=$?
    ELAPSED=$(( $(date +%s) - START ))
    kill "$NC_PID" 2>/dev/null; pkill -P "$NC_PID" 2>/dev/null

    # ORDER MATTERS. The outer `timeout 25` stands in for "forever", and it exits
    # 124 too — so a non-zero exit is only evidence of anything once we know the
    # OUTER kill is not what produced it. Checked first, and the exit-code check
    # is skipped when it did, rather than printing a green line for the bug.
    # (2s budget + 1s grace; generous slack for bb start-up.)
    if [[ "$ELAPSED" -ge 20 ]]; then
        fail "wedged-repl" "took ${ELAPSED}s — the outer kill fired, so ai-eval.sh did not bound it"
    else
        ok "wedged-repl" "returned in ${ELAPSED}s (TIMEOUT=2 + 1s grace)"
        if [[ "$CODE" -eq 0 ]]; then
            fail "wedged-repl" "a REPL that never answered returned success (exit 0)"
        else
            ok "wedged-repl" "non-zero exit ($CODE) instead of hanging"
        fi
    fi
    if [[ "$OUT" == *"wedged"* ]]; then
        ok "wedged-repl" "says the REPL is wedged rather than failing silently"
    else
        fail "wedged-repl" "no diagnosis in output. Got: $OUT"
    fi
fi

# The grace is the whole reason a 300s `wait` is not raced by a 300s shell kill.
# Checked BEHAVIOURALLY — an earlier round asserted the shape of the KILL_AFTER
# line with grep, which went stale the moment the arithmetic moved into a branch
# and reported "#190 is back" over working code. Grep the behaviour, not the
# spelling: with a 2s budget and 6s of grace the kill must land after 6s, which
# is only true if the grace is being ADDED rather than ignored.
if [[ "$PORT" != "0" ]]; then
    sleep 60 | nc -l "$PORT" > /dev/null 2>&1 &
    NC_PID=$!
    sleep 0.5
    START=$(date +%s)
    TIMEOUT=2 TIMEOUT_GRACE=6 timeout 25 "$AI_EVAL" wedged "$PORT" '(+ 1 1)' >/dev/null 2>&1
    ELAPSED=$(( $(date +%s) - START ))
    kill "$NC_PID" 2>/dev/null
    if [[ "$ELAPSED" -lt 6 ]]; then
        fail "grace" "killed after ${ELAPSED}s with TIMEOUT=2 + 6s grace — the grace
       is not being added, so a caller whose REPL deadline equals its TIMEOUT
       (wait) is a coin flip"
    elif [[ "$ELAPSED" -ge 20 ]]; then
        fail "grace" "took ${ELAPSED}s — the outer kill fired, so nothing bounded it"
    else
        ok "grace" "killed at ${ELAPSED}s = TIMEOUT + grace, so the REPL's own deadline wins"
    fi

    # TIMEOUT=0 must mean NO backstop. bot-watch depends on it: watch-for-runs! is
    # an infinite loop, so any finite budget kills a healthy command. Proven by
    # showing the process is STILL ALIVE well past what a bounded run would allow.
    sleep 60 | nc -l "$PORT" > /dev/null 2>&1 &
    NC_PID=$!
    sleep 0.5
    TIMEOUT=0 "$AI_EVAL" wedged "$PORT" '(+ 1 1)' >/dev/null 2>&1 &
    EVAL_PID=$!
    sleep 8
    if kill -0 "$EVAL_PID" 2>/dev/null; then
        ok "unbounded" "TIMEOUT=0 still running after 8s — no backstop, as bot-watch needs"
    else
        fail "unbounded" "TIMEOUT=0 exited within 8s — something is still bounding it,
       so bot-watch would be killed mid-watch"
    fi
    kill "$EVAL_PID" 2>/dev/null
    kill "$NC_PID" 2>/dev/null
    wait "$EVAL_PID" 2>/dev/null || true
fi

# ---------------------------------------------------------------------------
# Half 1b: the lein FALLBACK must report a timeout too.
#
# Round-2 guest MAJOR. That branch ends in a `grep -v` pipeline, and a pipeline's
# status is its LAST command's — so `timeout` killing lein was reported as
# whatever grep made of the partial output: 0 if any line survived the filters,
# never 124. send_command's timeout diagnosis keys on exactly 124, so on this
# branch a killed eval read as SUCCESS and the dispatcher carried on.
#
# It survived because the branch is unreachable while `bb` is installed, and the
# guard above tests whichever backend happens to be there. So force it: a PATH
# with no bb, and a stub `lein` that emits a line the filters keep (making grep
# exit 0 — the exact masking case) and then hangs.
# ---------------------------------------------------------------------------

FAKEBIN="$TMP/fakebin"
mkdir -p "$FAKEBIN"
cat > "$FAKEBIN/lein" <<'LEOF'
#!/bin/bash
# A line that survives every grep -v filter in ai-eval.sh, so the pipeline's own
# exit status would be 0 — which is what used to mask the kill.
echo "surviving output line"
sleep 60
LEOF
chmod +x "$FAKEBIN/lein"
# `timeout` itself lives in the same prefix as bb on this host, so link it in
# rather than inheriting the directory that would bring bb back.
ln -sf "$(command -v timeout)" "$FAKEBIN/timeout" 2>/dev/null || true

if PATH="$FAKEBIN:/usr/bin:/bin" command -v bb >/dev/null 2>&1; then
    fail "lein-fallback" "could not build a PATH without bb, so the fallback
       branch cannot be forced and this assertion would test nothing"
else
    START=$(date +%s)
    OUT=$(PATH="$FAKEBIN:/usr/bin:/bin" TIMEOUT=2 TIMEOUT_GRACE=1 \
          timeout 25 "$AI_EVAL" fallback 9 '(+ 1 1)' 2>&1) && CODE=0 || CODE=$?
    ELAPSED=$(( $(date +%s) - START ))
    if [[ "$ELAPSED" -ge 20 ]]; then
        fail "lein-fallback" "took ${ELAPSED}s — the outer kill fired, so the lein
       branch is not bounded at all"
    elif [[ "$CODE" -ne 124 ]]; then
        fail "lein-fallback" "exited $CODE, not 124, after being killed. The
       pipeline's status is grep's, not timeout's — use PIPESTATUS[0].
       send_command keys its timeout diagnosis on 124, so this reads as success."
    else
        ok "lein-fallback" "reports 124 through the grep pipeline (${ELAPSED}s)"
    fi
    if [[ "$OUT" == *"wedged"* ]]; then
        ok "lein-fallback" "diagnoses the wedge on this branch too"
    else
        fail "lein-fallback" "killed with no diagnosis. Got: $OUT"
    fi
fi

# ---------------------------------------------------------------------------
# Half 1c: a budget bash cannot safely calculate must be REFUSED, not guessed.
#
# Round-3 guest MAJOR, all four reproduced. The digits-only check accepted values
# that $(( )) then mangled, and `wait` passes the seat's own number straight
# through, so `wait 08` was a live path to two of them:
#   08   -> "value too great for base": bash ABORTS mid-command (leading zero =
#           octal, and 8 is not an octal digit).
#   010  -> octal 8, so the backstop is silently 2s shorter than asked for.
#   foo  -> arithmetic ZERO, which now means "no backstop" — the opposite of a
#           small number.
#   maxint -> wraps NEGATIVE once the grace is added.
# ---------------------------------------------------------------------------

# Accepted and normalized as DECIMAL (these are the ones a seat can actually
# produce): the command must reach the REPL and come back with the answer.
# ("" is here deliberately: ${TIMEOUT:-10} treats empty as UNSET, so an empty
# value means "use the default" and must not be refused as malformed.)
#
# "not refused" is NOT enough to assert here, and a mutation proved it: with the
# 10# normalization removed, TIMEOUT=08 makes bash ABORT with "value too great
# for base" — exit 1, not 78 — and a `-ne 78` check sailed straight over it. So
# assert the absence of the arithmetic failure itself.
for good in 08 010 5 ""; do
    OUT=$(TIMEOUT="$good" "$AI_EVAL" probe 1 '(+ 1 1)' 2>&1) && CODE=0 || CODE=$?
    if [[ "$CODE" -eq 78 ]]; then
        fail "timeout-parse" "TIMEOUT=$good was refused, but it is a whole number
       of seconds — a leading zero must be read as decimal, not octal"
    elif [[ "$OUT" == *"value too great"* || "$OUT" == *"error token"* ]]; then
        fail "timeout-parse" "TIMEOUT=$good aborted bash arithmetic: $OUT
       (a leading zero is being read as octal — normalize with 10#)"
    else
        ok "timeout-parse" "TIMEOUT='$good' accepted (decimal, or empty => default)"
    fi
done

# ...and the VALUE has to be right, not merely accepted: octal 010 is 8, so a
# budget two seconds short would otherwise pass every check above. Read the
# number back out of the wedged-REPL diagnosis, which prints KILL_AFTER.
if [[ "$PORT" != "0" ]]; then
    sleep 60 | nc -l "$PORT" > /dev/null 2>&1 &
    NC_PID=$!
    sleep 0.5
    OUT=$(TIMEOUT=010 TIMEOUT_GRACE=0 timeout 25 "$AI_EVAL" wedged "$PORT" '(+ 1 1)' 2>&1) || true
    kill "$NC_PID" 2>/dev/null
    if [[ "$OUT" == *"within 10s"* ]]; then
        ok "timeout-parse" "TIMEOUT=010 is ten seconds, not octal eight"
    elif [[ "$OUT" == *"within 8s"* ]]; then
        fail "timeout-parse" "TIMEOUT=010 became 8s — bash read the leading zero as
       octal, so every zero-padded budget is silently short"
    else
        fail "timeout-parse" "could not read the budget back from the diagnosis: $OUT"
    fi
fi

# Refused, loudly, with the config exit code rather than a guessed budget.
for bad in foo 1.5 9223372036854775807; do
    OUT=$(TIMEOUT="$bad" "$AI_EVAL" probe 1 '(+ 1 1)' 2>&1) && CODE=0 || CODE=$?
    if [[ "$CODE" -ne 78 ]]; then
        fail "timeout-parse" "TIMEOUT='$bad' exited $CODE, not 78. Whatever budget
       bash computed from it, the caller did not ask for it."
    else
        ok "timeout-parse" "TIMEOUT='$bad' refused with EX_CONFIG"
    fi
done

# ...and the dispatcher must not swallow that into an overall success. `execute`
# captures with `|| EVAL_STATUS=$?`, so a refusal has to survive to the exit code
# or an un-babysat driver believes the command ran.
OUT=$(TIMEOUT=nonsense "$SEND_CMD" corp status 2>&1) && CODE=0 || CODE=$?
if [[ "$CODE" -eq 0 ]]; then
    fail "timeout-parse" "send_command exited 0 over a refused invocation"
elif [[ "$OUT" != *"did not run"* ]]; then
    fail "timeout-parse" "send_command exited $CODE but never said the command
       did not run. Got: $OUT"
else
    ok "timeout-parse" "send_command propagates the refusal (exit $CODE)"
fi

# ...and `wait` SEPARATELY, because it is the one caller that captures `execute`
# inside $( ) — with a `|| true` that LOOKS like it would swallow the refusal.
#
# It does not, and that is worth pinning rather than trusting. `execute` signals
# "did not run" by calling `exit`, and `exit` inside a command substitution
# terminates the subshell immediately: `||` is never evaluated, so the status
# reaches the assignment and `set -e` ends the script. A round-4 review seat read
# the `|| true` and filed this as a MAJOR — reasonable from the source, and wrong;
# running the pre-change binary showed `wait 10000000` already exiting 78.
#
# So this test exists to keep that true, not because it was ever false. `wait` is
# the seat's primary parked-play command and the one most likely to hit a
# timeout; a refusal reaching an un-babysat driver as exit 0, with a cheerful
# heartbeat footer, is the worst place to lose it.
#
# An 8-digit budget is refused by the length bound, so this needs no live REPL
# and cannot actually park.
OUT=$("$SEND_CMD" corp wait 10000000 2>&1) && CODE=0 || CODE=$?
if [[ "$CODE" -eq 0 ]]; then
    fail "wait-refusal" "the wait command exited 0 over a refused budget — an un-babysat
       driver reads that as a completed quiet wait"
elif [[ "$OUT" != *"did not run"* ]]; then
    fail "wait-refusal" "the wait command exited $CODE but never said it did not run.
       Got: $OUT"
else
    ok "wait-refusal" "the wait command propagates a refusal (exit $CODE) instead of a quiet wait"
fi

# ---------------------------------------------------------------------------
# Half 2: every command is CLASSIFIED, and the blocking ones clear the ceiling.
#
# Round 1 of this test hand-listed five blocking commands, and both review seats
# found the same hole in it: `bot-respond`, `bot-turn`, `bot` and `bot-watch`
# block too, and the guard was structurally blind to a missed sixth. That is the
# #180/#185 shape — a list whose failure mode is silence.
#
# So the inventory is DERIVED from send_command's own dispatch. Every label it
# handles must appear in exactly one set below; a new command fails this test
# until someone classifies it. The classification still needs a human — whether a
# command blocks is a fact about the Clojure it calls, not about its name — but
# it can no longer be forgotten.
# ---------------------------------------------------------------------------

# BLOCKING: the REPL side can run longer than the short default. Value is the
# argv to drive it with. Each must end up above the REPL loop ceiling.
declare -a BLOCKING=(
    "continue"                  # -> monitor-run! (300s park + 300s loop)
    "monitor-run"               # same, under its alias
    "run HQ"                    # -> run! -> auto-continue-loop! (300s)
    "find-card Hedge_Fund"      # -> find-card! : up to 10 bot-driven turns
    "draw-to-card Hedge_Fund"   # -> draw-to-card! : up to 45 draw round-trips
    "bot"                       # -> play-turn, which may make a run
    "bot-turn"                  # -> play-full-turn, same
    "bot-respond"               # -> respond-to-run! -> monitor-run! (300s)
)

# UNBOUNDED: designed never to return on its own. Any finite budget truncates a
# HEALTHY command, so these must run with no backstop at all (TIMEOUT=0).
declare -a UNBOUNDED=(
    "bot-watch"                 # -> watch-for-runs! : infinite poll, "Ctrl+C to stop"
)

# SELF-BUDGETED: sets its own budget from the number the seat asked for, and
# hands the REPL the same number. Safe only because ai-eval.sh adds grace.
declare -a SELF_BUDGETED=( "wait" )

# FAST: answers immediately, so the short default is right and a wedged REPL
# must not hang them for half an hour.
declare -a FAST=(
    help --help -h abilities advance archives auto-pass auto-start board
    board-compact bot-loop bot-loop-status bot-loop-stop bot-status card-text
    change chat choose choose-card choose-value clear-heartbeats clicks concede
    connect continue-run create-game credits dashboard dashboard-compact
    debug-chat diagnose-blocker discard discard-card end-phase-12
    end-post-discard end-turn eval fire-subs fix-credits game-over? game-over-status
    get-cursor hand
    hand-text heap indicate-action install install-index jack-out join keep-hand
    leave-game let-subs-fire list-game-ids list-lobbies list-playables log
    log-compact mulligan multi-choose nuke-state peer-status ping play
    play-index prompt purge remove-tag replay-clear replay-save replay-start
    replay-status replay-stop resync rez score smart-end-turn snapshot
    start-game start-turn status status-compact take-credit tank trash
    trash-resource use-ability use-runner-ability draw
)

STUB="$TMP/stub-eval.sh"
cat > "$STUB" <<'STUBEOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then
    expr="$(cat)"
else
    expr="${!#}"
fi
# ensure_connection's probes set their own small TIMEOUT and are not the subject
# here; record only the budget the COMMAND itself ran under.
case "$expr" in
    *ensure-connected!*|*sync-verdict!*) ;;
    *) printf '%s\n%s\n' "${TIMEOUT:-unset}" "$expr" > "$STUB_TIMEOUT_FILE" ;;
esac
printf '"stub"\n'
STUBEOF
chmod +x "$STUB"

# Ask the real dispatcher whether it handles a name. Its default arm prints
# "Unknown command", which makes this authoritative in a way no regex is.
probe_says_unknown() {
    local out
    out=$(AI_EVAL="$STUB" "$SEND_CMD" corp "$1" 2>&1) || true
    [[ "$out" == *"Unknown command"* ]]
}

# Self-test the probe before trusting it to filter: if it answered "known" for
# everything, every candidate would be classified-or-fail and the nested-case
# junk would show up as noise; if it answered "unknown" for everything, LABELS
# would be empty and the whole guard would pass vacuously — which is the exact
# failure being engineered out.
if probe_says_unknown "status"; then
    fail "probe" "the dispatcher probe calls a REAL command unknown — it cannot
       be used to derive the inventory"
elif ! probe_says_unknown "definitely-not-a-command-xyzzy"; then
    fail "probe" "the dispatcher probe calls a MADE-UP command known — it would
       accept every candidate, including nested flag arms"
else
    ok "probe" "the dispatcher discriminates real commands from invented ones"
fi

# --- the derivation: what does send_command actually dispatch on? ---
CASE_START=$(grep -n '^case "\$COMMAND" in' "$SEND_CMD" | tail -1 | cut -d: -f1)
CASE_END=$(awk -v s="$CASE_START" 'NR>s && /^esac/ {print NR; exit}' "$SEND_CMD")
if [[ -z "$CASE_START" || -z "$CASE_END" ]]; then
    fail "inventory" "could not locate send_command's dispatch case block — this
       test can no longer see what commands exist, so it is not a guard"
    LABELS=""
else
    # Two steps, because three rounds of review each found a DIFFERENT way a
    # single clever extractor under-reported — and every time it stayed green,
    # because a missing label cannot fail an "is it classified?" check:
    #   r1: required exactly four-space indentation, missed a two-space arm.
    #   r2: widening swept in nested `case` arms and `)` inside quoted Clojure.
    #   r3: a bash-grammar parser in awk still excluded `?`, so the live
    #       `game-over?|game-over-status)` arm was absent and the count looked fine.
    # That is this repo's "three rounds means the plan is wrong" signal, so the
    # parser is gone rather than patched a fourth time.
    #
    # STEP 1 — candidates, deliberately OVER-inclusive: anything shaped like a
    # case label. It may pick up nested flag-parsing arms (`--since`, `on|off`)
    # and `)` from inside quoted Clojure (`gameid`, `nil`). That is fine; being
    # too greedy is the safe direction, and it needs no grammar.
    #
    # The charset is wide on purpose (round-4 guest MAJOR named the misses):
    # `?` for the live `game-over?|game-over-status)` arm, SPACES so `foo | bar)`
    # yields both, and an optional leading `(` for bash's `(foo)` form. What it
    # deliberately excludes — `{`, `:`, `"`, `[` — is what keeps embedded Clojure
    # from tripling the probe count for no gain.
    CANDIDATES=$(awk -v s="$CASE_START" -v e="$CASE_END" 'NR>s && NR<e' "$SEND_CMD" \
                 | grep -vE '^[[:space:]]*#' \
                 | grep -oE '^[[:space:]]*\(?[A-Za-z0-9*_.?|-][A-Za-z0-9*_.?| -]*\)' \
                 | sed 's/)$//' | sed 's/^[[:space:]]*(\{0,1\}//' \
                 | tr '|' '\n' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' \
                 | grep -v '^\*$' | grep -v '^$' | sort -u)

    # The one arm layout no single-line scan can see: alternatives split across
    # lines with a trailing `|`. Valid bash, unused here — so FAIL LOUDLY if it
    # ever appears rather than silently dropping the continuation. A stated limit
    # beats a silent miss; the whole point of this derivation is that a missing
    # label cannot fail an "is it classified?" check.
    CONT=$(awk -v s="$CASE_START" -v e="$CASE_END" 'NR>s && NR<e' "$SEND_CMD" \
           | grep -vE '^[[:space:]]*#' \
           | grep -cE '^[[:space:]]*[A-Za-z0-9*_.?|-][A-Za-z0-9*_.?| -]*\|[[:space:]]*$' || true)
    if [[ "${CONT:-0}" -gt 0 ]]; then
        fail "inventory" "send_command has $CONT case label(s) continued onto the
       next line (a trailing '|'). This extraction is single-line, so those
       alternatives would be dropped SILENTLY. Put the alternatives on one line."
    else
        ok "inventory" "no multi-line case labels (the one layout this cannot see)"
    fi

    # STEP 2 — ask the DISPATCHER which of them it actually handles. Its `*)` arm
    # prints "Unknown command", so send_command itself is the authority on what
    # is a command; no parser of ours has to be right. ~65ms each against the
    # stub backend, and it is the only step whose answer cannot silently drift.
    LABELS=""
    while read -r cand; do
        [[ -z "$cand" ]] && continue
        if ! probe_says_unknown "$cand"; then
            LABELS+="$cand"$'\n'
        fi
    done <<< "$CANDIDATES"
    LABELS=$(printf '%s' "$LABELS" | sort -u)
fi

LABEL_COUNT=$(printf '%s\n' "$LABELS" | grep -c . || true)
if [[ "$LABEL_COUNT" -lt 50 ]]; then
    fail "inventory" "only found $LABEL_COUNT dispatch labels; the extraction is
       broken and would silently pass over everything"
else
    ok "inventory" "$LABEL_COUNT dispatch labels derived from send_command"
fi

classified_names() {
    local e
    for e in "${BLOCKING[@]}" "${UNBOUNDED[@]}"; do echo "${e%% *}"; done
    printf '%s\n' "${SELF_BUDGETED[@]}" "${FAST[@]}"
}

# Every dispatched label must be classified...
while read -r label; do
    [[ -z "$label" ]] && continue
    if ! classified_names | grep -qx -- "$label"; then
        fail "unclassified" "send_command handles '$label' but this test does not
       classify it. Decide whether its REPL side can block for longer than the
       ${CEILING_SECS:-300}s loop ceiling, then add it to BLOCKING (with a
       TIMEOUT=\"\$REPL_BLOCKING_TIMEOUT\" in its case block), UNBOUNDED,
       SELF_BUDGETED or FAST in this file."
    fi
done <<< "$LABELS"

# ...and nothing may be classified that is no longer dispatched (a stale entry
# is a silent hole: it looks covered and drives nothing).
while read -r name; do
    [[ -z "$name" ]] && continue
    if ! printf '%s\n' "$LABELS" | grep -qx -- "$name"; then
        fail "stale-entry" "'$name' is classified here but send_command no longer
       dispatches it — remove it, or this test is guarding a ghost"
    fi
done < <(classified_names | sort -u)

# --- drive the ones whose budget is a claim we can check ---


# The REPL-side ceiling these must clear. Read from the source rather than
# retyped, so a change to the loop budget fails here instead of drifting.
if ! grep -qE 'timeout-ms 300000' "$SCRIPT_DIR/../src/clj/ai_runs.clj"; then
    fail "ceiling" "could not find auto-continue-loop!'s :timeout-ms 300000 in
       ai_runs.clj — the 300s figure this test compares against may have moved"
fi
CEILING_SECS=300

drive() {
    STUB_TIMEOUT_FILE="$TMP/timeout.$RANDOM"
    : > "$STUB_TIMEOUT_FILE"
    export STUB_TIMEOUT_FILE
    AI_EVAL="$STUB" "$SEND_CMD" corp "$@" >/dev/null 2>&1 || true
}
timeout_for() { drive "$@"; head -1 "$STUB_TIMEOUT_FILE"; }
expr_for()    { drive "$@"; tail -n +2 "$STUB_TIMEOUT_FILE"; }

for entry in "${BLOCKING[@]}"; do
    # shellcheck disable=SC2086
    set -- $entry
    name="$1"
    t=$(timeout_for "$@")
    if [[ -z "$t" || "$t" == "unset" ]]; then
        fail "$name" "the stub never saw the command (dispatch changed?) — got '$t'"
    elif [[ "$t" == "0" ]]; then
        fail "$name" "runs unbounded, but it is classified BLOCKING (a finite
       backstop was intended). Move it to UNBOUNDED or set REPL_BLOCKING_TIMEOUT."
    elif [[ "$t" -le "$CEILING_SECS" ]]; then
        fail "$name" "runs under TIMEOUT=${t}s, but its REPL side can block for
       ${CEILING_SECS}s or more. A healthy invocation would be killed mid-flight,
       and the seat would be told the REPL is wedged when it is merely working.
       Set TIMEOUT=\"\$REPL_BLOCKING_TIMEOUT\" in this command's case block."
    else
        ok "$name" "TIMEOUT=${t}s clears the ${CEILING_SECS}s REPL ceiling"
    fi
done

for entry in "${UNBOUNDED[@]}"; do
    # shellcheck disable=SC2086
    set -- $entry
    name="$1"
    t=$(timeout_for "$@")
    if [[ "$t" != "0" ]]; then
        fail "$name" "runs under TIMEOUT=${t}s, but it never returns on its own —
       any finite budget kills a HEALTHY command and tells the seat to bounce a
       live REPL. Set TIMEOUT=0 in its case block."
    else
        ok "$name" "runs unbounded (TIMEOUT=0), as its own help text promises"
    fi
done

# `wait` is the one command whose shell budget legitimately EQUALS its REPL
# deadline: it passes the seat's number both to the REPL and to ai-eval.sh. That
# is a coin flip WITHOUT the grace checked above and correct WITH it, so what is
# pinned here is the equality.
WAIT_T=$(timeout_for wait 42)
WAIT_EXPR=$(expr_for wait 42)
if [[ "$WAIT_T" != "42" ]]; then
    fail "wait" "asked for a 42s wait but ran the shell under TIMEOUT=${WAIT_T}s"
elif [[ "$WAIT_EXPR" != *"42"* ]]; then
    fail "wait" "asked for a 42s wait but the REPL was sent: $WAIT_EXPR"
else
    ok "wait" "shell budget matches the deadline handed to the REPL (grace decides the race)"
fi

# ...and the control: a plain read-only command must NOT inherit the long budget,
# or a wedged REPL hangs `status` for half an hour.
for fastcmd in status board log; do
    FT=$(timeout_for "$fastcmd")
    if [[ -z "$FT" || "$FT" == "unset" ]]; then
        fail "$fastcmd" "the stub never saw '$fastcmd' (dispatch changed?)"
    elif [[ "$FT" == "0" || "$FT" -gt "$CEILING_SECS" ]]; then
        fail "$fastcmd" "a non-blocking command runs under TIMEOUT=${FT}s — the
       backstop has been widened past the commands that need it"
    else
        ok "$fastcmd" "stays on the short budget (${FT}s)"
    fi
done

# ---------------------------------------------------------------------------
# Half 3: the dispatcher must not report success over a killed eval.
#
# Guest panel MAJOR (#190 round 1): `execute` captured with `|| true`, so RET was
# always 0. A killed eval printed its fragment, fell through into after_action —
# sending MORE evals to a client it had just failed to reach — and exited 0.
# Automation (ai-self-play.sh, a model driver) read that as "the command ran".
# ---------------------------------------------------------------------------

KILLED_STUB="$TMP/killed-eval.sh"
cat > "$KILLED_STUB" <<'KEOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then expr="$(cat)"; else expr="${!#}"; fi
# ensure_connection's probes must still succeed, or we are testing the gate and
# not the timeout.
case "$expr" in
    *ensure-connected!*) printf '"ok"\n'; exit 0 ;;
    *sync-verdict!*)     printf '"SYNC-VERDICT OK"\n'; exit 0 ;;
esac
printf 'partial output before the kill\n'
exit 124
KEOF
chmod +x "$KILLED_STUB"

OUT=$(AI_EVAL="$KILLED_STUB" "$SEND_CMD" corp status 2>&1) && CODE=0 || CODE=$?
if [[ "$CODE" -eq 0 ]]; then
    fail "killed-eval" "send_command exited 0 over an eval that was killed —
       automation cannot tell this from a completed command"
else
    ok "killed-eval" "exits non-zero ($CODE) when the eval was killed"
fi
if [[ "$OUT" == *"FRAGMENT"* || "$OUT" == *"fragment"* ]]; then
    ok "killed-eval" "says the printed output is a fragment, not the answer"
else
    fail "killed-eval" "printed output with no warning that it is partial. Got: $OUT"
fi
if [[ "$OUT" == *"TIMEOUT="* ]]; then
    ok "killed-eval" "tells the seat how to re-run with a bigger budget"
else
    fail "killed-eval" "no recovery advice. Got: $OUT"
fi

if [[ "$fails" -gt 0 ]]; then
    echo "❌ $fails failure(s)"
    exit 1
fi
echo "✅ send_command/ai-eval timeout guards passed."
