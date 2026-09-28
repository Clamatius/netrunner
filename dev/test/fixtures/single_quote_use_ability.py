#!/usr/bin/env python3
"""Rewrite use-ability's interpolation as a SINGLE-QUOTED shell concatenation.

This is the mutation a code-review seat used to defeat the first version of the
#255 enumeration. Neither source census can see the shape (census A pairs on \\"
and finds none; census B never opens, because the line has no `execute "`), and
the enumeration missed it too while it drove every command with the payload in
argument ONE -- `use-ability` gates its index first, so nothing was ever sent.

Kept as a file rather than inline in the test because the quoting is exactly what
is under test, and a heredoc inside a heredoc inside a shell string is how a
fixture silently stops matching.
"""
import sys

OLD = '        execute "(ai-actions/use-ability! \\"$(clj_str "$CARD_NAME")\\" $ABILITY_INDEX)"\n'
NEW = '        execute \'(ai-actions/use-ability! "\'"$CARD_NAME"\'" \'"$ABILITY_INDEX"\')\'\n'

def main() -> int:
    src, dst = sys.argv[1], sys.argv[2]
    text = open(src).read()
    if OLD not in text:
        print("use-ability fixture is stale - it no longer matches send_command",
              file=sys.stderr)
        return 1
    open(dst, "w").write(text.replace(OLD, NEW, 1))
    return 0

if __name__ == "__main__":
    sys.exit(main())
