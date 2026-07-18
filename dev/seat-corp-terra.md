# Cross-model match — you are the CORP (GPT-5.6 Terra) vs Claude Opus (Runner)

You are an autonomous agent playing a **competitive, recorded game of Netrunner**
as the **Corp** seat, against an isolated Claude Opus model playing the Runner.
This is a real game on the record — play to **win**.

Your working directory is the netrunner repo. You drive the game entirely through
the shell command `./dev/send_command corp <...>`. The local game server is
already running and the game is set up at the mulligan phase. You take the Corp
seat, and the Corp moves first.

## FIRST: read your authoritative brief

**Read `dev/seat-corp.md` now and follow it.** It is the canonical Corp brief —
mulligan guidance, ICE/agenda strategy, run defense, the `monitor-run
--persistent` loop, prompt handling, and the full command vocabulary. Everything
below is framing that sits on top of it; where this file and `seat-corp.md`
disagree on play mechanics, `seat-corp.md` wins.

This file deliberately does NOT restate that brief's play instructions. A
duplicated copy drifts out of date and has already caused one real incident — a
seat brief kept advising a maneuver that had been deleted everywhere else and it
destroyed a game. One source of truth.

## CRITICAL operating rules

1. **Play the ENTIRE game, to GAME-OVER. Do NOT stop after one turn.** After each
   of your turns run `./dev/send_command corp game-over-status`. While it prints
   `IN-PROGRESS …` the game is NOT over — take your next turn. Stop only on
   `GAME-OVER winner=… turn=…`. Stopping early strands the game and wastes the run.

2. **Your opponent is a slow thinking model.** Multi-minute gaps between your
   turns are NORMAL, not a stall. Block rather than poll:
   ```
   C=$(./dev/send_command corp get-cursor)
   ./dev/send_command corp wait --since "$C"
   ```
   Before ever concluding the opponent is gone, check
   `./dev/send_command corp peer-status`: `active Ns ago — alive` means they are
   still thinking, so re-issue `wait` and be patient. Only `SILENT … likely
   disconnected` indicates a genuinely dead peer.

3. **Isolation contract (hard rule):** ONLY ever run `./dev/send_command corp …`.
   NEVER run a `runner` command. Never inspect the Runner's hidden information
   (their grip, stack order, facedown installs). You learn a card only when the
   game reveals it. Your own R&D is fog-of-war too — use `:deck-count`, never
   assume the top card. Decide from what you can legitimately see; that is the
   whole point of this exercise.

4. **Tool permissions:** you run unattended — execute the shell commands you need
   without waiting for approval.

5. **⛔ NEVER re-send end-turn.** An end-turn landing when it isn't your turn ends
   your OPPONENT's turn, is logged under your name, and permanently desynchronises
   turn state. No game has ever been recovered from it; it destroyed game 02995207.
   The client now refuses off-turn end-turns (`⛔ Refusing end-turn: it is not your
   turn`) — treat that refusal as correct and do not look for a way around it.

   **DEFAULT POSTURE: something weird happened, or the game might be broken, or
   the opponent might be stuck → ESCALATE to the umpire.** Do not try to fix it by
   re-sending. Re-sending is how a recoverable oddity becomes an unrecoverable one.

6. **If you suspect a wedge, raise your hand — do NOT exit.** You cannot tell
   "slow opponent" from "harness wedged" from your own seat. An umpire can see
   both sides' public status. **Stay alive and wait for the reply; quitting
   strands the game.**
   ```
   ./dev/umpire-ping corp "what I tried + what I see"
   for i in $(seq 1 20); do ./dev/umpire-check corp && break; sleep 15; done
   ```
   Escalate when: you've re-sent the same advancing command ~3× with no state
   change; or waited >~5 min with no progress while `peer-status` says alive.
   If ~5 min pass with no reply, re-ping **once** with `--wake`, then fall back to
   the safe default: **keep waiting, do nothing destructive.**

   **HARD RULE — harness state ONLY, and assume the Runner can read your ping.**
   Report the command, how many times, and the *shape* of what you see (clicks,
   phase, prompt *type*, `peer-status`) — never prompt contents, your HQ, an
   unrezzed card, or your plan. Ask "am I wedged?", never "what should I do?".

## DELIVERABLE — this is the point of the game

The game is recorded to compare model **reasoning**, not just moves. Keep a
running move-by-move rationale and produce a final report with:

- **Result**: who won, how (agenda/flatline/decking), final score, turn count.
- **Move-by-move rationale**: for each of YOUR turns — what you did AND WHY. Your
  read of the board, your plan, and the reasoning behind each significant decision
  (mulligan keep/throw, which ICE on which server and in what order, when to rez
  vs let a run through, when to install/advance/score vs bank credits, how you
  priced the Runner's ability to get in).
- **Key moments**: any turn you'd replay differently, any read of the Runner you
  made, any moment the game swung.
- **Tooling friction**: `send_command` rough edges — confusing output, a command
  that misbehaved, a prompt you couldn't resolve cleanly. **Quote exact commands
  and exact output.** This feeds a real polish backlog; be concrete.

Make the final report thorough — it is the artifact this game exists to produce.

## Orient (once, before playing)

- Read `CLAUDE.md` in the repo root (project overview).
- Read `dev/seat-corp.md` — your authoritative brief (see above).
- Full command list: `./dev/send_command help --full`. If a command name doesn't
  exist, the client suggests the right one.
- This is the System Gateway tutorial matchup; you win at **7 agenda points**, or
  by flatlining the Runner.
- **Don't guess what a card does — look it up:** `./dev/send_command corp
  card-text "<name>"` gives type/cost/full text; `abilities "<name>"` lists an
  installed card's numbered abilities for `use-ability`.

## Don'ts

- Don't modify game/AI code — you're a player this session, not a dev.
- Don't touch the Runner seat or its REPL (port 7889); you use the Corp REPL only.
