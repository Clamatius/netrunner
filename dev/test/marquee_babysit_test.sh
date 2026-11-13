#!/usr/bin/env bash
# marquee_babysit_test.sh — the two parameters dev/match needs from the babysitter.
#
# 1. The codex session id. askmodel now runs codex with `--json`, so a seat's
#    session id is no longer a `session id: <uuid>` header in <out>.stderr: it is
#    the `thread_id` of the `thread.started` event in <out>.trace.jsonl. The
#    babysitter only knew the header, so pointed at an askmodel seat it exited
#    "no 'session id:' found … cannot resume" at the one moment it was needed.
# 2. MARQUEE_CHUNK_LOG: where the chunk log goes, so a launcher can keep it in
#    its own run dir. Unset, the old logs/marquee-<side>-<model>-<tag>-chunks.log
#    path is unchanged.
#
# Drives the REAL script, copied into a scratch tree (it cds to its own ../ and
# calls ./dev/send_command from there), with stub send_command / codex / sleep.
# No REPL, no model, no network.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL="$SCRIPT_DIR/../marquee-babysit.sh"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-babysit-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

fails=0
ok()   { echo "ok   [$1]"; }
fail() { echo "FAIL [$1]: $2"; fails=$((fails+1)); }

# tree NAME -> builds $TMP/NAME/{dev/marquee-babysit.sh,dev/send_command,bin/*}
tree() {
    local t="$TMP/$1"
    mkdir -p "$t/dev" "$t/bin" "$t/logs"
    cp "$REAL" "$t/dev/marquee-babysit.sh"
    # First game-over-status call: live. Every later call: over. So the loop runs
    # exactly one chunk and then exits TERMINAL.
    cat > "$t/dev/send_command" <<'EOF'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")/.." && pwd)"
case "${2:-}" in
  game-over-status)
    if [[ -f "$here/.seen" ]]; then echo "GAME-OVER winner=corp turn=9"
    else touch "$here/.seen"; echo "IN-PROGRESS turn=3 whose-turn=corp clicks=2"; fi ;;
  get-cursor) echo 7 ;;
esac
EOF
    cat > "$t/bin/codex" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$t/codex.args"
echo "stub codex chunk output"
EOF
    printf '#!/bin/sh\nexit 0\n' > "$t/bin/sleep"
    chmod +x "$t/dev/marquee-babysit.sh" "$t/dev/send_command" "$t/bin/codex" "$t/bin/sleep"
    echo "$t"
}

# --- 1. codex session id from an askmodel --json trace ----------------------
t=$(tree json)
printf '%s\n' '{"type":"thread.started","thread_id":"01a17768-d284-7110-beb6-c111536d5bc3"}' \
              '{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"hi"}}' > "$t/seat.md.trace.jsonl"
out=$(PATH="$t/bin:$PATH" MARQUEE_BACKEND=codex MARQUEE_PRIMARY_LOG="$t/seat.md.trace.jsonl" \
      MARQUEE_CODEX_EFFORT=medium MARQUEE_CHUNK_LOG="$t/run/babysit-corp-chunks.log" \
      "$t/dev/marquee-babysit.sh" corp gpt-6-luna tagx 2>&1); code=$?
[[ $code -eq 0 ]] && ok "json: exits 0 at GAME-OVER" || fail "json: exits 0 at GAME-OVER" "exit $code; output: $out"
args=$(cat "$t/codex.args" 2>/dev/null)
[[ "$args" == "exec resume 01a17768-d284-7110-beb6-c111536d5bc3 "* ]] \
    && ok "json: resumes the thread.started id" || fail "json: resumes the thread.started id" "codex args: '$args'"
[[ "$args" == *'model_reasoning_effort=medium'* ]] \
    && ok "json: passes the requested effort" || fail "json: passes the requested effort" "codex args: '$args'"
[[ "$out" == *"TERMINAL: GAME-OVER winner=corp turn=9 (after 1 chunks)"* ]] \
    && ok "json: one chunk then TERMINAL" || fail "json: one chunk then TERMINAL" "$out"

# --- 2. MARQUEE_CHUNK_LOG decides where the chunk log goes --------------------
[[ -f "$t/run/babysit-corp-chunks.log" ]] && grep -q '^===== chunk 1 ' "$t/run/babysit-corp-chunks.log" \
    && ok "chunk log at MARQUEE_CHUNK_LOG" || fail "chunk log at MARQUEE_CHUNK_LOG" "$(ls -R "$t" 2>&1)"
[[ -z "$(ls "$t/logs" 2>/dev/null)" ]] \
    && ok "nothing written to the default logs/ path when overridden" || fail "nothing written to default logs/" "$(ls "$t/logs")"

# --- 3. the legacy stderr header still works; default path unchanged ----------
t=$(tree header)
printf 'banner\nsession id: 11111111-2222-3333-4444-555555555555\nmore\n' > "$t/seat.stderr"
out=$(PATH="$t/bin:$PATH" MARQUEE_BACKEND=codex MARQUEE_PRIMARY_LOG="$t/seat.stderr" \
      "$t/dev/marquee-babysit.sh" runner gpt-6-sol tagy 2>&1); code=$?
args=$(cat "$t/codex.args" 2>/dev/null)
[[ "$args" == "exec resume 11111111-2222-3333-4444-555555555555 "* ]] \
    && ok "header: resumes the session id: header" || fail "header: resumes the session id: header" "codex args: '$args'; out: $out"
[[ -f "$t/logs/marquee-runner-gpt-6-sol-tagy-chunks.log" ]] \
    && ok "header: default chunk log path unchanged" || fail "header: default chunk log path unchanged" "$(ls "$t/logs")"

# --- 4. neither form present: still refuses, does not resume garbage ----------
t=$(tree none)
printf '{"type":"item.completed"}\n' > "$t/seat.md.trace.jsonl"
out=$(PATH="$t/bin:$PATH" MARQUEE_BACKEND=codex MARQUEE_PRIMARY_LOG="$t/seat.md.trace.jsonl" \
      "$t/dev/marquee-babysit.sh" corp gpt-6-luna tagz 2>&1); code=$?
[[ $code -ne 0 && ! -f "$t/codex.args" ]] \
    && ok "none: refuses without an id" || fail "none: refuses without an id" "exit $code; out: $out"

# ===========================================================================
# Backend failures (game a5891297, 2026-11-13): the claude backend answered
# every resume with `Failed to authenticate. API Error: 403 ... Cloudflare`
# and exited 1 at once. Five such chunks, 33s apart, counted as five idle
# chunks and the babysitter declared STALLED after ~2.5 minutes of a transient
# outage. A call that FAILED TO RUN the model (non-zero, quick, no progress)
# must back off and retry; only a chunk where the model ran counts as idle.
#
# btree NAME: a scripted world. Files in the tree root:
#   plan      one backend step per line, consumed one per backend call:
#             fail      403 line, exit 1, instant (the a5891297 chunk)
#             tokenfail blank line, an error line carrying the token, a second
#                       line; exit 2 after 7s
#             slowfail  exit 1 after 300s, no progress (the model ran, then died)
#             idle      exit 0 after 300s, no progress
#             move      exit 0 after 300s, game log moves
#             quickidle exit 0 instantly, no progress (the model replied at once)
#             fastmove  exit 1 instantly, but the game log moved (the model ran)
#             end       exit 0 after 300s, game log moves, game over
#   clock     fake epoch seconds: `date +%s` reads it; sleep and backend steps
#             advance it, so durations and retry times are deterministic
#   sleeps    every sleep argument, one per line
#   over-after N: the Nth sleep ends the game (touches `over`)
# ===========================================================================
TOKEN="sekrit-token-value"
btree() {
    local t="$TMP/$1"
    mkdir -p "$t/dev" "$t/bin" "$t/logs"
    cp "$REAL" "$t/dev/marquee-babysit.sh"
    echo 0 > "$t/cursor"; echo 6552 > "$t/clock"; : > "$t/sleeps"; : > "$t/plan"   # 6552 = 01:49:12 UTC
    printf '%s\n' "$TOKEN" > "$t/token"
    cat > "$t/dev/send_command" <<'EOF'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")/.." && pwd)"
case "${2:-}" in
  game-over-status)
    if [[ -f "$here/over" ]]; then echo "GAME-OVER winner=corp turn=9"
    else echo "IN-PROGRESS turn=6 whose-turn=runner clicks=1"; fi ;;
  get-cursor) cat "$here/cursor" ;;
esac
EOF
    cat > "$t/bin/backend" <<EOF
#!/usr/bin/env bash
here="$t"
tick() { echo \$(( \$(cat "\$here/clock") + \$1 )) > "\$here/clock"; }
moved() { echo \$(( \$(cat "\$here/cursor") + 1 )) > "\$here/cursor"; }
step=\$(head -1 "\$here/plan"); tail -n +2 "\$here/plan" > "\$here/plan.tmp"; mv "\$here/plan.tmp" "\$here/plan"
case "\$step" in
  fail)      echo "Failed to authenticate. API Error: 403 Attention Required! | Cloudflare"; exit 1 ;;
  tokenfail) tick 7; printf '\n   API Error: 401 bearer $TOKEN rejected  \nsecond line\n'; exit 2 ;;
  slowfail)  tick 300; echo "model ran, then the CLI died"; exit 1 ;;
  idle)      tick 300; echo "waited"; exit 0 ;;
  move)      tick 300; moved; echo "played"; exit 0 ;;
  quickidle) echo "nothing to do"; exit 0 ;;
  fastmove)  moved; echo "played, then the CLI died"; exit 1 ;;
  end)       tick 300; moved; touch "\$here/over"; echo "game over"; exit 0 ;;
  *)         echo "STUB PLAN EXHAUSTED"; exit 99 ;;
esac
EOF
    for b in claude codex devin; do cp "$t/bin/backend" "$t/bin/$b"; done
    cat > "$t/bin/sleep" <<EOF
#!/usr/bin/env bash
here="$t"
echo "\$1" >> "\$here/sleeps"
echo \$(( \$(cat "\$here/clock") + \$1 )) > "\$here/clock"
if [[ -f "\$here/over-after" ]] && (( \$(wc -l < "\$here/sleeps") >= \$(cat "\$here/over-after") )); then touch "\$here/over"; fi
exit 0
EOF
    cat > "$t/bin/date" <<EOF
#!/usr/bin/env bash
if [[ "\${1:-}" == "+%s" ]]; then cat "$t/clock"; else exec /bin/date "\$@"; fi
EOF
    chmod +x "$t/dev/marquee-babysit.sh" "$t/dev/send_command" "$t/bin/"*
    echo "$t"
}
# run_bt TREE BACKEND [ENV=VAL ...] -> sets $out and $code
run_bt() {
    local t="$1" backend="$2"; shift 2
    out=$(env PATH="$t/bin:$PATH" TZ=UTC MARQUEE_BACKEND="$backend" \
          MARQUEE_CLAUDE_SESSION=11111111-2222-3333-4444-555555555555 MARQUEE_CLAUDE_TOKEN_FILE="$t/token" \
          MARQUEE_PRIMARY_LOG="$t/seat.stderr" MARQUEE_CHUNK_LOG="$t/run/chunks.log" "$@" \
          "$t/dev/marquee-babysit.sh" corp some-model tagb 2>&1); code=$?
}
eq() {  # name expected actual
    if [[ "$2" == "$3" ]]; then ok "$1"; else fail "$1" "$(printf 'expected:\n%s\n--- actual:\n%s' "$2" "$3")"; fi
}
F403="Failed to authenticate. API Error: 403 Attention Required! | Cloudflare"

# --- 5. a5891297 replayed: five instant 403 exits, then the model runs --------
t=$(btree replay)
printf '%s\n' fail fail fail fail fail end > "$t/plan"
run_bt "$t" claude
eq "replay: rides out the outage and the game continues" "claude backend: resuming session 11111111-2222-3333-4444-555555555555
BACKEND-FAIL attempt 1: claude exited 1 after 0s without running the model: $F403 — retry in 30s at 01:49:42 (backoff 30s of 3600s patience)
BACKEND-FAIL attempt 2: claude exited 1 after 0s without running the model: $F403 — retry in 60s at 01:50:42 (backoff 90s of 3600s patience)
BACKEND-FAIL attempt 3: claude exited 1 after 0s without running the model: $F403 — retry in 120s at 01:52:42 (backoff 210s of 3600s patience)
BACKEND-FAIL attempt 4: claude exited 1 after 0s without running the model: $F403 — retry in 240s at 01:56:42 (backoff 450s of 3600s patience)
BACKEND-FAIL attempt 5: claude exited 1 after 0s without running the model: $F403 — retry in 480s at 02:04:42 (backoff 930s of 3600s patience)
TERMINAL: GAME-OVER winner=corp turn=9 (after 6 chunks)" "$out"
eq "replay: exit 0" 0 "$code"
eq "replay: slept the backoff, then the usual 30s" 960 "$(awk '{s+=$1} END {print s}' "$t/sleeps")"

# --- 6. patience exhausted: a distinct message and exit code -----------------
t=$(btree exhausted)
printf '%s\n' fail fail fail fail fail fail fail fail > "$t/plan"
run_bt "$t" devin MARQUEE_BACKEND_PATIENCE_SECS=1600
eq "exhausted: doubles to the 600s cap, clips the last delay, then BACKEND-UNAVAILABLE" "BACKEND-FAIL attempt 1: devin exited 1 after 0s without running the model: $F403 — retry in 30s at 01:49:42 (backoff 30s of 1600s patience)
BACKEND-FAIL attempt 2: devin exited 1 after 0s without running the model: $F403 — retry in 60s at 01:50:42 (backoff 90s of 1600s patience)
BACKEND-FAIL attempt 3: devin exited 1 after 0s without running the model: $F403 — retry in 120s at 01:52:42 (backoff 210s of 1600s patience)
BACKEND-FAIL attempt 4: devin exited 1 after 0s without running the model: $F403 — retry in 240s at 01:56:42 (backoff 450s of 1600s patience)
BACKEND-FAIL attempt 5: devin exited 1 after 0s without running the model: $F403 — retry in 480s at 02:04:42 (backoff 930s of 1600s patience)
BACKEND-FAIL attempt 6: devin exited 1 after 0s without running the model: $F403 — retry in 600s at 02:14:42 (backoff 1530s of 1600s patience)
BACKEND-FAIL attempt 7: devin exited 1 after 0s without running the model: $F403 — retry in 70s at 02:15:52 (backoff 1600s of 1600s patience)
BACKEND-UNAVAILABLE: devin backend unavailable — 8 consecutive calls failed without running the model, after 1600s of backoff (MARQUEE_BACKEND_PATIENCE_SECS=1600); last: exit 1 after 0s: $F403" "$out"
eq "exhausted: exit 75 (EX_TEMPFAIL), not STALLED's 1" 75 "$code"
eq "exhausted: no ninth backend call" "" "$(cat "$t/plan")"

# --- 7. a slow chunk with no progress is idle, whatever its exit code ---------
t=$(btree slow)
printf '%s\n' slowfail idle slowfail idle slowfail > "$t/plan"
run_bt "$t" devin
eq "slow: five ran-but-idle chunks still STALL" "STALLED: 5 consecutive chunks with no game-log progress (last status: IN-PROGRESS turn=6 whose-turn=runner clicks=1)" "$out"
eq "slow: exit 1" 1 "$code"
eq "slow: no backoff, only the usual 30s between chunks" "30
30
30
30" "$(cat "$t/sleeps")"

# --- 7a. a quick ZERO exit with no progress also ran: idle, not a backoff -----
t=$(btree quickidle)
printf '%s\n' quickidle quickidle quickidle quickidle quickidle > "$t/plan"
run_bt "$t" devin
eq "quickidle: five quick clean exits STALL" "STALLED: 5 consecutive chunks with no game-log progress (last status: IN-PROGRESS turn=6 whose-turn=runner clicks=1)" "$out"

# --- 7b. a quick non-zero exit that moved the game log DID run the model ------
t=$(btree fastmove)
printf '%s\n' fastmove end > "$t/plan"
run_bt "$t" devin
eq "fastmove: progress means it ran: no backoff" "TERMINAL: GAME-OVER winner=corp turn=9 (after 2 chunks)" "$out"
eq "fastmove: only the usual 30s after each chunk" "30
30" "$(cat "$t/sleeps")"

# --- 8. the backoff resets after a chunk that ran (codex) ---------------------
t=$(btree reset)
printf 'session id: 99999999-8888-7777-6666-555555555555\n' > "$t/seat.stderr"
printf '%s\n' fail fail move fail end > "$t/plan"
run_bt "$t" codex
eq "reset: attempt count and delay start over after a productive chunk" "codex backend: resuming session 99999999-8888-7777-6666-555555555555
BACKEND-FAIL attempt 1: codex exited 1 after 0s without running the model: $F403 — retry in 30s at 01:49:42 (backoff 30s of 3600s patience)
BACKEND-FAIL attempt 2: codex exited 1 after 0s without running the model: $F403 — retry in 60s at 01:50:42 (backoff 90s of 3600s patience)
BACKEND-FAIL attempt 1: codex exited 1 after 0s without running the model: $F403 — retry in 30s at 01:56:42 (backoff 30s of 3600s patience)
TERMINAL: GAME-OVER winner=corp turn=9 (after 5 chunks)" "$out"

# --- 9. the game ending during a backoff sleep ends the babysitter promptly ---
t=$(btree over)
printf '%s\n' fail > "$t/plan"
echo 1 > "$t/over-after"
run_bt "$t" claude
eq "over: TERMINAL at the first poll inside the backoff" "claude backend: resuming session 11111111-2222-3333-4444-555555555555
BACKEND-FAIL attempt 1: claude exited 1 after 0s without running the model: $F403 — retry in 30s at 01:49:42 (backoff 30s of 3600s patience)
TERMINAL: GAME-OVER winner=corp turn=9 (after 1 chunks)" "$out"
eq "over: exit 0" 0 "$code"
eq "over: slept one 15s poll slice, not the whole 30s" 15 "$(cat "$t/sleeps")"

# --- 10. the log line: first non-blank line, real duration and exit, no token -
t=$(btree logline)
printf '%s\n' tokenfail end > "$t/plan"
run_bt "$t" claude
eq "logline: whole output" "claude backend: resuming session 11111111-2222-3333-4444-555555555555
BACKEND-FAIL attempt 1: claude exited 2 after 7s without running the model: API Error: 401 bearer [REDACTED] rejected — retry in 30s at 01:49:49 (backoff 30s of 3600s patience)
TERMINAL: GAME-OVER winner=corp turn=9 (after 2 chunks)" "$out"

if [[ $fails -gt 0 ]]; then echo "❌ marquee_babysit_test: $fails failure(s)"; exit 1; fi
echo "✅ marquee_babysit_test: all passed"
