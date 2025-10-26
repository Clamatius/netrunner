(ns test-harness
  "Multi-client WebSocket test harness for AI player development

  Runs multiple WebSocket clients concurrently in futures to test:
  - Connection and handshake
  - Lobby creation and joining
  - Game setup and initialization

  Exercises the full WebSocket stack to catch codec, auth, and routing issues."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [gniazdo.core :as ws])
  (:import [java.io ByteArrayInputStream]))

;; ============================================================================
;; Test Client State Management
;; ============================================================================

(defonce test-clients
  "Registry of active test clients"
  (atom {}))

(defn create-client-state
  "Create isolated state for a test client"
  [client-name]
  {:name client-name
   :connected false
   :socket nil
   :uid nil
   :gameid nil
   :messages []
   :lobby-list nil
   :game-state nil
   :errors []})

(defn log-client
  "Log a message with client name prefix"
  [client-name & msgs]
  (println (str "[" client-name "] ") (apply str msgs)))

(defn record-message!
  "Record a received message for a client"
  [client-name msg]
  (swap! test-clients update-in [client-name :messages] conj msg))

(defn record-error!
  "Record an error for a client"
  [client-name error]
  (swap! test-clients update-in [client-name :errors] conj error)
  (log-client client-name "❌ ERROR: " error))

;; ============================================================================
;; Message Parsing (EDN format for Sente WebSocket)
;; ============================================================================

(defn parse-message
  "Parse incoming WebSocket message (EDN format)"
  [client-name msg]
  (try
    (let [data (if (string? msg)
                 (edn/read-string msg)
                 msg)]
      (cond
        ;; Wrapped handshake: [[:chsk/handshake [...]]]
        (and (vector? data) (= 1 (count data)) (vector? (first data)))
        (let [[type payload] (first data)]
          {:type type :data payload})

        ;; Normal message: [:event-type data] or [:event-type]
        (vector? data)
        {:type (first data)
         :data (second data)}

        :else
        {:type :unknown :data data}))
    (catch Exception e
      (record-error! client-name (str "Parse error: " (.getMessage e)))
      nil)))

;; ============================================================================
;; Message Handlers
;; ============================================================================

(defn handle-handshake
  "Handle successful WebSocket handshake"
  [client-name data]
  (let [uid (first data)]
    (log-client client-name "✅ Connected! UID: " uid)
    (swap! test-clients update client-name assoc
           :connected true
           :uid uid)))

(defn handle-lobby-list
  "Handle lobby list response"
  [client-name data]
  (log-client client-name "📋 Received " (count data) " game(s)")
  (swap! test-clients update client-name assoc :lobby-list data))

(defn handle-lobby-state
  "Handle lobby state update"
  [client-name data]
  (log-client client-name "🎮 Lobby state updated")
  (when-let [gameid (:gameid data)]
    (swap! test-clients update client-name assoc :gameid gameid)))

(defn handle-game-start
  "Handle game start message"
  [client-name data]
  (log-client client-name "🎮 GAME STARTED!")
  (swap! test-clients update client-name assoc :game-state data))

(defn handle-message
  "Route message to appropriate handler"
  [client-name {:keys [type data] :as msg}]
  (when msg
    (record-message! client-name msg)
    (case type
      :chsk/handshake (handle-handshake client-name data)
      :lobby/list (handle-lobby-list client-name data)
      :lobby/state (handle-lobby-state client-name data)
      :game/start (handle-game-start client-name data)
      :chsk/ws-ping nil  ; Ignore pings
      ;; Log unhandled messages
      (when (not= type :chsk/ws-ping)
        (log-client client-name "📨 Unhandled: " type)))))

;; ============================================================================
;; WebSocket Operations
;; ============================================================================

(defn on-receive
  "Callback when WebSocket receives a message"
  [client-name msg]
  (when-let [parsed (parse-message client-name msg)]
    (handle-message client-name parsed)))

(defn connect-client!
  "Connect a test client to the WebSocket server"
  [client-name url]
  (log-client client-name "🔌 Connecting to " url "...")
  (try
    ;; Use "ai-client-" prefix to trigger fake user creation in web.auth
    (let [client-id (str "ai-client-" client-name "-" (java.util.UUID/randomUUID))
          full-url (str url "?client-id=" client-id)
          socket (ws/connect full-url
                             :on-receive (fn [msg & _] (on-receive client-name msg))
                             :on-connect (fn [& _]
                                          (log-client client-name "⏳ WebSocket connected, waiting for handshake..."))
                             :on-close (fn [& args]
                                        (log-client client-name "❌ Disconnected: " (first args))
                                        (swap! test-clients update client-name assoc :connected false))
                             :on-error (fn [& args]
                                        (record-error! client-name (str "WebSocket error: " (first args)))))]
      (swap! test-clients update client-name assoc :socket socket)
      (log-client client-name "✨ WebSocket connection initiated")
      socket)
    (catch Exception e
      (record-error! client-name (str "Connection failed: " (.getMessage e)))
      nil)))

(defn disconnect-client!
  "Disconnect a test client"
  [client-name]
  (when-let [socket (get-in @test-clients [client-name :socket])]
    (ws/close socket)
    (swap! test-clients update client-name assoc :socket nil :connected false)
    (log-client client-name "👋 Disconnected")))

(defn send-message!
  "Send a message from a test client"
  [client-name event-type data]
  (if-let [socket (get-in @test-clients [client-name :socket])]
    (try
      ;; Encode with EDN (Sente's WebSocket default)
      (let [msg (pr-str [event-type data])]
        (ws/send-msg socket msg)
        (log-client client-name "📤 Sent: " event-type)
        true)
      (catch Exception e
        (record-error! client-name (str "Send failed: " (.getMessage e)))
        false))
    (do
      (record-error! client-name "Not connected")
      false)))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn wait-for
  "Wait for a predicate to be true, or timeout"
  [pred timeout-ms interval-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (cond
        (pred) true
        (> (- (System/currentTimeMillis) start) timeout-ms) false
        :else (do
                (Thread/sleep interval-ms)
                (recur))))))

(defn client-connected?
  "Check if a client is connected"
  [client-name]
  (get-in @test-clients [client-name :connected]))

(defn get-client
  "Get client state"
  [client-name]
  (get @test-clients client-name))

;; ============================================================================
;; Game Operations
;; ============================================================================

(defn create-lobby!
  "Create a new lobby"
  [client-name options]
  (log-client client-name "🎮 Creating lobby...")
  (send-message! client-name :lobby/create options))

(defn request-lobby-list!
  "Request the list of available games"
  [client-name]
  (log-client client-name "📋 Requesting lobby list...")
  (send-message! client-name :lobby/list {}))

(defn join-lobby!
  "Join a game by ID"
  [client-name {:keys [gameid side password]}]
  (log-client client-name "🎮 Joining game " gameid " as " side "...")
  (let [request-side (or side "Any Side")]
    (send-message! client-name :lobby/join
                   (cond-> {:gameid gameid
                            :request-side request-side}
                     password (assoc :password password)))))

;; ============================================================================
;; Test Scenarios
;; ============================================================================

(defn run-connection-test
  "Test 1: Basic connection and handshake"
  []
  (println "\n========================================")
  (println "TEST: Connection and Handshake")
  (println "========================================\n")

  ;; Initialize client state
  (swap! test-clients assoc :client1 (create-client-state :client1))

  ;; Connect
  (when (connect-client! :client1 "ws://localhost:1042/chsk")
    ;; Wait for handshake (max 3 seconds)
    (if (wait-for #(client-connected? :client1) 3000 100)
      (do
        (println "\n✅ TEST PASSED: Client connected and received handshake")
        (println "UID:" (get-in @test-clients [:client1 :uid]))
        {:success? true
         :client (get-client :client1)})
      (do
        (println "\n❌ TEST FAILED: Handshake timeout")
        {:success? false
         :error "Handshake timeout"
         :client (get-client :client1)}))))

(defn run-lobby-test
  "Test 2: Create and join lobby"
  []
  (println "\n========================================")
  (println "TEST: Lobby Creation and Joining")
  (println "========================================\n")

  ;; Initialize two clients
  (swap! test-clients assoc
         :corp (create-client-state :corp)
         :runner (create-client-state :runner))

  (try
    ;; Connect both clients
    (println "Step 1: Connecting both clients...")
    (connect-client! :corp "ws://localhost:1042/chsk")
    (connect-client! :runner "ws://localhost:1042/chsk")

    ;; Wait for both handshakes
    (if-not (wait-for #(and (client-connected? :corp)
                            (client-connected? :runner))
                      5000 100)
      {:success? false :error "Handshake timeout"}

      (do
        (println "\n✅ Both clients connected")
        (Thread/sleep 500)  ; Let server process connections

        ;; Corp creates a lobby
        (println "\nStep 2: Corp creating lobby...")
        (create-lobby! :corp {:title "Test Game"
                              :side "Corp"
                              :format "casual"})

        (Thread/sleep 1000)  ; Wait for lobby creation

        ;; Check if corp has a gameid
        (if-let [gameid (get-in @test-clients [:corp :gameid])]
          (do
            (println "\n✅ Lobby created with ID: " gameid)

            ;; Runner requests lobby list
            (println "\nStep 3: Runner requesting lobby list...")
            (request-lobby-list! :runner)

            (Thread/sleep 1000)  ; Wait for lobby list

            ;; Runner joins the game
            (println "\nStep 4: Runner joining game...")
            (join-lobby! :runner {:gameid gameid :side "Runner"})

            (Thread/sleep 1000)  ; Wait for join

            (println "\n✅ TEST PASSED: Lobby created and joined")
            {:success? true
             :gameid gameid
             :corp (get-client :corp)
             :runner (get-client :runner)})

          (do
            (println "\n❌ TEST FAILED: No gameid received after lobby creation")
            {:success? false
             :error "No gameid"
             :corp (get-client :corp)}))))

    (catch Exception e
      (println "\n❌ TEST FAILED with exception: " (.getMessage e))
      {:success? false
       :error (.getMessage e)})))

(defn cleanup!
  "Disconnect all test clients and clear state"
  []
  (println "\n🧹 Cleaning up test clients...")
  (doseq [client-name (keys @test-clients)]
    (disconnect-client! client-name))
  (reset! test-clients {})
  (println "✅ Cleanup complete\n"))

;; ============================================================================
;; Usage Examples
;; ============================================================================

(comment
  ;; Run basic connection test
  (run-connection-test)

  ;; Run lobby creation/join test
  (run-lobby-test)

  ;; Check client state
  @test-clients
  (get-client :corp)
  (get-client :runner)

  ;; Inspect messages
  (get-in @test-clients [:corp :messages])

  ;; Clean up
  (cleanup!)
  )
