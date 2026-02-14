# Runner Playbook

**Purpose:** Strategic guidance for Runner play in Netrunner. Assumes you know the rules (see `netrunner-complete-mechanics.md`) and commands (see `send_command help`).

**Core objective:** Find 7 agenda points before Corp scores 7 (6s in tutorial game). Do this through volume of accesses.

**Golden Rule:** Netrunner is a dynamic game and boardstate can override rules, never mind strategies - so strats here are heuristics at best.

## Fundamental Principles

### The Click Economy

**Clicks are your most valuable resource.** Credits can be gained; clicks cannot by default.

**Click efficiency ratings:**
- 1 click → 1 credit = **BAD** (basic action, last resort)
- 1 click → 4 credits = **GOOD** (Sure Gamble)
- 1 click → 3 cards = **EXCELLENT** (Diesel)
- 1 click → multiple accesses = **EXCELLENT** (running)

**The 2-click tax:** Every card costs ~2 clicks (1 to draw, 1 to install/play). Every card must provide more than 2 clicks of value.

**Sure Gamble as the unit of credit measure:** Every deck plays 3x Sure Gamble, because 5→9 on turn 1 is too good not to. Total exchange is burst 2:1 clicks:credits. Money cards are usually burst or drip (slow rewards). It is important to distinguish which is which.

### Conditions Reward Sequencing

**Many cards have "if/when/first time" triggers.** Before acting, ask: can I order my clicks to enable discounts, maximize triggers, or dodge thresholds?

**Common patterns:**
- **"If you made a successful run this turn..."** (Carmen) → Run a free server first, then install at discount
- **"The first time each turn..."** (Docklands Pass) → Make that first breach count; don't waste it on low-value access
- **"If the Runner has 6 credits or less..."** (Whitespace) → Enter with >9 credits to avoid the ETR
- **"Whenever you make a successful run..."** (Pennyshaver) → Run before clicking to cash out, so you collect the bonus

**The difference between "run then install" and "install then run" is often 2+ credits.** Read the trigger, then order your clicks.

### The Statistical Reality

**You need ~mean 17 *random* accesses to win with 7 points (tutorial less)** (varies by agenda density and luck).

This means:
- Runner has inevitable late-game advantage (volume wins)
- Corp is racing against this statistical clock
- Your goal: Complete rig while applying relentless pressure to keep corp from scores and make them have to spend $ on rezzes → win through volume
- Every agenda you steal from remotes decreases the total expected random accesses
- Agendas are always somewhere - if the Corp has drawn many cards but not scored, HQ is more likely to have them
- Accessing R&D denies the Corp drawing an agenda on their mandatory draw without you stealing it first, because you access the top of their deck in draw order

---

## Rig Building Priority

**Essential components (install in this order):**

1. **Economy** (Turn 1-2)
   - Drip pays off over time - the earlier the better
   - You take a tempo hit to install, so front-load it

2. **First breaker** (Turn 2-3)
   - Tempo hit to install so ideally only install the revealed type needed
   - Lets you start running protected servers
   - Don't wait for "perfect" rig
   - Killer typically protects you from damage, Fracter and Decoder from End The Run

**Sandbagging breakers:** Consider keeping breakers in hand instead of installing immediately.
- Corp sees empty rig → thinks remote is safe → installs agenda → you surprise-install and steal
- Mayfly + Overclock from hand = 6¢ to crack almost any single-ICE remote
- AI breakers (Mayfly) are brutal surprise plays: raw credits → stolen agenda
- Tradeoff: risk losing sandbagged breakers to net damage (Diviner, traps)
- Best against glacier Corps building "safe" remotes; worse against damage-heavy Corps

3. **Complete breaker suite** (Turn 3-5)
   - Must have all 3 types covered (one breaker per ICE type) to ensure access given enough $

4. **Multi-access tools** (Turn 6+)
   - Typically expensive (3+ credits) so a tempo hit to install
   - Only install after complete rig + stable economy
   - Requirement: 10+ credits, all breakers installed

---

## The Draw Decision

**Early game (Turns 1-5): Draw for rig pieces**

```
Missing breakers? → Draw until you find them
Missing economy? → Draw until you find Sure Gamble
Have rig + economy? → Stop drawing, start running
```

**Pattern:** Draw before installing (see more options before committing).

Example:
```
Hand: Cleaver, Daily Casts, Overclock (3 cards)
Plan: Install 2 cards this turn

Better: Draw first (see 4 cards), THEN install best 2
Worse: Install immediately (might draw better cards after)
```

**Mid game (Turns 6-10): Draw selectively**

```
if need_specific_card AND useful_cards_in_stack > 50%:
    draw
else:
    run_instead
```

**Late game (Turns 10+): Probability poker**

Simple heuristic: `if useful_cards / stack_size > 0.5: draw, else run`

### The Millstone Trap

A common beginner error: **treating cards you'll never see as different from cards that don't exist.**

**The trap:** "I'll only play Wildcat Strike when my hand is nearly empty, so if I draw 4, I won't have to discard."

**The reality:** Discarding from overdraw is **filtering, not loss**. Those discarded cards are statistically equivalent to cards at the bottom of your deck - cards you were never going to draw. The difference:
- You now have **information** (you know what's gone)
- You had **choice** (you picked what to discard)

Waiting for "perfect" hand size is pure downside. You delayed a strong card for a "cost" that isn't real.

**The heuristic:** Cards you were never going to draw don't exist. Overdraw filtering is free. A turn of draw-draw-draw-draw, discard 4 is completely legitimate if those were the right draws.

**Application to multi-access:** You're not "wasting" accesses on non-agendas. You're gaining previously hidden info. Each non-agenda access from R&D increases agenda density in what's left and means when they drew it, you know they didn't draw an agenda. Each card accessed from R&D or HQ has to go _somewhere_.

**Survival draws (against damage decks):**

Always draw before running if facing damage:
```
Damage per run: 2 (from ICE/traps)
Runs planned: 2
Current hand: 3
Minimum safe: 2 + 2 = 4

Draws needed: 4 - 3 = 1 draw before running
```

**Rule:** Hand size must be > expected damage + 2 buffer.

---

## Running Decisions

### Should I Run This Server?

**Pre-run checklist:**

1. **Do I have breakers for all ICE types?**
   - Check board for rezzed ICE
   - Code Gate? Need Unity
   - Barrier? Need Cleaver
   - Sentry? Need Carmen
   - Missing type? Don't run OR accept letting subs fire

2. **Can I afford break costs + trash costs?**
   - Calculate: ICE break costs + potential trash (3-5 credits)
   - Keep credit buffer for surprises if possible
   - Have a plan for after the run
   - Can't afford? Build economy first

3. **What's the expected value?**
   - R&D: Always valuable (random agenda access)
   - HQ: Valuable if Corp drew many cards (see Drawn estimate)
   - Remote with 3+ counters: Must contest (scoreable agenda)
   - Remote with 0 counters: Unknown (could be anything)

### ICE Breaking

**Type matching is mandatory:**
**Always check ICE type first:**
```
./send_command card-text "Palisade"
Output: ICE - Barrier
Conclusion: Need Cleaver to break
```

**Bioroid bypass (critical optimization):**

Bioroid ICE allows spending clicks instead of credits, e.g.:
```
Brân 1.0 (Bioroid, 3 subs):
- Break with Cleaver: 8 credits
- Bypass with clicks: 3 clicks (1 per sub for full break)

Almost always bypass: Clicks are cheaper than 8 credits unless you are [effectively] very rich for the run
```

**When to break instead of bypass:**
- Insufficient clicks to spend but need access
- Have free credits (Overclock, temporary funds)

**Subroutines:**
- Some subroutines may have a trigger that is not met (runner below $x, runner tagged, etc) and since they have no effect you do not need to break them
- Some subroutines, especially on Sentries, can inflict damage or trash programs (not in tutorial)

### Server Priority

**Early game (Turns 1-5):**
1. HQ runs (force ICE rezzes, check for agendas in hand)
2. Facecheck remotes (deny economy assets)
3. R&D if desperate (Corp hasn't drawn much yet)

**Mid game (Turns 6-10):**
1. R&D multi-access (primary win condition)
2. Remote when obvious (Corp advancing = must contest)
3. HQ pressure (keep them honest)

**Late game (Turns 10+):**
1. R&D relentlessly (statistical inevitability)
2. Ignore heavily-defended remote unless affordable
3. HQ only if R&D is impossible

### HQ Dynamics

**HQ value is dynamic** - it depends on whether Corp can safely install agendas.

**HQ is empty when:**
- Corp is aggressively installing and advancing (agendas go to remotes as drawn)
- You just stole from remotes (Corp's hand was drained to refill the remote)
- Corp has weak/no remote ICE and keeps trying anyway

**HQ is full when:**
- You credibly threaten the remote (breakers + credits ready)
- Corp is scared to install because you'll steal
- Agendas pile up in hand waiting for a safe scoring window

**The play pattern:**
1. Build rig that threatens remote (breakers + credits)
2. Corp sees threat, holds agendas instead of installing into certain death
3. Agendas accumulate in HQ
4. Hit HQ before Corp finds a scoring window (more ICE, econ burst, etc.)

**Key insight:** HQ pressure is a *consequence* of remote threat, not a substitute for it. Make the remote scary first, then cash in on the flooded HQ.

**After early remote steals:** R&D is usually richer than HQ. Corp draws agendas to install them, not hold them. If you just stole from a remote, Corp's hand is likely ICE/econ, not more agendas. Diversify to R&D or set up instead of hammering empty HQ.

---

## Agenda Tracking

Your status display shows real-time agenda tracking:
```
Agenda Points: 0 / 7  │  Missing: 18 (Drawn: ~8, HQ: 5, R&D: 29, Remotes: 2/1)
```

**What it means:**
- `Missing: 18` - Unaccounted agenda points (total - scored - stolen)
- `Drawn: ~8` - Expected agenda points drawn from R&D (turn-based estimate)
- `HQ: 5` - Cards in Corp hand (hidden, could contain agendas)
- `R&D: 29` - Cards remaining in deck
- `Remotes: 2/1` - Unrezzed remote cards / cards with advancement counters

**Strategic use:**

**Expected Draw Math:**
```
Drawn: ~8 agenda points expected in drawn cards
Scored: 2 (Corp score area)
Stolen: 3 (your score area)
Unaccounted: 8 - 2 - 3 = 3 points

Where are those 3 points?
- In HQ (stuck in hand)
- In remotes (facedown)
- In Archives facedown
```

**Remote Interpretation:**
```
Remotes: 0/0  → No remote pressure, focus R&D/HQ
Remotes: 3/0  → 3 never-advance cards (agenda/asset/trap unknown)
Remotes: 2/1  → 1 card has counters (likely agenda or trap)
Remotes: 1/3  → 1 card with 3+ counters (URGENT: scoreable or lethal)
```

**Advancement counter signals:**
- **0 counters (never-advance):** Could be agenda (Seamless Launch threat), asset, or trap
  - Corp can score without advancing using fast-advance tools
  - Check if suspicious (multiple turns idle, Corp at scoring range)
  - They have to have the fast-advance and use it now if it's an agenda
- **1-2 counters:** Probably 3-cost agenda (needs 1 more turn) or building trap
  - Corp invested tempo, not an idle asset
  - Contest before it reaches 3+ counters
- **3+ counters:** Scoreable agenda OR lethal trap
  - Corp heavily committed (3+ clicks spent)
  - Contest NOW or accept they score/you risk flatline
- **5+ counters:** Extreme danger (overadvanced trap)
  - Overadvanced traps: Urtica Cipher at 5 counters = 7 net damage (2 base + 5)
  - Check hand size vs expected damage before running

**Pressure Target Selection:**
```
if Remotes: X/3+:
    contest_remote_immediately  # Scoreable agenda or lethal trap
elif Drawn > (Scored + Stolen + 4):
    pressure_HQ  # Agendas stuck in hand
elif R&D > 20 AND Missing > 10:
    multiaccess_RD  # High agenda density
else:
    default_RD_pressure  # Standard strategy
```

---

## R&D Physicality

**R&D is an ordered deck.** Cards don't shuffle between accesses. Understanding this prevents wasted runs.

**Core mechanics:**
- Top card stays on top until Corp draws (mandatory draw or click action), without you trashing or stealing it
- Corp's mandatory draw happens at turn start (before their first click)
- Click-to-draw is optional (costs 1 click)
- Trashing a card in R&D sends it to Archives face-up
- Trash costs in R&D are even more expensive than usual because the Corp didn't even have to draw the card - but if R&D access is cheap potentially allows you another access

**Practical implications:**

**Same card twice = Corp hasn't drawn:**
```
Turn 5: Access R&D → see Hedge Fund
Turn 5: Access R&D again → see Hedge Fund again [MISTAKE!]

Corp didn't draw between runs. Next access will show same card until Corp draws.
```

**Depth prediction:**
```
Access shows card at depth:
- Depth 0 (top): Corp draws it next turn (mandatory)
- Depth 1: Corp draws it turn after next
- Depth 2+: Accessible via multi-access, but not drawn soon

Exception: Corp click-to-draw, stealing, trashing accelerates this
```

**Information flow:**
```
R&D (ordered) → HQ (drawn) → Installed/Scored/Archives

When you see a card in R&D:
- If junk: Stop running R&D until Corp draws
- If agenda: Run again! Corp might draw it and score from hand
- If trap: Note position, avoid multi-access that hits it, consider trashing if rich
```

**Efficient R&D pressure:**
```
if accessed_same_card_twice:
    stop_running_RD  # Wait for Corp to draw
elif top_card_is_junk AND no_multi_access:
    pressure_HQ_instead  # Fresh cards there
elif top_card_is_agenda:
    run_repeatedly  # Deny Corp the draw
```

**Why this matters:** In our test game, we ran R&D multiple times seeing the same Whitespace on top. Each run cost 2 credits (Unity break cost) for zero new information. Recognizing "same card = Corp hasn't drawn" saves clicks and credits.

---

## Damage Management

**Damage = random discard from hand.** Hand size functions as health.

**Damage types (all work the same for Runner):**
- Net damage: From traps, ICE, agendas - typically more damage per advancement on traps
- Meat damage: From operations, tags (not in tutorial decks)
- Brain damage: Random discard + permanent -1 max hand size (avoid at ALL costs)

**Flatline condition:** Hand size < damage amount → instant loss

**Survival strategy:**
```
Before running damage servers:
1. Count expected damage (ICE subs + trap potential)
2. Draw to buffer: hand_size = expected_damage + 2
3. Run only when safe
4. Rebuild hand after taking damage
```

**Example:**
```
Planning to run remote with Diviner (1 net damage per run)
Current hand: 3 cards
Runs planned: 2

Damage total: 2 × 1 = 2 damage
Safe hand size: 2 + 2 = 4 minimum

Action: Draw 1 card first, THEN run twice
```

**When to tank damage:**
- Stealing game-winning agenda (need 3 points, have 4)
- Denying critical economy asset early game
- Accessing R&D with multi-access when ahead

**When to avoid damage:**
- Hand size ≤ expected damage (flatline risk)
- Already behind on tempo (can't afford rebuild time)
- Brain damage (permanent penalty)

## Common Patterns

**Facecheck:** Run into unrezzed ICE without appropriate breaker.

**When to facecheck (Turns 1-4):**
```
if Corp_credits < (likely_ICE_rez_cost + 5):
    facecheck  # They probably can't afford rez
elif rezzed Corp_installed_economy_asset and can_afford_trash_cost:
    facecheck  # Deny snowball or Corp gets rich
elif turn_number <= 3:
    facecheck  # Deny early economy setup
```

**Facecheck benefits:**
- Force rezzes (Corp poverty - they cannot afford good defenses everywhere)
- Deny economy assets
- Information gathering, e.g. know what breaker to install

**Facecheck costs:**
- Damage (need to rebuild hand)
- ETR subroutines (wasted click, no access)
- Tags (Corp can trash your resources)

**Rule of thumb:** Facecheck when Corp credits < (likely rez cost + 5).

### Remote Pressure Timing

**When Corp installs in remote:**
```
Turn 1 install → Probably economy asset
  Action: Check if you can afford trash cost

Turn 2-3 install → Could be economy or naked agenda
  Action: Check if Corp is rich/poor (gambling indicator)

Turn N install + advance → AGENDA LIKELY
  Action: Count advancement counters
  3+ counters → Must contest next turn or they score

Turn N install + multiple advances same turn → FAST ADVANCE
  Action: Check if scoreable now (2-cost = 2 adv)
  If scoreable, too late; if needs 1 more, contest immediately
```

**ICE on remote:**
```
0 ICE → Run immediately (free access)
1 ICE → Run if you can break that type
2 ICE → Calculate full break cost, run if affordable
3+ ICE → Corp committed to defending this
  Check: Is there an agenda? (advancement counters?)
  If yes + affordable: Contest
  If no or too expensive: Pressure R&D instead
```

### The Never-Advance Bluff

Corp can score agendas without advancing them:

**Seamless Launch pattern:**
```
Turn 5: Corp installs in remote (0 counters)
Turn 6: Corp plays Seamless Launch (+2 adv)
        Corp advances (+1 adv)
        Corp scores (3 counters total)

Runner thought: "0 counters = safe to ignore"
Reality: Scored in one turn, no response window
```

**Defense:**
- Always check remotes, even 0-counter cards
- Especially when Corp has credits for Seamless Launch
- Especially when Corp needs 1-2 points to win
- Accept you can't check everything (choose highest priority)

---

## Turn Patterns

### Basic Runner Turn

```
Turn start: Gain 4 clicks

Option A (Building): Draw → Install → Install → Credit
Option B (Running): Draw → Run R&D → Run HQ → Credit
Option C (Pressure): Run R&D → Run R&D → Run remote → Credit

Turn end: Discard to hand size if needed
```

**Early game focus:**
```
Turns 1-2: Economy + first breaker
Turn 3-4: Complete breaker suite
Turn 5+: Begin running pressure
```

**Mid game focus:**
```
Install multi-access (R&D Interface)
Run R&D every turn (2 accesses per run)
Contest obvious remote scoring attempts
```

**Late game focus:**
```
Run R&D multiple times per turn
Ignore remotes unless critical
Maintain hand size > 3 (flatline prevention)
```

## Economic Warfare

### Credit Types and Payment

**Temporary credit effects:**
- Provide credits for current run/action only
- Automatically offered in payment prompts during runs
- Game uses temporary credits first, then normal pool
- Check card text for phrases like "gain X credits to spend during this run"

**Hosted credits (on installed cards):**
- Credits stored on the card itself (shown as counters on the card)
- Must manually use card ability to transfer to your credit pool
- NOT automatically offered in payment prompts
- Check card text for the transfer ability (usually requires a click)
- **Use ability BEFORE running** if you need those credits for break/trash costs

**Credit planning before runs:**
- Calculation trick: Build a table of the subroutines you know you face in a server, one row at a time
- Total break cost is then:
  - All the subroutines you *must* break (ETR, damage)
  - Plus the ones you *want* to break
  - Plus any cost from upgrades
  - Plus trash cost for assets/upgrades
- Watch for a stale table when you install new rig or the server is improved
- Understanding how much it costs you per access is important to win the economy war and know which servers are strong or weak
```
Target: Remote with 2 ICE (Palisade + Brân 1.0)

Break costs:
  Palisade (Barrier): 3 credits (Cleaver)
  Brân 1.0 (Bioroid): 0 credits (bypass with 3 clicks)

Trash cost:
  Unknown asset: Assume 0-4 credits depending on meta. Invariably 0 for advanced cards.

Total needed: $3 + 3 click + $4 = $7 + 3 clicks minimum to assure success - but be ~broke afterwards
```

**Follow up:** Unless you stole the winning agenda, the game continues. Have a plan for what happens next given your now poorer position.

**Economics of servers:**

**When poor (< 5 credits):**
1. Play economy, use installs or draw for it
2. Click for credit as last resort
3. Don't run expensive servers until rebuilt
4. Remember being rich threatens remote access

**Trashing can be expensive but necessary:**
- But never trash things you do not have to, e.g. a trap you can safely leave in a remote now you know where it is or something ineffectual

---

## Win Conditions

**Primary:** Steal 7 agenda points through volume of accesses.

**The math:**
- mean random ~17 accesses needed
- R&D multi-access = 2 accesses per run
- 9 runs with multi-access = 18 accesses = likely win, but that is a lot of accesses on one server

**Secondary scenarios:**
- HQ flood steal (Corp drew many agendas, stuck in hand)
- Remote snipes (stealing before Corp can score)
- Corp deck-out (Corp draws from empty R&D = loss)

**You lose if:**
- Corp scores 7 agenda points first (6 in tutorial game)
- Flatlined (damage > hand size)

**Endgame (Missing < 5 points):**
- Every agenda is critical
- Contest remotes when possible (can't let Corp score)
- Maximum R&D pressure (find agendas before Corp draws them)
- Keep safe hand size (don't die to damage)

---

## Quick Reference

**Status interpretation:**
```
Clicks: 4, Credits: 5  → Minimum $ to play Sure Gamble
Hand: 2 cards          → DANGER: Draw before running if target could have damaging ICE or be a trap
Remotes: 2/3           → URGENT: 2+ counters = contest now
Drawn: 10, Scored: 2   → 8 points unaccounted = HQ or remotes
```

**Decision flowchart:**
```
Start of turn:
── Plan turn. Make 2 plans for the turn if position is complex and pick the best. If you need to draw, draw now to get more options, may change plan
├─ Missing needed breakers? → Draw/install rig, don't be afraid to overdraw if necessary and in a hurry OR simply attack elsewhere if vulnerable
├─ The run action is your most powerful - most subroutines are not that bad and invariably cost the Corp $ right now. End The Run costs you one click and tells you what breaker you need. Force the Corp to play your game
├─ They usually cannot afford to rez everything - attack their weak points and trash their fresh money assets when you can afford to
├─ Advanced remotes → Contest remote immediately if possible, consider trap possibility
├─ Poor (< 6 credits)? → Build economy; $ threatens remotes via run into ETR, install breaker, run and steal
├─ Complete rig? → Run R&D to deny the Corp seeing agendas before you steal them.
└─ Default → Run R&D, contest remotes as needed
```

**Emergency situations:**
```
Corp at game point → Contest ALL remotes, can't let them score
Hand size < 3 → Draw immediately (flatline danger)
```

---
tl;dr pressure cheaply while building economy & rig → win through volume.
