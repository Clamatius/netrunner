# AI Player - Quick Start Guide

## Overview

The AI player uses a **two-REPL architecture** to maintain a persistent WebSocket connection while allowing single-shot command execution:

1. **Game Server REPL** (port 7888) - The main Jinteki server
2. **AI Client REPL** (port 7889) - Maintains WebSocket connection to server

This allows sending individual commands via `send_command`, which connect to the persistent client REPL that maintains game state.

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
├── send_command                    # Main command interface
├── start-ai-client-repl.sh        # Start AI client
├── stop-ai-client.sh              # Stop AI client
├── ai-eval.sh                     # Low-level REPL eval (used by send_command)
│
├── src/clj/
│   ├── nrepl-eval.sh              # nREPL communication script
│   ├── ai_websocket_client_v2.clj # WebSocket client
│   ├── ai_actions.clj             # High-level game actions
│   ├── ai_client_init.clj         # Initialization code
│   ├── card_loader.clj            # Card database loader
│   └── user.clj                   # User namespace (for main server REPL)
│
└── GAME_REFERENCE.md              # Complete command reference
```

### How It Works

1. **start-ai-client-repl.sh** launches nREPL server on port 7889
2. **ai_client_init.clj** loads on startup, connecting WebSocket to game server
3. **send_command** sends Clojure expressions via **ai-eval.sh** to the AI client REPL
4. **ai_websocket_client_v2.clj** maintains WebSocket connection and game state
5. **ai_actions.clj** provides high-level action functions

### State Management

Game state stored in atom: `@ai-websocket-client-v2/client-state`

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
1. Check port 7889 is free: `lsof -i:7889`
2. Kill existing process: `./dev/stop-ai-client.sh`
3. Check logs: `tail -f /tmp/ai-client-repl.log`

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

- **Game Commands:** See `GAME_REFERENCE.md` for complete command reference
- **Debugging:** See `DEVELOPMENT.md` for WebSocket internals and debugging
- **Game Mechanics:** See `netrunner-complete-mechanics.md` for full game rules

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
