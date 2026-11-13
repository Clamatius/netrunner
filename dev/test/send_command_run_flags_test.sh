#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-run-flags.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats" SEND_COMMAND_LOG="$TMP/commands.log"
export STUB_CALLS="$TMP/calls"
cat > "$TMP/stub" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$STUB_CALLS"
echo '"stub"'
EOF
chmod +x "$TMP/stub"
fails=0
for verb in run continue continue-run monitor-run; do
    : > "$STUB_CALLS"
    args=()
    [[ "$verb" == run ]] && args=(HQ)
    out=$(AI_EVAL="$TMP/stub" "$SCRIPT_DIR/../send_command" corp "$verb" "${args[@]}" --rez "Ice Wall" --no-rez 2>&1) && code=0 || code=$?
    clean=$(printf '%s\n' "$out" | sed $'s/\033\[[0-9;]*m//g')
    if [[ "$code" -eq 0 || "$clean" != '❌ Error: --rez and --no-rez cannot be used together. Nothing was sent.' || -s "$STUB_CALLS" ]]; then
        echo "FAIL [contradictory-run-flags:$verb]: exit=$code output=$clean calls=$(cat "$STUB_CALLS")"
        fails=$((fails+1))
    else
        echo "ok   [contradictory-run-flags:$verb]"
    fi
done
[[ "$fails" -eq 0 ]] || exit 1
