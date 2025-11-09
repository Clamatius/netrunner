# AI Player Action Patterns

This document captures proven Intent => Action patterns for the AI player. Each entry shows what works, what to expect, and how to execute.

---

## 🚀 Quick Start: CLI Interface

**New!** Simple command-line interface for common actions. No more complex eval statements!

### Basic Usage

```bash
# Show help
./dev/send_command help

# Check game state
./dev/send_command status
./dev/send_command hand
./dev/send_command credits

# Take actions
./dev/send_command take-credit
./dev/send_command draw
./dev/send_command play "Sure Gamble"
./dev/send_command end-turn

# Workflows
./dev/simple-turn.sh  # Take all credits and end turn
```

### Available Commands

| Command | Description | Example |
|---------|-------------|---------|
| `status` | Show game state | `./dev/send_command status` |
| `hand` | Show cards in hand | `./dev/send_command hand` |
| `credits` | Show current credits | `./dev/send_command credits` |
| `clicks` | Show remaining clicks | `./dev/send_command clicks` |
| `list-lobbies` | List available games | `./dev/send_command list-lobbies` |
| `join <id> <side>` | Join a game | `./dev/send_command join <uuid> Runner` |
| `start-turn` | Start your turn | `./dev/send_command start-turn` |
| `take-credit` | Click for credit | `./dev/send_command take-credit` |
| `draw` | Draw a card | `./dev/send_command draw` |
| `play <name>` | Play card by name | `./dev/send_command play "Sure Gamble"` |
| `play-index <N>` | Play card by index | `./dev/send_command play-index 0` |
| `install <name>` | Install card by name | `./dev/send_command install "Daily Casts"` |
| `install-index <N>` | Install card by index | `./dev/send_command install-index 0` |
| `run <server>` | Run on server | `./dev/send_command run "R&D"` |
| `choose <N>` | Choose from prompt | `./dev/send_command choose 0` |
| `choose-value <text>` | Choose by matching text | `./dev/send_command choose-value keep` |
| `discard` | Discard to hand size | `./dev/send_command discard` |
| `keep-hand` | Keep mulligan | `./dev/send_command keep-hand` |
| `end-turn` | End turn | `./dev/send_command end-turn` |

### Example Turn

```bash
# Check current state
./dev/send_command status
./dev/send_command hand

# Start turn
./dev/send_command start-turn

# Play Sure Gamble (costs 5, gain 9 = net +4 credits)
./dev/send_command play "Sure Gamble"

# Install a resource or program
./dev/send_command install "Daily Casts"
# Or install by index
./dev/send_command install-index 0

# Take credits with remaining clicks
./dev/send_command take-credit

# End turn
./dev/send_command end-turn
```

Or use the simplified version when testing:
```bash
./dev/simple-turn.sh  # Automatically takes all credits and ends turn
```

---

## Detailed Action Patterns

The sections below provide technical details about each action, including the underlying protocol, expected diffs, and what we learned during testing.

---

## 1. List Lobbies

**Intent:** See available games to join

**Action:**
```clojure
(load-file "dev/list-and-show-lobbies.clj")
```

**What Happens:**
- Sends `:lobby/list` message to server
- Receives `[[[:lobby/list [...]] [:lobby/state]]]` batch
- Displays formatted list of games with:
  - Game title
  - Game ID (UUID)
  - Format (e.g., "system-gateway")
  - Player count (e.g., "0 / 2")
  - Player details (side, username)

**Expected Log Output:**
```
🔍 RAW RECEIVED: [[[:lobby/list [{...}]] [:lobby/state]]]
📦 BATCH of 2 events
📋 Received 1 game(s)
🎮 Lobby state update
```

**Success Criteria:**
- Receive lobby list without errors
- Can see game IDs to join

**What We Learned:**
- Use `load-file` for complex scripts (avoids shell escaping issues)
- Server batches messages as double-nested vectors

---

## 2. Join Lobby as Runner

**Intent:** Join a specific game as the Runner side

**Action:**
```clojure
(do
  (println "Joining game as Runner...")
  (let [gameid-uuid (java.util.UUID/fromString "<GAME-UUID-HERE>")]
    (ai-websocket-client-v2/send-message! :lobby/join
                                           {:gameid gameid-uuid
                                            :request-side "Runner"}))
  (Thread/sleep 3000)
  (println "Joined!"))
```

**What Happens:**
- Sends `:lobby/join` with gameid and requested side
- Server adds AI player to lobby
- Receives `:lobby/state` update showing both players
- System message: "AI-{uid} joined the game."

**Expected Log Output:**
```
🔍 RAW RECEIVED: [[[:lobby/state {...players: [{...Corp...}, {...Runner...}]...}]]]
📤 Sent: :lobby/join
🎮 Lobby state update
   GameID: #uuid "..."
```

**Success Criteria:**
- `:lobby/state` message shows AI player with "Runner" side
- No error messages
- Game messages include join confirmation

**What We Learned:**
- Must convert string UUID to `java.util.UUID` object
- Use `:request-side` key (not just `:side`)
- Server responds with full lobby state including all players
- Game is NOT started yet - waiting for lobby creator to click "Start"

---

## 3. Receive Game Start

**Intent:** Receive initial game state when game begins

**What Happens:**
- Lobby creator clicks "Start"
- Server sends `:game/start` message with complete initial game state
- Client stores full state (5 cards in hand, 5 credits, 0 clicks)
- Corp player gets first mulligan prompt
- After Corp responds, Runner gets mulligan prompt

**Expected Log Output:**
```
🎮 GAME STARTING!
  GameID: <uuid>
```

**Success Criteria:**
- `:game/start` message received
- Full game state populated
- Can query state: `(ws/my-credits)`, `(ws/my-hand-count)`, etc.

**What We Learned:**
- Game starts at turn 0
- Active player is "Corp" (Corp takes first turn)
- Both players have 0 clicks until they start their turn
- Mulligan happens before turn 1 begins

---

## 4. Handle Mulligan (Keep Hand)

**Intent:** Respond to mulligan prompt by keeping the starting hand

**Action:**
```clojure
(load-file "dev/ai-keep-hand.clj")
```

**Or manually:**
```clojure
(let [gameid (:gameid @ws/client-state)
      prompt (ws/get-prompt)
      keep-choice (first (filter #(= "Keep" (:value %)) (:choices prompt)))
      keep-uuid (:uuid keep-choice)]
  (ws/send-message! :game/action
                    {:gameid (if (string? gameid)
                              (java.util.UUID/fromString gameid)
                              gameid)
                     :command "choice"
                     :args {:choice {:uuid keep-uuid}}}))
```

**What Happens:**
- Receive `:game/diff` with mulligan prompt
- Prompt has type "mulligan" with two choices: "Keep" and "Mulligan"
- Send `choice` action with UUID of selected choice
- Server sends `:game/diff` clearing prompt and adding log entry
- Game proceeds to next phase (Corp's turn or Runner's mulligan)

**Expected Log Output:**
```
🔄 GAME/DIFF received
   Diff sample: ({:runner {:prompt-state {:msg "Keep hand?", ...}}}, {})
📝 Applying diff to state
   BEFORE - Runner credits: 5
   BEFORE - Runner clicks: 0
   BEFORE - Runner hand size: 5
   AFTER  - Runner credits: 5
   AFTER  - Runner clicks: 0
   AFTER  - Runner hand size: 5
   ✓ Diff applied successfully

[After sending choice:]
🔄 GAME/DIFF received
   Diff sample: ({:runner {:keep "keep"}, :log ["+", ...]}, {:runner {:prompt-state 0}})
   ✓ Diff applied successfully
```

**Success Criteria:**
- Mulligan prompt received and parsed correctly
- Choice sent successfully
- Prompt cleared (`:prompt-state 0` in diff removals)
- Log entry confirms choice: "AI-{uid} keeps their hand."
- **Diff updates work without errors**

**What We Learned:**
- Prompts have `:msg`, `:prompt-type`, `:choices`, and `:eid`
- Each choice has `:value`, `:uuid`, and `:idx`
- Response format: `{:choice {:uuid "<uuid>"}}`
- Diff format: `[{alterations} {removals}]`
- Removals use `0` to indicate deletion (`:prompt-state 0` means remove prompt)
- Log entries use `:+` marker for append: `[:+ {:text "..."}]`

---

## 5. Verify Diff Handling Works

**How to Test:**
Watch logs during game actions. After EVERY server action you should see:

```
🔄 GAME/DIFF received
   GameID: <uuid>
   Diff type: nil
   Diff keys (if map): nil
   Diff sample: ({...alterations...} {...removals...})
📝 Applying diff to state
   BEFORE - Runner credits: X
   BEFORE - Runner clicks: X
   BEFORE - Runner hand size: X
   AFTER  - Runner credits: Y
   AFTER  - Runner clicks: Y
   AFTER  - Runner hand size: Y
   ✓ Diff applied successfully
```

**Success Criteria:**
- No `nth not supported` errors
- No exceptions in diff application
- State values change appropriately (BEFORE → AFTER)
- Multiple diffs can be processed in sequence

**What We Learned:**
- Server sends diffs in format: `[alterations removals]`
- Must call `differ/patch` with ENTIRE vector, not individual elements
- The `differ` library natively supports `:+` for array appends
- Map diffs are deep-merged automatically
- Can't use `reduce` on the diff vector - pass whole thing to `differ/patch`

---

---

## 6. Start Turn

**Intent:** Begin your turn (gain clicks, Corp draws mandatory card)

**Action:**
```clojure
(do
  (require '[ai-websocket-client-v2 :as ws])
  (let [gameid (:gameid @ws/client-state)]
    (ws/send-message! :game/action
                      {:gameid (if (string? gameid)
                                (java.util.UUID/fromString gameid)
                                gameid)
                       :command "start-turn"
                       :args nil})))
```

**What Happens:**
- Active player switches to you
- **Corp:** Draws 1 card (mandatory), then gains clicks (usually 3)
- **Runner:** Gains 4 clicks (no mandatory draw)
- Log entry: "X started their turn N with Y [Credit] and Z cards..."

**Expected Log Output:**
```
🔄 GAME/DIFF received
   Diff sample: ({:end-turn false, :active-player "runner",
                  :runner {:click 4, ...}, :log ["+", ...]}, {})
📝 Applying diff to state
   BEFORE - Runner clicks: 0
   AFTER  - Runner clicks: 4
   ✓ Diff applied successfully
```

**Success Criteria:**
- Active player becomes your side
- Clicks updated (4 for Runner, 3 for Corp typically)
- Corp hand size increases by 1
- Runner hand size stays same
- Log confirms turn start

**What We Learned:**
- Corp has mandatory draw at turn start (click 1 = draw)
- Runner does NOT auto-draw - more freedom with click usage
- Turn number increments
- Cards in hand become playable (`:playable true` flags set)

---

## 7. Making Runs (Runner)

**Intent:** Initiate a run on a server

**Action:**
```bash
./dev/send_command run "R&D"
./dev/send_command run "HQ"
./dev/send_command run "Archives"
./dev/send_command run "remote1"
```

**CRITICAL: Server Name Format**
Server names MUST use exact game format:
- ✅ `"R&D"` (with ampersand)
- ❌ `"rd"` or `"RD"` (will silently fail)
- ✅ `"HQ"`
- ✅ `"Archives"`
- ✅ `"remote1"`, `"remote2"`, etc.

**Run Phase Sequence:**

A run is NOT a single action - it's a sequence of phases with prompts:

1. **Initiation Phase**
   - Send: `{:command "run", :args {:server "R&D"}}`
   - Receive prompt: "Continue to Approach Server"
   - Phase indicator: "Initiation"

2. **Continue to next phase**
   - Send: `{:command "continue", :args nil}`
   - Or use: `./dev/send_command choose 0` (if continue is option 0)

3. **Subsequent phases** (if ICE present):
   - Approach ICE (Corp can rez)
   - Encounter ICE (Runner can use icebreakers)
   - Movement between ICE positions
   - Each requires continue/choose responses

4. **Approach Server**
   - Final continue before accessing cards

5. **Breach/Access**
   - Prompt to steal agendas or trash cards
   - Use `choose-value` for semantic selection

**WebSocket Traffic Example:**
```clojure
;; Start run
[[:game/action {:gameid #uuid "...",
                :command "run",
                :args {:server "R&D"}}]]

;; Continue through phase
[[:game/action {:gameid #uuid "...",
                :command "continue",
                :args nil}]]
```

**What We Learned:**
- Previous run commands "didn't work" because they initiated the run but we never responded to continuation prompts
- The run stalled waiting for our input at each phase
- Must monitor prompts and respond to advance through run phases
- Discovered correct format by capturing WebSocket traffic from web client

---

## 8. Discard to Hand Size

**Intent:** Discard down to maximum hand size (usually 5 cards)

**Action:**
```bash
./dev/send_command discard
```

**What Happens:**
- Auto-detects your side (Corp or Runner)
- Calculates how many cards to discard
- Automatically selects and discards from end of hand
- Uses `select-card!` with proper `eid` from prompt

**Technical Details:**
```clojure
;; Under the hood
(let [state @ws/client-state
      side (keyword (:side state))
      prompt (get-in state [:game-state side :prompt-state])
      hand (get-in state [:game-state side :hand])
      card-to-discard (last hand)
      eid (:eid prompt)]
  (ai-websocket-client-v2/select-card! card-to-discard eid))
```

**Success Criteria:**
- Hand size reduced to maximum
- Log shows: "AI-{uid} trashes {card-name}"
- Prompt cleared

**What We Learned:**
- Discard prompts require selecting specific cards, not just choosing an option
- Must use `select-card!` function with the `eid` from the prompt
- The `discard-to-hand-size!` wrapper handles this automatically
- Common at end of turn when you've drawn too many cards

---

## 9. Enhanced Prompt Handling

**New Capabilities:**

### Viewing Prompts
```bash
./dev/send_command prompt
```

**Output shows:**
- Message text (e.g., "Keep hand?")
- Prompt type (e.g., "mulligan", "select")
- Card information (if relevant)
- Available choices with indices
- Selectable cards count

### Choosing by Value (Semantic Selection)
```bash
./dev/send_command choose-value keep
./dev/send_command choose-value steal
./dev/send_command choose-value "jack out"
```

**Benefits:**
- More intuitive than remembering indices
- Works with partial matches (case-insensitive)
- Shows which option was matched before selecting

**What We Learned:**
- Prompts come in different formats (maps with `:value`, raw strings, etc.)
- New `format-choice` helper handles all formats robustly
- Semantic selection much better for AI decision-making
- Falls back to showing all options if no match found

---

## Technical Notes (Updated)

### Bash History Expansion Fix
The `send_command` script now includes `set +H` to disable bash history expansion. This prevents `!` characters in function names from being stripped. Critical for commands like:
- `run!`
- `choose-by-value!`
- `discard-to-hand-size!`

Without this fix, these functions would fail with "No such var" errors.

---

## Next Actions to Document

10. **Complete Run Automation** - Handle full run sequence with prompts
11. **ICE Interaction** - Breaking subroutines, jacking out
12. **Install with Target Selection** - Installing ICE on specific servers
13. **Use Installed Card** - Trigger ability on installed card
14. **Corp Scoring** - Advancing and scoring agendas

---

## Technical Notes

### Message Format Patterns
- **Send:** `(pr-str [[event-type data]])`
- **Receive:** Batched as `[[[:event1 data1] [:event2 data2]]]`
- **Ping:** Ignore `:chsk/ws-ping` messages

### UUID Handling
Always convert string UUIDs to Java UUID objects:
```clojure
(java.util.UUID/fromString "uuid-string-here")
```

### Timing
- Use `Thread/sleep` after sending messages to allow server response time
- Typical wait: 1000-3000ms depending on action

### Namespace Aliases in REPL
- `ai-websocket-client-v2` - WebSocket client functions
- `ai-actions` - High-level action helpers (if loaded)
- `ws` - Shorthand alias for websocket client (if loaded)

---

## Testing Checklist

- [x] Can list lobbies
- [x] Can join as Runner
- [x] Can join as Corp
- [x] Can receive game start
- [x] Can handle mulligan (keep hand)
- [x] **Diff handling works without errors!**
- [x] Can take basic actions (credit, draw, end turn)
- [x] Can play cards (by name and index)
- [x] Can install cards
- [x] Can initiate runs
- [x] Can handle discard prompts
- [x] Can use semantic choice selection
- [ ] Can complete full run sequence (with continue prompts)
- [ ] Can handle ICE encounters
- [ ] Can access and steal/trash cards

**Last Updated:** 2025-11-09

**Recent Improvements:**
- ✅ Fixed bash history expansion (`set +H`) - enables `!` in function names
- ✅ Added `choose-value` for semantic prompt selection
- ✅ Added `discard` command with auto-detection
- ✅ Discovered correct server name format (`"R&D"` not `"rd"`)
- ✅ Documented run phase sequence from WebSocket capture
- ✅ Enhanced prompt display with robust format handling
