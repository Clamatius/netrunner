#!/usr/bin/env bash
# #251 was "a padded digit string reaches a bare Clojure literal", and it was
# found and fixed THREE times in one change: once for the index arms, once for
# the counts, and once for `change`/`fix-credits` - whose guard is spelled
# `^-?[0-9]+$`, so a sweep anchored on `^[0-9]+$` went blind to them. The same
# blindness hid `advance`, which matches its count with a GLOB.
#
# So the guard here is not "are the known arms fixed" (choose_label_route_test
# pins that, per arm). It is: is the numeric-argument shape still spelled in
# exactly ONE place? A new arm that rolls its own digit check fails here, at the
# commit that adds it, instead of shipping #251 again under a fourth spelling.
#
# What it does NOT prove: that the gate is CORRECT (the route test's padding and
# refusal assertions do that), or that an arm passing a number through some
# shape this sweep cannot recognise - `${x//[!0-9]/}`, a `printf %d`, a bare
# `-gt` on unvalidated input - is caught. It is a spelling census, not a proof.
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

# The gate block is delimited by its own markers rather than by line numbers, so
# editing the file above it does not silently move the window off the block.
GATE_OPEN='# >>> NUMERIC GATE >>>'
GATE_CLOSE='# <<< NUMERIC GATE <<<'

# Print every line that spells a numeric SHAPE and is not allowed to.
# Allowed: inside the gate block; delegating to the gate on the same line; or
# carrying the NUMERIC-GATE-EXEMPT marker, which must be on the line itself.
violations() {
    local file="$1"
    awk '
        index($0, gate_open) { in_gate = 1 }
        index($0, gate_close) { in_gate = 0; next }
        in_gate { next }
        # A comment-only line is prose, not code.
        /^[[:space:]]*#/ { next }
        # Strip a trailing comment before looking for the marker, but remember it.
        {
            marked = ($0 ~ /NUMERIC-GATE-EXEMPT/)
            code = $0
            sub(/#.*$/, "", code)
        }
        code !~ /\[[0-9]-[0-9]\]/ { next }
        marked { next }
        code ~ /(is_num|num_arg|num_arg_min|num_arg_signed|num_or|decimal_index)/ { next }
        { printf "%d: %s\n", NR, $0 }
        # An unclosed gate would exempt everything below the open sentinel and
        # report a CLEAN sweep - blind, not wrong. Make that a violation itself.
        END { if (in_gate) printf "%d: NUMERIC GATE BLOCK NEVER CLOSED - every line below the open sentinel was skipped\n", NR }
    ' gate_open="$GATE_OPEN" gate_close="$GATE_CLOSE" "$file"
}

echo "--- the numeric shape is spelled in exactly one place ---"
FOUND="$(violations "$SEND_CMD")"
check 'no-numeric-shape-outside-the-gate' "$FOUND" ''
if [[ -n "$FOUND" ]]; then
    echo "   Each line above validates a number without going through the gate."
    echo "   Route it through is_num / num_arg / num_or / num_arg_signed /"
    echo "   num_arg_min, or mark the line NUMERIC-GATE-EXEMPT and say why."
fi

# The gate block must actually be found; an awk window that matched nothing would
# report a clean sweep over the whole file.
check 'gate-block-open-sentinel-exists' "$(grep -cF "$GATE_OPEN" "$SEND_CMD")" '1'
check 'gate-block-close-sentinel-exists' "$(grep -cF "$GATE_CLOSE" "$SEND_CMD")" '1'

# --- Mutation tests. A sweep that cannot see is worse than no sweep: it reports
# --- clean. Each fixture below is a defect the sweep must catch.
TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-gate-sweep.XXXXXX")" || exit 1
[[ -n "$TMP" && -d "$TMP" ]] || { echo "FAIL: no temp dir"; exit 1; }
trap 'rm -rf "$TMP"' EXIT

mutant() {  # mutant NAME SED_EXPR
    local name="$1" expr="$2" f="$TMP/mutant"
    sed "$expr" "$SEND_CMD" > "$f"
    if cmp -s "$SEND_CMD" "$f"; then
        echo "NOT OK [$name] MUTATION DID NOT APPLY - assertion proves nothing"
        fail=$((fail + 1)); return
    fi
    if [[ -n "$(violations "$f")" ]]; then
        echo "ok   [$name]"
    else
        echo "NOT OK [$name] sweep reported CLEAN over the injected defect"
        fail=$((fail + 1))
    fi
}

echo "--- a new arm rolling its own digit check is caught (mutation tests) ---"
# The three spellings that actually hid a defect in #251, plus the marker being
# abused to silence a real arm.
mutant 'mutation-unsigned-regex-is-caught' \
    's|^    keep-hand)|    my-new-arm)\n        [[ "$X" =~ ^[0-9]+$ ]] \|\| error "nope"\n        execute "(f $X)"\n        ;;\n\n    keep-hand)|'
mutant 'mutation-signed-regex-is-caught' \
    's|^    keep-hand)|    my-new-arm)\n        [[ "$X" =~ ^-?[0-9]+$ ]] \|\| error "nope"\n        execute "(f $X)"\n        ;;\n\n    keep-hand)|'
mutant 'mutation-positive-regex-is-caught' \
    's|^    keep-hand)|    my-new-arm)\n        [[ "$X" =~ ^[1-9][0-9]*$ ]] \|\| error "nope"\n        execute "(f $X)"\n        ;;\n\n    keep-hand)|'
# The gate's own guts, moved OUT of the block, must be seen - otherwise deleting
# the block's closing brace would exempt the whole rest of the file.
# Losing either sentinel must not silently widen or erase the exempt window.
mutant 'mutation-lost-open-sentinel-is-caught' \
    's|# >>> NUMERIC GATE >>>|# (sentinel removed)|'
mutant 'mutation-lost-close-sentinel-is-caught' \
    's|# <<< NUMERIC GATE <<<|# (sentinel removed)|'

if ((fail)); then
    printf 'FAIL: %d numeric-gate sweep assertion(s)\n' "$fail"
    exit 1
fi
echo 'PASS: numeric-argument gate sweep'
