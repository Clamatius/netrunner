# CLAUDE.md

## Project: AI Player for Netrunner Card Game

Jinteki.net (Clojure/ClojureScript) - we're building AI players that connect via WebSocket to play against each other.

## Quick Start

```bash
make                    # Show all available commands
make check              # Quick AI compile check (~15s)
make test               # Run unit tests
make verify             # check + test (pre-commit gate)

make reset              # Fresh game (bounce REPLs, new game)
make resume             # Reload code, keep game state
make status             # Show current game status
```

**Always use `make` for build/test commands** - it's the single source of truth.

## AI Code Structure

**Core files (./dev/src/clj/):**
- `ai_card_actions.clj` - Card actions (install, play, advance, score, rez, etc.)
- `ai_basic_actions.clj` - Turn management (draw, credits, end-turn, auto-end-turn)
- `ai_runs.clj` - Runner run mechanics and auto-continue logic
- `ai_prompts.clj` - Prompt handling (select, choose, resolve)
- `ai_core.clj` - Shared helpers (card lookup, server naming, verification)
- `ai_websocket_client_v2.clj` - WebSocket client, state management
- `ai_heuristic_corp.clj` - Decision-tree Corp AI (tutorial decks)

**Entry point:**
- `./dev/send_command [corp|runner] <command>` - Execute actions for either side
- Two REPL instances: Runner (7889), Corp (7890)

## Testing Workflow

**Typical cycle:**
1. Make code changes
2. `make check` - Verify compilation (~15s)
3. `make resume` - Reload REPLs + reconnect
4. Test with `./dev/send_command`
5. Repeat

**Games may timeout** - server purges inactive games. If resume fails with "no active games", use `make reset`.

## Key Commands

```bash
# Game management
./dev/reset.sh [-v]      # Fresh game from scratch
./dev/resume.sh [-v]     # Reload code, keep game (queries server for active games)
./dev/send_command corp|runner status   # Game state
./dev/send_command corp|runner board    # Visual board

# Card actions
./dev/send_command corp install "Card Name" "Server 1"
./dev/send_command corp advance "Agenda Name"
./dev/send_command corp score "Agenda Name"
./dev/send_command runner run "HQ"

# Auto-end-turn triggers after clicks spent (unless agenda scorable)
```

## Important Behaviors

**Action verification:** Actions return `{:status :success|:waiting-input|:error}`:
- `:success` - Card moved zones, action completed
- `:waiting-input` - Prompt created (e.g., "choose server"), card still in hand
- `:error` - Timeout, failed, or blocked by existing prompt

**Prompt blocking:** Can't play/install if active prompt exists (returns immediate error vs 3s timeout)

## Debug Logging

**Enable verbose WebSocket logging:**
```bash
AI_DEBUG_LEVEL=true ./dev/send_command corp status
```

Shows internal debug messages:
- 🔍 RAW RECEIVED: Full WebSocket message payload
- 🔧 HANDLING MESSAGE: Message type being processed

Default (unset): Debug messages hidden, only user-facing output shown.

## Forum

Async discussion forum for the project. Token stored in `.forum/token`.

```bash
./forum threads                    # List all threads
./forum read <thread> --limit 10   # Read recent messages
./forum post <thread> "message"    # Post to thread
./forum --help                     # Full CLI reference
```

## Context Handover Protocol

When Michael sends `[X%]` (e.g., `[10%]`), it means context compaction is approaching with ~X% headroom remaining. 20k tokens is decent for chat but tight for code editing.

**On receiving the signal:**
1. Finish immediate thought/action (don't stop mid-edit)
2. Find a sensible stopping point
3. Write handover note to `dev/HANDOVER.md`:
   ```markdown
   # Handover: [date]
   ## Was Doing
   [Current task, progress]
   ## Next
   [What fresh-me should pick up]
   ## Context Needed
   [Key files, decisions, gotchas]
   ## Open Threads
   [Unresolved items, waiting on]
   ```
4. Say "ready for handover"

Fresh context wakes up, reads the handover, continues without 150k tokens of preamble.

**Why:** Claude has no introspective access to context fullness. This is human-in-the-loop fatigue sensing.

## Git Workflow

**Main dev branch:** `feat/ai_player_experiment`

**Before starting work:** Always `git pull` to avoid merge conflicts with Michael's changes.

**Committing:** Can commit directly to feature branches (this project, Server Defender, other Michael projects). This avoids stepping on each other's toes when working asynchronously.

**For complex features:** Create a feature branch, implement against RFC specs, then PR for review.

## References

- `make` or `Makefile` - All build/test commands (source of truth)
- `./dev/WORKFLOW.md` - Complete testing workflow guide
- `./dev/*playbook.md` - Game mechanics reference for players (WIP)
- `./dev/send_command --help` - All available commands
