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

# --- --format: legality in the LOBBY's format, not only System Gateway. ---
# eq <label> <actual> <expected>: whole-string equality.
eq() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1"; printf '    expected: %q\n    got:      %q\n' "$3" "$2"; fi; }
fmt_run() { out="$("$DECK_CHECK" "$@" 2>&1)"; rc=$?; }

# HB Discretion Advised carries Luminal Transubstantiation, banned in Standard
# (data/cards.edn :format :standard {:banned true}), and is legal in Core.
fmt_run --format standard "$HB"
if [ $rc -eq 1 ] && echo "$out" | grep -q 'not standard legal: Luminal Transubstantiation (banned)' \
   && echo "$out" | grep -q 'engine (standard): Includes a banned card: Luminal Transubstantiation'; then
    pass "--format standard refuses a deck with a Standard-banned card, by name, twice (hand + engine)"
else fail "--format standard (exit $rc): $out"; fi
fmt_run --format core "$HB"
if [ $rc -eq 0 ] && echo "$out" | grep -q '^  engine (core): legal'; then pass "--format core accepts it and says which format it checked"
else fail "--format core (exit $rc): $out"; fi
# The default is the deck's OWN :format (as seed-decks.sh), so today's run is unchanged.
fmt_run "$HB"
if [ $rc -eq 0 ] && echo "$out" | grep -q '^  engine (system-gateway): legal'; then pass "default format is the deck's own (system-gateway)"
else fail "default format (exit $rc): $out"; fi
fmt_run --format bogus "$HB"
if [ $rc -eq 2 ] && echo "$out" | grep -q "unknown format 'bogus'"; then pass "--format bogus is a usage error"
else fail "--format bogus (exit $rc): $out"; fi
fmt_run --format preconstructed "$HB"
if [ $rc -eq 2 ] && echo "$out" | grep -q "preconstructed"; then pass "--format preconstructed is refused (precon lobbies take no deck)"
else fail "--format preconstructed (exit $rc): $out"; fi
# casual is checked against basic deckbuilding, and says the server checks less.
fmt_run --format casual "$HB"
if [ $rc -eq 0 ] && echo "$out" | grep -q '^  engine (casual): legal.*identity'; then pass "--format casual names what the server does not check"
else fail "--format casual (exit $rc): $out"; fi

# --- --formats / --precons: what can be asked for. ---
fmt_run --formats
eq "--formats lists the engine's formats plus preconstructed" \
   "$(echo "$out" | awk '{print $1}' | tr '\n' ' ')" \
   "casual core eternal standard startup system-gateway throwback preconstructed "
fmt_run --precons
eq "--precons lists every matchup" "$(echo "$out" | grep -c '^worlds-20[12][0-9]-[ab] ')" "28"
eq "--precons line names the year, players and identities" \
   "$(echo "$out" | grep '^worlds-2012-a ')" \
   "worlds-2012-a  Worlds 2012: Ben Marsh (C) vs. Jeremy Zwirn (R) | Haas-Bioroid: Engineering the Future vs Gabriel Santiago: Consummate Professional"

# --- --lobby <format> <precon> <gateway-type>: mirrors web/lobby.clj validate-precon. ---
lobby() { out="$("$DECK_CHECK" --lobby "$@" 2>&1)"; rc=$?; }
lobby system-gateway worlds-2012-a Intermediate; eq "--lobby today's default is the Intermediate precon" "$rc $out" "0 LOBBY precon intermediate"
lobby system-gateway worlds-2012-a Constructed;  eq "--lobby SG Constructed" "$rc $out" "0 LOBBY constructed system-gateway"
lobby preconstructed worlds-2019-a Intermediate; eq "--lobby Worlds precon" "$rc $out" "0 LOBBY precon worlds-2019-a"
lobby preconstructed Worlds-2019-A Intermediate; eq "--lobby lowercases the key like the server" "$rc $out" "0 LOBBY precon worlds-2019-a"
lobby standard worlds-2012-a Constructed;        eq "--lobby standard is constructed (precon ignored)" "$rc $out" "0 LOBBY constructed standard"
lobby preconstructed worlds-2099-a Intermediate
if [ $rc -eq 1 ] && echo "$out" | grep -q "unknown matchup 'worlds-2099-a'"; then pass "--lobby refuses an unknown matchup"; else fail "--lobby unknown matchup (exit $rc): $out"; fi
lobby bogus worlds-2012-a Constructed
if [ $rc -eq 1 ] && echo "$out" | grep -q "unknown format 'bogus'"; then pass "--lobby refuses an unknown format"; else fail "--lobby bogus (exit $rc): $out"; fi
lobby quick-draft worlds-2012-a Constructed
if [ $rc -eq 1 ] && echo "$out" | grep -q "format 'quick-draft' has no deck legality"; then pass "--lobby refuses a format the server computes no legality for"; else fail "--lobby quick-draft (exit $rc): $out"; fi

# --- --audit second tier: static defcard scan for cards outside the SG pool. ---
fmt_run --audit --precon worlds-2012-b
if echo "$out" | grep -Eq '^Hostile Takeover +x3  scan: HIGH - .*bad publicity.*\(#298/#299\)'; then pass "--audit scan flags Hostile Takeover's bad publicity HIGH (#298/#299)"
else fail "--audit Hostile Takeover: $out"; fi
if echo "$out" | grep -Eq '^Data Raven +x3  .*exercises #325 \(unproven live\): trace'; then pass "--audit marks a trace card as exercising #325, not as a hole"
else fail "--audit Data Raven: $out"; fi
if echo "$out" | grep -Eq '^Data Raven .*(HIGH|MED|LOW)'; then fail "--audit Data Raven must not carry a severity for its trace: $out"; else pass "--audit gives a #325 prompt no severity"; fi
# A System Gateway deck is audited exactly as before: hand table only, no scan.
fmt_run --audit "$HB"
eq "--audit SG deck output is unchanged (whole rows + summary)" "$(echo "$out" | sed -n '3,$p')" "Haas-Bioroid: Precision Design       x1  -
Ansel 1.0                            x3  -
Brân 1.0                             x2  -
Government Subsidy                   x3  -
Hedge Fund                           x3  -
Luminal Transubstantiation           x1  -
Manegarm Skunkworks                  x2  -
Nico Campaign                        x3  tempo - start-of-turn rez a turn late (#270)
Offworld Office                      x3  -
Palisade                             x3  -
Pharos                               x1  -
Predictive Planogram                 x3  -
Seamless Launch                      x2  -
Send a Message                       x3  -
Sprint                               x2  LOW - :max 2 selects, choose by index (#212)
Superconducting Hub                  x1  -
Tithe                                x3  -
Urtica Cipher                        x3  -
Whitespace                           x3  -
SUMMARY Discretion Advised: worst flag LOW (2 flagged lines)"
sg_scan=0
for d in "$DECKS"/sg-*.edn; do "$DECK_CHECK" --audit "$d" 2>&1 | grep -Eq 'scan:|#325' && sg_scan=1; done
eq "--audit never scans a card of any SG sample deck" "$sg_scan" "0"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
