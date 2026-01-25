# Netrunner Complete Mechanics Reference

## Game Overview

**Asymmetric card game**: Corp (defender) vs Runner (attacker)
- **Win**: First to 7 agenda points (6 in starter game)
- **Alt Win**: Corp flatlines Runner (damage > grip size)
- **Alt Loss**: Corp decks out (can't draw from empty R&D)

---

## Core Resources & Concepts

### Universal Resources
- **Clicks (⚡)**: Time units for taking actions
  - Corp: 3/turn (+ mandatory draw)
  - Runner: 4/turn (no mandatory draw)
- **Credits (¢)**: Money for paying costs
  - Shared currency, tracked in credit pools
- **Cards**: Different visibility rules per side

### Information Asymmetry
| Aspect | Corp | Runner |
|--------|------|--------|
| Install state | Facedown (unrezzed) | Faceup immediately |
| Costs | Pay on rez (deferred) | Pay on install (upfront) |
| Hand visibility | Hidden (HQ) | Hidden (grip) |
| Board visibility | Hidden until rezzed | Always visible |

### Terminology Differences
| Neutral | Corp | Runner |
|---------|------|--------|
| Deck | R&D | Stack |
| Hand | HQ | Grip |
| Discard | Archives | Heap |
| Score Area | Score Area | Score Area |

---

## Turn Structure

### Corp Turn
1. **Draw Phase**
   - Gain ⚡⚡⚡
   - Resolve "when your turn begins" abilities
   - **Mandatory draw** 1 card from R&D
2. **Action Phase** (spend 3 clicks)
3. **Discard Phase**
   - If HQ > max hand size (default 5), discard to max

### Runner Turn
1. **Action Phase**
   - Gain ⚡⚡⚡⚡
   - Resolve "when your turn begins" abilities
   - Spend 4 clicks (no mandatory draw)
2. **Discard Phase**
   - If grip > max hand size (default 5), discard to max

### Turn Order
- Corp always goes first
- Players alternate until win condition met

---

## Basic Actions

### Corp Basic Actions
1. **⚡: Gain 1¢**
2. **⚡: Draw 1 card** (from R&D)
3. **⚡: Play 1 operation** (pay play cost, trash immediately)
4. **⚡: Install 1 card** (see Install Rules below)
5. **⚡, 1¢: Advance 1 card** (place advancement counter)
6. **⚡⚡⚡: Purge virus counters** (remove all from all virus cards)
7. **⚡, 2¢: Trash 1 Runner resource** (only if Runner is tagged)

### Runner Basic Actions
1. **⚡: Gain 1¢**
2. **⚡: Draw 1 card** (from stack)
3. **⚡: Play 1 event** (pay play cost, trash immediately)
4. **⚡: Install 1 card** (pay install cost, faceup)
5. **⚡: Run any server**
6. **⚡, 2¢: Remove 1 tag** (only if tagged)

---

## Server Structure

### Server Layout
```
[RUNNER SIDE]
      ↑
   [Ice 3] ← Position 3 (outermost)
   [Ice 2] ← Position 2
   [Ice 1] ← Position 1 (innermost)
   -------
   [ROOT] ← Cards installed here
   -------
[CORP SIDE - OUT OF PLAY AREA]
```

### Central Servers (Always Exist)
- **HQ**: Corp's hand (identity card represents this server)
  - Root: Can only contain upgrades
  - Protected by ice
  - Runner accesses: 1 random card from hand + all cards in root
  
- **R&D**: Corp's deck
  - Root: Can only contain upgrades
  - Protected by ice
  - Runner accesses: Top card of deck + all cards in root
  
- **Archives**: Corp's discard pile
  - Root: Can only contain upgrades
  - Protected by ice
  - Cards can be faceup (rezzed when trashed) or facedown (unrezzed when trashed)
  - Runner accesses: All cards in discard + all cards in root
  - When Runner breaches: All facedown cards flip faceup first

### Remote Servers (Created During Game)
- Corp creates by installing card in new remote server
- Each remote server root contains:
  - **Either**: 1 agenda OR 1 asset (not both, not multiple)
  - **Plus**: Any number of upgrades
- Protected by ice (same as central servers)
- Runner accesses: All cards in root only

### Ice Installation Rules
- **Orientation**: Horizontal (perpendicular to root cards)
- **Position**: Always installed in outermost position
- **Install cost**: 1¢ per ice already protecting that server
- **Can trash**: Any number of ice protecting server before installing (to reduce cost)
- **State**: Installed facedown (unrezzed)

---

## Card Types & Costs

### Corp Cards

**Operations** (One-shot)
- Play cost: Listed on card
- Effect: Resolve immediately, trash faceup

**Ice** (Installed)
- Rez cost: Listed on card
- Installed: Facedown, horizontal, protecting server
- Rezzed: Only during run, when Runner approaches

**Assets** (Installed)
- Rez cost: Listed on card
- Trash cost: Listed on card (Runner pays to trash on access)
- Installed: Facedown in remote server root
- Limit: 1 per remote server (or 0 if agenda present)

**Agendas** (Installed → Scored)
- Advancement requirement: Must have this many advancement counters
- Agenda points: Value when scored/stolen
- Installed: Facedown in remote server root
- Never rezzed: Scored directly when requirement met
- Limit: 1 per remote server (or 0 if asset present)

**Upgrades** (Installed)
- Rez cost: Listed on card
- Trash cost: Listed on card (Runner pays to trash on access)
- Installed: Facedown in any server root
- Limit: Any number per server

### Runner Cards

**Events** (One-shot)
- Play cost: Listed on card
- Effect: Resolve immediately, trash

**Programs** (Installed)
- Install cost: Listed on card
- Memory cost: Listed on card (MU, must not exceed total MU)
- Installed: Faceup in programs row
- Special types:
  - **Icebreakers**: Interface with ice (have strength)
  - **Viruses**: Accumulate virus counters

**Hardware** (Installed)
- Install cost: Listed on card
- Installed: Faceup in hardware row
- **Consoles**: Only 1 console installed at a time (subtype limit)

**Resources** (Installed)
- Install cost: Listed on card
- Installed: Faceup in resources row
- Vulnerable: Corp can trash if Runner is tagged

---

## The Run: Complete Sequence

### Overview
Runner attempts to breach a Corp server by passing through ice protecting it.

### 6 Phases of a Run

#### Phase 1: Initiation
1. Runner announces target server
2. Resolve "when a run begins" abilities
3. **Decision point**:
   - Ice protecting server? → Go to Phase 2 (approach outermost ice)
   - No ice? → Go to Phase 4 (movement)

#### Phase 2: Approach Ice
1. Resolve "when Runner approaches ice" abilities
2. **Corp decision**: Rez this ice? (pay rez cost, flip faceup)
   - Can also rez non-ice cards
3. **Decision point**:
   - Ice rezzed? → Go to Phase 3 (encounter)
   - Ice not rezzed? → Go to Phase 4 (movement, skip encounter)

#### Phase 3: Encounter Ice
1. Resolve "when Runner encounters ice" abilities
2. **Runner decision**: Break subroutines?
   - Use icebreakers (match strength, pay break costs)
   - May break some, all, or none
3. **Corp resolves unbroken subroutines** (top to bottom)
4. **Decision point**:
   - Subroutine ended run? → Go to Phase 6 (end)
   - Run continues? → Go to Phase 4 (movement)

#### Phase 4: Movement
1. Resolve "when Runner passes ice" abilities (if just encountered)
2. **Runner decision**: Jack out?
   - Yes → Go to Phase 6 (end, unsuccessful)
   - No → Continue
3. Corp can rez non-ice cards
4. **Decision point**:
   - More ice inward? → Return to Phase 2 (approach next ice)
   - No more ice? → Continue
5. Runner approaches server
6. Resolve "when Runner approaches server" abilities
7. Go to Phase 5 (success)

#### Phase 5: Success
1. **Run declared SUCCESSFUL**
2. Resolve "when successful run" abilities (including "if successful")
3. **Runner breaches server** (see Access Rules below)
4. Go to Phase 6 (end)

#### Phase 6: Run Ends
1. Check if run was successful (reached Phase 5) or unsuccessful
2. Resolve "when run ends" abilities
3. Run is over

---

## Access Rules

### What Gets Accessed
| Server | Access |
|--------|--------|
| **Remote** | All cards in root |
| **HQ** | 1 random card from hand + all cards in root |
| **R&D** | Top card of deck + all cards in root |
| **Archives** | All cards in discard + all cards in root |

### Access Order
- Runner chooses order (one at a time)
- Can intersperse root cards with central server cards
- For multiple HQ accesses: Set aside accessed cards, don't re-access
- For multiple R&D accesses: Top card first, then second from top, etc.

### Access Outcomes (Per Card)
| Card Type | Outcome |
|-----------|---------|
| **Agenda** | Runner **steals** (must, no choice) → add to score area |
| **Asset/Upgrade** | Runner may pay trash cost → send to Archives faceup |
| **Operation/Ice** | No effect (Runner gains intel only) |

### Special Access Rules
- **"When accessed" abilities**: Trigger even if unrezzed, resolve before continuing access
- **Multiple access**: Some cards grant +1 (or more) access from HQ/R&D
- **One at a time**: Never reveal multiple cards simultaneously
- **No re-access**: When accessing multiple from same location, set aside accessed cards

---

## Ice & Icebreaker System

### Ice Subtypes & Roles
| Subtype | Typical Effect | Example |
|---------|----------------|---------|
| **Barrier** | End the run | Palisade |
| **Sentry** | Damage, punishment | Tithe |
| **Code Gate** | Credit tax | Whitespace |

### Icebreaker Subtypes
| Subtype | Breaks | Example |
|---------|--------|---------|
| **Fracter** | Barriers | Cleaver |
| **Killer** | Sentries | Carmen |
| **Decoder** | Code Gates | Unity |
| **AI** | Any ice type | Mayfly |

### Breaking Ice (Runner's Procedure)
1. **Check match**: Does icebreaker interface with this ice subtype?
   - Most breakers: Specific subtype only
   - AI breakers: Any subtype
2. **Match strength**: Icebreaker strength ≥ ice strength
   - Pay credits to boost icebreaker strength
   - Strength boost expires after encounter
3. **Break subroutines**: Pay to break each subroutine
   - Can choose to break some, all, or none
4. **Unbroken resolve**: Corp resolves unbroken subroutines (top to bottom)

### Subroutine Types (Examples)
- **↳ End the run**: Run ends immediately, unsuccessful
- **↳ Do X net damage**: Runner trashes X cards from grip randomly
- **↳ Gain X¢**: Corp gains credits
- **↳ Runner loses X¢**: Runner loses credits
- **↳ Give Runner tag**: Place tag on Runner
- **↳ Trash X**: Trash Runner cards
- **↳ Install ice**: Corp installs ice during run

### Key Ice Concepts
- **Rez timing**: Only during run, when Runner approaches
- **Rez persistence**: Once rezzed, stays rezzed (encounter it every run)
- **Strength**: Higher strength = more expensive to break
- **Subroutine count**: More subroutines = more expensive to break fully

---

## Memory Management (Runner Only)

### Memory Units (MU)
- **Default**: 4 MU (from identity)
- **Program cost**: Each program lists MU cost (typically 1, some 2+)
- **Limit**: Total MU of installed programs ≤ available MU
- **Expansion**: Some hardware grants +MU

### Installing Programs at Capacity
1. Must trash installed programs first to free MU
2. Then install new program
3. Can't install if can't make room

---

## Advancement & Scoring

### Advancement (Corp)
- **Action**: ⚡, 1¢: Place 1 advancement counter on installed card
- **Target**: Any installed card with "can be advanced" or agendas
- **Effect**: Counters stay on card until scored/trashed

### Scoring Agendas (Corp Only)
- **Requirement**: Advancement counters ≥ advancement requirement
- **Timing**: Only during Corp turn (not an action, free timing)
- **Effect**: Flip faceup, remove counters, add to score area
- **Points gained**: Agenda point value
- **Can over-advance**: Can advance past requirement before scoring

### Agenda Point Values (Typical)
- 1 point agenda: 3 advancement requirement
- 2 point agenda: 4 advancement requirement  
- 3 point agenda: 5 advancement requirement

---

## Damage System

### Damage Types
- **Net Damage**: From ice, traps, abilities
- **Meat Damage**: From tags, operations, agendas
- **Effect is identical**: Random discard from grip/hand

### Damage Resolution
1. Corp declares X damage
2. Runner trashes X cards from grip **at random**
3. If grip size < X: Runner is **flatlined** (loses game)

### Damage Prevention Strategy
- Always maintain cards in grip > expected damage
- Key number: Most single damage sources are 2-4 damage
- Running with 0-1 cards in grip = high flatline risk

---

## Tags

### Gaining Tags
- Corp gives tag (ice subroutines, operations, agendas)
- Runner takes tag voluntarily (some card effects)
- Effect: Place counter on tag tracker

### Tagged State
- Runner is "tagged" if ≥1 tag on tracker
- Enables Corp actions:
  - **⚡, 2¢**: Trash 1 Runner resource
  - Some cards only work if Runner tagged (e.g., Retribution)

### Clearing Tags
- **⚡, 2¢**: Remove 1 tag (Runner action)
- Must clear each tag individually
- Tags persist across turns until cleared

### Tag Strategy
- **Corp**: Land tags, exploit before Runner clears
- **Runner**: Clear ASAP to protect resources and prevent powerful Corp cards

---

## Virus Counters (Runner Programs)

### Virus Programs
- Subtype: Virus
- Accumulate virus counters based on conditions
- Effects scale with counter count

### Corp Purge
- **⚡⚡⚡**: Remove all virus counters from all virus programs
- Takes entire Corp turn
- Decision: Let viruses grow vs. spend turn purging

### Examples
- **Leech**: Reduce ice strength during encounters
- **Conduit**: Access additional R&D cards

---

## Special Rules & Edge Cases

### Persistent Abilities
- Text: "Persistent - [effect]"
- Applies for entire run even if card trashed mid-run
- Example: AMAZE Amusements tags on stolen agendas

### Mulligan
- **Timing**: After both players draw starting hands, before game begins
- **Process**: Corp decides first, then Runner
- **Effect**: Shuffle hand back, draw new 5 cards
- **Limit**: One time only per player

### Maximum Hand Size
- **Default**: 5 cards
- **Modified by**: Scored agendas, installed cards
- **Enforcement**: During discard phase only (can hold more during turn)

### Golden Rule
- **Card text > rulebook**: When card contradicts rules, card wins
- Includes timing, costs, restrictions, effects

### Cannot vs. May Not
- **Cannot**: Absolute prohibition
- **May**: Optional choice
- **Must**: Mandatory action

### Paying Costs
- **General rule**: Can't use ability if can't pay full cost
- **Partial payment**: Not allowed (either pay all or none)
- **Click loss**: If no clicks left, can't lose clicks (can't pay cost)

### Hosting (Credits & Counters)
- **Hosted**: Credits/counters on a card
- **Bank**: Unlimited supply of credits/counters
- **Take**: Move from card to credit pool
- **Load**: Place from bank onto card
- **Empty**: Card with no hosted credits/counters (trash if load/empty card)

---

## Decision Trees for AI

### Corp Key Decisions

**1. Ice Rez Decision (Phase 2)**
```
When Runner approaches ice:
├─ Can I afford rez cost?
│  ├─ No → Don't rez
│  └─ Yes → Evaluate:
│     ├─ Will this stop the run? (End the run sub + Runner lacks breaker)
│     ├─ Will this tax Runner significantly? (High break cost)
│     ├─ What's in this server? (Agenda worth defending?)
│     └─ Runner's credit pool vs. expected break cost
└─ Decision: Rez or let pass
```

**2. Install Priority**
```
Turn planning:
├─ Economic cards first (Hedge Fund, Regolith Mining License)
├─ Ice on scoring server + R&D (protect critical servers)
├─ Advance agenda when Runner is poor/weak
└─ Score when safe (advancement requirement met + Runner can't threaten)
```

**3. Server Allocation**
```
Where to install ice:
├─ R&D: High priority (protects future draws)
├─ Scoring remote: Highest priority when advancing agenda
├─ HQ: Medium priority (protects agendas in hand)
└─ Archives: Low priority (usually undefended)
```

### Runner Key Decisions

**1. Run Timing**
```
Should I run now?
├─ Corp poor? (Can't afford to rez ice)
│  └─ YES → Run aggressively
├─ Card installed and advanced in remote?
│  └─ YES → Run before they score
├─ Do I have credits for breaking?
│  ├─ No → Build economy
│  └─ Yes → Run to pressure
└─ Unprotected server?
   └─ YES → Always run (free access)
```

**2. Breaking Decision (Phase 3)**
```
When encountering ice:
├─ Check each subroutine:
│  ├─ "End the run" → MUST break (if possible)
│  ├─ Damage → Break if grip size low
│  ├─ Tag → Break if resources installed
│  └─ Credit loss → Calculate cost vs. breaking cost
├─ Total break cost > value of server?
│  ├─ YES → Jack out
│  └─ NO → Break and continue
└─ Can't break (no breaker/credits)?
   └─ Jack out or tank subs (risk assessment)
```

**3. Install Priority**
```
What to install first:
├─ Economic cards (Telework Contract, Pennyshaver)
├─ Key icebreaker for observed ice types
├─ Multi-access cards (Jailbreak, Docklands Pass)
└─ Utility programs last
```

**4. Trash Decision (On Access)**
```
Should I trash this asset/upgrade?
├─ Trash cost vs. my credit pool?
├─ Effect of card (dangerous? economic?)
├─ Other servers to run this turn?
│  └─ Save credits for breaking ice
└─ Decision: Trash or leave
```

---

## Win Condition Tracking

### How Players Win

**Standard Win**: 7 agenda points (6 in starter)
- Corp scores agendas from scoring remote
- Runner steals agendas from servers

**Corp Alt Win**: Flatline Runner
- Deal damage > Runner's grip size

**Runner Alt Win**: Corp decks out
- Corp must draw but R&D is empty

### Agenda Point Sources
- Scored agendas (Corp score area)
- Stolen agendas (Runner score area)
- Each agenda has point value (1-3 typically)
- Running total tracked visibly

---

## Timing Windows Summary

### Corp Can Rez
- **Assets/Upgrades**: Almost any time (paid abilities window)
- **Ice**: Only when Runner approaching that ice (Phase 2)
- **Never**: Operations (played, not rezzed), Agendas (scored, not rezzed)

### Runner Can Use Icebreakers
- Only during encounter (Phase 3)
- Must match strength before breaking
- Strength boost expires after encounter

### Paid Abilities Window
- Opens at specific timing points (before/after phases)
- Both players can use non-action abilities
- Corp can rez cards

### Actions (⚡)
- Can only be used during action phase of your turn
- Cannot be used during runs or opponent's turn
- Exception: Some cards grant additional actions mid-run (rare)

---

## Quick Reference: Common Scenarios

**Scenario: Corp installs + advances card in remote**
- Corp likely advancing agenda to score
- Runner should run before Corp scores (next turn)
- Could be trap (Urtica Cipher) - risk assessment

**Scenario: Runner running with 2 cards in grip**
- High risk if Corp has damage ice
- 2+ net damage = flatline
- Should build grip or run safer servers

**Scenario: Runner tagged with resources installed**
- Corp can trash resources (⚡, 2¢ per resource)
- Runner should clear tags (⚡, 2¢ per tag) or lose resources
- Decision: Clear tags or accept resource loss

**Scenario: Corp has 5 agendas in HQ**
- HQ is agenda-rich (Runner should target)
- Corp should install + advance to move agendas out
- "Agenda flood" - Corp vulnerable to HQ runs

**Scenario: Ice installed, Runner can't break**
- Jack out if critical (vs "End the run")
- Tank damage if survivable (grip size > damage)
- Assess: Is access worth the penalty?

**Scenario: Virus counters accumulating**
- Corp decision: Purge now (⚡⚡⚡) or let grow?
- Depends on virus threat level
- Leech: 3+ counters = significant ice weakness
- Conduit: 3+ counters = deep R&D access

---

## Critical Rules for AI Implementation

1. **Turn structure is rigid**: Mandatory draw for Corp, specific phase order
2. **Access is mandatory**: Runner must access all cards they're allowed to
3. **Stealing is mandatory**: Runner must steal accessed agendas
4. **One at a time**: Cards accessed one at a time, not simultaneously
5. **Rez timing**: Ice only during approach, assets/upgrades almost any time
6. **Strength matching**: Must match before breaking (temporary boost)
7. **Memory limit**: Enforced immediately, must trash to make room
8. **Click conservation**: Can't end turn with unspent clicks
9. **Hand size**: Only enforced during discard phase
10. **Agenda limit**: 1 agenda OR 1 asset per remote (not both)

---

## State Tracking Requirements

### Game State
- Turn number
- Active player
- Phase of turn
- Credit pools (Corp, Runner)
- Score totals (Corp, Runner)
- Hand sizes (unknown counts for opponent)
- Tags on Runner
- Virus counters on each virus

### Board State
- All server configurations (ice positions, root contents)
- Rezzed vs unrezzed ice (position tracking)
- All installed Runner cards (visible)
- All scored agendas (both score areas)

### Run State (if in run)
- Target server
- Current phase (1-6)
- Current position (which ice or approaching server)
- Ice rezzed this run
- Successful run triggers pending
- Access queue remaining

---

## Probability & Game Theory Notes

### Information Gathering
- **HQ access**: Random from N cards, reveals 1
- **R&D access**: Top card (Corp doesn't know until drawn)
- **Ice rez**: Corp reveals strength, subs, type
- **Advancing cards**: Hints at agenda vs asset (advancement counter count)

### Risk Assessment
- **Running without full breaker suite**: High risk, high reward
- **Advancing agenda in remote**: Bait Runner to run, or real scoring threat
- **Ice density**: More ice = safer server but slower to build
- **Credit parity**: Player with more credits has advantage

### Tempo Considerations
- **Early game**: Runner pressure, Corp builds defenses
- **Mid game**: Corp scores, Runner contests
- **Late game**: Race to 7 points
- **Click efficiency**: 4-for-1 (Runner draws 4 cards) > 1-for-1 (click for credit)

