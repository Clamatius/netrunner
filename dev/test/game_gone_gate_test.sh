#!/usr/bin/env bash
# game_gone_gate_test.sh — regression guard for #109: acting on a game that is gone.
#
# Why this exists: ensure_connection called ensure-synced!, which correctly
# diagnosed the dead game, printed the correct guidance, returned false — and the
# command ran anyway against cleared state. Each action then invented its own
# explanation out of empty fields, so the outputs a seat sees at exactly the
# moment it needs to diagnose a purged game were:
#
#     draw     → "✅ Turn started successfully" … "deck empty?"   (false success)
#     choose 3 → "❌ Invalid choice index: 3"                     (the index was fine)
#
# The fix is one gate, not six re-worded surfaces: ensure_connection now asks for
# the VERDICT and refuses to send the command. This test drives the REAL
# dispatcher with a stub eval backend, because the bug was never in the Clojure
# (which was honest all along) — it was in the shell that threw the answer away.
#
# The allowlist half matters just as much: `wait` and `get-cursor` must still work
# on a dead game or marquee-babysit.sh loses its only exit condition, and the
# lobby commands must still work or there is no way back to a live game.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-gamegone-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
# Keep heartbeats out of the real logs dir (same isolation as peer_status_test).
export HEARTBEAT_DIR="$TMP/heartbeats"

# Stub eval backend. ensure_connection passes its expression as the LAST ARG;
# execute() passes --stdin and feeds the expression on stdin. We log every
# expression that reaches the "REPL" so a test can assert a command was never sent.
STUB="$TMP/stub-eval.sh"
cat > "$STUB" <<'STUBEOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then
    expr="$(cat)"
else
    expr="${!#}"
fi
printf '%s\n' "$expr" >> "$STUB_LOG"
if [[ "$expr" == *"sync-verdict!"* ]]; then
    printf '"SYNC-VERDICT %s"\n' "$STUB_VERDICT"
elif [[ "$expr" == *"ensure-connected!"* ]]; then
    printf '"ok"\n'
else
    printf 'STUB-EXECUTED\n'
fi
STUBEOF
chmod +x "$STUB"

fails=0
OUT=""; CODE=0; LOG=""

# run VERDICT COMMAND [args...] — drive send_command with the stubbed backend.
run() {
    local verdict="$1"; shift
    STUB_LOG="$TMP/log.$$.$RANDOM"
    : > "$STUB_LOG"
    export STUB_LOG STUB_VERDICT="$verdict"
    OUT=$(AI_EVAL="$STUB" "$SEND_CMD" corp "$@" 2>&1) && CODE=0 || CODE=$?
    LOG=$(cat "$STUB_LOG")
}

assert_contains() {  # NAME HAYSTACK NEEDLE
    local name="$1" hay="$2" needle="$3"
    if [[ "$hay" == *"$needle"* ]]; then echo "ok   [$name]"
    else echo "FAIL [$name]: expected to contain '$needle', got:"; printf '%s\n' "$hay" | sed 's/^/    /'; fails=$((fails+1)); fi
}
assert_not_contains() {  # NAME HAYSTACK NEEDLE
    local name="$1" hay="$2" needle="$3"
    if [[ "$hay" != *"$needle"* ]]; then echo "ok   [$name]"
    else echo "FAIL [$name]: expected NOT to contain '$needle', got:"; printf '%s\n' "$hay" | sed 's/^/    /'; fails=$((fails+1)); fi
}
assert_code() {  # NAME WANT GOT
    if [[ "$2" == "$3" ]]; then echo "ok   [$1]"
    else echo "FAIL [$1]: expected exit $2, got $3"; fails=$((fails+1)); fi
}

echo "--- the gate: an action on a gone game is refused, not guessed at ---"
run game-gone draw
assert_contains     "gone-draw-says-gone"      "$OUT" "GAME-GONE"
assert_contains     "gone-draw-names-command"  "$OUT" "'draw' was NOT sent"
assert_contains     "gone-draw-offers-confirm" "$OUT" "game-over-status"
assert_code         "gone-draw-exit-3"         3 "$CODE"
# The heart of #109: the draw must never reach the REPL. If it does, every
# downstream lie ("Turn started successfully", "deck empty?") comes back.
assert_not_contains "gone-draw-not-sent"       "$LOG" "draw-card!"

run game-gone choose 3
assert_contains     "gone-choose-says-gone"    "$OUT" "GAME-GONE"
assert_not_contains "gone-choose-no-index-lie" "$OUT" "Invalid choice index"
assert_not_contains "gone-choose-not-sent"     "$LOG" "choose"

run game-gone end-turn
assert_contains     "gone-end-turn-says-gone"  "$OUT" "GAME-GONE"
assert_not_contains "gone-end-turn-no-npe"     "$OUT" "NullPointerException"
assert_not_contains "gone-end-turn-not-sent"   "$LOG" "end-turn!"

echo "--- a DECIDED game is not a GONE game: the seat wants the result ---"
run game-over draw
assert_contains     "over-says-over"           "$OUT" "GAME-OVER"
assert_not_contains "over-not-mislabelled"     "$OUT" "GAME-GONE"
assert_code         "over-exit-3"              3 "$CODE"
assert_not_contains "over-draw-not-sent"       "$LOG" "draw-card!"

echo "--- the allowlist: diagnosis and recovery must survive a dead game ---"
# marquee-babysit.sh's only exit condition is game-over-status + wait; gating
# either one would replace a hung game with a hung babysitter.
run game-gone wait --timeout 1
assert_contains     "gone-wait-still-runs"     "$LOG" "wait"
assert_not_contains "gone-wait-not-refused"    "$OUT" "was NOT sent"

run game-gone get-cursor
assert_contains     "gone-get-cursor-runs"     "$LOG" "get-cursor"

# Recovery: if create/join were gated there would be no way back to a live game.
run game-gone create-game "Test Game"
assert_not_contains "gone-create-not-refused"  "$OUT" "was NOT sent"

run game-gone leave-game
assert_not_contains "gone-leave-not-refused"   "$OUT" "was NOT sent"

run game-gone list-lobbies
assert_not_contains "gone-list-not-refused"    "$OUT" "was NOT sent"

echo "--- a live game is untouched, and a broken backend does not lock the seat out ---"
run synced draw
assert_contains     "synced-draw-sent"         "$LOG" "draw-card!"
assert_not_contains "synced-not-refused"       "$OUT" "was NOT sent"

# Transient: the game may well be alive, so retrying is the seat's call, not ours.
run resync-failed draw
assert_contains     "transient-draw-sent"      "$LOG" "draw-card!"

# Fail-open. An eval timeout or a REPL error yields no parseable verdict; a broken
# backend must not be able to refuse a seat access to its own live game.
run "" draw
assert_contains     "no-verdict-fails-open"    "$LOG" "draw-card!"

echo
if [[ $fails -eq 0 ]]; then echo "✅ game-gone gate: all assertions passed"; exit 0
else echo "❌ game-gone gate: $fails assertion(s) failed"; exit 1; fi
