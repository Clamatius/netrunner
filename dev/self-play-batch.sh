#!/bin/bash
# Run N self-play games back-to-back and emit ONE line per game (result), plus
# any STALL. Stop the whole run on the first stall (a bug to investigate).
#
# Usage: ./dev/self-play-batch.sh [N]   (default 5 games)
#
# End/progress detection uses the machine-readable signal
#   ./dev/send_command corp game-over-status
# which prints exactly one of:
#   GAME-OVER winner=corp turn=18
#   GAME-OVER winner=tie turn=12
#   AWAITING-START turn=12 next-player=runner
#   IN-PROGRESS turn=12 whose-turn=runner clicks=3
#   NO-GAME
# Do NOT screen-scrape the human status banner here — that coupling broke this
# harness once when the banner format changed. The game-over-status contract is
# locked by ai-display-test.
#
# Stall = the IN-PROGRESS line (turn + whose-turn + clicks) is unchanged for 8
# consecutive 15s polls (~120s). That catches both a frozen turn and a
# within-turn spin (same clicks, not progressing).

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_DIR"

NGAMES=${1:-5}

for g in $(seq 1 "$NGAMES"); do
  make reset >/dev/null 2>&1
  ./dev/send_command corp keep-hand   >/dev/null 2>&1
  ./dev/send_command runner keep-hand >/dev/null 2>&1
  ./dev/send_command corp bot-loop    >/dev/null 2>&1
  ./dev/send_command runner bot-loop  >/dev/null 2>&1
  echo "GAME $g START"

  prev=""; same=0; ended=""
  for i in $(seq 1 120); do
    st=$(./dev/send_command corp game-over-status 2>/dev/null \
           | grep -E '^(GAME-OVER|AWAITING-START|IN-PROGRESS|NO-GAME)' | head -1)

    case "$st" in
      GAME-OVER*)
        echo "GAME $g END: $st"; ended=1; break ;;
    esac

    # Stall detection: same IN-PROGRESS line for 8 polls (~120s).
    if [ "$st" == "$prev" ] && [ -n "$st" ]; then
      same=$((same+1))
      if [ "$same" -ge 8 ]; then
        echo "GAME $g STALL: $st"
        echo "RUN ABORTED (stall = bug to investigate)"; exit 2
      fi
    else
      same=0
    fi
    prev="$st"; sleep 15
  done

  [ -z "$ended" ] && echo "GAME $g TIMEOUT (still running after ~30min)"
done

echo "ALL $NGAMES GAMES DONE"
