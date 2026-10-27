#!/usr/bin/env bash
# run_match_port_guard_test.sh — regression guard for #292: run-match.sh used to
# `kill -9` whatever held the two client REPL ports without asking whose they
# were.
#
# Why this exists. The two client REPLs are a machine-wide singleton — one
# runner on 7889, one corp on 7890, shared by every checkout — and marquee games
# are driven on them by model seats with timeouts measured in hours.
# `restart_clients` ran at the top of every game and killed both, so starting a
# match destroyed any game in progress; the only trace was "Port 7890 still in
# use, force killing...", printed by the killer, in the killer's log, AFTER the
# decision. The previous cycle worked around it by checking `ps` by hand, which
# is a convention held in one session's head.
#
# HOW it tests, and why this way. An earlier test plan drove awk-extracted
# functions, the way peer_status_test.sh drives `load_agent`. A review round
# killed that: extraction cannot reach the top-level wiring, so "the trap is
# gone", "the startup gate call is gone" and "step 1's client start came back"
# would have had no killer except a grep over the source — the
# comment-satisfied-guard shape this repo has filed twice. So the WHOLE SCRIPT
# runs here, in a scratch tree whose `dev/` holds stubs, and the assertions are
# over what the stubs recorded: what was killed, in what order, relative to the
# first launch, and at exit.
#
# Two things the stubs must get right, both of them review findings:
#   - `lsof` is called in two shapes (`-i :P -sTCP:LISTEN -t` and
#     `-p PID -a -d cwd -Fn`). An argument-blind stub feeds a pid list to the cwd
#     read, every registration fails, and the pressure lands on loosening the
#     production check.
#   - `kill` is a bash BUILTIN, so a PATH stub is never reached. It is replaced
#     per-run by a wrapper on PATH only for `xargs kill`; the signals the script
#     sends directly are observed through the fixture's own process table
#     instead, using pids that do not exist on this box.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
REAL_SCRIPT="$REAL_ROOT/dev/orchestrator/run-match.sh"

fails=0
pass() { echo "ok   [$1]"; }
fail() { echo "FAIL [$1]: $2"; fails=$((fails + 1)); }

assert_contains() {
    case "$2" in *"$3"*) pass "$1" ;; *) fail "$1" "expected to find: $3
--- actual ---
$2
--- end ---" ;; esac
}
assert_not_contains() {
    case "$2" in *"$3"*) fail "$1" "did NOT expect to find: $3
--- actual ---
$2
--- end ---" ;; *) pass "$1" ;; esac
}
assert_eq() {
    if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected '$3', got '$2'"; fi
}
# assert_before NAME HAYSTACK EARLIER LATER — ORDERING, not membership. The
# half of #292 that is about ordering ("the message is printed AFTER the
# decision") is invisible to two membership assertions: both are green when the
# warning follows the kill.
assert_before() {
    local name="$1" hay="$2" earlier="$3" later="$4" a b
    a="$(printf '%s\n' "$hay" | grep -n -- "$earlier" | head -1 | cut -d: -f1)"
    b="$(printf '%s\n' "$hay" | grep -n -- "$later"   | head -1 | cut -d: -f1)"
    if [ -z "$a" ]; then fail "$name" "never found earlier marker: $earlier"; return; fi
    if [ -z "$b" ]; then fail "$name" "never found later marker: $later"; return; fi
    if [ "$a" -lt "$b" ]; then pass "$name"; else
        fail "$name" "'$earlier' (line $a) should precede '$later' (line $b)
--- actual ---
$hay
--- end ---"
    fi
}

# ---------------------------------------------------------------------------
# The fixture tree.
#
# PORTS   — "port pid" lines; the fake process table's listeners.
# PROCS   — "pid cmdline" lines; what `ps -o command=` answers.
# LOBBIES — "port claim" lines; what a seat's ai-eval.sh gameid read answers.
# Stubs read these files on every call, so a test can change the world between
# games (a pid appearing mid-match, a REPL joining a lobby) the way the real one
# can.
# ---------------------------------------------------------------------------
make_tree() {
    T="$(mktemp -d "${TMPDIR:-/tmp}/nr-292.XXXXXX")"
    mkdir -p "$T/dev/orchestrator" "$T/bin" "$T/logs/matches" "$T/state" "$T/tmp"
    cp "$REAL_SCRIPT" "$T/dev/orchestrator/run-match.sh"
    chmod +x "$T/dev/orchestrator/run-match.sh"

    : > "$T/state/ports"
    : > "$T/state/procs"
    : > "$T/state/lobbies"
    : > "$T/state/killed"
    : > "$T/state/launched"
    : > "$T/state/sendcmd"
    : > "$T/state/lein"
    : > "$T/state/connected"

    cat > "$T/dev/orchestrator/match-config.json" <<'JSON'
{
  "match_id": "t292",
  "rounds": 1,
  "gateway_type": "Beginner",
  "agents": { "agent_a": "heuristic", "agent_b": "goldfish" }
}
JSON

    # lsof: dispatches on the shape it was called with.
    cat > "$T/bin/lsof" <<'LSOF'
#!/usr/bin/env bash
S="$NR292_STATE"
if [ "${1:-}" = "-p" ]; then
    # -p PID -a -d cwd -Fn
    pid="$2"
    printf 'p%s\nfcwd\nn%s\n' "$pid" "$NR292_FAKE_CWD"
    exit 0
fi
# -i :PORT -sTCP:LISTEN -t    (and the bare -i :PORT the game-server check uses)
#
# state/ports holds LISTENers; state/connected holds processes with a socket on
# the port at the OTHER end (an in-flight ai-eval.sh, say). Real lsof reports
# both for `-i :PORT` and only the first for `-i :PORT -sTCP:LISTEN`, so this
# stub does too — otherwise dropping the flag in production changes nothing
# here and the mutant survives.
port="${2#:}"
listen_only=0
for a in "$@"; do [ "$a" = "-sTCP:LISTEN" ] && listen_only=1; done
out="$(awk -v p="$port" '$1==p {print $2}' "$S/ports")"
if [ "$listen_only" -eq 0 ]; then
    extra="$(awk -v p="$port" '$1==p {print $2}' "$S/connected" 2>/dev/null)"
    out="$(printf '%s\n%s\n' "$out" "$extra" | grep -v '^$' || true)"
fi
[ -z "$out" ] && exit 1
printf '%s\n' "$out"
LSOF

    # ps -o command= -p PID  /  ps -o etime= -p PID
    cat > "$T/bin/ps" <<'PS'
#!/usr/bin/env bash
S="$NR292_STATE"
what="${2:-}"
pid="${4:-}"
case "$what" in
  command=) awk -v q="$pid" '$1==q {$1=""; sub(/^ /,""); print}' "$S/procs" ;;
  etime=)   grep -q "^$pid " "$S/procs" && echo "19:24:43" ;;
esac
grep -q "^$pid " "$S/procs" || exit 1
PS

    # The fixture's process table IS the truth about liveness, so the script's
    # direct `kill` calls have to reach it. kill is a builtin; the driver below
    # redefines it as a shell function before sourcing nothing — instead the
    # script runs as a child, so the wrapper is installed through a BASH_ENV
    # file that bash reads for non-interactive shells.
    cat > "$T/killwrap.sh" <<'KW'
kill() {
    local sig="" pid
    if [ "${1:0:1}" = "-" ]; then sig="$1"; shift; fi
    for pid in "$@"; do
        if [ "$sig" = "-0" ]; then
            grep -q "^$pid " "$NR292_STATE/procs" || return 1
            continue
        fi
        echo "${sig:--TERM} $pid" >> "$NR292_STATE/killed"
        # And on STDOUT, which run_match captures: an ordering assertion needs
        # the kill interleaved with the script's own output, and asserting only
        # that the inventory precedes the "stopping" line let a mutant that
        # moved the announcement below the TERM survive. stdout and not stderr
        # because every call site spells it `kill "$pid" 2>/dev/null`, which
        # would send this straight to /dev/null — the first attempt did exactly
        # that and the two assertions failed for that reason, not the bug's.
        echo "FIXTURE-KILL ${sig:--TERM} $pid"
        # -9 removes it from the world; TERM only if the fixture says it obeys.
        if [ "$sig" = "-9" ] || [ "${NR292_TERM_OBEYED:-1}" = "1" ]; then
            grep -v "^$pid " "$NR292_STATE/procs" > "$NR292_STATE/procs.tmp" 2>/dev/null
            mv "$NR292_STATE/procs.tmp" "$NR292_STATE/procs"
            awk -v q="$pid" '$2!=q' "$NR292_STATE/ports" > "$NR292_STATE/ports.tmp"
            mv "$NR292_STATE/ports.tmp" "$NR292_STATE/ports"
        fi
    done
    return 0
}
KW

    cat > "$T/dev/start-ai-client-repl.sh" <<'LAUNCH'
#!/usr/bin/env bash
# Records the launch AND the JVM_OPTS it was handed, then puts a listener in the
# fixture carrying whatever owner tag it was given — unless the test asked for a
# bind race (NR292_RACE_PORT), in which case a stranger with no tag gets there
# first.
S="$NR292_STATE"
side="$1"; port="$2"
echo "$side $port JVM_OPTS=${JVM_OPTS:-}" >> "$S/launched"
if [ "${NR292_FAIL_PORT:-}" = "$port" ]; then
    # Started, never bound. No listener appears for this port.
    exit 0
fi
if [ "${NR292_RACE_PORT:-}" = "$port" ]; then
    pid="9${port}"
    echo "$pid /usr/bin/java -classpath /elsewhere/netrunner nrepl.cmdline --port $port" >> "$S/procs"
else
    pid="8${port}"
    echo "$pid /usr/bin/java ${JVM_OPTS:-} -classpath /x nrepl.cmdline --port $port" >> "$S/procs"
fi
echo "$port $pid" >> "$S/ports"
LAUNCH

    cat > "$T/dev/ai-eval.sh" <<'EVAL'
#!/usr/bin/env bash
S="$NR292_STATE"
side="$1"; port="$2"; expr="$3"
case "$expr" in
  *lobby-list*)  echo '"fixture-game-id"' ;;
  *gameid*)
      claim="$(awk -v p="$port" '$1==p {$1=""; sub(/^ /,""); print}' "$S/lobbies")"
      [ "$claim" = "DEAD" ] && exit 1
      # An idle seat has :gameid nil, and the two probe spellings print it
      # DIFFERENTLY: (str nil) is the empty string, (pr-str nil) is the three
      # characters n-i-l. Answering "nil" to both is what let the production
      # code read every idle REPL as unresponsive while this test stayed green.
      if [ -z "$claim" ] || [ "$claim" = "nil" ]; then
          case "$expr" in
              *pr-str*) printf '"nil"\n' ;;
              *)        printf '""\n' ;;
          esac
      else
          printf '"%s"\n' "$claim"
      fi
      ;;
  *connected?*) echo "true" ;;
  *) echo "nil" ;;
esac
EVAL

    cat > "$T/dev/send_command" <<'SEND'
#!/usr/bin/env bash
echo "$*" >> "$NR292_STATE/sendcmd"
# Deterministic signal delivery for the trap case: no racing a background
# sleeper against a script whose sleeps are stubbed out. /bin/kill BY PATH,
# because BASH_ENV has replaced the `kill` BUILTIN in every bash child here.
if [ -n "${NR292_SIGNAL_AT:-}" ] && [ "$2" = "$NR292_SIGNAL_AT" ]; then
    /bin/kill -TERM "$PPID"
fi
case "$2" in
  bot-loop) echo "🤖 Autonomous loop launched" ;;
  log)      echo "GAME OVER - test harness" ;;
  *)        echo "ok" ;;
esac
SEND

    cat > "$T/dev/orchestrator/extract-results.sh" <<'EX'
#!/usr/bin/env bash
echo "results extracted"
EX

    # The game server. Kept OUT of state/launched, which is the client-launch
    # ledger the "nothing was launched" assertions read.
    # Every `sleep` in the script is waiting for something the stubs have
    # already done. Stubbed to nothing, or 11 whole-script runs take an hour.
    cat > "$T/bin/sleep" <<'SLEEP'
#!/usr/bin/env bash
exit 0
SLEEP

    cat > "$T/bin/lein" <<'LEIN'
#!/usr/bin/env bash
echo "lein $*" >> "$NR292_STATE/lein"
echo "1042 1042042" >> "$NR292_STATE/ports"
echo "1042042 lein run (game server stub)" >> "$NR292_STATE/procs"
LEIN

    chmod +x "$T/bin/"* "$T/dev/"*.sh "$T/dev/send_command" "$T/dev/orchestrator/extract-results.sh"
}

# run_match ARGS... — run the whole script in the fixture and capture it.
run_match() {
    OUT="$(cd "$T" && \
        NR292_STATE="$T/state" \
        NR292_FAKE_CWD="${NR292_FAKE_CWD:-$T}" \
        NR292_RACE_PORT="${NR292_RACE_PORT:-}" \
        NR292_SIGNAL_AT="${NR292_SIGNAL_AT:-}" \
        NR292_FAIL_PORT="${NR292_FAIL_PORT:-}" \
        RUN_MATCH_TMP="$T/tmp" \
        BASH_ENV="$T/killwrap.sh" \
        PATH="$T/bin:$PATH" \
        bash "$T/dev/orchestrator/run-match.sh" "$@" 2>&1)"
    CODE=$?
    KILLED="$(cat "$T/state/killed" 2>/dev/null)"
    LAUNCHED="$(cat "$T/state/launched" 2>/dev/null)"
}

listener() { echo "$1 $2" >> "$T/state/ports"; echo "$2 ${3:-/usr/bin/java -classpath /x nrepl.cmdline}" >> "$T/state/procs"; }
# A process with a socket on the port that is NOT listening on it.
connected() { echo "$1 $2" >> "$T/state/connected"; echo "$2 ${3:-ai-eval client}" >> "$T/state/procs"; }
lobby()    { echo "$1 $2" >> "$T/state/lobbies"; }
cleanup()  { [ -n "${T:-}" ] && rm -rf "$T"; }
trap cleanup EXIT

echo "=== #292: run-match.sh port ownership gate ==="

# --- 1. free ports: the gate must not refuse, and must not abort under set -e.
# The lsof stub EXITS 1 on a free port, which is what real lsof does and what a
# stub that printed nothing and exited 0 would hide: a bare `h=$(lsof ...)`
# capture aborts the whole script under `set -e` on the HAPPY path.
make_tree
lobby 7890 nil; lobby 7889 nil
run_match
assert_not_contains "free-ports-no-refusal"      "$OUT" "Refusing to start"
assert_not_contains "free-ports-no-abort"        "$OUT" "Client ports are held"
assert_contains     "free-ports-reached-the-match" "$OUT" "Starting Match Orchestrator"
assert_contains     "free-ports-launched-clients"  "$LAUNCHED" "corp 7890"
cleanup

# --- 2. a foreign holder, no --force: refuse, name it, kill nothing.
make_tree
listener 7890 4242
lobby 7890 nil; lobby 7889 nil
run_match
assert_eq       "foreign-refusal-exit-3" "$CODE" "3"
assert_contains "foreign-refusal-names-port" "$OUT" "port 7890 (corp seat): pid 4242"
assert_contains "foreign-refusal-shows-cwd"  "$OUT" "cwd:"
assert_contains "foreign-refusal-shows-age"  "$OUT" "up:    19:24:43"
assert_contains "foreign-refusal-offers-force" "$OUT" "--force"
assert_eq       "foreign-refusal-killed-nothing" "$KILLED" ""
assert_eq       "foreign-refusal-launched-nothing" "$LAUNCHED" ""
cleanup

# --- 3. a foreign holder with --force: proceed, and ANNOUNCE BEFORE KILLING.
make_tree
listener 7890 4242
lobby 7890 nil; lobby 7889 nil
run_match --force
assert_contains "force-proceeds"        "$OUT" "--force given"
assert_contains "force-killed-the-pid"  "$KILLED" "4242"
assert_before   "force-announces-before-stopping" "$OUT" \
                "stopping pid 4242" "FIXTURE-KILL -TERM 4242"
assert_before   "force-inventory-precedes-the-kill" "$OUT" \
                "Client ports are held" "FIXTURE-KILL -TERM 4242"
assert_before   "force-inventory-precedes-consent" "$OUT" \
                "pid 4242" "--force given"
cleanup

# --- 4. the self-recovery path: our own clients, restarted between games,
# need no flag. This is the finding that broke two earlier designs — a gate
# that refuses the harness's own leftovers trains the operator to always pass
# --force. The 1-round config plays two games, so restart_clients runs twice
# and the second run meets listeners this script started.
make_tree
lobby 7890 nil; lobby 7889 nil
run_match
assert_not_contains "own-clients-not-refused" "$OUT" "Refusing to start"
assert_not_contains "own-clients-not-foreign" "$OUT" "this match did not start"
assert_contains     "own-clients-restarted"   "$OUT" "stopping pid 87890"
assert_contains     "two-games-ran"           "$OUT" "Match Complete"
cleanup

# --- 5. the bind race: after the kill, a stranger takes the port. It is NEW,
# and in the real world it is rooted in a checkout just like ours, so only the
# owner tag can tell them apart. The script must neither adopt it nor kill it.
make_tree
lobby 7890 nil; lobby 7889 nil
NR292_RACE_PORT=7890 run_match
assert_contains     "race-refuses-to-adopt" "$OUT" "does not carry this match's"
assert_contains     "race-leaves-it-alone"  "$OUT" "Leaving it alone"
assert_not_contains "race-did-not-kill-the-stranger" "$KILLED" "97890"
cleanup

# --- 6. the owner tag actually travels: the launcher is handed JVM_OPTS
# carrying this invocation's tag. Without it, ownership would be "whoever is
# listening", which is what case 5 exists to refuse.
make_tree
lobby 7890 nil; lobby 7889 nil
run_match
assert_contains "launcher-got-the-owner-tag" "$LAUNCHED" "-Drun-match.owner=run-match-"
cleanup

# --- 7. an owned REPL that joined somebody else's lobby between games is NOT
# restarted. "I launched it" is not "nobody else is using it": run_game leaves
# both lobbies before returning, so a lobby here means another driver adopted
# the seat (send_command and resume.sh take whatever is listening).
make_tree
lobby 7890 nil; lobby 7889 nil
# The stubs read their fixture files on every call, so rewriting one mid-run
# changes the world the script sees — here, the corp seat joins a lobby after
# game 1 has left its own.
# The change is made BY a stub rather than by a background sleeper, so it lands
# at a known point: when game 1 leaves its lobby, somebody else gives the corp
# seat one.
cat >> "$T/dev/send_command" <<'HOOK'
if [ "$2" = "leave-game" ] && [ "$1" = "corp" ]; then
    printf '7890 abc-123\n7889 nil\n' > "$NR292_STATE/lobbies"
fi
HOOK
run_match
assert_contains "busy-own-repl-refused"   "$OUT" "reports that it is seated in a game"
assert_contains "busy-own-repl-abandons"  "$OUT" "Abandoning the remaining games"
# The refusal must not be followed by the teardown killing the very REPL it
# refused to disturb. That was this fix's own worst defect: "Not restarting it",
# then TERM/-9 from the EXIT trap a moment later — #292's harm, committed by
# #292's fix, with the log saying the opposite.
assert_not_contains "busy-own-repl-survives-teardown" "$KILLED" "87890"
assert_contains     "busy-own-repl-peer-still-stopped" "$KILLED" "87889"
cleanup

# --- 8. consent is spent. --force covers the pids the operator was SHOWN; a
# different foreign pid that appears later is not covered, flag or no flag.
make_tree
listener 7890 4242
lobby 7890 nil; lobby 7889 nil
cat >> "$T/dev/send_command" <<'HOOK'
if [ "$2" = "leave-game" ] && [ "$1" = "corp" ]; then
    echo "7889 5555" >> "$NR292_STATE/ports"
    echo "5555 /usr/bin/java -classpath /someone-else nrepl.cmdline" >> "$NR292_STATE/procs"
fi
HOOK
run_match --force
assert_contains     "late-foreign-pid-refused" "$OUT" "appeared after the match started"
assert_contains     "late-foreign-pid-says-force-does-not-cover" "$OUT" "--force does not cover it"
assert_not_contains "late-foreign-pid-not-killed" "$KILLED" "5555"
cleanup

# --- 9. teardown: the clients this run started are stopped when it ends, and
# nothing else is. Without the teardown the harness's own leftovers are foreign
# to the NEXT invocation and --force becomes habitual.
make_tree
lobby 7890 nil; lobby 7889 nil
run_match
assert_contains "teardown-ran"              "$OUT" "client REPL(s) this match started"
assert_contains "teardown-stopped-corp"     "$KILLED" "87890"
assert_contains "teardown-stopped-runner"   "$KILLED" "87889"
cleanup

# --- 10. teardown fires on a SIGNAL too, and ends the script. A handler that
# only tears down lets bash resume after the interrupted command, with dead
# REPLs and a match still to play.
make_tree
lobby 7890 nil; lobby 7889 nil
NR292_SIGNAL_AT=start-game run_match
assert_contains     "signal-tore-down"     "$OUT" "client REPL(s) this match started"
assert_not_contains "signal-ended-the-run" "$OUT" "Match Complete"
assert_contains     "signal-stopped-corp"  "$KILLED" "87890"
cleanup

# --- 10b. the gate asks for LISTENERS, not for sockets. `lsof -i :PORT` alone
# selects a socket at EITHER end, so an in-flight ai-eval.sh connection would
# count as a holder — and can exit between the inventory and the per-pid lookup.
# Nothing is listening here; there is only a client connection on 7890.
make_tree
connected 7890 6001
lobby 7890 nil; lobby 7889 nil
run_match
assert_not_contains "connection-is-not-a-holder" "$OUT" "pid 6001"
assert_not_contains "connection-does-not-refuse" "$OUT" "Refusing to start"
assert_contains     "connection-match-proceeds"  "$OUT" "Starting Match Orchestrator"
cleanup

# --- 10c. a REPL that will not answer the lobby probe must be reported as
# silent, not as idle. "did not answer" and "no lobby" must not collapse: for a
# foreign holder the operator reads one of them to decide a kill.
make_tree
listener 7890 4242
lobby 7890 DEAD; lobby 7889 nil
run_match
assert_contains     "silent-foreign-repl-says-so" "$OUT" "says:  did not answer"
assert_not_contains "silent-foreign-repl-not-idle" "$OUT" "says:  no lobby"
cleanup

# --- 10d. ... and one of OURS that will not answer is a wedged REPL, which is
# what restart_clients exists to clear. Deliberate asymmetry, so it is pinned
# out loud rather than left to a reader of the code.
make_tree
lobby 7890 nil; lobby 7889 nil
cat >> "$T/dev/send_command" <<'HOOK'
if [ "$2" = "leave-game" ] && [ "$1" = "corp" ]; then
    printf '7890 DEAD\n7889 nil\n' > "$NR292_STATE/lobbies"
fi
HOOK
run_match
assert_contains "wedged-own-repl-noticed"  "$OUT" "did not answer the lobby probe"
assert_contains "wedged-own-repl-restarted" "$OUT" "treating it as wedged"
assert_contains "wedged-own-repl-ran-game-2" "$OUT" "Match Complete"
cleanup

# --- 10e. a seat that starts while its PEER times out must still be owned, or
# the teardown walks past it and the NEXT match refuses this one's own orphan.
# (Registering only after both listeners are up is exactly that defect.)
make_tree
lobby 7890 nil; lobby 7889 nil
NR292_FAIL_PORT=7889 run_match
assert_contains "half-start-timed-out"       "$OUT" "Timeout waiting for the runner client"
assert_contains "half-start-owns-the-corp"   "$KILLED" "87890"
assert_contains "half-start-tore-down"       "$OUT" "client REPL(s) this match started"
cleanup

# --- 10f. an idle REPL is idle, not silent. `(str nil)` is the EMPTY STRING,
# so probing with `str` made every idle seat read as "did not answer" — the
# wrong half of the distinction that decides a kill, and the one the operator
# is being asked to judge.
make_tree
listener 7890 4242
lobby 7890 nil; lobby 7889 nil
run_match
assert_contains     "idle-foreign-repl-says-no-lobby" "$OUT" "says:  no lobby"
assert_not_contains "idle-foreign-repl-not-silent"    "$OUT" "says:  did not answer"
cleanup

# --- 10g. ... and our own idle seats are not announced as wedged every game.
make_tree
lobby 7890 nil; lobby 7889 nil
run_match
assert_not_contains "idle-own-repl-not-called-wedged" "$OUT" "treating it as wedged"
assert_contains     "idle-own-repl-ran-both-games"    "$OUT" "Match Complete"
cleanup

# --- 10i. the MIRROR of 10e: when the FIRST seat in CLIENT_SEATS is the one
# that never binds, the other must still be owned. A per-seat wait that returns
# on the first timeout never looks at the second seat at all.
make_tree
lobby 7890 nil; lobby 7889 nil
NR292_FAIL_PORT=7890 run_match
assert_contains "mirror-half-start-timed-out"     "$OUT" "Timeout waiting for the corp client"
assert_contains "mirror-half-start-owns-the-peer" "$KILLED" "87889"
cleanup

# --- 10j. the script's scratch logs go where it is told, not into the live
# /tmp paths. Before the seam, every run of this test truncated
# /tmp/game-server.log and both /tmp/ai-client-*.log — the real REPLs' logs,
# which is where a marquee's disconnect evidence would be.
make_tree
lobby 7890 nil; lobby 7889 nil
run_match
if [ -s "$T/tmp/ai-client-corp.log" ] || [ -f "$T/tmp/game-server.log" ]; then
    pass "scratch-logs-land-in-the-fixture"
else
    fail "scratch-logs-land-in-the-fixture" "nothing was written under \$T/tmp — the seam is not being used
$(ls -la "$T/tmp" 2>&1)"
fi
cleanup

# --- 11. argument parsing, driven directly (no match needed).
make_tree
AP="$(awk '/^parse_match_args\(\) \{/,/^\}/' "$REAL_SCRIPT")"
USG="$(awk '/^usage\(\) \{/,/^\}/' "$REAL_SCRIPT")"
if [ -z "$AP" ] || ! bash -c "$AP"$'\n''declare -F parse_match_args >/dev/null'; then
    fail "parse_match_args-extracted" "awk extraction did not yield a sourceable function"
else
    pass "parse_match_args-extracted"
    drive() { bash -c "DEFAULT_CONFIG=/d/default.json
$USG
$AP
parse_match_args \"\$@\" || exit \$?
echo \"FORCE=\$FORCE CONFIG=\$CONFIG_FILE\"" _ "$@" 2>&1; }
    assert_contains "args-none"          "$(drive)"                       "FORCE=0 CONFIG=/d/default.json"
    assert_contains "args-force-only"    "$(drive --force)"               "FORCE=1 CONFIG=/d/default.json"
    assert_contains "args-force-first"   "$(drive --force /x/c.json)"     "FORCE=1 CONFIG=/x/c.json"
    assert_contains "args-force-last"    "$(drive /x/c.json --force)"     "FORCE=1 CONFIG=/x/c.json"
    assert_contains "args-path-only"     "$(drive /x/c.json)"             "FORCE=0 CONFIG=/x/c.json"
    assert_contains "args-typo-refused"  "$(drive --forse)"               "unknown option: --forse"
    assert_not_contains "args-typo-is-not-a-config" "$(drive --forse)"    "CONFIG=--forse"
    assert_contains "args-two-paths-refused" "$(drive /a.json /b.json)"   "more than one config file"
fi
cleanup

# --- 12. structural, over the source with COMMENTS STRIPPED. Every claim below
# is about what RUNS; this repo has twice filed a guard that its own explanatory
# comment satisfied, and both of the strings here appear in the comments.
CODE_ONLY="$(sed 's/#.*$//' "$REAL_SCRIPT")"
RESTART_FN="$(printf '%s\n' "$CODE_ONLY" | awk '/^restart_clients\(\) \{/,/^\}/')"
assert_not_contains "restart-no-pidfile-kill"  "$RESTART_FN" "stop-ai-client.sh"
assert_not_contains "restart-no-literal-loop"  "$RESTART_FN" "7890 7889"
assert_contains     "restart-iterates-one-list" "$RESTART_FN" 'CLIENT_SEATS[@]'
assert_before       "restart-gates-before-killing" "$RESTART_FN" \
                    "preflight_client_ports restart" "kill "
assert_contains     "trap-is-armed"            "$CODE_ONLY" "trap teardown_our_clients EXIT"
assert_contains     "trap-covers-hup"          "$CODE_ONLY" "on_fatal_signal 129"
assert_contains     "startup-gate-is-called"   "$CODE_ONLY" "preflight_client_ports startup"
# step 1's adopt-or-start must not come back: outside restart_clients, nothing
# starts a client.
OUTSIDE="$(printf '%s\n' "$CODE_ONLY" | awk '/^restart_clients\(\) \{/,/^\}/ {next} {print}')"
assert_not_contains "no-client-start-outside-restart" "$OUTSIDE" "start-ai-client-repl.sh"
# Every scratch path must go through the seam, or this test starts truncating
# the live REPLs' logs again.
assert_not_contains "no-bare-tmp-paths" "$(printf '%s\n' "$CODE_ONLY" | grep -v 'RUN_MATCH_TMP:-')" "/tmp/"
assert_contains     "probe-uses-pr-str" "$CODE_ONLY" "pr-str (:gameid"
assert_contains     "probe-caps-the-grace" "$CODE_ONLY" "TIMEOUT_GRACE=0"

echo ""
if [ "$fails" -eq 0 ]; then
    echo "✅ #292 port guard: all assertions passed"
    exit 0
else
    echo "❌ #292 port guard: $fails assertion(s) failed"
    exit 1
fi
