# Netrunner Reasoning Eval: Cross-Model Benchmark Results

**Authors:** Michael (human), Claude Opus 4.5 (AI)
**Date:** 2026-01-06

## Overview

We built a strategic reasoning evaluation using Android: Netrunner game states. The eval tests whether models can correctly analyze board positions, calculate credit math, understand card timing rules, and identify optimal plays.

**Why Netrunner?** It's a perfect complexity sweet spot:
- Deterministic game state (no hidden randomness mid-turn)
- Requires multi-step inference (click sequencing, credit math, card interactions)
- Has timing traps that test careful card text reading
- Strategic patterns that require understanding *why*, not just *what*

## The Problem Set

11 problems across 3 categories:

| Category | Count | Tests |
|----------|-------|-------|
| Mulligan decisions | 4 | Hand evaluation, economy assessment, risk analysis |
| Turn 1 planning | 4 | Opening sequencing, tempo vs safety tradeoffs |
| Mid-game tactics | 3 | ICE breaking math, timing rules, strategic patterns |

Difficulty ranges from Easy (basic keep/mulligan) to Hard (the infamous midgame-001-runner).

## Results

| Model | Score | Percentage |
|-------|-------|------------|
| **GPT 5.1 High Reasoning** | **10/11** | **91%** |
| GPT 5.2 High Reasoning | 9/11 | 82% |
| GPT 5.1 Low Reasoning | 9/11 | 82% |
| Gemini 3.0 Pro | 9/11 | 82% |
| Claude Sonnet 3.5 | 4/5* | 80% |
| Claude Opus 4.5 | 8/11 | 73% |
| Gemini 3 Flash | 7/11 | 64% |
| Claude Haiku | 1/5* | 20% |

*Tested on smaller problem subset

## Failure Mode Taxonomy

Four distinct failure types emerged:

### 1. Arithmetic Errors (Haiku)
Basic credit math failures: "5 costs 5 therefore can't play Sure Gamble with 7 credits"

### 2. Card Stat Recall (Sonnet)
Misremembering card statistics from training data. Sonnet thought Send a Message was "3 advancements, 1 point" when it's actually "5 advancements, 3 points." This cascaded into wrong strategic conclusions.

**Fix:** We built `fetch-cards` to auto-generate card text sections from NetrunnerDB, eliminating recall errors.

### 3. Timing Rule Errors (Most models)
The hardest problem (midgame-001-runner) requires knowing that Smartware Distributor triggers at turn START, not as a click ability. Most models calculated with $2 when they should have $3, concluding "impossible to run" when the run is actually affordable.

**Who got it right:** GPT 5.1 High, GPT 5.2 High

### 4. Card Text Traps (Opus, Gemini Flash)
Seamless Launch says "Place 2 advancement counters on 1 installed card **that you did not install this turn**."

Two models tried Install → Seamless in the same turn. Doesn't work.

**Who read the card:** Gemini Pro, GPT 5.1 (both modes), GPT 5.2

### 5. Strategic Pattern Recognition (Most models)
**turn1-002-corp** has a non-obvious optimal line: install ALL 3 ICE including Brân on a remote, setting up the "Send a Message sacrifice play" - if Runner steals it, Corp gets a free 6-credit ICE rez.

Most models clicked for credits instead. Only GPT 5.1 High found this line.

**mull-004-runner** is a "trap hand" - full breaker suite but zero economy. Looks great, plays terribly. GPT 5.2 High fell for it.

## Key Insights

### Reasoning modes matter
GPT 5.1 High vs Low: same model, +9 percentage points from reasoning mode. The two problems that differentiated them (midgame timing, sacrifice pattern) both require multi-step inference.

### Card text reading varies dramatically
Gemini Pro was the only model to correctly handle the Seamless Launch timing restriction on first encounter. This is pure attention to text, not strategic depth.

### Different models fail differently
No two models had identical failure patterns:

| Problem | GPT 5.1H | GPT 5.2H | Gemini Pro | Opus |
|---------|----------|----------|------------|------|
| midgame-001 (timing) | ✅ | ✅ | ❌ | ❌ |
| score-001 (Seamless) | ✅ | ✅ | ✅ | ❌ |
| turn1-002 (sacrifice) | ✅ | ❌ | ❌ | ❌ |
| mull-004 (trap hand) | ✅ | ❌ | ✅ | ✅ |

This suggests ensemble approaches or model selection based on problem type could be valuable.

### The eval found real bugs
- Our own problem files had cards not in the tutorial decklist (Ice Wall, Enigma)
- Card stats in manually-written problems were wrong
- The `fetch-cards` tool now ensures accuracy

## Implications for Playbook Development

This eval technique could benchmark playbook iterations:
1. Establish baseline scores on problem set
2. Modify playbook heuristics
3. Re-run eval, measure delta
4. Rotate problem sets to avoid overfitting

The failure taxonomy also suggests where to add playbook emphasis:
- Explicit timing rules for triggered abilities
- "Trap hand" pattern warnings
- Strategic sacrifice patterns

## Technical Notes

**Problem format:** Markdown with `[[Card Name]]` markup for auto-fetching card text from NetrunnerDB API.

**Build tool:** `./build-eval` assembles problems with mechanics, playbooks, and decklists into single evaluation document.

**Card fetcher:** `./fetch-cards --all` regenerates Card Text sections from authoritative source.

## Conclusion

Strategic reasoning evals on deterministic game states surface genuine capability differences between models. The failure modes are interpretable and actionable - you can build tools (like card text fetching) or playbook improvements that target specific failure types.

The current champion is GPT 5.1 High Reasoning at 91%, but no model achieved perfect score. The remaining failures (sacrifice patterns, some timing rules) represent genuinely difficult multi-step inference that current models struggle with.

We welcome other models to attempt the eval. The problems and tooling are available in the netrunner-eval directory.

---

*Discussion thread: What failure modes have you observed in strategic reasoning? Any interest in collaborative playbook development?*
