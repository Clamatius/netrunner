# Answer: scoring-002-corp

## Breaking Cost Analysis

**Runner's icebreakers (all installed):**
- Carmen (Killer, Str 2): 2c: +3 str, 1c: break 1 sentry sub
- Cleaver (Fracter, Str 3): 2c: +1 str, 1c: break up to 2 barrier subs
- Unity (Decoder, Str 1): 1c: +3 str (3 icebreakers), 1c: break 1 code gate sub

**Breaking costs per ICE:**
- Tithe (Sentry, Str 1): Carmen at Str 2 > 1. Break 2 subs = $2
- Brân 1.0 (Barrier, Str 6): Cleaver +3 ($6), break 3 subs ($2) = $8
- Palisade (Barrier, Str 4 on remote): Cleaver +1 ($2), break 1 sub ($1) = $3

**Server 1 total: $13** (Tithe + Brân + Palisade)

## Q1: Runner's Grip Unknown

**Why Skunkworks is necessary:**

Runner can build significant economy over one turn:
- Pennyshaver click: place 1 ($3), take all = $5
- If grip contains Sure Gamble: $5 - 5 + 9 = $9
- If grip contains Overclock: $9 - 1 = $8 + 5 temp = $13 for run

$13 exactly covers Server 1's ICE! Without additional protection, a well-built Runner can break in.

**Skunkworks defense:**
"End the run unless they spend [click][click] or pay 5[credit]"

Even if Runner can afford the ICE, they've spent clicks building economy:
- Pennyshaver (1 click) + Sure Gamble (1 click) + Overclock (1 click) = 3 clicks used
- Only 1 click remaining for the run itself
- 0 clicks available for Skunkworks' 2-click requirement
- Not enough credits left for the $5 alternative

**Optimal Corp line:**

Turn 1:
1. Install Send a Message in Server 1
2. Install Manegarm Skunkworks in Server 1
3. Take credit → $6

Turn 2:
1. Seamless Launch ($1, +2 counters) → $5, SaM at 2/5
2. Seamless Launch ($1, +2 counters) → $4, SaM at 4/5
3. Advance ($1) → $3, SaM at 5/5
4. Score Send a Message! **Corp wins 6-3**

## Q1 Answer

**Install Send a Message in Server 1, install Manegarm Skunkworks in Server 1, take a credit.**

**Bonus - the bluff:** Runner sees 2 cards installed in the heavily-protected remote. No advancement counters visible. Could be asset + upgrade, could be anything. The imminent game loss is not obvious!

---

## Q2: Runner Has Sure Gamble + Overclock

**Runner's maximum economy line:**
- Click 1: Pennyshaver → $5
- Click 2: Sure Gamble → $9
- Click 3: Credit → $10
- Click 4: Overclock ($1) → $9 real + 5 temp = $14 for run

**Can Runner break Server 1?**
- Tithe: $2 → $12 remaining
- Brân: $8 → $4 remaining
- Palisade: $3 → $1 remaining

Yes! $14 > $13. Runner can break all ICE.

**But Skunkworks still saves Corp:**
- Runner approaches server after passing all ICE
- Must pay 2 clicks OR $5
- Runner has 0 clicks remaining (used all 4)
- Runner has $1 remaining (not $5)
- **Run ends!**

## Q2 Answer

**No, the answer doesn't change.**

Skunkworks was already the correct play in Q1. With the best Corp line, even a Runner who knows Sure Gamble + Overclock cannot get in:
- They have enough credits to break the ICE ($14 vs $13 needed)
- But they're out of clicks for Skunkworks (spent 4: Pennyshaver, Sure Gamble, Credit, Overclock)
- And they're out of credits for the $5 alternative ($1 remaining)

The Runner is close (only $2 short on the credit path, or 2 clicks short on the click path), but can't quite get there.

---

## Why This Is Hard

**Models must recognize:**

1. **Economy projection:** Runner at $2 can reach $14 with the right cards (Pennyshaver + Sure Gamble + Overclock)

2. **Skunkworks timing:** It triggers on approach (after passing ICE), so click expenditure during the turn matters

3. **Click counting:** 4 clicks total - if 3+ are used for economy/run events, Skunkworks' 2-click cost becomes unpayable

4. **The double-advance trap:** Advancing twice seems like faster progress, but leaves the server vulnerable to the $14 all-in

5. **Efficient card use:** The Skunkworks line uses both Seamless Launch (otherwise dead cards after Turn 1)

## Strategic Concepts Demonstrated

1. **Defensive upgrades are load-bearing:** Skunkworks isn't "nice to have" - it's the difference between winning and losing. The ICE alone isn't enough.

2. **Never-advance even a 5/3:** Counter-intuitive! Installing 2 cards with 0 advancement tokens hides that one is the winning agenda. The Runner sees "probably asset + upgrade" not "I lose next turn."

3. **Runner sandbagging:** The $2 board state looks weak but hides $14 of potential. Corp must respect the worst-case grip, not the visible credits.

4. **Sure Gamble + Overclock is maximum threat:** These 2 cards provide $9 of effective run economy (+4 from Gamble, +5 temp from Overclock). This is the ceiling for 2-card Runner hands.

5. **The Regolith trap:** Installing Skunkworks + Regolith Mining License seems safe ("I'll bluff and get rich"), but:
   - Doesn't win next turn
   - Leaves Send a Message in HQ (5 cards)
   - Docklands Pass = 2 accesses = 40% to lose the game
   - Economy doesn't matter if you're dead

6. **Math gates the answer:** You can prove the Runner mathematically cannot get into a Skunkworks-protected Server 1 with any 2-card hand from their decklist. But ONLY with the upgrade - without it, optimal Runner play wins.

## Common Mistakes

- Double-advancing without Skunkworks (loses to Sure Gamble + Overclock all-in)
- Not recognizing Runner can reach $14 through optimal economy sequencing
- Miscounting Skunkworks requirements (2 clicks OR $5, not both)
- Installing Skunkworks in a naked remote (ICE tax is what exhausts Runner's clicks)
- Missing the bluff value (2 unadvanced cards hides the winning threat)
- Installing Regolith instead of SaM ("safe economy" that risks 40% game loss)
