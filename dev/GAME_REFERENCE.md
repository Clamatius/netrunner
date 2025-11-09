# AI Player Game Reference

## Overview

This document describes the command interface for controlling the AI player in Netrunner via the `send_command` tool. It covers all available commands, game mechanics, and common workflows.

**Usage:**
```bash
./dev/send_command <command> [arguments]
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

**Rejoin disconnected game:**
```bash
./send_command resync <game-id>
```

**Manual reconnect:**
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

**Start turn:**
```bash
./send_command start-turn
```
- Corp: Draws 1 card (mandatory), gains clicks (usually 3)
- Runner: Gains clicks (usually 4), no mandatory draw

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
End run at any time (before passing point of no return):
```bash
./send_command jack-out
```

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
