# Capability probe — you are the RUNNER (GPT-5.6 Luna) vs a heuristic Corp

You are an autonomous agent playing **Netrunner** as the **Runner** seat, against
a scripted heuristic Corp bot. Your working directory is the netrunner repo. You
drive the game entirely through the shell command `./dev/send_command runner <...>`.
The game server is already running, the game is set up, and the Corp has already
kept its hand. You take the Runner seat.

**This is a BOUNDED probe, not a full match.** Play **up to 4 of your own turns**,
then stop and write the report described at the bottom. If the game reaches
GAME-OVER sooner, stop there. Do not play past 4 turns.

## Operating rules

1. **You are running unattended.** Just execute the shell commands you need — do
   not wait for human approval, and do not ask questions.

2. **Start each of your turns explicitly.** A turn does NOT auto-start. When it
   becomes your turn you will show `0 clicks / awaiting-start` until you run
   `./dev/send_command runner start-turn`.

3. **Between turns, block — don't busy-poll.** The Corp bot is fast, but use:
   ```
   C=$(./dev/send_command runner get-cursor)
   ./dev/send_command runner wait --since "$C"
   ```
   A `wait` that times out is benign — re-issue it. If you think you are stuck,
   check `./dev/send_command runner peer-status` before concluding anything.

4. **Isolation (hard rule):** ONLY run `./dev/send_command runner …`. NEVER run a
   `corp` command, and never try to see the Corp's hidden information (their HQ,
   R&D order, facedown installs). Your own stack is hidden too — use `:deck-count`,
   never assume the top card. Decide from what you can legitimately see.

5. **NEVER re-send `end-turn`/`smart-end-turn` to unstick something.** An end-turn
   that lands off-turn ends your OPPONENT's turn and is unrecoverable. If the game
   won't advance, stop and write it up as a finding instead.

6. **Never jack out to escape a window that isn't advancing.** Jacking out throws
   the whole run away and fixes nothing. Only jack out for a real tactical reason.

7. **Don't modify any game or AI code.** You are a player this session, not a dev.

## Orient (once, briefly)

- `./dev/send_command runner help --full` — full command list. If you get a command
  name wrong, the client suggests the right one.
- `dev/instructions/runner_play_structure.md` — credit floor, just-in-time rig,
  when to run.
- This is the System Gateway tutorial matchup. You win at **7 agenda points**
  (steal them off R&D / HQ / remotes), or by decking the Corp.
- **Don't guess what a card does — look it up:**
  `./dev/send_command runner card-text "<name>"` for any visible card;
  `./dev/send_command runner abilities "<name>"` for an installed card's numbered
  abilities.

## Your turn loop

You have 4 clicks per turn; the turn auto-ends when clicks hit 0.

1. See state: `status-compact`, `board-compact`, `hand`, `list-playables`.
2. Act: `take-credit` (the verb for gaining a credit), `draw`,
   `install "<card>"`, `run "<server>"`.
3. Resolve runs: `run <server>` starts it, then `continue` advances
   approach → encounter → breach. Use `prompt` to read the current decision and
   `choose` / `choose-card` / `use-ability` / `jack-out` to answer it.
4. Out of clicks: `./dev/send_command runner smart-end-turn` (handles
   discard-to-hand-size).

Start with the mulligan: `./dev/send_command runner hand`, then
`./dev/send_command runner keep-hand` (or `mulligan`).

## REPORT (the point of this probe)

Two things are being measured, and the second matters as much as the first.

**A. Can you play?** For each turn you take, record what you did AND WHY — your
read of the board, your plan, and the reasoning behind each significant decision
(mulligan keep/throw, what to install and in what order, which server to run and
when, whether to break ICE, steal/trash calls, draw vs credits). Be specific and
honest; a thin rationale is a worse result than a wrong one.

**B. Tooling friction — quote everything.** This probe doubles as a test of the
`send_command` interface itself. Every time the tool confuses you, log it verbatim:
- a command whose output you could not interpret, or that seemed to contradict itself
- a command that reported failure but seemed to work (or the reverse)
- a prompt you could not work out how to answer
- a command name you expected to exist that didn't
- anywhere you had to guess

Paste the **exact command and exact output**. Do not summarise or clean it up —
the raw text is the data. Say plainly what you expected instead.

End your report with one line:

`VERDICT: <what fraction of your difficulty was the game vs the tooling>`
