#!/usr/bin/env bash
# save-replay.sh [gameid] — flush + extract an OPEN-INFORMATION jinteki replay.
#
# Why this exists: the game server only writes the replay to mongo when the
# lobby tears down (close-lobby! -> stats/game-finished), which fires when the
# LAST player leaves the lobby — NOT on game-over. Our normal flow holds lobbies
# open (we resume games), so the replay never persists. This script leaves both
# seats (the 2nd leave triggers the flush), waits for the write, marks the
# replay shared (so the local viewer can serve it without a logged-in account),
# and dumps the replay JSON to dev/replays/<gameid>.json.
#
# The replay is open information — both players' hands are visible at every
# frame — which the game log can't give you. Use it for manual review and for
# move-by-move writeups.
#
# Preconditions:
#   - game was created with :save-replay true (send_command create-game default)
#   - game reached GAME-OVER (concede, agenda, decking, ...)
#   - mongod running on localhost:27017
#   - the game-server nREPL (7888) is up — mongo is reached THROUGH it
#
# NOTE: this script used to shell out to `mongosh`, with `2>/dev/null` on every
# call. `mongosh` is not installed here, so every probe returned empty, which
# has_replay read as "no" — the script reported "❌ No replay persisted" for
# replays that were sitting in mongo intact, and we wrote off at least one
# marquee game as lost on its say-so. Going through the server REPL means we use
# the connection the server itself uses, and a missing dependency fails loudly.
#
# Usage:
#   ./dev/save-replay.sh                 # uses the corp client's current gameid
#   ./dev/save-replay.sh <gameid>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTDIR="$SCRIPT_DIR/replays"
WEB_PORT="${WEB_SERVER_PORT:-1042}"
DB="netrunner"

GAMEID="${1:-}"

# The gameid the seats are currently in — used as the default, and to gate the
# flush (we can only flush by leaving the lobby the clients are actually seated
# in; leaving for any other gameid would close the wrong lobby). `|| true` so a
# corp-client/connection failure falls through to the "No gameid" error below
# rather than tripping `set -e`.
CUR_GAMEID=$("$SCRIPT_DIR/send_command" corp eval '(str (:gameid @ai-state/client-state))' 2>/dev/null | tail -1 | tr -d '"\n ' || true)
[[ "$CUR_GAMEID" == "nil" ]] && CUR_GAMEID=""

[[ -z "$GAMEID" ]] && GAMEID="$CUR_GAMEID"
if [[ -z "$GAMEID" ]]; then
    echo "❌ No gameid. Pass one explicitly, or run with an active corp game." >&2
    exit 1
fi
# Guard before we splice the id into mongosh --eval: gameids are UUIDs.
if [[ ! "$GAMEID" =~ ^[0-9a-fA-F-]+$ ]]; then
    echo "❌ Invalid gameid (expected a UUID): $GAMEID" >&2
    exit 1
fi

# A short prefix of the seats' CURRENT game expands to the full id (#104: the
# old path rejected the prefix while printing the very UUID that matched it).
# Prefixes of other games can't be resolved — the mongo lookup is exact-match —
# so a short id that isn't the current game's prefix needs the full UUID.
if [[ -n "$CUR_GAMEID" && "$GAMEID" != "$CUR_GAMEID" && "$CUR_GAMEID" == "$GAMEID"* ]]; then
    echo "ℹ️  Expanding gameid prefix $GAMEID → $CUR_GAMEID"
    GAMEID="$CUR_GAMEID"
elif [[ ${#GAMEID} -lt 36 ]]; then
    echo "❌ $GAMEID looks like a gameid prefix (full UUIDs are 36 chars) and it" >&2
    echo "   doesn't match the current game (${CUR_GAMEID:-none}) — pass the full id." >&2
    exit 1
fi

# Eval a Clojure form in the game-server REPL and echo its stdout.
# Errors are NOT swallowed: if the REPL is down or the form throws, callers see
# it (the old mongosh path hid exactly this and produced false "no replay").
repl_eval() {
    TIMEOUT=60 "$SCRIPT_DIR/ai-lein-eval.sh" server 7888 "$1" 2>&1
}

MONGO_PRELUDE='(require (quote [monger.core :as mg]) (quote [monger.collection :as mc]) (quote [clojure.java.io :as io]))'
DBCONN='(:db (mg/connect-via-uri "mongodb://localhost/'"$DB"'"))'

# Fail loudly if we cannot reach mongo through the server at all, rather than
# letting an infrastructure failure masquerade as "this game has no replay".
# NB on marker style: ai-lein-eval.sh ECHOES the form back before printing its
# output, so a bare literal marker matches the echo and every check passes
# vacuously. Each marker below is `NAME <computed-value>` — the two only appear
# adjacent in real output, never in the echoed source.
if ! repl_eval "(do $MONGO_PRELUDE (let [db $DBCONN] (println \"MONGOCOUNT\" (mc/count db \"game-logs\"))))" | grep -qE "MONGOCOUNT [0-9]+"; then
    echo "❌ Can't reach mongo through the game-server REPL (port 7888)." >&2
    echo "   Is the server up? Replay state is UNKNOWN — do not assume it is lost." >&2
    exit 1
fi

# Returns "yes" once the server has written the replay for this gameid.
has_replay() {
    if repl_eval "(do $MONGO_PRELUDE (let [db $DBCONN d (mc/find-one-as-map db \"game-logs\" {:gameid \"$GAMEID\"})] (println \"HASREPLAY\" (boolean (:has-replay d)))))" | grep -q "HASREPLAY true"; then
        echo "yes"
    else
        echo "no"
    fi
}

if [[ "$(has_replay)" != "yes" ]]; then
    # Flushing means leaving the lobby — only valid for the game the seats are
    # actually in. Refuse to leave for a different gameid (it would close the
    # wrong lobby and still never flush the one asked for).
    if [[ "$GAMEID" != "$CUR_GAMEID" ]]; then
        echo "❌ No persisted replay for $GAMEID, and the seats aren't in it" \
             "(current: ${CUR_GAMEID:-none})." >&2
        echo "   Can't flush a game the seats have left — re-run with that game active," >&2
        echo "   or it may still be flushing from an inactivity timeout." >&2
        exit 1
    fi
    echo "🎬 Flushing replay for $GAMEID (leaving both seats to close the lobby)..."
    # close-lobby! only fires on the LAST leave, so leave both; order doesn't matter.
    "$SCRIPT_DIR/send_command" runner leave-game >/dev/null 2>&1 || true
    "$SCRIPT_DIR/send_command" corp   leave-game >/dev/null 2>&1 || true
    for _ in $(seq 1 15); do
        sleep 1
        [[ "$(has_replay)" == "yes" ]] && break
    done
fi

if [[ "$(has_replay)" != "yes" ]]; then
    echo "❌ No replay persisted for $GAMEID after flush." >&2
    echo "   Check: created with :save-replay true? reached GAME-OVER? both seats left?" >&2
    exit 1
fi

mkdir -p "$OUTDIR"
OUT="$OUTDIR/$GAMEID.json"

# Mark shared (so the local viewer serves it without a logged-in player) and
# write the replay straight to disk from the server — same filesystem, and it
# keeps a 150KB+ payload out of the REPL's stdout.
# The replay is stored as a JSON string, so spit it as-is (don't re-encode).
repl_eval "(do $MONGO_PRELUDE (let [db $DBCONN] (mc/update db \"game-logs\" {:gameid \"$GAMEID\"} {\"\$set\" {:replay-shared true}}) (let [d (mc/find-one-as-map db \"game-logs\" {:gameid \"$GAMEID\"}) r (:replay d)] (io/make-parents \"$OUT\") (spit \"$OUT\" (if (string? r) r (str r))) (println \"WROTEBYTES\" (.length (io/file \"$OUT\"))))))" | grep -qE "WROTEBYTES [1-9][0-9]*" || {
    echo "❌ Failed to write a non-empty replay to $OUT (see REPL output above)." >&2
    exit 1
}

BYTES=$(wc -c < "$OUT" | tr -d ' ')
FRAMES=$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(len(d.get("history",[])) if isinstance(d,dict) else "?")' "$OUT" 2>/dev/null || echo "?")

echo "✅ Replay saved: $OUT (${BYTES} bytes, ${FRAMES} frames, open information)"
echo "   Browser view:  http://localhost:${WEB_PORT}/replay/${GAMEID}"
