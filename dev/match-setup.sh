#!/bin/bash
# Rung-1 match harness: isolated MODEL Runner vs heuristic patient CORP defender.
#
# This is the first rung of the separate-context model-vs-model harness (the
# eventual full version is sketched in dev/docs/MATCH_ORCHESTRATOR_DESIGN.md).
# It gives TRUE agent-level isolation for the model with only ONE new variable
# vs the prior single-context shakedown: the Runner seat is driven by a SEPARATE
# interactive Claude session that only ever touches runner commands + its own
# REPL (port 7889), so it can never leak Corp fog-of-war. The Corp seat is the
# already-working heuristic `bot-loop --patient` live defender.
#
# Usage: ./dev/match-setup.sh [patient-minutes]   (default 10)
#
# After this script reports "Runner seat ready":
#   1) Open a NEW terminal, run `claude`, and paste the contents of
#      dev/seats/seat-runner.md as your first message.
#   2) (optional) In a THIRD terminal: ./dev/umpire.sh   # calls the game + archives logs
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_DIR"

PATIENT_MIN="${1:-10}"
if [[ ! "$PATIENT_MIN" =~ ^[0-9]+$ ]]; then
  echo "❌ patient-minutes must be an integer (got '$PATIENT_MIN')"; exit 1
fi

echo "🎬 Rung-1 match setup: isolated model Runner vs heuristic patient Corp"
echo ""
echo "♻️  Fresh game (bounce REPLs, new game)…"
make reset >/dev/null 2>&1 || true

# Confirm a game actually exists. reset.sh does NOT restart the game server
# (port 1042 nREPL 7888); if it was down, reset can't make a game.
# A fresh reset sits at turn 0, which game-over-status reports as
# AWAITING-START (next-player=corp) — that still means a game EXISTS, so it must
# pass this check alongside GAME-OVER/IN-PROGRESS. Only NO-GAME/empty is failure.
st=$(./dev/send_command corp game-over-status 2>/dev/null \
       | grep -E '^(GAME-OVER|AWAITING-START|IN-PROGRESS|NO-GAME)' | head -1 || true)
if [[ -z "$st" || "$st" == NO-GAME* ]]; then
  echo "❌ No active game after reset (game-over-status: ${st:-<none>})."
  echo "   The game server (1042 web / 7888 nREPL) is NOT restarted by reset.sh."
  echo "   If 1042 is refused, start it then retry:"
  echo "     sleep 100000 | ./dev/repl-start.sh   # wait for 'Web server started successfully on port 1042'"
  exit 1
fi
echo "✅ Game created."

# Corp keeps FIRST. Mulligan-order gotcha: a runner keep-hand BEFORE corp keeps
# silently no-ops. Corp (this seat) decides here; the Runner/model decides in its
# own session, so order is correct.
echo "🛡️  Corp seat = heuristic defender. Keeping hand + launching patient loop…"
./dev/send_command corp keep-hand >/dev/null 2>&1
./dev/send_command corp bot-loop --patient "$PATIENT_MIN" >/dev/null 2>&1

# Sanity: confirm the loop is actually alive (not refused over a stale future).
loop_st=$(./dev/send_command corp bot-loop-status 2>/dev/null | tr -d '\n')
echo "✅ Corp patient defender looping (${PATIENT_MIN} min wall-clock bail)."
echo "   bot-loop-status: ${loop_st}"
echo ""
echo "🏃 Runner seat = LEFT FRESH (at mulligan) for the model session."
echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo " Runner seat ready. Next:"
echo "   1) New terminal → run 'claude' → paste dev/seats/seat-runner.md as message 1"
echo "   2) (optional) Third terminal → ./dev/umpire.sh   # polls + archives the result"
echo "═══════════════════════════════════════════════════════════════════════"
