# Answer: scoring-002-corp

## Breaking Cost Analysis

**Runner's icebreakers (all installed):**
- Carmen (Killer, Str 2): 2c: +3 str, 1c: break 1 sentry sub
- Cleaver (Fracter, Str 3): 2c: +1 str, 1c: break up to 2 barrier subs
- Unity (Decoder, Str 1): 1c: +3 str (3 icebreakers), 1c: break 1 code gate sub

**Breaking costs per ICE — count clicks as well as credits:**

| ICE | Credit path | Click/hybrid paths | Cheapest |
|-----|-------------|--------------------|----------|
| Tithe (Sentry, Str 1) | Carmen: break 2 subs = $2 | **Tank it**: 1 net damage + Corp gains $1 — no ETR! | ~$0 (1 net) |
| Brân 1.0 (Barrier, Str 6, **Bioroid**) | Cleaver +3 ($6) + 3 subs ($2) = $8 | "Lose [click]: break 1 sub" → **3 clicks = $0**; or 1–2 clicks + Cleaver $7 | 3 clicks |
| Palisade (Barrier, Str 4 on remote) | Cleaver +1 ($2) + 1 sub ($1) = $3 | — (ETR, must break) | $3 |

**CRITICAL: Brân is a bioroid.** Pricing Server 1 at "$13 all-credits" misses that the
Runner can pay Brân with clicks. The cheap way in is: run early, tank Tithe, click
through all of Brân, pay credits only for Palisade.

## Q1: Runner's Grip Unknown

**Why Skunkworks is necessary — the server WITHOUT it falls to a $2 Runner:**

```
Click 1: Overclock ($1) → $1 real + $5 hosted = $6 for the run
  Tithe:    tank both subs (1 net damage — grip 2 → 1, no ETR)
  Brân:     click-break all 3 subs (clicks 2, 3, 4)
  Palisade: Cleaver +1 ($2), break ($1) → $3 left
  Access → steal Send a Message → Runner 4 + 3 = 7. RUNNER WINS.
```

The Runner doesn't need Sure Gamble, doesn't need Pennyshaver, doesn't need $14.
One Overclock (2 copies in their deck) cracks the naked triple-ICE server from $2.
Double-advancing Send a Message without Skunkworks is not a "small risk" — it loses
to a 2-copy card they can easily be holding — and Q2 stipulates they are.

**Why Skunkworks holds — it taxes the SAME resources Brân just drained:**

Skunkworks triggers on approach, *after* the ICE: pay 2 clicks or $5, or the run ends.
Enumerate the Runner's best attempts (4 clicks, $2 + 2 hosted on Pennyshaver):

| Line | Clicks at approach | Credits at approach | Result |
|------|--------------------|---------------------|--------|
| Overclock c1 + 3 click-breaks on Brân | 0 | $3 (after Palisade) | DEAD |
| Penny c1, Overclock c2, 2 click-breaks + Cleaver $7 on Brân | 0 | < $0 (can't even afford Brân+Palisade: $9 vs $10+) | DEAD |
| Penny c1, Sure Gamble c2, Overclock c3, 1 click-break + $7 Brân | 0 | $1 (grip empty → must Carmen Tithe $2) | DEAD |
| Penny c1, SG c2, credit c3, Overclock c4, all-credit $13 break | 0 | $1 | DEAD |
| Keep 2 clicks for Skunkworks → only 2 econ/run clicks | 2 ✓ | ~$9 for $13 of ICE | DEAD (can't pass Brân) |

Brân demands 3 clicks or ~$7–8; Skunkworks demands 2 clicks or $5. Four clicks and
this economy cannot pay both. That's the whole design: **the upgrade and the bioroid
tax the same two currencies from opposite ends.**

**Optimal Corp line:**

Turn 1:
1. Install Send a Message in Server 1
2. Install Manegarm Skunkworks in Server 1
3. Take credit → $6 (keeps the $2 to rez Skunkworks mid-run and still leaves turn-2 money)

Turn 2:
1. Seamless Launch ($1, +2 counters) → $5, SaM at 2/5
2. Seamless Launch ($1, +2 counters) → $4, SaM at 4/5
3. Advance ($1) → $3, SaM at 5/5
4. Score Send a Message! **Corp wins 7-4**

## Q1 Answer

**Install Send a Message in Server 1, install Manegarm Skunkworks in Server 1, take a credit.**

**Bonus - the bluff:** Runner sees 2 cards installed in the heavily-protected remote. No advancement counters visible. Could be asset + upgrade, could be anything. The imminent game loss is not obvious!

---

## Q2: Runner Has Sure Gamble + Overclock

Knowing the grip is Sure Gamble + Overclock confirms the Runner has the *strongest*
possible 2-card hand — and it still doesn't get in.

**Max-credit line:** Pennyshaver ($5) → Sure Gamble ($9) → credit ($10) → Overclock
($9 + 5 = $14): breaks all three ICE for $13, arrives at approach with $1 and 0 clicks.
Skunkworks ends the run.

**Max-click line:** Overclock c1 ($6), tank Tithe, click Brân ×3: arrives with $3
(after Palisade) and 0 clicks. Skunkworks ends the run. (Note: in the SG+Overclock
lines that empty the grip first, tanking Tithe is a **flatline** — 1 net damage
against 0 cards — so those lines pay Carmen $2 for Tithe, making them even poorer.)

Every mix in between fails the same way — see the table above.

## Q2 Answer

**No, the answer doesn't change.** Skunkworks was already load-bearing in Q1; the
known grip just confirms the worst case was real. The Runner is close on every axis
(2 clicks short, or $2–4 short) but no 4-click line covers both Brân and Skunkworks.

---

## Why This Is Hard

**Models must recognize:**

1. **Bioroid click-breaks change the threat model:** Brân is $8 by credits but $0 by
   clicks. Any analysis that prices the server "all-credits" concludes the naked
   server is safe — it isn't. This is the single most common miss.

2. **Tithe is a tax, not a wall:** neither sub ends the run. Tanking 1 net damage is
   usually cheaper than $2 — *unless the grip is empty, when it's lethal.*

3. **Skunkworks timing:** it triggers on approach (after all ICE), so what the Runner
   has *left* is what matters — clicks and credits are both drained by then.

4. **Click counting:** 4 clicks must cover run initiation, Brân's demands, and
   Skunkworks' demands. They can't.

5. **Efficient card use:** the Skunkworks line uses both Seamless Launches and wins
   on turn 2. Nothing slower is acceptable with a 3-point agenda in hand and a
   Runner one steal from 7.

## Strategic Concepts Demonstrated

1. **Defensive upgrades are load-bearing:** Skunkworks isn't "nice to have" — without
   it the server loses to a single Overclock. The ICE alone isn't enough, no matter
   how expensive it looks in credits.

2. **Never-advance even a 5/3:** Counter-intuitive! Installing 2 cards with 0
   advancement tokens hides that one is the winning agenda. The Runner sees "probably
   asset + upgrade" not "I lose next turn."

3. **Runner sandbagging:** The $2 board state looks weak but hides a full break-in.
   Corp must respect the worst-case grip, not the visible credits.

4. **Complementary taxes win:** Brân taxes clicks-or-credits; Skunkworks taxes
   clicks-or-credits *after* Brân has collected. Stacking two half-walls of the same
   kind (two Brâns) would be strictly worse than this pairing.

5. **The Regolith trap:** Installing Skunkworks + Regolith Mining License seems safe
   ("I'll bluff and get rich"), but:
   - Doesn't win next turn
   - Leaves Send a Message in HQ (5 cards)
   - Docklands Pass = 2 accesses = 40% to lose the game (Runner 4 + 3 = 7)
   - Economy doesn't matter if you're dead

6. **Math gates the answer:** You can prove no 4-click line from this board covers
   both Brân and Skunkworks. But ONLY with the upgrade — without it, one Overclock
   plus three clicks walks in.

## Common Mistakes

- Pricing Brân at $8 flat and concluding the naked server is safe (bioroid click-break!)
- Double-advancing without Skunkworks (loses to Overclock + click-through, not just the $14 all-in)
- Treating Tithe as a wall (no ETR — it can be tanked) or tanking it on an empty grip (flatline)
- Miscounting Skunkworks requirements (2 clicks OR $5, not both)
- Installing Skunkworks in a naked remote (the ICE tax is what exhausts the Runner's clicks first)
- Missing the bluff value (2 unadvanced cards hides the winning threat)
- Installing Regolith instead of SaM ("safe economy" that risks a 40% game loss)
