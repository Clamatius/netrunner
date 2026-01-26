#!/usr/bin/env python3
"""Extract thinking and text blocks from Claude Code trajectory files.

Usage: ./traj-extract.py trajectory.txt [output.txt]

If output is not specified, prints to stdout.
"""

import json
import sys

def extract_thoughts(input_file, output_file=None):
    out = open(output_file, 'w') if output_file else sys.stdout

    with open(input_file, 'r') as f:
        for line in f:
            try:
                obj = json.loads(line.strip())
                if 'message' in obj and 'content' in obj['message']:
                    content = obj['message']['content']
                    if isinstance(content, list):
                        for item in content:
                            if item.get('type') == 'thinking':
                                out.write(f"\n💭 THINKING:\n{item.get('thinking', '')}\n")
                            elif item.get('type') == 'text':
                                out.write(f"\n📝 OUTPUT:\n{item.get('text', '')}\n")
            except json.JSONDecodeError:
                pass

    if output_file:
        out.close()

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    input_file = sys.argv[1]
    output_file = sys.argv[2] if len(sys.argv) > 2 else None
    extract_thoughts(input_file, output_file)
