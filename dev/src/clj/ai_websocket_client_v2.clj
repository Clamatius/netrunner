(ns ai-websocket-client-v2
  "WebSocket client using gniazdo (JVM WebSocket library)"
  (:require
   [ai-debug :as debug]
   [ai-state :as state]
   [ai-hud-utils :as hud]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [differ.core :as differ]
   [gniazdo.core :as ws]
   [jinteki.cards :refer [all-cards]])
  (:import [java.net URLEncoder]
           [org.eclipse.jetty.websocket.client WebSocketClient]
           [org.eclipse.jetty.util.ssl SslContextFactory]))

(declare ensure-connected!)

;; ============================================================================
;; Message Handling
;; ============================================================================

(defn parse-message
  "Parse incoming WebSocket message - returns vector of parsed events"
  [msg]
  (try
    (let [data (if (string? msg)
                 (edn/read-string {:readers {'time/instant #(java.time.Instant/parse %)}} msg)
                 msg)]
      (debug/debug "🔍 RAW RECEIVED:" (pr-str data))

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
  (when (not= type :chsk/ws-ping)
    (debug/debug "🔧 HANDLING MESSAGE:" type))

  ;; Record all messages (except pings)
  (when (not= type :chsk/ws-ping)
    (swap! state/client-state update :messages (fn [msgs] (conj (vec msgs) msg))))

  (case type
    :chsk/handshake
    (do
      (println "✅ Connected! UID:" (first data))
      (swap! state/client-state assoc :uid (first data) :connected true))

    :game/start
    (let [state (if (string? data)
                  (json/parse-string data true)
                  data)]
      (println "\n🎮 GAME STARTING!")
      (println "  GameID:" (:gameid state))
      ;; Initialize this client's section in shared HUD
      (hud/update-hud-section "Game Status"
                         (str "Game starting...\nGameID: " (:gameid state)))
      (state/set-full-state! state)
      (swap! state/client-state assoc :gameid (state/normalize-gameid (:gameid state)))
      ;; Bump cursor for wait synchronization
      (state/bump-cursor!))

    :game/diff
    (let [diff-data (if (string? data)
                      (json/parse-string data true)
                      data)
          {:keys [gameid diff]} diff-data
          client-gameid (:gameid @state/client-state)]
      ;; FIX: Compare as strings since JSON parses UUIDs as strings
      (if (= (str gameid) (str client-gameid))
        (do
          (println "\n🔄 GAME/DIFF received")
          (println "   GameID:" gameid)
          (println "   Diff type:" (type diff))
          (println "   Diff keys (if map):" (when (map? diff) (keys diff)))
          (println "   Diff sample:" (pr-str (if (coll? diff)
                                                (take 5 diff)
                                                diff)))
          (state/update-game-state! diff)
          ;; Clear lobby-state once game has started (receiving diffs means game is active)
          (swap! state/client-state dissoc :lobby-state :diff-mismatch)
          ;; Track last successful diff time
          (swap! state/client-state assoc :last-diff-time (System/currentTimeMillis))
          ;; Announce newly revealed cards in Archives
          (hud/announce-revealed-archives diff)
          ;; Auto-update game log HUD
          (hud/write-game-log-to-hud 30)
          ;; Bump cursor for wait synchronization
          (state/bump-cursor!)
          (println "   ✓ Diff applied successfully"))
        ;; Diff doesn't match our game - we might be stale
        (do
          (swap! state/client-state assoc :diff-mismatch true)
          (debug/debug "WARN" (str "Diff dropped - gameid mismatch. Diff: " gameid " Client: " client-gameid)))))

    :game/resync
    (let [state (if (string? data)
                  (json/parse-string data true)
                  data)]
      (println "🔄 Game resync")
      (state/set-full-state! state)
      ;; Bump cursor for wait synchronization
      (state/bump-cursor!))

    :game/error
    (do
      (println "❌ SERVER ERROR RECEIVED!")
      (println "   Data:" (pr-str data)))

    :lobby/list
    (do
      (swap! state/client-state assoc :lobby-list data)
      (println "📋 Received" (count data) "game(s)"))

    :lobby/state
    (do
      (println "🎮 Lobby state update")
      (when data
        (when-let [gameid (:gameid data)]
          (swap! state/client-state assoc :gameid (state/normalize-gameid gameid) :lobby-state data)
          (println "   GameID:" gameid))))

    :lobby/notification
    (println "🔔 Lobby notification:" data)

    :chsk/ws-ping
    ;; Respond to ping with pong to keep connection alive
    ;; Echo back ping data if present (some Sente versions use ping IDs)
    (when-let [socket (:socket @state/client-state)]
      (try
        (let [pong-msg (if data
                         (pr-str [[:chsk/ws-pong data]])
                         (pr-str [[:chsk/ws-pong]]))]
          (ws/send-msg socket pong-msg)
          (println "📤 Sent pong" (if data (str "(id: " data ")") "")))
        (catch Exception e
          (println "❌ Failed to send pong:" (.getMessage e)))))

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
;; Authentication
;; ============================================================================

(defn get-csrf-token!
  "Get CSRF token from the main page for WebSocket connection"
  []
  (try
    (let [get-res (http/get "http://localhost:1042" {:as :text})]
      (if (:error get-res)
        (do
          (println "❌ Failed to get CSRF token:" (:error get-res))
          nil)
        (let [csrf-token (second (re-find #"data-csrf-token=\"(.*?)\"" (str (:body get-res))))]
          (if csrf-token
            (do
              (swap! state/client-state assoc :csrf-token csrf-token)
              (println "✅ Got CSRF token")
              csrf-token)
            (do
              (println "❌ Could not extract CSRF token from page")
              nil)))))
    (catch Exception e
      (println "❌ CSRF token fetch exception:" (.getMessage e))
      nil)))

;; ============================================================================
;; WebSocket Connection
;; ============================================================================

(defn connect!
  "Connect to WebSocket server.

   Authentication modes:
   1. If :session-token is set in state, use proper cookie-based auth
   2. Otherwise fall back to client-id auth (requires server-side hack)

   The session token is obtained by calling ai-auth/login! before connect."
  [url]
  (println "🔌 Connecting to" url "...")
  (try
    ;; Close old connection if it exists to prevent leaks
    (when-let [old-socket (:socket @state/client-state)]
      (println "🧹 Closing old socket connection...")
      (try
        (ws/close old-socket)
        (catch Exception e
          (println "⚠️  Error closing old socket:" (.getMessage e)))))

    (when-let [old-client (:ws-client @state/client-state)]
      (println "🧹 Stopping old WebSocket client...")
      (try
        (.stop old-client)
        (catch Exception e
          (println "⚠️  Error stopping old client:" (.getMessage e)))))

    ;; Authentication: Match web client behavior
    ;; - client-id and csrf-token go in URL params
    ;; - session cookie goes in Cookie header (like browser sends automatically)
    (let [session-token (:session-token @state/client-state)
          csrf-token (:csrf-token @state/client-state)
          ;; Always generate a client-id (web client does this too)
          existing-id (:client-id @state/client-state)
          client-id (or existing-id (str "ai-client-" (java.util.UUID/randomUUID)))
          _ (swap! state/client-state assoc :client-id client-id)
          ;; Build URL with client-id and csrf-token (like web client)
          full-url (str url "?client-id=" client-id
                        (when csrf-token
                          (str "&csrf-token=" (URLEncoder/encode csrf-token "UTF-8"))))
          ;; Create custom WebSocket client with longer idle timeout (10 minutes instead of 5)
          ws-client (if (.startsWith url "wss://")
                      (WebSocketClient. (SslContextFactory.))
                      (WebSocketClient.))
          _ (doto (.getPolicy ws-client)
              (.setIdleTimeout 600000))
          _ (.start ws-client)
          ;; Build headers - include session cookie if authenticated
          headers (when session-token
                    {"Cookie" (str "session=" session-token)})
          ;; Prepare connection options
          conn-opts (cond-> {:on-receive on-receive
                             :on-connect (fn [& _args]
                                           (println "⏳ WebSocket connected, waiting for handshake..."))
                             :on-close (fn [& args]
                                         (println "❌ Disconnected:" (first args))
                                         (.stop ws-client)
                                         ;; Clear game state to prevent stale reads, but keep gameid for rejoin
                                         (swap! state/client-state assoc
                                                :connected false
                                                :game-state nil
                                                :last-state nil))
                             :on-error (fn [& args]
                                         (println "❌ Error:" (first args)))
                             :client ws-client}
                      headers (assoc :headers headers))
          socket (ws/connect full-url conn-opts)]
      (swap! state/client-state assoc
             :socket socket
             :ws-client ws-client)
      (if session-token
        (println "✨ WebSocket connected with session auth (user:" (:username @state/client-state) ")")
        (println "✨ WebSocket connected with client-id auth (fallback mode)"))
      socket)
    (catch Exception e
      (println "❌ Connection failed:" (.getMessage e))
      nil)))

(defn disconnect!
  "Disconnect from server"
  []
  (when-let [socket (:socket @state/client-state)]
    (ws/close socket)
    (println "👋 Socket disconnected"))

  (when-let [ws-client (:ws-client @state/client-state)]
    (try
      (.stop ws-client)
      (println "👋 WebSocket client stopped")
      (catch Exception e
        (println "⚠️  Error stopping client:" (.getMessage e)))))

  (swap! state/client-state assoc :socket nil :ws-client nil :connected false))

;; ============================================================================
;; Sending Messages
;; ============================================================================

(defn send-message!
  "Send a message to server.
   Auto-reconnects if disconnected or send fails."
  [event-type data]
  ;; Ensure connected before sending
  (when (ensure-connected!)
    (if-let [socket (:socket @state/client-state)]
      (try
        ;; Sente expects double-wrapped messages: [[:event-type data]]
        (let [msg (pr-str [[event-type data]])]
          (ws/send-msg socket msg)
          true)
        (catch Exception e
          (println "❌ Send failed:" (.getMessage e))
          ;; Try one reconnect and retry
          (println "♻️  Attempting reconnect and retry...")
          (when (ensure-connected!)
            (if-let [new-socket (:socket @state/client-state)]
              (try
                (let [msg (pr-str [[event-type data]])]
                  (ws/send-msg new-socket msg)
                  (println "✅ Retry successful")
                  true)
                (catch Exception e2
                  (println "❌ Retry failed:" (.getMessage e2))
                  false))
              false))))
      (do
        (println "❌ Not connected (after ensure-connected!)")
        false))))

(defn send-action!
  "Send a game action"
  [command args]
  (let [gameid (:gameid @state/client-state)]
    (if gameid
      (send-message! :game/action
                       {:gameid gameid
                        :command command
                        :args args})
      (println "❌ No active game"))))

;; ============================================================================
;; High-Level API
;; ============================================================================
;; Note: Lobby operations moved to ai-connection namespace

(defn get-current-state [] (:game-state @state/client-state))
(defn get-my-side [] (:side @state/client-state))
(defn connected? [] (:connected @state/client-state))
(defn in-game? [] (some? (:gameid @state/client-state)))
(defn get-lobby-list [] (:lobby-list @state/client-state))

(defn socket-healthy?
  "Check if the WebSocket socket is actually usable.
   The :connected flag can be true even when the socket object is broken.
   This function tries to verify the socket can send a message."
  []
  (when-let [socket (:socket @state/client-state)]
    (try
      ;; Try to send a ping message - if socket is broken, this will throw
      ;; We use ws-pong as a lightweight message that won't affect game state
      (ws/send-msg socket (pr-str [[:chsk/ws-ping]]))
      true
      (catch Exception e
        (debug/debug "WARN" (str "Socket health check failed: " (.getMessage e)))
        false))))

;; ============================================================================
;; Connection Management
;; ============================================================================

(defn rejoin-game!
  "Rejoin a game after reconnection using gameid from client state root"
  []
  (when-let [gameid (:gameid @state/client-state)]
    (let [side (or (:side @state/client-state) "Runner")]
      (println "♻️  Rejoining game:" gameid "as" side)
      (send-message! :lobby/join
                     {:gameid (state/normalize-gameid gameid)
                      :request-side (str/capitalize side)})
      (Thread/sleep 2000)
      true)))

(defn ensure-connected!
  "Check connection and reconnect if needed. Returns true if connected.
   Also checks socket health - the :connected flag can be stale.
   After reconnect, auto-rejoins game if we had one."
  ([] (ensure-connected! "ws://localhost:1042/chsk"))
  ([url]
   (let [had-gameid (:gameid @state/client-state)
         reconnected
         (cond
           ;; Socket exists but is broken - clear it and reconnect
           (and (connected?) (not (socket-healthy?)))
           (do
             (println "⚠️  Socket broken, clearing and reconnecting...")
             (swap! state/client-state assoc :socket nil :ws-client nil :connected false)
             (Thread/sleep 200)
             (connect! url)
             (Thread/sleep 2000)
             :reconnected)

           ;; Not connected at all
           (not (connected?))
           (do
             (println "⚠️  Not connected, reconnecting...")
             (connect! url)
             (Thread/sleep 2000)
             :reconnected)

           ;; Connected and healthy
           :else :already-connected)]
     ;; Auto-rejoin if we reconnected and had a game
     (when (and (= reconnected :reconnected) had-gameid (connected?))
       (rejoin-game!))
     (connected?))))

;; ============================================================================
;; Action Helpers (Used by ai-prompts)
;; ============================================================================
;; Note: Prompt display moved to ai-display namespace
;; Note: take-credits!, draw-card!, end-turn!, run-server!, play-card! moved to specialized modules

(defn safe-action!
  "Send action with connection check"
  [command args]
  (when (ensure-connected!)
    (send-action! command args)))

(defn choose!
  "Make a choice from a prompt by index or UUID"
  [choice]
  (if (number? choice)
    ;; Choice by index
    (let [prompt (state/get-prompt)
          uuid (get-in prompt [:choices choice :uuid])]
      (when uuid
        (safe-action! "choice" {:choice {:uuid uuid}})))
    ;; Choice by UUID string
    (safe-action! "choice" {:choice {:uuid choice}})))

(defn select-card!
  "Select a card for prompts like discard.
   Takes a card object and prompt eid.
   Card should have :cid, :zone, :side, :type fields."
  [card eid]
  (safe-action! "select"
                {:card {:cid (:cid card)
                        :zone (:zone card)
                        :side (:side card)
                        :type (:type card)}
                 :eid eid
                 :shift-key-held false}))

(defn handle-discard-prompt!
  "Handle discard down to hand size prompt.
   Discards cards one at a time until hand size is acceptable.
   Returns number of cards discarded."
  [side]
  (let [gs (state/get-game-state)
        prompt (get-in gs [side :prompt-state])
        hand (get-in gs [side :hand])
        hand-size-max (get-in gs [side :hand-size :total])
        cards-to-discard (- (count hand) hand-size-max)]
    (if (and (= "select" (:prompt-type prompt))
             (> cards-to-discard 0))
      (do
        (println (format "Need to discard %d cards from hand of %d (max %d)"
                        cards-to-discard (count hand) hand-size-max))
        ;; Snapshot hand once - local state doesn't update immediately after each select
        (let [cards-to-discard-list (take cards-to-discard hand)]
          (doseq [card cards-to-discard-list]
            (println (format "Discarding: %s" (:title card)))
            (select-card! card (:eid prompt))
            (Thread/sleep 500)))
        (println (format "✅ Discarded %d card(s)" cards-to-discard))
        cards-to-discard)
      0)))

;; Note: Status display functions moved to ai-display namespace
;; Note: announce-revealed-archives and write-game-log-to-hud moved to ai-display namespace

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
