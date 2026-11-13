#!/usr/bin/env bash
# match_launcher_test.sh — dev/match, the one-command game launcher.
#
# Drives the REAL dev/match, copied into a scratch tree whose dev/ holds stub
# send_command / reset.sh / save-replay.sh / marquee-babysit.sh / ai-bounce.sh /
# seed-decks.sh, with stub askmodel / claude / lsof / curl first on PATH. The
# stub game lives in $STATE files that the stub seats flip (keep, GAME-OVER).
# No REPL, no server, no model, no network — and nothing outside $TMP is
# written (every path dev/match writes is under its own tree).
#
# What is pinned, and why each matters:
#   - argument validation, deck both-or-neither: refused before ANY reset.
#   - brief rendering: title and opponent lines compared WHOLE, and nothing else
#     in the template changes (memory: pin-seat-facing-text-whole).
#   - the Corp-keep gate: the Runner seat starts strictly after the keep; a Corp
#     that dies first means no Runner seat at all.
#   - result.json on every outcome, and exit 0 only for "completed".
#   - cleanup: every seat, grandchild and babysitter it started is gone after
#     exit; a process it did NOT start survives (never kill by name or port).
#   - the launcher's own polling uses a private HEARTBEAT_DIR; a bot seat's
#     launch uses the real one.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEV="$SCRIPT_DIR/.."

# Canonical (no '//' from a trailing-slash TMPDIR): dev/match canonicalizes paths.
TMP="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/nr-match-test.XXXXXX")" && pwd)"
FOREIGN=""
cleanup() {
    # Belt and braces: anything a failing case leaked.
    for f in "$TMP"/*/state/pids-*; do [[ -f "$f" ]] && kill $(cat "$f") 2>/dev/null; done
    [[ -n "$FOREIGN" ]] && kill "$FOREIGN" 2>/dev/null
    rm -rf "$TMP"
}
trap cleanup EXIT

fails=0
ok()   { echo "ok   [$1]"; }
fail() { echo "FAIL [$1]: $2"; fails=$((fails+1)); }
check() { local name="$1"; shift; if "$@"; then ok "$name"; else fail "$name" "${MSG:-condition false}"; fi; MSG=""; }
MSG=""

GID="5ca1ab1e-0000-4000-8000-000000000001"     # the game dev/match creates
PREV="01d01d00-0000-4000-8000-000000000000"    # whatever the REPLs held before

# mk_tree NAME -> sets T (tree) and ST (state dir)
mk_tree() {
    T="$TMP/$1"; ST="$T/state"
    mkdir -p "$T/dev/seats" "$T/dev/replays" "$T/dev/decks" "$T/bin" "$ST" "$T/codex"
    cp "$DEV/match" "$T/dev/match"
    cp "$DEV/seats/template-corp.md" "$DEV/seats/template-runner.md" "$T/dev/seats/"
    echo '{:name "c"}' > "$T/dev/decks/corp.edn"; echo '{:name "r"}' > "$T/dev/decks/runner.edn"
    echo "$PREV" > "$ST/gameid"
    echo "NO-GAME" > "$ST/status"
    echo "false" > "$ST/keep"
    echo "1 1" > "$ST/progress"
    echo '{"winner":"corp","reason":"Agenda","turn":12,"corp":7,"runner":3}' > "$ST/result"
    echo "0 0" > "$ST/lobby"
    echo 5 > "$ST/quota"
    : > "$ST/events.log"; : > "$ST/calls.log"
    echo '{"tokens":{"access_token":"sekrit-token","account_id":"acct"}}' > "$T/codex/auth.json"
    echo "fake-oauth" > "$T/token"

    # --- stub send_command: the game, as state files ----------------------------
    cat > "$T/dev/send_command" <<'EOF'
#!/usr/bin/env bash
ST="$(cd "$(dirname "$0")/.." && pwd)/state"
side="$1"; cmd="${2:-}"; shift 2 2>/dev/null || shift
echo "$side $cmd HB=${HEARTBEAT_DIR:-default} $*" >> "$ST/calls.log"
ev() { echo "$*" >> "$ST/events.log"; }
case "$cmd" in
  game-over-status) cat "$ST/status" ;;
  eval)
    case "$1" in
      *'"GAMEID"'*)      echo "GAMEID $(cat "$ST/gameid")" ;;
      *'"CORPKEEP"'*)    echo "CORPKEEP $(cat "$ST/keep")" ;;
      *'"PROGRESS"'*)    echo "PROGRESS $(cat "$ST/progress")" ;;
      *'"MATCHRESULT"'*) echo "MATCHRESULT $(cat "$ST/result")"; echo nil ;;
      *'"LOBBY"'*)       echo "LOBBY $(cat "$ST/lobby")" ;;
    esac ;;
  bot-loop) ev "bot-loop $side $*"; echo "🔁 Autonomous loop launched for $side" ;;
  bot-loop-status) [[ -f "$ST/bot-done-$side" ]] && echo "Loop done? true -> x" || echo "Loop done? false" ;;
  select-deck) ev "select-deck $side $*"; echo "✅ Deck selected" ;;
  create-game) ev "sc $side $cmd $*"; echo "5ca1ab1e-0000-4000-8000-000000000001" > "$ST/gameid" ;;
  *) ev "sc $side $cmd $*" ;;
esac
exit 0
EOF
    cat > "$T/dev/reset.sh" <<'EOF'
#!/usr/bin/env bash
ST="$(cd "$(dirname "$0")/.." && pwd)/state"
echo "reset CORP_DECK=${CORP_DECK:-} RUNNER_DECK=${RUNNER_DECK:-}" >> "$ST/events.log"
[[ -f "$ST/reset-fails" ]] && { echo "reset stub: failing"; exit 1; }
echo "5ca1ab1e-0000-4000-8000-000000000001" > "$ST/gameid"
echo "AWAITING-START turn=0 next-player=corp blocked=my-mulligan" > "$ST/status"
exit 0
EOF
    cat > "$T/dev/save-replay.sh" <<'EOF'
#!/usr/bin/env bash
D="$(cd "$(dirname "$0")" && pwd)"; ST="$D/../state"
echo "save-replay $*" >> "$ST/events.log"
echo '{"history":[]}' > "$D/replays/$1.json"
echo "✅ Replay saved: $D/replays/$1.json"
EOF
    cat > "$T/dev/ai-bounce.sh" <<'EOF'
#!/usr/bin/env bash
ST="$(cd "$(dirname "$0")/.." && pwd)/state"; echo "ai-bounce" >> "$ST/events.log"
EOF
    cat > "$T/dev/seed-decks.sh" <<'EOF'
#!/usr/bin/env bash
ST="$(cd "$(dirname "$0")/.." && pwd)/state"; echo "seed $*" >> "$ST/events.log"; echo "LEGALITY ok" >&2; echo "deck-id-$1"
EOF
    # --- stub babysitter: records how it was armed, then behaves like the real
    # one at the start: waits for the primary pid (or forever, if told to).
    cat > "$T/dev/marquee-babysit.sh" <<'EOF'
#!/usr/bin/env bash
ST="$(cd "$(dirname "$0")/.." && pwd)/state"
echo "babysit $1 $2 $3 primary=$4 backend=${MARQUEE_BACKEND:-} codex_effort=${MARQUEE_CODEX_EFFORT:-} claude_effort=${MARQUEE_CLAUDE_EFFORT:-} session=${MARQUEE_CLAUDE_SESSION:-} primary_log=${MARQUEE_PRIMARY_LOG:-} chunk_log=${MARQUEE_CHUNK_LOG:-}" >> "$ST/events.log"
echo $$ >> "$ST/pids-babysit"
if [[ -f "$ST/chunk-$1" && -n "${MARQUEE_CHUNK_LOG:-}" ]]; then
  printf '\n===== chunk 1 00:00:00 status=x idle=0 =====\nOpenAI Codex v0\nexec\nTRANSCRIPT-NOISE\ntokens used\n1,234\nFINAL-REPORT-%s\n' "$1" > "$MARQUEE_CHUNK_LOG"
fi
if [[ -f "$ST/babysit-forever" ]]; then while :; do sleep 1; done; fi
while kill -0 "$4" 2>/dev/null; do sleep 0.2; done
exit 0
EOF
    # --- stub seats ----------------------------------------------------------
    # Both CLIs hand off to $ST/seat-<side>.sh <side> <out> (scenario-written);
    # with none, the seat just plays forever (until killed).
    cat > "$T/bin/askmodel" <<'EOF'
#!/usr/bin/env bash
if [[ "${2:-}" == --resolve ]]; then
  case "$1" in
    luna)  printf 'codex\tgpt-6-luna\topenai\n' ;;
    opus)  printf 'claude\tclaude-opus-5-5\tanthropic\n' ;;
    argon) printf 'devin\tgemini-4-argon-high\tgoogle\n' ;;
    *) echo "askmodel: unknown seat '$1'. Known: argon, luna, opus" >&2; exit 125 ;;
  esac; exit 0
fi
out=""; prev=""; for a in "$@"; do [[ "$prev" == -o ]] && out="$a"; prev="$a"; done
side=$(basename "$out" .out); ST="$MATCH_TEST_STATE"
echo "seat-start $side askmodel $*" >> "$ST/events.log"
echo $$ >> "$ST/pids-seat-$side"
code=0
if [[ -x "$ST/seat-$side.sh" ]]; then "$ST/seat-$side.sh" "$side" "$out"; code=$?; else while :; do sleep 1; done; fi
[[ -s "$out" ]] || echo "$side report from askmodel stub" > "$out"
ok=true; [[ -f "$ST/receipt-fail-$side" ]] && ok=false
echo "{\"ok\": $ok, \"reason\": \"stub\"}" > "$out.receipt"
echo "seat-exit $side" >> "$ST/events.log"
exit $code
EOF
    cat > "$T/bin/claude" <<'EOF'
#!/usr/bin/env bash
ST="$MATCH_TEST_STATE"; brief=$(cat)
side=runner; [[ "$brief" == *"you are the CORP"* ]] && side=corp
echo "seat-start $side claude $*" >> "$ST/events.log"
echo $$ >> "$ST/pids-seat-$side"
[[ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]] && echo "claude-token-set $side" >> "$ST/events.log"
code=0
if [[ -x "$ST/seat-$side.sh" ]]; then "$ST/seat-$side.sh" "$side" "" >&2; code=$?; else while :; do sleep 1; done; fi
printf '{"type":"result","result":"%s report from claude stub"}\n' "$side"
echo "seat-exit $side" >> "$ST/events.log"
exit $code
EOF
    cat > "$T/bin/lsof" <<'EOF'
#!/usr/bin/env bash
ST="$MATCH_TEST_STATE"; port=""
for a in "$@"; do case "$a" in -iTCP:*) port="${a#-iTCP:}" ;; esac; done
grep -qx "$port" "$ST/ports-down" 2>/dev/null && exit 1
echo 4242
EOF
    cat > "$T/bin/curl" <<'EOF'
#!/usr/bin/env bash
ST="$MATCH_TEST_STATE"
echo "curl-argv $*" >> "$ST/events.log"
cfg=$(cat); [[ "$cfg" == *"Authorization: Bearer sekrit-token"* ]] && echo "curl-config-has-bearer" >> "$ST/events.log"
echo "{\"rate_limit\":{\"primary_window\":{\"used_percent\": $(cat "$ST/quota")}}}"
EOF
    chmod +x "$T/dev/match" "$T/dev/send_command" "$T/dev/reset.sh" "$T/dev/save-replay.sh" \
             "$T/dev/ai-bounce.sh" "$T/dev/seed-decks.sh" "$T/dev/marquee-babysit.sh" "$T/bin/"*
}

# seat SIDE BODY -> writes the scenario's behaviour for that seat
seat() { printf '#!/usr/bin/env bash\nST=%q\n%s\n' "$ST" "$2" > "$ST/seat-$1.sh"; chmod +x "$ST/seat-$1.sh"; }
# The two behaviours most cases need.
KEEP_THEN_PLAY='sleep 1; echo "\"keep\"" > "$ST/keep"; echo keep-set >> "$ST/events.log"
while ! grep -q GAME-OVER "$ST/status"; do sleep 0.2; done; exit 0'
WIN_SOON='sleep 1; echo "GAME-OVER winner=corp turn=12" > "$ST/status"; exit 0'

# run_match ARGS... -> OUT, CODE (runs the tree's dev/match with the stubs)
run_match() {
    OUT=$(cd "$T" && PATH="$T/bin:$PATH" MATCH_TEST_STATE="$ST" CODEX_HOME="$T/codex" \
          CLAUDE_TOKEN_FILE="$T/token" ASKMODEL="$T/bin/askmodel" \
          MATCH_POLL_SECS=0.2 MATCH_KEEP_SECS="${KEEP:-8}" MATCH_STALL_SECS="${STALL:-2}" \
          MATCH_REPORT_GRACE_SECS=3 MATCH_HUMAN_JOIN_SECS=5 MATCH_MAX_SECS="${MAXS:-40}" \
          timeout 90 "$T/dev/match" "$@" 2>&1); CODE=$?
}
rj() { python3 -c 'import json,sys
d=json.load(open(sys.argv[1]))
for k in sys.argv[2].split("."): d = d[k] if isinstance(d, dict) else None
print("null" if d is None else d)' "$T/logs/match/$1/result.json" "$2" 2>/dev/null; }
line_of() { grep -n -F -- "$2" "$1" | head -1 | cut -d: -f1; }
all_dead() { local p; for f in "$ST"/pids-*; do [[ -f "$f" ]] || continue; for p in $(cat "$f"); do kill -0 "$p" 2>/dev/null && { MSG="pid $p from $(basename "$f") still alive"; return 1; }; done; done; return 0; }
eq() { [[ "$1" == "$2" ]] || { MSG="expected '$2', got '$1'"; return 1; }; }

# ════════════════════════════════════════════════════════════════════════════
# 1. Argument validation: exit 2, nothing run, nothing written.
mk_tree args
refuse() {  # NAME NEEDLE ARGS...
    local name="$1" needle="$2"; shift 2
    run_match "$@"
    if [[ $CODE -eq 2 && "$OUT" == *"$needle"* && ! -s "$ST/events.log" && ! -d "$T/logs/match" ]]; then ok "args: $name"
    else fail "args: $name" "exit $CODE, events: $(cat "$ST/events.log"), out: $OUT"; fi
}
refuse "missing --corp"          "--corp <seat> is required"   --runner luna
refuse "missing --runner"        "--runner <seat> is required" --corp luna
refuse "unknown flag"            "unknown argument: --corps"   --corps luna --runner luna
refuse "flag without value"      "--effort needs a value"      --corp luna --runner luna --effort
refuse "bad effort"              "--effort must be low|medium|high|xhigh" --corp luna --runner luna --effort max
refuse "both human"              "both seats human"            --corp human --runner human
refuse "unknown seat"            "Runner seat 'nosuch' does not resolve" --corp luna --runner nosuch
refuse "bad max-hours"           "--max-hours must be a positive number" --corp luna --runner luna --max-hours 0
refuse "bad tag"                 "--tag may only use"          --corp luna --runner luna --tag 'a b'
refuse "corp deck only"          "decks are both or neither"   --corp luna --runner luna --corp-deck "$T/dev/decks/corp.edn"
refuse "runner deck only"        "decks are both or neither"   --corp luna --runner luna --runner-deck "$T/dev/decks/runner.edn"
refuse "missing deck file"       "no such deck file: $T/dev/decks/nope.edn" --corp luna --runner luna --corp-deck "$T/dev/decks/corp.edn" --runner-deck "$T/dev/decks/nope.edn"
rm -f "$T/token"
refuse "claude seat, no token"   "needs an OAuth token"        --corp opus --runner luna

# --dry-run: prints the plan, writes nothing, runs nothing.
mk_tree dry
run_match --corp luna --runner opus --effort medium --corp-deck dev/decks/corp.edn --runner-deck dev/decks/runner.edn --tag d1 --dry-run
MSG="exit $CODE, events: $(cat "$ST/events.log")"
check "dry-run: exit 0, no events, no run dir" bash -c "[[ $CODE -eq 0 && ! -s '$ST/events.log' && ! -d '$T/logs/match' ]]"
for needle in "reset.sh" "CORP_DECK=$T/dev/decks/corp.edn" "askmodel luna --write --effort medium" "claude --model claude-opus-5-5 --effort medium" \
              "marquee-babysit.sh corp gpt-6-luna d1" "save-replay.sh"; do
    [[ "$OUT" == *"$needle"* ]] && ok "dry-run prints: $needle" || fail "dry-run prints: $needle" "$OUT"
done
[[ "$OUT" != *"fake-oauth"* ]] && ok "dry-run never prints the token" || fail "dry-run never prints the token" "$OUT"

# ════════════════════════════════════════════════════════════════════════════
# 2. completed — and the whole happy path, briefs included.
mk_tree done
touch "$ST/chunk-corp"
seat corp "$KEEP_THEN_PLAY"
seat runner "$WIN_SOON"
run_match --corp luna --runner opus --effort medium --corp-deck dev/decks/corp.edn --runner-deck dev/decks/runner.edn --tag g1
R="$T/logs/match/g1"
check "completed: exit 0" eq "$CODE" 0
[[ $CODE -eq 0 ]] || echo "$OUT" | tail -30
check "completed: outcome"  eq "$(rj g1 outcome)" completed
check "completed: gameid"   eq "$(rj g1 gameid)" "$GID"
check "completed: winner"   eq "$(rj g1 winner)" corp
check "completed: reason"   eq "$(rj g1 reason)" Agenda
check "completed: score"    eq "$(rj g1 score.corp)/$(rj g1 score.runner)" 7/3
check "completed: turns"    eq "$(rj g1 turns)" 12
check "completed: corp seat"   eq "$(rj g1 corp.seat)/$(rj g1 corp.backend)/$(rj g1 corp.model)/$(rj g1 corp.effort)" luna/codex/gpt-6-luna/medium
check "completed: runner seat" eq "$(rj g1 runner.seat)/$(rj g1 runner.backend)/$(rj g1 runner.model)/$(rj g1 runner.effort)" opus/claude/claude-opus-5-5/medium
check "completed: decks"    eq "$(rj g1 decks.corp)" "$T/dev/decks/corp.edn"
check "completed: babysit chunks" eq "$(rj g1 babysit_chunks.corp)/$(rj g1 babysit_chunks.runner)" 1/0
check "completed: codex chunk → only the final message is appended" \
    bash -c "f='$T/dev/replays/${GID:0:8}-corp-luna-report.md'; grep -q FINAL-REPORT-corp \$f && ! grep -q -e TRANSCRIPT-NOISE -e 'tokens used' \$f"
check "completed: replay"   eq "$(rj g1 replay)" "$T/dev/replays/$GID.json"
check "completed: corp report copied"   eq "$(rj g1 reports.corp)" "$T/dev/replays/${GID:0:8}-corp-luna-report.md"
check "completed: runner report copied" eq "$(rj g1 reports.runner)" "$T/dev/replays/${GID:0:8}-runner-opus-report.md"
check "completed: corp report text"   grep -q "corp report from askmodel stub" "$T/dev/replays/${GID:0:8}-corp-luna-report.md"
check "completed: runner report text" grep -q "runner report from claude stub" "$T/dev/replays/${GID:0:8}-runner-opus-report.md"
check "completed: duration/started/ended present" bash -c "[[ '$(rj g1 duration_s)' =~ ^[0-9]+$ && '$(rj g1 started)' == *Z && '$(rj g1 ended)' == *Z ]]"
check "completed: git_sha recorded" bash -c "[[ -n '$(rj g1 git_sha)' && '$(rj g1 git_sha)' != null ]]"
check "completed: decks reached reset" grep -qF "reset CORP_DECK=$T/dev/decks/corp.edn RUNNER_DECK=$T/dev/decks/runner.edn" "$ST/events.log"
check "completed: replay saved AFTER the seats" bash -c "(( $(line_of "$ST/events.log" "seat-exit runner") < $(line_of "$ST/events.log" "save-replay $GID") ))"
check "completed: claude seat got the token in env" grep -q "claude-token-set runner" "$ST/events.log"
check "completed: token never in the seat script" bash -c "! grep -q fake-oauth '$R/seat-runner.sh'"
check "completed: claude seat has its session id" bash -c "grep -q -- \"--session-id \$(cat '$R/runner.session')\" '$ST/events.log'"
check "completed: codex babysitter armed at matched effort, from the trace" \
    grep -qF "babysit corp gpt-6-luna g1 primary=$(head -1 "$ST/pids-seat-corp" 2>/dev/null) backend=codex codex_effort=medium claude_effort= session= primary_log=$R/corp.out.trace.jsonl chunk_log=$R/babysit-corp-chunks.log" "$ST/events.log"
check "completed: claude babysitter armed at matched effort, by session" \
    grep -qF "babysit runner claude-opus-5-5 g1 primary=" "$ST/events.log"
check "completed: claude babysitter session/effort" bash -c "grep 'babysit runner' '$ST/events.log' | grep -qF 'claude_effort=medium session=$(cat "$R/runner.session")'"
check "completed: nothing left running" all_dead
check "completed: launcher.log kept" test -s "$R/launcher.log"

# Briefs: title and opponent lines WHOLE; everything else is the template.
check "brief corp title"  eq "$(sed -n 1p "$R/brief-corp.md")" "# Cross-model match — you are the CORP (GPT-6 Luna) vs Claude Opus 5.5 (Runner)"
check "brief corp opp"    eq "$(sed -n 4p "$R/brief-corp.md")" "as the **Corp** seat, against an isolated Claude Opus 5.5 model playing the Runner."
check "brief runner title" eq "$(sed -n 1p "$R/brief-runner.md")" "# Cross-model match — you are the RUNNER (Claude Opus 5.5) vs GPT-6 Luna (Corp)"
check "brief runner opp"   eq "$(sed -n 4p "$R/brief-runner.md")" "as the **Runner** seat, against an isolated GPT-6 Luna model playing the Corp."
for s in corp runner; do
    # The template minus its two filled lines and the empty note slot == the brief minus the same lines.
    exp=$(sed -e '1d;4d' -e '/^{{OPPONENT_NOTE}}$/,+1d' "$DEV/seats/template-$s.md")
    got=$(sed -e '1d;4d' "$R/brief-$s.md")
    check "brief $s: rest is the template verbatim" eq "$got" "$exp"
    check "brief $s: no unfilled placeholder" bash -c "! grep -q '{{' '$R/brief-$s.md'"
    check "brief $s: forbids memory writes" grep -q "Do not write to any memory directory" "$R/brief-$s.md"
    check "brief $s: points at the canonical brief" grep -q "Read \`dev/seats/seat-$s.md\` now and follow it." "$R/brief-$s.md"
done

# ════════════════════════════════════════════════════════════════════════════
# 3. The Corp-keep gate.
check "gate: corp seat starts before the keep" bash -c "(( $(line_of "$ST/events.log" "seat-start corp") < $(line_of "$ST/events.log" "keep-set") ))"
check "gate: runner seat starts after the keep" bash -c "(( $(line_of "$ST/events.log" "keep-set") < $(line_of "$ST/events.log" "seat-start runner") ))"

mk_tree corpdies
seat corp 'sleep 0.5; exit 3'
run_match --corp luna --runner luna --tag g2
check "gate: corp dies before keeping → exit 1" eq "$CODE" 1
check "gate: … outcome launch-failed" eq "$(rj g2 outcome)" launch-failed
check "gate: … reason names it" bash -c "[[ '$(rj g2 reason)' == *'exited before keeping'* ]]"
check "gate: … the runner seat never started" bash -c "! grep -q 'seat-start runner' '$ST/events.log'"
check "gate: … no runner babysitter either" bash -c "! grep -q 'babysit' '$ST/events.log'"

mk_tree nokeep
KEEP=2 run_match --corp luna --runner luna --tag g3
check "gate: corp never keeps → launch-failed" eq "$(rj g3 outcome)" launch-failed
check "gate: … runner never started" bash -c "! grep -q 'seat-start runner' '$ST/events.log'"
check "gate: … corp seat was stopped" all_dead

# ════════════════════════════════════════════════════════════════════════════
# 4. The other outcomes.
mk_tree stalled
seat corp 'sleep 0.5; echo "\"keep\"" > "$ST/keep"; sleep 0.5; exit 0'
seat runner 'sleep 0.5; exit 0'
echo "x" > "$ST/progress"
run_match --corp luna --runner luna --no-babysit --tag g4
check "stalled: exit 1"   eq "$CODE" 1
check "stalled: outcome"  eq "$(rj g4 outcome)" stalled
check "stalled: replay null (lobby left open)" eq "$(rj g4 replay)" null
check "stalled: reports point at the run dir" eq "$(rj g4 reports.corp)" "$T/logs/match/g4/corp.out"
check "stalled: no babysitter → chunks null" eq "$(rj g4 babysit_chunks.corp)" null
check "stalled: no save-replay of the unfinished game" bash -c "! grep -q 'save-replay $GID' '$ST/events.log'"
check "stalled: the previous game's replay was flushed before the reset" \
    bash -c "(( $(line_of "$ST/events.log" "save-replay $PREV") < $(line_of "$ST/events.log" "reset CORP_DECK") ))"

mk_tree died
seat corp 'sleep 0.5; echo "\"keep\"" > "$ST/keep"; while :; do sleep 1; done'
seat runner 'sleep 0.5; exit 1'
touch "$ST/receipt-fail-runner"
run_match --corp luna --runner luna --no-babysit --tag g5
check "seat-died: outcome" eq "$(rj g5 outcome)" seat-died
check "seat-died: reason names the dead side" bash -c "[[ '$(rj g5 reason)' == *'runner seat exited (ok=False'* ]]"
check "seat-died: the live corp seat was stopped" all_dead

mk_tree bothfail
seat corp 'sleep 0.5; echo "\"keep\"" > "$ST/keep"; sleep 0.5; exit 1'
seat runner 'sleep 0.5; exit 1'
touch "$ST/receipt-fail-corp"
run_match --corp luna --runner luna --no-babysit --tag g5b
check "seat-died: both gone, one failed → seat-died, not stalled" eq "$(rj g5b outcome)" seat-died

mk_tree gone
seat corp "$KEEP_THEN_PLAY"
seat runner 'sleep 0.5; echo "GAME-GONE turn=4" > "$ST/status"; while :; do sleep 1; done'
run_match --corp luna --runner luna --tag g6
check "game-gone: outcome stalled" eq "$(rj g6 outcome)" stalled
check "game-gone: reason says GAME-GONE" bash -c "[[ '$(rj g6 reason)' == GAME-GONE* ]]"

# timeout + cleanup: seats (with grandchildren) and babysitters that never stop.
mk_tree timeout
seat corp 'echo "\"keep\"" > "$ST/keep"; sleep 1000 & echo $! >> "$ST/pids-grandchild"; while :; do sleep 1; done'
seat runner 'sleep 1000 & echo $! >> "$ST/pids-grandchild"; while :; do sleep 1; done'
touch "$ST/babysit-forever"
sleep 1000 & FOREIGN=$!
MAXS=4 run_match --corp luna --runner luna --tag g7
check "timeout: exit 1"  eq "$CODE" 1
check "timeout: outcome" eq "$(rj g7 outcome)" timeout
check "cleanup: seats, grandchildren and babysitters all stopped" all_dead
check "cleanup: there were babysitters to stop" test -s "$ST/pids-babysit"
check "cleanup: there were grandchildren to stop" test -s "$ST/pids-grandchild"
check "cleanup: a process it did not start survives" kill -0 "$FOREIGN"
kill "$FOREIGN" 2>/dev/null; FOREIGN=""

# interrupted: SIGTERM mid-game stops what it started and says so.
mk_tree sigterm
seat corp 'echo "\"keep\"" > "$ST/keep"; while :; do sleep 1; done'
( cd "$T" && PATH="$T/bin:$PATH" MATCH_TEST_STATE="$ST" CODEX_HOME="$T/codex" ASKMODEL="$T/bin/askmodel" \
  MATCH_POLL_SECS=0.2 MATCH_STALL_SECS=60 MATCH_MAX_SECS=60 exec "$T/dev/match" --corp luna --runner luna --tag g8 >/dev/null 2>&1 ) &
LPID=$!
for _ in $(seq 1 50); do grep -q 'seat-start runner' "$ST/events.log" && break; sleep 0.2; done
sleep 0.5; kill -TERM "$LPID" 2>/dev/null; wait "$LPID"; CODE=$?
check "interrupted: outcome" eq "$(rj g8 outcome)" interrupted
check "interrupted: seats stopped" all_dead
check "interrupted: lock released" test ! -e "$T/logs/match/.lock"

# launch-failed in preflight: a port down, a reset that fails.
mk_tree portdown
echo 7888 > "$ST/ports-down"
run_match --corp luna --runner luna --tag g9
check "port down: launch-failed" eq "$(rj g9 outcome)" launch-failed
check "port down: names the port" bash -c "[[ '$(rj g9 reason)' == *'port 7888'* ]]"
check "port down: no reset" bash -c "! grep -q '^reset' '$ST/events.log'"

mk_tree resetfail
touch "$ST/reset-fails"
run_match --corp luna --runner luna --tag g10
check "reset fails: launch-failed, no seat" bash -c "[[ '$(rj g10 outcome)' == launch-failed ]] && ! grep -q seat-start '$ST/events.log'"

# ════════════════════════════════════════════════════════════════════════════
# 5. Preflight: a live game, the codex quota.
mk_tree live
echo "IN-PROGRESS turn=5 whose-turn=corp clicks=2" > "$ST/status"
run_match --corp luna --runner luna --tag g11
check "live game: refused" eq "$(rj g11 outcome)" launch-failed
check "live game: no reset" bash -c "! grep -q '^reset' '$ST/events.log'"
seat corp "$KEEP_THEN_PLAY"; seat runner "$WIN_SOON"
run_match --corp luna --runner luna --tag g12 --force
check "live game + --force: completes" eq "$(rj g12 outcome)" completed
check "live game + --force: old replay flushed BEFORE the reset" \
    bash -c "(( $(line_of "$ST/events.log" "save-replay $PREV") < $(line_of "$ST/events.log" "reset CORP_DECK") ))"

mk_tree quota
echo 85 > "$ST/quota"
run_match --corp luna --runner luna --tag g13
check "quota > 80%: refused" bash -c "[[ '$(rj g13 outcome)' == launch-failed && '$(rj g13 reason)' == *'85% used'* ]]"
check "quota: bearer sent via curl config on stdin" grep -q curl-config-has-bearer "$ST/events.log"
check "quota: token never on curl's argv" bash -c "! grep 'curl-argv' '$ST/events.log' | grep -q sekrit"

# ════════════════════════════════════════════════════════════════════════════
# 6. Heartbeats: the launcher's polls are private; a bot's launch is not.
mk_tree bot
seat runner 'sleep 0.5; echo "GAME-OVER winner=runner turn=9" > "$ST/status"; exit 0'
echo '{"winner":"runner","reason":"Agenda","turn":9,"corp":2,"runner":7}' > "$ST/result"
( until grep -q '^bot-loop corp' "$ST/events.log"; do sleep 0.1; done; sleep 0.5; echo '"keep"' > "$ST/keep" ) &
run_match --corp bot --runner luna --tag g14
R="$T/logs/match/g14"
check "bot: completes" eq "$(rj g14 outcome)" completed
check "bot: corp seat recorded as the heuristic bot" eq "$(rj g14 corp.seat)/$(rj g14 corp.backend)/$(rj g14 corp.effort)" bot/heuristic/null
check "bot: bot-loop launched patient vs a model" grep -qx "bot-loop corp --patient 30" "$ST/events.log"
check "bot: bot-loop used the REAL heartbeat dir" grep -q "^corp bot-loop HB=default" "$ST/calls.log"
check "bot: every launcher poll used the private dir" \
    bash -c "! grep -E ' (game-over-status|eval|bot-loop-status) HB=' '$ST/calls.log' | grep -v 'HB=$R/.heartbeats' | grep -q ."
check "bot: no brief for the bot side" test ! -e "$R/brief-corp.md"
check "bot: runner brief names the bot" eq "$(sed -n 1p "$R/brief-runner.md")" "# Bot match — you are the RUNNER (GPT-6 Luna) vs the heuristic Corp bot (Corp)"
check "bot: bot loop stopped at the end" grep -q "^sc corp bot-loop-stop" "$ST/events.log"

mk_tree botbot
( until grep -q '^bot-loop corp' "$ST/events.log"; do sleep 0.1; done; sleep 0.5
  echo '"keep"' > "$ST/keep"; echo keep-set >> "$ST/events.log"
  until grep -q '^bot-loop runner' "$ST/events.log"; do sleep 0.1; done; echo "GAME-OVER winner=corp turn=3" > "$ST/status" ) &
run_match --corp bot --runner bot --tag g15
check "bot v bot: completes" eq "$(rj g15 outcome)" completed
check "bot v bot: not patient" grep -qx "bot-loop runner " "$ST/events.log"
check "bot v bot: corp bot first, runner bot only after the keep" \
    bash -c "(( $(line_of "$ST/events.log" "bot-loop corp") < $(line_of "$ST/events.log" "keep-set") && $(line_of "$ST/events.log" "keep-set") < $(line_of "$ST/events.log" "bot-loop runner") ))"

# ════════════════════════════════════════════════════════════════════════════
# 7. human: one AI client, the lobby waits, join instructions printed.
mk_tree human
echo "2 0" > "$ST/lobby"
seat corp 'sleep 0.5; echo "GAME-OVER winner=corp turn=8" > "$ST/status"; exit 0'
run_match --corp luna --runner human --tag g16
R="$T/logs/match/g16"
check "human: completes" eq "$(rj g16 outcome)" completed
check "human: no reset.sh (it would seat both clients)" bash -c "! grep -q '^reset' '$ST/events.log'"
check "human: the corp client creates the lobby on its side" grep -qF "sc corp create-game dev/match g16 Corp  Intermediate" "$ST/events.log"
check "human: game started after the human sat" bash -c "(( $(line_of "$ST/events.log" "create-game") < $(line_of "$ST/events.log" "sc corp start-game") ))"
check "human: no runner process" bash -c "! grep -q 'seat-start runner' '$ST/events.log'"
check "human: no runner babysitter" bash -c "! grep -q 'babysit runner' '$ST/events.log'"
check "human: join instructions" bash -c "[[ \"\$1\" == *'HUMAN SEAT — you are the Runner.'* && \"\$1\" == *'http://localhost:1042'* ]]" _ "$OUT"
check "human: brief title" eq "$(sed -n 1p "$R/brief-corp.md")" "# Human match — you are the CORP (GPT-6 Luna) vs a human player (Runner)"
check "human: brief warns peer-status cannot see a human" grep -q "it cannot see a human" "$R/brief-corp.md"
check "human: result records the human seat" eq "$(rj g16 runner.seat)/$(rj g16 runner.backend)" human/web

mk_tree humanwait
run_match --corp luna --runner human --tag g17
check "human: nobody joins → launch-failed, no seat" bash -c "[[ '$(rj g17 outcome)' == launch-failed ]] && ! grep -q seat-start '$ST/events.log'"

mk_tree humandeck
echo "2 2" > "$ST/lobby"
seat runner 'sleep 0.5; echo "GAME-OVER winner=runner turn=8" > "$ST/status"; exit 0'
( until grep -q 'start-game' "$ST/events.log"; do sleep 0.1; done; sleep 0.5; echo '"keep"' > "$ST/keep"; echo keep-set >> "$ST/events.log" ) &
run_match --corp human --runner luna --corp-deck dev/decks/corp.edn --runner-deck dev/decks/runner.edn --tag g18
check "human corp: completes" eq "$(rj g18 outcome)" completed
check "human corp: only the AI side's deck is seeded" bash -c "grep -q '^seed ai-runner $T/dev/decks/runner.edn' '$ST/events.log' && ! grep -q '^seed ai-corp' '$ST/events.log'"
check "human corp: Constructed lobby on the runner side" grep -qF "sc runner create-game dev/match g18 Runner  Constructed" "$ST/events.log"
check "human corp: model runner waits for the human's keep" bash -c "(( $(line_of "$ST/events.log" "keep-set") < $(line_of "$ST/events.log" "seat-start runner") ))"
check "human corp: tells the human how to load their deck" bash -c "[[ \"\$1\" == *'dev/seed-decks.sh <your-username> $T/dev/decks/corp.edn'* ]]" _ "$OUT"

# Same-model brief wording (the luna-v-luna smoke).
mk_tree same
run_match --corp luna --runner luna --tag g19 --dry-run
check "same-model corp title" bash -c "[[ \"\$1\" == *'    # Same-model match — you are the CORP (GPT-6 Luna) vs another GPT-6 Luna (Runner)'* ]]" _ "$OUT"
check "same-model corp opp"   bash -c "[[ \"\$1\" == *'    as the **Corp** seat, against a separate, isolated GPT-6 Luna instance playing the Runner.'* ]]" _ "$OUT"

if [[ $fails -gt 0 ]]; then echo "❌ match_launcher_test: $fails failure(s)"; exit 1; fi
echo "✅ match_launcher_test: all passed"
