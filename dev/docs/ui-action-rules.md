# Jinteki UI Action Rules

This document distills when actions are available based on the ClojureScript UI and server-side logic.
Function names in `[brackets]` reference the actual source code.

## Dynamic "Fully Break X" Ability

**Source:** `src/clj/game/core/ice.clj` lines 744-831 `[breaker-auto-pump]`

### When the ability appears:

The "Match strength and fully break [ICE]" dynamic ability is added to a breaker's abilities when ALL of these conditions are true:

```pseudocode
IF encounter is active                             # (peek (:encounters @state)) - line 806
AND ICE is active (rezzed, in-server)              # (active-ice? state current-ice) - line 807
AND breaker has a break or pump ability            # (or break-ability pump-ability) - line 808-809
AND there are unbroken subs                        # (pos? unbroken-subs) - line 815
AND no subs are unbreakable by this card           # no-unbreakable-subs - line 813
AND no effects preventing auto-break               # can-auto-break - line 814
AND either:
    - card has no strength (AI breaker), OR
    - card can pump, OR
    - already at matching strength               # line 812
AND Runner can pay the total cost                  # (can-pay? ... total-cost) - line 816
THEN:
    Add dynamic ability {:dynamic :auto-pump-and-break, :label "Fully break [ICE]"}
```

### Events that trigger recalculation:

The ability list is recalculated on these events (line 837-838):
- `:encounter-ice` - **primary trigger** - entering encounter phase
- `:approach-ice` - approaching ICE
- `:run`, `:pass-ice`, `:run-ends` - run state changes
- `:ice-strength-changed`, `:breaker-strength-changed` - strength changes
- `:subroutines-changed`, `:ice-subtype-changed` - ICE property changes

### Key insight for AI:
**The dynamic ability ONLY EXISTS during encounter-ice phase.** If you check abilities outside of an active encounter, you won't see it.

---

## Run Phase Buttons (Corp)

**Source:** `src/cljs/nr/gameboard/board.cljs` lines 1600-1659 `[corp-run-div]`

### Rez ICE button:
```pseudocode
IF phase == "approach-ice"
AND there is ICE at current position
AND ICE is not rezzed
THEN show "Rez [ICE name]" button
```

### Fire Unbroken Subroutines button:
```pseudocode
IF phase == "encounter-ice" OR encounters state exists
AND ICE has subroutines
AND any subroutine is (not broken AND not fired AND resolve-allowed)
THEN show "Fire unbroken subroutines" button
```

### Continue button (Corp during encounter):
```pseudocode
IF encounters state exists (we're in encounter)
THEN show "Continue" button
ENABLED when: Corp hasn't already passed (no-action != "corp")
```

### Continue button (Corp at initiation):
```pseudocode
IF phase == "initiation"
THEN show "Continue to [Approach ice/server]" button
ENABLED when: no-action != "corp"
```

---

## Run Phase Buttons (Runner)

**Source:** `src/cljs/nr/gameboard/board.cljs` lines 1661-1750 `[runner-run-div]`

### Next Phase button:
```pseudocode
IF run has a next-phase set
AND phase != "initiation"
THEN show "[next phase name]" button
ENABLED when: next-phase exists AND no-action not set
```

### Continue button (Runner at initiation):
```pseudocode
IF phase == "initiation"
THEN show "Continue to [Approach ice/server]" button
ENABLED when: no-action != "runner"
```

### Continue button (Runner in encounter):
```pseudocode
IF encounters state exists
LET pass-ice? = (phase == "encounter-ice" AND encounter-count == 1)
THEN show (if pass-ice? "Continue to [next phase]" else "Continue")
ENABLED when: no-action != "runner"
```

### Jack Out button:
```pseudocode
IF (phase == "approach-ice" OR phase == "approach-server")
AND position > 0 (not at server yet)
AND cannot-jack-out is not set
THEN show "Jack Out" button
```

---

## Card Ability Menu

**Source:** `src/cljs/nr/gameboard/board.cljs` lines 490-510 `[list-abilities]`

### When clicking a card shows abilities:
```pseudocode
# handle-abilities (lines 129-170)
IF card is not facedown runner card
AND (multiple actions/abilities available
     OR has corp/runner cross-side abilities
     OR has rez/derez/advance/trash actions
     OR is unrezzed corp card)
THEN show ability menu
```

### Ability button enabled state:
```pseudocode
FOR each ability in card.abilities:
    button.enabled = ability.playable   # server sets :playable flag

    IF ability.dynamic exists:
        send "dynamic-ability" command with {:dynamic :source :index}
    ELSE:
        send "ability" command with {:ability index}
```

### Key insight for AI:
The `:playable` flag is set server-side and indicates whether the ability can currently be used. This respects:
- Cost payment ability
- Timing restrictions
- Once-per-turn limits
- Other game state requirements

---

## Priority / No-Action System

**Source:** `src/cljs/nr/gameboard/board.cljs` - run button conditions

The `:no-action` field in run/encounters state tracks who has passed priority:
- `nil` - neither side has passed
- `"runner"` - Runner has passed, waiting for Corp
- `"corp"` - Corp has passed, waiting for Runner

Continue buttons check `no-action != [my-side]` to determine if enabled.

When both sides pass, the game automatically advances to the next phase.

---

## Summary: What the AI Should Know

1. **Dynamic abilities only exist during encounter** - Don't look for "Fully break X" outside of encounter-ice phase

2. **Check `:playable` flag** - The server already computed if the ability is usable

3. **Respect `:no-action` priority** - Don't send continue when you've already passed

4. **Run phases flow:**
   ```
   initiation → approach-ice → encounter-ice → [repeat for each ICE] → approach-server → access
   ```

5. **The UI is the authority** - If the web UI would show a button as disabled, the action isn't valid

---

## Prompts

**Source:** `src/clj/game/core/prompts.clj` (server), `src/cljs/nr/gameboard/board.cljs` lines 1796-1894 `[prompt-div]`

Prompts are modal dialogs that must be resolved before the game can continue. They block other actions until dismissed.

### Prompt Types

| Type | Description | How to Resolve |
|------|-------------|----------------|
| `:other` | Default. General choice prompts | Click a button or select a card |
| `:waiting` | "Waiting for X" - cannot be dismissed | Opponent must act |
| `:select` | Targeting cursor - click cards to select | Click target card(s), then "Done" |
| `:trace` | Trace/link strength bidding | Enter credit amount, click OK |
| `:run` | Run status indicator (not modal) | Run phases resolve it |

### UI Rendering Logic

```pseudocode
# prompt-div (board.cljs:1796-1894)
GIVEN prompt with {:card, :msg, :prompt-type, :choices}

IF choices has :number field:
    RENDER number dropdown (0 to N) + OK button
    RESPOND: "choice" command with integer

ELSE IF prompt-type == "trace":
    RENDER trace-div with credit slider
    RESPOND: "choice" command with credit integer

ELSE IF choices == "credit":
    RENDER credit dropdown (0 to your credits) + OK button
    RESPOND: "choice" command with integer

ELSE IF choices has :card-title field:
    RENDER text input for card name + OK button
    RESPOND: "choice" command with string

ELSE IF choices has :counter field:
    RENDER dropdown for counter type amount + OK button
    RESPOND: "choice" command with integer

ELSE:
    RENDER button for each choice in choices (except "Hide")
    RESPOND: "choice" command with {:uuid choice-uuid}
```

### Button Pane Priority

```pseudocode
# button-pane (board.cljs:1988-1998)
IF prompt exists AND prompt-type != "run":
    SHOW prompt-div        # Prompts take precedence!
ELSE IF run or encounters active:
    SHOW run-div           # Run controls
ELSE:
    SHOW basic-actions     # Normal turn actions
```

**Key insight:** Prompts block the run interface. If a card triggers a prompt during a run (e.g., accessing an agenda with :stolen ability), the run buttons disappear until the prompt is resolved.

---

## Card Effect Prompts

**Source:** `src/clj/game/core/actions.clj`, `src/clj/game/core/access.clj`

Card effects can create prompts at specific timing windows. **These often catch AI off guard.**

### Agenda Score/Steal Triggers

When an agenda is **scored** (Corp):
```pseudocode
# actions.clj:720-726
IF agenda has :on-score ability:
    register-pending-event for :agenda-scored
    queue-event :agenda-scored
    checkpoint (waits for ability resolution)  # <-- PROMPT CREATED HERE
```

When an agenda is **stolen** (Runner):
```pseudocode
# access.clj:200-205
IF agenda has :stolen ability:
    register-pending-event for :agenda-stolen
    queue-event :agenda-stolen
    checkpoint (waits for ability resolution)  # <-- PROMPT CREATED HERE
```

### Example: Send a Message

```clojure
;; From cards/agendas.clj
(defcard "Send a Message"
  (let [ability
        {:interactive (req true)
         :choices {:card #(and (ice? %) (not (rezzed? %)) (installed? %))}
         :async true
         :effect (effect (rez eid target {:ignore-cost :all-costs}))}]
    {:on-score ability
     :stolen ability}))
```

When Send a Message is scored OR stolen:
1. Game queues the ability
2. **Prompt appears** asking Corp to choose unrezzed ICE
3. Game WAITS until Corp selects a target
4. ICE is rezzed for free
5. Then scoring/stealing completes

**If the AI doesn't handle this prompt, the game hangs waiting for a selection.**

### Common Trigger Keywords

| Keyword | When Triggered | Side |
|---------|----------------|------|
| `:on-score` | Agenda scored | Corp |
| `:stolen` | Agenda stolen | Corp (usually) |
| `:on-access` | Card accessed | Either |
| `:on-rez` | Card rezzed | Corp |
| `:on-install` | Card installed | Either |
| `:on-trash` | Card trashed | Either |

### How to Detect Pending Prompts

```pseudocode
# Check game state
IF state[side][:prompt] is non-empty:
    prompt = first(state[side][:prompt])

    IF prompt[:prompt-type] == :waiting:
        # Can't act - waiting for opponent
    ELSE IF prompt[:prompt-type] == :select:
        # Must click valid targets, then "Done"
        # Valid targets have :selected key set
    ELSE:
        # Must choose from prompt[:choices]
```

---

## Summary: Prompt Handling for AI

1. **Always check for prompts first** - Prompts block other actions

2. **Prompts can appear mid-action** - Scoring/stealing/accessing can create new prompts

3. **Know your prompt types:**
   - `:waiting` = do nothing, opponent's turn
   - `:select` = click cards then "Done"
   - `:trace` = bid credits
   - `:other` = click a choice button

4. **Card effects are the surprise** - Learn which cards create prompts:
   - Agendas with `:on-score` / `:stolen`
   - ICE/Assets/Upgrades with `:on-rez`
   - Anything with `:on-access`

5. **The game hangs if you ignore prompts** - They must be resolved to continue
