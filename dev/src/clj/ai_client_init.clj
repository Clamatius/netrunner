(ns ai-client-init
  "Initialization script for AI Client REPL")

(println "\n=== AI Client REPL Starting ===")
(println "Loading foundational modules...")

;; Load foundation modules first (no dependencies)
(try
  (load-file "dev/src/clj/ai_debug.clj")
  (load-file "dev/src/clj/ai_state.clj")
  (load-file "dev/src/clj/ai_hud_utils.clj")
  (catch Exception e
    (println "❌ FATAL ERROR loading foundation modules:")
    (println (.getMessage e))
    (.printStackTrace e)
    (throw e)))

(println "Loading WebSocket client...")
;; Load the websocket client
(try
  (load-file "dev/src/clj/ai_websocket_client_v2.clj")
  (catch Exception e
    (println "❌ FATAL ERROR loading ai_websocket_client_v2.clj:")
    (println (.getMessage e))
    (.printStackTrace e)
    (throw e)))

(println "Loading actions modules...")
;; Load the 7 action modules in dependency order
(try
  ;; First load ai-core (no dependencies except websocket client)
  (load-file "dev/src/clj/ai_core.clj")
  ;; Then load modules that depend only on ai-core
  (load-file "dev/src/clj/ai_connection.clj")
  (load-file "dev/src/clj/ai_basic_actions.clj")
  (load-file "dev/src/clj/ai_prompts.clj")
  (load-file "dev/src/clj/ai_card_actions.clj")
  (load-file "dev/src/clj/ai_runs.clj")
  ;; Load ai_display last (depends on ai-basic-actions)
  (load-file "dev/src/clj/ai_display.clj")
  ;; Finally load the facade that re-exports everything
  (load-file "dev/src/clj/ai_actions.clj")
  (catch Exception e
    (println "❌ FATAL ERROR loading ai action modules:")
    (println (.getMessage e))
    (.printStackTrace e)
    (throw e)))

;; Also make these available in user namespace for easier access
(in-ns 'user)
(require '[ai-websocket-client-v2 :as ws])
(require '[ai-actions :as ai])
(in-ns 'ai-client-init)

(println "Setting client id")
;; Set client-id from environment variable or use default
;; IMPORTANT: Must start with "ai-client-" to trigger fake user creation in web.auth
(let [client-name (or (System/getenv "AI_CLIENT_NAME") "fixed-id")
      client-id (str "ai-client-" client-name)]
  (println (str "Client name: " client-name))
  (swap! ai-state/client-state assoc :client-id client-id))

;; Get CSRF token from the main page
(println "\nGetting CSRF token...")
(ai-websocket-client-v2/get-csrf-token!)

(println "Connecting to ws://localhost:1042/chsk...")
(ai-websocket-client-v2/connect! "ws://localhost:1042/chsk")
(Thread/sleep 2000)

(if (ai-websocket-client-v2/connected?)
  (do
    (println "\n✅ AI Client Ready!")
    (println "   UID:" (:uid @ai-state/client-state))
    (println "\nAvailable commands:")
    (println "  (ai-actions/connect-game! \"game-id\" \"Corp\") - Join a game")
    (println "  (ai-actions/status)                          - Show game status")
    (println "  (ai-actions/keep-hand)                       - Keep hand (mulligan)")
    (println "  (ai-actions/take-credits)                    - Click for credit")
    (println "  (ai-actions/end-turn)                        - End turn")
    (println "  (ai-actions/help)                            - Show all commands")
    (println "\nFor full API, see dev/src/clj/ai_actions.clj"))
  (println "\n❌ Failed to connect to game server"))

(println "\n=== Ready for commands ===\n")
