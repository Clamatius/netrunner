#!/bin/bash
# AI client takes a simple Runner turn

TIMEOUT=40 ./dev/src/clj/nrepl-eval.sh '
(do
  (println "=== LOADING AI CLIENT ===")
  (load-file "dev/src/clj/ai_websocket_client_v2.clj")

  (println "\n=== ENSURING CONNECTION ===")
  (ai-websocket-client-v2/ensure-connected!)
  (Thread/sleep 2000)

  (println "\n=== CURRENT STATUS ===")
  (ai-websocket-client-v2/show-status)

  (println "\n=== WAITING FOR RUNNER TURN ===")
  (loop [attempts 0]
    (when (< attempts 10)
      (if (ai-websocket-client-v2/my-turn?)
        (println "✅ It'\''s my turn!")
        (do
          (println "⏳ Waiting... (attempt" (inc attempts) ")")
          (Thread/sleep 2000)
          (recur (inc attempts))))))

  (when (ai-websocket-client-v2/my-turn?)
    (println "\n=== STARTING RUNNER TURN ===")
    (println "Hand:")
    (doseq [[idx card] (map-indexed vector (ai-websocket-client-v2/my-hand))]
      (println (str "  " idx ". " (:title card) " (" (:type card) ", cost " (:cost card) ")")))

    (println "\n=== ACTION 1: Install Smartware Distributor (FREE!) ===")
    (ai-websocket-client-v2/play-card! "Smartware Distributor")
    (Thread/sleep 1500)

    (println "\n=== ACTION 2: Play Sure Gamble (+4 credits) ===")
    (ai-websocket-client-v2/play-card! "Sure Gamble")
    (Thread/sleep 1500)

    (println "\n=== ACTION 3: Draw a card ===")
    (ai-websocket-client-v2/draw-card!)
    (Thread/sleep 1500)

    (println "\n=== ACTION 4: Take 1 credit ===")
    (ai-websocket-client-v2/take-credits!)
    (Thread/sleep 1500)

    (println "\n=== FINAL STATUS ===")
    (ai-websocket-client-v2/show-status)

    (println "\n=== END TURN ===")
    (ai-websocket-client-v2/end-turn!)
    (Thread/sleep 2000)

    (println "\n✅ TURN COMPLETE!")
    (ai-websocket-client-v2/show-status))

  (when-not (ai-websocket-client-v2/my-turn?)
    (println "\n❌ Never became Runner'\''s turn - check game state manually")))
'
