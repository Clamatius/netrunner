# Answer: partial-001-runner

## The Key Insight

**Subroutines don't have to be broken.** You can let some fire if you can survive the consequences.

Karunā's subroutines:
- Sub 1: "Do 2 net damage. **The Runner may jack out.**"
- Sub 2: "Do 2 net damage."

If you break sub 2 but let sub 1 fire, you take 2 damage but DON'T have to jack out. You can continue the run.

---

## Run Table: Server 1

| ICE | Type | Str | Breaker | Boost | Break | Full Cost |
|-----|------|-----|---------|-------|-------|-----------|
| Karunā | Sentry | 3 | Carmen (2) | $2 (+3) | $1/sub | $4 |
| Palisade | Barrier | 4* | Cleaver (3) | $2 (+1) | $1 | $3 |

*Palisade gets +2 str on remotes (2 → 4)

---

## Q1: Full Break Line

| Step | Action | Cost | Pool | Grip |
|------|--------|------|------|------|
| Start | — | — | $7 | 3 |
| Karunā | Boost Carmen +3 | $2 | $5 | 3 |
| Karunā | Break sub 1 | $1 | $4 | 3 |
| Karunā | Break sub 2 | $1 | $3 | 3 |
| Palisade | Boost Cleaver +1 | $2 | $1 | 3 |
| Palisade | Break sub 1 | $1 | $0 | 3 |
| Access | Steal agenda | — | $0 | 3 |

**End state:** $0, 3 cards in grip

---

## Q2: Partial Break Line

| Step | Action | Cost | Pool | Grip |
|------|--------|------|------|------|
| Start | — | — | $7 | 3 |
| Karunā | Boost Carmen +3 | $2 | $5 | 3 |
| Karunā | Break sub 2 only | $1 | $4 | 3 |
| Karunā | **Sub 1 fires:** 2 damage | — | $4 | 1 |
| — | (May jack out → decline) | — | $4 | 1 |
| Palisade | Boost Cleaver +1 | $2 | $2 | 1 |
| Palisade | Break sub 1 | $1 | $1 | 1 |
| Access | Steal agenda | — | $1 | 1 |

**End state:** $1, 1 card in grip

---

## Q3: The Tradeoff

**Choose Q2 (partial break) when:**
- Cards in grip are useless (already installed your rig)
- You need that extra $1 (trash an asset, pay an upgrade)
- You're broke and $0 remaining is dangerous
- Corp has no kill threat (Punitive, Neural EMP)

**Choose Q1 (full break) when:**
- Cards in grip are valuable (Sure Gamble, second breaker)
- Corp might punish low hand size (damage threats)
- You have money to spare
- One of those cards is your last copy of a critical piece

**In this specific position:** The grip is described as "nothing critical" — you've already set up. Q2 is probably correct. Trading 2 cards for $1 when those cards are chaff is good value.

---

## The Fundamental Lesson

**Subroutines are a tax, not a wall.**

Many players (especially beginners and AI) think "I must break all subroutines." But:
- Damage subs: Pay with cards if cards are cheap
- Credit loss subs: Pay if you're rich enough
- Tag subs: Pay if you can clear or don't care
- ETR subs: Must break (or the run ends)

The question isn't "can I break this?" — it's "what's the cheapest way through that leaves me in acceptable shape?"

---

## Common Mistakes

| Mistake | Why It's Wrong |
|---------|----------------|
| Always breaking everything | Overpaying when damage is affordable |
| Tanking when cards are valuable | Losing key pieces to save $1 |
| Forgetting "may jack out" | Sub 1 gives you a choice — you can stay! |
| Not checking both lines | Missing the cheaper option |
