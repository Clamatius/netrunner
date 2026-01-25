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

### Cost vs Effect

**A card's cost must be paid to play it
**Effect does not have this restriction - effects do as much as they can but no more
**Example: "Event. Cost $1. Effect: draw 4, lose a click. Taking advantage: play last click so you don't actually lose it
**Card templates use a colon to indicate costs, e.g. <click>: Take $3 credits from this resource"

### Conditionals

**Many cards have "if/when/first time" triggers.** Before acting, ask: can I order my clicks to enable discounts, maximize triggers, or dodge thresholds?

**Common patterns:**
- **"If you made a successful run this turn..."** Run servers you'd run anyway first, or weak servers like Archives if effect is strong
- **"Once per turn / The first time per turn..."** → Max EV typically from using every turn if possible
- **"Lose a click"** → Play last click
- **ICE subroutines** often have conditions that allow you to bypass them → exploit the condition to save $

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

1. **Economy events and rig** (Turn 1-2)
   - Drip pays off over time - the earlier the better
   - You take a tempo hit to install, so front-load it
   - Dipping too low on credits gives the Corp a scoring window
   - Rarely a good idea to go under $5 since then you can't Sure Gamble

2. **Breakers when locked out**
   - Big tempo hit to install so ideally only install the revealed type needed
   - Lets you start running servers protected by the type of ice broken
   - Killer typically protects you from damage, Fracter and Decoder from End The Run
   - Don't wait for "perfect" rig - facechecking ICE risks subroutines but a lot of ICE just end the run
   - Every ICE the corp rezzes means they had to invest there and may not be able to afford rezzes elsewhere

   **Sandbagging breakers:** Consider keeping breakers in hand instead of installing immediately.
   - Corp sees empty rig → thinks remote is safe → installs agenda → you surprise-install and steal
   - Mayfly + Overclock from hand = 6¢ to crack almost any single-ICE remote
   - AI breakers (Mayfly) are brutal surprise plays: raw credits → stolen agenda
   - Tradeoff: risk losing sandbagged breakers to net damage (sentries, traps) - run multiple breaker copies in deck
   - Best against glacier Corps building "safe" remotes; worse against damage-heavy Corps

3. **Complete breaker suite** (Turn 3-5)
   - Must have all 3 types covered (one breaker per ICE type) or an AI breaker that breaks all types to ensure access given enough $

4. **Multi-access permanent installs** (Turn 6+)
   - Typically expensive (3+) so a tempo hit to install
   - Only install after complete rig + stable economy

---

## The Tempo War

**Netrunner is fundamentally an economic race.**

**Michael's formulation:** "The trick to win more than luck dictates is to maximize cost for access to servers with agendas (Corp) or minimize (Runner)."

### Install As Needed, Not As Drawn

**The trap:** "I drew Carmen (Killer), I should install it."

**The reality:** Installing breakers you don't need is pure tempo loss.

**Cost analysis (Shaper tutorial deck):**
| Breaker | Install Cost | Effect if Unused |
|---------|--------------|------------------|
| Carmen (Killer) | 5¢ | Zero. Sentries didn't fire. |
| Cleaver (Fracter) | 3¢ | Zero. No Barriers rezzed. |
| Unity (Decoder) | 1¢ | Zero. No Code Gates. |

**Total cost of installing all 3:** 9¢ (11¢ without Carmen discount)

**Total cost of installing only what you need:** 1-5¢ typically

**The discipline:**
```
Drew breaker? → Ask: "Is there ICE of this type I need to break?"
  YES → Install (but still consider sandbagging)
  NO → Keep in hand until needed
```

**Example (Game 5):**
```
Turn 4: Drew Carmen, installed for 5¢
Turns 4-15: Karuna (only Sentry on board) never rezzed
Result: 5¢ + 1 click wasted, enabled scoring window
```

The Carmen sat idle while Corp built a 5-ICE remote. Those 5¢ could have:
- Contested Remote 1 early (before it grew to 5 ICE)
- Trashed Nico Campaign (denied Corp 9¢)
- Funded 5 R&D runs through Whitespace (Unity: 1¢/break)
- Pro: can drop breaker after ICE paid for to invalidate its ETR and gain surprise access.
- Con: can lose card to damage 
- Damage is hypothetical install costs are known and immediate

### Cheap Pressure > Expensive Access

**When you have a 1¢ break:** Exploit it ruthlessly.

**Example: Unity + Whitespace**
```
Whitespace: Code Gate, 1 sub
Unity break cost: 1¢

R&D protected only by Whitespace?
→ Run R&D EVERY turn for 1¢ + 1 click
→ Either Corp adds ICE (tempo cost to them) or you see top card every turn
→ Mean ~17 accesses to win - at 1¢ each that's 17¢ total!
```

**Contrast: Unity + Palisade + Brân**
```
Break cost: 1¢ + 3¢ + 8¢ = 12¢ per run
12¢ × 17 accesses = 204¢ (impossible)
```

**Strategic implication:** Identify which servers are cheap and attack them relentlessly. Force Corp to invest in defense or lose.

### Forcing Rezzes as Value

**Every ICE rez costs Corp credits.** Even getting ETR'd has value.

**The play:**
```
Run → Hit unrezzed ICE → Corp rezzes (3-6¢) → ETR

Result:
- You: Lost 1 click (cost: ~1¢ equivalent)
- Corp: Lost 3-6¢ in rez cost, cannot spend elsewhere
- Info: Now know ICE type, can install correct breaker
- Net: Corp paid 2-5¢ more than you for this exchange
```

**Don't fear ETR. Fear spending 5¢ when 0¢ would do.**

**Locking down the remote makes HQ juicier. Locking down R&D denies agendas altogether.
**Sometimes you can feint to attack the server you actually care about

---

## The Draw Decision

**Early game (Turns 1-5): Draw for rig pieces**

```
Missing required breakers for an important server (scoring remote, R&D)? → Draw until you find them
Missing economy? → Draw until you find $ cards
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

**Survival draws (against damage decks):**

Always draw before running if facing damage:
```
If expected damage per run: ~2 (from ICE/traps)
Current hand: 3
Minimum safe: 2 + 2 = 4

Draws needed: 4 - 3 = 1 draw before running
```

**Heuristic:** Hand size must be > expected damage + 2 buffer. Sometimes damage is better than spending credits - e.g. you're losing low-value cards like duplicate breakers

---

## Running Decisions

### Should I Run This Server?

**Pre-run checklist:**

1. **Do I have breakers for all ICE types?**
   - Check board for rezzed ICE
   - Code Gate? Need Unity
   - Barrier? Need Cleaver
   - Sentry? Need Carmen
   - Stopgap? AI breakers can break multiple types with a downside
   - Missing type? Don't run OR accept letting subs fire

2. **Can I afford break costs + trash costs?**
   - Calculate: ICE break costs + potential trash (3-5 credits)
   - Keep credit buffer for surprises if possible
   - Have a plan for after the run
   - Can't afford? Build economy first

3. **What's the expected value?**
   - R&D: Always valuable (random agenda access)
   - HQ: Valuable if Corp drew many cards without scoring much (see Drawn estimate)
   - Remote with counters: High priority to contest (scoreable agenda or advanced trap)
   - Remote with 0 counters: Unknown (could be anything)
   - Remote with multiple cards: At least one is an upgrade, usually boosting server defence - budget extra run cost to fight and trash it

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
1. Facecheck servers generally to get accesses (deny economy assets). Strike a balance between developing position and getting cheap accesses.
2. Facechecking for rezzes reveals hidden info and helps keep Corp credits low
3. If you actually want access to server A and it has unrezzed ICE, with the corp on a tight budget, perhaps run unrezzed server B first in case they can't defend both
4. Runner typically has advantage because Corp cannot afford all defenses

**Mid game (Turns 6-10):**
1. Corp typically has advantage because Runner has not built full rig and repeat ICE traversal favours Corp
2. R&D multi-access (primary win condition)
3. Remote when obvious (Corp advancing = must contest)
4. HQ pressure when agendas not being scored/stolen (keep them honest)
5. Drop sandbagged breakers and deliver surprise server breaches

**Late game (Turns 10+):**
1. Runner typically favoured because of gaining efficient R&D / remote access
2. Full breaker suite usually necessary
3. Denying Corp agenda draws from R&D is plan A. Denying Corp agenda installs in remote so HQ becomes attractive is plan B.
4. Watch for total server break costs - running through heavy ICE can cost $10+ per run
5. If Runner has Corp in remote lockdown in endgame and HQ is not protected well, they may discard agendas to Archives

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

### R&D Lock Strategy

**When R&D is cheap to access, run it EVERY turn.**

**The R&D Lock:**
```
Setup: R&D protected by single Whitespace (Code Gate)
Break cost: Unity = 1¢

Turn 5: Run R&D (1¢) - see Hedge Fund
Turn 5: Second run same turn - same card (wasted)
Turn 6: Corp draws, run R&D (1¢) - fresh card
Turn 7: Run R&D (1¢) - fresh card
...

Result: 1 access per turn costs 1¢. Corp MUST ice R&D or lose.
```

**Why this works:**
- You're spending 1¢ to force Corp to either:
  - Add ICE (costs them 3-6¢ rez + opportunity cost)
  - Let you access every card before they draw it
- Every agenda you steal from R&D is one they can't score
- Corp's mandatory draw feeds you cards, not them

**When to R&D Lock:**
- Single weak ICE (1-2¢ break cost)
- No multi-access installed (one card at a time)
- Early-mid game (Corp hasn't iced heavily)

**When to break the lock:**
- Corp installs in remote with advancement counters (must contest)
- Break cost rises above 3¢ (diminishing returns)
- Same card twice = Corp hasn't drawn yet, switch servers

**Common mistake:** Not running cheap R&D because "I only see one card."
One card per turn for 1¢ = 10+ accesses by Turn 10 for 10¢.
That's often enough to steal 4-6 agenda points.

**R&D trash costs are effectively more expensive than usual for you:**
- Not only did the corp not have to pay to rez it, they didn't even have to draw or install it!

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

**Standard Agenda Types**
```
Varies per meta, but default:
* 3/1. Only 1 point for install + 3 advancements. Low priority for both sides. Credit opportunity cost: $8
* 4/2. Invariably requires 2 turns to score. Often has a strong effect. 
* 5/3. 
```

**Advancement counter signals:**
- **0 counters (never-advance):** Could be agenda (Seamless Launch threat), asset, trap or upgrade
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

---

## Card Physicality

**Runner cards are akin to health.** Damage causes discards

**HQ access is accessing the Corp's hand.** Reveals info about their current options

**R&D is an ordered deck.** Cards don't shuffle between accesses. Understanding this prevents wasted runs.

**Core mechanics:**
- Top card stays on top until Corp draws (mandatory draw or click action), without you trashing or stealing it
- Corp's mandatory draw happens at turn start (before their first click)
- Click-to-draw is optional (costs 1 click)
- Trashing a card in R&D sends it to Archives face-up
- Trash costs in R&D are even more expensive than usual because the Corp didn't even have to draw the card - but if R&D access is cheap potentially allows you another access
- Running empty remotes is usually pointless since you access 0 cards (although the run is still considered successful)
- Running Archives flips all cards face-up and accesses _all_ of them. Corp discards are face-down and _can_ be agendas (risky for Corp)

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
When accessing R&D, vital to track whether top card has already been seen
```

**Information flow:**
```
R&D (ordered) → HQ (drawn) → Installed/Scored/Archives

When you see a card in R&D:
- If junk: Stop running R&D until Corp draws
- If agenda: Stealing agenda as top card means next card is now fresh
- If advanceable trap: Consider trashing if rich OR hitting the trap in the remote would be disastrous
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
- Meat damage: From tag-punishment operations (Orbital Superiority = 4 damage if tagged)
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

---

## Tags

**Tags are the Corp's handle on you.** While tagged, you're vulnerable to punishment.

### How You Get Tagged

- **ICE subroutines:** Funhouse gives 1 tag if you don't pay 4¢
- **Operations:** Public Trail tags you if you have more credits than Corp
- **Failed traces:** Some cards initiate traces; losing gives tags
- **Agenda effects:** Some agendas tag on steal or access

### Why Tags Are Dangerous

**Meat damage operations:**
```
Public Trail (1¢) → Runner tagged
Orbital Superiority → 4 meat damage if Runner tagged

Hand size 4? You're dead.
Hand size 5? You survive with 1 card.
```

**Resource trashing:**
- Corp can spend click + 2¢ to trash ANY of your installed resources while tagged
- Economy resources (Daily Casts, Liberated Account) = tempo destruction
- Key program/hardware support resources = rig crippled

**The kill combo (intermediate deck):**
```
Turn N: Runner runs into Funhouse, takes tag (couldn't afford 4¢)
Turn N: Runner ends turn tagged with 4 cards in hand
Turn N+1: Corp plays Orbital Superiority → 4 meat damage → FLATLINE
```

### Clearing Tags

**Command:** `remove-tag` (costs 2¢ + 1 click per tag)

**Clear tags when:**
- You have < 5 cards in hand (meat damage kill range)
- Corp is playing kill cards (Orbital Superiority, Punitive Counterstrike)
- You have valuable resources installed
- End of turn and Corp has clicks + credits for punishment

**Float tags when:**
- You have 5+ cards AND Corp can't kill you this turn
- No resources to lose
- Clearing would cost more than the punishment
- You need those clicks/credits for a critical play

### Survival Heuristics

**The Funhouse rule:**
```
Before running through Funhouse:
  Can I pay 4¢ to avoid the tag? → Pay it
  Can't afford 4¢? → Do I have 5+ cards AND 3¢ to clear? → Run, clear after
  Neither? → DON'T RUN (or accept death risk)
```

**End of turn check:**
```
Tagged + < 5 cards + Corp has kill card = CLEAR TAG NOW
Tagged + 5+ cards + no resources = probably safe to float
Tagged + valuable resources = clear or lose them
```

**Credit math:**
- Funhouse: 4¢ to avoid OR 2¢ + click to clear after = 4¢ is often better
- Multiple tags: Each costs 2¢ + click to clear = expensive, avoid accumulating

**Key insight:** Tags are temporary if you clear them. The danger is ending your turn tagged when Corp can punish. If Corp spends their turn tagging you and you clear before their next turn, they wasted tempo.

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

**Contest remotes early (prevent glacier buildup):**
```
Turn 3: Corp installs ICE #2 on Remote 1
Turn 4: Corp installs ICE #3 on Remote 1
Turn 5: Corp installs ICE #4 on Remote 1
...
Turn 10: Remote 1 has 5 ICE. Cost to run: 15¢+

Mistake: "I'll contest when I have full rig"
Reality: By then, full rig isn't enough

Better:
Turn 3: Run Remote 1 (force 2 rezzes, 6-10¢ Corp cost)
Turn 5: Run again (force rez of new ICE)
Result: Corp can't afford to keep icing AND scoring
```

**The glacier trap:** Every ICE you let them install for free makes the server harder. Contest early to:
- Force rezzes (costs Corp credits)
- Reveal ICE types (helps rig building)
- Slow down glacier (Corp spends clicks on ICE, not agendas)

**When to accept glacier:** If R&D or HQ are cheap, hammer those instead. Let Corp build a fortress they can never use.

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
- Especially when Corp needs 1-2 points to win
- Accept you can't check everything (choose highest priority)

---


## Economic Warfare

### Credit Types and Payment

**Temporary credit effects:**
- Provide credits for current run/action only
- Automatically offered in payment prompts during runs
- Game uses temporary credits first, then normal pool
- Check card text for phrases like "gain X credits to spend during this run"
- Be sure to choose the temporary $ when spending $ NOT usual pool

**Hosted credits (on installed cards):**
- Credits stored on the card itself (shown as counters on the card)
- Typically must manually use card ability to transfer to your credit pool so NOT automatically offered in payment prompts
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
  Brân 1.0 (Bioroid): 0 credits (bypass with 3 clicks) or $8 (Cleaver, hugely expensive)

Trash cost:
  Unknown asset: Assume 0-4 credits depending on meta. Invariably 0 for advanced cards and additional 0-4 for upgrades.

Total needed: $3 + 3 click + $4 = $7 + 3 clicks minimum to assure success - but be ~broke afterwards
```

**Follow up:** Unless you stole the winning agenda, the game continues. Have a plan for what happens next given your now poorer position.

**Economics of servers:**

**When poor (< 5 credits):**
1. Play economy, use installs or draw for it
2. Click for credit as last resort
3. Don't run expensive servers until rebuilt
4. Remember being rich threatens remote access

**Trashing drip economy - The Math:**

Drip economy assets pay out over multiple turns. Trashing them early denies the most value.

**Example: Nico Campaign**
```
Nico Campaign: Trash cost 5¢, gives Corp 9¢ over 3 turns
Turn 1: Nico installed
Turn 2: You access Nico but don't trash (save 5¢)
Turn 3-5: Corp takes 3¢/turn = 9¢ total

Net result: Corp gained 9¢, you "saved" 5¢
True cost: 9¢ - 5¢ = 4¢ advantage to Corp
```

**When to trash drip economy:**
```
Asset value to Corp > Trash cost to you?
  YES → Trash it
  NO → Leave it

Nico Campaign: 9¢ value > 5¢ trash = TRASH
```

**When NOT to trash:**
- Late game when asset has already paid out most value
- When trashing leaves you unable to contest next play
- When the asset is hogging the scoring remote (trap it with your threat) - if HQ unknown, run HQ when the asset is about to expire

**Trashing priority (high to low):**
1. **Strong defensive upgrades** - remove before they tax you repeatedly
2. **Drip economy (Nico, PAD Campaign)** - highest value denial when fresh
3. **Burst economy (Rashida, Clearinghouse)** - if unused, leave it
4. **Traps you can't safely leave** - often better to leave known traps as info

**The tempo calculation:**
```
You spend: Trash cost (5¢) + run cost (1-3¢)
Corp loses: Full asset value (9¢) + install click

If your cost < Corp's loss: TRASH
Example: 5¢ + 2¢ = 7¢ < 9¢ + 1 click = favorable trade
```

**Common mistake:** "I can't afford to trash."
Reality: You can't afford NOT to trash economy that outvalues trash cost.

---

## Win Conditions

**Primary:** Steal 7 agenda points through volume of accesses, 6 in tutorial.

**The math:**
- Mean random ~17 accesses needed in full game
- R&D multi-access = 2 accesses per run
- 9 runs with multi-access = 18 accesses = likely win, but that is a lot of accesses on one server

**Secondary scenarios:**
- HQ flood steal (Corp drew many agendas, stuck in hand)
- Remote snipes (stealing before Corp can score)
- Each non-random steal from a remote drops the expected # random accesses for win dramatically

**You lose if:**
- Corp scores winning agenda points first
- You _must_ discard to damage but can't (you do NOT lose on empty hand): flatlined

**Corp loses if:**
- They _must_ draw but R&D is empty (they do NOT lose on R&D empty, ONLY if they must draw)

**Endgame (win within 3 so either side could win):**
- Every advanced agenda is critical to steal since it could immediately win for Corp

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
├─ The run action is your most powerful - for unrezzed ICE, most subroutines are not that bad and cost the Corp to rez. End The Run costs you one click and tells you what breaker you need. Force the Corp to play your game
├─ They usually cannot afford to rez everything - attack their weak points and trash their fresh money assets when you can afford to
├─ Advanced remotes → Contest remote immediately if possible, consider trap possibility. More advancements ~= more deadly
├─ Poor (< 6 credits)? → Build economy; $ threatens remotes via run into ETR, install breaker, run and steal
├─ Complete rig? → Run R&D to deny the Corp seeing agendas before you steal them.
└─ Default → Run R&D, contest remotes as needed
```

**Emergency situations:**
```
Corp at game point → Contest ALL remotes, can't let them score
Hand size < 3 → Draw immediately (flatline danger)
Tagged + < 5 cards → Clear tag or draw to 5+ before ending turn
Tagged + resources → Clear tag or lose them to Corp trash
```

---
tl;dr pressure cheaply while building economy & rig → win through volume.
