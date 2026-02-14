# Technical Issues from HITL Game 1

## ✅ FIXED - High Priority

### 1. Server Name Parsing Bug (Server NaN) ✅ FIXED
**Issue**: `./dev/send_command corp install "Diviner" R&D` creates a server called "R" instead of protecting R&D central.

**What happened**:
- Command: `./dev/send_command corp install Diviner R&D`
- Expected: ICE installed protecting R&D
- Actual: Created server "R" (displayed as "Server NaN" in some views)
- Game chat showed: "spends and pays 0 to install ice protecting R."

**Fix implemented**:
- Added `validate-server-name` function in ai_core.clj
- Rejects invalid server names (single letters, unrecognized patterns)
- Validates remote servers exist before allowing install
- Requires explicit "new" keyword to create new remotes
- Shows helpful error messages with existing server list

---

### 2. Overadvancement Protection ✅ FIXED
**Issue**: Successfully advanced Superconducting Hub to 4 advancement counters when it only needs 3 to score.

**What happened**:
- Turn 6: Advanced Hub from 2 → 3 → 4 counters before scoring
- This is legal (some agendas have overadvance abilities) but was unintentional

**Fix implemented**:
- Modified `advance-card!` in ai_card_actions.clj
- Blocks advancing past requirement by default
- Use `--overadvance` flag to force advancing past requirement
- Shows clear message: "⚠️  Blocked: Card already at X/Y counters (fully advanced)"

---

## ✅ FIXED - Medium Priority

### 3. Wait-for-Relevant-Diff Documentation ✅ FIXED
**Issue**: Help text makes `wait-for-relevant-diff` seem like it's only for AI-vs-AI games, but it's also useful for HITL.

**Old help text**:
```
wait-for-relevant-diff [seconds] - Wait for relevant events only (model-vs-model coordination)
```

**Fix implemented**: Updated help text to:
```
wait-for-relevant-diff [seconds] - Wait for relevant game events (ignores opponent economy)
                   Wakes on: runs starting/ending, prompts, your turn to act
                   Ignores: opponent credits, draws, minor actions
                   Works for: AI-vs-AI, HITL testing, script automation
```

---

### 4. Opponent Turn Wait Loop Race Conditions
**Issue**: Starting a wait loop after opponent has already started taking actions results in timeouts.

**What happened**:
- Started waiting for opponent turn
- Opponent took action before wait mechanism established
- Wait loop timed out (exit code 124)
- Multiple occurrences throughout game

**User note**: "that's a great example of the race I talked about - you start waiting, but I take my first action fast enough it resolves before you start"

**Current workaround**: Shorter sleep intervals and manual status checks

**Fix needed**: Better synchronization or different waiting mechanism that doesn't miss events that occurred between "turn end" and "wait start"

---

### 5. Forum Author Attribution ✅ FIXED
**Issue**: AI posts to forum appear with author "michael" instead of AI identity.

**What happened**: All forum posts during game were attributed to Michael, not to the AI player.

**Fix implemented**:
- Created `ai-corp` participant in forum
- Updated ~/.forum/token to use ai-corp auth token
- Fixed existing posts in thread (renamed files, updated frontmatter)
- Future posts from AI sessions will use correct author

---

## Low Priority / Nice to Have

### 6. Run Monitoring UX
**Issue**: `continue` vs `monitor-run` distinction could be clearer.

**What happened**: Used both `continue` and `monitor-run` during opponent runs, not always clear which was appropriate.

**Improvement**: Clearer guidance on when to use each command, or unify into single command with better auto-detection of run phases.

---

## Summary

**FIXED**: Issues #1, #2, #3, #5
**REMAINING**: Issues #4 (race conditions), #6 (run monitoring UX)

### Changes Made:
- `dev/src/clj/ai_core.clj`: Added `get-existing-remote-names`, `validate-server-name`, fixed `normalize-server-name` to return "New remote"
- `dev/src/clj/ai_card_actions.clj`: Added overadvance protection to `advance-card!`, server validation to `install-card!`
- `dev/send_command`: Updated help text for `wait-for-relevant-diff`, `advance`, `install`; added `--overadvance` flag parsing

### Tested 2026-01-04:
- Invalid server "R" → rejected with helpful error
- Non-existent "Server 5" → rejected with hint about existing remotes
- "new" keyword → creates REMOTE1 (not REMOTENEW)
- Agenda at 3/3 counters → advance blocked with message
- `--overadvance` flag → allows advancing past requirement
