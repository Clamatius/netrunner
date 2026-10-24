#!/usr/bin/env bash
# send_command_side_test.sh — the first argument is a side only if it is
# `runner` or `corp`, and a command without one is refused (#279).
#
# Before #279, line 54 decided "side or command?" with a hand-kept list of
# command names, and any lowercase token NOT on it was taken as a custom client
# name (which always meant the Runner's port). The list fell 14 commands behind
# the dispatcher, so for those 14 the sideless form ate the command as a client
# name: `send_command status-compact` printed help and exited 0, and
# `send_command log-compact 3` blamed `3`. Completing the list would have been
# worse — it would have made `send_command concede` concede the Runner's game.
#
# This pins the inverted rule: every sideless command is refused before
# anything runs (no eval, no log line, no heartbeat), help and clear-heartbeats
# still work without a side, and the two sides reach their own ports.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-side-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats"
export SEND_COMMAND_LOG="$TMP/commands.log"

# A stub eval backend that records how it was called and answers nothing useful.
STUB="$TMP/stub-eval.sh"
cat > "$STUB" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$STUB_CALLS"
echo '"ok"'
EOF
chmod +x "$STUB"
export STUB_CALLS="$TMP/calls"

fails=0
ok()   { echo "ok   [$1]"; }
fail() { echo "FAIL [$1]: $2"; fails=$((fails+1)); }

run() {  # args...
    rm -rf "$HEARTBEAT_DIR"; mkdir -p "$HEARTBEAT_DIR"
    : > "$STUB_CALLS"; : > "$SEND_COMMAND_LOG"
    OUT=$(AI_EVAL="$STUB" NR_NO_AUTO_PROMPT=1 "$SEND_CMD" "$@" 2>&1) && CODE=0 || CODE=$?
    CALLS=$(cat "$STUB_CALLS"); LOGGED=$(cat "$SEND_COMMAND_LOG")
    HEARTBEATS=$(ls "$HEARTBEAT_DIR")
}

# 1. Refused, and nothing happened. The 14 commands #279 measured as drifted,
#    plus commands that were always on the old list (status, take-credit), a
#    made-up client name, and a capitalised side.
refused_cases=(
    "auto-start" "board-compact" "bot-respond" "bot-watch" "concede"
    "list-game-ids" "log-compact 3" "nuke-state" "replay-clear" "replay-save"
    "replay-start" "replay-status" "replay-stop" "status-compact"
    "status" "take-credit" "bob status" "Runner status"
)
for args in "${refused_cases[@]}"; do
    # Unquoted on purpose: each case is a whole argv.
    # shellcheck disable=SC2086
    run $args
    first="${args%% *}"
    if [[ "$CODE" -eq 0 ]]; then
        fail "refused:$args" "exited 0. Output: $OUT"
    elif [[ "$OUT" != *"no side given: '$first' is not runner or corp. Nothing was sent."* ]]; then
        fail "refused:$args" "the refusal did not name '$first'. Output: $OUT"
    elif [[ "$OUT" != *"./send_command <runner|corp> <command> [args...]"* ]]; then
        fail "refused:$args" "the hint did not give the usage shape. Output: $OUT"
    elif [[ -n "$CALLS" || -n "$LOGGED" || -n "$HEARTBEATS" ]]; then
        fail "refused:$args" "a refused command left a trace: eval='$CALLS' log='$LOGGED' heartbeats='$HEARTBEATS'"
    else
        ok "refused:$args"
    fi
done

# 1b. An EMPTY first argument is a missing side, not a request for help: it is
#     what `send_command "$SIDE" status` sends when $SIDE is unset. `${1:-help}`
#     used to turn it into help at exit 0 (r1 MAJOR). Quoted args can't go
#     through the split loop above, so these are spelled out.
for case_name in "empty" "empty+status"; do
    if [[ "$case_name" == "empty" ]]; then run ""; else run "" status; fi
    if [[ "$CODE" -eq 0 ]]; then
        fail "refused:$case_name" "exited 0. Output: $OUT"
    elif [[ "$OUT" != *"no side given: '' is not runner or corp. Nothing was sent."* ]]; then
        fail "refused:$case_name" "no refusal. Output: $OUT"
    elif [[ -n "$CALLS" || -n "$LOGGED" || -n "$HEARTBEATS" ]]; then
        fail "refused:$case_name" "a refused command left a trace: eval='$CALLS' log='$LOGGED' heartbeats='$HEARTBEATS'"
    else
        ok "refused:$case_name"
    fi
done

# 1c. The hint is the usage shape, never argv re-printed: re-printing dropped the
#     quoting (`play "Sure Gamble"` came back as `play Sure Gamble`) and put a
#     side in front of a mis-cased one (`<runner|corp> Runner status`).
run play "Sure Gamble"
if [[ "$OUT" == *"play Sure Gamble"* ]]; then
    fail "hint-not-argv" "the hint re-printed the unquoted arguments. Output: $OUT"
else
    ok "hint-not-argv"
fi

# 2. The sideless commands that touch no client still run, and still touch no
#    client or heartbeat. reset.sh relies on clear-heartbeats being sideless.
for args in "" "help" "--help" "-h" "clear-heartbeats"; do
    # shellcheck disable=SC2086
    run $args
    if [[ "$CODE" -ne 0 ]]; then
        fail "sideless-ok:[$args]" "exited $CODE. Output: $OUT"
    elif [[ "$OUT" == *"no side given"* ]]; then
        fail "sideless-ok:[$args]" "was refused. Output: $OUT"
    elif [[ -n "$CALLS" || -n "$HEARTBEATS" ]]; then
        fail "sideless-ok:[$args]" "touched a client or heartbeat: eval='$CALLS' heartbeats='$HEARTBEATS'"
    else
        ok "sideless-ok:[$args]"
    fi
done

# 3. Each side reaches its own client.
for triple in "runner 7889 corp" "corp 7890 runner"; do
    read -r side port other <<< "$triple"
    run "$side" get-cursor
    if [[ "$CALLS" != *"$side $port"* ]]; then
        fail "routes:$side" "expected the eval backend to be called for '$side $port'. Calls: $CALLS"
    elif [[ "$CALLS" == *"$other"* ]]; then
        fail "routes:$side" "the other side's client was called too. Calls: $CALLS"
    else
        ok "routes:$side"
    fi
done

# 3b. The usage strings name the caller's side exactly once. A sed that
#     prefixed $CLIENT_NAME also hit find-card's, which already said
#     `corp|runner`, and printed `send_command runner corp|runner find-card` (r1).
run runner find-card
if [[ "$OUT" != *"Usage: send_command runner find-card \"Card Name\""* ]]; then
    fail "usage-names-side-once" "find-card's usage did not read 'send_command runner find-card'. Output: $OUT"
else
    ok "usage-names-side-once"
fi

# 4. The full help no longer advertises a sideless form.
run help --full
if [[ "$OUT" == *"default client"* || "$OUT" == *"backward compatible"* ]]; then
    fail "help-no-default-client" "help --full still documents a sideless default client"
else
    ok "help-no-default-client"
fi

if [[ "$fails" -gt 0 ]]; then
    echo "❌ side detection: $fails assertion(s) failed"
    exit 1
fi
echo "✅ side detection: all assertions passed"
