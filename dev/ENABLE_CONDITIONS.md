# Enable-conditions inventory (#152)

`src/cljs/nr/gameboard/board.cljs` (and `actions.cljs`) IS the rules layer: the
engine's `process_actions.clj` trusts the client, so every illegal move the
AI seats have ever made (#131 start-turn over an open mulligan, #133 off-turn
end-turn, the jack-out-mid-encounter class, the phase-window commands) was a
`send-command` site whose **enabling condition** — the `when`/`if`/`cond-button`
predicate around the button — we had not mirrored. #112 diffed the wire
*vocabulary*; this table diffs the *conditions*.

Policy (#107): **client-side gate by default; engine gate only when a leak is
derailing AND the client check is raceable.** Exactly one row earns the engine
gate (end-turn, off-turn / duplicate — `game.core.process-actions/guarded-end-turn`).

The ratchet `dev/test/send_command_inventory_test.clj` fails when the number
of `send-command` call sites, or the set of literal command strings, changes —
so an upstream merge that adds a button surfaces here instead of as the next
incident. Counted 2026-08-21: **64 call sites, 33 literal commands**.

Legend — **UI condition** is board.cljs's enable predicate (paraphrased);
**ours** is the refusing predicate in our sender (file); **gap** is what the
sender lacked at the time of the inventory. ✅ mirrored · ➖ not sent by us
(vocabulary gap, see #112) · ✨ fixed in this PR · ⚠️ known residual.

## Turn structure

| command | UI condition (board.cljs) | ours | test | gap |
|---|---|---|---|---|
| `end-turn` | active player = me ∧ ¬phase-locked ∧ clicks = 0 ∧ ¬`:end-turn` (basic-actions; also the space-bar handler) | `end-turn!` (ai_basic_actions): no-board refuse · `:end-turn` true → "no turn in progress" (#133) · not-my-turn (:active-player) · already-ended (log) · clicks>0 refuse/`--force` · **✨ phase-1.2 window open → refuse** | ai-basic-actions-test `test-end-turn-refuses-while-phase-12-window-is-open`; ai-turn-validation-test (#133) | ✨ phase-1.2 (clicks=0 is exactly the state the click guard waves through); **engine gate** `guarded-end-turn` for off-turn/duplicate (game.ai-end-turn-gate-test) |
| `start-turn` | active player ≠ me ∧ `:end-turn` ∧ ¬post-discard | `start-turn!`: no-board · post-discard · first-turn/side · opponent mulligan (#87) · own mulligan (#131) · opponent-has-clicks / opp-ended (log) · `opponent-has-blocking-prompt?` engine guard (2de58a1fd) | ai-turn-boundary-test, ai-basic-actions-test | ✅ |
| `end-phase-12` / `phase-12-pass-priority` | active player = me ∧ phase-12 window; consent variant: `(not (side phase-12))` | `close-phase-window! :phase-12`: owner / consent / already-passed | game.ai-phase-windows-test, ai-phase-window-test | ✅ |
| `end-post-discard` / `post-discard-pass-priority` | same shape for the post-discard window | `close-phase-window! :post-discard` | same | ✅ |
| `start-next-phase` | run ∧ `:next-phase` ∧ phase ≠ initiation ∧ ¬`:no-action` | — | — | ➖ #112 item 1 (the only potential game-killer in the vocab list; unverified) |

## Basic actions

| command | UI condition | ours | test | gap |
|---|---|---|---|---|
| `credit` | ¬phase-locked ∧ basic ability 0 playable (clicks>0) | `take-credit!` → `ensure-turn-started!` (clicks>0 or can-start) | ai-basic-actions-test | ✅ (phase-locked ⇒ 0 clicks ⇒ refused/no-op) |
| `draw` | ¬phase-locked ∧ ability 1 playable ∧ **`(pos? (:deck-count @me))`** | `draw-card!`: ensure-turn-started · **✨ deck-count = 0 → refuse** (engine: a Corp click-draw from empty R&D is `win-decked`) | `test-draw-refuses-on-an-empty-deck` | ✨ deck-out — derailing, not raceable (local count) ⇒ client gate |
| `purge` | ¬phase-locked ∧ ability 6 playable (3 clicks) | `purge-viruses!`: side · clicks ≥ 3 | ai-basic-actions-test | ✅ |
| `remove-tag` | ¬phase-locked ∧ ability 5 playable ∧ tags>0 | `remove-tag!`: tagged · credits ≥2 · clicks ≥1 | ai-basic-actions-test | ✅ |
| `trash-resource` | ¬phase-locked ∧ ability 5 playable ∧ runner tagged | `trash-resource!`: tagged · credits ≥2 · clicks ≥1 (+ #151 select-prompt surfacing) | `test-trash-resource-*` | ✅ |
| `run` | runner ∧ ¬phase-locked ∧ clicks>0 (+ server ∈ runnable-list) | `run!`: side · ensure-turn-started · server normalisation | ai-runs-test | ✅ |

## Cards

| command | UI condition | ours | test | gap |
|---|---|---|---|---|
| `play` (hand) | card in hand ∧ engine `playable?` flag ∧ ¬any-prompt-open ∧ own card | `play-card!` / `install-card!`: affordability pre-check · prompt-blocking · verify-in-log (honest fail) | ai-actions-test | ✅ (engine `playable?` not re-derived beyond cost; a refused play reports as failed, not success) |
| `play` (server menu) / `expend` | install-list from engine | `install-card!` with server | ai-actions-test | ✅ / ➖ expend (#112) |
| `flashback` | discard ∧ flashback-playable | — | — | ➖ (#112) |
| `ability` / `dynamic-ability` / `runner-ability` / `corp-ability` | ability `playable?` ∧ menu open | `use-ability!` / `use-runner-ability!`: break-legality guard (#100 class) · verify-in-log | ai-ability-legality-test, game.ai-ability-legality-test | ✅ (corp-ability ➖, unused) |
| `rez` (ICE, run prompt) | approach-ice ∧ ice ∧ ¬rezzed | `rez-card!`: `valid-ice-rez?` (approach-ice) · asset/upgrade any time · `:rezzed` flag flip as ground truth (#86); strategy handlers at approach-ice / movement-pos-0 (#67) | continue-run-rez-test, game.ai-upgrade-rez-timing-test | ✅ |
| `derez` / `trash` / `advance` / `score` (card menu) | action ∈ engine's per-card `:actions` | `trash-installed!` (found) · `advance-card!` (overadvance guard) · `score-agenda!` (counters ≥ requirement) | ai-actions-test | ✅ (derez ➖) |
| `unbroken-subroutines` | **encounter-ice (or `@encounters`) ∧ ice has an unbroken, unfired, resolvable sub** | manual `fire-unbroken-subs!`: side · **✨ encountering THIS ice ∧ fireable subs**; strategy handlers already encounter-gated | ai-actions-test `fire-subs-refuses-outside-an-encounter-with-that-ice` | ✨ engine `play-unbroken-subroutines` checks only "no blocking prompt" — would fire any rezzed ICE's subs any time |
| `subroutine` (fire one) | corp ∧ encounter | — | — | ➖ (#112) |
| `system-msg` ("indicates to fire…") | encounter ∧ unbroken subs | `let-subs-fire!` (tank signal): side | ai-runs-test | ✅ (informational) |
| `toggle-auto-no-action` | run ∧ encounter-count ≤ 1 ∧ ¬success | `toggle-auto-no-action!`: side | — | ⚠️ unguarded toggle; harmless (UI flag), left |

## Run flow

| command | UI condition | ours | test | gap |
|---|---|---|---|---|
| `continue` (8 sites) | per phase: `:no-action` ≠ my side; encounter: `(not= side (:no-action @encounters))`; initiation/movement/breach likewise | chokepoints `send-continue!` ×3 + `send-choice!`: waiting-prompt (#75) · already-passed incl. **encounter ledger** (#98/#150) · `should-i-act?` · stalled-window self-advance (#31) | continue-run-rez-test, run-window-selfadvance-test, game.ai-duplicate-continue-test | ✅ |
| `jack-out` | run ∧ ¬forced-encounter ∧ phase ≠ success ∧ movement ∧ ¬cannot-jack-out ∧ `:no-action` ≠ runner | `jack-out-legality` (ai_runs) mirrors every clause; send_command consults it before the send | ai-runs-test | ✅ |
| `choice` (9 sites) / `select` / `bad-pub-choice` | prompt present; `:eid` of the prompt | `choose!`/`select-card!`/`choose-by-value!`: `:eid` always named (#113) | ai-prompts-test, game.ai-pay-all-test | ✅ (bad-pub-choice ➖) |

## Housekeeping (not game actions)

`shuffle` · `view-deck` · `close-deck` · `move` (drag) · `toast` (ack) ·
`generate-install-list` · `generate-runnable-zones` — UI conveniences we
either do not send or compute locally. No enable condition to mirror.

## Residuals worth a follow-up

- `toggle-auto-no-action` unguarded (harmless flag).
- `start-next-phase` is the one vocabulary gap that can wedge a game (#112 item 1).
- Engine `play-unbroken-subroutines` is encounter-blind (upstream); our gate is client-side only — fine under #107 (not raceable: the encounter is local state).
