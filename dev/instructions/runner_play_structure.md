# Runner Play Structure

**Purpose:** Turn-by-turn execution checklist & heuristics for the Agent Player.

---

## The Golden Rule
The cards and board always beat every rule given here, given combinations of effects. 

## Operational Heuristics (Hardest Constraints)

### 1. The Credit Floor (3¢)
*   **Rule**: Never run or install if it leaves you with < 3 credits.
*   **Reason**: You need money to steal (trash upgrades, pay for ICE breaking, etc)
*   **Priority**: If < 3 credits, your ONLY priority is **Economy** cards, `take-credit` worst case.

### 2. Just-in-Time Rig
*   **Rule**: Do not install things unless you have a use for them
*   **Exceptions**:
    *   You are rich (> 10 credits).
    *   You are about to run a dangerous face-down server and need safety.
    *   You are preparing a specific "Go Turn" (e.g., Deep Dive/Docklands).
*   **Waste**: Installing `Carmen` (Killer) when Corp has no Sentries is a waste of tempo.

### 3. Action Priority (The Decision Loop)
1.  **WIN**: If less than 3 points to win and remote/R&D is open -> RUN.
2.  **ECONOMY**: If < 5 credits -> Play economy events / `take-credit`.
3.  **PRESSURE**: If rich (> 6 credits) -> Run weak servers (R&D/HQ). Facecheck if necessary for info.
4.  **BUILD**: If locked out -> Install **needed** breakers only.
5.  **DRAW**: If hand empty or digging for breakers.

---

## Ballpark Credit Model

Credit = $1 - basic unit
$0 - so broke can't even play econ cards. Often difficult to dig out of here without clicking for credits
$5 - enough to play Sure Gamble again for +$4 - but can't install breakers and credibly run without it
$10 - threatening run on most servers - especially with run events, can crack big servers.
NOTE: Losing the actual threat can lead to Corp scoring since you cannot run the remote.
Due to this, being poor is usually awful unless you win on the spot.
Comparable to Chess, your effective assessed position is:
- $ (flexible)
- Currently KNOWN useful cards with enough $ to install and/or use (sometimes required)
- [discounted] threat to do things, e.g. cards in hand could be unknown breakers for corp

Typical unrezzed budgets for breakables: $5 / unrezzed ICE, $4 / unrezzed asset / upgrade

## Turn Template

**Before planning, ask:**
□ Is there something URGENT I must do this turn? (Contest remote, survive threat)
□ If not, and hand < 5 cards: **Draw click 1**, then re-plan with full options.
□ Still not urgent after drawing? Consider drawing again.
Exception: some cards lose you a click if you play them. They cost you positionally ~$1.5 unless you play last click.

The cards in your hand are known. The cards in your deck might be better.
You frequently don't need to install much if you _make_ Corp rez ICE and 
tell you what you need / where is weak. Hidden info makes calculation difficult,
so where possible reveal it for future planning and probe for weaknesses.
Running unrezzed servers defending something unknown always tells you something, but not empty ones.

Important plan inputs:
□ If hand < 3 and damage ICE/traps possible: draw if possible
□ Credit check: Do I have 5¢? (Sure Gamble threshold) If not in order consider:
- use econ cards if helpful
- draw for more econ
- click for credit (generally worst action but sometimes unavoidable)
□ Do I contest advanced remotes? (1+ counters = potential 2+ point score next turn)
□ Do I run R&D if it's cheap? (Deny Corp draws)
□ Do I need to use clicks on a known Bioroid ICE this turn?
□ HQ is generally only worth running if:
- Corp hasn't scored in 2+ turns, e.g. via threatened remote lockdown / previous score
- Corp is agenda flooded at game start - e.g. plays little or no ICE

**Then write out your plan:**

```
TURN [N] — [credits]¢, |||| clicks

Plan:
  | [action 1]     [cost]¢  → [remaining]¢
  || [action 2]    [cost]¢  → [remaining]¢
  ||| [action 3]   [cost]¢  → [remaining]¢
  |||| [action 4]  [cost]¢  → [remaining]¢

After turn: [credits]¢
Hand size: [N] cards
```

Example:
```
TURN 4 — 8¢, |||| clicks

Plan:
  | Draw                    0¢  → 8¢  (hand 3→4)
  || Install Cleaver        3¢  → 5¢
  ||| Run R&D               1¢  → 4¢  (Unity break)
  |||| Run R&D              1¢  → 3¢  (if Corp drew)

After turn: 3¢
Hand size: 3 cards (played 1)
```
Use tallies to accurately count clicks.

---

## Before Running

### Step 1: Can I get in?

□ What ICE protects this server? List each piece.
□ For each ICE:
  - Type? (Barrier/Code Gate/Sentry)
  - Do I have the right breaker?
  - If no breaker: Can I click through? (Bioroid only)
  - If no breaker and not bioroid: Can I survive letting subs fire?

### Step 2: Can I afford it?

□ Calculate total break cost:
```
ICE 1: [name] - [cost]¢ to break
ICE 2: [name] - [cost]¢ to break
...
TOTAL: [sum]¢

My credits: [N]¢
Enough? [YES/NO]
```

□ If remote: Add potential trash cost (+3-5¢ buffer)
□ Do I have enough credits AFTER the run to survive/continue?
□ Make a note of how much it costs you to break each piece of ICE you know

### Step 3: Is it worth it?

□ What will I access?
  - R&D: Top of deck (always somewhat valuable), _ordered_
  - HQ: Random from hand (valuable if Corp holding agendas)
  - Remote: The installed card(s)
  - Archives: All cards, flipped face-up first

□ If remote has advancement counters:
  - 0 counters: Could be asset, upgrade, or never-advance agenda
  - 1-2 counters: Is agenda in progress or advanceable trap. Agendas: 1 counter: likely 4adv/2pts. 2 counters: likely 5adv/3pts.

□ R&D being _ordered_ means when you run R&D, you are seeing cards the Corp will draw unless you steal or trash.
□ If you see the top card and don't trash it, it will still be right there if you run R&D again this turn.
□ The cards have to go somewhere so ICE you see in R&D or HQ will probably be installed soon etc.
□ If the Corp is not scoring for a while (maybe because you can run the remote), there may be agendas in HQ that they are scared to install.

### Trash decisions

**Before trashing, ask:** "If they draw this, how bad?"

- Best: only trash if you can run again easily to get next cord: add mental $2 to R&D trash costs, $1 to R&D to represent draw & install
- If you don't trash, corp is now guaranteed to draw non-agenda and now next card may be agenda again if you access

---

## ICE Encounter Checklist

When you hit ICE, in order:

### 1. Identify
□ ICE name and type (Barrier/Code Gate/Sentry/Multi)
□ Is it a Bioroid? (Check for "Bioroid" subtype)
□ How many subroutines?

### 2. Decide: Break or Let Fire?

**If Bioroid:**
□ Can spend clicks to break (1 click per sub)
□ Clicks remaining: [N]
□ Subs to break: [N]
□ Click through? [YES/NO]

**If not Bioroid (or choosing to pay):**
□ Breaker type needed: [Killer/Fracter/Decoder/AI]
□ Do I have it installed?
□ Break cost: [N]¢
□ Can afford? [YES/NO]

**If can't break:**
□ What do the subs do? (Read each one)
□ Survivable?
  - ETR: Run ends, I'm fine but no access
  - Net damage [N]: Need [N] cards in hand
  - Trash program: Lose a breaker (BAD)
  - Gain credits/tags: Usually survivable
□ Let subs fire? [YES/NO]

### 3. Execute
□ Break subs OR let them fire
□ Continue to next ICE or server

---

## Breaking ICE - Command Guide

### Breaker Types Must Match ICE Types

| ICE Type   | Breaker Type | Example Breakers |
|------------|--------------|------------------|
| Barrier    | Fracter      | Cleaver, Paperclip |
| Code Gate  | Decoder      | Unity, Gordian Blade |
| Sentry     | Killer       | Carmen, Bukhgalter |

**AI breakers** (like Mayfly) can break any ICE type, but usually have a drawback (trash after use).

**Critical rule:** A Fracter cannot break Code Gates. A Killer cannot break Barriers. If you run with the wrong breaker, you cannot break the ICE.

### Ability Indices During Encounters

When you encounter ICE, use `abilities "<breaker>"` to see available actions:

```bash
./send_command abilities "Cleaver"
```

**Outside a run (or wrong ICE type):**
```
[0] Break up to 2 Barrier subroutines  (1¢)
[1] Add 1 strength                      (2¢)
```

**During encounter with matching ICE type:**
```
[0] Break up to 2 Barrier subroutines  (1¢)
[1] Add 1 strength                      (2¢)
[2] Fully break Palisade               (1¢)   ← USE THIS
```

**Key insight:** If ability [2] doesn't appear, either:
1. You're not in an encounter phase, OR
2. Your breaker can't break this ICE type, OR
3. You can't afford the full break cost

### The Recommended Workflow

**ALWAYS use the "Fully break" ability [2] when available** (unless you want some subs to fire).

```bash
./send_command run "R&D"
./send_command continue                    # Approach ICE
./send_command continue                    # Encounter ICE
./send_command use-ability "Cleaver" 2     # Fully break
./send_command continue                    # Done breaking, proceed
```

### Manual Breaking (Situational)

When you only want to break some subroutines:

```bash
./send_command use-ability "Mayfly" 0      # "Break 1 subroutine"
./send_command choose 0                     # Break first sub
./send_command choose 1                     # Done (let remaining fire)
./send_command continue                     # Pass priority
```

---

## Keep Your Options Open

**Hand size is decision space, not just damage buffer.**

- 1-2 cards: Corp knows what you CAN'T do. Limited options.
- 3-4 cards: Functional but constrained.
- 5 cards: Maximum threat. Corp must respect all possibilities.

**Signs you're playing your hand as a to-do list:**
- Installing cards "because I can" rather than "because I need to"
- Hovering at 1-2 cards for multiple turns
- Never clicking to draw when you "have stuff to do"

**The fix:** If nothing is urgent, draw first. Once you SEE the better cards, you'll know what to do with them.

---

## Don't Forget

**Bioroids can be clicked through.**
- 1 click breaks 1 subroutine
- Brân 1.0: 3 clicks to fully break (vs 8¢ with Cleaver)
- Check clicks remaining before deciding
- ⚠️ **Clicks spent DURING the encounter, not banked across turns**
- Can't click through Brân #1, then click through Brân #2 on same run—each encounter is fresh

**Conditional subroutines exist.**
- "End the run IF Runner has 6¢ or less" - stay above threshold
- "Do X IF Runner is tagged" - no tag = no effect
- Read the sub before breaking - might not need to

**⚠️ Same R&D card = Corp hasn't drawn. STOP.**
- See same card twice? **Stop running R&D immediately.**
- You're wasting clicks seeing the same card.
- Wait for Corp's mandatory draw next turn.
- Switch to HQ or remote pressure.

**Unrezzed ICE might not get rezzed.**
- Corp might be poor
- Run forces them to pay or let you through
- Info either way

**Damage needs hand buffer.**
- Net damage = random discard
- Hand size 0 + damage = FLATLINE (you lose)
- Keep hand ≥ expected damage + 2

**Traps scale with advancement.**
- Check hand size vs worst case before accessing

---

## Quick Reference: Break Costs

Keep a running tally as ICE is rezzed:

```
SERVER: R&D
  ICE 1: Whitespace (Code Gate) - Unity 1¢
  ICE 2: [unrezzed]
  TOTAL KNOWN: 1¢

SERVER: Remote 1
  ICE 1: Brân 1.0 (Bioroid Barrier) - 3 clicks OR Cleaver 8¢
  ICE 2: Palisade (Barrier) - Cleaver 3¢
  TOTAL: 3 clicks + 3¢  OR  11¢
```

Update when:
- New ICE rezzed
- You install new breakers
- ICE is trashed

---

## Run Response Quick Reference

```
Corp installs in remote:
  └─ 0 counters → Low priority (probably asset)
  └─ 1+ counters → Medium priority (building something)
  └─ 3+ counters → HIGH PRIORITY (scorable/lethal)

Corp advances existing card:
  └─ Count total counters
  └─ 4+ counters on 4/2 = SCORES NEXT CLICK
  └─ Contest NOW or accept the points loss

Corp has scored 4-5 points:
  └─ EVERY remote is potentially game-winning
  └─ Contest everything you can afford
```

---

## Damage Mechanics

Your hand size is your health. Damage causes random discards—if you must discard but can't, you flatline and lose.

**Damage Types (all work the same for Runner):**
- **Net Damage:** From traps (Urtica Cipher), ICE, certain agendas
- **Meat Damage:** From tag-punishment operations (Orbital Superiority)
- **Core Damage:** Random discard PLUS permanent -1 max hand size (avoid!)

**Key Differences from End-of-Turn Discard:**
- Damage = **random discard** (no player choice)
- End-of-turn = **chosen discard** (player selects which cards)

**Survival Rule:** Hand size must be > expected damage + 2 buffer

**Example:**
```
Planning run on server with potential trap (Urtica Cipher at 2 adv = 4 damage)
Current hand: 3 cards
Safe hand size: 4 + 2 = 6 minimum

Action: Draw 3 cards first, THEN run
```

---

## Common Errors

**"I'll install all my breakers now"** → Tempo loss. Install only what you need.

**"I can't break this ICE"** → Check for Bioroid click-through first.

**"The sub just does damage, I'll tank it"** → Count your hand size first.

**"I'll check the remote later"** → 3+ counters = too late next turn.

**"R&D is too expensive"** → Is it though? 1-2¢ per access is worth it every turn.

**"I need to trash everything"** → Only trash if value > cost. Leave worthless stuff.

**"I have cards, I should play them"** → Hand is options, not a to-do list. Draw instead if nothing's urgent.
