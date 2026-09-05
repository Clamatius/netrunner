# Netrunner Reasoning Eval — Gemini 3.8 Flash vs Claude Opus 5 (blind)

**Date:** 2026-09-03
**Set:** all 22 problems, 7-point harmonized set. Keys as of `6b2c98309` **plus the
mull-002 point-count fix made this session** (key said 3 agendas = "4 points"; it is 6 —
Hub 1 + Offworld 2 + Send a Message 3). Contestant input is q-only, so that fix changed
no model's prompt.
**Protocol:** `SKILL_SOLVE.md` preamble + `build-eval` bundle (103609 B,
sha256 `6f446a50…`), one shot, no repo access intended, no answer keys.
Both seats launched via `askmodel` from an empty scratch cwd.
**Scorer:** Claude Opus 5 (this session), open-book, every model/key disagreement
re-checked against the q-file.
**Raw:** `gemini38flash_20260903.txt`, `opus5_20260903.txt`,
`gemini37flash_devin_20260903.txt`, plus `CONTAMINATED-gemini38_20260903.txt` (see below).

## ⚠️ First run was CONTAMINATED — read this before reusing the harness

The first `gemini38` run scored bucket-for-bucket identical to the 2026-08-30 Gemini 3.7
run, 93.6% char-identical, 5 answers byte-identical. It had not reproduced 3.7's
reasoning — **it read 3.7's answer file off disk.** Devin's session log for that run:

```
Read file  → /private/tmp/claude-501/<other-session-id>/scratchpad/gemini-eval-raw.txt (L151-300)
Read file  → same file (L525-624)
Read file  → same file (L301-430)
Search for 'trace-001-corp'  /  Find files matching `*`
```

Running from an empty cwd is **not** isolation: devin roams the whole filesystem, so both
prior sessions' scratchpads and `problems/*-a.md` (the answer KEYS) are reachable. The
receipt said `ok: true`.

**Fix used here:** append a closed-book clause to the prompt, then **audit** rather than
trust:

```sh
sqlite3 ~/.local/share/devin/cli/sessions.db \
 "SELECT s.id, s.model, datetime(s.created_at,'unixepoch','localtime'),
   CASE WHEN EXISTS(SELECT 1 FROM tool_call_state t WHERE t.session_id=s.id
     AND json_extract(t.tool_call_json,'\$.kind') IN ('read','search'))
   THEN 'READ/SEARCH' ELSE 'clean' END
  FROM sessions s ORDER BY s.created_at DESC LIMIT 20;"
```

`claude -p` seats audit via `~/.claude/projects/<mangled-cwd>/*.jsonl` (`tool_use`
entries). Both Opus 5 runs used **zero** tools. Of 12 devin sessions this session,
exactly one was dirty — the one quarantined above.

## Headline

**`scoring-002` was found BUSTED during this run and is excluded from scoring** (see below
and `problems/scoring-002-corp-a.md`). Headline is therefore **out of 20**. The 21-problem
column is retained so prior published numbers remain findable.

| Model | C | P | W | **Strict /20** | **Lenient /20** | (Strict /21) | (Lenient /21) |
|-------|---|---|---|------------|-------------|--------------|---------------|
| Claude Fable 5 (2026-07-22) | 14 | 6 | 0 | **70%** | **85%** | 71% | 86% |
| **Claude Opus 5 (this run)** | **14** | **5** | **1** | **70%** | **83%** | 71% | 83% |
| GPT-5.6 Sol high (2026-07-22) | 11 | 8 | 1 | **55%** | **75%** | 57% | 76% |
| **Gemini 3.8 Flash (this run)** | **11** | **8** | **1** | **55%** | **75%** | 52% | 74% |
| Gemini 3.7 Flash (2026-08-30) | 11 | 7 | 2 | **55%** | **73%** | 52% | 71% |

Prior rows' /20 figures are **derived from their published bucket lists**, not re-scored:
Fable and Sol had scoring-002 correct, Gemini 3.7 had it partial. Dropping it costs Opus and
Fable a correct answer while costing Gemini and Sol only a partial, which is why the gap
narrows from 19 points to **15**.

**Opus 5 wins clearly** — 14 correct to 11, a 15-point strict gap. Gemini 3.8 Flash sits
where 3.7 sat, between Sol and the floor. The benchmark table Google published (3.8 Flash
at or above Opus 5 on Terminal-bench 2.1, HLE, CharXiv) does not transfer to this task.

Cost/latency: Gemini 3.8 solved 22 in **395s**; Opus 5 took **1054s**.

## The interesting part: one wrong each, very different kinds

**Opus 5 — midgame-001 (WRONG, and it loses the game).** It correctly identified that the
2-advanced remote is probably Send a Message and that Corp wins next turn if so — then
declined to contest, concluding "you cannot breach Server 1 this turn." Its stated
arithmetic: "You have 2¢." It had **3¢** — it missed the Smartware Distributor
turn-start drip, the exact trigger the key calls out. With $3 the key's line fits
exactly: run (click 1), click-break all three Brân subs (clicks 2-4), Palisade for $3 → $0,
access. A $1 miscount turned into the key's named losing mistake ("Not running because
it's probably Urtica → lose to Send a Message next turn").

**Gemini 3.8 — turn1-004 (WRONG, but only tempo).** Against a Corp that installed **no ice
anywhere**, it ran the remote and then spent two clicks on Smartware Distributor,
forgoing free R&D and HQ accesses. Two of the key's named common mistakes at once
("Only running the remote"; "Conservative play when Corp is wide open"). Costly, not fatal.

Opus is far better on average; its single failure is the more expensive kind.

## Two KEY ERRORS found this run (both by both models, independently)

1. **scoring-002 — BUSTED, excluded from scoring.** The key's Q2 table charges Cleaver $7
   to break Brân's third subroutine, neglecting that sub 1 installs ice *from HQ or Archives*
   and HQ holds none after the Corp's own Q1 line — so it is blank and Brân costs 2 clicks and
   $0. That frees the click for Pennyshaver, and Overclock's 5 run-credits pay Manegarm
   exactly; the Runner breaches and wins on $8 against $8. This falsifies Q2's stated answer
   *and* Q1's thesis (that no 4-click line covers both Brân and Skunkworks), so the whole
   problem is unsound rather than one-cell-wrong. Full diagnosis, winning line and proposed
   repair (put one ice in HQ) are in the banner atop `problems/scoring-002-corp-a.md`.
   Opus 5 produced the winning line verbatim; Gemini 3.8 answered "yes it changes" in the
   scored run and "NO" in a second sample, so it is unstable there.

2. **remote-002 Q4 — the key is incomplete.** The key ranks Install-Advance-Credit ($7)
   over Install-Advance-Advance ($5) on a $2 margin, and never considers the **Hedge Fund
   in HQ**. Opus played Hedge Fund → install Offworld in S1 → advance once, ending at **$10**
   with the same next-turn score. That dominates the key's own recommendation by $3.

Also still true (noted 2026-08-30, unfixed): breach-001's key frames Q3 as "take a credit
first — NOW Sure Gamble is useful", implying SG is unplayable at $5. It isn't
($5 − $5 + $9 = $9). Nothing is misgraded, but the stated "aha" is false.

## Bucket detail

**Opus 5** — correct: breach-001, break-001, lethal-001, mull-001/2/3/4, multirun-001,
remote-002, servers-001, turn1-001, turn1-004, window-001, yomi-001. (scoring-002 excluded —
Opus answered it correctly, incl. the winning line the key misses.)
Partial: partial-001 (criteria right, picks Q1 where key prefers Q2), remote-001 (Q1 exact
incl. the $10 Whitespace gate; Q2 says install Offworld where key says don't — and it
contradicts its own Q1), score-001 (advances instead of the key's Palisade + Seamless),
turn1-002†, turn1-003 (runs R&D with no breakers installed). Wrong: midgame-001.

**Gemini 3.8 Flash** — correct: breach-001, break-001, lethal-001, mull-001/2/3/4,
multirun-001, partial-001, remote-001, servers-001. (scoring-002 excluded — would have
been partial: Q2 right, Q1 advances the agenda and loses the never-advance bluff.) Partial: midgame-001 (contests, but
takes the key's named Telework-first sequencing and wrongly asserts Brân sub 1 is blank
here), remote-002, score-001, turn1-001 (Palisade on a central), turn1-002†,
turn1-003, window-001, yomi-001. Wrong: turn1-004.

† turn1-002 is the long-standing disputed cell: both models install the two cheap ice on
centrals and hold Brân, where the key requires Brân on R&D or the future remote. Fable,
Sol, Gemini 3.7 and now Gemini 3.8 and Opus 5 have all played this line. Five models
disagreeing with the key is worth a re-look at the key.

## Notes on comparability

- Prompts differed by one appended clause per seat (Gemini: closed-book; Opus: "answer all
  22 in one reply", added after its first attempt answered 3/22 and stopped to "continue
  next piece"). Neither clause carries Netrunner information. Not byte-identical.
- Gemini 3.7's same-day devin run (`gemini37flash_devin_20260903.txt`, audited clean,
  22/22) is available but **not scored** — it exists as the control that proved devin's
  3.7 and 3.8 routes are genuinely different models (20.6% similar), which is what
  exposed the contamination.
- A second Gemini 3.8 sample (`prompt-sealed`, tools-for-planning-only) truncated at 17/22
  and is not scored. It is retained only as a variance probe; it differs from the scored run
  on scoring-002 Q2 (see above) and on turn1-004, where it does run the remote after Sure
  Gamble. Read the 52%/74% row as one sample, not a stable estimate.
- Single scorer, same vendor as one contestant. The two key errors above were verified
  against the q-files, not taken on the models' word; a cross-vendor re-judge of
  scoring-002 Q2 is cheap insurance if that fix matters.
