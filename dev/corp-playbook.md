# Corp Playbook

**Purpose:** Strategic guidance for Corp play in Netrunner. Assumes you know the rules (see `netrunner-complete-mechanics.md`) and commands (see `send_command help`).

**Core objective:** Score 7 agenda points before Runner steals 7 (6s in tutorial game). Do this by creating scoring windows and exploiting Runner weaknesses.

**Golden Rule:** Netrunner is a dynamic game and boardstate can override rules, never mind strategies - so strats here are heuristics at best.

## Fundamental Principles

### The Fundamental Asymmetry (Why Corp Is Hard)

**Runner's base card includes:** `Click: Run any server.`

This is always available. No setup. No cost beyond the click. Every unrezzed ICE is a free pass. Every naked server is a free access. The threat is permanent and omnidirectional.

**Corp's base card:** Click for credit, draw, install, advance. No attack action. Everything is building toward a scoring window that Runner can disrupt at any time.

**Mandatory draw is secretly a curse:**
- Can't stop it
- Floods HQ with agendas you can't score fast enough
- Deck-out is a loss condition (Runner doesn't have this)

**When both sides play poorly:** Runner wins by default. Runner's floor is "randomly check servers, eventually find 7 points." Corp's floor is "pray Runner doesn't run the right server." You must actively create situations where Runner's base action isn't good enough.

### The Time Pressure

**You are racing against the clock.** Runner has statistical inevitability (~17 random accesses to find 7 points). Your advantages:

- **Information asymmetry:** You know what's in HQ, R&D, and remotes (Runner doesn't)
- **Tempo control:** You choose when to install, advance, and score
- **Deferred costs:** Install cards facedown (rez later only when needed, e.g. drip before start of turn)
- **Flatline threat:** Deal damage > Runner's hand size = instant win

**Your goal:** Score 7 points before Runner completes rig and applies relentless pressure.

### The Click Economy (Corp Version)

**You get 3 clicks + mandatory draw** (Runner gets 4 clicks, no mandatory draw).

**This means:**
- You have 3 effective clicks per turn (draw is automatic)
- Every click-for-credit is 33% of your turn = **failure state**
- Play economy cards instead as much as possible, that's why they're there

**Click efficiency ratings:**
- 1 click → 1 credit = **BAD** (basic action, desperation only)
- 1 click → 4 credits = **GOOD** (Hedge Fund)
- 1 click → install ICE protecting key server = **EXCELLENT**
- 1 click → advance agenda toward scoring = **EXCELLENT**

### Key Corp Deck Type Strats (often hybrids of these concepts)

#### Always Be Jamming (ABJ)

- Install cards in defended remotes constantly. Make Runner check everything and pay a tax to do so
- Runner can't tell agendas from assets from traps
- Every check costs Runner tempo (clicks, credits, damage)
- Forces Runner into difficult decisions
- Some checks will be too expensive → you score
- Single remote has highest tax but e.g. dual allows separate drip and scoring remotes

#### Kill
- Winning with agendas is the backup plan
- Typically requires significant density of kill combo in deck so it's obvious if your deck is of this kind
- Examples: tag-into-meat damage, a Weyland speciality. Net damage, from Jinteki. Rarely, brain damage combos from Haas-Bioroid.
- Sometimes tries to fork runner - let corp score or flatline

#### Spam
 - Generate many, typically undefended remotes with annoying trash costs or effects
 - Decks usually constrain runner actions somehow (e.g. net damage on trashing things)

#### Glacier

 - Build a giant remote and accept early agenda losses
 - Usually has some kind of ETR upgrades so that simple icebreakers will not work

## ICE Placement Strategy

**Rule of thumb priority order for ICE:**

1. **R&D first** (most attacked server, protects future draws)
2. **HQ second** (prevents agenda sniping from hand)
3. **Scoring remote third** (when ready to score)
4. **Archives last** (usually undefended unless running recursion)

Note install cost + 1 per existing ICE. Final choice here depends on deck and what runner is doing.

**ICE sequencing (outermost to innermost):**

Ideally, try to ETR on inner ice and annoyance outside it. Don't assume ice will actually fire, it's a tax.

**Installation cost:** Installing ICE costs 1 credit per ICE already protecting that server.
- First ICE on server: 0 credits
- Second ICE: 1 credit
- Third ICE: 2 credits

**You can trash existing ICE before installing** to reduce installation cost.

### Rez Timing

**CRITICAL: "Deferred rez" does NOT mean "maybe don't rez"**

The point of installing ICE facedown is to rez it WHEN THE RUN MATTERS. A run on a 3-point agenda matters. That's the moment to rez.

**When to rez ICE:**
- **Cheap ICE (2-4 credits):** Rez immediately when approached (establish board presence)
- **Expensive ICE (5+ credits):** Wait until Runner commits to run (they've spent clicks)
- **Damage ICE:** Rez when Runner has small hand (lethal threat)
- **When protecting something important:** REZ IT. Don't let a 6-credit ICE sit unrezzed while Runner steals a 3-point agenda.

**Before rezzing, check:**
```
1. Can I afford the tempo hit? (Rezzing Brân for 6 when you have 7 = crippled next turn)
2. What am I BUILDING toward? (Rez to create a reusable taxing server, not just to stop one run)
3. Can Runner afford to break? (If not, rez is very valuable)
4. Does the ICE generate value even if broken? (See Brân example below)
```

**Example: Paying the gold price for Brân**
```
You have: 12 credits, unrezzed Brân on asset server, Palisade in hand
Runner runs. You rez Brân (now at 6 credits).

Brân's first sub: "Install 1 ICE from HQ directly inward, ignoring all costs"
→ Drop Palisade BEHIND Brân. Free install, still facedown.
Subs 2-3: ETR, ETR. Run ends.

Result: 2-ICE stack where inner ICE is mystery to Runner.
Next turn: Install Regolith behind both, rez, start mining.
Runner now faces Brân + ??? + economy engine.

Even if Runner BREAKS the ETRs but lets sub 1 fire, you got a free ICE install.
Brân generates value even when "broken."
```

**The key insight:** You don't pay 6 credits to stop ONE run. You pay it to BUILD a taxing server that will stop MANY runs. If you don't have follow-up (economy, more ICE, agenda to score), maybe don't rez yet - give up the access, save for a better moment.

**Don't rez when:**
- You can't afford it (need credits for other priorities)
- Runner has breaker for that ice that's very cheap to use in the matchup
- Run is on low-value server that you think it will be a mistake to access anyway
- Server is empty or contains expendable asset

---

## Scoring Windows

**Identify and exploit these situations:**

### 1. Runner Is Poor (< 5 credits)
```
Runner can't afford to break your ICE
Action: Install agenda in remote, advance, score before they rebuild economy
```

### 2. Runner Missing Breaker Type
```
Check Runner's rig (visible in board state)
No X type breaker - (note some AI breakers break all ice)? Put agenda behind X ICE
They can't contest without that breaker type if it ETRs
```

### 3. Runner Has Small Hand (1-2 cards)
```
If you have damage ICE/traps:
- Install and advance agenda in remote
- If they run: Damage ICE can flatline them
- If they don't run: You score
Win-win situation
```

### Fast Advance

**Score agendas in one turn** (no response window for Runner):

**Seamless Launch pattern:**
```
Standard 3-cost agenda: 3 turns minimum
  Turn 1: Install
  Turn 2: Advance 3 times
  Turn 3: Score

Fast advance with Seamless Launch: 1 turn
  Same turn: Install, play Seamless Launch (+2 adv), advance (+1), score
  Total: 1 turn, 3 clicks
  Runner cannot respond
```

**When to fast advance:**
- Runner has complete rig (can contest any remote)
- You need points urgently (behind on score)
- HQ is flooded with agendas (need to score them quickly)

---

## Economic Management

**Your economy engine:**

1. **Mandatory draw** (automatic every turn)
2. **Economy Operations** (note min costs)
3. **Economy assets** (note rez costs)

**Never click for credits** unless desperate. Cards are 4-8× more efficient. But don't overdraw and agenda flood HQ.

### Economy Assets: Worth Defending or Not

Economy assets fall into two categories based on click investment:

**Active Economy (DEFEND THIS)** - e.g., Regolith Mining License
```
Regolith: Rez 2, loads 15 credits. Click: Take 3 credits.

This is CLICK-BASED, not turn-based. One turn behind strong ICE:
- Start of turn: Rez for 2 (loads 15)
- Click 1: Take 3 (you're at +1 net, 12 remaining)
- Click 2: Take 3 (you're at +4 net, 9 remaining)
- Click 3: Take 3 (you're at +7 net, 6 remaining)

That's Hedge Fund value PLUS efficient clicking. And 6 credits still on it!
Next turn: Take 6 more, install something good in same server.

Put this behind your best ICE. You're going to be working it.
Runner must click-through Brân (3 clicks) + pay 3 trash = entire turn + money.
If they don't run, you get +13 net credits over 2 turns.
```

**Passive Economy (EXPENDABLE)** - e.g., Nico Campaign
```
Nico: Rez 2, loads 9 credits. When your turn begins, take 3.

This is TURN-BASED. Zero clicks after install.
Lower yield (+7 net + 1 card) but completely hands-off.

Two uses:
1. Drip server: Light ICE, taxing but not your main remote.
   It ticks while you focus elsewhere.
2. Bait: In scoring server, forces a decision:
   - Runner runs → pays ICE tax + 2 trash, you install real agenda next turn
   - Runner ignores → you rez, start collecting (behind turn 1, ahead by turn 3)

Only costs 1 click ever (the install). If Runner trashes it, you lost a click,
they lost a run + 2 credits + ICE tax. Often a good trade for you.
```

**The key insight:** Active economy demands protection because you're investing clicks. Passive economy can be opportunistic - treat it as bait or background income.

### Credit Planning

**Before rezzing ICE, remember you need to do things afterwards. Can you afford it in the big picture?**

---

## Agenda Management

### The Draw Trap

**Fast drawing:**
- Good: Find agendas faster → score faster
- Bad: Flood hand with agendas → Runner steals from HQ

**Hand size management:**
```
Max hand size: 5 cards
Agendas in hand: 2
Risk level: HIGH (40% of hand is agendas)

Options:
1. Install both in different remotes (split Runner attention)
2. Install one, keep one (still risky if HQ run)
3. Score one immediately (requires scoring window)
4. Fast advance (Seamless Launch)
```

**If agenda flooded (3+ agendas in hand):**
1. Install all agendas in remotes immediately
2. ICE HQ heavily (prevent HQ runs)
3. Accept some might be stolen (minimize damage). Force consecutive runs to open the scoring window.
4. Worst case: consider bluffing Archives discard. V risky. If rich, icing Archives can make Runner check sometimes.

### When to Draw Beyond Mandatory

**Corp already gets 1 free draw per turn.** Additional draws are for specific purposes:

**Draw extra when:**
- Desperately need ICE (R&D undefended, Runner building rig)
- Looking for fast-advance tools (Seamless Launch)
- Need economy (poor, can't rez installs)

**Drawing risky when:**
- HQ has 2+ agendas (flooding risk)
- About to score (use clicks for advancing, not fishing)
- Runner pressuring HQ (drawing adds more agendas to vulnerable hand)

---

## The Bluff Game

**Every facedown card is Schrödinger's Agenda** (to Runner).

### Never-Advance Strategy

**Install agendas without advancing them:**
```
Turn 5: Install card in remote (0 counters)
Turn 6: Play Seamless Launch (+2), advance (+1), score
  Runner thought: "0 counters = probably asset, safe to ignore"
  Reality: Scored in one turn, no warning
```

**Maintain the bluff:**
- Advance assets occasionally (make them look like agendas)
- Never-advance agendas (make them look like assets)
- Install in same remote repeatedly (all installs ambiguous)
- Use scoring window tactics even for assets

### Trap Play

**Expect: typical traps do not kill the runner but act as a tempo hit and occasional free wins, forcing runss**

**Advanced cards are a much bigger investment but will likely bait runs harder - a double advance threatens 5/3 score**

## Turn Efficiency Patterns

**Corp turn structure:**
``
1. Gain clicks (usually 3)
2. Mandatory draw (automatic)
3. Take actions (install, advance, play operations)
4. Discard to hand size (5 - their discards are face down unless Archives is run, so can be agendas)
```

* Have a plan at start of turn for all your clicks.
* You are in a race against time. The runner will access but you need to win.
* You win that race primarily by running them out of money.

---

## Win Conditions

**Primary:** Score 7 agenda points before Runner steals 7. (6s in tutorial)

**The race:**
- You score from remotes (controlled timing)
- Runner steals from centrals + remotes (volume)
- You must score faster than they accumulate accesses

**[Typically] Secondary: Flatline**

Deal damage > Runner's hand size = instant win. Not all decks have this capability. 
Unless the deck is dedicated to it, this is occasional free wins from Runner error but
mostly tempo hit from card loss.

**Tertiary: Deck Out**

Corp draws from empty stack = instant win for runner.

---

## Advanced Concepts

### The Gambit: Trading Agenda for Position

Some agendas have "when stolen" triggers that benefit you. Example: Send a Message lets you rez 1 ICE for free when scored OR stolen.

**The King's Gambit pattern:**
```
Turn N: Install 5/3 agenda behind unrezzed Brân 1.0
Turn N: Advance twice (signals "come get it")
Runner runs, you DON'T rez (can't afford 6 credits, or choosing not to)
Runner steals → Send a Message trigger → rez Brân for FREE

Cost: 3 agenda points, ~5 credits (install + advances)
Gain: 6-credit ICE rezzed, Runner spent a click
```

**THIS IS ONLY VALID IF YOU HAVE FOLLOW-UP:**
```
Turn N+1: Install Palisade on same server (Brân + Palisade stack)
Turn N+1: Install 4/2 agenda, advance once
Turn N+2: Advance twice, score

Now Runner faces Brân (6+ credits or 3 clicks) PLUS Palisade (barrier ETR).
You score 2 points back and have a reusable scoring remote.
```

**If you don't have follow-up, it's not a gambit - it's just losing.**

The Corp agent in Autonomous Game 1 got a free Brân from Send a Message, then abandoned Server 1 and built naked remotes elsewhere. That's paying for dinner and leaving before eating.

### Server Discipline

**Build depth, not breadth.**

```
GOOD: Server 1 with Brân + Palisade (2 ICE stack, taxing)
BAD: Server 1 with Brân, Server 2 with Tithe, Server 3 with Whitespace

The first forces Runner to pay 9+ credits per access.
The second gives Runner three servers to check for 1-3 credits each.
```

**Don't abandon good servers:**
- If you have a rezzed Brân on Server 1, USE IT
- Install your next agenda/asset behind the ICE you already paid for
- Building a new remote means starting from scratch

**When to make a new remote:**
- Your scoring remote is compromised (Runner can break everything cheaply)
- You need a drip server separate from scoring server
- You're flooding and need to spread agendas (desperate)

---

## Common Mistakes to Avoid

1. **Drawing excessively**
2. **Installing agendas without ICE** - Runner gets free steals
3. **Not jamming enough** - Runner ignores remotes, builds rig safely, then pressures centrals
4. **Overprotecting one server** - 4 ICE on HQ, 0 on R&D = Runner farms R&D freely
5. **Rezzing ICE too early** - Rez when Runner commits to run, not during install
6. **Clicking for credits repeatedly** - Play economy cards instead (4-8× better)
7. **Not re-using servers** - Force re-runs (don't abandon rezzed ICE!)
8. **Not using fast-advance**
9. **Ignoring Runner's rig** - Esp. check what breakers they have, exploit missing types
10. **Flooding HQ with agendas** - Install them immediately, don't let them accumulate
11. **Not rezzing when it matters** - "Deferred rez" means rez when run matters, not "never rez"
12. **Abandoning good positions** - If you have a taxing server, use it

---

## Matchup-Specific Strategy

### vs Aggressive Runner

**Pattern:** Runner checks all remotes, runs early and often.

**Counter:**
- Install something every turn on a taxing remote.

### vs Economic Runner

**Pattern:** Runner builds credits, only runs when rich (10+ credits).

**Counter:**
- Rush early (score before they're ready) and build econ behind light ICE
- Fast advance (Seamless Launch, can't be contested)
- Tax heavily (expensive ICE on scoring server, drain credits)
- Jam multiple remotes (can't afford to check all)
- Consider point trade if ahead

### vs Rig-Building Runner

**Pattern:** Runner installs breakers for 3-4 turns before running.

**Counter:**
- Score aggressively during build phase
- Install agendas in remotes early
- Advance and score before rig complete
- If they complete rig: Fast advance remaining agendas

---

## Reading Runner Intent

**Runner tells:**

**Not running for 2+ turns:**
- Building rig (missing breakers)
- Building economy (poor)
- Action: Score now (they can't contest)

**Running HQ repeatedly:**
- Trying to steal from hand
- Suspects agenda flood
- Action: Install agendas in remotes, ICE HQ heavily

**Running R&D with multi-access:**
- Committed to R&D pressure strategy
- Likely has complete rig
- Action: Fast advance, score before they find agendas

**Checking remotes aggressively:**
- Afraid of you scoring
- Has credits to contest
- Action: Use traps to punish, or score in heavily ICE'd remote

**Clicking for credits:**
- Poor economy
- Can't afford to run
- Action: Scoring window, install and advance NOW

---

**Scoring priority:**
```
Higher value = fewer scoring windows needed to win
But: Lower cost = faster to score. 5/3 needs multiple fast-advances on one turn even if installed
Note that scoring is a big tempo hit for you unless the agenda does something very good on score - don't forget economy
Balance: Score what you can when windows appear
```

**Emergency situations:**
```
Runner at game point → They need 1 score to win, defend ALL servers that could have agendas
HQ flooded (3+ agendas) → Install all, ICE HQ, score fastest
Poor (< 3 credits) → Play economy cards, click for credits, can't rez ICE
R&D naked + Runner has rig → ICE R&D immediately or lose to pressure
```


**Remember:** You control the tempo. Create scoring windows, exploit Runner weaknesses, and jam constantly.

Score fast → win before Runner's statistical advantage takes over.
