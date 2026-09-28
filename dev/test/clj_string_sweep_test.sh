#!/usr/bin/env bash
# #255 was "seat-supplied text reaches the eval as SOURCE", and it was the third
# instance of that class (after `wait --since` and `fix-credits` under #251). The
# six sites the issue named were not the whole of it: there were twenty, and the
# reason nobody knew that is the guard that was supposed to be watching.
#
# send_command_clj_string_test.sh's structural half was:
#
#     RAW=$(sed -E 's/\$\(clj_str "\$[A-Za-z_]+"\)//g' "$SEND_CMD" \
#           | grep -nE 'execute .*\\"\$[A-Za-z_]+\\"' || true)
#
# blind THREE ways:
#   1. `execute .*` needs `execute` ON THE LINE, so an accumulator built earlier
#      (`NAMES_VEC+=`, `FLAGS_ARGS=`) and every continuation line of a multiline
#      `execute` were invisible - 19 of the 20 sites.
#   2. `[A-Za-z_]+` cannot match `$1`, and all five flag arms interpolated
#      `\"$1\"`, so they were invisible even without the anchor.
#   3. its strip regex does not strip `$(clj_str "$1")` either, so it would have
#      reported the FIXED flag sites as violations.
#
# So this is not "are the known sites fixed" (clj_string_route_test.sh pins that,
# per arm, by driving the real dispatcher). It is two censuses:
#
#   A. no `$` reaches the inside of a SHELL-ESCAPED Clojure string literal
#      (`\"…\"`) unescaped, anywhere in the file - not only on a line that says
#      `execute`. The qualifier is load-bearing: an expression assembled by
#      printf or a heredoc has no `\"` for this to pair, and census B is what
#      catches those (there are mutations for all four shapes I got past A).
#   B. every BARE `$NAME` interpolated into an `execute` expression is one of a
#      reviewed list. A bare interpolation is not a string at all: a new arm
#      writing `execute "(f $ARG)"` puts seat text where Clojure reads CODE, and
#      census A cannot see it by construction. Two design seats raised this
#      independently and I had declined it twice; a pinned COUNT is what makes a
#      whitelist a review decision instead of a list that goes stale in silence.
#
# WHERE THE COMPLETENESS CLAIM LIVES, and it is NOT here. Four review rounds put
# their MAJORs on these censuses, and the last one found five more shapes they
# cannot see: a bare positional (`$1`), a captured execute
# (`R="$(execute "(f $ARG)")"`), a Clojure-level escaped quote inside a string, a
# single-quoted expression with shell concatenation (`execute '(f "'"$X"'")'`), and
# reuse of an already-whitelisted variable NAME in a new ungated arm. All five are
# fixed below, but that is not the point: they were five members of an OPEN set,
# because a per-line text scan of shell source is being asked a data-flow question.
#
# So the class-closure claim moved to the enumeration, which inspects the OUTPUT:
#   clj_string_route_test.sh  - drives every command the dispatcher has with a
#                               quote-bearing payload (fast; carries the mutation
#                               battery for all five shapes above).
#   clj_string_roundtrip_test.clj - the same, precisely: does the value arrive as
#                               ONE Clojure string, by identity, with a reader.
#
# These censuses stay because they are cheap and they localise a defect to a LINE,
# which the enumeration cannot. They are spelling censuses. They are not a proof,
# and after four rounds of being treated as one, they say so here.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)" || exit 1
SEND_CMD="$SCRIPT_DIR/../send_command"
[[ -r "$SEND_CMD" ]] || { echo "FAIL: cannot read $SEND_CMD"; exit 1; }

fail=0
check() {
    local name="$1" actual="$2" expected="$3"
    if [[ "$actual" == "$expected" ]]; then
        echo "ok   [$name]"
    else
        printf 'NOT OK [%s]\n expected: %q\n      got: %q\n' "$name" "$expected" "$actual"
        fail=$((fail + 1))
    fi
}

# How many lines may carry CLJ-STR-EXEMPT. ZERO: every interpolation into a
# Clojure string in this file goes through clj_str, with no exceptions. Four of
# those escapes are ceremonial (a signed amount already through the numeric gate,
# and $CLIENT_NAME, which line 54's regex restricts to [a-z0-9-] and so cannot
# carry a quote) and they are paid for deliberately, to delete the escape hatch
# rather than to keep a list of reasons. Raising this number is a review decision.
EXPECTED_EXEMPTIONS=0

# awk here is BSD awk (20200816), not gawk: bracket forms [$] and [(] are the
# spelling that works. `clj_str_v?` - which means clj_str, a literal underscore
# and an optional v - matched neither helper and silently stripped nothing.

# --- Census A: a $ inside a Clojure STRING LITERAL ---------------------------
#
# A Clojure string literal inside a shell double-quoted expression is delimited
# by \", so the unit is the text between a PAIR of \". That pairing assumes every
# such literal opens and closes on ONE line, which is true of this file today and
# is asserted separately below - otherwise a string spanning lines would be a
# hole rather than a violation.
census_a() {
    awk '
        # Inside a shell double-quoted string, each PAIR of backslashes becomes one
        # literal backslash and a trailing lone backslash escapes the quote. So a
        # quote is a Clojure string DELIMITER when the backslash run before it is
        # 1 mod 4:
        #   \"      run 1  -> "        a DELIMITER
        #   \\"     run 2  -> \ + shell quote
        #   \\\"    run 3  -> \"       a Clojure-level ESCAPED quote, not a delimiter
        #   \\\\"   run 4  -> \\ + shell quote
        #   \\\\\"  run 5  -> \\"      an escaped backslash then a DELIMITER
        #
        # Two wrong versions preceded this. The first paired on the two-character
        # text \" with a regex, so it read the TAIL of a run-3 escape as a
        # delimiter, mis-paired the rest of the line, and left an interpolation in a
        # gap it believed was outside a string. The second - written to fix exactly
        # that - said "exactly ONE backslash", which is wrong at run 5, and a guest
        # seat ran the case. Hence the arithmetic, spelled out above.
        function clj_string_interps(line,    i, c, run, instr, n, seg) {
            n = 0; run = 0; instr = 0; seg = ""
            for (i = 1; i <= length(line); i++) {
                c = substr(line, i, 1)
                if (c == "\\") { run++; if (instr) seg = seg c; continue }
                if (c == "\"") {
                    if (run % 4 == 1) {
                        if (instr) { if (index(seg, "$")) n++; instr = 0; seg = "" }
                        else { instr = 1; seg = "" }
                    } else if (instr) seg = seg c
                    run = 0
                    continue
                }
                run = 0
                if (instr) seg = seg c
            }
            return n
        }
        # A comment-only line is prose, not code: this file QUOTES the old raw
        # accumulator shape in a comment, as documentation of what #255 fixed.
        /^[[:space:]]*#/ { next }
        {
            marked = ($0 ~ /CLJ-STR-EXEMPT/)
            line = $0
            # The one safe form: clj_str, INLINE, printing its result. A helper that
            # ASSIGNS instead of printing would emit an EMPTY argument here and must
            # NOT be blessed by prefix, so this matches clj_str only.
            gsub(/[$][(]clj_str "[^"]*"[)]/, "SAFE", line)
            if (clj_string_interps(line) == 0) next
            if (marked) { exempt_used++; next }
            printf "%d: %s\n", NR, $0
        }
        END {
            if (exempt_used != expect_exempt) printf "0: %d line(s) carry CLJ-STR-EXEMPT, expected %d - a new exemption needs review, not a paste\n", exempt_used, expect_exempt
        }
    ' expect_exempt="${2:-$EXPECTED_EXEMPTIONS}" "$1"
}

echo "--- no \$ reaches the inside of a Clojure string literal unescaped ---"
FOUND="$(census_a "$SEND_CMD")"
check 'census-a-clean' "$FOUND" ''
if [[ -n "$FOUND" ]]; then
    echo "   Each line above interpolates into a Clojure string without clj_str."
    echo "   Write \\\"\$(clj_str \"\$VAR\")\\\", or use clj_str_args for a list."
fi

echo "--- every Clojure string literal opens and closes on ONE line ---"
# Census A walks \" PAIRS, so an unterminated \" at end of line is ignored. That
# is a hole, not a violation, unless this holds - so it is asserted, and a future
# multi-line Clojure string fails HERE instead of quietly widening the blind spot.
ODD="$(awk '!/^[[:space:]]*#/ { line = $0; gsub(/\\\\\\\\"/, "Q", line); n = gsub(/\\"/, "&", line); if (n % 2) printf "%d: %s\n", NR, $0 }' "$SEND_CMD")"
check 'no-multiline-clojure-string' "$ODD" ''

# --- Census B: a BARE $NAME inside an execute expression --------------------
#
# Not a string: Clojure reads this position as CODE. The reviewed list, with the
# reason each name is allowed to be code:
#
#   numeric-gated (#251's gate validated the digit shape):
#     INDEX COUNT N ABILITY_INDEX AMOUNT INDICES_VEC DELTA CHOICE TIMEOUT SINCE
#     (TIMEOUT and SINCE are in `wait`, inside a CAPTURED execute. The first
#     version of this census stripped a captured execute along with the
#     interpolation inside it, so those two were invisible; unwrapping the capture
#     surfaced them. Both are gated: `num_arg SINCE`, and TIMEOUT is reset to 300
#     in the arm and only replaced from `is_num`/$NUM_ARG.)
#   shape-gated to a lowercase keyword name, because it is interpolated as a
#   Clojure KEYWORD (`:$KEY`) and so is read as code. Census B found this arm
#   UNGATED on its first run: `change 'credit) (ai-actions/end-turn!) (comment' 5`
#   emitted two top-level forms and the second ended the turn (#255):
#     KEY
#   built by clj_str_args, so already a list of escaped literals:
#     FLAGS_ARGS ARGS_STR NAMES_VEC
#   an internal literal this file writes, never a seat argument:
#     OPTS OVERWRITE BOT_NS LOOP_FN LOOP_ARGS PAY_ALL
#     (SELF_HB was listed here and is interpolated into NO execute at all - a dead
#     entry in a reviewed whitelist is worse than a missing one, because it
#     pre-authorises a name nobody reviewed for that use. Removed; a guest seat
#     found it by counting listed against seen.)
#
# SCOPE OF THIS WHITELIST, precisely: it is keyed on the NAME, and a name is not a
# value. A new arm writing `N="$1"; execute "(f $N)"` reuses an allowed name and
# this census reports clean - a guest seat reproduced that. The stated reasons are
# properties of today's SITES. What closes that hole is the enumeration in
# clj_string_route_test.sh, which drives every command and looks at the OUTPUT;
# there is a mutation there for exactly this shape. Do not read this list as a
# proof that every bare interpolation is gated.
#   the `eval` arm, whose entire purpose is to send seat text as source:
#     EXPR
BARE_ALLOWED='INDEX|COUNT|N|ABILITY_INDEX|AMOUNT|INDICES_VEC|DELTA|CHOICE|KEY|TIMEOUT|SINCE|FLAGS_ARGS|ARGS_STR|NAMES_VEC|OPTS|OVERWRITE|BOT_NS|LOOP_FN|LOOP_ARGS|PAY_ALL|EXPR'
EXPECTED_BARE=21   # pinned: a NEW bare name is a review decision, and dropping one that is still used is too

census_b() {
    awk '
        /^[[:space:]]*#/ { next }
        # Track a multiline execute: it opens with `execute "` and the expression
        # runs to the line whose text closes it. Bare interpolation matters only
        # inside an expression that is SENT.
        {
            line = $0
            # A CAPTURED execute - R="$(execute "(f $ARG)")" - is an execute too,
            # and the command-substitution strip below used to remove it TOGETHER
            # with the interpolation inside it, so the whole arm read clean. Unwrap
            # the capture first. The shape is already in this file (send_command
            # reads a prompt that way).
            gsub(/[$][(][[:space:]]*execute[[:space:]]/, "execute ", line)
            if (!in_exec) { if (line !~ /execute [ '"'"'"]*"/ && line !~ /execute "/) next; in_exec = 1 }
            # Strip Clojure string literals: their contents are census A s job.
            work = line
            while (match(work, /\\"[^\\]*\\"/)) work = substr(work, 1, RSTART - 1) "S" substr(work, RSTART + RLENGTH)
            # Strip command substitutions (their own shell context).
            gsub(/[$][(][^)]*[)]/, "S", work)
            n = 0
            while (match(work, /[$][{]?([A-Za-z_][A-Za-z0-9_]*|[0-9]+|[@*#])[}]?/)) {
                name = substr(work, RSTART, RLENGTH)
                work = substr(work, RSTART + RLENGTH)
                gsub(/[${}]/, "", name)
                # A POSITIONAL is never allowed bare: it is raw seat text, and the
                # name regex used to be [A-Za-z_]-only, which is the identical $1
                # blind spot this file criticises in the guard it replaced.
                if (name ~ /^[0-9@*#]+$/) { printf "%d: bare $%s (a positional - raw seat text) - %s\n", NR, name, $0; continue }
                if (name ~ allowed_re) { seen[name] = 1; continue }
                printf "%d: bare $%s - %s\n", NR, name, $0
            }
            # The expression ends when the shell string closes: a line ending in
            # `)"` or `"` with no continuation. Approximated by an unescaped " at
            # end of line, which is how every execute in this file ends.
            if (line ~ /[^\\]"[[:space:]]*$/ || line ~ /[^\\]"[)][[:space:]]*$/) in_exec = 0
        }
        END {
            k = 0
            for (nm in seen) k++
            if (k != expect_bare) printf "0: %d whitelisted bare name(s) actually used, expected %d - a new bare name slipped in, or an arm that was the LAST user of one was deleted; either way bump the count deliberately\n", k, expect_bare
        }
    ' allowed_re="^($BARE_ALLOWED)$" expect_bare="${2:-$EXPECTED_BARE}" "$1"
}

echo "--- every bare \$NAME in an execute expression is on the reviewed list ---"
FOUND_B="$(census_b "$SEND_CMD")"
check 'census-b-clean' "$FOUND_B" ''
if [[ -n "$FOUND_B" ]]; then
    echo "   A bare interpolation is CODE, not a string. Put the value in a"
    echo "   Clojure string (clj_str), or add the name above with its reason."
fi

# --- Mutations. A census that cannot see is worse than none: it reports clean.
TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-clj-sweep.XXXXXX")" || exit 1
[[ -n "$TMP" && -d "$TMP" ]] || { echo "FAIL: no temp dir"; exit 1; }
trap 'rm -rf "$TMP"' EXIT

# The OLD pattern, verbatim, so each mutation can record what CHANGED rather than
# asserting a tautology about the new one.
old_pattern_sees() {
    sed -E 's/\$\(clj_str "\$[A-Za-z_]+"\)//g' "$1" \
        | grep -qE 'execute .*\\"\$[A-Za-z_]+\\"'
}

mutant() {  # mutant NAME CENSUS SED_EXPR [also-visible-to-old]
    local name="$1" census="$2" expr="$3" old="${4:-no}" f="$TMP/mutant"
    sed "$expr" "$SEND_CMD" > "$f"
    if cmp -s "$SEND_CMD" "$f"; then
        echo "NOT OK [$name] MUTATION DID NOT APPLY - assertion proves nothing"
        fail=$((fail + 1)); return
    fi
    if [[ -n "$($census "$f")" ]]; then
        echo "ok   [$name]"
    else
        echo "NOT OK [$name] census reported CLEAN over the injected defect"
        fail=$((fail + 1))
    fi
    if [[ "$old" == "blind" ]]; then
        if old_pattern_sees "$f"; then
            echo "NOT OK [$name/old-was-blind] the OLD pattern sees it too - this mutation records nothing"
            fail=$((fail + 1))
        else
            echo "ok   [$name/old-was-blind]"
        fi
    fi
}

clean_mutant() {  # clean_mutant NAME CENSUS SED_EXPR  -- must NOT flag
    local name="$1" census="$2" expr="$3" f="$TMP/mutant"
    sed "$expr" "$SEND_CMD" > "$f"
    if cmp -s "$SEND_CMD" "$f"; then
        echo "NOT OK [$name] MUTATION DID NOT APPLY - assertion proves nothing"
        fail=$((fail + 1)); return
    fi
    local out; out="$($census "$f")"
    if [[ -z "$out" ]]; then
        echo "ok   [$name]"
    else
        echo "NOT OK [$name] census FALSELY flagged a safe line:"
        printf '%s\n' "$out" | sed 's/^/       /'
        fail=$((fail + 1))
    fi
}

NEW_ARM='    my-new-arm)'
echo "--- the three shapes the old guard was blind to (mutation tests) ---"
mutant 'mutation-accumulator-line' census_a \
    "s|^    keep-hand)|$NEW_ARM\\n        ACC=\"\$ACC \\\\\"\$1\\\\\"\"\\n        execute \"(f\$ACC)\"\\n        ;;\\n\\n    keep-hand)|" blind
mutant 'mutation-multiline-continuation' census_a \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(do\\n                   (f {:title \\\\\"\$TITLE\\\\\"})\\n                   nil)\"\\n        ;;\\n\\n    keep-hand)|" blind
mutant 'mutation-dollar-one-named-site' census_a \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f \\\\\"\$1\\\\\")\"\\n        ;;\\n\\n    keep-hand)|" blind

echo "--- and the two shapes the REVISED pattern was going to be blind to ---"
# An interpolation INSIDE a string rather than hugging the quotes, and one via
# another command substitution. Both found in design review.
mutant 'mutation-interior-interpolation' census_a \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f \\\\\"prefix \$VALUE\\\\\")\"\\n        ;;\\n\\n    keep-hand)|" blind
mutant 'mutation-interior-command-substitution' census_a \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f \\\\\"\$(basename \"\$F\")\\\\\")\"\\n        ;;\\n\\n    keep-hand)|" blind

echo "--- a non-printing escaper must NOT be blessed by prefix ---"
# `$(clj_str_v X "$V")` assigns and prints nothing, so the emitted argument would
# be EMPTY while a prefix-matching strip reported clean. Worse than unescaped:
# the command looks like it worked.
mutant 'mutation-assigning-helper-not-blessed' census_a \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f \\\\\"\$(clj_str_v OUT \"\$TITLE\")\\\\\")\"\\n        ;;\\n\\n    keep-hand)|"

echo "--- the exemption marker is not an escape hatch (mutation tests) ---"
mutant 'mutation-marker-pasted-on-a-new-arm' census_a \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f \\\\\"\$1\\\\\")\" # CLJ-STR-EXEMPT: looks official\\n        ;;\\n\\n    keep-hand)|"

echo "--- a Clojure string spanning two lines is reported, not ignored ---"
ODD_MUTANT="$TMP/odd"
sed "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f \\\\\"start\\n                   end\\\\\")\"\\n        ;;\\n\\n    keep-hand)|" "$SEND_CMD" > "$ODD_MUTANT"
if cmp -s "$SEND_CMD" "$ODD_MUTANT"; then
    echo "NOT OK [mutation-multiline-string-detected] MUTATION DID NOT APPLY"
    fail=$((fail + 1))
elif [[ -n "$(awk '!/^[[:space:]]*#/ { n = gsub(/\\"/, "&"); if (n % 2) print NR }' "$ODD_MUTANT")" ]]; then
    echo "ok   [mutation-multiline-string-detected]"
else
    echo "NOT OK [mutation-multiline-string-detected] the odd-quote check missed it"
    fail=$((fail + 1))
fi

echo "--- census B sees a bare interpolation (mutation tests) ---"
mutant 'mutation-bare-arg-is-code' census_b \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f \$ARG)\"\\n        ;;\\n\\n    keep-hand)|"
mutant 'mutation-bare-arg-braced' census_b \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f \${ARG})\"\\n        ;;\\n\\n    keep-hand)|"
mutant 'mutation-bare-arg-in-multiline-execute' census_b \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(do\\n                   (f \$ARG)\\n                   nil)\"\\n        ;;\\n\\n    keep-hand)|"
# The whitelist count is the contract in both directions: dropping a name that is
# still used, or adding one that is not, is a review decision.
mutant 'mutation-whitelisted-name-renamed' census_b 's|{\$OVERWRITE}|{$OVERWRITE_FLAG}|'

echo "--- the two censuses are complementary, not redundant ---"
# I tried to get an arm past census A and succeeded four ways; each one is caught
# by census B instead, which is the argument for having both. Census A only ever
# sees a Clojure string that was escaped FOR THE SHELL (\"), so an expression
# assembled by printf or a heredoc has no \" for it to pair, and a keyword or
# symbol interpolation is not a string at all.
mutant 'mutation-printf-built-expression' census_b \
    "s|^    keep-hand)|$NEW_ARM\\n        E=\$(printf '(f \"%s\")' \"\$VAL\")\\n        execute \"\$E\"\\n        ;;\\n\\n    keep-hand)|"
mutant 'mutation-bare-keyword-interpolation' census_b \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f :\$VAL)\"\\n        ;;\\n\\n    keep-hand)|"
mutant 'mutation-bare-symbol-interpolation' census_b \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f (quote \$VAL))\"\\n        ;;\\n\\n    keep-hand)|"
# ...and the converse: an interior interpolation inside a properly shell-escaped
# string is census A's, and census B cannot see it (it strips string literals on
# purpose, because their contents are A's job).
clean_mutant 'clean-census-b-leaves-string-interiors-to-census-a' census_b \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f \\\\\"pre \$VAL\\\\\")\"\\n        ;;\\n\\n    keep-hand)|"

echo "--- census B's COUNT check is not a mutation witness (mutation test) ---"
# Forcing the expected count makes census_b print a diagnostic over ANY file,
# including a clean one, so an assertion built on a forced count proves nothing.
# This pins that, because one was shipped here and a guest seat had to find it.
if [[ -n "$(census_b "$SEND_CMD" 18)" ]]; then
    echo "ok   [census-b-count-is-not-a-witness] (a forced count flags the CLEAN file too)"
else
    echo "NOT OK [census-b-count-is-not-a-witness] a forced count no longer flags the clean"
    echo "       file, so the warning above this check is stale"
    fail=$((fail + 1))
fi

echo "--- neither census cries wolf (negative mutation tests) ---"
clean_mutant 'clean-a-wrapped-site-is-not-flagged' census_a \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f \\\\\"\$(clj_str \"\$CARD_NAME\")\\\\\")\"\\n        ;;\\n\\n    keep-hand)|"
clean_mutant 'clean-a-commented-raw-line-is-not-flagged' census_a \
    "s|^    keep-hand)|        # execute \"(f \\\\\"\$1\\\\\")\" -- documented, not code\\n    keep-hand)|"
clean_mutant 'clean-clj_str_args-list-is-not-flagged' census_b \
    "s|^    keep-hand)|$NEW_ARM\\n        FLAGS_ARGS=\"\$(clj_str_args \"\$@\")\"\\n        execute \"(f\${FLAGS_ARGS:+ \$FLAGS_ARGS})\"\\n        ;;\\n\\n    keep-hand)|"

echo "--- the five shapes a code round found, now that they are fixed ---"
# Census B's three: a bare positional, a captured execute, and reuse of an
# already-whitelisted NAME in a new ungated arm.
mutant 'mutation-bare-positional-parameter' census_b \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f \$1)\"\\n        ;;\\n\\n    keep-hand)|"
mutant 'mutation-captured-execute' census_b \
    "s|^    keep-hand)|$NEW_ARM\\n        R=\"\$(execute \"(f \$ARG)\")\"\\n        echo \"\$R\"\\n        ;;\\n\\n    keep-hand)|"
# Census A's one: a Clojure-LEVEL escaped quote inside the string, which used to
# mis-pair the walk and hide the interpolation after it.
# A run of FIVE backslashes: an escaped backslash then a DELIMITER. The scanner's
# first version said "exactly ONE backslash" and read this as not-a-delimiter, so
# the interpolation after it sat in a gap believed to be outside a string. A guest
# seat ran the case; this pins the arithmetic. The fixture is a FILE because the
# quoting is the subject, and a sed expression inside a shell string is how a
# fixture silently stops matching.
R5="$TMP/run5"
rm -f "$R5"
if ! python3 "$SCRIPT_DIR/fixtures/run_five_backslashes.py" "$SEND_CMD" "$R5"; then
    echo "NOT OK [mutation-run-of-five-backslashes] fixture is stale"
    fail=$((fail + 1))
elif [[ -n "$(census_a "$R5" 0)" ]]; then
    echo "ok   [mutation-run-of-five-backslashes]"
else
    echo "NOT OK [mutation-run-of-five-backslashes] census A reported CLEAN over a string"
    echo "       that really does open after five backslashes"
    fail=$((fail + 1))
fi

mutant 'mutation-clojure-escaped-quote-interior' census_a \
    "s|^    keep-hand)|$NEW_ARM\\n        execute \"(f \\\\\"pre\\\\\\\\\\\\\"\$CHOICE\\\\\\\\\\\\\"post\\\\\")\"\\n        ;;\\n\\n    keep-hand)|"
# The two neither census can ever see, which is why the enumeration exists: a
# single-quoted expression with shell concatenation, and an arm reusing a
# whitelisted NAME. Both are mutation-tested in clj_string_route_test.sh, against
# the enumeration. Asserting them here would be asserting a known blind spot.

echo "--- reverting each real #255 fix goes red (mutation tests) ---"
# The census's whole claim is that it would have caught #255. Put each site back
# the way it was and watch it fail.
revert() {  # revert NAME  -- put one real #255 site back and watch census A go red
    local name="$1" find="$2" repl="$3" f="$TMP/revert"
    # Remove the target first and check the writer's exit status. A fixture that
    # no longer matches the file must FAIL here: leaving a PREVIOUS mutant in
    # place made every broken fixture report ok, which is this file's own subject.
    rm -f "$f"
    if ! python3 - "$SEND_CMD" "$f" "$find" "$repl" <<'PYREV'
import sys
src, dst, find, repl = sys.argv[1:5]
s = open(src).read()
if find not in s:
    sys.exit("revert fixture not found in send_command: " + find)
open(dst, 'w').write(s.replace(find, repl, 1))
PYREV
    then
        echo "NOT OK [$name] revert fixture is stale - it no longer matches send_command"
        fail=$((fail + 1)); return
    fi
    if cmp -s "$SEND_CMD" "$f"; then
        echo "NOT OK [$name] REVERT DID NOT APPLY - assertion proves nothing"
        fail=$((fail + 1)); return
    fi
    if [[ -n "$(census_a "$f")" ]]; then
        echo "ok   [$name]"
    else
        echo "NOT OK [$name] census A reported CLEAN over the reverted site"
        fail=$((fail + 1))
    fi
}

revert 'revert-run-flags' 'FLAGS_ARGS="$(clj_str_args "$@")"' 'FLAGS_ARGS=" \"$1\""'
revert 'revert-discard-names' 'NAMES_VEC="[$(clj_str_args "${CARD_NAMES[@]}") ]"' 'NAMES_VEC="[\"$name\" ]"'
revert 'revert-tank-ice-name' '(let [ice-name \"$(clj_str "$ICE_NAME")\"' '(let [ice-name \"$ICE_NAME\"'
revert 'revert-create-game-title' '{:title \"$(clj_str "$TITLE")\"' '{:title \"$TITLE\"'
revert 'revert-create-game-side' ':side \"$(clj_str "$SIDE")\"' ':side \"$SIDE\"'
revert 'revert-select-deck' '(conn/select-deck! \"$(clj_str "$DECK_ID")\")' '(conn/select-deck! \"$DECK_ID\")'
revert 'revert-replay-save' '(state/save-replay! \"$(clj_str "$FILENAME")\")' '(state/save-replay! \"$FILENAME\")'
revert 'revert-fix-credits-amount' 'AMOUNT="\"$(clj_str "$AMOUNT")\""' 'AMOUNT="\"$AMOUNT\""'
revert 'revert-find-card-boundary-side' '(= ap \"$(clj_str "$1")\")' '(= ap \"$1\")'
revert 'revert-draw-to-card-name' '(ai-actions/draw-to-card! \"$(clj_str "$CARD_NAME")\")' '(ai-actions/draw-to-card! \"$CARD_NAME\")'
revert 'revert-multi-choose-names' 'ARGS_STR="$(clj_str_args "${CARD_NAMES[@]}")"' 'ARGS_STR=" \"$name\""'
revert 'revert-client-name-println' 'Autonomous loop launched for $(clj_str "$CLIENT_NAME")' 'Autonomous loop launched for ${CLIENT_NAME}'

echo "--- the change-arm KEY gate is witnessed by the ROUTE test, not here ---"
# What used to be here was a FALSE WITNESS, and a guest seat caught it: the
# assertion removed the gate and then called census_b with an expected count of
# 18, and `census_b send_command 18` prints the same 19-vs-18 diagnostic on the
# UNMUTATED file. It passed because I forced the count, not because census B saw
# anything. Census B cannot see that gate go: a shape gate is not an
# interpolation, so it is not in either census's subject.
#
# The gate's witness is clj_string_route_test.sh (`change-key-injection-*`, which
# goes red when the gate is replaced by `if false`) and the lein suite
# (`a-bare-keyword-argument-is-refused-rather-than-read-as-code`). What census B
# DID do is find the hole in the first place, which is a different job.
if grep -q 'change takes a state key' "$SEND_CMD"; then
    echo "ok   [change-key-gate-exists]"
else
    echo "NOT OK [change-key-gate-exists] the shape gate on change's \$KEY is gone;"
    echo "       its behaviour is pinned in clj_string_route_test.sh"
    fail=$((fail + 1))
fi

if ((fail)); then
    printf 'FAIL: %d clj-string sweep assertion(s)\n' "$fail"
    exit 1
fi
echo 'PASS: clj-string interpolation censuses'
