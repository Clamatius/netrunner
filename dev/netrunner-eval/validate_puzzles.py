#!/usr/bin/env python3
"""
Validate Netrunner puzzle source files for common issues.
Usage: python validate_puzzles.py [problems_dir]
"""

import json
import re
import sys
from pathlib import Path

import yaml

# What we check for
QUESTION_SECTIONS = ['Question', 'Questions']  # Must have one of these
CONTEXT_SECTIONS = ['Context', 'Situation']  # Must have one of these
BOARD_SECTION_PREFIXES = ['Board', 'State', 'Hand']  # Must have one starting with these


def load_card_lookup(path: Path) -> set:
    """Load valid card names."""
    with open(path) as f:
        return set(json.load(f).keys())


def parse_sections(content: str) -> dict:
    """Parse markdown into sections."""
    sections = {}
    current_section = None
    current_content = []

    for line in content.split('\n'):
        if line.startswith('## '):
            if current_section:
                sections[current_section] = '\n'.join(current_content).strip()
            current_section = line[3:].strip()
            current_content = []
        elif line.startswith('# '):
            sections['_title'] = line[2:].strip()
        else:
            current_content.append(line)

    if current_section:
        sections[current_section] = '\n'.join(current_content).strip()

    return sections


def extract_yaml(content: str) -> tuple[dict | None, str | None]:
    """Extract and parse YAML block, return (data, error)."""
    match = re.search(r'```yaml\s*\n(.*?)```', content, re.DOTALL)
    if not match:
        return None, "No YAML block found"
    try:
        return yaml.safe_load(match.group(1)), None
    except yaml.YAMLError as e:
        return None, f"YAML parse error: {e}"


def validate_card_list(items: list, field_name: str, valid_cards: set) -> list:
    """Validate a list of cards, return issues."""
    issues = []
    if not isinstance(items, list):
        issues.append(f"{field_name}: Expected list, got {type(items).__name__}")
        return issues
    
    for i, item in enumerate(items):
        if isinstance(item, str):
            card_name = item
        elif isinstance(item, dict):
            card_name = item.get('card', '')
        else:
            issues.append(f"{field_name}[{i}]: Invalid item type {type(item).__name__}")
            continue
        
        if card_name and card_name not in valid_cards:
            # Special cases that are OK
            if card_name in ('Unknown', 'Agenda', 'Asset', 'Upgrade'):
                continue
            issues.append(f"{field_name}[{i}]: Unknown card '{card_name}'")
    
    return issues


def validate_puzzle(q_file: Path, valid_cards: set) -> list:
    """Validate a single puzzle file, return list of issues."""
    issues = []
    content = q_file.read_text()
    sections = parse_sections(content)
    
    # Check title has difficulty
    title = sections.get('_title', '')
    if not re.search(r'\[(Easy|Medium|Hard)\]', title):
        issues.append("Missing difficulty tag [Easy|Medium|Hard] in title")
    
    # Check required sections
    has_context = any(s in sections for s in CONTEXT_SECTIONS)
    if not has_context:
        issues.append(f"Missing context section (need one of: {CONTEXT_SECTIONS})")
    
    has_question = any(s in sections or any(s.startswith(q) for q in QUESTION_SECTIONS) 
                       for s in sections for q in QUESTION_SECTIONS)
    has_question = any(s in sections for s in QUESTION_SECTIONS) or \
                   any(s.startswith('Question') for s in sections)
    if not has_question:
        issues.append(f"Missing question section (need one of: {QUESTION_SECTIONS})")
    
    # Check for Board State, State, or Hand section (prefix match)
    has_board_or_hand = any(
        section.startswith(prefix) 
        for section in sections 
        for prefix in BOARD_SECTION_PREFIXES
    )
    if not has_board_or_hand:
        issues.append("Missing Board State / State / Hand section")
        return issues
    
    # Only validate YAML if there's a Board State section
    if 'Board State' not in sections:
        return issues  # Hand-based puzzles don't need YAML validation
    
    # Parse and validate YAML
    board_content = sections.get('Board State', '')
    board_data, yaml_error = extract_yaml(board_content)
    
    if yaml_error:
        issues.append(yaml_error)
        return issues
    
    if not board_data:
        issues.append("Empty YAML block")
        return issues
    
    # Validate corp section
    corp = board_data.get('corp', {})
    if not corp:
        issues.append("Missing 'corp' in YAML")
    else:
        # Check ICE in servers
        for server_name in ['HQ', 'R&D', 'Archives']:
            if server_name in corp:
                server = corp[server_name]
                if isinstance(server, dict) and 'ice' in server:
                    issues.extend(validate_card_list(
                        server['ice'], f"corp.{server_name}.ice", valid_cards))
        
        # Check remotes
        for key, val in corp.items():
            if key.startswith('Server') or key.startswith('Remote'):
                if isinstance(val, dict):
                    if 'ice' in val:
                        issues.extend(validate_card_list(
                            val['ice'], f"corp.{key}.ice", valid_cards))
                    if 'root' in val:
                        root = val['root']
                        if isinstance(root, list):
                            issues.extend(validate_card_list(
                                root, f"corp.{key}.root", valid_cards))
                        elif isinstance(root, dict) and 'card' in root:
                            if root['card'] not in valid_cards and root['card'] not in ('Unknown', 'Agenda'):
                                issues.append(f"corp.{key}.root: Unknown card '{root['card']}'")
    
    # Validate runner section
    runner = board_data.get('runner', {})
    if not runner:
        issues.append("Missing 'runner' in YAML")
    else:
        # Check grip
        grip = runner.get('grip')
        if grip is not None:
            if isinstance(grip, int):
                issues.append(f"runner.grip: Is a number ({grip}), should be a list of card names")
            elif isinstance(grip, list):
                issues.extend(validate_card_list(grip, "runner.grip", valid_cards))
        
        # Check rig
        rig = runner.get('rig')
        if rig is not None:
            if isinstance(rig, int):
                issues.append(f"runner.rig: Is a number ({rig}), should be a list of card names")
            elif isinstance(rig, list):
                issues.extend(validate_card_list(rig, "runner.rig", valid_cards))
    
    return issues


def main():
    # Determine paths
    if len(sys.argv) > 1:
        problems_dir = Path(sys.argv[1])
    else:
        problems_dir = Path(__file__).parent / "problems"
    
    card_lookup_file = problems_dir.parent / "card_lookup.json"
    
    if not problems_dir.exists():
        print(f"Error: Problems directory not found: {problems_dir}")
        sys.exit(1)
    
    if not card_lookup_file.exists():
        print(f"Warning: Card lookup not found: {card_lookup_file}")
        print("Card name validation disabled.")
        valid_cards = set()
    else:
        valid_cards = load_card_lookup(card_lookup_file)
        print(f"Loaded {len(valid_cards)} valid card names")
    
    # Find and validate all puzzle files
    q_files = sorted(problems_dir.glob('*-q.md'))
    print(f"Found {len(q_files)} puzzle files\n")
    
    total_issues = 0
    files_with_issues = 0
    
    for q_file in q_files:
        issues = validate_puzzle(q_file, valid_cards)
        
        if issues:
            files_with_issues += 1
            total_issues += len(issues)
            print(f"❌ {q_file.name}")
            for issue in issues:
                print(f"   • {issue}")
            print()
        else:
            print(f"✓ {q_file.name}")
    
    # Summary
    print(f"\n{'='*50}")
    print(f"Total: {len(q_files)} files, {files_with_issues} with issues, {total_issues} total issues")
    
    if total_issues > 0:
        sys.exit(1)


if __name__ == '__main__':
    main()
