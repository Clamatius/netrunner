# Answer: midgame-001-runner

## Expected Reasoning

**What's the unrezzed card?**

We saw Urtica Cipher in HQ last turn. A card with 2 advancements in a remote is the classic Urtica bluff line - install, advance twice, looks like a 5/3 agenda.

But Corp also drew a card this turn (mandatory draw). The unseen cards include 2x Send a Message. If it's Send a Message at 2 adv, Corp's next turn: advance, advance, advance, score → **Corp wins**.

**The decision:**
- If it's Urtica: 4 net damage, we have 5 cards, we survive with 1
- If it's Send a Message: We lose next turn if we don't contest

**Risk/reward asymmetry:** Being wrong about "it's probably Urtica" means instant loss. Being wrong about "it might be an agenda" means losing a mediocre hand.

**Grip quality check:**
```
VRcation        - draw 4, lose a click. Costs 2 clicks total, leaves only 2 - can't run Brân
Telework        - nice but we have one on board
Red Team        - $5 install, can't afford + run same turn
Verbal Plasticity - setup card
Carmen          - duplicate, already installed
```

This hand is mostly setup/duplicates. Losing it to Urtica is acceptable.

## Answer

```
Turn start: Smartware Distributor drips → $2 + $1 = $3 in pool
Click 1: Run Server 1
  - Approach Brân (rezzed)
  - Encounter: Click through ALL 3 subs (clicks 2, 3, 4)
    - Sub 1 (ice install): CLICKED - Corp can't grow remote
    - Sub 2 (ETR): CLICKED
    - Sub 3 (ETR): CLICKED
  - Pass Brân, no subs fire
  - Approach Palisade → Boost Cleaver ($2), break ($1) → $0
  - Access:
    - If Urtica: Take 4 net damage, survive with 1 card
    - If agenda: STEAL
```

**Post-access state:**
- Credits: $0 (but Telework has 6, Pennyshaver has 2 for recovery)
- Hand: 1 card (if Urtica) or 5 cards (if agenda)
- **Remote unchanged** - Corp can't lock you out with extra ice

**Why click ALL of Brân (including sub 1)?**

If you Telework first and only click subs 2-3, Corp installs Palisade or Whitespace from sub 1. Now their remote has 3 ice. Even if you get in this turn, future runs cost $5-6 more. You've helped them build a scoring server.

Clicking sub 1 denies this. Remote stays at 2 ice. You can contest again next turn with Telework + Pennyshaver credits.

**Why this is correct even if it's Urtica:**

This is what Urtica is *for* - threatening game point. Corp leverages "maybe it's Send a Message" to protect a trap. But if you call the bluff and survive, Corp is exposed:

- $5 or less, remote penetrated
- Centrals have no ice
- 2x Send a Message still in R&D

The most likely outcome after surviving Urtica: **R&D lock**. You draw back up (Telework, Smartware drip), then run R&D every turn. With no R&D ice and 2 Send a Messages in deck, you'll find them before Corp can rebuild. That's often game.

**Why VRcation doesn't work:**

VRcation draws 4 but costs 2 clicks (play + forced click loss). That leaves only clicks 3-4. Running Brân requires: 1 click to initiate + 2 clicks to pass ETRs = 3 clicks minimum. The math doesn't work - you can't VRcation AND run this turn.

## Common Mistakes
- Telework first → only click subs 2-3 → Corp grows remote with free ice install
- VRcation first → only 2 clicks left → can't pass Brân's ETRs
- Thinking Pennyshaver gives money mid-run (you have to click it)
- Running on click 2+ (not enough clicks to pass all 3 Brân subs)
- Not running because "it's probably Urtica" → lose to Send a Message next turn
- Overvaluing the grip (it's mostly duplicates and setup cards)

## Key Insight

When the downside of being wrong is "lose the game," don't play the odds. 4 damage with 5 cards is survivable. Letting Corp score Send a Message is not. Run it.

**Strategic sequencing matters:** The Telework line looks richer ($5 vs $3) but lets Corp install ice from sub 1, making their remote harder to contest next time. By running on click 1 and clicking ALL of Brân, you deny them this. Getting in broke is better than getting in rich if it keeps the remote small.

Urtica's real purpose is threatening game point - Corp leverages "maybe it's the winning agenda" to protect a trap. But surviving the trap exposes Corp's weak position: poor credits, penetrable remote, naked centrals. The game after eating Urtica often favors Runner - **R&D lock** with 2 Send a Messages still in deck is a real path to victory.
