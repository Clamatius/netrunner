#!/usr/bin/env bash
# Fast test runner using persistent REPL
# Starts test REPL once, then reloads code and runs tests

set -euo pipefail

# Configuration
TEST_REPL_PORT=7891
PIDFILE="/tmp/test-repl-${TEST_REPL_PORT}.pid"
LOGFILE="/tmp/test-repl-${TEST_REPL_PORT}.log"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if REPL is running
is_repl_running() {
    if [ -f "$PIDFILE" ]; then
        local pid=$(cat "$PIDFILE")
        if ps -p "$pid" > /dev/null 2>&1; then
            # Check if it's actually listening on the port
            if lsof -i ":$TEST_REPL_PORT" -t > /dev/null 2>&1; then
                return 0
            fi
        fi
        # Stale pidfile
        rm -f "$PIDFILE"
    fi
    return 1
}

# Start test REPL
start_test_repl() {
    echo -e "${YELLOW}Starting test REPL on port $TEST_REPL_PORT...${NC}"

    # Start REPL in background
    nohup lein repl :start :port "$TEST_REPL_PORT" > "$LOGFILE" 2>&1 &
    local pid=$!
    echo "$pid" > "$PIDFILE"

    echo -e "${GREEN}Test REPL starting (PID: $pid)${NC}"
    echo "   Log file: $LOGFILE"
    echo "   Waiting for REPL to be ready..."

    # Wait for REPL to be ready (max 60 seconds)
    local max_wait=60
    local waited=0
    while [ $waited -lt $max_wait ]; do
        if lsof -i ":$TEST_REPL_PORT" -t > /dev/null 2>&1; then
            echo -e "${GREEN}Test REPL ready!${NC}"
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
        if [ $((waited % 5)) -eq 0 ]; then
            echo "   Still waiting... (${waited}s)"
        fi
    done

    echo -e "${RED}ERROR: Test REPL failed to start${NC}"
    cat "$LOGFILE"
    return 1
}

# Stop test REPL
stop_test_repl() {
    if [ -f "$PIDFILE" ]; then
        local pid=$(cat "$PIDFILE")
        echo -e "${YELLOW}Stopping test REPL (PID: $pid)...${NC}"
        kill "$pid" 2>/dev/null || true
        rm -f "$PIDFILE"
        echo -e "${GREEN}Test REPL stopped${NC}"
    else
        echo "No test REPL running"
    fi
}

# Send command to REPL
send_to_repl() {
    local cmd="$1"
    local description="${2:-Running command}"

    echo -e "${YELLOW}$description...${NC}"

    # Use lein repl :connect to send command
    echo "$cmd" | lein repl :connect localhost:"$TEST_REPL_PORT" 2>&1 | \
        grep -v "nREPL" | \
        grep -v "Connecting to" | \
        grep -v "REPL-y" || true
}

# Reload code in REPL
reload_code() {
    local namespaces="${1:-ai-actions ai-actions-test ai-actions-sad-path-test test-helpers ai-websocket-client-v2}"

    echo -e "${YELLOW}Reloading code...${NC}"
    for ns in $namespaces; do
        send_to_repl "(require '[$ns] :reload)" "  Reloading $ns"
    done
}

# Run tests in REPL
run_tests() {
    local test_spec="${1:-}"

    # Load kaocha if not already loaded
    send_to_repl "(require '[kaocha.repl :as k])" "Loading test runner"

    if [ -z "$test_spec" ]; then
        # Run all tests
        echo -e "${GREEN}Running all tests...${NC}"
        send_to_repl "(k/run {:kaocha/tests [{:kaocha.testable/id :all}]})" "Running tests"
    elif [[ "$test_spec" =~ / ]]; then
        # Specific test (namespace/test-name)
        echo -e "${GREEN}Running test: $test_spec${NC}"
        send_to_repl "(k/run '$test_spec)" "Running test"
    else
        # Namespace
        echo -e "${GREEN}Running tests in: $test_spec${NC}"
        send_to_repl "(k/run '$test_spec)" "Running tests"
    fi
}

# Show usage
usage() {
    cat << EOF
Usage: $0 [COMMAND] [TEST-SPEC]

Fast test runner using persistent REPL on port $TEST_REPL_PORT

Commands:
  start             Start test REPL (if not already running)
  stop              Stop test REPL
  restart           Restart test REPL
  reload [NS...]    Reload specified namespaces (or all test namespaces)
  test [SPEC]       Run tests (reload code first)
  run [SPEC]        Run tests without reloading (faster)
  status            Show REPL status
  logs              Show REPL logs
  help              Show this help

Test Specifications:
  (none)                    Run all tests
  ai-actions-test           Run all tests in namespace
  ai-actions-sad-path-test  Run all sad path tests
  ai-actions-test/test-show-hand  Run specific test

Examples:
  $0                        # Run all tests (with reload)
  $0 test                   # Run all tests (with reload)
  $0 test ai-actions-test   # Run specific namespace
  $0 run                    # Run all tests (no reload - faster!)
  $0 reload                 # Just reload code
  $0 restart                # Restart REPL
  $0 stop                   # Stop REPL

For fastest iteration:
  1. Start REPL once: $0 start
  2. After code changes: $0 test [spec]
  3. After minimal changes: $0 run [spec] (skips reload)

REPL persists between runs for fast testing (<1s vs 30s)!
EOF
}

# Main script
main() {
    local command="${1:-test}"
    shift || true

    case "$command" in
        start)
            if is_repl_running; then
                echo -e "${GREEN}Test REPL already running${NC}"
            else
                start_test_repl
            fi
            ;;

        stop)
            stop_test_repl
            ;;

        restart)
            stop_test_repl
            sleep 1
            start_test_repl
            ;;

        reload)
            if ! is_repl_running; then
                start_test_repl
            fi
            reload_code "$*"
            ;;

        test)
            if ! is_repl_running; then
                start_test_repl
            fi
            reload_code
            run_tests "$*"
            ;;

        run)
            if ! is_repl_running; then
                start_test_repl
            fi
            run_tests "$*"
            ;;

        status)
            if is_repl_running; then
                local pid=$(cat "$PIDFILE")
                echo -e "${GREEN}Test REPL running${NC}"
                echo "   PID: $pid"
                echo "   Port: $TEST_REPL_PORT"
                echo "   Pidfile: $PIDFILE"
                echo "   Logfile: $LOGFILE"
            else
                echo -e "${YELLOW}Test REPL not running${NC}"
            fi
            ;;

        logs)
            if [ -f "$LOGFILE" ]; then
                tail -f "$LOGFILE"
            else
                echo "No log file found"
            fi
            ;;

        help|--help|-h)
            usage
            ;;

        *)
            echo -e "${RED}Unknown command: $command${NC}"
            echo
            usage
            exit 1
            ;;
    esac
}

main "$@"
