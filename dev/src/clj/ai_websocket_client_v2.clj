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

;; ============================================================================
;; State Management (Delegated to ai-state)
;; ============================================================================

;; Re-export state atom and functions from ai-state
(def client-state state/client-state)
(def update-game-state! state/update-game-state!)
(def set-full-state! state/set-full-state!)

;; ============================================================================
;; HUD Management (Delegated to ai-hud-utils)
;; ============================================================================

(def announce-revealed-archives hud/announce-revealed-archives)
(def write-game-log-to-hud hud/write-game-log-to-hud)
(def update-hud-section hud/update-hud-section)

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
    (swap! client-state update :messages (fn [msgs] (conj (vec msgs) msg))))

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
      ;; Initialize this client's section in shared HUD
      (update-hud-section "Game Status"
                         (str "Game starting...\nGameID: " (:gameid state)))
      (set-full-state! state)
      (swap! client-state assoc :gameid (:gameid state)))

    :game/diff
    (let [diff-data (if (string? data)
                      (json/parse-string data true)
                      data)
          {:keys [gameid diff]} diff-data
          client-gameid (:gameid @client-state)]
      ;; FIX: Compare as strings since JSON parses UUIDs as strings
      (when (= (str gameid) (str client-gameid))
        (println "\n🔄 GAME/DIFF received")
        (println "   GameID:" gameid)
        (println "   Diff type:" (type diff))
        (println "   Diff keys (if map):" (when (map? diff) (keys diff)))
        (println "   Diff sample:" (pr-str (if (coll? diff)
                                              (take 5 diff)
                                              diff)))
        (update-game-state! diff)
        ;; Clear lobby-state once game has started (receiving diffs means game is active)
        (swap! client-state dissoc :lobby-state)
        ;; Announce newly revealed cards in Archives
        (announce-revealed-archives diff)
        ;; Auto-update game log HUD
        (write-game-log-to-hud 30)
        (println "   ✓ Diff applied successfully")))

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
          (swap! client-state assoc :gameid gameid :lobby-state data)
          (println "   GameID:" gameid))))

    :lobby/notification
    (println "🔔 Lobby notification:" data)

    :chsk/ws-ping
    ;; Respond to ping with pong to keep connection alive
    ;; Echo back ping data if present (some Sente versions use ping IDs)
    (when-let [socket (:socket @client-state)]
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
              (swap! client-state assoc :csrf-token csrf-token)
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
  "Connect to WebSocket server"
  [url]
  (println "🔌 Connecting to" url "...")
  (try
    ;; Close old connection if it exists to prevent leaks
    (when-let [old-socket (:socket @client-state)]
      (println "🧹 Closing old socket connection...")
      (try
        (ws/close old-socket)
        (catch Exception e
          (println "⚠️  Error closing old socket:" (.getMessage e)))))

    (when-let [old-client (:ws-client @client-state)]
      (println "🧹 Stopping old WebSocket client...")
      (try
        (.stop old-client)
        (catch Exception e
          (println "⚠️  Error stopping old client:" (.getMessage e)))))

    ;; Reuse existing client-id if available, otherwise generate new one
    (let [existing-id (:client-id @client-state)
          client-id (or existing-id (str "ai-client-" (java.util.UUID/randomUUID)))
          csrf-token (:csrf-token @client-state)
          ;; Add client-id as query parameter for Sente handshake
          ;; If we have a CSRF token, also add it to URL
          full-url (if csrf-token
                     (str url "?client-id=" client-id
                          "&csrf-token=" (URLEncoder/encode csrf-token "UTF-8"))
                     (str url "?client-id=" client-id))
          ;; Create custom WebSocket client with longer idle timeout (10 minutes instead of 5)
          ;; This prevents connection drops when there's no activity
          ws-client (if (.startsWith url "wss://")
                      (WebSocketClient. (SslContextFactory.))
                      (WebSocketClient.))
          _ (doto (.getPolicy ws-client)
              ;; Set idle timeout to 10 minutes (600000 ms) to prevent timeouts
              ;; Server sends pings every ~30 seconds, so this gives plenty of buffer
              (.setIdleTimeout 600000))
          _ (.start ws-client)
          ;; Prepare connection options
          conn-opts {:on-receive on-receive
                     :on-connect (fn [& _args]
                                  (println "⏳ WebSocket connected, waiting for handshake..."))
                     :on-close (fn [& args]
                                (println "❌ Disconnected:" (first args))
                                (.stop ws-client)  ; Clean up custom client
                                (swap! client-state assoc :connected false))
                     :on-error (fn [& args]
                                (println "❌ Error:" (first args)))
                     :client ws-client}  ; Pass custom client to gniazdo
          socket (ws/connect full-url conn-opts)]
      (swap! client-state assoc
             :socket socket
             :ws-client ws-client  ; Store ws-client so we can clean it up later
             :client-id client-id)
      (if existing-id
        (println "✨ WebSocket reconnected with existing client-id:" client-id)
        (println "✨ WebSocket connection initiated with new client-id:" client-id))
      socket)
    (catch Exception e
      (println "❌ Connection failed:" (.getMessage e))
      nil)))

(defn disconnect!
  "Disconnect from server"
  []
  (when-let [socket (:socket @client-state)]
    (ws/close socket)
    (println "👋 Socket disconnected"))

  (when-let [ws-client (:ws-client @client-state)]
    (try
      (.stop ws-client)
      (println "👋 WebSocket client stopped")
      (catch Exception e
        (println "⚠️  Error stopping client:" (.getMessage e)))))

  (swap! client-state assoc :socket nil :ws-client nil :connected false))

;; ============================================================================
;; Sending Messages
;; ============================================================================

(defn send-message!
  "Send a message to server"
  [event-type data]
  (if-let [socket (:socket @client-state)]
    (try
      ;; Sente expects double-wrapped messages: [[:event-type data]]
      (let [msg (pr-str [[event-type data]])]
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
      ;; Convert gameid string to UUID object for server
      (let [gameid-uuid (if (string? gameid)
                          (java.util.UUID/fromString gameid)
                          gameid)]
        (send-message! :game/action
                       {:gameid gameid-uuid
                        :command command
                        :args args}))
      (println "❌ No active game"))))

;; ============================================================================
;; Lobby Operations
;; ============================================================================

(defn request-lobby-list!
  "Request the list of available games"
  []
  ;; Use send-message! with nil data (server doesn't need data for lobby list)
  (send-message! :lobby/list nil))

(defn create-lobby!
  "Create a new game lobby
   Options:
     :title - game title (required)
     :side - \"Corp\", \"Runner\", or \"Any Side\" (default: \"Any Side\")
     :format - game format (default: \"system-gateway\" for beginner fixed decks)
     :gateway-type - \"Beginner\" or \"Intermediate\" (default: \"Beginner\")
     :room - \"casual\" or \"competitive\" (default: \"casual\")
     :allow-spectator - allow spectators (default: true)
     :spectatorhands - spectators can see hands (default: false)
     :save-replay - save replay (default: true)
     Other optional keys: :password, :timer, :singleton, :turmoil, etc."
  [{:keys [title side format gateway-type room allow-spectator spectatorhands save-replay]
    :or {side "Any Side"
         format "system-gateway"
         gateway-type "Beginner"
         room "casual"
         allow-spectator true
         spectatorhands true  ; Default true for AI testing/debugging
         save-replay true}
    :as options}]
  (if-not title
    (println "❌ Error: :title is required")
    (let [lobby-options (-> options
                            (assoc :side side
                                   :format format
                                   :gateway-type gateway-type
                                   :room room
                                   :allow-spectator allow-spectator
                                   :spectatorhands spectatorhands
                                   :save-replay save-replay))]
      (send-message! :lobby/create lobby-options)
      (println "🎮 Creating lobby:" title)
      (println "   Format:" format (when (= format "system-gateway") (str "(" gateway-type ")"))))))

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

(defn resync-game!
  "Request full game state resync (for reconnecting to started games)
   Usage: (resync-game! gameid)"
  [gameid]
  (let [uuid-gameid (if (string? gameid)
                      (java.util.UUID/fromString gameid)
                      gameid)]
    (swap! client-state assoc :gameid uuid-gameid)
    (send-message! :game/resync {:gameid uuid-gameid})
    (println "🔄 Requesting game state resync for" uuid-gameid)))

;; ============================================================================
;; High-Level API
;; ============================================================================

(defn get-current-state [] (:game-state @client-state))
(defn get-my-side [] (:side @client-state))
(defn connected? [] (:connected @client-state))
(defn in-game? [] (some? (:gameid @client-state)))
(defn get-lobby-list [] (:lobby-list @client-state))

;; ============================================================================
;; Connection Management
;; ============================================================================

(defn ensure-connected!
  "Check connection and reconnect if needed. Returns true if connected."
  ([] (ensure-connected! "ws://localhost:1042/chsk"))
  ([url]
   (if (connected?)
     true
     (do
       (println "⚠️  Not connected, reconnecting...")
       (connect! url)
       (Thread/sleep 2000)
       (connected?)))))

(defn rejoin-game!
  "Rejoin a game after reconnection using gameid from game state"
  []
  (when-let [gameid (get-in @client-state [:game-state :gameid])]
    (println "♻️  Rejoining game:" gameid)
    (let [gameid-uuid (if (string? gameid)
                        (java.util.UUID/fromString gameid)
                        gameid)]
      (send-message! :lobby/join
                     {:gameid gameid-uuid
                      :request-side "Runner"}))
    (Thread/sleep 2000)
    true))

;; ============================================================================
;; Game State Queries (Delegated to ai-state)
;; ============================================================================

(def get-game-state state/get-game-state)
(def active-player state/active-player)
(def my-turn? state/my-turn?)
(def turn-number state/turn-number)
(def runner-state state/runner-state)
(def corp-state state/corp-state)
(def credits-for-side state/credits-for-side)
(def clicks-for-side state/clicks-for-side)
(def hand-count-for-side state/hand-count-for-side)
(def my-credits state/my-credits)
(def my-clicks state/my-clicks)
(def my-hand state/my-hand)
(def my-hand-count state/my-hand-count)
(def my-installed state/my-installed)
(def runner-credits state/runner-credits)
(def runner-clicks state/runner-clicks)
(def runner-hand-count state/runner-hand-count)
(def corp-credits state/corp-credits)
(def corp-clicks state/corp-clicks)
(def corp-hand-count state/corp-hand-count)
(def get-prompt state/get-prompt)
(def get-turn-status state/get-turn-status)

;; ============================================================================
;; Enhanced Access Prompt Display
;; ============================================================================

(defn- access-prompt?
  "Detect if prompt is an access prompt by checking for 'steal' or 'trash' keywords"
  [prompt]
  (when-let [choices (:choices prompt)]
    (let [choice-values (map :value choices)
          choice-text (clojure.string/lower-case (clojure.string/join " " choice-values))]
      (or (clojure.string/includes? choice-text "steal")
          (clojure.string/includes? choice-text "trash")))))

(defn- extract-card-name
  "Extract card name from access prompt message
   Format: 'You accessed Regolith Mining License'"
  [msg]
  (when msg
    (let [msg-lower (clojure.string/lower-case msg)]
      (when (clojure.string/includes? msg-lower "you accessed")
        ;; Extract everything after "you accessed " until end or period
        (when-let [match (re-find #"(?i)you accessed\s+(.+?)(?:\.|$)" msg)]
          (second match))))))

(defn- show-access-prompt
  "Display access prompt with enhanced card metadata"
  [prompt]
  (let [msg (:msg prompt)
        card-name (extract-card-name msg)
        card-data (when card-name (get @all-cards card-name))
        choices (:choices prompt)
        has-steal? (some #(clojure.string/includes?
                           (clojure.string/lower-case (str (:value %)))
                           "steal")
                        choices)]

    ;; Display header with card metadata
    (if card-data
      (let [card-type (or (:type card-data) "unknown")
            trash-cost (:cost card-data)  ; trash cost is in :cost field
            points (:agendapoints card-data)  ; agenda points
            metadata (cond
                       points (str "[" card-type ", points=" points "]")
                       trash-cost (str "[" card-type ", trash=" trash-cost "]")
                       :else (str "[" card-type "]"))]
        (println (str "\n❓ You accessed: " card-name " " metadata)))
      ;; Fallback if card not found in DB
      (println (str "\n❓ " msg)))

    ;; Show full card text ONLY when "steal" keyword present (nasty effects)
    (when (and has-steal? card-data (:text card-data))
      (println "⚠️  SPECIAL STEAL CONDITION:")
      (println (str "   " (:text card-data))))

    ;; Display choices
    (when choices
      (doseq [[idx choice] (map-indexed vector choices)]
        (println (str "  [" idx "] " (:value choice)))))

    prompt))

(defn show-prompt
  "Display current prompt in readable format.
   Detects access prompts and shows enhanced card metadata."
  []
  (if-let [prompt (get-prompt)]
    (if (access-prompt? prompt)
      ;; Enhanced display for access prompts
      (show-access-prompt prompt)
      ;; Standard display for other prompts
      (do
        (println "\n🔔 PROMPT")
        (println "Message:" (:msg prompt))
        (println "Type:" (:prompt-type prompt))
        (when-let [choices (:choices prompt)]
          (println "Choices:")
          (doseq [[idx choice] (map-indexed vector choices)]
            (println (str "  " idx ". " (:value choice) " [UUID: " (:uuid choice) "]"))))
        prompt))
    (println "No active prompt")))

;; ============================================================================
;; Action Helpers
;; ============================================================================

(defn safe-action!
  "Send action with connection check"
  [command args]
  (when (ensure-connected!)
    (send-action! command args)))

(defn take-credits!
  "Take credits for clicks"
  []
  (safe-action! "credit" nil))

(defn draw-card!
  "Draw a card"
  []
  (safe-action! "draw" nil))

(defn end-turn!
  "End the current turn"
  []
  (safe-action! "end-turn" nil))

(defn run-server!
  "Run on a server"
  [server]
  (safe-action! "run" {:server server}))

(defn choose!
  "Make a choice from a prompt by index or UUID"
  [choice]
  (if (number? choice)
    ;; Choice by index
    (let [prompt (get-prompt)
          uuid (get-in prompt [:choices choice :uuid])]
      (when uuid
        (safe-action! "choice" {:choice {:uuid uuid}})))
    ;; Choice by UUID string
    (safe-action! "choice" {:choice {:uuid choice}})))

(defn play-card!
  "Play a card from hand by index or title"
  [card & args]
  (if (number? card)
    ;; Card by index
    (let [hand (my-hand)
          card-obj (nth hand card nil)]
      (when card-obj
        (safe-action! "play" (into {:card (:cid card-obj)} args))))
    ;; Card by title (find in hand)
    (let [hand (my-hand)
          card-obj (first (filter #(= card (:title %)) hand))]
      (when card-obj
        (safe-action! "play" (into {:card (:cid card-obj)} args))))))

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
  (let [gs (get-game-state)
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

;; ============================================================================
;; Status Display
;; ============================================================================

(defn show-status
  "Display current game status or lobby state"
  []
  (let [lobby (:lobby-state @client-state)
        gs (get-game-state)]
    ;; Check if we're in a lobby but game hasn't started yet
    (if (and lobby (not (:started lobby)))
      ;; Show lobby status
      (let [players (:players lobby)
            player-count (count players)
            players-with-decks (count (filter :deck players))
            sides (set (map :side players))
            ready? (and (= 2 player-count)
                       (every? :deck players)
                       (every? #(get-in % [:deck :identity]) players)
                       (contains? sides "Corp")
                       (contains? sides "Runner"))]
        (println "📊 LOBBY STATUS")
        (println "\nGame:" (:title lobby))
        (println "Format:" (:format lobby))
        (println "Players:" player-count "/2")
        (doseq [player players]
          (let [username (get-in player [:user :username])
                side (:side player)
                has-deck? (some? (:deck player))
                deck-name (get-in player [:deck :name])]
            (println (format "  • %s (%s) - %s"
                           username
                           side
                           (if has-deck?
                             (str "✅ " deck-name)
                             "⏳ No deck selected")))))
        (println "\nStatus:"
               (cond
                 ready? "✅ Ready to start! Use 'start-game' or 'auto-start'"
                 (< player-count 2) (format "⏳ Waiting for players (%d/2)" player-count)
                 (< players-with-decks 2) (format "⏳ Waiting for deck selection (%d/2 ready)" players-with-decks)
                 :else "⏳ Waiting...")))) ;; Extra ) closes the lobby-status let
      ;; Show game status (existing code)
      (let [my-side (:side @client-state)
            game-id (:gameid @client-state)
            active-side (active-player)
            turn-num (turn-number)
            end-turn (get-in gs [:end-turn])
            prompt (get-prompt)
            prompt-type (:prompt-type prompt)
            run-state (get-in gs [:run])
            runner-clicks (get-in gs [:runner :click])
            corp-clicks (get-in gs [:corp :click])
            both-zero-clicks (and (= 0 runner-clicks) (= 0 corp-clicks))
            ;; Calculate who should go next when both have 0 clicks
            next-player (cond
                         (= turn-num 0) "corp"
                         (= active-side "corp") "runner"
                         (= active-side "runner") "corp"
                         :else "unknown")
            ;; Detect if a player has left (game state exists but player data is nil)
            runner-missing? (and gs (nil? (get-in gs [:runner :user])))
            corp-missing? (and gs (nil? (get-in gs [:corp :user])))]

        ;; If a player has left, show recovery message instead of confusing nils
        (if (or runner-missing? corp-missing?)
          (do
            (println "📊 GAME STATUS")
            (println "\n⚠️  PLAYER DISCONNECTED")
            (when runner-missing?
              (println "\n❌ Runner has left the game"))
            (when corp-missing?
              (println "\n❌ Corp has left the game"))
            (println "\n💡 To reconnect:")
            (println "   ./dev/send_command" (clojure.string/lower-case my-side) "join" game-id my-side)
            (println "\nOr use ai-bounce.sh to restart both clients:")
            (println "   ./dev/ai-bounce.sh" game-id))

          ;; Normal game status display
          (do
            (println "📊 GAME STATUS")
            (println "\nTurn:" (turn-number) "-" active-side)

    ;; Active player / waiting status
    (let [runner-clicks runner-clicks
          corp-clicks corp-clicks
          both-zero-clicks both-zero-clicks]
      (cond
        ;; End-turn was called, and it's my side's turn to start (opponent just finished)
        (and end-turn (not= my-side active-side))
        (do
          (println "Status: 🟢 Waiting to start" my-side "turn (use 'start-turn' command)")
          (println "💡 Use 'start-turn' to begin your turn"))

        ;; End-turn was called, waiting for opponent to start (I just finished)
        (and end-turn (= my-side active-side))
        (println "Status: ⏳ Waiting for" (if (= active-side "corp") "runner" "corp") "to start turn")

        ;; Both players have 0 clicks but end-turn not called yet
        both-zero-clicks
        (println "Status: 🟢 Waiting to start" next-player "turn (use 'start-turn' command)")

        ;; Waiting for opponent
        (not= my-side active-side)
        (println "Status: ⏳ Waiting for" active-side "to act")

        ;; Waiting prompt
        (= :waiting prompt-type)
        (println "Status: ⏳" (:msg prompt))

        ;; My turn and active
        :else
        (println "Status: ✅ Your turn to act")))

    ;; Run status
    (when run-state
      (println "\n🏃 ACTIVE RUN:")
      (println "  Server:" (:server run-state))
      (println "  Phase:" (:phase run-state))
      (when-let [pos (:position run-state)]
        (println "  Position:" pos)))

    (println "\n--- RUNNER ---")
    (println "Credits:" (runner-credits))
    (let [clicks runner-clicks]
      (if (and (= "runner" active-side) (zero? clicks) (not end-turn))
        (do
          (println "Clicks:" clicks "(End of Turn)")
          (println "💡 Use 'end-turn' to finish your turn"))
        (println "Clicks:" clicks)))
    (let [hand-count (my-hand-count)
          max-hand-size (get-in gs [:runner :hand-size-modification] 5)]
      (println "Hand:" hand-count "cards")
      (when (and (= "runner" my-side) (> hand-count max-hand-size))
        (println "⚠️  Over hand size! Discard to" max-hand-size "at end of turn")))
    (let [agenda-points (get-in gs [:runner :agenda-point] 0)
          ;; Calculate agenda tracking for Runner only
          corp-scored (get-in gs [:corp :agenda-point] 0)
          runner-stolen agenda-points
          ;; Determine total agenda points from deck size
          hq-size (get-in gs [:corp :hand-count] 0)
          rd-size (get-in gs [:corp :deck-count] 0)
          discard-size (count (get-in gs [:corp :discard] []))
          initial-deck-size (+ rd-size hq-size discard-size
                             (* corp-scored 1))
          total-agendas (cond
                         (<= initial-deck-size 44) 18
                         (<= initial-deck-size 49) 20
                         (<= initial-deck-size 54) 22
                         :else (+ 22 (* 2 (quot (- initial-deck-size 50) 5))))
          accounted (+ corp-scored runner-stolen)
          missing (- total-agendas accounted)
          ;; Calculate expected agendas drawn
          turn-num (or (turn-number) 0)
          ;; Conservative estimate: mandatory draws only (1 per turn after turn 0)
          cards-drawn (max 0 turn-num)
          agenda-density (if (pos? initial-deck-size)
                          (/ (float total-agendas) initial-deck-size)
                          0)
          expected-drawn (int (* cards-drawn agenda-density))
          ;; Count remote servers - only unrezzed cards in roots (exclude ice, rezzed assets/upgrades)
          servers (get-in gs [:corp :servers] {})
          remotes (filter #(and (string? (key %))
                              (re-matches #"remote\d+" (key %)))
                        servers)
          ;; Filter to unrezzed cards only in remote roots
          unrezzed-remotes (filter (fn [[_ server]]
                                    (let [content (get-in server [:content])]
                                      (some #(not (:rezzed %)) content)))
                                  remotes)
          unrezzed-count (count unrezzed-remotes)
          ;; Count cards with advancement counters (among unrezzed cards)
          advanced-count (count (filter (fn [[_ server]]
                                         (let [content (get-in server [:content])]
                                           (some #(and (not (:rezzed %))
                                                      (pos? (get-in % [:advance-counter] 0)))
                                                content)))
                                       remotes))]
      (if (= "runner" my-side)
        (println (format "Agenda Points: %d / 7  │  Missing: %d (Drawn: ~%d, HQ: %d, R&D: %d, Remotes: %d/%d)"
                        agenda-points missing expected-drawn hq-size rd-size unrezzed-count advanced-count))
        (println "Agenda Points:" agenda-points "/ 7")))
    (println "\n--- CORP ---")
    (println "Credits:" (corp-credits))
    (let [clicks corp-clicks]
      (if (and (= "corp" active-side) (zero? clicks) (not end-turn))
        (do
          (println "Clicks:" clicks "(End of Turn)")
          (println "💡 Use 'end-turn' to finish your turn"))
        (println "Clicks:" clicks)))
    (let [hand-count (corp-hand-count)
          max-hand-size (get-in gs [:corp :hand-size-modification] 5)]
      (println "Hand:" hand-count "cards")
      (when (and (= "corp" my-side) (> hand-count max-hand-size))
        (println "⚠️  Over hand size! Discard to" max-hand-size "at end of turn")))
    (let [agenda-points (get-in gs [:corp :agenda-point] 0)]
      (println "Agenda Points:" agenda-points "/ 7"))
    (when (and prompt (not= :waiting prompt-type))
      (println "\n🔔 Active Prompt:" (:msg prompt)))

    ;; Show recent log entries
    (when-let [log (get-in gs [:log])]
      (let [recent-log (take-last 3 log)]
        (when (seq recent-log)
          (println "\n--- RECENT LOG ---")
          (doseq [entry recent-log]
            (println " " (:text entry))))))

        nil)))))  ;; Closes: normal-status do, if, outer let, defn

(defn list-active-game-ids
  "Return list of active game IDs (parseable format for scripts)
   Returns vector of game ID strings, or empty vector if none"
  []
  (if-let [games (get-lobby-list)]
    (vec (map :gameid games))
    []))

(defn show-games
  "Display available games in a readable format"
  []
  (if-let [games (get-lobby-list)]
    (do
      (println "\n📋 Available Games:")
      (doseq [game games]
        (println "\n🎮" (:title game))
        (println "   ID:" (:gameid game))
        (println "   Players:" (count (:players game)) "/" (:max-players game 2))
        (when-let [players (:players game)]
          (doseq [player players]
            (println "     -" (:side player) ":" (get-in player [:user :username] "Waiting..."))))
        (when (:started game)
          (println "   ⚠️  Game already started")))
      (println "\nTo join a game, use: (join-game! {:gameid \"...\" :side \"Corp\"})")
      (println))
    (do
      (println "No games available. Request lobby list with: (request-lobby-list!)")
      nil)))

(defn get-game-log
  "Get the game log from current game state"
  []
  (get-in @client-state [:game-state :log]))

(defn show-game-log
  "Display game log in readable format"
  ([] (show-game-log 20))
  ([n]
   (if-let [log (get-game-log)]
     (do
       (println "\n📜 GAME LOG (last" n "entries)")
       (doseq [entry (take-last n log)]
         (when (map? entry)
           (let [text (str/replace (:text entry "") "[hr]" "")
                 user (:user entry)
                 timestamp (:timestamp entry)]
             (println (str "  " text)))))
       nil)
     (println "No game log available"))))

(defn show-status-compact
  "Display ultra-compact game status (1-2 lines, no decorations)"
  []
  (let [lobby (:lobby-state @client-state)
        gs (get-game-state)]
    (if (and lobby (not (:started lobby)))
      ;; Lobby compact status
      (let [players (:players lobby)
            player-count (count players)
            ready? (and (= 2 player-count) (every? :deck players))]
        (println (format "Lobby: %d/2 players%s"
                        player-count
                        (if ready? " [READY]" ""))))
      ;; Game compact status
      (let [my-side (:side @client-state)
            active-side (active-player)
            turn (turn-number)
            prompt (get-prompt)
            run-state (get-in gs [:run])

            ;; Runner state
            runner-credits (get-in gs [:runner :credit] 0)
            runner-clicks (get-in gs [:runner :click] 0)
            runner-hand (get-in gs [:runner :hand] [])
            runner-ap (get-in gs [:runner :agenda-point] 0)

            ;; Corp state
            corp-credits (get-in gs [:corp :credit] 0)
            corp-clicks (get-in gs [:corp :click] 0)
            corp-hand (get-in gs [:corp :hand] [])
            corp-ap (get-in gs [:corp :agenda-point] 0)

            ;; Compact format: T3-Corp | Me(R): 4c/2cl/5h/0AP | Opp(C): 5c/0cl/4h/0AP
            my-stats (if (= my-side "runner")
                      (format "%dc/%dcl/%dh/%dAP" runner-credits runner-clicks (count runner-hand) runner-ap)
                      (format "%dc/%dcl/%dh/%dAP" corp-credits corp-clicks (count corp-hand) corp-ap))
            opp-stats (if (= my-side "runner")
                       (format "%dc/%dcl/%dh/%dAP" corp-credits corp-clicks (count corp-hand) corp-ap)
                       (format "%dc/%dcl/%dh/%dAP" runner-credits runner-clicks (count runner-hand) runner-ap))
            my-label (if (= my-side "runner") "R" "C")
            opp-label (if (= my-side "runner") "C" "R")

            prompt-str (cond
                        run-state (format "Run:%s" (:server run-state))
                        prompt (let [msg (:msg prompt)]
                                (if (> (count msg) 30)
                                  (str (subs msg 0 27) "...")
                                  msg))
                        :else "-")]

        (println (format "T%d-%s | Me(%s):%s | Opp(%s):%s | %s"
                        turn
                        active-side
                        my-label
                        my-stats
                        opp-label
                        opp-stats
                        prompt-str))
        nil))))

(defn announce-revealed-archives
  "Announce newly revealed cards in Archives from diff"
  [diff]
  (when (vector? diff)
    (doseq [[old-data new-data] (partition 2 diff)]
      (when (and (map? new-data) (:corp new-data))
        (let [discard-changes (get-in new-data [:corp :discard])]
          (when (vector? discard-changes)
            (doseq [[idx card-data] (partition 2 discard-changes)]
              (when (and (map? card-data)
                        (:new card-data)
                        (:seen card-data)
                        (:title card-data))
                (let [cost-str (if-let [cost (:cost card-data)]
                                (str cost "¢")
                                "")
                      type-str (:type card-data)
                      subtitle (if (not-empty cost-str)
                                (str type-str ", " cost-str)
                                type-str)]
                  (println (str "\n📂 Revealed in Archives: "
                              (:title card-data)
                              " (" subtitle ")")))))))))))

(defn write-game-log-to-hud
  "Write game log to CLAUDE.local.md for HUD visibility (multi-client safe)"
  ([] (write-game-log-to-hud 30))
  ([n]
   (if-let [log (get-game-log)]
     (let [log-entries (take-last n log)
           log-text (str/join "\n"
                              (for [entry log-entries]
                                (when (map? entry)
                                  (str "- " (str/replace (:text entry "") "[hr]" "")))))]
       (update-hud-section "Game Log"
                          (str "Last " n " entries:\n\n"
                               log-text
                               "\n\n---\n"
                               "Updated: " (java.time.Instant/now)))
       (println "✅ Game log written to CLAUDE.local.md"))
     (println "No game log available"))))

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
