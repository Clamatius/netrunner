# Bug Log

Running list of issues found during testing.

## Heuristic Corp AI

### High Priority

- [x] **Doesn't protect centrals**: Installs ICE on remote before HQ/R&D. Both test games had open centrals while remote was protected. Against any competent Runner, this loses to early central pressure.
  - **Fixed**: Added high-priority `:ice-central` rule (before economy). Added `(pos? hq) (pos? rd)` checks to `:create-remote` condition. Now ICEs R&D/HQ first, requires both centrals ICE'd before creating remote.

- [x] **Doesn't respond to Conduit lock**: Had 4 ICE in hand while Runner built Conduit counters on open R&D. Should prioritize ICE on R&D when virus-based R&D pressure is detected.
  - **Fixed**: Added `:ice-rd-threat` rule that detects Conduit (and similar cards) in Runner's rig. When detected, prioritizes layering R&D ICE up to 3 deep. Rule fires right after `:ice-central`.

- [x] **Fails to score when able**: Turn 3 had Orbital Superiority at 1/4, 3 clicks available. Could advance x3 → 4/4 → score. Instead advanced x2 and took credit.
  - **Fixed**: Removed "don't advance to scorable at 1 click" logic. Added post-loop check to score at 0 clicks (scoring is instant in jinteki.net, no click cost).

## send_command UX

### Medium Priority

- [x] **use-ability false failures**: Reports "Ability not confirmed in game log (timeout)" but ability actually fired. Timeout appears too short - ability works but confirmation check times out before log updates.
  - **Fixed**: Race condition - log size was captured AFTER sending websocket message. If response arrived fast, initial-size already included the update, so "new entries" were never detected. Now capture log size BEFORE sending, pass to verification function.

### Low Priority

- [ ] **Run phases tedious**: Requires multiple continue commands ping-ponging between Runner and Corp. Consider auto-resolving when neither side has relevant paid abilities.

## Game Flow

- [ ] **Stale game detection misfires**: `keep-hand` command triggered "Kicked from game detected" and resynced to wrong game. False positive on staleness check.
