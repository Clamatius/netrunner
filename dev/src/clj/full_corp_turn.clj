;; Complete a full Corp turn: click for credit 2 more times, then end turn
(println "=== COMPLETING CORP TURN 1 ===")

(load-file "dev/src/clj/ai_websocket_client_v2.clj")

(def gameid (java.util.UUID/fromString "b0f6d322-b95d-4b7b-9e5f-260105bbadc6"))

;; Click for credit #2
(println "\n📤 Action 2: Click for credit...")
(ai-websocket-client-v2/send-message!
  :game/action
  {:gameid gameid :command "credit" :args nil})
(Thread/sleep 1500)

;; Click for credit #3
(println "📤 Action 3: Click for credit...")
(ai-websocket-client-v2/send-message!
  :game/action
  {:gameid gameid :command "credit" :args nil})
(Thread/sleep 1500)

(def gs (:game-state @ai-websocket-client-v2/client-state))
(println "\n📊 After 3 clicks:")
(println "  Clicks:" (get-in gs [:corp :click]))
(println "  Credits:" (get-in gs [:corp :credit]))

;; End turn
(println "\n📤 Ending turn...")
(ai-websocket-client-v2/send-message!
  :game/action
  {:gameid gameid :command "end-turn" :args nil})
(Thread/sleep 2000)

(def gs2 (:game-state @ai-websocket-client-v2/client-state))
(println "\n📊 After end-turn:")
(println "  Turn:" (:turn gs2))
(println "  Active player:" (:active-player gs2))
(println "  End-turn:" (:end-turn gs2))
(println "  Corp clicks:" (get-in gs2 [:corp :click]))

(println "\n📋 Recent Log (last 5):")
(doseq [entry (take-last 5 (get-in gs2 [:log]))]
  (println "  " (:text entry)))

(println "\n📡 Connection:")
(println "  Still connected:" (:connected @ai-websocket-client-v2/client-state))

(println "\n✅ Corp turn 1 complete!")
(println "=== END ===")
