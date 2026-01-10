# Problem: remote-001-corp [Medium]

> **Note:** Tutorial games are played to **6 points** (not the standard 7).

## Context

You are the Corp, about to start your turn. You have Offworld Office in HQ and want to install it in Server 1 and advance it. But is Server 1 actually safe?

## Board State

```yaml
corp:
  credits: 8
  points: 2
  HQ:
    ice:
      - {card: Karunā, rezzed: true}
    contents: [Offworld Office, Nico Campaign]
  R&D:
    ice:
      - {card: Karunā, rezzed: true}
  Server 1:
    ice:
      - {card: Whitespace, rezzed: true}   # outer
      - {card: Tithe}                       # unrezzed
      - {card: Palisade}                    # unrezzed
      - {card: Palisade}                    # inner, unrezzed
    root: {card: Regolith Mining License, credits: 3}

runner:
  credits: 2
  points: 0
  grip:
    - {card: Unknown, rezzed: false}
  rig:
    - {card: Cleaver}
    - {card: Pennyshaver, credits: 2}
    - {card: Red Team, credits: 12}
    - {card: Smartware Distributor, credits: 3}
  # No Killer or Decoder installed
```


## Card Text (Auto-Generated)

**Cleaver** - Icebreaker: Fracter (Install 3, Strength 3, 1 MU)
Interface → 1[credit]: Break up to 2 barrier subroutines.
2[credit]: +1 strength.

**Karunā** - ICE: Sentry - AP (Rez 4, Strength 3)
↳ Do 2 net damage. The Runner may jack out.
↳ Do 2 net damage.

**Nico Campaign** - Asset: Advertisement (Rez 2, Trash 2)
When you rez this asset, load 9[credit] onto it. When it is empty, trash it and draw 1 card.
When your turn begins, take 3[credit] from this asset.

**Offworld Office** - Agenda: Expansion (Adv 4, Points 2)
When you score this agenda, gain 7[credit].

**Palisade** - ICE: Barrier (Rez 3, Strength 2)
While this ice is protecting a remote server, it gets +2 strength.
↳ End the run.

**Pennyshaver** - Hardware: Console (Cost 3)
+1[mu]
Whenever you make a successful run, place 1[credit] on this hardware.
[click]: Place 1[credit] on this hardware, then take all credits from it.
Limit 1 console per player.

**Red Team** - Resource: Job (Cost 5)
When you install this resource, load 12[credit] onto it. When it is empty, trash it.
[click]: Run a central server you have not run this turn. If successful, take 3[credit] from this resource.

**Regolith Mining License** - Asset (Rez 2, Trash 3)
When you rez this asset, load 15[credit] onto it. When it is empty, trash it.
[click]: Take 3[credit] from this asset.

**Smartware Distributor** - Resource: Connection (Cost 0)
[click]: Place 3[credit] on this resource.
When your turn begins, take 1[credit] from this resource.

**Tithe** - ICE: Sentry - AP (Rez 1, Strength 1)
↳ Do 1 net damage.
↳ Gain 1[credit].

**Whitespace** - ICE: Code Gate (Rez 2, Strength 0)
↳ The Runner loses 3[credit].
↳ If the Runner has 6[credit] or less, end the run.

## Question

**Q1:** If the Runner doesn't draw cards on their turn, can they breach Server 1?

**Q2:** Should you install Offworld Office in Server 1 and advance it?
