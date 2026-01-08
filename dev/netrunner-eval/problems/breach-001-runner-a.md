# Answer: breach-001-runner

## The Setup

You have $4 and three cards: Sure Gamble, Overclock, Mayfly.

**First observation:** Sure Gamble costs $5. You have $4. It looks unplayable... *unless* you find another credit first.

**Second observation:** Manegarm accepts 2 clicks OR $5. This creates resource flexibility.

**Third observation:** Brân can be clicked through (Bioroid) OR broken with Mayfly ($8).

These three observations unlock three completely different paths.

---

## Run Table: Server 1

**ICE:**
| ICE | Type | Str | Subs | Payment Options |
|-----|------|-----|------|-----------------|
| Brân 1.0 | Barrier | 6 | Install, ETR, ETR | 3 clicks ($0) OR Mayfly ($8) |

**Warning:** Sub 1 installs ICE from HQ. Corp has **Karunā** in HQ!

**Why this blocks "skip sub 1" lines:**

| "Skip sub 1" attempt | Why it fails |
|---------------------|--------------|
| Break Karunā with Mayfly | Boost $2 + break $2 = $4. Pool left: $4. Manegarm needs $5. **$1 short!** |
| Tank Karunā damage | 4 damage (2+2), grip is 2-3 cards. **Flatline!** |

Karunā is precisely calibrated: expensive enough that you can't afford Manegarm after, lethal enough that you can't tank. All valid solutions must break sub 1.

**Upgrade:**
| Card | Trigger | Payment Options |
|------|---------|-----------------|
| Manegarm Skunkworks | Approach server | 2 clicks OR $5 |

---

## Q1: Overclock First (Clicks for Brân)

| Step | Action | Clicks | Real $ | OC $ | Notes |
|------|--------|--------|--------|------|-------|
| Start | — | 4 | $4 | — | — |
| Click 1 | **Overclock** | 3 | $4 | $5 | Run S1 |
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
| Start | — | 4 | $4 | — | — |
| Click 1 | **Install Mayfly** | 3 | $3 | — | Different! |
| Click 2 | Overclock | 2 | $3 | $5 | Run S1 |
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
| Start | — | 4 | $4 | — |
| Click 1 | **Take credit** | 3 | $5 | NOW SG IS PLAYABLE! |
| Click 2 | Sure Gamble | 2 | $9 | The trap was a trick |
| Click 3 | Install Mayfly | 1 | $8 | — |
| Click 4 | Overclock | 0 | $8 + $5 = $13 | Run S1 |
| — | Boost Mayfly +5 | 0 | $8 | From OC |
| — | Break 3 subs | 0 | $5 | $3 from real pool |
| — | Manegarm ($5) | 0 | $0 | Exact! |
| — | Access | 0 | $0 | **STEAL** |

**Resource allocation:** Credits → Everything (Brân + Manegarm)

**Cards used:** ALL THREE! Sure Gamble + Overclock + Mayfly

---

## The Three Paths

| Q | Click 1 | Brân Payment | Manegarm Payment | Cards Used | End State |
|---|---------|--------------|------------------|------------|-----------|
| 1 | Overclock | 3 clicks | $5 | OC | $4, 0 clicks |
| 2 | Mayfly | $8 | 2 clicks | OC + MF | $0, 0 clicks |
| 3 | Credit | $8 | $5 | ALL THREE | $0, 0 clicks |

---

## Why Each Path Works

**Q1 insight:** Bioroid clicks are free. Use them for Brân, save Overclock's $5 for Manegarm.

**Q2 insight:** Manegarm accepts clicks. Use credits for Brân (Mayfly), pay Manegarm with your remaining 2 clicks.

**Q3 insight:** Sure Gamble isn't dead—it's *one credit away* from being playable. Taking a credit as Click 1 unlocks a completely different economy path that uses all three cards.

---

## Common Mistakes

| Mistake | Why It Fails |
|---------|--------------|
| "Sure Gamble is unplayable" | True at $4, false at $5 |
| Finding only one path | Misses resource fungibility |
| Finding only two paths | Misses the "click for credit" unlock |
| Skipping Brân sub 1 | Corp installs Karunā! $4 to break + $5 Manegarm = $9, have $8. Or tank 4 damage = flatline. |
| Mixing resource allocations wrong | E.g., clicking 2 Brân subs + Mayfly 1 sub = not enough for Manegarm |

---

## Difficulty

**Hard.** This puzzle requires:

1. Recognizing Sure Gamble is *conditionally* playable
2. Understanding Bioroid click-breaking
3. Understanding Manegarm's OR (not AND)
4. Finding three distinct resource allocations
5. Precise accounting across all three paths
6. The "aha" that taking a credit enables an entirely new line

The question structure ("find three different Click 1s") is the real test. Most solvers will find Q1, struggle with Q2, and never consider Q3.
