# Problem: score-001-corp [Easy]

## Situation
Turn 9. You're Corp.
Score: Corp 5, Runner 4
ID: Pravdivost Consulting (no special ability, 45 card min)

## Board State

```yaml
corp:
  credits: 12
  points: 5
  HQ:
    ice:
      - {card: Tithe, rezzed: true}       # outer
      - {card: Tithe, rezzed: true}       # inner
    contents:
      - Regolith Mining License
      - Seamless Launch
      - Government Subsidy
      - Superconducting Hub
      - Palisade
  R&D:
    ice:
      - {card: Brân 1.0, rezzed: true}    # outer
      - {card: Karunā, rezzed: true}      # inner
  Server 1:
    ice:
      - {card: Whitespace, rezzed: true}  # outer
      - {card: Karunā, rezzed: true}      # inner

runner:
  credits: 4
  points: 4
  grip:
    - {card: Unknown, rezzed: false}
  rig:
    - {card: Cleaver}
    - {card: Unity}
  # No Killer installed
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

**Government Subsidy** - Operation: Transaction (Cost 10)
Gain 15[credit].

**Karunā** - ICE: Sentry - AP (Rez 4, Strength 3)
↳ Do 2 net damage. The Runner may jack out.
↳ Do 2 net damage.

**Palisade** - ICE: Barrier (Rez 3, Strength 2)
While this ice is protecting a remote server, it gets +2 strength.
↳ End the run.

**Regolith Mining License** - Asset (Rez 2, Trash 3)
When you rez this asset, load 15[credit] onto it. When it is empty, trash it.
[click]: Take 3[credit] from this asset.

**Seamless Launch** - Operation (Cost 1)
Place 2 advancement counters on 1 installed card that you did not install this turn.

**Superconducting Hub** - Agenda: Expansion (Adv 3, Points 1)
When you score this agenda, you may draw 2 cards.
You get +2 maximum hand size.

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
It's the start of your turn. What's your best line?
