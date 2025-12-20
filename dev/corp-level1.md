# Corp Level 1 - Coordination & Priority

You are Corp in Netrunner. Level 1 tests your ability to coordinate with the Runner agent.

## Prerequisites
- Level 0 PASS (basic tooling works)
- Game already started, both sides kept hands

## Your Goal (Level 1)
Prove you can:
1. Detect when it's your turn
2. Respond to priority windows during Runner's turn
3. Wait efficiently without deadlocking

## The Coordination Dance

**Critical concept:** You must act during BOTH players' turns.
- Your turn: take actions (install, advance, play operations)
- Runner's turn: respond to priority windows (rez ICE, pass priority)

**During Runner's runs, you will get prompts to:**
- Rez ICE when Runner approaches
- Fire subroutines when Runner can't break
- Pass priority to let run continue

If you don't respond, the game hangs.

## Command Reference

```bash
# Check if you need to act
./dev/send_command corp prompt      # Shows current prompt (if any)
./dev/send_command corp status      # Shows whose turn, clicks remaining

# Wait for something to happen (efficient polling)
./dev/send_command corp wait-for-relevant-diff 30   # Waits up to 30s, wakes on run/prompt

# Respond to prompts
./dev/send_command corp continue    # Pass priority / continue run
./dev/send_command corp rez <name>  # Rez ICE when prompted
./dev/send_command corp choose <N>  # Choose option N from prompt
```

## The Agent Loop

```
LOOP:
  1. Check `prompt` - is there something to respond to?
     - If yes: handle the prompt (rez, continue, choose)
     - If no: continue to step 2

  2. Check `status` - is it my turn with clicks remaining?
     - If yes: take an action
     - If no: continue to step 3

  3. Wait for game state change
     - Use `wait-for-relevant-diff 30`
     - When it returns, go back to step 1

  4. Before acting on any event, RE-CHECK `prompt`
     - The state may have changed since you woke up
     - Don't respond to a run that already ended
```

## Test Sequence

### Phase 1: Turn Detection
```bash
./dev/send_command corp status
```
Check: Is `active-player` = "Corp"? Do you have clicks?

### Phase 2: Execute a Turn
If it's your turn:
```bash
./dev/send_command corp take-credit   # or install ICE
./dev/send_command corp take-credit
./dev/send_command corp take-credit
./dev/send_command corp smart-end-turn
```

### Phase 3: Wait for Runner
```bash
./dev/send_command corp wait-for-relevant-diff 60
```
This should wake when:
- Runner starts a run (you may need to rez)
- Runner ends turn (your turn starts)

### Phase 4: Priority Response
When Runner runs, check `prompt`:
```bash
./dev/send_command corp prompt
```

If prompt shows rez window:
```bash
./dev/send_command corp continue    # Pass (don't rez)
# OR
./dev/send_command corp rez "Tithe" # Rez the ICE
```

### Phase 5: Verify Turn Handoff
After Runner ends turn, verify:
```bash
./dev/send_command corp status
```
Check: Is `active-player` = "Corp"? Is `turn` incremented?

## Success Criteria

Level 1 PASS if:
1. You detected turn transitions correctly
2. You responded to at least one priority window during Runner's run
3. No deadlocks occurred (game didn't hang)
4. `wait-for-relevant-diff` woke on events (not just timeout)

## Race Condition Warning

**Before responding to any event, always re-check `prompt`.**

The Runner might have queued multiple actions. You might wake up to "run started" but by the time you check, the run is already over. Always verify the current state before acting.

```bash
# WRONG:
wait-for-relevant-diff 30
# Woke up! Must be a run!
rez "Tithe"  # ERROR: No run in progress

# RIGHT:
wait-for-relevant-diff 30
prompt       # Check what's actually happening
# If prompt shows rez window, THEN rez
```

## Forum Reporting

Post results to the forum thread when done.

```bash
$FORUM_CLI post netrunner-level-1-coordination "## Corp Level 1 Results

### Turn Detection - [PASS/FAIL]
<observations>

### Priority Response - [PASS/FAIL]
<observations about responding during Runner's run>

### Wait Efficiency - [PASS/FAIL]
Did wait-for-relevant-diff wake early on events? Or exhaust timeout?

### Handoff - [PASS/FAIL]
<turn transition observations>

**Overall: [PASS/FAIL]**"
```
