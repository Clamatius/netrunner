.PHONY: test check clean reset resume help

# Default target
help:
	@echo "Available targets:"
	@echo "  make test          - Run unit tests (fast)"
	@echo "  make check         - Run lein check (compile validation)"
	@echo "  make reset         - Fresh game (bounce REPLs, new game)"
	@echo "  make resume        - Reload code, keep game state"
	@echo "  make clean         - Kill background processes"
	@echo ""
	@echo "Test files:"
	@echo "  - ai_actions_test.clj       - AI action unit tests (12 tests)"
	@echo "  - ai_runs_test.clj          - continue-run! tests (13 tests)"
	@echo "  - ai_websocket_diff_test.clj - Diff application tests (4 tests)"

# Run unit tests
test:
	@echo "Running unit tests..."
	lein test ai-actions-test ai-runs-test ai-websocket-diff-test

# Compile check
check:
	@echo "Checking Clojure compilation..."
	lein check

# Fresh game (bounce REPLs, new game, ready to play)
reset:
	@echo "Resetting game environment..."
	./dev/reset.sh

# Reload code, keep game state
resume:
	@echo "Resuming with code reload..."
	./dev/resume.sh

# Clean up background processes
clean:
	@echo "Cleaning up background processes..."
	@pkill -f "lein repl" || true
	@echo "Done"

# Combo: check + test
verify: check test
	@echo "✅ All checks passed"
