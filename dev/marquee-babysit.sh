#!/bin/bash
# Marquee seat babysitter for CLI-backed model seats (Terra/Sol/Luna/...).
# The seat CLI exits whenever the model ends its reply; while the game is live,
# continue the SAME conversation with an action-forcing nudge.
# Proven in marquee 6d8f4cf8 (Terra Corp: one nudge chunk then played the
# entire remaining game). Harmless when unneeded — it exits on GAME-OVER.
#
# Usage: dev/marquee-babysit.sh <corp|runner> <model> [log-tag] [primary-pid]
#   e.g. dev/marquee-babysit.sh runner gpt-5.6-sol game-a 12345
# If primary-pid is given, the loop first WAITS for that process to exit —
# `devin -p -c` continues the most recent conversation, so nudging while the
# primary seat process is still alive would double-drive one conversation.
#
# Backends (env MARQUEE_BACKEND, default devin):
#   devin  — `devin -p -c` (continues the most recent conversation; NOT safe to
#            run two devin babysitters at once).
#   codex  — `codex exec resume <session-id>`. Resume is BY ID, so two codex
#            seats can be babysat concurrently. Requires MARQUEE_PRIMARY_LOG
#            pointing at the primary seat's log, from which the session id is
#            read (`codex exec` prints `session id: <uuid>` in its header).
#            MARQUEE_CODEX_EFFORT overrides reasoning effort (default xhigh).
#            An askmodel seat runs codex with --json, so it prints no header:
#            point MARQUEE_PRIMARY_LOG at its <out>.trace.jsonl instead, whose
#            `thread.started` event carries the same id as `thread_id`.
#   claude — `claude -p --resume <session-id>`. Resume is BY ID like codex, but
#            the id is ASSIGNED by the launcher (`claude --session-id <uuid>`)
#            rather than scraped back out of a log, so there is nothing to parse
#            and nothing to get wrong. Requires MARQUEE_CLAUDE_SESSION=<uuid>
#            (the same uuid the primary seat was launched with) and an OAuth
#            token — a `claude -p` subshell does NOT inherit an interactive
#            login, so this reads ~/claude_oauth.txt (override:
#            MARQUEE_CLAUDE_TOKEN_FILE) and exports CLAUDE_CODE_OAUTH_TOKEN.
#            MARQUEE_CLAUDE_EFFORT overrides reasoning effort (default xhigh).
#
# Backend failures (game a5891297: every claude resume answered
# `Failed to authenticate. API Error: 403 ... Cloudflare` and exited 1 at once):
#   A chunk whose backend call exits NON-ZERO within FAST_FAIL_SECS (60) and
#   leaves the game log where it was never ran the model. It does not count
#   toward the "5 idle chunks = STALLED" rule; it is retried with exponential
#   backoff 30s, 60s, 120s, 240s, 480s, then 600s each, until the backoff slept
#   since the last chunk that ran reaches MARQUEE_BACKEND_PATIENCE_SECS
#   (default 3600). Then: BACKEND-UNAVAILABLE line, exit 75 (EX_TEMPFAIL).
#   Any chunk that ran (slow, zero exit, or game-log progress) resets the
#   backoff. Each failure logs a BACKEND-FAIL line: exit code, duration, first
#   output line (token redacted), next retry time. The game ending during a
#   backoff sleep is noticed within one 15s poll.
#   60s: the a5891297 failures took ~3s per chunk including both send_command
#   calls (33s apart, 30s of it the sleep); a chunk that runs the model loads
#   the conversation and makes at least one model turn plus tool calls.
#   3600s: dev/match declares a side dead only when its seat AND its
#   babysitter are both gone (side_alive), so a babysitter backing off keeps
#   the game alive; its own clock is --max-hours (default 6h). An hour is 3x
#   the launcher's 20-minute stall window and outlasts the a5891297 outage
#   (healthy again within 20 minutes). Cost: a PERMANENT fast failure (a bad
#   CLI flag) now takes the patience to give up instead of ~2.5 minutes; the
#   BACKEND-FAIL lines name its cause from the first attempt.
#
# FAIRNESS NOTE: the *_EFFORT defaults are xhigh, which is DELIBERATELY not the
# usual seat default. If a match is running both seats at a matched effort, pass
# the match's level explicitly on BOTH babysitters — otherwise a seat that gets
# nudged silently finishes the game at a different dial position than its
# opponent, and the result stops being a fair comparison.
set -u
SIDE="${1:?usage: marquee-babysit.sh <corp|runner> <model> [log-tag] [primary-pid]}"
MODEL="${2:?usage: marquee-babysit.sh <corp|runner> <model> [log-tag] [primary-pid]}"
TAG="${3:-$(date '+%Y%m%d-%H%M')}"
PRIMARY_PID="${4:-}"
BACKEND="${MARQUEE_BACKEND:-devin}"
PRIMARY_LOG="${MARQUEE_PRIMARY_LOG:-}"
CODEX_EFFORT="${MARQUEE_CODEX_EFFORT:-xhigh}"
CLAUDE_EFFORT="${MARQUEE_CLAUDE_EFFORT:-xhigh}"
CLAUDE_SESSION="${MARQUEE_CLAUDE_SESSION:-}"
CLAUDE_TOKEN_FILE="${MARQUEE_CLAUDE_TOKEN_FILE:-$HOME/claude_oauth.txt}"
PATIENCE="${MARQUEE_BACKEND_PATIENCE_SECS:-3600}"
[[ "$PATIENCE" =~ ^[0-9]+$ ]] || { echo "MARQUEE_BACKEND_PATIENCE_SECS must be a whole number of seconds, got '$PATIENCE'"; exit 1; }
FAST_FAIL_SECS=60; BACKOFF_FIRST=30; BACKOFF_CAP=600; BACKOFF_POLL=15
cd "$(dirname "$0")/.." || exit 1
mkdir -p logs
# MARQUEE_CHUNK_LOG: a launcher (dev/match) keeps the chunk log in its run dir.
LOG="${MARQUEE_CHUNK_LOG:-logs/marquee-${SIDE}-${MODEL}-${TAG}-chunks.log}"
mkdir -p "$(dirname "$LOG")"

# Newest codex session id in a log, or empty. Two spellings: the `session id:`
# header plain `codex exec` prints (and every resume chunk below), and the
# `thread.started` event `codex exec --json` emits — which is all an askmodel
# seat's <out>.trace.jsonl has.
codex_session_id() {
  [ -f "$1" ] || return 1
  local id
  id=$(grep -E '^session id: ' "$1" 2>/dev/null | tail -1 | awk '{print $3}')
  if [ -z "$id" ]; then
    id=$(grep -E '"type": *"thread.started"' "$1" 2>/dev/null | tail -1 \
         | sed -n 's/.*"thread_id": *"\([0-9A-Za-z-]*\)".*/\1/p')
  fi
  printf '%s' "$id"
}

if [ "$BACKEND" = codex ]; then
  [ -n "$PRIMARY_LOG" ] || { echo "codex backend needs MARQUEE_PRIMARY_LOG=<primary seat log>"; exit 1; }
fi

if [ "$BACKEND" = claude ]; then
  # Fail HERE, before the primary-pid wait, not at the first nudge: a babysitter
  # that dies only once it is needed is a babysitter that was never armed. It
  # would sit for an hour looking healthy and then evaporate at the one moment
  # the game depended on it.
  [ -n "$CLAUDE_SESSION" ] || { echo "claude backend needs MARQUEE_CLAUDE_SESSION=<uuid the primary seat was launched with>"; exit 1; }
  [ -r "$CLAUDE_TOKEN_FILE" ] || { echo "claude backend needs a readable token file (MARQUEE_CLAUDE_TOKEN_FILE, default ~/claude_oauth.txt): $CLAUDE_TOKEN_FILE"; exit 1; }
  CLAUDE_CODE_OAUTH_TOKEN="$(cat "$CLAUDE_TOKEN_FILE")"
  [ -n "$CLAUDE_CODE_OAUTH_TOKEN" ] || { echo "token file $CLAUDE_TOKEN_FILE is empty"; exit 1; }
  export CLAUDE_CODE_OAUTH_TOKEN
  echo "claude backend: resuming session $CLAUDE_SESSION"
fi

if [ -n "$PRIMARY_PID" ]; then
  echo "waiting for primary seat process $PRIMARY_PID to exit before babysitting..."
  while kill -0 "$PRIMARY_PID" 2>/dev/null; do sleep 20; done
  echo "primary seat process $PRIMARY_PID exited; babysitter active"
fi

if [ "$BACKEND" = codex ]; then
  SESSION="$(codex_session_id "$PRIMARY_LOG")"
  [ -n "$SESSION" ] || { echo "no 'session id:' found in $PRIMARY_LOG — cannot resume"; exit 1; }
  echo "codex backend: resuming session $SESSION"
fi

NUDGE="The Netrunner game is STILL LIVE and you are the ${SIDE} seat. Your last reply ended without finishing the game — that strands the match. Do NOT narrate intentions and do NOT end your reply until ./dev/send_command ${SIDE} game-over-status prints GAME-OVER or GAME-GONE. EXECUTE commands now: check status, take your turn(s), use the blocking wait loop between turns (C=\$(./dev/send_command ${SIDE} get-cursor); ./dev/send_command ${SIDE} wait --since \"\$C\"), and keep looping. A wait timeout is benign — re-issue wait. Keep appending to your move-by-move rationale as you play; produce the final report only at GAME-OVER/GAME-GONE."

# Exit 0 if the game is over; otherwise leave the status line in $s.
check_terminal() {
  s=$(./dev/send_command "$SIDE" game-over-status 2>/dev/null | tail -1)
  case "$s" in
    GAME-OVER*|GAME-GONE*|NO-GAME*) echo "TERMINAL: $s (after $chunk chunks)"; exit 0;;
  esac
}
# Sleep $1 seconds in BACKOFF_POLL slices, ending the babysitter as soon as the
# game is over instead of after the whole (up to 600s) delay.
backoff_sleep() {
  local left="$1" step
  while [ "$left" -gt 0 ]; do
    step=$BACKOFF_POLL; [ "$left" -lt "$step" ] && step=$left
    sleep "$step"; left=$((left-step))
    check_terminal
  done
}
# First non-blank line of this chunk's output (bytes after offset $1 of $LOG),
# trimmed, capped at 200 chars, with the OAuth token redacted.
first_line() {
  local line
  line=$(tail -c "+$(( $1 + 1 ))" "$LOG" 2>/dev/null | tr -d '\r' | grep -m1 '[^[:space:]]')
  line="${line#"${line%%[![:space:]]*}"}"; line="${line%"${line##*[![:space:]]}"}"
  if [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then line="${line//"$CLAUDE_CODE_OAUTH_TOKEN"/[REDACTED]}"; fi
  [ -n "$line" ] || line="(no output)"
  printf '%s' "${line:0:200}"
}
clock_at() {  # epoch -> HH:MM:SS (BSD date, then GNU)
  date -r "$1" '+%H:%M:%S' 2>/dev/null || date -d "@$1" '+%H:%M:%S'
}

idle=0
chunk=0
fails=0; backoff_used=0; delay=0
while true; do
  check_terminal
  before=$(./dev/send_command "$SIDE" get-cursor 2>/dev/null | tail -1)
  chunk=$((chunk+1))
  {
    echo ""
    echo "===== chunk $chunk $(date '+%H:%M:%S') status=$s idle=$idle ====="
  } >> "$LOG"
  offset=$(wc -c < "$LOG" | tr -d ' ')
  t0=$(date +%s)
  if [ "$BACKEND" = codex ]; then
    # `exec resume` has no -s (codex 0.156 rejects it: every chunk died on
    # "unexpected argument '-s'" and the loop stalled out) — set it via config.
    timeout 3600 codex exec resume "$SESSION" "$NUDGE" \
      -m "$MODEL" -c sandbox_mode='"danger-full-access"' -c model_reasoning_effort="$CODEX_EFFORT" \
      >> "$LOG" 2>&1
    rc=$?
    # A resume may be recorded under a fresh id; chain onto whatever it used.
    newest="$(codex_session_id "$LOG")"
    [ -n "$newest" ] && SESSION="$newest"
  elif [ "$BACKEND" = claude ]; then
    # --resume keeps the SAME id, so the next chunk resumes the same conversation
    # and there is no id to re-scrape (contrast the codex branch above).
    printf '%s' "$NUDGE" | timeout 3600 claude --model "$MODEL" \
      --effort "$CLAUDE_EFFORT" --resume "$CLAUDE_SESSION" \
      --dangerously-skip-permissions -p >> "$LOG" 2>&1
    rc=$?
  else
    DEVIN_PERMISSION_MODE=dangerous timeout 3600 devin -p -c --model "$MODEL" -- "$NUDGE" \
      >> "$LOG" 2>&1
    rc=$?
  fi
  t1=$(date +%s); dur=$((t1-t0))
  after=$(./dev/send_command "$SIDE" get-cursor 2>/dev/null | tail -1)
  if [ "$rc" -ne 0 ] && [ "$dur" -lt "$FAST_FAIL_SECS" ] && [ "$before" = "$after" ]; then
    # The backend call failed to run the model: back off, do not count idle.
    fails=$((fails+1))
    why="exit $rc after ${dur}s: $(first_line "$offset")"
    left=$((PATIENCE-backoff_used))
    if [ "$left" -le 0 ]; then
      echo "BACKEND-UNAVAILABLE: $BACKEND backend unavailable — $fails consecutive calls failed without running the model, after ${backoff_used}s of backoff (MARQUEE_BACKEND_PATIENCE_SECS=$PATIENCE); last: $why"
      exit 75
    fi
    if [ "$fails" -eq 1 ]; then delay=$BACKOFF_FIRST; else delay=$((delay*2)); fi
    [ "$delay" -gt "$BACKOFF_CAP" ] && delay=$BACKOFF_CAP
    [ "$delay" -gt "$left" ] && delay=$left
    backoff_used=$((backoff_used+delay))
    echo "BACKEND-FAIL attempt $fails: $BACKEND exited $rc after ${dur}s without running the model: $(first_line "$offset") — retry in ${delay}s at $(clock_at $((t1+delay))) (backoff ${backoff_used}s of ${PATIENCE}s patience)"
    backoff_sleep "$delay"
    continue
  fi
  fails=0; backoff_used=0
  if [ "$before" = "$after" ]; then idle=$((idle+1)); else idle=0; fi
  if [ "$idle" -ge 5 ]; then
    echo "STALLED: 5 consecutive chunks with no game-log progress (last status: $s)"
    exit 1
  fi
  sleep 30
done
