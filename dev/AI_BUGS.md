# AI Player Bugs

Bugs found during AI player development and iteration testing.

## Bug #11: No validation on start-turn - wrong player can start turn

**Status**: Found 2025-11-11

**Description**: The `start-turn` command does not validate whose turn it is. Runner was able to call `start-turn` in turn 1, when Corp should always go first.

**Steps to Reproduce**:
1. Start new game
2. Keep hands for both players
3. Call `./dev/send_command runner start-turn` (should be corp's turn)
4. Command succeeds instead of returning error

**Expected Behavior**:
- `start-turn` should check if it's the calling player's turn
- If wrong player calls `start-turn`, return error: "It's not your turn. Wait for [other-side] to complete their turn."
- Corp always goes first in turn 1

**Actual Behavior**:
- Runner can call `start-turn` when it's corp's turn
- No validation or error message
- Game enters inconsistent state (runner shows "ready to start turn" but clicks don't work)

**Impact**: Medium - Breaks basic turn order rules, causes game state desync

**Related Code**:
- `dev/src/clj/ai_actions.clj` - `start-turn!` function needs validation
- Should check `(get-in @client-state [:game-state :active-player])` against `:side`

---

## Bug #12: continue-run bypasses ICE completely - no rez prompt

**Status**: Found 2025-11-11

**Description**: The `continue-run` command auto-continues through the entire run sequence, including bypassing ALL ICE without giving corp a rez prompt. This defeats the purpose of `continue-run` which should pause at important decision points like ICE rez opportunities.

**Steps to Reproduce**:
1. Corp installs ICE on a server (e.g., Tithe on HQ)
2. Runner initiates run on that server (`run HQ`)
3. Runner calls `continue-run`
4. Observe: run goes straight to access, bypassing the ICE entirely

**Expected Behavior**:
- `continue-run` should auto-continue through paid ability windows
- `continue-run` should PAUSE at corp rez decision (to avoid information leakage about corp's credits)
- `continue-run` should PAUSE after rez (paid abilities on rez)
- `continue-run` should PAUSE at ICE encounter (paid abilities)
- `continue-run` should PAUSE at any real choice point

**Actual Behavior**:
- `continue-run` bypasses all ICE without any rez prompts
- Runner goes straight from run initiation to server access
- ICE is still installed and visible on board, just completely ignored during run

**Impact**: CRITICAL - Makes ICE completely useless, breaks core game mechanic

**Related Code**:
- `dev/src/clj/ai_actions.clj` - `continue-run!` function (lines 1743-1954)
- Current bug: Lines 1924-1945 auto-continue for BOTH runner AND corp at paid ability windows
- This bypasses corp rez decisions - sends "continue" to corp without checking for rez opportunity
- Needs to detect run phase and pause when:
  - `run-phase = :approach-ice` AND corp has rez opportunity
  - `run-phase = :encounter-ice` AND runner has break decisions
  - Runner/corp is waiting for opponent's decision (no own prompt but opponent has decision)

**Game State**:
- Game ID: 57f1fce5-6c45-423c-b6d4-d6aebff410ce
- HQ had unrezzed Tithe (ICE #0)
- Runner ran HQ, continue-run bypassed Tithe, accessed Nico Campaign from hand

---
