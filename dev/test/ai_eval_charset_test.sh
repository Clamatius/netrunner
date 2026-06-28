#!/usr/bin/env bash
# ai_eval_charset_test.sh — regression guard for UTF-8 handling in ai-eval.sh.
#
# Why this exists: card-text "Karunā" reported "Card not found: Karun" in the
# cross-model marquee (issue #37). Under a C locale a JVM-default-charset decode
# turns the 2-byte ā into replacement chars, so the card lookup sees "Karun" +
# garbage and fails. ai-eval.sh sends our Clojure code and decodes the nREPL
# response, so it must pin UTF-8 on BOTH legs regardless of host locale.
#
# Two checks:
#   1. behavioral — round-trip a multibyte string through the SAME transport
#      ai-eval.sh uses (slurp :encoding UTF-8 → bencode write → bencode read →
#      String. … "UTF-8"); the accented name must survive byte-exact.
#   2. source pin-guard — the explicit UTF-8 pins must still be present in
#      ai-eval.sh, so the fix can't silently regress (the bug shipped twice in
#      the sibling EDN filter precisely because nothing guarded the source).

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AI_EVAL="$SCRIPT_DIR/../ai-eval.sh"
fails=0

# --- 1. behavioral round-trip through the real bencode transport ------------
if command -v bb >/dev/null 2>&1; then
    EXPR_FILE=$(mktemp)
    printf '%s' '(ai-actions/show-card-text "Karunā")' > "$EXPR_FILE"
    trap "rm -f '$EXPR_FILE'" EXIT
    roundtrip=$(bb -e "(require '[bencode.core :as b])
      (let [code (slurp \"$EXPR_FILE\" :encoding \"UTF-8\")
            baos (java.io.ByteArrayOutputStream.)]
        (b/write-bencode baos {\"code\" code})
        (let [arr (.toByteArray baos)
              in (java.io.PushbackInputStream. (java.io.ByteArrayInputStream. arr))
              decoded (b/read-bencode in)]
          (print (String. ^bytes (get decoded \"code\") \"UTF-8\"))))" 2>/dev/null)
    if [[ "$roundtrip" == '(ai-actions/show-card-text "Karunā")' ]]; then
        echo "ok   [bencode-roundtrip] accented name survives slurp+bencode+decode"
    else
        echo "FAIL [bencode-roundtrip] expected the accented name intact, got: $roundtrip" >&2
        fails=$((fails+1))
    fi
else
    echo "skip [bencode-roundtrip] bb not on PATH"
fi

# --- 2. source pin-guard: explicit UTF-8 must be present in ai-eval.sh -------
assert_pinned() {
    local name="$1" pattern="$2"
    if grep -qF -- "$pattern" "$AI_EVAL"; then
        echo "ok   [$name] UTF-8 pin present"
    else
        echo "FAIL [$name] missing UTF-8 pin '$pattern' in ai-eval.sh (issue #37 would recur)" >&2
        fails=$((fails+1))
    fi
}
# bb path: slurp our code as UTF-8 and decode the response as UTF-8
assert_pinned "bb-slurp-utf8"   'slurp expr-file :encoding \"UTF-8\"'
assert_pinned "bb-decode-utf8"  '(String. (bytes %) \"UTF-8\")'
# lein fallback: stock JVM honors file.encoding, so pin it
assert_pinned "lein-file-encoding" '-Dfile.encoding=UTF-8'

echo "---"
if [[ "$fails" -ne 0 ]]; then
    echo "ai_eval_charset_test: $fails FAILED"; exit 1
fi
echo "ai_eval_charset_test: ALL PASSED"
