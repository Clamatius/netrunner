#!/bin/bash
# AI Client nREPL eval script
# Sends commands to the AI Client REPL
# Usage: ./ai-eval.sh [client_name] [port] <clojure-expression>
# Usage: ./ai-eval.sh [client_name] [port] <clojure-expression>
# Old usage still supported: ./ai-eval.sh <clojure-expression>

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/load-env.sh"

TIMEOUT=${TIMEOUT:-10}

# The shell timeout is a BACKSTOP against a wedged REPL — one that accepts the
# socket and then never answers — not a second copy of the caller's own deadline.
# Several REPL entry points park for their full budget on purpose (`wait` 300s,
# monitor-run's 300s park, auto-continue-loop!'s 300s), and a shell kill at
# exactly TIMEOUT would race them at the moment they were about to answer
# cleanly. The grace exists so the REPL's own deadline always wins that race.
TIMEOUT_GRACE=${TIMEOUT_GRACE:-15}

# Validate rather than let $(( )) invent a budget, and normalize to DECIMAL.
# Three separate ways an innocent-looking value went wrong (round-3 guest MAJOR,
# all reproduced):
#   TIMEOUT=foo  -> arithmetic ZERO, which since #190 means "no backstop" — the
#                   exact opposite of the small number the caller asked for.
#   TIMEOUT=010  -> bash reads a leading zero as OCTAL, so this is 8, and the
#                   backstop is silently two seconds short of what was asked.
#   TIMEOUT=08   -> "value too great for base": the script ABORTS mid-command.
#   TIMEOUT=<max int> -> wraps NEGATIVE when the grace is added.
# `wait` passes the seat's own number straight through, so `wait 08` was a live
# path to two of these.
#
# EX_CONFIG (78), not a generic code: send_command distinguishes "this did not
# run" from "the REPL answered with an error", and 1/2 are already spoken for.
for _v in TIMEOUT TIMEOUT_GRACE; do
    _val="${!_v}"
    case "$_val" in
        ''|*[!0-9]*)
            echo "❌ $_v must be a whole number of seconds, got: '$_val'" >&2
            echo "   (TIMEOUT=0 means no backstop; there is no other special value.)" >&2
            exit 78
            ;;
    esac
    # Bound the LENGTH before any arithmetic, so nothing can overflow into a
    # negative budget. 7 digits is ~115 days.
    if [ "${#_val}" -gt 7 ]; then
        echo "❌ $_v is ${_val}s, which is not a timeout anyone meant." >&2
        exit 78
    fi
done
# 10# forces base 10 regardless of leading zeros.
TIMEOUT=$((10#$TIMEOUT))
TIMEOUT_GRACE=$((10#$TIMEOUT_GRACE))

# TIMEOUT=0 means NO backstop. Exactly one caller needs it and it is not an
# escape hatch for "this felt slow": `send_command bot-watch` runs
# ai-heuristic-corp/watch-for-runs!, an intentionally infinite poll loop whose
# own help text says "Ctrl+C to stop". Any finite number truncates a healthy
# command there, and a wedge is indistinguishable from "no runs happening"
# anyway, so the honest budget is none. (Guest panel MAJOR, round 1 of #190.)
if [ "$TIMEOUT" = "0" ]; then
    KILL_AFTER=0
else
    KILL_AFTER=$((TIMEOUT + TIMEOUT_GRACE))
fi

# Parse arguments - support both old and new usage plus stdin mode
# Stdin mode: ./ai-eval.sh --stdin client_name port < file_with_expression
if [ "${1:-}" == "--stdin" ]; then
    # Stdin mode: read expression from stdin to avoid shell escaping
    CLIENT_NAME="${2:-fixed-id}"
    REPL_PORT="${3:-7889}"
    EXPRESSION=$(cat)
elif [ $# -eq 1 ]; then
    # Old usage: just expression
    CLIENT_NAME="fixed-id"
    REPL_PORT="${CLIENT_1_PORT:-7889}"
    EXPRESSION="$1"
elif [ $# -eq 3 ]; then
    # New usage: client_name port expression
    CLIENT_NAME="$1"
    REPL_PORT="$2"
    EXPRESSION="$3"
else
    echo "Usage: $0 [client_name] [port] <clojure-expression>"
    echo "       $0 --stdin [client_name] [port] < file_with_expression"
    echo "Examples:"
    echo "  $0 '(ai-actions/status)'"
    echo "  $0 runner $CLIENT_1_PORT '(ai-actions/status)'"
    echo "  echo '(ai-actions/install-card! \"test\")' | $0 --stdin corp 7890"
    exit 1
fi

# Check if AI client REPL is running
if [ -f /tmp/ai-client-${CLIENT_NAME}.pid ]; then
    PID=$(cat /tmp/ai-client-${CLIENT_NAME}.pid)
    if ! ps -p $PID > /dev/null 2>&1; then
        echo "❌ AI Client REPL '$CLIENT_NAME' not running (stale PID file)"
        echo "   Start it with: ./dev/start-ai-client-repl.sh $CLIENT_NAME $REPL_PORT"
        rm /tmp/ai-client-${CLIENT_NAME}.pid
        exit 1
    fi
else
    # Silently continue - PID file may not exist but client could still be running
    : # no-op
fi

# Use Babashka if available (much faster), otherwise fall back to lein
if command -v bb &> /dev/null; then
    # Babashka nREPL client - fast and designed for scripting
    # Use temp file to avoid shell escaping issues with ! and other special chars
    EXPR_FILE=$(mktemp)
    printf '%s' "$EXPRESSION" > "$EXPR_FILE"
    trap "rm -f '$EXPR_FILE'" EXIT

    # #190: this branch used to run bare. `check-ai.sh` only takes its warm path
    # when `command -v bb` succeeds, so whenever check-ai.sh was the caller this
    # was the branch taken — and the TIMEOUT it passed was read into a variable
    # that only the (unreachable) lein fallback below ever used. The bencode read
    # is blocking, so a wedged REPL hung `make check` indefinitely with no message.
    # KILL_AFTER=0 => run bare (see TIMEOUT=0 above). `timeout 0` means "no
    # limit" in GNU coreutils but NOT everywhere, so branch rather than rely on it.
    if [ "$KILL_AFTER" = "0" ]; then BB_TIMEOUT=(); else BB_TIMEOUT=(timeout "$KILL_AFTER"); fi
    "${BB_TIMEOUT[@]}" bb -e "(require '[bencode.core :as b] '[clojure.java.io :as io])
          ;; Pin UTF-8 for BOTH the slurp of our code (which may carry accented
          ;; card names like \"Karunā\") and the decode of the nREPL response.
          ;; Under a C locale a JVM default charset mis-decodes multibyte chars
          ;; into replacement chars, so the card lookup sees \"Karun\" + garbage
          ;; and fails (issue #37). bb itself defaults to UTF-8, but pinning makes
          ;; this robust regardless of host charset.
          (let [expr-file \"$EXPR_FILE\"
                expr-code (slurp expr-file :encoding \"UTF-8\")]
            (with-open [sock (java.net.Socket. \"localhost\" $REPL_PORT)]
              (let [in (java.io.PushbackInputStream. (io/input-stream sock))
                    out (io/output-stream sock)
                    bytes->str #(when % (String. (bytes %) \"UTF-8\"))
                    result-value (atom nil)]
                (b/write-bencode out {\"op\" \"eval\" \"code\" expr-code})
              (.flush out)
              (loop []
                (when-let [response (b/read-bencode in)]
                  (when-let [val (get response \"value\")]
                    (let [val-str (bytes->str val)]
                      (print val-str)
                      (flush)
                      (reset! result-value val-str)))
                  (when-let [out-msg (get response \"out\")]
                    (print (bytes->str out-msg))
                    (flush))
                  (when-let [err (get response \"err\")]
                    (binding [*out* *err*]
                      (print (bytes->str err))
                      (flush)))
                  (when-let [ex (get response \"ex\")]
                    (binding [*out* *err*]
                      (println (str \"Exception: \" (bytes->str ex)))
                      (flush))
                    (reset! result-value :error))
                  (when-let [root-ex (get response \"root-ex\")]
                    (binding [*out* *err*]
                      (println (str \"Root Exception: \" (bytes->str root-ex)))
                      (flush))
                    (reset! result-value :error))
                  (when-not (get response \"status\")
                    (recur))))
                ;; Check if result indicates error and exit with code 1
                (when (= @result-value :error)
                  (System/exit 1))
                (when @result-value
                  (try
                    (let [result (read-string @result-value)]
                      (when (and (map? result) (= :error (:status result)))
                        (System/exit 1)))
                    (catch Exception _ nil))))))"
    BB_STATUS=$?
    if [ "$BB_STATUS" -eq 124 ]; then
        echo "❌ REPL on port $REPL_PORT did not answer within ${KILL_AFTER}s" >&2
        echo "   (TIMEOUT=${TIMEOUT}s + ${TIMEOUT_GRACE}s grace). The socket accepted the" >&2
        echo "   connection but the eval never returned — the REPL is wedged, not slow." >&2
        echo "   Recover with: ./dev/ai-bounce.sh   (or: make reset)" >&2
    fi
    exit $BB_STATUS
else
    # Fallback to lein repl :connect (slower, for compatibility). Unlike bb, this
    # is a stock JVM that honors file.encoding — under a C locale it defaults to a
    # non-UTF-8 charset and mangles accented card names piped on stdin (issue #37).
    # Pin UTF-8 so multibyte input/output survives this path too.
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
    if [ "$KILL_AFTER" = "0" ]; then LEIN_TIMEOUT=(); else LEIN_TIMEOUT=(timeout "$KILL_AFTER"); fi
    "${LEIN_TIMEOUT[@]}" lein repl :connect localhost:$REPL_PORT <<EOF 2>&1 | \
        grep -v "^user=>" | \
        grep -v "find-doc" | \
        grep -v "^  #_=>" | \
        grep -v "Welcome back!" | \
        grep -v "Connecting to nREPL" | \
        grep -v "REPL-y" | \
        grep -v "Clojure" | \
        grep -v "OpenJDK" | \
        grep -v "Docs:" | \
        grep -v "Source:" | \
        grep -v "Javadoc:" | \
        grep -v "Exit:" | \
        grep -v "Results:" | \
        grep -v "Bye for now" | \
        sed 's/\x1b\[[0-9;]*[A-Za-z]//g' | \
        grep -v "^[[:space:]]*$"
$EXPRESSION
EOF
    # PIPESTATUS[0], not $?. A pipeline's status is its LAST command's, and this
    # one ends in `grep -v` — so a `timeout` kill was reported as whatever grep
    # made of the partial output: 0 if any line survived the filters, 1 if none,
    # never 124. send_command's timeout diagnosis keys on exactly 124, so on this
    # branch a killed eval read as success and the dispatcher carried on into
    # after_action. (Round-2 guest MAJOR. Pre-existing as a status bug; it only
    # became load-bearing when something started depending on 124.)
    #
    # This branch is unreachable while `bb` is installed, which is why the guard
    # in send_command_timeout_test.sh forces it explicitly with a PATH that hides
    # bb — testing "whichever backend happens to be installed" is how it stayed
    # green over this.
    LEIN_STATUS=${PIPESTATUS[0]}
    if [ "$LEIN_STATUS" -eq 124 ]; then
        echo "❌ REPL on port $REPL_PORT did not answer within ${KILL_AFTER}s" >&2
        echo "   (TIMEOUT=${TIMEOUT}s + ${TIMEOUT_GRACE}s grace). The socket accepted the" >&2
        echo "   connection but the eval never returned — the REPL is wedged, not slow." >&2
        echo "   Recover with: ./dev/ai-bounce.sh   (or: make reset)" >&2
    fi
    exit $LEIN_STATUS
fi
