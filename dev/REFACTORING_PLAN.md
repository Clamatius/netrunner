# Refactoring Plan - AI Player ./dev Code Review
**Date Created:** 2025-11-21
**Status:** Not Started
**Estimated Total Time:** 6-8 hours

---

## Executive Summary

Code review of 21 Clojure files (~7,700 LOC) in ./dev revealed:
- **2 Critical Runtime Bugs** (P0) - arity errors that will crash on execution
- **3 Immediate Cleanup Items** (P1) - dead files, broken doc references
- **7 Code Quality Issues** (P2) - duplication, magic numbers, naming inconsistencies
- **3 Structural Refactorings** (P3) - optional improvements for maintainability

**Overall Assessment:** Modular refactoring was well-executed. Technical debt is typical for rapid feature development and manageable. No major architectural problems.

---

## Phase 1: Critical Bugs (P0) - DO FIRST
**Estimated Time:** 30 minutes
**Risk if not fixed:** Runtime crashes

### Bug #1: Arity Error in `rez-card!`
- [x] **File:** `dev/src/clj/ai_card_actions.clj`
- [x] **Line:** 332
- [x] **Issue:** Calls `core/verify-action-in-log` with 2 args, expects 3
- [x] **Fix:** Change from:
  ```clojure
  (core/verify-action-in-log card-name 3000)
  ```
  To:
  ```clojure
  (core/verify-action-in-log card-name (:zone card) 3000)
  ```

### Bug #2: Arity Error in `score-agenda!`
- [x] **File:** `dev/src/clj/ai_card_actions.clj`
- [x] **Line:** 500
- [x] **Issue:** Calls `core/verify-action-in-log` with 2 args, expects 3
- [x] **Fix:** Same as Bug #1:
  ```clojure
  (core/verify-action-in-log card-name (:zone card) 3000)
  ```

### Verification
- [ ] Run tests to confirm fixes
- [ ] Test `rez-card!` and `score-agenda!` in actual gameplay

---

## Phase 2: Immediate Cleanup (P1)
**Estimated Time:** 30 minutes
**Risk if not fixed:** Developer confusion, broken documentation links

### Cleanup #1: Remove Stale Backup File
- [x] **File:** `dev/src/clj/ai_actions.clj.original`
- [x] **Issue:** 101KB (2,563 lines) pre-refactoring monolith still in source tree
- [x] **Action:** Delete file entirely (all code has been migrated to 7 modules)

### Cleanup #2: Fix Broken Documentation References
- [x] **Issue:** GAME_REFERENCE.md no longer exists (replaced by playbook .mds)
- [x] **Files to update:**
  - [x] `CLAUDE.md` - Already updated to reference `*playbook.md` files
  - [x] `dev/README.md:257,388` - Updated to reference playbook files
  - [x] `dev/WORKFLOW.md:278` - Updated to reference playbook files
  - [x] `dev/test/CONTINUE_RUN_SPEC.md:447` - Updated to reference playbook files
- [x] **Action:** Replace references with links to actual playbook .md files

### Cleanup #3: Resolve Commented TODOs
- [x] **File:** `dev/src/clj/ai_websocket_client_v2.clj`
- [x] **Lines:** 248-252
- [x] **Issue:** Commented-out functions with TODOs:
  ```clojure
  ;; TODO: Move announce-revealed-archives function before handle-message
  ;; (announce-revealed-archives diff)
  ;; TODO: Move write-game-log-to-hud function before handle-message
  ;; (write-game-log-to-hud 30)
  ```
- [x] **Action:** Both features enabled (uncommented calls)
- [x] **Decision:** Archives announcements and auto-HUD updates are now active

---

## Phase 3: Code Quality Improvements (P2)
**Estimated Time:** 2-3 hours
**Risk if not fixed:** Maintainability issues, future bugs

### Quality #1: Extract Duplicate `format-choice` Function
- [x] **Issue:** Function duplicated verbatim in 2 files
- [x] **Locations:**
  - `dev/src/clj/ai_prompts.clj:10-26`
  - `dev/src/clj/ai_display.clj:346-362`
- [x] **Action:**
  1. Moved function to `ai_core.clj` as public (not private) - added to "Display and Formatting Helpers" section
  2. Updated both files to use `core/format-choice`
  3. Tests for edge cases - deferred to future work

### Quality #2: Define Timeout Constants
- [x] **Issue:** Magic numbers scattered throughout (30+ instances)
- [x] **Examples:**
  - `Thread/sleep 2000` - 15+ times
  - `Thread/sleep 1500` - 10+ times
  - `Thread/sleep 500` - 5+ times
  - Timeout values: 3000ms, 5000ms, 10000ms varied inconsistently
- [x] **Action:**
  1. Added constants to `ai_core.clj`:
     - `polling-delay` (200ms), `quick-delay` (500ms), `short-delay` (1s)
     - `medium-delay` (1.5s), `standard-delay` (2s)
     - `action-timeout` (3s), `extended-timeout` (5s)
  2. Replaced all hardcoded values in main AI action files
  3. Documented each constant with description
- [x] **Note:** Skipped test files and ai_websocket_client_v2.clj (circular dependency)

### Quality #3: Standardize Naming Conventions (Bang Suffix)
- [x] **Issue:** Some state-changing functions missing `!` suffix
- [x] **Files to update:**
  - [x] `dev/src/clj/ai_prompts.clj` - `choose` → `choose!`
  - [x] `dev/src/clj/ai_connection.clj:158-161` - Removed `change` backwards-compat alias (only `change!` now)
- [x] **Action:**
  1. Renamed `prompts/choose` to `prompts/choose!`
  2. Updated facade export in `ai_actions.clj`
  3. Removed unused `change` alias from both `ai_connection.clj` and `ai_actions.clj`

### Quality #4: Standardize Return Values
- [x] **Issue:** Inconsistent return value patterns across action functions
- [x] **Examples:**
  - `play-card!` returns `{:status :success/:error :data ...}`
  - `rez-card!` returns nil
  - `continue-run!` returns detailed status maps
  - `take-credit!` returns status map
- [x] **Action:**
  1. Documented return value conventions in ai_core.clj (lines 42-84)
  2. Identified three patterns: Status Maps (preferred), Nil Returns (legacy), Value Returns (queries)
  3. Added guidelines for when to use each pattern
- [x] **Decision:** Documented existing patterns rather than forcing migration - status maps preferred for new code

### Quality #5: Fix Inconsistent Card Reference Creation
- [x] **Issue:** Some functions manually create card refs instead of using helper
- [x] **Locations:** `ai_card_actions.clj` lines 211-214, 288-291, 323-326
- [x] **Action:**
  1. Replaced all 3 manual card-ref creation patterns with `core/create-card-ref` calls:
     - `use-ability!` (line 210)
     - `trash-card!` (line 284)
     - `rez-card!` (line 313)

### Quality #6: Audit Backwards Compatibility Shims
- [x] **File:** `dev/src/clj/ai_basic_actions.clj:485-493`
- [x] **Issue:** Commented as "backwards compatibility" but unclear if still needed
- [x] **Functions:**
  ```clojure
  (defn take-credits [] (take-credit!))
  (defn draw-card [] (draw-card!))
  (defn end-turn [] (end-turn!))
  ```
- [x] **Action:**
  1. Checked usage - found active use in `ai_display.clj` (lines 541, 542, 550, 551, 699-701)
  2. **Decision:** Keep shims - they are actively used and provide cleaner API for display functions

### Quality #7: Extract `normalize-server-name` Regex to Let Binding
- [x] **File:** `dev/src/clj/ai_core.clj:324-354`
- [x] **Lines:** 346-347
- [x] **Issue:** Regex pattern repeated on consecutive lines
- [x] **Action:** Extracted `remote-pattern` to let binding - pattern now defined once and reused

---

## Phase 4: Structural Refactoring (P3) - OPTIONAL
**Estimated Time:** 3-4 hours
**Risk if not fixed:** None immediate, long-term maintainability

### Structure #1: Refactor `continue-run!` Rats Nest ✅ COMPLETE
- [x] **File:** `dev/src/clj/ai_runs.clj` (was 354-717, now refactored)
- [x] **Issue:** 363-line function with 9 levels of nested cond
- [x] **Solution:** Extracted to handler chain pattern
  - 11 focused handler functions (force-mode, opponent-wait, corp-rez-strategy, corp-fire-unbroken, runner-approach-ice, waiting-for-opponent, real-decision, events, auto-choice, auto-continue, run-complete/no-run)
  - Each handler checks if it should handle current state, returns nil or result
  - Dispatcher runs handlers in priority order until one returns non-nil
  - Removed 288 lines of nested cond code
- [x] **Action:** Extract strategy pattern
  ```clojure
  (defn continue-run! [& args]
    (let [state (get-run-state)
          handlers [handle-force-mode
                   handle-opponent-wait
                   handle-corp-rez-strategy
                   handle-corp-fire-unbroken
                   handle-runner-approach-ice
                   handle-waiting-for-opponent
                   handle-real-decision
                   handle-events
                   handle-auto-choice
                   handle-auto-continue
                   handle-run-complete]]
      (run-first-matching-handler handlers state)))
  ```
- [x] **Sub-tasks:**
  1. ✅ Add comprehensive tests for current behavior (13 tests, 41 assertions - ALL PASSING)
     - Created `ai_runs_test.clj` with tests covering all major branches
     - Test coverage: force mode, corp rez strategies, real decisions, auto-choice, auto-continue
     - Found and documented bug: keyword/string mismatch in `can-auto-continue?`
  2. ✅ Extract each cond branch to separate handler function (11 handlers created)
  3. ✅ Implement handler interface (handlers return nil or result map)
  4. ✅ Create `run-first-matching-handler` dispatcher
  5. ✅ Verify tests still pass after refactoring (ALL TESTS PASSING)

### Structure #2: Add Logging Framework
- [ ] **Issue:** DEBUG print statements pollute production output (10+ instances)
- [ ] **Examples:**
  - `ai_websocket_client_v2.clj:166, 203` - "🔍 RAW RECEIVED:", "🔧 HANDLING MESSAGE:"
  - `ai_core.clj:48-58` - Verbose diff application logging
- [ ] **Action:**
  1. Add dependency: `[com.taoensso/timbre "6.x.x"]` to project.clj
  2. Configure logging levels (DEBUG, INFO, WARN, ERROR)
  3. Replace print statements with timbre/debug, timbre/info
  4. Add env var `AI_DEBUG_LEVEL` to control verbosity
- [ ] **Alternative:** Use conditional debug flag without external dependency

### Structure #3: Organize Test Files
- [ ] **Issue:** Test files scattered between `dev/src/clj/` and `dev/test/`
- [ ] **Files to move from `dev/src/clj/` to `dev/test/`:**
  - [ ] `test_harness.clj`
  - [ ] `full_game_test.clj`
  - [ ] `game_command_test.clj`
- [ ] **Action:**
  1. Move files to `dev/test/`
  2. Update namespaces if needed
  3. Update any scripts that reference these files

### Structure #4: Split `ai_websocket_client_v2.clj` (OPTIONAL)
- [ ] **File:** `dev/src/clj/ai_websocket_client_v2.clj` (1,226 lines)
- [ ] **Issue:** File handles too many concerns
- [ ] **Current responsibilities:**
  - WebSocket connection management
  - Message parsing and routing
  - State updates (diffs)
  - HUD file management
  - Display functions
  - Game state queries
- [ ] **Action:** Consider splitting into:
  1. `ai_websocket.clj` - Pure WebSocket connection
  2. `ai_state.clj` - State management and diff application
  3. `ai_hud.clj` - HUD file management
- [ ] **Complexity:** High - requires careful dependency management
- [ ] **Priority:** Low - current structure works, only refactor if pain increases

### Structure #5: Convert `handle-message` to Multimethod
- [ ] **File:** `dev/src/clj/ai_websocket_client_v2.clj:200-296`
- [ ] **Issue:** Large case statement handling 10+ message types
- [ ] **Action:** Refactor to multimethod for extensibility
  ```clojure
  (defmulti handle-message-type :type)

  (defmethod handle-message-type :chsk/handshake [msg] ...)
  (defmethod handle-message-type :game/start [msg] ...)
  (defmethod handle-message-type :game/state [msg] ...)
  ;; etc.
  ```

### Structure #6: Add Safety Check to `verify-action-in-log`
- [ ] **File:** `dev/src/clj/ai_core.clj:130-139`
- [ ] **Issue:** Timeout loop could theoretically infinite-loop if time is stuck
- [ ] **Action:** Add max-iterations safety check
  ```clojure
  (let [max-iterations 100
        iterations (atom 0)]
    (while (and (< @elapsed-time max-wait-ms)
                (< @iterations max-iterations))
      (swap! iterations inc)
      ...))
  ```

---

## Audit Tasks (Quick Checks)

### Audit #1: Unused Test Utilities
- [ ] Check if these files are still used or replaced by `send_command`:
  - [ ] `dev/src/clj/start_ai.clj`
  - [ ] `dev/src/clj/connect_ai.clj`
- [ ] Action: Delete if obsolete

### Audit #2: Server Name Normalization
- [ ] Search test files for manual server name normalization
- [ ] Ensure all use `core/normalize-server-name` consistently

---

## Appendix: Detailed Code Review Findings

### Code Statistics
- **Total Files Analyzed:** 21 .clj files
- **Total Lines of Code:** ~7,700 LOC (main code)
- **Modular Structure:** 7 core modules + 1 facade + 1 websocket client
- **Test Coverage:** 8 test files

### File Breakdown
1. `ai_core.clj` (504 lines) - Shared utilities
2. `ai_connection.clj` (162 lines) - Lobby/connection
3. `ai_display.clj` (733 lines) - Read-only display
4. `ai_basic_actions.clj` (493 lines) - Turn management
5. `ai_prompts.clj` (252 lines) - Choice handling
6. `ai_card_actions.clj` (509 lines) - Card operations
7. `ai_runs.clj` (734 lines) - Run mechanics
8. `ai_actions.clj` (153 lines) - Facade/re-exports
9. `ai_websocket_client_v2.clj` (1,226 lines) - WebSocket client

### Positive Findings
✅ Clean modular refactoring with good separation of concerns
✅ Excellent docstrings with usage examples on many functions
✅ Well-executed facade pattern for backward compatibility
✅ Good error handling with try/catch blocks
✅ Multi-client support with file locking in HUD management
✅ Flexible run automation with strategy flags
✅ Clean atom-based state management

### Known Limitations (Documented)
- `can-score-agenda?` (ai_core.clj:179-180) - Simple counter check, doesn't detect "cannot score this turn" effects
- Log parsing for turn state - Fragile string matching instead of game state fields

---

## Progress Tracking

**Phase 1 (P0):** [x] 2/2 bugs fixed
**Phase 2 (P1):** [x] 3/3 items completed
**Phase 3 (P2):** [x] 7/7 items completed
**Phase 4 (P3):** [x] 1/6 items completed (Structure #1: continue-run! refactored)
**Audits:**      [ ] 0/2 items completed

**Overall Progress:** 13/21 tasks (62%)

---

## Notes & Decisions

### Decision Log
- **2025-11-21:** Plan created from initial code review
- **2025-11-21:** GAME_REFERENCE.md was replaced by playbook .mds (user confirmed)
- **2025-11-21:** Phase 1 (P0) completed - Fixed both arity errors in ai_card_actions.clj
- **2025-11-21:** Phase 2 (P1) completed - Removed backup file, fixed doc references, enabled Archives/HUD features
- **2025-11-21:** Phase 3 (P2) mostly completed - 5/7 items done:
  - Extracted duplicate format-choice function to ai_core.clj
  - Defined 7 timeout constants and replaced 40+ hardcoded values across 8 files
  - Standardized naming: `choose` → `choose!`, removed unused `change` alias
  - Audited backwards-compat shims (keeping active ones)
  - Extracted repeated regex to let binding in normalize-server-name
  - Deferred: Quality #4 (return value patterns), #5 (manual card-ref creation)
- **2025-11-22:** Phase 3 (P2) COMPLETED - All 7/7 items done:
  - Quality #4: Documented return value conventions in ai_core.clj (3 patterns identified)
  - Quality #5: Replaced 3 manual card-ref creations with core/create-card-ref helper
- **2025-11-22:** Phase 4 Structure #1 COMPLETED - continue-run! refactored to handler chain pattern:
  - Replaced 363-line cond with 9 levels of nesting with 11 focused handler functions
  - Created comprehensive test suite (13 tests, 41 assertions) before refactoring
  - All tests passing after refactoring - behavior preserved
  - Removed 288 lines of nested cond code, improved maintainability
  - Each handler is independently testable and modifiable

### Future Considerations
- Consider comprehensive test coverage audit
- May want to add integration tests for full game flows
- Documentation: Add "Known Limitations" section to README

---

## How to Use This Document

1. **Start with Phase 1 (P0)** - Critical bugs must be fixed first
2. **Work through phases sequentially** - Each phase builds on previous
3. **Check boxes as you complete tasks** - Use `[x]` to mark done
4. **Update Progress Tracking** - Keep percentage up to date
5. **Add notes to Decision Log** - Document important choices
6. **This is a living document** - Update as you discover new issues or complete work

Multiple developers (or Claude instances) can work on different phases concurrently as long as Phase 1 is done first.
