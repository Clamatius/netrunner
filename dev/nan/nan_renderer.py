import sys
import re
from collections import defaultdict

class NANRenderer:
    def __init__(self):
        self.servers = defaultdict(list) # server_name -> list of ice names (0 is innermost)
        self.roots = defaultdict(list)   # server_name -> list of cards in root
        self.runner_rig = []             # list of runner cards
        self.corp_score = 0
        self.runner_score = 0
        self.current_turn = 0
        
    def parse_nan(self, nan_content):
        lines = nan_content.strip().split('\n')
        for line in lines:
            if not line.strip(): continue
            self.process_turn(line)
            
    def process_turn(self, line):
        # Format: "Player T# [CorpScore-RunnerScore]: Actions" or "Player T#: Actions"
        match = re.match(r"(Corp|Runner) T(\d+)(?: \[(\d+)-(\d+)\])?: (.+)", line)
        if not match: return
        
        player = match.group(1)
        turn = int(match.group(2))
        
        # If scores are present in header, sync them
        if match.group(3) and match.group(4):
            self.corp_score = int(match.group(3))
            self.runner_score = int(match.group(4))
            
        actions_str = match.group(5)
        self.current_turn = turn
        actions = [a.strip() for a in actions_str.split(';')]
        
        for action in actions:
            self.apply_action(player, action)

    def apply_action(self, player, action):
        # Basic state tracking
        
        # Install ICE
        # "ice S1" -> adds unrezzed ice to S1
        if action.startswith("ice "):
            server = action.split(" ")[1]
            # ICE is installed at the outermost position.
            # In our list, index 0 is innermost. So we append.
            self.servers[server].append({"name": "?", "rezzed": False})
            
        # Install Card (Corp)
        # "install S1" -> adds card to root
        elif action.startswith("install ") and player == "Corp":
            server = action.split(" ")[1]
            self.roots[server].append({"name": "?", "rezzed": False, "advancement": 0})
            
        # Install Card (Runner)
        # "install CardName"
        elif action.startswith("install ") and player == "Runner":
            card = action.split(" ", 1)[1]
            self.runner_rig.append(card)
            
        # Rez
        # "rez Name@Pos Server" or "rez Name Server"
        elif action.startswith("rez "):
            # Parse: rez <Name>[@<Pos>] [<Server>...]
            # Regex is safer
            # Try ICE format first: Name@Pos Server
            # Note: action is full string "rez Name..."
            match = re.match(r"rez (.+)@(\d+) (.+)", action)
            if match:
                name = match.group(1)
                pos = int(match.group(2))
                server = match.group(3)
                
                # Ensure server list is long enough
                while len(self.servers[server]) <= pos:
                    self.servers[server].append({"name": "?", "rezzed": False})
                
                self.servers[server][pos] = {"name": name, "rezzed": True}
                return

            # Try Asset/Upgrade format: Name Server
            # But wait, "rez Manegarm Skunkworks S1" vs "rez Palisade@1 S1"
            # If no @, assume asset/upgrade or implicit?
            # NAN spec says "rez <card_name> <server>" for assets
            parts = action.split(" ", 1)[1].rsplit(" ", 1)
            if len(parts) == 2:
                name = parts[0]
                server = parts[1]
                # Look in root
                found = False
                for card in self.roots[server]:
                    if card["name"] == "?" or card["name"] == name:
                        card["name"] = name
                        card["rezzed"] = True
                        found = True
                        break
                if not found:
                    # Add it if not found (maybe installed face down and we missed the install or it was implicit setup?)
                    # Or maybe it was just installed?
                    # "install S1" adds "?"
                    # Let's assume it was the last installed '?' if available, or append.
                    self.roots[server].append({"name": name, "rezzed": True, "advancement": 0})
            else:
                # Old format or implicit server (shouldn't happen with new parser mostly)
                pass
                            
        # Score
        elif action.startswith("score "):
            name = action.split(" ", 1)[1]
            self.corp_score += 1 # We don't know points, just count
            # Remove from root if it was there?
            # Finding which server is hard without tracking "advance S1".
            pass
            
        # Advance
        elif action.startswith("advance "):
            server = action.split(" ")[1]
            if self.roots[server]:
                self.roots[server][-1]["advancement"] += 1

    def render(self):
        print(f"--- Game State at Turn {self.current_turn} ---")
        print(f"Score: Corp {self.corp_score} | Runner {self.runner_score}")
        print("\n[ Corp Board ]")
        
        # Sort servers: HQ, R&D, Archives, then S1, S2...
        server_order = ["HQ", "R&D", "Archives"]
        remotes = sorted([s for s in self.servers.keys() if s not in server_order], key=lambda x: int(x[1:]) if x[1:].isdigit() else 99)
        remotes.extend(sorted([s for s in self.roots.keys() if s not in server_order and s not in remotes], key=lambda x: int(x[1:]) if x[1:].isdigit() else 99))
        
        all_servers = []
        seen = set()
        for s in server_order + remotes:
            if s not in seen:
                all_servers.append(s)
                seen.add(s)

        for server in all_servers:
            ice_str = ""
            if server in self.servers:
                ices = self.servers[server]
                # Render from outermost (highest index) to innermost (0)
                for i in range(len(ices)-1, -1, -1):
                    ice = ices[i]
                    if ice["rezzed"] and ice["name"] != "?":
                        ice_str += f"[{ice['name']}] "
                    else:
                        status = "REZ" if ice["rezzed"] else "UNK"
                        ice_str += f"[{status} ice] "
            
            root_str = ""
            if server in self.roots:
                cards = self.roots[server]
                for card in cards:
                    adv = f"({card['advancement']} adv)" if card['advancement'] > 0 else ""
                    name = card['name'] if card['name'] != "?" else "Unknown"
                    root_str += f"[{name}{adv}]"
            
            print(f"{server:<10} : {ice_str} {root_str}")

        print("\n[ Runner Rig ]")
        print(", ".join(self.runner_rig))

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python nan_renderer.py <nan_file>")
        sys.exit(1)
        
    with open(sys.argv[1], 'r') as f:
        content = f.read()
        
    renderer = NANRenderer()
    renderer.parse_nan(content)
    renderer.render()
