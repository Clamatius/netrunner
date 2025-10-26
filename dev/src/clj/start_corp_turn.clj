;; Send start-turn command to begin Corp turn 1
(println "=== STARTING CORP TURN 1 ===")

(load-file "dev/src/clj/ai_websocket_client_v2.clj")

(println "\n📊 Current State:")
(def gs (:game-state @ai-websocket-client-v2/client-state))
(println "  Turn:" (:turn gs))
(println "  Active player:" (:active-player gs))
(println "  End-turn:" (:end-turn gs))
(println "  Corp clicks:" (get-in gs [:corp :click]))

(println "\n📤 Sending start-turn command...")
(ai-websocket-client-v2/send-message!
  :game/action
  {:gameid (java.util.UUID/fromString "b0f6d322-b95d-4b7b-9e5f-260105bbadc6")
   :command "start-turn"
   :args nil})

(println "⏳ Waiting for response...")
(Thread/sleep 3000)

(println "\n📊 State After Start Turn:")
(def gs2 (:game-state @ai-websocket-client-v2/client-state))
(println "  Turn:" (:turn gs2))
(println "  Active player:" (:active-player gs2))
(println "  Corp clicks:" (get-in gs2 [:corp :click]))
(println "  Corp credits:" (get-in gs2 [:corp :credit]))
(println "  Phase:" (:phase gs2))

(println "\n📋 Recent Log:")
(doseq [entry (take-last 3 (get-in gs2 [:log]))]
  (println "  " (:text entry)))

(println "\n📡 Connection:")
(println "  Still connected:" (:connected @ai-websocket-client-v2/client-state))

(println "\n=== END ===")
