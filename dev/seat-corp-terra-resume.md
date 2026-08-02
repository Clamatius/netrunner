# RESUME — you are the CORP (GPT-5.6 Terra), mid-game, vs Claude Opus (Runner)

You are resuming an **in-progress** competitive Netrunner game as the **Corp**. A
previous session of you played turns 1–9 and then ended while waiting on an
opponent decision. The game is still live and it is your job to finish it.

**Read `dev/seat-corp-terra.md` first — it is your brief.** Everything there still
applies (isolation contract, never re-send end-turn, escalate rather than retry,
the deliverable). This file only adds resume-specific context.

## Where the game stands

- It is roughly **turn 9**. Check for yourself: `./dev/send_command corp snapshot`
  and `./dev/send_command corp log-compact 20`.
- Your opponent was holding an unresolved decision prompt on a rezzed piece of ICE
  protecting R&D. It may still be open, or already resolved — **verify, don't assume**.
- You do NOT have your predecessor's reasoning. Re-derive the board from
  `snapshot`, `board`, `log-compact`, and `card-text` on anything you don't know.

## CRITICAL — do not end your session while the game is live

Your predecessor ended its session while correctly waiting on the opponent. **That
stranded the seat and the game could not continue until a human restarted it.**

**Waiting is an ACTION, not a reason to stop.** While `game-over-status` prints
`IN-PROGRESS`, you must stay in the loop:

```
C=$(./dev/send_command corp get-cursor)
./dev/send_command corp wait --since "$C"     # blocks; re-issue when it returns
```

If a `wait` times out and the opponent is still alive (`peer-status`), **issue
another `wait`**. A slow model opponent can think for many minutes; that is normal.
Loop until `game-over-status` prints `GAME-OVER winner=… turn=…` (or
`GAME-GONE turn=…` — the server tore the game down without a result; also a
stop). Only then stop and write your report.

If an umpire reply tells you to "hold" or "wait", that means **keep looping on
`wait`** — it does NOT mean end your session.

## Umpire

An umpire is watching and can see both sides' public status. Escalate rather than
retry:

```
./dev/umpire-ping corp "what I tried + what I see"
for i in $(seq 1 20); do ./dev/umpire-check corp && break; sleep 15; done
```

**Umpire replies are point-in-time statements about the specific window you asked
about.** Do not carry an earlier reply forward to a later window — a previous
session did this and acted on a window it did not own. If the situation has moved
on, ask again.

Harness state only in your pings: command, count, and the *shape* of what you see.
Never prompt contents, your HQ, or your plan. The mailbox is opponent-readable.

## Deliverable

Same as the main brief: result, move-by-move rationale **for the turns you play**,
key moments, and concrete tooling friction with exact commands and output. Note in
your report that you resumed mid-game and did not witness turns 1–9.
