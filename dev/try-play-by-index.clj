;; Try playing Sure Gamble by card index
;; In the beginner deck, Sure Gamble is at index 1 in starting hand

(println "\n=== TRYING TO PLAY BY INDEX ===")

(println "Current credits:" (ws/my-credits))
(println "\nAttempting to play card at index 1 (should be Sure Gamble)...")

;; Try sending just the index
(ws/send-action! "play" {:card 1})
(Thread/sleep 2000)

(println "\nCredits after:" (ws/my-credits))
(println "Did it work?")

:done
