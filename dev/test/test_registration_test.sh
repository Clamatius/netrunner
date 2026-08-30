#!/bin/bash
# A test namespace that is not registered in `make test` does not fail — it
# silently stops running, and the suite still reports green.
#
# That is the half of #180 the issue itself called worse than the conflict. The
# per-line split fixed how OFTEN two branches collide on the `lein test` list;
# it could not fix what happens when someone resolves that collision badly. Drop
# a line (with its trailing backslash) while untangling a conflict and `lein
# test` runs 39 namespaces instead of 40, prints "0 failures", and a regression
# in the dropped namespace ships behind a green tick.
#
# The new form does make ONE botch class loud: a lost mid-list backslash turns
# the next TAB line into its own shell command, so make exits non-zero with
# "command not found". A cleanly dropped whole line stays silent. This closes
# that remaining hole.
#
# WHAT IS CHECKED. Three properties of the list `make -n test` actually expands
# — the expansion, not the Makefile text, because the expansion is what runs:
#
#   1. Every test namespace WE own is registered.
#   2. No namespace is registered twice (a duplicate is a botched resolution
#      that happens to still be green, and it hides a drop elsewhere).
#   3. The list is sorted, which is what keeps concurrent appends landing at
#      different insertion points rather than all colliding at the end.
#
# OWNERSHIP, and why this deliberately does not police everything. The repo is a
# fork: test/clj is mostly upstream jinteki (~50 game.core.* / game.cards.* /
# web.* namespaces) that we intentionally do NOT run. So "every *_test.clj on
# disk" is the wrong set and would fail permanently. Two directories are
# unambiguously ours:
#
#   dev/test/*_test.clj            — the whole directory is ours
#   test/clj/game/ai_*_test.clj    — the ai_ prefix marks ours
#
# test/clj/web is MIXED (replay_share and lobby_disconnect are ours from #89 and
# #76; deck/nrdb/stats/user/replay_restore arrived in upstream merges), and
# nothing in the filename distinguishes them. Rather than guess, this guard does
# not cover that directory — a new ours-test added there is still registered by
# hand. Stated so the next reader knows the boundary instead of assuming the
# tick means more than it does.
#
# Namespaces are read from each file's own (ns ...) form rather than derived
# from its path, so a file whose name and namespace disagree cannot slip past.

set -u
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
PASS=0; FAIL=0

ok()   { echo "ok   [$1]"; PASS=$((PASS+1)); }
nope() { echo "NOT OK [$1]"; [ -n "${2:-}" ] && echo "$2" | sed 's/^/       /'; FAIL=$((FAIL+1)); }
die()  { echo "NOT OK [fixture] $1"; exit 1; }

# Namespaces excluded from `make test` on purpose. ai-behavioral-test is slow and
# has its own target (`make test-behavioral`, Makefile). Anything added here is a
# deliberate exemption and should say why.
EXCLUDED="ai-behavioral-test"

WORK="$(mktemp -d)" || die "mktemp failed"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------
# The comparison logic, factored so it can be driven against synthetic input.
# registered_file / owned_file are sorted namespace-per-line lists.
# Prints the unregistered namespaces; exits 1 if any.
# ---------------------------------------------------------------------------
unregistered() {
  comm -23 "$2" "$1"
}

# Expand what `make test` will really run.
expand_registered() {
  ( cd "$REPO_ROOT" && make -n test 2>/dev/null ) \
    | sed '1d' | tr -d '\\' | tr -s ' \t\n' '\n' \
    | grep -v '^$' | sed '1,2d'
}

# Read the (ns ...) name out of a Clojure test file.
ns_of() { grep -m1 -E '^\(ns ' "$1" | sed -E 's/^\(ns +([a-zA-Z0-9._!?*<>=+-]+).*/\1/'; }

# ---------------------------------------------------------------------------
# Real data
# ---------------------------------------------------------------------------
expand_registered | sort > "$WORK/registered.txt"
[ -s "$WORK/registered.txt" ] || die "could not expand 'make -n test' (got nothing)"

: > "$WORK/owned.txt"
for f in "$REPO_ROOT"/dev/test/*_test.clj "$REPO_ROOT"/test/clj/game/ai_*_test.clj; do
  [ -e "$f" ] || continue
  ns="$(ns_of "$f")"
  [ -n "$ns" ] || die "no (ns ...) form in $f"
  echo "$ns"
done | sort -u > "$WORK/owned.txt"
[ -s "$WORK/owned.txt" ] || die "found no owned test namespaces — globs are wrong"

# Drop the deliberate exemptions.
for ex in $EXCLUDED; do
  grep -vx "$ex" "$WORK/owned.txt" > "$WORK/owned.tmp" && mv "$WORK/owned.tmp" "$WORK/owned.txt"
done

echo "--- every test namespace we own is registered in \`make test\` ---"
MISSING="$(unregistered "$WORK/registered.txt" "$WORK/owned.txt")"
if [ -z "$MISSING" ]; then
  ok "all-owned-namespaces-registered"
else
  nope "all-owned-namespaces-registered" \
"These test namespaces exist but \`make test\` never runs them.
They will not fail — they will silently not run:
$MISSING
Add each to the lein test list in the Makefile (alphabetical position), or, if
the omission is deliberate, add it to EXCLUDED in this script with a reason."
fi

echo "--- the registered list has no duplicates ---"
DUPES="$(sort "$WORK/registered.txt" | uniq -d)"
if [ -z "$DUPES" ]; then
  ok "no-duplicate-registrations"
else
  nope "no-duplicate-registrations" "registered more than once:
$DUPES"
fi

echo "--- the registered list is sorted (keeps concurrent appends apart) ---"
if expand_registered | LC_ALL=C sort -c 2>/dev/null; then
  ok "registered-list-is-sorted"
else
  nope "registered-list-is-sorted" \
"The lein test list is no longer in sorted order. Sorted order is what makes two
branches adding a test insert at different points instead of colliding at the
end of the list. Re-sort it (LC_ALL=C)."
fi

# ---------------------------------------------------------------------------
# Mutation tests: a guard that cannot go red proves nothing. Drive the SAME
# comparison against synthetic input where the defect is known to be present.
# ---------------------------------------------------------------------------
# Driven against SYNTHETIC lists, not the real ones. Deriving the mutation from
# live data means that when the real check is already red, this one fails too
# with a confusing second message about a namespace nobody dropped — noise on
# top of the finding that matters. Synthetic input tests the comparison and
# nothing else, so it says the same thing whatever state the Makefile is in.
echo "--- the check itself detects a dropped namespace (mutation test) ---"
printf 'a-test\nc-test\n'          | sort > "$WORK/mut-registered.txt"
printf 'a-test\nb-test\nc-test\n'  | sort > "$WORK/mut-owned.txt"
DROPPED="$(unregistered "$WORK/mut-registered.txt" "$WORK/mut-owned.txt")"
if [ "$DROPPED" = "b-test" ]; then
  ok "mutation-dropped-namespace-is-caught"
else
  nope "mutation-dropped-namespace-is-caught" \
    "an owned namespace absent from the registered list should have been reported as 'b-test', got: ${DROPPED:-<nothing>}"
fi

echo "--- a complete list reports nothing missing (mutation test, negative) ---"
if [ -z "$(unregistered "$WORK/mut-owned.txt" "$WORK/mut-owned.txt")" ]; then
  ok "mutation-complete-list-is-clean"
else
  nope "mutation-complete-list-is-clean" "identical lists should report no missing namespaces"
fi

echo "--- the check itself detects an unsorted list (mutation test) ---"
if printf 'b-test\na-test\n' | LC_ALL=C sort -c 2>/dev/null; then
  nope "mutation-unsorted-is-caught" "sort -c accepted an out-of-order list"
else
  ok "mutation-unsorted-is-caught"
fi

echo "--- the check itself detects a duplicate (mutation test) ---"
if [ -n "$(printf 'a-test\na-test\n' | uniq -d)" ]; then
  ok "mutation-duplicate-is-caught"
else
  nope "mutation-duplicate-is-caught" "uniq -d did not report a duplicated entry"
fi

echo
echo "Passed: $PASS   Failed: $FAIL"
if [ "$FAIL" -eq 0 ]; then
  echo "✅ test registration guard: all assertions passed"
  exit 0
else
  echo "❌ test registration guard: $FAIL assertion(s) failed"
  exit 1
fi
