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
set -u
SIDE="${1:?usage: marquee-babysit.sh <corp|runner> <model> [log-tag] [primary-pid]}"
MODEL="${2:?usage: marquee-babysit.sh <corp|runner> <model> [log-tag] [primary-pid]}"
TAG="${3:-$(date '+%Y%m%d-%H%M')}"
PRIMARY_PID="${4:-}"
BACKEND="${MARQUEE_BACKEND:-devin}"
PRIMARY_LOG="${MARQUEE_PRIMARY_LOG:-}"
CODEX_EFFORT="${MARQUEE_CODEX_EFFORT:-xhigh}"
cd "$(dirname "$0")/.." || exit 1
mkdir -p logs
LOG="logs/marquee-${SIDE}-${MODEL}-${TAG}-chunks.log"

# Newest `session id:` in a codex log, or empty.
codex_session_id() {
  [ -f "$1" ] || return 1
  grep -E '^session id: ' "$1" 2>/dev/null | tail -1 | awk '{print $3}'
}

if [ "$BACKEND" = codex ]; then
  [ -n "$PRIMARY_LOG" ] || { echo "codex backend needs MARQUEE_PRIMARY_LOG=<primary seat log>"; exit 1; }
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

idle=0
chunk=0
while true; do
  s=$(./dev/send_command "$SIDE" game-over-status 2>/dev/null | tail -1)
  case "$s" in
    GAME-OVER*|GAME-GONE*|NO-GAME*) echo "TERMINAL: $s (after $chunk chunks)"; exit 0;;
  esac
  before=$(./dev/send_command "$SIDE" get-cursor 2>/dev/null | tail -1)
  chunk=$((chunk+1))
  {
    echo ""
    echo "===== chunk $chunk $(date '+%H:%M:%S') status=$s idle=$idle ====="
  } >> "$LOG"
  if [ "$BACKEND" = codex ]; then
    timeout 3600 codex exec resume "$SESSION" "$NUDGE" \
      -m "$MODEL" -s danger-full-access -c model_reasoning_effort="$CODEX_EFFORT" \
      >> "$LOG" 2>&1
    # A resume may be recorded under a fresh id; chain onto whatever it used.
    newest="$(codex_session_id "$LOG")"
    [ -n "$newest" ] && SESSION="$newest"
  else
    DEVIN_PERMISSION_MODE=dangerous timeout 3600 devin -p -c --model "$MODEL" -- "$NUDGE" \
      >> "$LOG" 2>&1
  fi
  after=$(./dev/send_command "$SIDE" get-cursor 2>/dev/null | tail -1)
  if [ "$before" = "$after" ]; then idle=$((idle+1)); else idle=0; fi
  if [ "$idle" -ge 5 ]; then
    echo "STALLED: 5 consecutive chunks with no game-log progress (last status: $s)"
    exit 1
  fi
  sleep 30
done
