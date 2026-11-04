#!/bin/bash
# Load environment variables from .env file if it exists
# and set default ports for Netrunner development

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

# Load .env if present.
#
# This was one line - `export $(grep -v '^#' "$ROOT_DIR/.env" | xargs)` - and
# every way it could go wrong went wrong silently, with exit status 0 (#310):
#
#   - An UNTERMINATED QUOTE makes xargs print nothing and exit 1, so `export`
#     ran with NO ARGUMENTS, which in bash prints the whole exported
#     environment as `declare -x` lines on stdout: 82 lines on this box,
#     including any token in it. None of the file's keys were exported, so
#     every port silently fell back to its default - the collision `.env`
#     exists to prevent - and the caller could not tell, because the status
#     was 0.
#   - Any value containing a SPACE was truncated at the space, quoted or not:
#     the command substitution is UNQUOTED, so the shell word-splits xargs's
#     output again and the quoting xargs honoured is lost.
#   - `grep -v '^#'` only skips a comment in column 1, so an INDENTED comment
#     became arguments, and a TRAILING comment made `#` one:
#     "export: `#': not a valid identifier".
#   - A key with a space in it (`FOO BAR=y`) quietly exported `BAR=y` instead.
#
# So parse it here, one line at a time: a bad line costs that line and is named
# on stderr, and a good line is never collateral. Values are assigned, never
# evaluated - `$(...)` in a .env stays literal text - because this file is
# sourced by every script in dev/.
#
# PER-LINE APPLICATION IS DELIBERATE, and it has one consequence worth naming
# out loud (panel r1/F1). With a malformed `.env` the OLD loader applied no
# line at all, so every port fell back to its default; this one applies the
# file's good lines. `stop-ai-client.sh`'s no-PID-file branch kills by port
# (`LEIN_PID=$(lsof -ti:$REPL_PORT); kill $LEIN_PID`) with no ownership check,
# and `reset.sh` runs that stop BEFORE any start, so whatever moves a port
# moves an unguarded kill with it. Applying the configuration the file asks
# for is the intended behaviour - and the identical kill lands on the identical
# process with a WELL-FORMED `.env`, which the old loader honoured too - but
# this file does redirect that kill in the malformed case, and the ownership
# check that wants fixing sits before the kill, not here: #293 and #294.
#
# A warning NEVER prints a value, only the line number and the key where there
# is one (r1/F7): a malformed `AI_PASSWORD=` line would otherwise write the
# secret to stderr and into `reset.sh`'s log.
#
# The parse runs in a function so that `local` protects the parser's own state.
# Without it, a `.env` key could overwrite the line counter mid-loop and stop
# the rest of the file loading (r1/F6); the `__le_` prefix is reserved and
# refused for the same reason, because `export` on a shadowed local would
# assign the local and lose the value at return.
_netrunner_load_env() {
  local __le_file="$1" __le_lineno=0 __le_line __le_t __le_key __le_val __le_warn
  while IFS= read -r __le_line || [ -n "$__le_line" ]; do
    __le_lineno=$((__le_lineno + 1))
    __le_warn="load-env.sh: $__le_file line $__le_lineno ignored:"

    # Trim surrounding whitespace, then drop blank lines and comments. Unlike
    # the old grep, this sees a comment that is indented.
    __le_t="${__le_line#"${__le_line%%[![:space:]]*}"}"
    __le_t="${__le_t%"${__le_t##*[![:space:]]}"}"
    case "$__le_t" in
      ''|'#'*) continue ;;
      *=*)     ;;
      *)       echo "$__le_warn no '=' on the line" >&2; continue ;;
    esac

    __le_key="${__le_t%%=*}"
    __le_val="${__le_t#*=}"
    # `export FOO=bar` worked by accident before (xargs made `export` a second
    # variable to export); keep accepting it rather than start rejecting it.
    case "$__le_key" in export[[:space:]]*) __le_key="${__le_key#export}" ;; esac
    __le_key="${__le_key#"${__le_key%%[![:space:]]*}"}"
    __le_key="${__le_key%"${__le_key##*[![:space:]]}"}"
    case "$__le_key" in
      ''|[0-9]*|*[!A-Za-z0-9_]*)
        echo "$__le_warn not a usable variable name: '$__le_key'" >&2; continue ;;
      __le_*)
        echo "$__le_warn '$__le_key' is reserved by the loader" >&2; continue ;;
    esac

    # Trim the value at BOTH ends before anything else. The order matters
    # twice: `FOO = "bar"` is then seen as quoted even though a space followed
    # the `=` (r1/F4), and an unquoted `CLIENT_1_PORT= 8889` cannot ship a
    # value with a space in it (r1/F2). That second one is not cosmetic - the
    # padded value reaches `lsof -i:$CLIENT_1_PORT`, which is
    # `ai-bounce.sh:44`'s "are both client REPLs listening" probe and
    # `stop-ai-client.sh`'s kill selector, and lsof answers "unacceptable port
    # specification" and exits 1, so one stray space would silently disable
    # the port probe on both doors.
    __le_val="${__le_val#"${__le_val%%[![:space:]]*}"}"
    __le_val="${__le_val%"${__le_val##*[![:space:]]}"}"
    case "$__le_val" in
      \"*\"|\'*\')
        # A closed quote pair: the interior is taken verbatim, so a `#` or a
        # space inside it survives. That is what quoting is for.
        __le_val="${__le_val:1:${#__le_val}-2}" ;;
      \"*|\'*)
        # Opens with a quote and does not close with one. Before calling that
        # unterminated, drop a trailing ` # comment` and look again: a QUOTED
        # value with a comment after it is well formed, and the old loader did
        # apply it, so refusing it lost a setting AND misnamed the reason
        # (r1/F3, r1/F5).
        __le_val="${__le_val%%[[:space:]]#*}"
        __le_val="${__le_val%"${__le_val##*[![:space:]]}"}"
        case "$__le_val" in
          \"*\"|\'*\') __le_val="${__le_val:1:${#__le_val}-2}" ;;
          *) echo "$__le_warn unterminated quote in the value of '$__le_key'" >&2; continue ;;
        esac ;;
      *)
        # Unquoted: a trailing `# comment` is an annotation, which is how
        # `.env.example` writes its own. Quote the value to keep a literal ` #`.
        __le_val="${__le_val%%[[:space:]]#*}"
        __le_val="${__le_val%"${__le_val##*[![:space:]]}"}" ;;
    esac

    export "$__le_key=$__le_val"
  done < "$__le_file"
}
if [ -f "$ROOT_DIR/.env" ]; then
  _netrunner_load_env "$ROOT_DIR/.env"
fi
unset -f _netrunner_load_env

# Default ports
export GAME_SERVER_PORT="${GAME_SERVER_PORT:-7888}"
export CLIENT_1_PORT="${CLIENT_1_PORT:-7889}"
export CLIENT_2_PORT="${CLIENT_2_PORT:-7890}"
export WEB_SERVER_PORT="${WEB_SERVER_PORT:-1042}"

# Log configuration (optional, can be noisy)
# echo "Configuration:"
# echo "  GAME_SERVER_PORT: $GAME_SERVER_PORT"
# echo "  CLIENT_1_PORT:    $CLIENT_1_PORT"
# echo "  CLIENT_2_PORT:    $CLIENT_2_PORT"
