# Answer: break-001-runner

## Expected Reasoning

**What breakers do I need?**
- Whitespace is a **Code Gate** → needs **Decoder** (Unity)
- Palisade is a **Barrier** → needs **Fracter** (Cleaver)
- Carmen is a **Killer** (breaks Sentries) → **not needed**, no Sentries present

**Break cost calculation:**

Whitespace (str 0, 2 subs):
- Unity str 1 ≥ Whitespace str 0 → no boost needed
- Break 2 subs: $2
- Total: **$2**

Palisade (str 2 + 2 on remote = str 4, 1 sub):
- Cleaver str 3 < Palisade str 4 → boost +1 ($2)
- Break 1 sub: $1
- Total: **$3**

**Total run cost:** $2 + $3 = **$5**

**Install costs:** Cleaver $3 + Unity $3 = **$6**

**Total needed:** $5 (break) + $6 (install) = **$11**

**Economy:** Start $7, Sure Gamble costs $5 gives $9 → $7 - $5 + $9 = **$11** ✓

## Answer

```
Start: $7, 4 clicks

Click 1: Sure Gamble → $7 - $5 + $9 = $11
Click 2: Install Cleaver ($3) → $8
Click 3: Install Unity ($3) → $5
Click 4: Run Server 1
  - Encounter Whitespace (str 0):
    - Unity str 1, no boost needed
    - Break sub 1 ($1) → $4
    - Break sub 2 ($1) → $3
  - Encounter Palisade (str 4):
    - Cleaver str 3, boost +1 ($2) → $1
    - Break ETR ($1) → $0
  - Access card

End: $0, 0 clicks, accessed!
```

## Common Mistakes

- **Installing Carmen** → Costs $5, no Sentries to break, now you can't afford the run
- **Wrong install order** → If you run before installing, you have no breakers
- **Forgetting Sure Gamble first** → $7 - $3 - $3 = $1, can't afford $5 break cost
- **Miscounting Palisade strength** → It's str 4 on remote (base 2 + 2 bonus), not str 2

## Key Insight

Match breaker types to ice types:
- **Fracter** breaks **Barrier**
- **Decoder** breaks **Code Gate**
- **Killer** breaks **Sentry**

Carmen is a trap in this problem - there's no Sentry ice, so installing it wastes $5 and a click you can't afford.
