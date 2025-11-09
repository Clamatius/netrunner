#!/bin/bash
# Join game as Runner and monitor for prompts

TIMEOUT=60 ./dev/src/clj/nrepl-eval.sh '
(do
  (println "=== LOADING AI CLIENT ===")
  (load-file "dev/src/clj/ai_websocket_client_v2.clj")

  (println "\n=== CONNECTING ===")
  (ai-websocket-client-v2/connect! "ws://localhost:1042/chsk")
  (Thread/sleep 2000)

  (println "\n=== JOINING GAME AS RUNNER ===")
  (let [gameid-uuid (java.util.UUID/fromString "edb5e3bc-b7e8-4450-bb5e-e966d701319b")]
    (ai-websocket-client-v2/send-message! :lobby/join
                                           {:gameid gameid-uuid
                                            :request-side "Runner"}))
  (Thread/sleep 3000)

  (println "\n=== WAITING FOR GAME START ===")
  (loop [attempts 0]
    (when (< attempts 20)
      (let [gs (:game-state @ai-websocket-client-v2/client-state)]
        (if gs
          (println "Game started!")
          (do
            (Thread/sleep 1000)
            (recur (inc attempts)))))))

  (Thread/sleep 2000)

  (println "\n=== HANDLING MULLIGAN ===")
  (let [gs (:game-state @ai-websocket-client-v2/client-state)
        prompt (get-in gs [:runner :prompt-state])]
    (when prompt
      (println "Message:" (:msg prompt))
      (let [keep-uuid (get-in prompt [:choices 0 :uuid])]
        (println "Keeping hand...")
        (ai-websocket-client-v2/send-action! "choice" {:choice {:uuid keep-uuid}})
        (Thread/sleep 2000))))

  (println "\n=== RUNNER HAND DATA ===")
  (let [gs (:game-state @ai-websocket-client-v2/client-state)
        hand (get-in gs [:runner :hand])]
    (println "Hand count:" (count hand))
    (doseq [[idx card] (map-indexed vector hand)]
      (println (format "  %d. %-25s | CID: %s" idx (:title card) (:cid card))))

    (println "\n=== FIRST CARD DETAIL ===")
    (clojure.pprint/pprint (first hand)))

  (println "\n=== MONITORING FOR PROMPTS ===")
  (println "Checking every 2 seconds for 60 seconds...")
  (loop [checks 0]
    (when (< checks 30)
      (Thread/sleep 2000)
      (let [gs (:game-state @ai-websocket-client-v2/client-state)
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
              (ai-websocket-client-v2/send-action! "choice" {:choice cids})
              (Thread/sleep 2000)
              (println "Discard sent!")
              (System/exit 0)))))

      (recur (inc checks))))

  (println "\nMonitoring complete"))
'
