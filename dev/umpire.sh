#!/bin/bash
# Single-game umpire for the Rung-1 match harness (dev/match-setup.sh).
#
# Polls the machine-readable signal `send_command corp game-over-status` until
# the game ends or stalls, then archives both sides' logs and prints the result.
# Same end/stall detection contract as dev/self-play-batch.sh (locked by
# ai-display-test) — do NOT screen-scrape the human banner.
#
#   GAME-OVER winner=corp turn=18         → result, archive, exit 0
#   AWAITING-START turn=12 next-player=..  → clean turn boundary; keep polling
#   IN-PROGRESS turn=12 whose-turn=..      → keep polling
#   NO-GAME                               → no game set up; exit 1
#
# Stall = the status line is unchanged for N consecutive polls. With a model in
# a seat, "no change" is normal for minutes while it thinks, so the window is
# deliberately generous. A turn HANDOFF (AWAITING-START) can include both seats'
# think time, so it gets a more patient budget than a mid-turn spin — and it is
# a distinct token, so a clean boundary is no longer misread as a corp stall.
#
# Usage: ./dev/umpire.sh [poll-seconds] [stall-polls] [start-stall-polls]
#   poll-seconds      interval between polls                  (default 15)
#   stall-polls       identical mid-turn polls before STALL   (default 40 ≈ 10 min)
#   start-stall-polls identical boundary polls before STALL   (default 2×stall-polls)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_DIR"

POLL=${1:-15}
STALL_POLLS=${2:-40}
START_STALL_POLLS=${3:-$((STALL_POLLS*2))}
STAMP=$(date +%Y%m%d-%H%M%S)
ARCHIVE_DIR="$REPO_DIR/dev/match-logs"
mkdir -p "$ARCHIVE_DIR"

archive() {
  local result="$1"
  local out="$ARCHIVE_DIR/match-$STAMP.log"
  {
    echo "RESULT: $result"
    echo "ENDED:  $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo ""
    echo "===== CORP LOG ====="
    ./dev/send_command corp log 40 2>/dev/null
    echo ""
    echo "===== RUNNER LOG ====="
    ./dev/send_command runner log 40 2>/dev/null
  } > "$out"
  echo "📁 Archived → $out"
}

echo "👁️  Umpire watching (poll ${POLL}s, stall after ${STALL_POLLS} identical polls)…"

prev=""; same=0
while true; do
  st=$(./dev/send_command corp game-over-status 2>/dev/null \
         | grep -E '^(GAME-OVER|AWAITING-START|IN-PROGRESS|NO-GAME)' | head -1 || true)

  case "$st" in
    GAME-OVER*)
      echo ""
      echo "🏁 $st"
      archive "$st"
      exit 0 ;;
    NO-GAME*)
      echo "❌ NO-GAME — run ./dev/match-setup.sh first."
      exit 1 ;;
  esac

  # A turn handoff gets a more patient stall budget than a mid-turn spin.
  case "$st" in
    AWAITING-START*) limit=$START_STALL_POLLS ;;
    *)               limit=$STALL_POLLS ;;
  esac

  if [ "$st" == "$prev" ] && [ -n "$st" ]; then
    same=$((same+1))
    printf '\r⏳ %s  [unchanged %d/%d]   ' "$st" "$same" "$limit"
    if [ "$same" -ge "$limit" ]; then
      echo ""
      echo "🧊 STALL: $st (unchanged for ${limit} polls ≈ $((limit*POLL/60)) min)"
      archive "STALL $st"
      exit 2
    fi
  else
    same=0
    printf '\r▶️  %s            ' "$st"
  fi
  prev="$st"
  sleep "$POLL"
done
