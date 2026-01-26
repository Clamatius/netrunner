# Official Timing Structures

These timing structures are taken from the official Netrunner rules. Understanding these helps debug stuck states and timing-dependent interactions.

## Corporation Turn (Rule 5.6)

**5.6.1: Draw Phase**
- a) Corporation gains allotted clicks (default: 3)
- b) Paid ability window. Corp may rez non-ice cards or score agendas during this window
- c) Corporation recurring credits refill
- d) The turn formally begins. Turn begins events resolve
- e) The corporation performs their mandatory draw
- f) Proceed to the action phase (5.6.2)

**5.6.2: Action Phase**
- a) Paid ability window. Corp may rez non-ice cards or score agendas during this window
- b) If the corporation has unspent clicks, they take an action
- c) If an action occurred, return to (a)
- d) The action phase is complete. Proceed to the discard phase (5.6.3)

**5.6.3: Discard Phase**
- a) The corporation discards to maximum hand size, if applicable
- b) Paid ability window. Corp may rez non-ice cards during this window
- c) If the corporation has any clicks remaining, they lose those clicks
- d) The Corporation's turn formally ends. Turn end triggers resolve
- e) Proceed to the Runner turn

## Runner Turn (Rule 5.7)

**5.7.1: Action Phase**
- a) Runner gains allotted clicks (default: 4)
- b) Paid ability window. Corp may rez non-ice cards
- c) Runner recurring credits refill
- d) The turn formally begins. Turn begins events resolve
- e) Paid ability window. Corp may rez non-ice cards
- f) If the Runner has unspent clicks, they take an action
- g) If an action occurred, return to (e)
- h) The action phase is complete. Proceed to the discard phase (5.7.2)

**5.7.2: Discard Phase**
- a) The runner discards to maximum hand size, if applicable
- b) Paid ability window. Corp may rez non-ice cards
- c) If the runner has any clicks remaining, they lose those clicks
- d) The Runner's turn formally ends. Turn end triggers resolve
- e) Proceed to the Corporation turn

## Run Timing Structure (Rule 6.9)

**6.9.1: Initiation Phase**
- a) Runner declares a server
- b) Runner gains Bad Publicity credits
- c) Run formally begins - Run events fire
- d) Paid Ability Window. Corp may rez non-ice cards during this window
- e) Proceed to the outermost ice, if applicable, and begin the approach phase (6.9.2)
- f) Otherwise, proceed to the movement phase (6.9.4)

**6.9.2: Approach Ice Phase**
- a) You are now approaching the ice. Approach events resolve
- b) Paid Ability Window. Corp may rez the approached ice, or non-ice cards, during this window
- c) If approached ice is rezzed, continue to encounter phase (6.9.3)
- d) Otherwise, proceed to the movement phase (6.9.4)

**6.9.3: Encounter Ice Phase**
- a) You are now encountering this ice. Encounter events resolve
- b) Paid ability window. Encountered ice may be interfaced during this window
- c) If there are unbroken subroutines to resolve, the corporation resolves the topmost unbroken subroutine. If they do, repeat this step
- d) The encounter is complete. Proceed to the movement phase (6.9.4)

**6.9.4: Movement Phase**
- a) If you were encountering or approaching an ice, you pass it. Pass-Ice events resolve
- b) If there are no more ice inwards from the passed ice, 'when you pass all ice on the server' events resolve
- c) Paid ability window
- d) The runner may jack out. If they do, proceed to the run ends phase (6.9.6)
- e) The runner proceeds to the next position inwards, if applicable
- f) Paid ability window. The corporation may rez non-ice cards
- g) If you are approaching another ice, return to the approach ice phase (6.9.2)
- h) The runner approaches the attacked server. Approach events resolve
- i) Continue to the success phase (6.9.5)

**6.9.5: Success Phase**
- a) The run is declared successful. Successful run events are met
- b) The runner breaches the attacked server
- c) The success phase is complete. Continue to the run ends phase (6.9.6)

**6.9.6: Run Ends Phase**
- a) Any open priority windows complete or are closed
- b) The runner loses any unspent bad publicity credits
- c) If the success phase was not reached and the server still exists, the run becomes unsuccessful
- d) The run ends. Run ends events resolve

## Key Timing Insights

- Paid ability windows occur between almost every action
- `continue` command advances through paid ability windows
- Jack out is ONLY legal at 6.9.4.d (movement phase)
- Discard happens BEFORE turn end (5.6.3.a, 5.7.2.a)
- Clicks are lost in discard phase if unspent (5.6.3.c, 5.7.2.c)

This reference reflects the current implementation. For complete game rules, see the Null Signal Games website.
