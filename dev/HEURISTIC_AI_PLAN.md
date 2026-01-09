# Heuristic AI Development Plan

## Current State
- **Corp heuristic player working** (`ai_heuristic_corp.clj`)
- Plays tutorial deck competently against goldfish
- Scores agendas, protects remotes, manages economy

## Development Methodology
**"Watch the goldfish fall in holes"**
1. Run bot against goldfish (or later, against itself)
2. Observe failure mode
3. Add branch to decision tree to cover that case
4. Repeat

## Known Holes (TODO)

### High Priority
- [ ] **Send a Message target selection** - Currently picks "Done" (option 0) instead of actually choosing a valid target. Should look for installable ICE or scorable agenda.
- [ ] **Drip asset timing** - Currently doesn't rez PAD Campaign etc. Should rez at opponent's EOT during paid ability window.
- [ ] **Economy prioritization** - Bot can strand itself with 0 credits. Should play Hedge Fund earlier if available and affordable.

### Medium Priority
- [ ] **Smarter discard selection** - Currently discards first card. Should discard duplicates, then lowest-value cards (operations over agendas).
- [ ] **Install order for agendas** - Sometimes installs 5/3 when a 3/2 is faster. Should prefer faster-to-score agendas.
- [ ] **Remote slot management** - Only one remote currently. Could use multiple remotes for assets vs agendas.

### Low Priority (Polish)
- [ ] **ICE quality selection** - Currently installs first ICE. Could prefer higher-strength or ETR ICE for agenda servers.
- [ ] **Credit threshold tuning** - Current min-credits of 5 is arbitrary. Could adjust based on board state.
- [ ] **Overadvance consideration** - Some agendas benefit from overadvancing.

## Future: Runner Heuristic

### Why It's Harder
- Run decisions are complex (which server? when?)
- ICE breaking requires matching breakers to ICE types
- Risk assessment (can I afford to hit unrezzed ICE?)
- Multi-access decisions (how many cards to access?)

### Suggested Approach
1. Start with economy-only runner (just takes credits, installs programs)
2. Add "safe run" logic (only run if all ICE is rezzed and breakable)
3. Add "poke run" logic (run centrals when Corp is low on credits)
4. Add agenda-stealing logic (steal vs trash decisions)

## Testing Commands
```bash
./dev/reset.sh                    # Fresh game
./dev/send_command corp bot-turn  # Corp plays full turn
./dev/send_command corp bot-status # Show decision state

# Goldfish runner
./dev/send_command runner start-turn && \
./dev/send_command runner take-credit && \
./dev/send_command runner take-credit && \
./dev/send_command runner take-credit && \
./dev/send_command runner take-credit
```

## Architecture Notes
- Decision tree in `decide-action` function
- State queries in helper functions (scorable-agendas, etc.)
- Action execution via existing `ai_card_actions` functions
- Prompt handling in `handle-prompt-if-needed`

All changes should be isolated to `ai_heuristic_corp.clj` - no changes needed to core game code.
