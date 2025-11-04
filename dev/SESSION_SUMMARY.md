# AI Player Development Session Summary

## What We Accomplished

### 1. **Loaded Real Game Content** ✅
- Modified `full_game_test.clj` to load System Gateway beginner teaching decks
- Added `:gateway-type "beginner"` parameter to lobby creation
- Corp deck: 34 cards (The Syndicate identity)
- Runner deck: 30 cards (The Catalyst identity)
- Games now start with actual playable decks instead of empty decks

### 2. **Created Two-Level Testing Architecture** ✅

#### Integration Tests (`dev/src/clj/full_game_test.clj`)
- WebSocket clients connecting to real running server
- Tests full network layer: connection → lobby → game start → actions
- Subject to server's deck shuffling (non-deterministic)
- Good for verifying the AI can communicate with the server

**Key functions:**
- `(run-full-game-test!)` - Create game with both AI clients
- `(auto-mulligan!)` - Auto-respond to mulligan prompts
- `(check-state)` - View game state and deck counts
- `(check-prompts)` - See current prompts for both sides
- `(send-action! client command args)` - Send any game action

#### Unit Tests (`dev/src/clj/game_command_test.clj`) **← Recommended for AI development**
- Direct game engine testing (no WebSocket/server)
- Fixed-order decks (no shuffling - fully deterministic!)
- Open-hand games where all cards are visible
- Fast and reproducible

**Key functions:**
- `(test-basic-turn-flow)` - Example: credits, draw, end turn
- `(test-playing-cards)` - Example: playing ops, installing cards
- `(open-hand-game)` - Create game with first 5 cards as starting hand
- `(custom-open-hand-game corp-hand corp-deck runner-hand runner-deck)` - Full control over game setup
- `(print-game-state state)` - View current state
- `(print-board-state state)` - View installed cards

### 3. **AI Response Helpers** ✅
Added initial AI logic for responding to game prompts:

```clojure
;; In full_game_test.clj
(auto-mulligan!)           ; Both sides keep hands
(respond-to-mulligan! :corp)  ; Corp keeps hand
(get-prompt :corp)         ; Get current prompt info
```

### 4. **Comprehensive Documentation** ✅
- **`dev/AI_GAME_ACTIONS.md`**: Complete reference of all game actions
  - 30+ action types documented with examples
  - WebSocket message format
  - Common patterns (turn flow, runs, etc.)

- **`dev/AI_TESTING_GUIDE.md`**: Step-by-step testing instructions
  - How to use both test levels
  - REPL commands and examples
  - Troubleshooting guide

- **`dev/SESSION_SUMMARY.md`**: This file!

## What's Ready to Test

### Integration Test Flow
```clojure
;; From REPL after (lein repl) and (go):
(load-file "dev/src/clj/full_game_test.clj")
(in-ns 'full-game-test)

;; Run full test
(run-full-game-test!)

;; Wait for game to start (~3 seconds)
(Thread/sleep 3000)

;; Verify decks loaded
(check-state)
;; Expected: Corp ~29 cards in deck, Runner ~25 cards in deck

;; Check prompts
(check-prompts)
;; Expected: Both sides have mulligan prompts

;; Respond to mulligan
(auto-mulligan!)

;; Wait for response
(Thread/sleep 2000)

;; Check state again - should be Corp's turn starting
(check-prompts)
(check-state)

;; Send a basic action: Corp clicks for credit
(send-action! :corp "credit" nil)
```

### Unit Test Flow (Recommended)
```clojure
;; From REPL:
(load-file "dev/src/clj/game_command_test.clj")
(in-ns 'game-command-test)

;; Run example tests
(test-basic-turn-flow)
(test-playing-cards)

;; Create custom game
(def my-game (open-hand-game))
(print-game-state my-game)

;; Play actions
(core/process-action "credit" my-game :corp nil)
(core/process-action "draw" my-game :corp nil)
(play-from-hand my-game :corp "Hedge Fund")
(print-game-state my-game)
```

## Next Steps for AI Development

### Phase 1: Basic Turn Logic (Unit Tests)
1. **Implement mulligan decision**
   - Keep if 2+ economy cards
   - Mulligan otherwise

2. **Implement basic Corp turn**
   - Play economy operations (Hedge Fund, etc.)
   - Install ice on centrals
   - Install and advance agendas in remote
   - Click for credits if nothing else to do

3. **Implement basic Runner turn**
   - Play economy events (Sure Gamble, etc.)
   - Install breakers and economy resources
   - Run undefended servers
   - Click for credits if nothing else to do

### Phase 2: Action Testing (Unit Tests)
Use `game_command_test.clj` to test specific scenarios:
- Corp rezzing ice during a run
- Runner breaking subroutines
- Scoring agendas
- Trashing resources
- Tag punishment

### Phase 3: Integration Testing
Once basic AI logic works in unit tests, test via WebSocket:
- Verify AI can play full games against itself
- Test network resilience (disconnects, timeouts)
- Test with different preconstructed decks

### Phase 4: Advanced AI
- Rig building (install breakers in correct order)
- Economic analysis (credit management)
- Run timing (when to pressure Corp)
- Bluffing (install unrezzed cards strategically)

## Key Files Reference

### Test Files
- `dev/src/clj/full_game_test.clj` - Integration tests (WebSocket)
- `dev/src/clj/game_command_test.clj` - Unit tests (direct engine)

### Documentation
- `dev/AI_GAME_ACTIONS.md` - Complete action reference
- `dev/AI_TESTING_GUIDE.md` - Testing instructions
- `dev/SESSION_SUMMARY.md` - This summary

### Infrastructure
- `dev/watch-errors.sh` - Error log monitor
- `dev/repl-start.sh` - REPL launcher with logging
- `CLAUDE.local.md` - Auto-updated error notifications

## Troubleshooting

### Integration Tests
- **Deck counts are 0**: Verify `:gateway-type "beginner"` in lobby/create
- **Connection timeout**: Old sockets not cleaned up - restart REPL
- **Parse errors**: Verify EDN reader for time/instant tags

### Unit Tests
- **Compilation errors**: Ensure test framework is loaded
- **Card not found**: Check card title spelling matches data/cards.edn
- **State mutations**: Remember @state is mutable atom

### Dev Loop
- Errors logged to: `dev/repl-errors.log`
- Recent errors: Check `CLAUDE.local.md`
- Full output: Check `dev/repl-output.log` with sequence numbers

## Testing Checklist

Before moving to AI logic implementation, verify:

- [ ] Integration test creates game successfully
- [ ] Both clients connect and join lobby
- [ ] System Gateway beginner decks load (check deck counts)
- [ ] Mulligan prompts appear
- [ ] Auto-mulligan works (both sides keep hands)
- [ ] Game progresses to Corp's first turn
- [ ] Unit tests compile and run
- [ ] test-basic-turn-flow shows correct state transitions
- [ ] test-playing-cards installs and plays cards correctly
- [ ] Can send manual actions and see state changes

Once all checklist items pass, ready to implement AI decision logic!

## Architecture Summary

```
┌─────────────────────────────────────────────────────────────┐
│                    AI Player Architecture                    │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌─────────────────┐                  ┌──────────────────┐  │
│  │  Unit Tests     │                  │ Integration Tests │  │
│  │  (game engine)  │                  │  (WebSocket)     │  │
│  ├─────────────────┤                  ├──────────────────┤  │
│  │ • Deterministic │                  │ • Full network   │  │
│  │ • Fixed decks   │                  │ • Real server    │  │
│  │ • Fast          │                  │ • Shuffled decks │  │
│  │ • Open hands    │                  │ • Async          │  │
│  └────────┬────────┘                  └────────┬─────────┘  │
│           │                                    │            │
│           │  ┌────────────────────────────┐   │            │
│           └─→│   AI Decision Logic        │←──┘            │
│              │  • Read game state         │                │
│              │  • Evaluate options        │                │
│              │  • Select action           │                │
│              │  • Send command            │                │
│              └────────────────────────────┘                │
│                           │                                 │
│                           ↓                                 │
│              ┌────────────────────────────┐                │
│              │  Game Action Reference     │                │
│              │  (AI_GAME_ACTIONS.md)      │                │
│              └────────────────────────────┘                │
└─────────────────────────────────────────────────────────────┘
```

## Key Insight from This Session

The user correctly identified we need **two levels**:

1. **Integration level**: Tests the plumbing (can we connect, send messages, receive responses?)
2. **Unit level**: Tests the logic (given this game state, what action should AI take?)

Mixing these concerns would make debugging hard. Now we can:
- Develop AI logic in unit tests (fast, deterministic)
- Verify it works via WebSocket in integration tests
- Be confident that bugs are either "logic bugs" or "network bugs", not "somewhere in between"

Great architecture decision! 🎯
