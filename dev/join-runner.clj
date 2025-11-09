(do
  (println "Joining game as Runner...")
  (let [gameid-uuid (java.util.UUID/fromString "cbcc3a5a-751a-4844-a199-98983ffd39c2")]
    (ai-websocket-client-v2/send-message! :lobby/join
                                           {:gameid gameid-uuid
                                            :request-side "Runner"}))
  (Thread/sleep 3000)
  (println "Joined!"))
