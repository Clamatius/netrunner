# Game 1 Runner Mulligan Decision (Opus 4.6)

## Starting Hand
- Jailbreak (Event, 0 cost)
- Cleaver (Program, 3 cost)
- Mayfly (Program, 1 cost)
- Smartware Distributor (Resource, 0 cost)
- Leech (Program, 1 cost)

Starting credits: 5

## Assessment

### Economy
- **Smartware Distributor** is the sole economy card. It's free to install, costs a click to charge 3 credits, then drips 1 credit per turn passively. Slow but sustainable.
- No burst economy (no Sure Gamble equivalent). Total install cost of the hand is 5 credits (Cleaver 3 + Mayfly 1 + Leech 1 + Smartware 0 + Jailbreak 0), which exactly matches our starting credits but we'd spend all money with no clicks left.
- Realistic turn 1 economy: install Smartware (free), charge it (1 click, gain 3 credits later via drip), leaving flexibility.

### Early Pressure
- **Jailbreak** is an excellent early aggression card. 0 cost, runs HQ or R&D, draws a card and accesses an additional card on success. This is premium turn-1 pressure before the Corp can ICE up.
- **Mayfly** (1 cost AI breaker) can break any single ICE type for one run, then trashes itself. Perfect for punching through early ICE to land Jailbreak.
- **Leech** accumulates virus counters on successful central runs, reducing ICE strength. Synergizes with both breakers to make future runs cheaper.

### Breaker Coverage
- **Cleaver** (fracter, str 3) handles barriers solidly.
- **Mayfly** (AI) is a temporary all-purpose breaker - covers code gates and sentries for one critical run.
- **Missing:** No permanent killer (sentry breaker) or decoder (code gate breaker). After Mayfly trashes, we're vulnerable to sentries and code gates until we draw replacements.

### What's Missing
- Burst economy (Sure Gamble, Dirty Laundry)
- Permanent killer and decoder
- Card draw engine beyond Jailbreak's incidental draw

## Decision: KEEP

## Detailed Reasoning

This hand has a coherent early game plan with meaningful synergies:

1. **Turn 1 plan:** Install Smartware Distributor (0 cost, 1 click), charge it (1 click, loads 3 credits for drip), then either install Leech (1 credit, 1 click) and run with Jailbreak (0 cost, 1 click) -- or save Leech for turn 2 and run twice.

2. **The Jailbreak + Mayfly combo** is strong early pressure. If the Corp hasn't ICE'd R&D, Jailbreak gives us 2 accesses + a card draw for 0 credits. If they have single ICE, Mayfly breaks it for 1 credit (sacrificing itself but landing the multi-access). This is worth it early when stealing even one agenda is game-defining.

3. **Leech + Cleaver synergy** gives us a sustained barrier-breaking plan. Leech tokens reduce ICE strength, making Cleaver even more efficient. This is our mid-game backbone.

4. **Mulligan risk is real.** We'd be gambling away guaranteed early pressure (Jailbreak), a free economy engine (Smartware), and temporary full-breaker coverage (Mayfly). The new hand might have better economy but could easily be worse overall -- no guarantee of breakers or pressure cards.

5. **The economy is thin but workable.** Smartware Distributor provides 1 credit/turn passively after the initial charge click. Combined with clicking for credits when needed, we can fund Leech (1) and Mayfly (1) installs while keeping Cleaver (3) for when we actually see barriers. We don't need to install everything immediately.

**Bottom line:** A hand with a free economy engine, free multi-access pressure, temporary AI breaking, a permanent fracter, and ICE-weakening virus support is above average. The missing pieces (killer, decoder, burst econ) are things we can draw into. Mulliganing this hand is greedy.

---

## Turn Log

### Turn 1 - Runner (5 credits, 4 clicks)

**Hand:** Jailbreak, Cleaver, Mayfly, Smartware Distributor, Leech

**Board:** HQ has 1 unrezzed ICE, R&D has 1 unrezzed ICE, Remote 1 has 1 unrezzed card (naked, no ICE).

**Pre-turn Assessment:**
- The naked remote is suspicious. Could be a trap (Urtica Cipher), economy asset, or agenda.
- Two unrezzed ICE on centrals. Corp has 5 credits -- enough to rez expensive ICE.
- I have no permanent decoder or killer. Mayfly is my only non-fracter breaker and it trashes after use.
- Smartware Distributor needs to come down early for long-term economy.
- Leech needs successful central runs to accumulate counters.

**Strategic Plan:**
1. Install Smartware Distributor (0 cost) -- start drip economy immediately
2. Install Leech (1 credit) -- virus pressure, benefits from central runs
3. Probe run HQ (click, not Jailbreak) -- test if Corp rezzes ICE. If they don't rez, access 1 card. If they rez, I learn what ICE it is and Corp spends credits, potentially locking them out of rezzing R&D ICE.
4. If HQ probe succeeds: follow up with Jailbreak on HQ for 2 accesses + draw. If probe fails: draw a card to rebuild hand and find answers.

**Reasoning for HQ probe before Jailbreak:** Using Jailbreak blind risks wasting a premium multi-access event against ICE I can't break. A naked click-run tests Corp's willingness to rez without spending my best card. If Corp doesn't rez, I save Jailbreak for a bigger payoff next turn with more information.

**Execution:**

| Click | Action | Result |
|-------|--------|--------|
| 1 | Install Smartware Distributor | Installed (0 cost). Credits: 5 -> 5 (wait - actually 5, no cost). Note: Smartware needs a click to charge before it starts dripping. Skipped charging this turn to prioritize aggression. |
| 2 | Install Leech | Installed (1 credit). Credits: 5 -> 4. |
| 3 | Run HQ (probe) | Corp rezzed **Diviner** (code gate, str 3, 1 sub: "Do 1 net damage", ETR). Corp spent 2 credits (5 -> 3). No decoder available, had to tank the subroutine. **Diviner dealt 1 net damage -- randomly trashed Mayfly from hand.** Run ended by ETR. |
| 4 | Draw | Drew **Mutual Favor** (Event, 0 cost: search stack for icebreaker, install free if successful run this turn). |

**End of Turn State:**
- **Credits:** 4
- **Hand:** Jailbreak, Cleaver, Mutual Favor (3 cards)
- **Rig:** Leech (program), Smartware Distributor (resource)
- **Discard:** Mayfly (net damage)
- **Corp:** 3 credits, Diviner rezzed on HQ, unrezzed ICE on R&D, Urtica Cipher in naked remote

**Post-Turn Analysis:**

***What went well:***
- Learned HQ ICE is Diviner (code gate, str 3). Now I know exactly what I need to break it.
- Forced Corp to spend 2 credits (5 -> 3). Corp now **cannot rez Karuna** (4 cost) on R&D. This is significant -- R&D is temporarily soft.
- Drew Mutual Favor, which is exactly the tutor I needed to replace lost Mayfly / find a decoder.
- Economy engine (Smartware Distributor) is in play and will start generating once charged.

***What went badly:***
- Lost Mayfly to net damage. This was my only non-fracter breaker and my insurance policy against unknown ICE. The random discard was unlucky (1/3 chance of hitting Mayfly vs Jailbreak or Cleaver).
- Failed to access HQ. The two agendas sitting in Corp's hand (from Corp log: Offworld Office + Superconducting Hub) remain safe.
- Did not charge Smartware Distributor. Economy is stalling -- I have 4 credits and no income generating yet.

***Was the probe run correct?***
In hindsight, the probe run achieved its intelligence-gathering objective (revealed Diviner, drained Corp credits) but the net damage trashing Mayfly was a steep price. The alternative was to skip the run entirely and set up more safely (install Smartware, charge it, install Leech, draw). That would have preserved Mayfly but given Corp a free turn to draw economy and ICE up further. On balance, the probe was reasonable -- the information gained is valuable and the credit drain on Corp opens R&D. The Mayfly loss was bad luck, not bad strategy.

**Turn 2 Priorities:**
1. **Charge Smartware Distributor** -- must start economy engine. This gives 3 credits in drip over coming turns.
2. **Play Mutual Favor** -- search for a decoder (probably Unity or Buzzsaw) to handle Diviner. If I make a successful run first, I can install it for free.
3. **Consider R&D pressure** -- Corp is at 3 credits, cannot rez Karuna (4 cost). R&D is soft this turn. If I find a decoder via Mutual Favor, I could Jailbreak R&D for 2 accesses. But I need the decoder for Diviner on HQ, not for R&D (which has a sentry). Actually, R&D has Karuna (sentry) which Corp can't afford to rez. A naked R&D run might just work.
4. **Avoid Remote 1** -- it's Urtica Cipher (trap). Do not run it.

**Key Decision for Turn 2:** The sequencing question is whether to run R&D early (while Corp is poor) or set up economy first. Aggressive line: Mutual Favor -> find decoder -> run R&D (Corp can't rez Karuna) -> Jailbreak HQ (now with decoder for Diviner). Conservative line: Charge Smartware, Mutual Favor, install breaker, draw. The aggressive line risks Corp drawing into economy and rezzing Karuna after all. The window of Corp poverty is NOW.

---

### Turn 2 - Runner (4 credits, 4 clicks)

**Hand:** Jailbreak, Cleaver, Mutual Favor (3 cards)

**Board:** HQ: Diviner rezzed (code gate, str 3). R&D: 1 unrezzed ICE (likely Karuna, sentry). Remote 1: 1 unrezzed card (Urtica Cipher), no ICE. Corp: 6 credits (took all 3 clicks for credits last turn).

**Pre-turn Assessment:**
- Corp went from 3 to 6 credits. They can now rez Karuna (4 cost) on R&D. Running R&D without a killer and 5+ cards is a flatline risk (Karuna does 4 net damage).
- Mutual Favor can tutor any icebreaker from deck. Carmen (killer, 5 cost) is the priority for sentry safety. Unity (decoder, 3 cost) handles Diviner. Mayfly (AI, 1 cost) is cheap but disposable.
- Remote 1 is naked. If the card is unadvanced, trap damage should be manageable.
- Carmen has a discount: costs 2 less if a successful run was made this turn. If I run first then install, Carmen costs 3 instead of 5.

**Strategic Plan:**
1. Mutual Favor -> fetch Carmen (killer, most critical missing breaker for Karuna safety)
2. Draw a card (buffer hand size, find economy)
3. Run naked Remote 1 (no ICE = free access, triggers Carmen discount, clears unknown threat)
4. Install Carmen (3 credits with discount after successful run) or draw depending on outcome

**Execution:**

| Click | Action | Result |
|-------|--------|--------|
| 1 | Play Mutual Favor | Searched deck. Found: Carmen (killer, 5c), Cleaver (fracter, 3c), Mayfly (AI, 1c), Unity (decoder, 3c). **Chose Carmen** -- killer is the highest priority to neutralize Karuna flatline threat. Credits: 4 (free event). |
| 2 | Draw | Drew **Telework Contract** (Resource, 1c install: load 9 credits, click for 3 at a time). Excellent economy card. Hand: Jailbreak, Cleaver, Carmen, Telework Contract (4 cards). |
| 3 | Run Remote 1 | No ICE, approached server. Accessed **Urtica Cipher** (trap asset). Corp chose to fire the ability -- dealt **2 net damage**. Lost Telework Contract and Jailbreak to random discard. Hand dropped to 2 cards (Cleaver, Carmen). Then paid **2 credits to trash** Urtica Cipher. Credits: 4 -> 2. Run successful. |
| 4 | Draw | Drew **Overclock** (Event, 1c: place 5 temp credits, run any server). Turn auto-ended at 0 clicks. |

**End of Turn State:**
- **Credits:** 2
- **Hand:** Cleaver, Carmen, Overclock (3 cards)
- **Rig:** Leech (program), Smartware Distributor (resource, uncharged)
- **Discard:** Mayfly, Mutual Favor, Jailbreak, Telework Contract (4 cards in heap)
- **Corp:** 6 credits, Diviner rezzed on HQ, unrezzed ICE on R&D, Remote 1 now empty (Urtica trashed)

**Post-Turn Analysis:**

***What went well:***
- Fetched Carmen, the critical killer breaker. Once installed, Karuna is no longer a flatline threat.
- Trashed Urtica Cipher from the remote. Corp lost their trap and has to invest time/cards to rebuild Remote 1.
- Drew Overclock, which is effectively a 5-credit run subsidy. Combined with Carmen's discount mechanic, this enables a powerful future turn.
- Run was successful, so Carmen's discount is "primed" for this turn (but I had no clicks left to use it).

***What went badly:***
- Urtica Cipher dealt 2 net damage despite being "unadvanced." I miscalculated -- the card either had advancement tokens from a previous turn or has base damage. This cost me Telework Contract (9 credits of econ, devastating loss) and Jailbreak (premium multi-access event).
- Economy is in dire straits: 2 credits, no economy cards in hand, Smartware Distributor still uncharged. Lost both Telework Contract and Jailbreak to damage.
- Carmen remains uninstalled (costs 5 normally, 3 with run discount, but I only have 2 credits). Need economy before I can deploy it.
- Still no decoder installed. Diviner on HQ remains unbreakable.

***Was the Remote 1 run correct?***
Mixed. Trashing Urtica Cipher was strategically valuable -- it removes Corp's trap and empties the remote. But the 2 net damage losing Telework Contract was catastrophic for economy. The calculation assumed 0 damage from an unadvanced trap, which was wrong. In hindsight, I should have investigated whether Urtica has a base damage component or whether the Corp advanced it (Corp turn 1 had clicks available beyond what the log shows). The 2 credits spent trashing it were also costly given my economic position. The run was defensible as a calculated risk but the outcome was painful.

**Turn 3 Priorities:**
1. **Economy recovery is urgent.** With 2 credits and no economy cards, I need to click for credits or find economy draws. Smartware Distributor charging (1 click for 3 credits over time) is essential but slow.
2. **Install Carmen.** Need 5 credits (or 3 after a successful run). Overclock provides 5 temp credits during a run, but those can't be used for installs. I need real credits: click-click-credit gets me to 4, still short of 5. Plan: charge Smartware (no instant credits), take credits, draw for economy.
3. **Overclock is the best run card available.** It provides 5 temp credits during a run. With Carmen installed, I could Overclock into R&D through Karuna: pump Carmen (2c to +3 str, reaching str 5 vs Karuna str 5), break 4 subs (4c). Total: 6 credits to break Karuna, covered by Overclock's 5 temp + 1 real credit.
4. **Sequence matters:** I need to install Carmen BEFORE running with Overclock. Can't use Overclock credits for installs. So Turn 3 likely needs to be: econ, econ, install Carmen, then save Overclock for Turn 4.

**Key Decision for Turn 3:** Pure setup turn. Click for credits (or charge Smartware), build toward Carmen install. The aggressive window is temporarily closed until breaker suite is online. Corp will likely use this breathing room to score or set up further.

---

### Turn 3 - Runner (2 credits, 4 clicks)

**Hand:** Cleaver, Carmen, Overclock (3 cards)

**Board:** HQ: Diviner rezzed (code gate, str 3). R&D: 1 unrezzed ICE (likely Karunā, sentry str 3). **Server 2: 1 unrezzed ICE + 1 unrezzed card with 1 advancement counter.** Corp: 5 credits, 3 cards in hand.

**Pre-turn Assessment:**

The Server 2 setup is a scoring threat. Corp used all 3 clicks last turn: install ICE, install card, advance once. The advanced card is almost certainly an agenda. Scoring timelines:

| Agenda | Adv Req | Current | Needed | Corp Clicks | Scorable Turn 4? |
|--------|---------|---------|--------|-------------|-------------------|
| Superconducting Hub (3/1) | 3 | 1 | 2 adv | 2 of 3 clicks | Yes (AA + spare click) |
| Offworld Office (4/2) | 4 | 1 | 3 adv | 3 of 3 clicks | Yes (AAA, exactly) |
| Send a Message (5/3) | 5 | 1 | 4 adv | 3 clicks/turn | No (needs 2 turns) |

Additionally, Corp has 2x Seamless Launch in the deck (1c operation: place 2 adv counters on a card not installed this turn). If Corp plays Seamless Launch, the scoring math accelerates dramatically:
- Superconducting Hub: Seamless Launch alone = 1+2 = 3 adv. Score with 1 click + 1c. Leaves 2 clicks free.
- Offworld Office: Seamless + 1 advance = 1+2+1 = 4 adv. Score with 2 clicks + 1c. Leaves 1 click free.
- Send a Message: Seamless + 2 advances = 1+2+2 = 5 adv. Score with 3 clicks + 1c. Exactly fits.

**Conclusion: Corp can likely score ANY agenda in Server 2 next turn.** Even Send a Message is scorable with Seamless Launch. I MUST be able to contest Server 2 on Runner Turn 4.

**Can I contest Server 2 THIS turn?**

No. The math doesn't work:
- Carmen costs 3 (with successful run discount) or 5 (without). I have 2 credits.
- Even if I take a credit (3c) and install Carmen (0c remaining), I can't afford Overclock (1c) to run.
- Without breakers installed, I can't break ICE on Server 2 regardless of credits.
- I need a setup turn.

**Strategic Plan: Maximize Turn 4 readiness**

The key insight is that Carmen costs 2 less after a successful run. Archives is undefended (no ICE visible on it), making it a free successful run to trigger Carmen's discount.

1. Click 1: Run Archives (free, undefended) -- triggers Carmen discount, gains Leech counter
2. Click 2: Take credit (2 -> 3) -- enough for discounted Carmen install
3. Click 3: Install Carmen (3 -> 0, with discount) -- killer online for Karunā and sentries
4. Click 4: Charge Smartware Distributor (place 3 credits) -- starts drip economy for next turns

This leaves me at 0 credits but with Carmen installed, Smartware loaded with 3 credits (drips 1/turn), Leech at 1 counter, and Overclock + Cleaver in hand.

**Turn 4 projection (starting 0 + 1 Smartware drip = 1 credit, 4 clicks):**

Overclock run on Server 2: Pay 1c to play Overclock, get 5 temp credits during run. Need to break Server 2 ICE.

Server 2 ICE possibilities (remaining in Corp deck after Diviner on HQ, likely Karunā on R&D):
- **Palisade** (barrier, str 2, +2 on remote = **str 4**): Cleaver breaks for 2c pump + 1c break = 3c. Or with 1 Leech counter: str 3 Cleaver vs str 3 Palisade, break for 1c. Total: 1-3c.
- **Whitespace** (code gate, str 0): No decoder. Subs: lose 3c then ETR if ≤6c. I'd lose 3 temp credits and get ETR'd (I'd have ≤6c). **Cannot break. Run fails.**
- **Tithe** (sentry, str 1): Carmen breaks easily. 1c break = 1c total.
- **Karunā** (sentry, str 3): Carmen needs +1 str (2c pump to str 5), break 2 subs (2c). Total: 4c. With Leech: str 2 Karunā, pump Carmen once (2c to str 5, overkill), break 2 subs (2c). Total: 4c. Actually wait -- Leech makes Karunā str 2, Carmen is str 2, no pump needed. Break 2 subs = 2c. Total: 2c.
- **Diviner** (code gate, str 3): No decoder. 1 net damage + ETR. **Cannot break.**
- **Brân 1.0** (barrier, str 6): Can spend clicks to break subs (Bioroid), or Cleaver: needs +3 str (6c pump) + break 3 subs. Very expensive. With Leech: str 5, Cleaver needs 2c pump to str 5, break for... still expensive. **Difficult but survivable via click-breaking.**

**Risk assessment for Turn 4 Server 2 run:**
- If sentry (Tithe, Karunā): Carmen handles it. Overclock credits sufficient. **Good.**
- If barrier (Palisade, Brân): Need Cleaver installed (3c, don't have). **Bad unless I install Cleaver too.**
- If code gate (Whitespace, Diviner): No decoder. **Run fails. Cannot contest.**

This is the fundamental problem: I have 2 of 3 breaker types (killer + fracter in hand) but only enough economy to deploy one. The ICE type on Server 2 determines whether I can contest. Against sentries, Carmen + Overclock is sufficient. Against barriers or code gates, I'm stuck.

**Why Carmen over Cleaver:** Carmen is more critical because Karunā (4 net damage) is a flatline threat. Palisade just ETRs (safe to face-check). Code gates I can't break regardless. Carmen keeps me alive; Cleaver just saves a failed run.

**Execution:**

| Click | Action | Result |
|-------|--------|--------|
| 1 | Run Archives | Undefended. Approached server, Leech gained 1 virus counter (now 1 total). Accessed Archives (empty/nothing meaningful). Successful run -- Carmen discount active. Credits: 2, Clicks: 3. |
| 2 | Take credit | Credits: 2 -> 3. Clicks: 2. |
| 3 | Install Carmen | Paid 3 credits (5 base - 2 discount for successful run). Carmen installed (killer, str 2). Credits: 0. Clicks: 1. |
| 4 | Charge Smartware Distributor | Used ability: place 3 credits on Smartware Distributor. It will drip 1 credit/turn at start of turn. Credits: 0. Clicks: 0. Turn auto-ended. |

**End of Turn State:**
- **Credits:** 0
- **Hand:** Cleaver, Overclock (2 cards)
- **Rig:** Leech (1 virus counter), Carmen (killer, str 2), Smartware Distributor (3 credits loaded)
- **Discard:** Mayfly, Mutual Favor, Jailbreak, Telework Contract
- **Corp:** 5 credits, 3 cards in hand, Server 2 with 1-adv card + ICE

**Post-Turn Analysis:**

***What went well:***
- Carmen installed efficiently for 3 credits via the Archives discount trick. This is the most important breaker for survival (Karunā flatline protection).
- Leech gained its first counter. Even 1 counter can save 2 credits against str-3 ICE by letting Carmen/Cleaver match strength without pumping.
- Smartware Distributor is finally charged. Will drip 1c/turn starting Turn 4, providing passive economy.
- Clean setup turn with no damage taken.

***What went badly:***
- Still at 0 credits. Economy is critically low. One Smartware drip per turn is slow.
- Cannot install Cleaver (3c) without dedicating clicks to credits. If Server 2 ICE is a barrier, I'm in trouble.
- No decoder at all. If Server 2 ICE is Whitespace or another Diviner, the run fails regardless.
- Corp gets a free turn to advance/score. If they have Seamless Launch, the agenda could be scored before I can contest.
- Hand size is only 2 cards. A Karunā face-check on R&D would flatline me (4 net damage > 2 cards). Must avoid R&D without Carmen ready to break.

***Was the Archives run correct?***
Yes. This was a zero-risk play that accomplished three things: (1) activated Carmen's 2c discount, saving a full click of economy, (2) gained a Leech counter for future ICE encounters, (3) checked Archives for any facedown cards. The alternative was clicking for 3 credits and paying full price for Carmen, which would have left Smartware uncharged. The Archives trick saved a net 1 click of tempo.

**Turn 4 Decision Framework:**

Starting position: 0 + 1 Smartware drip = 1 credit, 4 clicks, hand: Cleaver + Overclock.

**Scenario A: Prioritize contesting Server 2 (aggressive)**
- Click 1: Take credit (1 -> 2)
- Click 2: Play Overclock (pay 1c, run Server 2, get 5 temp credits)
- If ICE is sentry: Carmen breaks it (2-4c from Overclock). Access card. If agenda, steal.
- If ICE is barrier: Can't break (Cleaver not installed). Tank ETR.
- If ICE is code gate: Can't break. Tank subs.
- Remaining 2 clicks: Install Cleaver if possible, take credits, draw.

**Scenario B: Full setup (conservative)**
- Click 1: Take credit (1 -> 2)
- Click 2: Take credit (2 -> 3)
- Click 3: Install Cleaver (3 -> 0)
- Click 4: Take credit (0 -> 1) or draw
- Result: Both breakers online but no run this turn. Corp scores freely.

**Scenario C: Hybrid (install Cleaver + Overclock run)**
- Click 1: Take credit (1 -> 2)
- Click 2: Take credit (2 -> 3)
- Click 3: Install Cleaver (3 -> 0)
- Click 4: Play Overclock (need 1c... have 0). **Fails. Can't afford Overclock after Cleaver.**

**Scenario D: Credit + Overclock early**
- Click 1: Play Overclock (pay 1c, have 0 + 5 temp). Run Server 2.
- If ICE is sentry: Carmen breaks. Steal/access.
- If ICE is barrier/code gate: Fail. Wasted Overclock.
- Clicks 2-4: Economy/setup.

**Assessment:** Scenario A or D is the only way to contest Server 2 while Corp might still be 1 advancement short. The gamble is on the ICE type. With 5 barriers, 4 code gates, and 4 sentries in the deck (minus Diviner on HQ, minus likely Karunā on R&D), the remaining pool is roughly:
- Barriers: Brân 1.0 x2, Palisade x3 = 5
- Code gates: Diviner x1, Whitespace x2 = 3
- Sentries: Karunā x1 (if other is on R&D), Tithe x2 = 3

Roughly 45% barrier, 27% code gate, 27% sentry. Only sentry (27%) lets me break with just Carmen. Not great odds for a blind Overclock run.

**Recommendation for Turn 4:** Unless I draw a decoder or economy card, the best play may be to accept Corp scores the first agenda, set up fully (install Cleaver, build credits), and contest future remotes with a complete rig. Losing 1-2 agenda points early is recoverable. Getting flatlined by Karunā on a desperate R&D run is not.

However, if Corp scores Offworld Office (4/2, +7 credits), they become massively rich and harder to contest. If it's Superconducting Hub (3/1, +2 draw), Corp refills hand. Send a Message (5/3) lets Corp rez ICE for free. All scoring triggers benefit Corp significantly.

**The uncomfortable truth:** This turn was necessary setup, but the scoring window is almost certainly lost. The net damage from Urtica Cipher on Turn 2 (losing Telework Contract) was the decisive blow to our economy. Without those 9 credits, we can't deploy breakers fast enough to contest the first score.

**What actually happened:** Corp scored Superconducting Hub (3/1) from Server 1, gaining 1 agenda point and drawing 2 cards. Corp then installed ICE on Server 2 and a new card in Server 2's root. Score is now Corp 1 - Runner 0.

---

### Turn 4 - Runner (1 credit after Smartware drip, 4 clicks)

**Hand:** Cleaver, Overclock (2 cards)

**Board:** HQ: Diviner rezzed (code gate, str 3). R&D: 1 unrezzed ICE (likely Karuna sentry). Server 2: 1 unrezzed ICE + 1 unrezzed card (0 advancement tokens, freshly installed). Corp: 3 credits, 5 cards in hand.

**Score:** Corp 1 (Superconducting Hub) - Runner 0.

**Pre-turn Assessment:**

Corp scored last turn, going to 1 agenda point. The new Server 2 card was installed with 0 advancement tokens -- Corp used their remaining clicks on ICE install + card install, no advancing. This means Corp needs their NEXT turn to start advancing.

**Scoring timeline for Server 2:**
- 3/1 agenda (e.g. another Superconducting Hub): Corp needs 3 advances. Without Seamless Launch: advance x3 = 3 clicks, 3c. Scorable Corp Turn 5. With Seamless: Seamless (1c) + advance (1c) = score in 2 clicks, 2c. Also scorable Corp Turn 5.
- 4/2 agenda (Offworld Office): Corp needs 4 advances. Without Seamless: advance x3 = 3 clicks, 3c on Turn 5, then advance + score on Turn 6. With Seamless: Seamless (1c) + advance x2 (2c) = 4 advances in 3 clicks, 3c. Scorable Corp Turn 5!
- 5/3 agenda (Send a Message): Without Seamless: 3 advances Turn 5 + 2 advances Turn 6 = score Turn 6. With Seamless: Seamless + advance x3 = 5 advances in 4 clicks... but Corp only has 3 clicks. Needs 2 turns. Earliest score: Turn 6.

**Key insight:** With Seamless Launch, Corp can score anything up to a 4/2 next turn. Without Seamless, only a 3/1 scores next turn. I cannot contest Server 2 this turn regardless -- I lack the economy to both install breakers AND run. The question is whether to set up for a devastating Turn 5 run or make a weak run now.

**Economy analysis:**

I have 1 credit. Smartware dripped from 3 to 2 credits remaining (1 dripped this turn). My options:

| Plan | Actions | End State | Turn 5 Projection |
|------|---------|-----------|-------------------|
| A: Pure credits | credit, credit, credit, credit | 5c, hand: Cleaver/Overclock | Play Sure Gamble if drawn... wait, no draw in this line |
| B: Draw + credits | draw, credit, credit, credit | 4c, hand: 3 cards (potential econ) | Depends on draw |
| C: Install Cleaver | credit, credit, install Cleaver, draw | 0c, Cleaver installed, 3 cards | Broke but rig stronger |
| D: Run Archives + setup | run Archives, credit, credit, install Cleaver | 0c, Cleaver installed, 2 Leech | Broke, 2 breakers online |

**Click 1 Decision: Draw first.**

Drawing before committing to a plan is correct here. If I draw economy (Sure Gamble, Dirty Laundry, Creative Commission), it fundamentally changes the calculus. If I draw a decoder, I might pivot to an Overclock run. Information before commitment.

**Execution:**

| Click | Action | Result |
|-------|--------|--------|
| 1 | Draw | Drew **Sure Gamble** (Event, 5c: Gain 9 credits). Hand: Cleaver, Overclock, Sure Gamble. Credits: 1, Clicks: 3. |

**Sure Gamble changes everything.** This is the burst economy I've been missing since Turn 1. Sure Gamble costs 5 to play and gains 9 (net +4). But I currently have 1 credit -- I need 5 to play it. I cannot play it this turn unless I get 4 more credits in 3 clicks... which is exactly what click-for-credit gives: 1+1+1+1 = 4 credits. Wait, that's 3 more clicks for 3 more credits = 4 total. Still short by 1.

**Can I play Sure Gamble this turn?** No. I need 5 credits and can only reach 4 with 3 remaining clicks. But next turn: start with 4 + 1 Smartware drip = 5 credits. Play Sure Gamble immediately for 9 credits. Then install Cleaver (9 -> 6), play Overclock (6 -> 5 + 5 temp), run Server 2 with 5 real + 5 temp = 10 credits of breaking power. That's enough to break virtually any ICE combination.

**Revised plan: Accumulate credits this turn, set up devastating Turn 5.**

| Click | Action | Result |
|-------|--------|--------|
| 2 | Take credit | Credits: 1 -> 2. Clicks: 2. |
| 3 | Take credit | Credits: 2 -> 3. Clicks: 1. |
| 4 | Take credit | Credits: 3 -> 4. Clicks: 0. Turn auto-ended. |

**End of Turn State:**
- **Credits:** 4
- **Hand:** Cleaver, Overclock, Sure Gamble (3 cards)
- **Rig:** Leech (1 virus counter), Carmen (killer, str 2), Smartware Distributor (2 credits remaining)
- **Discard:** Mayfly, Mutual Favor, Jailbreak, Telework Contract
- **Corp:** 3 credits, 5 cards in hand, Server 2 has 0-advancement card + ICE

**Post-Turn Analysis:**

***What went well:***
- Drew Sure Gamble, the single best economy card in the deck. This transforms our Turn 5 from "broke and struggling" to "fully funded assault."
- Accumulated to 4 credits. With Smartware drip, Turn 5 opens at exactly 5 credits -- the minimum to play Sure Gamble.
- Turn 5 projection: Sure Gamble (5 -> 9), install Cleaver (9 -> 6), play Overclock (6 -> 5 real + 5 temp), run Server 2. That's 2 breakers online (Carmen + Cleaver) and 10 credits of breaking power. Only a code gate ICE on Server 2 stops this plan.

***What went badly:***
- Spent the entire turn clicking for credits and drawing. Zero board development. Corp gets another free turn to advance.
- Cleaver still not installed. If Corp runs me through a barrier somehow (unlikely as Runner), I have no answer.
- Still no decoder. Diviner on HQ remains unbreakable. HQ is locked out until I find Unity or Buzzsaw.
- If Corp has Seamless Launch, they can score a 4/2 agenda from 0 advancement tokens in a single turn (Seamless + advance + advance + score = 4 advances, 3 clicks, 3c). This would put Corp at 3 agenda points (1 + 2) and give them +7 credits from Offworld Office, creating a massive economy and score advantage.

***Was the "pure credits" line correct?***

**Yes, and here's why the alternatives are worse:**

- **Install Cleaver now (Plan C/D):** Leaves me at 0 credits. Turn 5: 0 + 1 Smartware = 1. Need 4 more clicks to reach 5 for Sure Gamble. That means Turn 5 is ALSO a setup turn, and I can't run until Turn 6. Two full setup turns is catastrophic -- Corp scores for sure.

- **Overclock run on Server 2 now:** With only Carmen, I can break sentries (~27% of ICE pool). If it's a barrier (45%) or code gate (27%), Overclock is wasted and I've spent 1 credit + a click for nothing. Burning Overclock on 27% odds is terrible expected value, especially when Turn 5 offers a nearly guaranteed break with Cleaver + Carmen + 10 credits.

- **Run R&D with Overclock:** R&D has Karuna (sentry). Carmen + Overclock can break it (Carmen str 2, Karuna str 3 minus 1 Leech = str 2, match. Break 2 subs for 2c. Overclock covers it easily). But R&D access is random -- 1/33 cards, maybe 6-8 agendas in deck = ~20% hit rate per access. And I'd burn Overclock for a single access instead of saving it for a targeted Server 2 run where a known card sits. **Overclock is more valuable on Server 2 than R&D.**

- **The accumulate plan (chosen):** Turn 5 opens with Sure Gamble -> 9 credits -> install Cleaver -> 6 credits -> Overclock run Server 2. Three breaker types (Carmen killer, Cleaver fracter) handle sentries and barriers. Only code gate ICE on Server 2 stops us. This is the highest-EV line by a significant margin.

***The acceptable loss:***

Corp will likely score whatever is in Server 2 if they have Seamless Launch. But even a worst case of Corp scoring Offworld Office (going to 3 points + 7 credits) is recoverable. The game goes to 7 points. At 3, Corp still needs 4 more points. With a funded rig (Carmen + Cleaver + eventually a decoder), I can contest every future remote and pressure R&D. The Sure Gamble draw is the economy inflection point that makes the mid-game winnable.

**Turn 5 Plan:**

Starting: 4 + 1 Smartware drip = 5 credits, 4 clicks.

Optimal line:
1. **Click 1: Play Sure Gamble** (5 -> 9 credits). Burst economy online.
2. **Click 2: Install Cleaver** (9 -> 6 credits). Fracter online. Rig: Carmen (killer) + Cleaver (fracter).
3. **Click 3: Play Overclock** (6 -> 5 credits + 5 temp). Run Server 2.
   - If sentry ICE: Carmen breaks (2-4c from Overclock/real credits).
   - If barrier ICE: Cleaver breaks (1-3c). Palisade at str 4 on remote: Cleaver (str 3) needs +1 str (2c) + break (1c) = 3c. Or with Leech: Palisade str 3, Cleaver str 3, break for 1c.
   - If code gate ICE: Cannot break. Run fails. Fall back to taking credits or drawing with Click 4.
   - Access card: If agenda, steal. If asset, evaluate trashing.
4. **Click 4:** Depends on Click 3 outcome. If run succeeded: take credit or draw. If run failed: take credit or draw for decoder.

**Contingency:** If Corp doesn't advance Server 2 (suggesting it's an asset, not an agenda), I might redirect Overclock to R&D instead. R&D pressure with Carmen through Karuna is viable and hits the larger agenda pool.

**What actually happened:** Corp installed a naked card in Server 3 (no ICE) and did NOT advance the Server 2 card. The plan above is obsolete -- a naked remote demands immediate attention.

---

### Turn 5 - Runner (5 credits after Smartware drip, 4 clicks)

**Hand:** Cleaver, Overclock, Sure Gamble (3 cards)

**Board:** HQ: Diviner rezzed (code gate, str 3). R&D: 1 unrezzed ICE (Karuna sentry). Server 2: 1 unrezzed ICE + Regolith Mining License (rezzed, 9 credits loaded). **Server 3: NO ICE + 1 unrezzed card (NAKED remote).**

**Score:** Corp 1 (Superconducting Hub) - Runner 0.

**Pre-turn Assessment:**

Corp's Turn 5 was unusual: they spent 2 clicks on Regolith Mining License (gaining 6 credits total) and then installed a card in a brand new Server 3 with zero ICE protection. This is a naked remote -- free to access with no risk of ICE.

**What could the naked card be?**

Corp just scored Superconducting Hub (drawing 2 cards) and has Seamless Launch in their deck/hand. The naked install pattern suggests:

1. **Offworld Office (4/2 agenda):** Corp may plan to Seamless Launch + advance next turn for a fast score. Installing naked saves clicks on ICE installation. High probability -- this is a classic fast-advance setup.
2. **Urtica Cipher (trap):** We already triggered one in Remote 1. Corp could try again, but investing a card slot in a naked trap when they could be advancing agendas is low-EV at this score.
3. **Economy asset:** Unlikely in a naked remote when Server 2 already has Regolith.

**Risk/reward calculation:**
- If agenda (Offworld Office, 4/2): Steal for 2 points, take the lead 2-1. Game-defining.
- If trap (unadvanced Urtica Cipher): 2 net damage to a 3-card hand. Survivable at 1 card remaining, though painful. We don't lose to flatline unless we're at 2 or fewer cards when the damage hits... we have exactly 3 cards. If Urtica has 0 advancement tokens, it deals 2 damage. 3 - 2 = 1 card. We survive.
- If economy asset: We see it and can trash it or leave it. No real downside.

**The naked remote MUST be run immediately.** Every turn we don't run it, Corp can advance or ICE it. A free access is too valuable to pass up when the expected value is this high.

**Strategic Plan:**
1. Click 1: Run Server 3 (no ICE, free access)
2. Click 2: Play Sure Gamble (5 -> 9 credits) -- burst economy
3. Click 3: Install Cleaver (9 -> 6 credits) -- fracter online
4. Click 4: Draw (hand size buffer, find decoder/economy)

**Why this order?** Running Server 3 first is critical because:
- It costs nothing (no ICE, no credits needed)
- If it's an agenda, we steal before Corp can protect it
- If it's a trap, we take damage BEFORE committing our hand to installs. If we install first and then hit a trap, we lose the installed cards' value AND take damage to a smaller hand.
- The Leech counter from a successful run is a minor bonus

**Execution:**

| Click | Action | Result |
|-------|--------|--------|
| 1 | Run Server 3 | No ICE. Breached server. Accessed **Offworld Office** (Agenda, 4/2). **STOLEN -- gained 2 agenda points!** Score: Runner 2 - Corp 1. Credits: 5, Clicks: 3. |
| 2 | Play Sure Gamble | Paid 5 credits, gained 9 credits. Net +4. Credits: 5 -> 9. Clicks: 2. |
| 3 | Install Cleaver | Paid 3 credits. Fracter (str 3) installed. Credits: 9 -> 6. Clicks: 1. |
| 4 | Draw | Drew **DZMZ Optimizer** (Hardware, 2 cost: +1 MU, first program install each turn costs 1 less). Hand: Overclock, DZMZ Optimizer. Credits: 6, Clicks: 0. Turn auto-ended. |

**End of Turn State:**
- **Score: Runner 2 - Corp 1** (stole Offworld Office!)
- **Credits:** 6
- **Hand:** Overclock, DZMZ Optimizer (2 cards)
- **Rig:** Leech (1 virus counter), Carmen (killer, str 2), Cleaver (fracter, str 3), Smartware Distributor (1 credit remaining)
- **Discard:** Mayfly, Mutual Favor, Jailbreak, Telework Contract, Sure Gamble
- **Corp:** 7 credits, 5 cards in hand, Regolith Mining License on Server 2 (with ICE)

**Post-Turn Analysis:**

***What went well:***
- **Stole Offworld Office for free.** The single most impactful play of the game so far. A 4/2 agenda stolen from a naked remote with zero cost in credits, breaker usage, or card investment. This is the reward for paying attention to board state.
- **Took the score lead 2-1.** Corp now needs 6 more agenda points; we need 5. This reverses the pressure -- Corp must now protect agendas more carefully, which costs them tempo.
- **Economy recovered.** Sure Gamble took us from 5 to 9 credits. After installing Cleaver (3c), we end at 6. This is the healthiest economy we've had since Turn 1.
- **Cleaver installed.** Two of three breaker types are now online (Carmen for sentries, Cleaver for barriers). We can contest any server protected by sentries or barriers.
- **Drew DZMZ Optimizer.** +1 MU means we can install a decoder without running into memory limits. The 1-credit discount on the first program each turn is a nice bonus.

***What went badly:***
- **Still no decoder.** Diviner on HQ remains unbreakable. HQ is locked until we find Unity or Buzzsaw. This means Corp's hand (5 cards including potential agendas) is safe from us.
- **Hand size is only 2.** If Corp retaliates with net damage (Diviner face-check on HQ, or another trap), we're vulnerable. Need to draw up next turn.
- **Smartware Distributor is almost empty** (1 credit remaining). Economy engine is winding down. Need to find new income sources.

***Was the Server 3 run correct?***

Unequivocally yes. This was the single highest-EV play available. The expected value of running a naked remote in this game state is enormous:
- ~60-70% chance of being an agenda (given Corp's hand composition and scoring pattern) = free 2 points
- ~20-30% chance of being a trap = 2 net damage, survivable
- ~10% chance of being an asset = minor value (information or trash)

The actual outcome (Offworld Office steal) confirms the read, but even the worst case (Urtica Cipher for 2 damage) was acceptable. Running before installing was correct because it preserved hand size for trap survival.

**Turn 6 Priorities:**

1. **Install DZMZ Optimizer** (2c) -- gets +1 MU and future program discount online. But only if we plan to install a program this turn or soon.
2. **Draw aggressively** -- need to find a decoder (Unity, Buzzsaw) to crack HQ/Diviner. Also want more economy and hand size buffer.
3. **Consider R&D pressure** -- with Carmen + Cleaver + Overclock, we can break Karuna (sentry) on R&D using Overclock credits. Carmen (str 2) vs Karuna (str 3 - 1 Leech = str 2) = match, break 2 subs for 2c. Overclock provides 5 temp credits, more than enough. Single R&D access has ~15-20% agenda hit rate.
4. **Protect the lead** -- at 2-1, we can afford to be patient. Corp needs to find and score agendas. We need to contest remotes and apply R&D pressure. The game is ours to lose now.

**Key Decision for Turn 6:** The tension is between setup (install DZMZ, draw for decoder, build credits) and aggression (Overclock run on R&D while Leech has a counter). Given the score lead, moderate aggression is correct -- we don't need to take big risks, but we should keep pressure on so Corp can't freely score.

---

### Turn 6 - Runner (7 credits after Smartware drip, 4 clicks)

**Hand:** Overclock, DZMZ Optimizer (2 cards)

**Board:** HQ: Diviner rezzed (code gate, str 3). R&D: 1 unrezzed ICE (Karuna sentry, confirmed). Server 2: 1 unrezzed ICE (Palisade) + Regolith Mining License (rezzed, 6 credits). Server 4: 1 unrezzed ICE (freshly installed, unknown). Corp: 10 credits, 6 cards in hand.

**Score:** Runner 2 (Offworld Office) - Corp 1 (Superconducting Hub).

**Pre-turn Assessment:**

Corp's Turn 6 was methodical setup: took 3 credits from Regolith Mining License (10c -> 7c -> 10c net after clicks), drew a card, and installed ICE on a brand new Server 4 (no content behind it yet). They're building infrastructure -- 10 credits is a war chest, and 6 cards in hand means plenty of options. Server 4 is likely a future scoring remote.

**The strategic picture at 2-1:**

We're ahead on points but behind on board position. Corp has 10 credits to our 7, 4 servers with ICE (we can only break sentries and barriers), and they're likely holding agendas they want to score. Our advantage is tempo pressure -- every turn they spend setting up instead of scoring is a turn we can use to find a decoder and lock down the board.

**Critical gap: still no decoder.** Diviner on HQ and any code gates on remotes remain unbreakable. Corp knows this -- they'll likely deploy agendas behind code gate ICE. Server 4's ICE was freshly installed; if it's Whitespace (code gate), it creates a scoring remote we cannot contest.

**Overclock on R&D is the best aggressive play available.**

The math is compelling:
- Overclock costs 1c, provides 5 temp credits during run
- Karuna (str 3, sentry): Carmen breaks it efficiently. With Leech counters reducing Karuna's strength, the breaking cost is covered by Overclock's temp credits.
- R&D has 29 cards. Assuming ~5-6 agendas remain in deck: ~17-21% hit rate per access.
- At 5 points (if we steal an agenda here + the Send a Message we're about to find), any agenda wins. Even a 1-pointer.
- After the Overclock run, 3 remaining clicks allow setup (draws, installs).

**Why not pure setup?** At 2-1, passive play gives Corp time to advance behind protected remotes. Every run on R&D forces Corp to respect the threat and changes their scoring calculus. Even accessing non-agendas gives information (what's NOT in R&D is in HQ or remotes).

**Why not R&D second run without Overclock?** Carmen costs 4c to fully break Karuna without Overclock assistance. That's expensive from a 7c pool. Overclock lets us run cheaply, preserving real credits for the follow-up.

**Strategic Plan:**
1. Click 1: Play Overclock (1c), run R&D. Break Karuna with Carmen + Overclock credits. Access top card.
2. Click 2: Based on outcome -- if game isn't won, run R&D again (Karuna breaking costs 4c from real credits, or less with Leech) OR draw.
3. Click 3-4: Draw/setup to find decoder, rebuild hand size.

**Execution:**

| Click | Action | Result |
|-------|--------|--------|
| 1 | Play Overclock -> Run R&D | Paid 1c (7 -> 6 + 5 temp). Approached Karuna. Corp rezzed it (4c, Corp 10 -> 6). Carmen matched strength and fully broke Karuna (4c from Overclock temp credits). **Accessed Send a Message (5/3 agenda). STOLEN for 3 agenda points!** Score: **Runner 5 - Corp 1!** Corp triggered Send a Message's steal ability: rezzed Whitespace on Server 4 for free. Credits: 6, Clicks: 3. |
| 2 | Run R&D (click-run) | Approached Karuna. Carmen fully broke it (paid 4 real credits, 6 -> 2). Leech placed 1 virus counter. **Accessed Hedge Fund (operation). No action.** Run successful but no agenda. Credits: 2, Clicks: 2. |
| 3 | Draw | Drew **Creative Commission** (Event, 1c: gain 5 credits, lose a click if any remaining). Hand: DZMZ Optimizer, Creative Commission. Credits: 2, Clicks: 1. |
| 4 | Play Creative Commission | Paid 1c (2 -> 1), gained 5c (1 -> 6). No clicks remaining after playing, so no penalty. Credits: 6, Clicks: 0. Turn auto-ended. |

**End of Turn State:**
- **Score: Runner 5 - Corp 1** (stole Send a Message from R&D!)
- **Credits:** 6
- **Hand:** DZMZ Optimizer (1 card)
- **Rig:** Leech (3 virus counters), Carmen (killer, str 2), Cleaver (fracter, str 3), Smartware Distributor (depleted, 0 credits)
- **Discard:** Mayfly, Mutual Favor, Jailbreak, Telework Contract, Sure Gamble, Overclock, Creative Commission (7 cards)
- **Corp:** 6 credits, 6 cards in hand, Karuna rezzed on R&D, Whitespace rezzed on Server 4, Palisade unrezzed on Server 2

**Post-Turn Analysis:**

***What went well:***
- **Stole Send a Message for 3 agenda points.** This is the game-defining play. Going from 2-1 to 5-1 puts us on match point. ANY agenda steal wins the game. The Overclock investment paid off spectacularly -- a 5/3 agenda is the highest-value steal possible.
- **Ran R&D twice in one turn.** Even though the second run hit Hedge Fund, double-running R&D applies maximum pressure and checks 2 cards deep. The first run was subsidized by Overclock; the second was funded by real credits.
- **Drew Creative Commission and played it optimally.** On the last click, Creative Commission's downside (lose a click) is negated. Net +4 credits from a 1-credit investment. This recovered the 4 credits spent breaking Karuna on the second run.
- **Leech accumulated to 3 counters.** Each successful R&D run added a counter. Three Leech counters make future ICE breaking significantly cheaper -- Karuna drops from str 3 to str 0, making it free for Carmen to break (1c per sub, no pump needed).
- **Confirmed ICE identities.** Server 2 has Palisade (barrier), Server 4 has Whitespace (code gate, rezzed for free via Send a Message trigger). Now we know every piece of ICE on the board.

***What went badly:***
- **Second R&D run cost 4 real credits to break Karuna.** Without Overclock, Carmen's "match strength and fully break" ability costs 4c. This is expensive. The Leech counters should have reduced this cost, but the auto-break used the expensive match-and-break mode rather than spending Leech counters + cheaper break. A manual sequence of Leech -> pump -> break would have been 2c. This is a mechanical inefficiency worth addressing.
- **Hand size is dangerously low (1 card).** At 5 points, flatline risk is our biggest concern. Karuna does 4 net damage if unbroken; even Diviner does 1. With only 1 card in hand, any unexpected net damage flatlines us. Must draw up immediately next turn.
- **Still no decoder.** Whitespace on Server 4 is now rezzed, and we confirmed we cannot break it. If Corp installs an agenda behind Whitespace, we cannot contest. HQ behind Diviner is also inaccessible. Finding Unity is now absolutely critical.
- **Smartware Distributor is depleted.** Our passive economy engine has run dry. Future income is pure click-for-credit unless we draw economy cards.

***Was the Overclock R&D run correct?***

**Emphatically yes.** This was the highest-EV play of the game. The reasoning:

1. **Overclock subsidized the run almost entirely.** Breaking Karuna with temp credits meant the run's real cost was just 1 credit (Overclock's play cost). We effectively converted 1 real credit into an R&D access.
2. **The hit rate justified the investment.** With ~5-6 agendas in 29 cards, each access has ~17-21% of hitting an agenda. We hit a 5/3 -- the jackpot.
3. **At 2-1, proactive aggression is correct.** Sitting back and setting up gives Corp time to build impregnable scoring remotes. R&D pressure forces Corp to respect the threat.
4. **The opportunity cost of NOT running was high.** Alternative uses of Click 1 (install DZMZ for 2c, or draw, or take credit) don't advance the win condition. Running does.

***Was the second R&D run correct?***

**Marginal but defensible.** The second run cost 4 real credits and accessed Hedge Fund (a miss). Expected value: ~20% * (game win) - 80% * (4 credits wasted) = high EV because game wins are infinitely valuable. The question is whether those 4 credits could have been better used elsewhere. At 5-1, every agenda access is a potential game-winner, making even expensive runs worthwhile. However, the 4-credit cost was inflated by not optimally using Leech counters. If the break had cost 2c (with Leech), the second run would have been clearly correct.

***Send a Message's trigger:***

Corp used the stolen Send a Message trigger to rez Whitespace on Server 4 for free. This was the strongest choice available (Palisade on Server 2 was the alternative). Whitespace protects their likely future scoring remote, and since we have no decoder, it's effectively a hard ETR against us. This partially compensates Corp for losing a 5/3 agenda.

**Turn 7 Priorities:**

1. **Draw aggressively for Unity (decoder).** This is the single most important strategic objective. Without a decoder, HQ (6 cards) and Server 4 (Whitespace) are locked. With a decoder, we can contest every server on the board and likely close the game.
2. **Rebuild hand size.** At 1 card, we're flatline-vulnerable. Need to get to 3-4 cards minimum.
3. **Install DZMZ Optimizer** when economically viable. It provides +1 MU (needed for a 4th program slot for the decoder) and -1 cost on first program/turn.
4. **Continue R&D pressure if decoder not found.** With 3 Leech counters, Carmen breaks Karuna cheaply (str 3 - 3 = str 0, Carmen str 2 > 0, break 2 subs for 2c, no pump). Each R&D run costs 2c + 3 Leech counters. We regenerate 1 Leech per successful run, so this is a depleting resource.
5. **Prepare for game-winning HQ run.** If we find Unity, install it, and run HQ through Diviner: access 1 of 6 cards. With ~2-3 agendas in a 6-card hand, that's a 33-50% hit rate per access. Much better odds than R&D.

**Winning line projection:** Find Unity (1-3 draws) -> Install DZMZ + Unity (2c + 2c = 4c, or 2c + 3c = 5c without DZMZ discount) -> Run HQ through Diviner with Unity -> Steal any agenda -> WIN at 7+ points.

**The game is firmly in our control at 5-1.** Corp needs to find and score 6 more agenda points while we need just 2. They have to protect every agenda in hand, on the board, and in R&D. We only need one to slip through.

**What actually happened:** Corp installed a card in Server 4's root, played Seamless Launch (but Seamless requires targeting a card NOT installed this turn -- so it likely failed or targeted something else), and manually advanced the Server 4 card once. Net result: Server 4 has Whitespace (rezzed) + unrezzed card with 1 advancement counter. Corp ended at 4 credits, 5 cards in hand.

---

### Turn 7 - Runner (6 credits, 4 clicks)

**Hand:** DZMZ Optimizer (1 card)

**Board:** HQ: Diviner rezzed (code gate, str 3). R&D: Karuna rezzed (sentry, str 3). Server 2: unrezzed ICE + Regolith Mining License (6 credits). **Server 4: Whitespace rezzed (code gate, str 0) + unrezzed card with 1 advancement counter.** Corp: 4 credits, 5 cards in hand.

**Score:** Runner 5 (Offworld Office + Send a Message) - Corp 1 (Superconducting Hub). **Need 2 more points to win.**

**Pre-turn Assessment:**

The Corp's turn created a critical scoring threat in Server 4. The card with 1 advancement counter is almost certainly an agenda. Corp used Seamless Launch (2 advancement counters on a card not installed this turn) plus a manual advance, but the board only shows 1 advancement counter -- suggesting Seamless Launch could not legally target the card because it was installed the same turn. This means the card only has 1 advancement token from the manual advance.

**Server 4 scoring timelines (from 1 advancement):**

| Agenda | Adv Req | Current | Needed | Corp Clicks Required | Scorable Next Turn? |
|--------|---------|---------|--------|---------------------|---------------------|
| Superconducting Hub (3/1) | 3 | 1 | 2 | 2 clicks, 2c | Yes (advance x2, spare click) |
| Offworld Office (4/2) | 4 | 1 | 3 | 3 clicks, 3c | Yes (advance x3, exactly fits, 4c -> 1c) |

**Conclusion: Corp CAN score this agenda next turn regardless of type.** With 4 credits and 3 clicks, they can put 3 more advancement counters (enough for either 3/1 or 4/2). I cannot prevent the score -- I cannot contest Server 4 this turn because Whitespace (code gate) blocks me and I have no decoder.

**The Whitespace Problem:**

Whitespace subs: (1) "Lose 3 credits" and (2) "If you have 6 credits or fewer, end the run."
- At my current 6 credits: lose 3 -> 3 credits, which is <= 6, so the run ENDS.
- To brute-force past Whitespace, I need 10+ credits before the encounter (10-3=7 > 6, so sub 2 doesn't fire).
- Alternative: find a decoder (Unity) to break Whitespace's subs.

**Leech optimization discovery:**

In Turn 6, the auto-break spent 4 credits to break Karuna without using Leech efficiently. The optimal approach is to spend exactly 1 Leech counter to reduce Karuna from str 3 to str 2, matching Carmen's str 2, then break 2 subs for 2 credits. Since each successful run regenerates 1 Leech counter, the Leech supply is self-sustaining at -1/+1 per run. This means I can run R&D indefinitely at 2 credits per run as long as I have at least 1 Leech counter.

**Strategic Decision Tree:**

The fundamental question: draw aggressively for Unity, or run R&D for agenda lottery?

- **Drawing for Unity:** 1 Unity in 27 remaining deck cards = ~3.7% per draw. Even 4 draws = ~14% chance. Poor odds for the specific card.
- **R&D runs:** Each access has ~12-15% agenda hit rate. Any agenda wins. With efficient Leech usage, each run costs only 2c. With 6 credits, I can do 3 runs at 2c each = 3 accesses = ~35% combined win probability.
- **Hybrid:** Run R&D first (test luck), then draw. Best of both worlds but fewer total attempts.

**Key insight:** R&D runs and drawing BOTH have low per-attempt hit rates, but they search different pools. Drawing finds Unity (which unlocks Server 4 + HQ), while R&D runs directly hit agendas. The R&D option has higher immediate win probability but drawing for Unity unlocks far more options going forward.

**Chosen Plan: Hybrid R&D pressure + draw**

1. Click 1: Run R&D (2c, efficient Leech break). Each R&D access is a potential game-ender.
2. Click 2: Draw a card. If Unity appears, pivot to installing it. If economy, plan for next turn.
3. Clicks 3-4: React based on outcomes.

**Execution:**

| Click | Action | Result |
|-------|--------|--------|
| 1 | Run R&D | Approached Karuna. Used 1 Leech counter (3 -> 2 counters), reducing Karuna to str 2. Carmen at str 2 matched, broke both subs for 2c. Credits: 6 -> 4. Leech regained 1 counter on successful run (2 -> 3). **Accessed Predictive Planogram (operation). No steal possible.** Run successful but no agenda. Clicks: 3. |
| 2 | Draw | **Drew Unity!** (Program, decoder, 3c). The exact card I needed. Hand: DZMZ Optimizer, Unity. Credits: 4, Clicks: 2. |

**Unity changes everything.** With Unity in hand, the entire game opens up:
- Server 4 (Whitespace): Unity breaks both subs for 2c. I can contest the agenda.
- HQ (Diviner): Unity at str 1, pumps +3 with 3 installed icebreakers (Carmen, Cleaver, Unity) = str 4 > Diviner str 3. Break 1 sub for 1c. Total: 2c to crack HQ.
- R&D (Karuna): Carmen + Leech already handles this for 2c.

**I can now break every piece of ICE on the board.**

**Revised plan with Unity in hand:**

The question is whether to install Unity now or save credits for a Run. With 4 credits and 2 clicks:

| Option | Actions | End State | Analysis |
|--------|---------|-----------|----------|
| A: Install DZMZ + Unity | Install DZMZ (2c), Install Unity (2c w/ discount) | 0c, full rig, 5 MU | Complete rig but broke. Need 2+ credits to run anything next turn. Corp scores Server 4. |
| B: Install Unity + credit | Install Unity (3c), take credit | 2c, Unity online, DZMZ in hand | Can run R&D immediately next turn (2c). Corp scores Server 4. |
| C: Install Unity + run R&D | Install Unity (3c), run R&D (need 2c, only have 1c) | N/A | **Cannot afford.** |
| D: Run R&D + install Unity | Run R&D (2c), install Unity (3c, only 2c left) | N/A | **Cannot afford.** |

**Options C and D are impossible** -- running R&D costs 2c and Unity costs 3c, total 5c > 4c available.

**Option B is optimal.** Ending at 2 credits means I can run R&D on the first click of Turn 8 (2c to break Karuna with Leech). Or if Corp doesn't score Server 4, I can run there (2c for Whitespace). Option A wastes the DZMZ discount benefit since I won't install another program soon, and the 0-credit end state delays my next run by 1-2 clicks of credit gathering.

**Accepting the Server 4 score:** Corp will almost certainly score the agenda in Server 4 on their turn (1 adv + 2-3 more = 3 or 4 total). This is unavoidable. The score goes to either 5-2 (if Superconducting Hub) or 5-3 (if Offworld Office). Both are survivable -- I still only need 2 points to win, and with a complete breaker suite, I can contest every server.

| Click | Action | Result |
|-------|--------|--------|
| 3 | Install Unity | Paid 3c. Decoder (str 1) installed. Full breaker suite now online: Carmen (killer), Cleaver (fracter), Unity (decoder). Credits: 4 -> 1. Clicks: 1. |
| 4 | Take credit | Credits: 1 -> 2. Clicks: 0. Turn auto-ended. |

**End of Turn State:**
- **Score: Runner 5 - Corp 1**
- **Credits:** 2
- **Hand:** DZMZ Optimizer (1 card)
- **Rig:** Leech (3 virus counters), Carmen (killer, str 2), Cleaver (fracter, str 3), Unity (decoder, str 1), Smartware Distributor (depleted)
- **MU:** 4/4 (Leech 1 + Carmen 1 + Cleaver 1 + Unity 1)
- **Discard:** Mayfly, Mutual Favor, Jailbreak, Telework Contract, Sure Gamble, Overclock, Creative Commission (7 cards)
- **Corp:** 4 credits, 5 cards in hand, Server 4 agenda with 1 advancement

**Post-Turn Analysis:**

***What went well:***
- **Drew Unity on the first draw attempt.** At 3.7% per draw, this was lucky. The single most important card in the deck for the current game state. This transforms the game from "locked out of 2 servers" to "can break every ICE on the board."
- **Efficient Leech management on R&D run.** Spent exactly 1 Leech counter (not 3), regenerated 1 on success. Net zero Leech consumption. Breaking cost was 2c instead of the 4c we paid on Turn 6. This optimization saves 2 credits per R&D run -- massive over multiple runs.
- **Full breaker suite online.** Carmen (sentries), Cleaver (barriers), Unity (code gates). Every ICE type is covered. No server on the board is safe from us.
- **R&D access provided information.** Predictive Planogram is a Corp economy operation -- knowing it's on top of R&D tells us the next card is NOT an agenda. Useful for deciding whether to run R&D again next turn (the deck doesn't shuffle between runs, but we accessed the top card which moves to the next one).

***What went badly:***
- **R&D access missed (Predictive Planogram).** No agenda on top. The 12-15% miss rate is expected, but a hit here would have won the game outright.
- **Hand size is still dangerously low (1 card).** If Corp lands any net damage (Diviner face-check on HQ, or a surprise interaction), we could flatline. Need to draw up next turn.
- **Cannot prevent Corp from scoring Server 4.** The 2-credit end state and 0 remaining clicks mean the agenda is lost. This was an unavoidable consequence of the economy situation.
- **Smartware Distributor is depleted and dead weight.** No more passive income. Economy is pure click-for-credit until we draw more economy cards.

***Was the hybrid plan correct?***

**Yes.** The R&D run first was correct because:
1. It cost 2 credits (cheap) and had ~12% chance of winning the game immediately.
2. Even on a miss, Leech regenerated, maintaining future run efficiency.
3. Drawing second gave us the chance to find Unity AND pivot the plan. If we'd drawn first and found Unity, we'd have wanted to install it and still couldn't afford to run anyway.
4. The R&D access before drawing is strictly better than drawing before R&D access because it costs the same number of clicks either way, but the R&D run has a chance of making the draw irrelevant (game win).

***Was installing Unity over DZMZ correct?***

**Yes.** The 2-credit advantage of Option B (Unity + credit) over Option A (DZMZ + Unity) is decisive. Starting next turn at 2c vs 0c means:
- At 2c: Run R&D immediately (Click 1: run, 2c to break Karuna). Then 0c, 3 clicks to draw/credit.
- At 0c: Credit, credit, then run (Click 3: run, 2c to break). Lost 2 clicks of tempo.

The DZMZ Optimizer's benefits (+1 MU, -1 program cost) are minimal when we already have 4/4 MU and no immediate program installs planned. Its value is speculative; the 2 credits are concrete.

**Turn 8 Priorities:**

1. **Expect Corp to score Server 4 agenda.** Score becomes 5-2 or 5-3. Unavoidable.
2. **Run R&D first click** (2c with Leech, ~12% hit rate). Any agenda wins.
3. **Draw aggressively** -- need hand buffer (flatline protection) and economy cards.
4. **Plan HQ assault.** With Unity, Diviner costs 2c to break. Corp has 5 cards in hand. If ~2 agendas are in HQ, each access has ~40% hit rate. HQ runs are higher density than R&D (5 cards vs 27 cards, same number of agendas proportionally).
5. **Monitor Server 4 for re-use.** After Corp scores, they may install another agenda in Server 4 behind Whitespace. Now we can contest it immediately.

**Winning scenarios for Turn 8:**
- R&D access hits agenda: WIN
- HQ access hits agenda: WIN (need 2c per HQ run through Diviner)
- Corp installs naked agenda: WIN (free run, immediate steal)

**The game state is strongly in our favor.** 5-1 (or 5-2/5-3 after Corp scores) with a complete breaker suite, 3 Leech counters for efficient breaking, and access to every server. Corp must protect every agenda perfectly; we only need one to slip through. The math strongly favors the Runner closing this game within 2-3 turns.

**What actually happened:** Corp advanced the Server 4 card twice and scored Orbital Superiority (4/2 agenda). This gave Corp 2 agenda points (1 -> 3) and triggered Orbital Superiority's ability: gave Runner 1 tag. Corp ended at 1 credit, 6 cards in hand. Score is now Runner 5 - Corp 3.

---

### Turn 8 - Runner (2 credits, 4 clicks after start-turn)

**Hand:** DZMZ Optimizer (1 card)

**Board:** HQ: Diviner rezzed (code gate, str 3). R&D: Karuna rezzed (sentry, str 3). Server 2: unrezzed ICE + Regolith Mining License (6 credits). Server 4: Whitespace rezzed (code gate, str 0) -- empty after Orbital Superiority scored. Corp: 1 credit, 6 cards in hand.

**Score:** Runner 5 (Offworld Office + Send a Message) - Corp 3 (Superconducting Hub + Orbital Superiority). **Need 2 more points to win.**

**Pre-turn Assessment:**

Corp scored Orbital Superiority from Server 4, going from 1 to 3 agenda points. The scoring trigger gave Runner 1 tag. Corp ended at only 1 credit -- they're broke.

**Tag Assessment:**

I now have 1 tag. The key question: remove it or ignore it?

Tag removal costs: 2 credits + 1 click. That's ALL my money and 25% of my turn.

Tag danger with Corp at 1 credit:
- Trash resources ($2 + click): Corp can't afford it. Smartware Distributor is depleted anyway (0 credits).
- Public Trail ($2 trace): Corp can't afford to play it.
- Punitive Counterstrike: Not in Startup card pool / not relevant.
- BOOM!: Not in this format.
- Predictive Planogram: Corp gains extra value from it while Runner is tagged (draw 3 + gain 3 instead of choosing one). This is the main tag danger -- it gives Corp economic acceleration.

**Conclusion: IGNORE THE TAG.** Corp at 1 credit cannot exploit the tag this turn. The 2 credits + click are worth far more as run fuel. If I survive this turn without Corp punishing the tag, I can reassess next turn.

**HQ is the highest-value target.**

Corp has 6 cards in hand. Remaining agendas in the game:
- 1x Offworld Office (4/2) -- 2 points, game-winning
- 1x Orbital Superiority (4/2) -- 2 points, game-winning (Corp scored the other copy)
- Plus any unseen 3/1 agendas

Estimated 2-3 agendas in Corp's 6-card hand = ~33-50% hit rate per random access. Each HQ access costs 2 credits (Unity breaks Diviner for 2c).

**R&D comparison:** 27 cards with ~3-4 agendas scattered = ~11-15% per access. R&D costs 2c + 1 Leech counter per access. HQ is roughly 2-3x better hit rate.

**Strategic Plan: Double HQ pressure.**

1. Click 1: Run HQ (2c to break Diviner with Unity). ~33% win chance.
2. Clicks 2-3: Take credit x2 (rebuild to 2c).
3. Click 4: Run HQ again (2c). ~33% win chance.

Combined: ~56% chance of winning this turn across two HQ accesses. This is the highest-probability line available.

**Why not clear tag + run?** Clearing tag (2c + 1 click) leaves 0c, 3 clicks. Need 2 clicks for credits + 1 click to run = only 1 HQ access. 33% win rate vs 56% for double HQ. Worse in every way.

**Why not R&D?** R&D at ~13% per access vs HQ at ~33%. Even accounting for the Leech efficiency on R&D (2c + 1 Leech vs 2c pure for HQ), HQ's density is far superior.

**Execution:**

| Click | Action | Result |
|-------|--------|--------|
| 1 | Run HQ | Approached Diviner. Unity (str 1 + 3 icebreakers = str 4) matched and fully broke Diviner (str 3) for 2c. Passed Diviner, breached HQ. Leech gained 1 virus counter (3 -> 4). **Accessed Hedge Fund (operation). No steal.** Credits: 2 -> 0. Clicks: 3. |
| 2 | Take credit | Credits: 0 -> 1. Clicks: 2. |
| 3 | Take credit | Credits: 1 -> 2. Clicks: 1. |
| 4 | Run HQ | Approached Diviner. Unity matched and fully broke Diviner for 2c. Passed Diviner, breached HQ. Leech gained 1 virus counter (4 -> 5). **Accessed Predictive Planogram (operation). No steal.** Credits: 2 -> 0. Clicks: 0. Turn ended. |

**End of Turn State:**
- **Score: Runner 5 - Corp 3** (unchanged)
- **Credits:** 0
- **Hand:** DZMZ Optimizer (1 card)
- **Rig:** Leech (5 virus counters), Carmen (killer, str 2), Cleaver (fracter, str 3), Unity (decoder, str 1), Smartware Distributor (depleted)
- **MU:** 4/4
- **Tags:** 1
- **Discard:** Mayfly, Mutual Favor, Jailbreak, Telework Contract, Sure Gamble, Overclock, Creative Commission (7 cards)
- **Corp:** 1 credit, 6 cards in hand

**Post-Turn Analysis:**

***What went well:***
- **Executed the highest-EV line.** Two HQ accesses at ~33% each gave ~56% cumulative win probability. This was the correct strategic choice.
- **Unity performed flawlessly.** Both Diviner breaks cost exactly 2c (pump +3 for 1c, break 1 sub for 1c). The breaker suite is fully operational -- every server on the board is accessible.
- **Leech accumulated to 5 counters.** Each successful HQ run added a counter. Five Leech counters are an enormous reserve for future ICE encounters -- Karuna on R&D costs 0 pump credits (str 3 - 3 = str 0, Carmen str 2 > 0), just 2c to break 2 subs.
- **Ignored the tag correctly.** Corp at 1 credit could not exploit the tag. Spending 2c + 1 click on removal would have reduced HQ accesses from 2 to 1, cutting win probability from ~56% to ~33%.

***What went badly:***
- **Both HQ accesses missed agendas.** Hit Hedge Fund (click 1) and Predictive Planogram (click 4). At ~33% per access, missing both has ~44% probability -- unlucky but not improbable. The two cards accessed were both operations, confirming that Corp's hand is heavy on non-agenda cards.
- **Ended at 0 credits.** Fully committed to the double-run strategy, leaving nothing for next turn. Will start Turn 9 at 0 credits with no Smartware drip (depleted).
- **Hand size still dangerously low (1 card).** With a tag, if Corp plays Orbital Superiority from HQ... wait, that's an agenda, not an operation with meat damage. The tag's main risk is Predictive Planogram giving Corp extra economy (gain 3 + draw 3 while tagged), but Corp needs 0 credits to play it. Actually -- Predictive Planogram costs 0! Corp CAN play it at 1 credit. If they play Planogram while I'm tagged, they get both effects: gain 3 credits AND draw 3 cards. That's a massive swing from their 1-credit position. **The tag IS exploitable via Planogram.**
- **Predictive Planogram observation:** I accessed it from HQ, confirming Corp has at least one copy. If they draw it (or already have it), they'll play it while I'm tagged for maximum value. This makes tag removal more important than I initially assessed.

***Was the double HQ line correct?***

**Yes, even though both accesses missed.** The reasoning:

1. Expected value: 56% chance of winning the game immediately. No other line comes close.
2. Opportunity cost: Clearing the tag would have cost exactly 1 HQ access (from 2 to 1), reducing win probability by ~23 percentage points. Even accounting for Planogram risk, the immediate win probability is worth more.
3. Information gained: We now know Hedge Fund and Predictive Planogram are in Corp's hand. That narrows the composition: of 6 cards, at least 2 are operations. The remaining 4 cards include the agendas (if any), ICE, and other cards.
4. The miss doesn't invalidate the strategy. Variance is inherent in random HQ access. Two misses at 33% each is a ~44% outcome -- well within normal bounds.

***Planogram Tag Interaction:***

This is a significant tactical update. Predictive Planogram (0 cost operation) resolves one of: gain 3 credits OR draw 3 cards. But if Runner is tagged, Corp resolves BOTH. At 1 credit, Corp playing Planogram while I'm tagged would go from 1 credit to 4 credits AND draw 3 cards. That's an enormous swing from a nearly-broke position.

This means the tag has concrete economic consequences even with Corp at 1 credit. However, the Turn 8 decision was still correct because:
- Winning the game immediately (56% chance) > preventing Planogram value
- Corp may not have Planogram in hand (we accessed one copy, they may not have another)
- Even with Planogram, Corp goes to 4 credits + 9 cards -- strong but not devastating

**Turn 9 Priorities:**

1. **Economy first.** Starting at 0 credits, I need at least 2c to run anything. Click for 2 credits minimum.
2. **Reassess tag.** If Corp plays Planogram and gains massive economy + hand size, the tag becomes more dangerous (they can afford resource trashing, Public Trail for more tags, etc.). Consider removing tag if economy permits.
3. **Continue HQ pressure.** HQ density is still the best target. Each access is ~33% win rate. At 2c per access, each credit-credit-run cycle gives one shot.
4. **Draw for hand buffer.** With 1 card in hand, any net damage flatlines me. Diviner does 1 net damage if subs fire -- but I'm breaking it. Karuna does 4 net damage if unbroken -- only relevant on R&D, and Carmen handles it. The main risk is a surprise interaction or Corp card that does damage outside of ICE.
5. **Monitor for naked remotes.** If Corp installs an agenda without ICE protection, run it immediately. This won the game on Turn 5 (Offworld Office steal).

**Winning projection:** With 5 Leech counters, full breaker suite, and HQ access unlocked via Unity, I can run HQ every other click (credit, credit, run = 3-click cycle for 1 access). Over 2-3 turns, that's 3-4 more HQ accesses at ~33% each = ~80-90% cumulative win probability. The game should close within 2-3 turns barring extreme bad luck or Corp finding a way to lock me out.

**The critical variable is Corp's next turn.** If they play Planogram (tagged bonus), rebuild to 7+ credits, and install an agenda behind double ICE, the game could extend. But at 5-3, time is on our side -- Corp needs 4 more points while we need 2. Every turn that passes without Corp scoring is a turn closer to our eventual win.

---
