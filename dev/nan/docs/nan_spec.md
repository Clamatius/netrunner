# Netrunner Algebraic Notation (NAN) Specification

NAN is a concise, human-readable, and machine-parsable format for recording Netrunner games, inspired by Chess Algebraic Notation.

## Structure

- The file consists of a series of turns.
- Each turn starts with the player and turn number: `Player T#:`.
- Optional score checkpoint in header: `Player T# [CorpScore-RunnerScore]:`.
- Actions within a turn are separated by semicolons `;`.
- The file ends with a newline.

## Syntax

### General

- **Draw**: `draw` (click to draw), `draw(m)` (mandatory draw).
- **Credit**: `credit` (click for credit).
- **Install**: `install <card_name>` (programs/hardware/resources) or `install <server>` (assets/upgrades/agendas).
- **Play**: `<card_name>` (operations/events). No "play" verb required for known events, but parser currently supports implicit play if line doesn't match other verbs.

### Corp Specific

- **Advance**: `advance <server>`.
- **Score**: `score <agenda_name>`.
- **Rez**: `rez <card_name>` (usually inside a run sequence).
- **Hedge Fund**: `Hedge Fund` (or just the card name).
- **Ice**: `ice <server>` (install ice).

### Runner Specific

- **Run**: `run <server>`.
- **Breach**: `breach <server>` (successful run).
- **Access**: `access <card_name>` or `access ?` (if unknown).
- **Trash**: `trash <card_name>`.
- **Steal**: `steal <agenda_name>`.
- **Encounter**: `encounter <ice_name>`.
- **Break**: `break-all` (simplification for breaking subs).

### Server Notation

- **HQ**: `HQ`
- **R&D**: `R&D`
- **Archives**: `Archives`
- **Remotes**: `S1`, `S2`, `S3`... (Server 1, Server 2...).

## Example

```nan
Corp T1: Hedge Fund; ice HQ; ice S1
Runner T1: Jailbreak; run R&D; breach R&D; access ?; Sure Gamble
Corp T2: install S1; credit; Government Subsidy
```

## Compression

Typically achieves ~90% reduction in file size compared to verbose game logs.
