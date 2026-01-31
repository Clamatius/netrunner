# Netrunner Algebraic Notation (NAN) System

This PR consolidates the NAN tooling into a dedicated subproject (`dev/nan/`) and introduces a full **Replay-to-NAN pipeline**.

## 📦 Components

1. **Spec (`docs/nan_spec.md`)**: Updated documentation for the NAN format, now including **Board Positioning** (`@pos`) and **Score Checkpoints** (`[Corp-Runner]`).
2. **Replay Converter (`json_replay_to_nan.py`)**: Converts Jinteki-style JSON replays (from `ai-state.clj`) into NAN format. Handles the diff-patching to reconstruct game history.
3. **Log Parser (`log_parser.py`)**: Core logic for parsing raw game logs into NAN. Now decoupled for reuse.
4. **Renderer (`nan_renderer.py`)**: Visualizes the board state at any turn from a NAN file. Now supports exact ICE positioning.
5. **Parser (`nan_parser.py`)**: Reference implementation for parsing NAN files programmatically.

## 🚀 New Features

### 1. Score Checkpoints

Turn headers now include the score at the start of the turn for easier state tracking/validation.

```nan
Corp T6 [1-0]: Seamless Launch; advance S1; advance S1; score Offworld Office
Runner T6 [3-0]: Overclock; run S1...
```

### 2. Exact Board Positioning

The "inside vs outside" problem is solved. Rez and Encounter actions now specify the 0-indexed position (innermost is 0).

```nan
rez Palisade@1 S1  ; The middle piece of ice
encounter Diviner@0 ; The innermost piece of ice
```

## 🛠️ Usage

**Convert a Replay:**

```bash
python3 dev/nan/json_replay_to_nan.py dev/replays/replay-example.json > game.nan
```

**Visualize the Game:**

```bash
python3 dev/nan/nan_renderer.py game.nan
```

*Output:*

```text
--- Game State at Turn 15 ---
Score: Corp 4 | Runner 0

[ Corp Board ]
HQ         : [Diviner] [Diviner]
R&D        : [Brân 1.0] [Tithe] [Brân 1.0]
S1         : [Karunā] [Palisade] [Palisade] [Palisade] [Manegarm Skunkworks]
```
