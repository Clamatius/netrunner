#!/usr/bin/env bash
# ai_eval_port_default_test.sh — ai-eval.sh's two argument shapes must DEFAULT
# the REPL port the same way (#307).
#
# What was wrong. The script has two branches that resolve a port when the
# caller omits one, eleven lines apart, and they disagreed:
#
#     --stdin:   REPL_PORT="${3:-7889}"                 # a literal
#     one-arg:   REPL_PORT="${CLIENT_1_PORT:-7889}"     # the configured value
#
# So one script honoured a configured CLIENT_1_PORT on one branch and ignored it
# on the other. On a workspace that moved its ports (the capability
# .env.example documents), `--stdin` dialled 7889 — either nothing, or ANOTHER
# workspace's runner REPL, which answers normally. That is the #291 family one
# level down, and it is the shape this repo keeps filing: a second copy of a
# rule that agrees with the first only on the default.
#
# HOW it tests. The port is interpolated into the bb expression the script
# builds, as `(java.net.Socket. "localhost" <PORT>)`, so a `bb` stub that
# echoes its argv shows which port the real script was about to dial. The
# assertion is over THAT, not over any message the script prints — a script
# that reported one port and dialled another would still fail here. (Both the
# repo's "misleading-output bug class" note and #309's sibling fix turn on that
# distinction: the launcher's readiness probe was satisfied by the WRONG repl's
# listener and still announced success.)
#
# WHY THE PIDFILE PATH IS REDIRECTED. ai-eval.sh reads, and on a pid that `ps`
# reports gone REMOVES, /tmp/ai-client-<name>.pid — a machine-wide path (#293)
# shared by every checkout. Pointed at `corp` or `runner` while a game is live,
# an unredirected run could delete a running repl's pidfile.
#
# An earlier version dodged that by only ever passing a made-up client name, and
# claimed in its comments that it therefore pinned ai-eval.sh's side-blindness.
# It did not. A panel seat reproduced the hole: ADD a corp arm to ai-eval.sh and
# all 11 assertions stay green, because no assertion ever passed the name `corp`.
# The claim was on the record in four places and was false in all four — a
# fixture monoculture, where every input shares the one value that cannot
# distinguish the arms. So the three /tmp paths are now redirected into the
# fixture tree (count PINNED, as in the #309 guard) and the side-blindness
# assertions pass the LITERAL names `corp` and `runner`, which is the only way to
# witness an arm that keys on them.
#
# WHY THE SCRIPT IS RUN FROM A FIXTURE ROOT and not in place. ai-eval.sh sources
# load-env.sh, and load-env.sh applies the .env AFTER the caller's environment is
# already set — so the checkout's .env wins over an env var this test passes in.
# The first version ran the script in place and was green only because this
# machine has no .env anywhere; with one copied from .env.example exactly as its
# own header instructs, three assertions went red against a CORRECT script, which
# would have broken `make verify` for any developer who followed that
# instruction. (Panel MAJOR, round 1, reproduced by two vendors and here before
# fixing.) So ai-eval.sh and load-env.sh are copied verbatim into a tree whose
# root has no .env.
#
# That "applies AFTER the caller" is a property of the loader, not of one
# implementation of it, which is why it is stated that way. This paragraph used
# to quote the mechanism as `export $(... $ROOT_DIR/.env ...)` — a one-liner
# #310 deleted and replaced with a per-line parser that assigns through
# `declare -x`. The hazard survived the rewrite; the quote did not, and a comment
# citing deleted code in the file that exists to BE this hazard's record is worth
# correcting. (Caught by grepping the test tree for descriptions of load-env.sh;
# a diff cannot show it, because neither side of the merge touched these lines.)
#
# There is no "the fixture root has no .env" assertion. $E is a fresh
# `mktemp -d`, so such a check cannot fail, and an earlier version of it was
# rightly called a tautology dressed as a guard. The witness that this fixture
# controls which .env the script sees is `fixture-root-env-is-the-one-honoured`
# below: if load-env.sh resolved ROOT_DIR to the checkout rather than $E,
# planting $E/.env would change nothing and that assertion would go red.
#
# WHY A `ps` STUB, AND WHY NO PIDFILE BOOKKEEPING. ai-eval.sh reads
# /tmp/ai-client-<name>.pid — a machine-wide path (#293) shared by every
# checkout — and DELETES it when `ps` says that pid is gone (ai-eval.sh:118-122;
# deletion needs `ps` to exit exactly 1). This test must pass the literal names
# `corp` and `runner`, because an arm keying on them is invisible to any other
# name, so it must not be able to delete a live repl's pidfile.
#
# The stub makes that structural: with `ps` exiting 0, the `rm` arm is
# unreachable however the path is spelled, and the only remaining access is a
# read. `stale-pidfile-survives-a-run` witnesses the property directly rather
# than asserting anything about the script's text.
#
# Two earlier attempts at this are worth not repeating. Passing only a made-up
# client name was safe but WORTHLESS: the test claimed to pin ai-eval.sh's
# side-blindness while never passing `corp`, so adding a corp arm left all 11
# assertions green — a fixture monoculture, where every input shared the one
# value that cannot distinguish the arms, and the false claim had reached four
# places (panel MAJOR, round 2). Replacing it with a `sed` rewrite of the
# literal paths plus a pinned count of them was then a DETECTOR where a
# structural fix existed, and round 3 reproduced two holes in it: a `/tmp/ai-client-`
# occurrence inside a comment satisfied the count while a real operation had
# moved out of reach, and a legitimate TMPDIR containing the sed delimiter `#`
# broke the rewrite and turned nine assertions red against unchanged production
# code. Counting a script's text is not a safety property; making the dangerous
# branch unreachable is.
#
# The files are copied with `cp` — the real current ones, not a transcription —
# so that what runs here is what ships. The last case plants a .env in the
# FIXTURE root and shows it is honoured, which is what proves the isolation is
# real and load-bearing: the .env this test is exposed to is one it controls.
#
# (A second, near-verbatim copy of the fixture-root paragraph stood here and has
# been removed. Besides repeating the stale `export $(...)` quote corrected
# above, it claimed the .env absence "is ASSERTED rather than assumed" — the
# exact opposite of the note further up, which records that there is deliberately
# no such assertion because $E is a fresh `mktemp -d` and the check would be a
# tautology dressed as a guard. Two paragraphs, one file, contradicting each
# other about whether an assertion exists.)
#
# SIDE-BLINDNESS IS NOW COVERED, and this paragraph is the record of the change.
# It used to say the opposite: that `--stdin corp` still defaulted to the
# RUNNER's port, that this was filed as #312 rather than fixed, and that "the
# assertion below pins today's behaviour so the fix for it has to come here and
# change this file on purpose". That is what happened — #312 was fixed, and this
# file is where it had to be said.
#
# The fix did NOT add the second copy of the mapping that #312 priced as its
# cost. The rule is one function, `client_default_port`, in load-env.sh — the
# file both doors already source and the one that already owns these port
# values — and `start-ai-client-repl.sh`'s own `case` arm was replaced by a call
# to it. So the side->port decision sites went 2 -> 1, not 2 -> 3, and the
# reasoning is on the record in load-env.sh beside the function.
#
# Two consequences for the assertions below:
#   - `corp-name-selects-client2-port` is the flipped one. It was
#     `corp-name-does-not-yet-move-the-default` expecting CLIENT_1_PORT.
#   - `runner-name-...` is UNCHANGED and is still load-bearing: it is what
#     distinguishes "corp is special" from "the name now swings everything".
#
# Still NOT covered, and still true: #313. `client_default_port`'s catch-all
# gives the runner's port to every name that is not `corp`, so a typo
# (`--stdin corpp`) resolves to 7889. The fix is not a name allowlist — see the
# KNOWN GAP note in load-env.sh — and it is blocked on #293.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
REAL_AI_EVAL="$REAL_ROOT/dev/ai-eval.sh"

fails=0
pass() { echo "ok   [$1]"; }
fail() { echo "FAIL [$1]: $2"; fails=$((fails + 1)); }
assert_eq() {
    if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected '$3', got '$2'"; fi
}

E="$(mktemp -d "${TMPDIR:-/tmp}/nr-307.XXXXXX")"
trap 'rm -rf "$E"' EXIT
mkdir -p "$E/bin" "$E/dev"

# The real current scripts, copied verbatim so load-env.sh resolves ROOT_DIR
# to $E. Nothing is rewritten: see the header on why counting the script's
# text was the wrong way to make this safe.
cp "$REAL_AI_EVAL" "$E/dev/ai-eval.sh"
cp "$REAL_ROOT/dev/load-env.sh" "$E/dev/load-env.sh"
chmod +x "$E/dev/ai-eval.sh" "$E/dev/load-env.sh"
AI_EVAL="$E/dev/ai-eval.sh"
# The stub stands in for the real bb: it prints the argv ai-eval.sh handed it,
# which contains the interpolated socket expression, and succeeds. ai-eval.sh
# prefers bb whenever `command -v bb` finds one, so putting this first on PATH
# is what selects the branch under test.
cat > "$E/bin/bb" <<'STUB'
#!/bin/bash
printf '%s\n' "$*"
exit 0
STUB
# `ps` always reports the process ALIVE, which makes ai-eval.sh's pidfile `rm`
# arm unreachable (it needs exit 1). This is what makes the literal `corp` and
# `runner` names below safe to pass; `stale-pidfile-survives-a-run` proves it.
cat > "$E/bin/ps" <<'STUB'
#!/bin/bash
exit 0
STUB
chmod +x "$E/bin/bb" "$E/bin/ps"

NAME="porttest-$$"

# dialled_port ENV... -- ARGS...  — the port the script was about to connect to.
dialled_port() {
    local -a envs=()
    while [ "${1:-}" != "--" ]; do envs+=("$1"); shift; done
    shift
    echo '(+ 1 1)' | env "${envs[@]}" PATH="$E/bin:$PATH" "$AI_EVAL" "$@" 2>&1 \
        | sed -n 's/.*localhost" \([0-9]*\).*/\1/p' | head -1
}

echo "=== #307: ai-eval.sh port defaults agree across argument shapes ==="

# The safety property the literal side names rest on, witnessed rather than
# argued: a pidfile naming a dead pid must SURVIVE a run. If the `ps` stub ever
# stops being reached, this goes red here, on a pidfile this test created
# itself, instead of silently deleting a real repl's during `make verify`.
STALE_PID="/tmp/ai-client-$NAME.pid"
printf '99999999\n' > "$STALE_PID"
dialled_port -i -- --stdin "$NAME" > /dev/null 2>&1
if [ -f "$STALE_PID" ]; then
    pass "stale-pidfile-survives-a-run"
else
    fail "stale-pidfile-survives-a-run" "ai-eval.sh deleted $STALE_PID; the ps stub is not being reached, so passing the literal side names below is NOT safe"
fi
rm -f "$STALE_PID"

# THE BUG: --stdin ignored a configured CLIENT_1_PORT.
assert_eq "stdin-default-honours-client1-port" \
    "$(dialled_port CLIENT_1_PORT=18889 -- --stdin "$NAME")" "18889"

# The legacy one-arg branch always honoured it. Pin it, then pin that the two
# branches now give the SAME answer — the agreement is the point, and two
# separate absolute assertions can both drift to a new shared wrong value
# without either of them noticing.
assert_eq "legacy-one-arg-honours-client1-port" \
    "$(dialled_port CLIENT_1_PORT=18889 -- '(+ 1 1)')" "18889"
assert_eq "both-shapes-agree-on-configured-port" \
    "$(dialled_port CLIENT_1_PORT=18889 -- --stdin "$NAME")" \
    "$(dialled_port CLIENT_1_PORT=18889 -- '(+ 1 1)')"

# With nothing configured, both shapes land on 7889. Note WHERE that comes from:
# load-env.sh exports CLIENT_1_PORT=7889 whenever the caller left it unset, so
# these two measure that export, NOT the `:-7889` literal inside the fix. A seat
# mutated the inner literal to 9999 and every assertion stayed green, which is
# true and worth knowing: the literal is defence-in-depth mirroring the legacy
# branch at ai-eval.sh:94, not the live default. What is pinned here is the
# EFFECTIVE fallback a caller gets, which is the thing callers depend on.
assert_eq "stdin-falls-back-to-7889" \
    "$(dialled_port -i -- --stdin "$NAME")" "7889"
assert_eq "both-shapes-agree-on-fallback" \
    "$(dialled_port -i -- --stdin "$NAME")" \
    "$(dialled_port -i -- '(+ 1 1)')"

# An explicit port still beats the default, and is not overwritten by the env.
assert_eq "stdin-explicit-port-wins" \
    "$(dialled_port CLIENT_1_PORT=18889 -- --stdin "$NAME" 7777)" "7777"
assert_eq "three-arg-explicit-port-wins" \
    "$(dialled_port CLIENT_1_PORT=18889 -- "$NAME" 7777 '(+ 1 1)')" "7777"

# #312: the client NAME selects the side's port. Pinned with the LITERAL side
# names, which is the only thing that can witness an arm keying on them — an
# earlier version of this test passed only a made-up name and therefore stayed
# green when a corp arm was ADDED, a fixture monoculture that reached four
# comments as a false claim. Both names are safe because the `ps` stub makes the
# pidfile `rm` arm unreachable, witnessed by stale-pidfile-survives-a-run above.
#
# The pair is the point. `corp` alone cannot distinguish "corp is special" from
# "the name now swings everything", and `runner` alone cannot distinguish "the
# catch-all is right" from "the name is still never consulted".
assert_eq "corp-name-selects-client2-port" \
    "$(dialled_port CLIENT_1_PORT=18889 CLIENT_2_PORT=18890 -- --stdin corp)" "18890"
assert_eq "runner-name-does-not-move-the-default" \
    "$(dialled_port CLIENT_1_PORT=18889 CLIENT_2_PORT=18890 -- --stdin runner)" "18889"

# The bug's SIGNATURE, mirroring the assertion of the same name in
# client_repl_port_default_test.sh: a .env that moves only the runner must not
# move corp. Two absolute assertions can drift to a shared wrong value; this one
# fails if corp ever starts reading CLIENT_1_PORT again, whatever its value.
assert_eq "corp-is-unmoved-by-a-runner-only-env" \
    "$(dialled_port CLIENT_1_PORT=18889 -- --stdin corp)" "7890"

# With nothing configured at all, each side lands on its own built-in default.
# This is the shape an operator actually types (`--stdin corp`, no port, no
# .env), and it is the one that silently dialled the runner before #312.
assert_eq "corp-with-nothing-configured-gets-7890" \
    "$(dialled_port -i -- --stdin corp)" "7890"
assert_eq "runner-with-nothing-configured-gets-7889" \
    "$(dialled_port -i -- --stdin runner)" "7889"

# An explicit port still beats the NAME, not just the environment — the
# three-argument shape is how start-ai-both.sh and send_command call this.
assert_eq "explicit-port-beats-the-corp-name" \
    "$(dialled_port CLIENT_1_PORT=18889 CLIENT_2_PORT=18890 -- --stdin corp 7777)" "7777"

# The isolation is load-bearing, not decorative: a .env in the FIXTURE root is
# read and honoured, which is the same mechanism that let the checkout's .env
# break the first version of this test. If this goes red, the copy above has
# stopped controlling which .env the script sees.
printf 'CLIENT_1_PORT=19999\n' > "$E/.env"
assert_eq "fixture-root-env-is-the-one-honoured" \
    "$(dialled_port -i -- --stdin "$NAME")" "19999"
rm -f "$E/.env"
assert_eq "removing-fixture-env-restores-the-default" \
    "$(dialled_port -i -- --stdin "$NAME")" "7889"

echo
if [ "$fails" -eq 0 ]; then
    echo "✅ ai-eval port default guards passed."
    exit 0
else
    echo "❌ $fails assertion(s) failed."
    exit 1
fi
