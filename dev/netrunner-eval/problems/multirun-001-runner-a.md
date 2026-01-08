# Answer: multirun-001-runner

## Expected Reasoning

**Breaking Karunā:**
- Karunā: Sentry, Strength 3, 2 subs
- Carmen: Killer, Strength 2
- Boost: 2 credits (+3 strength, 2→5)
- Break 2 subs: 2 credits (1 each)
- **Cost per HQ run: 4 credits**

**Carmen's install discount:**
- Base cost: 5 credits
- "If you made a successful run this turn, costs 2 less"
- **With discount: 3 credits**

**Free run options:**
- Archives has no ICE protecting it
- Running Archives triggers: Carmen discount + Pennyshaver (+$1)

**The key trade-off:**
Running HQ twice requires: Install Carmen ($3) + 2 runs ($8) = $11
With $9, can't afford two HQ runs.

Docklands Pass costs $2 but gives +1 access on first HQ breach.
Install Carmen ($3) + Docklands ($2) + 1 run ($4) = $9 exactly!

## Answer

**Maximum HQ accesses: 2**

**Optimal sequence:**
1. Click 1: Run Archives (free, successful run triggers Carmen discount, Pennyshaver → $2)
2. Click 2: Install Carmen ($3 discounted). Cash: $6
3. Click 3: Install Docklands Pass ($2). Cash: $4
4. Click 4: Run HQ (boost $2 + break $2 = $4). Cash: $0. Access 2 cards.

## Red Herrings

- **Pennyshaver click:** "[click]: Place 1, then take all" = nets +$3 for a click. But using this click means losing a run click, which costs more value.
- **Smartware Distributor:** Has $0. Click places 3 credits but doesn't give money this turn (turn-start trigger takes 1).
- **Unity:** Only breaks code gates. Karunā is a sentry.
- **Running HQ twice:** Would need $8 for runs (after $3 Carmen install) but only have $6.

## Why This Is Medium Difficulty

Models must:
1. Recognize Archives run is free AND triggers Carmen's install discount
2. Calculate break cost correctly (boost + per-sub break cost)
3. Compare "more runs" vs "Docklands bonus" trade-off
4. Notice $9 = $3 + $2 + $4 exactly (no waste)

## Common Mistakes

- Forgetting Carmen's install discount (running Archives first)
- Calculating Pennyshaver click as better than it is
- Trying to run HQ twice without enough credits
- Missing that Docklands = +1 access for $2 + 1 click, same as a second run would cost
