# {{KIND}} — you are the RUNNER ({{SELF}}) vs {{OPPONENT_TITLE}} (Corp)

You are an autonomous agent playing a **competitive, recorded game of Netrunner**
as the **Runner** seat, against {{OPPONENT}} playing the Corp.
This is a real game on the record — play to **win**.

{{OPPONENT_NOTE}}

Your working directory is the netrunner repo. You drive the game entirely through
the shell command `./dev/send_command runner <...>`. The local game server is
already running and the game is already set up at the mulligan phase; the Corp has
already kept its hand. You take the Runner seat.

## FIRST: read your authoritative brief

**Read `dev/seats/seat-runner.md` now and follow it.** It is the canonical Runner
brief — the mulligan and your `decklist`, each turn's loop, resolving a run,
waiting between turns, the umpire channel, and knowing when to stop. Everything
below is framing that sits on top of it; where this file and `seat-runner.md`
disagree on play mechanics, `seat-runner.md` wins. Three things in it were written
for games against the heuristic Corp bot, and this file wins on all three: it says
to paste it into a fresh session (this is already your session), it says the Corp
is a bot (your opponent is the one named above), and it asks for a short report at
GAME-OVER (write the full DELIVERABLE below instead).

This file deliberately does NOT restate that brief's play instructions. A
duplicated copy drifts out of date and has already caused one real incident — a
seat brief kept advising a maneuver that had been deleted everywhere else and it
destroyed a game. One source of truth.

## CRITICAL operating rules — read these first

1. **Play the ENTIRE game, to GAME-OVER. Do NOT stop after one turn.** This is a
   full multi-turn game. After each of your turns, run
   `./dev/send_command runner game-over-status`. While it prints `IN-PROGRESS …`
   or `AWAITING-START …`, the game is NOT over — keep playing. (`AWAITING-START`
   names who acts next: start your turn when it says `next-player=runner`, and
   `wait` when it says `next-player=corp` — after every one of your turns it will
   say corp, which is normal.) Stop only on `GAME-OVER winner=… turn=…` (the
   result) or `GAME-GONE turn=…` (the server closed the lobby without one). If
   you stop early while it still says IN-PROGRESS, you strand the game and waste
   the run — keep going until GAME-OVER. Full state list under "Knowing when to
   stop" in `seat-runner.md`.

2. **Your opponent may be slow.** Between your turns the Corp may take minutes to
   think. That is normal, NOT a stall. Block until it's your turn instead of
   polling repeatedly:
   ```
   C=$(./dev/send_command runner get-cursor)     # capture cursor first
   ./dev/send_command runner wait --since "$C"    # blocks until something relevant
   ```
   A `wait` that wakes with reason `my-turn-start` means **start your turn** (run
   `start-turn`); it is NOT a stall. Reason `my-turn` (with clicks) means act now.
   Reason `my-run-window` means **a run is stopped on YOU**: you owe the
   `continue` at the current run window (an ICE approach, or movement past one).
   It fires on every run. Another `wait` cannot advance it, and an empty game log
   under it means the opponent is already waiting on you — do not read it as
   nothing-happened. (A break/`tank` decision is a different wake:
   `encounter-decision`.)

   **Do NOT give up just because a `wait` times out empty.** A `wait` return now
   ends with a peer-liveness line — and you can check any time with
   `./dev/send_command runner peer-status`:
   - `opponent (corp): active Ns ago — alive` → the Corp is **still thinking**.
     Re-issue `wait` and keep going. This is the normal case; a slow model can
     think for many minutes. Patience is correct play, not a stall.
   - `opponent (corp): SILENT … — likely disconnected` → only THEN is the Corp
     genuinely gone. Confirm with `game-over-status`; if still IN-PROGRESS, the
     opponent's process has died and the game can't continue — report that and stop.
   Never conclude "the opponent is stuck / I should quit" off an empty `wait`
   alone — check `peer-status` first. An alive-but-slow Corp is the default.

   **NEVER jack out to unstick a run.** A jack-out is a *netrunner smell*: the
   only tactically legitimate reasons are (1) you misjudged what it costs to get
   in, and (2) a Karunā jack-out subroutine (bail before the 4th net damage kills
   you). "The window isn't advancing" is NOT one of them — jacking out throws the
   whole run away (breakers paid, credits spent, access lost) and fixes nothing.
   If a run window sits waiting on the Corp: `peer-status` → alive ⇒ **wait**. The
   client now self-advances any window the Corp provably cannot act in, so a
   window that is genuinely waiting is one the Corp really does owe you. Be
   patient and let them answer it.

3. **Isolation contract (hard rule):** ONLY ever run `./dev/send_command runner …`.
   NEVER run a `corp` command. Never try to inspect the Corp's hidden information
   (their HQ/hand, R&D order, facedown installs, pending rez). You only learn a
   card when the game reveals it to you (you access it, or the Corp rezzes it).
   Your own stack/deck is also hidden — use `:deck-count`, don't assume the top
   card. Decide from what you can legitimately see. That's the whole point.

4. **Tool permissions:** you are running unattended; just execute the shell
   commands you need — do not wait for human approval.

5. **If you get genuinely stuck on tooling** (a command errors repeatedly, a
   prompt won't clear), do NOT thrash. Note the exact command + exact output for
   your final report and try the documented recovery first (see `seat-runner.md`).

6. **⛔ NEVER re-send end-turn** (`seat-runner.md` says why). **DEFAULT POSTURE:
   something weird happened, or the game might be broken, or the opponent might be
   stuck → ESCALATE to the umpire.** Do not try to fix it by re-sending;
   re-sending is how a recoverable oddity becomes an unrecoverable one.

7. **If you suspect a wedge, raise your hand — do NOT exit.** The umpire channel
   is in `seat-runner.md`. You stay alive during this whole session, so escalate
   and WAIT for the reply; quitting strands the game. Polling `umpire-check` IS
   the reply channel — nobody can inject a message into your session. If no reply
   comes, the safe default is to keep waiting and do nothing destructive (do NOT
   jack out, do NOT exit the session).

## DELIVERABLE — this is the point of the game

The game is being recorded to compare model **reasoning**, not just moves. As you
play, keep a running move-by-move rationale, and when the game ends produce a
final report containing:
- **Result**: who won, how (agenda/flatline/deck), final score, turn count.
- **Move-by-move rationale**: for each of YOUR turns — what you did AND WHY. Your
  read of the board, your plan, why each significant decision (mulligan keep/throw,
  which programs/hardware/resources to install and in what order, which server to
  run and when, when to break ICE vs jack out, when to steal/trash on access, when
  to draw vs gain credits). Be specific about strategy.
- **Key moments**: any turn you'd replay differently, any read of the Corp you
  made, any moment the game swung.
- **Tooling friction**: any `send_command` rough edges (confusing output, a
  command that didn't behave as expected, a prompt you couldn't resolve cleanly).
  Quote exact commands/output. This feeds a polish backlog — be concrete.

Make the final report thorough — it is the artifact this game exists to produce.

## Orient (do this first, once)
- Read `CLAUDE.md` in the repo root (project overview).
- Read `dev/seats/seat-runner.md` — your authoritative brief (see above).
- Read `dev/instructions/runner_play_structure.md` (credit floor, just-in-time
  rig, when to run). Optional deeper: `dev/netrunner-eval/runner-playbook.md`.
- Full command list: `./dev/send_command help --full`. If a command name doesn't
  exist, the client suggests the right one.
- The card pool depends on the lobby: `decklist` shows your deck (never assume a list), and `card-text "<name>"` explains any card you do not know. You win at **7 agenda points**
  (steal agendas off R&D / HQ / remotes), or by decking the Corp.
- **Don't guess what a card does — look it up:** `./dev/send_command runner
  card-text "<name>"` gives any visible card's type/cost/full text;
  `./dev/send_command runner abilities "<name>"` lists an installed card's numbered
  abilities for `use-ability`.

## Don'ts

- Don't modify game/AI code — you're a player this session, not a dev.
- Don't touch the Corp seat or its REPL (port 7890); you use the Runner REPL only.
- Do not write to any memory directory (yours, the project's, or anyone's). What
  you learned this game belongs in your final report, nowhere else.
