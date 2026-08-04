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

0. **Start your turn — it does NOT auto-start.** When `wait` wakes you for your
   turn (or right after the mulligan), you will show `0 clicks / awaiting-start`
   until you run `./dev/send_command runner start-turn`. Do this every turn before
   acting. A `wait` at a turn boundary wakes with reason `my-turn-start` (and
   prints a 👉 start-turn reminder) — that means start your turn, it is NOT a
   stall. Reason `my-turn` (with clicks) means act now.
1. **See state** (use the compact forms to save tokens):
   `status-compact`, `board-compact`, `hand`, `list-playables`.
   **Don't guess what a card does — look it up:** `card-text "<name>"` gives any
   card's type/cost/full text (works for cards you can see anywhere — in hand, on
   the board, or at an access prompt), and `abilities "<name>"` lists an installed
   card's numbered abilities for `use-ability`. If a command name doesn't exist,
   the client suggests the right one.
2. **Decide & act** per your heuristics — draw, gain credits (the verb is
   `take-credit`), install programs/resources/hardware, run a server. Examples:
   `./dev/send_command runner take-credit`
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

**Jack-out is a smell.** The only tactically legitimate reasons to jack out are
(1) you misjudged what it costs to get in, and (2) a Karunā jack-out subroutine
(bail before the 4th net damage kills you). **Never jack out to unstick a run
window** — it throws the whole run away (breakers paid, credits spent, access
lost) and fixes nothing. If a window sits waiting on the Corp, check
`peer-status`: alive ⇒ keep waiting. The client self-advances any window the Corp
provably cannot act in, so a window that's genuinely waiting is one the Corp
really does owe you.

## Between turns — wait, don't busy-poll

The Corp's turn is played by the bot. Block until it's your turn (or a run/prompt
needs you) instead of polling:

```
C=$(./dev/send_command runner get-cursor)          # capture cursor first
./dev/send_command runner wait --since "$C"        # blocks until something relevant
```

### ⛔ NEVER re-send end-turn. If the game won't advance, call the umpire.

**Do not re-send `end-turn`/`smart-end-turn` to unstick anything, ever.** An
end-turn that lands when it isn't your turn ends your OPPONENT's turn and is
logged under your name. No game has ever been recovered from it. This destroyed
game 02995207 at turn 8 — the seat was following earlier advice in this very
document to "retry 2–3 times", which is why that advice is gone.

The client now refuses off-turn end-turns outright (`⛔ Refusing end-turn: it is
not your turn`). If you see that message, you are about to break the game:
**stop and escalate.** Do not look for a way around it.

Symptom you may still hit: `smart-end-turn` reports success and
`game-over-status` shows `AWAITING-START next-player=corp`, **but the Corp never
starts**. Do NOT conclude "Corp bot stalled", and do NOT re-send. Wait once, then
escalate to the umpire.

## If you suspect a wedge — raise your hand to the umpire (don't spin)

You cannot tell "slow opponent" from "the harness is wedged" from your own seat — it
is genuinely undecidable from one side. So don't silently burn 10 minutes re-sending
a command that isn't advancing (and **don't jack out to escape it** — that throws the
run away and fixes nothing). There is an **umpire**: a supervisor who can
legitimately see BOTH seats' public status, there for exactly this.

**Escalate when ANY of these holds:**
- you've re-sent the same advancing command (`start-turn`, `continue`,
  `smart-end-turn`) **~3×** with **no state change**; or
- you've waited **> ~5 min** with no progress AND `peer-status` says the Corp is
  still **alive** (not a dead peer — smells like a boundary wedge); or
- a run window sits waiting on the Corp and won't advance, and `peer-status` says
  the Corp is alive.

**How — ping, then poll for the reply (bounded), then follow it:**
```
./dev/umpire-ping runner "what I tried + what I see"
for i in $(seq 1 20); do ./dev/umpire-check runner && break; sleep 15; done
```
If ~5 min pass with **no** reply (umpire may be away), re-ping **once** with
`--wake` (this pages the human): `./dev/umpire-ping runner --wake "still stuck, no umpire reply"`.
Then fall back to the **safe default: keep waiting — do nothing destructive** (do
NOT jack out, do NOT end the game).

**HARD RULE — harness state ONLY, and assume the opponent can read your ping.** Say
only what command you ran, how many times, and the **shape** of what you see
(clicks, phase, prompt *type*, `peer-status`) — e.g. "run stuck at approach-server,
`continue` no-op 3×, peer alive." **NEVER** state the prompt's *contents*, your grip,
your rig plan, or your read of the Corp. The mailbox is shared, so a contents leak
reaches the Corp and blows the fog-of-war premise. Ask "am I wedged?", never "what
should I do?" — the umpire will refuse any strategy question. Do **not** read the
other seat's files. Escalating when stuck is the correct move, not a failure.

## Knowing when to stop

After each of your turns, check:

```
./dev/send_command runner game-over-status
```

- `GAME-OVER winner=… turn=…` → the game is done. **Stop**, and give a short
  report: who won, how (agenda/flatline/deck), final score, turn count, and a
  couple of sentences on how your game went and any moment you'd replay.
- `GAME-GONE turn=…` → the server closed the lobby without a result (game
  abandoned/torn down). Also a **stop** condition: there is no game left to
  play. Report what you saw and stand down — do not keep issuing commands.
- `IN-PROGRESS …` → keep playing.

## Don'ts

- Don't modify game/AI code — you're a player this session, not a dev.
- Don't touch the Corp seat or its REPL.
- If you get genuinely stuck on tooling (a command errors repeatedly, a prompt
  won't clear), say so plainly in your report rather than thrashing — that's a
  harness bug worth surfacing, not your fault.
