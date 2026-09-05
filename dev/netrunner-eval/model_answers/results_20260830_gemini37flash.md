# Netrunner Reasoning Eval — Gemini 3.7 Flash (blind, first run)

**Date:** 2026-08-30
**Set:** all 22 problems, 7-point harmonized set, keys as of `6b2c98309` (the
post-2026-07-22 corrections are IN for this run — see Comparability below).
**Protocol:** same as the Fable/Sol head-to-head — `SKILL_SOLVE.md` preamble +
byte-identical `build-eval` bundle, one shot, no repo access, no answer keys.
Backend: native `gemini` CLI 0.23.0, `-m gemini-3.7-flash`, API-key auth,
isolated config home. Model identity confirmed from the CLI's own stats block
(`models: ['gemini-3.7-flash']`), not inferred from the flag.
**Cost/time:** all 22 solved in one shot, ~7 minutes, no truncation, ~26k input.
**Scorer:** Claude Opus 5 (session), open-book, every model/key disagreement
re-checked against the q-file.
**Raw:** `gemini37flash_20260830.txt`.

## Headline (21 in-scope; trace-001 tracked separately, as in the July table)

| Model | Fully correct | Partial | Wrong | Strict | Lenient (partial=½) |
|-------|--------------|---------|-------|--------|---------------------|
| Claude Fable 5 (2026-07-22) | 15 | 6 | 0 | **71%** | **86%** |
| GPT-5.6 Sol high (2026-07-22) | 12 | 8 | 1 | **57%** | **76%** |
| **Gemini 3.7 Flash (this run)** | 11 | 8 | 2 | **52%** | **71%** |

Correct: breach-001, break-001, lethal-001, mull-001/2/3/4, multirun-001,
partial-001, remote-001, yomi-001 (+ trace-001, out of scope).
Partial: midgame-001, remote-002, score-001, scoring-002, turn1-001, turn1-002†,
turn1-003, turn1-004.
Wrong: servers-001, window-001.

† disputed key cell (see July results doc, item 2) — Gemini played the identical
Diviner/Karunā rez-coverage line both July models played.

## Where it lands

Third of three, but within one problem of Sol on strict and 5 points on lenient —
not the blowout "benches below Terra" would predict. Its arithmetic is genuinely
reliable: every credit and click total I re-derived checked out, including the
exact-$13 lethal-001 line and both partial-001 end states. It commits to concrete
lines and never declared a position impossible (Sol's inherited under-commit tic).

**It beat Sol on the tempo flagship's decision.** midgame-001 is where Sol declared
the server "mathematically inaccessible" and built economy — losing on the spot to
Send a Message. Gemini identified the same threat clock, proved the trap could not
flatline a 5-card grip, and ran. That is the right call, and Sol's lineage does not
make it.

**Its line for that run then loses to Brân's first subroutine.** It click-broke only
the two ETRs, leaving the ice-install sub to fire — with two ICE (Palisade,
Whitespace) *known* to be in HQ from last turn's Docklands breach, printed in the
q-file it was given. The Corp installs one directly inward for free and the run dies
with $2 and 0 clicks. It missed the Smartware turn-start credit that funds the key's
guaranteed all-three-subs line (so did Fable and Sol), but unlike Fable it built no
fallback proof. Right read, dead line — scored partial; a harsher grader says wrong.

## The two real failures (both strategic, not arithmetic)

1. **window-001 — WRONG, hits two of the key's three named traps at once.** It
   played Hedge Fund first (already at $11; costs a shield off a flooded HQ) and
   then jammed **Send a Message** — the exact "Jam Send a Message Trap": a 5/3
   needing three more advancements, with no Seamless in hand, parked behind an ICE
   that costs $0-and-3-clicks to pass. Its own analysis had *acknowledged* the
   click-break two paragraphs earlier and then priced the remote as safe anyway.
2. **servers-001 Q2 — WRONG, and lethal.** Its 7-access chain tanks Tithe's net
   damage on a grip that its own line has already emptied. The third tank is a
   flatline against 0 cards — the key's named common mistake verbatim. Q1 (4
   guaranteed accesses) is the key's own named defensible alternative and is fine.

**The pattern:** it prices ICE correctly and then reasons about *safety* as if the
price were a wall. remote-002 (claims a "guaranteed flatline" lock, missing
draw-to-4-then-tank), score-001 and turn1-001 (advances a to-be-scored agenda /
Palisade on a central — Sol's information-tell demerits) are the same axis. Damage
and ETR maths right; the threat model built on top of them over-claims security.

## Key error found this run

**mull-002 (KEY ERROR, minor):** the key says "THREE agendas (**4 points total**)".
The hand is Superconducting Hub (1) + Send a Message (3) + Offworld Office (2) =
**6 points**. Gemini counted 6. The verdict (MULLIGAN) is unaffected; the stated
reasoning is wrong. Fix the count.

Also worth noting: breach-001's key still frames Q3 as "take a credit first —
NOW Sure Gamble is useful", implying SG is unplayable at $5. It isn't ($5 − $5 +
$9 = $9); Gemini and Fable both opened SG directly. The q-file only asks for three
distinct Click 1s, so nothing was misgraded, but the key's stated "aha" is false.

## Comparability caveat

The July Fable/Sol scores were taken against pre-`6b2c98309` keys and were never
restated. This run used the corrected keys, which are **stricter** on window-001
(the corrected key names both of Gemini's choices as traps) and clearer on
servers-001 and breach-001. Treat the 5-point lenient gap to Sol as soft. What is
not soft: the two wrongs are wrong on the merits, independent of key wording.

## Missing rung

There is no Luna run on this set. Luna is the model whose seat behaviour we know
best ("plays each decision defensibly, cannot hold a plan"), and it is the natural
floor to bracket Gemini against. One `codex exec` at the same protocol closes it.
