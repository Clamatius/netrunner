#!/bin/bash
# Reset AI Test Game
# One-stop script to bounce clients, start fresh game, and get to Corp's first turn
#
# Usage:
#   ./dev/reset.sh           # Quiet mode (logs to file)
#   ./dev/reset.sh -v        # Verbose mode (show all output)
#   ./dev/reset.sh --help    # Show help

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$SCRIPT_DIR/../logs"
LOG_FILE="$LOG_DIR/reset-$(date +%Y%m%d-%H%M%S).log"
VERBOSE=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            cat << EOF
🔄 AI Test Game Reset

Bounces both AI clients, creates fresh game, keeps hands, starts Corp turn.

Usage:
  ./dev/reset.sh           # Quiet mode (logs to file)
  ./dev/reset.sh -v        # Verbose mode (show all output)

On success:
  - Returns exit code 0
  - Game ready for Corp's first action
  - Displays game ID and status

On failure:
  - Returns non-zero exit code
  - Check log file for details

Log files: ./logs/reset-YYYYMMDD-HHMMSS.log
EOF
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage"
            exit 1
            ;;
    esac
done

# Create log directory
mkdir -p "$LOG_DIR"

# Helper function to run commands
run_step() {
    local step_name="$1"
    local step_num="$2"
    shift 2
    local cmd="$@"

    echo "[$step_num/5] $step_name..." | tee -a "$LOG_FILE"

    if [ "$VERBOSE" = true ]; then
        # Show output in real-time
        if eval "$cmd" 2>&1 | tee -a "$LOG_FILE"; then
            echo "✅ $step_name complete" | tee -a "$LOG_FILE"
            echo "" | tee -a "$LOG_FILE"
            return 0
        else
            echo "❌ $step_name FAILED" | tee -a "$LOG_FILE"
            echo "   Check log: $LOG_FILE" | tee -a "$LOG_FILE"
            return 1
        fi
    else
        # Quiet mode - log only
        if eval "$cmd" >> "$LOG_FILE" 2>&1; then
            echo "✅ $step_name complete"
            return 0
        else
            echo "❌ $step_name FAILED"
            echo "   Check log: $LOG_FILE"
            echo ""
            echo "Last 20 lines of log:"
            tail -20 "$LOG_FILE"
            return 1
        fi
    fi
}

# Start the reset process
echo "🔄 Resetting AI Test Game"
echo "📝 Logging to: $LOG_FILE"
echo ""
echo "=== AI Test Game Reset ===" >> "$LOG_FILE"
echo "Started: $(date)" >> "$LOG_FILE"
echo "" >> "$LOG_FILE"

# Step 1: Bounce clients
run_step "Bouncing AI clients" 1 "$SCRIPT_DIR/ai-bounce.sh"

# Step 2: Create and join game
run_step "Creating self-play game" 2 "$SCRIPT_DIR/ai-self-play.sh"

# Step 3: Corp keeps hand
run_step "Corp keeping hand" 3 "$SCRIPT_DIR/send_command corp keep-hand"

# Step 4: Runner keeps hand
run_step "Runner keeping hand" 4 "$SCRIPT_DIR/send_command runner keep-hand"

# Step 5: Start Corp's turn
run_step "Starting Corp turn" 5 "$SCRIPT_DIR/send_command corp start-turn"

# Verify game state
echo ""
echo "🎮 Verifying game state..."
GAME_ID=$("$SCRIPT_DIR/send_command" corp eval '(str (:gameid @ai-state/client-state))' 2>/dev/null | tail -1 | tr -d '"' | tr -d '\n')

if [ -z "$GAME_ID" ] || [ "$GAME_ID" = "nil" ]; then
    echo "⚠️  Warning: Could not verify game ID"
else
    echo "✅ Game ID: $GAME_ID"
fi

# Show final status
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ Reset Complete - Game Ready!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "Corp's Turn - Ready for first action"
echo ""
echo "Quick commands:"
echo "  Status:  ./dev/send_command corp status"
echo "  Board:   ./dev/send_command corp board"
echo "  Hand:    ./dev/send_command corp hand"
echo ""
echo "Log file: $LOG_FILE"
echo ""

# Log completion
echo "" >> "$LOG_FILE"
echo "Completed: $(date)" >> "$LOG_FILE"
echo "Status: SUCCESS" >> "$LOG_FILE"

exit 0
