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

_CREDIT_GAIN_RE = re.compile(r"gains? (\d+) \[Credit")
_CREDIT_PAY_RE = re.compile(r"pays? (\d+) \[Credit")
_CREDIT_LOSE_RE = re.compile(r"loses? (\d+) \[Credit")
_CREDIT_CHECKPOINT_RE = re.compile(
    r"(?:started|is ending) (?:his|her|their) turn \d+ with (\d+) \[Credit"
)


def _strip_quoted(action_text):
    """Drop quoted card text: "resolves N unbroken subroutines on X
    ("[subroutine] ... pay 4 [Credits]" ...)" quotes costs verbatim without
    anyone paying them."""
    return re.sub(r'"[^"]*"', "", action_text)


def extract_credit_delta(action_text):
    """Extract the acting player's credit change from a log action text.

    Returns ("set", n) for authoritative turn-start/turn-end checkpoints
    ("started their turn 3 with 10 [Credit]"), ("delta", n) for a net
    gain/pay/lose amount, or None when the line doesn't touch credits.
    Amounts hosted on cards (e.g. "place 3 [Credits]") are not the player's
    pool and don't match any of these patterns.
    """
    action_text = _strip_quoted(action_text)
    m = _CREDIT_CHECKPOINT_RE.search(action_text)
    if m:
        return ("set", int(m.group(1)))

    net = 0
    found = False
    for m in _CREDIT_GAIN_RE.finditer(action_text):
        net += int(m.group(1))
        found = True
    for m in _CREDIT_PAY_RE.finditer(action_text):
        net -= int(m.group(1))
        found = True
    for m in _CREDIT_LOSE_RE.finditer(action_text):
        net -= int(m.group(1))
        found = True
    return ("delta", net) if found else None


_CREDIT_BENEFICIARY_RE = re.compile(
    r"the (Runner|Corp) (?:to )?(?:gains?|loses?|pays?) \d+ \[Credit"
)


def credit_beneficiary(action_text):
    """Side named as the credit recipient/victim, when it isn't the actor.

    Covers third-party phrasings like "uses Wildcat Strike to force the
    Runner to gain 6 [Credits]" where the log actor (ai-corp) is not whose
    pool changes. Returns "Runner", "Corp", or None.
    """
    m = _CREDIT_BENEFICIARY_RE.search(_strip_quoted(action_text))
    return m.group(1) if m else None


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
    match = re.search(r"to install (?:ice|facedown .+) protecting (.+)\.$", action_text)
    if match:
        # Clean server name "Server 1 (new remote)" -> "S1"
        server = match.group(1).split(" (")[0].replace("Server ", "S")
        return f"ice {server}"
        
    # Install Card (Asset/Upgrade/Agenda)
    match = re.search(r"to install (?:a card|facedown .+) in the root of (.+)\.$", action_text)
    if match:
        server = match.group(1).split(" (")[0].replace("Server ", "S")
        return f"install {server}"

    # Install Program/Hardware/Resource
    match = re.search(r"to install (.+?)(?: from the Stack)?\.$", action_text)
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
    # Run-related actions are Runner. Use word boundaries (\b) rather than a
    # leading space: replay action text begins with the verb (the actor token is
    # stripped to its own line), so " scores " / " steals " never matched.
    if re.search(r"\bmakes? a run on ", action_text):
        return "Runner"
    if re.search(r"\bbreaches\b", action_text):
        return "Runner"
    if re.search(r"\bencounters\b", action_text):
        return "Runner"
    if re.search(r"\bjacks out\b", action_text):
        return "Runner"
    # Score is Corp, steal is Runner
    if re.search(r"\bscores\b", action_text) and "agenda point" in action_text:
        return "Corp"
    if re.search(r"\bsteals\b", action_text):
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

    # Running credit pools, resynced on turn-start/turn-end checkpoint lines.
    credits = {"Corp": 5, "Runner": 5}
    turn_start_credits = (5, 5)
    # Side of the last appended action that carries a credit annotation, so a
    # follow-up effect line ("uses Sure Gamble to gain 9 [Credits]") that
    # simplifies to None folds its delta into its cause instead of dangling.
    last_annotated_side = None

    # Track username -> side mapping as we discover it
    username_to_side = {}

    def flush_turn():
        if turn_actions:
            # Join actions with semicolon
            # Format: Player T# [Corp-Runner] {Ccredits Rcredits}: ...
            header = (
                f"{active_player} T{current_turn}"
                f" [{turn_start_score[0]}-{turn_start_score[1]}]"
                f" {{C{turn_start_credits[0]} R{turn_start_credits[1]}}}"
            )
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

        simplified = simplify_action(action)
        credit_change = extract_credit_delta(action)

        if simplified and simplified.startswith("Turn"):
            flush_turn()
            last_annotated_side = None
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
                username_to_side.setdefault(actor, active_player)

            # The turn line itself is the authoritative credit checkpoint
            # ("started their turn N with X [Credit]").
            if credit_change and credit_change[0] == "set":
                credits[active_player] = credit_change[1]

            # Capture score and credits at start of this turn
            turn_start_score = (corp_score, runner_score)
            turn_start_credits = (credits["Corp"], credits["Runner"])
            continue

        # Attribute the credit change: an explicitly named beneficiary ("force
        # the Runner to gain 6 [Credits]") wins over the log actor. Otherwise
        # fall back to the turn owner, which is right for everything except
        # opponent paid effects — and those (rez, trash-on-access) are
        # side-detected.
        side = (
            credit_beneficiary(action)
            or username_to_side.get(actor)
            or detected_side
            or active_player
        )
        annotation = None
        if credit_change and side:
            kind, amount = credit_change
            if kind == "set":
                if credits[side] != amount:
                    print(
                        f"credit drift: {side} computed {credits[side]}, "
                        f"log says {amount} ({action})",
                        file=sys.stderr,
                    )
                    credits[side] = amount
            elif amount != 0:
                credits[side] += amount
                annotation = f"→{side[0]}{credits[side]}"

        if not simplified:
            if annotation:
                if last_annotated_side == side and turn_actions:
                    # Effect of the previous action: refresh its total (the
                    # cause may carry no annotation yet if it was zero-cost).
                    if re.search(r"→[CR]\d+$", turn_actions[-1]):
                        turn_actions[-1] = re.sub(
                            r"→[CR]\d+$", annotation, turn_actions[-1]
                        )
                    else:
                        turn_actions[-1] += f" {annotation}"
                else:
                    # No rendered cause to attach to (drip income, tag
                    # removal, ...): emit the new total on its own.
                    turn_actions.append(annotation)
                    last_annotated_side = side
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

        if annotation:
            turn_actions.append(f"{simplified} {annotation}")
            last_annotated_side = side
        else:
            turn_actions.append(simplified)
            # A zero-delta credit line ("pays 0 [Credits] to play X") is
            # still a foldable cause for a follow-up payoff line.
            is_zero_credit_action = (
                credit_change is not None and credit_change[0] == "delta"
            )
            last_annotated_side = side if is_zero_credit_action else None

    flush_turn()
    return "\n".join(dsl_lines)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python log_parser.py <log_file>")
        sys.exit(1)
        
    events = parse_log(sys.argv[1])
    dsl = generate_dsl(events)
    print(dsl)
