;; Direct join test with more debugging
(println "\n=== DIRECT JOIN TEST ===")

;; First, check if we're connected
(println "Connected:" (ws/connected?))
(println "Socket exists:" (some? (:socket @ws/client-state)))

;; Try to join with direct websocket call
(println "\nSending join message...")
(let [gameid (java.util.UUID/fromString "47622719-f452-44a0-b821-3350ca13fcc6")]
  (ws/send-message! :lobby/join
                    {:gameid gameid
                     :request-side "Runner"})
  (println "Message sent!"))

;; Wait for response
(println "Waiting for server response...")
(Thread/sleep 3000)

;; Check state
(println "\nAfter join attempt:")
(println "GameID in state:" (:gameid @ws/client-state))
(println "In game?:" (ws/in-game?))

:done
