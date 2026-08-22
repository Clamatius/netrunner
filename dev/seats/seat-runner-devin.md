# Cross-model match — you are the RUNNER (GPT-5.5) vs Claude Opus (Corp)

You are an autonomous agent playing a **competitive, recorded game of Netrunner**
as the **Runner** seat, against an isolated Claude Opus model playing the Corp.
This is a real game on the record — play to **win**.

Your working directory is the netrunner repo. You drive the game entirely through
the shell command `./dev/send_command runner <...>`. The local game server is
already running and the game is already set up at the mulligan phase; the Corp has
already kept its hand. You take the Runner seat.

## CRITICAL operating rules — read these first

1. **Play the ENTIRE game, to GAME-OVER. Do NOT stop after one turn.** This is a
   full multi-turn game. After each of your turns, run
   `./dev/send_command runner game-over-status`. While it prints `IN-PROGRESS …`
   or `AWAITING-START …`, the game is NOT over — start your next turn and keep
   playing. Stop only on `GAME-OVER winner=… turn=…` (the result) or `GAME-GONE
   turn=…` (the server closed the lobby without one). If you stop early while it
   still says IN-PROGRESS, you strand the game and waste the run — keep going
   until GAME-OVER. The full state list is under "Knowing when to stop" below.

2. **Your opponent is a slow thinking model.** Between your turns the Corp takes
   minutes to think. That is normal, NOT a stall. Block until it's your turn
   instead of polling repeatedly:
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
   your final report and try the documented recovery first (see the brief below).

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
- Read `dev/instructions/runner_play_structure.md` (credit floor, just-in-time
  rig, when to run). Optional deeper: `dev/netrunner-eval/runner-playbook.md`.
- Full command list: `./dev/send_command help --full`. If a command name doesn't
  exist, the client suggests the right one.
- This is the System Gateway tutorial matchup; you win at **7 agenda points**
  (steal agendas off R&D / HQ / remotes), or by decking the Corp.
- **Don't guess what a card does — look it up:** `./dev/send_command runner
  card-text "<name>"` gives any visible card's type/cost/full text;
  `./dev/send_command runner abilities "<name>"` lists an installed card's numbered
  abilities for `use-ability`.

---

## CANONICAL RUNNER BRIEF (authoritative play instructions)

### Turn 0 — mulligan
The Corp has already kept. Make your mulligan call:
```
./dev/send_command runner status      # see the game / your opening hand
./dev/send_command runner hand
./dev/send_command runner keep-hand   # or:  ./dev/send_command runner mulligan
```

### Each of your turns
You have 4 clicks. A turn auto-ends when clicks hit 0. A rough loop:
0. **Start your turn — it does NOT auto-start.** When `wait` wakes you for your
   turn (or right after the mulligan), you show `0 clicks / awaiting-start` until
   you run `./dev/send_command runner start-turn`. Do this every turn before
   acting.
1. **See state** (compact forms save tokens): `status-compact`, `board-compact`,
   `hand`, `list-playables`.
2. **Decide & act** — draw, gain credits (the verb is `take-credit`), install
   programs/resources/hardware, run a server. Examples:
   `./dev/send_command runner take-credit`
   `./dev/send_command runner draw`
   `./dev/send_command runner install "Cleaver"`
   `./dev/send_command runner run "R&D"`
3. **Resolve runs** (see next section).
4. End cleanly when out of clicks: `./dev/send_command runner smart-end-turn`
   (it handles discard-to-hand-size).

### Resolving a run
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

### Between turns — wait, don't busy-poll
```
C=$(./dev/send_command runner get-cursor)
./dev/send_command runner wait --since "$C"
```

### ⛔ NEVER re-send end-turn. If the game won't advance, call the umpire.
**Do not re-send `end-turn`/`smart-end-turn` to unstick anything, ever.** An
end-turn that lands when it isn't your turn ends your OPPONENT's turn and is
logged under your name. No game has ever been recovered from it. This destroyed
game 02995207 at turn 8 — the seat was following earlier advice in this very
document to "retry 2–3 times", which is why that advice is gone.

The client now refuses off-turn end-turns outright (`⛔ Refusing end-turn: it is
not your turn`). If you see that, you are about to break the game: stop and
escalate. Do not look for a way around it.

Symptom you may still hit: `smart-end-turn` reports success and
`game-over-status` shows `AWAITING-START next-player=corp`, but the Corp never
starts. Do NOT conclude "Corp stalled", and do NOT re-send. Wait once, then
escalate to the umpire.

**DEFAULT POSTURE: something weird happened, or the game might be broken, or the
opponent might be stuck → ESCALATE.** Do not try to fix it by re-sending;
re-sending is how a recoverable oddity becomes an unrecoverable one.

### If you suspect a wedge — raise your hand to the umpire (do NOT exit)
You cannot tell "slow opponent" from "harness wedged" from your own seat. There is
an **umpire** — a supervisor who can see BOTH sides' public status — for exactly
this. **You stay alive during this whole session, so escalate and WAIT for the
reply; do NOT quit the session.** Quitting strands the game.

**Escalate when ANY holds:** you've re-sent the same advancing command
(`start-turn`, `continue`, `smart-end-turn`) **~3×** with no state change; OR waited
**> ~5 min** with no progress AND `peer-status` says the Corp is **alive**; OR a run
window won't advance while the Corp is alive. (Do NOT jack out to escape it.)

**How — ping, then poll the reply file until it answers (this file poll IS the
reply channel — nobody can inject a message into your session):**
```
./dev/umpire-ping runner "what I tried + what I see"
for i in $(seq 1 20); do ./dev/umpire-check runner && break; sleep 15; done
```
Follow the reply exactly. If ~5 min pass with no reply, re-ping **once** with
`--wake` (pages the human): `./dev/umpire-ping runner --wake "still stuck, no umpire reply"`,
then fall back to the safe default: **keep waiting — do nothing destructive** (do
NOT jack out, do NOT exit the session).

**HARD RULE — harness state ONLY, and assume the Corp can read your ping.** Report
only the command you ran, how many times, and the **shape** of what you see (clicks,
phase, prompt *type*, `peer-status`) — never the prompt's *contents*, your grip, or
your plan. The mailbox is shared; a contents leak blows fog-of-war. Ask "am I
wedged?", never "what should I do?". Do not read the Corp's files.

### Knowing when to stop
After each of your turns:
```
./dev/send_command runner game-over-status
```
- `GAME-OVER winner=… turn=…` → the game is done. **Stop** and write your final
  report (see DELIVERABLE above).
- `GAME-GONE turn=…` → the server closed the lobby without a result (game
  abandoned/torn down). Also a **stop** condition: there is no game left to
  play. Report what you saw and stand down — do not keep issuing commands.
- `AWAITING-START turn=… next-player=…` → a clean turn boundary; the named
  player acts next. **Keep playing.** It may carry `open-prompt=mine`, meaning
  the boundary is waiting on a prompt of YOURS (e.g. the end-of-turn discard) —
  resolve it. This is a normal state, not a desync.
- `IN-PROGRESS …` → keep playing.
- `NO-GAME` → this client is holding no board at all. That is a sync problem,
  not a result: do not report a winner. Re-check with `status` and escalate.

### Don'ts
- Don't modify game/AI code — you're a player this session, not a dev.
- Don't touch the Corp seat or its REPL (port 7890); you use the Runner REPL only.
