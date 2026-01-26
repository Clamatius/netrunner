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

---

## Turn Template

Write this out before taking actions:

```
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

## Common Errors

**"I'll rez it next turn"** → Runner runs THIS turn. You can't.

**"They can't break this"** → Did you check for AI breakers? Click-through?

**"The ICE will stop them"** → Is it rezzed? Do you have credits to rez?

**"I have 3 ICE on this server"** → All unrezzed? That's 0 protection.

**"They're poor, they can't run"** → They have 5¢. Your ICE costs 4¢ to break. They run.
