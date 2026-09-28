#!/usr/bin/env python3
"""Inject an arm whose Clojure string opens after a run of FIVE backslashes.

In a shell double-quoted string, five backslashes then a quote become an escaped
backslash followed by a DELIMITER (run % 4 == 1). Census A's first scanner said
"exactly ONE backslash", read this as not-a-delimiter, and left the interpolation
after it in a gap it believed was outside a string. A code-review seat ran the
case; this pins the arithmetic.
"""
import sys

ARM = (
    "    my-new-arm)\n"
    '        execute "(f \\\\\\\\\\"$CHOICE\\\\\\\\\\")"\n'
    "        ;;\n"
)

def main() -> int:
    src, dst = sys.argv[1], sys.argv[2]
    s = open(src).read()
    anchor = "    keep-hand)"
    if anchor not in s:
        print("anchor arm not found in send_command", file=sys.stderr)
        return 1
    open(dst, "w").write(s.replace(anchor, ARM + "\n" + anchor, 1))
    return 0

if __name__ == "__main__":
    sys.exit(main())
