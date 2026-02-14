# Problem: partial-001-runner [Easy]

> **Note:** Tutorial games are played to **6 points** (not the standard 7).

## Context

You are the Runner, 4 clicks remaining. There's an agenda in the remote that will score next turn. You must breach this turn.

## Board State

```yaml
corp:
  credits: 5
  points: 4
  Server 1:
    ice:
      - {card: Karunā, rezzed: true}      # outer
      - {card: Palisade, rezzed: true}    # inner
    root: {card: Agenda}                   # unrezzed agenda

runner:
  credits: 7
  points: 4
  clicks: 4
  grip:
    - {card: Carmen}
  rig:
    - {card: Carmen}
    - {card: Cleaver}
```


## Card Text (Auto-Generated)

**Carmen** - Icebreaker: Killer (Install 5, Strength 2, 1 MU)
If you made a successful run this turn, this program costs 2[credit] less to install.
Interface → 1[credit]: Break 1 sentry subroutine.
2[credit]: +3 strength.

**Cleaver** - Icebreaker: Fracter (Install 3, Strength 3, 1 MU)
Interface → 1[credit]: Break up to 2 barrier subroutines.
2[credit]: +1 strength.

**Karunā** - ICE: Sentry - AP (Rez 4, Strength 3)
↳ Do 2 net damage. The Runner may jack out.
↳ Do 2 net damage.

**Palisade** - ICE: Barrier (Rez 3, Strength 2)
While this ice is protecting a remote server, it gets +2 strength.
↳ End the run.

## Question

**Q1:** Find a line that breaches Server 1 using full ICE breaks. What's your ending credit total?

**Q2:** Find a different line where you let one subroutine fire. What's your ending credit total and grip size?

**Q3:** When would you choose the Q2 line over Q1? When would you choose Q1?
