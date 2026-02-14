# Answer: remote-001-corp

## The Trap

**Corp's reasoning:** "4 ICE protecting the remote, Runner has $2 and only Cleaver (no Killer, no Decoder). Obviously safe!"

**The flaw:** Missing breakers don't matter if you can afford to tank the subroutines.

---

## Run Table: Server 1

**ICE (outer to inner):**
| ICE | Type | Str | Subs | Break Cost | Tank Cost |
|-----|------|-----|------|------------|-----------|
| Whitespace | Code Gate | 0 | Lose $3, ETR if ≤$6 | No decoder! | $3 (need $7+ after) |
| Tithe | Sentry | 1 | 1 net damage, Corp +$1 | No killer! | 1 card |
| Palisade | Barrier | 4* | ETR | Cleaver: $2 boost + $1 = $3 | — |
| Palisade | Barrier | 4* | ETR | Cleaver: $3 | — |

*Palisade gets +2 strength on remotes (2 → 4)

**Total to breach:** $3 (Whitespace) + 1 card (Tithe) + $3 + $3 = **$9 + 1 card, need $10+ entering**

**Key insight:** Whitespace sub 2 says "6 or less" — with $7+ after losing $3, Runner survives!

---

## Q1: Can Runner Breach?

**Yes.** Here's the line:

| Step | Action | Pool | Pennyshaver | Red Team | Notes |
|------|--------|------|-------------|----------|-------|
| Turn start | Smartware trigger | $3 | $2 | $12 | +$1 from Smartware |
| Click 1 | Red Team → Archives | $6 | $3 | $9 | +$3 Red Team, +$1 Pennyshaver |
| Click 2 | Run Archives | $6 | $4 | $9 | +$1 Pennyshaver |
| Click 3 | Click Pennyshaver | $11 | $0 | $9 | Place $1, take $5 |
| Click 4 | **Run Server 1** | $11 | $0 | $9 | — |

**The Run:**
| ICE | Action | Pool After | Notes |
|-----|--------|------------|-------|
| Whitespace | Tank sub 1 | $8 | Lose $3. Sub 2: $8 > $6, continue! |
| Tithe | Tank both subs | $8 | 1 damage (discard grip), Corp +$1 |
| Palisade | Break with Cleaver | $5 | Boost $2 + break $1 |
| Palisade | Break with Cleaver | $2 | Boost $2 + break $1 |
| **Breach** | Access Regolith | $2 | (or Offworld if installed!) |

---

## Q2: Should You Install Offworld?

**No!** The remote is not safe.

If you install Offworld and advance:
- Runner breaches with the line above
- Steals Offworld (2 points)
- You wasted a click and a credit for nothing

**Better lines:**
1. **Credit, Credit, Credit** — Build to $11, reassess next turn
2. **Install Nico in Server 2** — Force Runner to choose: trash Nico ($2) or let you gain $9
3. **Click Regolith 3x** — Take $9, Regolith empties and trashes, but you're at $17

The remote *looks* safe (4 ICE, no breakers!) but Red Team + Pennyshaver + tanking = $11, exactly enough.

---

## Why This Is Hard

1. **Breaker gap illusion** — "No decoder means Whitespace stops them" is wrong when rich
2. **Whitespace threshold** — "6 or less" means $7+ survives, easy to miscount
3. **Red Team math** — Clicking Pennyshaver vs running twice for Pennyshaver credits
4. **Corp perspective** — Must calculate Runner's optimal line, not just "they can't break my ICE"

---

## Common Mistakes

| Mistake | Why It's Wrong |
|---------|----------------|
| "No decoder = safe" | Whitespace sub 2 doesn't fire at $7+ |
| "Runner only has $2" | Red Team + Pennyshaver = $11 in one turn |
| Forgetting Smartware trigger | Turn-start +$1 matters for threshold |
| Miscounting Whitespace | "6 or less" vs "less than 6" — $6 gets ETR'd, $7 doesn't |
