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
#
# SCOPE, since #255 split this file's job three ways:
#   here   - clj_str's own behaviour, as a unit.
#   clj_string_sweep_test.sh  - the censuses: is every site escaped, and is every
#                               bare interpolation reviewed.
#   clj_string_route_test.sh  - what each arm actually emits, per arm, on two
#                               shells.
#   clj_string_roundtrip_test.clj - does the emitted expression READ as one form.

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

echo "--- a trailing newline is TRUNCATED, deliberately (#255) ---"
# `$(clj_str "$X")` is a command substitution, and command substitution strips
# trailing newlines. Two design rounds tried a `printf -v` primitive to preserve
# them and it produced four review findings, two MAJOR, so the mechanism was
# deleted and this became a known property instead of a bug. It is pinned here so
# the next reader learns it from a passing assertion rather than a surprise. No
# card title, server name, flag, deck id or replay filename has one.
check "trailing-newline-truncated" 'Hedge Fund' "$(clj_str 'Hedge Fund
')"
check "interior-newline-kept"      'a
b'                                  "$(clj_str 'a
b')"

echo "--- the reader round trip lives in the lein suite now ---"
# It was guarded by `command -v clojure`, and there is no clojure/clj CLI on this
# box (only lein), so it printed a skip on EVERY run of make verify and the
# property was never checked. `read-string` also reads only the FIRST form, so it
# could not have caught an extra executable one even when it ran. Both fixed in
# dev/test/clj_string_roundtrip_test.clj, which `make test` runs.
if [[ -r "$SCRIPT_DIR/clj_string_roundtrip_test.clj" ]]; then
    echo "ok   [reader-round-trip-relocated]"
    PASS=$((PASS + 1))
else
    echo "FAIL [reader-round-trip-relocated] — clj_string_roundtrip_test.clj is missing,"
    echo "       so nothing checks that the emitted expression READS as one form."
    FAIL=$((FAIL + 1))
fi

echo "--- the structural census lives in clj_string_sweep_test.sh now ---"
# What used to be here:
#
#     grep -nE 'execute .*\\"\$[A-Za-z_]+\\"'
#
# It was blind THREE ways and #255 lived in all three: `execute .*` needs
# `execute` on the LINE (so every accumulator and every continuation line of a
# multiline form was invisible - 19 of the 20 sites), `[A-Za-z_]+` cannot match
# `$1` (so all five flag arms were invisible anyway), and its strip regex does not
# strip `$(clj_str "$1")` (so it would have flagged the FIXED sites). A guard that
# is green over the defect it is named for is worse than no guard.
if [[ -x "$SCRIPT_DIR/clj_string_sweep_test.sh" ]]; then
    echo "ok   [structural-census-relocated]"
    PASS=$((PASS + 1))
else
    echo "FAIL [structural-census-relocated] — clj_string_sweep_test.sh is missing,"
    echo "       so nothing censuses the interpolation sites."
    FAIL=$((FAIL + 1))
fi

echo
echo "Passed: $PASS   Failed: $FAIL"
if [[ $FAIL -gt 0 ]]; then
    echo "❌ send_command Clojure-string escaping: FAILURES"
    exit 1
fi
echo "✅ send_command Clojure-string escaping: all assertions passed"
