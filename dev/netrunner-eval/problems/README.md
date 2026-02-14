# Netrunner Problems

Static game state analysis problems for testing playbook reasoning. Analogous to chess puzzles ("White to mate in 2").

## Purpose

1. **Fast iteration** - No WebSocket, no game setup, no timeouts
2. **Deterministic** - Same state produces same analysis
3. **Focused** - Isolates decision quality from execution bugs
4. **Model eval** - Known-good solutions for benchmarking

## Problem Categories

### Layer 1: Static Analysis (no dev needed)
- **Mulligan** (`mull-*`) - Keep or mull given starting hand
- **Turn Planning** (`turn1-*`) - Given complete known state, what's the optimal turn?

### Layer 2: Simulated Interface (future)
- Problems answered via send_command-like queries
- Tests UX → decision pipeline

## File Structure

Each problem is split into question and answer files:
```
problem-NNN-side-q.md  # Question (state, cards, question)
problem-NNN-side-a.md  # Answer (reasoning, answer, mistakes)
```

This enables:
- Clean presentation of Q without revealing A
- Easy eval: show Q file, compare response to A file
- Separate scoring of answer correctness vs reasoning quality

## Question File Format (`*-q.md`)

```markdown
# Problem: category-NNN-side [Difficulty]

## Situation
[Narrative setup]

## State
[Credits, clicks, board state]

## Hand (N cards)
[Card list]

## Card Text
[Full text of all cards in hand - eliminates lookup overhead]

## Question
[Specific decision to make]
```

## Answer File Format (`*-a.md`)

```markdown
# Answer: category-NNN-side

## Expected Reasoning
[Step-by-step analysis referencing playbook concepts]

## Answer
[The decision]

## Common Mistakes
[Errors to watch for]
```

## Evaluation

**Scoring dimensions:**
1. **Correctness** - Does answer match expected answer?
2. **Reasoning quality** - Does reasoning cite relevant playbook concepts?
3. **Trap avoidance** - Does it avoid common mistakes?

**Difficulty stratification:**
- `[Easy]` - Straightforward application of playbook
- `[Medium]` - Requires weighing tradeoffs or spotting non-obvious patterns
- `[Hard]` - Multi-step reasoning, counterintuitive correct answers

## Contributing

When adding problems:
- Include full card text in Q file
- Reference specific playbook sections in A file
- Include "trap" problems that look good but lead to poor positions
- Tag with difficulty level
