# Problem: lethal-001-runner [Hard]

> **Note:** Tutorial games are played to **6 points** (not the standard 7).

## Context

You are the Runner, 4 clicks remaining. Corp has an un-stealable agenda in a remote and will score next turn if you don't win now.

## Board State

```yaml
corp:
  credits: 0  # Not specified, assume minimal
  points: 5
  HQ:
    ice:
      - {card: Karunā, rezzed: true}
    cards: 2  # 1 agenda, 1 Manegarm Skunkworks
  R&D:
    ice: []
  Archives:
    ice: []

runner:
  credits: 13
  points: 5
  clicks: 4
  grip: 
    - {card: Carmen}
    - {card: Carmen}
    - {card: Unity}
  rig:
    - {card: Unity}
```


## Card Text (Auto-Generated)

**Carmen** - Icebreaker: Killer (Install 5, Strength 2, 1 MU)
If you made a successful run this turn, this program costs 2[credit] less to install.
Interface → 1[credit]: Break 1 sentry subroutine.
2[credit]: +3 strength.

**Karunā** - ICE: Sentry - AP (Rez 4, Strength 3)
↳ Do 2 net damage. The Runner may jack out.
↳ Do 2 net damage.

**Manegarm Skunkworks** - Upgrade (Rez 2, Trash 3)
Whenever the Runner approaches this server, end the run unless they either spend [click][click] or pay 5[credit].

**Unity** - Icebreaker: Decoder (Install 3, Strength 1, 1 MU)
Interface → 1[credit]: Break 1 code gate subroutine.
1[credit]: +X strength. X is equal to the number of installed icebreakers (including this one).

## Question

Find a line that guarantees stealing the agenda from HQ this turn. Show your work.
