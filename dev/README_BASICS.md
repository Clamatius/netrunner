# AI Player - Basic Connection Guide

## Step 1: Test Connection

This verifies you can connect to the game server:

```bash
./dev/src/clj/nrepl-eval.sh '
(do
  (load-file "dev/src/clj/ai_websocket_client_v2.clj")
  (ai-websocket-client-v2/connect! "ws://localhost:1042/chsk")
  (Thread/sleep 2000)
  (if (ai-websocket-client-v2/connected?)
    (println "✅ Connected! UID:" (:uid @ai-websocket-client-v2/client-state))
    (println "❌ Failed to connect"))
  (ai-websocket-client-v2/disconnect!))'
```

Expected output:
```
🔌 Connecting to ws://localhost:1042/chsk ...
✨ WebSocket connection initiated...
⏳ WebSocket connected, waiting for handshake...
✅ Connected! UID: <your-uid>
```

## Step 2: Join a Game

First, create a game in the web UI, then join it:

```bash
# Replace <GAMEID> with the actual game ID from the web UI
./dev/02-join-and-mulligan.sh <GAMEID> Corp
```

This will:
1. Connect to the server
2. Join the specified game as Corp
3. Wait for the game to start
4. Handle the mulligan prompt (keeps hand)
5. Show game status

## Step 3: Understanding the WebSocket Client

The client (`dev/src/clj/ai_websocket_client_v2.clj`) provides these key functions:

### Connection
- `(connect! url)` - Connect to WebSocket server
- `(connected?)` - Check if connected
- `(disconnect!)` - Disconnect cleanly

### Lobby Operations
- `(request-lobby-list!)` - Get list of available games
- `(join-game! {:gameid "..." :side "Corp"})` - Join a game

### Game Actions
- `(send-action! command args)` - Send any game action
- `(take-credits!)` - Take credits for clicks
- `(draw-card!)` - Draw a card
- `(end-turn!)` - End the turn
- `(choose! idx)` - Make a choice from a prompt

### State Queries
- `(get-game-state)` - Get full game state
- `(get-prompt)` - Get current prompt, if any
- `(show-status)` - Display readable game status
- `(my-hand)`, `(my-credits)`, etc. - Get specific state

## Common Patterns

### Connect and Join
```clojure
(load-file "dev/src/clj/ai_websocket_client_v2.clj")
(ai-websocket-client-v2/connect! "ws://localhost:1042/chsk")
(Thread/sleep 2000)
(ai-websocket-client-v2/join-game! {:gameid "12345..." :side "Corp"})
```

### Wait for Prompt
```clojure
(loop [checks 0]
  (when (< checks 10)
    (Thread/sleep 1000)
    (if-let [prompt (ai-websocket-client-v2/get-prompt)]
      (do
        (println "Got prompt:" (:msg prompt))
        ; Handle prompt...
        )
      (recur (inc checks)))))
```

### Make a Choice
```clojure
; By index
(ai-websocket-client-v2/choose! 0)  ; Choose first option

; Or by UUID (for more complex prompts)
(let [prompt (ai-websocket-client-v2/get-prompt)
      uuid (get-in prompt [:choices 0 :uuid])]
  (ai-websocket-client-v2/send-action! "choice" {:choice {:uuid uuid}}))
```

## Troubleshooting

**Connection fails:**
- Make sure the game server is running (`lein repl`, then `(go)`)
- Check that you're using the correct URL: `ws://localhost:1042/chsk`

**Can't join game:**
- Verify the game ID is correct
- Check that the game hasn't already started
- Make sure the side ("Corp" or "Runner") is available

**Prompts not appearing:**
- Wait longer - game state updates may take a moment
- Check that it's your turn/phase
- Use `(ai-websocket-client-v2/show-status)` to see current state
