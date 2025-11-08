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
         :lobby-list nil
         :client-id nil}))

(defn apply-diff
  "Apply a diff to current state to get new state using differ library"
  [old-state diff]
  (if old-state
    ;; Use differ/patch which properly handles sparse array updates
    ;; like hand: [0 {:playable true} 1 {:playable true} ...]
    (differ/patch old-state diff)
    diff))

(defn update-game-state!
  "Update game state from a diff (or array of diffs)"
  [diff]
  (try
    (let [old-state (:last-state @client-state)
          ;; Normalize to always be a sequence of diffs
          ;; Check the structure carefully
          diffs (cond
                  ;; Empty vector - nothing to do
                  (and (vector? diff) (empty? diff))
                  []

                  ;; Vector of maps - use as-is
                  (and (vector? diff)
                       (> (count diff) 0)
                       (every? #(or (map? %) (nil? %)) diff))
                  diff

                  ;; Single map - wrap it
                  (map? diff)
                  [diff]

                  ;; Nil - skip
                  (nil? diff)
                  []

                  ;; Unknown - log and wrap
                  :else
                  (do
                    (println "⚠️  Unknown diff type:" (type diff))
                    (println "   Value:" (pr-str (take 100 (pr-str diff))))
                    [diff]))
          ;; Filter out nil and empty diffs, then apply
          filtered-diffs (filter #(and (some? %) (not (and (map? %) (empty? %)))) diffs)
          new-state (reduce apply-diff old-state filtered-diffs)]
      (swap! client-state assoc
             :game-state new-state
             :last-state new-state))
    (catch Exception e
      (println "❌ Error in update-game-state!:" (.getMessage e))
      (println "   Diff type:" (type diff))
      (println "   Diff:" (pr-str (take 200 (pr-str diff))))
      (.printStackTrace e))))

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

    :lobby/notification
    (println "🔔 Lobby notification:" data)

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
    ;; Reuse existing client-id if available, otherwise generate new one
    (let [existing-id (:client-id @client-state)
          client-id (or existing-id (str "ai-client-" (java.util.UUID/randomUUID)))
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
      (swap! client-state assoc
             :socket socket
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
      ;; Sente expects double-wrapped messages: [[:event-type data]]
      (let [msg (pr-str [[event-type data]])]
        (ws/send-msg socket msg)
        (println "📤 Sent:" event-type)
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
         spectatorhands false
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
;; Game State Queries
;; ============================================================================

(defn get-game-state [] (:game-state @client-state))

(defn active-player [] (get-in @client-state [:game-state :active-player]))
(defn my-turn? [] (= "runner" (active-player)))
(defn turn-number [] (get-in @client-state [:game-state :turn]))

(defn runner-state [] (get-in @client-state [:game-state :runner]))
(defn corp-state [] (get-in @client-state [:game-state :corp]))

(defn my-credits [] (get-in @client-state [:game-state :runner :credit]))
(defn my-clicks [] (get-in @client-state [:game-state :runner :click]))
(defn my-hand [] (get-in @client-state [:game-state :runner :hand]))
(defn my-hand-count [] (get-in @client-state [:game-state :runner :hand-count]))
(defn my-installed [] (get-in @client-state [:game-state :runner :rig]))

(defn corp-credits [] (get-in @client-state [:game-state :corp :credit]))
(defn corp-clicks [] (get-in @client-state [:game-state :corp :click]))
(defn corp-hand-count [] (get-in @client-state [:game-state :corp :hand-count]))

(defn get-prompt
  "Get current Runner prompt, if any"
  []
  (get-in @client-state [:game-state :runner :prompt-state]))

(defn show-prompt
  "Display current prompt in readable format"
  []
  (if-let [prompt (get-prompt)]
    (do
      (println "\n🔔 PROMPT")
      (println "Message:" (:msg prompt))
      (println "Type:" (:prompt-type prompt))
      (when-let [choices (:choices prompt)]
        (println "Choices:")
        (doseq [[idx choice] (map-indexed vector choices)]
          (println (str "  " idx ". " (:value choice) " [UUID: " (:uuid choice) "]"))))
      prompt)
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
        (loop [discarded 0]
          (if (< discarded cards-to-discard)
            (let [current-hand (get-in (get-game-state) [side :hand])
                  card-to-discard (first current-hand)]
              (when card-to-discard
                (println (format "Discarding card %d/%d: %s"
                                (inc discarded) cards-to-discard (:title card-to-discard)))
                (select-card! card-to-discard (:eid prompt))
                (Thread/sleep 1000)
                (recur (inc discarded))))
            discarded))
        cards-to-discard)
      0)))

;; ============================================================================
;; Status Display
;; ============================================================================

(defn show-status
  "Display current game status"
  []
  (println "\n" (str/join "" (repeat 70 "=")))
  (println "📊 GAME STATUS")
  (println (str/join "" (repeat 70 "=")))
  (println "\nTurn:" (turn-number) "-" (active-player))
  (println "My turn?" (my-turn?))
  (println "\n--- RUNNER ---")
  (println "Credits:" (my-credits))
  (println "Clicks:" (my-clicks))
  (println "Hand:" (my-hand-count) "cards")
  (println "\n--- CORP ---")
  (println "Credits:" (corp-credits))
  (println "Clicks:" (corp-clicks))
  (println "Hand:" (corp-hand-count) "cards")
  (when-let [prompt (get-prompt)]
    (println "\n🔔 Active Prompt:" (:msg prompt)))
  (println (str/join "" (repeat 70 "=")))
  nil)

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
