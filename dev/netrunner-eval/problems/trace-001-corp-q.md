# Problem: trace-001-corp [Hard]

> **Note:** This puzzle uses cards from the original Android: Netrunner (FFG era), not the current System Gateway format. Standard game: first to 7 agenda points wins.

## Context

You are the Corp (Haas-Bioroid: Engineering the Future). Both players are at 4 agenda points.

The Runner is on their 4th click, making their third Sneakdoor Beta run this turn. They've broken Ichi 1.0's program-trash subroutines with Femme Fatale ($6), and now the trace subroutine is firing.

**Trace rules reminder:** Corp pays first to boost trace strength. Runner then pays credits (plus link) to match or exceed. If Runner cannot or will not match, trace succeeds.

## Board State

```yaml
corp:
  identity: "Haas-Bioroid: Engineering the Future"
  credits: 11
  points: 4
  HQ:
    ice: [Tollbooth (Femme counter), Ichi 1.0, Eli 1.0]
    contents:
      - Archived Memories
      - Adonis Campaign
      - Scorched Earth
      - Accelerated Beta Test
      - NAPD Contract
  R&D:
    ice: [Eli 1.0]
  Archives:
    ice:
      - {card: Ichi 1.0, rezzed: true}  # Runner is encountering this
    contents: [Hedge Fund, Adonis Campaign, Hedge Fund, Ash 2X3ZB9CY]
  Server 1:
    ice: [Enigma (inner), Rototurret, Data Raven]
    root:
      - {card: Adonis Campaign, credits: 3}

runner:
  identity: "Gabriel Santiago: Consummate Professional"
  credits: 8
  points: 4
  link: 0
  clicks: 0  # On click 4, mid-run
  grip: 4 cards (unknown)
  rig:
    - Gordian Blade
    - Desperado
    - Femme Fatale (Tollbooth counter)
    - Sneakdoor Beta
    - Plascrete Carapace (4 power counters)
    - Kati Jones (0 credits)
```

## The Trace

Ichi 1.0's third subroutine: **Trace[1].** If successful, do 1 core damage and give the Runner 1 tag.

The trace is now firing. Base trace strength is 1.

## Card Text Reference

**Ichi 1.0** - ICE: Sentry - Bioroid - Tracer - Destroyer (Rez 5, Strength 4)
Lose [click]: Break 1 subroutine on this ice. Only the Runner can use this ability.
↳ Trash 1 installed program.
↳ Trash 1 installed program.
↳ Trace[1]. If successful, do 1 core damage and give the Runner 1 tag.

**Scorched Earth** - Operation: Black Ops (Cost 3)
Play only if the Runner is tagged.
Do 4 meat damage.

**Archived Memories** - Operation (Cost 0)
Add 1 card from Archives to HQ.

**Plascrete Carapace** - Hardware: Gear (Cost 3)
When you install this hardware, load 4 power counters onto it. When it is empty, trash it.
[interrupt] → Hosted power counter: Prevent 1 meat damage.

**Adonis Campaign** - Asset: Advertisement (Rez 4, Trash 3)
Put 12[credit] from the bank on Adonis Campaign when rezzed. When there are no credits left on Adonis Campaign, trash it.
Take 3[credit] from Adonis Campaign when your turn begins.

**Core Damage** - Permanently reduces Runner's maximum hand size by 1. Runner also discards 1 card randomly from grip when dealt.

## Questions

**Q1:** If you don't boost the trace, what happens? Can Runner beat it?

**Q2:** The Runner has Plascrete Carapace with 4 counters. Single Scorched Earth deals 4 meat damage. Does tagging them and playing Scorched kill them?

**Q3:** Find the line that wins the game this turn cycle. How much should you boost the trace, and what's the exact sequence on your turn?
