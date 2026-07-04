# Netrunner Algebraic Notation (NAN) Specification

NAN is a concise, human-readable, and machine-parsable format for recording Netrunner games, inspired by Chess Algebraic Notation.

## Structure

- The file consists of a series of turns.
- Each turn starts with the player and turn number: `Player T#:`.
- Optional score checkpoint in header: `Player T# [CorpScore-RunnerScore]:`.
- Optional credit checkpoint in header: `Player T# [0-2] {C14 R10}:` — both
  players' credit pools at the start of the turn (after the turn owner's
  start-of-turn income/checkpoint).
- Actions within a turn are separated by semicolons `;`.
- The file ends with a newline.

## Credit Annotations

Actions that change a credit pool carry the acting side's *new total*:
`credit →C6`, `Hedge Fund →C14`, `rez Karunā@0 R&D →C5` (a Corp rez during
the Runner's turn shows the Corp total). Costs and payoffs on the same
action fold together: `Sure Gamble →R9` is net (-5 play, +9 gain). A credit
change with no rendered action (drip income, tag removal, subroutine
credits) appears as a bare total: `→R7`. Totals are recomputed from log
deltas and resynced against the authoritative "started/ending their turn
with N [Credit]" log lines; disagreements are corrected and reported on
stderr at generation time.

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
