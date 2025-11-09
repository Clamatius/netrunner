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

## ICE Breaking Logic (TODO)

For a full AI implementation, Runner needs to:

1. **Detect ICE type** from game state during encounter
2. **Match icebreaker** - find installed breaker that can break this type
   - Barrier → Barrier breaker (e.g., Corroder)
   - Code Gate → Code Gate breaker (e.g., Unity)
   - Sentry → Sentry breaker (e.g., Mimic)
3. **Break subroutines** using icebreaker abilities
   ```bash
   # Example: Unity breaking code gate subs
   ./send_command use-ability "Unity" 0  # Usually ability 0 is break
   ```
4. **Pay costs** - breaking costs credits and may have strength requirements
5. **Continue** when done breaking
   ```bash
   ./send_command continue
   ```

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

## Test Case: Unity vs Palisade

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
