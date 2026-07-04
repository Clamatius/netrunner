"""Adapter: flat send_command log dump -> NAN via log_parser.

For games where the jinteki replay doesn't persist (a human web-UI seat
can't be made to leave by save-replay.sh, so the replay never flushes to
mongo), the AI client's cached log is the only transcript:

    ./dev/send_command <side> log 300 > gameN_log.txt
    python3 flat_log_to_nan.py gameN_log.txt > docs/gameN.nan

This rebuilds the actor/[ts]/text triples that log_parser expects from the
flat client log, exactly the way json_replay_to_nan.extract_log does it.
"""
import re
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from log_parser import parse_log_lines, generate_dsl


def flat_to_triples(path):
    lines = []
    with open(path) as f:
        for raw in f:
            text = raw.strip()
            if not text or text == "[hr]":
                continue
            m = re.match(r"([\w.-]+) ", text)
            actor = m.group(1) if m else "System"
            lines.append(actor)
            lines.append("[00:00:00]")
            lines.append(text)
    return lines


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python flat_log_to_nan.py <flat_log.txt>", file=sys.stderr)
        sys.exit(1)
    triples = flat_to_triples(sys.argv[1])
    events = parse_log_lines(triples)
    print(generate_dsl(events))
