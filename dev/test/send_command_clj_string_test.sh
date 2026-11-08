#!/usr/bin/env bash
# send_command_clj_string_test.sh — a card title with a quote in it must survive
# the trip into a Clojure string literal.
#
# Why this exists: send_command builds the expression it sends to the REPL by
# interpolating shell variables into Clojure string literals:
#
#     execute "(ai-actions/play-card! \"$CARD_NAME\")"
#
# 28 card titles carry embedded quotes — Cerberus "Lady" H1, Kate "Mac"
# McCaffrey, Ele "Smoke" Scovak, Corporate "Grant". Interpolated raw, the title
# CLOSES the string it was meant to fill:
#
#     (ai-actions/play-card! "Cerberus "Lady" H1")
#
# which is not one string argument but a string, two symbols and a string. The
# REPL is handed broken code, so the command never reaches its handler and the
# seat's failure has nothing to do with the game.
#
# The escaping idiom already existed inline, written twice (draw-to-card and
# find-card, presumably by whoever first hit it) and copied to none of the other
# twenty sites. This guards the extracted helper AND the sites, because a helper
# nothing is required to call is the same defect one refactor later.
#
# Like send_command_wrap_test.sh, the helper is extracted from the LIVE script
# rather than reimplemented, so this cannot pass against a stale copy.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

FN_BODY=$(sed -n '/^clj_str() {$/,/^}$/p' "$SEND_CMD")
if [[ -z "$FN_BODY" ]]; then
    echo "FAIL: could not extract clj_str() from $SEND_CMD" >&2
    exit 1
fi
eval "$FN_BODY"

PASS=0
FAIL=0
check() {
    local label="$1" expected="$2" actual="$3"
    if [[ "$actual" == "$expected" ]]; then
        echo "ok   [$label]"
        PASS=$((PASS + 1))
    else
        echo "FAIL [$label]"
        echo "       expected: $expected"
        echo "       actual:   $actual"
        FAIL=$((FAIL + 1))
    fi
}

echo "--- a quoted title becomes ONE Clojure string ---"
check "embedded-quotes" 'Cerberus \"Lady\" H1' "$(clj_str 'Cerberus "Lady" H1')"
check "leading-quote"   '\"Grant\"'             "$(clj_str '"Grant"')"
check "colon-title"     'Kate \"Mac\" McCaffrey: Catalyst' \
                        "$(clj_str 'Kate "Mac" McCaffrey: Catalyst')"

echo "--- backslashes are escaped FIRST, not re-escaped ---"
# A lone backslash must become two, and must not turn the quote escaping into
# a backslash that escapes the escape.
check "lone-backslash"  'a\\b'      "$(clj_str 'a\b')"
check "backslash-quote" 'a\\\"b'    "$(clj_str 'a\"b')"

echo "--- ordinary titles are untouched ---"
check "plain-title"     'Hedge Fund'        "$(clj_str 'Hedge Fund')"
check "ampersand"       'R&D'               "$(clj_str 'R&D')"
check "brackets"        'Gain 3 [Credits]'  "$(clj_str 'Gain 3 [Credits]')"
check "empty"           ''                  "$(clj_str '')"

echo "--- the escaped form actually reads as one Clojure string ---"
# Round-trip through a reader rather than trusting the shape by eye.
#
# Build the SAME expression execute() builds -- the escaped title interpolated
# straight into Clojure source -- not a nested string literal. Nesting it inside
# another Clojure string needs a SECOND level of escaping, and a round-trip
# written that way fails on correct output, which is worse than no test: it
# reports the fix broken. (Caught by running it: it read as three forms.)
if command -v clojure >/dev/null 2>&1; then
    EXPR="(print (identity \"$(clj_str 'Cerberus "Lady" H1')\"))"
    READ=$(printf '%s' "$EXPR" | clojure -M - 2>/dev/null)
    check "reader-round-trip" 'Cerberus "Lady" H1' "$READ"
else
    echo "skip [reader-round-trip] (clojure CLI not on PATH)"
fi

echo "--- no site interpolates a bare variable into a Clojure string ---"
# The structural half. A helper nothing is obliged to call decays: this fails on
# a NEW raw site rather than waiting for someone to play a Cerberus.
#
# WHAT THIS CATCHES, stated as a bound rather than implied to be complete: a bare
# variable that IS an entire Clojure string literal, `\"$VAR\"`, anywhere in the
# file -- not only on an `execute` line, and including `$1`, `$@` and `$*`.
#
# That bound is deliberate, and it is the third shape this check has had. The
# history is worth keeping, because it is the argument against a fourth:
#
#   r0  `grep 'execute .*\"$[A-Za-z_]+\"'`. Green over SIXTEEN raw sites. Two
#       blind spots: a site that accumulates Clojure into a VARIABLE has no
#       `execute` on its line (seven sites, including raw CARD TITLES into
#       discard-by-names! and multi-choose!), and `[A-Za-z_]+` cannot match `$1`
#       (the five flag arms).
#   r1  dropped the `execute` anchor and allowed digits. Found all sixteen.
#       A seat then showed it still misses an interpolation INSIDE a longer
#       literal, `\"before $X after\"` (r1/I1).
#   r2  tried to fix that by tracking delimiter parity in awk. A panel returned
#       ELEVEN findings against that mechanism, five MAJOR, and one of them is
#       structural: the walk resets per PHYSICAL LINE, while this file's
#       `execute` blocks span many lines (r2/F8). A line-oriented scan cannot
#       decide "inside a Clojure literal" for multi-line generated Clojure. The
#       other ten were about the `_CLJ` and marker conventions that existed only
#       to feed exemptions to that walk.
#
# So the parity mechanism and both conventions are DELETED rather than patched a
# third time, and the sites that needed exempting are escaped inline instead.
# What is left is the bound above: smaller than the question, honest about it.
#
# NOT CAUGHT, and none of these is hypothetical -- each was executed by a seat:
#   - an interpolation inside a longer literal: `\"before $X after\"`
#   - a literal spanning physical lines, with the interpolation on a later one
#   - `$(cmd)` other than the clj_str wrap, e.g. `\"$(date)\"`
#   - a second interpolation on a line whose message call blanked it (below)
# Closing those needs a different mechanism: build the expression and hand it to
# a reader, rather than pattern-matching the generator. That is its own change
# with its own design round, not a rider here.
#
# Comment lines and user-message calls are blanked, whole. Blanking (not
# dropping) keeps `grep -n` reporting real file line numbers. The whole-line part
# IS a hole -- a raw interpolation sharing a line with a message call is
# invisible (r2/F6, r2/F9) -- and it stays open rather than being papered over
# with a marker that has the identical hole.
scan_raw() {   # scan_raw FILE -> one `LINENO:TEXT` per unsafe interpolation
    sed -E 's/\$\(clj_str "\$\{?[A-Za-z_0-9]+\}?"\)//g' "$1" \
      | sed -E 's/^[[:space:]]*#.*$//' \
      | sed -E '/(error|warn|info|echo)[[:space:]]+"/s/.*//' \
      | grep -nE '\\"\$\{?[A-Za-z_0-9@*]+\}?\\"' || true
}
scan_line() { printf '%s\n' "$1" | scan_raw /dev/stdin; }

# The r0 pattern, frozen, so `widening-is-load-bearing` can show what it missed.
scan_line_old() {
    printf '%s\n' "$1" | sed -E 's/\$\(clj_str "\$[A-Za-z_]+"\)//g' \
      | grep -nE 'execute .*\\"\$[A-Za-z_]+\\"' || true
}

RAW=$(scan_raw "$SEND_CMD")
if [[ -z "$RAW" ]]; then
    echo "ok   [no-raw-interpolation-sites]"
    PASS=$((PASS + 1))
else
    echo "FAIL [no-raw-interpolation-sites] — these embed a bare variable:"
    echo "$RAW" | sed 's/^/       /'
    FAIL=$((FAIL + 1))
fi

echo "--- that guard can actually fail (mutation tests, all through scan_raw) ---"
flags() {   # flags LABEL LINE  -- the scanner must report this line
    if [[ -n "$(scan_line "$2")" ]]; then
        echo "ok   [$1]"; PASS=$((PASS + 1))
    else
        echo "FAIL [$1] — scan_raw missed: $2"; FAIL=$((FAIL + 1))
    fi
}
clean() {   # clean LABEL LINE  -- the scanner must NOT report this line
    if [[ -z "$(scan_line "$2")" ]]; then
        echo "ok   [$1]"; PASS=$((PASS + 1))
    else
        echo "FAIL [$1] — scan_raw falsely flagged: $2"; FAIL=$((FAIL + 1))
    fi
}

flags mutation-raw-site-is-detected 'execute "(ai-actions/play-card! \"$CARD_NAME\")"'
clean mutation-wrapped-site-is-clean 'execute "(ai-actions/play-card! \"$(clj_str "$CARD_NAME")\")"'
# TWO interpolations on one line, only one escaped. install-card! is this shape.
flags mutation-partial-escaping-is-detected \
      'execute "(ai-actions/install-card! \"$CARD_NAME\" \"$(clj_str "$SERVER")\")"'

# --- the two blind spots that hid seven live raw sites (r0) ---
# 1. ACCUMULATION: builds Clojure into a variable, so no `execute` on the line.
flags mutation-accumulation-site-is-detected 'FLAGS_ARGS="$FLAGS_ARGS \"$1\""'
flags mutation-accumulation-append-is-detected 'NAMES_VEC+="\"$name\" "'
# 2. POSITIONAL PARAMETER: `[A-Za-z_]+` cannot match `$1`.
flags mutation-positional-param-is-detected 'execute "(ai-actions/run! \"$1\")"'
# ...and the argument-list forms a seat found the class had also left out (r2/F10).
flags mutation-dollar-star-is-detected 'execute "(ai-actions/chat! \"$*\")"'
flags mutation-dollar-at-is-detected 'execute "(ai-actions/chat! \"$@\")"'

# Dropping the `execute` anchor and allowing digits is what buys those five. If
# the r0 pattern caught them, that change was decoration.
if [[ -z "$(scan_line_old 'FLAGS_ARGS="$FLAGS_ARGS \"$1\""')" \
   && -z "$(scan_line_old 'NAMES_VEC+="\"$name\" "')" \
   && -z "$(scan_line_old 'execute "(ai-actions/run! \"$1\")"')" ]]; then
    echo "ok   [widening-is-load-bearing]"
    PASS=$((PASS + 1))
else
    echo "FAIL [widening-is-load-bearing] — the r0 pattern already caught these,"
    echo "       so dropping its anchor buys nothing"
    FAIL=$((FAIL + 1))
fi

clean comment-quoting-the-hazard-is-clean '# like this: FLAGS_ARGS="$FLAGS_ARGS \"$1\""'
clean user-message-is-not-clojure-source '    *) error "needs your side: find-card \"$CARD_NAME\"" ;;'

# --- the bounds, asserted so they cannot be mistaken for coverage ---
# Each of these SHOULD ideally be flagged and is not. Pinning them means the
# limit is a decision on the record, and the day someone closes it these go red
# and get deleted deliberately rather than silently widening the claim.
echo "--- known limits, pinned so they stay deliberate ---"
clean limit-interpolation-inside-a-longer-literal \
      'execute "(println \"before $CARD_NAME after\")"'
clean limit-command-substitution-other-than-clj_str \
      'execute "(println \"$(date)\")"'
clean limit-raw-site-sharing-a-line-with-a-message \
      'execute "(f \"$CARD_NAME\")"; info "done"'
echo
echo "Passed: $PASS   Failed: $FAIL"
if [[ $FAIL -gt 0 ]]; then
    echo "❌ send_command Clojure-string escaping: FAILURES"
    exit 1
fi
echo "✅ send_command Clojure-string escaping: all assertions passed"
