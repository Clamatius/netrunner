#!/bin/bash
# AI Full Reload Workflow
# Reloads server-side code and restarts AI clients
#
# Usage:
#   ./dev/ai-reload-all.sh [namespace] [game-id]
#
# Examples:
#   ./dev/ai-reload-all.sh                           # Reload common namespaces, restart clients
#   ./dev/ai-reload-all.sh game.core.turns           # Reload specific namespace, restart clients
#   ./dev/ai-reload-all.sh game.core.turns afad1410-... # Reload, restart, resync to game

set -e

NAMESPACE="${1:-}"
GAME_ID="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "🔄 AI Full Reload Workflow"
echo "=========================="
echo ""

# Step 1: Reload server-side code
echo "📚 Reloading server-side code..."
echo ""

if [ -n "$NAMESPACE" ]; then
  # Reload specific namespace
  echo "   Reloading: $NAMESPACE"
  lein repl :connect localhost:7888 <<EOF
(require '$NAMESPACE :reload)
(println "✅ Reloaded $NAMESPACE")
EOF
else
  # Reload common namespaces
  echo "   Reloading common namespaces:"
  lein repl :connect localhost:7888 <<EOF
(require 'game.core.turns :reload)
(println "   ✅ game.core.turns")
(require 'game.core.actions :reload)
(println "   ✅ game.core.actions")
(require 'web.lobby :reload)
(println "   ✅ web.lobby")
(println "✅ Common namespaces reloaded")
EOF
fi

echo ""

# Step 2: Bounce clients
echo "🔄 Bouncing AI clients..."
echo ""

if [ -n "$GAME_ID" ]; then
  "$SCRIPT_DIR/ai-bounce.sh" "$GAME_ID"
else
  "$SCRIPT_DIR/ai-bounce.sh"
fi

echo ""
echo "🎉 Full reload complete!"
echo ""
echo "Next steps:"
if [ -n "$GAME_ID" ]; then
  echo "  Check game state:"
  echo "    ./dev/send_command runner status"
  echo "    ./dev/send_command corp status"
else
  echo "  Start a new game:"
  echo "    ./dev/ai-self-play.sh"
  echo ""
  echo "  Or create lobby manually:"
  echo "    ./dev/send_command corp create-game 'Test Game'"
fi
