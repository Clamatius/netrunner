# Problem: servers-001-runner

> **Note:** Tutorial games are played to **6 points** (not the standard 7).

## Context

You are the Runner, 4 clicks remaining at the start of your turn. You need information from both HQ and R&D to find agendas.

## Board State

**Runner:**
- Credits: $7
- Points: 0
- Grip: [[Carmen]], [[Jailbreak]]
- Stack: Contains 2 more [[Jailbreak]] (among other cards)
- Rig: [[Unity]]

**Corp:**
- Points: 3
- HQ: [[Tithe]] (rezzed) - 5 cards in hand
- R&D: [[Whitespace]] (rezzed)
- Archives: unprotected


## Card Text (Auto-Generated)

**Carmen** - Icebreaker: Killer (Install 5, Strength 2, 1 MU)
If you made a successful run this turn, this program costs 2[credit] less to install.
Interface → 1[credit]: Break 1 sentry subroutine.
2[credit]: +3 strength.

**Jailbreak** - Event: Run (Cost 0)
Run HQ or R&D. If successful, draw 1 card and when you breach the attacked server, access 1 additional card.

**Tithe** - ICE: Sentry - AP (Rez 1, Strength 1)
↳ Do 1 net damage.
↳ Gain 1[credit].

**Unity** - Icebreaker: Decoder (Install 3, Strength 1, 1 MU)
Interface → 1[credit]: Break 1 code gate subroutine.
1[credit]: +X strength. X is equal to the number of installed icebreakers (including this one).

**Whitespace** - ICE: Code Gate (Rez 2, Strength 0)
↳ The Runner loses 3[credit].
↳ If the Runner has 6[credit] or less, end the run.

## Question

Access both HQ and R&D this turn, maximizing total accesses. What is the maximum number of cards you can access, and what is the sequence of actions?
