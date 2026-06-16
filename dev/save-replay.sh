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

# Returns "yes" once the server has written the replay for this gameid.
has_replay() {
    mongosh --quiet --eval 'const G="'"$GAMEID"'"; const d=db.getSiblingDB("'"$DB"'")["game-logs"].findOne({gameid:G},{"has-replay":1}); print(d && d["has-replay"]===true ? "yes" : "no");' 2>/dev/null
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

# Mark shared so the local replay viewer serves it without a logged-in player.
mongosh --quiet --eval 'const G="'"$GAMEID"'"; db.getSiblingDB("'"$DB"'")["game-logs"].updateOne({gameid:G},{$set:{"replay-shared":true}});' >/dev/null 2>&1

# Dump the replay JSON ({metadata, history:[...frames]}) for writeups.
# The replay is stored as a JSON string, so emit it as-is (don't re-encode).
mongosh --quiet --eval 'const G="'"$GAMEID"'"; const d=db.getSiblingDB("'"$DB"'")["game-logs"].findOne({gameid:G},{replay:1}); print(typeof d.replay==="string" ? d.replay : JSON.stringify(d.replay));' 2>/dev/null > "$OUT"

BYTES=$(wc -c < "$OUT" | tr -d ' ')
FRAMES=$(mongosh --quiet --eval 'const G="'"$GAMEID"'"; const d=db.getSiblingDB("'"$DB"'")["game-logs"].findOne({gameid:G},{replay:1}); const r=(typeof d.replay==="string")?JSON.parse(d.replay):d.replay; print(Array.isArray(r.history)?r.history.length:"?");' 2>/dev/null)

echo "✅ Replay saved: $OUT (${BYTES} bytes, ${FRAMES} frames, open information)"
echo "   Browser view:  http://localhost:${WEB_PORT}/replay/${GAMEID}"
