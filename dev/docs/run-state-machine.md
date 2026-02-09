# Run State Machine Documentation

This document captures the game state transitions during runs, derived from empirical observation.
Use this as a spec for implementing run automation logic.

## Key State Fields

### Run Object (`[:game-state :run]`)
| Field | Type | Description |
|-------|------|-------------|
| `:phase` | string | Current phase: `"initiation"`, `"movement"`, `"approach-ice"`, `"encounter-ice"`, `"success"`, etc. |
| `:server` | vector | Target server, e.g., `["archives"]` or `["remote" "1"]` |
| `:position` | int | Position in ICE array. `0` = at server, `1`+ = in front of ICE at that index |
| `:no-action` | nil/string/false | Priority state: `nil` (no one passed), `"runner"` (Runner passed), `"corp"` (Corp passed), `false` (reset after phase change) |
| `:cannot-jack-out` | boolean | Whether Runner can jack out |

### Prompts (`[:game-state :side :prompt-state]`)
| Field | Description |
|-------|-------------|
| `:prompt-type` | `"run"` (paid ability window), `"select"` (card selection), `"waiting"`, `"other"` (access), etc. |
| `:msg` | Human-readable message |
| `:choices` | Vector of choice buttons `[{:value "Steal" :uuid "..."}]` |
| `:selectable` | Card IDs that can be selected |

### Priority Windows
Each side has a paid ability window at most phase boundaries:
- **Runner prompt**: `type="run"` msg="You are running on X"
- **Corp prompt**: `type="run"` msg="The Runner is running on X"
- When `:choices` is empty, it's just a priority pass point (no paid abilities available)

---

## Client Isolation
**Important**: Each client only sees their own prompts clearly. Runner may see Corp's prompt as `nil` even when Corp has an active paid ability window. Always check from the relevant client's perspective.

---

## Scenario 1: Empty Server (Archives, no ICE)

### Phase Sequence (Single-Stepped)

| Step | Runner Action | Phase | :no-action | Notes |
|------|--------------|-------|------------|-------|
| 0 | `run Archives` | `"initiation"` | `nil` | Click spent, run begins |
| 1 | Runner continues | `"initiation"` | `"runner"` | Runner passed priority |
| 2 | Corp continues | `"movement"` | `false` | Both passed, phase advances, :no-action resets |
| 3 | Runner continues | `"movement"` | `"runner"` | Runner passed |
| 4 | Corp continues | `"success"` | `false` | Access phase, Runner sees access prompts |
| 5 | Runner handles access | run = nil | - | Run complete |

### State Snapshots

**After `run Archives --no-continue`:**
```clojure
{:phase "initiation"
 :server ["archives"]
 :position 0
 :no-action nil}
;; Runner prompt: type="run" msg="You are running on Archives" choices=[]
;; Corp prompt: nil (from Runner's view - see Client Isolation)
```

**After Runner passes (continue):**
```clojure
{:phase "initiation"
 :no-action "runner"}  ; <-- Runner passed priority
;; Corp now has: type="run" msg="The Runner is running on Archives"
```

**After Corp passes:**
```clojure
{:phase "movement"     ; <-- Phase advanced!
 :no-action false}     ; <-- Reset after phase change
```

**After both pass in movement:**
```clojure
{:phase "success"}
;; Runner prompt: type="other" msg="You accessed Superconducting Hub." choices=["Steal"]
```

**After access handled:**
```clojure
{:run nil}  ; Run complete
```

### Log Messages
```
1. "spends [Click] to make a run on Archives"  -> initiation starts
2. "will continue the run"                      -> Runner passes in initiation
3. "approaches Archives"                        -> movement/approach-server
4. "breaches Archives"                          -> breach starts
5. "accesses X from Archives"                   -> access
6. "steals X and gains N agenda point"          -> access resolution
```

### State Machine Diagram
```
IDLE ──click──> INITIATION
                    │
              [both pass]
                    ▼
                MOVEMENT (position=0, no ICE)
                    │
              [both pass]
                    ▼
                SUCCESS / BREACH
                    │
              [access resolved]
                    ▼
                  IDLE (run=nil)
```

---

## Priority Model

### How `:no-action` Works
1. Phase starts with `:no-action nil`
2. When Runner continues: `:no-action "runner"`
3. When Corp continues: phase advances, `:no-action false` (reset)
4. Repeat for next phase

### Detecting "Waiting for Opponent"
From Runner's perspective:
- `:no-action "runner"` means "I passed, waiting for Corp"

From Corp's perspective:
- `:no-action nil` or `:no-action "runner"` with Corp having a prompt = Corp should act

### Empty Paid Ability Windows
When `:prompt-type "run"` but `:choices []`:
- This is just a priority pass point
- Safe to auto-continue if no paid abilities available

---

## Key Insights

1. **Phase advances when BOTH pass** - Not when one passes
2. **`:no-action` resets to `false` on phase change** - Fresh priority window
3. **`nil` vs `false`**: `nil` = fresh phase start, `false` = after both passed (may appear briefly)
4. **Client isolation**: Can't always see opponent's prompts from your client
5. **Empty server skips ICE phases**: Goes `initiation -> movement -> success`

---

## Scenario 2: 1 Unrezzed ICE, Corp Declines Rez

Setup: Whitespace (Code Gate, rez cost 2) installed on Archives, unrezzed.

### Phase Sequence (Single-Stepped)

| Step | Action | Phase | Position | :no-action | Notes |
|------|--------|-------|----------|------------|-------|
| 0 | `run Archives` | `"initiation"` | 1 | `nil` | Position=1 (in front of 1 ICE) |
| 1 | Runner continues | `"initiation"` | 1 | `"runner"` | |
| 2 | Corp continues | `"approach-ice"` | 1 | `false` | NEW PHASE! |
| 3 | Runner continues | `"approach-ice"` | 1 | `"runner"` | |
| 4 | Corp continues (declines rez) | `"movement"` | 0 | `false` | Skips encounter-ice! |
| 5 | Runner continues | `"movement"` | 0 | `"runner"` | |
| 6 | Corp continues | `"success"` | 0 | `false` | Access phase |

### Key Observations

**Position tracking:**
- Position = 1 means "in front of ICE at index 0" (outermost)
- Position = 0 means "at the server" (past all ICE)

**Approach-ice phase:**
```clojure
{:phase "approach-ice"
 :position 1
 :no-action false}
```
- Corp has `prompt-type="run"` with `choices=[]` (just priority window)
- Rez opportunity is available as an ACTION, not a choice button
- To rez: use `rez-card!` command
- To decline: just pass (continue)

**ICE state during approach:**
```clojure
;; Get ICE being approached
(let [run (get-in gs [:game-state :run])
      server (last (:server run))
      ice-list (get-in gs [:game-state :corp :servers (keyword server) :ices])
      position (:position run)
      ice-index (- (count ice-list) position)]  ; Convert position to array index
  (nth ice-list ice-index))

;; Result:
{:title "Whitespace"
 :rezzed nil        ; nil = unrezzed
 :cost 2}           ; rez cost
```

**Skip encounter-ice when unrezzed:**
- When Corp declines rez (passes in approach-ice)
- Phase goes: `approach-ice` → `movement` (skipping `encounter-ice`)
- Position decrements: 1 → 0

### Log Messages
```
1. "spends [Click] to make a run on Archives"
2. "approaches ice protecting Archives at position 0"    <- approach-ice
3. "passes ice protecting Archives at position 0"        <- declined rez
4. "will continue the run"
5. "approaches Archives"                                 <- movement
6. "breaches Archives"
7. "accesses X from Archives"
```

### State Machine Diagram
```
IDLE ──click──> INITIATION (position=1)
                    │
              [both pass]
                    ▼
           APPROACH-ICE (position=1)
                    │
       ┌────────────┴────────────┐
       │                         │
   [Corp rezzes]           [Corp declines]
       │                         │
       ▼                         ▼
 ENCOUNTER-ICE            MOVEMENT (position=0)
       │                         │
     (etc)                 [both pass]
                                 ▼
                            SUCCESS
```

---

## Scenario 3: 1 ICE, Corp Rezzes, Runner Breaks

Setup: Whitespace (Code Gate, rez 2, str 0) on Archives, Mayfly (AI, str 1) installed.

### Phase Sequence

| Step | Action | Phase | Position | Notes |
|------|--------|-------|----------|-------|
| 0 | `run Archives` | `"initiation"` | 1 | |
| 1-2 | Both pass | `"approach-ice"` | 1 | |
| 3 | Runner passes | `"approach-ice"` | 1 | no-action: runner |
| 4 | **Corp rezzes** | `"approach-ice"` | 1 | Rez is an ACTION, not end of priority |
| 5 | Corp passes | `"encounter-ice"` | 1 | **NEW PHASE!** |
| 6 | **Runner breaks** | `"encounter-ice"` | 1 | Uses icebreaker ability |
| 7 | Runner passes | `"encounter-ice"` | 1 | no-action: runner |
| 8 | Corp passes | `"movement"` | 0 | Position decrements |
| 9-10 | Both pass | `"success"` | 0 | Access phase |

### Key Observations

**Encounter-ICE phase structure:**
```clojure
{:phase "encounter-ice"
 :position 1}
```

**ICE state during encounter:**
```clojure
{:title "Whitespace"
 :rezzed this-turn  ; or true if rezzed earlier
 :current-strength 0
 :subroutines [{:label "Make the Runner lose 3 [Credits]"
                :broken nil  ; nil = unbroken
                :fired nil}
               {:label "End the run if..."
                :broken nil
                :fired nil}]}
```

**After breaking:**
```clojure
{:subroutines [{:broken true :fired nil}
               {:broken true :fired nil}]}
```

**Icebreaker abilities during encounter:**
```clojure
;; Mayfly abilities:
{:abilities [{:label "Break 1 subroutine" :playable true}
             {:label "Add 1 strength" :playable true}
             {:label "Fully break Whitespace"  ; Dynamic ability
              :playable null       ; <-- IMPORTANT: Dynamic abilities have :playable null, NOT true!
              :dynamic "auto-pump-and-break"}]}
```

**⚠️ Dynamic Ability Gotcha:**
Dynamic abilities (`:dynamic "auto-pump-and-break"`) have `:playable null` (or missing), NOT `:playable true`.
The server generates these on-the-fly based on ICE being encountered. Don't filter by `:playable true`
when looking for break abilities - check for `:dynamic` containing `"break"` instead.

### Breaking Mechanics

1. Runner must have **strength >= ICE strength** to use break abilities
2. `:dynamic "auto-pump-and-break"` = convenience ability that handles both
3. Breaking sets `:broken true` on subroutines
4. Broken subs don't fire (Corp can't fire them)

### Priority During Encounter

Unlike approach-ice, encounter-ice has a different priority dynamic:
- Runner can pass multiple times (log shows "has no further action" 3x)
- Phase advances when CORP passes
- `:no-action` may reset each time Runner passes in encounter (needs verification)

### Log Messages
```
1. "approaches ice protecting Archives at position 0"
2. "pays 2 [Credits] to rez Whitespace"               <- rez action
3. "encounters Whitespace protecting Archives"         <- encounter-ice starts
4. "uses Mayfly to break all 2 subroutines"           <- break action
5. "has no further action" (may repeat)               <- Runner passes
6. "passes Whitespace protecting Archives"             <- encounter ends
7. "will continue the run"                            <- movement
8. "approaches Archives"
9. "breaches Archives"
```

### State Machine Diagram
```
           APPROACH-ICE (position=1)
                    │
       ┌────────────┴────────────┐
       │                         │
   [Corp rezzes]           [Corp declines]
       │                         │
       ▼                         ▼
 ENCOUNTER-ICE              MOVEMENT (skip)
       │
   [Runner breaks or lets subs fire]
   [Both pass]
       │
       ▼
   MOVEMENT (position=0)
       │
   [both pass]
       ▼
   SUCCESS
```

---

## Position Mechanics (Multi-ICE Summary)

For servers with N ICE:
- **Position = N** at run start (outermost ICE first)
- **Position decrements** as Runner passes each ICE
- **Position = 0** when at the server (past all ICE)

### ICE Array Indexing
```clojure
;; ICE are stored innermost-first in the array:
;; [:ices] = [innermost, ..., outermost]
;;
;; But position counts from outermost:
;; position 2 = in front of outermost (if 2 ICE)
;; position 1 = in front of innermost (if 2 ICE)
;; position 0 = at server

;; Convert position to array index:
(let [ice-count (count ice-list)
      ice-index (- ice-count position)]
  (nth ice-list ice-index))
```

### 2 ICE Example (Scenario 4)
```
Server with 2 ICE: [Inner, Outer]

Run starts:    position=2 (outermost)
Pass Outer:    position=1 (innermost)
Pass Inner:    position=0 (at server)
```

---

## Complete Run State Machine

```
                         IDLE
                           │
                     [click spent]
                           ▼
                     INITIATION
                           │
                     [both pass]
                           ▼
              ┌────── position > 0? ──────┐
              │            │              │
           yes│            │no            │
              ▼            │              │
        APPROACH-ICE       │              │
              │            │              │
    ┌─────────┴─────────┐  │              │
    │                   │  │              │
[rez + pass]       [pass]  │              │
    │                   │  │              │
    ▼                   │  │              │
ENCOUNTER-ICE           │  │              │
    │                   │  │              │
[break/fire + pass]     │  │              │
    │                   │  │              │
    ├───────────────────┘  │              │
    │                      │              │
    ▼                      │              │
  MOVEMENT ◄───────────────┘              │
  (position--)                            │
    │                                     │
    └── position > 0? ──yes──> APPROACH-ICE (next ICE)
                  │
                  no
                  │
                  ▼
              SUCCESS / BREACH
                  │
            [access resolved]
                  │
                  ▼
                IDLE (run=nil)
```

---

## Key API Functions (from ai-runs.clj)

### Checking ICE State
```clojure
(get-current-ice state)  ; Returns ICE being approached/encountered
```

### Checking Priority
```clojure
(waiting-for-opponent? state side)  ; Are we waiting for opp?
(can-auto-continue? prompt phase side state)  ; Safe to auto-pass?
(corp-has-rez-opportunity? state)  ; Corp at rez decision?
```

### Run Control
```clojure
(run! "server" "--no-continue")  ; Start without auto-continue
(continue-run!)  ; Take one action based on state
(auto-continue-loop!)  ; Loop until decision needed
```

---

## Handler Chain Priority Order

The `continue-run!` function uses a handler chain pattern. Each handler examines the context
and either returns nil (not handled, try next) or returns a result map (stop chain).

**Handler Priority (from ai_runs.clj):**

| Priority | Handler | Side | Description |
|----------|---------|------|-------------|
| 0 | `handle-force-mode` | Both | `--force` bypasses ALL checks |
| 1 | `handle-opponent-wait` | Both | Opponent pressed WAIT button |
| 1.5 | `handle-corp-rez-strategy` | Corp | Auto-handle rez with `--no-rez`/`--rez` |
| 1.6 | `handle-corp-fire-unbroken` | Corp | Auto-fire subs with `--fire-unbroken` |
| 1.7 | `handle-corp-rez-decision` | Corp | Pause for manual rez decision |
| 1.7 | `handle-corp-fire-decision` | Corp | Pause for manual fire decision |
| 1.74 | `handle-corp-all-subs-resolved` | Corp | All subs broken/fired, continue |
| 1.75 | `handle-corp-waiting-after-subs-fired` | Corp | Wait for Runner after subs resolve |
| 1.8 | `handle-paid-ability-window` | Both | General priority passing |
| 2 | `handle-runner-approach-ice` | Runner | Wait for Corp rez decision |
| 2.3 | `handle-runner-tactics` | Runner | Tactics system (if configured) |
| 2.4 | `handle-runner-full-break` | Runner | Auto-break with `--full-break` |
| 2.5 | `handle-runner-encounter-ice` | Runner | Wait for Corp fire decision |
| 2.6 | `handle-runner-pass-broken-ice` | Runner | All subs broken, continue |
| 2.7 | `handle-runner-pass-fired-ice` | Runner | Subs fired, continue |
| 3 | `handle-waiting-for-opponent` | Both | Generic opponent wait |
| 3 | `handle-real-decision` | Both | I have a real choice to make |
| 4 | `handle-events` | Both | Pause for rez/ability/sub events |
| 5 | `handle-access-display` | Runner | Show access info (returns nil) |
| 5 | `handle-auto-choice` | Both | Auto-click single mandatory choice |
| 5.5 | `handle-recently-passed-in-log` | Both | Backup wait detection via log |
| 6 | `handle-auto-continue` | Both | Auto-pass empty paid ability windows |
| 7 | `handle-run-complete` | Both | Run finished |
| 8 | `handle-no-run` | Both | No active run |

---

## Run Strategy Flags

Flags can be passed to `run!`, `continue-run!`, or `monitor-run!` to automate decisions.

### Runner Flags

| Flag | Description |
|------|-------------|
| `--full-break` | Auto-break all ICE. Tries first affordable break ability. If unaffordable, signals Corp to fire subs. |
| `--tank <ice>` | Pre-authorize letting subs fire on specific ICE (can repeat) |
| `--tank-all` | Pre-authorize letting subs fire on ALL ICE (yolo mode) |
| `--no-continue` | Don't auto-continue after run starts (stop at first decision) |

### Corp Flags

| Flag | Description |
|------|-------------|
| `--no-rez` | Auto-decline all rez opportunities |
| `--rez <ice>` | Only rez specified ICE, decline others (can repeat) |
| `--fire-unbroken` | Auto-fire unbroken subs when Runner signals done breaking |
| `--fire-if-asked` | Wait silently while Runner breaks, auto-fire when signaled, wake only for rez decisions |

### Both Sides

| Flag | Description |
|------|-------------|
| `--force` | Bypass ALL smart checks, just send continue. **AI-vs-AI only!** |
| `--since <cursor>` | Fast-return if state has advanced past cursor |

### Combined Usage Examples

```bash
# Runner: auto-break everything, tank if can't afford
./dev/send_command runner run "HQ" --full-break

# Corp: rez Tithe only, auto-fire when Runner signals
./dev/send_command corp monitor-run --rez "Tithe" --fire-unbroken

# Corp: sleep until run ends, handling rez/fire automatically
./dev/send_command corp monitor-run --fire-if-asked --since 892
```

---

## Runner Signaling Pattern (Encounter-ICE)

During encounter-ice, Runner and Corp need to coordinate:

1. **Runner breaks or decides not to break**
2. **Runner signals "done breaking"** via system message:
   `"indicates to fire all unbroken subroutines on <ICE>"`
3. **Corp sees signal** and can fire subs or pass
4. **Both pass** to move to next phase

### Detection Functions

```clojure
;; Corp detects Runner signal:
(runner-signaled-let-fire? state ice-title)
;; Looks for "indicates to fire" + ice-title in recent log

;; Runner sends signal (internal):
(let-subs-fire-signal! gameid ice-title)
;; Sends system-msg with the signal pattern
```

### Auto-Continue Exception: Unrezzed ICE

Corp should NOT auto-continue during `approach-ice` with unrezzed ICE:
- Rez decisions are too important to auto-pass
- The `can-auto-continue?` function explicitly checks for this
- Use `--no-rez` to explicitly decline, or `--rez <ice>` to specify

---

## Notes for AI Implementation

1. **Always check `:phase`** before taking actions
2. **Use `:no-action`** to detect "waiting for opponent"
3. **Rez/break are ACTIONS**, not priority ends - still need to pass after
4. **Phase only advances when BOTH pass**
5. **Unrezzed ICE = skip encounter-ice**
6. **`:run nil` = run complete**

---

## TODO: Future Work
- [ ] Scenario 4: 2 ICE, mixed rez decisions (follows same pattern, just 2 approach-ice cycles)
- [ ] Scenario 5: Runner lets subs fire (Corp uses fire-unbroken-subs)
- [ ] Scenario 6: Jack out during run
- [ ] Document access phase in more detail (steal vs trash vs other options)
