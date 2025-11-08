;; Direct test of lobby creation
(println "\n=== Direct Lobby Creation Test ===\n")

;; Get the client state
(def client-state ai-websocket-client-v2/client-state)

(println "Current connection state:")
(println "  Connected:" (:connected @client-state))
(println "  Socket:" (some? (:socket @client-state)))
(println "  UID:" (:uid @client-state))

;; Define send-message function inline if needed
(require '[gniazdo.core :as gniazdo])

(defn test-send-message! [event-type data]
  (if-let [socket (:socket @client-state)]
    (try
      (let [msg (pr-str [[event-type data]])]
        (gniazdo/send-msg socket msg)
        (println "📤 Sent message:" event-type)
        (println "   Data:" data)
        true)
      (catch Exception e
        (println "❌ Send failed:" (.getMessage e))
        false))
    (do
      (println "❌ No socket available")
      false)))

;; Test: Create a lobby
(println "\n--- Testing lobby creation ---")
(test-send-message! :lobby/create
                    {:title "Direct Test Lobby"
                     :side "Any Side"
                     :format "standard"
                     :room "casual"
                     :allow-spectator true
                     :spectatorhands false
                     :save-replay true})

(println "\nWaiting for response...")
(Thread/sleep 3000)

;; Request lobby list to see if it worked
(println "\n--- Requesting lobby list ---")
(test-send-message! :lobby/list nil)

(Thread/sleep 2000)

(println "\n=== Test Complete ===\n")
:done
