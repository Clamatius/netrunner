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
    # STUB_VERDICT=THROW mimics a REPL that errors on the call (old code loaded,
    # or an exception inside it): ai-eval.sh prints the trace and exits NON-ZERO.
    if [[ "$STUB_VERDICT" == "THROW" ]]; then
        printf 'Syntax error compiling at (form-init.clj:3:38).\nNo such var: conn/sync-verdict!\n'
        exit 1
    fi
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

# #216: the primary read loop used to bypass this authority. A purged seat saw
# "NO BOARD -> resync", resync said "try status", and status printed the first
# message byte-for-byte. These reads must diagnose the teardown before their
# boardless renderers can send the seat around that cycle again.
# The expression each read sends. The gate test's stub log records the
# EXPRESSION it was handed, not what it printed, so "did the read run?" has to
# be asked with the read's own call — asserting on the stub's output string
# would be vacuous in both directions, which is the trap this file already
# caught once.
read_expr() {
    case "$1" in
        status)   echo "ai-actions/status" ;;
        board)    echo "ai-actions/show-board" ;;
        hand)     echo "ai-actions/show-hand" ;;
        snapshot) echo "ai-actions/show-snapshot" ;;
    esac
}

for read_cmd in status board hand snapshot; do
    run game-gone "$read_cmd"
    assert_contains     "gone-$read_cmd-says-gone" "$OUT" "GAME-GONE"
    assert_contains     "gone-$read_cmd-recovers"  "$OUT" "reset.sh"
    # The renderer must not run: a GONE game's cached board has no authority
    # behind it (#138). Asserting the absence of "NO BOARD" was vacuous — the
    # stub never emits it, so that check stayed green over the very defect it
    # named (panel NIT, confirmed by mutation: dropping all four
    # ensure_connection calls turned 8 assertions red and this was not one).
    # Assert instead that the read never reached the backend at all.
    assert_not_contains "gone-$read_cmd-not-sent"  "$LOG" "$(read_expr "$read_cmd")"
done

echo "--- ...but a DECIDED game is exactly what a READ is for ---"
# Both review seats, independently, MAJOR. #216 gated the four reads on the same
# verdict set as actions, so a finished game answered `status` with "was NOT
# sent (it would have been answered from a finished game's state)" — which is
# the post-mortem the seat asked for, refused and described in the same breath.
# Reads take --allow-decided: game-over passes, game-gone and resync-failed do
# not, and ACTIONS are still refused on all three.
for read_cmd in status board hand snapshot; do
    run game-over "$read_cmd"
    assert_code         "over-$read_cmd-exit-0"    0 "$CODE"
    assert_contains     "over-$read_cmd-rendered"  "$LOG" "$(read_expr "$read_cmd")"
    assert_not_contains "over-$read_cmd-not-refused" "$OUT" "was NOT sent"
done

run game-over draw
assert_contains     "over-action-still-refused"  "$OUT" "was NOT sent"
assert_not_contains "over-action-not-sent"       "$LOG" "draw-card!"

# A seat told to retry must not be pointed at the command it is already running.
# #216 routed `status` into refuse_no_state, where the advice line said "if it
# keeps failing: ... status" — the closed loop #216 exists to break, one branch
# further in (panel MINOR).
run resync-failed status
assert_contains     "retry-status-says-retry"   "$OUT" "Retry the same command"
assert_not_contains "retry-status-not-itself"   "$OUT" "keeps failing"
run resync-failed draw
assert_contains     "retry-draw-points-at-status" "$OUT" "keeps failing"

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

run synced help --dev
assert_contains     "dev-help-names-game-id-probe" "$OUT" "list-game-ids"

echo "--- a live game is untouched, and a broken backend does not lock the seat out ---"
run synced draw
assert_contains     "synced-draw-sent"         "$LOG" "draw-card!"
assert_not_contains "synced-not-refused"       "$OUT" "was NOT sent"

# Transient: resync-game! clears the cached state BEFORE asking for a fresh one, so a
# resync that didn't land leaves the client deliberately empty — acting there is #109's
# mechanism, not an edge case. Refused, but as a RETRY, not as a teardown.
run resync-failed draw
assert_contains     "transient-refused"        "$OUT" "NO STATE"
assert_contains     "transient-says-retry"     "$OUT" "Retry the same command"
assert_contains     "transient-names-probe"     "$OUT" "list-game-ids"
assert_not_contains "transient-not-teardown"   "$OUT" "GAME-GONE"
assert_code         "transient-exit-4"         4 "$CODE"
assert_not_contains "transient-not-sent"       "$LOG" "draw-card!"

# ...but the allowlist still bypasses it: a babysit `wait` through a slow resync
# must not become a refusal loop.
run resync-failed wait --timeout 1
assert_contains     "transient-wait-still-runs" "$LOG" "wait"

# Fail-open. An eval timeout or a REPL error yields no parseable verdict; a broken
# backend must not be able to refuse a seat access to its own live game.
run "" draw
assert_contains     "no-verdict-fails-open"    "$LOG" "draw-card!"

# A backend that THROWS is the case that actually shipped broken: ai-eval.sh exits
# non-zero, and under `set -e` the unguarded capture killed the command dead —
# no output, exit 1, nothing for a seat to read. Caught live against a REPL running
# the pre-fix Clojure (new CLI + old REPL is the normal state mid-deploy).
run THROW draw
assert_contains     "throwing-backend-still-acts" "$LOG" "draw-card!"
assert_code         "throwing-backend-not-fatal"  0 "$CODE"
if [[ -n "${OUT//[[:space:]]/}" ]]; then echo "ok   [throwing-backend-not-silent]"
else echo "FAIL [throwing-backend-not-silent]: command produced NO output at all"; fails=$((fails+1)); fi

echo
if [[ $fails -eq 0 ]]; then echo "✅ game-gone gate: all assertions passed"; exit 0
else echo "❌ game-gone gate: $fails assertion(s) failed"; exit 1; fi
