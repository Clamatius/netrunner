;; Check client state
(println "\n📊 Client State:")
(println "Connected:" (:connected @ws/client-state))
(println "GameID:" (:gameid @ws/client-state))
(println "Side:" (:side @ws/client-state))
(println "UID:" (:uid @ws/client-state))
(println "\nFull state keys:" (keys @ws/client-state))
:done
