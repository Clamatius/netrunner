;; Test system-gateway Beginner format (default)
(println "\n=== Testing System Gateway Beginner Format ===\n")

(require '[gniazdo.core :as gniazdo])

(def client-state ai-websocket-client-v2/client-state)

(defn send-test! [event-type data]
  (let [socket (:socket @client-state)
        msg (pr-str [[event-type data]])]
    (gniazdo/send-msg socket msg)
    (println "📤 Sent:" event-type)
    (println "   " data)))

;; Create a system-gateway Beginner game (should have fixed decks)
(println "Creating System Gateway Beginner lobby...")
(send-test! :lobby/create
            {:title "System Gateway - Beginner"
             :format "system-gateway"
             :gateway-type "Beginner"
             :side "Any Side"
             :room "casual"
             :allow-spectator true
             :save-replay true})

(Thread/sleep 2500)

;; Request lobby list
(println "\nRequesting lobby list...")
(ai-websocket-client-v2/request-lobby-list!)

(Thread/sleep 1500)

;; Show lobbies
(println "\n📋 Current Lobbies:")
(doseq [g (ai-websocket-client-v2/get-lobby-list)]
  (println (format "  - %s [%s%s]"
                   (:title g)
                   (:format g)
                   (if (= "system-gateway" (:format g))
                     (str " - " (or (:gateway-type g) "?"))
                     ""))))

(println "\n✅ Test complete\n")
:done
