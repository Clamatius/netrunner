;; Request lobby list and display
(println "\n📡 Requesting lobby list from server...")
(ai-websocket-client-v2/request-lobby-list!)
(Thread/sleep 2000)

(println "\n📋 Available Lobbies:")
(let [lobbies (:lobby-list @ai-websocket-client-v2/client-state)]
  (if (empty? lobbies)
    (println "  No lobbies found")
    (doseq [lobby lobbies]
      (println "\n🎮" (:title lobby))
      (println "   ID:" (:gameid lobby))
      (println "   Format:" (:format lobby))
      (println "   Players:" (count (filter identity [(:corp lobby) (:runner lobby)])) "/ 2"))))

(println "\n✅ Done")
:done
