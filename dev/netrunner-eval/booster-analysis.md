# System Gateway Booster Pack Analysis

Beyond the tutorial decks, each side gets a "booster pack" of additional cards. This analysis covers strategic implications and what they teach/test.

## Runner Booster

| Card | Type | Cost | Summary |
|------|------|------|---------|
| Conduit (2x) | Program: Virus | $4, 1 MU | R&D multi-access that scales with virus counters |
| Leech (2x) | Program: Virus | $1, 1 MU | Strength reduction via virus counters (centrals only) |
| Mutual Favor (2x) | Event | $0 | Search for icebreaker, install if ran this turn |
| DZMZ Optimizer (2x) | Hardware | $2 | +1 MU, first program each turn costs $1 less |
| Wildcat Strike (2x) | Event | $2 | Corp chooses: you gain $6 or draw 4 |

### Card-by-Card

**Conduit** - The win condition. Medium reprint at +$1 cost, but still the R&D lock engine. With manageable break costs on R&D, this forces Corp to either:
- Overdefend R&D (weakening remotes)
- Purge repeatedly (massive tempo loss)
- Rush before it matters (tutorial Corp can't)

At 2+ counters, every R&D run threatens multiple accesses. At 4+, you're seeing a significant chunk of their deck each run.

**Leech** - Datasucker's slightly worse cousin (centrals only, not any server). Still excellent. Converts successful central runs into strength reduction, which destroys expensive boost costs:

- Carmen vs Karuna without Leech: $6 boost + $2 break = **$8**
- Carmen + Leech (3 counters) vs Karuna: $0 boost + $2 break = **$2**

That's a $6 swing per encounter. Corps are even less likely to purge since you're spending counters efficiently rather than stockpiling.

**Mutual Favor** - Deck stabilizer. $0 to search for any icebreaker, and if you ran this turn, install it directly. Fixes the "where's my fracter" problem that loses games. Very strong.

**DZMZ Optimizer** - Trap card for deckbuilders who like "value engines." Math:
- Cost: $2 + 2 clicks (draw + install)
- Return: $1 per program installed
- This deck installs maybe 5-6 programs total
- Net gain: $3-4 best case, over entire game
- **Verdict: First cut in deck construction**

The +1 MU is nice but not worth the tempo investment except in endgame where you decided you want the full rig: 3x breaker, Leech, Conduit.

**Wildcat Strike** - Reverse punisher. Corp chooses which good thing you get:
- Option A: Pay $2, gain $6 = net $4 burst (Hedge Fund efficiency with $2 floor instead of $5)
- Option B: Draw 4 cards

Both outcomes are excellent for Runner. Corp just picks their poison based on current game state (give cards if rushing, give money if they're flooded). See: **The Millstone Trap** below.

### Runner Booster Summary

A coherent pressure package:
- **Conduit**: Proactive win condition that scales with time
- **Leech**: Efficiency that compounds, making ice taxation ineffective
- **Mutual Favor**: Consistency to find the breakers that use Leech

This creates *inevitability*. The longer the game goes, the more Runner favored it becomes.

---

## Corp Booster

| Card | Type | Cost | Summary |
|------|------|------|---------|
| Funhouse (2x) | ICE: Code Gate | Rez $5, Str 4 | Take tag or ETR; sub gives another tag unless pay $4 |
| Public Trail (2x) | Operation | $4 | Give tag unless Runner pays $8 (if they ran last turn) |
| AMAZE Amusements (2x) | Upgrade | Rez $1, Trash $3 | Persistent: 2 tags if Runner steals from this server |
| Orbital Superiority (2x) | Agenda: 4/2 | - | On score: 4 meat damage if tagged, else give 1 tag |
| Retribution (1x) | Operation | $1 | Trash 1 program or hardware (Runner must be tagged) |
| Predictive Planogram (2x) | Operation: Transaction | $0 | Gain $3 or draw 3; if tagged, do both |

### Card-by-Card

**Funhouse** - Data Raven's successor, minus the trace. On encounter: take 1 tag or ETR (mandatory choice). Subroutine: pay $4 or take another tag.

Best placement: **R&D**, not remotes. On a remote, Runner just goes tag-me and steals your agenda (known threat). On R&D, every speculative run costs them significantly.

**The R&D House Tax:**
- Without breaker: 2 tags (clear = 2 clicks + $4) or 1 tag + $4 = roughly 3 clicks + $4
- With Unity (str 4): Take encounter tag, break sub, clear tag = 2 clicks + $3

The 5 rez cost is brutal early. If it's your only R&D ice and Runner's building toward Conduit, you might not afford the window.

**Public Trail** - Econ advantage amplifier. Only good when you're ahead:
- Runner rich: They pay $8, you wasted $4 and a click
- Runner poor: They take tag, you follow up with punishment
- Play only if they ran last turn (timing restriction)

**AMAZE Amusements** - Persistent upgrade that gives 2 tags on steal. Will land tags eventually, but then what? (See: tag punishment problem below)

**Orbital Superiority** - The "I win" card that usually doesn't. 4 meat damage sounds scary but:
- Most Runners have 5 cards
- They need to be tagged AND careless
- If they're not tagged, you just give them 1 tag (enabling your other cards, I guess?)

Don't depend on opponent misplay.

**Retribution** - Trash a program or hardware. The dream is killing their only Cleaver to lock them out. The reality:
- They have Mayfly backup
- They might have duplicate breakers
- You spent a card and click for temporary inconvenience

**Predictive Planogram** - Consolation prize. $3 or draw 3; both if tagged. Fine card, but not a game-winning threat.

### Corp Booster Summary: The Tag Punishment Problem

The Corp booster asks: "What happens when Runner gets tagged?"

**Answer: Not much.**

| Dream | Reality |
|-------|---------|
| Orbital Superiority flatline | Requires misplay + bad hand size |
| Retribution key breaker | They have Mayfly backup |
| Public Trail into punishment | They're probably richer than you |
| AMAZE lands 2 tags | Great, now spend $2 to trash... Telework? |

The tag package is **reactive** - it sits in hand waiting for conditions. Runner's cards are **proactive** - Conduit pressure compounds every turn.

**Resource Trash Targets** (basic action: click + $2 if tagged):

| Resource | Priority | Notes |
|----------|----------|-------|
| Verbal Plasticity | HIGH | 1-of, $3 sunk, +1 draw compounds |
| Red Team | MEDIUM | Only if still loaded |
| Telework Contract | LOW | They click it 3x and cash out before you can respond |

Best case: You blow up their Verbal Plasticity. That's your ceiling.

---

## Overall Assessment

**The booster pack favors Runner.**

Runner gets:
- Proactive pressure (Conduit)
- Compounding efficiency (Leech)
- Consistency (Mutual Favor)
- Cards that scale with time

Corp gets:
- Reactive punishment (requires tags + follow-up)
- Situational cards (dead in hand until conditions met)
- Effects that don't scale
- Dependency on opponent mistakes

**What This Teaches/Tests:**

The Runner booster tests whether the Runner recognizes they **don't have to clear tags**. New players will be scared of Funhouse, AMAZE, and the threat of tag punishment. They'll spend clicks and credits clearing tags defensively.

Correct play: Evaluate the *actual* punishment ceiling. In this card pool:
- No Scorched Earth / lethal damage threat
- Retribution is tempo, not game-ending
- Going tag-me is often viable

The Corp booster tests whether Corp can **rush before inevitability kicks in**. Tutorial Corp isn't built for rushing, which is the problem.

---

## The Millstone Trap

A common beginner error in tactical games, named after the Magic: The Gathering card.

### The General Form

**Treating cards you'll never see as different from cards that don't exist.**

### MTG Version (Millstone)

- **Beginner thinking**: "I mill their deck 2 cards at a time. Eventually they'll deck out and I win!"
- **Reality**: Those milled cards were already "gone" - they were at the bottom of the deck, never to be drawn. You spent mana affecting nothing while your opponent developed their board.

Mill only matters if:
1. It's your dedicated win condition (deck them entirely)
2. You're enabling graveyard synergies
3. You're denying a specific tutor target

Otherwise, milled cards = cards at the bottom of their deck = functionally nonexistent.

### Netrunner Version (Wildcat Strike / Overdraw)

- **Beginner thinking**: "I'll only play Wildcat Strike when my hand is nearly empty, so if Corp gives me draw 4, I won't have to discard."
- **Reality**: Discarding from overdraw is **filtering, not loss**.

Those discarded cards are statistically equivalent to cards at the bottom of your deck - cards you were never going to draw. The only difference:
- You now have **information** (you know what's gone)
- You had **choice** (you picked what to discard)

Waiting for "perfect" hand size is pure downside. You delayed a strong card to avoid a "cost" that isn't real.

### The Heuristic

> Cards you were never going to draw don't exist. Discarding from overdraw is filtering, not loss. Mill effects that don't win the game or enable synergies are irrelevant - treat milled cards as "bottom of deck you never reached."

### Application: Multi-Access

This also applies to Conduit digs and other multi-access:
- You're not "wasting" accesses on non-agendas
- You're filtering non-agendas out of the remaining pool
- Each non-agenda access increases the density of agendas in what's left

The Runner who "got unlucky" accessing 4 non-agendas has actually improved their next run's odds significantly.

---

## Development Notes

### AI Considerations for These Cards

**Runner AI needs:**
- Conduit counter valuation: When is the run worth the tax?
- Tag-me evaluation: When to stop clearing tags?
- Leech counter management: Spend vs stockpile decisions

**Corp AI needs:**
- Funhouse placement heuristics (R&D > remote)
- Purge timing: When are virus counters threatening enough?
- Tag punishment opportunity recognition
- Rush vs glacier decision when facing Conduit

**Key insight for Corp AI:** Recognize "Conduit on table + repeated R&D runs = losing unless something changes." Possible responses:
1. Purge (tempo loss, but resets pressure)
2. Stack R&D (weakens remotes)
3. Rush agendas before lock completes

Tutorial Corp deck lacks fast-advance tools, making option 3 difficult.
