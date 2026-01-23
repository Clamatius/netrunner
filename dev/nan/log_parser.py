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
        
    return None 

def generate_dsl(events):
    dsl_lines = []
    current_turn = 0
    active_player = ""
    turn_actions = []
    
    corp_score = 0
    runner_score = 0
    turn_start_score = (0, 0)
    
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
        simplified = simplify_action(action)
        
        if not simplified:
            continue
            
        if simplified.startswith("Turn"):
            flush_turn()
            parts = simplified.split(" ")
            current_turn = parts[1]
            if event['actor'] == "Clamatius": 
                active_player = "Corp"
            else:
                active_player = "Runner"
            
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
