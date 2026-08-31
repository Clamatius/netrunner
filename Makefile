.PHONY: test test-shell test-behavioral check check-full clean reset resume status help compile-deps watch-corp watch-runner

# Default target
help:
	@echo "AI Development Commands:"
	@echo ""
	@echo "  make check         - Quick AI code compile check"
	@echo "                       (~1s with REPL+bb; cold start ~20s warm cache,"
	@echo "                        ~85s uncached — always cold in a worktree)"
	@echo "  make test          - Run unit tests (~2s)"
	@echo "  make verify        - check + test + test-shell"
	@echo ""
	@echo "  make reset         - Fresh game (bounce REPLs, new game)"
	@echo "  make resume        - Reload code, keep game state"
	@echo "  make status        - Show current game status"
	@echo ""
	@echo "  make watch-corp    - Stream corp game events (for Monitor)"
	@echo "  make watch-runner  - Stream runner game events (for Monitor)"
	@echo ""
	@echo "  make check-full    - Full lein check (slow, entire codebase)"
	@echo "  make test-behavioral - Behavioral tests (slow, ~30s per test)"
	@echo "  make compile-deps  - AOT compile stable deps (one-time, speeds cold start)"
	@echo "  make clean         - Kill background processes"

# Quick AI-only compile check
# Fast (~1s) when REPL running + Babashka installed. Cold start otherwise:
# ~20s with a warm lein/JVM cache, ~85s without. A worktree is ALWAYS cold —
# the foreign-REPL guard in check-ai.sh refuses a REPL rooted in another tree.
check:
	@./dev/check-ai.sh

# Full lein check (slow, compiles entire Jinteki codebase)
check-full:
	@echo "Running full lein check (this takes a while)..."
	lein check

# Run unit tests
test:
	@echo "Running unit tests..."
	lein test \
	  ai-ability-legality-test \
	  ai-actions-sad-path-test \
	  ai-actions-test \
	  ai-basic-actions-test \
	  ai-connection-test \
	  ai-display-test \
	  ai-forced-encounter-test \
	  ai-heuristic-corp-test \
	  ai-heuristic-runner-test \
	  ai-loop-sync-test \
	  ai-phase-window-test \
	  ai-prompts-test \
	  ai-pure-functions-test \
	  ai-run-corp-decisions-test \
	  ai-runs-test \
	  ai-stall-test \
	  ai-state-test \
	  ai-turn-boundary-test \
	  ai-turn-validation-test \
	  ai-wait-test \
	  ai-websocket-diff-test \
	  ai-websocket-error-recovery-test \
	  ai-wire-card-ref-test \
	  check-ai-sweep-test \
	  continue-run-rez-test \
	  game.ai-ability-legality-test \
	  game.ai-corp-pass-ledger-wire-test \
	  game.ai-duplicate-continue-test \
	  game.ai-end-turn-gate-test \
	  game.ai-forced-encounter-wire-test \
	  game.ai-hosted-card-ref-test \
	  game.ai-hosted-rig-wire-test \
	  game.ai-pay-all-test \
	  game.ai-phase-windows-test \
	  game.ai-upgrade-rez-timing-test \
	  game.ai-waiting-prompt-test \
	  game.ai-zero-sub-encounter-wire-test \
	  game.core.turns-test \
	  run-window-selfadvance-test \
	  send-command-inventory-test \
	  web.ai-client-auth-test \
	  web.lobby-disconnect-test \
	  web.replay-share-test

# Run shell-level tests (fast, no REPL/server needed) — e.g. send_command output filters
# #185: no list. The eight scripts used to be named one per line, which is the
# same shape as #180 — forget a line, or lose one resolving a conflict, and the
# script silently never runs while `make verify` still reports green. It is worse
# here than in the `lein test` list, because one of those lines is the on-switch
# for the registration guard itself: a botched resolve disabled every shell test
# INCLUDING the one that would have noticed.
#
# The obvious guard (grep each basename out of `make -n test-shell`) was written,
# and removed, because commenting a line out leaves the basename in the recipe
# text and the check stays green. A substring match over uninterpreted recipe
# text answers "is this name mentioned?", not "does this run?".
#
# So: glob, and there is nothing left to forget. `dev/test/*_test.sh` is what
# runs, in shell glob order (sorted, deterministic). The trade, stated out loud:
# a new matching file is picked up with no opt-in, and the recipe can no longer
# run one script in isolation — run it directly for that.
test-shell:
	@echo "Running shell tests..."
	@set -e; \
	  found=0; \
	  for t in dev/test/*_test.sh; do \
	    [ -f "$$t" ] || continue; \
	    found=$$((found + 1)); \
	    "$$t"; \
	  done; \
	  if [ "$$found" -eq 0 ]; then \
	    echo "❌ no shell tests matched dev/test/*_test.sh — the glob is broken, not the suite empty"; \
	    exit 1; \
	  fi; \
	  echo "✅ $$found shell test scripts passed."

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

# Watch game events (for Claude Code Monitor tool or standalone)
watch-corp:
	@./dev/watch_game.sh corp

watch-runner:
	@./dev/watch_game.sh runner

# Combo: check + test + test-shell (pre-commit quality gate)
verify: check test test-shell
	@echo "✅ All checks passed"

# AOT compile stable dependencies (one-time, speeds up cold start check)
compile-deps:
	@echo "Compiling stable dependencies (one-time)..."
	lein with-profile +aot-deps compile
	@echo "✅ Dependencies compiled to target/aot-classes/"
	@echo "   Future cold-start checks will be faster"
