# Problem: remote-002-corp [Medium]

> **Note:** Tutorial games are played to **6 points** (not the standard 7).

## Context

You are the Corp, Turn 4, 3 clicks remaining. You have an Offworld Office in hand and want to score it. The Runner has been building their rig but is missing a key piece.

## Board State

```yaml
corp:
  credits: 7
  points: 0
  clicks: 3
  HQ:
    contents: [Offworld Office, Hedge Fund, Nico Campaign]
  Server 1:
    ice:
      - {card: Karunā, rezzed: false}
    root: null
  Server 2:
    ice:
      - {card: Palisade, rezzed: true}
    root: null

runner:
  credits: 4
  points: 0
  clicks: 0  # Their turn just ended
  grip: 3 cards (unknown)
  rig:
    - {card: Cleaver}
    - {card: Unity}
    - {card: Pennyshaver, credits: 1}
  notes: "No Killer installed - Carmen is missing"
```

## Card Text (Auto-Generated)

**Cleaver** - Icebreaker: Fracter (Install 3, Strength 3, 1 MU)
Interface → 1[credit]: Break up to 2 barrier subroutines.
2[credit]: +1 strength.

**Hedge Fund** - Operation: Transaction (Cost 5)
Gain 9[credit].

**Karunā** - ICE: Sentry - AP (Rez 4, Strength 3)
↳ Do 2 net damage. The Runner may jack out.
↳ Do 2 net damage.

**Offworld Office** - Agenda: Expansion (Adv 4, Points 2)
When you score this agenda, gain 7[credit].

**Palisade** - ICE: Barrier (Rez 3, Strength 2)
While this ice is protecting a remote server, it gets +2 strength.
↳ End the run.

**Unity** - Icebreaker: Decoder (Install 3, Strength 1, 1 MU)
Interface → 1[credit]: Break 1 code gate subroutine.
1[credit]: +X strength. X is equal to the number of installed icebreakers (including this one).

## Questions

**Q1:** You install Offworld Office in Server 2 (behind Palisade) and advance it twice. Can Runner steal it next turn? Show the run economics.

**Q2:** You install Offworld Office in Server 1 (behind Karunā) and advance it twice. What happens if Runner runs it? Consider both outcomes of the first subroutine.

**Q3:** Instead of jamming the agenda, you could install Nico Campaign in Server 2 to build economy. Compare ending credits and board position. Is this better or worse than installing Offworld?

**Q4:** What's your best line this turn? Consider server choice AND click sequence (Install-Advance-Advance vs Install-Advance-Credit).
