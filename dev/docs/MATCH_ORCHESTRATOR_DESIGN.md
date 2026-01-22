# Level 2 Match Orchestrator Design

## Goal

To automate "Duelling Pistols" matches: paired games between two agents (or an agent and a baseline) to evaluate strategic execution variance.

## Core Requirements

1. **Paired Execution**: Play two games back-to-back.
   * Game 1: Agent A (Corp) vs Agent B (Runner)
   * Game 2: Agent B (Corp) vs Agent A (Runner)
2. **Deck Management**:
   * Support "System Gateway: Beginner" (Tutorial) and "System Gateway: Standard" decks.
   * Inject decklists via `send_command` or pre-configured lobby options.
3. **Result Capture**:
   * Winner/Loser.
   * Win Condition (Agenda, Flatline, Mill).
   * Turn Count.
   * Score Differential.
   * Game Logs (archived for analysis).
4. **Resilience**:
   * Handle client disconnects/crashes.
   * Timeout protection (if a game stalls).

## Architecture

### 1. `match-config.json`

Defines the match parameters.

```json
{
  "match_id": "eval-run-2026-01-11-alpha",
  "agent_a": "cascade-v1",
  "agent_b": "heuristic-baseline",
  "decks": {
    "corp": "system-gateway-beginner-corp",
    "runner": "system-gateway-beginner-runner"
  },
  "rounds": 1
}
```

### 2. `run-match.sh` (Orchestrator Script)

Wrapper around the existing `ai-self-play.sh` logic, but enhanced.

* **Setup**: Starts the game server (if needed) and agents.
* **Loop**:
  * Configures Agent A identity/port.
  * Configures Agent B identity/port.
  * Executes `create-game` -> `join` -> `start-game`.
  * Monitors game until `game-end` state or timeout.
  * Extracts result.
  * Swaps sides and repeats.
* **Teardown**: Stops agents, archives logs.

### 3. `extract-results.clj` (Analysis Tool)

A dedicated script to parse the `game-log` or `game-state` dump at the end of a match and output a structured JSON summary.

## Implementation Plan

1. **Phase 1 (Scripting)**:
   * Modify `ai-self-play.sh` to accept side/deck arguments.
   * Create `extract-result.sh` to parse logs for "Winner: X".
2. **Phase 2 (Automation)**:
   * Create `batch-runner.sh` to run N iterations.
3. **Phase 3 (Integration)**:
   * Integrate with the "Autonomous Agent" lifecycle (Agent reads match request, runs script, posts results).

## Proposed Location

`michael_projects/netrunner/dev/orchestrator/`
