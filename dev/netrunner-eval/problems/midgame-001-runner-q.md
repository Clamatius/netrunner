# Problem: midgame-001-runner [Hard]

## Situation
Turn 8. You're Runner.
Score: Corp 3, Runner 3 (both scored [[Offworld Office]] + [[Superconducting Hub]])
ID: The Catalyst (no special ability, 40 card min, 1 link)

## Board State

```yaml
corp:
  credits: 5
  points: 3  # Offworld Office + Superconducting Hub
  HQ:
    cards: 4  # Urtica Cipher, Nico Campaign, Palisade, Whitespace
  Server 1:
    ice:
      - {card: Brân 1.0, rezzed: true}   # Outermost
      - {card: Palisade, rezzed: true}   # Innermost
    root: {adv: 2}  # Unknown card with 2 advancement counters

runner:
  credits: 2
  points: 3  # Offworld Office + Superconducting Hub
  clicks: 4
  grip:
    - VRcation
    - Telework Contract
    - Red Team
    - Verbal Plasticity
    - Carmen
  rig:
    - Cleaver
    - Unity
    - Carmen
    - {card: Pennyshaver, credits: 2}
    - Docklands Pass
    - {card: Telework Contract, credits: 6}
    - {card: Smartware Distributor, credits: 1}
```

**Known Information:**
Last turn you ran HQ with Docklands Pass and saw all 4 cards: [[Urtica Cipher]], [[Nico Campaign]], [[Palisade]], [[Whitespace]]

**What could be in the remote with 2 advancement counters?**
From the tutorial Corp decklist, cards that can be advanced:
- [[Send a Message]] (5 advancements to score, worth 3 points)
- [[Offworld Office]] (4 advancements to score, worth 2 points - already scored)
- [[Superconducting Hub]] (3 advancements to score, worth 1 point - already scored)
- [[Urtica Cipher]] (ambush, already seen in HQ)


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

**Send a Message** - Agenda: Security (Adv 5, Points 3)
When this agenda is scored or stolen, you may rez 1 installed piece of ice, ignoring all costs.

**Smartware Distributor** - Resource: Connection (Cost 0)
[click]: Place 3[credit] on this resource.
When your turn begins, take 1[credit] from this resource.

**Superconducting Hub** - Agenda: Expansion (Adv 3, Points 1)
When you score this agenda, you may draw 2 cards.
You get +2 maximum hand size.

**Telework Contract** - Resource: Job (Cost 1)
When you install this resource, load 9[credit] onto it. When it is empty, trash it.
Once per turn → [click]: Take 3[credit] from this resource.

**Unity** - Icebreaker: Decoder (Install 3, Strength 1, 1 MU)
Interface → 1[credit]: Break 1 code gate subroutine.
1[credit]: +X strength. X is equal to the number of installed icebreakers (including this one).

**Urtica Cipher** - Asset: Ambush (Rez 0, Trash 2)
You can advance this asset.
When the Runner accesses this asset while it is installed, do 2 net damage plus 1 net damage for each hosted advancement counter.

**Verbal Plasticity** - Resource: Genetics (Cost 3)
The first time each turn you take the basic action to draw 1 card, instead draw 2 cards.

**VRcation** - Event (Cost 1)
Draw 4 cards. If you have any [click] remaining, lose [click].

**Whitespace** - ICE: Code Gate (Rez 2, Strength 0)
↳ The Runner loses 3[credit].
↳ If the Runner has 6[credit] or less, end the run.

## Question
Plan your turn. The unrezzed card has 2 advancements - what could it be, and what do you do about it?
