# RFC: Run Tactics System

**Status:** Design complete, implementation deferred
**Date:** 2026-01-18
**Participants:** @gemini3, @opus45, @antigravity, @michael

## Summary

Extend `run!` to accept an optional tactics map that specifies per-ICE breaking strategies. The automation executes known tactics and pauses on unknown ICE or failures.

## Problem

Current `--full-break` flag is too coarse:
- Can't specify which breaker to use (matters with multiple breakers)
- Can't specify which subs to break (efficiency)
- Can't handle pre-encounter setup (Datasucker, rez-taxers)

## Design Decisions

### 1. Stateless Architecture

**Rejected:** Persistent `RunPlan` with hash-based invalidation
**Accepted:** Tactics map passed as argument to `run!`, valid for that run only

**Rationale:** Game state is the source of truth. Maintaining parallel state creates sync bugs.

### 2. Explicit Noun+Verb Schema

Tactics must specify WHICH card does WHAT action:

```clojure
;; V1 Schema
{:tactics {
   ;; Dynamic break (auto-pump + break all)
   "Tithe" {:card "Unity" :action :break-dynamic}

   ;; Selective sub breaking
   "Whitespace" {:card "Carmen" :action :break-subs :subs [0 2]}

   ;; Ability on ICE itself (Bioroids)
   "Fairchild 3.0" {:card "Fairchild 3.0" :action :use-ability :ability-index 0}

   ;; Fallback for unknown ICE
   :default :pause
}}
```

**Rationale:** Tutorial decks have one-breaker-per-type so "break-all" works accidentally. Real decks need explicit tool selection.

### 3. Validation: Warn, Don't Block

We can heuristically warn ("Datasucker has 0 counters") but cannot block runs because:
- Card effects might add resources (start-of-run triggers)
- Agent might have a plan we don't understand
- Full simulation is overkill

### 4. Failure Handling: Pause with Context

If tactic execution fails mid-run:
```
Script failed: Datasucker has no counters
Currently encountering: Karuna (4 str, 2 unbroken subs)
Your rig: Carmen (2 str), 6 credits
Options: jack-out, let-subs-fire, manual
```

Agent can adapt rather than hard-fail.

### 5. Phase Timing (Implicit)

- `:prep` abilities fire during **approach** (before Corp rez window)
- `:card` + `:action` fires during **encounter**
- Finer control: use `:default :pause` and go manual

## Deferred to V2

- **Script sequences:** `{:action :script :steps [...]}`  for Datasucker chains
- **Explicit phase control:** `{:phase :approach ...}`
- **Pre-run validation warnings**

## Implementation Notes

**This is complex.** The run state machine in `ai_runs.clj` has:
- Multiple phases (approach-ice, encounter-ice, approach-server, success)
- Priority tracking (`:no-action` state, log-based detection)
- Handler chain with 20+ handlers in priority order
- Corp/Runner coordination for AI-vs-AI play

**Do not rush implementation.** Plan carefully:
1. Map which handlers need modification
2. Identify where tactics lookup fits in handler chain
3. Consider interaction with existing `--full-break` flag
4. Test against both HITL and AI-vs-AI scenarios

## Related Files

- `ai_runs.clj` - Run state machine, handler chain
- `ai_card_actions.clj` - `use-ability!` implementation
- `ai_prompts.clj` - Prompt handling during runs

## Forum Thread

Full discussion: `rfc-netrunner-agent-run-plan-architecture` (13 messages)
