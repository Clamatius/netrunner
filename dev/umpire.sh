#!/bin/bash
# Single-game umpire for the Rung-1 match harness (dev/match-setup.sh).
#
# Polls the machine-readable signal `send_command corp game-over-status` until
# the game ends or stalls, then archives both sides' logs and prints the result.
# Same end/stall detection contract as dev/self-play-batch.sh (locked by
# ai-display-test) — do NOT screen-scrape the human banner.
#
#   GAME-OVER winner=corp turn=18      → result, archive, exit 0
#   IN-PROGRESS turn=12 whose-turn=..  → keep polling
#   NO-GAME                            → no game set up; exit 1
#
# Stall = the IN-PROGRESS line is unchanged for STALL_POLLS consecutive polls.
# With a model in the Runner seat, "no change" is normal for minutes while it
# thinks, so the stall window is deliberately generous (default ~10 min).
#
# Usage: ./dev/umpire.sh [poll-seconds] [stall-polls]
#   poll-seconds  interval between polls            (default 15)
#   stall-polls   identical polls before STALL      (default 40 ≈ 10 min)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_DIR"

POLL=${1:-15}
STALL_POLLS=${2:-40}
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
         | grep -E '^(GAME-OVER|IN-PROGRESS|NO-GAME)' | head -1 || true)

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

  if [ "$st" == "$prev" ] && [ -n "$st" ]; then
    same=$((same+1))
    printf '\r⏳ %s  [unchanged %d/%d]   ' "$st" "$same" "$STALL_POLLS"
    if [ "$same" -ge "$STALL_POLLS" ]; then
      echo ""
      echo "🧊 STALL: $st (unchanged for ${STALL_POLLS} polls ≈ $((STALL_POLLS*POLL/60)) min)"
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
