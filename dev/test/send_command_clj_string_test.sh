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
# Per-INTERPOLATION, not per-line. `grep -v clj_str` discarded the whole line,
# so a site with two arguments stayed green when only ONE lost its escaping --
# install-card! takes both a card name and a server. (Fresh delta seat, Astra,
# who executed the partial revert rather than reasoning about it.) Strip every
# wrapped interpolation first, then look at what is left.
#
# NOT anchored on `execute`, and not restricted to named variables. Those two
# limits each hid live raw sites for months (#255), and neither was visible from
# the pattern's own green:
#
#   1. ACCUMULATION. A site that builds Clojure source into a VARIABLE first --
#      `FLAGS_ARGS="$FLAGS_ARGS \"$1\""`, `NAMES_VEC+="\"$name\" "` -- has no
#      `execute` on that line, so an `execute `-anchored pattern looks straight
#      past it. Seven sites were raw this way: five flag-forwarding arms
#      (run/continue/continue-run/monitor-run) and both card-name accumulators
#      (discard-by-names!, multi-choose!) -- the latter being exactly the
#      quoted-card-title case this file exists for.
#   2. POSITIONAL PARAMETERS. `[A-Za-z_]+` cannot match `$1`, so a forwarded
#      argument was invisible even on an `execute` line.
#
# Comment lines are excluded BY RULE, not by a pinned count -- a count cannot see
# an addition it never extracted (#260). They document this hazard and must stay
# free to quote it. The exclusion BLANKS the line rather than dropping it, so
# `grep -n` keeps reporting real file line numbers; dropping would renumber every
# finding below the first comment -- a guard whose output lies about where the
# defect is.
#
# There is NO general exclusion for user-facing message calls. Round 1 had one
# (`/(error|warn|info|echo)[[:space:]]+"/s/.*//`) and a seat showed it blanked the
# ENTIRE line, so `execute "(f \"$CARD_NAME\")"; info "done"` on one line hid a
# live raw interpolation behind its neighbour's exemption (r1/I4). Narrowing it
# would need to know where the message's argument ends, through escaped quotes.
# Deleting the rule is the sized answer: exactly two lines in send_command print
# an escaped quote to a human, and they carry CLJ-STR-EXEMPT like any other
# proved-safe site. One convention, no coarse rule, no hole in it.
# Three things are recognised as already-safe, each by a rule a new site cannot
# accidentally inherit:
#   - an inline `$(clj_str "$X")` wrap, the ordinary idiom;
#   - a `*_CLJ` variable, the convention for a value escaped at ASSIGNMENT time
#     because its use site sits inside a multi-line Clojure string where no
#     shell comment can reach. The suffix is only a claim, so
#     `clj-vars-are-assigned-from-clj_str` below CHECKS it;
#   - an explicit `CLJ-STR-EXEMPT: <reason>` marker, for a value a validator has
#     already proved cannot carry a quote (fix-credits' amount). This mirrors the
#     NUMERIC-GATE-EXEMPT marker the numeric sweep already uses, so there is one
#     convention for "proved safe at this site", not two.
# ONE scanner, called by the real check AND by every mutation below.
#
# The three mutations that used to live here each RE-SPELLED the pattern inline
# (`grep -qE 'execute .*\\"\$[A-Za-z_]+\\"'`). So they tested a copy, and when the
# real pipeline changed they went on asserting things about the old spelling and
# passed vacuously. A guard's mutation test that does not call the guard proves
# nothing about the guard. Everything below goes through scan_raw.
# INSIDE a Clojure string literal, judged by delimiter PARITY -- not by whether
# the variable happens to fill the whole literal.
#
# Round 1 matched `\"$VAR\"`, which requires the interpolation to BE the entire
# literal. A seat showed that `execute "(println \"before $CARD_NAME after\")"`
# therefore passed (r1/I1), and re-measuring with the scanner below found FOUR
# live instances the round-1 guard was green over, in the bot-loop arm's println
# strings. So the shape was not hypothetical.
#
# A plain grep cannot do this. The first attempt mapped `\"` to a sentinel and
# looked for `$` after one, which cannot tell "inside a literal" from "between
# two literals" -- it flagged every composed variable that legitimately sits
# outside one (`$FLAGS_ARGS`, `$ABILITY_INDEX`, `$COUNT`), five false positives
# on this file. Tracking parity is what distinguishes them, and that needs a walk
# over the line rather than a pattern.
scan_raw() {   # scan_raw FILE -> one `LINENO:TEXT` per unsafe interpolation
    sed -E 's/\$\(clj_str "\$\{?[A-Za-z_0-9]+\}?"\)/@SAFE@/g' "$1" \
      | sed -E 's/\$\{?[A-Za-z_0-9]*_CLJ\}?/@SAFE@/g' \
      | sed -E '/CLJ-STR-EXEMPT/s/.*//' \
      | sed -E 's/^[[:space:]]*#.*$//' \
      | awk '
        {
            inside = 0; i = 1; n = length($0)
            while (i <= n) {
                # `\"` opens or closes a Clojure string literal in the generated text.
                if (substr($0, i, 2) == "\\\"") { inside = !inside; i += 2; continue }
                if (inside && substr($0, i, 1) == "$") {
                    nxt = substr($0, i + 1, 1)
                    # A digit matters: `$1` is how every forwarded flag arrives.
                    if (nxt ~ /[A-Za-z_{0-9]/) { print NR ":" $0; next }
                }
                i += 1
            }
        }' || true
}
scan_line() { printf '%s\n' "$1" | scan_raw /dev/stdin; }

# The pattern as it was BEFORE this widening, kept only so the two holes below
# can be shown to have been holes. If a future change makes the new scanner stop
# catching what this one missed, `widening-is-load-bearing` goes red.
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

# --- the two holes that hid seven live raw sites (#255) ---
# 1. ACCUMULATION: builds Clojure source into a variable, so there is no
#    `execute` on the line. All five flag arms and both card-name accumulators.
flags mutation-accumulation-site-is-detected 'FLAGS_ARGS="$FLAGS_ARGS \"$1\""'
flags mutation-accumulation-append-is-detected 'NAMES_VEC+="\"$name\" "'
# 2. POSITIONAL PARAMETER: `[A-Za-z_]+` cannot match `$1`.
flags mutation-positional-param-is-detected 'execute "(ai-actions/run! \"$1\")"'

# And the widening is what buys those three. If the OLD pattern caught them,
# this change is decoration; it must miss all three for the new one to be worth
# its complexity.
if [[ -z "$(scan_line_old 'FLAGS_ARGS="$FLAGS_ARGS \"$1\""')" \
   && -z "$(scan_line_old 'NAMES_VEC+="\"$name\" "')" \
   && -z "$(scan_line_old 'execute "(ai-actions/run! \"$1\")"')" ]]; then
    echo "ok   [widening-is-load-bearing]"
    PASS=$((PASS + 1))
else
    echo "FAIL [widening-is-load-bearing] — the pre-widening pattern already caught these,"
    echo "       so the extra complexity in scan_raw buys nothing"
    FAIL=$((FAIL + 1))
fi

# --- the three safe-by-rule forms, each must be recognised ---
clean exempt-clj-suffixed-var-is-clean 'execute "(runs/tank \"$ICE_NAME_CLJ\")"'
clean exempt-marker-is-honoured 'AMOUNT="\"$AMOUNT\"" ;;  # CLJ-STR-EXEMPT: validated numeric'
clean comment-quoting-the-hazard-is-clean '# like this: FLAGS_ARGS="$FLAGS_ARGS \"$1\""'
# A message line printing an escaped quote to a human carries the marker like any
# other proved-safe site; there is no blanket message rule to have a hole (r1/I4).
clean user-message-is-exempt-by-marker \
      '    *) error "needs your side: find-card \"$CARD_NAME\"" ;;  # CLJ-STR-EXEMPT: a message, not Clojure'
# ...and WITHOUT the marker that same line is flagged, so the marker is doing the
# work and the deleted blanket rule is really gone.
flags message-without-marker-is-flagged \
      '    *) error "needs your side: find-card \"$CARD_NAME\"" ;;'

# --- the hole a seat found in round 1's pattern (r1/I1) ---
flags interpolation-inside-a-longer-string-is-detected \
      'execute "(println \"before $CARD_NAME after\")"'
# ...while a composed variable BETWEEN two literals is NOT inside one. The first
# attempt at the fix flagged all five of these on the real file.
clean composed-var-between-two-literals-is-clean \
      'execute "(f \"a\" $FLAGS_ARGS \"b\")"'
clean bare-numeric-outside-any-literal-is-clean \
      'execute "(ai-actions/fix-credits! $AMOUNT)"'

# A `_CLJ` suffix is a CLAIM that the value was escaped at assignment time. The
# name proves nothing on its own, so check every assignment actually calls
# clj_str -- otherwise the suffix becomes a way to silence this guard.
#
# The anchor allows a declaration keyword. Round 1 anchored on `^[[:space:]]*NAME=`
# and a seat showed `local SOME_CLJ="${X//\"/\\\"}"` defeated the whole convention
# with one keyword (r1/I2): scan_raw exempts the use site on the suffix, and this
# check could not match past `local `, so both halves reported clean together.
# send_command has 32 `local` lines, so it is an ordinary thing to write.
scan_clj_assignments() {   # -> one `LINENO:TEXT` per _CLJ assignment that skips clj_str
    grep -nE '^[[:space:]]*(local|export|declare|readonly|typeset)?[[:space:]]*[A-Za-z_0-9]*_CLJ=' "$1" \
      | grep -v 'clj_str' || true
}
echo "--- every _CLJ variable is really assigned from clj_str ---"
BAD_CLJ=$(scan_clj_assignments "$SEND_CMD")
if [[ -z "$BAD_CLJ" ]]; then
    echo "ok   [clj-vars-are-assigned-from-clj_str]"
    PASS=$((PASS + 1))
else
    echo "FAIL [clj-vars-are-assigned-from-clj_str] — these claim _CLJ without calling clj_str:"
    echo "$BAD_CLJ" | sed 's/^/       /'
    FAIL=$((FAIL + 1))
fi
# ...and that THAT check can fail, or the suffix convention is unenforced.
#
# These go through scan_clj_assignments. Round 1 re-spelled its grep inline here,
# so deleting the real check left the suite green and the mutation test went on
# passing about a copy (r1/I3) -- the same defect this file fixes three lines up
# for scan_raw, reintroduced in the same commit. A mutation test that does not
# call the thing it is mutating proves nothing about it.
assign_flags() {   # assign_flags LABEL LINE  -- must be reported
    printf '%s\n' "$2" > "$TMP_ASSIGN"
    if [[ -n "$(scan_clj_assignments "$TMP_ASSIGN")" ]]; then
        echo "ok   [$1]"; PASS=$((PASS + 1))
    else
        echo "FAIL [$1] — scan_clj_assignments missed: $2"; FAIL=$((FAIL + 1))
    fi
}
assign_clean() {   # assign_clean LABEL LINE  -- must NOT be reported
    printf '%s\n' "$2" > "$TMP_ASSIGN"
    if [[ -z "$(scan_clj_assignments "$TMP_ASSIGN")" ]]; then
        echo "ok   [$1]"; PASS=$((PASS + 1))
    else
        echo "FAIL [$1] — scan_clj_assignments falsely flagged: $2"; FAIL=$((FAIL + 1))
    fi
}
TMP_ASSIGN="$(mktemp "${TMPDIR:-/tmp}/clj-assign.XXXXXX")"
trap 'rm -f "$TMP_ASSIGN"' EXIT

assign_flags mutation-hand-rolled-clj-var-is-detected \
             '        ICE_NAME_CLJ="${ICE_NAME//\"/\\\"}"'
# The keyword that defeated round 1's anchor (r1/I2).
assign_flags mutation-local-hand-rolled-clj-var-is-detected \
             '        local SOME_CLJ="${X//\"/\\\"}"'
assign_flags mutation-export-hand-rolled-clj-var-is-detected \
             '        export SOME_CLJ="${X//\"/\\\"}"'
assign_clean mutation-proper-clj-assignment-is-clean \
             '        ICE_NAME_CLJ="$(clj_str "$ICE_NAME")"'
assign_clean mutation-proper-local-clj-assignment-is-clean \
             '        local SOME_CLJ="$(clj_str "$X")"'

echo
echo "Passed: $PASS   Failed: $FAIL"
if [[ $FAIL -gt 0 ]]; then
    echo "❌ send_command Clojure-string escaping: FAILURES"
    exit 1
fi
echo "✅ send_command Clojure-string escaping: all assertions passed"
