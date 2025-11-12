# Continue-Run TDD Specification

## Goal

Build a `continue-run` that makes running delightful by auto-continuing through meaningless paid ability windows while pausing at all meaningful decisions and unexpected events.

**Core Principle**: Only pause if we need to. When do we need to?
1. A meaningful choice is presented
2. An unexpected event occurs (like any rez, from runner's POV)

## Run Timing Structure Reference

From official Netrunner rules (Rule 6.9):

### 6.9.1: Initiation Phase
- a) Runner declares server
- b) Runner gains bad pub credits
- c) Run formally begins
- d) **Paid ability window** - Corp may rez non-ice
- e) Proceed to approach phase OR movement phase

### 6.9.2: Approach Ice Phase
- a) Approaching ice event
- b) **Paid ability window** - **Corp may rez the approached ice** ⚠️ MUST PAUSE
- c) If ice rezzed, proceed to encounter
- d) Otherwise, proceed to movement

### 6.9.3: Encounter Ice Phase
- a) Encountering ice event
- b) **Paid ability window** - Ice may be interfaced (broken) ⚠️ MAY NEED TO PAUSE
- c) Corp resolves unbroken subs
- d) Encounter complete, proceed to movement

### 6.9.4: Movement Phase
- a) Pass ice event
- b) "Passed all ice" event (if applicable)
- c) **Paid ability window**
- d) Runner may jack out
- e) Proceed to next ice position OR approach server
- f) **Paid ability window** - Corp may rez non-ice
- g) Repeat approach OR continue to success

### 6.9.5: Success Phase
- a) Run successful event
- b) **Breach server** (access cards)
- c) Continue to run ends

### 6.9.6: Run Ends Phase
- a) Close priority windows
- b) Runner loses bad pub credits
- c) Run becomes unsuccessful if not successful
- d) Run ends event

## Pause Decision Matrix

### MUST PAUSE (Meaningful Decisions)

| Situation | Reason | Detection |
|-----------|--------|-----------|
| Corp approaching ICE (unrezzed) | Corp rez decision (ALWAYS pause - info leakage prevention) | `run-phase = :approach-ice` AND corp has prompt AND ICE unrezzed |
| Corp has rez prompt during run | Corp may rez upgrade/asset | Corp prompt with rez choices |
| Runner encountering rezzed ICE | Runner break decision | `run-phase = :encounter-ice` AND runner has choices (break abilities) |
| Runner accessing card | Access decision (steal/trash/nothing) | Prompt type = access AND 2+ choices |
| Runner has 2+ real choices | Any meaningful decision | Runner prompt with 2+ choices (not just "Continue" / "Done") |
| Corp has 2+ real choices during run | Any meaningful decision | Corp prompt with 2+ choices during run |

### SHOULD PAUSE (Unexpected Events - Information Value)

| Event | Reason | Detection |
|-------|--------|-----------|
| ICE rezzed | Show ICE identity and cost | New log entry contains "rez" + ICE name |
| Ability used during run | Show what happened | New log entry contains "uses" or "triggers" |
| Subroutines fired | Show effects | New log entry contains "fire" |
| Tag dealt | Important state change | New log entry contains "tag" |
| Damage dealt | Important state change | New log entry contains "damage" |
| Run redirected | Major change | Run server changed |

### AUTO-CONTINUE (Boring)

| Situation | Reason | Detection |
|-----------|--------|-----------|
| Empty paid ability window | Nothing to do | Prompt type = "run", no choices, no selectable |
| Single mandatory choice | No real decision | Exactly 1 choice AND choice is mandatory (e.g., "Steal") |
| Initiation paid ability (no ice) | Routine window | `run-phase = :initiation` AND no corp rez opportunities |
| Movement paid ability (passed ice) | Routine window between ice | `run-phase = :movement` AND no new events |
| Post-access cleanup | Run finishing | No more prompts, run phase complete |

## Critical Information Leakage Prevention

**Corp MUST pause at unrezzed ICE even if can't afford to rez.**

If we auto-continue when corp can't afford rez, runner learns corp's credit state. This is a huge information leak.

**Solution**: ALWAYS pause when:
- `run-phase = :approach-ice`
- Corp has a prompt (paid ability window)
- There is unrezzed ICE at current position

Corp must explicitly choose to "not rez" (send continue) to avoid leaking "I can't afford this".

## Test Scenarios

### Scenario 1: Simple Run on Empty Server
```
Given: Runner runs Archives
And: Archives has no ICE
When: continue-run is called
Then: Auto-continues through initiation paid ability
And: Auto-continues to success
And: Returns status :run-complete
```

### Scenario 2: Run on Single Unrezzed ICE - Corp Doesn't Rez
```
Given: Corp has Tithe installed on HQ (unrezzed)
And: Corp has 5 credits (can afford to rez)
When: Runner runs HQ
And: continue-run is called
Then: Auto-continues through initiation
And: PAUSES at approach-ice (corp rez decision)
And: Returns status :rez-decision-required
And: Shows "Corp must decide: rez or continue"

When: Corp sends continue (chooses not to rez)
And: continue-run is called again
Then: Auto-continues through movement (ICE not encountered)
And: Auto-continues to success
And: Accesses cards
```

### Scenario 3: Run on Single Unrezzed ICE - Corp Rezzes
```
Given: Corp has Tithe installed on HQ (unrezzed)
When: Runner runs HQ
And: continue-run is called
Then: PAUSES at approach-ice (corp rez decision)

When: Corp rezzes Tithe
Then: New log entry: "Corp rezzes Tithe"
And: continue-run is called again
Then: PAUSES (unexpected event: ICE rezzed)
And: Shows "ICE rezzed: Tithe (cost X)"
And: Returns status :ice-rezzed

When: continue-run is called again
Then: Proceeds to encounter-ice
And: PAUSES (runner decision: break or not)
And: Returns status :encounter-decision
And: Shows runner icebreaker abilities
```

### Scenario 4: Run Through Multiple ICE
```
Given: HQ has Tithe (outer, rezzed) and Enigma (inner, unrezzed)
When: Runner runs HQ
And: continue-run is called
Then: Auto-continues initiation
And: Encounters Tithe (outer ice)
And: PAUSES (runner decision: break subs)

When: Runner breaks all subs
And: continue-run is called
Then: Auto-continues through movement (passed Tithe)
And: Approaches Enigma (inner ice)
And: PAUSES (corp rez decision for Enigma)

When: Corp continues (doesn't rez)
And: continue-run is called
Then: Auto-continues movement (unrezzed ICE not encountered)
And: Auto-continues to success
And: Accesses HQ
```

### Scenario 5: Runner Breaks ICE Partially
```
Given: Runner encountering rezzed Whitespace (2 subs)
And: Runner has Unity (code gate breaker)
When: continue-run is called
Then: PAUSES at encounter (runner decision)

When: Runner uses Unity ability "Break 1 subroutine"
And: Selects sub 0 to break
And: Selects "Done" (let sub 1 fire)
Then: Corp fires unbroken sub 1
And: continue-run is called
Then: PAUSES (unexpected event: subs fired)
And: Shows "Subroutine fired: [effect]"

When: continue-run is called
Then: Auto-continues through movement
And: Continues run
```

### Scenario 6: Access Decisions
```
Given: Runner successfully runs R&D
And: Top card is Hedge Fund (trash cost 3)
When: continue-run is called
Then: Auto-continues to access
And: PAUSES (runner decision: trash or not)
And: Shows "Trash Hedge Fund (3cr)?"
And: Shows choices: ["Trash", "No action"]

When: Runner chooses "No action"
And: Access complete
Then: continue-run returns :run-complete
```

### Scenario 7: Mandatory Steal
```
Given: Runner accesses agenda (no prevent-steal effects)
When: continue-run reaches access
Then: Auto-selects "Steal" (only 1 choice)
And: Shows "Auto-stealing: [agenda name]"
And: Continues run completion
```

### Scenario 8: Upgrade Rez During Run
```
Given: HQ has Hokusai Grid installed (unrezzed)
And: Runner running HQ
When: continue-run at movement phase
And: Corp rezzes Hokusai Grid (surprise)
Then: PAUSES (unexpected event: rez)
And: Shows "Corp rezzed: Hokusai Grid"

When: continue-run is called
Then: Continues run
```

## Architecture: Stateless State Machine

**Key Insight**: `continue-run` cannot use recursion with state in local variables. It must be **stateless** and **resumable**.

### Why Stateless?

When runner waits for corp's rez decision:
1. Runner calls `continue-run`
2. Detects "waiting for opponent"
3. **MUST RETURN** (can't recurse - opponent hasn't acted yet)
4. User waits for opponent action (game diffs arrive via WebSocket)
5. User calls `continue-run` AGAIN
6. Must resume from where we left off

**Solution**: Use game state as source of truth, not local variables.

### State Machine Behavior

Each call to `continue-run` examines CURRENT game state and takes ONE action:

```clojure
(defn continue-run!
  "Stateless run handler - examines current state, takes ONE action, returns.
   Call repeatedly until run completes or decision required.

   Returns:
     {:status :action-taken :action :sent-continue}  - Sent continue, call again
     {:status :waiting-for-opponent :reason ...}      - Paused, wait for opp
     {:status :decision-required :prompt ...}         - Paused, user must decide
     {:status :run-complete :accessed [...]}          - Run finished"
  []
  (let [state @ws/client-state
        side (:side state)
        run-phase (get-in state [:game-state :run :phase])
        my-prompt (get-in state [:game-state (keyword side) :prompt-state])
        opp-prompt (get-in state [:game-state (keyword (other-side side)) :prompt-state])]

    (cond
      ;; 1. Check if waiting for opponent
      (waiting-for-opponent? state side)
      {:status :waiting-for-opponent
       :reason (waiting-reason state side)}

      ;; 2. Check if I have a decision to make
      (has-real-decision? my-prompt)
      {:status :decision-required
       :prompt my-prompt}

      ;; 3. Check for unexpected events (rez, damage, etc)
      (has-unexpected-event? state)
      {:status :event-occurred
       :event (get-latest-event state)}

      ;; 4. Can auto-continue (boring paid ability window)
      (can-auto-continue? my-prompt run-phase)
      (do
        (send-continue!)
        {:status :action-taken :action :sent-continue})

      ;; 5. Run complete
      (run-complete? state)
      {:status :run-complete
       :accessed (extract-accessed-cards state)}

      :else
      {:status :unknown-state})))
```

### Implementation Requirements

### State Detection

```clojure
(defn waiting-for-opponent?
  "True if my side is waiting for opponent to make a decision"
  [state side]
  (let [run-phase (get-in state [:game-state :run :phase])
        my-prompt (get-in state [:game-state (keyword side) :prompt-state])
        opp-side (other-side side)
        opp-prompt (get-in state [:game-state (keyword opp-side) :prompt-state])]

    (cond
      ;; Runner waiting for corp rez decision
      (and (= side "runner")
           (= run-phase :approach-ice)
           (not my-prompt)  ; Runner has no prompt
           opp-prompt)      ; Corp has prompt (rez decision)
      true

      ;; Corp waiting for runner break decision
      (and (= side "corp")
           (= run-phase :encounter-ice)
           (not my-prompt)
           opp-prompt)
      true

      ;; Both sides have prompts but it's opponent's priority
      (and my-prompt opp-prompt
           (opponent-has-priority? state side))
      true

      :else
      false)))

(defn waiting-reason
  "Returns human-readable reason for waiting"
  [state side]
  (let [run-phase (get-in state [:game-state :run :phase])
        opp-side (other-side side)
        current-ice (get-current-ice state)]

    (cond
      (and (= side "runner") (= run-phase :approach-ice))
      (str "Corp must decide: rez " (:title current-ice) " or continue")

      (and (= side "corp") (= run-phase :encounter-ice))
      "Runner must decide: break subroutines or take effects"

      :else
      "Waiting for opponent action")))
```

### Prompt Analysis

```clojure
(defn has-real-choices?
  "True if prompt has 2+ meaningful choices (not just Done/Continue)"
  [prompt]
  (let [choices (:choices prompt)
        non-trivial (remove trivial-choice? choices)]
    (>= (count non-trivial) 2)))

(defn trivial-choice?
  "True if choice is just Continue/Done/OK"
  [choice]
  (let [value (clojure.string/lower-case (:value choice ""))]
    (or (= value "continue")
        (= value "done")
        (= value "ok")
        (= value ""))))
```

### Corp Rez Detection

```clojure
(defn corp-has-rez-opportunity?
  "True if corp is at a rez decision point"
  [state]
  (let [run-phase (get-in state [:game-state :run :phase])
        corp-prompt (get-in state [:game-state :corp :prompt-state])
        current-ice (get-current-ice state)]

    (or
      ;; Approaching unrezzed ICE
      (and (= run-phase :approach-ice)
           current-ice
           (not (:rezzed current-ice))
           corp-prompt)

      ;; Corp has explicit rez choices
      (has-rez-choices? corp-prompt))))

(defn has-rez-choices?
  "True if prompt contains rez options"
  [prompt]
  (let [choices (:choices prompt)]
    (some #(clojure.string/includes? (:value % "") "Rez") choices)))
```

## Edge Cases to Handle

1. **Runner flatlines during run** - Detect damage + empty hand = run ends
2. **Run ends mid-sequence** (ICE sub: "End the run") - Detect run-phase = nil
3. **Jack out** - Runner chooses to jack out (is a real choice, pause)
4. **Can't afford to break** - Runner has no valid break abilities (auto-continue)
5. **Prevent-steal effects** - Agenda access may have 2+ choices
6. **Multiple accesses** - R&D multi-access requires multiple decisions
7. **Nested abilities** - Abil triggers during run may create nested prompts
8. **Corp has no decision** - Sometimes paid ability window with no actual choices

## Success Criteria

The new `continue-run` should:

1. ✅ **Pause at ALL corp rez opportunities** (no info leakage)
2. ✅ **Pause at ALL runner break decisions** (meaningful choice)
3. ✅ **Pause at ALL access decisions** with 2+ options
4. ✅ **Show ICE identity when rezzed** (unexpected event)
5. ✅ **Auto-continue empty paid ability windows** (boring)
6. ✅ **Auto-steal mandatory agendas** (single choice)
7. ✅ **Handle both runner and corp perspectives** (dual-client support)
8. ✅ **Never get stuck** (max iterations, timeout safety)
9. ✅ **Clear user feedback** (show why paused, what options available)
10. ✅ **Make running delightful** (minimal clicks for common cases)

## Test Files to Create

1. `dev/test/continue_run_test.clj` - Core logic tests
2. `dev/test/continue_run_rez_test.clj` - Rez detection tests
3. `dev/test/continue_run_encounter_test.clj` - Encounter/break tests
4. `dev/test/continue_run_access_test.clj` - Access decision tests
5. `dev/test/continue_run_integration_test.clj` - Full run sequences

## Implementation Plan

1. ✅ Create this specification (DONE)
2. ⬜ Fix AI_BUGS.md filename reference (ai_actions.clj not ai_game_actions.clj)
3. ⬜ Write unit tests for pause detection logic
4. ⬜ Write unit tests for rez opportunity detection
5. ⬜ Write unit tests for encounter decisions
6. ⬜ Write integration tests for full run sequences
7. ⬜ Implement new `detect-pause-condition` helper
8. ⬜ Implement new `corp-has-rez-opportunity?` helper
9. ⬜ Refactor `continue-run!` to use pause detection
10. ⬜ Test against known bug scenarios (Bug #12 reproduction)
11. ⬜ Iteration test with real games
12. ⬜ Document behavior in GAME_REFERENCE.md

---

**Last Updated**: 2025-11-11
**Status**: Specification complete, ready for TDD implementation
