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

(println "Loading WebSocket client and auth...")
;; Load the websocket client and auth module
(try
  (load-file "dev/src/clj/ai_websocket_client_v2.clj")
  (load-file "dev/src/clj/ai_auth.clj")
  (catch Exception e
    (println "❌ FATAL ERROR loading ai_websocket_client_v2.clj or ai_auth.clj:")
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

;; Make these available in user namespace for easier access
(in-ns 'user)
(require '[ai-state :as state])
(require '[ai-websocket-client-v2 :as ws])
(require '[ai-auth :as auth])
(require '[ai-connection])
(require '[ai-actions])
(require '[ai-actions :as ai])

;; Stay in user namespace so evals work correctly
;; (REPL evals will be in whatever namespace is current when init finishes)

;; ============================================================================
;; Authentication Configuration
;; ============================================================================
;; Authentication mode:
;; - If AI_USERNAME is set: Use proper auth (register/login, then connect)
;; - Otherwise: Fall back to client-id hack (for backwards compatibility)
;;
;; Environment variables:
;;   AI_USERNAME  - Username for AI player account
;;   AI_PASSWORD  - Password (defaults to AI_USERNAME + "_pass" if not set)
;;   AI_BASE_URL  - Server URL (defaults to http://localhost:1042)
;;   AI_CLIENT_NAME - Fallback client-id suffix (if not using proper auth)

(let [username (System/getenv "AI_USERNAME")
      password (or (System/getenv "AI_PASSWORD") (when username (str username "_pass")))
      base-url (or (System/getenv "AI_BASE_URL") "http://localhost:1042")
      ws-url (str (clojure.string/replace base-url #"^http" "ws") "/chsk")]

  (if username
    ;; === Proper Authentication Mode ===
    (do
      (println "\n🔐 Authenticating as:" username)
      (println "   Server:" base-url)

      ;; Get CSRF token first (needed for both login and WebSocket)
      (println "   Getting CSRF token...")
      (ai-websocket-client-v2/get-csrf-token!)

      ;; Authenticate (will auto-register if needed)
      (let [auth-result (ai-auth/ensure-authenticated!
                          {:username username
                           :password password
                           :base-url base-url})]
        (if (= :success (:status auth-result))
          (do
            (println "   Connecting WebSocket...")
            (ai-websocket-client-v2/connect! ws-url)
            (Thread/sleep 2000))
          (println "❌ Authentication failed:" (:message auth-result)))))

    ;; === Fallback: Client-ID Mode (requires server hack) ===
    (do
      (println "\n⚠️  No AI_USERNAME set - using client-id auth (fallback mode)")
      (println "   Set AI_USERNAME and AI_PASSWORD for proper auth")

      ;; Set client-id from AI_CLIENT_NAME env var (set by start-ai-client-repl.sh)
      ;; IMPORTANT: Must start with "ai-client-" to trigger fake user creation
      ;; Using side name (runner/corp) ensures stable identity across REPL restarts
      (let [client-name (or (System/getenv "AI_CLIENT_NAME") "runner")
            client-id (str "ai-client-" client-name)]
        (println (str "   Client name: " client-name))
        (swap! ai-state/client-state assoc :client-id client-id))

      ;; Get CSRF token from the main page
      (println "   Getting CSRF token...")
      (ai-websocket-client-v2/get-csrf-token!)

      (println "   Connecting WebSocket...")
      (ai-websocket-client-v2/connect! ws-url)
      (Thread/sleep 2000))))

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
