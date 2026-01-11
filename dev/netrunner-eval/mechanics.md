# Netrunner Mechanics (Condensed)

Android: Netrunner is an asymmetric two-player card game. The **Corporation (Corp)** protects servers and scores agendas. The **Runner** breaks into servers and steals agendas.

## Win Conditions

**Corp wins by:**
- Scoring 7 agenda points (6 in tutorial games)
- Flatline: Dealing damage when Runner has no cards in hand (grip)

**Runner wins by:**
- Stealing 7 agenda points (6 in tutorial games)
- Corp drawing from empty deck (R&D)

## Resources

### Credits
- Universal currency for both sides
- Start: 5 credits each
- Basic action: Spend 1 click, gain 1 credit (inefficient)

### Clicks
- Action points per turn
- Corp: 3 clicks per turn
- Runner: 4 clicks per turn
- Most actions cost 1 click

### Cards
- Corp deck: R&D (draw pile), HQ (hand), Archives (discard)
- Runner deck: Stack (draw pile), Grip (hand), Heap (discard)
- Hand size limit: 5 cards (discard at end of turn if over)

## Corp Basics

### Servers
Corp has servers that Runner can attack:

**Central Servers (always exist):**
- **HQ** - Corp's hand. Runner accesses 1 random card.
- **R&D** - Corp's deck. Runner accesses top card.
- **Archives** - Corp's discard. Runner accesses all cards (facedown cards flip faceup on breach).

**Remote Servers (Corp creates):**
- Created by installing cards
- Can contain 1 agenda or asset, plus any number of upgrades
- Protected by ICE installed in front
- No limit to number, and no cost to create more via new installs

### Installing (Corp)
- Install costs 1 click (cards themselves install for free unless stated)
- Cards install facedown (hidden from Runner). They're always installed into just one server.
- ICE installs in front of servers, protecting them
  - ICE install cost: 1 credit per ICE already protecting that server
- Agendas/assets/upgrades install IN servers (root)

### Card Types (Corp)

**Agendas** - Score these to win
- Have advancement requirement (e.g., "Adv 3" = needs 3 advancement counters)
- Have point value (e.g., "Points 2")
- Score when counters ≥ requirement (free action, no click cost, Corp turn only)
- If Runner accesses an agenda, they MUST steal it (mandatory)

**ICE** - Protects servers
- Installs in front of servers
- Starts unrezzed (facedown, inactive)
- Corp rezzes (flips faceup, pays rez cost) when Runner approaches
- Has subroutines (↳) that fire if not broken
- Has strength (Runner must match to break)
- Types: Barrier, Code Gate, Sentry (each broken by specific breaker types Fracter, Decoder, Killer respectively)

**Assets** - Economy, utility, traps
- Install in remote servers
- Must rez to use abilities
- Runner can trash if they access and pay trash cost

**Operations** - One-time effects
- Play from hand, pay cost, resolve, goes to Archives
- Example: Hedge Fund (cost 5, gain 9 = net +4)

**Upgrades** - Enhance servers
- Install in any server (central or remote)
- Can coexist with agendas/assets

### Advancing
- Action: Spend 1 click, pay 1 credit, place 1 advancement counter
- Primarily used on agendas
- Some traps can be advanced (to bluff as agendas)

### Rezzing
- Flip card faceup, pay rez cost
- ICE: Rez when Runner approaches (before they can break)
- Assets/Upgrades: Rez at almost any time
- Once rezzed, stays rezzed

## Runner Basics

### The Rig
Runner's installed cards:
- **Programs** - Icebreakers and utilities (limited by Memory Units, default 4)
- **Hardware** - Permanent upgrades
- **Resources** - Economy, connections, tools

### Icebreakers
Programs that break ICE subroutines:
- **Fracter** - Breaks Barriers
- **Decoder** - Breaks Code Gates
- **Killer** - Breaks Sentries

To break ICE:
1. Boost breaker strength ≥ ICE strength
2. Pay to break subroutines

### Running
Runner's core action - attacking a server.

**Run structure:**
1. Declare target server
2. Approach each ICE (outermost first)
   - Corp may rez ICE
   - If rezzed: Runner may break subroutines, then unbroken subs fire
   - After passing ICE: Runner may jack out (can't jack out before first ICE)
3. If Runner passes all ICE: Breach server, and access server contents

**Subroutine effects (common):**
- "End the run" - Run fails, no access
- "Do X <type> damage" - Runner discards X random cards
- "The Runner loses X credits" - Economic damage

**Accessing:**
- HQ: Access 1 random card from Corp hand
- R&D: Access top card of deck
- Remote: Access all cards in server
- Agenda accessed = stolen (Runner takes it)
- Asset/Upgrade accessed = may pay trash cost to trash, goes faceup to Archives

### Card Types (Runner)

**Events** - One-time effects
- Play from hand, pay cost, resolve, goes to Heap
- Example: Sure Gamble (cost 5, gain 9 = net +4)

**Programs** - Icebreakers and tools
- Install cost paid once
- Use Memory Units (MU) - typically 4 MU available initially
- Persist until trashed

**Hardware** - Permanent upgrades
- Install once, persist until trashed

**Resources** - Economy and utility
- Install once, some have ongoing effects
- Can be trashed if Runner is tagged (not in tutorial): Corp spends click and 2 credits to trash Resource of tagged Runner.

## Game structure

- Corp chooses to mulligan their hand or keep
- Then runner makes mulligan choice for their grip
- Corp takes initial turn, and then players alternate

### Mulligan
- At game start, draw 5 cards
- May shuffle back and draw 5 new cards (once only)

## Turn Structure

### Corp Turn
0. **End of Opponent Turn** - Paid effects, like rezzes
1. **Mandatory draw** - Draw 1 card (not a click)
2. **Action phase** - Spend 3 clicks on:
   - Draw 1 card
   - Gain 1 credit
   - Install 1 card
   - Play 1 operation
   - Advance 1 card
   - Paid effects, like rezzes and card abilities. Paid effects that do not take a click can be played between actions that do.
   - Score agenda (if fully advanced)
3. **Discard phase** - Discard to hand size (5). Corp discards are initially face down in Archives.

### Runner Turn
1. **Action phase** - Spend 4 clicks on:
   - Draw 1 card
   - Gain 1 credit
   - Install 1 card
   - Play 1 event
   - Run any server
2. **Discard phase** - Discard to hand size (5)

## Damage

**Net damage** - Discard cards randomly from grip
- Common source: ICE subroutines, traps
- If damage > cards in grip: Flatline (Corp wins)

**Survival:** Always maintain grip size > expected damage - grip is akin to health of runner

## Basic Tactical Concepts

### Facedown Information
- Installed Corp cards are facedown until rezzed or accessed
- Corp knows all their cards; Runner must guess/probe
- Unrezzed ICE could be anything - the threat has value

### Economy
- Clicks are the fundamental currency of the game, with an initial basic ability trade of 1:1 for credits.
- Both players typically find ways to do better than 1:1
- Credits enable everything
- Clicking for 1 credit is inefficient (operation/event economy is better)
- Corp needs credits to rez ICE
- Runner needs credits to break ICE and trash assets

### Tempo
- Who is dictating the pace of the game?
- Installing/advancing creates pressure (Runner must respond or Corp scores), but typically loses tempo for Corp when scored due to advancement costs
- Building rig takes time but enables efficient runs later

### Mulligan
- When making this choice, consider your plan for turn 1 given what you know and eval how strong
