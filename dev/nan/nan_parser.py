import re
import sys
import json

class NANParser:
    def __init__(self):
        pass

    def parse_file(self, file_path):
        with open(file_path, 'r') as f:
            lines = [line.strip() for line in f.readlines() if line.strip()]
        
        game_record = []
        for line in lines:
            turn_data = self.parse_line(line)
            if turn_data:
                game_record.append(turn_data)
        return game_record

    def parse_line(self, line):
        # Format: "Player T# [CorpScore-RunnerScore] {Cn Rn}: Action1; ..."
        # Score and credit checkpoints are optional
        match = re.match(
            r"(Corp|Runner) T(\d+)(?: \[(\d+)-(\d+)\])?(?: \{C(\d+) R(\d+)\})?: (.+)",
            line,
        )
        if not match:
            print(f"Warning: Could not parse line: {line}")
            return None

        player = match.group(1)
        turn_number = int(match.group(2))
        corp_score = int(match.group(3)) if match.group(3) else None
        runner_score = int(match.group(4)) if match.group(4) else None
        corp_credits = int(match.group(5)) if match.group(5) else None
        runner_credits = int(match.group(6)) if match.group(6) else None
        actions_str = match.group(7)

        actions = [self.parse_action(a.strip()) for a in actions_str.split(";")]

        result = {
            "player": player,
            "turn": turn_number,
            "actions": actions
        }

        if corp_score is not None:
            result["score"] = {"corp": corp_score, "runner": runner_score}

        if corp_credits is not None:
            result["credits"] = {"corp": corp_credits, "runner": runner_credits}

        return result

    def parse_action(self, action_str):
        # Peel a trailing credit annotation: "rez Palisade@0 S1 →C4" or a
        # standalone total "→R7" (credit change with no rendered action).
        credits_after = None
        match = re.match(r"(.*?)\s*→([CR])(\d+)$", action_str)
        if match:
            credits_after = (match.group(2), int(match.group(3)))
            stripped = match.group(1)
        else:
            stripped = action_str

        # Simple verb-object parsing
        parts = stripped.split(" ", 1) if stripped else [""]
        verb = parts[0] or None
        target = parts[1] if len(parts) > 1 else None

        result = {
            "raw": action_str,
            "verb": verb,
            "target": target
        }
        if credits_after is not None:
            result["credits_after"] = credits_after
        return result

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python nan_parser.py <nan_file>")
        sys.exit(1)
        
    parser = NANParser()
    record = parser.parse_file(sys.argv[1])
    print(json.dumps(record, indent=2))
