# HITL Game Findings - 2025-11-28

## Game Summary
- **Corp**: AI-corp (Claude) playing System Gateway beginner deck
- **Runner**: Clamatius (Human) playing System Gateway beginner deck
- **Result**: Runner wins 6-5 (Superconducting Hub steal from R&D on turn 12)
- **Duration**: ~40 minutes of play

## What Worked Well

1. **Auto-end turn** - Correctly ended turns when no scorable agendas and 0 clicks
2. **Basic action flow** - Install, advance, score worked correctly
3. **Card text display on first sight** - Helped understand card abilities
4. **Status command** - Gave good visibility into game state
5. **Board command** - Visual representation was useful

## Issues Found

### 1. `--fire-unbroken` Flag Not Working Reliably ✅ FIXED
**Symptom**: `continue --fire-unbroken` returned `nil` without firing subroutines
**Workaround**: Had to use explicit `fire-subs "ICE Name"` command
**Impact**: Delayed runs, required human prompting to proceed
**Priority**: HIGH - core run mechanic
**Fix**: Two issues fixed:
  1. `handle-corp-fire-unbroken` required `my-prompt` but Corp has no prompt during encounter-ice
  2. `continue --flag` didn't route to `continue-run` handler (now it does)

### 2. Premature Rez Timing (Turn 2 HQ Run)
**What happened**: AI rezzed Tithe before runner formally approached the ICE
**Game log shows**: Subs fired at 5:23:57, but approach happened at 5:24:04
**Result**: Subs fired twice (once premature, once during proper encounter)
**Root cause**: AI didn't wait for approach-ice phase before rezzing
**Priority**: MEDIUM - affects run flow

### 3. Seamless Launch Misplay (Turn 8)
**What happened**: Played Seamless Launch on same-turn installed agenda
**Card text**: "Place 2 advancement counters on an installed card you can advance that you did not install this turn"
**Result**: Had to select "Done" and waste the operation
**Root cause**: AI didn't read card restrictions carefully
**Lesson**: Should parse card text for timing restrictions
**Priority**: LOW - player error, not system bug

### 4. Multiple Continues Required for Run Progression
**Symptom**: Single `continue` often returned `nil` without advancing phase
**Workaround**: Had to call `continue` multiple times and check `status` repeatedly
**Impact**: Tedious manual progression
**Priority**: MEDIUM - UX issue

### 5. Whitespace Subs Required Explicit Fire ✅ FIXED
**Turn 10**: Runner let Whitespace subs fire, but AI had to call `fire-subs "Whitespace"` explicitly
**Expected**: `--fire-unbroken` or automatic firing when runner passes without breaking
**Priority**: HIGH - same as issue #1
**Fix**: Same as issue #1 - `continue --fire-unbroken` now works

## Game State Observations

### Correct Behaviors
- Server 1 correctly held Asset (Urtica Cipher) + Upgrade (Manegarm Skunkworks)
- Scoring Send a Message at 5/5 worked correctly
- Agenda point tracking accurate throughout
- Credit transactions all correct
- Discard to hand size prompts worked

### Run Timing Issues Observed
```
Turn 2 HQ Run Timeline:
5:22:40 - Run initiated
5:23:12 - AI rezzes Tithe (PREMATURE - no approach yet)
5:23:57 - Subs fire (PREMATURE)
5:24:04 - Runner approaches Tithe (should be BEFORE rez)
5:24:57 - Runner encounters Tithe
5:25:01 - Runner has no further action
```

The rez and sub firing happened BEFORE the approach, which is incorrect timing.

## Recommendations

### Immediate Fixes
1. **Fix `--fire-unbroken`** - Should auto-fire unbroken subs during encounter phase
2. **Add rez timing validation** - Only allow rez during approach-ice phase, not initiation

### Future Improvements
1. **Better phase awareness** - Track run phases more explicitly
2. **Card text parsing** - Warn about timing restrictions before playing cards
3. **Single-continue run progression** - One continue should advance to next decision point

## Cards Seen This Game

### Corp
- Hedge Fund, Offworld Office (x2), Karunā, Tithe (x2), Urtica Cipher (x2)
- Manegarm Skunkworks, Palisade (x2), Whitespace, Nico Campaign
- Regolith Mining License (x2), Government Subsidy, Brân 1.0
- Send a Message (x2), Seamless Launch

### Runner
- Sure Gamble (x2), Verbal Plasticity, Creative Commission, Red Team
- Cleaver (x2 - trashed to damage), Carmen (x2), Mayfly (x2)
- Pennyshaver, Smartware Distributor (x2), Telework Contract (x2)
- Tread Lightly (trashed to damage), Jailbreak (x3), Overclock
- Unity, VRcation (discarded)

## Final Score Progression
- Turn 4: Corp 2-0 (Offworld Office scored)
- Turn 4: Corp 2-2 (Offworld Office stolen from Server 2)
- Turn 8: Corp 2-5 (Send a Message stolen from R&D)
- Turn 12: Corp 5-5 (Send a Message scored from Server 2)
- Turn 12: Corp 5-6 (Superconducting Hub stolen from R&D) - RUNNER WINS
