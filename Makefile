.PHONY: test test-behavioral check check-full clean reset resume status help

# Default target
help:
	@echo "AI Development Commands:"
	@echo ""
	@echo "  make check         - Quick AI code compile check (~15s)"
	@echo "  make test          - Run unit tests (~2s)"
	@echo "  make verify        - check + test"
	@echo ""
	@echo "  make reset         - Fresh game (bounce REPLs, new game)"
	@echo "  make resume        - Reload code, keep game state"
	@echo "  make status        - Show current game status"
	@echo ""
	@echo "  make check-full    - Full lein check (slow, entire codebase)"
	@echo "  make test-behavioral - Behavioral tests (slow, ~30s per test)"
	@echo "  make clean         - Kill background processes"

# Quick AI-only compile check (fast, ~15s)
check:
	@./dev/check-ai.sh

# Full lein check (slow, compiles entire Jinteki codebase)
check-full:
	@echo "Running full lein check (this takes a while)..."
	lein check

# Run unit tests
test:
	@echo "Running unit tests..."
	lein test ai-actions-test ai-runs-test ai-websocket-diff-test ai-state-test ai-pure-functions-test ai-turn-validation-test

# Run behavioral tests (slow, requires game server)
test-behavioral:
	@echo "Running behavioral tests (slow, ~30s per test)..."
	lein test ai-behavioral-test

# Fresh game (bounce REPLs, new game, ready to play)
reset:
	@echo "Resetting game environment..."
	./dev/reset.sh

# Reload code, keep game state
resume:
	@echo "Resuming with code reload..."
	./dev/resume.sh

# Show game status
status:
	@./dev/send_command corp status 2>/dev/null || echo "No active game or REPL not running"

# Clean up background processes
clean:
	@echo "Cleaning up background processes..."
	@pkill -f "lein repl" || true
	@echo "Done"

# Combo: check + test (pre-commit quality gate)
verify: check test
	@echo "✅ All checks passed"
