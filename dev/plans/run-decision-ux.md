# Run Decision UX Plan

## Goal

Reduce autonomous/HITL run-defense turns by making the agent-facing Corp UX describe real decisions, not raw JNet action windows. The low-level `continue`, `continue-run`, `rez`, and `fire-subs` controls remain available as an escape hatch, but the default path should be:

- Corp sleeps through empty priority windows.
- Corp wakes only for a real decision in the current cardpool.
- When Corp wakes after sleeping, the output tells the agent what happened while it was asleep.

This plan is scoped to the current beginner/eval cardpool behavior and should be conservative for unknown cards or prompts.

## Product Contract

For `./dev/send_command corp monitor-run --persistent`:

- Server with no ICE and no relevant Corp prompt: keep sleeping until access/run end.
- Rezzed ICE only: keep sleeping except for unbroken-sub firing if we still choose to expose that decision.
- One unrezzed ICE: wake at approach with a compact `rez <ICE> / decline` decision.
- Defensive upgrade on the attacked server: wake only at the card's relevant checkpoint, initially `:passed-last-ice` / `:pre-access` for `Manegarm Skunkworks`.
- Assets/upgrades on other servers: do not wake unless their title is explicitly in a cross-server capability table.
- Unknown real Corp prompt: wake conservatively and label it as an unsupported/low-level decision.

The UX should lead with the safe high-level commands and place raw controls second:

- Prefer: `continue --no-rez`, `continue --rez "<ICE>"`, or future `run-decision <choice>` if added.
- Escape hatch: `prompt`, `board`, `continue --single`, raw `rez`, `fire-subs`.

## Engineering Shape

Add a pure Corp decision classifier near the existing Corp run handlers. The classifier answers "is there a real Corp decision here?" only; strategy and CLI presentation live in separate consumers.

```clojure
(corp-run-decision state)
;; =>
;; {:kind :none
;;  :summary "No Corp decision: no relevant unrezzed ICE or server upgrades"
;;  :server :remote1}
;;
;; {:kind :rez-ice
;;  :wake-reason :rez-decision
;;  :server :remote1
;;  :phase "approach-ice"
;;  :ice {:title "Palisade" :position 1}}
;;
;; {:kind :fire-unbroken
;;  :wake-reason :fire-decision
;;  :ice {:title "Enigma" :position 1 :unbroken-count 1}}
;;
;; {:kind :server-upgrade
;;  :wake-reason :server-upgrade-decision
;;  :card {:title "Manegarm Skunkworks"}
;;  :checkpoint :pre-access}
;;
;; {:kind :unsupported-prompt
;;  :wake-reason :decision-required
;;  :prompt my-prompt}
;; ```

The classifier should be side-effect free and tested with mocked plus captured game states. Handler code should consume it, not duplicate card/window logic. A presenter maps semantic decisions to text/commands for HITL use. Autonomous policy maps semantic decisions to actions (`:rez`, `:decline`, `:fire`, `:wait`) without parsing shell strings.

## Wake Rule

Do not make local server decisions depend on a hand-maintained allowlist. Default safety rule:

- Wake for the currently approached unrezzed ICE.
- Wake when rezzed ICE has unbroken/unfired subs and the Runner has signaled.
- Wake for a forced/actionable Corp prompt with choices or selectables.
- Wake when unrezzed root content on the attacked server can matter at the current pre-access checkpoint.
- Ignore assets/upgrades on other servers unless a future cross-server capability entry says otherwise.

The card table is only for exceptional cards, not the primary attacked-server rule:

- Cross-server relevant cards go in a small explicit table.
- Known no-effect economy assets on other servers stay ignored.
- Unknown real Corp prompts still wake.
- If a cardpool audit shows a during-run optional paid ability that does not create a forced prompt, add that card to the wake rule before sleeping past its window.

## Implementation Steps

1. Add pure helpers to `dev/src/clj/ai_run_corp_handlers.clj` or a new `ai_run_corp_decisions.clj` if the namespace starts to sprawl:
   - attacked server key from `[:game-state :run :server]`
   - attacked server ICE/content lookup
   - current checkpoint from run phase/position
   - current approached ICE only, not "any ICE on server"
   - root content on attacked server
   - `corp-run-decision`
   - `present-corp-run-decision`

2. Refactor existing handlers to use the classifier:
   - `handle-corp-rez-decision` should return `:kind :rez-ice` metadata.
   - `handle-corp-fire-decision` should return `:kind :fire-unbroken` metadata.
   - add a server-upgrade/pre-access handler before generic paid-window auto-continue.
   - leave `handle-waiting-for-opponent` and access-trigger real-prompt fallback conservative.
   - keep autonomous policy separate from the classifier; HITL wake output and autonomous choices are different consumers.

3. Improve sleep/wake output:
   - Track summarized events observed while `monitor-run --persistent` slept.
   - On wake, print a short "While you slept" block: rez events, subroutine fires, access start/end, run end, and any card revealed.
   - Avoid dumping noisy "has no further action" entries.

4. Update command help and seat docs:
   - `monitor-run --persistent` is the default Corp defense command.
   - Explain that it wakes for `rez`, `fire-unbroken` if not auto-fired, known upgrade checkpoints, unsupported prompts, run end, timeout, or no-run.
   - Put low-level commands in an escape-hatch paragraph instead of the primary loop.

5. Tests:
   - Pure classifier tests in `dev/test/ai_runs_test.clj` or a new focused test namespace.
   - Cases:
     - no ICE/no content => `:none`
     - rezzed ICE/all subs resolved => `:none`
     - unrezzed ICE at approach => `:rez-ice`
     - two unrezzed ICE => wake for only the current position
     - unrezzed ICE on another server => `:none`
     - unbroken subs after Runner signal => `:fire-unbroken`
     - unbroken subs before Runner signal => wait, not a Corp decision
     - root upgrade on attacked server at pre-access checkpoint => `:server-upgrade`
     - root upgrade already rezzed => `:none` unless a real prompt appears
     - `Manegarm Skunkworks` on another server => `:none`
     - unknown real Corp prompt => `:unsupported-prompt`
     - unaffordable unrezzed ICE/root content still wakes; affordability must not leak or suppress the decision
     - "while you slept" filters no-action spam and includes material events
   - Existing focused regressions should still pass:
     - `lein test ai-runs-test continue-run-rez-test ai-display-test`
     - `bash -n dev/send_command`

## Risk Controls

- Do not suppress a prompt with choices or selectables unless the classifier can prove it is a known ignorable empty window.
- Do not infer hidden facedown card titles from Runner-visible state. This classifier is for the Corp client, where installed Corp card identity is legal for that seat.
- Keep defaults conservative. Missing a possible auto-sleep is acceptable; sleeping through a real decision is not.
- Keep raw/low-level API discoverable for unsupported card behavior and debugging.
- Do not display base rez cost as if it were effective cost; modifiers can change the actual payable amount.

## Open Design Choices

- Whether `fire-unbroken` should stay a Corp wake or become default auto-fire under a clearer Runner `let-subs-fire` handshake. Product preference seems to be auto-fire, but the staged path can keep the current decision until the handshake is trusted.
- Whether to add a single command like `run-decision rez` / `run-decision decline`, or keep using existing `continue --rez` / `continue --no-rez` commands for the first implementation.
- How much event history to retain for "While you slept": likely a cursor/log-count snapshot at monitor start plus a compact filter at wake.
- Exact System Gateway/beginner cardpool audit for optional during-run Corp paid abilities that do not force prompts. This is the gating check before broadening the sleep rule beyond the local attacked-server cases.
