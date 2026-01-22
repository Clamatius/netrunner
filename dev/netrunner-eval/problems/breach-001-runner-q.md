# Problem: breach-001-runner [Hard]

> **Note:** Tutorial games are played to **6 points** (not the standard 7).

## Context

You are the Runner. It is Turn 6. You are at 5 points. The Corp is at 5 points. There is an Agenda in Server 1. If you steal it, you win. If you don't, the Corp scores it next turn and wins.

## Board State

```yaml
corp:
  credits: 10
  HQ:
    ice:
      - {card: Karunā, rezzed: false}
    cards: 1  # 1 asset in HQ
  Server 1:
    ice:
      - {card: Brân 1.0, rezzed: true}
    root:
      - {card: Manegarm Skunkworks, rezzed: true}
      - {card: Agenda, rezzed: false}  # Unknown agenda

runner:
  credits: 5
  clicks: 4
  grip:
    - {card: Sure Gamble}
    - {card: Overclock}
    - {card: Mayfly}
  rig: []  # No programs installed
```

## Card Text (Auto-Generated)

**Brân 1.0** - ICE: Barrier - Bioroid (Rez 6, Strength 6)
Lose [click]: Break 1 subroutine on this ice. Only the Runner can use this ability.
↳ You may install 1 piece of ice from HQ or Archives directly inward from this ice, ignoring all costs.
↳ End the run.
↳ End the run.

**Karunā** - ICE: Sentry - AP (Rez 4, Strength 3)
↳ Do 2 net damage. The Runner may jack out.
↳ Do 2 net damage.

**Manegarm Skunkworks** - Upgrade (Rez 2, Trash 3)
Whenever the Runner approaches this server, end the run unless they either spend [click][click] or pay 5[credit].

**Mayfly** - Icebreaker: AI (Install 1, Strength 1, 2 MU)
Interface → 1[credit]: Break 1 subroutine. When this run ends, trash this program.
1[credit]: +1 strength.

**Overclock** - Event: Run (Cost 1)
Place 5[credit] on this event, then run any server. You can spend hosted credits during that run.

**Sure Gamble** - Event (Cost 5)
Gain 9[credit].

## Questions

**Q1:** Find a sequence of actions that lets you breach Server 1 and steal the agenda this turn.

**Q2:** Find a *different* sequence where your Click 1 action is different from your Q1 answer.

**Q3:** Find a *third* sequence where your Click 1 action is different from both Q1 and Q2.

All three paths must successfully breach the server. Prove each path works with click and credit accounting.
