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
  (let [result (go)]
    (println "✓ (go) returned successfully!")
    (println "  Result:" result)
    (println "  Result type:" (type result))
    (when (map? result)
      (println "  System keys:" (keys result))))
  (catch Exception e
    (println "✗ (go) failed:")
    (println "  Message:" (.getMessage e))
    (println "\nStack trace:")
    (.printStackTrace e)))

(println "\n4. Checking port 1042...")
(try
  (let [test-sock (java.net.ServerSocket. 1042)]
    (.close test-sock)
    (println "✓ Port 1042 is available (web server NOT running)"))
  (catch java.net.BindException e
    (println "✅ Port 1042 is in use - WEB SERVER IS RUNNING!"))
  (catch Exception e
    (println "? Port check error:" (.getMessage e))))
