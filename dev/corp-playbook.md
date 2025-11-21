# Corp Playbook

**Purpose:** Strategic guidance for Corp play in Netrunner. Assumes you know the rules (see `netrunner-complete-mechanics.md`) and commands (see `send_command help`).

**Core objective:** Score 7 agenda points before Runner steals 7 (6s in tutorial game). Do this by creating scoring windows and exploiting Runner weaknesses.

---

## Fundamental Principles

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

### Always Be Jamming (ABJ)

**Core strategy:** Install cards in defended remotes constantly. Make Runner check everything and pay a tax to do so.

**Why this works:**
- Runner can't tell agendas from assets from traps
- Every check costs Runner tempo (clicks, credits, damage)
- Forces Runner into difficult decisions
- Some checks will be too expensive → you score
- Single remote has highest tax but e.g. dual allows separate drip and scoring remotes

---

## ICE Placement Strategy

**Priority order for ICE:**

1. **R&D first** (most attacked server, protects future draws)
2. **HQ second** (prevents agenda sniping from hand)
3. **Scoring remote third** (when ready to score)
4. **Archives last** (usually undefended unless running recursion)

Note install cost + 1 per existing ICE.

**ICE sequencing (outermost to innermost):**

Try to ETR on inner ice and annoyance outside it. Don't assume ice will actually fire, it's a tax.

**Installation cost:** Installing ICE costs 1 credit per ICE already protecting that server.
- First ICE on server: 0 credits
- Second ICE: 1 credit
- Third ICE: 2 credits

**You can trash existing ICE before installing** to reduce installation cost.

### Rez Timing

**When to rez ICE:**
- **Cheap ICE (2-4 credits):** Rez immediately when approached (establish board presence)
- **Expensive ICE (5+ credits):** Wait until Runner commits to run (they've spent clicks)
- **Damage ICE:** Rez when Runner has small hand (lethal threat)

**Don't rez when:**
- You can't afford it (need credits for other priorities)
- Runner has no breaker for that type (they'll just let subs fire and continue)
- Run is on low-value server (let them access trash, save money)

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
No X type breaker? Put agenda behind X ICE
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

### 4. Late in Runner Turn / Runner at 0 Clicks
```
They can't contest until next turn
Action: Install and advance now, score next turn before they draw/rebuild
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

## Common Mistakes to Avoid

1. **Drawing excessively**
2. **Installing agendas without ICE** - Runner gets free steals
3. **Not jamming enough** - Runner ignores remotes, builds rig safely, then pressures centrals
4. **Overprotecting one server** - 4 ICE on HQ, 0 on R&D = Runner farms R&D freely
5. **Rezzing ICE too early** - Rez when Runner commits to run, not during install
6. **Clicking for credits repeatedly** - Play economy cards instead (4-8× better)
7. **Not re-using servers** - Force re-runs
8. **Not using fast-advance**
9. **Ignoring Runner's rig** - Esp. check what breakers they have, exploit missing types
10. **Flooding HQ with agendas** - Install them immediately, don't let them accumulate

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

## Quick Reference

**Status interpretation:**
```
Clicks: 3, Credits: 8    → Can rez medium ICE, advance agenda
Clicks: 0, Credits: 15   → End turn (well positioned for next turn)
Hand: 4 cards (2 agendas) → Install agendas, don't draw more
Runner hand: 2 cards     → OPPORTUNITY: Damage ICE can flatline
Runner missing Sentry    → Install agenda behind Sentry ICE
```

**Decision flowchart:**
```
Start of turn:
├─ R&D unprotected? → Install ICE on R&D (highest priority)
├─ HQ has 2+ agendas? → Install in remotes, don't draw
├─ Runner poor/incomplete rig? → SCORING WINDOW: Install, advance, score
├─ Runner rich + complete rig? → Fast advance or trap defense
└─ Default → Build economy, install ICE, jam remotes
```

**Scoring priority:**
```
Higher value = fewer scoring windows needed to win
But: Lower cost = faster to score. 5/3 needs multiple fast-advances on one turn even if installed.
Balance: Score what you can when windows appear
```

**Emergency situations:**
```
Runner at game point → They need 1 score to win, defend ALL servers
HQ flooded (3+ agendas) → Install all, ICE HQ, score fastest
Poor (< 3 credits) → Play economy cards, click for credits, can't rez ICE
R&D naked + Runner has rig → ICE R&D immediately or lose to pressure
```


**Remember:** You control the tempo. Create scoring windows, exploit Runner weaknesses, and jam constantly.

Score fast → win before Runner's statistical advantage takes over.
