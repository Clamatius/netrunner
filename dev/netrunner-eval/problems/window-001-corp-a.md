# Answer: window-001-corp

## Key Analysis

### Q1: Can the Runner contest Server 1?

**Yes — cheaply. Brân is a tax, not a wall.** This is the problem's main trap, and it
has two independent parts.

**Part 1 — Brân only has TWO end-the-run subroutines.**

```
↳ You may install 1 piece of ice from HQ or Archives directly inward.   ← not an ETR
↳ End the run.
↳ End the run.
```

Sub 1 does not stop the run. A Runner willing to let it fire needs to beat only two
subroutines.

**Part 2 — the Runner has more money than $7.** Pennyshaver: `[click]: Place 1 credit
on this hardware, then take all credits from it.` With $2 hosted that is **+$3 for one
click → $10**.

**Actual cost to get through Brân** (Cleaver str 3 vs Brân str 6; boost $2 per +1;
`1[credit]: Break up to 2 barrier subroutines`):

| Method | Clicks | Credits | Affordable? |
|--------|--------|---------|-------------|
| Click-break both ETRs (let sub 1 fire) | 1 run + 2 = **3** | **$0** | Yes — and leaves a click |
| Click-break all 3 subs | 1 run + 3 = 4 | $0 | Yes (whole turn) |
| Cleaver both ETRs (let sub 1 fire) | **1** | $6 boost + $1 = **$7** | Yes — *exactly* their pool |
| Cleaver all 3 subs | 1 | $6 + $2 = $8 | Yes after a Pennyshaver click ($10) |

The old claim — "$8 needed, Runner has $7, they literally cannot afford it" — is wrong
twice over: they can decline to break sub 1 ($7 exactly), and they can click Pennyshaver
for $10 anyway.

**What sub 1 is actually worth (the gamble, from both sides):**

If the Runner lets sub 1 fire and the Corp *does* hold ICE, the Corp installs it
**directly inward, ignoring all costs** — a free inner ICE on the scoring remote, no
install click and no install cost paid, and every future run on that server is far more
expensive. That is the risk the Runner takes, and it is why the safe line breaks all three.

**But on this board the Corp holds no ICE.** HQ is `[Send a Message, Superconducting Hub,
Nico Campaign, Urtica Cipher, Hedge Fund]` — every card is an agenda, asset, upgrade, or
operation. Sub 1 is **blank**, and the Corp cannot reinforce Server 1 at all this turn.
The Runner doesn't know that; the Corp does, and must plan on the assumption that a
Runner who guesses right gets in for 3 clicks and $0.

---

### Q2: What's the HQ risk?

**HQ is the real crisis, and it is cheap to attack.**

Palisade only gets +2 strength *while protecting a remote*. On HQ it is **strength 2**,
below Cleaver's 3 — no boost needed, break is **$1 flat**. HQ costs one click and one
credit per look.

**Behind that $1 door:** 2 agendas in 5 cards, worth 4 points.

| Card accessed | Runner result |
|---------------|---------------|
| Send a Message (3 pts) | Runner 3 → 6 |
| Superconducting Hub (1 pt) | Runner 3 → 4 |
| Anything else (3 of 5) | Nothing |

**40% per access, at $1 a go.** Stealing *both* wins them the game (3 + 4 = 7).

| HQ Size | Agenda access risk |
|---------|--------------------|
| 5 cards | 40% |
| 4 cards | 50% |
| 3 cards | 67% |

---

### Q3: How do choices affect risk?

Two forces pull against each other:

1. **Cards in HQ are shields.** Playing Hedge Fund or installing a non-agenda *raises*
   the density of what's left.
2. **Installing an agenda removes it from the random-access pool entirely** — no longer
   stealable off a $1 HQ run, only by contesting the remote.

Installing **Superconducting Hub** does both at once: HQ drops to 4 cards but from 2
agendas to 1, so density falls 40% → 25%, and the maximum HQ haul falls from 4 points
(game-winning) to 3 (not). Playing **Hedge Fund** is the worst of both: −1 shield, no
agenda removed, and $11 is already ample.

---

## Optimal Line

**The Corp's win condition is Send a Message** — at 4 points, scoring it is 7 and the
game. Superconducting Hub only reaches 5. There is no Seamless Launch in hand, so SaM
needs five hard advancements, and it cannot be protected by an ICE that costs $7 or
3 clicks to pass. Jamming SaM here loses it.

**Recommended: install Urtica Cipher in Server 1, advance, advance.** (End: $9.)

At 4 points, *any* 2-counter card in your remote is a must-check — if it were Send a
Message you would score it next turn and win, so the Runner cannot assume bluff. When
they pay the Brân tax and access, Urtica at 2 counters does **2 + 2 = 4 net damage**
into a 4-card grip: empty hand, no steal, most of their turn gone. You jam the real
agenda next turn against an empty grip.

**The honest costs:** if they read the bluff and ignore it, you have spent a turn and $2
and still hold 2 agendas in a $1-to-break HQ. And even when they *do* check via the
3-click line, they keep 1 click and $7 — enough for one $1 HQ poke at 40%.

**Defensible alternative: install Superconducting Hub in Server 1, advance, advance.**
Cuts HQ's maximum haul from 7-point lethal to 3, and scores next turn if unchecked
(+1 point, draw 2, refilling HQ's shields). If they check, they spend their whole turn
and their credits for a single point — an acceptable trade. Prefer this if you would
rather de-risk HQ than gamble on a bluff being called.

Both lines score as correct. What is **not** correct is any line whose reasoning rests
on "the Runner can't get into Server 1."

---

## Wrong Lines

### The "Hedge Fund First" Trap
Hedge Fund ($15), install, advance — HQ drops to 3 cards with both agendas still in it
(67% per access). The $4 does nothing this turn; you are already at $11.

### The "Jam Send a Message" Trap
SaM needs 3 more advancements with no Seamless in hand, so it sits for a full Runner
turn behind a $7 ICE. Stealing it puts them at 6 and removes your only route to 7.

### The "Brân is a wall" Trap
Any plan that assumes Server 1 is safe. It costs 3 clicks and $0, or one click and $7.

---

## Key Takeaways

1. **Read every subroutine, not just the count.** Brân is a 3-sub ICE with only 2 ETRs.
   Its first subroutine is a *conditional* threat — worthless when your hand holds no
   ICE, devastating when it does.
2. **Bioroid ICE is priced in clicks.** "They can't afford it" is meaningless against an
   ICE that can be paid with clicks.
3. **Check the board for hidden economy.** Pennyshaver turns $7 into $10 for one click.
4. **Palisade is strength 2 on centrals.** The card that taxes $3 on a remote taxes $1
   on HQ.
5. **Installing an agenda is a defensive act.** It leaves the random-access pool, and its
   points stop being available to a lucky $1 run.
