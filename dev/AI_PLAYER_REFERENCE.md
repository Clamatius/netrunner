# AI Player Quick Reference

## The Essence

**Don't read this document. Read the code.**

Every test function in `game_command_test.clj` is a **working example** of how to do something in Netrunner. The AI player learns by seeing working code, not by reading planning documents.

---

## How To Use This

1. **Want to learn how to do X?** → Find the test that does X
2. **Run the test** → See it work
3. **Read the code** → See exactly what actions were called
4. **Copy the pattern** → Use it in your AI logic

---

## Working Examples (As Built)

### ✅ Phase 1: COMPLETE

| Test Function | What It Demonstrates | Key Actions |
|--------------|---------------------|-------------|
| `test-basic-turn-flow` | Taking credits, drawing cards, ending turn | `(core/process-action "credit" ...)`, `(take-credits ...)` |
| `test-playing-cards` | Playing operations, installing ICE & assets | `(play-from-hand state :corp "Hedge Fund")`, `(play-from-hand state :corp "Palisade" "HQ")` |

### 🔜 Phase 2: Corp Board Development (Next Up)

| Test Function | What It Demonstrates | Status |
|--------------|---------------------|--------|
| `test-asset-management` | Install & rez assets, use abilities | 🏗️ To build |
| `test-agenda-scoring` | Advance & score agendas | 🏗️ To build |
| `test-ice-installation` | Install ICE on multiple servers | 🏗️ To build |

### ⏸️ Phase 3-7: Coming Soon...

---

## Code Patterns You'll See

### Basic Pattern: Take An Action
```clojure
;; Before
(print-game-state state)  ; Runner has 5 credits

;; Action
(core/process-action "credit" state :runner nil)

;; After
(print-game-state state)  ; Runner has 6 credits
```

### Install & Use Pattern
```clojure
;; Install a card
(play-from-hand state :corp "Nico Campaign" "New remote")

;; Rez it
(let [nico (get-content state :remote1 0)]
  (rez state :corp nico))

;; Use its ability
(card-ability state :corp nico 0)  ; Trigger ability #0
```

### Run Pattern
```clojure
;; Start run
(run-on state :hq)

;; Both sides pass priority through phases
(core/process-action "continue" state :corp nil)
(core/process-action "continue" state :runner nil)

;; Access cards
(click-prompt state :runner "No action")  ; Or "Steal" for agendas
```

---

## The Real Documentation

The real documentation is in **3 places**:

1. **`game_command_test.clj`** - Working example code
2. **`test/clj/game/cards/*.clj`** - Thousands of card tests showing mechanics
3. **REPL experimentation** - Try things, see what happens

### Example: Learning How Breakers Work

**Don't read docs. Run this:**

```clojure
;; Load the test file
(load-file "dev/src/clj/game_command_test.clj")
(in-ns 'game-command-test)

;; Run the test (when it exists)
(test-breaking-barrier)

;; See the output - it shows every action
;; Read the code - it shows exactly how to call each action
;; Now you know how breakers work
```

---

## When Tests Don't Exist Yet

If a test doesn't exist yet for the mechanic you need:

1. **Look at card tests**: `test/clj/game/cards/` has examples for almost everything
2. **Check AI_GAME_ACTIONS.md**: Lists all available actions with basic syntax
3. **Experiment in REPL**:
   ```clojure
   (def my-state (open-hand-game))
   (play-from-hand my-state :corp "Hedge Fund")
   (print-game-state my-state)
   ```

---

## Progress Tracker

As tests are built, update this table:

| Phase | Tests Complete | Tests Remaining |
|-------|----------------|-----------------|
| Phase 1: Foundation | 2 ✅ | 0 |
| Phase 2: Corp Board | 0 | 3 |
| Phase 3: Runner Rig | 0 | 3 |
| Phase 4: Basic Runs | 0 | 3 |
| Phase 5: ICE Encounters | 0 | 5 |
| Phase 6: Advanced Runs | 0 | 3 |
| Phase 7: Complex States | 0 | 3 |
| **Total** | **2** | **20** |

---

## For AI: How To Learn

You're an AI that needs to play Netrunner. Here's how:

1. **Start with working tests**: Run `(test-basic-turn-flow)` to see turns
2. **Read the code for that test**: See what actions it calls
3. **Copy those patterns**: Use them in your decision logic
4. **When you need something new**: Run the test that demonstrates it
5. **Build incrementally**: Master simple actions before complex ones

### Learning Order (Recommended)

1. ✅ Basic turns (`test-basic-turn-flow`)
2. ✅ Installing cards (`test-playing-cards`)
3. 🔜 Using abilities (`test-asset-management`)
4. 🔜 Scoring agendas (`test-agenda-scoring`)
5. 🔜 Simple runs (`test-unopposed-central-runs`)
6. 🔜 Breaking ICE (`test-breaking-barrier`)
7. ... (continue with iteration plan)

---

## Key Insight

**Code is truth. Documentation lies.**

A working test that breaks a barrier is worth more than 100 pages explaining how barriers work. When you see:

```clojure
(card-ability state :runner cleaver 0)  ; Breaks 2 barrier subs
```

You learn:
- Ability index is 0
- It's called on the runner's breaker
- It happens during an encounter
- It actually works (test passes)

That's everything you need to know.

---

## Building Your AI Brain

### Simple AI (Phase 1-2 knowledge)
```clojure
(defn simple-ai-turn [state side]
  ;; If I have economy cards, play them
  (when-let [hedge-fund (find-card "Hedge Fund" (:hand (get-corp)))]
    (play-from-hand state :corp "Hedge Fund"))

  ;; Click for credits with remaining clicks
  (take-credits state side))
```

### Smarter AI (Phase 2-3 knowledge)
```clojure
(defn smarter-ai-turn [state side]
  ;; Install breakers if in hand
  ;; Use asset abilities for economy
  ;; Make runs if I have breakers
  ;; Else click for credits
  ...)
```

### Advanced AI (Phase 4-5 knowledge)
```clojure
(defn advanced-ai-run [state]
  ;; Evaluate: Can I break the ICE on this server?
  ;; Calculate: Do I have enough credits?
  ;; Decide: Is it worth running?
  ;; Execute: Run and break ICE if needed
  ...)
```

Each phase of tests unlocks new capabilities for your AI brain.

---

## The Vision

By the end of all iterations, you'll have **~20 working test functions** that collectively demonstrate **every game mechanic**.

An AI that can read and understand these tests can play Netrunner.

That's the cockpit. That's the essence.
