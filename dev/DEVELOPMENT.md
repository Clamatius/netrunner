# AI Player Development Guide

## Overview

This document covers the internals of the AI player system: WebSocket protocol, state management, debugging techniques, and testing approaches. Use this when you need to understand how the system works or debug issues.

---

## Architecture

### Two-REPL Design

**Game Server REPL (port 7888)**
- Main Jinteki server
- Hosts game at http://localhost:1042
- Validates all game actions
- Sends state updates via WebSocket

**AI Client REPL (port 7889)**
- Maintains persistent WebSocket connection
- Stores local game state
- Processes commands from `send_command`
- Updates state via diffs from server

**Why Two REPLs?**
- **Isolation**: AI can't accidentally manipulate server state directly
- **Validation**: All actions go through server validation (legal moves only)
- **Real Protocol**: Tests actual WebSocket communication
- **Stateful Connection**: WebSocket stays open between commands

### File Organization

```
dev/
├── send_command                    # User-facing command interface
├── ai-eval.sh                      # Send code to AI client REPL
├── start-ai-client-repl.sh         # Start AI client
├── stop-ai-client.sh               # Stop AI client
│
└── src/clj/
    ├── nrepl-eval.sh               # Generic nREPL evaluator
    ├── ai_websocket_client_v2.clj  # WebSocket client (core)
    ├── ai_actions.clj              # High-level game actions
    ├── ai_client_init.clj          # Initialization code
    ├── card_loader.clj             # MongoDB card loader
    └── user.clj                    # Server REPL namespace
```

---

## WebSocket Protocol

### Sente Message Format

**The Breakthrough:** Sente uses double-wrapped vectors for message serialization.

**Sending (Client → Server):**
```clojure
(require '[gniazdo.core :as ws])
(ws/send-msg socket (pr-str [[event-type data]]))

;; Example:
(ws/send-msg socket (pr-str [[:game/action {:gameid #uuid "..."
                                             :command "credit"
                                             :args nil}]]))
```

**Receiving (Server → Client):**
```clojure
;; Batched events:
[[[:event1 data1] [:event2 data2] [:event3 data3]]]

;; Single event:
[[:event data]]

;; Ping (ignore):
[[:chsk/ws-ping]]
```

**Key Insight:** The outer vector is the WebSocket envelope, inner vectors are event tuples.

### Message Types

**Connection:**
- `:chsk/handshake` - Connection established
- `:chsk/ws-ping` - Heartbeat (ignore)
- `:chsk/state` - Connection state change

**Lobby:**
- `:lobby/list` - List of available games
- `:lobby/state` - Lobby state update (players joined/left)
- `:lobby/create` - Game created
- `:lobby/join` - Join game
- `:lobby/leave` - Leave game

**Game:**
- `:game/start` - Game started (initial full state)
- `:game/resync` - Full state sync (rejoin/reconnect)
- `:game/diff` - State update (incremental changes)
- `:game/action` - Action sent to server

### Example Message Flow

**Create and Join Game:**
```clojure
;; 1. Client sends create
→ [[:lobby/create {:title "Test Game"
                   :side "Corp"
                   :format "system-gateway"
                   ...}]]

;; 2. Server responds with lobby state
← [[[:lobby/state {...game details...}]]]

;; 3. Another client joins
→ [[:lobby/join {:gameid #uuid "..." :request-side "Runner"}]]

;; 4. Server updates lobby state
← [[[:lobby/state {...updated with both players...}]]]

;; 5. Creator starts game
→ [[:game/start {:gameid #uuid "..."}]]

;; 6. Server sends initial game state to both clients
← [[[:game/start {...full game state...}]]]
```

**Game Action:**
```clojure
;; 1. Client sends action
→ [[:game/action {:gameid #uuid "..."
                  :command "credit"
                  :args nil}]]

;; 2. Server sends diff to both clients
← [[[:game/diff [{:corp {:credit 6}  ; additions
                  :log [:+ {:text "Corp takes 1 credit"}]}
                 {}]]]]                ; removals
```

---

## State Management

### Client State Atom

Located: `ai-websocket-client-v2/client-state`

**Structure:**
```clojure
{:uid "AI-d01402a2-9f0e-49b4-a585-c385a767fe12"  ; User ID
 :gameid #uuid "1806d5c9-f540-4158-ab66-8182433dcf10"
 :side "runner"                      ; "corp" or "runner"
 :game-state {...}                   ; Full game state
 :lobby-list [...]                   ; Available games
 :chsk <websocket-channel>}          ; WebSocket connection
```

### Game State Structure

**Top-level keys:**
- `:corp` - Corp player state
- `:runner` - Runner player state
- `:active-player` - Current active player ("corp" or "runner")
- `:turn` - Turn number
- `:end-turn` - Boolean, true when waiting for end turn
- `:run` - Active run state (or nil)
- `:log` - Game log entries

**Player state (`:corp` or `:runner`):**
```clojure
{:credit 5                           ; Credits
 :click 4                            ; Clicks remaining
 :hand [...]                         ; Cards in hand
 :deck [...]                         ; Cards in deck
 :discard [...]                      ; Discard pile
 :rig {:program [...] :hardware [...] :resource [...]}  ; Installed cards (Runner)
 :servers {...}                      ; Installed cards (Corp)
 :prompt-state {...}                 ; Current prompt
 :user {:username "AI-xyz"}}         ; User info
```

**Run state (`:run`):**
```clojure
{:server "R&D"                       ; Target server
 :position 0                         ; ICE position
 :phase :encounter-ice               ; Current phase
 :ices [...]                         ; ICE on this server
 :current-ice {...}}                 ; ICE being encountered
```

### Diff Handling

Server sends incremental updates as diffs to minimize bandwidth.

**Diff Format:**
```clojure
[{:alterations ...}   ; Map of changes/additions
 {:removals ...}]     ; Map of deletions
```

**Examples:**

**Simple update (credit change):**
```clojure
[{:runner {:credit 6}}   ; Set runner credits to 6
 {}]                     ; No removals
```

**Log append:**
```clojure
[{:log [:+ {:text "Corp takes 1 credit"}]}  ; Append to log
 {}]
```

**Prompt clear:**
```clojure
[{}                          ; No additions
 {:runner {:prompt-state 0}}] ; Delete prompt-state (0 = remove)
```

**Application:**
```clojure
(require '[differ.core :as differ])

(defn apply-diff [state diff-vector]
  (differ/patch state diff-vector))
```

**Critical:** Pass entire diff vector `[alterations removals]` to `differ/patch`, not individual elements.

---

## Debugging

### Check REPL Status

```bash
# Is AI client running?
lsof -i:7889

# View logs
tail -f /tmp/ai-client-repl.log
```

### Inspect State in REPL

```bash
# Connect to AI client
lein repl :connect localhost:7889
```

```clojure
;; Check connection
@client-state

;; View game state
(require '[ai-websocket-client-v2 :as ws])
(ws/get-game-state)

;; Check specific values
(ws/my-credits)
(ws/my-hand-count)
(ws/get-prompt)

;; See full state keys
(keys (ws/get-game-state))
```

### Common Issues

**Problem: Commands fail with "not connected"**

Check:
```clojure
(require '[ai-websocket-client-v2 :as ws])
(:uid @ws/client-state)  ; Should be "AI-xxxxx"
```

Fix:
```bash
./send_command connect
```

**Problem: Game state is nil**

This means `:game/start` or `:game/resync` hasn't been received yet.

Check:
```clojure
;; Wait for state
(Thread/sleep 2000)
(ws/get-game-state)

;; Force resync
(ws/request-resync! gameid)
```

**Problem: Prompts not appearing**

Check prompt state directly:
```clojure
(get-in (ws/get-game-state) [:runner :prompt-state])
```

Prompt should have:
- `:msg` - Prompt message
- `:prompt-type` - Type (mulligan, select, etc.)
- `:choices` - Available choices
- `:eid` - Event ID

**Problem: Diff application fails**

Check logs for errors. Common cause: trying to apply diffs to wrong state structure.

Debug:
```clojure
;; Enable logging in ai_websocket_client_v2.clj
;; Look for "Applying diff to state" messages
```

### Message Tracing

Enable verbose logging in `ai_websocket_client_v2.clj`:

```clojure
;; In handle-message function, uncomment:
(println "🔍 RAW RECEIVED:" message)
```

This shows all WebSocket traffic for debugging protocol issues.

### Testing Without Web UI

Run both Corp and Runner as AI clients:

```bash
# Terminal 1: Start server
lein repl
(go)

# Terminal 2: Start AI client 1 (Corp)
./dev/start-ai-client-repl.sh

# Terminal 3: Create game as Corp
./dev/send_command create-game "Test" "Corp"

# Terminal 4: Start AI client 2 (Runner) on different port
# (modify start script to use port 7890)

# Terminal 5: Join as Runner
./dev/send_command join <game-id> Runner

# Back to Terminal 3: Start game
./dev/send_command start-game
```

---

## Testing

### Unit Tests

Located: `dev/src/clj/game_command_test.clj`

**Approach:**
- Direct game engine testing (no WebSocket)
- Fixed-order decks (deterministic)
- Open-hand games (all cards visible)

**Run tests:**
```clojure
(load-file "dev/src/clj/game_command_test.clj")
(in-ns 'game-command-test)
(test-basic-turn-flow)
```

### Integration Tests

Located: `dev/src/clj/full_game_test.clj`

**Approach:**
- WebSocket clients connecting to real server
- Tests connection, lobby creation, game start
- Subject to deck shuffling (non-deterministic)

**Run tests:**
```clojure
(load-file "dev/src/clj/full_game_test.clj")
(in-ns 'full-game-test)
(run-full-game-test!)
```

### Manual Testing

**Basic workflow:**
```bash
# 1. Status checks
./send_command status
./send_command board
./send_command hand

# 2. Take action
./send_command start-turn
./send_command play "Sure Gamble"

# 3. Verify state
./send_command status
./send_command credits

# 4. Check logs
./send_command log 10
```

---

## Advanced Topics

### UUID Handling

Server expects Java UUID objects, not strings:

```clojure
(java.util.UUID/fromString "1806d5c9-f540-4158-ab66-8182433dcf10")
```

`send_command` handles this automatically, but when writing Clojure directly:

```clojure
;; ✅ Correct
(ws/send-message! :game/action
  {:gameid (java.util.UUID/fromString gameid-string)
   :command "credit"
   :args nil})

;; ❌ Wrong
(ws/send-message! :game/action
  {:gameid gameid-string  ; String won't work!
   :command "credit"
   :args nil})
```

### Card Loading

Cards are loaded from MongoDB into `@jinteki.cards/all-cards` atom.

**In AI client REPL:**
```clojure
(require '[card-loader])
(card-loader/load-cards!)
(count @jinteki.cards/all-cards)  ; Should be ~1990
```

This is automatic when using `send_command card-text`.

### Timing

WebSocket communication is asynchronous. Allow time for server responses:

```clojure
;; After sending action
(ws/send-action! "credit" nil)
(Thread/sleep 1000)  ; Wait for diff
(ws/my-credits)      ; Now updated
```

`send_command` includes automatic delays, but when writing direct REPL code, add sleeps.

### Prompt Types

Different prompt types require different response formats:

**Choice prompt (`:prompt-type` = "select" or similar):**
```clojure
;; Respond with choice UUID
{:choice {:uuid "abc-123"}}
```

**Card selection (`:prompt-type` = "select-card"):**
```clojure
;; Respond with card UUID and event ID
{:card {:uuid "card-uuid"} :eid 42}
```

**Continue prompt:**
```clojure
;; Just send continue command
{:command "continue" :args nil}
```

---

## Code Style

### Websocket Client (`ai_websocket_client_v2.clj`)

**State management:**
- Single atom: `client-state`
- Update via `swap!` only
- Never `reset!` (preserves connection)

**Message handling:**
- `handle-message` - Dispatch by event type
- `handle-batch` - Process batched events
- `apply-diff` - Apply state updates

**Public API:**
- `connect!` / `disconnect!` - Connection management
- `send-message!` / `send-action!` - Send to server
- `get-game-state` / `get-prompt` - Query state
- `my-credits` / `my-hand` - Convenience accessors

### Actions (`ai_actions.clj`)

**Naming:**
- Functions end with `!` for side effects
- No `!` for pure queries
- Examples: `play-card!`, `take-credits!`, `status` (no `!`)

**Error handling:**
- Print clear error messages
- Return nil on failure
- Don't throw (let caller decide)

---

## Future Enhancements

### Potential Improvements

1. **Auto-retry on disconnect** - Currently manual reconnect
2. **State diff debugging mode** - Show before/after for each diff
3. **Command history** - Track all commands sent
4. **Action undo** - Roll back to previous state (testing only)
5. **Multi-game support** - Handle multiple concurrent games

### Extension Points

**Adding new commands to `send_command`:**
1. Add case to `send_command` script
2. Implement function in `ai_actions.clj`
3. Update help text
4. Test with `./send_command <new-command>`

**Adding new message handlers:**
1. Add case to `handle-message` in `ai_websocket_client_v2.clj`
2. Update state appropriately
3. Add logging for visibility

---

## Resources

- **Main Interface:** `send_command` - All available commands
- **Game Reference:** `GAME_REFERENCE.md` - Command documentation
- **Quick Start:** `README.md` - Setup and basic usage
- **Source Code:**
  - `ai_websocket_client_v2.clj` - Core WebSocket client
  - `ai_actions.clj` - High-level actions
  - `ai_client_init.clj` - Initialization

---

**Last Updated:** 2025-11-09
