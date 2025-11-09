;; Simple check of current lobbies
(println "\n📋 Current Lobby State:")
(clojure.pprint/pprint (:lobby-list @ai-websocket-client-v2/client-state))
:done
