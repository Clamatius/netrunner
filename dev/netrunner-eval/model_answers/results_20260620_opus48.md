# Netrunner Reasoning Eval — Claude Opus 4.8 (blind run)

**Date:** 2026-06-20
**Model:** Claude Opus 4.8 (1M), fresh context, blind (no answer keys, no repo access — fed only the `build-eval` output)
**Set:** all 22 problems (the prior 2026-01-06 benchmark used 11)
**Scorer:** Opus 4.8 (autonomous polish round) against `problems/*-a.md`, with board-state and card-fact discrepancies verified against the q-files and `card_lookup.json`.

## Headline

| Bucket | Count | Problems |
|--------|-------|----------|
| **Fully correct** | 13 | break-001, mull-001/002/003/004, partial-001, remote-002, score-001, servers-001, turn1-001, turn1-003, window-001, yomi-001 |
| **Partial** | 6 | breach-001, multirun-001, scoring-002, trace-001, turn1-002, turn1-004 |
| **Wrong** | 3 | lethal-001, midgame-001, remote-001 |

Strict (fully-correct only): **13/22 ≈ 59%**. Lenient (partial = ½): **16/22 ≈ 73%**.
Not directly comparable to the 2026-01-06 run (11-problem subset; Opus 4.5 = 73%, GPT 5.1 High = 91%) — this set is larger and weighted toward Hard.

**Two of the model's "disagreements" with the answer key were the key being wrong, not the model.** See doc bugs below. This is the cleanest model-vs-doc separation the eval has produced.

## Doc / harness bugs the model surfaced (model RIGHT, doc WRONG)

1. **`partial-001-q.md` grip mismatch (fixed this round).** Question board state had `grip: [Carmen]` (1 card); the answer key computes every line with a **3-card grip** and makes the partial-break line (Q2) the intended answer. With 1 card, Q2 is genuinely impossible (Karunā's 2 net damage flatlines a 1-card grip; Palisade's only sub is ETR). The model correctly answered for the board as written and correctly called Q2 impossible. Fix: restored grip to 3 non-critical cards to match the answer key's intent (card choice is a judgment call — adjust if desired).

2. **Government Subsidy cost wrong in two answer keys (fixed this round).** `mull-002-a.md` and `turn1-002-a.md` stated Gov Subsidy "needs 6 credits." The card (and the q-file auto-text) says **Cost 10**. The model read 10. Doesn't flip either verdict (still mulligan / still unplayable), but it was a stale fact. Fixed all 4 references.

3. **(minor) `midgame-001` win-threshold note missing.** The answer's "Corp scores Send a Message → Corp wins" logic implies a 6-point game, but the q-file has no tutorial-6 note (ID is 40-card Catalyst, i.e. standard 7). At 7, scoring SaM is 6-3, not a win. Doesn't excuse the model's error there (see below), but the note should be made explicit either way.

4. **(minor) `trace-001` is out of scope.** It uses classic-era cards (Scorched Earth, Plascrete, Adonis, Ichi, Sneakdoor, NAPD, ABT) absent from the tutorial `decklists.md`, so it tests training recall rather than provided-context reasoning like the other 21. Either annotate it as a knowledge problem or port it to tutorial cards.

5. **(minor) `remote-001` difficulty inconsistent** — title tag `[Medium]`, answer says "Why This Is Hard". Cosmetic.

## Genuine model errors (the interesting part)

### remote-001-corp — fell for the headline trap (WRONG)
The puzzle's whole point: "missing breakers don't matter if you can tank." Whitespace (code gate) and Tithe (sentry) both had their full sub text in the question — **neither has a hard ETR** (Whitespace: lose 3¢ / ETR-only-if-≤6¢; Tithe: 1 net damage / Corp gains 1¢). The Runner (Red Team + Pennyshaver ≈ $11) can tank through and break the two Palisades with Cleaver. The model said "no Decoder for Whitespace, no Killer for Tithe → cannot breach." It treated breaker-type gaps as walls and never checked whether the subroutines actually end the run. **Model error, high signal** — this is exactly the "subroutines are a tax, not a wall" lesson, and the model had all the text in front of it.

### midgame-001-runner — tempo miscount, failed to contest (WRONG)
The 2-counter remote is almost certainly Send a Message (5/3). The model wrote "Send a Message needs 5 advancements, has 2, **not scorable soon**" and chose to build economy instead of contesting. But Corp finishes it in **one turn** (3 advance clicks: 2→5 = score). The correct play (per key) is run it *now*, click through all of Brân to deny the free ICE-install sub, and contest. The model under-counted the threat clock and passed on a game-deciding agenda. **Model error, and it's squarely on the [070]/[069] tempo-blindness axis** — the model didn't treat "how close is the Corp to scoring" as the first-class question, exactly the gap michael-nr flagged.

### lethal-001-runner — missed a 2-step guarantee (WRONG, hard)
Needs: run Archives first (free successful run → Carmen install discount), then trash Manegarm Skunkworks from HQ on the first access to *guarantee* the second access hits the agenda, using a partial break to afford it. The model missed both the Archives-discount and the trash-to-guarantee, and concluded no guarantee exists (it did flag its own uncertainty). Very hard; still a model miss.

### Partials worth noting
- **scoring-002** — got the win target (Send a Message) and Seamless timing right, but **omitted Manegarm Skunkworks from the main line** — the load-bearing upgrade that is the entire puzzle. It gestured at Skunkworks in the Q2 discussion but didn't put it in the answer.
- **turn1-004** — Corp is wide open (no ICE anywhere). Model ran only the remote and installed, missing the free R&D + HQ accesses ("run *everything*"). A listed common mistake.
- **breach-001** — found 2 of the 3 distinct lines (matching key Q1/Q2); its third line had a 1-credit arithmetic slip and it never found the "click-for-credit-first unlocks Sure Gamble" insight.
- **multirun-001** — landed on the right number (2) but never cleanly constructed the Archives-discount line; reasoning was muddled.
- **turn1-002** — iced both centrals correctly but clicked for credit instead of installing Brân on a remote for the Send-a-Message free-rez synergy.
- **trace-001** Q1/Q2 correct; Q3 missed the Archived Memories recursion kill (replay Scorched from Archives) + that trace core-damage drops the grip below the double-Scorched threshold.

## Model strengths observed
- **Seamless Launch "did not install this turn"** — got it right every time it mattered (score-001, scoring-002, yomi-001, window-001). No same-turn fast-advance illusions.
- **All four mulligans correct** with sound economy/ICE-balance reasoning.
- **Yomi / leveling game (yomi-001)** — captured "the threat of an agenda is a resource; condition the opponent first" well.
- Strong on remote-002 (agenda belongs behind Karunā because the Runner lacks a Killer).

## Takeaways for the project
- The model's recurring blind spot is **the threat clock and the tax-vs-wall distinction** — both are *tempo* reasoning, the same axis as the marquee-game [070] "red mist" observation. The playbooks frame "can I break this ICE?" far more prominently than "how close is the opponent to winning, and is this subroutine actually a wall?" Candidate playbook addition: a first-class "threat clock" check (how many clicks until the opponent scores/wins) and an explicit "does this subroutine end the run?" triage step.
- The eval now cleanly distinguishes model error from doc error: 3 genuine misses, 2 confirmed doc bugs the model was right about. That's the signal we wanted.

Raw blind solve: `model_answers/opus_4.8.txt`.
