#!/bin/bash
# A test namespace that is not registered in `make test` does not fail — it
# silently stops running, and the suite still reports green.
#
# That is the half of #180 the issue itself called worse than the conflict. The
# per-line split fixed how OFTEN two branches collide on the `lein test` list;
# it could not fix what happens when someone resolves that collision badly. Drop
# a line (with its trailing backslash) while untangling a conflict and `lein
# test` runs one fewer namespace, prints "0 failures", and a regression in the
# dropped namespace ships behind a green tick.
#
# The new list form does make ONE botch class loud: a lost mid-list backslash
# turns the next TAB line into its own shell command, so make exits non-zero with
# "command not found". A cleanly dropped whole line stays silent. This closes it.
#
# WHAT IS CHECKED, against the list `make -n test` actually EXPANDS (the
# expansion, not the Makefile text, because the expansion is what runs):
#
#   1. Every test namespace WE own is registered.
#   2. No namespace is registered twice — a duplicate is a botched resolution
#      that is still green, and it can hide a drop elsewhere.
#   3. The list is sorted, which keeps concurrent appends landing at different
#      insertion points instead of colliding at the end.
#
# The `test-shell` recipe is the same hand-maintained-list trap in the same file,
# and one of its lines is this guard's own on-switch. A check for that was tried
# here and removed as unsound; see the note further down and #185.
#
# OWNERSHIP. This repo is a fork: test/clj is mostly upstream jinteki (~50
# game.core.* / game.cards.* / web.* namespaces we intentionally never run), so
# "every *_test.clj on disk" is the wrong set. An earlier version of this guard
# guessed ownership from paths (dev/test/* plus an ai_ prefix). That was wrong in
# the direction that matters: it was green while `web.replay-share-test` was
# deleted from the Makefile, and it never noticed that
# test/clj/game/core/turns_test.clj — added by our own a8f64dda9, the turn-boundary
# fix — had not run since 2026-06-16.
#
# The reliable signal is git, not the path. A test file is OURS if it does not
# exist in upstream/master, and it is RUNNABLE if it actually defines tests:
#
#     owned(f)  =  absent from upstream/master  AND  contains a (deftest ...)
#
# The deftest half is what correctly excludes dev/src/clj/{full_game,game_command}
# _test.clj — 2271 lines named *_test.clj with zero deftests (see #182).
#
# If upstream/master is not fetched, EVERY file classifies as ours and this guard
# would flood with false reds. That case dies loudly rather than guessing.

set -u
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
UPSTREAM_REF="upstream/master"
PASS=0; FAIL=0

ok()   { echo "ok   [$1]"; PASS=$((PASS+1)); }
nope() { echo "NOT OK [$1]"; [ -n "${2:-}" ] && echo "$2" | sed 's/^/       /'; FAIL=$((FAIL+1)); }
# stderr, so that a fixture failure inside a command substitution can never be
# captured as data.
die()  { echo "NOT OK [fixture] $1" >&2; exit 1; }

# Namespaces excluded from `make test` on purpose. ai-behavioral-test is slow and
# has its own target (`make test-behavioral`). Anything added here is a deliberate
# exemption and should say why.
EXCLUDED="ai-behavioral-test"

WORK="$(mktemp -d)" || die "mktemp failed"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# Expand what `make test` will really run, from the given repo dir.
# Anchored on `lein test` rather than counting header lines: `sed '1d'` assumed
# exactly one @echo banner, so adding or removing one silently corrupted the list.
expand_registered() {
  ( cd "${1:-$REPO_ROOT}" && make -n test 2>/dev/null ) \
    | sed -n '/^lein test/,$p' \
    | tr -d '\\' | tr -s ' \t\n' '\n' \
    | grep -v '^$' | sed '1,2d'
}

# Namespaces that are OURS and runnable, written one per line to $1.
#
# The scan reads from a FILE, not a pipe. As the head of a pipeline the loop runs
# in a subshell, where `die`'s exit kills only that subshell: the run continues,
# every remaining file goes unscanned (masking real gaps in the same run), and
# die's message lands in the captured output as if it were a namespace.
owned_namespaces() {
  local out="$1" f ns
  cd "$REPO_ROOT" || die "cannot cd to $REPO_ROOT"
  git rev-parse --verify --quiet "$UPSTREAM_REF" >/dev/null \
    || die "$UPSTREAM_REF is not fetched — cannot tell our tests from upstream's.
       Run: git fetch upstream
       (Without it every test file looks like ours and this guard floods.)"

  git ls-files '*_test.clj' > "$WORK/testfiles.txt" || die "git ls-files failed"
  : > "$out"
  while IFS= read -r f; do
    # upstream's file -> upstream's problem
    git cat-file -e "$UPSTREAM_REF:$f" 2>/dev/null && continue
    # ours, but defines no tests (harness scripts, #182) -> nothing to register.
    # NOT anchored at column 0: this repo already wraps deftests in a shared-setup
    # `(let [...] (deftest a ...) (deftest b ...))` in three card-test files, and an
    # anchored match would read such a file as "defines no tests" and silently
    # exempt it from ever needing registration — the same invisibility this guard
    # exists to remove, moved down into ownership detection.
    grep -qE '^[[:space:]]*\(deftest[[:space:](]' "$f" || continue
    ns="$(ns_of "$f")" || die "no usable (ns ...) form in $f"
    printf '%s\n' "$ns" >> "$out"
  done < "$WORK/testfiles.txt"
}

# Read the (ns ...) name from a Clojure file, and validate it. `sed` echoes its
# input unchanged when the pattern does not match, so without the shape check a
# line like `(ns ^:integration foo-test)` would be returned verbatim as a
# "namespace".
ns_of() {
  local line ns
  line="$(grep -m1 -E '^\(ns[[:space:]]' "$1")" || return 1
  ns="$(printf '%s' "$line" | sed -E 's/^\(ns[[:space:]]+([a-zA-Z0-9._!?*<>=+-]+).*/\1/')"
  case "$ns" in
    ''|*'('*|*' '*|*'^'*) return 1 ;;
  esac
  printf '%s\n' "$ns"
}

# ---------------------------------------------------------------------------
# Real data
# ---------------------------------------------------------------------------
expand_registered > "$WORK/registered-raw.txt"
[ -s "$WORK/registered-raw.txt" ] || die "could not expand 'make -n test' (got nothing)"
sort "$WORK/registered-raw.txt" > "$WORK/registered.txt"

UPSTREAM_DATE="$(cd "$REPO_ROOT" && git log -1 --format=%cs "$UPSTREAM_REF" 2>/dev/null || echo unknown)"
owned_namespaces "$WORK/owned-raw.txt"
sort -u "$WORK/owned-raw.txt" > "$WORK/owned.txt"
[ -s "$WORK/owned.txt" ] || die "found no owned test namespaces — ownership detection is broken"

for ex in $EXCLUDED; do
  grep -vx "$ex" "$WORK/owned.txt" > "$WORK/owned.tmp" || true
  mv "$WORK/owned.tmp" "$WORK/owned.txt"
done

echo "--- every test namespace we own is registered in \`make test\` ---"
MISSING="$(comm -23 "$WORK/owned.txt" "$WORK/registered.txt")"
if [ -z "$MISSING" ]; then
  ok "all-owned-namespaces-registered"
else
  nope "all-owned-namespaces-registered" \
"These test namespaces are ours (absent from $UPSTREAM_REF, and they define tests)
but \`make test\` never runs them. They will not fail — they will silently not run:
$MISSING
Add each to the lein test list in the Makefile (alphabetical position), or, if the
omission is deliberate, add it to EXCLUDED in this script with a reason.

BEFORE believing this: your $UPSTREAM_REF is dated $UPSTREAM_DATE.
Ownership is 'absent from $UPSTREAM_REF', so a STALE ref misreports upstream's own
new test files as ours — and upstream lands here in big merge bursts, which is
exactly when this check is least trusted and most likely to be wrong. If any name
above looks like upstream's (game.core.*, game.cards.*, jinteki.*, web.* that is
not ours), re-run after:  git fetch upstream"
fi

echo "--- the registered list has no duplicates ---"
DUPES="$(uniq -d < "$WORK/registered.txt")"
if [ -z "$DUPES" ]; then
  ok "no-duplicate-registrations"
else
  nope "no-duplicate-registrations" "registered more than once:
$DUPES"
fi

echo "--- the registered list is sorted (keeps concurrent appends apart) ---"
if LC_ALL=C sort -c "$WORK/registered-raw.txt" 2>/dev/null; then
  ok "registered-list-is-sorted"
else
  nope "registered-list-is-sorted" \
"The lein test list is no longer in sorted order. Sorted order is what makes two
branches adding a test insert at different points instead of colliding at the end
of the list. Re-sort it (LC_ALL=C)."
fi

# NOTE: a check that every dev/test/*_test.sh is wired into `make test-shell`
# was tried here and REMOVED, deliberately — see #185.
#
# It matched the script's basename against the text of the expanded recipe, which
# is not proof that anything runs: commenting a line out (`#@./dev/test/foo.sh`)
# leaves the basename in the text, so the check stayed green over a fully
# disabled test. That is the same "text that looks like a signal is not the
# signal" defect this guard was rewritten to remove one layer down, reintroduced
# in its own new check.
#
# Doing it properly means parsing the recipe into real invocations rather than
# grepping it, which is a second parser to get wrong. Rather than patch a
# twice-defective addition a third time inside a guard whose core check has to
# stay trustworthy, it is filed as its own piece of work.

# ---------------------------------------------------------------------------
# Mutation tests. A guard that cannot go red proves nothing.
#
# Driven against a FIXTURE MAKEFILE rather than live data: deriving mutations
# from the real list made them fail a confusing second time whenever the real
# check was already red. Using a fixture Makefile (rather than a bare namespace
# list) keeps that isolation AND puts expand_registered — the bespoke parser
# where the real fragility lives — under test.
# ---------------------------------------------------------------------------
make_fixture() { # $1=dir  $2=echo-banner-lines
  mkdir -p "$1"
  { echo "test:"
    [ "$2" -ge 1 ] && printf '\t@echo "Running unit tests..."\n'
    [ "$2" -ge 2 ] && printf '\t@echo "second banner"\n'
    printf '\tlein test \\\n\t  a-test \\\n\t  b-test \\\n\t  c-test\n'
  } > "$1/Makefile"
}

echo "--- expand_registered parses a fixture Makefile (mutation test) ---"
make_fixture "$WORK/fx1" 1
GOT="$(expand_registered "$WORK/fx1" | tr '\n' ' ')"
if [ "$GOT" = "a-test b-test c-test " ]; then
  ok "mutation-expand-parses-list"
else
  nope "mutation-expand-parses-list" "expected 'a-test b-test c-test', got: $GOT"
fi

echo "--- expand_registered survives a changed @echo banner (mutation test) ---"
make_fixture "$WORK/fx0" 0
make_fixture "$WORK/fx2" 2
G0="$(expand_registered "$WORK/fx0" | tr '\n' ' ')"
G2="$(expand_registered "$WORK/fx2" | tr '\n' ' ')"
if [ "$G0" = "a-test b-test c-test " ] && [ "$G2" = "a-test b-test c-test " ]; then
  ok "mutation-expand-immune-to-banner-count"
else
  nope "mutation-expand-immune-to-banner-count" \
    "banner count changed the parsed list — 0 banners: '$G0' / 2 banners: '$G2'"
fi

echo "--- a dropped namespace is reported (mutation test) ---"
printf 'a-test\nc-test\n'         | sort > "$WORK/mut-registered.txt"
printf 'a-test\nb-test\nc-test\n' | sort > "$WORK/mut-owned.txt"
DROPPED="$(comm -23 "$WORK/mut-owned.txt" "$WORK/mut-registered.txt")"
if [ "$DROPPED" = "b-test" ]; then
  ok "mutation-dropped-namespace-is-caught"
else
  nope "mutation-dropped-namespace-is-caught" \
    "an owned namespace absent from the registered list should be reported as 'b-test', got: ${DROPPED:-<nothing>}"
fi

echo "--- a complete list reports nothing missing (mutation test, negative) ---"
if [ -z "$(comm -23 "$WORK/mut-owned.txt" "$WORK/mut-owned.txt")" ]; then
  ok "mutation-complete-list-is-clean"
else
  nope "mutation-complete-list-is-clean" "identical lists should report no missing namespaces"
fi

echo "--- ns_of rejects a line it cannot parse (mutation test) ---"
printf '(ns ^:integration weird-test)\n' > "$WORK/weird.clj"
printf '(ns plain-good-test)\n'          > "$WORK/good.clj"
if ! ns_of "$WORK/weird.clj" >/dev/null 2>&1 && [ "$(ns_of "$WORK/good.clj")" = "plain-good-test" ]; then
  ok "mutation-ns_of-rejects-unparseable"
else
  nope "mutation-ns_of-rejects-unparseable" \
    "ns_of should fail on '(ns ^:integration ...)' and succeed on a plain ns form"
fi

echo "--- the unsorted and duplicate checks detect their defects (mutation test) ---"
if printf 'b-test\na-test\n' | LC_ALL=C sort -c 2>/dev/null; then
  nope "mutation-unsorted-and-duplicate-caught" "sort -c accepted an out-of-order list"
elif [ -z "$(printf 'a-test\na-test\n' | uniq -d)" ]; then
  nope "mutation-unsorted-and-duplicate-caught" "uniq -d did not report a duplicated entry"
else
  ok "mutation-unsorted-and-duplicate-caught"
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
