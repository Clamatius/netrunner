#!/bin/bash
# Load environment variables from .env file if it exists
# and set default ports for Netrunner development

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

# Load .env if present
if [ -f "$ROOT_DIR/.env" ]; then
  # Export variables from .env
  export $(grep -v '^#' "$ROOT_DIR/.env" | xargs)
fi

# Default ports
export GAME_SERVER_PORT="${GAME_SERVER_PORT:-7888}"
export CLIENT_1_PORT="${CLIENT_1_PORT:-7889}"
export CLIENT_2_PORT="${CLIENT_2_PORT:-7890}"

# Log configuration (optional, can be noisy)
# echo "Configuration:"
# echo "  GAME_SERVER_PORT: $GAME_SERVER_PORT"
# echo "  CLIENT_1_PORT:    $CLIENT_1_PORT"
# echo "  CLIENT_2_PORT:    $CLIENT_2_PORT"
