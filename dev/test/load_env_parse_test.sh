#!/usr/bin/env bash
# load_env_parse_test.sh — a malformed .env must cost you that LINE, not the
# whole file and not the contents of your environment (#310).
#
# Why this exists. `dev/load-env.sh` loaded the file with one line:
#
#     export $(grep -v '^#' "$ROOT_DIR/.env" | xargs)
#
# and every way that can go wrong went wrong silently, with exit status 0:
#
#   - An UNTERMINATED QUOTE makes xargs print nothing and exit 1, so `export`
#     runs with NO ARGUMENTS — which in bash prints the entire exported
#     environment as `declare -x` lines on STDOUT. Measured on this box before
#     the fix: 82 lines, including a token planted in the environment. The
#     file's own keys were never exported, so every port silently fell back to
#     its default — which is precisely the collision .env exists to avoid, and
#     the caller cannot tell, because the status is 0.
#   - A value containing a SPACE was truncated at the space: `AI_USERNAME=two
#     words` exported `AI_USERNAME=two`.
#   - A TRAILING COMMENT (`CLIENT_1_PORT=8889  # workspace two`) made `#` an
#     argument: "export: `#': not a valid identifier" on stderr.
#
# What this test pins. Line-level containment (a bad line never costs a good
# one), the warning that replaces the silence, and the two properties the old
# line got right that a hand-rolled parser could easily lose: quotes are
# stripped, and values are NEVER evaluated — `$(...)` in a .env must stay
# literal text, because this file is sourced by every script in dev/.
#
# HOW it tests. The real load-env.sh is copied into a scratch root beside a
# scratch .env and sourced by a fresh bash, because the thing under test is
# top-level wiring (the parse and the `export`s) with no function to call. The
# assertions read the resulting variables out of that same bash, so what is
# checked is what a caller would actually get.

set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REAL_LOAD_ENV="$(cd "$SCRIPT_DIR/.." && pwd -P)/load-env.sh"

PASS=0; FAIL=0
pass() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

[ -f "$REAL_LOAD_ENV" ] || { echo "load_env_parse_test: no $REAL_LOAD_ENV"; exit 1; }

SANDBOX=$(mktemp -d)
trap 'rm -rf "$SANDBOX"' EXIT
mkdir -p "$SANDBOX/dev"
cp "$REAL_LOAD_ENV" "$SANDBOX/dev/load-env.sh"

# Source load-env.sh in a fresh bash with the given .env in place; record the
# variables it produced, plus stdout and stderr, into $SANDBOX.
#   $1 = .env contents, or the literal NO_ENV_FILE for "no .env at all"
load_env_with() {
    if [ "$1" = NO_ENV_FILE ]; then rm -f "$SANDBOX/.env"; else printf '%s\n' "$1" > "$SANDBOX/.env"; fi
    rm -f "$SANDBOX/out" "$SANDBOX/err" "$SANDBOX/vars"
    env -i PATH="$PATH" HOME="$HOME" \
        LOAD_ENV_CANARY='tok-do-not-print-me' \
        bash -c '
          source "$1/dev/load-env.sh" >"$1/out" 2>"$1/err"
          {
            echo "GAME_SERVER_PORT=${GAME_SERVER_PORT-<unset>}"
            echo "CLIENT_1_PORT=${CLIENT_1_PORT-<unset>}"
            echo "CLIENT_2_PORT=${CLIENT_2_PORT-<unset>}"
            echo "WEB_SERVER_PORT=${WEB_SERVER_PORT-<unset>}"
            echo "AI_USERNAME=${AI_USERNAME-<unset>}"
            echo "NOTE=${NOTE-<unset>}"
            echo "LITERAL=${LITERAL-<unset>}"
            echo "__le_lineno=${__le_lineno-<unset>}"
          } > "$1/vars"
        ' _ "$SANDBOX"
}
var()    { sed -n "s/^$1=//p" "$SANDBOX/vars"; }
stdout() { cat "$SANDBOX/out"; }
stderr() { cat "$SANDBOX/err"; }

assert_var() {  # label, name, expected
    got=$(var "$2")
    [ "$got" = "$3" ] && pass "$1" || fail "$1: $2 is [$got], expected [$3]"
}
assert_stdout_silent() {
    n=$(grep -c . "$SANDBOX/out")
    if [ "$n" -eq 0 ]; then pass "$1: stdout silent"
    else fail "$1: stdout printed $n line(s), first: $(head -1 "$SANDBOX/out")"; fi
}
assert_no_env_dump() {
    if grep -q 'declare -x' "$SANDBOX/out" "$SANDBOX/err" 2>/dev/null; then
        fail "$1: dumped the environment (declare -x lines)"
    elif grep -q 'tok-do-not-print-me' "$SANDBOX/out" "$SANDBOX/err" 2>/dev/null; then
        fail "$1: leaked an environment value it was never given"
    else pass "$1: no environment dump"; fi
}
assert_stderr_has() {
    case "$(stderr)" in *"$2"*) pass "$1" ;; *) fail "$1: stderr lacks [$2], stderr was: $(stderr)" ;; esac
}
assert_stderr_lacks() {
    case "$(stderr)" in *"$2"*) fail "$1: stderr contains [$2]: $(stderr)" ;; *) pass "$1" ;; esac
}

echo "no .env at all → built-in defaults"
load_env_with NO_ENV_FILE
assert_var "defaults: game server"  GAME_SERVER_PORT 7888
assert_var "defaults: runner client" CLIENT_1_PORT 7889
assert_var "defaults: corp client"   CLIENT_2_PORT 7890
assert_var "defaults: web server"    WEB_SERVER_PORT 1042
assert_stdout_silent "defaults"

echo
echo "well-formed .env → every key taken (control)"
load_env_with 'GAME_SERVER_PORT=8888
CLIENT_1_PORT=8889
CLIENT_2_PORT=8890
WEB_SERVER_PORT=2042'
assert_var "well-formed: game server" GAME_SERVER_PORT 8888
assert_var "well-formed: corp client" CLIENT_2_PORT 8890
assert_stdout_silent "well-formed"
assert_stderr_lacks "well-formed: no warnings" "load-env.sh"

echo
echo "unterminated quote → the LINE is lost, nothing else is (#310)"
load_env_with 'GAME_SERVER_PORT=8888
AI_USERNAME="unterminated
CLIENT_2_PORT=8890'
assert_stdout_silent  "unterminated quote"
assert_no_env_dump    "unterminated quote"
assert_var "unterminated quote: the line before it survived" GAME_SERVER_PORT 8888
assert_var "unterminated quote: the line after it survived"  CLIENT_2_PORT 8890
assert_var "unterminated quote: the bad key is not set"      AI_USERNAME '<unset>'
assert_stderr_has "unterminated quote: named on stderr" "line 2"

echo
echo "value containing a space → kept whole, not truncated"
load_env_with 'AI_USERNAME=two words
CLIENT_1_PORT=8889'
assert_var "spaced value: whole value kept" AI_USERNAME 'two words'
assert_var "spaced value: next line unharmed" CLIENT_1_PORT 8889

echo
echo "trailing comment → stripped, not treated as a key"
load_env_with 'GAME_SERVER_PORT=8888   # second workspace
CLIENT_1_PORT=8889'
assert_var "trailing comment: value is clean" GAME_SERVER_PORT 8888
assert_var "trailing comment: next line unharmed" CLIENT_1_PORT 8889
assert_stderr_lacks "trailing comment: no identifier error" "not a valid identifier"

echo
echo "quoting"
load_env_with 'AI_USERNAME="quoted-ok"
NOTE='"'"'single quoted'"'"'
LITERAL=$(echo pwned)'
assert_var "double quotes stripped" AI_USERNAME 'quoted-ok'
assert_var "single quotes stripped" NOTE 'single quoted'
assert_var "a command substitution stays LITERAL text" LITERAL '$(echo pwned)'

echo
echo "bogus keys are named and skipped, the file keeps loading"
load_env_with '2FOO=x
FOO BAR=y
no-equals-sign-at-all
CLIENT_2_PORT=8890'
assert_var "bogus keys: later good line still loaded" CLIENT_2_PORT 8890
assert_stderr_has "bogus key: leading digit named"  "line 1"
assert_stderr_has "bogus key: space in name named"  "line 2"
assert_stderr_has "bogus key: missing '=' named"    "line 3"
assert_no_env_dump "bogus keys"

echo
echo "blank lines and indented comments are ignored quietly"
load_env_with '
   # indented comment
	# tab-indented comment

CLIENT_1_PORT=8889
'
assert_var "blank/comment lines: real key still loaded" CLIENT_1_PORT 8889
assert_stderr_lacks "blank/comment lines: no warnings" "load-env.sh"

echo
echo "a value's whitespace is trimmed at BOTH ends (r1/F2, r1/F4)"
load_env_with 'CLIENT_1_PORT= 8889
CLIENT_2_PORT=8890 
AI_USERNAME=  "  interior kept  "  
NOTE =  quoted-by-spacing  '
assert_var "leading space in a port value is trimmed"  CLIENT_1_PORT 8889
assert_var "trailing space in a port value is trimmed" CLIENT_2_PORT 8890
assert_var "a padded QUOTED value is still seen as quoted, interior preserved" AI_USERNAME '  interior kept  '
assert_var "a space before the '=' does not defeat the parse" NOTE 'quoted-by-spacing'
# The reason F2 is not cosmetic: that value is a port, and the port goes to lsof.
load_env_with 'CLIENT_1_PORT= 8889'
if env -i PATH="$PATH" HOME="$HOME" bash -c '
      source "$1/dev/load-env.sh" >/dev/null 2>&1
      lsof -i:$CLIENT_1_PORT -sTCP:LISTEN -t >/dev/null 2>"$1/lsoferr"
      grep -q "unacceptable port specification" "$1/lsoferr" && exit 1 || exit 0
    ' _ "$SANDBOX"; then
    pass "the resulting port is one lsof will accept"
else
    fail "the resulting port made lsof say 'unacceptable port specification' — ai-bounce.sh:44's listen probe and stop-ai-client.sh's kill selector both read it"
fi

echo
echo "a QUOTED value may be followed by a comment (r1/F3, r1/F5)"
load_env_with 'CLIENT_1_PORT="8889"   # workspace two
NOTE="a # inside quotes is kept"
CLIENT_2_PORT=8890'
assert_var "quoted value + trailing comment is applied" CLIENT_1_PORT 8889
assert_var "a '#' INSIDE the quotes survives"            NOTE 'a # inside quotes is kept'
assert_var "the line after it is unharmed"               CLIENT_2_PORT 8890
assert_stderr_lacks "it is not misdiagnosed as an unterminated quote" "unterminated quote"

echo
echo "a .env key cannot overwrite the parser's own state (r1/F6)"
load_env_with '__le_lineno=08
CLIENT_1_PORT=8889
__le_val=whatever
CLIENT_2_PORT=8890'
assert_var "the good line AFTER the reserved key still loads"  CLIENT_1_PORT 8889
assert_var "and so does the one after the second"              CLIENT_2_PORT 8890
assert_var "the reserved key is not exported"                  __le_lineno '<unset>'
assert_stderr_has "the reserved key is named on stderr" "reserved"

echo
echo "a warning never prints the VALUE (r1/F7)"
load_env_with 'AI_PASSWORD="private-sentinel-do-not-print
CLIENT_1_PORT=8889'
assert_var "the line after the bad one still loads" CLIENT_1_PORT 8889
assert_stderr_has   "the key is named"                     "AI_PASSWORD"
assert_stderr_has   "the line number is named"             "line 1"
assert_stderr_lacks "the secret value is NOT in the warning" "private-sentinel-do-not-print"
load_env_with 'this line has no equals sign and a secret-looking blob sk-live-abc123
CLIENT_1_PORT=8889'
assert_stderr_lacks "a line with no '=' has its CONTENT withheld too" "sk-live-abc123"
assert_stderr_has   "but its line number is still named"              "line 1"

echo
echo "a QUOTED value ends at its CLOSING quote, not at the first ' #' (r2/F1, r2/F7)"
load_env_with 'CLIENT_1_PORT="8889" # use "runner"
NOTE="a # inside" # outside
CLIENT_2_PORT=8890'
assert_var "a comment that itself contains quotes does not corrupt the value" CLIENT_1_PORT 8889
assert_var "an interior ' #' survives AND a trailing comment is still dropped" NOTE 'a # inside'
assert_var "the line after them is unharmed" CLIENT_2_PORT 8890
assert_stderr_lacks "neither is misreported as an unterminated quote" "unterminated quote"
load_env_with 'NOTE="pa #ss" # mine'
assert_var "a quoted value whose interior has ' #', plus a comment" NOTE 'pa #ss'
# The closing quote must really close: text after it is refused, not exported.
load_env_with 'NOTE="bar" and then some
CLIENT_1_PORT=8889'
assert_var "text after the closing quote is refused, not exported" NOTE '<unset>'
assert_var "and the next line still loads"                        CLIENT_1_PORT 8889
assert_stderr_has "it says what is actually wrong" "after the closing quote"

echo
echo "a value that is ONLY a comment is EMPTY, so the default fires (r2/F3)"
load_env_with 'CLIENT_1_PORT=   # blank means use the default
CLIENT_2_PORT=8890'
assert_var "the comment text is not exported as the port" CLIENT_1_PORT 7889
assert_var "the line after it is unharmed"                CLIENT_2_PORT 8890

echo
echo "the text before a bad '=' is withheld, because it can be the VALUE (r2/F5)"
load_env_with 'AI_TOKEN dGhpc2lzc2VjcmV0==
CLIENT_1_PORT=8889'
assert_var "the line after it still loads" CLIENT_1_PORT 8889
assert_stderr_has   "the line number is named"            "line 1"
assert_stderr_lacks "the secret is NOT in the warning"    "dGhpc2lzc2VjcmV0"
assert_stderr_lacks "and neither is the name beside it"   "AI_TOKEN"

echo
echo "a failed export costs its line, not the whole caller (r2/F4)"
printf 'UID=5\nCLIENT_1_PORT=8889\n' > "$SANDBOX/.env"
if out=$(env -i PATH="$PATH" HOME="$HOME" bash -c '
          set -e
          source "$1/dev/load-env.sh" 2>/dev/null
          echo "REACHED:$CLIENT_1_PORT"
        ' _ "$SANDBOX" 2>/dev/null); then
    case "$out" in
      REACHED:8889) pass "a readonly key does not abort a 'set -e' caller, and the next line loads" ;;
      *)            fail "caller continued but with the wrong state: [$out]" ;;
    esac
else
    fail "a readonly key in .env aborted the 'set -e' caller — eight scripts in dev/ source this under set -e"
fi

echo
echo "a CRLF .env parses (it was a limit; the both-ends trim removed it)"
printf 'NOTE="bar"\r\nCLIENT_1_PORT=8889\r\n' > "$SANDBOX/.env"
rm -f "$SANDBOX/out" "$SANDBOX/err" "$SANDBOX/vars"
env -i PATH="$PATH" HOME="$HOME" LOAD_ENV_CANARY='tok-do-not-print-me' bash -c '
      source "$1/dev/load-env.sh" >"$1/out" 2>"$1/err"
      { echo "CLIENT_1_PORT=${CLIENT_1_PORT-<unset>}"; echo "NOTE=${NOTE-<unset>}"; } > "$1/vars"
    ' _ "$SANDBOX"
assert_var "a quoted CRLF value loses its quotes and its \r" NOTE bar
assert_var "an unquoted CRLF value is usable as a port"      CLIENT_1_PORT 8889

echo
echo "─────────────────────────────"
echo "load_env_parse_test: passed $PASS, failed $FAIL"
[ "$FAIL" -eq 0 ]
