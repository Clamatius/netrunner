# Runner Level 1 - Coordination & Runs

You are Runner in Netrunner. Level 1 tests your ability to coordinate with the Corp agent.

## Prerequisites
- Level 0 PASS (basic tooling works)
- Game already started, both sides kept hands

## Your Goal (Level 1)
Prove you can:
1. Detect when it's your turn
2. Execute a run and handle all phases
3. Wait for Corp to respond during runs
4. Handle prompts (break ICE, access cards)

## The Coordination Dance

**Critical concept:** Runs require back-and-forth with Corp.

When you run:
1. You initiate the run
2. Corp gets priority windows (may rez ICE)
3. You encounter ICE (may need to break)
4. Corp fires unbroken subroutines
5. You access cards (may steal/trash)

If Corp doesn't respond to their priority windows, the game hangs.
If you don't respond to break/access prompts, the game hangs.

## Command Reference

```bash
# Check if you need to act
./dev/send_command runner prompt      # Shows current prompt (if any)
./dev/send_command runner status      # Shows whose turn, clicks remaining

# Wait for something to happen
./dev/send_command runner wait-for-relevant-diff 30   # Waits up to 30s

# Run commands
./dev/send_command runner run "HQ"       # Start run on HQ
./dev/send_command runner continue       # Pass priority / continue run
./dev/send_command runner use-ability "Mayfly" 0   # Use breaker to break ICE

# Respond to prompts
./dev/send_command runner choose <N>         # Choose option N
./dev/send_command runner choose-value steal # Choose by text match
```

## The Agent Loop

```
LOOP:
  1. Check `prompt` - is there something to respond to?
     - If yes: handle the prompt (break ICE, access choice, etc.)
     - If no: continue to step 2

  2. Check `status` - is it my turn with clicks remaining?
     - If yes: take an action (run, install, credit)
     - If no: continue to step 3

  3. Wait for game state change
     - Use `wait-for-relevant-diff 30`
     - When it returns, go back to step 1
```

## Test Sequence

### Phase 1: Wait for Your Turn
Corp goes first. Wait for them:
```bash
./dev/send_command runner status          # Check if your turn
./dev/send_command runner wait-for-relevant-diff 60   # Wait if not
```

### Phase 2: Start Your Turn
When it's your turn:
```bash
./dev/send_command runner start-turn
./dev/send_command runner status          # Verify 4 clicks
```

### Phase 3: Execute a Run
```bash
./dev/send_command runner run "HQ"
```

Now you enter the run sequence. Check prompts repeatedly:

```bash
./dev/send_command runner prompt
```

Possible prompts during run:
- "Continue?" → `continue` to proceed
- "Break subroutine?" → use breaker or let it fire
- "Access [card]" → `choose-value steal` or `choose-value "no action"`

### Phase 4: Complete Run Phases

```bash
# Approach ICE (Corp may rez)
./dev/send_command runner continue

# Encounter ICE (break or let fire)
./dev/send_command runner prompt
# If ICE is rezzed, break it or continue to let subs fire

# Pass ICE
./dev/send_command runner continue

# Access server
./dev/send_command runner prompt
# Handle access: steal agenda, trash asset, or no action
```

### Phase 5: Complete Turn
```bash
./dev/send_command runner take-credit     # Use remaining clicks
./dev/send_command runner smart-end-turn
```

### Phase 6: Verify Handoff
```bash
./dev/send_command runner status
```
Check: Is `active-player` = "Corp"?

## Handling ICE

When you encounter ICE:
1. Check `prompt` - it will show the ICE and your options
2. Check `abilities <breaker>` - see what your breaker can do
3. Use breaker: `use-ability "Mayfly" 0` (break subroutine)
4. Or let subs fire: `continue` (risky - may end run or deal damage)

**Matching breakers to ICE:**
- Barrier ICE → Fracter breakers (e.g., Cleaver)
- Code Gate ICE → Decoder breakers (e.g., Unity)
- Sentry ICE → Killer breakers (e.g., Revolver)
- Any ICE → AI breakers (e.g., Mayfly) - but usually expensive

## Success Criteria

Level 1 PASS if:
1. You detected turn start correctly
2. You completed a full run (all phases)
3. You handled at least one prompt during the run
4. Turn handoff worked (Corp's turn started after you ended)

## Waiting for Corp

During your run, Corp needs to respond to priority windows. If the game seems stuck:

```bash
./dev/send_command runner prompt   # Check if YOU need to act
./dev/send_command runner log 5    # See recent game events
```

If prompt is empty and game is stuck, Corp agent may not be responding.
Post to forum for coordination help.

## Forum Reporting

```bash
$FORUM_CLI post netrunner-level-1-coordination "## Runner Level 1 Results

### Turn Detection - [PASS/FAIL]
<observations about waiting for/detecting your turn>

### Run Execution - [PASS/FAIL]
<phases completed, any stuck points>

### Prompt Handling - [PASS/FAIL]
<ICE encounters, access choices handled>

### Handoff - [PASS/FAIL]
<turn transition to Corp>

**Overall: [PASS/FAIL]**"
```
