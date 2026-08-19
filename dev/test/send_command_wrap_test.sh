#!/usr/bin/env bash
# send_command_wrap_test.sh — regression guard for the display-command wrap heuristic.
#
# Why this exists: `execute()` in dev/send_command wraps display commands in
# (with-out-str ...) so their println output comes back as a value. The test for
# "is this a display command?" used SUBSTRING matching:
#
#     [[ "$expr" == *"(ai-actions/show-"* ]] || ...
#
# so ANY expression that merely MENTIONED a display fn anywhere inside it got
# wrapped. For `eval` — the documented escape hatch for inspecting live state —
# that was silently destructive:
#
#     $ ./dev/send_command corp eval '(count (with-out-str (ai-actions/show-credits)))'
#     $                                       # nothing. exit 0. no error.
#
# The expression became (with-out-str (count (with-out-str (ai-actions/show-credits)))):
# the inner call's output went to the inner capture, and `count`'s return value
# was swallowed by the outer one. The seat gets silence at the exact moment it is
# using eval to work out what is wrong — the #109 failure mode with no message at
# all. (It cost a whole debugging detour: silence read as "the fn throws".)
#
# The fix is a LEADING-position match: wrap an expression that IS a display call,
# not one that merely contains one. Every real call site in send_command passes a
# bare `(ai-actions/<fn> ...)`, so nothing else changes.
#
# Like send_command_filter_test.sh, this extracts the LIVE predicate from
# dev/send_command and runs it, so the test cannot silently drift from the source.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

# Pull the real prefix list and the real predicate out of send_command and define
# them here. If either disappears or is renamed, this fails loudly rather than
# testing a stale copy.
PREFIX_LINE=$(grep -E "^DISPLAY_EXPR_PREFIXES=" "$SEND_CMD" | head -1)
if [[ -z "$PREFIX_LINE" ]]; then
    echo "FAIL: could not extract DISPLAY_EXPR_PREFIXES from $SEND_CMD" >&2
    exit 1
fi
FN_BODY=$(sed -n '/^is_display_expr() {$/,/^}$/p' "$SEND_CMD")
if [[ -z "$FN_BODY" ]]; then
    echo "FAIL: could not extract is_display_expr() from $SEND_CMD" >&2
    exit 1
fi
eval "$PREFIX_LINE"
eval "$FN_BODY"
echo "Using live wrap predicate from send_command"

fails=0
# assert_wraps NAME EXPR  → predicate must say "this is a display command"
assert_wraps() {
    local name="$1" expr="$2"
    if [[ -z "$expr" ]]; then echo "FAIL [$name]: test input was empty (broken setup)"; fails=$((fails+1)); return; fi
    if is_display_expr "$expr"; then
        echo "ok   [$name] wrapped"
    else
        echo "FAIL [$name]: expected WRAP, predicate declined: $expr"; fails=$((fails+1))
    fi
}
# assert_bare NAME EXPR → predicate must leave it alone
assert_bare() {
    local name="$1" expr="$2"
    if [[ -z "$expr" ]]; then echo "FAIL [$name]: test input was empty (broken setup)"; fails=$((fails+1)); return; fi
    if is_display_expr "$expr"; then
        echo "FAIL [$name]: expected NO wrap, predicate claimed it: $expr"; fails=$((fails+1))
    else
        echo "ok   [$name] left bare"
    fi
}

echo "--- every real send_command call site must still be wrapped ---"
assert_wraps "show-board"       '(ai-actions/show-board)'
assert_wraps "show-hand"        '(ai-actions/show-hand)'
assert_wraps "show-credits"     '(ai-actions/show-credits)'
assert_wraps "show-log-arg"     '(ai-actions/show-log 5)'
assert_wraps "show-snapshot"    '(ai-actions/show-snapshot 5)'
assert_wraps "show-card-text"   '(ai-actions/show-card-text "Palisade")'
assert_wraps "list-playables"   '(ai-actions/list-playables)'
assert_wraps "status"           '(ai-actions/status)'
assert_wraps "status-compact"   '(ai-actions/status-compact)'
assert_wraps "board-compact"    '(ai-actions/board-compact)'
assert_wraps "hand-cards"       '(ai-actions/show-hand-cards)'
assert_wraps "prompt-if-any"    '(ai-actions/show-prompt-if-any)'
assert_wraps "leading-space"    '  (ai-actions/show-board)'

echo "--- an eval that merely MENTIONS a display fn must NOT be wrapped ---"
# These are the shapes that came back as pure silence before the fix.
assert_bare "count-of-capture"  '(count (with-out-str (ai-actions/show-credits)))'
assert_bare "own-with-out-str"  '(with-out-str (ai-actions/list-playables))'
assert_bare "try-catch-around"  '(try (ai-actions/show-credits) (catch Throwable t (str "THREW " (.getMessage t))))'
assert_bare "first-line-of"     '(first (clojure.string/split-lines (with-out-str (ai-actions/list-playables))))'
assert_bare "resolve-the-var"   '(pr-str (resolve (symbol "ai-actions" "list-playables")))'
assert_bare "do-block"          '(do (println "x") (ai-actions/show-board))'

# NOTE on `(do ...)`: an earlier fix tried to PEEL leading `(do ...)` so that
# `eval '(do (ai-actions/status))'` would be wrapped, because `status` returns
# @client-state and the unwrapped path printed the session-token. A second review
# pass showed that rule is unwinnable in bash: `(do a b)` returns its LAST form,
# not its first, so `(do (println "x") (ai-actions/status))` still leaked; and
# `(do,(f))` and `(do ; comment` both parse as ordinary `do` and evaded the
# match. Worse, peeling wrapped `(do (ai-actions/status) 42)` and swallowed the
# 42. The leak is now closed where it cannot be evaded — `redact_secrets` on the
# OUTPUT — so the predicate goes back to the one rule it can actually enforce:
# an expression IS a display call, or it is not.
assert_bare "do-status"         '(do (ai-actions/status))'
assert_bare "do-multi-form"     '(do (println "checking") (ai-actions/status))'

assert_bare "plain-state-read"  '(get-in @ai-state/client-state [:game-state :run])'
assert_bare "other-namespace"   '(ai-display/show-credits)'

echo "--- execute() must actually USE the predicate ---"
# Guest-panel MINOR: everything above tests the helper in isolation. If execute()
# reverted to the substring test while the (now unused) helper stayed correct,
# every assertion above would still pass. Pin the wiring itself.
if grep -qE '^[[:space:]]*if is_display_expr "\$expr"; then' "$SEND_CMD"; then
    echo "ok   [execute-calls-predicate] wired"
else
    echo "FAIL [execute-calls-predicate]: execute() does not gate on is_display_expr"
    fails=$((fails+1))
fi
if grep -qE '\$expr" == \*"\(ai-actions/' "$SEND_CMD"; then
    echo "FAIL [no-substring-test]: the old substring wrap test is back in execute()"
    fails=$((fails+1))
else
    echo "ok   [no-substring-test] substring predicate is gone"
fi

echo "--- credentials never reach the seat, whatever the expression ---"
# The rule the predicate cannot enforce, enforced where it can be: on the bytes.
REDACT_BODY=$(sed -n '/^redact_secrets() {$/,/^}$/p' "$SEND_CMD")
if [[ -z "$REDACT_BODY" ]]; then
    echo "FAIL: could not extract redact_secrets() from $SEND_CMD"; fails=$((fails+1))
else
    eval "$REDACT_BODY"
    assert_redacted() {
        local name="$1" input="$2" out
        out=$(printf '%s\n' "$input" | redact_secrets)
        if [[ "$out" == *"SUPERSECRET"* ]]; then
            echo "FAIL [$name]: credential survived: $out"; fails=$((fails+1))
        else
            echo "ok   [$name] redacted"
        fi
    }
    assert_redacted "edn-session-token" '{:uid "ai-corp", :session-token "SUPERSECRET.jwt.value", :side "corp"}'
    assert_redacted "edn-csrf-token"    '{:csrf-token "SUPERSECRET+slashes/and+plus=", :connected true}'
    assert_redacted "both-on-one-line"  '{:session-token "SUPERSECRET1", :csrf-token "SUPERSECRET2"}'
    # And it must not eat ordinary game output.
    keep=$(printf '%s\n' '💰 Credits: 5' | redact_secrets)
    if [[ "$keep" == '💰 Credits: 5' ]]; then
        echo "ok   [ordinary-output-untouched] preserved"
    else
        echo "FAIL [ordinary-output-untouched]: got '$keep'"; fails=$((fails+1))
    fi
fi

echo
if [[ $fails -eq 0 ]]; then
    echo "✅ send_command wrap heuristic: all assertions passed"
    exit 0
else
    echo "❌ send_command wrap heuristic: $fails assertion(s) failed"
    exit 1
fi
