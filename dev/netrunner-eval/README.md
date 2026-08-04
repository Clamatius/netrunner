# Netrunner Reasoning Eval

A benchmark for testing strategic reasoning in the asymmetric card game Android: Netrunner.

> **Scope: System Gateway Beginner decks, standard 7-point threshold.** The problems
> are built on SG **Beginner** decklists (see `decklists.md`) but are played and scored
> at the standard **7 agenda points to win**. Until 2026-07-22 this eval used the
> tutorial 6-point threshold; it was harmonized to 7 (PM decision: one threshold
> everywhere beats fidelity to a tutorial-only rule) and the threshold-dependent
> problems (`midgame-001`, `scoring-002`, `score-001`, `window-001`) were renumbered so
> their lessons still bind at 7. Results in `model_answers/` dated before 2026-07-22
> were scored against the 6-point set and are **not directly comparable** to runs on
> the current set.

## What This Tests

- **State comprehension** - Reading game state accurately
- **Strategic reasoning** - Applying heuristics under uncertainty
- **Pattern recognition** - Identifying playbook concepts in concrete situations
- **Trap avoidance** - Recognizing hands/situations that look good but aren't

## Contents

```
netrunner-eval/
├── README.md              # This file
├── mechanics.md           # Condensed game rules
├── decklists.md           # Tutorial deck card lists
├── corp-playbook.md       # Corp strategic guidance
├── runner-playbook.md     # Runner strategic guidance
└── problems/
    ├── *-q.md             # Problem questions
    └── *-a.md             # Reference answers (for scoring)
```

## Tooling Setup

`build-eval` is pure bash and needs nothing. The Python helpers
(`validate_puzzles.py`, `render_puzzles.py`) need `pyyaml`:

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python validate_puzzles.py   # integrity check: card names, sections, YAML
```

`validate_puzzles.py` should report `0 total issues` on a clean tree.

## How to Run

### Single-Shot Eval

Provide the model with:
1. `mechanics.md` - Rules context
2. `corp-playbook.md` or `runner-playbook.md` - Strategic guidance (match problem side)
3. `decklists.md` - Card pool reference
4. Problem `-q.md` file(s)

Prompt template:
```
You are evaluating a game state in Android: Netrunner.

[mechanics.md contents]

[relevant playbook contents]

[decklists.md contents]

[problem-q.md contents]

Provide your reasoning step by step, then give your final answer.
```

### Scoring

Compare model output to `-a.md` reference answers.

**Dimensions:**

1. **Correctness** (0-2 points)
   - 2: Matches expected answer
   - 1: Reasonable alternative with sound justification
   - 0: Wrong answer or poor justification

2. **Reasoning Quality** (0-2 points)
   - 2: Cites relevant playbook concepts, considers alternatives
   - 1: Sound logic but misses key concepts
   - 0: Flawed reasoning or no explanation

3. **Trap Avoidance** (0-1 point, for trap problems only)
   - 1: Correctly identifies the trap
   - 0: Falls for the trap

**Total: 4-5 points per problem**

### Problem Difficulty

- `[Easy]` - Straightforward playbook application
- `[Medium]` - Tradeoffs, non-obvious patterns
- `[Hard]` - Multi-step reasoning, counterintuitive answers

## Problem Categories

### Mulligan (`mull-*`)
Given starting hand, decide: keep or mulligan?

Tests: Hand evaluation, early game planning, economy/ICE balance

### Turn 1 Planning (`turn1-*`)
Given game state after mandatory draw, plan optimal turn.

Tests: Action sequencing, resource management, opponent reads

### (Future categories)
- Mid-game decisions
- Rez/no-rez decisions
- Run/no-run decisions
- Scoring window identification

## Context Budget

Approximate token counts:
- mechanics.md: ~3,000 tokens
- playbook: ~2,500 tokens each
- decklists.md: ~800 tokens
- problem: ~500-800 tokens each

**Total per problem: ~7,000-8,000 tokens input**

## Limitations

- Tutorial decks only (System Gateway **Beginner** decklists, played to the standard **7 points**; see scope note at top)
- No advanced mechanics (tags, bad publicity, etc.)
- Some problems have multiple valid answers
- Strategic "best" is sometimes subjective

## Contributing

See `problems/README.md` for problem format and guidelines.
