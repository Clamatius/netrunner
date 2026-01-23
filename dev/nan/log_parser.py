import re
import sys
import json

def parse_log_lines(lines):
    # Skip header
    if lines and lines[0].startswith("Game Log"):
        lines = lines[1:]

    events = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line:
            i += 1
            continue
            
        # Chat detection (Double Name)
        if i + 1 < len(lines) and lines[i+1] == line:
            # Chat message
            actor = line
            timestamp = lines[i+2]
            message = lines[i+3]
            events.append({
                'type': 'chat',
                'actor': actor,
                'message': message
            })
            i += 4
            continue
        
        # Standard Action
        # Actor
        # Timestamp
        # Action
        if i + 2 < len(lines) and lines[i+1].startswith("["):
            actor = line
            timestamp = lines[i+1]
            action = lines[i+2]
            
            events.append({
                'type': 'game_event',
                'actor': actor,
                'timestamp': timestamp,
                'action': action
            })
            i += 3
            continue
            
        # Fallback
        i += 1
    
    return events

def parse_log(log_path):
    with open(log_path, 'r') as f:
        lines = [line.strip() for line in f.readlines()]
    return parse_log_lines(lines)

def simplify_action(action_text):
    # Regex patterns
    
    # Turn Start
    match = re.search(r"started (his|her|their) turn (\d+)", action_text)
    if match:
        return f"Turn {match.group(2)}"
        
    # Draws
    if "makes his mandatory start of turn draw" in action_text:
        return None # Implicit
    if "to use Corp Basic Action Card to draw" in action_text:
        return "draw"
    if "to use Runner Basic Action Card to draw" in action_text:
        return "draw"
        
    # Gain Credits
    if "to use Corp Basic Action Card to gain" in action_text:
        return "credit"
    if "to use Runner Basic Action Card to gain" in action_text:
        return "credit"
        
    # Asset/Ability Usage
    match = re.search(r"to use (.+) to (gain|place|draw)", action_text)
    if match:
        card = match.group(1)
        if "Basic Action Card" not in card:
            return f"use {card}"

    # Play Operation/Event
    match = re.search(r"to play (.+)\.$", action_text)
    if match:
        return f"{match.group(1)}"
        
    # Install ICE
    match = re.search(r"to install ice protecting (.+)\.$", action_text)
    if match:
        # Clean server name "Server 1 (new remote)" -> "S1"
        server = match.group(1).split(" (")[0].replace("Server ", "S")
        return f"ice {server}"
        
    # Install Card (Asset/Upgrade/Agenda)
    match = re.search(r"to install a card in the root of (.+)\.$", action_text)
    if match:
        server = match.group(1).split(" (")[0].replace("Server ", "S")
        return f"install {server}"

    # Install Program/Hardware/Resource
    match = re.search(r"to install (.+)\.$", action_text)
    if match:
        card = match.group(1)
        # Filter out "ice protecting" which is caught above, but just in case
        if "ice protecting" not in action_text:
             return f"install {card}"

    # Advance
    match = re.search(r"to advance a card in (.+)\.$", action_text)
    if match:
        server = match.group(1).replace("Server ", "S")
        return f"advance {server}"
        
    # Score
    match = re.search(r"scores (.+) and gains", action_text)
    if match:
        return f"score {match.group(1)}"
        
    # Runs
    match = re.search(r"to make a run on (.+)\.$", action_text)
    if match:
        server = match.group(1).replace("Server ", "S")
        return f"run {server}"
        
    if "breaches" in action_text:
        server = action_text.split("breaches ")[1].replace(".", "").replace("Server ", "S")
        return f"breach {server}"
        
    match = re.search(r"accesses (.+) from (.+)\.$", action_text)
    if match:
        card = match.group(1)
        if "unseen card" in card:
            card = "?"
        return f"access {card}"
        
    match = re.search(r"(?:trashes|to trash) (.+?)(?: from .+)?\.$", action_text)
    if match:
        return f"trash {match.group(1)}"
        
    match = re.search(r"steals (.+) and gains", action_text)
    if match:
        return f"steal {match.group(1)}"
        
    # ICE Interaction
    match = re.search(r"rez (.+) protecting (.+) at position (\d+)", action_text)
    if match:
        ice = match.group(1)
        server = match.group(2).replace("Server ", "S")
        pos = match.group(3)
        return f"rez {ice}@{pos} {server}"
        
    # Asset/Upgrade Rez
    match = re.search(r"rez (.+) in (.+?)(?: at no cost)?(?:\.|$)", action_text)
    if match:
        card = match.group(1)
        server = match.group(2).replace("Server ", "S")
        return f"rez {card} {server}"

    if "encounters" in action_text:
        # Format: "encounters Name protecting Server at position N"
        match = re.search(r"encounters (.+) protecting (.+) at position (\d+)", action_text)
        if match:
            ice = match.group(1)
            pos = match.group(3)
            return f"encounter {ice}@{pos}"
            
        ice = action_text.split("encounters ")[1].replace(".", "")
        # Remove server context if too long, or keep simple
        # "Diviner protecting HQ at position 0" -> "Diviner"
        if " protecting " in ice:
            ice = ice.split(" protecting ")[0]
        return f"encounter {ice}"
        
    if "break all subroutines" in action_text:
        return "break-all"

    # Damage
    match = re.search(r"suffers? (\d+) (net|meat|core) damage", action_text)
    if match:
        amount = match.group(1)
        dmg_type = match.group(2)
        return f"damage {amount} {dmg_type}"

    # Flatline check
    if "is flatlined" in action_text:
        return "flatline"

    # Discard from hand (end of turn)
    match = re.search(r"discards (.+) from (HQ|the grip)", action_text)
    if match:
        card = match.group(1)
        return f"discard {card}"

    return None

def detect_side_from_action(action_text):
    """Detect if an action is from Corp or Runner based on content."""
    if "Corp Basic Action Card" in action_text:
        return "Corp"
    if "Runner Basic Action Card" in action_text:
        return "Runner"
    if "makes his mandatory start of turn draw" in action_text:
        return "Corp"  # Only Corp has mandatory draw
    if "makes her mandatory start of turn draw" in action_text:
        return "Corp"
    if "makes their mandatory start of turn draw" in action_text:
        return "Corp"
    # Run-related actions are Runner
    if " make a run on " in action_text or " makes a run on " in action_text:
        return "Runner"
    if " breaches " in action_text:
        return "Runner"
    if " encounters " in action_text:
        return "Runner"
    if " jacks out" in action_text:
        return "Runner"
    # Score is Corp, steal is Runner
    if " scores " in action_text and " agenda point" in action_text:
        return "Corp"
    if " steals " in action_text:
        return "Runner"
    # Rez is typically Corp
    if " rez " in action_text.lower() and "protecting" in action_text:
        return "Corp"
    return None

def generate_dsl(events):
    dsl_lines = []
    current_turn = 0
    active_player = ""
    turn_actions = []

    corp_score = 0
    runner_score = 0
    turn_start_score = (0, 0)

    # Track username -> side mapping as we discover it
    username_to_side = {}

    def flush_turn():
        if turn_actions:
            # Join actions with semicolon
            # Format: Player T# [Corp-Runner]: ...
            header = f"{active_player} T{current_turn} [{turn_start_score[0]}-{turn_start_score[1]}]"
            dsl_lines.append(f"{header}: {'; '.join(turn_actions)}")
            turn_actions.clear()

    for event in events:
        if event['type'] == 'chat':
            continue

        action = event['action']
        actor = event.get('actor', '')

        # Try to learn username -> side mapping from action content
        detected_side = detect_side_from_action(action)
        if detected_side and actor and actor not in username_to_side:
            username_to_side[actor] = detected_side
            # Also map the other player
            other_side = "Runner" if detected_side == "Corp" else "Corp"
            for other_actor in username_to_side:
                if username_to_side[other_actor] != detected_side:
                    break
            # We'll fill in the other side when we see them

        simplified = simplify_action(action)

        if not simplified:
            continue

        if simplified.startswith("Turn"):
            flush_turn()
            parts = simplified.split(" ")
            current_turn = parts[1]

            # Determine active player from mapping or detection
            if actor in username_to_side:
                active_player = username_to_side[actor]
            elif detected_side:
                active_player = detected_side
                username_to_side[actor] = detected_side
            else:
                # Fallback: Turn 1 first player is always Corp
                if current_turn == "1" and not username_to_side:
                    active_player = "Corp"
                    username_to_side[actor] = "Corp"
                else:
                    # Alternate from last known
                    active_player = "Runner" if active_player == "Corp" else "Corp"

            # Capture score at start of this turn
            turn_start_score = (corp_score, runner_score)
            continue

        # Track score updates
        if simplified.startswith("score ") or simplified.startswith("steal "):
            # Look for "gains X agenda point"
            p_match = re.search(r"gains (\d+) agenda point", action)
            if p_match:
                points = int(p_match.group(1))
                if simplified.startswith("score"):
                    corp_score += points
                else:
                    runner_score += points
            
        turn_actions.append(simplified)
        
    flush_turn()
    return "\n".join(dsl_lines)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python log_parser.py <log_file>")
        sys.exit(1)
        
    events = parse_log(sys.argv[1])
    dsl = generate_dsl(events)
    print(dsl)
