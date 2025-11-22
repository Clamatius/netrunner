# CLAUDE.md

## Project: AI Player for Netrunner Card Game

Jinteki.net (Clojure/ClojureScript) - we're building AI players that connect via WebSocket to play against each other.

## Quick Start

```bash
./dev/reset.sh          # Fresh game (bounce REPLs, new game, ready to play)
./dev/resume.sh         # Changed code? Reload REPLs, keep game state
./dev/send_command corp help   # Check latest helptext
./dev/send_command corp status   # Check game state from corp pov
```

## AI Code Structure

**Core files (./dev/src/clj/):**
- `ai_card_actions.clj` - Card actions (install, play, advance, score, rez, etc.)
- `ai_basic_actions.clj` - Turn management (draw, credits, end-turn, auto-end-turn)
- `ai_runs.clj` - Runner run mechanics and auto-continue logic
- `ai_prompts.clj` - Prompt handling (select, choose, resolve)
- `ai_core.clj` - Shared helpers (card lookup, server naming, verification)
- `ai_websocket_client_v2.clj` - WebSocket client, state management

**Entry point:**
- `./dev/send_command [corp|runner] <command>` - Execute actions for either side
- Two REPL instances: Runner (7889), Corp (7890)

## Testing Workflow

**Typical cycle:**
1. Make code changes
2. `./dev/resume.sh` - Full REPL reload + reconnect (partial reloads unreliable)
3. Test with `send_command`
4. Repeat

**Games may timeout** - server purges inactive games (timing inconsistent). If resume fails with "no active games", use `reset.sh`.

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

## References

- `./dev/WORKFLOW.md` - Complete testing workflow guide
- `./dev/*playbook.md` - Game mechanics reference for players (WIP)
- `./dev/send_command --help` - All available commands
- `lein check` - Verify Clojure compilation
