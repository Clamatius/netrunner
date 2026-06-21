import json
import re
import sys
import os
from log_parser import parse_log_lines, generate_dsl

def patch(state, diff):
    """
    Apply a Clojure 'differ' style diff to a state.
    Diff is expected to be [changes, removals].
    """
    if not isinstance(diff, list) or len(diff) != 2:
        # Maybe it's not a diff but a whole new state?
        # Or maybe the replay format is different.
        # For now, assume it fits the pattern.
        return diff

    changes, removals = diff
    
    new_state = _apply_changes(state, changes)
    new_state = _apply_removals(new_state, removals)
    return new_state

def _apply_seq_changes(state, changes):
    """Apply a Clojure 'differ' sequence diff to a list.

    differ encodes vector alterations as a flat list of (marker, value) pairs:
      - marker "+" => append value to the end of the list
      - integer index (int or digit-string) => recurse/replace at that index
    The replay log is append-only ("+"), but index edits are handled too so
    other vectors (servers, rig, ...) reconstruct correctly.
    """
    new_list = list(state)
    i = 0
    while i + 1 < len(changes):
        marker, value = changes[i], changes[i + 1]
        if marker == "+":
            new_list.append(value)
        else:
            try:
                idx = int(marker)
            except (ValueError, TypeError):
                idx = None
            if idx is not None:
                while len(new_list) <= idx:
                    new_list.append(None)
                new_list[idx] = _apply_changes(new_list[idx], value)
        i += 2
    return new_list


def _apply_changes(state, changes):
    if state is None:
        return changes

    # Clojure differ sequence diff: list-of-pairs against a list state.
    if isinstance(changes, list) and isinstance(state, list):
        return _apply_seq_changes(state, changes)

    if isinstance(changes, dict):
        if not isinstance(state, dict) and not isinstance(state, list):
            # Replacing primitive with dict?
            return changes
            
        # If state is list, treat it as dict with integer keys for patching?
        # differ in Clojure treats vectors as vectors but diffs might use indices.
        # Let's handle list state by converting to dict temporarily or checking indices.
        is_list = isinstance(state, list)
        if is_list:
            # Convert list to dict for patching
            # This assumes changes has integer keys (as strings or ints)
            temp_state = {str(i): v for i, v in enumerate(state)}
        else:
            temp_state = state.copy()

        for k, v in changes.items():
            if k in temp_state:
                temp_state[k] = _apply_changes(temp_state[k], v)
            else:
                # New key
                temp_state[k] = v
                
        if is_list:
            # Reassemble list. 
            # We need to find the max index.
            # Only integer-looking keys count.
            indices = [int(k) for k in temp_state.keys() if str(k).isdigit()]
            if not indices:
                return []
            max_idx = max(indices)
            new_list = [None] * (max_idx + 1)
            for k, v in temp_state.items():
                if str(k).isdigit():
                    new_list[int(k)] = v
            return new_list
        else:
            return temp_state
            
    # Primitive value replacement
    return changes

def _apply_removals(state, removals):
    if not removals:
        return state
        
    if isinstance(removals, list):
        # Removing keys from state (which is dict)
        # removals is a list of keys to remove?
        # Or removals is a set? In JSON it's a list.
        if isinstance(state, dict):
            new_state = state.copy()
            for k in removals:
                if k in new_state:
                    del new_state[k]
            return new_state
        # If state is list, removals of indices?
        # That would shift things? differ usually avoids shifting if possible or just sends full update.
        # But if it sends removals for a vector, it might mean setting to nil or resizing?
        # Let's assume dict-like removals for now.
        return state

    if isinstance(removals, dict):
        # Nested removals
        if isinstance(state, dict):
            new_state = state.copy()
            for k, v in removals.items():
                if k in new_state:
                    new_state[k] = _apply_removals(new_state[k], v)
            return new_state
        if isinstance(state, list):
            # Similar to changes, map removals to indices
            # But converting back is tricky if we don't know if it's a hole or a shrink.
            # Usually logs just grow, so we might not hit removals on the log vector.
            pass
            
    return state

def extract_log(state):
    # game-state -> log
    # log is usually a list of dicts: [{"user": "...", "text": "..."}]
    if not state: return []
    
    # Check simple path
    log = state.get('log')
    if not log:
        # Maybe nested under game-state?
        log = state.get('game-state', {}).get('log')
        
    if not log:
        return []
        
    # Replay log entries are dicts {user, text, timestamp}. In jinteki replays
    # game events are ALL "__system__" messages with the acting player embedded
    # in the text ("ai-corp scores ...", "ai-runner steals ..."). log_parser
    # expects raw text-log triples:
    #   Actor
    #   [timestamp]
    #   action text
    # so we reconstruct the actor from the text prefix rather than the user
    # field. Non-event chrome ("[hr]", "ai-corp has created the game.") yields
    # an actor token but simplify_action returns None for it, so it's dropped.
    lines = []
    for entry in log:
        if not isinstance(entry, dict):
            continue
        text = entry.get('text', '')
        if not text:
            continue
        user = entry.get('user', 'Unknown')
        if user and user != "__system__":
            actor = user
        else:
            m = re.match(r'([\w.-]+) ', text)
            actor = m.group(1) if m else "System"
        lines.append(actor)
        lines.append("[00:00:00]")  # synthetic timestamp; parser only checks "[" prefix
        lines.append(text)

    return lines

def main():
    if len(sys.argv) < 2:
        print("Usage: python json_replay_to_nan.py <replay.json>")
        sys.exit(1)
        
    with open(sys.argv[1], 'r') as f:
        data = json.load(f)
        
    # data structure: {:metadata {...} :history [init-state diff1 diff2 ...]}
    # history is a list. First element is full state?
    # Or first is init state (full), rest are diffs.
    
    history = data.get('history', [])
    if not history:
        print("No history found in replay.")
        sys.exit(1)
        
    current_state = history[0]
    
    # We could just jump to the end if we trust the diff application
    for diff in history[1:]:
        current_state = patch(current_state, diff)
        
    # Extract log from final state
    log_lines = extract_log(current_state)
    
    # Use log_parser to generate NAN
    events = parse_log_lines(log_lines)
    nan = generate_dsl(events)
    print(nan)

if __name__ == "__main__":
    main()
