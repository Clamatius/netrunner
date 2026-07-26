# Answer: breach-001-runner

## The Setup

You have $5 and three cards: Sure Gamble, Overclock, Mayfly.

**First observation:** Sure Gamble costs $5. You have $5, but you'd drop to $0 with no way to play more cards. Not useful *unless* you can gain a credit first.

**Second observation:** Manegarm accepts 2 clicks OR $5. This creates resource flexibility.

**Third observation:** Brân can be clicked through (Bioroid) OR broken with Mayfly ($8).

**Fourth observation:** Overclock costs $1 to play, then provides $5 during the run.

These observations unlock three completely different paths.

---

## Run Table: Server 1

**ICE:**
| ICE | Type | Str | Subs | Payment Options |
|-----|------|-----|------|-----------------|
| Brân 1.0 | Barrier | 6 | Install, ETR, ETR | 3 clicks ($0) OR Mayfly ($8) |

**About sub 1 — read the board carefully.** Sub 1 installs ICE *from HQ or Archives*.
The Karunā on the board state is listed under HQ's `ice:` — it is **protecting** HQ, not
sitting *in* HQ. HQ's contents are `cards: 1  # 1 asset`. **The Corp has no ICE to
install, so sub 1 is blank on this board.**

That means letting sub 1 fire costs you nothing *here*, and lines that decline to break
it are legal (see Q4).

**But understand what you are gambling.** When the Corp *does* hold ICE, that subroutine
installs it **directly inward, ignoring all costs** — a free inner ICE, no install click,
no install cost, and every future run on this server gets permanently more expensive.
Getting this wrong late in a game can hand the Corp the server. With the game on the line
and Archives contents unstated, breaking all three subs is the defensible default; the
$0 saving is small and the downside is losing the game.

**Upgrade:**
| Card | Trigger | Payment Options |
|------|---------|-----------------|
| Manegarm Skunkworks | Approach server | 2 clicks OR $5 |

---

## Q1: Overclock First (Clicks for Brân)

| Step | Action | Clicks | Real $ | OC $ | Notes |
|------|--------|--------|--------|------|-------|
| Start | — | 4 | $5 | — | — |
| Click 1 | **Overclock** ($1) | 3 | $4 | $5 | Run S1 |
| — | Click-break sub 1 | 2 | $4 | $5 | Prevent install |
| — | Click-break sub 2 | 1 | $4 | $5 | ETR avoided |
| — | Click-break sub 3 | 0 | $4 | $5 | ETR avoided |
| — | Manegarm ($5) | 0 | $4 | $0 | Pay from OC |
| — | Access | 0 | $4 | $0 | **STEAL** |

**Resource allocation:** Clicks → Brân, Credits → Manegarm

**Cards used:** Overclock only

---

## Q2: Install Mayfly First (Credits for Brân)

| Step | Action | Clicks | Real $ | OC $ | Notes |
|------|--------|--------|--------|------|-------|
| Start | — | 4 | $5 | — | — |
| Click 1 | **Install Mayfly** ($1) | 3 | $4 | — | Different! |
| Click 2 | Overclock ($1) | 2 | $3 | $5 | Run S1 |
| — | Boost Mayfly +5 | 2 | $3 | $0 | 1→6 str |
| — | Break 3 subs | 2 | $0 | $0 | $3 from pool |
| — | Manegarm (2 clicks) | 0 | $0 | $0 | Pay with clicks! |
| — | Access | 0 | $0 | $0 | **STEAL** |

**Resource allocation:** Credits → Brân, Clicks → Manegarm

**Cards used:** Overclock + Mayfly

---

## Q3: Credit First (Unlocks Sure Gamble!)

| Step | Action | Clicks | $ | Notes |
|------|--------|--------|---|-------|
| Start | — | 4 | $5 | — |
| Click 1 | **Take credit** | 3 | $6 | NOW SG IS USEFUL! |
| Click 2 | Sure Gamble | 2 | $10 | $6 - $5 + $9 |
| Click 3 | Install Mayfly | 1 | $9 | — |
| Click 4 | Overclock ($1) | 0 | $8 + $5 = $13 | Run S1 |
| — | Boost Mayfly +5 | 0 | $8 | From OC |
| — | Break 3 subs | 0 | $5 | $3 from real pool |
| — | Manegarm ($5) | 0 | $0 | Exact! |
| — | Access | 0 | $0 | **STEAL** |

**Resource allocation:** Credits → Everything (Brân + Manegarm)

**Cards used:** ALL THREE! Sure Gamble + Overclock + Mayfly

---

## Q4: The Cheapest Line — No Cards At All

You do not need a single card in hand. You start with exactly what the server costs.

| Step | Action | Clicks | $ | Notes |
|------|--------|--------|---|-------|
| Start | — | 4 | $5 | — |
| Click 1 | **Basic run on Server 1** | 3 | $5 | No card played |
| — | Click-break sub 1 | 2 | $5 | (optional here — sub 1 is blank) |
| — | Click-break sub 2 | 1 | $5 | ETR avoided |
| — | Click-break sub 3 | 0 | $5 | ETR avoided |
| — | Manegarm ($5) | 0 | $0 | Exact |
| — | Access | 0 | $0 | **STEAL** |

**Cards used: none.** Sure Gamble, Overclock and Mayfly all stay in hand.

**Cheaper still (declining sub 1):** click-break only the two ETRs, pay Manegarm $5, and
you breach with **1 click to spare** — usable on a free run elsewhere, or on Pennyshaver-
style economy if you had it. On this board that is strictly better; against an unknown
Archives it is the gamble described above.

---

## The Four Paths

| Q | Click 1 | Brân Payment | Manegarm Payment | Cards Used | End State |
|---|---------|--------------|------------------|------------|-----------|
| 1 | Overclock | 3 clicks | $5 | OC | $4, 0 clicks |
| 2 | Mayfly | $8 | 2 clicks | OC + MF | $0, 0 clicks |
| 3 | Credit | $8 | $5 | ALL THREE | $0, 0 clicks |
| 4 | **Basic run** | 3 clicks | $5 | **NONE** | $0, 0 clicks |

---

## Why Each Path Works

**Q1 insight:** Bioroid clicks are free. Use them for Brân, save Overclock's $5 for Manegarm.

**Q2 insight:** Manegarm accepts clicks. Use credits for Brân (Mayfly), pay Manegarm with your remaining 2 clicks.

**Q3 insight:** Sure Gamble is only useful if you can play cards afterward. Taking a credit first → play SG → fund everything with credits → use all three cards.

**Q4 insight:** The starting position already contains the answer — 4 clicks and $5 is
exactly one Brân and one Manegarm. Every card in hand is *optional*. Solvers who reach
for a card first never test whether they needed one.

---

## Common Mistakes

| Mistake | Why It Fails |
|---------|--------------|
| Not realizing Overclock costs $1 | Changes all math! |
| Finding only one path | Misses resource fungibility |
| Finding only two paths | Misses the "click for credit" unlock |
| Assuming Brân sub 1 must be broken | It's the *only* non-ETR sub, and HQ holds no ICE here. Breaking it is prudent, not mandatory — know which you're doing. |
| Misreading the board | Karunā is *protecting* HQ, not *in* HQ. Cards under a server's `ice:` are not part of its contents. |
| Mixing resource allocations wrong | E.g., clicking 2 Brân subs + Mayfly 1 sub = not enough for Manegarm |

---

## Difficulty

**Hard.** This puzzle requires:

1. Recognizing Overclock costs $1 (not free!)
2. Understanding Bioroid click-breaking
3. Understanding Manegarm's OR (not AND)
4. Finding four distinct resource allocations
5. Precise accounting across all four paths
6. The "aha" that taking a credit enables an entirely new line
7. The opposite "aha" (Q4): that no card is needed at all

The question structure ("find different Click 1s") is the real test. Most solvers find
Q1, struggle with Q2, never consider Q3 — and reach for a card in hand before checking
whether the naked position already pays for the server.
