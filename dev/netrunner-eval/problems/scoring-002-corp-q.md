# Problem: scoring-002-corp [Medium]

> **Note:** Tutorial games are played to **6 points** (not the standard 7).

## Context

You are the Corp, 3 clicks remaining at the start of your turn (after mandatory draw, but assume you draw no new cards for this puzzle).

## Board State

```yaml
corp:
  credits: 5
  points: 3
  clicks: 3    # after mandatory draw
  HQ:
    ice:
      - {card: Tithe, rezzed: true}        # outer
      - {card: Whitespace, rezzed: true}   # inner
    contents:
      - Send a Message
      - Seamless Launch
      - Seamless Launch
      - Regolith Mining License
      - Manegarm Skunkworks
  R&D:
    ice:
      - {card: Karunā, rezzed: true}       # outer
      - {card: Brân 1.0, rezzed: true}     # inner
  Server 1:
    ice:
      - {card: Tithe, rezzed: true}        # outer
      - {card: Brân 1.0, rezzed: true}     # middle
      - {card: Palisade, rezzed: true}     # inner

runner:
  credits: 2
  points: 3
  grip:
    - {card: Unknown, rezzed: false}
    - {card: Unknown, rezzed: false}
  rig:
    - {card: Unity}
    - {card: Cleaver}
    - {card: Carmen}
    - {card: Pennyshaver, credits: 2}
    - {card: Docklands Pass}
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

**Manegarm Skunkworks** - Upgrade (Rez 2, Trash 3)
Whenever the Runner approaches this server, end the run unless they either spend [click][click] or pay 5[credit].

**Overclock** - Event: Run (Cost 1)
Place 5[credit] on this event, then run any server. You can spend hosted credits during that run.

**Palisade** - ICE: Barrier (Rez 3, Strength 2)
While this ice is protecting a remote server, it gets +2 strength.
↳ End the run.

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

**Send a Message** - Agenda: Security (Adv 5, Points 3)
When this agenda is scored or stolen, you may rez 1 installed piece of ice, ignoring all costs.

**Sure Gamble** - Event (Cost 5)
Gain 9[credit].

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

**Q1:** What is the best line of play for Corp this turn, if they draw no new cards?

**Q2:** If you somehow know the 2 cards in the Runner's grip are [[Sure Gamble]] and [[Overclock]], would that change your answer?
