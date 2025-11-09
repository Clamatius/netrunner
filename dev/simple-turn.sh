#!/bin/bash
# simple-turn.sh - Auto-pilot a simple turn
# Takes all available credits and ends turn
# Useful for testing or when you just want to bank resources

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_COMMAND="$SCRIPT_DIR/send_command"

# Color output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

success() {
    echo -e "${GREEN}✅ $1${NC}"
}

warn() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

info "Simple Turn: Taking all credits and ending turn"
echo

# Get current clicks
CLICKS=$("$SEND_COMMAND" clicks 2>&1 | grep -oE '[0-9]+' || echo "0")

if [[ "$CLICKS" == "0" ]]; then
    warn "No clicks available. Is it your turn?"
    exit 1
fi

info "Available clicks: $CLICKS"
echo

# Take credits for each click
for ((i=1; i<=CLICKS; i++)); do
    echo "Click $i/$CLICKS:"
    "$SEND_COMMAND" take-credit
    echo
done

# End turn
info "All clicks used. Ending turn..."
"$SEND_COMMAND" end-turn

echo
success "Simple turn complete!"
