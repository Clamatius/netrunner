# Turn-boundary wedge — ROOT CAUSE FOUND + CONFIRMED (2026-06-16)

## STATUS: FIXED (Option 1, defensive engine fix) — 2026-06-16

Implemented in `src/clj/game/core/turns.clj` `end-turn-continue`: right after the
boundary sets `:end-turn true` (line 188), we now also
`(swap! state dissoc-in [(other-side side) :turn-started])`, guaranteeing the
incoming player can always `start-turn` regardless of how their prior flag got
stuck. Safe wrt the "act before clicks" (phase-1.2) flow: the incoming side is not
in phase 1.2 at the boundary, and the extra-turns path restarts the *same* side, so
clearing the *other* side's flag never interferes.

Regression test: `test/clj/game/core/turns_test.clj`
(`wedge-stale-turn-started-cleared-on-boundary`) — red→green confirmed (without the
fix: `active-player` stuck on `:runner`, corp click 0; with it: corp turn starts).
Engine suites green (turns/engine/scenarios 3077 assertions; Encore extra-turns
46 assertions). Options 2 (root self-heal) and 3 (umpire watchdog) remain available
if the wedge ever recurs through a path this defensive clear doesn't cover.



First rung-2 game (two isolated model seats) wedged at the turn-3→4 boundary; no
normal client action recovered it.

## ROOT CAUSE (confirmed by live recovery)

**A stale `[:corp :turn-started] = true` flag tripped the `start-turn` guard.**
`game.core.turns/start-turn` (src/clj/game/core/turns.clj:84) is wrapped in
`(when-not (get-in @state [side :turn-started]) ...)`. The flag is set true at
turn start (line 88) and cleared in `end-turn-continue` (line 208,
`dissoc-in [side :turn-started]`) for the side that is ENDING. The Corp's flag was
stuck `true` from its own turn 3 — so every Corp `start-turn` for turn 4 silently
no-op'd, leaving the engine in a valid-but-frozen "runner ended, waiting for corp
to start" boundary (`active-player=:runner, end-turn=true, turn=3`,
`runner turn-started=nil`, `corp turn-started=true`).

**CONFIRMED:** clearing the flag on the live server state
(`(swap! st update :corp dissoc :turn-started)` via the 7888 nREPL) → the next
`corp start-turn` immediately advanced the game to `turn=4 whose-turn=corp`.
That's the whole bug.

## WHY the Corp's flag wasn't cleared (hypothesis — needs the clean repro)

Corp's turn-3 end must have skipped `end-turn-continue` line 208. Leading theory:
the **rolled-back-end-turn** path (the same class as `4796cb135`). If Corp's
optimistic end-turn ran `end-turn-continue` (clearing the flag) and was then rolled
back on a `:game/error` resync, the rollback RESTORES the pre-end state — which had
`turn-started=true` — and the self-heal re-send may not re-run `end-turn-continue`
to clear it again. So the rollback can leave `:turn-started` stuck. Corp scored
Offworld on a near-last click on T3 then ended — exactly the settling-resolution
shape that triggers the rollback. (Alt: a post-discard consent pause, line 246-258,
that a model seat never acknowledged — less likely, the turn DID end for the runner.)

## FIX DIRECTIONS (pick after the clean repro confirms the WHY)

1. **Engine, defensive (low-risk, recommended):** when `end-turn-continue` sets
   `:end-turn true` (line 188, the boundary), ALSO clear the NEXT player's stale
   flag: `(swap! state dissoc-in [(other-side side) :turn-started])`. Guarantees the
   incoming player can always start, regardless of how their prior flag got stuck.
   Belt-and-suspenders against the rollback class without changing the dedupe guard.
2. **Root:** make the rolled-back-end-turn self-heal restore/clear `:turn-started`
   correctly (if theory holds), so it never gets stuck in the first place.
3. **Orchestrator boundary-watchdog (operational):** umpire detects "AWAITING-START
   next-player=X for >N s with X's start-turn no-op" and clears `[X :turn-started]`
   via the 7888 nREPL (the exact recovery proven above). Mitigates regardless of
   root, and keeps seats from needing to self-diagnose.

## Original capture (server state at the wedge)

Live repro was on the game server (gameid `4d8d3b38-4950-4335-bc65-e055bc61066e`);
recovery already advanced it to turn 4, so that exact frozen instance is consumed.
Re-repro per below.

## REFRAME (important): this is a turn-START bug, not an end-turn rollback

Earlier hypothesis (rolled-back end-turn, like `4796cb135`) is WRONG. Authoritative
server engine state (dumped from the 7888 nREPL, saved to `/tmp/wedge-server-state.txt`)
shows the Runner's turn-end FULLY COMPLETED:

```
turn 3   active-player :runner   end-turn true
phase nil   run? false   eid 400648
queued-events {}        ; nothing pending
effect-completed 0      ; nothing blocked
turn-events: [:post-runner-turn-ends] [:runner-turn-ends] [:runner-action-phase-ends] ...  ; ALL FIRED
CORP click 0  click-per-turn 3   RUNNER click 0
```

So the engine is in a VALID "runner ended their turn, waiting for corp to start"
boundary state. `:runner-turn-ends` and `:post-runner-turn-ends` fired; nothing is
queued or mid-async. The wedge is purely: **the Corp's turn never begins** —
`active-player` is stuck on `:runner` and the Corp's `start-turn` is a no-op (the
seat reported "Ready to start - 0 clicks" but clicks stay 0 and NO server diff is
received). Confirmed: manual `start-turn`, `smart-end-turn`, `end-turn --force`,
`resync`, AND the heuristic `bot-loop` all fail to advance it.

## Why rung-1 never hit it (hypothesis)

Rung-1 = model Runner vs heuristic Corp. The heuristic Corp's `bot-loop` is always
running and issues its `start-turn` the instant the boundary appears. Rung-2 = two
slow models; the Corp deliberates for a long time before sending `start-turn`. So the
trigger is likely TIME/RACE between runner-end and corp-start — a long idle gap at the
boundary. Strongly suggests a deterministic repro: end the runner's turn, WAIT a long
time (minutes?), then have the corp try `start-turn`.

## Where to look (code reading — no bounce needed)

1. AI client `start-turn` — `dev/src/clj/ai_basic_actions.clj` (what server action does
   it send? does it guard on `my-turn?`/`active-player` and silently no-op when
   `active-player` is still `:runner` at the boundary? that would be the bug — a
   client-side guard that refuses to send because it reads the boundary as "not my
   turn yet", so the server never gets the start-turn action).
2. Engine turn transition — `src/clj/game/core/turns.clj`: `end-turn`, `start-turn`,
   how/when `active-player` flips to the next player, and whether `start-turn` has a
   precondition that fails in the boundary state. Does the server expect a
   `:game/action {:command "start-turn"}`? Is there a server-side timeout that voids
   the pending turn-start after long idle?
3. Whether the long idle triggered a server-side inactivity timer that left the game
   in a half-state (the inactivity purge path in `lobby.clj` ~line 839 — `game-finished`
   fires on timeout; but here `:winner` is nil and the game still exists, so it didn't
   fully time out — but a partial timeout effect is worth ruling out).

## Repro plan (after a bounce / fresh game)

1. `make reset`; corp keep, runner keep.
2. Drive a couple of normal turns OR jump straight to: runner takes its turn and ends.
3. At the boundary (`AWAITING-START next-player=corp`), DO NOT start corp immediately —
   `sleep` for increasing intervals (30s, 60s, 120s, 300s) then try `corp start-turn`.
4. Find the threshold where `start-turn` stops working. That isolates whether it's a
   pure idle-timeout or something else.

## Recovery / mitigation ideas

- If it's a client-side `start-turn` guard no-op: fix the guard so a corp at the
  boundary (active-player still runner, end-turn true, my next-turn) actually SENDS the
  start-turn action.
- If it's a server idle-void: an orchestrator/umpire **boundary-watchdog** that nudges
  `start-turn` promptly (before any idle threshold) would both mitigate AND keep seats
  from having to self-recover (they can't distinguish "slow opp" from "wedge").

## Server-state dump command (re-use)

```
lein repl :connect localhost:7888 <<'EOF'
(require '[web.app-state :as app-state])
(let [st (some-> (get (:lobbies @app-state/app-state) #uuid "<GAMEID>") :state deref)]
  (println "turn" (:turn st) "active-player" (:active-player st) "end-turn" (:end-turn st))
  (println "queued-events" (:queued-events st) "effect-completed" (count (:effect-completed st)))
  (println "turn-events" (take 5 (:turn-events st))))
EOF
```
Note: lobbies are keyed by `java.util.UUID`, NOT string — `get-lobby "str"` returns nil.
