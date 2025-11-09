# Ready to Test: Discard Handling Implementation

## What's Been Done

### 1. ✅ Implemented Discard Handling

Added two new functions to `dev/src/clj/ai_websocket_client_v2.clj`:

```clojure
(select-card! card eid)
  ;; Sends proper select action with full card object + eid

(handle-discard-prompt! side)
  ;; Auto-handles discard down to hand size
  ;; Discards cards one at a time
  ;; Returns number of cards discarded
```

### 2. ✅ Updated Documentation

- `AI_SESSION_SUMMARY.md` - Added Session 2 breakthrough section
- `AI_NETWORKED_CLIENT_TESTING.md` - Added discard discovery details
- Both docs now have Chrome DevTools instructions
- Committed all changes to git

### 3. ✅ Key Discovery

**The Problem**: Discard wasn't working because we used wrong command format

**The Solution** (from Chrome DevTools network inspection):
```clojure
;; WRONG
(send-action! "choice" {:choice [cid1 cid2]})

;; CORRECT
(send-action! "select"
              {:card {:cid "..." :zone [...] :side "..." :type "..."}
               :eid {:eid 4650}
               :shift-key-held false})
```

## Ready to Test

When you're back from lunch, we can test with AI as Corp:

### Test Plan

1. Create new game with AI as Corp
2. Human joins as Runner
3. Start game, both keep hands
4. AI sends "start-turn"
5. AI takes turn with 3 clicks
6. AI draws extra cards (or just takes credits to not use up hand)
7. AI ends turn with >5 cards in HQ
8. **NEW**: AI auto-discards using `handle-discard-prompt!`

### Test Script

I can create a test script that does:
```clojure
(load-file "dev/src/clj/ai_websocket_client_v2.clj")
(ai-websocket-client-v2/connect! "ws://localhost:1042/chsk")
;; ... create game as Corp
;; ... handle mulligan
;; ... send start-turn
;; ... take turn actions
;; ... end turn
;; ... call (handle-discard-prompt! :corp)
```

## What to Watch For

1. Does `handle-discard-prompt!` correctly detect the select prompt?
2. Does it send the right format with `:eid`?
3. Do cards get discarded one at a time?
4. Does it stop when hand size is correct?
5. Does turn transition to Runner?

## Notes

- Avoided bash heredoc syntax issues (exclamation marks crash REPL)
- All helper functions use file-based scripts instead
- Chrome DevTools was CRITICAL for discovering correct format
