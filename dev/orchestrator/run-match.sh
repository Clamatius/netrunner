#!/bin/bash
# run-match.sh
# Orchestrates a Level 2 "Duelling Pistols" match between two agents.
# Usage: ./run-match.sh [--force] [config-file]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# `pwd -P` normalizes what used to be the literal string "$SCRIPT_DIR/../..".
# Honest about why: this is hygiene, NOT the gate's authority. An earlier design
# round had register_client_pid compare a listener's cwd against this path, and
# two seats reproduced that ".../dev/orchestrator/../.." never compares equal to
# the canonical path lsof reports — it would have refused every clean launch.
# That comparison is gone (ownership is the owner tag below, because cwd cannot
# tell our REPL from any other launcher's). What is left is DEV_DIR, LOG_DIR and
# the paths printed in diagnostics, where a canonical path is simply the one an
# operator can compare by eye against lsof's.
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
DEV_DIR="$ROOT_DIR/dev"
DEFAULT_CONFIG="$SCRIPT_DIR/match-config.json"
LOG_DIR="$ROOT_DIR/logs/matches"
# Where the script's own scratch logs go. A seam, because the test runs this
# REAL script and the fixed /tmp paths meant every run truncated
# /tmp/game-server.log and /tmp/ai-client-{corp,runner}.log — the live REPLs'
# logs, which is exactly where a marquee's disconnect evidence would be. So the
# #292 test was damaging the thing #292 protects, ten-plus times per
# `make test-shell` and 23 times per mutation battery. Default unchanged.
RUN_MATCH_TMP="${RUN_MATCH_TMP:-/tmp}"

# ----------------------------------------------------------------------------
# #292: this script kills whatever holds the two client REPL ports. Those REPLs
# are a machine-wide singleton shared by every checkout, and marquee games are
# driven on them by model seats for hours. Killing them unasked loses somebody
# else's game, and the person who loses it is not the person who ran this.
#
# One fact, one place: the ports this script will KILL and the seat each one
# belongs to. The kill loop, the launches, the registration and the gate all
# read CLIENT_SEATS, so the gate cannot end up watching a port the kill does not
# touch. Deliberately literals and deliberately NOT CLIENT_1_PORT/CLIENT_2_PORT
# from load-env.sh: send_command fixes the port from the side with no env seam
# (#291), so honouring the env here would make this script dial a REPL that
# nothing answers on. #291 is where that mapping gets one home.
CLIENT_SEATS=("7890:corp" "7889:runner")

# Pids of listeners THIS invocation launched and verified (see
# register_client_pid). Scoped to one live process on purpose: a claim of
# ownership must not outlive the only process that can honour it. An earlier
# design recorded this in a file, and a review round showed what that buys —
# the record goes on vouching for a REPL during exactly the period when nobody
# is watching it, so a marquee that adopted our leftover got killed by the next
# run with the file's blessing.
OUR_CLIENT_PIDS=()

# The holders preflight_client_ports inspected and approved on its last pass.
# The kill iterates THIS, not a fresh `lsof`: re-inventorying meant a process
# that took a client port between the gate and the kill was killed having never
# been gated, in the one code path whose purpose is to gate that kill. Every
# effect in a guarded batch has to answer to the guard that ran, not to a second
# observation.
APPROVED_KILL_PIDS=()

# Foreign pids the operator accepted with --force, captured at the moment they
# were shown. Spent by the first restart_clients and then cleared: consent is
# scoped in time as well as identity, so a pid recycled three hours later cannot
# inherit it.
CONSENTED_PIDS=()
FORCE=0

# Ownership is PROVED, not inferred. Every other launcher on this box
# (ai-bounce.sh, resume.sh, reset.sh) cds to a checkout root and leaves a fresh
# listener on the same port, so "new since the last inventory, and rooted here"
# is satisfied by a stranger that won a bind race. This tag goes into the client
# JVM's own command line via JVM_OPTS, and a listener counts as ours only if it
# carries it. Measured: JVM_OPTS reaches the project JVM through
# `nohup lein with-profile ai-client run`, and `ps -o command=` shows the -D.
OWNER_TAG="run-match-$$-${RANDOM}${RANDOM}"

usage() {
    echo "Usage: $(basename "$0") [--force] [config-file]" >&2
    echo "" >&2
    echo "  --force   Kill client REPLs this match did not start. Only the ones" >&2
    echo "            listed in the refusal you are overriding; anything that" >&2
    echo "            takes a port later still stops the match." >&2
    exit 2
}

# parse_match_args — sets FORCE and CONFIG_FILE. Its own function so the arg
# arms are testable without running a match. The unknown-flag arm matters: `$1`
# used to be the config path unconditionally, so `--forse` became a filename and
# the script died later with "Config file not found", which is a bad way to find
# out you did not get the behaviour you asked for.
parse_match_args() {
    local seen_positional=0
    FORCE=0
    CONFIG_FILE=""
    # Remembered separately from CONFIG_FILE, which always ends up set: the
    # --force hint should echo back what the operator typed, not add a default
    # path that reads as if it were part of what they must repeat.
    CONFIG_GIVEN=""
    while [ $# -gt 0 ]; do
        case "$1" in
            --force)
                FORCE=1
                ;;
            -*)
                echo "Error: unknown option: $1" >&2
                usage
                ;;
            *)
                if [ "$seen_positional" -ne 0 ]; then
                    echo "Error: more than one config file given ('$CONFIG_FILE' and '$1')" >&2
                    usage
                fi
                CONFIG_FILE="$1"
                CONFIG_GIVEN="$1"
                seen_positional=1
                ;;
        esac
        shift
    done
    [ -n "$CONFIG_FILE" ] || CONFIG_FILE="$DEFAULT_CONFIG"
}

# ---- process facts, all best-effort -----------------------------------------
#
# `|| true` on every one of these is DEFENSIVE, not load-bearing — and the
# distinction is here because a mutation test caught the comment that claimed
# otherwise. lsof exits NON-ZERO when nothing matches, and under `set -e` (:6) a
# bare `h=$(lsof -i :59999 -t)` on a free port aborts the whole script
# (measured: `bash -c 'set -e; echo before; h=$(lsof ...); echo after'` prints
# only "before"). That was a real defect in an earlier DESIGN of this gate,
# which captured holders into a variable. Nothing here does: every call site is
# a `for pid in $(...)` word list or a `[ -n "$(...)" ]` test, where the
# substitution's status is discarded. Removing the `|| true` today changes no
# behaviour and no test goes red. It stays so that the first caller who DOES
# assign the result does not reintroduce the abort.
#
# -sTCP:LISTEN (as dev/ai-bounce.sh:44 already spells it) because `-i :PORT`
# alone selects sockets at EITHER end: an in-flight ai-eval.sh connection would
# count as a holder, and can exit between the lsof and the per-pid lookup.
listen_holders() {
    lsof -i ":$1" -sTCP:LISTEN -t 2>/dev/null || true
}

# lsof -Fn prints three lines (p<pid>, fcwd, n<path>); the cwd is the n one.
pid_cwd() {
    lsof -p "$1" -a -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1 || true
}

pid_etime() {
    ps -o etime= -p "$1" 2>/dev/null | tr -d ' ' || true
}

# Bounded on purpose: a client REPL's command line is kilobytes of java
# classpath and unbounded it buries the message it is evidence for.
pid_cmd_excerpt() {
    ps -o command= -p "$1" 2>/dev/null | cut -c1-100 || true
}

pid_carries_owner_tag() {
    ps -o command= -p "$1" 2>/dev/null | grep -q -- "$OWNER_TAG"
}

pid_in_list() {
    local needle="$1"; shift
    local candidate
    for candidate in "$@"; do
        [ "$candidate" = "$needle" ] && return 0
    done
    return 1
}

# client_lobby_claim — ask a listening REPL whether it is in a game, and say so
# in its own voice. This is the REPL's claim, not a verdict: a seat in hour 3 of
# a marquee and an idle leftover from last night look identical in cwd and
# etime (both are 19-hour-old JVMs rooted in the same checkout), and telling
# them apart is exactly the judgement --force asks the operator to make.
#
# It is a plain atom read through ai-eval.sh with a timeout, the same shape this
# script already uses to find a game id, NOT a send_command action: nothing here
# can block on a prompt. Three outcomes, and all three are printed as given.
client_lobby_claim() {
    local side="$1" port="$2" raw out
    # pr-str, NOT str. `(str nil)` is the EMPTY STRING, so an idle REPL answered
    # "" and was reported as "did not answer" — the "no lobby" arm could never
    # be reached on a real REPL, every idle foreign leftover was described to
    # the operator as unresponsive at the moment that distinction decides a
    # kill, and every one of our own idle seats was called wedged, every game.
    # `(pr-str nil)` is the four characters n-i-l, which is also what the test
    # fixture used to return unconditionally — so the stub agreed with the bug.
    # TIMEOUT_GRACE=0 because ai-eval.sh's backstop is TIMEOUT + TIMEOUT_GRACE
    # (:19, :78 — grace defaults to 15), so a socket that accepts and never
    # answers would hold this for 20s, not the 5 the timeout suggests.
    # The exit status is taken BEFORE the pipeline, because a pipeline's status
    # is `tail`'s and a failed ai-eval.sh would read as a successful empty
    # answer — i.e. "no lobby", the reassuring one, for a REPL that said
    # nothing. "did not answer" and "no lobby" must not collapse into each
    # other: one of them is used below to decide a kill.
    if ! raw="$(TIMEOUT=5 TIMEOUT_GRACE=0 "$DEV_DIR/ai-eval.sh" "$side" "$port" \
                 '(pr-str (:gameid @ai-state/client-state))' 2>/dev/null)"; then
        echo "did not answer"
        return 0
    fi
    out="$(printf '%s' "$raw" | tail -1 | tr -d '"' | tr -d '\n')"
    case "$out" in
        "")                  echo "did not answer" ;;
        nil)                 echo "no lobby" ;;
        *Exception*|*ERROR*) echo "did not answer" ;;
        *)                   echo "in lobby $out" ;;
    esac
}

# print_holder PORT PID SIDE [CLAIM]
#
# CLAIM is passed in when the caller already has it. It used to re-probe, which
# cost a second timeout AND let the line argue with the message above it: a REPL
# that left its lobby between the two calls produced "reports that it is seated
# in a game" followed by "says: no lobby", at the moment the operator most needs
# to trust what they are reading.
print_holder() {
    local port="$1" pid="$2" side="$3" claim="${4:-}"
    [ -n "$claim" ] || claim="$(client_lobby_claim "$side" "$port")"
    echo "      • port $port ($side seat): pid $pid"
    echo "        cwd:   $(pid_cwd "$pid")"
    echo "        up:    $(pid_etime "$pid")"
    echo "        says:  $claim"
    echo "        cmd:   $(pid_cmd_excerpt "$pid")"
}

# preflight_client_ports PHASE  (PHASE: startup | restart)
#
# The gate. Two call sites, one function: once at startup so a refusal costs
# nothing and does not first boot a game server, and once as restart_clients'
# first act so games 2..N are covered too — ownership cannot be inferred from
# position in the script, because from game 2 onward the holders are the ones
# this script launched itself.
#
# Everything it prints, it prints BEFORE anything dies. The message this
# replaces ("Port 7890 still in use, force killing...") was printed after the
# decision was made, which is the wrong order for a destructive one.
preflight_client_ports() {
    local phase="$1"
    local entry port side pid claim
    local -a foreign_pids=() foreign_rows=() busy_rows=() busy_pids=() seen_pids=()
    APPROVED_KILL_PIDS=()

    for entry in "${CLIENT_SEATS[@]}"; do
        port="${entry%%:*}"
        side="${entry##*:}"
        for pid in $(listen_holders "$port"); do
            if pid_in_list "$pid" ${OUR_CLIENT_PIDS[@]+"${OUR_CLIENT_PIDS[@]}"}; then
                # Ours — but "I launched it" is not "nobody else is using it".
                # run_game leaves both lobbies before it returns, so one of our
                # idle REPLs should report no lobby here. A lobby means another
                # driver adopted it between games (send_command and resume.sh
                # both take whatever is listening), and restarting it would
                # disconnect a game we did not create.
                claim="$(client_lobby_claim "$side" "$port")"
                case "$claim" in
                    "in lobby "*)
                        busy_rows+=("$port|$pid|$side|$claim")
                        busy_pids+=("$pid")
                        ;;
                    "did not answer")
                        # Deliberate asymmetry, stated rather than silent: a
                        # FOREIGN holder that will not answer is somebody
                        # else's problem and the operator decides, but one of
                        # OURS that will not answer is a wedged REPL, which is
                        # the thing restart_clients exists to clear. So this
                        # proceeds — out loud, so the log says which of the two
                        # happened.
                        echo "   ℹ️  our $side REPL (pid $pid) did not answer the lobby probe;"
                        echo "      treating it as wedged and restarting it."
                        ;;
                esac
            else
                foreign_pids+=("$pid")
                foreign_rows+=("$port|$pid|$side")
            fi
            seen_pids+=("$pid")
        done
    done

    if [ "${#busy_rows[@]}" -ne 0 ]; then
        # L7: a non-nil :gameid is the REPL's CLAIM, not proof somebody else
        # seated it. ai_connection's leave-game can fail to take (run_game sends
        # it to /dev/null and send_command still exits 0) and a late
        # :lobby/state can re-set the field, in which case this would be our own
        # lobby. Refusing is right either way — we are not restarting a seat
        # that says it is in a game — so the sentence weakens to what was
        # actually said instead of growing a branch for a state nobody can
        # distinguish from here.
        echo "❌ A client REPL this match started reports that it is seated in a game:"
        for entry in "${busy_rows[@]}"; do
            IFS='|' read -r port pid side claim <<< "$entry"
            print_holder "$port" "$pid" "$side" "$claim"
        done
        echo "   Not restarting it, in case another driver is playing on it."
        echo "   Abandoning the remaining games of this match; results already"
        echo "   extracted are saved under $LOG_DIR."
        # L1 — the defect this gate was committing itself. The script is about
        # to exit under set -e, and the EXIT trap stops everything in
        # OUR_CLIENT_PIDS. Leaving a busy pid there means printing "not
        # restarting it" and then killing it a moment later: the exact harm
        # #292 exists to prevent, introduced by #292's fix. Disown it, so the
        # teardown cannot reach it.
        local keep
        local -a survivors=()
        for keep in ${OUR_CLIENT_PIDS[@]+"${OUR_CLIENT_PIDS[@]}"}; do
            pid_in_list "$keep" ${busy_pids[@]+"${busy_pids[@]}"} || survivors+=("$keep")
        done
        OUR_CLIENT_PIDS=(${survivors[@]+"${survivors[@]}"})
        return 1
    fi

    if [ "${#foreign_rows[@]}" -eq 0 ]; then
        APPROVED_KILL_PIDS=(${seen_pids[@]+"${seen_pids[@]}"})
        return 0
    fi

    echo "⚠️  Client ports are held by ${#foreign_rows[@]} process(es) this match did not start:"
    for entry in "${foreign_rows[@]}"; do
        IFS='|' read -r port pid side <<< "$entry"
        print_holder "$port" "$pid" "$side"
    done

    if [ "$phase" = "restart" ]; then
        # Between games. Consent given at startup covered the pids the operator
        # was shown then; these may not be those.
        for pid in "${foreign_pids[@]}"; do
            if ! pid_in_list "$pid" ${CONSENTED_PIDS[@]+"${CONSENTED_PIDS[@]}"}; then
                echo "   This appeared after the match started, so --force does not cover it:"
                echo "   you consented to killing what you were shown, not to a standing licence."
                echo "   Abandoning the remaining games; results so far are under $LOG_DIR."
                return 1
            fi
        done
        echo "   Already consented to at startup — stopping them."
        APPROVED_KILL_PIDS=(${seen_pids[@]+"${seen_pids[@]}"})
        return 0
    fi

    if [ "$FORCE" -ne 1 ]; then
        echo "   Refusing to start: killing these would end whatever is using those seats."
        echo "   A marquee seat or another worktree drives the same two REPLs, and the"
        echo "   only trace left in ITS log would be a disconnect."
        echo ""
        echo "   If they are yours to kill, re-run with --force:"
        echo "       $(basename "$0") --force${CONFIG_GIVEN:+ $CONFIG_FILE}"
        return 1
    fi

    CONSENTED_PIDS=(${foreign_pids[@]+"${foreign_pids[@]}"})
    APPROVED_KILL_PIDS=(${seen_pids[@]+"${seen_pids[@]}"})
    echo "   --force given: killing the ${#CONSENTED_PIDS[@]} process(es) listed above."
    return 0
}

# teardown_our_clients — stop the REPLs this invocation started, and only those.
#
# MATCH_ORCHESTRATOR_DESIGN.md:56 has always said "Teardown: Stops agents"; the
# script never did it. It matters for the gate as well as for tidiness: without
# it, this script's own leftovers are foreign to the NEXT invocation, so the
# second run on a clean box refuses, --force becomes the habitual spelling, and
# the unconditional kill is back wearing a flag.
#
# `set +e` first: errexit is live inside a trap handler, so one kill of a pid
# that exited between the inventory and the signal would abort the handler and
# orphan the other seat.
teardown_our_clients() {
    set +e
    [ "${#OUR_CLIENT_PIDS[@]}" -eq 0 ] && return 0

    local pid entry side pidfile recorded alive i
    echo "🧹 Stopping the ${#OUR_CLIENT_PIDS[@]} client REPL(s) this match started..."
    for pid in "${OUR_CLIENT_PIDS[@]}"; do
        kill -0 "$pid" 2>/dev/null && kill "$pid" 2>/dev/null
    done
    for ((i=1; i<=5; i++)); do
        alive=0
        for pid in "${OUR_CLIENT_PIDS[@]}"; do
            kill -0 "$pid" 2>/dev/null && alive=1
        done
        [ "$alive" -eq 0 ] && break
        sleep 1
    done
    for pid in "${OUR_CLIENT_PIDS[@]}"; do
        kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
    done

    # start-ai-client-repl.sh:70-77 left /tmp/ai-client-<side>.pid naming the
    # pids we just killed, and ai-eval.sh reports a dead one as "(stale PID
    # file)" — a misleading complaint about a cleanup that worked. Remove only
    # the files whose content is a pid we owned; another REPL's file is not ours
    # to delete.
    for entry in "${CLIENT_SEATS[@]}"; do
        side="${entry##*:}"
        pidfile="$RUN_MATCH_TMP/ai-client-${side}.pid"
        [ -f "$pidfile" ] || continue
        recorded="$(cat "$pidfile" 2>/dev/null)"
        pid_in_list "$recorded" "${OUR_CLIENT_PIDS[@]}" && rm -f "$pidfile"
    done

    OUR_CLIENT_PIDS=()
    return 0
}

# A handler that only tears down does NOT end the script: bash resumes after the
# interrupted command, with dead REPLs and a match still to play
# (`bash -c 'trap "echo trapped" TERM; kill -TERM $$; echo continued'` prints
# "continued"). So the signal arms tear down, disarm EXIT, and exit. HUP is in
# the list because a closed terminal, not kill -9, is the realistic way a
# 20-minute foreground script dies.
on_fatal_signal() {
    teardown_our_clients
    trap - EXIT
    exit "$1"
}
trap teardown_our_clients EXIT
trap 'on_fatal_signal 130' INT
trap 'on_fatal_signal 143' TERM
trap 'on_fatal_signal 129' HUP
# ----------------------------------------------------------------------------

parse_match_args "$@"

mkdir -p "$LOG_DIR"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "Error: Config file not found: $CONFIG_FILE" >&2
    exit 1
fi

# The gate goes here: after the config check (a bogus config should not be
# reported as a port problem) and before the game server, so a refusal has not
# started anything.
if ! preflight_client_ports startup; then
    exit 3
fi

echo "🥊 Starting Match Orchestrator"
echo "📄 Config: $CONFIG_FILE"

# 0. Start Game Server (if not running)
echo "🔌 Checking Game Server..."
if ! lsof -i :1042 > /dev/null; then
    echo "   Starting Game Server (lein run)..."
    # Run lein from the project root
    (cd "$ROOT_DIR" && nohup lein run > "$RUN_MATCH_TMP/game-server.log" 2>&1 &)
    
    # Wait for server
    echo "   Waiting for game server to listen on 1042..."
    MAX_WAIT_SERVER=120
    for ((i=1; i<=MAX_WAIT_SERVER; i++)); do
        if lsof -i :1042 > /dev/null; then
            echo "   ✅ Game Server ready!"
            break
        fi
        if [ $i -eq $MAX_WAIT_SERVER ]; then
            echo "   ❌ Timeout waiting for game server"
            echo "   Check $RUN_MATCH_TMP/game-server.log for details"
            exit 1
        fi
        sleep 1
    done
else
    echo "   ✅ Game Server already running"
fi

# 1. AI clients are NOT started here.
#
# This used to adopt-or-start them, and restart_clients killed and restarted the
# same two REPLs seconds later at the top of the first run_game — so the adopt
# was undone immediately. Worse, the adopt arm would have written a REPL this
# script did NOT start into OUR_CLIENT_PIDS, which is the one write path that can
# launder a stranger into the ownership list the kill trusts. Nothing between
# here and the match loop touches a client (config parsing only), so the block
# is deleted rather than guarded. Review round 2 named it; the gate above is
# what makes the deletion safe to notice.

# 2. Parse Config (Simple grep/sed for now, JSON parsing later)
MATCH_ID=$(grep "match_id" "$CONFIG_FILE" | cut -d '"' -f 4)
ROUNDS=$(grep "rounds" "$CONFIG_FILE" | cut -d ':' -f 2 | tr -d ' ,')

echo "DEBUG: Raw ROUNDS value: '$ROUNDS'"

# Extract agents block
AGENTS_BLOCK=$(grep -A 4 '"agents":' "$CONFIG_FILE")
AGENT_A=$(echo "$AGENTS_BLOCK" | grep '"agent_a":' | cut -d '"' -f 4)
AGENT_B=$(echo "$AGENTS_BLOCK" | grep '"agent_b":' | cut -d '"' -f 4)

# Fallback for old config format (corp/runner)
if [ -z "$AGENT_A" ]; then
    AGENT_A=$(echo "$AGENTS_BLOCK" | grep '"corp":' | cut -d '"' -f 4)
fi
if [ -z "$AGENT_B" ]; then
    AGENT_B=$(echo "$AGENTS_BLOCK" | grep '"runner":' | cut -d '"' -f 4)
fi

# Extract gateway type (default: Beginner)
GATEWAY_TYPE=$(grep '"gateway_type":' "$CONFIG_FILE" | cut -d '"' -f 4)
GATEWAY_TYPE="${GATEWAY_TYPE:-Beginner}"

echo "🆔 Match ID: $MATCH_ID"
echo "🔄 Rounds: $ROUNDS"
echo "🎮 Gateway: $GATEWAY_TYPE"
echo "🤖 Agent A: $AGENT_A"
echo "🤖 Agent B: $AGENT_B"

# register_client_pid PORT SIDE — adopt a listener only on proof it is ours.
#
# start-ai-client-repl.sh is launched with `&` and its exit status is never
# checked, and the readiness check accepts ANY socket on the port. So if our
# nREPL loses a bind race to another launcher, the wait loop sees the winner.
# "New since the last inventory, and rooted in this checkout" does not tell them
# apart — ai-bounce.sh, resume.sh and reset.sh all cd to a checkout root too.
# The owner tag does: it is in our JVM's command line and in nobody else's.
#
# Refusing to adopt is also refusing to kill. A listener without the tag is left
# exactly where it is, and the match stops.
register_client_pid() {
    local port="$1" side="$2" pid found=0
    for pid in $(listen_holders "$port"); do
        if pid_carries_owner_tag "$pid"; then
            OUR_CLIENT_PIDS+=("$pid")
            found=1
            echo "   ✅ $side REPL is ours: pid $pid on port $port"
        else
            echo "   ❌ port $port is held by pid $pid, which does not carry this match's"
            echo "      owner tag — another launcher won the bind race."
            print_holder "$port" "$pid" "$side"
            echo "      Leaving it alone and stopping the match."
            return 1
        fi
    done
    if [ "$found" -ne 1 ]; then
        echo "   ❌ nothing is listening on port $port after starting the $side client"
        echo "      (see $RUN_MATCH_TMP/ai-client-${side}.log)"
        return 1
    fi
}

# Helper to restart AI clients
restart_clients() {
    echo "♻️  Restarting AI Clients to clear previous brains..."

    # The gate runs FIRST, before anything dies, and it can refuse. Under set -e
    # (:6) and a plain call site, that ends the match — which is the intent: the
    # alternative is playing on over somebody else's game.
    if ! preflight_client_ports restart; then
        return 1
    fi

    # Kill by PORT, not by pidfile. The two `stop-ai-client.sh` calls that used
    # to be here killed whatever /tmp/ai-client-<side>.pid named — a global path
    # any checkout's launcher wrote, naming a pid that may hold a different port
    # or none at all — and they ran BEFORE the port inventory, so they were two
    # unguarded kills in front of the guard. They are deleted, not gated: the
    # kill below covers the same ground, from the same list the gate used.
    local entry port side pid i alive _inherited_jvm_opts
    _inherited_jvm_opts="${JVM_OPTS:-${JAVA_OPTS:-}}"
    local -a doomed=(${APPROVED_KILL_PIDS[@]+"${APPROVED_KILL_PIDS[@]}"})
    for pid in ${doomed[@]+"${doomed[@]}"}; do
        echo "   ⏹  stopping pid $pid"
    done
    # TERM, a grace window, then -9 — politer than the straight `kill -9` this
    # replaces, and announced above before any of it happens.
    for pid in ${doomed[@]+"${doomed[@]}"}; do
        kill "$pid" 2>/dev/null || true
    done
    for ((i=1; i<=5; i++)); do
        alive=0
        for pid in ${doomed[@]+"${doomed[@]}"}; do
            kill -0 "$pid" 2>/dev/null && alive=1
        done
        [ "$alive" -eq 0 ] && break
        sleep 1
    done
    for pid in ${doomed[@]+"${doomed[@]}"}; do
        kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
    done

    # Everything we owned has just been killed, and the consent that authorized
    # killing the foreign ones is spent — a pid recycled later must not inherit
    # it.
    OUR_CLIENT_PIDS=()
    CONSENTED_PIDS=()

    # JVM_OPTS carries the owner tag into the client JVM's command line; see
    # OWNER_TAG. It starts from ${JVM_OPTS:-$JAVA_OPTS} and not from JVM_OPTS
    # alone: lein itself does `export JVM_OPTS="${JVM_OPTS:-"$JAVA_OPTS"}"`
    # (verified at leiningen 2.12.0 libexec/bin/lein:238), so once this script
    # always sets JVM_OPTS non-empty, an operator with only JAVA_OPTS set would
    # silently lose it from the client JVMs. An earlier comment here said "an
    # operator's own JVM_OPTS survives" and covered only the variable I thought
    # of.
    for entry in "${CLIENT_SEATS[@]}"; do
        port="${entry%%:*}"
        side="${entry##*:}"
        JVM_OPTS="${_inherited_jvm_opts:+$_inherited_jvm_opts }-Drun-match.owner=$OWNER_TAG" \
            "$DEV_DIR/start-ai-client-repl.sh" "$side" "$port" \
            > "$RUN_MATCH_TMP/ai-client-${side}.log" 2>&1 &
    done

    # Wait and register PER SEAT. Registering only after BOTH are up would leave
    # a client that started fine outside OUR_CLIENT_PIDS when its peer timed
    # out — so the teardown would walk past it and the next match would refuse
    # this one's own orphan.
    # Wait for BOTH seats, registering each the moment its own port is up, and
    # only then decide whether to fail. Two separate reasons, both of them
    # review findings:
    #   - Registering after the PAIR is ready leaves a client that started fine
    #     outside OUR_CLIENT_PIDS when its peer times out, so the teardown walks
    #     past it and the next match refuses this run's own orphan.
    #   - Waiting per seat in sequence has the same hole one layer up: the first
    #     seat's timeout returns before the second is ever examined. The loop
    #     below is over TIME, not over seats, so whichever one comes up is
    #     registered whichever one fails.
    echo "   Waiting for clients to listen..."
    MAX_WAIT=60
    local -a pending=("${CLIENT_SEATS[@]}") still_pending=()
    for ((wait_i=1; wait_i<=MAX_WAIT; wait_i++)); do
        still_pending=()
        for entry in ${pending[@]+"${pending[@]}"}; do
            port="${entry%%:*}"
            side="${entry##*:}"
            if [ -n "$(listen_holders "$port")" ]; then
                register_client_pid "$port" "$side" || return 1
            else
                still_pending+=("$entry")
            fi
        done
        pending=(${still_pending[@]+"${still_pending[@]}"})
        [ "${#pending[@]}" -eq 0 ] && break
        sleep 1
    done
    if [ "${#pending[@]}" -ne 0 ]; then
        for entry in "${pending[@]}"; do
            echo "   ❌ Timeout waiting for the ${entry##*:} client on port ${entry%%:*}"
        done
        return 1
    fi
    echo "   ✅ Clients listening on ports!"
    
    # Wait for WebSocket connection
    echo "   Waiting for WebSocket connections..."
    for ((wait_i=1; wait_i<=MAX_WAIT; wait_i++)); do
        CORP_OK=$("$DEV_DIR/ai-eval.sh" corp 7890 "(ai-websocket-client-v2/connected?)" 2>/dev/null || echo "false")
        RUNNER_OK=$("$DEV_DIR/ai-eval.sh" runner 7889 "(ai-websocket-client-v2/connected?)" 2>/dev/null || echo "false")
        
        if [[ "$CORP_OK" == "true" ]] && [[ "$RUNNER_OK" == "true" ]]; then
            echo "   ✅ Clients fully connected!"
            break
        fi
        
        if [ $wait_i -eq $MAX_WAIT ]; then
            echo "   ❌ Timeout waiting for WebSocket connection"
            echo "   Corp: $CORP_OK, Runner: $RUNNER_OK"
            exit 1
        fi
        sleep 1
    done
    
    sleep 2 # Buffer for init
}

# Helper to load agent code
#
# #290: every arm used to hand-roll `(future (ns/fn))` through ai-eval.sh, which
# bypassed send_command entirely — so no arm was handed the `:heartbeat-file`
# that `bot-loop` passes (#286) and all four read as "SILENT — likely
# disconnected" to anything watching the liveness clock 300s in. Routing through
# send_command also picks up the relaunch guard and `bot-loop-status` /
# `bot-loop-stop`, which the hand-rolled future could not be reached by at all:
# it bound no var.
#
# No port argument. send_command fixes the port from the side (runner 7889 /
# corp 7890) and offers no override, so a port passed in here could only be a
# SECOND copy of that mapping — the two-owner shape, and it would agree on the
# defaults while the real divergence (start-ai-client-repl.sh honours
# CLIENT_1_PORT via load-env.sh; send_command ignores it) stayed unguarded. A
# design seat killed the version of this function that took one.
#
# stdout is NOT discarded, and the exit status is not trusted on its own. The
# relaunch guard prints "already running" and exits 0, and the
# --patient-on-a-goldfish warning is a print too; under `> /dev/null` the
# harness could not tell a refused relaunch from a launch. So: show what
# send_command said, and require the launch confirmation.
load_agent() {
    local side=$1
    local agent_type=$2
    local agent_flag out

    echo "      Injecting $side agent ($agent_type)..."

    # The SIDE is validated, the agent label is not. Before this check the
    # agent-default arm below read `if corp ... else ...`, so an unknown side
    # was silently treated as the runner and the warning blamed the AGENT, which
    # was fine — a code review called that out and it is cheaper to remove the
    # arm than to document it.
    case "$side" in
        corp|runner) ;;
        *)
            echo "      ❌ load_agent: unknown side '$side' (expected corp or runner)"
            echo "         Nothing was injected."
            return 1
            ;;
    esac

    # The harness keeps its own lenient vocabulary (and its per-side default);
    # `bot-loop --agent` is strict, so a bad value never reaches it from here.
    case "$agent_type" in
        "heuristic")
            agent_flag="heuristic"
            ;;
        "passive"|"goldfish")
            agent_flag="goldfish"
            ;;
        *)
            if [ "$side" == "corp" ]; then
                echo "      ⚠️  Unknown Corp agent '$agent_type', defaulting to heuristic"
                agent_flag="heuristic"
            else
                echo "      ⚠️  Unknown Runner agent '$agent_type', defaulting to goldfish"
                agent_flag="goldfish"
            fi
            ;;
    esac

    if ! out=$("$DEV_DIR/send_command" "$side" bot-loop --agent "$agent_flag" 2>&1); then
        printf '%s\n' "$out" | sed 's/^/      /'
        echo "      ❌ $side agent ($agent_flag) was NOT launched: send_command failed."
        echo "         Nothing is playing that seat; aborting rather than timing out in 600s."
        return 1
    fi
    printf '%s\n' "$out" | sed 's/^/      /'
    case "$out" in
        *"Autonomous loop launched"*)
            ;;
        *)
            echo "      ❌ $side agent ($agent_flag): send_command exited 0 but printed no launch"
            echo "         confirmation — a refused relaunch, or a loop that never started."
            return 1
            ;;
    esac
}

run_game() {
    local round=$1
    local game_suffix=$2
    local corp_agent=$3
    local runner_agent=$4
    
    GAME_LOG="$LOG_DIR/${MATCH_ID}_r${round}_game${game_suffix}.log"
    echo "🎮 Starting Game ${game_suffix} (Corp=$corp_agent, Runner=$runner_agent)..."
    
    # Restart clients to ensure no zombie threads
    restart_clients
    
    # Create Game
    LOBBY_TITLE="Match ${MATCH_ID} R${round}${game_suffix}"
    echo "   Creating lobby: $LOBBY_TITLE ($GATEWAY_TYPE)..."
    "$DEV_DIR/send_command" corp create-game "$LOBBY_TITLE" "Corp" "gateway-beginner-corp" "$GATEWAY_TYPE" > "$RUN_MATCH_TMP/create-game.log" 2>&1
    if [ $? -ne 0 ]; then
        echo "   ❌ Failed to create game. Output:"
        cat "$RUN_MATCH_TMP/create-game.log"
    fi
    sleep 2
    
    # Retry loop for Game ID (Search by Title)
    MAX_RETRIES=10
    GAME_ID_RAW=""
    for ((r=1; r<=MAX_RETRIES; r++)); do
        # 1. Request lobby list refresh
        "$DEV_DIR/ai-eval.sh" corp 7890 '(ai-connection/request-lobby-list!)' > /dev/null
        sleep 1
        
        # 2. Search for game ID by title in lobby list
        GAME_ID_RAW=$("$DEV_DIR/ai-eval.sh" corp 7890 "(some #(when (= (:title %) \"$LOBBY_TITLE\") (str (:gameid %))) (:lobby-list @ai-state/client-state))" | tail -1 | tr -d '"' | tr -d '\n')
        
        if [ -n "$GAME_ID_RAW" ] && [ "$GAME_ID_RAW" != "nil" ] && [ "$GAME_ID_RAW" != "" ]; then
            break
        fi
        
        # Fallback: check if we are already in the game
        GAME_ID_RAW=$("$DEV_DIR/ai-eval.sh" corp 7890 '(str (:gameid @ai-state/client-state))' | tail -1 | tr -d '"' | tr -d '\n')
        if [ -n "$GAME_ID_RAW" ] && [ "$GAME_ID_RAW" != "nil" ] && [ "$GAME_ID_RAW" != "" ]; then
             echo "      (Found via client state)"
             break
        fi

        # Fallback 2: Server Log Scrape
        SERVER_LOG="$RUN_MATCH_TMP/game-server.log"
        if [ -f "$SERVER_LOG" ]; then
            GAME_ID_RAW=$(grep -A 1 "$LOBBY_TITLE" "$SERVER_LOG" | tail -n 1 | grep "gameid:" | sed 's/.*gameid: #uuid "\([^"]*\)".*/\1/')
            if [ -n "$GAME_ID_RAW" ] && [ "$GAME_ID_RAW" != "" ] && [[ "$GAME_ID_RAW" != *"DEBUG"* ]]; then
                 echo "      ⚠️  (Found via server log scrape)"
                 break
            fi
        fi

        echo "      Waiting for Game ID... ($r/$MAX_RETRIES)"
        sleep 1
    done

    if [ -z "$GAME_ID_RAW" ] || [ "$GAME_ID_RAW" == "nil" ]; then
        echo "❌ Failed to get Game ID after creation"
        return 1
    fi

    echo "   Game ID: $GAME_ID_RAW"
    
    # Join Game
    echo "   Runner joining..."
    "$DEV_DIR/send_command" runner join "$GAME_ID_RAW" "Runner" "gateway-beginner-runner" > /dev/null
    sleep 2
    
    # Start Game
    echo "   Starting..."
    "$DEV_DIR/send_command" corp start-game > /dev/null

    # Inject Brains
    #
    # A failed injection ends the match run, because `set -e` (:6) is on and
    # run_game is called plainly — and that is the right outcome: a seat with
    # nothing playing it yields a 600s timeout and a log of one bot moving.
    #
    # The PARTIAL case is what needs teardown, and it is a state this change
    # created: before it, load_agent discarded its output and never failed. Corp
    # launched, runner refused, and a corp loop plays on alone in a lobby nobody
    # leaves (code review). So stop both loops and leave both seats before the
    # failure propagates. `bot-loop-stop` and `leave-game` both take
    # `ensure_connection --allow-dead`, so neither can be refused for the same
    # reason the injection just was; `|| true` because teardown must not replace
    # the diagnosis above it with one of its own.
    echo "   🧠 Injecting brains..."
    if ! load_agent "corp" "$corp_agent" || ! load_agent "runner" "$runner_agent"; then
        echo "   🧹 Brain injection failed — stopping any launched loop and leaving the lobby"
        for inj_side in corp runner; do
            "$DEV_DIR/send_command" "$inj_side" bot-loop-stop > /dev/null 2>&1 || true
            "$DEV_DIR/send_command" "$inj_side" leave-game    > /dev/null 2>&1 || true
        done
        return 1
    fi
    
    # Monitor Loop
    echo "   Monitoring..."
    FINISHED=false
    START_TIME=$(date +%s)
    
    while [ "$FINISHED" = false ]; do
        LOG_OUTPUT=$("$DEV_DIR/send_command" corp log 10 2>/dev/null) 
        
        if echo "$LOG_OUTPUT" | grep -qE "GAME OVER|wins the game"; then
            FINISHED=true
            echo "   ✅ Game Over detected!"
        fi
        
        CURRENT_TIME=$(date +%s)
        if [ $((CURRENT_TIME - START_TIME)) -gt 600 ]; then
            echo "   ❌ Timeout!"
            FINISHED=true
        fi
        
        sleep 5
    done
    
    # Save Log
    echo "   Saving log to $GAME_LOG"
    "$DEV_DIR/send_command" corp log 1000 > "$GAME_LOG"
    
    # Extract Result
    "$SCRIPT_DIR/extract-results.sh" "$GAME_LOG"
    
    # Leave Game
    "$DEV_DIR/send_command" corp leave-game > /dev/null
    "$DEV_DIR/send_command" runner leave-game > /dev/null
    sleep 2
}

# 3. Match Loop
for ((i=1; i<=ROUNDS; i++)); do
    echo "=== Round $i of $ROUNDS ==="
    
    # Game A: Agent A (Corp) vs Agent B (Runner)
    run_game "$i" "A" "$AGENT_A" "$AGENT_B"
    
    # Game B: Agent B (Corp) vs Agent A (Runner)
    echo "🔄 Swapping sides..."
    run_game "$i" "B" "$AGENT_B" "$AGENT_A"
done

echo "🏁 Match Complete"
