# Netrunner Game Actions Reference

This document catalogs all game actions available to the AI player, extracted from the test framework.

All actions are sent via `core/process-action` with the format:
```clojure
(core/process-action action-type state side data)
```

## Turn Management

### start-turn
**Action:** `"start-turn"`
**Side:** `:corp` or `:runner`
**Data:** `nil`
**Description:** Starts the side's turn (draws cards, gains clicks)
**Example:**
```clojure
(core/process-action "start-turn" state :corp nil)
```

### end-turn
**Action:** `"end-turn"`
**Side:** `:corp` or `:runner`
**Data:** `nil`
**Description:** Ends the current turn
**Example:**
```clojure
(core/process-action "end-turn" state :corp nil)
```

---

## Basic Click Actions

### credit
**Action:** `"credit"`
**Side:** `:corp` or `:runner`
**Data:** `nil`
**Description:** Spend 1 click to gain 1 credit
**Test Helper:** `(take-credits state side)` - takes credits for remaining clicks and ends turn
**Example:**
```clojure
(core/process-action "credit" state :runner nil)
```

### draw
**Action:** `"draw"`
**Side:** `:corp` or `:runner`
**Data:** `nil`
**Description:** Spend 1 click to draw 1 card
**Example:**
```clojure
(core/process-action "draw" state :runner nil)
```

---

## Playing Cards

### play
**Action:** `"play"`
**Side:** `:corp` or `:runner`
**Data:** `{:card card :server server}` (server optional for non-installables)
**Description:** Play a card from hand
**Test Helper:** `(play-from-hand state side "Card Title" server)`
**Example:**
```clojure
;; Play an event
(core/process-action "play" state :runner {:card card})

;; Install a card to a server
(core/process-action "play" state :corp {:card card :server "HQ"})

;; Install to new remote
(core/process-action "play" state :corp {:card card :server "New remote"})
```

---

## Runner Actions

### run
**Action:** `"run"`
**Side:** `:runner` (always)
**Data:** `{:server server}`
**Description:** Start a run on a server
**Test Helper:** `(run-on state server)` - includes auto-continue past initiation
**Servers:** `:hq`, `:rd`, `:archives`, `:remote1`, `:remote2`, etc.
**Example:**
```clojure
(core/process-action "run" state :runner {:server :hq})
```

### continue
**Action:** `"continue"`
**Side:** `:corp` or `:runner` (depends on phase)
**Data:** `nil`
**Description:** Continue past a run phase (approach ice, approach server, etc.)
**Example:**
```clojure
;; Corp passes priority at approach ice
(core/process-action "continue" state :corp nil)

;; Runner continues past ice
(core/process-action "continue" state :runner nil)
```

### jack-out
**Action:** `"jack-out"`
**Side:** `:runner`
**Data:** `nil`
**Description:** Jack out of the current run
**Example:**
```clojure
(core/process-action "jack-out" state :runner nil)
```

### remove-tag
**Action:** `"remove-tag"`
**Side:** `:runner`
**Data:** `nil`
**Description:** Spend 1 click and 2 credits to remove 1 tag
**Example:**
```clojure
(core/process-action "remove-tag" state :runner nil)
```

---

## Corp Actions

### rez
**Action:** `"rez"`
**Side:** `:corp`
**Data:** `{:card card}`
**Description:** Rez a card (ice, asset, upgrade)
**Test Helper:** `(rez state side card)`
**Example:**
```clojure
(core/process-action "rez" state :corp {:card ice})
```

### derez
**Action:** `"derez"`
**Side:** `:corp` (usually, but can be `:runner` for some effects)
**Data:** `{:card card}`
**Description:** Derez a card
**Example:**
```clojure
(core/process-action "derez" state :corp {:card ice})
```

### advance
**Action:** `"advance"`
**Side:** `:corp`
**Data:** `{:card card}`
**Description:** Spend 1 click and 1 credit to place 1 advancement token on a card
**Example:**
```clojure
(core/process-action "advance" state :corp {:card agenda})
```

### score
**Action:** `"score"`
**Side:** `:corp`
**Data:** `{:card card}` (plus optional args)
**Description:** Score an agenda from a remote server
**Example:**
```clojure
(core/process-action "score" state :corp {:card agenda})
```

### trash-resource
**Action:** `"trash-resource"`
**Side:** `:corp`
**Data:** `nil`
**Description:** Spend 1 click and 2 credits to trash a runner resource (if runner is tagged)
**Example:**
```clojure
(core/process-action "trash-resource" state :corp nil)
```

---

## Prompt Responses

### choice
**Action:** `"choice"`
**Side:** `:corp` or `:runner`
**Data:** `{:choice choice :eid eid}`
**Description:** Respond to a prompt with a text/number choice
**Test Helper:** `(click-prompt state side "Choice Text")`
**Example:**
```clojure
;; Choose "Keep" in mulligan prompt
(core/process-action "choice" state :corp {:choice {:uuid choice-uuid} :eid eid})

;; Numeric choice (credits to pay for trace)
(core/process-action "choice" state :runner {:choice 3 :eid eid})
```

### select
**Action:** `"select"`
**Side:** `:corp` or `:runner`
**Data:** `{:card card :eid eid}`
**Description:** Respond to a prompt by selecting a card
**Test Helper:** `(click-card state side card)`
**Example:**
```clojure
(core/process-action "select" state :runner {:card target-card :eid eid})
```

---

## Card Abilities

### ability
**Action:** `"ability"`
**Side:** `:corp` or `:runner`
**Data:** `{:card card :ability ability-index}`
**Description:** Trigger a card's ability (0-indexed)
**Test Helper:** `(card-ability state side card ability-index)`
**Example:**
```clojure
;; Trigger first ability (index 0)
(core/process-action "ability" state :runner {:card card :ability 0})
```

### corp-ability / runner-ability
**Action:** `"corp-ability"` or `"runner-ability"`
**Side:** `:corp` or `:runner`
**Data:** ability-data
**Description:** Trigger basic action card abilities
**Example:**
```clojure
(core/process-action "corp-ability" state :corp ability-data)
```

### dynamic-ability
**Action:** `"dynamic-ability"`
**Side:** `:runner` (typically)
**Data:** `{:dynamic "auto-pump"}` or `{:dynamic "auto-pump-and-break"}`
**Description:** Auto-pump icebreakers or auto-pump-and-break
**Example:**
```clojure
(core/process-action "dynamic-ability" state :runner {:dynamic "auto-pump-and-break" :card breaker})
```

---

## Miscellaneous Actions

### trash
**Action:** `"trash"`
**Side:** `:corp` or `:runner`
**Data:** `{:card card}`
**Description:** Trash a card from hand or board
**Example:**
```clojure
(core/process-action "trash" state :runner {:card card})
```

### subroutine
**Action:** `"subroutine"`
**Side:** `:corp`
**Data:** `{:card ice :subroutine ability}`
**Description:** Fire an ice subroutine (used in tests to manually trigger subs)
**Example:**
```clojure
(core/process-action "subroutine" state :corp {:card ice :subroutine 0})
```

### unbroken-subroutines
**Action:** `"unbroken-subroutines"`
**Side:** `:corp`
**Data:** `{:card ice}`
**Description:** Fire all unbroken subroutines on ice
**Example:**
```clojure
(core/process-action "unbroken-subroutines" state :corp {:card ice})
```

---

## Test Framework Helpers

These are common test helper functions that wrap `process-action`:

```clojure
;; Setup
(do-game ...)                                    ; Create a game context
(new-game {:corp {:deck [...] :hand [...]}      ; Initialize a game
          :runner {:deck [...] :hand [...]}})

;; Turn flow
(start-turn state side)                         ; Start turn
(take-credits state side)                       ; Take credits for remaining clicks, end turn

;; Playing cards
(play-from-hand state side "Card Title")        ; Play/install from hand
(play-from-hand state side "Card Title" "New remote")

;; Runs
(run-on state :hq)                              ; Start run and auto-continue initiation
(run-empty-server state :rd)                    ; Run server with no ice, access cards
(run-continue state)                            ; Continue to next phase
(run-jack-out state)                            ; Jack out

;; Corp actions
(rez state :corp card)                          ; Rez a card
(advance state card)                            ; Advance a card
(score-agenda state :corp agenda)               ; Score an agenda

;; Prompts
(click-prompt state side "Choice Text")         ; Click a prompt choice
(click-card state side card)                    ; Select a card in a prompt

;; Abilities
(card-ability state side card ability-index)    ; Trigger a card ability

;; Card access
(get-corp)                                      ; Get corp player state
(get-runner)                                    ; Get runner player state
(find-card "Card Title" zone)                   ; Find a card in a zone
```

---

## Common Patterns

### Corp Turn Pattern
```clojure
(start-turn state :corp)
(core/process-action "credit" state :corp nil)  ; Click for credit
(core/process-action "draw" state :corp nil)    ; Click to draw
(play-from-hand state :corp "Ice Wall" "HQ")    ; Install ice
(end-turn state :corp)
```

### Runner Turn Pattern
```clojure
(start-turn state :runner)
(core/process-action "run" state :runner {:server :hq})    ; Make a run
;; ... run phases ...
(core/process-action "credit" state :runner nil)            ; Click for credit
(end-turn state :runner)
```

### Run Sequence
```clojure
;; Start run
(core/process-action "run" state :runner {:server :hq})

;; Corp passes priority at initiation
(core/process-action "continue" state :corp nil)

;; Runner continues
(core/process-action "continue" state :runner nil)

;; Approach ice
(core/process-action "continue" state :corp nil)  ; Corp passes
(core/process-action "continue" state :runner nil)  ; Runner continues

;; Either break ice subs or jack out
(core/process-action "jack-out" state :runner nil)
```

---

## Next Steps for AI

To build an AI player, you need to:

1. **Read game state**: `@state` contains full game state
2. **Identify current prompt**: Check `(get-in @state [side :prompt-state])`
3. **Determine valid actions**: Based on clicks, credits, cards in hand
4. **Send appropriate action**: Via `core/process-action` or WebSocket equivalent

For WebSocket clients (like our AI), actions are sent as:
```clojure
[:game/action {:gameid gameid :command "action-type" :args {...}}]
```
