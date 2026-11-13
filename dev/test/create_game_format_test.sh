#!/usr/bin/env bash
# `create-game` builds the :lobby/create payload. Pin the WHOLE expression it
# hands the eval backend, for each kind of lobby the server distinguishes:
#
#   format          gateway-type    precon        -> server (lobby.clj validate-precon)
#   system-gateway  Beginner/Intermediate  (ignored)  precon lobby, gateway decks
#   system-gateway  Constructed            (ignored)  constructed SG lobby
#   preconstructed  (ignored)              <matchup>  precon lobby, Worlds decks
#   <other>         (ignored)              (ignored)  constructed lobby in <other>
#
# The web UI (src/cljs/nr/new_game.cljs:12-29 new-game-keys, :50 ws-send!)
# sends :format, :gateway-type AND :precon on every create, whatever the format,
# so the payload KEYS never change with the lobby kind here either - only values.
#
# What this does NOT prove: that the server makes the lobby (that needs the live
# stack), or that the client's create-lobby! passes these keys through
# unchanged (ai_connection.clj:126-134 assoc's defaults over a few of them).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)" || exit 1
SEND_CMD="$SCRIPT_DIR/../send_command"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-create-game.XXXXXX")" || exit 1
[[ -n "$TMP" && -d "$TMP" ]] || { echo "FAIL: could not create a temp dir"; exit 1; }
trap 'rm -rf "$TMP"' EXIT
export HEARTBEAT_DIR="$TMP/heartbeats" SEND_COMMAND_LOG="$TMP/commands.log"

# Same fail-closed stub as choose_label_route_test.sh: every --stdin eval and
# every positional eval that is not a connection preflight is logged.
cat > "$TMP/eval" <<'STUB' || exit 1
#!/usr/bin/env bash
if [[ "${1:-}" == "--stdin" ]]; then
    expr="$(cat)"
    printf '%s %s|%s\n' "${2:-<none>}" "${3:-<none>}" "$expr" >> "$ACTION_LOG"
    printf 'nil\n'
    exit 0
fi
expr="${!#}"
case "$expr" in
    *sync-verdict!*) printf '"SYNC-VERDICT in-sync"\n' ;;
    *ensure-connected!*) printf '"ok"\n' ;;
    *) printf 'POSITIONAL %s %s|%s\n' "${1:-<none>}" "${2:-<none>}" "$expr" >> "$ACTION_LOG"
       printf 'nil\n' ;;
esac
STUB
chmod +x "$TMP/eval" || exit 1

fail=0
check() {
    local name="$1" actual="$2" expected="$3"
    if [[ "$actual" == "$expected" ]]; then
        echo "ok   [$name]"
    else
        printf 'FAIL [%s]\n expected: %q\n      got: %q\n' "$name" "$expected" "$actual"
        fail=$((fail + 1))
    fi
}
run_create() {
    export ACTION_LOG="$TMP/actions.log"
    : > "$ACTION_LOG"
    NR_NO_AUTO_PROMPT=0 SHOW_LAST_LOG=0 AI_EVAL="$TMP/eval" \
        "$SEND_CMD" corp create-game "$@" > "$TMP/output" 2>&1
    code=$?
    actions="$(cat "$ACTION_LOG")"
}
# payload <title> <format> <gateway-type> <precon> — the whole expected log.
payload() {
    printf '%s' 'corp 7890|(do
                   (require (quote [ai-connection :as conn]))
                   (conn/create-lobby! {:title "'"$1"'"
                                        :side "Any Side"
                                        :format "'"$2"'"
                                        :gateway-type "'"$3"'"
                                        :precon "'"$4"'"
                                        :room "casual"
                                        :allow-spectator true
                                        :spectatorhands true
                                        :save-replay true
                                        :api-access false
                                        :password ""
                                        :singleton false
                                        :open-decklists true
                                        :turmoil false
                                        :timer nil})
                   nil)'
}

# --- Today's invocations: the create-lobby PAYLOAD is byte for byte what it was
# before the format argument existed. (Not the whole command: create-game now
# also runs dev/deck-check --lobby before it sends; see the refusals below.) ---
# The default lobby: gateway Intermediate precon. An EMPTY precon argument still
# defaults to worlds-2012-a (bash ${3:-...} treats "" as unset).
run_create "AI Self-Play Test" "Any Side" "" "Intermediate"
check 'today-intermediate-exits' "$code" '0'
check 'today-intermediate-payload' "$actions" "$(payload 'AI Self-Play Test' system-gateway Intermediate worlds-2012-a)"
run_create "AI Self-Play Test" "Any Side" "" "Constructed"
check 'today-sg-constructed-payload' "$actions" "$(payload 'AI Self-Play Test' system-gateway Constructed worlds-2012-a)"
run_create "T"
check 'title-only-defaults' "$actions" "$(payload 'T' system-gateway Intermediate worlds-2012-a)"

# --- The format argument (5th positional). ---
run_create "T" "Any Side" "worlds-2019-a" "Intermediate" "preconstructed"
check 'precon-lobby-exits' "$code" '0'
check 'precon-lobby-payload' "$actions" "$(payload 'T' preconstructed Intermediate worlds-2019-a)"
run_create "T" "Any Side" "" "Constructed" "standard"
check 'standard-constructed-payload' "$actions" "$(payload 'T' standard Constructed worlds-2012-a)"
# An explicit system-gateway is the same lobby as the default.
run_create "T" "Any Side" "" "Constructed" "system-gateway"
check 'explicit-sg-equals-default' "$actions" "$(payload 'T' system-gateway Constructed worlds-2012-a)"
# An empty format argument means the default, like every other positional here.
run_create "T" "Any Side" "" "Intermediate" ""
check 'empty-format-is-default' "$actions" "$(payload 'T' system-gateway Intermediate worlds-2012-a)"
# The title lands inside a Clojure string literal: a quote must stay one string.
run_create 'a "b"' "Any Side" "" "Intermediate"
check 'title-is-one-string' "$actions" "$(payload 'a \"b\"' system-gateway Intermediate worlds-2012-a)"

# --- Refusals: create-game asks dev/deck-check --lobby (the launch path's own
# check, a mirror of web/lobby.clj validate-precon) BEFORE it sends, because
# the server accepts any of these and leaves a lobby no deck can join. ---
refuses() {
    local name="$1" needle="$2"; shift 2
    run_create "$@"
    check "$name-exits-nonzero" "$([[ $code -ne 0 ]] && echo nonzero || echo "$code")" nonzero
    check "$name-sends-nothing" "$actions" ""
    check "$name-says-why" "$(grep -cF -- "$needle" "$TMP/output")" 1
}
refuses unknown-matchup "unknown matchup 'worlds-2099-a'" T "Any Side" worlds-2099-a Intermediate preconstructed
refuses unknown-format "unknown format 'bogus'" T "Any Side" "" Constructed bogus
refuses quoted-format "unknown format 'x\" :password \"p'" T "Any Side" "" Constructed 'x" :password "p'
# A gateway-type the server would read as Constructed although it names neither:
# a typo of Intermediate silently becomes a Constructed lobby with no decks.
refuses gateway-typo "gateway-type 'Intermediat'" T "Any Side" "" Intermediat
# (An EMPTY precon argument is not a refusal: it defaults to worlds-2012-a,
# a real matchup, exactly as it always has.)
# deck-check unavailable (no bb, missing file): refuse, never skip the check.
NR_DECK_CHECK="$TMP/no-such-deck-check" refuses check-unavailable "cannot check the lobby" "AI Self-Play Test" "Any Side" "" "Intermediate"

if ((fail)); then
    printf 'FAIL: %d create-game payload assertion(s)\n' "$fail"
    exit 1
fi
echo 'PASS: create-game payload per lobby kind (offline backend)'
