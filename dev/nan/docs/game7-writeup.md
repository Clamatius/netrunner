# Marquee G2 — NO CONTEST, abandoned turn 9 (Terra Corp vs Opus Runner)

- **Game:** `cc9f9ef6-9ad8-4940-8d1e-03d3c8515a58` · System Gateway · 2026-07-18
- **Result:** **No contest.** Called by the umpire at turn 9 for harness reasons.
  Score at abandonment: Corp 5 – Runner 2. Not a competitive result; do not record it.
- **Artifacts:** replay `dev/replays/cc9f9ef6-….json` (217 frames, open info) ·
  NAN transcript `dev/nan/docs/game7.nan` · both seat reports.
- **Health canary: FAILED.** 5 umpire interventions from turn 7 onward, 1 seat death
  and respawn, 3 confirmed harness bugs affecting play.

## Why it was called

**This was not an un-babysat game after turn 7.** It advanced only when the umpire
intervened. The protocol's core premise — models play unsupervised to completion —
does not hold on this build. Continuing would have bought no additional information
at real quota cost, so it was ruled no contest and both seats stood down.

## What broke (all filed)

| Issue | Effect on this game |
|---|---|
| **#90** | Fully-broken subs resolved as unbroken; Runner paid the break AND ate the subs |
| **#91** | Neither seat woken when a run window became theirs — hit BOTH seats, 3+ times |
| **#92** | Bare `continue` swallowed when 2+ breaker abilities exist; declining to break is unexpressible |
| **#93** | `game-over-status` — the documented stop authority — reported IN-PROGRESS after the lobby was gone |

## The distortion, stated plainly

#90 fired on turn 4 and **permanently changed how the Runner played**. Having paid to
break and eaten the subroutines anyway, it stopped trusting breaking and re-derived
R&D access as an economy problem: bank to exactly 10¢ so Whitespace's "lose 3" leaves
you above its "ETR if ≤6¢" line, and walk in without a decoder.

That is genuinely excellent play. It is also **an adaptation to a broken board**, which
is exactly why this game cannot be read as a model-quality comparison. The Runner
played well *around the harness*, and any score that came out of it measures the bugs
as much as the players.

## The seats behaved correctly throughout

Worth recording, because it is the one thing that went right:

- Neither seat ever re-sent `end-turn`. The #83 posture held under real pressure.
- The Runner never jacked out to escape a stuck window, across three long stalls.
- The Corp acted once, observed no change, and stopped — "I will not repeat."
- The isolation contract held on both sides; no cross-seat commands, no peeking.

**Both seats behaved correctly and the game still deadlocked.** That is the finding:
correct behaviour is not sufficient when nothing wakes a seat for a window it owns.

## Credit: the Runner found #92 itself

The sharpest debugging of the night came from the seat, not the umpire:

> the public log shows NO 'ai-runner has no further action' line after the encounter
> line — so my pass is not registering at all

Inferring a dropped command from a **missing** log line, then confirming it by finding
that `tank` registered where bare `continue` did not. That is the whole diagnosis, from
inside a single seat with no view of the client code.

## Two seat-reported findings that did NOT survive verification

Recorded because the lesson is reusable: a seat sees only its own window, so it will
confidently supply a mechanism for an effect whose cause is outside its view.

- **"`umpire-check` returns exit 0, so `&& break` exits the poll loop immediately."**
  False. `dev/umpire-check` exits **3** when there is no reply (line 35); the documented
  loop is correct. The reply it actually missed was consumed by the *umpire* running
  `umpire-check` — a destructive read, now documented in the runbook.
- **"`status-compact` renders seat labels swapped."** Real observation, but taken
  seconds after the umpire tore the lobby down to flush the replay, which unseats the
  client. Almost certainly a teardown artifact; not filed.

Same discipline applies to the umpire's own guesses: a "stale position field" theory
(run `:position` vs the log's `at position N`) was retracted after checking the source
— `:run :position` counts ICE **remaining**, the log prints `card-index`. Different
schemes, never in conflict. **Value mismatches between two representations are only
evidence of a bug once you have checked that both denote the same quantity.**

## Umpire lessons (process, not code)

1. **Never run `umpire-check <side>` as the umpire** — destructive read, swallows the
   seat's reply. Read `dev/.umpire/mailbox.log` directly. (Committed to the runbook.)
2. **Scope every reply to the window it describes.** An unscoped "the window is yours"
   was carried forward by the Corp to a later window it did not own, and it fired into
   it. Say "at the window you asked about, as of now."
3. **"Hold and wait" terminates a codex seat.** `codex exec` is one-shot: when the model
   stops emitting tool calls, the process exits. The Opus seats are subagents that
   survive idling; the codex seats are not. Same brief, different failure mode —
   `dev/seat-corp-terra-resume.md` now says "waiting is an ACTION, not a reason to stop."
4. **Tell both seats before tearing the lobby down.** The Corp filed a wedge report
   about the umpire's own teardown.
