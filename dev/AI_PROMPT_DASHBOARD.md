# AI Player Prompt Dashboard

## Critical Learning: Prompts = "Wrong Button Detection"

**When prompts are open unexpectedly, it means the AI player did something wrong and needs to recover.**

## Prompt Checking Functions

Available in `game_command_test.clj`:

### `(check-prompts state)`
- Checks both Corp and Runner for open prompts
- Prints detailed information: message, type, choices
- Returns `true` if prompts are open
- Excludes `:run` prompts (those are normal during runs)

```clojure
(when (check-prompts state)
  (println "Need to handle a prompt!"))
```

### `(assert-no-prompts state "context message")`
- Validation helper that prints error if prompts exist
- Shows helpful hints about what went wrong
- Use after actions that should complete cleanly

```clojure
(play-from-hand state :corp "Hedge Fund")
(assert-no-prompts state "after playing Hedge Fund")
```

### `(has-prompts? state)`
- Simple boolean check
- Returns `true` if either side has prompts
- Good for AI decision loops

```clojure
(if (has-prompts? state)
  (handle-prompt state)
  (continue-playing state))
```

## Common Prompt Causes

### 1. Card Doesn't Exist
```clojure
;; ❌ BAD - leaves prompts open
(play-from-hand state :corp "Nico Campaign")  ; Not in hand!

;; ✓ GOOD - verify card is in hand first
(when (some #(= "Nico Campaign" (:title %)) (:hand (:corp @state)))
  (play-from-hand state :corp "Nico Campaign"))
```

### 2. Missing Required Choice
Some cards require choices (server, target, etc.):
```clojure
(play-from-hand state :corp "Ice Wall" "HQ")  ; Needs server choice
(click-prompt state :corp "HQ")  ; Must answer prompt!
```

### 3. Triggered Abilities
Some cards trigger automatically and require responses:
```clojure
;; Offworld Office scores and prompts for placement choice
(score-agenda state :corp agenda)
(click-prompt state :corp "Done")  ; Must dismiss prompt
```

## Turn Transition and Prompts

**Critical:** `take-credits` checks for prompts and will fail if they're open:

```clojure
;; From test_framework.clj line 278:
(defmacro take-credits [state side]
  `(let [other# (if (= ~side :corp) :runner :corp)]
     (error-wrapper (ensure-no-prompts ~state))  ; ← CHECKS HERE!
     (dotimes [_# (or ~n (get-in @~state [~side :click]))]
       (core/process-action "credit" ~state ~side nil))
     (when (zero? (get-in @~state [~side :click]))
       (end-turn ~state ~side)
       (start-turn ~state other#))))
```

**If turn transitions fail mysteriously, check for open prompts!**

## AI Player Strategy

### Before Each Action
```clojure
(defn ai-take-action [state side action]
  ;; Clear any stale prompts first
  (when (has-prompts? state)
    (handle-or-clear-prompts state side))

  ;; Take action
  (execute-action state side action)

  ;; Verify clean state
  (assert-no-prompts state (str "after " action)))
```

### Prompt Recovery
When prompts are detected:
1. **Log the prompt details** - what is being asked?
2. **Check if it's expected** - some prompts are normal (scoring, installing ICE)
3. **Handle or abort** - respond to expected prompts, abort on unexpected
4. **Learn** - add this pattern to training data

```clojure
(defn handle-unexpected-prompt [state side]
  (let [prompt (get-prompt state side)]
    (println "Unexpected prompt:" (:msg prompt))
    (println "Type:" (:prompt-type prompt))
    (println "Choices:" (:choices prompt))

    ;; Attempt generic recovery
    (case (:prompt-type prompt)
      :select (click-prompt state side "Done")  ; Try to dismiss
      :waiting nil  ; Waiting for opponent, that's ok
      (println "Unknown prompt type - may need manual intervention"))))
```

## Testing Checklist

When writing new AI player tests:

- [ ] Add `(assert-no-prompts state "context")` after complex actions
- [ ] Check prompts before turn transitions
- [ ] Verify card exists in hand before playing
- [ ] Handle expected prompts (server selection, scoring, etc.)
- [ ] Test recovery from "wrong button" scenarios

## Example: Clean Test Flow

```clojure
(defn test-clean-flow []
  (let [state (new-game ...)]
    ;; Setup
    (start-turn state :corp)
    (assert-no-prompts state "after start-turn")

    ;; Play operation
    (play-from-hand state :corp "Hedge Fund")
    (assert-no-prompts state "after Hedge Fund")

    ;; Install with server choice
    (play-from-hand state :corp "Ice Wall" "HQ")
    ;; Prompt is EXPECTED here - answer it
    (assert-no-prompts state "after Ice Wall install")

    ;; End turn
    (take-credits state :corp)  ; Has built-in prompt check!

    ;; Start Runner turn
    (start-turn state :runner)
    (assert-no-prompts state "after start-turn runner")))
```

## Future: Prompt Dashboard UI

For the AI player, we should build a "prompt dashboard" that shows:
- Current open prompts for both sides
- Prompt history (what was asked, how we responded)
- Unexpected prompt alerts
- Suggested responses based on prompt type

This will be critical for debugging AI player decisions and training it to handle prompts correctly.
