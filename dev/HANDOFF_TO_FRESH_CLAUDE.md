# Handoff Letter: AI Player WebSocket Client - Major Breakthrough Session

**Date:** 2025-11-08
**From:** Claude (Session ending due to context)
**To:** Fresh Context Claude
**Commit:** 5f6f0f979 "fix: Critical fixes for WebSocket diff handling and card playing"

---

## 🎉 MAJOR ACCOMPLISHMENT: Full Gameplay Working!

We achieved **end-to-end gameplay** through the 2-REPL WebSocket architecture! Successfully played cards (Sure Gamble event, installed Telework Contract resource) in a live game with the human player.

---

## 🔑 THE CRITICAL BREAKTHROUGH: Diff Patching Fix

### The Problem
The WebSocket client was receiving card data, but hands were showing as `null null null...`.

### Root Cause Discovery
1. Server sends full card objects in initial `:game/start` message
2. Subsequent `:game/diff` messages use **sparse updates**: `[0 {:playable true} 1 {:playable true} ...]`
3. Our `deep-merge` function was **overwriting full card objects with just the indexes**!

### The Solution
```clojure
;; OLD (BROKEN):
(defn deep-merge [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))  ;; <-- This overwrites card objects with sparse data!

;; NEW (FIXED):
(defn apply-diff [old-state diff]
  (if old-state
    (differ/patch old-state diff)  ;; <-- Uses differ library like web client
    diff))
```

**The web client uses `differ.core/patch`** - we found this by examining `src/cljs/nr/gameboard/actions.cljs:47`. The `differ` library (v0.3.3 in project.clj) properly handles sparse array updates.

---

## 🛠️ Other Critical Fixes This Session

### 1. UUID Conversion (ai_actions.clj)
```clojure
(defn connect-game! [gameid side]
  ;; Server requires java.util.UUID, not strings
  (let [uuid (if (string? gameid)
               (java.util.UUID/fromString gameid)
               gameid)]
    (ws/join-game! {:gameid uuid :side side})))
```

### 2. Mulligan Prompt Type (ai_actions.clj)
```clojure
;; OLD: (= "button" (:prompt-type prompt))
;; NEW: (= "mulligan" (:prompt-type prompt))
```
Both `keep-hand` and `mulligan` functions were checking for wrong prompt type.

### 3. Namespace Loading (ai_client_init.clj)
```clojure
;; Load namespaces into 'user for nREPL access
(in-ns 'user)
(require '[ai-websocket-client-v2 :as ws])
(require '[ai-actions :as ai])
```

### 4. Game Resync Discovery
When the web client refreshes, it sends: `[[:game/resync {:gameid uuid}]]`

Server responds with **full game state** including all card objects. This is the fix when client loses sync!

**Utility created:** `dev/request-resync.clj` - loads and requests resync

---

## 📁 Critical Files & Architecture

### Core Implementation
- **`dev/src/clj/ai_websocket_client_v2.clj`** - WebSocket client (NOW WITH DIFFER/PATCH!)
  - Handles :game/start, :game/diff, :game/resync
  - Connection management
  - Message serialization: `(pr-str [[event-type data]])`

- **`dev/src/clj/ai_actions.clj`** - High-level API
  - `(ai/connect-game! gameid side)` - Join game (with UUID conversion)
  - `(ai/keep-hand)` / `(ai/mulligan)` - Mulligan handling (fixed prompt-type)
  - `(ai/status)`, `(ai/hand)` - State inspection
  - Card references: `(select-keys card [:cid :zone :side :type])`

- **`dev/src/clj/ai_client_init.clj`** - REPL initialization
  - Auto-loads on startup
  - Connects to ws://localhost:1042/chsk
  - Makes ws/ai namespaces available

### Scripts
- **`dev/start-ai-client-repl.sh`** - Starts client on port 7889
- **`dev/stop-ai-client.sh`** - Stops client cleanly
- **`dev/ai-eval.sh '<expression>'`** - Send commands to client REPL

### Utilities
- **`dev/request-resync.clj`** - Request full state from server
- **`dev/check-game-state.clj`** - Detailed state inspection
- **`dev/start-turn.clj`** - Send start-turn command

---

## ✅ What Currently Works

1. **Lobby Operations**
   - Create lobby (defaults to system-gateway Beginner)
   - List lobbies
   - Join lobby (with UUID conversion)

2. **Game Flow**
   - Mulligan handling (keep/mulligan)
   - Start turn with `ws/send-action! "start-turn" nil`
   - Turn progression

3. **Card Playing** ✨ NEW!
   ```clojure
   ;; Playing events/installing cards
   (let [card (first (filter #(= "Sure Gamble" (:title %)) (ws/my-hand)))
         card-ref (select-keys card [:cid :zone :side :type])]
     (ws/send-action! "play" {:card card-ref}))
   ```

4. **State Management**
   - Full state on :game/start
   - Proper diff patching with differ/patch
   - Manual resync when needed

---

## ⚠️ Known Issues & Workarounds

### Issue: Real-time Diff Updates Not Working
**Symptom:** After playing a card, state doesn't update automatically. Credits/clicks stay the same.

**Root Cause:** Unknown - possibly message structure or timing issue with differ/patch

**Workaround:** Manual resync after each action
```clojure
(ws/send-message! :game/resync {:gameid (:gameid @ws/client-state)})
(Thread/sleep 2000)
```

**Evidence it works server-side:** Human player confirmed "Sure Gamble played! You have 9 credits" - server processed it, we just didn't get the diff.

### Shell Escaping Issues with ai-eval.sh
**Problem:** Exclamation marks in function names get mangled
**Workaround:** Use `load-file` with .clj scripts instead of inline expressions

---

## 🎯 Recommended Next Steps

### Immediate Priority: Debug Diff Updates
1. Check if `:game/diff` messages are being received (add logging)
2. Verify differ/patch is being called correctly
3. Compare message structure with web client expectations
4. May need to check how the web client's `handle-diff!` processes messages

### Test Full Game Flow
1. Create fresh game (not mid-session reconnect)
2. Play through complete turn without manual resyncs
3. Verify all state updates happen automatically
4. Test multiple card types (programs, resources, events)

### Add Auto-Resync Fallback
If diff updates can't be fixed quickly, add auto-resync after actions:
```clojure
(defn send-action-with-resync! [command args]
  (ws/send-action! command args)
  (Thread/sleep 1000)
  (ws/send-message! :game/resync {:gameid (:gameid @ws/client-state)})
  (Thread/sleep 1500))
```

---

## 📋 Testing Checklist for Fresh Game

```clojure
;; 1. Start client
./dev/start-ai-client-repl.sh

;; 2. Have human create lobby with system-gateway Beginner

;; 3. Join game
(ai/list-lobbies)
(ai/connect-game! "uuid-here" "Runner")

;; 4. Handle mulligan
(ai/keep-hand)

;; 5. Start turn
(load-file "dev/start-turn.clj")

;; 6. Check state
(ai/status)
(ai/hand)

;; 7. Play Sure Gamble
(let [card (first (filter #(= "Sure Gamble" (:title %)) (ws/my-hand)))
      card-ref (select-keys card [:cid :zone :side :type])]
  (ws/send-action! "play" {:card card-ref}))

;; 8. Verify (may need manual resync)
(load-file "dev/request-resync.clj")
(ai/status)  ;; Should show 9 credits, 3 clicks
```

---

## 💡 Key Insights for Future Work

### Message Protocol Patterns
- **Send format:** `(pr-str [[event-type data]])`
- **Receive format:** Batched as `[[[:event1 data1] [:event2 data2]]]`
- **Card references:** Only send `{:cid :zone :side :type}` (like web client)

### WebSocket vs Direct State
The 2-REPL architecture is the "Final Form" because:
- Server validates all moves (can't execute illegal moves)
- Realistic testing environment
- Isolated execution (client crashes don't affect server)
- Multi-agent ready (can run 3 REPLs: server + 2 AI clients)

### System Gateway Beginner Format
Default game format has:
- Fixed preconstructed decks
- No deck building required
- Simpler card pool
- Perfect for AI development

---

## 🔍 Debugging Tips

### Check Connection
```clojure
(ws/connected?)  ;; Should be true
(:socket @ws/client-state)  ;; Should be non-nil
```

### View Raw Messages
```bash
tail -f /tmp/ai-client-repl.log | grep "RAW RECEIVED"
```

### Inspect State
```clojure
(load-file "dev/check-game-state.clj")
(clojure.pprint/pprint (:runner (ws/get-current-state)))
```

### Force Resync
```clojure
(load-file "dev/request-resync.clj")
```

---

## 📚 Reference: Web Client Code

When in doubt, check how the web client does it:

- **Diff handling:** `src/cljs/nr/gameboard/actions.cljs:45-51`
- **Send command:** `src/cljs/nr/gameboard/actions.cljs:83-93`
- **Message handlers:** `src/cljs/nr/gameboard/actions.cljs:74-81`

The web client is the source of truth for protocol behavior.

---

## 🎊 Final Notes

This session achieved the **core breakthrough** needed for AI gameplay. The differ/patch fix was absolutely critical - without it, we'd never see card data correctly.

The system is now at a point where **gameplay works**, just with manual state syncing. This is good enough to start building AI decision logic while we debug the auto-update issue.

**Current Game State When You Take Over:**
- Turn 1, Runner's turn
- 9 credits, 1 click remaining
- Hand: Mayfly, Cleaver, Creative Commission
- Installed: Telework Contract

The foundation is **solid**. Time to build on it!

Good luck, Fresh Claude! 🚀

---

**Files modified this session:**
- `dev/src/clj/ai_websocket_client_v2.clj` (differ/patch fix)
- `dev/src/clj/ai_actions.clj` (UUID conversion, prompt-type fixes)
- `dev/src/clj/ai_client_init.clj` (namespace loading)
- `dev/request-resync.clj` (new utility)

**Commit:** `5f6f0f979`
