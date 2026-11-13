#!/bin/bash
# umpire-ping --wake pages the owner's phone. An unattended run with nobody to
# page must be able to switch that off: on 2026-11-13 a seat in an unattended
# dev/match game paged the owner at 02:00 because an API outage killed its
# opponent's seat. The switch is a file, logs/NO-WAKE, so it covers every seat
# process however it was launched (env does not reach a codex/claude seat reliably).
#
# curl is stubbed on PATH: this test can never reach ntfy.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
ok()  { echo "ok   [$1]"; PASS=$((PASS+1)); }
bad() { echo "NOT OK [$1]"; echo "       $2"; FAIL=$((FAIL+1)); }

# A sandbox copy: umpire-ping resolves its mailbox and the switch from its own dir.
mkdir -p "$TMP/repo/dev" "$TMP/repo/logs" "$TMP/bin"
cp "$REPO/dev/umpire-ping" "$TMP/repo/dev/umpire-ping"
cat > "$TMP/bin/curl" <<'STUB'
#!/bin/bash
echo "$*" >> "$CURL_LOG"
exit 0
STUB
chmod +x "$TMP/bin/curl"
export CURL_LOG="$TMP/curl.log"; : > "$CURL_LOG"
run() { PATH="$TMP/bin:$PATH" "$TMP/repo/dev/umpire-ping" "$@" 2>&1; }

echo "--- control: --wake pages when the switch is absent ---"
out="$(run runner --wake "control ping")"
if [ -s "$CURL_LOG" ] && grep -q "ntfy sent" <<<"$out"; then ok wake-pages-without-switch
else bad wake-pages-without-switch "expected the stub curl to be called; out: $out"; fi

echo "--- logs/NO-WAKE: --wake records the ping and pages nobody ---"
: > "$CURL_LOG"; rm -f "$TMP/repo/dev/.umpire/.last-wake"
: > "$TMP/repo/logs/NO-WAKE"
out="$(run runner --wake "switched off ping")"
if [ ! -s "$CURL_LOG" ]; then ok no-page-with-switch
else bad no-page-with-switch "curl was called: $(cat "$CURL_LOG")"; fi
if grep -q "switched off ping" "$TMP/repo/dev/.umpire/mailbox.log"; then ok ping-still-recorded
else bad ping-still-recorded "the mailbox lost the ping"; fi
want="🔕 --wake is switched off for this run (logs/NO-WAKE): nobody was paged. The ping is in the mailbox; keep waiting and do nothing destructive."
if grep -qxF "$want" <<<"$out"; then ok says-nobody-was-paged
else bad says-nobody-was-paged "missing line: $want
       got: $out"; fi
if grep -q "ntfy sent" <<<"$out"; then bad no-false-paged-line "output claims a page was sent"; else ok no-false-paged-line; fi

echo; echo "Passed: $PASS   Failed: $FAIL"
[ "$FAIL" -eq 0 ] && echo "✅ umpire_ping_test: all passed" || { echo "❌ umpire_ping_test: $FAIL failed"; exit 1; }
