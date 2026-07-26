# Netrunner Reasoning Eval — Fable 5 vs GPT-5.6 Sol (blind head-to-head)

**Date:** 2026-07-22
**Set:** all 22 problems, **first run on the 7-point harmonized set** (commit `77ef06b67`).
NOT comparable to the June 6-point runs — point-sensitive boards were renumbered and the
scoring-002 key was rewritten (bioroid click-break fix) before this run.
**Protocol:** identical input for both models — same solver preamble + `build-eval` bundle,
byte-for-byte. Fable: fresh isolated subagent, no repo access. Sol: `codex exec`,
read-only sandbox, `-m gpt-5.6-sol`, `model_reasoning_effort=high` (June parity).
**Scorer:** Claude Fable 5 (session), open-book against `problems/*-a.md`, every
model/key disagreement verified against the q-file. **Caveat:** single scorer,
same vendor as one contestant; symmetric standards applied (Fable docked to partial
on remote-002 / servers-001 / midgame-001), but a cross-vendor re-judge of the
disputed cells is cheap insurance if the margin matters.
**Raw:** `fable5_20260722.txt`, `gpt56_sol_20260722.txt`.

## Headline (21 in-scope; trace-001 tracked separately, see below)

| Model | Fully correct | Partial | Wrong | Strict | Lenient (partial=½) |
|-------|--------------|---------|-------|--------|---------------------|
| **Claude Fable 5** | 15 | 6 | 0 | **71%** (15/21) | **86%** |
| **GPT-5.6 Sol (high)** | 12 | 8 | 1 | **57%** (12/21) | **76%** |

Fable buckets — correct: breach-001, break-001, lethal-001, mull-001/2/3/4,
multirun-001, partial-001, remote-001, score-001, scoring-002, turn1-001, turn1-004,
yomi-001. Partial: midgame-001, remote-002, servers-001, turn1-002†, turn1-003,
window-001†. Wrong: none.

Sol buckets — correct: breach-001, break-001, lethal-001, mull-001/2/3/4,
multirun-001, partial-001, remote-001, scoring-002, yomi-001. Partial: remote-002,
score-001, servers-001, turn1-001, turn1-002†, turn1-003, turn1-004, window-001†.
Wrong: midgame-001.

† = scored against a key that is itself disputed/flawed (see Key errors). If those
keys are reworked toward the models' shared position, both models likely upgrade.

## The generational result

The June runs' three shared-wrong problems (lethal-001, midgame-001, remote-001) and
the excluded trace-001 were the set's teeth. This generation:

- **lethal-001**: both fully correct, guaranteed exact-$13 lines. Fable tanked on run 2
  (key's order) and chose R&D over Archives with the free-access argument; Sol tanked on
  run 1 — mirror order, equally exact. June: 0/2 models.
- **remote-001**: both fully correct incl. the tank-Whitespace-at-$7 threshold, and both
  added an insight the key lacks (installing Offworld into Server 1 trashes the Corp's
  own hosted Regolith). June: 0/2 — this was the "subroutines are a tax" shared blind spot.
- **trace-001**: both solved all three sub-questions perfectly from supplied card text,
  including the Archived Memories → double Scorched recursion and the exact
  boost-8/Adonis-funding arithmetic. **Recommend un-excluding this problem** — the
  "out-of-scope recall" rationale did not bind for either model.
- **scoring-002** (rewritten key): both found the Manegarm lock AND the bioroid
  click-break threat that the old key missed — Sol reproduced the empty-grip-Tithe
  flatline nuance, Fable enumerated the lockout proof. Had the key not been fixed
  the day before, both would have been scored against a wrong threat model.
- **breach-001**: both produced three valid exactly-accounted lines (June GPT declared
  one impossible). Fable's third line — a plain basic run, click through Brân, pay
  Manegarm $5 from the starting pool, zero cards played — is legal and absent from the key.

## Where they separated

**midgame-001 (the tempo flagship) decided the strict gap's headline.** Both models
nailed the renumber's agenda-accounting (all Offworlds/Hubs gone → remote is Send a
Message or Urtica, double game point) and the threat clock. Both then **missed the
Smartware Distributor turn-start credit** (pool is $3 on click 1, not $2) — which is
exactly what funds the key's guaranteed run-on-click-1, click-all-of-Brân line.
From there:

- **Fable** engineered a fallback: Telework first, run click 2, click-break Brân's two
  ETRs and *let the ice-install sub fire*, with a per-branch proof that every Corp
  response either still gets breached or drops Corp below the credits needed to score
  next turn. Sophisticated salvage of a miscount — partial.
- **Sol** declared the server "mathematically inaccessible" and built economy — losing
  on the spot if the card is Send a Message. This is the *identical* failure signature
  GPT-5.5 showed on this problem in June ("the exact run line appears impossible" →
  econ fallback), down to correctly counting the threat first. The lineage tic —
  under-commit and declare impossible when the line is tight — survived a model
  generation. Wrong.

Secondary separators, all small: Sol placed Palisade on a central (turn1-001), advanced
a to-be-scored agenda unnecessarily twice (score-001, window-001 — information tells),
and punished only 1 of 3 free accesses on turn1-004. Fable's only unforced analytical
error all run: claiming remote-002's Karunā jam leaves "jack out or flatline" as the
only branches — missing draw-once → 4-card grip → tank 4 net → steal at 0 cards
(which Sol found, sharper than the key's own two-draw version, before retreating to
the key's named Nico trap anyway).

> **Post-run update (2026-07-22, same day):** all six items below were fixed in
> commit `6b2c98309` after Michael adjudicated. Two grew on inspection: window-001 had a
> *second* independent error (Brân has only 2 ETRs — sub 1 installs ice and can be
> declined, so both ETRs cost $7 exactly, not $8), and breach-001 turned out to have a
> board misread of its own (Karunā is under HQ's `ice:` — protecting HQ, not in it — so
> the key's "all valid solutions must break sub 1" was unfounded). breach-001 gained a
> **Q4** (the cheapest line uses no cards at all). Scores above are *not* restated
> against the corrected keys; treat the turn1-002 and window-001 cells as understated
> for both models.

## Key errors & issues found this run (the bet paid)

1. **window-001 (CONFIRMED KEY ERROR):** Q1 claims the Runner "literally cannot afford"
   Cleaver-through-Brân ($8 vs $7) and that "Server 1 is safe this turn." One
   Pennyshaver click makes it $10, and the $0 whole-turn click-through contest exists
   regardless — both models called this out independently. Same bioroid-blindness
   class as scoring-002's old key. The Hub-jam line may still be defensible as
   "1-point bait is a fine trade," but the key's stated justification is false and
   its "All-In Bluff" trap rebuttal never engages the empty-grip payoff. Needs rework.
2. **turn1-002 (KEY JUDGMENT DISPUTED):** both models independently played Karunā-on-HQ
   / Diviner-on-R&D / click-for-credit, noting $6 exactly covers both rezzes, whereas
   the key's Brân-on-remote line leaves $5 and cannot rez both ICE if probed. The key
   should either rebut the rez-coverage math or admit the alternative.
3. **servers-001 (Q-FILE AMBIGUITY):** "maximum number of cards you can access" —
   key answers best-case (6, needs both Jailbreak chain draws to hit); both models
   answered guaranteed (Fable 4 damage-free, Sol 5 raw with staleness caveats) and
   explicitly rejected the luck-dependent chain. One disambiguating sentence in the
   q-file ("assume best-case draws" or ask for both numbers) fixes it.
4. **remote-002 (MINOR KEY SLIP):** Option B has the Runner draw twice before tanking
   Karunā; one draw suffices (4 cards vs 4 net, survive at 0). Sol's version is correct.
5. **breach-001 (KEY GAP, enhancement):** add the zero-card baseline line (basic run +
   3 click-breaks + $5 Manegarm) Fable found.
6. **trace-001 (SCOPE):** consider un-excluding (see above).

## Behavioral signatures

- **Sol** keeps the GPT-lineage under-commit tic at exactly the inherited coordinates
  (midgame-001), and leaks small information tells (unnecessary advancements) the keys
  punish. Otherwise sharp — its arithmetic was flawless all run, and it found genuine
  lines the keys missed (multirun-001 Pennyshaver two-run route, remote-002 one-draw tank).
- **Fable** committed to a concrete line on all 22 (no impossibility claims), was the
  only model to match the never-advance disguise plays (score-001, window-001-adjacent),
  but overclaimed safety once (remote-002) — its miss class is enumerating the
  *opponent's* preparatory clicks, not its own arithmetic.
- **New shared blind spot, doc/eval target:** on-board **turn-start triggers**
  (Smartware drip) — both models computed the click-1 pool without it on midgame-001.
  Neither playbook currently drills "recount your pool at turn start including drips."

## Takeaways

- Fable 5 edges Sol on this set, 15-12 strict with zero wrongs; the margin is one real
  reasoning separation (midgame-001) plus accumulated small tells, not arithmetic.
- The June tempo/threat-clock axis is essentially closed at this generation; the
  residual frontier is fine-grained state tracking (turn-start triggers, opponent prep
  clicks) and information discipline (when advancing telegraphs).
- Key-error rate continues to track model-error locations: every key issue found this
  run sits on a problem where a June model also stumbled. Suspect the key hardest
  exactly where models "fail."
