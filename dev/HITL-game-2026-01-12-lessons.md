# HITL Game Session 2026-01-12 - Lessons Learned

**Result:** Runner technical win (Corp misplay on timing - fired subs before Runner finished breaking)

**Final Score:** 0-5 (but Runner wins on technicality)

## Critical Playbook Gaps Identified

### Gap #1: R&D Physicality

The playbook doesn't explain that R&D is an **ordered deck**:

- Top card stays on top until Corp draws (mandatory draw or click action)
- If you access the same card twice in a row, Corp hasn't drawn - **stop running R&D**
- Drawn cards go to HQ, then possibly Archives if discarded
- "2 cards deep" = accessible turn after next (unless Corp takes extra draw actions)
- This is crucial for efficient pressure - don't waste clicks on R&D when you know top card is junk

**Strategic implications:**
- After accessing R&D, check if Corp drew before running again
- Track Corp draw patterns to predict when new cards become accessible
- Information flows: R&D → HQ → (scored/installed/Archives)

### Gap #2: Breaking ICE Mechanics

Needs a dedicated "Breaking ICE For Noobs" section:

1. **Use `abilities <card>` during encounters** to see all options
2. **"Fully break X" ability** (often index 2) breaks all subs at once - USE THIS
3. **Manual sub-by-sub breaking** exists but is advanced/situational
4. **Timing is critical:** Break ALL subs before passing priority
5. **Strength matching:** Breaker strength must be >= ICE strength before breaking

**The Mayfly Disaster Pattern:**
- Runner breaks sub 0, Corp immediately fires sub 1
- This happened because of HITL timing (Corp didn't wait long enough)
- But also: Runner should have used "Fully break" if available
- Playbook should emphasize: always check for "Fully break X" ability first

### Gap #3: Breaker Type Matching

Playbook mentions this but needs more emphasis:

- Unity = Code Gate breaker (Whitespace, Diviner)
- Cleaver = Barrier breaker (Palisade)
- Carmen = Sentry breaker
- **Can't break ICE with wrong breaker type** - critical to check before running

## Technical Issues Observed

### Kick/Rejoin Spam

Every command showed:
```
⚠️  Kicked from game detected - auto-resyncing...
   Rejoining game: #uuid "..."
✅ Resynced successfully
```

**Analysis:** Not actual kicks - game state persisted fine. Likely over-eager stale state detector. Low priority but worth investigating. Check `ai_connection.clj` or `ai_websocket_client_v2.clj` for the detection logic.

### Telework Contract Ability Timeout

`use-ability "Telework Contract" 0` sometimes timed out with:
```
❌ Ability failed: Telework Contract - Ability not confirmed in game log (timeout)
```

But the ability actually worked (credits were gained). Possible log detection race condition.

## What Worked Well

1. **"Fully break" ability** - Once discovered, R&D pressure was consistent
2. **Economy timing** - Creative Commission on last click (no penalty), Telework draining
3. **Tread Lightly** - Made ICE +3 to rez, got into unprotected HQ
4. **Trashing traps** - Denied Urtica Cipher from R&D

## What Went Poorly

1. **Early breaker hunt** - Drew many events, no programs for several turns
2. **50/50 remote choice** - Picked trap (Server 1) instead of agenda (Server 2)
3. **Couldn't contest fortress** - Server 3 had 4 ICE, only had Code Gate breaker
4. **Many accesses, no agendas** - Variance, but also Corp drew agendas into hand

## Next Steps

1. **Sparring session:** Runner practices with all breaker types to understand controls
2. **Draft "Breaking ICE For Noobs"** section after sparring
3. **Add R&D Physicality** section to runner-playbook.md
4. **Investigate kick/rejoin spam** in websocket client code
