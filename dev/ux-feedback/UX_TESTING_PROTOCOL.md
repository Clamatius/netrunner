# UX Testing Protocol for AI Player CLI

## Purpose

Play games against heuristic AI or subagents to discover friction points in the CLI interface. Each session should result in actionable feedback for improving the player experience.

## Goals

### 1. Minimize Noise
- No useless info or redundant headers
- No extra tokens that don't add value
- Compact output that fits terminal width

### 2. Right Info at Right Time
- **First encounter**: Show full card text (player learning the card)
- **Subsequent**: Show shorter reminder text (player knows the card)
- Context-appropriate details (don't show Runner info when Corp is acting)

### 3. Minimize Turns Required
- Only interrupt for **meaningful choices**
- Auto-continue through "OK" confirmations where possible
- Batch similar items (e.g., multiple untrashable accesses → single response)
- Each API turn is expensive in wall time and compute

## Examples of Turn Waste

**Bad**: Access 3 operations, press OK three times
```
📋 You accessed Hedge Fund. [Operation]
   → No action (cannot trash)
📋 You accessed IPO. [Operation]
   → No action (cannot trash)
📋 You accessed Restructure. [Operation]
   → No action (cannot trash)
```

**Good**: Batch untrashables until a real choice appears
```
📋 Accessed 3 cards (no trash options):
   • Hedge Fund [Operation]
   • IPO [Operation]
   • Restructure [Operation]
```

## Known Issues (Document as you find them)

| Issue | Severity | Workaround |
|-------|----------|------------|
| Overclock credit selection | Medium | Use `choose-card Overclock` for each credit needed |

## Test Scenarios

### Basic Flow
1. Mulligan decisions
2. Click economy (credits, draw)
3. Installing cards
4. Advancing/scoring agendas

### Run Flow (High Priority)
1. Initiating runs
2. Approach/encounter ICE phases
3. Breaking with icebreakers
4. Letting subs fire (tank scenarios)
5. Access phase (single/multi-access)
6. Successful run triggers

### Opponent Interaction
1. Waiting for opponent actions
2. Paid ability windows
3. Turn transitions

## Session Template

```markdown
# UX Feedback Session - YYYY-MM-DD

## Setup
- Player side: Runner/Corp
- Opponent: heuristic-corp/heuristic-runner/subagent
- Decks: [deck names]

## Summary
[1-2 sentence result]

## Friction Points

### [Title]
**Context**: [What were you trying to do]
**Problem**: [What went wrong or was confusing]
**Actual output**: [Copy relevant CLI output]
**Expected**: [What should have happened]
**Severity**: High/Medium/Low
**Suggested fix**: [If obvious]

---

## What Worked Well
- [List positives]

## Proposed Fixes (Priority Order)
1. [HIGH] ...
2. [MEDIUM] ...
3. [LOW] ...
```

## Running a Test Session

### Setup
```bash
# Start fresh game
make reset

# Keep hands
./dev/send_command corp keep-hand
./dev/send_command runner keep-hand

# Start heuristic opponent (if testing against bot)
./dev/ai-eval.sh corp 7890 "(do (require 'ai-heuristic-corp) (ai-heuristic-corp/start-autonomous!))"
# OR
./dev/ai-eval.sh runner 7889 "(do (require 'ai-heuristic-runner) (ai-heuristic-runner/start-autonomous!))"
```

### During Play
- Note friction points as they occur
- Copy exact CLI output for bug reports
- Try different command variations
- Test edge cases when you encounter them

### After Session
1. Write up findings in `dev/ux-feedback/session-YYYY-MM-DD.md`
2. Prioritize issues by impact
3. Create tasks for HIGH priority items
