(do
  (println "Joining new game as Corp...")
  (let [gameid-uuid (java.util.UUID/fromString "23081ab2-0447-47ed-9d56-91a8eb7f8d2b")]
    (ai-websocket-client-v2/send-message! :lobby/join
                                           {:gameid gameid-uuid
                                            :request-side "Corp"}))
  (Thread/sleep 3000)
  (println "Joined as Corp!"))
