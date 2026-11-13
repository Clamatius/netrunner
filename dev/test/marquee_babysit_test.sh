#!/usr/bin/env bash
# marquee_babysit_test.sh — the two parameters dev/match needs from the babysitter.
#
# 1. The codex session id. askmodel now runs codex with `--json`, so a seat's
#    session id is no longer a `session id: <uuid>` header in <out>.stderr: it is
#    the `thread_id` of the `thread.started` event in <out>.trace.jsonl. The
#    babysitter only knew the header, so pointed at an askmodel seat it exited
#    "no 'session id:' found … cannot resume" at the one moment it was needed.
# 2. MARQUEE_CHUNK_LOG: where the chunk log goes, so a launcher can keep it in
#    its own run dir. Unset, the old logs/marquee-<side>-<model>-<tag>-chunks.log
#    path is unchanged.
#
# Drives the REAL script, copied into a scratch tree (it cds to its own ../ and
# calls ./dev/send_command from there), with stub send_command / codex / sleep.
# No REPL, no model, no network.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL="$SCRIPT_DIR/../marquee-babysit.sh"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-babysit-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

fails=0
ok()   { echo "ok   [$1]"; }
fail() { echo "FAIL [$1]: $2"; fails=$((fails+1)); }

# tree NAME -> builds $TMP/NAME/{dev/marquee-babysit.sh,dev/send_command,bin/*}
tree() {
    local t="$TMP/$1"
    mkdir -p "$t/dev" "$t/bin" "$t/logs"
    cp "$REAL" "$t/dev/marquee-babysit.sh"
    # First game-over-status call: live. Every later call: over. So the loop runs
    # exactly one chunk and then exits TERMINAL.
    cat > "$t/dev/send_command" <<'EOF'
#!/usr/bin/env bash
here="$(cd "$(dirname "$0")/.." && pwd)"
case "${2:-}" in
  game-over-status)
    if [[ -f "$here/.seen" ]]; then echo "GAME-OVER winner=corp turn=9"
    else touch "$here/.seen"; echo "IN-PROGRESS turn=3 whose-turn=corp clicks=2"; fi ;;
  get-cursor) echo 7 ;;
esac
EOF
    cat > "$t/bin/codex" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$t/codex.args"
echo "stub codex chunk output"
EOF
    printf '#!/bin/sh\nexit 0\n' > "$t/bin/sleep"
    chmod +x "$t/dev/marquee-babysit.sh" "$t/dev/send_command" "$t/bin/codex" "$t/bin/sleep"
    echo "$t"
}

# --- 1. codex session id from an askmodel --json trace ----------------------
t=$(tree json)
printf '%s\n' '{"type":"thread.started","thread_id":"01a17768-d284-7110-beb6-c111536d5bc3"}' \
              '{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"hi"}}' > "$t/seat.md.trace.jsonl"
out=$(PATH="$t/bin:$PATH" MARQUEE_BACKEND=codex MARQUEE_PRIMARY_LOG="$t/seat.md.trace.jsonl" \
      MARQUEE_CODEX_EFFORT=medium MARQUEE_CHUNK_LOG="$t/run/babysit-corp-chunks.log" \
      "$t/dev/marquee-babysit.sh" corp gpt-6-luna tagx 2>&1); code=$?
[[ $code -eq 0 ]] && ok "json: exits 0 at GAME-OVER" || fail "json: exits 0 at GAME-OVER" "exit $code; output: $out"
args=$(cat "$t/codex.args" 2>/dev/null)
[[ "$args" == "exec resume 01a17768-d284-7110-beb6-c111536d5bc3 "* ]] \
    && ok "json: resumes the thread.started id" || fail "json: resumes the thread.started id" "codex args: '$args'"
[[ "$args" == *'model_reasoning_effort=medium'* ]] \
    && ok "json: passes the requested effort" || fail "json: passes the requested effort" "codex args: '$args'"
[[ "$out" == *"TERMINAL: GAME-OVER winner=corp turn=9 (after 1 chunks)"* ]] \
    && ok "json: one chunk then TERMINAL" || fail "json: one chunk then TERMINAL" "$out"

# --- 2. MARQUEE_CHUNK_LOG decides where the chunk log goes --------------------
[[ -f "$t/run/babysit-corp-chunks.log" ]] && grep -q '^===== chunk 1 ' "$t/run/babysit-corp-chunks.log" \
    && ok "chunk log at MARQUEE_CHUNK_LOG" || fail "chunk log at MARQUEE_CHUNK_LOG" "$(ls -R "$t" 2>&1)"
[[ -z "$(ls "$t/logs" 2>/dev/null)" ]] \
    && ok "nothing written to the default logs/ path when overridden" || fail "nothing written to default logs/" "$(ls "$t/logs")"

# --- 3. the legacy stderr header still works; default path unchanged ----------
t=$(tree header)
printf 'banner\nsession id: 11111111-2222-3333-4444-555555555555\nmore\n' > "$t/seat.stderr"
out=$(PATH="$t/bin:$PATH" MARQUEE_BACKEND=codex MARQUEE_PRIMARY_LOG="$t/seat.stderr" \
      "$t/dev/marquee-babysit.sh" runner gpt-6-sol tagy 2>&1); code=$?
args=$(cat "$t/codex.args" 2>/dev/null)
[[ "$args" == "exec resume 11111111-2222-3333-4444-555555555555 "* ]] \
    && ok "header: resumes the session id: header" || fail "header: resumes the session id: header" "codex args: '$args'; out: $out"
[[ -f "$t/logs/marquee-runner-gpt-6-sol-tagy-chunks.log" ]] \
    && ok "header: default chunk log path unchanged" || fail "header: default chunk log path unchanged" "$(ls "$t/logs")"

# --- 4. neither form present: still refuses, does not resume garbage ----------
t=$(tree none)
printf '{"type":"item.completed"}\n' > "$t/seat.md.trace.jsonl"
out=$(PATH="$t/bin:$PATH" MARQUEE_BACKEND=codex MARQUEE_PRIMARY_LOG="$t/seat.md.trace.jsonl" \
      "$t/dev/marquee-babysit.sh" corp gpt-6-luna tagz 2>&1); code=$?
[[ $code -ne 0 && ! -f "$t/codex.args" ]] \
    && ok "none: refuses without an id" || fail "none: refuses without an id" "exit $code; out: $out"

if [[ $fails -gt 0 ]]; then echo "❌ marquee_babysit_test: $fails failure(s)"; exit 1; fi
echo "✅ marquee_babysit_test: all passed"
