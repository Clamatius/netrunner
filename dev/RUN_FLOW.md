# Run Flow Reference

## Overview
This document describes the command sequence for runs on servers, based on wire protocol observations.

## Basic Run Sequence

### 1. Initiate Run
**Runner:**
```bash
./send_command run "Server 2"  # or HQ, R&D, Archives
```

### 2. Approach ICE (if present)
**Runner:** Passes priority in paid ability window
```bash
./send_command continue
```

**Corp:** Gets opportunity to rez ICE or continue
```clojure
;; Corp sends one of:
{:command "rez" :args {:card {...}}}
{:command "continue" :args nil}
```

### 3. Encounter ICE (if rezzed)
**Runner:** Uses icebreaker abilities to break subroutines
```bash
# Use icebreaker ability to break subroutines
./send_command use-ability "Unity" 0  # Break code gate sub
./send_command continue  # When done breaking
```

**Corp:** Fires unbroken subroutines
```clojure
{:command "unbroken-subroutines" :args {:card {...}}}
```

### 4. Access Cards (if successful)
If run is successful, Runner accesses cards from the server.

## Wire Protocol Example

From Corp perspective during ICE encounter (messages shown in reverse chronological order):

```clojure
;; Last (fires unbroken subs)
[[:game/action {:command "unbroken-subroutines", :args {:card {...}}}]]

;; Middle (continue to encounter)
[[:game/action {:command "continue", :args nil}]]

;; First (rez the ICE)
[[:game/action {:command "rez", :args {:card {...}}}]]
```

## ICE Breaking Mechanics

### Full Break (Auto-break)
Some icebreakers have auto-break abilities for specific ICE:
```bash
./send_command run HQ
./send_command continue  # Approach
./send_command continue  # Encounter
./send_command use-ability "Unity" 2  # "Fully break Whitespace"
./send_command continue  # Done breaking
# Corp has no unbroken subs to fire
```

### Partial Break (Manual subroutine selection)
Breaking individual subroutines is a multi-step process:

1. **Use break ability** (typically ability 0)
   ```bash
   ./send_command use-ability "Unity" 0  # "Break 1 Code Gate subroutine"
   ```

2. **Select which subroutines to break** (opens prompt)
   ```
   Prompt: "Break a subroutine"
   Choices:
     0. [Subroutine 0 text]
     1. [Subroutine 1 text]
     2. Done
   ```

3. **Choose subroutine(s) to break**
   ```bash
   ./send_command choose 0  # Break sub 0
   # Prompt updates with remaining subs
   ./send_command choose 1  # Done (or break more subs)
   ```

4. **Pass priority** when done breaking
   ```bash
   ./send_command continue
   ```

5. **Corp fires unbroken subs**
   ```bash
   ./send_command fire-subs "Whitespace"
   ```

**Example - Partial Break Strategy:**
- ICE: Whitespace (2 subs: "Lose 3 credits", "ETR if ≤6 credits")
- Strategy: Break sub 0, let sub 1 fire to end run
- Saves credits when you can't/don't want to continue the run

## Commands Needed for Full Run Support

### Implemented ✅
- `run <server>` - Initiate run
- `continue` - Pass priority / proceed
- `jack-out` - End run unsuccessfully
- `use-ability <name> <N>` - Use card ability (for breaking)

### Future Enhancements 💡
- `break-ice <breaker>` - Auto-break all matching subs with breaker
- `break-sub <breaker> <sub-index>` - Break specific subroutine
- Helper to detect ICE type and suggest appropriate breaker
- Auto-calculate break costs and strength requirements

## Test Cases

### Test 1: Unity vs Whitespace (Full Break) ✅
**Scenario:** Runner with Unity runs on HQ, fully breaks Whitespace (code gate)

**Result:**
- Unity fully breaks Whitespace using auto-break ability
- Runner accesses HQ successfully

**Commands:**
```bash
./send_command run HQ
./send_command continue           # Pass approach priority
./send_command continue           # Continue to encounter
./send_command use-ability "Unity" 2  # Fully break Whitespace
./send_command continue           # Done breaking
./send_command continue           # Proceed to access
# Access HQ cards
```

### Test 2: Unity vs Whitespace (Partial Break) ✅
**Scenario:** Runner with Unity runs on HQ, breaks only sub 0 of Whitespace

**Result:**
- Unity breaks sub 0 ("Lose 3 credits")
- Corp fires sub 1 ("ETR if ≤6 credits")
- Run ends

**Commands:**
```bash
./send_command run HQ
./send_command continue           # Pass approach priority
./send_command continue           # Continue to encounter
./send_command use-ability "Unity" 0  # Break 1 subroutine
./send_command choose 0           # Break sub 0 (lose 3 credits)
./send_command choose 1           # Done breaking
./send_command continue           # Pass priority
# Corp fires unbroken subs
./send_command fire-subs "Whitespace"
```

**Log Output:**
```
AI-fixed-id pays 1 [credits] to use Unity to break 1 Code Gate subroutine on Whitespace.
Clamatius uses Whitespace to end the run.
Clamatius resolves 1 unbroken subroutine on Whitespace.
```

### Test 3: Unity vs Palisade (Type Mismatch) ✅
**Scenario:** Runner with Unity (code gate breaker) runs on Server 2 with Palisade (barrier)

**Result:**
- Unity cannot break Palisade (type mismatch)
- Corp fires unbroken subroutines
- Run ends

**Commands:**
```bash
./send_command install Unity      # 1 click, 3 credits
./send_command run "Server 2"     # 1 click
./send_command continue           # Pass approach priority
# Corp rezzes Palisade
./send_command continue           # No way to break, proceed
# Corp fires subs, run ends
```

**Log Output:**
```
AI-fixed-id spends [click] and pays 3 [credits] to install Unity.
AI-fixed-id spends [click] to make a run on Server 2.
AI-fixed-id approaches ice protecting Server 2 at position 0.
Clamatius pays 3 [credits] to rez Palisade protecting Server 2 at position 0.
AI-fixed-id encounters Palisade protecting Server 2 at position 0.
Clamatius uses Palisade to end the run.
Clamatius resolves 1 unbroken subroutine on Palisade ("[subroutine] End the run").
```
