# Marquee Runbook — cross-model game pair (Opus ↔ GPT-5.5)

> ## Current round: Fable ↔ GPT-5.6 Sol (2026-08-03) — deltas from the base runbook
> Tier-1 pair. Everything below still applies except:
> - **Models:** Claude seat = **Fable** (background Agent subagent, `model: "fable"`);
>   guest seat = **GPT-5.6 Sol** via devin (`--model gpt-5.6-sol` — PONG-verified
>   2026-08-03). Codex CLI stays reserved for gpt-5.5-pinned experiments.
> - **Seat briefs:** `dev/seat-corp-sol.md` / `dev/seat-runner-sol.md` (wrappers over
>   the canonical `seat-corp.md`/`seat-runner.md`, adapted from the Terra briefs);
>   mid-game restart: `dev/seat-corp-sol-resume.md`.
> - **Babysitter (keep armed for any 5.6 seat):** `dev/marquee-babysit.sh <side>
>   gpt-5.6-sol <tag>` — re-invokes `devin -p -c` with an action-forcing nudge
>   whenever the seat's process exits while the game is live. Proven Terra round
>   6d8f4cf8; harmless when unneeded. Launch it right after spawning the devin seat.
> - **Pre-round fixes in:** #95 (bioroid click-break reachable — Runner seat) and
>   #94 (`--fire-if-asked` no longer swallows the movement/pos-0 upgrade-rez
>   window). Both change fairness-relevant play; do not run the pair on an older
>   build.
> - **Game A:** Corp = Fable, Runner = Sol. **Game B:** swap. (Corp spawns first,
>   as below.)

Turnkey launch sequence for an **un-babysat** cross-model Netrunner game. Michael
does NOT want to HITL these (model turns are 5+ min each — a huge time sink); the
autonomous round owns the whole thing and posts results to the forum.

This is the live **acceptance test for `monitor-run --persistent`** (the Corp
defender-loop fix, commit `ac3232f12`). See forum `ai-netrunner` 056–061.

## Acceptance criterion (codex55, msg 060) — what "passed" means
Every time the Corp's `monitor-run --persistent` RETURNS, it must be one of:
**rez decision · fire decision · access-trigger decision · run-complete · timeout · no-run.**
If it ever returns a plain `:waiting-for-opponent` *while a run is still active*,
that is the regression smell — capture it (command + output + cursor) as the
top finding. Preserve the command log around every `monitor-run` return.

## Preconditions (check first, ~30s)
```bash
make test                 # expect 285+ tests, 0 failures (known-good baseline)
./dev/send_command corp game-over-status   # NO-GAME or a finished game → safe to reset
DEVIN_PERMISSION_MODE=dangerous devin -p --model gpt-5.5 -- "Reply with exactly: PONG"
                          # expect PONG, exit 0 → GPT-5.5 provisioning live
                          # NOTE: devin CLI dropped --prompt; inline prompts now go after `--`.
                          # --prompt-file (used by the Runner spawn below) is unchanged.
which devin               # /Users/mcooper/.local/bin/devin
```
Both model strings are valid: `gpt-5.5`, `claude-opus-4.8`.

## Seat assignment
- **Game 1:** Corp = **Opus** (subagent, `dev/seat-corp.md`); Runner = **GPT-5.5**
  (`devin -p`, `dev/seat-runner-devin.md`). Rationale: GPT on the simpler proactive
  Runner; the proven Opus-subagent harness takes the harder reactive Corp defense.
- **Game 2 (the pair):** swap — Corp = GPT-5.5, Runner = Opus. Play the pair so
  seat-brief/player-doc bias cancels out. Game 2 needs a GPT **Corp** brief
  (`dev/seat-corp.md` is Opus-flavored prose but model-agnostic instructions; a
  thin `seat-corp-devin.md` wrapper like the runner one is the clean path if GPT
  needs the explicit loop-to-game-over framing).

## Launch sequence (ORDER MATTERS — mulligan-race guard depends on it)
1. **Fresh game, replay on** (create-game defaults to `:save-replay true`):
   ```bash
   ./dev/reset.sh           # fresh game at mulligan; note the GameID
   ```
2. **Spawn the Corp seat FIRST** as a background Agent subagent (context-isolated
   — fog-of-war): prompt = "read and follow `dev/seat-corp.md`; play to GAME-OVER;
   keep a move-by-move rationale for the final report." `run_in_background: true`.
3. **Confirm the Corp kept its hand** before spawning the Runner — read-only via
   the idle/free REPL:
   ```bash
   ./dev/send_command runner log-compact 5     # look for "ai-corp keeps their hand"
   ```
   The `7f8f8b3ec` mulligan-race guard means a premature Runner start is refused,
   but spawning Corp→keep→Runner keeps the opening clean.
4. **Spawn the Runner seat** (GPT-5.5) — run in background via Bash:
   ```bash
   DEVIN_PERMISSION_MODE=dangerous devin -p --model gpt-5.5 \
     --prompt-file dev/seat-runner-devin.md > logs/marquee-runner-devin.log 2>&1
   ```
   `DEVIN_PERMISSION_MODE=dangerous` is REQUIRED (no human to approve tool calls;
   without it the seat hangs on the first `send_command`). devin runs in repo cwd,
   so it gets `send_command` + the local server for free. **devin output BUFFERS to
   process end** — you won't see incremental output; rely on the game log/watcher
   for live state. Capture the buffered output to a file so the Runner's final
   report survives — use repo-root `logs/` (it exists; `dev/logs/` does NOT, a
   redirect there fails).

## Monitor (read-only, fog-of-war intact)
- NEVER peek into either seat's hidden info; only read the shared log / public state.
- Use the watcher per side if you want a live feed:
  `./dev/watch_game.sh corp` / `./dev/watch_game.sh runner` (Monitor tool).
- Each seat checks its own `game-over-status`; you do too, to know when to harvest.

## Umpire escalation channel (issue #20) — you adjudicate wedges
A seat that suspects a wedge pings you out of band instead of spinning silently.
Take the umpire post for the whole game:
```
Monitor({command:"./dev/umpire-watch.sh", description:"seat escalations", persistent:true})
```
The watcher wakes you on each seat PING (and, on (re)start, replays any still-
unanswered ping). On a ping:
1. Read BOTH sides' **public** status only — `game-over-status`, `peer-status`,
   `prompt` per side — and decide wedged-or-not. (You may look at both; that's the
   whole point of the umpire seat. Do NOT relay what you see.)
2. Answer: `./dev/umpire-reply <side> "<harness-state answer>"`. The seat is polling
   `umpire-check` and will pick it up.
3. If genuinely wedged, apply the documented recovery yourself where you can — but
   **NEVER re-send an end-turn, and never instruct a seat to.** An off-turn end-turn
   ends the opponent's turn and is logged under the sender's name; no game has ever
   been recovered from it (it killed game 02995207). The client now refuses off-turn
   end-turns, so treat that refusal as correct and look elsewhere. If you can't
   recover without one, the game is dead — abandon it and `--wake`/ntfy Michael
   rather than "fixing" it into an unrecoverable state.

**HARD CONSTRAINT (you see both hands — do not blow fog-of-war):** reply about
harness/tooling state ONLY. Keep recoveries **command-only and card-name-free**
(don't name an unrezzed ICE). If a seat asks a strategy question, refuse and
redirect. The mailbox is opponent-readable, so a leak in YOUR reply is as bad as one
in a seat's ping. Canned replies to prefer (bland by design):
- `not wedged — opponent has an open decision window, keep waiting`
- `not wedged — opponent is mid-turn, keep waiting`
- `wedged — hold position, do not re-send anything; I am investigating`
- `harness channel only — I can't advise on play; re-ask about tooling state`

**One umpire per match dir.** Two sessions Monitoring the same mailbox will both
reply and step on each other. If you're supervising, you're the only umpire.
(`reset.sh` clears `dev/.umpire/` on a fresh game, so stale pings don't carry over.)

**⛔ NEVER run `./dev/umpire-check <side>` as the umpire.** It is the SEAT's
consumption endpoint and it is a **destructive read**: it marks the pending reply
seen (`dev/.umpire/reply-<side>.seen`), so the seat never receives it. Doing this
silently swallowed an answer mid-match — the seat re-pinged "still no reply" four
seconds after the reply had been written, and the only fix was to re-send it.
To inspect the channel, read the log directly:
```bash
tail -20 dev/.umpire/mailbox.log      # safe: pings AND replies, non-destructive
```
Rule of thumb: `umpire-ping`/`umpire-check` belong to the seats, `umpire-reply`
and reading the mailbox belong to you.

## Known gotchas
- **Corp `--persistent` is now the default** in `seat-corp.md` — one `monitor-run`
  owns the whole Runner run; it wakes only for rez/fire/access/run-end. On a
  **timeout** during an active run the seat re-issues `monitor-run --persistent`
  (normal pacing vs a slow opponent — not a stall).
- **NEVER re-send end-turn** (this replaces the old "retry 2–3×" advice, which
  killed game 02995207). An end-turn landing off-turn ends the OPPONENT's turn and
  is logged under the sender's name, leaving no `<opponent> is ending` line — after
  which log-derived turn state permanently disagrees with `:end-turn` and the match
  wedges. The client now refuses off-turn end-turns; if a seat reports
  `⛔ Refusing end-turn`, that guard is doing its job. A seat that thinks the game
  won't advance should escalate, not retry.
- **Symmetric priority window** (the original deadlock) is now side/priority-aware
  in the prompt (`013469b09`) AND auto-passed by the persistent Corp loop — this
  game is the test that those two fixes together let the models self-resolve.

## On GAME-OVER — harvest
```bash
./dev/save-replay.sh <gameid>     # leaves both seats → flush → dev/replays/<gameid>.json
```
Then collect: (1) the result (winner/how/score/turns), (2) BOTH seats' move-by-move
rationale writeups (the point of the exercise — model reasoning, not just moves),
(3) a tooling-friction triage list (quote exact commands/output), (4) the
`--persistent` acceptance check above. **Post all of it to forum `ai-netrunner`.**
Then run Game 2 (swap) for the full pair.
