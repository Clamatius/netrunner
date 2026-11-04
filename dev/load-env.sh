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
#     every port silently fell back to its default - the collision .env exists
#     to prevent - and the caller could not tell, because the status was 0.
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
if [ -f "$ROOT_DIR/.env" ]; then
  _env_lineno=0
  while IFS= read -r _env_line || [ -n "$_env_line" ]; do
    _env_lineno=$((_env_lineno + 1))
    _env_warn="load-env.sh: $ROOT_DIR/.env line $_env_lineno ignored:"

    # Trim surrounding whitespace, then drop blank lines and comments. Unlike
    # the old grep, this sees a comment that is indented.
    _env_t="${_env_line#"${_env_line%%[![:space:]]*}"}"
    _env_t="${_env_t%"${_env_t##*[![:space:]]}"}"
    case "$_env_t" in
      ''|'#'*) continue ;;
      *=*)     ;;
      *)       echo "$_env_warn no '=' in: $_env_t" >&2; continue ;;
    esac

    _env_key="${_env_t%%=*}"
    _env_val="${_env_t#*=}"
    # `export FOO=bar` worked by accident before (xargs made `export` a second
    # variable to export); keep accepting it rather than start rejecting it.
    case "$_env_key" in export[[:space:]]*) _env_key="${_env_key#export}" ;; esac
    _env_key="${_env_key#"${_env_key%%[![:space:]]*}"}"
    _env_key="${_env_key%"${_env_key##*[![:space:]]}"}"
    case "$_env_key" in
      ''|[0-9]*|*[!A-Za-z0-9_]*)
        echo "$_env_warn not a usable variable name: '$_env_key'" >&2; continue ;;
    esac

    # A quoted value is taken verbatim between the quotes. An unquoted one
    # loses a trailing `# comment` and any whitespace before it - which is how
    # `.env.example` writes its own annotations. An opening quote with no
    # closing one is the #310 case: refuse the line and say so.
    case "$_env_val" in
      \"*\"|\'*\')
        _env_val="${_env_val:1:${#_env_val}-2}" ;;
      \"*|\'*)
        echo "$_env_warn unterminated quote in value: $_env_val" >&2; continue ;;
      *)
        _env_val="${_env_val%%[[:space:]]#*}"
        _env_val="${_env_val%"${_env_val##*[![:space:]]}"}" ;;
    esac

    export "$_env_key=$_env_val"
  done < "$ROOT_DIR/.env"
  unset _env_lineno _env_line _env_warn _env_t _env_key _env_val
fi

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
