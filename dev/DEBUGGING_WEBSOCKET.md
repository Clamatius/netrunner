# Debugging WebSocket Game State

## Problem: check-state shows nil deck counts

When you run `(check-state)` and see:
```
⚠️  No game state yet
⚠️  Game state not yet synchronized from server
```

This means the `:game/resync` or `:game/diff` messages haven't been received yet (or parsed incorrectly).

## Debugging Steps

### 1. Check if messages are being received

```clojure
(check-messages)  ; Shows last 10 messages
```

Expected output should include:
- `:chsk/handshake` (connection established)
- `:lobby/state` (joined game)
- `:game/start` (game started)
- `:game/resync` (full state sync) ← **This is the critical one!**

If you DON'T see `:game/resync`, the server hasn't sent the game state yet.

### 2. Check total message count

```clojure
(count (get-in @clients [:corp :messages]))
(count (get-in @clients [:runner :messages]))
```

If counts are low (< 5), messages aren't flowing. Check:
- Server is running (`lein repl` and `(go)`)
- No connection errors in server console

### 3. Wait longer and retry

Sometimes the server is slow:

```clojure
(Thread/sleep 5000)  ; Wait 5 more seconds
(check-state)
(check-messages)
```

### 4. Check if game-state is set but path is wrong

```clojure
;; See what keys are in the game state
(keys (get-in @clients [:corp :game-state]))

;; If keys are present, the state IS there!
;; Try exploring the structure:
(get-in @clients [:corp :game-state :corp])
(get-in @clients [:corp :game-state :runner])
```

### 5. Check raw client state

```clojure
;; CAREFUL: This will print a lot, but less than full game state
(keys (get @clients :corp))
;; Should show: :name :connected :socket :client-id :uid :gameid :game-state :last-state :messages

;; Check if game-state key exists but is nil
(:game-state (get @clients :corp))
```

### 6. Look at a recent :game/resync message

```clojure
;; Find the resync message
(def resync-msg
  (first (filter #(= (:type %) :game/resync)
                 (get-in @clients [:corp :messages]))))

;; Check if data is there
(:data resync-msg)

;; If data is a string, it needs parsing
(type (:data resync-msg))
```

## Common Issues

### Issue: No :game/resync message received

**Cause**: Game hasn't fully started on server side.

**Solution**: Wait longer, or check server console for errors.

### Issue: :game/resync received but data is nil

**Cause**: Server sent empty state (shouldn't happen).

**Solution**: Check server logs for errors during game initialization.

### Issue: :game/resync data is a string that won't parse

**Cause**: JSON parsing issue.

**Solution**: Check if `cheshire` library is available:
```clojure
(require '[cheshire.core :as json])
(json/parse-string "{\"test\": 1}" true)
```

### Issue: Game state exists but wrong path

**Cause**: State structure might be different than expected.

**Solution**: Explore the actual structure:
```clojure
(clojure.pprint/pprint
  (get-in @clients [:corp :game-state]))
```

## Quick Debug Session Example

```clojure
;; After running (run-full-game-test!)
(Thread/sleep 5000)

;; Step 1: See what messages we got
(check-messages 15)

;; Step 2: If we see :game/resync, check if state is set
(get-in @clients [:corp :game-state])

;; Step 3: If nil, check the resync message data
(def msgs (get-in @clients [:corp :messages]))
(def resync (first (filter #(= :game/resync (:type %)) msgs)))
(:data resync)  ; Should have game state

;; Step 4: If data is there but state is nil, the handler isn't working
;; Check for errors in console/logs
```

## Success Criteria

When working correctly, you should see:

```clojure
(check-messages)
;=>
; === RECENT MESSAGES ===
;
; [ :corp ] Received 8 total messages, showing last 8
;   - :chsk/handshake
;   - :lobby/state
;   - :game/start
;   - :game/resync     ← This is present!
;   - :chsk/ws-ping
;   ...

(check-state)
;=>
; === GAME STATE ===
;
; [ :corp ]
;   Credits: 5
;   Clicks: 0
;   Hand size: 5
;   Deck size: 29       ← Real numbers, not nil!
```
