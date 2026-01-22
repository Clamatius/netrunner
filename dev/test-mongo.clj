(println "Testing MongoDB connection directly...")
(require '[monger.core :as mg])

(try
  (println "1. Connecting to MongoDB...")
  (def conn (mg/connect-via-uri "mongodb://localhost:27017/netrunner"))
  (println "✓ Connected successfully:")
  (println "  Connection:" (type conn))
  (println "  Keys:" (keys conn))
  
  (println "\n2. Testing database access...")  
  (def db (:db conn))
  (println "✓ Got database:" db)
  
  (println "\n3. Testing collection access...")
  (require '[monger.collection :as mc])
  (def collections (mc/find-maps db "users" nil))
  (println "✓ Can query database. Found" (count collections) "users")
  
  (println "\n4. Connection state check...")
  (println "  Connection details:" conn)
  
  (println "\n✓ MongoDB connection is working!")
  (catch Exception e
    (println "\n✗ MongoDB test failed:")
    (println "  Message:" (.getMessage e))
    (.printStackTrace e)))
