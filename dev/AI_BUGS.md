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

**Status**: FIXED 2025-11-11

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

**Iteration Test Results** (2025-11-11):
- Created new game (ID: c35aa533-fc24-4047-a4a1-8736e9a3c57d)
- Installed Tithe (unrezzed) on HQ
- Runner initiated run on HQ
- Runner called continue-run 4x
- Log shows: "AI-runner approaches ice protecting HQ at position 0." → "AI-runner passes ice protecting HQ at position 0."
- **Result**: Runner bypassed ICE without corp getting rez decision
- **Status**: Bug still present after initial refactor attempt

**Fix Implementation** (2025-11-11):
- Root cause: Runner's view of ICE data is minimal (placeholder with only `:cid`, `:new`, `:side`, `:zone`)
- When unrezzed: `:rezzed` field doesn't exist in game state (not `false`, completely absent)
- When rezzed: `:rezzed` field exists and equals `true`
- Solution: Detect unrezzed ICE by checking `(not (:rezzed current-ice))` which returns `true` for unrezzed ICE
- Implementation in `dev/src/clj/ai_actions.clj` lines 1956-1992
- Detection: Check if at `:approach-ice` phase AND ICE exists at position AND ICE not rezzed

**Verification Test** (2025-11-11):
- Game ID: 3d48da8a-7118-4a5f-ab3d-cbc70a56b8c3
- Setup: Whitespace (cost 2, unrezzed) on HQ, corp has 7 credits
- Test sequence:
  1. Runner ran HQ, called `continue-run` at approach-ice
  2. ✅ Auto-continued through runner's paid ability window
  3. ✅ Paused, waiting for corp rez decision (did NOT bypass)
  4. Corp sent `continue` (declined to rez)
  5. Runner called `continue-run` again
  6. ✅ Bypassed unrezzed ICE, proceeded to movement phase
  7. Log shows: "AI-runner approaches ice" → "AI-corp has no further action" → "AI-runner passes ice"
- **Result**: continue-run correctly pauses at corp rez decision, does NOT bypass unrezzed ICE
- **Status**: BUG FIXED ✅

---

## Enhancement Requests

### Enhancement #1: Command error handling - exit with error on failure

**Status**: Suggested 2025-11-11

**Description**: When using Unix chaining (&&) to send multiple commands at once, if a command dies (e.g., sent something out of sequence), the command should RETURN 1 (exit with error code) so the chain pauses instead of continuing to execute subsequent commands.

**Example**:
```bash
# If start-turn fails (wrong turn), this should stop, not continue
./dev/send_command runner start-turn && ./dev/send_command runner run HQ
```

**Current Behavior**: Commands may fail silently, allowing subsequent commands to execute on broken game state

**Desired Behavior**: Failed commands return non-zero exit code, stopping && chains

**Impact**: Prevents cascading errors from executing multiple commands on broken state

---

### Enhancement #2: Real-time game log visibility for AI

**Status**: Suggested 2025-11-11

**Description**: Claude Code needs continuous feed of game log to understand what's happening (or not happening) during game execution. Without this visibility, it's difficult to debug command sequences and understand game state progression.

**Proposed Solutions**:
1. Echo game log in every send-command response
2. Write game log into a hook pickup file that Claude can monitor
3. Add optional --show-log flag to send_command
4. Auto-append last N log lines to status output

**Example Issue**: When using --force to end turn prematurely, Claude didn't realize game state was broken until multiple commands later because it had no visibility into the log showing errors.

**Impact**: Improves debugging efficiency, helps Claude understand game flow

---
