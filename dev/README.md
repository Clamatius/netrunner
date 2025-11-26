# AI Player - Quick Start Guide

## Overview

The AI player supports both **double-REPL** and **triple-REPL** architectures:

### Double-REPL Mode (Single AI Client)
1. **Game Server REPL** (port 7888) - The main Jinteki server
2. **AI Client REPL** (port 7889) - Maintains WebSocket connection to server

### Triple-REPL Mode (Multi-Client / Self-Play)
1. **Game Server REPL** (port 7888) - The main Jinteki server
2. **Runner AI Client REPL** (port 7889) - Controls Runner side
3. **Corp AI Client REPL** (port 7890) - Controls Corp side

The triple-REPL setup enables AI self-play, automated testing, and controlling both sides of a game simultaneously.

---

## Setup

### Prerequisites
- Game server running on port 1042 (http://localhost:1042)
- Leiningen (for REPL management)

### Start the Game Server

```bash
# Terminal 1
lein repl
```

In the REPL:
```clojure
(go)
```

Server now running on port 7888, hosting game at http://localhost:1042

### Start the AI Client

```bash
# Terminal 2
./dev/start-ai-client-repl.sh
```

This will:
1. Start nREPL server on port 7889
2. Load AI client code
3. Connect to game server WebSocket
4. Wait for commands

**Output:**
```
Starting AI Client REPL on port 7889...
✅ nREPL server is listening on port 7889
Loading AI client initialization...
✅ AI Client REPL ready!
```

### Stop the AI Client

```bash
./dev/stop-ai-client.sh
```

---

## Triple-REPL Setup (Self-Play Mode)

### Start Both AI Clients

```bash
# Start both Runner and Corp clients at once
./dev/start-ai-both.sh
```

This will:
1. Start Runner client on port 7889 (username: AI-runner)
2. Start Corp client on port 7890 (username: AI-corp)
3. Both connect to game server WebSocket
4. Each maintains independent game state

**Output:**
```
Starting Runner client on port 7889...
✅ AI Client REPL ready for 'runner'!

Starting Corp client on port 7890...
✅ AI Client REPL ready for 'corp'!

✅ Both AI Clients ready!
```

### Run Self-Play Automation

```bash
# Automated game setup: create lobby + both join
./dev/ai-self-play.sh
```

This will:
1. Corp creates a game lobby
2. Runner joins the game
3. Game auto-starts when both players are ready
4. Provides commands for continuing play

### Stop Both AI Clients

```bash
./dev/stop-ai-both.sh
```

### Manual Triple-REPL Usage

**Start clients individually:**
```bash
./dev/start-ai-client-repl.sh runner 7889
./dev/start-ai-client-repl.sh corp 7890
```

**Send commands to specific clients:**
```bash
# Runner commands
./dev/ai-eval.sh runner 7889 '(ai-actions/status)'
./dev/ai-eval.sh runner 7889 '(ai-actions/hand)'

# Corp commands
./dev/ai-eval.sh corp 7890 '(ai-actions/status)'
./dev/ai-eval.sh corp 7890 '(ai-actions/show-board)'
```

**Stop clients individually:**
```bash
./dev/stop-ai-client.sh runner
./dev/stop-ai-client.sh corp
```

### Multi-Client Game Flow

```bash
# 1. Start both clients
./dev/start-ai-both.sh

# 2. Corp creates game
./dev/ai-eval.sh corp 7890 '(ai-actions/create-lobby! "Test Game")'

# 3. Get game ID from Corp
GAME_ID=$(./dev/ai-eval.sh corp 7890 '(str (:gameid @ai-state/client-state))' | tail -1)

# 4. Runner joins
./dev/ai-eval.sh runner 7889 "(ai-actions/connect-game! \"$GAME_ID\" \"Runner\")"

# 5. Keep hands (or mulligan)
./dev/ai-eval.sh runner 7889 '(ai-actions/keep-hand)'
./dev/ai-eval.sh corp 7890 '(ai-actions/keep-hand)'

# 6. Play the game with both clients...
```

### Shared Game Log HUD

When running multiple clients, they share a single `CLAUDE.local.md` file with sections for each client:

```markdown
# Game Log HUD

## Game Status (runner)
Game starting...
GameID: abc-123

## Game Log (runner)
Last 30 entries:
- Runner drew 5 cards
- Corp drew 5 cards

## Game Status (corp)
Game starting...
GameID: abc-123

## Game Log (corp)
Last 30 entries:
- Runner drew 5 cards
- Corp drew 5 cards
```

File locking ensures no conflicts when both clients write simultaneously.

---

## Basic Usage

### View Available Commands

```bash
./dev/send_command help
```

### Common Commands

**Check status:**
```bash
./dev/send_command status
./dev/send_command board
./dev/send_command hand
```

**Join a game:**
```bash
# List available games
./dev/send_command list-lobbies

# Join game (copy UUID from list-lobbies output)
./dev/send_command join <game-id> Runner
```

**Or create your own game:**
```bash
./dev/send_command create-game "AI Test" "Runner"
# Wait for opponent to join via web UI
./dev/send_command start-game
```

**Take a turn:**
```bash
./dev/send_command start-turn
./dev/send_command play "Sure Gamble"
./dev/send_command take-credit
./dev/send_command end-turn
```

---

## Architecture

### File Structure

```
dev/
├── send_command                    # Main command interface (legacy)
├── start-ai-client-repl.sh        # Start AI client (parameterized for multi-client)
├── stop-ai-client.sh              # Stop AI client (supports client name)
├── ai-eval.sh                     # Low-level REPL eval (supports multi-client)
│
├── start-ai-both.sh               # Start both Runner + Corp clients
├── stop-ai-both.sh                # Stop both clients
├── ai-self-play.sh                # Automated self-play setup
│
├── src/clj/
│   ├── nrepl-eval.sh              # nREPL communication script
│   ├── ai_websocket_client_v2.clj # WebSocket client (multi-client safe)
│   ├── ai_actions.clj             # High-level game actions
│   ├── ai_client_init.clj         # Initialization code (reads client name)
│   ├── card_loader.clj            # Card database loader
│   └── user.clj                   # User namespace (for main server REPL)
│
└── *playbook.md                   # Game mechanics reference for players (WIP)
```

### How It Works

**Single Client Mode:**
1. **start-ai-client-repl.sh** launches nREPL server on port 7889
2. **ai_client_init.clj** loads on startup, connecting WebSocket to game server
3. **send_command** sends Clojure expressions via **ai-eval.sh** to the AI client REPL
4. **ai_websocket_client_v2.clj** maintains WebSocket connection and game state
5. **ai_actions.clj** provides high-level action functions

**Multi-Client Mode:**
1. Each client gets unique name (e.g., "runner", "corp")
2. Clients run on different ports (7889, 7890, etc.)
3. Each has unique MongoDB user ID (`ai-player-runner`, `ai-player-corp`)
4. Separate PID/log files prevent conflicts
5. Shared HUD file uses sections with file locking
6. Commands target specific client: `./dev/ai-eval.sh <client-name> <port> '<code>'`

### State Management

Game state stored in atom: `@ai-state/client-state`

Contains:
- `:gameid` - Current game UUID
- `:side` - Your side ("Corp" or "Runner")
- `:game-state` - Full game state (updated via diffs from server)
- `:uid` - Your user ID
- `:chsk` - WebSocket channel

---

## Troubleshooting

### AI Client Won't Start

**Problem:** `start-ai-client-repl.sh` times out

**Solutions:**
1. Check port is free: `lsof -i:7889` (or 7890 for corp)
2. Kill existing process: `./dev/stop-ai-client.sh runner` (or corp)
3. Check logs: `tail -f /tmp/ai-client-runner.log` (or corp)

**Multi-Client Issues:**
- Port conflict: Each client needs unique port (7889, 7890, etc.)
- Name conflict: Each client needs unique name (runner, corp, etc.)
- Check both clients: `lsof -i:7889 && lsof -i:7890`

### Connection Fails

**Problem:** Commands fail with "not connected" errors

**Solutions:**
1. Check game server is running on port 1042
2. Manually reconnect: `./dev/send_command connect`
3. Restart AI client: `./dev/stop-ai-client.sh && ./dev/start-ai-client-repl.sh`

### Game State Desync

**Problem:** AI thinks it's in a different game state than server

**Solutions:**
1. Resync: `./dev/send_command resync <game-id>`
2. Leave and rejoin: `./dev/send_command leave-game` then `./dev/send_command join <game-id> <side>`

### Commands Not Working

**Problem:** `send_command` returns errors

**Check:**
1. Is AI client running? `lsof -i:7889` should show Java process
2. Are you in the right directory? Commands should be run from project root or `dev/`
3. Check REPL logs: `tail -f /tmp/ai-client-repl.log`
4. Try reconnecting: `./dev/send_command connect`

### Can't Join Game

**Problem:** `join` command fails

**Verify:**
1. Game ID is correct (copy exact UUID from `list-lobbies`)
2. Game hasn't already started
3. Side is available ("Corp" or "Runner")
4. Spelling is exact: `Runner` not `runner`, `Corp` not `corp`

---

## Development Tips

### View Logs

**AI Client REPL:**
```bash
tail -f /tmp/ai-client-repl.log
```

**Game Server:**
Check terminal where `lein repl` is running

### Execute Arbitrary Code

```bash
./dev/send_command eval '(println "Debug:" @ws/client-state)'
```

### Inspect State

```bash
./dev/send_command eval '(keys @ws/client-state)'
./dev/send_command eval '(ws/my-credits)'
./dev/send_command eval '(ws/my-hand-count)'
```

### Test Without Web UI

```bash
# Create game as Corp
./dev/send_command create-game "Test" "Corp"

# In another terminal, join as Runner
./dev/send_command join <game-id> Runner

# Start game
./dev/send_command start-game
```

---

## Next Steps

- **Game Commands:** See `./dev/send_command --help` for complete command reference
- **Game Mechanics:** See `*playbook.md` files for game reference
- **Debugging:** See `DEVELOPMENT.md` for WebSocket internals and debugging

---

## Quick Command Cheat Sheet

```bash
# Setup
./dev/start-ai-client-repl.sh

# Status
./dev/send_command status
./dev/send_command board
./dev/send_command hand

# Lobby
./dev/send_command list-lobbies
./dev/send_command create-game "AI Game" "Runner"
./dev/send_command join <uuid> Runner
./dev/send_command start-game

# Turn
./dev/send_command start-turn
./dev/send_command take-credit
./dev/send_command play "Sure Gamble"
./dev/send_command run "R&D"
./dev/send_command end-turn

# Cleanup
./dev/stop-ai-client.sh
```
