#!/usr/bin/env bash
# The launch path (dev/reset.sh -> dev/ai-self-play.sh) takes FORMAT= and
# PRECON= and must refuse a bad combination BEFORE any lobby exists: before it
# leaves, seeds, creates, or even bounces the clients.
#
# How: ai-self-play.sh calls its helpers RELATIVE to the cwd (./dev/send_command,
# ./dev/ai-eval.sh, ./dev/seed-decks.sh, ./dev/deck-check), so it runs here from
# a temp "repo" whose dev/ holds logging stubs - plus the REAL deck-check, which
# is offline. Every stub call lands in one log, and a refusal must leave it EMPTY.
# reset.sh resolves its helpers from its own directory, so it is symlinked in
# beside a stub ai-bounce.sh.
#
# Not proven here: what the live server does with the lobby (see
# create_game_format_test.sh for the payload) or whether a seeded deck is
# accepted (seed-decks.sh asks the server).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)" || exit 1
DEV="$(cd "$SCRIPT_DIR/.." && pwd)"
HB="$DEV/decks/sg-hb-discretion-advised.edn"
SHAPER="$DEV/decks/sg-shaper-planning-ahead.edn"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/nr-self-play.XXXXXX")" || exit 1
[[ -n "$TMP" && -d "$TMP" ]] || { echo "FAIL: could not create a temp dir"; exit 1; }
trap 'rm -rf "$TMP"' EXIT

R="$TMP/repo"; mkdir -p "$R/dev" "$TMP/pids" "$TMP/bin" || exit 1
LOG="$TMP/calls.log"
ln -s "$DEV/deck-check" "$R/dev/deck-check"
ln -s "$DEV/ai-self-play.sh" "$R/dev/ai-self-play.sh"
ln -s "$DEV/reset.sh" "$R/dev/reset.sh"
stub() { printf '#!/usr/bin/env bash\n%s\n' "$2" > "$R/dev/$1"; chmod +x "$R/dev/$1"; }
stub send_command 'IFS="|"; echo "sc $*" >> "'"$LOG"'"
case "$2" in select-deck) echo "✅ Deck selected" ;; esac; exit 0'
stub ai-eval.sh 'echo "eval $1 $2" >> "'"$LOG"'"; echo "\"game-1\""'
stub seed-decks.sh 'IFS="|"; echo "seed $*" >> "'"$LOG"'"; echo "id-$1"'
stub ai-bounce.sh 'echo "bounce" >> "'"$LOG"'"'
# sleep is a no-op: the script paces a live server, and there is none.
printf '#!/bin/sh\nexit 0\n' > "$TMP/bin/sleep"; chmod +x "$TMP/bin/sleep"
# The REPL liveness check reads <dir>/ai-client-<side>.pid; point it at THIS
# shell so it passes without reading the live stack's pid files.
echo $$ > "$TMP/pids/ai-client-corp.pid"; echo $$ > "$TMP/pids/ai-client-runner.pid"

fail=0
check() {
    local name="$1" actual="$2" expected="$3"
    if [[ "$actual" == "$expected" ]]; then echo "ok   [$name]"
    else printf 'FAIL [%s]\n expected: %q\n      got: %q\n' "$name" "$expected" "$actual"; fail=$((fail + 1)); fi
}
# run <script> [VAR=value ...] — the caller's FORMAT/PRECON/decks are pinned
# empty first so an exported value cannot leak in.
run() {
    local script="$1"; shift
    : > "$LOG"
    out="$(cd "$R" && env FORMAT= PRECON= CORP_DECK= RUNNER_DECK= AI_PID_DIR="$TMP/pids" \
           PATH="$TMP/bin:$PATH" "$@" bash "./dev/$script" 2>&1)"
    code=$?
    calls="$(cat "$LOG")"
}
# refuses <name> <needle> VAR=value ...: non-zero, says why, and touched NOTHING.
refuses() {
    local name="$1" needle="$2"; shift 2
    run ai-self-play.sh "$@"
    check "$name-exits-nonzero" "$([[ $code -ne 0 ]] && echo nonzero || echo "$code")" nonzero
    check "$name-says-why" "$(grep -cF -- "$needle" <<<"$out")" 1
    check "$name-touches-nothing" "$calls" ""
}

refuses unknown-format "unknown format 'bogus'" FORMAT=bogus CORP_DECK="$HB" RUNNER_DECK="$SHAPER"
refuses unknown-matchup "unknown matchup 'worlds-2099-a'" PRECON=worlds-2099-a
refuses precon-with-decks "PRECON=worlds-2019-a is a preconstructed lobby: it takes no decks" \
    PRECON=worlds-2019-a CORP_DECK="$HB" RUNNER_DECK="$SHAPER"
refuses precon-with-format "PRECON=worlds-2019-a is a preconstructed lobby; FORMAT=standard conflicts" \
    PRECON=worlds-2019-a FORMAT=standard
refuses deck-not-legal-in-lobby-format "not standard legal: Luminal Transubstantiation (banned)" \
    FORMAT=standard CORP_DECK="$HB" RUNNER_DECK="$SHAPER"
refuses constructed-format-without-decks "FORMAT=standard is a Constructed lobby: it needs CORP_DECK and RUNNER_DECK" \
    FORMAT=standard
refuses preconstructed-without-precon "FORMAT=preconstructed needs PRECON=<matchup>" FORMAT=preconstructed
refuses quick-draft "format 'quick-draft' has no deck legality" FORMAT=quick-draft CORP_DECK="$HB" RUNNER_DECK="$SHAPER"
# A precon whose list names a title the server cannot resolve: process-deck
# silently drops it and the deck plays short (2012-b Runner: "Crashspace").
refuses precon-drops-a-title 'Ben Marsh - 2012: Gabe: the server would silently drop ["Crashspace"]' PRECON=worlds-2012-b
refuses one-deck "Constructed needs BOTH CORP_DECK and RUNNER_DECK" CORP_DECK="$HB"

# reset.sh refuses before it bounces the clients.
run reset.sh FORMAT=bogus CORP_DECK="$HB" RUNNER_DECK="$SHAPER"
check reset-refuses-exits-nonzero "$([[ $code -ne 0 ]] && echo nonzero || echo "$code")" nonzero
check reset-refuses-says-why "$(grep -cF "unknown format 'bogus'" <<<"$out")" 1
check reset-refuses-before-bounce "$calls" ""

# --- What a good request sends: the WHOLE call sequence. ---
run ai-self-play.sh PRECON=worlds-2019-a
check precon-exits "$code" 0
check precon-calls "$calls" "sc corp|leave-game
sc runner|leave-game
sc corp|create-game|AI Self-Play Test|Any Side|worlds-2019-a|Intermediate|preconstructed
eval corp 7890
sc runner|join|game-1|Runner
sc corp|start-game"

run ai-self-play.sh FORMAT=core CORP_DECK="$HB" RUNNER_DECK="$SHAPER"
check constructed-core-exits "$code" 0
check constructed-core-calls "$calls" "sc corp|leave-game
sc runner|leave-game
seed ai-corp|$HB|core
seed ai-runner|$SHAPER|core
sc corp|create-game|AI Self-Play Test|Any Side||Constructed|core
eval corp 7890
sc runner|join|game-1|Runner
sc corp|select-deck|id-ai-corp
sc runner|select-deck|id-ai-runner
sc corp|start-game"

# Today's two launches. NOT unchanged as commands: create-game now gets a 5th
# argument (its own default) and seed-decks.sh a 3rd (the lobby format), and
# reset.sh runs a preflight first. What IS unchanged is the create-lobby
# payload these calls produce (create_game_format_test pins it).
run ai-self-play.sh
check default-exits "$code" 0
check default-create "$(grep create-game <<<"$calls")" "sc corp|create-game|AI Self-Play Test|Any Side||Intermediate|system-gateway"
run ai-self-play.sh CORP_DECK="$HB" RUNNER_DECK="$SHAPER"
check sg-constructed-seeds-in-sg "$(grep '^seed' <<<"$calls")" "seed ai-corp|$HB|system-gateway
seed ai-runner|$SHAPER|system-gateway"
check sg-constructed-create "$(grep create-game <<<"$calls")" "sc corp|create-game|AI Self-Play Test|Any Side||Constructed|system-gateway"

# --check (reset.sh's preflight) validates and stops: nothing is touched.
: > "$LOG"
out="$(cd "$R" && env FORMAT= PRECON=worlds-2019-a CORP_DECK= RUNNER_DECK= AI_PID_DIR="$TMP/pids" \
       PATH="$TMP/bin:$PATH" bash ./dev/ai-self-play.sh --check 2>&1)"; code=$?
check check-exits "$code" 0
check check-touches-nothing "$(cat "$LOG")" ""
check check-names-the-lobby "$(grep -c 'LOBBY precon worlds-2019-a' <<<"$out")" 1
: > "$LOG"
out="$(cd "$R" && env FORMAT= PRECON=worlds-2012-b CORP_DECK= RUNNER_DECK= AI_PID_DIR="$TMP/pids" \
       PATH="$TMP/bin:$PATH" bash ./dev/ai-self-play.sh --check 2>&1)"; code=$?
check check-refuses-a-short-precon "$([[ $code -ne 0 ]] && echo nonzero || echo "$code")" nonzero
check check-names-the-dropped-card "$(grep -cF '["Crashspace"]' <<<"$out")" 1

# --- seed-decks.sh asks the server for legality in the LOBBY format it is given. ---
# The real script, beside a stub ai-eval.sh that logs the expression it would
# send the server REPL and answers nothing (so the script reports "Not seeded").
S="$TMP/seed/dev"; mkdir -p "$S"
ln -s "$DEV/seed-decks.sh" "$S/seed-decks.sh"; ln -s "$DEV/load-env.sh" "$S/load-env.sh"
printf '#!/usr/bin/env bash\nprintf "%%s\\n" "${!#}" >> "%s"\n' "$LOG" > "$S/ai-eval.sh"; chmod +x "$S/ai-eval.sh"
seed() { : > "$LOG"; env -u GAME_SERVER_PORT bash "$S/seed-decks.sh" "$@" >/dev/null 2>&1; code=$?; calls="$(cat "$LOG")"; }
seed ai-corp "$HB" core
check seed-checks-the-lobby-format "$(grep -cF 'fmt (keyword (or (not-empty "core") (:format deck)))' <<<"$calls")" 1
seed ai-corp "$HB"
check seed-defaults-to-the-decks-format "$(grep -cF 'fmt (keyword (or (not-empty "") (:format deck)))' <<<"$calls")" 1
seed ai-corp "$HB" 'core") (System/exit 0'
check seed-refuses-a-non-slug-exits "$([[ $code -ne 0 ]] && echo nonzero || echo "$code")" nonzero
check seed-refuses-a-non-slug-sends-nothing "$calls" ""

if ((fail)); then printf 'FAIL: %d launch-path assertion(s)\n' "$fail"; exit 1; fi
echo 'PASS: launch path FORMAT/PRECON (stubbed helpers, real deck-check)'
