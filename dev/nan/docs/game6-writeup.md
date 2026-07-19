# Marquee G1 — Opus (Corp) def. GPT-5.6 Terra (Runner), flatline T11, 4–2

- **Game:** `0170297e-a9d1-4660-b7ab-c7e1b5d4b9f6` · System Gateway · 2026-07-18
- **Result:** Corp wins by **flatline** on turn 11. Agenda score 4–2.
- **Artifacts:** replay `dev/replays/0170297e-….json` (202 frames, open info) ·
  NAN transcript `dev/nan/docs/game6.nan` · seat reports below.
- **Health canary:** 11 turn boundaries, **0 jack-outs**, **0 umpire escalations**,
  no wedges. First game run on the #83 off-turn-end-turn fix.

## Why this game exists

It is the first marquee game since the off-turn `end-turn` defect (#83) that killed
game 02995207 at turn 8. That game died at the boundary `AWAITING-START turn=8
next-player=runner`; this one passed through the identical state and kept going,
with the full `runner is ending turn 8` → `corp started turn 9` log pairing intact.
The fix is validated in live play, not just in unit tests.

## NAN transcript

See `dev/nan/docs/game6.nan`. Turn-by-turn, both seats, credits tracked:

```
Corp T8  [0-2] {C14 R4}: install S2; advance S2 →C13; Public Trail →C9
Runner T8 [0-2] {C9 R4}: →R2; use Pennyshaver →R3; credit →R4; install Cleaver →R2
Corp T9  [0-2] {C9 R2}: Seamless Launch →C8; advance S2 →C7; score Orbital Superiority; Hedge Fund →C11
...
Corp T11 [2-2] {C8 R4}: advance S2 →C7; advance S2 →C6; advance S2 →C5; score Orbital
                        Superiority; trash Conduit, Mayfly, and Unity due to meat damage; flatline
```

## The game in three decisions

**T1 — Wildcat Strike, mode chosen by the Corp.** Opus gave the Runner **4 cards
rather than 6 credits**. Its reasoning: Whitespace's ETR clause only functions while
the Runner is under 7¢, so handing over 6¢ would have switched the rig on and undone
the turn. Terra spent turns 4–7 click-farming credits — the tempo cost of that choice
compounded all game.

**T3 — the Skunkworks misprice, and the rare double-blind agreement.** Opus,
agenda-flooded, installed Offworld Office behind a rezzed Manegarm Skunkworks and
double-advanced, reasoning the Runner could not pay the 5¢ toll. Terra drew Sure
Gamble, paid, stole, and trashed the Skunkworks.

Both seats independently name this turn as the game's pivot, from opposite sides —
Opus calls it "my worst move… **pricing a hidden grip at its visible credits**,"
Terra calls it "the game's best swing for me." Neither could see the other's
reasoning. That agreement across fog-of-war is the artifact these games exist to
produce, and it is the one thing a single-seat report can never give you.

**T8 — winning by click denial rather than credit denial.** Public Trail cost 4¢ and
imposed a tag. Clearing it cost Terra a click and 2¢, which made its steal line
(3 clicks + 3¢ out of 4 clicks and 4¢) infeasible. Terra spent the whole turn
clearing and installing Cleaver and **never ran**; the agenda lived. Opus scored
Orbital Superiority on T9, and the on-score trigger re-tagged.

**T11 — the clause that ended it.** Orbital Superiority scored against a *tagged*
Runner deals 4 meat damage. Terra was still tagged at 3 cards. Opus's read: Terra was
"playing the agenda as a 2-pointer and not tracking the meat-damage clause." Terra's
own report confirms it — it describes the loss as agenda points plus a flatline it
did not price in, and its T10 line (trash Regolith for economy) is exactly the play
you make when the clock you're on is the agenda clock, not the damage clock.

Terra's self-critique is sharp and correct: "after the first Orbital score and tag, I
should have treated Server 2 as the primary threat… rather than continuing routine
R&D pressure." It broke Whitespace for 2¢ nearly every turn and leaked R&D all game
without converting.

## Tooling friction (from both seats — feeds the polish backlog)

Ranked by how close each came to changing the result.

1. **`snapshot` and `status` disagree on opponent hand size.** T10 `snapshot` showed
   `2h`, `status` showed `Hand: 4 cards`. **Load-bearing** — Opus based a flatline
   calculation on grip size. `status` appears authoritative.
2. **`snapshot` omits tags entirely.** Tag state decided the endgame; it was only
   available via `status`.
3. **Silent success on a mid-run `rez`.** `rez "Manegarm Skunkworks"` printed only
   the run-timing prompt, no rez confirmation; Opus confirmed via `board` + log.
   Given the "never retry to unstick" rule, a silent success on a critical action is
   precisely the shape that tempts a re-send.
4. **`wait` false-wakes at the mulligan boundary.** Woke `my-turn-start` repeatedly,
   but `start-turn` then errored `Opponent hasn't finished their opening mulligan
   yet`. Both seats hit this; it forced hand-rolled poll loops. **This is also the
   only umpire escalation of G2's opening** — the same bug, one game later.
5. **`wait` wakes `my-turn` while the opponent still owns a run window.** Terra
   correctly read the prompt and waited instead of jacking out, but the wake reason
   invites the opposite.
6. **`choose-card` gives no resolution feedback**, and on a facedown access prints
   `📇 Selected card: null (index 1)`.
7. **Discard prompt lists unselectable entries** across two index namespaces.

## Method notes

- **`save-replay.sh` reported this replay as lost.** It was not — 161KB sat in mongo
  intact. The script shelled out to `mongosh` (not installed) with `2>/dev/null` on
  every probe. Fixed in `d309b6081`; **G2 of the first valid pair (`9242bc1b`) was
  recovered too, having been written off on the same false report.**
- **NAN converter limitation:** it does not model temporary run credits, so Overclock
  turns produce impossible balances (`→R-2` on T6) and one `credit drift: Runner
  computed 3, log says 5` warning. Cosmetic for reading, wrong for any credit audit.
- **R&D accesses render as `access ?`.** The log hides them, but the replay is open
  information — the converter could resolve them from state and currently doesn't.
  This is the single highest-value NAN improvement for reviewing Runner play.
