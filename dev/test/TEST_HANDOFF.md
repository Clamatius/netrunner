# Test Infrastructure Handoff

## Status: Base Pattern Established ✓

The test infrastructure has been successfully integrated and verified. Query functions now follow the established pattern of returning data while maintaining REPL-friendly println behavior.

## Current Test Results

**Overall**: 32 tests, 35 assertions, 9 errors, 15 failures

**Passing**: 17 tests (including all 3 query function tests)

## Pattern Established

Query functions should return data AND print for REPL use:

```clojure
(defn show-hand
  "Show hand using side-aware state access. Returns the hand."
  []
  (let [state @ws/client-state
        side (:side state)
        hand (get-in state [:game-state (keyword side) :hand])]
    (when hand
      (println (str "\n🃏 " (clojure.string/capitalize side) " Hand:"))
      (doseq [[idx card] (map-indexed vector hand)]
        (println (str "  " idx ". " (:title card) " [" (:type card) "]"))))
    (or hand [])))  ; Return hand data
```

**Key points:**
- Print for REPL visibility (when, println, doseq, etc.)
- Return data at end for testability
- Use `(or value default)` pattern for safe returns

## Remaining Work

### Action Validation Tests (~10+ tests failing)

These tests are failing because the action functions need validation logic:

1. **test-end-turn** - Should validate that no clicks remain before allowing end turn
2. **test-mulligan** - Needs proper mulligan state validation
3. **test-keep-hand** - Needs hand state validation
4. **test-advance-card** - Needs advance validation
5. **test-run-normalized-server** - Needs server name normalization
6. **test-choose-option** - Needs choice validation (slowest test: 0.5s)
7. **test-status** - Needs implementation or return value
8. **test-play-card-by-name** - Needs play validation
9. **test-play-card-by-index** - Needs index validation
10. **test-draw-card** - Needs draw validation
11. **test-install-card-by-name** - Needs install validation
12. **test-run-hq** - Needs run validation
13. **test-take-credit** - Needs credit validation
14. **test-rez-card** - Needs rez validation

### Sad Path Tests (~9+ error tests)

These test error conditions and likely need validation error messages:

1. **test-runner-cannot-rez** - Runner shouldn't be able to rez corp cards
2. **test-play-card-empty-hand** - Handle empty hand gracefully
3. **test-play-card-nil-input** - Handle nil input
4. **test-action-when-not-connected** - Check connection state
5. **test-action-without-game-state** - Check game state exists
6. **test-choose-invalid-option** - Validate choice options (slowest error test: 0.5s)
7. **test-run-empty-server** - Validate server exists
8. **test-run-invalid-server** - Validate server name

## Running Tests

### Run all AI tests:
```bash
lein kaocha --focus ai-actions-test --focus ai-actions-sad-path-test
```

### Run specific test:
```bash
lein kaocha --focus ai-actions-test/test-end-turn
```

### Run with documentation reporter:
```bash
lein kaocha --reporter documentation --focus ai-actions-test
```

## Test Infrastructure Files

- **dev/test/test_helpers.clj** - Mock utilities, state injection, assertion helpers
- **dev/test/ai_actions_test.clj** - Happy path tests (17 tests)
- **dev/test/ai_actions_sad_path_test.clj** - Error condition tests (15 tests)
- **dev/test/README.md** - Test documentation and examples
- **dev/run-tests.sh** - Fast persistent REPL test runner (has classpath issues, use `lein kaocha` instead)

## Implementation Files

- **dev/src/clj/ai_actions.clj** - Action and query functions
- **dev/src/clj/ai_websocket_client_v2.clj** - WebSocket client with state management

## Notes for Web Claude

1. **Test runner script** (dev/run-tests.sh) has classpath issues with persistent REPL. Use `lein kaocha` directly instead.

2. **Validation approach**: Action functions should validate preconditions before executing:
   - Check connection state
   - Check game state exists
   - Validate input parameters
   - Check action is legal (right side, right game phase, etc.)
   - Return meaningful error messages

3. **Error handling pattern**: Functions should return success/failure indicators or throw descriptive exceptions that tests can assert on.

4. **Performance note**: The choose-option tests are slowest (~0.5s each). This might be normal for choice validation tests, but worth monitoring.

5. **Architecture decision pending**: User indicated willingness to refactor to separate layers (get-*/show-* or injectable output) later if needed. Current pragmatic approach is fine for now.

## Next Steps

1. Review failing action validation tests to understand expected behavior
2. Add validation logic to action functions
3. Implement proper error messages for sad path tests
4. Run tests after each fix to verify progress
5. Consider grouping similar validations (e.g., all "check connection" logic)

## Test Helpers Available

See `dev/test/test_helpers.clj` for:
- `mock-client-state` - Create fake client state
- `with-mock-state` - Run test with mocked state
- `assert-error-message` - Assert error output
- `assert-success-message` - Assert success output
- `make-card`, `make-hand`, `make-prompt` - Test data builders
- `sample-runner-cards`, `sample-corp-cards` - Common test cards

## Questions?

If you have questions about the test infrastructure or implementation approach, check:
- **dev/test/README.md** - Comprehensive test documentation
- **FAST_TESTING.md** - Fast testing methodology
- Existing test implementations in ai_actions_test.clj for examples
