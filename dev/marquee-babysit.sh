#!/bin/bash
# Marquee seat babysitter for devin-backed model seats (Terra/Sol/...).
# devin -p exits whenever the model ends its reply; while the game is live,
# continue the same conversation (-c) with an action-forcing nudge.
# Proven in marquee 6d8f4cf8 (Terra Corp: one nudge chunk then played the
# entire remaining game). Harmless when unneeded — it exits on GAME-OVER.
#
# Usage: dev/marquee-babysit.sh <corp|runner> <devin-model> [log-tag]
#   e.g. dev/marquee-babysit.sh runner gpt-5.6-sol game-a
set -u
SIDE="${1:?usage: marquee-babysit.sh <corp|runner> <devin-model> [log-tag]}"
MODEL="${2:?usage: marquee-babysit.sh <corp|runner> <devin-model> [log-tag]}"
TAG="${3:-$(date '+%Y%m%d-%H%M')}"
cd "$(dirname "$0")/.." || exit 1
mkdir -p logs
LOG="logs/marquee-${SIDE}-${MODEL}-${TAG}-chunks.log"

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
  DEVIN_PERMISSION_MODE=dangerous timeout 3600 devin -p -c --model "$MODEL" -- "$NUDGE" \
    >> "$LOG" 2>&1
  after=$(./dev/send_command "$SIDE" get-cursor 2>/dev/null | tail -1)
  if [ "$before" = "$after" ]; then idle=$((idle+1)); else idle=0; fi
  if [ "$idle" -ge 5 ]; then
    echo "STALLED: 5 consecutive chunks with no game-log progress (last status: $s)"
    exit 1
  fi
  sleep 30
done
