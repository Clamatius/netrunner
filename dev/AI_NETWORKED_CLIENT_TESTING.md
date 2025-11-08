# AI Networked Client Testing - Session Notes

**Date**: 2025-11-08
**Goal**: Test AI WebSocket client connecting to localhost game and verify full integration

---

## Summary: SUCCESS ✅

The AI WebSocket client successfully connected to a localhost game, joined as Runner, responded to prompts, and is receiving game state updates in real-time.

---

## What We Tested

### 1. Connection & Authentication ✅

**Method**: WebSocket connection with auto-generated AI user
- Client ID prefix `ai-client-` triggers fake user creation in `web/auth.clj`
- No manual authentication required
- Auto-generated username: `AI-{client-id-suffix}`

**Code location**: `src/clj/web/auth.clj:65-74`
```clojure
;; TEMP: Allow AI players without authentication
;; Create a fake user for client-ids that start with "ai-client-"
ai-user (when (and client-id (str/starts-with? (str client-id) "ai-client-"))
          {:username (str "AI-" (subs (str client-id) 10))
           :emailhash "ai"
           :_id "ai-player"
           :special true
           ...})
```

**Result**: Connected successfully as `AI-c9002bcc-c2b5-4cd3-8f7b-ec90f39bce17`

---

### 2. Lobby Operations ✅

**Tested**:
- Request lobby list: `(ai-websocket-client-v2/request-lobby-list!)`
- View available games: `(ai-websocket-client-v2/show-games)`
- Join game as Runner: `(ai-websocket-client-v2/join-game! {:gameid "..." :side "Runner"})`

**Key finding**: Game ID must be a UUID object (not string) when sending
```clojure
;; Correct format
(let [gameid-uuid (java.util.UUID/fromString "a7378932-9d6e-43c9-9ee6-d4649aaae089")]
  (send-message! :lobby/join {:gameid gameid-uuid :request-side "Runner"}))
```

**Result**: Successfully joined game, received `:lobby/state` confirmation with both players listed

---

### 3. Game Start & Mulligan ✅

**Received**:
- `:game/start` message with full initial state (JSON string format)
- Game state includes: hands, credits, decks, identity cards, etc.

**Prompt handling**:
- Received mulligan prompt: "Keep hand?"
- Sent choice via: `(send-action! "choice" {:choice {:uuid "baae72ba-9be4-453f-b0a3-70cf8e9bcc27"}})`
- Prompt cleared successfully

**Result**: Both players kept hands, game proceeded to Turn 1

---

### 4. Game State Updates ✅

**Update mechanism**: `:game/diff` messages
- Diffs come as JSON strings: `"{\"gameid\":\"...\",\"diff\":[...]}"`
- Client applies diffs using deep-merge
- State tracked in `client-state` atom

**Example diff** (Turn 1 start):
```clojure
{:end-turn false
 :last-revealed []
 :turn 1
 :active-player "corp"
 :corp {:click 3, :deck-count 28, :hand-count 6, :aid 2}
 :log ["+", {...}, "+", {...}]}
```

**Result**: State updates received and applied correctly

---

## Issues Found

### 1. Connection Timeout (30 seconds) ⚠️

**Problem**: WebSocket connections timeout after 30 seconds of inactivity

**Impact**:
- AI client needs frequent reconnection checks
- Long analysis periods between moves will disconnect
- Need "am I connected?" check before each action

**Solution needed**: Reconnection helper function that:
1. Checks connection status
2. Reconnects if needed (preserving client-id)
3. Re-joins game if needed

---

### 2. Client State Tracking (Minor) ⚠️

**Problem**: `client-state` atom doesn't track `:gameid` and `:side` correctly

**Current behavior**:
```clojure
(:gameid @client-state)  ;; => nil (should be UUID)
(:side @client-state)    ;; => nil (should be :runner)
```

**Workaround**: Game state is still accessible via `:game-state` key
```clojure
(:gameid (:game-state @client-state))  ;; => Works!
```

**Impact**: Low - doesn't affect gameplay, just state queries

**Fix location**: `ai_websocket_client_v2.clj:149-156` (handle-message for :game/start)

---

### 3. JSON String Parsing ℹ️

**Observation**: Some messages come as JSON strings, not EDN
- `:game/start` data is JSON string
- `:game/diff` data is JSON string
- Client already handles this correctly with `json/parse-string`

**Not a bug**: This is expected behavior, just noting for documentation

---

## Tools Created

### 1. Connection Script: `dev/test-ai-connection.sh` ✅

**Purpose**: Quick test of AI client connection and lobby viewing

**Usage**:
```bash
./dev/test-ai-connection.sh
```

**What it does**:
1. Loads AI client code
2. Connects to `ws://localhost:1042/chsk`
3. Requests lobby list
4. Displays available games

**Output example**:
```
🔌 Connecting to ws://localhost:1042/chsk ...
✅ Connected! UID: AI-c9002bcc-c2b5-4cd3-8f7b-ec90f39bce17
📋 Available Games:
======================================================================
🎮 Clamatius's game
   ID: #uuid "a7378932-9d6e-43c9-9ee6-d4649aaae089"
   Players: 1 / 2
     - Corp : Clamatius
======================================================================
```

---

## Successful Command Sequence

Here's the full sequence that worked:

```clojure
;; 1. Load client
(load-file "dev/src/clj/ai_websocket_client_v2.clj")

;; 2. Connect
(ai-websocket-client-v2/connect! "ws://localhost:1042/chsk")

;; 3. Request lobby list
(ai-websocket-client-v2/request-lobby-list!)
(Thread/sleep 2000)

;; 4. View games
(ai-websocket-client-v2/show-games)

;; 5. Join game
(let [gameid-uuid (java.util.UUID/fromString "a7378932-9d6e-43c9-9ee6-d4649aaae089")]
  (ai-websocket-client-v2/send-message! :lobby/join
                                         {:gameid gameid-uuid
                                          :request-side "Runner"}))

;; 6. Wait for game start
(Thread/sleep 3000)

;; 7. Check mulligan prompt
(let [gs (:game-state @ai-websocket-client-v2/client-state)
      prompt (get-in gs [:runner :prompt-state])]
  (println "Prompt:" (:msg prompt))
  (println "Choices:" (:choices prompt)))

;; 8. Keep hand
(ai-websocket-client-v2/send-action! "choice"
                                      {:choice {:uuid "baae72ba-9be4-453f-b0a3-70cf8e9bcc27"}})

;; 9. Monitor game state
(let [gs (:game-state @ai-websocket-client-v2/client-state)]
  (println "Turn:" (:turn gs))
  (println "Active player:" (:active-player gs)))
```

---

## Recommendations for Next Steps

### 1. Create Reconnection Helper ⭐ HIGH PRIORITY

Given 30-second timeout, every AI action should start with:
```clojure
(defn ensure-connected! []
  (when-not (ai-websocket-client-v2/connected?)
    (println "⚠️  Reconnecting...")
    (ai-websocket-client-v2/connect! "ws://localhost:1042/chsk")
    (Thread/sleep 2000)))
```

### 2. Create Game State Query Helpers

Make it easier to access game state:
```clojure
(defn my-side []
  (if (= "runner" (:active-player @client-state)) :runner :corp))

(defn my-credits []
  (get-in (:game-state @client-state) [(my-side) :credit]))

(defn my-clicks []
  (get-in (:game-state @client-state) [(my-side) :click]))

(defn my-hand []
  (get-in (:game-state @client-state) [(my-side) :hand]))
```

### 3. Create Action Helpers

Wrap common actions with error handling:
```clojure
(defn take-credit []
  (ensure-connected!)
  (send-action! "credit" nil))

(defn draw-card []
  (ensure-connected!)
  (send-action! "draw" nil))
```

### 4. Create Prompt Helper

Check for prompts and display them clearly:
```clojure
(defn check-prompt []
  (let [gs (:game-state @client-state)
        prompt (get-in gs [:runner :prompt-state])]
    (when prompt
      (println "🔔 PROMPT:" (:msg prompt))
      (println "Type:" (:prompt-type prompt))
      (println "Choices:")
      (doseq [[idx choice] (map-indexed vector (:choices prompt))]
        (println (str "  " idx ". " (:value choice) " [" (:uuid choice) "]")))
      prompt)))
```

---

## Key Learnings

1. **Authentication is already handled** - The "ai-client-" prefix is sufficient
2. **Game state updates work** - Diffs are applied correctly
3. **Prompt system works** - Can send choices and clear prompts
4. **Timeout management is critical** - 30 seconds requires frequent reconnection checks
5. **UUID handling matters** - Server expects UUID objects, not strings
6. **Message format varies** - Some EDN, some JSON strings (client handles both)

---

## Files Modified/Created

- ✅ Created: `dev/test-ai-connection.sh` - Quick connection test script
- ✅ Read: `dev/src/clj/ai_websocket_client_v2.clj` - Main AI client (no changes needed)
- ✅ Read: `src/clj/web/auth.clj` - Verified AI user authentication
- ✅ Read: `src/clj/web/lobby.clj` - Verified join-lobby mechanics

---

## Creating Precon Games - IMPORTANT ⭐

To create a System Gateway beginner game with auto-selected decks:

```clojure
(ai-websocket-client-v2/send-message! :lobby/create
  {:title "Game Title"
   :format "system-gateway"
   :room "casual"
   :side "Corp"  ; or "Runner"
   :gateway-type "beginner"  ; ← KEY! Not :precon!
   :allow-spectator true
   :open-decklists true})
```

**Critical**: For `system-gateway` format, use `:gateway-type "beginner"` NOT `:precon "beginner"`!

**Why**: The `validate-precon` function (src/clj/web/lobby.clj:94-102) checks:
- For `system-gateway` format → uses `gateway-type` parameter
- For `preconstructed` format → uses `precon` parameter

**Verification**:
```clojure
(let [games (ai-websocket-client-v2/get-lobby-list)
      my-game (first games)]
  (println "Precon:" (:precon my-game)))  ; Should show :beginner
```

---

## CRITICAL: Game Start Sequence 🔥

After much debugging, here's the complete sequence to start a game:

1. **Create lobby** with `:gateway-type "beginner"` (not `:precon`!)
2. **Both players join** (no deck selection needed for precon games)
3. **Creator clicks "Start Game"** in UI (or send `:lobby/start`)
4. **Both players get mulligan prompts** ("Keep hand?" / "Mulligan")
5. **Both players respond** to mulligan
6. **Corp sends `"start-turn"` action** ← THIS WAS THE MISSING PIECE! (Corp always goes first)

```clojure
;; After both players keep/mulligan:
(ai-websocket-client-v2/send-action! "start-turn" nil)
```

**Bug found**: Between mulligan and actual turn start, the Corp prompt shows as integer `0` instead of a proper prompt object with `:msg "Start Turn"` or similar. This is a server-side issue with how the prompt state is serialized.

**Result**: Turn 1 begins, Corp gets 3 clicks and draws mandatory card!

---

## Session 2: Discard Handling (2025-11-08 Afternoon)

### Discard Prompt Discovery 🎉

**Problem**: AI couldn't handle end-of-turn discard because we didn't know the correct format.

**Solution**: Used Chrome DevTools to capture actual WebSocket messages from UI.

#### Discard Prompt Structure
```clojure
{:msg "Discard down to 5 cards"
 :prompt-type "select"  ; ← NOT "choice"!
 :selectable ["cid1" "cid2" "cid3" ...]  ; CIDs of selectable cards
 :eid {:eid 4650}}  ; Event ID - must include in response
```

#### Correct Discard Action Format
```clojure
[:game/action
 {:gameid #uuid "..."
  :command "select"  ; ← Command is "select" not "choice"
  :args {:card {:cid "0a5e80cc-f2fd-403f-88e6-b81403615add"
                :zone ["hand"]
                :side "Corp"  ; or "Runner"
                :type "ICE"}  ; Card type
         :eid {:eid 4650}  ; From prompt
         :shift-key-held false}}]
```

#### Key Insights
1. **Select command**: Discard uses `"select"` not `"choice"`
2. **Full card object**: Must send entire card with zone/side/type, not just CID
3. **Event ID required**: Must include `:eid` from the prompt
4. **One at a time**: Cards selected individually until done
5. **Selectable list**: Prompt contains CIDs of cards that can be selected

#### Implementation
Added to `ai_websocket_client_v2.clj`:
```clojure
(defn select-card! [card eid]
  "Select a card for discard or other select prompts")

(defn handle-discard-prompt! [side]
  "Auto-handle discard down to maximum hand size")
```

### Chrome DevTools for WebSocket Inspection

**How to capture WebSocket messages in Chrome**:
1. Open DevTools (F12)
2. Go to Network tab
3. Filter by "WS" (WebSocket)
4. Click on the WebSocket connection
5. Go to "Messages" sub-tab
6. Perform action in UI (e.g., discard card)
7. See the actual message sent

This was CRITICAL for discovering the correct format!

## Next Session TODO

- [x] Create reconnection wrapper in `ai_websocket_client_v2.clj`
- [x] Add game state query helpers
- [x] Add action helpers with reconnection checks
- [x] Debug turn start sequence (found `"start-turn"` action!)
- [x] Solve discard handling (found `"select"` command format!)
- [ ] Test discard handling with AI as Corp in actual game
- [ ] Test full turn: draw, install, play event, run
- [ ] Document prompt handling patterns
- [ ] Add logging for all state transitions
- [ ] Fix prompt state bug (showing `0` instead of map)
- [ ] Create helper to detect and auto-send `"start-turn"` when needed
