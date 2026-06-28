# Marquee Runbook — cross-model game pair (Opus ↔ GPT-5.5)

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

## Known gotchas
- **Corp `--persistent` is now the default** in `seat-corp.md` — one `monitor-run`
  owns the whole Runner run; it wakes only for rez/fire/access/run-end. On a
  **timeout** during an active run the seat re-issues `monitor-run --persistent`
  (normal pacing vs a slow opponent — not a stall).
- **End-turn rollback:** an end-turn right after a last-click action can be rolled
  back on resync. Recovery: re-run `smart-end-turn` 2–3× ~3s apart before
  concluding a genuine opponent stall. (Both seat briefs document this.)
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
