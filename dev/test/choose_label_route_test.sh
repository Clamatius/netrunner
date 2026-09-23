#!/usr/bin/env bash
# Exercise the real send_command dispatcher with a fake eval backend. This
# proves a label reaches choose-by-value! as one Clojure string, without
# connecting to a game or treating a fake backend as engine acceptance.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEND_CMD="$SCRIPT_DIR/../send_command"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-choice-route.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats" SEND_COMMAND_LOG="$TMP/commands.log"

cat > "$TMP/eval" <<'STUB'
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then
    expr="$(cat)"
else
    expr="${!#}"
fi
printf '%s\n' "$expr" >> "$STUB_LOG"
case "$expr" in
    *sync-verdict!*) printf '"SYNC-VERDICT in-sync"\n' ;;
    *ensure-connected!*) printf '"ok"\n' ;;
    *) printf 'nil\n' ;;
esac
STUB
chmod +x "$TMP/eval"

fail=0
check() {
    local name="$1" actual="$2" expected="$3"
    if [[ "$actual" == "$expected" ]]; then
        echo "ok   [$name]"
    else
        printf 'FAIL [%s]\n expected: %s\n      got: %s\n' "$name" "$expected" "$actual"
        fail=$((fail + 1))
    fi
}
run_choice() {
    local label="$1"
    export STUB_LOG="$TMP/expressions.log"
    : > "$STUB_LOG"
    NR_NO_AUTO_PROMPT=1 AI_EVAL="$TMP/eval" "$SEND_CMD" runner choose "$label" > "$TMP/output" 2>&1
    code=$?
    route="$(rg '^\(ai-actions/choose-' "$STUB_LOG" || true)"
}

run_choice 'R&D'
check 'label-command-exits' "$code" '0'
check 'label-routes-to-matcher' "$route" '(ai-actions/choose-by-value! "R&D")'

run_choice 'Cerberus "Lady" H1'
check 'quoted-label-exits' "$code" '0'
check 'quoted-label-is-one-string' "$route" '(ai-actions/choose-by-value! "Cerberus \"Lady\" H1")'

run_choice '1'
check 'numeric-choice-exits' "$code" '0'
check 'numeric-choice-keeps-index-path' "$route" '(ai-actions/choose-option! 1)'

if ((fail)); then
    printf 'FAIL: %d choice routing assertion(s)\n' "$fail"
    exit 1
fi
echo 'PASS: CLI choice routing (offline backend)'
