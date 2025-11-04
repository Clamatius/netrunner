# AI Client Testing Guide

This guide explains how to test the AI WebSocket clients and verify the full game flow.

## Quick Start

From your REPL (after `lein repl` and `(go)`):

```clojure
;; Load the test file
(load-file "dev/src/clj/full_game_test.clj")

;; Switch to the test namespace
(in-ns 'full-game-test)

;; Run the full game test
(run-full-game-test!)

;; After waiting ~10 seconds for game to start, check state
(check-state)
```

Expected output from `(check-state)`:
```
=== GAME STATE ===

[ :corp ]
  Credits: 5
  Clicks: 0
  Hand size: 5
  Deck size: 29

[ :runner ]
  Credits: 5
  Clicks: 0
  Hand size: 5
  Deck size: 25

=== DECK VERIFICATION ===
Corp:  29 cards in deck, 5 in hand (expect ~29 in deck after draw)
Runner: 25 cards in deck, 5 in hand (expect ~25 in deck after draw)
```

## Deck Loading Verification

The test now loads System Gateway beginner teaching decks:

- **Corp deck**: 34 cards (29 in deck after 5-card starting hand)
  - Identity: The Syndicate: Profit over Principle
  - 3x Offworld Office, 2x Send a Message, 2x Superconducting Hub, etc.

- **Runner deck**: 30 cards (25 in deck after 5-card starting hand)
  - Identity: The Catalyst: Convention Breaker
  - 2x Creative Commission, 3x Jailbreak, 3x Sure Gamble, etc.

If deck counts are 0, the preconstructed decks didn't load correctly.

## Checking Current Prompts

To see what prompts the AI clients currently have:

```clojure
(check-prompts)
```

Expected output after game start (mulligan phase):
```
=== CURRENT PROMPTS ===

[ :corp ]
  Message: Keep hand?
  Type: :mulligan
  Choices: ["Keep" "Mulligan"]

[ :runner ]
  Message: Waiting for Corp to keep hand or mulligan
  Type: :waiting
  Choices: []
```

## Responding to Prompts

To respond to the mulligan prompt, you need to send a choice action. Here's the structure:

```clojure
;; Get the current prompt info
(let [gs (get-in @clients [:corp :game-state])
      prompt (get-in gs [:corp :prompt-state])
      choices (:choices prompt)
      keep-choice (first (filter #(= "Keep" (:value %)) choices))]
  (println "Choice UUID:" (:uuid keep-choice))
  (println "Prompt EID:" (:eid prompt)))

;; Send the choice (using the UUID from above)
(send-action! :corp "choice" {:choice {:uuid "<uuid-from-above>"}
                               :eid <eid-from-above>})
```

However, the exact format for choices over WebSocket may differ from test framework.
We need to verify the correct format by inspecting server code or experimenting.

## Next Steps: Implementing AI Responses

1. **Mulligan logic**: Implement auto-keep or auto-mulligan
2. **Turn actions**: Click for credits, draw cards, play cards from hand
3. **Run logic**: Make runs, respond to ICE
4. **Corp logic**: Install and rez ICE, advance agendas

See `dev/AI_GAME_ACTIONS.md` for complete action documentation.

## Useful Helper Functions

```clojure
;; Check all client state
@clients

;; Get Corp's game state
(get-in @clients [:corp :game-state])

;; Get Runner's game state
(get-in @clients [:runner :game-state])

;; Get Corp's hand cards (if visible)
(get-in @clients [:corp :game-state :corp :hand])

;; Check all messages received by a client
(get-in @clients [:corp :messages])

;; Clean up and reset
(doseq [name [:corp :runner]]
  (when-let [socket (get-in @clients [name :socket])]
    (ws/close socket)))
(reset! clients {})
```

## Common Issues

**Issue**: Deck counts are 0
**Solution**: Verify `:gateway-type "beginner"` is set in lobby/create call

**Issue**: "No socket available" when sending actions
**Solution**: Check that `(run-full-game-test!)` completed successfully

**Issue**: Game ends immediately with "Decked"
**Solution**: This happens if decks are empty - verify preconstructed decks loaded

**Issue**: Can't parse messages - time/instant error
**Solution**: Verify EDN reader `{'time/instant #(Instant/parse %)}` is defined

## Debugging

Check the server console and `dev/repl-errors.log` for server-side errors.

Check `CLAUDE.local.md` for recent error summaries.

Watch the console output during `(run-full-game-test!)` to see connection flow:
- `[SEQ:XXXXX]` lines show sequence of events
- Look for "✅ Connected!" messages
- Look for "🎮 Joined game:" messages
- Look for "🎮 GAME STARTED!" messages
