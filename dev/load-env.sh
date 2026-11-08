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
    *)
      # Unquoted. A comment starts at a `#` that FOLLOWS WHITESPACE, and that
      # has to be judged on the UNTRIMMED value, because trimming destroys the
      # very whitespace the rule depends on. Three separate bugs came out of
      # getting this wrong, each fixed by patching the previous patch:
      #
      #   - judged on the trimmed value, `CLIENT_1_PORT=   # use the default`
      #     kept the comment TEXT instead of going empty, so the
      #     `${CLIENT_1_PORT:-7889}` fallback never fired (r2/F3);
      #   - a separate `'#'*` arm added to fix that then read
      #     `AI_PASSWORD=#secret` as a comment and silently exported empty,
      #     where shell assignment gives `#secret` (r4/F3, r4/F5);
      #   - and that arm's `*) ;;` branch was a NO-OP, not a fallthrough -
      #     bash `case` has no fallthrough, whatever its comment claimed - so
      #     `NOTE=#a #b #c` was exported whole where bash assigns `#a`
      #     (advisor pass, BLOCKER).
      #
      # Stripping from the untrimmed value and trimming afterwards is one rule
      # that answers all three, and it DELETES the special arm rather than
      # adding a fourth case to it. Quote the value to keep a literal ` #`.
      __le_val="${__le_t#*=}"
      __le_val="${__le_val%%[[:space:]]#*}"
      __le_val="${__le_val#"${__le_val%%[![:space:]]*}"}"
      __le_val="${__le_val%"${__le_val##*[![:space:]]}"}"
      # A quote MID-VALUE is refused. Quotes are only recognised when the value
      # BEGINS with one, so `NOTE=#"secret"` would otherwise export
      # `#"secret"` - where both shell assignment and the pre-#310 loader give
      # `#secret`, because xargs did shell-style quote removal anywhere in the
      # word. That makes it a silent value change this cycle INTRODUCED, found
      # by re-measuring an advisor note against the pre-cycle commit rather
      # than the previous one.
      #
      # Supporting it would mean mid-word quote removal, i.e. actually
      # implementing shell word splitting, which is a mechanism this file has
      # no business growing. Refusing by name is the sized answer: it trades a
      # value nobody can predict for an error naming the line. The cost is
      # real and is a regression against the original loader for this one
      # shape - `KEY=a"b"c` used to work by accident - so it is documented in
      # `.env.example` rather than left for someone to discover.
      case "$__le_val" in
        *\"*|*\'*)
          echo "$__le_warn a quote mid-value is not supported in '$__le_key' - quote the whole value instead" >&2; continue ;;
      esac ;;
  esac

  # A BACKSLASH in the resulting VALUE is refused by name (design round / F7).
  # It has no escape meaning here, and the closing-quote scan read the quote in
  # `AI_PASSWORD="pa\"` as the closing one and silently exported `pa\` - a
  # value the author did not write, which is the one failure mode this file may
  # not have. Supporting dotenv-style escapes would be a new mechanism for a
  # syntax no key in `.env.example` needs.
  #
  # This check sits AFTER the dispatch on purpose. Round 4 had it before, where
  # it also saw trailing comments, so `CLIENT_1_PORT=8889 # see C:\dir` was
  # refused for a backslash the value does not contain - a false refusal whose
  # message was itself false (r4/F2, r4/F6). Every true refusal survives the
  # move, because the hazard leaves its backslash inside the value: `"pa\"`
  # yields the value `pa\`, and `"a\"b"` is already refused above as
  # unexpected text after the closing quote.
  case "$__le_val" in
    *\\*)
      echo "$__le_warn a backslash is not supported in the value of '$__le_key'" >&2; continue ;;
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

# The side->port default, in ONE place (#312).
#
# Two doors resolve a port when the caller omits one - `start-ai-client-repl.sh`
# (which side's REPL to START) and `ai-eval.sh` (which side's REPL to DIAL) -
# and they each used to own a copy of the rule. Both had half of it:
#
#   start-ai-client-repl.sh  consulted the NAME but not, before #309, for corp
#   ai-eval.sh               honoured the configured PORT but never the NAME
#
# so `ai-eval.sh --stdin corp` with no port dialled the RUNNER (#312), and with
# both REPLs up the runner answered normally - the expression ran, output came
# back, and it was attributed to corp. `send_command` has the mirror-image half:
# it maps the side correctly and hard-codes both numbers (#291).
#
# This file is where the rule belongs rather than a third copy of it: it already
# owns these four values, and both doors already `source` it unconditionally.
# One definition cannot disagree with itself, which is the whole defect class
# (#291, #307, #312) that kept getting filed one door at a time.
#
# The `:-` literals stay INSIDE the function on purpose. The exports above
# always set both names, so they are unreachable in normal use - they are the
# defence-in-depth the #307 author deliberately kept when mirroring
# ai-eval.sh's legacy branch, and moving the rule here must not quietly delete
# it. (Measured: with the function defined but neither variable exported, corp
# still resolves to 7890.)
#
# KNOWN GAP, filed as #313, and NOT closed by moving the arm here: the catch-all
# gives the runner's port to every name that is not `corp`, so
# `start-ai-client-repl.sh corpp` still resolves to 7889 and then succeeds
# against the runner's listener - no REPL started, two pid files naming one
# process, operator told it worked. A name allowlist is not the fix: the hole is
# that the launcher's readiness probe cannot tell its own REPL from someone
# else's, and the `*)` arm is load-bearing for `runner` AND for the `fixed-id`
# legacy default that dev/README.md still documents. #313 needs #293's ownership
# check on the /tmp/ai-client-* paths first. This function preserves that
# semantics exactly; it changes where the arm lives, not what it does.
#
# This does NOT answer ASK #308 (should these ports be configurable at all).
# Under "keep", this is the owner every door should call; under "delete", it is
# one function to remove instead of N copies to find.
client_default_port() {
    case "${1:-}" in
        corp) printf '%s\n' "${CLIENT_2_PORT:-7890}" ;;
        *)    printf '%s\n' "${CLIENT_1_PORT:-7889}" ;;  # runner, and the fixed-id legacy name
    esac
}

# Log configuration (optional, can be noisy)
# echo "Configuration:"
# echo "  GAME_SERVER_PORT: $GAME_SERVER_PORT"
# echo "  CLIENT_1_PORT:    $CLIENT_1_PORT"
# echo "  CLIENT_2_PORT:    $CLIENT_2_PORT"
