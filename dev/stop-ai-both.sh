#!/bin/bash
# Stop both AI Client REPLs (Runner and Corp)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/load-env.sh"

echo "🛑 Stopping both AI Client REPLs..."
echo ""

# Stop Runner
echo "Stopping Runner client..."
"$SCRIPT_DIR/stop-ai-client.sh" runner $CLIENT_1_PORT

# Stop Corp
echo "Stopping Corp client..."
"$SCRIPT_DIR/stop-ai-client.sh" corp $CLIENT_2_PORT

echo ""
echo "✅ Both clients stopped"
echo ""
