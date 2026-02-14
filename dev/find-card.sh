#!/bin/bash
# find-card.sh - Cycle turns until Corp draws target card
# Usage: ./dev/find-card.sh "Send a Message"
#        ./dev/find-card.sh "Project Vitruvius"

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/send_command"

TARGET="${1:-}"
MAX_TURNS="${2:-20}"

if [[ -z "$TARGET" ]]; then
    echo "Usage: $0 <card-name> [max-turns]"
    echo "Example: $0 \"Send a Message\" 15"
    exit 1
fi

echo "🔍 Searching for: $TARGET (max $MAX_TURNS turns)"

check_hand() {
    "$SEND_CMD" corp eval "(some #(= \"$TARGET\" (:title %)) (ai-state/my-hand))" 2>/dev/null | grep -q "true"
}

# Check if already in hand
if check_hand; then
    echo "✅ Found $TARGET in Corp hand!"
    "$SEND_CMD" corp hand
    exit 0
fi

for turn in $(seq 1 $MAX_TURNS); do
    echo "--- Turn $turn ---"

    # Corp turn: draw if possible, then take credits, end turn
    "$SEND_CMD" corp eval '(let [clicks (ai-state/my-clicks)] (when (> clicks 0) (dotimes [_ clicks] (ai-basic-actions/take-credits)) (ai-basic-actions/end-turn!)))' >/dev/null 2>&1 || true

    # Check after corp turn
    if check_hand; then
        echo "✅ Found $TARGET in Corp hand!"
        "$SEND_CMD" corp hand
        exit 0
    fi

    # Runner turn: take credits
    "$SEND_CMD" runner start-turn >/dev/null 2>&1 || true
    "$SEND_CMD" runner eval '(let [clicks (ai-state/my-clicks)] (when (> clicks 0) (dotimes [_ clicks] (ai-basic-actions/take-credits)) (ai-basic-actions/end-turn!)))' >/dev/null 2>&1 || true

    # Corp start turn (draws a card)
    "$SEND_CMD" corp start-turn >/dev/null 2>&1 || true

    # Check after mandatory draw
    if check_hand; then
        echo "✅ Found $TARGET in Corp hand!"
        "$SEND_CMD" corp hand
        exit 0
    fi
done

echo "❌ Card not found after $MAX_TURNS turns"
echo "Current hand:"
"$SEND_CMD" corp hand
exit 1
