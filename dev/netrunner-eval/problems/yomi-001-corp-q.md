# Problem: yomi-001-corp [Easy]

> **Note:** Tutorial games are played to **6 points** (not the standard 7).

## Context

You are the Corp, Turn 3, 3 clicks remaining. You have a scoring remote protected by Brân. The Runner has been building economy and has no efficient way through Brân yet.

## Board State

```yaml
corp:
  credits: 3
  points: 0
  clicks: 3
  HQ:
    ice:
      - {card: Whitespace, rezzed: true}
    contents: [Seamless Launch, Regolith Mining License, Offworld Office]
  R&D:
    ice:
      - {card: Whitespace, rezzed: true}
      - {card: Tithe, rezzed: true}
  Server 1:
    ice:
      - {card: Brân 1.0, rezzed: true}
    root: null  # empty
runner:
  credits: 6
  points: 0
  grip:
    - {card: Unknown, rezzed: false}
    - {card: Unknown, rezzed: false}
    - {card: Unknown, rezzed: false}
    - {card: Unknown, rezzed: false}
    - {card: Unknown, rezzed: false}
  rig:
    - {card: Cleaver}
    - {card: Unity}
    - {card: Pennyshaver}
  notes: "No Killer installed"
```


## Card Text (Auto-Generated)

**Brân 1.0** - ICE: Barrier - Bioroid (Rez 6, Strength 6)
Lose [click]: Break 1 subroutine on this ice. Only the Runner can use this ability.
↳ You may install 1 piece of ice from HQ or Archives directly inward from this ice, ignoring all costs.
↳ End the run.
↳ End the run.

**Cleaver** - Icebreaker: Fracter (Install 3, Strength 3, 1 MU)
Interface → 1[credit]: Break up to 2 barrier subroutines.
2[credit]: +1 strength.

**Offworld Office** - Agenda: Expansion (Adv 4, Points 2)
When you score this agenda, gain 7[credit].

**Pennyshaver** - Hardware: Console (Cost 3)
+1[mu]
Whenever you make a successful run, place 1[credit] on this hardware.
[click]: Place 1[credit] on this hardware, then take all credits from it.
Limit 1 console per player.

**Regolith Mining License** - Asset (Rez 2, Trash 3)
When you rez this asset, load 15[credit] onto it. When it is empty, trash it.
[click]: Take 3[credit] from this asset.

**Seamless Launch** - Operation (Cost 1)
Place 2 advancement counters on 1 installed card that you did not install this turn.

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

**Q1:** Find a line that scores Offworld Office next turn.

**Q2:** Find a different line where you install something else Turn 1. What's the plan?

**Q3:** How does the Runner's response to Q2 set you up for future turns? Why might Q2 be better than Q1 even though it doesn't score immediately?
