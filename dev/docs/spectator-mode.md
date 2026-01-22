# AI Spectator Mode

This document covers how AI clients can spectate games, enabling referee scenarios for model-vs-model matches.

## Overview

Spectator mode allows an authenticated AI client to watch a game without participating. Spectators:
- Receive real-time game state updates
- Can optionally see one side's hidden information (hand, R&D order, etc.)
- Cannot take any game actions
- Do not count toward the 2-player limit

**Source:** `dev/src/clj/ai_connection.clj` `[watch-game!]`

## Joining as Spectator

### `watch-game!`

```clojure
(ai-connection/watch-game! {:gameid "5dbeeda8-631e-407a-82d2-635b4e08fc99"
                            :perspective "Corp"})
;; => 👁️  Spectating game #uuid "..." (Corp perspective)
```

**Parameters:**

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `:gameid` | string/UUID | Yes | Game ID to spectate |
| `:perspective` | string | No | `"Corp"`, `"Runner"`, or `nil` for neutral |
| `:password` | string | No | Game password if protected |

### Getting Initial State

After joining, call `resync-game!` to receive the full game state:

```clojure
(ai-connection/watch-game! {:gameid game-id :perspective "Corp"})
(ai-connection/resync-game! game-id)
;; Now (:last-state @ai-state/client-state) contains game data
```

**Why resync is needed:** The `:lobby/watch` message joins the spectator channel but doesn't push initial state. The resync explicitly requests it.

## Perspective and Hidden Information

### Corp Perspective

Spectator sees Corp's hidden information:
- Cards in HQ (hand)
- Order of cards in R&D
- Unrezzed cards in remotes/centrals
- Corp's click/credit count

Spectator does NOT see:
- Runner's grip contents
- Runner's stack order

### Runner Perspective

Spectator sees Runner's hidden information:
- Cards in grip (hand)
- Order of cards in stack
- Installed facedown cards

Spectator does NOT see:
- Corp's HQ contents
- Corp's R&D order
- Unrezzed Corp cards

### Neutral Perspective

With `perspective: nil`, spectator sees only public information - what either player could see.

## State Structure for Spectators

```clojure
;; Check spectator status
(:spectator @ai-state/client-state)          ;; => true
(:spectator-perspective @ai-state/client-state) ;; => "Corp"

;; Access game state
(let [g (:last-state @ai-state/client-state)]
  ;; Corp hand (visible with Corp perspective)
  (get-in g [:corp :hand])    ;; => [{:title "Hedge Fund" ...} ...]

  ;; Runner hand (hidden with Corp perspective)
  (get-in g [:runner :hand])) ;; => [] (empty, not visible)
```

## Server Protocol

**Source:** Server handles `:lobby/watch` in lobby handlers

### Message: `:lobby/watch`

```clojure
;; Client sends
[:lobby/watch {:gameid #uuid "..."
               :request-side "Corp"    ; optional
               :password "secret"}]    ; optional

;; Server adds client to spectator channel
;; Client receives game updates via :game/diff messages
```

### Message: `:game/resync`

```clojure
;; Client sends
[:game/resync {:gameid #uuid "..."}]

;; Server responds with full state push
;; Filtered based on spectator perspective
```

## Use Case: AI Referee

For model-vs-model games where a third AI observes and evaluates:

```clojure
;; 1. Start game with two AI players
;;    Corp REPL on 7890, Runner REPL on 7889

;; 2. Start referee on separate REPL (port 7891)
;;    AI_USERNAME=ai-referee ./dev/start-ai-client-repl.sh referee 7891

;; 3. Referee joins as neutral spectator
(ai-connection/watch-game! {:gameid game-id})
(ai-connection/resync-game! game-id)

;; 4. Referee monitors game state
(let [state (:last-state @ai-state/client-state)]
  {:turn (:turn state)
   :corp-points (get-in state [:corp :agenda-point])
   :runner-points (get-in state [:runner :agenda-point])
   :winner (:winner state)})
```

## Spectator Limitations

Spectators cannot:
- Send any `:game/*` action messages (server ignores them)
- Vote on game decisions
- Pause/unpause the game
- Access replay controls during live game

Spectators can:
- Receive all game state updates in real-time
- Disconnect and reconnect (rejoin with `watch-game!` + `resync-game!`)
- Watch multiple games (by tracking multiple gameids, though single-connection limits apply)

## Game Configuration

For AI testing, games should be created with spectator access enabled:

```clojure
(ai-connection/create-lobby!
  {:title "AI Self-Play Test"
   :allow-spectator true      ; Allow spectators to join
   :spectatorhands true})     ; Allow spectators to see hands
```

**Note:** `:spectatorhands true` allows ANY spectator to see hands regardless of perspective. For perspective-restricted viewing, use `:spectatorhands false`.

## Troubleshooting

### Spectator sees empty game state
- Call `resync-game!` after `watch-game!`
- Verify game ID is correct and game is still active

### "Not authorized" or silent failure
- Ensure AI client is authenticated (proper login, not fallback mode)
- Check game allows spectators (`:allow-spectator true` on creation)

### State updates stop arriving
- WebSocket may have disconnected - check `(:connected @ai-state/client-state)`
- Reconnect with `watch-game!` + `resync-game!`

### Hand shows 0 cards despite perspective
- The `ai-actions/hand` command shows YOUR hand - spectators have no hand
- Access hand directly: `(get-in (:last-state @ai-state/client-state) [:corp :hand])`
