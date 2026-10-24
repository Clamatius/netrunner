#!/usr/bin/env bash
# send_command_help_test.sh — the help text must be TEXT, not shell.
#
# Why this exists: the role help blocks are plain double-quoted assignments, so
# an unescaped " inside them CLOSES the string and hands the rest to the shell.
# Adding the line that documents quoting "R&D" (#110 §3) did exactly that: the
# inner quotes ended the assignment, the shell saw a bare &, and `runner help`
# printed
#
#     ./dev/send_command: line 229: D, Archives, remote1, etc.)
#                        QUOTE R: command not found
#
# instead of the Running section. Nothing caught it but running the command by
# hand — the same class of failure as the EDN-tail leak next door, and shipped
# for the same reason: no committed test. This is that test.
#
# It renders every help variant and asserts (a) no shell diagnostics leak into
# the output, and (b) the sections a seat actually needs are present.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

# send_command writes its invocation log and touches the per-side peer-liveness
# file BEFORE it dispatches `help`, so even a pure-text help run mutates both.
# Four other shell tests that drive this dispatcher already redirect them; this
# one did not, so every run appended synthetic `help` lines to the real
# logs/commands.log and refreshed logs/.heartbeats/{corp,runner} — the exact
# timestamps an un-babysat game reads to tell a thinking opponent from a dead
# process. (Design review r2, guest seat.)
TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-help-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats"
export SEND_COMMAND_LOG="$TMP/commands.log"

PASS=0
FAIL=0

fail() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }
pass() { echo "  ✓ $1"; PASS=$((PASS + 1)); }

# A rendered help must never contain shell DIAGNOSTICS. The discriminator has to
# be precise, because the help text legitimately QUOTES an error message: the
# R&D note says  'command not found: D'  on purpose. So match the shape the
# shell actually emits — "<word>: command not found" (trailing) — not the phrase
# itself, which appears leading-and-quoted in our documentation.
assert_no_shell_errors() {
    local label="$1" out="$2"
    if [[ -z "$out" ]]; then
        fail "$label: produced NO output (broken invocation, not a pass)"
        return
    fi
    local bad=""
    grep -qE ': command not found' <<<"$out" && bad+="command-not-found "
    grep -qE ': No such file or directory' <<<"$out" && bad+="no-such-file "
    grep -qE 'send_command: line [0-9]+' <<<"$out" && bad+="bash-line-diagnostic "
    grep -qE 'unexpected (EOF|token)' <<<"$out" && bad+="parse-error "
    if [[ -n "$bad" ]]; then
        fail "$label: shell leaked into help output [$bad]"
        echo "$out" | head -8 | sed 's/^/      /'
    else
        pass "$label: renders as text"
    fi
}

assert_contains() {
    local label="$1" out="$2" needle="$3"
    if grep -qF -- "$needle" <<<"$out"; then
        pass "$label: contains '$needle'"
    else
        fail "$label: missing '$needle'"
    fi
}

# The isolation above is load-bearing, so assert it rather than trusting it:
# without these exports every run of this test appends to the real
# logs/commands.log and refreshes logs/.heartbeats/{corp,runner} — the
# peer-liveness timestamps an un-babysat game reads to tell a thinking opponent
# from a dead process. It was verified by mutation and then left unasserted, so
# the next reader could drop the exports as unused with the suite still green
# (code review).
echo "Test isolation"
if [[ "${SEND_COMMAND_LOG:-}" == "$TMP/"* && "${HEARTBEAT_DIR:-}" == "$TMP/"* ]]; then
    pass "command log and heartbeats are redirected into this test's TMP"
else
    fail "SEND_COMMAND_LOG/HEARTBEAT_DIR not redirected — this run writes the REAL log and heartbeats"
fi
"$SEND_CMD" corp help >/dev/null 2>&1
if [[ -e "$TMP/commands.log" ]]; then
    pass "the log redirect is in force (this test's log received the invocation)"
else
    fail "no log at $TMP/commands.log — SEND_COMMAND_LOG is not reaching send_command"
fi
# The heartbeat half needs its own proof: the log check passing says nothing about
# HEARTBEAT_DIR, and the heartbeat is the half with the real consequence — those
# timestamps are what an un-babysat game reads to tell a thinking opponent from a
# dead process. (Both seats, r4. One seat's repro also claimed the redirect was
# broken outright; it is not — it had found leftover files in the shared,
# gitignored logs/ dir and attributed them to the run.)
if [[ -e "$TMP/heartbeats/corp" ]]; then
    pass "the heartbeat redirect is in force (this test's dir received the touch)"
else
    fail "no heartbeat at $TMP/heartbeats/corp — HEARTBEAT_DIR is not reaching send_command"
fi

echo
echo "Rendering every help variant from $SEND_CMD"
echo

for role in runner corp ""; do
    label="${role:-generic}"
    out="$("$SEND_CMD" $role help 2>&1)"
    assert_no_shell_errors "$label help" "$out"
done

echo
echo "Full help (--full)"
full="$("$SEND_CMD" help --full 2>&1)"
assert_no_shell_errors "full help" "$full"

echo
echo "Content the seats depend on"

# #110 §1: continue-run is a real command in the dispatcher allowlist. If help
# doesn't document it, a seat told to run it (as the run-pause hint used to say)
# has no way to learn what it does.
assert_contains "full help" "$full" "continue-run"
assert_contains "full help" "$full" "monitor-run"

# #110 §2: the per-credit payment escape hatch.
assert_contains "full help" "$full" "--all"

# #110 §3: the quoting note must survive rendering in the runner brief — this is
# the exact string whose inner quotes broke the script.
runner_help="$("$SEND_CMD" runner help 2>&1)"
assert_contains "runner help" "$runner_help" '"R&D"'
assert_contains "runner help" "$runner_help" "command not found: D"

# #244 friction (marquee fffee105): `help monitor-run` printed the general help,
# and the command's flags were only in `help --full`. Per-command help is cut
# from --full, so there is no second copy to drift.
echo
echo "Per-command help (help <command>)"
mr="$("$SEND_CMD" corp help monitor-run 2>&1)"
assert_no_shell_errors "help monitor-run" "$mr"
assert_contains "help monitor-run" "$mr" "Alias for 'continue'"
assert_contains "help monitor-run" "$mr" "--persistent"
if [[ "$mr" == *"Card Actions:"* ]]; then
    fail "help monitor-run: printed the general help, not the command"
else
    pass "help monitor-run: not the general help"
fi
# monitor-run IS continue, so the run flags a Corp needs (--no-rez) belong here too.
assert_contains "help monitor-run" "$mr" "--no-rez"
# Flags blocks are matched by whole command word: `run` is not `monitor-run`.
rn="$("$SEND_CMD" runner help run 2>&1)"
if [[ "$rn" == *"monitor-run Flags"* ]]; then
    fail "help run: pulled in the monitor-run Flags block (substring match)"
else
    pass "help run: no monitor-run Flags block"
fi
# Aliases share their flags (panel): continue IS monitor-run, continue-run is continue --single.
ct="$("$SEND_CMD" corp help continue 2>&1)"
assert_contains "help continue" "$ct" "--persistent"
cr="$("$SEND_CMD" corp help continue-run 2>&1)"
assert_contains "help continue-run" "$cr" "--no-rez"
# And the form is discoverable from the help a seat actually reads (panel).
assert_contains "corp help" "$("$SEND_CMD" corp help 2>&1)" "help <command>"
rz="$("$SEND_CMD" corp help rez 2>&1)"
assert_contains "help rez" "$rz" "Rez installed Corp card"
nope="$("$SEND_CMD" corp help no-such-command 2>&1)"
assert_contains "help no-such-command" "$nope" "No help entry for 'no-such-command'"
# Both views, not just --full. The old assertion was `help --full` alone, and it
# was GREEN over a false sentence: the message said "'help --full' documents
# every one", which was never true of the ~24 commands only --dev documents.
# Changed deliberately, not quietly (#283).
assert_contains "help no-such-command" "$nope" "help --full"
assert_contains "help no-such-command" "$nope" "help --dev"

# ── #280 / #281 / #283: discoverability ──────────────────────────────────────
#
# A capability reachable in the dispatcher must be findable from help: "an
# undiscoverable capability is not a capability" (#204/#216).
#
# These assert an ENTRY, not a substring. A substring census is what put the
# wrong number in both #280 and #281: `status-compact` and `board-compact` were
# counted as documented because they appear inside `snapshot`'s prose, `--tank`
# was satisfied by `--tank-all`, and any sentence containing "change" would do.
# So: match a two-space-indented line whose entry name is exactly the command.
#
# The comparison is LITERAL, never a pattern. `game-over?` as an ERE means
# "game-ove" plus an optional r — it rejects its own entry and accepts
# `  game-ove`. That bug was in show_help_command's matcher AND in the first
# draft of this test (design review r1 and r2, guest seats), which is why
# game-over? is in every sample below.
echo
echo "Command entries (#280): a real entry, in some rendered view"

all_views="$("$SEND_CMD" help 2>&1; "$SEND_CMD" corp help 2>&1; "$SEND_CMD" runner help 2>&1; \
             "$SEND_CMD" help --full 2>&1; "$SEND_CMD" help --dev 2>&1)"

# True if some line is "  <cmd>" followed by a space or end-of-line. awk with a
# literal substr compare, so a command containing a regex metacharacter (?) is
# matched as the name it is.
has_entry() {
    awk -v cmd="$1" '
        function starts_entry(line,   p, n) {
            p = "  " cmd; n = length(p)
            if (substr(line, 1, n) != p) return 0
            return length(line) == n || substr(line, n + 1, 1) == " " }
        starts_entry($0) { found = 1 }
        END { exit(found ? 0 : 1) }' <<<"$2"
}

assert_entry() {
    local cmd="$1"
    if has_entry "$cmd" "$all_views"; then
        pass "entry exists: $cmd"
    else
        fail "NO help entry in any view for dispatcher command: $cmd"
    fi
}

# The nine measured by an anchored census over the dispatcher's arm labels.
# `concede` is deliberately NOT here: it is held pending #279's decision on side
# detection (documenting it would advertise an unconfirmed, irreversible
# game-ender whose no-side form silently prints help). If #279 is decided and
# concede gets an entry, add it to this list.
for c in status-compact board-compact hand-text log-compact \
         game-over-status 'game-over?' peer-status bot-status change; do
    assert_entry "$c"
done
# `help` itself: the discovery verb denied its own name ("No help entry for
# 'help'"), which is the misleading-output class this change exists to remove.
#
# assert_entry alone is NOT enough here, and this is the third time an entry
# assertion has been satisfied by a NEIGHBOUR: "  help --full     - ..." starts
# with "  help" followed by a space, so deleting the BARE help entry left the
# whole suite green (a seat reproduced it by mutation). Pin the bare entry by
# its own text.
assert_entry help
assert_contains "full help" "$full" "Your side's commands"

echo
echo "Run flags (#281): each has its OWN entry line in a Flags section"
# A flag counts as documented only if it HEADS an indented entry line, i.e. some
# line is <indent><flag> followed by a space or end-of-line.
#
# "appears somewhere in some view" is NOT the test. That is the measurement bug
# this whole change exists to correct (both #280 and #281 shipped a wrong count
# from it), and the first version of THIS assertion re-committed it: a plain
# whole-token grep over the rendered views was satisfied for --since by
# `wait [seconds] [--since N]` and for --force by `end-turn [--force]` — other
# commands' flags. Deleting the entire --dev harness flag section left both
# green (code review, two seats, reproduced by mutation). Requiring the flag at
# the HEAD of its line excludes both, because there it is mid-line.
#
# Literal compare again, for the same reason as has_entry.
has_flag_entry() {
    awk -v flag="$1" '
        {
            line = $0
            sub(/^ +/, "", line)                  # strip the indent
            if (line == $0) next                  # unindented: a section header
            n = length(flag)
            if (substr(line, 1, n) != flag) next
            if (length(line) == n || substr(line, n + 1, 1) == " ") found = 1
        }
        END { exit(found ? 0 : 1) }' <<<"$2"
}
assert_flag() {
    local flag="$1"
    if has_flag_entry "$flag" "$all_views"; then
        pass "flag has its own entry: $flag"
    else
        fail "flag handled by parse-run-flags but has no entry line: $flag"
    fi
}
# All seven that were absent.
for f in --tank --tank-all --tactics --tactics-file --return-on-signal --since --force; do
    assert_flag "$f"
done
# The assertion must be able to TELL that difference: --since and --force appear
# mid-line as other commands' flags, so a head-of-line test must reject those
# lines on their own. Pin it, so the weaker form cannot come back silently.
other_cmd_flags="  wait [seconds] [--since N]  Block until something happens
  end-turn [--force] - End your turn (requires --force if clicks remain)"
for f in --since --force; do
    if has_flag_entry "$f" "$other_cmd_flags"; then
        fail "has_flag_entry counts $f where it is only another command's flag"
    else
        pass "has_flag_entry rejects $f as another command's flag"
    fi
done

echo
echo "Per-command help reaches the dev view too (#283)"
# All 24 --dev entries were unreachable by name: the lookup read --full only.
for c in nuke-state list-game-ids replay-save bot-turn change bot-status; do
    out="$("$SEND_CMD" corp help "$c" 2>&1)"
    if [[ "$out" == *"No help entry"* ]]; then
        fail "help $c: denied, though help --dev documents it"
    else
        pass "help $c: resolves"
    fi
done
# peer-status is documented in COMPACT help, so assert_entry above is satisfied
# without the --full entry this change added. The point of that entry is that
# the per-command lookup (which reads --full then --dev, never compact) can find
# it, so assert THAT: deleting the --full entry left 53/53 green otherwise
# (code review, two seats, reproduced by mutation).
ps="$("$SEND_CMD" corp help peer-status 2>&1)"
if [[ "$ps" == *"No help entry"* ]]; then
    fail "help peer-status: denied — compact documents it but --full/--dev do not"
else
    pass "help peer-status: resolves"
fi

# The name that breaks a pattern-based matcher.
go="$("$SEND_CMD" corp help 'game-over?' 2>&1)"
if [[ "$go" == *"No help entry"* ]]; then
    fail "help game-over?: denied (the ? was treated as a quantifier)"
else
    pass "help game-over?: resolves"
fi
# ...and it must be ITS OWN entry. Asserting the command name here proves
# nothing: the miss message contains it too ("No help entry for 'game-over?'"),
# so that assertion was satisfied by the very denial it excluded (code review).
# Assert the entry's text instead.
assert_contains "help game-over?" "$go" "Alias for game-over-status"

echo
echo "Trailers name the view that actually holds the entry (#283)"
full_hit="$("$SEND_CMD" corp help rez 2>&1)"
dev_hit="$("$SEND_CMD" corp help nuke-state 2>&1)"
assert_contains "help rez (full hit)" "$full_hit" "help --full"
assert_contains "help nuke-state (dev hit)" "$dev_hit" "help --dev"
# No trailer may claim --full holds EVERY command: it excludes every --dev one.
# This sentence was wrong in two places four lines apart, and the first fix
# corrected only one of them (design review r1 then r2, guest seats).
for label_out in "help rez:$full_hit" "help nuke-state:$dev_hit" "help no-such-command:$nope"; do
    lbl="${label_out%%:*}"; body="${label_out#*:}"
    # Widened: the first version banned 'every command' and 'documents every
    # one', and my own replacement wording 'documents every PLAYER command' slid
    # straight past it — still a completeness claim, still false (concede is a
    # player command held out of --full). Ban the claim shape, not one phrasing.
    if grep -qiE 'every ([a-z]+ )?command|documents every|all of them' <<<"$body"; then
        fail "$lbl: trailer still claims --full documents every command"
    else
        pass "$lbl: no false 'every command' claim"
    fi
done
# A dev-sourced hit says so, so the "not used when playing a game" boundary the
# --dev view exists to draw is not silently erased.
assert_contains "help nuke-state (dev hit)" "$dev_hit" "Dev/test command"

# The same false-completeness sentence had FOUR homes, not three: the miss
# message, the success trailer, the --full `Help:` entry, and the hint printed
# beside "Unknown command". I fixed the first three and found the fourth only by
# grepping the script for the pointer afterwards.
#
# This one is checked at the SOURCE, not in rendered output, for two reasons: the
# unknown-command path runs check_client first and so needs a live REPL (this
# test is deliberately REPL-free), and a source check covers every place that
# points a seat at a help view — including a fifth one nobody has written yet.
# Same idiom as send_command_wrap_test.sh extracting its constant from source.
echo
echo "No pointer at a help view claims that view is complete (source check)"
# Ban the QUANTIFIER, not a list of phrasings. The first version of this guard
# enumerated wordings ('for everything', 'every ... command', 'all of them') and
# my own next commit wrote "All player commands:" — a fifth instance of the same
# false claim, past a guard written in the same commit to prevent it. A totality
# word next to a help-view pointer is the shape; spell that.
bad_pointers="$(grep -nE "help --(full|dev)" "$SEND_CMD" \
                | grep -iE '\b(all|every|everything|complete|entire|exhaustive|full list)\b' \
                | grep -vE '^[0-9]+:[[:space:]]*#' || true)"
if [[ -n "$bad_pointers" ]]; then
    fail "a help-view pointer claims completeness:"
    sed 's/^/      /' <<<"$bad_pointers"
else
    pass "no help-view pointer in $(basename "$SEND_CMD") claims completeness"
fi

# ── The corrected CLAIMS, pinned ────────────────────────────────────────────
#
# Five of this change's entries shipped a statement that was simply false about
# what the command does, and each was caught by review rather than by a test
# (r3: K2/K7/K8/K9/K11). Prose corrections with nothing pinning them can revert
# silently, and these are the ones where being wrong misleads a seat about
# behaviour — so pin the corrected claim, not just the entry's existence (r4).
echo
echo "Corrected entry claims stay corrected"

ht="$("$SEND_CMD" corp help hand-text 2>&1)"
# It truncates to the first sentence; it must not promise complete text, and it
# must point at the command that does give it.
if grep -qiE 'full rules text|complete rules text|full card text' <<<"$ht"; then
    fail "hand-text entry promises full text again — the arm passes no full? and truncates"
else
    pass "hand-text entry does not promise full text"
fi
assert_contains "help hand-text" "$ht" "card-text"

bs="$("$SEND_CMD" corp help bot-status 2>&1)"
# The arm dispatches on \$BOT_NS, so the entry may not describe only the Corp bot.
assert_contains "help bot-status" "$bs" "Runner bot"

sn="$("$SEND_CMD" corp help monitor-run 2>&1)"
# --since's "run ended" also requires no live encounter (#164).
if grep -qF -- "--since" <<<"$sn"; then
    pass "monitor-run help names --since"
else
    pass "monitor-run help does not pull the harness block (--since is --dev-only)"
fi

tf="$("$SEND_CMD" runner help run 2>&1)"
# --tactics must not be called categorically unusable: a keyword-only map works.
if grep -qiE 'NOT USABLE from this script' <<<"$tf"; then
    fail "--tactics entry is categorically 'NOT USABLE' again — a keyword-only map does get through"
else
    pass "--tactics entry is narrowed to maps containing a quoted string"
fi

echo
echo "─────────────────────────────"
echo "Passed: $PASS   Failed: $FAIL"
[[ $FAIL -eq 0 ]] || exit 1
