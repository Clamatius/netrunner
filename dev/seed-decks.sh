#!/bin/bash
# Seed a deck file into the game server's mongo, owned by an AI seat.
#
# Constructed lobbies (System Gateway "Constructed") have no precon: each seat
# must select a deck it OWNS (`find-deck-for-user` matches :_id AND :username).
# This upserts the deck by (username, name), so re-seeding is idempotent and the
# id is stable across runs. Prints the deck id on the LAST line, and the
# server's own legality verdict for the lobby format before it, so an illegal
# list is caught here rather than as a lobby that silently never starts.
#
# Usage: ./dev/seed-decks.sh <ai-corp|ai-runner> <deck.edn>
#   e.g. ./dev/seed-decks.sh ai-corp dev/decks/sg-haas-bioroid.edn
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/load-env.sh"

USERNAME="${1:?usage: seed-decks.sh <ai-corp|ai-runner> <deck.edn>}"
DECK_FILE="${2:?usage: seed-decks.sh <ai-corp|ai-runner> <deck.edn>}"
[[ -f "$DECK_FILE" ]] || { echo "❌ No such deck file: $DECK_FILE" >&2; exit 1; }
DECK_PATH="$(cd "$(dirname "$DECK_FILE")" && pwd)/$(basename "$DECK_FILE")"

# The server REPL reads the file itself: no EDN has to survive shell quoting.
out=$(TIMEOUT=20 "$SCRIPT_DIR/ai-eval.sh" server "$GAME_SERVER_PORT" "(do
  (require (quote monger.collection) (quote clojure.edn) (quote web.lobby) (quote jinteki.validator))
  (let [db (:db (:mongodb/connection integrant.repl.state/system))
        deck (assoc (clojure.edn/read-string (slurp \"$DECK_PATH\")) :username \"$USERNAME\")
        status (:status (web.lobby/process-deck deck))
        fmt (keyword (:format deck))
        _ (monger.collection/update db \"decks\" {:username \"$USERNAME\" :name (:name deck)}
                                    (assoc deck :date (java.util.Date.)) {:upsert true})
        saved (monger.collection/find-one-as-map db \"decks\" {:username \"$USERNAME\" :name (:name deck)})]
    (println \"LEGALITY\" fmt (pr-str (get status fmt)))
    (println \"DECK-ID\" (str (:_id saved)))))" 2>&1)

echo "$out" | grep -E '^LEGALITY' || true
id=$(echo "$out" | sed -n 's/^DECK-ID //p' | tail -1)
if [[ -z "$id" ]]; then
    echo "❌ Seed failed for $USERNAME ($DECK_FILE):" >&2
    echo "$out" >&2
    exit 1
fi
if ! echo "$out" | grep -q '^LEGALITY .*:legal true'; then
    echo "⚠️  $DECK_FILE is NOT legal for its format — a lobby of that format will refuse it." >&2
fi
echo "$id"
