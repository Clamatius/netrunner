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

echo "--- no execute site interpolates a bare variable into a Clojure string ---"
# The structural half. A helper nothing is obliged to call decays: this fails on
# a NEW raw site rather than waiting for someone to play a Cerberus.
RAW=$(grep -nE 'execute .*\\"\$[A-Za-z_]+\\"' "$SEND_CMD" | grep -v 'clj_str' || true)
if [[ -z "$RAW" ]]; then
    echo "ok   [no-raw-interpolation-sites]"
    PASS=$((PASS + 1))
else
    echo "FAIL [no-raw-interpolation-sites] — these embed a bare variable:"
    echo "$RAW" | sed 's/^/       /'
    FAIL=$((FAIL + 1))
fi

echo "--- that guard can actually fail (mutation test) ---"
# Feed the same pattern a line we know is raw. If this reports clean, the grep
# above is decorative and the check above proves nothing.
MUTANT='execute "(ai-actions/play-card! \"$CARD_NAME\")"'
if echo "$MUTANT" | grep -qE 'execute .*\\"\$[A-Za-z_]+\\"'; then
    echo "ok   [mutation-raw-site-is-detected]"
    PASS=$((PASS + 1))
else
    echo "FAIL [mutation-raw-site-is-detected] — the pattern misses a known raw site"
    FAIL=$((FAIL + 1))
fi

# ...and that a WRAPPED site is not falsely flagged.
SAFE='execute "(ai-actions/play-card! \"$(clj_str "$CARD_NAME")\")"'
if echo "$SAFE" | grep -E 'execute .*\\"\$[A-Za-z_]+\\"' | grep -qv 'clj_str'; then
    echo "FAIL [mutation-wrapped-site-is-clean] — a wrapped site is flagged"
    FAIL=$((FAIL + 1))
else
    echo "ok   [mutation-wrapped-site-is-clean]"
    PASS=$((PASS + 1))
fi

echo
echo "Passed: $PASS   Failed: $FAIL"
if [[ $FAIL -gt 0 ]]; then
    echo "❌ send_command Clojure-string escaping: FAILURES"
    exit 1
fi
echo "✅ send_command Clojure-string escaping: all assertions passed"
