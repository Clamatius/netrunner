;; Start runner turn
(println "\n⏭️  Starting my turn...")
(ws/send-action! "start-turn" nil)
(Thread/sleep 1500)

(println "✅ Turn started!")
(ai/status)

:done
