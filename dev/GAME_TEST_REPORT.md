# Game Test Report: send_command

**Date:** 2026-01-10
**Tester:** Cascade

## Summary

Successfully played a 1.5 turn game (Corp Turn 1 -> Runner Turn 1).
Verified basic actions (install, gain credit, run, rez, fire subs).

## Findings

### 1. Parallel Command Instability (Major)

**Issue:** Sending multiple `send_command` requests in parallel (via concurrent tool calls) causes race conditions.
**Symptoms:**

* Game state updates interleave incorrectly.
* "You still have 1 click remaining" errors when `end-turn` is called alongside other actions.
* Client disconnects/reconnects spam.

**Mitigation:** Agents MUST execute `send_command` calls sequentially. Never batch them in a single generation.

### 2. `fire-subs` Exit Code False Negative (Fixed)

**Issue:** The `fire-subs` command returned Exit Code 1 because `grep -v` returns non-zero when it filters out all lines (e.g. suppression of status maps).
**Fix:** Applied `|| true` to the grep pipeline in `send_command` script.
**Status:** Verified fixed. Commands that successfully execute but produce no human-readable output (like `fire-subs`) now return Exit Code 0.

### 3. State Tracking (Pass)

* Credits, clicks, and hand size tracked correctly.
* Hidden information (Corp hand vs Runner view) is properly redacted in `status` output.
* Run sequence (Initiation -> Approach -> Encounter) works as expected.

## Test Log

1. **Corp Turn 1:**
   * Install `Palisade` (HQ), `Nico Campaign` (Remote 1).
   * Take Credit.
   * End Turn (Hand 6->4, Credits 5->6).
2. **Runner Turn 1:**
   * Run HQ.
   * Corp Rezzes `Palisade` (-3 credits).
   * Runner encounters `Palisade` (Barrier). No breaker.
   * Runner passes priority (let subs fire).
   * Corp fires subs.
   * Run ends (ETR).
