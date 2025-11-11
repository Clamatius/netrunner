#!/bin/bash
# Stop both AI Client REPLs (Runner and Corp)

echo "🛑 Stopping both AI Client REPLs..."
echo ""

# Stop Runner
echo "Stopping Runner client..."
./dev/stop-ai-client.sh runner 7889

# Stop Corp
echo ""
echo "Stopping Corp client..."
./dev/stop-ai-client.sh corp 7890

echo ""
echo "✅ Both clients stopped"
echo ""
