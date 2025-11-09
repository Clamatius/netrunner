(ns ai-monitor-hand
  (:require [ai-websocket-client-v2 :as ai]))

(defn monitor-and-respond []
  (println "=== JOINING GAME AS RUNNER ===")
  (let [gameid-uuid (java.util.UUID/fromString "edb5e3bc-b7e8-4450-bb5e-e966d701319b")]
    (ai/send-message! :lobby/join
                      {:gameid gameid-uuid
                       :request-side "Runner"}))
  (Thread/sleep 3000)

  (println "\n=== WAITING FOR GAME START ===")
  (loop [attempts 0]
    (when (< attempts 20)
      (let [gs (ai/get-game-state)]
        (if gs
          (println "Game started!")
          (do
            (Thread/sleep 1000)
            (recur (inc attempts)))))))

  (Thread/sleep 2000)

  ;; Handle mulligan
  (let [gs (ai/get-game-state)
        prompt (get-in gs [:runner :prompt-state])]
    (when prompt
      (println "\n=== MULLIGAN PROMPT ===")
      (println "Message:" (:msg prompt))
      (let [keep-uuid (get-in prompt [:choices 0 :uuid])]
        (println "Keeping hand...")
        (ai/send-action! "choice" {:choice {:uuid keep-uuid}})
        (Thread/sleep 2000))))

  ;; Show hand data
  (let [gs (ai/get-game-state)
        hand (get-in gs [:runner :hand])]
    (println "\n=== RUNNER HAND DATA ===")
    (println "Hand count:" (count hand))
    (doseq [[idx card] (map-indexed vector hand)]
      (println (format "  %d. %-25s | CID: %s" idx (:title card) (:cid card))))

    (println "\n=== FIRST CARD DETAIL ===")
    (clojure.pprint/pprint (first hand)))

  (println "\n=== MONITORING FOR PROMPTS ===")
  (println "Will check every 2 seconds for 60 seconds...")
  (loop [checks 0]
    (when (< checks 30)
      (Thread/sleep 2000)
      (let [gs (ai/get-game-state)
            prompt (get-in gs [:runner :prompt-state])
            turn (:turn gs)
            active (:active-player gs)]

        (println (format "\n[Check %d] Turn: %s | Active: %s" (inc checks) turn active))

        (when prompt
          (println "PROMPT:" (:msg prompt))
          (println "Type:" (:prompt-type prompt))

          (when (= "select" (:prompt-type prompt))
            (let [hand (get-in gs [:runner :hand])
                  cards-to-discard (take 2 hand)
                  cids (map :cid cards-to-discard)]
              (println "\n=== DISCARD DETECTED ===")
              (println "Discarding:" (map :title cards-to-discard))
              (println "CIDs:" cids)
              (ai/send-action! "choice" {:choice cids})
              (Thread/sleep 2000)
              (println "Discard sent!")
              (System/exit 0)))))

      (recur (inc checks))))

  (println "\nMonitoring complete"))
