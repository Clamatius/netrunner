# Problem: breach-001-runner

> **Note:** Tutorial games are played to **6 points** (not the standard 7).

## Context

You are the Runner. It is Turn 6. You are at 5 points. The Corp is at 5 points. There is an Agenda in Server 1. If you steal it, you win. If you don't, the Corp scores it next turn and wins.

## Board State

**Runner:**
- Credits: $4
- Clicks: 4 (start of turn)
- Grip: [[Sure Gamble]], [[Overclock]], [[Mayfly]]
- Rig: Empty (no programs installed)

**Corp:**
- Credits: $10
- HQ: [[Karunā]], 1 asset
- Server 1:
  - [[Brân 1.0]] (rezzed)
  - [[Manegarm Skunkworks]] (rezzed, in root)
  - Agenda (unrezzed, installed)

## Card Text (Auto-Generated)

**Brân 1.0** - ICE: Barrier - Bioroid (Rez 6, Strength 6)
Lose [click]: Break 1 subroutine on this ice. Only the Runner can use this ability.
↳ You may install 1 piece of ice from HQ or Archives directly inward from this ice, ignoring all costs.
↳ End the run.
↳ End the run.

**Manegarm Skunkworks** - Upgrade (Rez 2, Trash 3)
Whenever the Runner approaches this server, end the run unless they either spend [click][click] or pay 5[credit].

**Mayfly** - Icebreaker: AI (Install 1, Strength 1)
Interface → 1[credit]: Break 1 subroutine.
1[credit]: +1 strength.
When this run ends, trash this program.

**Overclock** - Event: Run (Cost 0)
Place 5[credit] on this event, then run any server. When that run ends, trash Overclock. Use credits on Overclock during runs.

**Sure Gamble** - Event (Cost 5)
Gain 9[credit].

**Karunā** - ICE: Sentry - AP (Rez 4, Strength 3)
↳ Do 2 net damage. The Runner may jack out.
↳ Do 2 net damage.

## Questions

**Q1:** Find a sequence of actions that lets you breach Server 1 and steal the agenda this turn.

**Q2:** Find a *different* sequence where your Click 1 action is different from your Q1 answer.

**Q3:** Find a *third* sequence where your Click 1 action is different from both Q1 and Q2.

All three paths must successfully breach the server. Prove each path works with click and credit accounting.
