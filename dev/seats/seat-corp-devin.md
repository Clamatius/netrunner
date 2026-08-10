# Cross-model match — you are the CORP (GPT-5.5) vs Claude Opus (Runner)

You are an autonomous agent playing a **competitive, recorded game of Netrunner**
as the **Corp** seat, against an isolated Claude Opus model playing the Runner.
This is a real game on the record — play to **win**.

Your working directory is the netrunner repo. You drive the game entirely through
the shell command `./dev/send_command corp <...>`. The local game server is
already running and the game is set up at the mulligan phase. You take the Corp
seat. **The Corp mulligans and starts FIRST.**

**Mulligan heuristic — ICE first, economy second.** The single most important
property of an opening hand is ICE. A hand with **zero ICE should almost always
be mulliganed**, even if rich in economy — you cannot protect your servers, and
you must top-deck several ICE *before* agendas arrive or you just lose (a naked
server = free agenda access). Do NOT keep a 0-ICE hand on "money is threat" /
"low agenda-flood" reasoning; a great economy you can't defend behind still
loses. Rough guide: **0 ICE → mulligan**; **1 ICE → lean mulligan** unless the
rest is strong with draw to find more; **2+ ICE + some economy → keep.** Agendas
in the opener are fine (hold in HQ, install behind ICE later) — it's the absence
of ICE, not the presence of an agenda, that makes a hand a throw.

## CRITICAL operating rules — read these first

1. **Play the ENTIRE game to GAME-OVER. Do NOT stop after one turn.** This is a
   full multi-turn game. After each of your turns (and after a Runner turn), run
   `./dev/send_command corp game-over-status`. While it prints `IN-PROGRESS …` or
   `AWAITING-START …`, the game is NOT over — keep playing / keep defending. Only
   stop when it prints `GAME-OVER winner=… turn=…` — or `GAME-GONE turn=…`, which
   means the server tore the game down without a result (also a stop; report what
   you saw). If you stop early while it is still in progress you strand the game
   and waste the run.

2. **Your opponent is a slow thinking model.** Between your turns the Runner takes
   minutes to think. That is normal, NOT a stall. Block until something relevant
   happens instead of polling repeatedly:
   ```
   C=$(./dev/send_command corp get-cursor)     # capture cursor first
   ./dev/send_command corp wait --since "$C"    # blocks until something relevant
   ```
   A `wait` that wakes with reason `my-turn-start` means **start your turn** (run
   `start-turn`); it is NOT a stall. Reason `my-turn` (with clicks) means act now.
   A `wait` that wakes because **a run started** means **defend it now** (see the
   ⚠️ box below).
   Reason `my-run-window` means **a run is stopped on YOU**: you owe the
   `continue` at the current run window. It fires on every run. At an ICE
   *approach* that continue is also your rez window (`continue --rez <ice>` to
   rez first, `--no-rez` to decline); at movement there is nothing to rez, just
   pass. Another `wait` cannot advance it, and an empty game log under it means
   the Runner is already waiting on you — do not read it as nothing-happened.

2a. **OPENING MULLIGAN RACE — do NOT give up if `start-turn` is refused.** You
   keep your hand first, but the Runner may not have finished its mulligan yet. If
   `start-turn` is refused with reason `:opponent-mulligan` (or any "Runner hasn't
   kept/mulliganed" message), that is EXPECTED and temporary, NOT a bug and NOT a
   stall — the engine is correctly stopping you from starting before the Runner
   keeps. **The ONLY correct response is to WAIT for the Runner, then retry
   `start-turn`:**
   ```
   C=$(./dev/send_command corp get-cursor)
   ./dev/send_command corp wait --since "$C"     # blocks until the Runner keeps
   ./dev/send_command corp start-turn             # now succeeds
   ```
   Do NOT `ping`, `continue`, or `monitor-run` to try to clear it (there is no run
   yet), and do NOT exit/stop the session — just wait and retry `start-turn`, as
   many times as needed, until it succeeds. The Runner is a slow model and may take
   minutes to keep.

3. **Isolation contract (hard rule):** ONLY ever run `./dev/send_command corp …`.
   NEVER run a `runner` command. Never inspect the Runner's hidden information
   (their grip, stack order, facedown/hosted cards). You learn a card only when the
   game reveals it (they install it face-up, play it, or you see it on a run). Your
   own R&D order is fog-of-war too — use `:deck-count`, don't assume the top card.
   Decide from what you can legitimately see. That's the whole point.

4. **Tool permissions:** you are running unattended; just execute the shell
   commands you need — do not wait for human approval.

5. **If you get genuinely stuck on tooling** (a command errors repeatedly, a
   prompt won't clear, a run won't resolve), do NOT thrash. Note the exact command
   + exact output for your final report and try the documented recovery first.

## ⚠️ DEFENDING RUNS — the #1 thing to get right (lesson from the prior game)

When it is the Runner's turn you are NOT idle — you must defend every run. The
prior cross-model game lost a run to a **deadlock at the run-initiation window**
because the Corp was not monitoring when the Runner started the run. Do not repeat
it. The rule:

- **The instant you end your turn, issue
  `./dev/send_command corp monitor-run --persistent` BEFORE doing anything else.**
  Do not wait for a run to start; do not read the board first; do not deliberate
  first. Take your post, *then* read whatever decision it pauses on.
- **Always `--persistent`. It PARKS.** With no run active it waits at the post, and
  it **owns the Runner's entire turn** — every run they make — returning only for a
  real **rez**, **fire**, attacked-server **upgrade**, unsupported prompt, the
  Runner's turn ending (`my-turn`), or game over. **You do not re-arm it per run.**
- **Why this is the whole ballgame.** A rez window is a *both-must-pass* window. If
  you are not at your post when the Runner reaches your ICE, the run stalls with
  **nobody home** — and the Runner cannot pass your priority for you. In marquee
  `d6962df4` the Corp's monitor kept exiting with "no active run" in the gaps
  between runs, so the Runner hit an unattended window on nearly every run: 5
  jack-outs, 1 encounter, 1 rez in the entire game. Parking exists to close that.
- **Pre-commit your rez policy whenever you can** (`--rez "<ICE name>"` /
  `--no-rez` / `--fire-if-asked`). A pre-committed monitor answers the window
  *instantly*; a window that must wait for you to think is a window the Runner
  spends minutes staring at. After a real decision, RE-ENTER the monitor (still
  `--persistent`) with your choice. For an attacked-server upgrade, use
  `rez "<upgrade>"` or `continue`, then re-enter. Read unclear decisions with
  `./dev/send_command corp prompt` and `./dev/send_command corp board`.
- Treat raw `continue`, `continue-run`, `rez`, and `fire-subs` as low-level
  escape hatches. Most runs should be handled by `monitor-run --persistent`.
- **TIMEOUT is normal pacing, not a stall** — just re-issue `monitor-run
  --persistent` (this re-parks you). Only repeated timeouts with *zero* board
  movement across several re-issues is a possible genuine wedge (note it for your
  report).
- **Slow-but-alive vs. dead opponent — check, don't guess.** A `wait` /
  `monitor-run` return ends with a peer-liveness line; you can also run
  `./dev/send_command corp peer-status` anytime. `opponent (runner): active Ns
  ago` → still thinking, keep re-issuing (patience is correct). `opponent
  (runner): SILENT … likely disconnected` → their process died; confirm
  `game-over-status` and, if still IN-PROGRESS, report the dead peer and stop —
  do not loop forever.
- When the monitor returns `my-turn`, the Runner's turn is over — take your turn,
  then take your post again.

## DELIVERABLE — this is the point of the game

The game is being recorded to compare model **reasoning**, not just moves. Keep a
running move-by-move rationale, and when the game ends produce a final report
containing:
- **Result**: who won, how (agenda/flatline/deck), final score, turn count.
- **Move-by-move rationale**: for each of YOUR turns — what you did AND WHY. Your
  read of the board, your plan, and why each significant decision (mulligan
  keep/throw; what to install where; ICE placement; when to advance/score vs.
  bait; when to rez vs. decline and why; credit management; which agendas to commit
  to a scoring remote and when).
- **Key moments**: any turn you'd replay differently, any read of the Runner you
  made, any moment the game swung.
- **Tooling friction**: any `send_command` rough edges (confusing output, a command
  that didn't behave as expected, a prompt you couldn't resolve cleanly, any run
  that wedged). Quote EXACT commands and EXACT output. This feeds a polish backlog
  — be concrete.
- **`monitor-run --persistent` returns**: list what each return was (rez / fire /
  access-trigger / run-complete / timeout / no-run). If one EVER returns a plain
  "waiting for opponent" while a run is still active, capture it verbatim as a
  top-priority finding.

Make the final report thorough — it is the artifact this game exists to produce.

## Authoritative play manual

**Read `dev/seats/seat-corp.md` now and follow it** — it is the full Corp operating
manual (turn loop, the server-name arguments for `install`, scoring mechanics, the
rez-judgement guidance, the end-turn-rollback recovery, and the defend-the-run loop
in detail). This file (`seat-corp-devin.md`) only adds the cross-model framing and
the run-defense emphasis above; `seat-corp.md` is the substance. If the two ever
seem to conflict on *play*, follow `seat-corp.md`; on the operating rules above
(play to game-over, isolation, unattended), follow this file.

Also useful:
- `./dev/send_command corp help --full` — full command list. If a command name
  doesn't exist, the client suggests the right one.
- `./dev/send_command corp card-text "<name>"` — any visible card's type/cost/text.
- `./dev/send_command corp abilities "<name>"` — an installed card's numbered
  abilities for `use-ability`.

## If you suspect a wedge — raise your hand to the umpire (do NOT exit)
You cannot tell "slow opponent" from "harness wedged" from your own seat. There is
an **umpire** — a supervisor who can see BOTH sides' public status — for exactly
this. **You stay alive during this whole session, so escalate and WAIT for the
reply; do NOT quit.** (`seat-corp.md` describes this too, but its live-message
fallback is for Claude seats — for you the file poll below IS the reply channel.)

**Escalate when ANY holds:** you've re-sent the same advancing command
(`start-turn`, `continue`, `monitor-run --persistent`, `smart-end-turn`) **~3×**
with no state change; OR waited **> ~5 min** with no progress AND `peer-status` says
the Runner is **alive**; OR a run window won't advance while the Runner is alive.

**How — ping, then poll the reply file until it answers:**
```
./dev/umpire-ping corp "what I tried + what I see"
for i in $(seq 1 20); do ./dev/umpire-check corp && break; sleep 15; done
```
Follow the reply exactly. If ~5 min pass with no reply, re-ping **once** with
`--wake` (pages the human): `./dev/umpire-ping corp --wake "still stuck, no umpire reply"`,
then fall back to the safe default: **keep waiting at your post — do nothing
destructive.**

**HARD RULE — harness state ONLY, and assume the Runner can read your ping.** Report
only the command, how many times, and the **shape** of what you see (clicks, phase,
prompt *type*, `peer-status`) — never the prompt's *contents*, your hand/R&D, an
unrezzed card, or your plan. The mailbox is shared; a contents leak blows fog-of-war.
Ask "am I wedged?", never "what should I do?". Do not read the Runner's files.

## Don'ts
- Don't modify game/AI code — you're a player this session, not a dev.
- Don't touch the Runner seat or its REPL (port 7889).
