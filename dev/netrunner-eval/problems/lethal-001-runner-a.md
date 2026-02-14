# Answer: lethal-001-runner

## Run Table: HQ

**ICE (Karunā rezzed):**
| ICE | Type | Str | Breaker | Boost | Break | Notes |
|-----|------|-----|---------|-------|-------|-------|
| Karunā | Sentry | 3 | Carmen (2) | $2 (+3) | $1/sub | Sub 1: 2 dmg + jack out. Sub 2: 2 dmg. NO ETR. |

**Break options:**
| Strategy | Boost | Break | Total | Damage | Notes |
|----------|-------|-------|-------|--------|-------|
| Full break | $2 | $2 | $4 | 0 | Safe |
| Partial (tank sub 1) | $2 | $1 | $3 | 2 | Survives with 2+ cards |

**Access (2 cards in HQ):**
| Card | Trash Cost | Priority | Notes |
|------|------------|----------|-------|
| Agenda | — | STEAL | Win condition |
| Manegarm Skunkworks | $3 | MUST | Removes from HQ → guarantees next access |

---

## The Trap: 75% Lines

Without the trash-and-tank optimization, you get probability, not certainty.

**Line A (no discount, full breaks):**
```
Install Carmen ($5): $8
Run HQ, full break ($4): $4 → 50% win, 50% Manegarm
If Manegarm: trash ($3): $1
Run HQ, full break ($4): Need $4, have $1. CAN'T AFFORD.
```

**Line B (discount, full breaks):**
```
Run R&D (free): $13, discount active
Install Carmen ($3): $10
Run HQ, full break ($4): $6 → 50% win, 50% Manegarm
If Manegarm: trash ($3): $3
Run HQ, full break ($4): Need $4, have $3. CAN'T AFFORD.
```

Both lines = **75% win rate** (hit agenda run 1 or run 2 without trashing).

---

## Guaranteed Line: Trash + Partial Break

| Click | Action | Cost | Pool | Hand | Notes |
|-------|--------|------|------|------|-------|
| 1 | Run Archives | $0 | $13 | 3 | Discount active |
| 2 | Install Carmen | $3 | $10 | 2 | Discounted |
| 3 | Run HQ (full break) | $4 | $6 | 2 | Access → Case A or B |
| 4 | Run HQ (tank sub 1) | $3 | $0 | 0 | Guaranteed agenda |

**Click 3 Access:**
```
Case A (Agenda): Steal. WIN.
Case B (Skunkworks): Trash ($3). Pool: $6 - $3 = $3. Continue to Click 4.
```

**Click 4 (Case B only):**
```
Boost Carmen: $2. Pool: $1.
Break Sub 2 only: $1. Pool: $0.
Tank Sub 1: 2 net damage. Hand: 2 → 0. SURVIVE.
Access: Agenda (guaranteed). WIN.
```

**Total cost:** $3 + $4 + $3 + $3 = **exactly $13**

---

## Why This Works

| Run | Break Strategy | Cost | Damage | Result |
|-----|----------------|------|--------|--------|
| Archives | None (unprotected) | $0 | 0 | Enables Carmen discount |
| HQ #1 | Full break | $4 | 0 | 50% win or trash Manegarm |
| HQ #2 | Partial (tank sub 1) | $3 | 2 | Guaranteed agenda access |

**Key insights:**

1. **Trash creates certainty.** Paying $3 to trash Manegarm removes it from HQ, guaranteeing the second access hits the agenda.

2. **Partial break saves $1.** Breaking only sub 2 costs $3 instead of $4 — exactly the margin needed after trashing.

3. **Damage is affordable.** 2 damage with 2 cards in hand = survive with 0 cards.

4. **$13 is precisely calibrated:**
   - Discount: saves $2
   - Full break: $4
   - Trash: $3
   - Partial break: $3
   - Total: $3 + $4 + $3 + $3 = $13

---

## Common Mistakes

| Mistake | Why It Fails |
|---------|--------------|
| Not trashing Manegarm | Leaves 50/50 on second run instead of guaranteed |
| Full break on run 2 | Costs $4, only have $3 after trashing |
| Forgetting Carmen discount | Can't afford the line without Archives run first |
| Fear of 2 damage | 2 cards in hand survives 2 damage at 0 cards |
| Settling for 75% | Missing the trash + partial break combo |
