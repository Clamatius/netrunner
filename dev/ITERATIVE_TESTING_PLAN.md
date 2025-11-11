# Iterative Testing Plan for AI Player Development

## Philosophy: Play-Till-Bug Strategy

**Goal:** Polish the AI player experience through rapid iteration cycles.

**Approach:**
1. **Play** - Execute gameplay scenarios until friction/bugs are discovered
2. **Queue** - Document issues in a bug queue (don't fix immediately)
3. **Stop** - When queue has ~3-5 items or critical blocker found
4. **Polish** - Implement fixes for queued issues
5. **Test** - Verify fixes and add any new issues to queue
6. **Repeat** - Iterate ~20 times until experience is smooth

**Target:** ~20 iterations to reach production-ready gameplay experience

---

## Testing Commands Quick Reference

### Essential Diagnostic Commands

**Check connection/game state:**
```bash
./dev/send_command <side> status     # Game or lobby state
./dev/send_command <side> prompt     # Current prompt details
./dev/send_command <side> log        # Recent game events
```

**Before any action:**
```bash
./dev/send_command help              # Full command reference
```

### Multi-Client Testing

**Format:**
```bash
./dev/send_command runner <command> [args]
./dev/send_command corp <command> [args]
```

**Examples:**
```bash
./dev/send_command runner status
./dev/send_command corp take-credit
./dev/send_command runner run HQ
```

### Full Game Setup

**Quick start both clients:**
```bash
./dev/start-ai-both.sh               # Start Runner (7889) + Corp (7890)
./dev/stop-ai-both.sh                # Stop both clients
```

**Full game from scratch:**
```bash
./dev/ai-self-play.sh                # Automated: create + join + ready
```

---

## Debugging Stuck States

### Core Principle

> **"Almost all stuck states so far were solved via prompt commands and examining game log vs expected"**

### Diagnostic Workflow

1. **Check prompts on BOTH sides:**
   ```bash
   ./dev/send_command runner prompt
   ./dev/send_command corp prompt
   ```

2. **Examine game log for clues:**
   ```bash
   ./dev/send_command runner log | tail -20
   ./dev/send_command corp log | tail -20
   ```

3. **Compare expected vs actual:**
   - What should have happened? (check GAME_REFERENCE.md)
   - What actually happened? (check log)
   - Who is waiting on whom? (check prompts)

4. **Resolve blockers:**
   - Clear prompts with appropriate responses
   - Use `continue`, `choose`, or `choose-value` as needed
   - Check status again to verify resolution

### Common Stuck States

**Symptom:** Game appears frozen, no progress
**Diagnose:**
- Both players have 0 clicks?
- Active prompt on either side?
- Recent log shows "is ending their turn"?

**Solutions:**
- Discard prompt blocking → `choose-card` + `choose 0`
- Mulligan prompt → `keep-hand` or `mulligan`
- Paid ability window → `continue`
- Opponent must act first → wait or check their prompt

**Symptom:** Can't start turn
**Diagnose:**
```bash
./dev/send_command runner status
# Check: "Waiting to start turn" vs "Waiting for <opponent>"
```

**Solutions:**
- If opponent has blocking prompt → resolve their prompt first
- If both at 0 clicks and no prompts → `start-turn`

**Symptom:** Run command doesn't work
**Diagnose:**
```bash
./dev/send_command runner log | grep -i "run"
# Did run appear in log?
```

**Solutions:**
- Blocking prompt elsewhere → check `prompt` and resolve
- Server name wrong → check exact format (e.g., "R&D" not "rd")
- Waiting for opponent action → check corp `prompt`

---

## Testing Checklist Template

### Before Starting Iteration

- [ ] Both clients running and connected
- [ ] Fresh game created (or known game state)
- [ ] Current bug queue documented
- [ ] Focus area identified (e.g., "test auto-end-turn")

### During Iteration

- [ ] Test specific scenario (e.g., "Corp over hand size")
- [ ] Document observed behavior
- [ ] Note expected vs actual differences
- [ ] Check BOTH client perspectives (runner + corp)
- [ ] Capture relevant log entries

### After Finding Bug

- [ ] Add to bug queue with:
  - Symptom description
  - Reproduction steps
  - Affected commands/state
  - Severity (blocker / friction / nice-to-have)

### After Implementing Fix

- [ ] Test original bug scenario
- [ ] Test related scenarios (regression check)
- [ ] Update documentation if behavior changed
- [ ] Remove from bug queue if verified fixed

---

## Test Scenario Templates

### Scenario: Auto-End-Turn

**Setup:**
```bash
./dev/ai-self-play.sh
./dev/send_command corp keep-hand
./dev/send_command runner keep-hand
```

**Test:**
```bash
./dev/send_command runner start-turn
./dev/send_command runner take-credit  # 4 → 3 clicks
./dev/send_command runner take-credit  # 3 → 2 clicks
./dev/send_command runner take-credit  # 2 → 1 clicks
./dev/send_command runner take-credit  # 1 → 0 clicks
# Expected: "💡 Auto-ending turn (0 clicks, no prompts)"
./dev/send_command runner status
# Expected: Turn ended, waiting for Corp to start
```

**Verify:**
- Auto-end message appears
- Turn successfully ends
- Corp can start their turn

### Scenario: Turn-Start Blocking

**Setup:**
```bash
# Get into state where Corp is over hand size
./dev/send_command corp start-turn
./dev/send_command corp take-credit  # x3 to end turn
# Corp should have discard prompt
```

**Test:**
```bash
./dev/send_command runner start-turn
# Expected: Turn start BLOCKED (opponent has active prompt)
./dev/send_command runner status
# Expected: Still waiting to start turn
```

**Verify:**
- Runner cannot start turn while Corp discarding
- Clear message about blocking
- After Corp discards, Runner CAN start turn

### Scenario: Auto-Start Lobby

**Setup:**
```bash
./dev/send_command corp create-game "Test Auto-Start"
./dev/send_command runner join <game-id> Runner
```

**Test:**
```bash
./dev/send_command corp status
# Expected: Lobby status showing 2/2 players, both with decks
# Expected: "✅ Ready to start! Use 'start-game' or 'auto-start'"

./dev/send_command corp auto-start
# Expected: "✅ Lobby ready! Auto-starting game..."
# Expected: Game starts, mulligan prompts appear
```

**Verify:**
- Lobby validation works correctly
- Auto-start only triggers when truly ready
- Game starts successfully
- Both players receive mulligan prompts

---

## Bug Queue Format

### Template

```markdown
## Bug #N: [Short Title]

**Severity:** [Blocker / High / Medium / Low]

**Symptom:**
[What goes wrong from user perspective]

**Steps to Reproduce:**
1. [Command or action]
2. [Expected result]
3. [Actual result]

**Affected Commands:**
- `command-name`

**Proposed Fix:**
[Brief description of solution approach]

**Status:** [Queued / In Progress / Testing / Fixed]
```

### Example

```markdown
## Bug #3: Discard Prompt Blocks Game

**Severity:** Blocker

**Symptom:**
When Corp ends turn over hand size, discard prompt appears but cannot be completed. Runner cannot act. Game stuck.

**Steps to Reproduce:**
1. Corp draws extra cards during turn
2. `./dev/send_command corp end-turn` (auto-ends despite over hand size)
3. Corp sees "Discard down to 5 cards" prompt
4. `./dev/send_command corp choose-card 0` + `choose 0` doesn't work
5. Selectable cards show `title: null`
6. Runner blocked from starting turn

**Affected Commands:**
- `choose-card`
- `start-turn` (blocked)

**Proposed Fix:**
1. Investigate why selectable cards lack :title field
2. Add turn-start blocking when opponent has active :select prompt
3. Fix discard completion flow

**Status:** Fixed (turn-start blocking) / Investigating (discard completion)
```

---

## Pro Tips

### Efficiency Tips

1. **Use help liberally:**
   ```bash
   ./dev/send_command help | grep -i <keyword>
   ```

2. **Chain diagnostics:**
   ```bash
   ./dev/send_command runner status && \
   ./dev/send_command runner prompt && \
   ./dev/send_command runner log | tail -10
   ```

3. **Background log monitoring:**
   ```bash
   tail -f /tmp/ai-client-runner.log &
   tail -f /tmp/ai-client-corp.log &
   ```

### Testing Philosophy

- **Test both perspectives:** Always check runner AND corp status/prompts
- **Read the log:** Game log is ground truth for what actually happened
- **Trust the system:** Server-side game state is authoritative
- **Batch bugs:** Don't fix immediately - queue 3-5 issues then batch-fix
- **Iterate quickly:** Small fixes are better than perfect solutions

### Common Mistakes

❌ **Don't:**
- Fix bugs one-by-one without queuing
- Test only one client's perspective
- Assume status message is accurate (check prompts!)
- Skip reading game log when stuck

✅ **Do:**
- Queue bugs until you have ~5 items
- Check BOTH runner and corp state
- Trust game log over status messages
- Use `prompt` command to see actual game state

---

## Iteration Log Template

Track progress across iterations:

```markdown
## Iteration N - [Date] - [Focus Area]

**Starting Bug Queue:** N items
- Bug #X: [title]
- Bug #Y: [title]

**Testing Performed:**
- [Scenario tested]
- [Result]

**New Issues Found:**
- Bug #Z: [title] (Severity)

**Fixes Implemented:**
- Bug #X: [description]
- Commit: [hash]

**Ending Bug Queue:** N items

**Next Focus:** [Area to test next iteration]
```

---

## Next Steps

After each iteration:

1. Update bug queue
2. Commit fixes with clear messages
3. Update GAME_REFERENCE.md if gameplay behavior changed
4. Update this document if testing best practices discovered
5. Choose next focus area from queue

**Target:** Keep iterating until bug queue empty and gameplay feels smooth (~20 iterations expected)

---

**Last Updated:** 2025-11-11
**Current Iteration:** 1 (auto-end-turn testing complete)
