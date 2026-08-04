# Answer: servers-001-runner

## Critical Insight: Tithe Has No ETR!

**Tithe's subroutines:**
1. ↳ Do 1 net damage.
2. ↳ Gain 1[credit].

**Neither subroutine ends the run!** You can walk through Tithe by breaking only the
damage sub ($1) — or by breaking *nothing at all* and taking 1 net damage. Sub 2 giving
the Corp a credit is irrelevant to you.

---

## Breaking Costs

**Tithe (Sentry, Str 1):**
- Carmen Str 2 > Tithe Str 1 (no boost needed)
- Break damage sub only: **$1**
- Or pay **1 card** (tank it) and spend nothing

**Whitespace (Code Gate, Str 0):**
- Unity Str 1 > Whitespace Str 0 (no boost needed)
- Break both subs: **$2** — sub 2 is a conditional ETR and must be beaten (you will be
  at or below $6 for most of this turn, so it *would* fire)

**Carmen install:** $5 base, **$3** after any successful run.

---

## Q1: Guaranteed Accesses — 5 (no draw luck)

The Jailbreak in hand is guaranteed; the ones in the stack are not. Tithe's lack of an
ETR means HQ runs can be made **free**, paying with cards instead of credits.

| Click | Action | Cost | Credits | Grip | Accesses |
|-------|--------|------|---------|------|----------|
| 1 | Jailbreak R&D, break Whitespace ×2 | $2 | $5 | 2 (Carmen + drawn card) | **2** |
| 2 | Run HQ, tank Tithe (1 net) | $0 | $5 | 1 | **1** |
| 3 | Run HQ, tank Tithe (1 net) | $0 | $5 | 0 | **1** |
| 4 | Run R&D, break Whitespace ×2 | $2 | $3 | 0 | **1** |

**Total: 5 accesses**, ending on $3 with an empty grip.

Note the click-4 access re-reads the top of R&D — the same card seen on click 1 — so
this is 5 access *events* but 4 distinct cards. A defensible alternative that trades an
access for safety:

| Click | Action | Cost | Credits | Accesses |
|-------|--------|------|---------|----------|
| 1 | Jailbreak R&D, break Whitespace ×2 | $2 | $5 | **2** |
| 2 | Install Carmen (discounted) | $3 | $2 | — |
| 3 | Run HQ, Carmen breaks damage sub | $1 | $1 | **1** |
| 4 | Run HQ, Carmen breaks damage sub | $1 | $0 | **1** |

**4 accesses, zero damage, Carmen installed.** Correct if you expect a trap or damage
follow-up; the 5-access line ends at 0 cards and dies to any net damage.

---

## Q2: Maximum With Cooperating Draws — 6

Each Jailbreak draws a card. If those draws find the other Jailbreaks in the stack, they
chain:

| Click | Action | Cost | Credits | Result |
|-------|--------|------|---------|--------|
| 1 | Jailbreak R&D (break Whitespace) | $2 | $5 | Access 2, draw 1 → *Jailbreak*, discount active |
| 2 | Install Carmen | $3 | $2 | Killer installed |
| 3 | Jailbreak HQ (break damage sub only) | $1 | $1 | Access 2, draw 1 → *Jailbreak* |
| 4 | Jailbreak HQ (break damage sub only) | $1 | $0 | Access 2 |

**Total: 6 accesses** (2 R&D + 4 HQ), $0 remaining, no damage taken.

**$7 is precisely calibrated:** $2 + $3 + $1 + $1 = $7 exactly.

This line is *not* guaranteed — it needs both draws to hit Jailbreaks. State the
dependency; a solver who assumes the chain without flagging it has answered Q2 by luck.

---

## Why This Works

1. **Partial breaking Tithe:** no ETR, so you choose your currency — $1 (Carmen), or
   1 card (tank), or $0 and 1 card if you have no Killer at all.
2. **Carmen discount timing:** any successful run first (the Jailbreak) drops Carmen
   to $3.
3. **Jailbreak is gas:** +1 access *and* a card, for $0.
4. **Order matters:** R&D first enables the discount and sees fresh cards before HQ.

---

## Wrong Lines (Common Mistakes)

**Breaking both Tithe subs every run ($2 each):**
Wastes $1/run to stop the Corp gaining $1. You still access either way.

**Installing Carmen first without the discount:**
$5 leaves $2 — one Whitespace run and nothing else.

**Repeating R&D without noting staleness:**
Extra R&D runs re-read the same top card unless the Corp has drawn. Counting those as
new information is the trap.

**Tanking Tithe with an empty grip:**
1 net damage against 0 cards is a flatline. The 5-access line ends at exactly 0 — a
third tank kills you.

---

## Key Takeaways

1. **Read the subs:** not all ICE has an ETR. Tithe only taxes.
2. **Choose your currency:** credits, cards, and clicks are interchangeable at the
   margin. Tithe's damage sub is cheap when your grip is expendable and lethal when
   it isn't.
3. **Distinguish guaranteed from best-case.** "Maximum accesses" answers that depend on
   draws must say so.
4. **Run Tables help:** analyze each sub — ETR (must break), damage (price it against
   your grip), economy (usually ignore).
