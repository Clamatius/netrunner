# AI Player - Two-REPL Architecture

## Overview

The AI player uses a **two-REPL architecture** to maintain a persistent WebSocket connection while allowing single-shot command execution:

1. **Game Server REPL** (port 7888) - The main Jinteki server
2. **AI Client REPL** (port 7889) - Maintains WebSocket connection to server

This allows Claude (or other AI) to send individual commands via `ai-eval.sh`, which connect to the persistent client REPL that maintains game state.

## Quick Start

### 1. Start the Game Server

```bash
# Terminal 1
lein repl
# Then in REPL:
(go)
```

Server runs on port 7888 and hosts the game at `http://localhost:1042`

### 2. Start the AI Client

```bash
# Terminal 2
chmod +x ./dev/start-ai-client-repl.sh
./dev/start-ai-client-repl.sh
```

This starts the AI Client REPL on port 7889 and automatically:
- Loads the WebSocket client
- Connects to `ws://localhost:1042/chsk`
- Loads high-level action API
- Shows available commands

### 3. Create a Game in Web UI

1. Open `http://localhost:1042` in browser
2. Create a new game
3. Copy the game ID from the URL

### 4. Join the Game as AI

```bash
# Terminal 3
chmod +x ./dev/ai-eval.sh
./dev/ai-eval.sh '(ai-actions/connect-game! "GAME-ID-HERE" "Corp")'
```

### 5. Start the Game in Web UI

Click "Start" in the web interface (as the human player)

### 6. Play Through Mulligan

```bash
./dev/ai-eval.sh '(ai-actions/auto-keep-mulligan)'
```

### 7. Take a Turn

```bash
# Check status
./dev/ai-eval.sh '(ai-actions/status)'

# Take a simple turn (3x credits, end)
./dev/ai-eval.sh '(ai-actions/simple-corp-turn)'
```

## Architecture Diagram

```
┌─────────────────────┐
│  Game Server REPL   │  Port 7888 (lein repl)
│   (Jinteki.net)     │
└──────────┬──────────┘
           │ WebSocket (port 1042)
           │
           ▼
┌─────────────────────┐
│  AI Client REPL     │  Port 7889 (persistent connection)
│  - WebSocket client │
│  - Game state atom  │
│  - Action API       │
└──────────┬──────────┘
           │ nREPL connection
           │
           ▼
┌─────────────────────┐
│   ai-eval.sh        │  Single-shot commands
│   (Claude/AI)       │
└─────────────────────┘
```

## Available Commands

### Connection & Status
```bash
# Join a game
./dev/ai-eval.sh '(ai-actions/connect-game! "game-id" "Corp")'

# Show game status
./dev/ai-status.sh  # or
./dev/ai-eval.sh '(ai-actions/status)'

# Show current prompt
./dev/ai-eval.sh '(ai-actions/show-prompt)'

# Show hand
./dev/ai-eval.sh '(ai-actions/hand)'
```

### Mulligan
```bash
# Auto-handle mulligan (keeps hand)
./dev/ai-eval.sh '(ai-actions/auto-keep-mulligan)'

# Manually keep hand
./dev/ai-eval.sh '(ai-actions/keep-hand)'

# Manually mulligan
./dev/ai-eval.sh '(ai-actions/mulligan)'
```

### Basic Actions
```bash
# Click for credit
./dev/ai-eval.sh '(ai-actions/take-credits)'

# Draw card
./dev/ai-eval.sh '(ai-actions/draw-card)'

# End turn
./dev/ai-eval.sh '(ai-actions/end-turn)'
```

### Prompts & Choices
```bash
# Choose first option from prompt
./dev/ai-eval.sh '(ai-actions/choose 0)'

# Wait for prompt (max 10 seconds)
./dev/ai-eval.sh '(ai-actions/wait-for-prompt 10)'

# Wait for my turn
./dev/ai-eval.sh '(ai-actions/wait-for-my-turn 30)'
```

### High-Level Workflows
```bash
# Simple Corp turn: 3x credit, end
./dev/ai-eval.sh '(ai-actions/simple-corp-turn)'

# Simple Runner turn: 4x credit, end
./dev/ai-eval.sh '(ai-actions/simple-runner-turn)'
```

### Help & Debugging
```bash
# Show all commands
./dev/ai-eval.sh '(ai-actions/help)'

# Inspect raw game state
./dev/ai-eval.sh '(ai-actions/inspect-state)'

# Inspect raw prompt
./dev/ai-eval.sh '(ai-actions/inspect-prompt)'
```

## Management Scripts

```bash
# Start AI client
./dev/start-ai-client-repl.sh

# Stop AI client
./dev/stop-ai-client.sh

# Check status
./dev/ai-status.sh

# Send custom command
./dev/ai-eval.sh '<clojure-expression>'
```

## File Structure

```
dev/
├── start-ai-client-repl.sh   # Start persistent client REPL
├── stop-ai-client.sh          # Stop client REPL
├── ai-eval.sh                 # Send commands to client REPL
├── ai-status.sh               # Quick status check
└── src/clj/
    ├── ai_websocket_client_v2.clj  # WebSocket client
    └── ai_actions.clj              # High-level action API
```

## How It Works

### The Two REPLs

1. **Game Server REPL** runs the actual game engine
   - Started with `lein repl` then `(go)`
   - Hosts WebSocket server at `ws://localhost:1042/chsk`
   - Manages game state and rules

2. **AI Client REPL** connects to the game server
   - Started with `start-ai-client-repl.sh`
   - Maintains persistent WebSocket connection
   - Stores game state in `@ai-websocket-client-v2/client-state` atom
   - Provides action API via `ai-actions` namespace

### Command Flow

```
Claude → ai-eval.sh → nREPL connection → AI Client REPL → WebSocket → Game Server
                                              ↓
                                        Game State Atom
                                      (persists between commands)
```

### Why This Works

- **Persistent Connection**: Client REPL keeps WebSocket open
- **Stateful**: Game state persists in atom between commands
- **Simple Interface**: Claude just sends Clojure expressions
- **No HTTP Proxy**: REPL itself is the RPC mechanism
- **Extensible**: Easy to add new high-level actions

## Connecting to Public Server

To connect to the public Jinteki.net server instead of local:

```bash
# In start-ai-client-repl.sh, change the connect line to:
(ai-websocket-client-v2/connect! "wss://www.jinteki.net/chsk")
```

Then use the same commands as above.

## Troubleshooting

**AI Client won't start:**
```bash
# Check if port 7889 is in use
lsof -ti:7889

# Kill any existing process
./dev/stop-ai-client.sh
```

**Can't connect to game:**
- Ensure game server is running (`lein repl` → `(go)`)
- Check game ID is correct
- Verify game hasn't already started

**Commands hang or timeout:**
- AI Client might have crashed - check Terminal 2
- Restart with `./dev/stop-ai-client.sh` then `./dev/start-ai-client-repl.sh`

**Game state out of sync:**
- Client should receive automatic updates via WebSocket
- Try `./dev/ai-status.sh` to see current state
- If completely broken, restart AI Client

## Development

### Adding New Actions

Edit `dev/src/clj/ai_actions.clj`:

```clojure
(defn my-new-action
  "Description of action"
  [arg1 arg2]
  (println "Doing something...")
  (ws/send-action! "command-name" {:arg1 arg1 :arg2 arg2})
  (Thread/sleep 500)
  (println "✅ Done"))
```

Then reload in REPL:
```bash
./dev/ai-eval.sh '(load-file "dev/src/clj/ai_actions.clj")'
```

### Direct REPL Access

You can also connect directly to the AI Client REPL:

```bash
lein repl :connect localhost:7889
```

Then use all functions directly:
```clojure
user=> (ai-actions/status)
user=> (ai-actions/take-credits)
user=> @ai-websocket-client-v2/client-state
```

## Next Steps

This architecture enables:
1. ✅ Simple command interface for Claude
2. ✅ Persistent connection and state
3. ✅ Works with public Jinteki server
4. 🔜 Add more sophisticated AI decision-making
5. 🔜 Build card-specific action helpers
6. 🔜 Create game tree search for strategic play
