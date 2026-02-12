# Corp Play Structure

**Purpose:** Turn-by-turn execution checklist & heuristics for the Agent Player.

---
## The Golden Rule:
Card effects and board state always beat every heuristic given here, given combinations of effects.

## Operational Heuristics (Hard Constraints)
Netrunner is won mostly on relative credit efficiency.

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
*   **Econ Assets**: Install `Drip Assets` as bait or when no agenda to score, `Click Assets` in the scoring remote when poor.

### 4. ICE Layering
*   Mix ICE types on important servers. Palisade + Tithe beats Palisade alone.
*   Each type forces a different breaker. Runner with only Cleaver walks through barriers but eats net damage from sentries.
*   "Doesn't ETR" ≠ useless. Taxing ICE wins the economic war—every run through ICE costs them.

---

## Turn Template

Work through these phases **in order** after mandatory draw.

### Phase 0: PRE-TURN
□ Do I need to rez anything before my turn starts?
□ MANDATORY DRAW. (Then plan with what you have.)
□ Have a future plan for every card. In mid/late game the plan being nothing helps passive HQ defense for 0¢

### Phase 1: SCORE CHECK
□ Agenda installed and scoreable? → **Score it.** Nothing else matters.

### Phase 2: SURVIVAL
□ Credits below floor (4¢)? → Emergency econ: events / assets in ICEd remote / take-credit
□ Central server (HQ/R&D) naked? → Install ICE.
□ Can I afford to rez my ICE if Runner runs my most important server? If not, get credits.

### Phase 3: DEVELOP (only if Phase 2 is clear)
□ **Before installing an agenda, check:**
  - Can I rez the protecting ICE/Upgrades RIGHT NOW or next turn?
  - What breakers does Runner have? Can they break my ICE?
  - What's their credit pool?
  - If no to rez costs: DON'T INSTALL unless deliberately bluffing.
□ Rich (>8¢) and remote protected? → Install agenda/asset, advance.
□ Expensive (>5¢ cost) cards to rez/play? Try to have a plan to play the high EV ones
□ No remote? → Build one (ICE first, then install behind it).

**Then write out your turn plan and any multi-turn plan updates:**

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

## When Runner Runs

### Approach Phase (ICE)
□ Can I afford to rez this ICE?
□ SHOULD I rez? (Consider: save money, bluff, no point if they break easily)
□ If rezzing: say the cost out loud, subtract from credits
□ After last ICE broken, rez any upgrades

### Access Phase
□ Trap? Trigger it.
□ Agenda? They steal. Note the score change.
□ Asset/Upgrade? They may trash (check trash cost vs their credits).

---

## Drawing as Corp
□ As a general rule, drawing as Corp is risky as you can draw agendas faster than you can score them
□ Every agenda in hand/play is a huge liability
□ The two usual cases: 
    □ Rich (have ICE rez costs + ~5¢) and need agenda to score. Drawing last click is risky unless HQ is safer than the remote.
    □ Desperate (need ICE for a server)
□ Taking credits is a low-efficiency action but unlike the Runner, we can't simply draw for ¢ cards when poor

## Don't Forget

**Bioroids can be clicked through.**
- This makes bioroid "locks" economic, not absolute

**Unrezzed ICE does nothing.**
- No subs fire on unrezzed ICE
- Runner walks through for free
- The threat is only real if you CAN rez
- If Runner is attacking less important server, make sure you can afford to defend the important one

**Advancement counters are lost if stolen.**
- Agenda with 3 counters stolen = you wasted 4 clicks + 3 credits
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

**Critical insight:** A 4/2 installed and advanced once is a HUGE signal. Runner knows it's likely an agenda. Either protect it well or score fast. Cards that can advance agendas are therefore important since they let you bluff important agendas as assets.

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
  □ Etiquette: don't fire subroutines unless Runner indicates ok

Repeat for each ICE (outer to inner)

Access phase:
  □ What will they see?
  □ Agenda → stolen (update score)
  □ Trap → fire it
  □ Asset → may trash for [cost]
```

---

## Corp Run Commands

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
| Rez upgrade on approach to server | `continue --rez "Upgrade Name"` | On approach to server |
| Auto-handle full run | `monitor-run` | See `help` for full flags (--rez, --fire-if-asked, --since) |

For each ICE (outer to inner): `continue --rez` on approach → Runner encounters → `fire-subs` if unbroken → repeat. To let them through: just `continue` repeatedly.

### Gotchas

1. **Rez timing is approach, not encounter** — `continue` past approach without `--rez` = ICE stays unrezzed. You missed the window.
2. **Upgrades rez on approach to server** — Manegarm must be rezzed as Runner approaches the server, not during ICE encounters.
3. **`continue` without `--rez` passes priority** — Runner walks through. Don't forget your rez!

---

## Common Errors

**"I'll rez it next turn"** → Runner runs THIS turn. You can't.

**"They can't break this"** → Did you check for AI breakers? Click-through?

**"The ICE will stop them"** → Is it rezzed? Do you have credits to rez?

**"I have 3 ICE on this server"** → All unrezzed+unaffordable? That's 0 protection.

**"They're poor, they can't run"** → They have 2¢. Your ICE costs 4¢ to break. They click for credits and run, even without cards in hand.

**"I shouldn't install, the remote isn't safe"** → Force the Runner to run and it to cost as much as possible. They will access sometimes. If you don't score quickly enough, HQ will become unsafe or R&D will give up too many points - sometimes you must trade an expensive steal to get your points.
