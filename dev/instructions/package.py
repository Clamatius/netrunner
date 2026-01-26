#!/usr/bin/env python3
"""
Package instruction files for Corp or Runner AI player.

Usage:
    ./package.py corp [--extras]    # Corp instructions
    ./package.py runner [--extras]  # Runner instructions
    ./package.py --help
"""

import argparse
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent

# File mappings for each side
CORP_FILES = [
    "player_instructions.md",      # Shared command interface
    "corp_play_structure.md",       # Turn heuristics
    "tutorial_deck_corp.md",        # Deck reference
]

RUNNER_FILES = [
    "player_instructions.md",      # Shared command interface
    "runner_play_structure.md",     # Turn heuristics
    "tutorial_deck_runner.md",      # Deck reference
]

CORP_EXTRAS = [
    "extras/corp-playbook.md",      # Strategic depth
    "extras/timing-reference.md",   # Official timing structures
]

RUNNER_EXTRAS = [
    "extras/runner-playbook.md",    # Strategic depth
    "extras/timing-reference.md",   # Official timing structures
]


def read_file(path: Path) -> str:
    """Read file content, stripping ANSI codes from txt files."""
    content = path.read_text()
    # Strip ANSI escape codes (used in player_instructions.md)
    # Handle both literal \033 and actual escape character
    import re
    content = re.sub(r'\\033\[[0-9;]*m', '', content)  # Literal \033
    content = re.sub(r'\x1b\[[0-9;]*m', '', content)   # Actual escape
    return content


def package_side(side: str, include_extras: bool = False) -> str:
    """Package all instruction files for the given side."""
    if side == "corp":
        files = CORP_FILES + (CORP_EXTRAS if include_extras else [])
    else:
        files = RUNNER_FILES + (RUNNER_EXTRAS if include_extras else [])

    sections = []
    for filename in files:
        filepath = SCRIPT_DIR / filename
        if not filepath.exists():
            print(f"Warning: {filename} not found, skipping", file=sys.stderr)
            continue

        content = read_file(filepath)
        # Use filename as section header
        header = f"# === {filename} ==="
        sections.append(f"{header}\n\n{content}")

    return "\n\n".join(sections)


def main():
    parser = argparse.ArgumentParser(description="Package AI player instructions")
    parser.add_argument("side", choices=["corp", "runner"], help="Which side to package")
    parser.add_argument("--extras", action="store_true", help="Include extras/playbook")
    parser.add_argument("-o", "--output", help="Output file (default: stdout)")
    parser.add_argument("--stats", action="store_true", help="Show file stats instead of content")

    args = parser.parse_args()

    if args.stats:
        # Show stats for each file
        if args.side == "corp":
            files = CORP_FILES + CORP_EXTRAS
        else:
            files = RUNNER_FILES + RUNNER_EXTRAS

        total_lines = 0
        total_chars = 0
        print(f"\n{args.side.upper()} instruction files:\n")
        print(f"{'File':<40} {'Lines':>8} {'Chars':>10}")
        print("-" * 60)

        for filename in files:
            filepath = SCRIPT_DIR / filename
            if filepath.exists():
                content = read_file(filepath)
                lines = len(content.splitlines())
                chars = len(content)
                total_lines += lines
                total_chars += chars
                marker = "*" if filename in (CORP_EXTRAS + RUNNER_EXTRAS) else " "
                print(f"{marker}{filename:<39} {lines:>8} {chars:>10}")
            else:
                print(f" {filename:<39} {'MISSING':>8}")

        print("-" * 60)
        print(f"{'Total':<40} {total_lines:>8} {total_chars:>10}")
        print(f"\n* = extras (included with --extras flag)")
        return

    output = package_side(args.side, args.extras)

    if args.output:
        Path(args.output).write_text(output)
        print(f"Written to {args.output}", file=sys.stderr)
    else:
        print(output)


if __name__ == "__main__":
    main()
