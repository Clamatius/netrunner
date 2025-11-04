# AI Cockpit Development - Iteration Plan

## Vision
Build a comprehensive open-hand game test in `game_command_test.clj` that exercises all basic and intermediate Netrunner mechanics. This creates a "cockpit" demonstrating how to programmatically control every lever in the game.

**Key principle**: Each iteration adds ONE new mechanic, building on previous work.

**Target timeline**: 15-25 coding sessions to complete all phases.

---

## Phase 1: Foundation ✅ COMPLETE

- [x] Basic turn flow (credit, draw, end turn)
- [x] Playing economy operations (Hedge Fund)
- [x] Installing cards (ICE on servers, programs/hardware/resources)

**Completed in**: Initial session
**Test functions**: `test-basic-turn-flow`, `test-playing-cards`

---

## Phase 2: Corp Economic & Board Development

### Iteration 2.1: Asset Installation & Rezzing
**Status**: 🔜 NEXT UP
**New mechanics**: Installing assets to remotes, rezzing assets, using card abilities
**Test function**: `test-asset-management`

**Specific actions**:
- Install Nico Campaign to new remote
- Rez Nico Campaign (pay rez cost)
- Use card abilities (Nico's credit-generation ability via `card-ability`)
- Verify credit gain from asset abilities

**Cards needed**: Nico Campaign, Regolith Mining License

**Implementation notes**:
- Need to find installed card: `(get-content state :remote1 0)`
- Rez action: `(rez state :corp card)`
- Ability trigger: `(card-ability state :corp card 0)` (0-indexed)
- Check credits before/after to verify

**Expected challenges**:
- Understanding which ability index to use
- Prompt responses after ability triggers

---

### Iteration 2.2: Agenda Management
**Status**: ⏸️ PENDING
**New mechanics**: Advancing cards, scoring agendas
**Test function**: `test-agenda-scoring`

**Specific actions**:
- Install agenda (Offworld Office) in remote
- Advance agenda multiple times (3+ advances) using `advance` helper
- Score agenda when advancement requirement met
- Verify agenda points awarded to Corp

**Cards needed**: Offworld Office (3 advancement, 2 points)

**Implementation notes**:
- Advance: `(advance state card)` or `(core/process-action "advance" state :corp {:card card})`
- Check advancement counters: `(get-counters card :advancement)`
- Score: `(score-agenda state :corp card)`
- Verify: `(count (:scored (get-corp)))` and `(:agenda-point (get-corp))`

---

### Iteration 2.3: ICE Management
**Status**: ⏸️ PENDING
**New mechanics**: Installing ICE, multiple ICE on same server
**Test function**: `test-ice-installation`

**Specific actions**:
- Install ICE on HQ (Palisade)
- Install ICE on R&D (Brân 1.0)
- Install multiple ICE on same remote (nested protection - outermost to innermost)
- Verify board state shows correct ICE positions

**Cards needed**: Palisade, Brân 1.0, Tithe

**Implementation notes**:
- Install: `(play-from-hand state :corp "Palisade" "HQ")`
- ICE installs at innermost position by default
- Multiple ICE on same server: each install adds to innermost
- Check positions: `(get-ice state :hq)` returns vector [outer ... inner]

---

## Phase 3: Runner Rig Building

### Iteration 3.1: Program Installation
**Status**: ⏸️ PENDING
**New mechanics**: Installing programs (breakers), memory tracking
**Test function**: `test-program-installation`

**Specific actions**:
- Install breakers: Carmen, Cleaver, Unity (each costs 1 MU)
- Verify memory usage (should not exceed 4 MU base)
- Install hardware (Docklands Pass - provides extra MU)
- Verify memory available increases

**Cards needed**: Carmen, Cleaver, Unity, Docklands Pass

**Implementation notes**:
- Check MU: `(get-in @state [:runner :memory :available])`
- Programs go to rig: `(get-program state)` returns all installed programs
- Each breaker has `:memoryunits` field (usually 1)

---

### Iteration 3.2: Resource Management
**Status**: ⏸️ PENDING
**New mechanics**: Installing resources, using resource abilities
**Test function**: `test-resource-management`

**Specific actions**:
- Install Smartware Distributor
- Use click ability to place credits on card
- Use start-of-turn ability to gain credits
- Install multiple economy resources

**Cards needed**: Smartware Distributor, Telework Contract

**Implementation notes**:
- Resources go to rig: `(get-resource state)`
- Abilities: `(card-ability state :runner card ability-idx)`
- Smartware has multiple abilities (check which index)
- Some abilities are automatic (start-of-turn)

---

### Iteration 3.3: Event Economy
**Status**: ⏸️ PENDING
**New mechanics**: Playing run events with benefits
**Test function**: `test-runner-events`

**Specific actions**:
- Play Sure Gamble (costs 5, gain 9)
- Play Creative Commission (draw 2, gain 2)
- Play Overclock (gain click for runs)
- Verify credit/card changes

**Cards needed**: Sure Gamble, Creative Commission, Overclock

**Implementation notes**:
- Events: `(play-from-hand state :runner "Sure Gamble")`
- Events go to discard after playing
- Check hand/credit before and after

---

## Phase 4: Basic Runs ⚠️ COMPLEXITY INCREASE

### Iteration 4.1: Unopposed Central Server Runs
**Status**: ⏸️ PENDING
**New mechanics**: Running, accessing cards, "continue" timing
**Test function**: `test-unopposed-central-runs`

**Specific actions**:
- Run on HQ (no ICE) - access random cards from HQ
- Respond to access prompts (steal agenda / don't steal)
- Run on R&D - access from top of deck
- Run on Archives - access all cards in Archives

**Cards needed**: Just need Corp to have cards in HQ/R&D/Archives

**Implementation notes**:
- Use test helper: `(run-empty-server state :hq)` handles timing
- Or manual: `(run-on state :hq)` + respond to prompts
- Access prompts will appear for each card
- Stealing agenda: `(click-prompt state :runner "Steal")`

**Focus**: Understanding run phases WITHOUT ICE:
1. Run initiation
2. Approach server (no ICE to encounter)
3. Access cards
4. Run ends

---

### Iteration 4.2: Unopposed Remote Runs
**Status**: ⏸️ PENDING
**New mechanics**: Running remotes, accessing specific cards
**Test function**: `test-remote-access`

**Specific actions**:
- Corp installs agenda in Remote 1 (unrezzed)
- Runner runs Remote 1
- Access unrezzed agenda
- Steal agenda from remote
- Verify agenda points awarded to Runner

**Implementation notes**:
- Remote servers: `:remote1`, `:remote2`, etc.
- Accessing remote shows all cards in that remote
- Steal: `(click-prompt state :runner "Steal")`
- Check: `(:agenda-point (get-runner))`

---

### Iteration 4.3: Running Through Unrezzed ICE
**Status**: ⏸️ PENDING
**New mechanics**: Passing unrezzed ICE, corp rez decisions
**Test function**: `test-unrezzed-ice-passing`

**Specific actions**:
- Corp installs ICE on HQ (DON'T rez yet)
- Runner runs HQ
- Approach ICE phase
- Corp chooses not to rez (pass priority)
- Runner continues past unrezzed ICE
- Access HQ

**Implementation notes**:
- Install but don't rez: `(play-from-hand state :corp "Palisade" "HQ")`
- During run: Corp gets rez window
- Corp passes: `(core/process-action "continue" state :corp nil)`
- Runner continues: `(core/process-action "continue" state :runner nil)`

**Focus**: Approach-ice timing, corp rez windows, priority passing

---

## Phase 5: Encounters With ICE ⚠️ CRITICAL COMPLEXITY

### Iteration 5.1: Basic ICE Encounter (No Breaking)
**Status**: ⏸️ PENDING
**New mechanics**: ICE rezzing, subroutine firing, encounter phases
**Test function**: `test-ice-encounter-basic`

**Specific actions**:
- Corp installs + rezzes Palisade on HQ
- Runner runs HQ
- Encounter Palisade
- Runner does NOT break subs
- Subroutines fire ("End the run")
- Run ends unsuccessfully

**Key run phases**:
1. Run initiated: `(run-on state :hq)`
2. Corp priority at initiation → `(continue state :corp)`
3. Runner continues → `(continue state :runner)`
4. Approach ICE (Palisade)
5. Corp rezzes ICE → `(rez state :corp ice)`
6. Corp priority → `(continue state :corp)`
7. Encounter ICE
8. Runner passes (doesn't use breaker) → `(continue state :runner)`
9. Fire unbroken subroutines
10. Run ends

**Implementation notes**:
- Palisade has 1 subroutine: "End the run"
- When fired, run terminates immediately
- No access happens
- Check: `(get-run)` should be nil after run ends

---

### Iteration 5.2: Breaking Barrier ICE
**Status**: ⏸️ PENDING
**New mechanics**: Using breaker abilities, breaking subroutines
**Test function**: `test-breaking-barrier`

**Specific actions**:
- Corp rezzes Palisade (Barrier, strength 2, "End the run")
- Runner has Cleaver installed (Fracter, strength 3)
- Runner encounters Palisade
- Runner uses Cleaver to break subroutines
- Runner continues to HQ
- Access cards

**Cards needed**: Palisade, Cleaver

**Implementation notes**:
- During encounter: Runner can use breaker abilities
- Break ability: `(card-ability state :runner cleaver 0)` (ability index 0)
- Cleaver breaks "up to 2 Barrier subroutines" per use
- Must match type: Fracter breaks Barrier
- After breaking all subs, continue to access

**Focus**: Breaker type matching (Fracter ↔ Barrier)

---

### Iteration 5.3: Breaking Code Gate ICE
**Status**: ⏸️ PENDING
**New mechanics**: Breaking different ICE types
**Test function**: `test-breaking-code-gate`

**Specific actions**:
- Corp rezzes Brân 1.0 (Code Gate)
- Runner has Unity installed (Decoder)
- Runner breaks subroutines
- Successful run to server

**Cards needed**: Brân 1.0, Unity

**Focus**: Decoder breaks Code Gate

---

### Iteration 5.4: AI Breaker (Breaks Any Type)
**Status**: ⏸️ PENDING
**New mechanics**: AI breakers that break any ICE type
**Test function**: `test-ai-breaker`

**Specific actions**:
- Install Mayfly (AI breaker - breaks any type)
- Break Barrier ICE with Mayfly
- Break Code Gate ICE with Mayfly
- Verify Mayfly works on all types

**Cards needed**: Mayfly, Palisade, Brân 1.0

**Implementation notes**:
- AI breakers have "AI" subtype
- Can break any ICE type (Barrier, Code Gate, Sentry)
- Usually more expensive or have restrictions
- Mayfly: strength 1, breaks 1 sub at a time

**Focus**: Demonstrating breaker flexibility

---

### Iteration 5.5: Strength Pumping & Breaking
**Status**: ⏸️ PENDING
**New mechanics**: Pumping breaker strength, cost calculation
**Test function**: `test-strength-pumping`

**Specific actions**:
- Corp rezzes high-strength ICE (Whitespace - strength 3+)
- Runner has low-strength breaker (Carmen - strength 2)
- Runner pumps Carmen's strength (ability to add +1 strength)
- Runner breaks subroutines (now strong enough)
- Verify credits spent correctly

**Cards needed**: Whitespace, Carmen

**Implementation notes**:
- Breakers usually have 2 abilities: break subs, pump strength
- Pump ability: often ability index 1
- Strength pump usually temporary (for current encounter)
- Carmen: +1 strength for 2 credits
- Check credits before/after pumping

**Focus**: Dynamic strength adjustment during encounters

---

## Phase 6: Advanced Run Mechanics

### Iteration 6.1: Jack Out
**Status**: ⏸️ PENDING
**New mechanics**: Voluntary run termination
**Test function**: `test-jack-out`

**Specific actions**:
- Runner starts run on protected server
- Runner decides to jack out during approach ICE
- Run ends (no access, no consequences)

**Implementation notes**:
- Jack out: `(core/process-action "jack-out" state :runner nil)`
- Can only jack out at certain timing windows
- Run ends immediately, no access

---

### Iteration 6.2: Multi-ICE Server
**Status**: ⏸️ PENDING
**New mechanics**: Breaking through layers of ICE
**Test function**: `test-layered-ice`

**Specific actions**:
- Corp installs 3 ICE on HQ (position matters: outermost to innermost)
- Runner encounters and breaks all three in order
- Successful HQ access after breaking through all layers

**Implementation notes**:
- Encounter order: outermost first, then inner layers
- Must break or jack out at each layer
- Each encounter is separate (pump strength again if needed)

**Focus**: ICE ordering, position tracking

---

### Iteration 6.3: Run Events During Runs
**Status**: ⏸️ PENDING
**New mechanics**: Playing events mid-run for bonuses
**Test function**: `test-run-events`

**Specific actions**:
- Play Jailbreak (make run on R&D, draw card)
- Play Tread Lightly (bypass ICE effect)
- Verify event effects apply during run

**Cards needed**: Jailbreak, Tread Lightly

---

## Phase 7: Complex Game States

### Iteration 7.1: Full Game Flow
**Status**: ⏸️ PENDING
**Test function**: `test-full-game-flow`

**Actions**: Play complete game from start to Corp winning (scoring 7 points)
- Multiple turns back and forth
- Building board state incrementally
- Making runs on various servers
- Scoring multiple agendas
- Game ends when Corp reaches 7 agenda points

---

### Iteration 7.2: Prompt Handling Showcase
**Status**: ⏸️ PENDING
**Test function**: `test-prompt-responses`

**Actions**: Exercise all prompt types
- Choice prompts (text selections)
- Card selection prompts (click-card)
- Numeric prompts (credit payment for traces, etc.)
- Priority passing (continue prompts)

---

### Iteration 7.3: Tag & Removal
**Status**: ⏸️ PENDING
**New mechanics**: Getting tagged, removing tags
**Test function**: `test-tags`

**Specific actions**:
- Corp plays operation that gives tags (Send a Message)
- Runner receives tags
- Runner spends click + 2 credits to remove tag
- Verify tag count decreases

**Implementation notes**:
- Check tags: `(get-in @state [:runner :tag :total])`
- Remove tag: `(core/process-action "remove-tag" state :runner nil)`
- Costs 1 click + 2 credits per tag removed

---

## Progress Tracking

### Completed: 2 iterations (Phase 1)
### Current: Ready to start Phase 2, Iteration 2.1
### Remaining: ~18-23 iterations

---

## Success Criteria

By end of this plan, we should have:

✅ **Comprehensive test suite** in `game_command_test.clj` with 20+ test functions
✅ **All basic mechanics exercised**: turns, economy, installing, rezzing, running, breaking
✅ **Clear examples** of how to call each action from AI code
✅ **Debugged and working** - all tests pass consistently
✅ **Documentation** in comments showing why each action is called

---

## Development Strategy

1. **One iteration at a time** - Each session focuses on ONE iteration from this plan
2. **Build incrementally** - Later iterations reuse helper functions from earlier ones
3. **Test immediately** - Run `(test-FUNCTION-NAME)` after writing each one
4. **Debug in REPL** - Use `(def my-state ...)` to pause and inspect game state
5. **Commit after each working iteration** - Keep git history clean with descriptive messages

---

## File Organization

All tests live in `dev/src/clj/game_command_test.clj`:
- Helper functions at top (open-hand-game, print-game-state, etc.)
- Group related tests together by phase
- Add section comments `;;; Phase X: Description` for clarity
- Update comment block at bottom with new test function names

---

## Common Patterns

### Test Function Template
```clojure
(defn test-MECHANIC-NAME
  "Brief description of what mechanic is being tested"
  []
  (println "\n========================================")
  (println "TEST: Mechanic Name")
  (println "========================================")

  (let [state (custom-open-hand-game
                ;; Carefully curated hands to demonstrate mechanic
                ["Corp Card 1" "Corp Card 2"]
                (drop 2 gateway-beginner-corp-deck)
                ["Runner Card 1" "Runner Card 2"]
                (drop 2 gateway-beginner-runner-deck))]

    (println "\n--- Setup ---")
    (print-game-state state)
    (print-board-state state)

    ;; Series of actions with explanatory prints
    (println "\n--- Action: Description ---")
    (some-action state)
    (print-game-state state)  ; Show result after action

    ;; More actions with clear steps...

    (println "\n✅ Test complete!")
    nil))  ; Return nil to avoid console spam
```

### Debugging Tips
```clojure
;; Pause execution and inspect
(def my-state (open-hand-game))
(print-game-state my-state)

;; Find cards
(find-card "Palisade" (:hand (get-corp)))
(get-ice my-state :hq 0)  ; Get first ICE on HQ

;; Check prompts
(get-in @my-state [:corp :prompt])
(get-in @my-state [:runner :prompt])

;; Inspect run state
(:run @my-state)
```

---

## Notes & Learnings

### Timing Windows
- Many actions create priority windows where both sides can act
- Use `(continue state side)` to pass priority
- Prompt responses are mandatory (must click-prompt or click-card)

### State Mutations
- The `state` atom is mutable - changes persist
- Actions modify state directly
- Always check state after actions to verify changes

### Card References
- Cards move between zones (hand → board → discard)
- Must re-get card reference after moves: `(get-card state card)`
- Use `find-card` for lookups by title

### Common Pitfalls
- Forgetting to handle prompts causes hangs
- Not checking if action succeeded (verify state changes)
- Assuming card positions (always check with helpers)
- Not accounting for costs (clicks, credits)

---

## Iteration Velocity Estimates

- **Simple iterations** (2.1-2.3, 3.1-3.3, 4.1-4.2): ~30-60 minutes each
- **Medium complexity** (4.3, 5.1-5.3): ~60-90 minutes each
- **High complexity** (5.4-5.5, 6.2, 7.1): ~90-120 minutes each
- **Total estimated time**: 20-35 hours of focused development

This plan represents approximately 15-25 pair programming sessions to complete.
