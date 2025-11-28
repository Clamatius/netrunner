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

## Commit Discipline for Concurrent Development

**Philosophy:** Only commit when fixes are proven-good through testing.

**Why:** With multiple Claudes potentially working concurrently (e.g., Web Claude with $1000 credits), we need clean, stable checkpoints to avoid breaking each other's work.

**Dev Flow:**
1. **Play till bug basket full** - Queue 3-5 issues without fixing immediately
2. **Fix** - Implement solutions for queued bugs
3. **Play till fixes verified** - Test that fixes work together in realistic scenarios
4. **Commit** - Only after verification succeeds
5. **Repeat** - Next iteration cycle

**Commit Checklist:**
- [ ] All fixes tested individually
- [ ] All fixes tested together in realistic gameplay
- [ ] No regressions introduced
- [ ] Commit message documents what was fixed and verified
- [ ] Branch is in clean, working state

**Anti-Patterns:**
- ❌ Committing untested code
- ❌ Committing after fixing just one bug (test multiple fixes together)
- ❌ Committing when something is "probably working"
- ❌ Making commit messages vague or incomplete

**Good Practice:**
- ✅ Test multiple bug fixes together before committing
- ✅ Document verification steps in commit message
- ✅ Keep commits focused but complete (all related fixes in one commit)
- ✅ Maintain stable checkpoints for concurrent development

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
./dev/send_command help              # Full command reference + timing guide
```

**Turn timing quick reference available in help:**
- Corp turn sequence: gain clicks → draw → actions → discard
- Runner turn sequence: gain clicks → actions → discard
- Run sequence: initiation → approach → encounter → movement → access
- See `./dev/send_command help` "Turn Timing Quick Reference" section
- See mechanics docs and full rulebook for progressively more detail

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
   - What should have happened? (check game mechanics docs / playbooks)
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
3. Update playbooks if gameplay behavior had obvious errors from playbook sources. Be careful not to overfit to specific situations, decks or games.
4. Update this document if testing best practices discovered
5. Choose next focus area from queue

**Target:** Keep iterating until bug queue empty and gameplay feels smooth (~20 iterations expected)

---

**Last Updated:** 2025-11-27
**Current Iteration:** 4 (2 HITL games successfully completed)
