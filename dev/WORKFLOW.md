# AI Development Workflow Scripts

Quick reference for AI player development and testing workflows.

## Quick Start

```bash
# Fresh game from scratch
./dev/reset.sh

# Changed code? Resume existing game
./dev/resume.sh

# Clean up old logs
./dev/clean-logs.sh
```

## Scripts Overview

### `reset.sh` - Fresh Game Reset
**Use when:** Starting a fresh test game

```bash
./dev/reset.sh           # Quiet mode (logs to file)
./dev/reset.sh -v        # Verbose mode (show all output)
./dev/reset.sh --help    # Show help
```

**What it does:**
1. Bounces both AI clients (full REPL reload)
2. Creates new self-play game
3. Both players keep hands
4. Starts Corp's turn
5. Ready to play!

**Output:** `logs/reset-YYYYMMDD-HHMMSS.log`

**When to use:**
- Starting new development session
- Testing from initial game state
- Something went wrong, need clean slate

---

### `resume.sh` - Resume Existing Game
**Use when:** Changed AI code, need to reload REPLs without losing game progress

```bash
./dev/resume.sh                                        # Auto-detect game ID
./dev/resume.sh 6e849fda-c813-409b-8c8c-0896ceca4663  # Explicit game ID
./dev/resume.sh -v                                     # Verbose mode
./dev/resume.sh --help                                 # Show help
```

**What it does:**
1. Detects game ID from HUD (`CLAUDE.local.md`)
2. Bounces both AI clients (full REPL reload)
3. Reconnects both to existing game
4. Verifies connection
5. Shows current game state

**Output:** `logs/resume-YYYYMMDD-HHMMSS.log`

**When to use:**
- You changed AI action code
- You changed strategy code
- Partial reload didn't work (they never do)
- Turn 8 with 879 virus counters, don't want to reset!

**Auto-detection sources (in order):**
1. `CLAUDE.local.md` HUD file
2. Runner client state (if running)
3. Corp client state (if running)

---

### `clean-logs.sh` - Log Management
**Use when:** Too many log files piling up

```bash
./dev/clean-logs.sh      # Keep last 20 logs
./dev/clean-logs.sh 10   # Keep last 10 logs
```

**What it does:**
- Deletes old `reset-*.log` and `resume-*.log` files
- Keeps most recent N logs (default: 20)

---

## Common Workflows

### Standard Development Flow

```bash
# 1. Start fresh game
./dev/reset.sh

# 2. Test some actions
./dev/send_command corp install "Hedge Fund" "HQ"
./dev/send_command corp advance "Priority Requisition"

# 3. Change AI code in editor...

# 4. Resume game (reload code, keep game state)
./dev/resume.sh

# 5. Continue testing with new code
./dev/send_command corp advance "Priority Requisition"
./dev/send_command corp score "Priority Requisition"

# 6. Repeat steps 3-5 as needed
```

### Debugging Flow

```bash
# Something broke, need verbose output
./dev/reset.sh -v           # See everything

# Or for resume
./dev/resume.sh -v          # See everything

# Check the logs
tail -f logs/reset-*.log    # Watch most recent
cat logs/reset-*.log        # Read full log
```

### Multi-Session Development

```bash
# End of day - note game ID
cat CLAUDE.local.md         # Shows: GameID: 6e849fda-...

# Next morning - resume where you left off
./dev/resume.sh 6e849fda-c813-409b-8c8c-0896ceca4663

# Or just let it auto-detect (if HUD still has it)
./dev/resume.sh
```

---

## Low-Level Component Scripts

These are called by `reset.sh` and `resume.sh`, but can be used directly:

### `ai-bounce.sh [game-id]`
Stops and restarts both AI clients, optionally reconnecting to game.

```bash
./dev/ai-bounce.sh                                     # Just restart
./dev/ai-bounce.sh 6e849fda-c813-409b-8c8c-0896ceca4663  # Restart + resync
```

### `ai-self-play.sh`
Creates new game and has both AI clients join.

```bash
./dev/ai-self-play.sh
```

### `start-ai-both.sh` / `stop-ai-both.sh`
Manual control of AI client REPLs.

```bash
./dev/start-ai-both.sh   # Start runner (7889) + corp (7890)
./dev/stop-ai-both.sh    # Stop both
```

---

## Direct REPL Access

Sometimes you need raw REPL access:

```bash
# Evaluate Clojure directly
./dev/ai-eval.sh runner 7889 '(ai-actions/status)'
./dev/ai-eval.sh corp 7890 '(ai-actions/board)'

# Send game commands
./dev/send_command runner status
./dev/send_command corp board
./dev/send_command corp install "Hedge Fund" "HQ"
```

---

## Tips & Tricks

### Check if clients are running
```bash
lsof -i :7889   # Runner REPL
lsof -i :7890   # Corp REPL
```

### Get game ID quickly
```bash
grep GameID CLAUDE.local.md
```

### Monitor game in real-time
```bash
# In separate terminal, watch HUD
watch -n 1 cat CLAUDE.local.md

# Or tail logs
tail -f logs/reset-*.log
```

### Recover from stuck state
```bash
# If resume fails, go nuclear
./dev/stop-ai-both.sh
killall -9 java lein  # If stop didn't work
./dev/reset.sh -v     # Fresh start with verbose output
```

### Save interesting game states
```bash
# Checkpoint a game ID for later
echo "6e849fda-c813-409b-8c8c-0896ceca4663" > game-checkpoint.txt

# Resume it later
./dev/resume.sh $(cat game-checkpoint.txt)
```

---

## Exit Codes

All scripts follow standard exit code conventions:
- `0` = Success
- `1` = General error (check log file)
- Non-zero = Failure (check log file for details)

---

## Log Files

Location: `./logs/`

- `reset-YYYYMMDD-HHMMSS.log` - Reset operations
- `resume-YYYYMMDD-HHMMSS.log` - Resume operations

Clean up with: `./dev/clean-logs.sh`

---

## Troubleshooting

### "Could not auto-detect game ID"
- Check `cat CLAUDE.local.md` for game ID
- Provide explicit game ID: `./dev/resume.sh [game-id]`
- Or start fresh: `./dev/reset.sh`

### "Timeout waiting for REPLs"
- REPLs might be hung
- Try: `./dev/stop-ai-both.sh && ./dev/start-ai-both.sh`
- Or nuclear: `killall -9 java lein`

### "Resume failed - clients not reconnected"
- Check log file for details
- Game might not exist on server
- Try fresh reset: `./dev/reset.sh`

### Partial reloads aren't working
- Don't use partial reloads, they're fragile
- Always use `./dev/resume.sh` for code changes
- It does full REPL bounce + reconnect

---

## See Also

- `./dev/send_command --help` - All game commands
- `./dev/*playbook.md` - Game mechanics reference for players
- `./dev/ITERATIVE_TESTING_PLAN.md` - Testing strategy
