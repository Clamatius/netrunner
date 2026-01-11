# Answer: trace-001-corp

## The Setup

Runner is on click 4, mid-Sneakdoor run. Ichi's trace is firing. Runner has Plascrete Carapace (4 counters) and 4 cards in grip.

**First instinct:** "Plascrete blocks Scorched. Can't kill."

**The trick:** You have Archived Memories in hand. Scorched Earth costs $3. Archived Memories costs **$0**.

---

## Q1: Don't Boost

| Trace | Runner Link | Runner Payment | Result |
|-------|-------------|----------------|--------|
| 1 | 0 | $1 | Runner matches, trace fails |

Runner has $8. They easily pay $1 to beat trace of 1.

**Result:** No tag, no core damage. Runner accesses HQ, might steal an agenda (40% chance: ABT or NAPD), goes to 6 points.

---

## Q2: Single Scorched

If you boost enough to guarantee the trace:

| Trace Strength | Runner Needs | Runner Has | Result |
|----------------|--------------|------------|--------|
| 9 | $9 | $8 + 0 link | Cannot match, trace succeeds |

Cost to boost: 9 - 1 = **$8**. Corp: $11 → $3.

Trace succeeds:
- 1 core damage: Runner discards 1 card (grip 4 → 3), max hand size permanently reduced
- 1 tag: Runner is tagged

**Corp turn - Single Scorched attempt:**

| Step | Credits | Effect |
|------|---------|--------|
| Scorched Earth | $3 → $0 | 4 meat damage |
| Plascrete | — | Prevents 4 damage |
| Result | — | Runner survives with 3 cards |

**Single Scorched doesn't kill through Plascrete.**

---

## Q3: The Winning Line

**The insight:** Adonis Campaign has $3 on it. It triggers at start of Corp turn. And you have Archived Memories.

### Trace Phase

| Action | Corp Credits | Notes |
|--------|--------------|-------|
| Boost trace by 8 | $11 → $3 | Trace strength: 9 |
| Runner cannot match | — | $8 + 0 link < 9 |
| Trace succeeds | $3 | 1 core damage, 1 tag |

Runner state after trace: **3 cards in grip**, tagged, max hand size 4.

### Corp Turn

| Step | Corp Credits | Action |
|------|--------------|--------|
| Turn begins | $3 | — |
| Adonis Campaign triggers | $3 → **$6** | Take 3 credits |
| Play Scorched Earth | $6 → $3 | 4 meat damage |
| — | — | Plascrete prevents 4 (trashed) |
| — | — | Runner: 3 cards, no Plascrete |
| Play Archived Memories | $3 | **Cost: $0!** |
| — | — | Retrieve Scorched Earth from Archives |
| Play Scorched Earth | $3 → $0 | 4 meat damage |
| — | — | No Plascrete, 3 cards in grip |
| — | — | **4 > 3 = FLATLINE** |

---

## Why Every Number Matters

| Element | Value | Why It's Exact |
|---------|-------|----------------|
| Corp credits | $11 | $8 boost + $3 Scorched + $0 AM + $3 Scorched = $14 needed. Adonis provides $3. |
| Boost amount | $8 | $7 = trace 8, Runner pays 8 to match. Must be 9. |
| Adonis credits | $3 | Without this, can't afford double Scorched |
| Archived Memories cost | $0 | If it cost $1, you'd be $1 short |
| Runner grip | 4 → 3 | Core damage is **load-bearing** |
| Plascrete counters | 4 | Absorbs exactly one Scorched |
| Double Scorched | 8 damage | 4 (blocked) + 4 (kills) |

**Critical:** If Runner had 4 cards in grip (no core damage), they'd survive:
- Scorched #1: 4 damage, Plascrete prevents 4, grip still 4
- Scorched #2: 4 damage, no Plascrete, 4 - 4 = 0 cards
- 4 damage is NOT > 4 cards. Runner survives at 0 cards.

The core damage from the trace brings grip to 3. Then 4 > 3 = lethal.

---

## The Psychology

The Runner expected "trace fires, sure pay $1" - the routine exchange from the first two runs. They weren't counting:

1. That you'd boost $8 to guarantee it
2. That Adonis gives you the missing $3
3. That Archived Memories is free
4. That core damage makes the second Scorched lethal

**"Those runners are quite hard to kill."** - Not if you find the line.

---

## Common Mistakes

| Mistake | Why It's Wrong |
|---------|----------------|
| Not boosting because "Plascrete blocks Scorched" | Double Scorched kills, and you have the recursion |
| Boosting to 8 instead of 9 | Runner pays $8 to match, trace fails |
| Forgetting Adonis triggers | Can't afford double Scorched without it |
| Miscounting core damage | It's not just hand size reduction - it also discards a card NOW |
| Playing Scorched before Adonis triggers | You only have $3, need $6 for the full line |

---

## Difficulty

**Hard.** This puzzle requires:

1. Recognizing that single Scorched doesn't kill through Plascrete
2. Seeing the Archived Memories recursion line
3. Counting that Adonis provides the missing credits
4. Understanding that core damage reduces current grip, not just max hand size
5. Exact credit math across two turns
6. Boost calculation to ensure Runner can't match

The winning line spans the trace decision AND your entire next turn. You must see four moves ahead to find the kill.

---

## Tournament Context

This puzzle is based on a real tournament game. The Corp went 4-1 in Swiss, with 3 wins by flatline. Sometimes the kill is there - you just have to count.
