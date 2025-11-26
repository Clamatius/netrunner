;; Helper to debug (go) failures
;; Usage: Load this in the REPL after startup

(println "\n=== Debugging (go) initialization ===\n")

(println "1. Checking server config...")
(try
  (def config (web.system/server-config))
  (println "✓ Config loaded:")
  (clojure.pprint/pprint (select-keys config [:web/server :mongodb/connection]))
  (catch Exception e
    (println "✗ Failed to load config:")
    (println (.getMessage e))))

(println "\n2. Checking MongoDB connection...")
(try
  (def test-conn (get config :mongodb/connection))
  (println "MongoDB config:" test-conn)
  (catch Exception e
    (println "✗ MongoDB issue:" (.getMessage e))))

(println "\n3. Attempting (go) with error handling...")
(try
  (println "Calling (go)...")
  (go)
  (println "✓ (go) succeeded!")
  (println "\n4. Checking if web server is running...")
  (Thread/sleep 2000)
  (if-let [server @integrant.repl.state/system]
    (do
      (println "✓ Integrant system started:")
      (println "  Keys:" (keys server)))
    (println "✗ No Integrant system found"))
  (catch Exception e
    (println "✗ (go) failed:")
    (println "  Message:" (.getMessage e))
    (println "\nStack trace:")
    (.printStackTrace e)))

(println "\n5. Checking port 1042...")
(try
  (import '[java.net ServerSocket])
  (let [test-sock (ServerSocket. 1042)]
    (.close test-sock)
    (println "✓ Port 1042 is available"))
  (catch java.net.BindException e
    (println "✗ Port 1042 is already in use"))
  (catch Exception e
    (println "? Port check error:" (.getMessage e))))
