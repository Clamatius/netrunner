;; Test basic action: click for credit
(println "=== CLICKING FOR CREDIT ===")

(load-file "dev/src/clj/ai_websocket_client_v2.clj")

(def gs (:game-state @ai-state/client-state))

(println "\n📊 Before Action:")
(println "  Clicks:" (get-in gs [:corp :click]))
(println "  Credits:" (get-in gs [:corp :credit]))

(println "\n📤 Sending 'credit' command...")
(ai-websocket-client-v2/send-message!
  :game/action
  {:gameid (java.util.UUID/fromString "b0f6d322-b95d-4b7b-9e5f-260105bbadc6")
   :command "credit"
   :args nil})

(println "⏳ Waiting for response...")
(Thread/sleep 2000)

(def gs2 (:game-state @ai-state/client-state))

(println "\n📊 After Action:")
(println "  Clicks:" (get-in gs2 [:corp :click]))
(println "  Credits:" (get-in gs2 [:corp :credit]))

(println "\n📋 Recent Log:")
(doseq [entry (take-last 2 (get-in gs2 [:log]))]
  (println "  " (:text entry)))

(def clicks-before (get-in gs [:corp :click]))
(def clicks-after (get-in gs2 [:corp :click]))
(def credits-before (get-in gs [:corp :credit]))
(def credits-after (get-in gs2 [:corp :credit]))

(if (and (= (dec clicks-before) clicks-after)
         (= (inc credits-before) credits-after))
  (println "\n✅ SUCCESS! Clicked for credit!")
  (println "\n⚠️  Action may not have worked - check browser"))

(println "\n=== END ===")
