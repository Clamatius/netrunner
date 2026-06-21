# AI Player Game Reference

## Overview

This document covers Netrunner fundamentals and how to use the command interface. For command syntax and options, run:

```bash
./dev/send_command <side> help
```

Where `<side>` is `runner` or `corp`. The help text is the authoritative reference for all commands.

---

## Core Concepts

### Game Structure
- **Sides:** Corp (defending servers, plays first) vs Runner (attacking servers)
- **Win conditions:** 
- First to 7 agenda points (the default match format is System Gateway **Intermediate** — the 40-card booster decks — played to **7**; only the smaller Beginner base decks play to 6)
- Corp scores agenda points via advancing enough to pay cost then scoring after the last click (scoring does not require a click)
- Runner wins if Corp must draw from empty R&D
- Corp wins if Runner must discard but cannot (flatline)
- **Resources:** Credits (money, $), Clicks (actions per turn)
- **Servers:** Corp installs cards in servers (HQ, R&D, Archives, remote servers)
- **Runs:** Runner attacks servers to access cards. Agendas accessed must be stolen

### Card Zones
- **HQ:** Corp's hand, default max 5. Runner accesses random card(s) when running
- **R&D:** Corp's deck. Runner accesses top card(s) in order
- **Archives:** Corp's discard. Facedown until breached, then all flip face-up and are accessed
- **Centrals:** Collective noun for HQ, R&D and Archives
- **Remotes:** Corp-created servers for agendas, assets, upgrades. Protected by ICE
- **Rig:** Runner's installed programs, hardware, resources
- **Grip:** Runner's hand, default max 5
- **Heap:** Runner's discard

### Turn Flow
0. **Before turn start** - Both players may play effects, e.g. rez cards for Corp
1. **Start turn** - Gain clicks (Corp: 3, Runner: 4). Corp draws mandatory card. **Turns do NOT auto-start — run `start-turn` at the top of each of your turns. Until you do, you show 0 clicks / awaiting-start; that is not a stall.**
2. **Take actions** - Spend all clicks with actions.
3. **End turn** - All clicks must be spent. Discard to hand size if over limit.
- Corp: Choose face-down discards to Archives
- Runner: Choose face-up discards to Heap

### Prompts
Many actions create prompts requiring responses:
- **Mulligan:** Keep or redraw starting hand
- **Run phases:** Continue, jack out, break ICE subroutines
- **Card access:** Steal agenda, trash card (pay cost), or leave
- **Discard:** Select cards when over hand size
Use `prompt` command to see current prompt and options.

### Basic Actions
Players start with a "basic action card" that grants initial abilities.

Both sides:
click: draw card from R&D (Corp) / Stack (Runner)
click: gain $1

Corp:
click: play operation
click: install card in server
click, $1 per existing ICE on server: install ICE
click, $2: trash a Runner resource if Runner tagged
3x click: purge all virus counters (entire turn)
click, $1: advance an installed card in a server

Runner:
click: install program/hardware/resource
click: play event
click: initiate a run on a server
click, $2: remove a tag

Tags represent that the Corp knows something about the Runner and so can use conditionals based on that.

### Card Wording
- Effects are worded as <cost>: <effect>
- You must pay the cost, e.g. if you need to spend a click, you must have one remaining
- As much effect as can happen happens, e.g. if you lose $2 and have $1, you lose $1

### Conditionals
- Many cards have a conditional clause about when you can play them or use the ability

### Triggers
- Many cards have a trigger "when" conditional. They must happen if possible

---

## Run Timing

Runs are multi-phase sequences, not single actions.

### Phase Summary
After each phase, both players get a window to use triggered abilities (active player first)
Then, both players get a window to use paid abilities (active player first)

1. **Initiation** - Runner declares server, run begins
2. **Approach ICE** - Corp may rez ICE (if unrezzed)
3. **Encounter ICE** - Runner breaks subroutines or lets them fire
4. **Movement** - Pass ICE, approach next ICE or server
5. **Access** - Runner accesses cards in server
6. **Run Ends** - Cleanup

### ICE Positions
- Position 0 = outermost ICE (encountered first)
- Position N = innermost ICE (encountered last)
- Runner must pass ALL ICE to reach server

### Jack-Out Timing
**Critical:** Jack-out is ONLY legal during Movement phase (after passing ICE).
- ✅ After passing ICE, before approaching next ICE
- ✅ After passing all ICE, before accessing server
- ❌ During approach or encounter phases

### Subroutines
Each ICE has subroutines (→ symbols). Unbroken subs fire and resolve top-to-bottom. Examples:
- **End the run** - Most common. Stops the run.
- **Do N damage** - Runner discards cards randomly. 0 cards + damage = flatline (Runner loses).
- **Lose credits / gain tags / etc** - Various effects.

Breakers must match ICE type (Barrier→Fracter, Code Gate→Decoder, Sentry→Killer).

---

## Synchronization

### The Cursor Pattern

When waiting for opponent actions, use the cursor to avoid race conditions:

```bash
# Get current state cursor BEFORE your action
CURSOR=$(./send_command runner get-cursor)

# Take your action (smart-end-turn handles discard-to-hand-size; end-turn needs --force if clicks remain)
./send_command runner smart-end-turn

# Wait for state to advance past that point
./send_command runner wait --since $CURSOR
```

The `--since` flag makes `wait` return immediately if the game state already advanced past that cursor (e.g., the opponent already acted). Without it, you might wait full timeout for something that just happened.

### What `wait` Wakes On

`wait` always wakes on the full set of things any player needs to care about — the wake set is NOT configurable, because waiting for *less* is a footgun (you'd miss a run started during 'wait my turn', etc.). It wakes on:
- An opponent `ping` chat message (explicit "look over here")
- A prompt for us (decision required — encounter, rez window, access, etc.)
- A run starting (even if you were just waiting for your turn — Corp must participate)
- A run ending
- It becomes your turn to act
- The game ends (match over) — `wait` wakes immediately on game-over instead of hanging the full timeout; stop acting, run `game-over-status`, tear down
- Timeout (default 300s)

It is silent on opponent economy/draws/installs — those don't require you to act.

### Tips
- LLM opponent turns are SLOW — a single Opus turn can run several minutes. Default timeout is 300s and that is usually the right value. Do not lower it.
- A timeout expiring is NOT proof the opponent is stuck. Before concluding deadlock: send `chat "ping"`, then re-issue `wait --since $CURSOR`. Only after a second full timeout with no log activity should you assume something is actually broken.
- During a run you initiated, the Corp gets paid-ability windows for rez/fire-subs decisions — these can take a while. Stay patient; do not jack out preemptively just because waiting is slow.
- During the opponent's run against you (Corp side), use `monitor-run` to participate — it auto-handles priority windows and surfaces real decisions. `wait` alone is NOT enough during runs against you. For an autonomous Corp seat, prefer `monitor-run --persistent`: it owns the whole run with one command, sleeping through empty priority windows and waking only for a real rez/fire/access decision or run end (so you don't re-issue `monitor-run` through every symmetric pass-priority window).
- Humans use the Jinteki web UI, not send_command
- For AI-vs-AI, both sides should use cursor pattern

---

## Technical Notes

### Server Name Format
Always use exact format:
- **Central servers:** `"HQ"`, `"R&D"`, `"Archives"`
- **Remote servers:** `"Server 1"`, `"Server 2"`, etc.

### Timing
Commands go through a persistent REPL with WebSocket connection. Allow 1-2 seconds between rapid commands for server responses.

### Game IDs
UUIDs from `list-lobbies`. Example: `1806d5c9-f540-4158-ab66-8182433dcf10`

### Error Recovery
```bash
./send_command connect              # Reconnect WebSocket
./send_command resync <game-id>     # Rejoin game in progress
```
Both are required

### Debug Mode
```bash
AI_DEBUG_LEVEL=true ./send_command runner status
```
Shows internal WebSocket messages for debugging.

### If confused on what to plan
```bash
./send_command dashboard-compact
```
Shows the default plan for the heuristic-based player.
