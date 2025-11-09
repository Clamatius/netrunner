;; Helper script to join AI player to a game via server REPL
;; This bypasses WebSocket authentication complexity

(ns ai-join-via-server
  (:require [web.lobby :as lobby]
            [web.app-state :as app-state]))

(defn join-ai-to-game!
  "Join AI player to a game by directly calling server-side functions

   Args:
     gameid - UUID string of the game
     side - \"Corp\" or \"Runner\"
     username - AI player username (defaults to AIPlayer)"
  ([gameid side]
   (join-ai-to-game! gameid side "AIPlayer"))
  ([gameid side username]
   (let [ai-user {:username username
                  :emailhash "ai"
                  :_id "ai-player-id"
                  :special true
                  :options {:default-format "standard" :pronouns "none"}
                  :stats {:games-started 0 :games-completed 0}}
         uid (str "ai-client-" (java.util.UUID/randomUUID))
         data {:gameid (java.util.UUID/fromString gameid)
               :request-side side}
         lobby (app-state/get-lobby (java.util.UUID/fromString gameid))]

     (println "Joining AI player" username "to game" gameid "as" side)
     (println "UID:" uid)

     (if lobby
       (do
         (lobby/join-lobby! ai-user uid data nil lobby)
         (println "✅ AI joined successfully!")
         (println "Lobby players:" (map #(get-in % [:user :username]) (:players lobby))))
       (println "❌ Lobby not found for gameid" gameid)))))

(comment
  ;; Usage example:
  (join-ai-to-game! "6f6c1cd4-9316-42dc-8457-cb4391ca2d63" "Corp")
  )
