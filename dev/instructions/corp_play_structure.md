# Corp Play Structure

**Purpose:** Turn-by-turn execution checklist & heuristics for the Agent Player.

---
## The Golden Rule:
Card effects and board state always beat every heuristic given here, given combinations of effects.

## Operational Heuristics (Hard Constraints)

### 1. The Credit Floor (4¢)
*   **Rule**: Never end turn with < 4 credits voluntarily.
*   **Reason**: Money is threat. Poor Corp = Porous Servers.
*   **Exception**: Scoring the winning agenda point.

### 2. Action Priority (The Decision Loop)
1.  **SCORE**: If advanced agenda installed & scoreable -> Score.
2.  **EMERGENCY ECON**: If Credits < 4 -> Play `Hedge Fund` / `Government Subsidy` or `take-credit`.
3.  **DEFEND**: If Central Server (HQ/R&D) is naked -> Install ICE.
4.  **DEVELOP**: If rich (>8¢) & scoring remote exists -> Install Agenda/Asset in remote.
5.  **BUILD**: If rich (>8¢) & no remote -> Install ICE to create scoring remote.
6.  **DRAW**: If hand empty or digging for specific tool.

### 3. Installation Rules
*   **ICE**:
    * R&D and a single remote are your most important servers. 
    * If they are secure HQ is less important.
    * It's invariably a bad idea to defend more than 1 remote
*   **Agendas**: NEVER install naked (without ICE). Ideally at least 1 ICE that ends the run.
*   **Econ Assets**: Install `Drip Assets` behind light ICE, `Click Assets` in the scoring remote when poor or no agenda to score.

### 4. ICE Layering
*   Mix ICE types on important servers. Palisade + Tithe beats Palisade alone.
*   Each type forces a different breaker. Runner with only Cleaver walks through barriers but eats net damage from sentries.
*   "Doesn't ETR" ≠ useless. Taxing ICE wins the economic war—every run through ICE costs them.

---

## Turn Template

Write this out before taking actions:

```
Before turn: do I need to rez anything?
Then, MANDATORY DRAW.
NOW: Build your plan for the turn. Write it down so you can check the clicks and credits.

TURN [N] — [credits]¢, ||| clicks
Plan:
  | [action 1]     [cost]¢  → [remaining]¢
  || [action 2]    [cost]¢  → [remaining]¢
  ||| [action 3]   [cost]¢  → [remaining]¢

After turn: [credits]¢
Next turn income: +[N] from [source] → [total]¢
```

Example:
```
TURN 3 — 8¢, ||| clicks

Plan:
  | Install Brân on Remote    0¢  → 8¢
  || Install agenda behind it  0¢  → 8¢
  ||| Advance agenda           1¢  → 7¢

After turn: 7¢
Next turn income: +3 from Nico → 10¢
Can rez Brân (6¢) next turn? YES ✓
```

---

## Before Installing an Agenda

□ What ICE protects this server? List it.
□ What's the total rez cost of that ICE?
□ Do I have that many credits RIGHT NOW?
□ If no: Do I have it NEXT TURN with income?
□ If no: DON'T INSTALL THE AGENDA EXCEPT IF DELIBERATELY BLUFFING.

□ What breakers does Runner have installed?
□ Can they break my ICE? Which pieces?
□ Therefore how much does each of my ICE cost to break, and how much is the total cost to break each server?
□ If position complex, consider making a table of this to keep track, but it will go out of date on rig/server changes
□ Finally, what's Runner's credit pool?
□ Can they afford to break AND steal?
□ For subroutines don't need to be broken if the subroutine is worse than the cost to break

---

## When Runner Runs

### Approach Phase (ICE)
□ Can I afford to rez this ICE?
□ SHOULD I rez? (Consider: save money, bluff, no point if they break easily)
□ If rezzing: say the cost out loud, subtract from credits

### Access Phase
□ Trap? Trigger it.
□ Agenda? They steal. Note the score change.
□ Asset? They may trash (check trash cost vs their credits).

---

## Drawing as Corp
□ As a general rule, drawing as Corp is risky as you can draw agendas faster than you can score them
□ Every agenda in hand/play is a huge liability
□ The two usual cases: 
    □ Rich (have ICE rez costs + ~$8) and need agenda to score. Drawing last click is risky unless HQ is safer than the remote.
    □ Desperate (need ICE for a server)
□ Taking credits is a low-efficiency action but unlike the Runner, we can't simply draw for $ cards when poor

## Don't Forget

**Bioroids can be clicked through.**
- This makes bioroid "locks" economic, not absolute

**Unrezzed ICE does nothing.**
- No subs fire on unrezzed ICE
- Runner walks through for free
- The threat is only real if you CAN rez
- If Runner is attacking less important server, make sure you can afford to defend the important one

**Advancement counters are lost if stolen.**
- Agenda with 3 counters stolen = you wasted 3 clicks + 3 credits
- Don't over-advance vulnerable agendas

**Install costs for ICE:**
- First ICE on server: free
- Each additional ICE: +1¢ per existing ICE
- 3rd ICE on a server costs 2¢ to install

---

## Quick Reference: Scoring Math

| Agenda | Adv Needed | Minimum Turns | Click Pattern |
|--------|------------|---------------|---------------|
| 3/1    | 3          | 2             | Install → AAA (score) |
| 4/2    | 4          | 2             | Install+A → AAA (score) |
| 5/3    | 5          | 2             | Install+AA → AAA (score) |

A = Advance (costs 1¢ each)

**Critical insight:** A 4/2 installed and advanced once is a HUGE signal. Runner knows it's likely an agenda. Either protect it well or score fast.

---

## Run Response Checklist

```
Runner initiates run on [SERVER]

ICE at position [N]:
  □ Rezzed already? → subs will fire if unbroken
  □ Unrezzed? → Rez decision:
    - Cost: [X]¢
    - I have: [Y]¢
    - Rez? [YES/NO]

If rezzed, Runner must:
  □ Break with [breaker type] — do they have one?
  □ Pay [X]¢ to break — can they afford?
  □ Or let subs fire — survivable?
  □ Or jack out — they lose the run

Repeat for each ICE (outer to inner)

Access phase:
  □ What will they see?
  □ Agenda → stolen (update score)
  □ Trap → fire it
  □ Asset → may trash for [cost]
```

---

## Corp Run Commands (send_command Reference)

The Run Response Checklist above explains *what* to decide. This section explains *how* to execute those decisions using `send_command`.

### Decision Points During a Run

Corp gets priority at specific timing windows:
1. **Approach ICE** → Can rez ICE, use paid abilities
2. **Encounter ICE** → Can fire subroutines (if rezzed and unbroken)
3. **Approach Server** → Can rez upgrades (Manegarm, etc.)
4. **Access** → Traps fire automatically

### Command Reference

| Situation | Command | Notes |
|-----------|---------|-------|
| Pass priority (do nothing) | `continue` | Most common - just let the run proceed |
| Rez ICE on approach | `continue --rez "ICE Name"` | **Critical**: Must rez during approach, not encounter |
| Fire unbroken subs | `fire-subs` | After Runner declines to break |
| Rez upgrade (Manegarm etc.) | `continue --rez "Upgrade Name"` | On approach to server |
| Auto-handle full run | `monitor-run` | Convenience command (see caveats below) |
| Sleep until run ends | `monitor-run --fire-if-asked` | Auto-fire, auto-continue, wake only for rez |
| Fast-return check | `monitor-run --since <cursor>` | Immediately return if run already ended |

### Typical Corp Run Flow

```bash
# Runner announces: run on "Server 1"
# You have unrezzed Whitespace protecting it

# 1. Approach ICE - REZ NOW if you want to rez
./dev/send_command corp continue --rez "Whitespace"

# 2. Runner encounters, may break. Check status to see what happened
./dev/send_command corp status

# 3. If subs unbroken, fire them
./dev/send_command corp fire-subs

# 4. If run continues to server, continue or rez upgrades
./dev/send_command corp continue
```

### Common Patterns

**Let them through (poor or want them to hit trap):**
```bash
./dev/send_command corp continue   # repeat until run ends
```

**Defend with ICE:**
```bash
./dev/send_command corp continue --rez "ICE Name"
# ... Runner breaks or doesn't ...
./dev/send_command corp fire-subs   # if subs unbroken
```

**Multiple ICE (outer to inner):**
```bash
# They hit outermost first
./dev/send_command corp continue --rez "Outer ICE"
./dev/send_command corp fire-subs
# Then next ICE inward
./dev/send_command corp continue --rez "Inner ICE"
./dev/send_command corp fire-subs
```

### Alternative: monitor-run

`monitor-run` auto-passes non-decision windows and pauses for real choices.

**Basic usage:**
```bash
./dev/send_command corp monitor-run                     # Auto-pass until decision needed
./dev/send_command corp monitor-run --no-rez            # Also auto-decline all rez
./dev/send_command corp monitor-run --rez "Tithe"       # Only rez Tithe, decline others
./dev/send_command corp monitor-run --fire-unbroken     # Auto-fire when Runner signals done
```

**Sleep mode (--fire-if-asked):**
```bash
# Sleep until run ends - handles rez (if --rez specified), fire, and empty windows
./dev/send_command corp monitor-run --fire-if-asked --rez "Whitespace"
```
This combination pre-specifies the rez decision, auto-fires when Runner signals, and only wakes up when the run completes. Ideal for AI-vs-AI play.

**Fast-return (--since):**
```bash
# Get cursor from previous call
cursor=$(./dev/send_command corp monitor-run --fire-if-asked | grep cursor | ...)

# Later, check if anything happened without blocking
./dev/send_command corp monitor-run --since $cursor
```
If the run already ended, returns immediately instead of blocking.

**Caveats:**
- Uses stuck-state detection instead of iteration limits (500 max as safety net)
- Always wakes for rez decisions unless --rez or --no-rez specified
- Fallback: Use manual `continue` + `fire-subs` sequence if automation fails

### Gotchas

1. **Rez timing is approach, not encounter** - If you `continue` past approach without `--rez`, ICE stays unrezzed for that encounter. You missed the window.

2. **Can't rez during encounter** - Once Runner is encountering ICE, it's too late to rez it. Plan ahead.

3. **fire-subs only works if subs are unbroken** - Check status to see if Runner broke them first.

4. **Upgrades rez on approach to server** - Manegarm Skunkworks must be rezzed as Runner approaches the server, not during ICE encounters.

5. **continue without --rez passes priority** - The ICE stays unrezzed. Runner walks through. Don't forget your rez!

---

## Common Errors

**"I'll rez it next turn"** → Runner runs THIS turn. You can't.

**"They can't break this"** → Did you check for AI breakers? Click-through?

**"The ICE will stop them"** → Is it rezzed? Do you have credits to rez?

**"I have 3 ICE on this server"** → All unrezzed? That's 0 protection.

**"They're poor, they can't run"** → They have 5¢. Your ICE costs 4¢ to break. They run.
