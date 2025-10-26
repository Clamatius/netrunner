(ns ai-websocket-client-v2
  "WebSocket client using gniazdo (JVM WebSocket library)"
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [differ.core :as differ]
   [gniazdo.core :as ws]))

;; ============================================================================
;; State Management
;; ============================================================================

(defonce client-state
  (atom {:connected false
         :game-state nil
         :last-state nil
         :gameid nil
         :side nil
         :uid nil
         :socket nil
         :lobby-list nil}))

(defn apply-diff
  "Apply a diff to current state to get new state"
  [old-state diff]
  (if old-state
    (differ/patch old-state diff)
    diff))

(defn update-game-state!
  "Update game state from a diff"
  [diff]
  (let [old-state (:last-state @client-state)
        new-state (apply-diff old-state diff)]
    (swap! client-state assoc
           :game-state new-state
           :last-state new-state)))

(defn set-full-state!
  "Set initial game state"
  [state]
  (swap! client-state assoc
         :game-state state
         :last-state state))

;; ============================================================================
;; Message Handling
;; ============================================================================

(defn parse-message
  "Parse incoming WebSocket message - returns vector of parsed events"
  [msg]
  (try
    (let [data (if (string? msg)
                 (edn/read-string msg)
                 msg)]
      ;; DEBUG: Log what we received
      (println "🔍 RAW RECEIVED:" (pr-str data))

      (cond
        ;; Batched events: [[[event1] [event2] ...]]
        ;; Example: [[[:lobby/list []] [:lobby/state]]]
        (and (vector? data)
             (= 1 (count data))
             (vector? (first data))
             (every? vector? (first data)))
        (let [events (first data)]
          (println "📦 BATCH of" (count events) "events")
          (mapv (fn [[type payload]]
                  {:type type :data payload})
                events))

        ;; Single wrapped event: [[:chsk/handshake [...]]]
        (and (vector? data) (= 1 (count data)) (vector? (first data)))
        (let [[type payload] (first data)]
          [{:type type :data payload}])

        ;; Normal message: [:event-type data]
        (vector? data)
        [{:type (first data)
          :data (second data)}]

        ;; Unknown format
        :else
        [{:type :unknown :data data}]))
    (catch Exception e
      (println "❌ Error parsing message:" (.getMessage e))
      (.printStackTrace e)
      nil)))

(defn handle-message
  "Handle parsed message"
  [{:keys [type data] :as msg}]
  (case type
    :chsk/handshake
    (do
      (println "✅ Connected! UID:" (first data))
      (swap! client-state assoc :uid (first data) :connected true))

    :game/start
    (let [state (if (string? data)
                  (json/parse-string data true)
                  data)]
      (println "\n🎮 GAME STARTING!")
      (println "  GameID:" (:gameid state))
      (set-full-state! state)
      (swap! client-state assoc :gameid (:gameid state)))

    :game/diff
    (let [diff-data (if (string? data)
                      (json/parse-string data true)
                      data)
          {:keys [gameid diff]} diff-data]
      (when (= gameid (:gameid @client-state))
        (update-game-state! diff)
        (println "📊 State updated")))

    :game/resync
    (let [state (if (string? data)
                  (json/parse-string data true)
                  data)]
      (println "🔄 Game resync")
      (set-full-state! state))

    :game/error
    (println "❌ Server error!")

    :lobby/list
    (do
      (swap! client-state assoc :lobby-list data)
      (println "📋 Received" (count data) "game(s)"))

    :lobby/state
    (do
      (println "🎮 Lobby state update")
      (when data
        (when-let [gameid (:gameid data)]
          (swap! client-state assoc :gameid gameid)
          (println "   GameID:" gameid))))

    ;; Default
    (when (not= type :chsk/ws-ping)
      (println "Unhandled message type:" type))))

(defn on-receive
  "Called when WebSocket receives a message"
  [msg & _rest-args]
  (when-let [events (parse-message msg)]
    ;; parse-message now returns a vector of events
    (doseq [event events]
      (handle-message event))))

;; ============================================================================
;; WebSocket Connection
;; ============================================================================

(defn connect!
  "Connect to WebSocket server"
  [url]
  (println "🔌 Connecting to" url "...")
  (try
    ;; Generate a unique client-id for Sente
    (let [client-id (str "ai-client-" (java.util.UUID/randomUUID))
          ;; Add client-id as query parameter for Sente handshake
          full-url (str url "?client-id=" client-id)
          socket (ws/connect full-url
                             :on-receive on-receive
                             :on-connect (fn [& _args]
                                          (println "⏳ WebSocket connected, waiting for handshake..."))
                             :on-close (fn [& args]
                                        (println "❌ Disconnected:" (first args))
                                        (swap! client-state assoc :connected false))
                             :on-error (fn [& args]
                                        (println "❌ Error:" (first args))))]
      (swap! client-state assoc :socket socket)
      (println "✨ WebSocket connection initiated with client-id:" client-id)
      socket)
    (catch Exception e
      (println "❌ Connection failed:" (.getMessage e))
      nil)))

(defn disconnect!
  "Disconnect from server"
  []
  (when-let [socket (:socket @client-state)]
    (ws/close socket)
    (swap! client-state assoc :socket nil :connected false)
    (println "👋 Disconnected")))

;; ============================================================================
;; Sending Messages
;; ============================================================================

(defn send-message!
  "Send a message to server"
  [event-type data]
  (if-let [socket (:socket @client-state)]
    (try
      ;; Encode with EDN (Sente's WebSocket default)
      (let [msg (pr-str [event-type data])]
        (ws/send-msg socket msg)
        true)
      (catch Exception e
        (println "❌ Send failed:" (.getMessage e))
        (.printStackTrace e)
        false))
    (do
      (println "❌ Not connected")
      false)))

(defn send-action!
  "Send a game action"
  [command args]
  (let [gameid (:gameid @client-state)]
    (if gameid
      (send-message! :game/action
                     {:gameid gameid
                      :command command
                      :args args})
      (println "❌ No active game"))))

;; ============================================================================
;; Lobby Operations
;; ============================================================================

(defn request-lobby-list!
  "Request the list of available games"
  []
  ;; Try double-wrapping like the handshake: [[:lobby/list]]
  (if-let [socket (:socket @client-state)]
    (try
      (let [msg (pr-str [[:lobby/list]])]  ;; Double-wrapped like handshake
        (ws/send-msg socket msg)
        (println "📤 Sent: :lobby/list (double-wrapped)")
        true)
      (catch Exception e
        (println "❌ Send failed:" (.getMessage e))
        false))
    (do
      (println "❌ Not connected")
      false)))

(defn join-game!
  "Join a game by ID
   Options:
     :gameid - game ID to join
     :side - \"Corp\" or \"Runner\" or \"Any Side\"
     :password - optional password"
  [{:keys [gameid side password]}]
  (let [request-side (or side "Any Side")]
    (send-message! :lobby/join
                   (cond-> {:gameid gameid
                            :request-side request-side}
                     password (assoc :password password)))
    (println "🎮 Attempting to join game" gameid "as" request-side)))

;; ============================================================================
;; High-Level API
;; ============================================================================

(defn get-current-state [] (:game-state @client-state))
(defn get-my-side [] (:side @client-state))
(defn connected? [] (:connected @client-state))
(defn in-game? [] (some? (:gameid @client-state)))
(defn get-lobby-list [] (:lobby-list @client-state))

(defn show-games
  "Display available games in a readable format"
  []
  (if-let [games (get-lobby-list)]
    (do
      (println "\n📋 Available Games:")
      (println (str/join "" (repeat 70 "=")))
      (doseq [game games]
        (println "\n🎮" (:title game))
        (println "   ID:" (:gameid game))
        (println "   Players:" (count (:players game)) "/" (:max-players game 2))
        (when-let [players (:players game)]
          (doseq [player players]
            (println "     -" (:side player) ":" (get-in player [:user :username] "Waiting..."))))
        (when (:started game)
          (println "   ⚠️  Game already started")))
      (println (str/join "" (repeat 70 "=")))
      (println "\nTo join a game, use: (join-game! {:gameid \"...\" :side \"Corp\"})")
      (println))
    (do
      (println "No games available. Request lobby list with: (request-lobby-list!)")
      nil)))

;; ============================================================================
;; Usage
;; ============================================================================

(comment
  ;; Connect to local server
  (connect! "ws://localhost:1042/chsk")

  ;; Check status
  (connected?)
  @client-state

  ;; Send action
  (send-action! "credit" nil)

  ;; Disconnect
  (disconnect!)
  )
