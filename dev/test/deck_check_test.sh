#!/usr/bin/env bash
# deck_check_test.sh — dev/deck-check must accept the decks the server accepts and
# refuse the ways a deck goes wrong, each with a reason that names the rule.
# Pure offline: reads data/cards.edn and dev/decks/*.edn, never the game server.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DECK_CHECK="$SCRIPT_DIR/../deck-check"
DECKS="$SCRIPT_DIR/../decks"
HB="$DECKS/sg-hb-discretion-advised.edn"
SHAPER="$DECKS/sg-shaper-planning-ahead.edn"

PASS=0; FAIL=0
pass() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-deckcheck-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

[ -x "$DECK_CHECK" ] || { fail "no executable $DECK_CHECK"; echo "$PASS passed, $FAIL failed"; exit 1; }

# expect_pass <label> <deck>
expect_pass() {
    out="$("$DECK_CHECK" "$2" 2>&1)"; rc=$?
    if [ $rc -eq 0 ]; then pass "$1"; else fail "$1 (exit $rc): $out"; fi
}
# expect_fail <label> <regex> <deck>
expect_fail() {
    out="$("$DECK_CHECK" "$3" 2>&1)"; rc=$?
    if [ $rc -ne 0 ] && echo "$out" | grep -Eqi "$2"; then pass "$1"
    else fail "$1 (exit $rc, wanted /$2/): $out"; fi
}

echo "deck-check:"
expect_pass "HB Discretion Advised passes" "$HB"
expect_pass "Shaper Planning Ahead passes" "$SHAPER"

# 4th copy: Hedge Fund 3 -> 4 (also makes 45 cards; the copy rule must still be named)
sed 's/{:qty 3, :card "Hedge Fund"}/{:qty 4, :card "Hedge Fund"}/' "$HB" > "$TMP/four.edn"
expect_fail "4th copy fails with the copy reason" "too many copies.*Hedge Fund" "$TMP/four.edn"

sed 's/"Hedge Fund"/"Hedge Fund Deluxe"/' "$HB" > "$TMP/unknown.edn"
expect_fail "unknown title fails" "unknown card.*Hedge Fund Deluxe" "$TMP/unknown.edn"

# Over influence: swap 3 in-faction Palisade... use Shaper: add 3 Criminal Mutual Favor (9 inf) over a 15 budget
sed 's/{:qty 3, :card "Sure Gamble"}/{:qty 3, :card "Mutual Favor"}\n  {:qty 3, :card "Sure Gamble"}/' "$SHAPER" > "$TMP/inf.edn"
expect_fail "over-influence fails" "influence" "$TMP/inf.edn"

# exit status on a multi-deck run: one bad deck makes the run non-zero
"$DECK_CHECK" "$HB" "$TMP/unknown.edn" >/dev/null 2>&1; rc=$?
if [ $rc -ne 0 ]; then pass "one bad deck among good ones fails the run"; else fail "mixed run exited 0"; fi

# --audit
out="$("$DECK_CHECK" --audit "$DECKS/sg-anarch-party-hard.edn" 2>&1)"; rc=$?
if echo "$out" | grep -Eq '^Botulus.*HIGH.*#170'; then pass "--audit flags Botulus HIGH"; else fail "--audit Botulus HIGH: $out"; fi
out="$("$DECK_CHECK" --audit "$HB" 2>&1)"
if echo "$out" | grep -Eq '^Nico Campaign.*tempo'; then pass "--audit flags Nico Campaign tempo"; else fail "--audit Nico: $out"; fi

echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
