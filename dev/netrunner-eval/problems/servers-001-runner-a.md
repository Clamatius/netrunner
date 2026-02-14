# Answer: servers-001-runner

## Critical Insight: Tithe Has No ETR!

**Tithe's subroutines:**
1. ↳ Do 1 net damage.
2. ↳ Gain 1[credit].

**Neither subroutine ends the run!** You can walk through Tithe, breaking only the damage sub ($1) and letting Corp gain a credit. This changes everything.

---

## Breaking Costs

**Tithe (Sentry, Str 1):**
- Carmen Str 2 > Tithe Str 1 (no boost needed)
- Break damage sub only: **$1** (let sub 2 fire - Corp gains $1, you access!)
- Break both subs: $2

**Whitespace (Code Gate, Str 0):**
- Unity Str 1 > Whitespace Str 0 (no boost needed)
- Break 2 subs: **$2** (sub 2 is conditional ETR, must break)

**Carmen install:**
- Base: $5
- With discount (after successful run): **$3**

---

## Optimal Line: 6 Accesses

**Jailbreak R&D first, then chain Jailbreaks on HQ:**

| Click | Action | Cost | Credits | Result |
|-------|--------|------|---------|--------|
| 1 | Jailbreak R&D (break Whitespace) | $2 | $5 | Access 2, draw 1 (Jailbreak!), discount active |
| 2 | Install Carmen | $3 | $2 | Killer installed |
| 3 | Jailbreak HQ (break damage only) | $1 | $1 | Access 2, draw 1 (Jailbreak!) |
| 4 | Jailbreak HQ (break damage only) | $1 | $0 | Access 2 |

**Total: 6 accesses** (2 R&D + 4 HQ), $0 remaining.

---

## Why This Works

1. **Jailbreak chains:** Each Jailbreak draws a card. If your deck has more Jailbreaks (standard is 3 copies), you draw into them.

2. **Carmen discount timing:** Jailbreak R&D is a successful run, enabling the $2 discount for Carmen.

3. **Partial breaking Tithe:** Since Tithe has no ETR, you only need to break sub 1 (damage) for $1. Sub 2 (Corp gains $1) is irrelevant - you still access!

4. **$7 is precisely calibrated:**
   - Jailbreak R&D: $2
   - Carmen (discounted): $3
   - 2x Jailbreak HQ: $1 + $1 = $2
   - Total: $7 exactly

---

## Wrong Lines (Common Mistakes)

**Breaking both Tithe subs ($2 each run):**
- Jailbreak R&D ($2) → Carmen ($3) → Run HQ ($2) → $0
- Total: 3 accesses (2 R&D + 1 HQ) - wastes $1/run on pointless sub!

**Installing Carmen first without discount:**
- Install Carmen ($5): $2
- Can only afford one $2 Whitespace run
- Can't reach HQ with Carmen breaking!

**Skipping Jailbreak for normal runs:**
- Miss the +1 access AND the card draw
- Miss the Jailbreak chains

**Not recognizing the chain:**
- Only using the 1 Jailbreak in hand
- Missing that draws can find more Jailbreaks

---

## Key Takeaways

1. **Read the subs:** Not all ICE has ETR. Tithe only taxes, never stops you.

2. **Break smart:** Only break subs that matter. Damage matters (grip protection). Corp gaining $1? Who cares!

3. **Jailbreak is gas:** It's a run event that draws cards AND gives +1 access. Chaining Jailbreaks is powerful.

4. **Order matters:** R&D first enables Carmen discount. Then pivot to HQ with cheap breaks.

5. **Run Tables help:** Analyze each sub: ETR (must break), damage (usually break), economy (often ignore).
