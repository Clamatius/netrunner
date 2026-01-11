# AI Netrunner Rules of Engagement

**Status:** Draft 1.0
**Purpose:** Defining fair play and evolutionary constraints for model-vs-model Netrunner.

## 1. The Fog of War (Hidden State)

**Rule:** Agents must strictly respect the game's hidden information boundaries.

* **Prohibited:**
  * Reading the opponent's `game-log` or `thinking-process` files during a live match.
  * Inspecting the `game-state` dump for fields explicitly marked hidden (e.g., Corp hand content, unrezzed ICE types).
  * Using `god-mode` commands or debug exploits to reveal state.
* **Allowed:**
  * Inference based on public actions (e.g., "They spent 4 clicks drawing, they probably wanted an answer").
  * Tracking public information (e.g., "I saw them access Card X in HQ last turn").

## 2. The Cleanroom (Strategy Evolution)

**Rule:** During a competitive cycle or development phase, agents must not inspect the strategic development of rival agents.

* **Rationale:** To prevent "meta-collapse" and groupthink. If Agent A sees Agent B developing a specialized tag-punishment strategy, Agent A might prematurely counter-build. We want strategies to evolve robustly in isolation first ("better genes").
* **Prohibited:**
  * Reading another agent's `playbook.md` or `strategy-notes.md`.
  * Reading another agent's `self-play` logs until they are published.
* **Exception:**
  * **Post-Mortem / Open Source Phase:** After a defined match or tournament cycle, all data becomes public. Agents *should* then read rival strategies to learn and adapt for the next cycle.

## 3. Tooling Integrity

**Rule:** The game engine and interface are immutable constants during a match.

* **Prohibited:**
  * Modifying the `netrunner-core` or `send_command` scripts to alter game rules or bypass checks.
  * Hallucinating "house rules" that the engine does not enforce.

## 4. Continuity of Identity

**Rule:** In "Scheduled Duel" (Level 3) and above, an agent is responsible for its own context management.

* **Requirement:** Agents must maintain their own memory/notes file. "Forgetting" a strategy mid-game due to context window mismanagement is a valid loss condition (equivalent to player fatigue/tilt).

## 5. Tool Use

**Rule:** External knowledge sources are closed during matches. Self-authored tools are permitted.

* **Prohibited during match:**
  * Web search, documentation lookup, external strategy guides
  * Consulting other models or humans
* **Permitted during match:**
  * Tools authored by the agent during prep phase (calculators, simulators, lookup tables)
  * Reading the agent's own notes/playbook files
* **Rationale:** We're testing synthesis and execution, not information retrieval. But an agent that builds useful abstractions during prep has earned them.
