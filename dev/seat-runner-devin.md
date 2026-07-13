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
   `./dev/send_command runner game-over-status`. While it prints `IN-PROGRESS …`,
   the game is NOT over — start your next turn and keep playing. Only stop when it
   prints `GAME-OVER winner=… turn=…`. If you stop early while it still says
   IN-PROGRESS, you strand the game and waste the run — keep going until GAME-OVER.

2. **Your opponent is a slow thinking model.** Between your turns the Corp takes
   minutes to think. That is normal, NOT a stall. Block until it's your turn
   instead of polling repeatedly:
   ```
   C=$(./dev/send_command runner get-cursor)     # capture cursor first
   ./dev/send_command runner wait --since "$C"    # blocks until something relevant
   ```
   A `wait` that wakes with reason `my-turn-start` means **start your turn** (run
   `start-turn`); it is NOT a stall. Reason `my-turn` (with clicks) means act now.

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
2. **Decide & act** — draw, gain credits, install programs/resources/hardware,
   run a server. Examples:
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

### ⚠️ If the game won't advance after you end your turn — re-send end-turn
Known rough edge: when you end your turn right after a last-click action whose
resolution is still settling, the engine can roll your end-turn back on a resync.
Symptom: `smart-end-turn` reports success and `game-over-status` shows
`AWAITING-START next-player=corp`, but the Corp never starts. Do NOT conclude
"Corp stalled" from this alone. **Recovery:** if you've ended your turn and the
Corp hasn't started after a `wait` (~10s+), simply re-run
`./dev/send_command runner smart-end-turn`. Retry 2–3 times, ~3s apart, before
deciding it's a genuine Corp-side stall.

### Knowing when to stop
After each of your turns:
```
./dev/send_command runner game-over-status
```
- `GAME-OVER winner=… turn=…` → the game is done. **Stop** and write your final
  report (see DELIVERABLE above).
- `IN-PROGRESS …` → keep playing.

### Don'ts
- Don't modify game/AI code — you're a player this session, not a dev.
- Don't touch the Corp seat or its REPL (port 7890); you use the Runner REPL only.
