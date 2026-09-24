#!/bin/bash
# Seed a deck file into the game server's mongo, owned by an AI seat.
# Refuses (exit 1, nothing written) an illegal list or one with a title the card
# database doesn't know.
#
# Constructed lobbies (System Gateway "Constructed") have no precon: each seat
# must select a deck it OWNS (`find-deck-for-user` matches :_id AND :username).
# This upserts the deck by (username, name), so re-seeding is idempotent and the
# id is stable across runs. Prints the deck id on the LAST line, and the
# server's own legality verdict for the lobby format before it, so an illegal
# list is caught here rather than as a lobby that silently never starts.
#
# Usage: ./dev/seed-decks.sh <ai-corp|ai-runner> <deck.edn>
#   e.g. ./dev/seed-decks.sh ai-corp dev/decks/sg-hb-discretion-advised.edn
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/load-env.sh"

USERNAME="${1:?usage: seed-decks.sh <ai-corp|ai-runner> <deck.edn>}"
DECK_FILE="${2:?usage: seed-decks.sh <ai-corp|ai-runner> <deck.edn>}"
[[ -f "$DECK_FILE" ]] || { echo "❌ No such deck file: $DECK_FILE" >&2; exit 1; }
DECK_PATH="$(cd "$(dirname "$DECK_FILE")" && pwd)/$(basename "$DECK_FILE")"
# The path is spliced into a Clojure string literal below.
[[ "$DECK_PATH" == *[\\\"]* ]] && { echo "❌ Deck path may not contain a quote or backslash: $DECK_PATH" >&2; exit 1; }

# The server REPL reads the file itself: no EDN has to survive shell quoting.
out=$(TIMEOUT=20 "$SCRIPT_DIR/ai-eval.sh" server "$GAME_SERVER_PORT" "(do
  (require (quote monger.collection) (quote clojure.edn) (quote web.lobby) (quote jinteki.validator))
  (let [db (:db (:mongodb/connection integrant.repl.state/system))
        deck (assoc (clojure.edn/read-string (slurp \"$DECK_PATH\")) :username \"$USERNAME\")
        processed (web.lobby/process-deck deck)
        status (:status processed)
        ;; process-deck silently DROPS a title it can't resolve, and the
        ;; shorter deck may still be legal — so count what survived.
        dropped (remove (set (map (comp :title :card) (:cards processed)))
                        (map :card (:cards deck)))
        fmt (keyword (:format deck))
        legal? (and (empty? dropped) (true? (get-in status [fmt :legal])))
        _ (when legal? (monger.collection/update db \"decks\" {:username \"$USERNAME\" :name (:name deck)}
                                    (assoc deck :date (java.util.Date.)) {:upsert true}))
        saved (monger.collection/find-one-as-map db \"decks\" {:username \"$USERNAME\" :name (:name deck)})]
    (println \"LEGALITY\" fmt (pr-str (get status fmt)))
    (when (seq dropped) (println \"UNKNOWN-TITLES\" (pr-str (vec dropped))))
    (when legal? (println \"DECK-ID\" (str (:_id saved))))))" 2>&1)

echo "$out" | grep -E '^(LEGALITY|UNKNOWN-TITLES)' >&2 || true
id=$(echo "$out" | sed -n 's/^DECK-ID //p' | tail -1)
if [[ -z "$id" ]]; then
    # An illegal or partly-unresolvable list is NOT written, so it can never
    # overwrite a good copy saved under the same name.
    echo "❌ Not seeded: $USERNAME ($DECK_FILE) — see LEGALITY / UNKNOWN-TITLES above, or:" >&2
    echo "$out" | grep -vE '^(LEGALITY|UNKNOWN-TITLES)' >&2
    exit 1
fi
echo "$id"
