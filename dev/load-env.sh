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
# A warning names the line number, and the key only once the key is known to
# be a usable variable name. The text before a bad `=` is withheld: a line
# where a space replaced the `=` puts the VALUE in that position
# (`AI_TOKEN dGhpc2lzc2VjcmV0==`), and load-env.sh is sourced by eight
# scripts including `reset.sh`, which tees its output to a log (r1/F7, r2/F5).
#
# `__le_` is reserved: a `.env` key may not start with it, because the parser's
# own state lives in those names and a key that overwrote the line counter
# mid-loop would drop the rest of the file (r1/F6). Round 1 wrapped the loop in
# a function to get `local` as well; round 2 showed the refusal arm is what
# fixes the defect and the function only added scoping, so the function is
# gone and the refusal stayed (r2/F6).
if [ -f "$ROOT_DIR/.env" ]; then
_le_lineno=0
while IFS= read -r __le_line || [ -n "$__le_line" ]; do
  _le_lineno=$((_le_lineno + 1))
  __le_warn="load-env.sh: $ROOT_DIR/.env line $_le_lineno ignored:"

  # Trim surrounding whitespace, then drop blank lines and comments. Unlike the
  # old grep, this sees a comment that is indented.
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
      echo "$__le_warn the text before the '=' is not a usable variable name" >&2; continue ;;
    __le_*|_le_*)
      echo "$__le_warn '$__le_key' is reserved by the loader" >&2; continue ;;
  esac

  # Trim the value at both ends. Doing it first means `FOO = "bar"` is still
  # seen as quoted, and an unquoted `CLIENT_1_PORT= 8889` cannot ship a value
  # with a space in it - which matters because the padded value reaches
  # `lsof -i:$CLIENT_1_PORT`, the probe behind `ai-bounce.sh:44`'s "are both
  # client REPLs listening" check and `stop-ai-client.sh`'s kill selector,
  # where lsof answers "unacceptable port specification" and exits 1 (r1/F2,
  # r1/F4).
  __le_val="${__le_val#"${__le_val%%[![:space:]]*}"}"
  __le_val="${__le_val%"${__le_val##*[![:space:]]}"}"
  # A BACKSLASH is refused outright (design round / F7). It has no escape
  # meaning here, and the closing-quote scan read the quote in
  # `AI_PASSWORD="pa\"` as the closing one and silently exported `pa\` - a
  # value the author did not write, which is the one failure mode this file
  # may not have. Supporting dotenv-style escapes would be a new mechanism for
  # a syntax no key in `.env.example` needs; refusing by name is the sized
  # answer, and it says what to do instead.
  case "$__le_val" in
    *\\*)
      echo "$__le_warn a backslash is not supported in the value of '$__le_key'" >&2; continue ;;
  esac
  case "$__le_val" in
    \"*|\'*)
      # Quoted. Find the CLOSING quote - the next one after the opening quote,
      # not the last one on the line - and require that what follows it is
      # nothing, or a comment. Rounds 1 and 2 both cut at the first ` #`
      # instead, which refused `"a # b" # c` outright and, worse, silently
      # exported `8889" # use "runner` for `CLIENT_1_PORT="8889" # use
      # "runner"` with no warning at all (r2/F1, r2/F7).
      __le_q="${__le_val%"${__le_val#?}"}"
      __le_rest="${__le_val#?}"
      case "$__le_rest" in
        *"$__le_q"*) ;;
        *) echo "$__le_warn unterminated quote in the value of '$__le_key'" >&2; continue ;;
      esac
      __le_inner="${__le_rest%%"$__le_q"*}"
      __le_after="${__le_rest#"$__le_inner$__le_q"}"
      # What follows the closing quote is either nothing, or whitespace and
      # then a comment. A `#` FLUSH against the closing quote is not a comment:
      # `AI_PASSWORD="pa"#ss` silently exported `pa`, where shell assignment
      # gives `pa#ss` (design round / F6). Requiring the whitespace makes this
      # arm agree with the unquoted one below, where a `#` inside a word is
      # content, so the whole grammar is one rule: a comment starts at a `#`
      # that follows whitespace.
      case "$__le_after" in
        '') __le_val="$__le_inner" ;;
        [[:space:]]*)
          __le_tail="${__le_after#"${__le_after%%[![:space:]]*}"}"
          case "$__le_tail" in
            ''|'#'*) __le_val="$__le_inner" ;;
            *) echo "$__le_warn unexpected text after the closing quote in the value of '$__le_key'" >&2; continue ;;
          esac ;;
        *) echo "$__le_warn unexpected text after the closing quote in the value of '$__le_key'" >&2; continue ;;
      esac ;;
    '#'*)
      # The whole value is a comment, so the value is empty - and an EMPTY
      # value is what lets `${CLIENT_1_PORT:-7889}` fall back. Trimming before
      # this check is what made `CLIENT_1_PORT=   # use the default` export the
      # comment text, which is non-empty, so the fallback never fired and the
      # port went to lsof as prose (r2/F3).
      __le_val='' ;;
    *)
      # Unquoted: a ` #` begins a trailing annotation. Quote the value to keep
      # a literal one. (`.env.example` writes only whole-line comments, so this
      # is for hand-written files, not for a precedent in the tree - r2/F2.)
      __le_val="${__le_val%%[[:space:]]#*}"
      __le_val="${__le_val%"${__le_val##*[![:space:]]}"}" ;;
  esac

  # A failed assignment must cost this line and no more. `UID=5` in a .env made
  # the assignment fail with "readonly variable", and eight of the callers run
  # under `set -e`, so the CALLER aborted on the spot - which contradicted the
  # promise above (r2/F4). Containing it needs `declare -x` rather than
  # `export`: `export` is a SPECIAL builtin, and under `set -e` a failed
  # assignment through one exits the shell even from inside an `if` condition.
  # `declare -x` is not special, so the condition really does catch it, and at
  # top level it exports exactly as `export` does. Measured both ways.
  if ! declare -x "$__le_key=$__le_val" 2>/dev/null; then
    echo "$__le_warn '$__le_key' could not be set (readonly?)" >&2
    continue
  fi
done < "$ROOT_DIR/.env"
fi
unset _le_lineno __le_line __le_warn __le_t __le_key __le_val __le_q __le_rest __le_inner __le_after __le_tail

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
