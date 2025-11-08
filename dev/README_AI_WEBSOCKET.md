# AI WebSocket Client - Two/Three REPL Architecture

## Overview

This directory contains a **WebSocket-based AI client** that connects to the Netrunner game server using the same protocol as the web browser client. This architecture enables:

- **Isolated Testing**: AI client runs in separate REPL from game server
- **Real Protocol Testing**: Uses actual WebSocket communication, not direct state manipulation
- **Legal Moves Only**: Can't execute illegal moves (server validates everything)
- **Multi-Agent**: Can run multiple AI clients simultaneously (3-REPL setup)

## The Breakthrough: Serialization

The key challenge was figuring out **Sente's message format**:

```clojure
;; Sending messages (client → server)
(gniazdo.core/send-msg socket (pr-str [[event-type data]]))
;; Example: (pr-str [[:lobby/create {:title "My Game" ...}]])

;; Receiving messages (server → client)
;; Messages arrive as: [[[:event1 data1] [:event2 data2]]]
;; Or single:          [[:event data]]
```

This double-wrapping is Sente's protocol - the outer layer is the WebSocket envelope, the inner layer is the event vector.

## Architecture Comparison

### ❌ Old Approach: Direct State Manipulation
```clojure
;; Directly manipulate game state atom (brittle, can create illegal states)
(swap! state assoc-in [:corp :credit] 1000) ; Illegal!
```

### ✅ New Approach: WebSocket Client (Final Form)
```clojure
;; Send actions through server validation
(ai-websocket-client-v2/send-action! "credit" nil) ; Legal, validated
```

## Files

### Core Client Implementation
- **`ai_websocket_client_v2.clj`**: WebSocket client using gniazdo library
  - Connection management with auto-reconnect
  - Message parsing and batched event handling
  - State management (game state, lobby state)
  - Lobby operations (create, join, list)
  - Game actions (send-action!, take-credits!, etc.)

- **`ai_actions.clj`**: High-level API for common operations
  - Simplified wrappers around websocket client
  - Workflow helpers (mulligan, simple turns)
  - Human-friendly status displays

- **`ai_client_init.clj`**: Initialization script for AI client REPL
  - Loads WebSocket client and actions
  - Auto-connects to localhost:1042
  - Displays help on startup

### Scripts
- **`start-ai-client-repl.sh`**: Start AI client REPL on port 7889
- **`stop-ai-client.sh`**: Stop AI client REPL
- **`ai-eval.sh`**: Send commands to AI client REPL
- **`src/clj/nrepl-eval.sh`**: Generic nREPL eval (for any port)

### Test Files
- **`direct-lobby-test.clj`**: Direct test of lobby creation
- **`test-send-message.sh`**: Message sending test script
- **`test-lobby-creation.clj`**: Lobby creation test

## Quick Start

### 1. Start the Game Server (Terminal 1)
```bash
lein repl
# In REPL:
(go)  # Starts server on http://localhost:1042
```

### 2. Start AI Client (Terminal 2)
```bash
cd dev
./start-ai-client-repl.sh
# Output:
# ✅ AI Client Ready!
#    UID: AI-d01402a2-9f0e-49b4-a585-c385a767fe12
```

### 3. Use AI Client
```bash
# In another terminal
./dev/ai-eval.sh '(ai-actions/create-lobby! "Test Game")'
./dev/ai-eval.sh '(ai-actions/list-lobbies)'

# Or connect to REPL directly
lein repl :connect localhost:7889
# Then:
(ai-actions/create-lobby! "My Game")
(ai-actions/list-lobbies)
```

## Common Operations

### Create a Lobby
```clojure
;; Simple (uses defaults: system-gateway Beginner format)
(ai-actions/create-lobby! "My Test Game")

;; With options
(ai-actions/create-lobby! {:title "Advanced Game"
                            :format "standard"
                            :side "Corp"})
```

### List and Join Games
```clojure
;; List available games
(ai-actions/list-lobbies)
;; Output shows:
;; 🎮 My Test Game
;;    ID: 1dce5509-5f7a-4915-839a-096b49ace382
;;    Players: 1 / 2

;; Join a game
(ai-actions/connect-game! "1dce5509-5f7a-4915-839a-096b49ace382" "Corp")
```

### Game Actions
```clojure
;; Handle mulligan
(ai-actions/keep-hand)

;; Basic actions
(ai-actions/take-credits)
(ai-actions/draw-card)
(ai-actions/end-turn)

;; Check status
(ai-actions/status)
(ai-actions/show-prompt)
(ai-actions/hand)
```

## Three-REPL Setup (Two AI Clients)

For testing full games with AI vs AI:

### Terminal 1: Game Server
```bash
lein repl
(go)
```

### Terminal 2: AI Client 1 (Corp)
```bash
./dev/start-ai-client-repl.sh  # Starts on port 7889
lein repl :connect localhost:7889

;; Create and wait
(ai-actions/create-lobby! "AI vs AI Test")
;; Note the game-id from output
```

### Terminal 3: AI Client 2 (Runner)
```bash
# Start second client on different port
PORT=7890 ./dev/start-ai-client-repl.sh
lein repl :connect localhost:7890

;; Join the game
(ai-actions/connect-game! "game-id-from-above" "Runner")
```

Now you can control both sides independently and test full game flows!

## Default Game Format: System Gateway Beginner

The default format is **system-gateway Beginner** which provides:
- **Fixed decks**: No deck building required
- **Beginner cards**: Simpler card pool for testing
- **Immediate start**: Can start game without selecting decks

This is perfect for AI development since you don't need to:
- Set up deck databases
- Handle deck selection
- Deal with complex card interactions initially

To use other formats:
```clojure
(ai-actions/create-lobby! {:title "Standard Game"
                            :format "standard"})
```

## API Reference

### High-Level (ai-actions namespace)

#### Lobby Management
- `(create-lobby! title)` - Create lobby with defaults
- `(create-lobby! options-map)` - Create with custom options
- `(list-lobbies)` - Show available games

#### Game Connection
- `(connect-game! gameid side)` - Join a game
- `(status)` - Show game status
- `(hand)` - Show your hand

#### Mulligan
- `(keep-hand)` - Keep current hand
- `(mulligan)` - Redraw hand
- `(auto-keep-mulligan)` - Auto-handle mulligan

#### Actions
- `(take-credits)` - Click for credit
- `(draw-card)` - Draw a card
- `(end-turn)` - End turn
- `(choose n)` - Choose option n from prompt

#### Workflows
- `(simple-corp-turn)` - 3x credit, end
- `(simple-runner-turn)` - 4x credit, end

### Low-Level (ai-websocket-client-v2 namespace)

#### Connection
- `(connect! url)` - Connect to WebSocket
- `(disconnect!)` - Disconnect
- `(connected?)` - Check connection
- `(ensure-connected!)` - Reconnect if needed

#### Messages
- `(send-message! event-type data)` - Send raw message
- `(send-action! command args)` - Send game action

#### Lobby Operations
- `(create-lobby! options)` - Create lobby
- `(join-game! options)` - Join game
- `(request-lobby-list!)` - Request lobby list

#### State Queries
- `(get-game-state)` - Get current game state
- `(get-lobby-list)` - Get lobby list
- `(my-turn?)` - Is it my turn?
- `(my-credits)` - My credit count
- `(my-hand)` - My hand cards
- `(get-prompt)` - Current prompt

## Message Protocol Examples

### Creating a Lobby
```clojure
;; Send
[[:lobby/create {:title "Test"
                 :format "system-gateway"
                 :gateway-type "Beginner"
                 :side "Any Side"
                 :room "casual"
                 :allow-spectator true
                 :save-replay true}]]

;; Receive (batched)
[[[:lobby/state {...lobby-details...}]
  [:lobby/list [{...game1...} {...game2...}]]]]
```

### Joining a Game
```clojure
;; Send
[[:lobby/join {:gameid #uuid "..."
               :request-side "Corp"}]]

;; Receive
[[:lobby/state {...updated-lobby...}]]
```

### Game Action
```clojure
;; Send
[[:game/action {:gameid #uuid "..."
                :command "credit"
                :args nil}]]

;; Receive
[[:game/diff {:gameid #uuid "..."
              :diff {...state-changes...}}]]
```

## Troubleshooting

### Connection Issues
```clojure
;; Check connection
(ai-websocket-client-v2/connected?)  ; Should return true

;; Check socket
(:socket @ai-websocket-client-v2/client-state)  ; Should be non-nil

;; Reconnect
(ai-websocket-client-v2/connect! "ws://localhost:1042/chsk")
```

### Debug Messages
```clojure
;; Raw state inspection
@ai-websocket-client-v2/client-state

;; Message history (if enabled)
(:messages @ai-websocket-client-v2/client-state)

;; Lobby list
(ai-websocket-client-v2/show-games)
```

### Server Not Receiving Messages
The server has debugging enabled for lobby messages. Check server REPL output for:
```
🔍 DEBUG EVENT: {:id :lobby/create, :?data {...}, :uid "AI-..."}
```

## Why This Architecture Is Better

1. **Realistic Testing**: Uses real WebSocket protocol, not shortcuts
2. **Server Validation**: All moves validated by game engine
3. **Isolated Development**: AI client crashes don't affect server
4. **Multi-Agent Ready**: Easy to run multiple AI clients
5. **Protocol Documentation**: Code serves as protocol documentation
6. **Future-Proof**: Can evolve independently of server internals

## Next Steps

- Implement more game actions (install, play, run)
- Add AI decision-making logic
- Create higher-level strategy functions
- Test full game scenarios
- Add support for complex card interactions
