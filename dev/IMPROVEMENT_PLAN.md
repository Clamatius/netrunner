# AI Player Improvement Plan

Based on AI vs AI test game feedback (see test results from game playthrough)

## Priority 1: Critical Bugs ✅ FIXED
1. ✅ **Auto-End Turn State Bug** - FIXED
   - Issue: `check-auto-end-turn!` used `(take 3 log)` instead of `(take-last 3 log)`
   - Was checking oldest log entries instead of newest ones
   - Also improved `already-ended?` check to verify it's OUR side that ended
   - Fixed in: dev/src/clj/ai_basic_actions.clj:362-367

2. ✅ **Status Display Credit Bug** - FIXED
   - Issue: `show-status` used `(my-credits)` for Runner section instead of always showing Runner credits
   - When Corp player ran `status`, Runner section showed Corp credits
   - Fixed to use `(get-in gs [:runner :credit] 0)` directly
   - Fixed in: dev/src/clj/ai_websocket_client_v2.clj:970

## Priority 2: High-Impact QoL
3. **Smart Run Automation** - Biggest pain point according to tester
   - Current: Requires 4-6 `continue-run` commands per run (alternating between both sides)
   - Proposed solution:
     - `run-smart <server>` or `run <server> --auto`
     - Automatically handles continue prompts for both sides
     - Only pauses when real decision needed (rez? break? access choice?)
     - Would reduce 10+ commands per run to 2-3

4. **Corp Run Strategy Command** - "only rez X,Y ice when encountered"
   - 95% of runs, Corp just rezzes specific ICE
   - Command: `set-rez-strategy <ice1> <ice2>` or similar
   - Alternative: `set-rez-strategy none` (don't rez anything this run)
   - Note: "don't rez at all" might give too much bluff info, but maybe acceptable

## Priority 3: Display Improvements
5. **Verbose Feedback Mode** - Commands return `nil` with no confirmation
   - Every action should confirm what happened
   - Show credit deltas: "15¢ → 17¢ (+2)"
   - Show card movements: "Installed Diviner on HQ position 0"
   - Challenge: Balance between feedback and token usage
     - Principle: "You Have To Do Something" prompts should be in your face
     - Optional info (like hand after draw) might waste tokens during draw-draw-draw turns

6. **ICE Rez Prompts** - Make rez windows clearer
   - Current: Generic "Paid ability window"
   - Proposed: "Rez window - Available: rez Diviner (2¢) - Use 'rez <name>' or 'continue'"

## Priority 4: Command Improvements
7. **Retire `continue` command** - Replace with `continue-run`
   - `continue-run` already pauses at decisions
   - Keep `continue` as `continue --force` for emergencies

8. **Fix `list-playables`** - Doesn't show rez actions during runs

9. **Better `play` command error** - Currently fails with no help
   - Suggestion: "To install cards, use: install <card> <server>"
   - Or: Make `play` route to `install` when server argument provided

## Completed ✅
- ~~Suppress client ID warning spam~~
- ~~Fix deck count display~~
- ~~Add counter display (advancement, power, virus, credit)~~
- ~~Add ICE strength display for rezzed ICE~~
- ~~Show advancement counters on unrezzed cards~~

## Next Steps
1. ✅ ~~Fix auto-end turn bug~~ - COMPLETE
2. ✅ ~~Fix status credit bug~~ - COMPLETE
3. Design and implement smart run automation system (Priority 2)
4. Implement verbose feedback with token-aware output strategy (Priority 3)
5. Consider implementing Corp rez strategy command (Priority 2)
