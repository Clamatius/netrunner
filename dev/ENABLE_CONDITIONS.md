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
gate: `end-turn` off-turn / duplicate — `game.core.process-actions/guarded-end-turn`.

The ratchet `dev/test/send_command_inventory_test.clj` keys the call sites
**per command token** (literal or computed head form) — 64 sites on
2026-08-21 — and fails when any per-command count changes, with a message
naming the token. Known residual: a same-token remove+add in one merge is
invisible to it (that is what `git diff board.cljs` on an upstream merge is for).

Two UI facts that shape several rows (guest panel):
- `start-turn` grants the clicks **before** opening the phase-1.2 window
  (`game.core.turns`), so "clicks > 0" is *not* evidence the action phase has
  begun. board.cljs hides every basic-action button while `phase-locked`
  (`(or runner-phase-12 corp-phase-12 runner-post-discard corp-post-discard)`);
  our `ensure-can-act!` = `ensure-turn-started!` ∧ ¬`phase-locked?`.
- board.cljs has **two** UI paths for several card commands — the run panel
  (rules-correct conditions) and the per-card menu (looser: e.g. "Fire
  unbroken subroutines" from the card menu for any active ICE with an unbroken
  sub, no encounter check). We mirror the **stricter** path where they differ,
  since the looser one lets a human make an illegal move too.

Legend — **UI condition** is board.cljs's enable predicate (paraphrased;
"menu:" marks the card-menu site); **ours** is the refusing predicate in our
sender; **gap** is what the sender lacked at inventory time. ✅ mirrored ·
➖ not sent by us (vocabulary gap, #112) · ✨ fixed in this PR ·
⚠️ residual (relies on engine refusal + honest verify, not a pre-send predicate).

## Turn structure

| command | UI condition (board.cljs) | ours | test | gap |
|---|---|---|---|---|
| `end-turn` | button: active player = me ∧ ¬phase-locked ∧ clicks = 0 ∧ ¬`:end-turn`; space-bar: active ∧ ¬phase-1.2 ∧ clicks = 0 ∧ ¬`:end-turn` (does NOT check post-discard — UI inconsistency) | `end-turn!`: no-board · `:end-turn` true → "no turn in progress" (#133) · not-my-turn · already-ended (log) · clicks > 0 refuse/`--force` · **✨ phase-1.2 window open → refuse** · **✨ post-discard window open → refuse** | ai-basic-actions-test `test-end-turn-refuses-while-phase-12-window-is-open`, `…-post-discard-window-is-open`; ai-turn-validation-test | **engine gate** `guarded-end-turn` (off-turn · `:end-turn` true · this side's post-discard active) — game.ai-end-turn-gate-test (4 cases) |
| `start-turn` | active player ≠ me ∧ `:end-turn` ∧ ¬post-discard | `start-turn!`: no-board · post-discard · first-turn/side · opponent mulligan (#87) · own mulligan (#131) · opponent-has-clicks / opp-ended · engine `opponent-has-blocking-prompt?` (2de58a1fd) | ai-turn-boundary-test, ai-basic-actions-test | ✅ |
| `end-phase-12` / `phase-12-pass-priority` | button: active ∧ phase-12 ∧ (consent ⇒ ¬already-passed); **space-bar sends plain `end-phase-12` even when consent is required** (UI inconsistency; engine `end-phase-12` does not check consent) | `close-phase-window! :phase-12`: owner / consent / already-passed — stricter than the keyboard path | game.ai-phase-windows-test, ai-phase-window-test | ✅ |
| `end-post-discard` / `post-discard-pass-priority` | same shape for the post-discard window | `close-phase-window! :post-discard` | same | ✅ |
| `start-next-phase` | run ∧ `:next-phase` ∧ phase ≠ initiation ∧ ¬`:no-action` | — | — | ➖ #112 item 1 (the one vocabulary gap that can wedge a game; unverified) |

## Basic actions

| command | UI condition | ours | test | gap |
|---|---|---|---|---|
| `credit` | ¬phase-locked ∧ ability 0 playable (clicks > 0) | `take-credit!` → **✨ `ensure-can-act!`** (turn started ∧ ¬phase-locked) | `test-basic-actions-refuse-while-phase-locked` | ✨ phase-lock (clicks are already granted inside the window) |
| `draw` | ¬phase-locked ∧ ability 1 playable ∧ `(pos? deck-count)` | `draw-card!`: ✨ `ensure-can-act!` · **✨ deck-count = 0 → refuse** (pure UI mirror: the basic action card's draw has `:req (not-empty deck)`, so the engine refuses too; only mandatory/effect draws deck the Corp) | `test-draw-refuses-on-an-empty-deck`, phase-lock test | ✨ |
| `purge` | ¬phase-locked ∧ ability 6 playable (3 clicks) | `purge-viruses!`: ✨ `ensure-can-act!` · side · clicks ≥ 3 | ai-basic-actions-test | ✨ phase-lock |
| `remove-tag` | ¬phase-locked ∧ ability 5 playable ∧ tags > 0 | `remove-tag!`: ✨ `ensure-can-act!` · tagged · credits ≥ 2 · clicks ≥ 1 | ai-basic-actions-test | ✨ phase-lock |
| `trash-resource` | ¬phase-locked ∧ ability 5 playable ∧ runner tagged | `trash-resource!`: ✨ `ensure-can-act!` · tagged · credits ≥ 2 · clicks ≥ 1 (+ #151 select-prompt surfacing) | `test-trash-resource-*` | ✨ phase-lock |
| `run` | runner ∧ ¬phase-locked ∧ clicks > 0 (server ∈ `:runnable-list`, populated by `generate-runnable-zones`) | `run!`: side · ✨ `ensure-can-act!` · server normalisation | ai-runs-test | ✨ phase-lock; ⚠️ `:runnable-list` not mirrored (we never request it) — an un-runnable server is refused by the engine and reported |

## Cards

| command | UI condition | ours | test | gap |
|---|---|---|---|---|
| `play` (hand) | card in hand ∧ engine `:playable` flag ∧ ¬any-prompt-open ∧ own card | `play-card!` / `install-card!`: prompt-blocking · affordability pre-check · verify-in-log (honest fail) | ai-actions-test | ⚠️ the engine's `:playable` flag (timing/req rules beyond cost) is not checked pre-send; a refused play reports as failed, never as success |
| `play` (server menu) / `expend` | install-list from engine | `install-card!` with server | ai-actions-test | ⚠️ as above · expend ➖ (#112) |
| `flashback` | discard ∧ flashback-playable | — | — | ➖ (#112) |
| `ability` / `dynamic-ability` / `runner-ability` / `corp-ability` | ability `:playable` ∧ menu open | `use-ability!` / `use-runner-ability!`: break-legality guard (#100 class) · verify-in-log + `ability-failure-lines` (#116 reads `:playable` AFTER) | ai-ability-legality-test, game.ai-ability-legality-test | ⚠️ `:playable` not checked pre-send for non-break abilities; corp-ability ➖ |
| `rez` | run panel: approach-ice ∧ ice ∧ ¬rezzed; **menu: any unrezzed Asset/ICE/Upgrade, no phase condition** | `rez-card!`: `valid-ice-rez?` (ICE only at approach-ice — the stricter path) · asset/upgrade any time · `:rezzed` flip as ground truth (#86); strategy handlers at approach-ice / movement-pos-0 (#67) | continue-run-rez-test, game.ai-upgrade-rez-timing-test | ✅ (stricter than the menu) |
| `derez` | menu: rezzed ∧ derezzable | — | — | ➖ |
| `trash` (menu) | menu: **type ∈ {ICE, Program}** only | `trash-installed!`: found · **✨ type ∈ {ICE, Program}** (engine `trash-button` trashes anything) | ai-actions-test `trash-installed-offers-only-ice-and-programs` | ✨ |
| `advance` | menu: advanceable (engine per-card flag, rezzed/unrezzed rule) | `advance-card!`: overadvance guard · ensure-turn-started | ai-actions-test | ⚠️ advanceable flag not pre-checked (refused advance reports as unconfirmed) |
| `score` | menu: Agenda ∧ installed ∧ active player ∧ counters ≥ `(or current-advancement-requirement advancementcost)` | `score-agenda!`: counters ≥ **✨ `(or :current-advancement-requirement :advancementcost)`** (was printed cost only — blocked SanSan scores) | ai-actions-test `score-uses-the-current-advancement-requirement` | ✨ |
| `unbroken-subroutines` | run panel: encounter (`encounter-ice` phase ∨ `@encounters`) ∧ an unbroken, unfired, resolvable sub; **menu: any active ICE with an unbroken/unfired sub, no encounter check** | manual `fire-unbroken-subs!`: side · **✨ encountering THIS ice** (`[:encounters :ice]` first — forced encounters — else position ICE at encounter-ice) **∧ fireable subs**; strategy handlers already encounter-gated | ai-actions-test `fire-subs-refuses-outside-an-encounter-with-that-ice`, `…-allows-a-forced-encounter-outside-a-run` | ✨ (stricter than the menu; engine `play-unbroken-subroutines` checks only "no blocking prompt") |
| `subroutine` (fire one) | run panel: corp ∧ encounter; menu: any sub | — | — | ➖ (#112) |
| `system-msg` ("indicates to fire…") | run panel: encounter ∧ unbroken subs; menu: `(seq subroutines)` | `let-subs-fire!` (tank signal): side | ai-runs-test | ✅ (informational chat line) |
| `toggle-auto-no-action` | run ∧ encounter-count ≤ 1 ∧ ¬success | `toggle-auto-no-action!`: side | — | ⚠️ unguarded toggle; harmless UI flag |

## Run flow

| command | UI condition | ours | test | gap |
|---|---|---|---|---|
| `continue` (8 sites) | per phase: `:no-action` ≠ my side; encounter: `(not= side (:no-action @encounters))`; initiation/movement/breach likewise; space-bar: `(not= side no-action)` with run/encounter ledger | chokepoints `send-continue!` ×3 + `send-choice!`: waiting-prompt (#75) · already-passed incl. **encounter ledger** (#98/#150) · `should-i-act?` · stalled-window self-advance (#31) | continue-run-rez-test, run-window-selfadvance-test, game.ai-duplicate-continue-test | ✅ |
| `jack-out` | run ∧ ¬forced-encounter ∧ phase ≠ success ∧ movement ∧ ¬cannot-jack-out ∧ `:no-action` ≠ runner | `jack-out-legality` (ai_runs) mirrors every clause; send_command consults it before the send | ai-runs-test | ✅ |
| `choice` (9 sites) / `select` / `bad-pub-choice` | prompt present; `:eid` of the prompt | `choose!` / `select-card!` / `choose-by-value!`: `:eid` always named (#113) | ai-prompts-test, game.ai-pay-all-test | ✅ (bad-pub-choice ➖) |

## Housekeeping (not game actions)

`shuffle` · `view-deck` · `close-deck` · `move` (drag) · `toast` (ack) ·
`generate-install-list` · `generate-runnable-zones` — UI conveniences we
either do not send or compute locally. No enable condition to mirror.

## Residuals (on the record)

- `:playable` / advanceable flags not checked pre-send for `play`, abilities, `advance`
  (we pre-check cost and verify in the log; a refused action never reports success).
- `run` does not mirror `:runnable-list` (we never request `generate-runnable-zones`).
- `toggle-auto-no-action` unguarded (harmless flag).
- `start-next-phase` is the one vocabulary gap that can wedge a game (#112 item 1).
- Upstream UI inconsistencies noted above (space-bar end-turn ignores post-discard;
  space-bar `end-phase-12` ignores consent; card-menu fire/rez looser than the run panel).
