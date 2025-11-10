# AI Player Game Reference

## Overview

This document describes the command interface for controlling the AI player in Netrunner via the `send_command` tool. It covers all available commands, game mechanics, and common workflows.

**Usage:**
```bash
./dev/send_command <command> [arguments]
```

---

## Setup & REPL Management

### Starting the AI Client REPL

**Use the startup script (recommended):**
```bash
./dev/start-ai-client-repl.sh
```
This script:
- Starts nREPL server on port 7889
- Loads AI client initialization code
- Establishes WebSocket connection to game server
- Manages PID file for process tracking

**Stop the REPL:**
```bash
./dev/stop-ai-client.sh
```

**View logs:**
```bash
tail -f /tmp/ai-client-repl.log
```

**Important:** Do not use `lein repl :start` directly. The startup script uses the correct profile and initialization sequence.

### Restart After Disconnect

If the AI client REPL crashes or you restart it:

1. **Stop any existing REPL:**
   ```bash
   ./dev/stop-ai-client.sh
   ```

2. **Start fresh REPL:**
   ```bash
   ./dev/start-ai-client-repl.sh
   ```

3. **Rejoin your game** (if mid-game):
   ```bash
   ./send_command join "<game-id>" "<side>"
   ./send_command resync "<game-id>"
   ```

---

## Core Concepts

### Game Structure
- **Sides:** Corp (defending servers) vs Runner (attacking servers)
- **Resources:** Credits (money), Clicks (actions per turn)
- **Servers:** Corp installs cards in servers (HQ, R&D, Archives, Server 1-5)
- **Runs:** Runner attacks servers to access cards

### Turn Flow
1. Start turn (gain clicks, Corp draws mandatory card)
2. Take actions (play cards, install, take credits, make runs)
3. End turn (all clicks must be spent)

### Prompts
Many actions create prompts requiring responses:
- Mulligan (keep or redraw starting hand)
- Run phases (continue, jack out, break ICE)
- Card access (steal, trash, or leave)
- Discard to hand size (select cards to trash)

---

## Command Reference

### Connection & Lobby Management

**Create a new game:**
```bash
./send_command create-game "AI Test Game" "Runner" "worlds-2012-a"
```
- `<title>` - Game title (default: "AI Test Game")
- `[side]` - Side preference: "Corp", "Runner", or "Any Side" (default: "Any Side")
- `[precon]` - Preconstructed deck (default: "worlds-2012-a")

**Start game after opponent joins:**
```bash
./send_command start-game
```

**Leave current game:**
```bash
./send_command leave-game
```

**List available games:**
```bash
./send_command list-lobbies
```

**Join a game:**
```bash
./send_command join <game-id> <side>
# Example:
./send_command join "1806d5c9-f540-4158-ab66-8182433dcf10" Runner
```

**Rejoin after REPL restart:**
```bash
# After REPL restart, you lose session state and are removed from the game.
# Use this two-step process to rejoin:
./send_command join <game-id> <side>    # Step 1: Re-add to players list
./send_command resync <game-id>          # Step 2: Get full game state

# Example:
./send_command join "373031fa-d7a2-4f7c-8bfd-9cc7c0d3fc4f" "Runner"
./send_command resync "373031fa-d7a2-4f7c-8bfd-9cc7c0d3fc4f"
```

**Why both commands?**
- The AI client doesn't persist session state (unlike web browser with cookies)
- REPL restart = complete disconnect, server removes you from players list
- `join` re-adds you to the game's active players
- `resync` retrieves the current game state

**How to know if you need to rejoin:**
- Check the game log: `./send_command log`
- If you see `"<Your-username> has left the game."` → you need to **join + resync**
- If no disconnect message → just **resync** is enough

**Resync only (if still connected):**
```bash
# If WebSocket connected but game state is stale:
./send_command resync <game-id>
```

**Manual WebSocket reconnect:**
```bash
./send_command connect
```

---

### Game State & Intelligence

**Show game status:**
```bash
./send_command status
```
Shows: turn number, active player, your credits/clicks/hand size, waiting indicators

**Show full board state:**
```bash
./send_command board
```
Shows:
- Corp servers with installed ICE (rezzed/unrezzed, type when rezzed)
- Corp content (assets, agendas, upgrades)
- Runner rig (programs with strength, hardware, resources)
- Deck and discard pile counts

**Show hand:**
```bash
./send_command hand
```

**Show current prompt:**
```bash
./send_command prompt
```
Displays prompt message, type, available choices with indices

**Show credits/clicks:**
```bash
./send_command credits
./send_command clicks
```

**Show Corp Archives:**
```bash
./send_command archives
```
Shows faceup and facedown cards in Archives

**Show game log:**
```bash
./send_command log        # Last 20 entries
./send_command log 50     # Last 50 entries
```

**Look up card information:**
```bash
./send_command card-text "Tithe"
```
Shows: card type, subtype (crucial for ICE types: Sentry/Barrier/Code Gate), cost, strength, card text

**Show installed card abilities:**
```bash
./send_command abilities "Smartware Distributor"
```
Shows ability indices and descriptions to avoid wrong ability selection

---

### Turn Management

#### Turn Flow

**IMPORTANT:** The game pauses at the end of each player's turn to allow paid abilities and rez actions.

**How to recognize you're waiting to start your turn:**
- Both players have 0 clicks
- No active prompt
- Last log entry: `"<Opponent> is ending their turn..."`
- Status shows: `"Waiting for <opponent> to act"` (misleading - actually waiting for YOU)

**You must explicitly start your turn** - it doesn't start automatically.

**Start turn:**
```bash
./send_command start-turn
```
- Corp: Draws 1 card (mandatory), gains clicks (usually 3)
- Runner: Gains clicks (usually 4), no mandatory draw

**Indicate paid ability:**
```bash
./send_command indicate-action
```
Signal you want to use a paid ability during a priority window (pauses the game for you to act). Use this during opponent's turn end or other timing windows when you need to rez ICE, use card abilities, etc.

**End turn:**
```bash
./send_command end-turn
```
Validates all clicks are spent. Triggers discard-to-hand-size if needed.

---

### Basic Click Actions

**Click for credit:**
```bash
./send_command take-credit
```
Spend 1 click, gain 1 credit

**Draw card:**
```bash
./send_command draw
```
Spend 1 click, draw 1 card

---

### Mulligan & Hand Management

**Keep starting hand:**
```bash
./send_command keep-hand
```

**Mulligan (redraw):**
```bash
./send_command mulligan
```

**Discard to hand size:**
```bash
./send_command discard
```
Auto-detects your side, discards from end of hand to reach maximum (usually 5 cards)

---

### Card Actions

**Play card by name:**
```bash
./send_command play "Sure Gamble"
```
Plays events (Runner) or operations (Corp)

**Play card by index:**
```bash
./send_command play-index 0
```
Index: 0-4 for cards in hand

**Install card:**
```bash
# Runner (no server needed):
./send_command install "Daily Casts"
./send_command install-index 0

# Corp (must specify server):
./send_command install "Palisade" "HQ"         # Protect HQ
./send_command install "Palisade" "New remote" # Create new remote server
./send_command install "PAD Campaign" "HQ"     # Install in HQ root
```

Corp server targets:
- `"HQ"`, `"R&D"`, `"Archives"` - Install ICE protecting central servers or assets in server root
- `"New remote"` - Create new remote server
- `"Server 1"`, `"Server 2"`, etc. - Existing remote servers

**Use installed card ability:**
```bash
./send_command use-ability "Smartware Distributor" 0
```
- `<name>` - Installed card name
- `<N>` - Ability index (use `abilities` command to see indices)

**Trash installed card:**
```bash
./send_command trash "Telework Contract"
```

**Rez card (Corp only):**
```bash
./send_command rez "Palisade"
```
Pay rez cost to flip ICE/asset/upgrade faceup

**Advance card (Corp only):**
```bash
./send_command advance "Project Atlas"
```
Spend 1 click and 1 credit to place advancement counter (for scoring agendas)

**Score agenda (Corp only):**
```bash
./send_command score "Project Atlas"
```
Score agenda when it has sufficient advancement counters

**Important Scoring Rules:**
- ⚠️ **Corp turn only** - Cannot score on Runner's turn (prevents "bait and score" strategies)
- ⚠️ **Does NOT cost a click** - Scoring happens at the end of a click action (e.g., after final advance)
- This means Corp can install-advance-advance-score a 3-cost agenda in one turn (3 clicks)
- Some agendas have scoring effects that trigger prompts (e.g., "Send a Message" offers optional ICE rez)

---

### Run Mechanics

#### Initiating a Run

```bash
./send_command run "R&D"
./send_command run "HQ"
./send_command run "Archives"
./send_command run "Server 1"
```

**CRITICAL: Server Name Format**
Server names MUST match game format exactly:
- ✅ `"R&D"` (with ampersand and exact capitalization)
- ❌ `"rd"` or `"RD"` (will fail)
- ✅ `"HQ"`, `"Archives"`
- ✅ `"Server 1"`, `"Server 2"`, etc. (with space, title case)

#### Run Phase Sequence

A run is NOT a single action - it's a sequence of phases requiring responses:

**1. Initiation Phase**
```bash
./send_command run "R&D"
# Receive prompt: "Continue to Approach Server"
# Phase indicator: "Initiation"
```

**2. Approach ICE (if present)**
```bash
./send_command continue
# Corp gets opportunity to rez ICE
# Runner waits for Corp action
```

**ICE Positions (0-indexed):**
- Position 0 = outermost ICE (encountered first)
- Position 1 = second ICE from outside
- Position N = innermost ICE (encountered last)
- Game log shows: "approaches ice protecting HQ at position 0"

**3. Encounter ICE (if rezzed)**
Runner uses icebreakers to break subroutines:

**Option A: Full Break (auto-break)**
```bash
./send_command use-ability "Unity" 2  # "Fully break Whitespace"
./send_command continue               # Done breaking
```

**Option B: Partial Break (manual subroutine selection)**
```bash
./send_command use-ability "Unity" 0  # "Break 1 Code Gate subroutine"
# Prompt appears: "Break a subroutine"
# Choices:
#   0. [Subroutine 0 text]
#   1. [Subroutine 1 text]
#   2. Done
./send_command choose 0               # Break sub 0
./send_command choose 1               # Done breaking
./send_command continue               # Pass priority
```

**4. Corp fires unbroken subroutines**
```bash
./send_command fire-subs "Whitespace"
```

**5. Approach Server (if run not ended)**
```bash
./send_command continue
# Final continue before accessing cards
```

**6. Access Cards (breach)**
Prompts appear for each accessed card:
```bash
./send_command choose-value steal     # Steal agenda
./send_command choose-value trash     # Trash card (pay trash cost)
./send_command choose-value "no action"  # Leave card
```

**Jacking Out**

⚠️ **CRITICAL: Jack-out timing is restricted!**

You can ONLY jack out **AFTER passing ICE** (between pieces of stacked ICE), NOT during approach or encounter.

Legal jack-out windows:
- ✅ After passing ICE #1, before approaching ICE #2
- ✅ After passing all ICE, before accessing server
- ❌ During approach-ice phase
- ❌ During encounter-ice phase

```bash
# Example: Tactical jack-out after taking damage
./send_command run "R&D"
./send_command continue                    # Approach ICE #1
./send_command continue                    # Encounter ICE #1 (takes 4 net damage)
# ICE fires, you take damage but survive
./send_command continue                    # Pass ICE #1
# Now approaching ICE #2 which might flatline you
./send_command jack-out                    # ✅ Legal - save yourself!
```

**Common mistake:** Trying to jack out when you see dangerous ICE during approach. You MUST encounter it first, let subs fire, THEN jack out if still alive.

**Potential automation:** Jack-out legality could be checked automatically based on run phase:
- `run.phase` = "approach-ice" or "encounter-ice" → jack-out ILLEGAL
- `run.phase` = "movement" (between ICE) → jack-out LEGAL
- Server should enforce this, but worth validating before sending command

#### ICE Breaking Examples

**Full Break (Unity vs Whitespace - Code Gate):**
```bash
./send_command run HQ
./send_command continue                    # Approach
./send_command continue                    # Encounter
./send_command use-ability "Unity" 2       # Fully break Whitespace
./send_command continue                    # Done breaking
./send_command continue                    # Proceed to access
```

**Partial Break (break only dangerous subs):**
```bash
./send_command run HQ
./send_command continue                    # Approach
./send_command continue                    # Encounter
./send_command use-ability "Unity" 0       # Break 1 subroutine
./send_command choose 0                    # Break sub 0
./send_command choose 1                    # Done (let sub 1 fire)
./send_command continue                    # Pass priority
# Corp fires remaining subs (likely ends run)
```

**Type Mismatch (code gate breaker vs barrier ICE):**
```bash
./send_command run "Server 1"
./send_command continue                    # Approach
# Corp rezzes Palisade (Barrier)
./send_command continue                    # Can't break with Unity (Code Gate breaker)
# Corp fires subs, run likely ends
```

**Key Insight:** Always use `card-text` command to check ICE type before running:
```bash
./send_command card-text "Tithe"
# Output: ICE - Sentry - AP
# Conclusion: Need sentry breaker, not barrier/code gate breaker
```

---

### Damage Mechanics

The Runner can take three types of damage:

**Net Damage:**
- Common source: Accessing traps and certain agendas
- Effect: Runner discards N cards at random from hand
- If unable to discard enough cards, Runner flatlines (loses game)
- Example: Urtica Cipher deals 2 net damage on access

**Meat Damage:**
- Common source: Corp operations and certain card abilities
- Effect: Identical to net damage (random discard)
- If unable to discard enough cards, Runner flatlines (loses game)

**Brain Damage:**
- Common source: Specific ICE subroutines and Corp cards
- Effect: Random discard PLUS permanent max hand size reduction
- Each point of brain damage permanently reduces max hand size by 1
- If unable to discard enough cards, Runner flatlines (loses game)

**Key Differences from End-of-Turn Discard:**
- Damage = **random discard** (no player choice)
- End-of-turn = **chosen discard** (player selects which cards)
- Damage can cause flatline if hand is empty
- Commands handle damage automatically (cards are randomly trashed)

---

### Prompts & Choices

**Choose by index:**
```bash
./send_command choose 0
./send_command choose 1
```
Use when you know the exact option number

**Choose by value (semantic selection):**
```bash
./send_command choose-value keep
./send_command choose-value steal
./send_command choose-value "jack out"
```
More intuitive, matches text partial and case-insensitive

**Continue/pass priority:**
```bash
./send_command continue
```
Used in:
- Paid ability windows
- Run phases (between approach/encounter/access)
- Passing when no actions to take

---

## Common Workflows

### Game Start Sequence

```bash
# Create game
./send_command create-game "Test Game" "Runner"

# Wait for opponent to join, then start
./send_command start-game

# Handle mulligan
./send_command keep-hand
# or
./send_command mulligan
```

### Basic Runner Turn

```bash
./send_command start-turn

# Play economy card
./send_command play "Sure Gamble"

# Install program/resource
./send_command install "Daily Casts"

# Spend remaining clicks
./send_command take-credit
./send_command take-credit

./send_command end-turn
```

### Basic Corp Turn

```bash
./send_command start-turn
# (Automatically drew mandatory card)

# Install ICE
./send_command install "Palisade" "HQ"

# Install agenda
./send_command install "Project Atlas" "New remote"

# Spend remaining clicks
./send_command take-credit

./send_command end-turn
```

### Making a Run

```bash
# Check board state first
./send_command board
./send_command card-text "Tithe"  # Check ICE type if unknown

# Start run
./send_command run "R&D"

# Respond to prompts
./send_command continue           # Pass initiation
./send_command continue           # Approach ICE
# (Corp rezzes or continues)
./send_command use-ability "Cleaver" 0  # Break if barrier
./send_command choose 0           # Select sub to break
./send_command choose 1           # Done breaking
./send_command continue           # Pass priority

# Access cards
./send_command choose-value steal # Steal agenda if found
```

### Corp Scoring Agenda

```bash
# Advance agenda (repeat 3x for 3/2 agenda)
./send_command advance "Project Atlas"
./send_command advance "Project Atlas"
./send_command advance "Project Atlas"

# Score (if sufficient advancement)
./send_command score "Project Atlas"
```

---

## Technical Notes

### Server Name Format
Always use exact game format:
- **Central servers:** `"HQ"`, `"R&D"`, `"Archives"`
- **Remote servers:** `"Server 1"`, `"Server 2"`, `"Server 3"`, etc.
- **Install targets:** `"New remote"` to create new remote

### Timing
Commands are sent to a persistent REPL maintaining WebSocket connection. Allow 1-2 seconds for server responses between commands in rapid sequences.

### UUID Handling
Game IDs are UUIDs. Copy exact format from `list-lobbies` output:
```
1806d5c9-f540-4158-ab66-8182433dcf10
```

### Error Recovery
If connection drops:
```bash
./send_command connect
./send_command resync <game-id>
```

### Advanced Usage
Execute arbitrary Clojure in the AI client REPL:
```bash
./send_command eval '(println "Debug:" @ws/client-state)'
```

---

## Command Quick Reference

| Category | Command | Args |
|----------|---------|------|
| **Lobby** | create-game | title [side] [precon] |
| | start-game | - |
| | leave-game | - |
| | list-lobbies | - |
| | join | game-id side |
| | resync | game-id |
| **State** | status | - |
| | board | - |
| | hand | - |
| | prompt | - |
| | credits | - |
| | clicks | - |
| | archives | - |
| | log | [N] |
| | card-text | name |
| | abilities | name |
| **Turn** | start-turn | - |
| | indicate-action | - |
| | end-turn | - |
| **Basic** | take-credit | - |
| | draw | - |
| **Cards** | play | name |
| | play-index | N |
| | install | name [server] |
| | install-index | N [server] |
| | use-ability | name N |
| | trash | name |
| | rez | name |
| | advance | name |
| | score | name |
| **Runs** | run | server |
| | jack-out | - |
| | fire-subs | name |
| **Prompts** | choose | N |
| | choose-value | text |
| | continue | - |
| | keep-hand | - |
| | mulligan | - |
| | discard | - |

---

**Last Updated:** 2025-11-09

This reference reflects the current implementation. For game rules and card mechanics, see the complete Netrunner mechanics documentation.
