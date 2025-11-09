;; Test clicking for credit
(println "\n=== TEST: CLICK FOR CREDIT ===")
(println "Before:" (ws/my-credits) "credits," (get-in (ws/get-current-state) [:runner :click]) "clicks")

(ws/send-action! "credit" nil)
(Thread/sleep 1500)

(println "After:" (ws/my-credits) "credits," (get-in (ws/get-current-state) [:runner :click]) "clicks")
(println "✅ Test complete")

:done
