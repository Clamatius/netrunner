# Mechanics.md Review vs Complete Reference

**Status: REVIEWED** - Michael passed 2025-01-05, added clarifications and timing details.

Comparison of condensed `mechanics.md` against `netrunner-complete-mechanics.md`.

## Errors to Fix

### 1. Archives Access (WRONG)
**Condensed says:** "Archives - Corp's discard. Runner accesses all faceup cards."

**Should say:** Runner accesses ALL cards. Facedown cards flip faceup when Runner breaches Archives.

**Fix:** Change to "Runner accesses all cards (facedown cards flip faceup on breach)"

### 2. ICE Install Cost (MISSING)
**Condensed says:** Nothing about ICE install cost

**Complete says:** "Install cost: 1¢ per ice already protecting that server"

**Fix:** Add to Installing section: "ICE install cost: 1 credit per ICE already protecting that server"

### 3. Scoring Timing (IMPRECISE)
**Condensed says:** "Score by advancing, then taking score action when requirement met"

**Complete says:** "Timing: Only during Corp turn (not an action, free timing)"

**Fix:** Clarify scoring is free (no click cost), just needs advancement counters ≥ requirement

### 4. Jack Out Timing (IMPRECISE)
**Condensed says:** "Runner may jack out (end run voluntarily) between ICE"

**Complete says:** Jack out happens in Phase 4 (Movement), after passing ICE. Can't jack out before encountering first ICE.

**Fix:** Clarify: "Runner may jack out after passing ICE (not before first ICE)"

## Minor Gaps (Acceptable for Condensed)

### Turn Begin Triggers
Complete version mentions "when your turn begins" abilities. Relevant for Smartware Distributor, Nico Campaign. Could add a note but card text covers it.

### Memory Unit Details
Complete version has more on installing programs at MU capacity (must trash first). Current brief mention is probably sufficient.

### Rez Timing Windows
Complete version details paid ability windows. Condensed version's "almost any time" is close enough for eval purposes.

### Access Order
Complete version specifies Runner chooses access order. Minor but could matter for multi-access.

## Intentionally Omitted (Correct for Tutorial)

These are in the complete version but correctly omitted for tutorial deck eval:

- **Tags** - Mentioned as "not in tutorial", correct
- **Viruses & Purge** - Not in tutorial decks
- **Meat damage** - Not in tutorial decks (only net damage)
- **Traces** - Not in tutorial decks
- **Bad publicity** - Not in tutorial decks
- **Console limit** - Only 1 console in tutorial deck anyway

## Coverage Check

| Topic | Condensed | Complete | Notes |
|-------|-----------|----------|-------|
| Win conditions | ✓ | ✓ | Match |
| Credits/Clicks | ✓ | ✓ | Match |
| Servers | ✓ | ✓ | Archives fix needed |
| Card types | ✓ | ✓ | Good coverage |
| Installing | ~ | ✓ | Add ICE cost |
| Advancing | ✓ | ✓ | Match |
| Rezzing | ✓ | ✓ | Match |
| Running | ✓ | ✓ | Jack out timing fix |
| Breaking | ✓ | ✓ | Match |
| Accessing | ✓ | ✓ | Archives fix needed |
| Turn structure | ✓ | ✓ | Match |
| Damage | ✓ | ✓ | Net only (correct) |
| Mulligan | ✓ | ✓ | Match |

## Verdict

~~4 fixes needed, all minor.~~ Fixed + Michael's pass added:
- Game structure section (mulligan order, who goes first)
- Timing windows (end of opponent turn, paid effects between actions)
- Corp discard facedown to Archives
- Trashed cards go faceup to Archives
- Clicks as fundamental currency framing
- Tempo/advancement cost insight

Overall structure is sound for eval purposes.
