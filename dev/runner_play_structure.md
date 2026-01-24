# Runner Play Structure

**Purpose:** Turn-by-turn execution checklist. Not strategy—mechanics.

---

## Turn Template

**Before planning, ask:**
□ Is there something URGENT I must do this turn? (Contest remote, survive threat)
□ If not, and hand < 5 cards: **Draw click 1**, then re-plan with full options.
□ Still not urgent after drawing? Consider drawing again.

The cards in your hand are known. The cards in your deck might be better.
Exception: VRcation wants you to dump hand first, then play it last click.

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

### R&D Access Decisions

**Before trashing, ask:** "If they draw this, how bad?"

- R&D trash = expensive (Corp hasn't paid to draw/install yet) + you miss the card below
- Economy in scoring remote = setup time, not an agenda
- Often better: let them draw, trash from remote later, access deeper now

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

## Before Ending Turn

□ Hand size check: Do I have 3+ cards? (Damage buffer)
□ If hand < 3 and damage ICE/traps possible: DRAW FIRST next turn
□ Credit check: Do I have 5¢? (Sure Gamble threshold)
□ Did I contest advanced remotes? (3+ counters = score next turn)
□ Did I run R&D if it's cheap? (Deny Corp draws)

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

## Common Errors

**"I'll install all my breakers now"** → Tempo loss. Install only what you need.

**"I can't break this ICE"** → Check for Bioroid click-through first.

**"The sub just does damage, I'll tank it"** → Count your hand size first.

**"I'll check the remote later"** → 3+ counters = too late next turn.

**"R&D is too expensive"** → Is it though? 1-2¢ per access is worth it every turn.

**"I need to trash everything"** → Only trash if value > cost. Leave worthless stuff.

**"I have cards, I should play them"** → Hand is options, not a to-do list. Draw instead if nothing's urgent.
