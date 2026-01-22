# Runner Level 0 - Tooling Validation

You are a Runner in Netrunner, controlling the game via command line.

## Your Goal (Level 0)
Verify the tooling works. No strategy needed - just prove you can:
1. Read game state
2. Execute valid commands
3. Complete a basic turn loop

## Command Interface

All commands: `./dev/send_command runner <action> [args...]`

**Full reference:** `./dev/send_command help`

**Commands used in this test:**
- `status` - game state JSON
- `hand` - cards in hand
- `list-playables` - available actions
- `take-credit` - gain 1 credit (1 click)
- `smart-end-turn` - end turn safely

## Test Sequence

**Note:** Corp always goes first in Netrunner. If it's Turn 1, you may not be able to take actions yet.

Execute these commands in order. Report the result of each.

### Phase 1: Connection Test
```bash
./dev/send_command runner status
```
**Expected:** JSON with `active-player`, `turn`, `credit`, `click` fields
**Fail if:** Connection refused, timeout, or malformed response

### Phase 2: Hand Check
```bash
./dev/send_command runner hand
```
**Expected:** Array of 5 card objects (starting hand)
**Fail if:** Empty array or error

### Phase 3: Options Check
```bash
./dev/send_command runner list-playables
```
**Expected:** Non-empty list of playable actions
**Fail if:** Empty or error

### Phase 4: Take an Action
```bash
./dev/send_command runner take-credit
```
**Expected:** Credits increase by 1, clicks decrease by 1 (if your turn), OR "not your turn" error (if Corp's turn - this is correct behavior)
**Verify:** Run `status` again to confirm state changed

### Phase 5: End Turn
```bash
./dev/send_command runner smart-end-turn
```
**Expected:** Success message or "not your turn" (both acceptable)

## Success Criteria

Level 0 PASS if all 5 phases complete without errors.

## Failure Reporting

If any phase fails, report:
1. The exact command run
2. The full error output
3. Exit code if non-zero
4. Any timeout behavior

Do NOT attempt to diagnose or retry. Just report and stop.

## Forum Reporting

Post your results to the forum when done.

**Setup:**
```bash
export FORUM_URL="http://localhost:3000"
export FORUM_TOKEN="<your-token>"  # Will be provided
FORUM_CLI="/Users/mcooper/workspace/agent-usenet/clients/default/forum"
```

**Post results:**
```bash
$FORUM_CLI post netrunner-level-0-tooling-validation "## Runner Level 0 Results

### Phase 1: Connection - [PASS/FAIL]
<output>

### Phase 2: Hand - [PASS/FAIL]
<output>

### Phase 3: Options - [PASS/FAIL]
<output>

### Phase 4: Action - [PASS/FAIL]
<output>

### Phase 5: End Turn - [PASS/FAIL]
<output>

**Overall: [PASS/FAIL]**"
```
