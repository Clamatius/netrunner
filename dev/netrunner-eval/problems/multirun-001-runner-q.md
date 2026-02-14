# Problem: multirun-001-runner [Medium]

> **Note:** Tutorial games are played to **6 points** (not the standard 7).

## Context

You are the Runner, 4 clicks remaining at the start of your turn.

## Board State

```yaml
corp:
  credits: 0  # Not specified
  HQ:
    ice:
      - {card: Karunā, rezzed: true}
  R&D:
    ice:
      - {card: Brân 1.0, rezzed: true}
  Archives:
    ice: []
    cards: 4  # 4 face-up economy cards (no agendas)
  Server 1:
    ice:
      - {card: Palisade, rezzed: true}

runner:
  credits: 9
  clicks: 4
  grip:
    - {card: Carmen}
    - {card: Docklands Pass}
    - {card: Unity}
  rig:
    - {card: Cleaver}
    - {card: Pennyshaver, credits: 1}
    - {card: Smartware Distributor, credits: 0}
```


## Card Text (Auto-Generated)

**Brân 1.0** - ICE: Barrier - Bioroid (Rez 6, Strength 6)
Lose [click]: Break 1 subroutine on this ice. Only the Runner can use this ability.
↳ You may install 1 piece of ice from HQ or Archives directly inward from this ice, ignoring all costs.
↳ End the run.
↳ End the run.

**Carmen** - Icebreaker: Killer (Install 5, Strength 2, 1 MU)
If you made a successful run this turn, this program costs 2[credit] less to install.
Interface → 1[credit]: Break 1 sentry subroutine.
2[credit]: +3 strength.

**Cleaver** - Icebreaker: Fracter (Install 3, Strength 3, 1 MU)
Interface → 1[credit]: Break up to 2 barrier subroutines.
2[credit]: +1 strength.

**Docklands Pass** - Hardware (Cost 2)
The first time each turn you breach HQ, access 1 additional card.

**Karunā** - ICE: Sentry - AP (Rez 4, Strength 3)
↳ Do 2 net damage. The Runner may jack out.
↳ Do 2 net damage.

**Palisade** - ICE: Barrier (Rez 3, Strength 2)
While this ice is protecting a remote server, it gets +2 strength.
↳ End the run.

**Pennyshaver** - Hardware: Console (Cost 3)
+1[mu]
Whenever you make a successful run, place 1[credit] on this hardware.
[click]: Place 1[credit] on this hardware, then take all credits from it.
Limit 1 console per player.

**Smartware Distributor** - Resource: Connection (Cost 0)
[click]: Place 3[credit] on this resource.
When your turn begins, take 1[credit] from this resource.

**Unity** - Icebreaker: Decoder (Install 3, Strength 1, 1 MU)
Interface → 1[credit]: Break 1 code gate subroutine.
1[credit]: +X strength. X is equal to the number of installed icebreakers (including this one).

## Question

Access HQ as many times as possible this turn without drawing more cards. What is the maximum number of HQ accesses, and how do you achieve it?
