# Seat brief — you are the RUNNER

Paste this whole file as your first message into a **fresh** interactive
`claude` session (run from the netrunner repo root). A separate process has
already set up the game: you take the **Runner** seat; the **Corp** is played by
a heuristic defender bot that is already looping. Play a real, competitive game
and try to **win**.

## The one hard rule: isolation contract

You are ONE player at the table. Your only window into the game is the **Runner**
client (REPL on port 7889). This is deliberate — it is what makes this a fair
test, so honour it strictly:

- **Only ever run `./dev/send_command runner …`.** Never run a `corp` command.
- **Never try to inspect Corp's hidden information** — their hand (HQ), R&D order,
  facedown installs, or pending rez. You learn a card only when the game reveals
  it to you (you access it, or the Corp rezzes it).
- Your **own** deck/stack contents are fog-of-war too — use `:deck-count`, never
  assume what's on top.
- Do not read the Corp's source/strategy files or the heuristic code to predict
  it. Play what's in front of you.

If you ever feel the urge to "just peek" to make a decision — don't. Decide from
what you can legitimately see. That's the whole point.

## Orient (do this first, once)

- `CLAUDE.md` is already in your context (project root). Skim it.
- Strategy heuristics: read **`dev/instructions/runner_play_structure.md`**
  (credit floor, just-in-time rig, when to run). Optional deeper:
  `dev/netrunner-eval/runner-playbook.md`.
- Full command list: `./dev/send_command help --full`.
- This is the System Gateway tutorial matchup; you win at **7 agenda points**
  (steal agendas off R&D / HQ / remotes), or by decking the Corp.

## Turn 0 — mulligan

Corp goes first in Netrunner and has already kept. Make your mulligan call:

```
./dev/send_command runner status      # see the game / your opening hand
./dev/send_command runner hand
./dev/send_command runner keep-hand   # or:  ./dev/send_command runner mulligan
```

## Each of your turns

You have 4 clicks. A turn auto-ends when clicks hit 0. A rough loop:

1. **See state** (use the compact forms to save tokens):
   `status-compact`, `board-compact`, `hand`, `list-playables`.
2. **Decide & act** per your heuristics — draw, gain credits, install
   programs/resources/hardware, run a server. Examples:
   `./dev/send_command runner draw`
   `./dev/send_command runner install "Cleaver"`
   `./dev/send_command runner run "R&D"`
3. **Resolve runs** (see next section).
4. End cleanly when out of clicks: `./dev/send_command runner smart-end-turn`
   (it handles discard-to-hand-size).

## Resolving a run

`run <server>` starts it. Then drive the handshake from the prompts:

- `./dev/send_command runner prompt` — the current decision, if any.
- `./dev/send_command runner continue` — advance the run (approach → encounter →
  next ICE → breach). The client auto-continues simple steps.
- On an ICE encounter you choose to break subroutines (pay your icebreakers) or
  let them fire / jack out. Use `prompt`, then `choose` / `choose-card` /
  `use-ability` / `jack-out` as the prompt dictates.
- On access, you'll be offered steal/trash decisions — answer the prompt.
- If unsure what a prompt wants, read it with `prompt` and consult
  `./dev/send_command help --full`.

## Between turns — wait, don't busy-poll

The Corp's turn is played by the bot. Block until it's your turn (or a run/prompt
needs you) instead of polling:

```
C=$(./dev/send_command runner get-cursor)          # capture cursor first
./dev/send_command runner wait --since "$C"        # blocks until something relevant
```

## Knowing when to stop

After each of your turns, check:

```
./dev/send_command runner game-over-status
```

- `GAME-OVER winner=… turn=…` → the game is done. **Stop**, and give a short
  report: who won, how (agenda/flatline/deck), final score, turn count, and a
  couple of sentences on how your game went and any moment you'd replay.
- `IN-PROGRESS …` → keep playing.

## Don'ts

- Don't modify game/AI code — you're a player this session, not a dev.
- Don't touch the Corp seat or its REPL.
- If you get genuinely stuck on tooling (a command errors repeatedly, a prompt
  won't clear), say so plainly in your report rather than thrashing — that's a
  harness bug worth surfacing, not your fault.
