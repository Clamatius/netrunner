# Problem: break-001-runner [Easy]

## Situation
Turn 4. You're Runner.
Score: Corp 0, Runner 0
ID: The Catalyst (no special ability, 40 card min, 1 link)

## Board State

```yaml
corp:
  credits: 8
  points: 0
  Server 1:
    ice:
      - {card: Whitespace, rezzed: true}
      - {card: Palisade, rezzed: true}
    root: {adv: 2}
runner:
  credits: 7
  points: 0
  clicks: 4
  grip:
    - {card: Sure Gamble}
    - {card: Cleaver}
    - {card: Unity}
    - {card: Carmen}
    - {card: Docklands Pass}
  rig: []
```

## Card Text (Auto-Generated)

**Carmen** - Icebreaker: Killer (Install 5, Strength 2, 1 MU)
If you made a successful run this turn, this program costs 2[credit] less to install.
Interface → 1[credit]: Break 1 sentry subroutine.
2[credit]: +3 strength.

**Cleaver** - Icebreaker: Fracter (Install 3, Strength 3, 1 MU)
Interface → 1[credit]: Break up to 2 barrier subroutines.
2[credit]: +1 strength.

**Docklands Pass** - Hardware (Cost 2)
The first time each turn you breach HQ, access 1 additional card.

**Palisade** - ICE: Barrier (Rez 3, Strength 2)
While this ice is protecting a remote server, it gets +2 strength.
↳ End the run.

**Sure Gamble** - Event (Cost 5)
Gain 9[credit].

**Unity** - Icebreaker: Decoder (Install 3, Strength 1, 1 MU)
Interface → 1[credit]: Break 1 code gate subroutine.
1[credit]: +X strength. X is equal to the number of installed icebreakers (including this one).

**Whitespace** - ICE: Code Gate (Rez 2, Strength 0)
↳ The Runner loses 3[credit].
↳ If the Runner has 6[credit] or less, end the run.

## Question
Access the card in Server 1 this turn. Show your sequencing and credit math.
