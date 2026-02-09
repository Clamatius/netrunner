(ns ai-connection
  "Lobby management, game connection, and dev/testing commands"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-display :as display]
            [ai-core :as core]))

;; ============================================================================
;; Lobby Operations (Implementations)
;; ============================================================================

(defn request-lobby-list!
  "Request the list of available games"
  []
  (ws/send-message! :lobby/list nil))

(defn create-lobby!
  "Create a new game lobby
   Usage: (create-lobby! \"My Test Game\")
          (create-lobby! {:title \"My Game\" :side \"Corp\" :format \"startup\"})
   Options:
     :title - game title (required)
     :side - \"Corp\", \"Runner\", or \"Any Side\" (default: \"Any Side\")
     :format - game format (default: \"system-gateway\" for beginner fixed decks)
     :gateway-type - \"Beginner\" or \"Intermediate\" (default: \"Intermediate\")
     :room - \"casual\" or \"competitive\" (default: \"casual\")
     :allow-spectator - allow spectators (default: true)
     :spectatorhands - spectators can see hands (default: true for AI testing)
     :save-replay - save replay (default: true)"
  ([title-or-options]
   (let [options (if (map? title-or-options)
                  title-or-options
                  {:title title-or-options :side "Any Side"})
         {:keys [title side format gateway-type room allow-spectator spectatorhands save-replay]
          :or {side "Any Side"
               format "system-gateway"
               gateway-type "Intermediate"
               room "casual"
               allow-spectator true
               spectatorhands true
               save-replay true}} options]
     (if-not title
       (println "❌ Error: :title is required")
       (let [lobby-options (assoc options
                                  :side side
                                  :format format
                                  :gateway-type gateway-type
                                  :room room
                                  :allow-spectator allow-spectator
                                  :spectatorhands spectatorhands
                                  :save-replay save-replay)]
         (ws/send-message! :lobby/create lobby-options)
         (println "🎮 Creating lobby:" title)
         (println "   Format:" format (when (= format "system-gateway") (str "(" gateway-type ")")))
         (Thread/sleep core/standard-delay))))))

(defn join-game!
  "Join a game by ID
   Options:
     :gameid - game ID to join
     :side - \"Corp\" or \"Runner\" or \"Any Side\"
     :password - optional password"
  [{:keys [gameid side password]}]
  (let [request-side (or side "Any Side")]
    (ws/send-message! :lobby/join
                     (cond-> {:gameid gameid
                              :request-side request-side}
                       password (assoc :password password)))
    (println "🎮 Attempting to join game" gameid "as" request-side)))

(defn watch-game!
  "Join a game as a spectator
   Options:
     :gameid      - game ID to watch
     :perspective - \"Corp\", \"Runner\", or nil for neutral view
     :password    - optional password if game is password-protected

   Spectators receive game state updates but cannot take actions.
   With a perspective, you see that side's hidden information."
  [{:keys [gameid perspective password]}]
  (let [uuid-gameid (state/normalize-gameid gameid)]
    (ws/send-message! :lobby/watch
                      (cond-> {:gameid uuid-gameid}
                        perspective (assoc :request-side perspective)
                        password (assoc :password password)))
    (swap! state/client-state assoc
           :gameid uuid-gameid
           :spectator true
           :spectator-perspective perspective)
    (println "👁️  Spectating game" uuid-gameid
             (if perspective (str "(" perspective " perspective)") "(neutral view)"))))

(defn resync-game!
  "Request full game state resync (for reconnecting to started games).
   Clears cached state first to prevent stale data issues.
   Usage: (resync-game! gameid)"
  [gameid]
  (let [uuid-gameid (state/normalize-gameid gameid)]
    ;; Clear stale state before resync to prevent diff application issues
    (state/clear-game-state!)
    (swap! state/client-state assoc :gameid uuid-gameid)
    (ws/send-message! :game/resync {:gameid uuid-gameid})
    (println "🔄 Requesting game state resync for" uuid-gameid)))

(defn list-lobbies
  "Request and display the list of available games"
  []
  (println "Requesting lobby list...")
  (request-lobby-list!)
  (Thread/sleep core/short-delay)
  (display/show-games))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- wait-for-condition
  "Wait up to timeout-ms for predicate to return true"
  [pred timeout-ms label]
  (print (str "⏳ Waiting for " label "..."))
  (flush)
  (loop [waited 0]
    (if (pred)
      (do (println " Done!") true)
      (if (>= waited timeout-ms)
        (do (println (str "\n❌ Timed out waiting for: " label))
            false)
        (do (print ".")
            (flush)
            (Thread/sleep core/polling-delay)
            (recur (+ waited 200)))))))

(defn- in-lobby-players?
  "Check if our username appears in the lobby-state's players list"
  []
  (let [lobby-state (:lobby-state @state/client-state)
        my-username (:username @state/client-state)
        players (:players lobby-state)]
    (when (and my-username players)
      (some #(= my-username (get-in % [:user :username])) players))))

(defn- lobby-state-has-gameid?
  "Check if we have a lobby-state with matching gameid (for started games)"
  []
  (let [lobby-state (:lobby-state @state/client-state)
        expected-gameid (:gameid @state/client-state)
        lobby-gameid (:gameid lobby-state)]
    (and expected-gameid lobby-gameid
         (= (str expected-gameid) (str lobby-gameid)))))

(defn- wait-for-in-lobby
  "Wait until we're confirmed in the lobby (players list or gameid match).
   For pre-start games, checks players list. For started games, checks gameid match.
   Returns true if we're in the lobby, false on timeout."
  [timeout-ms]
  (wait-for-condition
    #(or (in-lobby-players?) (lobby-state-has-gameid?))
    timeout-ms
    "lobby confirmation"))

;; ============================================================================
;; Game Connection (Higher-Level)
;; ============================================================================

(defn in-game?
  "Check if currently in a game (has gameid)"
  []
  (some? (:gameid @state/client-state)))

(defn has-game-state?
  "Check if we have game state data (not just gameid)"
  []
  (some? (state/get-game-state)))

(defn connect-game!
  "Join a game by ID (with wait and status display)
   Usage: (connect-game! \"game-uuid\" \"Corp\")
          (connect-game! \"game-uuid\") ; defaults to Corp"
  ([gameid] (connect-game! gameid "Corp"))
  ([gameid side]
   (let [uuid (state/normalize-gameid gameid)]
     (join-game! {:gameid uuid :side side}))

   (if (wait-for-condition in-game? 5000 "game join")
     (display/show-status)
     (println "❌ Failed to join game"))))

(defn- detect-side-from-username
  "Detect side from username pattern.
   ai-runner -> Runner, ai-corp -> Corp
   Returns capitalized for server communication (server case-matches on these)."
  []
  (let [username (:username @state/client-state)]
    (cond
      (and username (clojure.string/includes? username "runner")) "Runner"
      (and username (clojure.string/includes? username "corp")) "Corp"
      :else nil)))

(defn reconnect-game!
  "Rejoin an already-started game by requesting full state resync (with wait)
   Usage: (reconnect-game! \"game-uuid\")"
  [gameid]
  (let [uuid-gameid (state/normalize-gameid gameid)
        ;; Try to determine side from: 1) existing state, 2) username, 3) default to Any
        my-side (or (:side @state/client-state)
                    (detect-side-from-username)
                    "Any Side")]
    (println "🔄 Reconnecting as" my-side)
    ;; Set gameid first so server knows which game we're asking about
    (swap! state/client-state assoc :gameid uuid-gameid :side my-side)
    ;; Try to join the game (this registers us as a player again)
    (join-game! {:gameid uuid-gameid :side my-side})
    ;; Give server time to process join
    (Thread/sleep 1000)
    ;; Request full state resync
    (resync-game! gameid)
    ;; Wait for game-state to arrive (longer timeout for network latency)
    (if (wait-for-condition has-game-state? 8000 "state resync")
      (display/show-status)
      (do
        (println "❌ Failed to resync game state")
        (println "💡 Try: ./dev/send_command <side> status")))))

(defn lobby-ready-to-start?
  "Check if the current lobby is ready to start a game.
   Validates: 2 players, both have decks with identities, one Corp + one Runner."
  []
  (let [client @state/client-state
        lobby (:lobby-state client)]
    (and lobby
         (= 2 (count (:players lobby)))
         (not (:started lobby))
         (every? :side (:players lobby))
         (every? :deck (:players lobby))
         (every? #(get-in % [:deck :identity]) (:players lobby))
         (let [sides (set (map :side (:players lobby)))]
           (and (contains? sides "Corp")
                (contains? sides "Runner"))))))

(defn auto-start-if-ready!
  "Check if lobby is ready and auto-start the game if safe.
   Returns :started if game was started, :not-ready if not ready, :already-started if started."
  []
  (let [client @state/client-state
        lobby (:lobby-state client)
        gameid (:gameid client)]
    (cond
      (not lobby)
      (do (println "❌ Not in a lobby")
          {:status :no-lobby})

      (:started lobby)
      (do (println "ℹ️  Game already started")
          {:status :already-started})

      (not (lobby-ready-to-start?))
      (let [players (count (:players lobby))
            with-decks (count (filter :deck (:players lobby)))]
        (println (format "⏳ Lobby not ready: %d/2 players, %d with decks" players with-decks))
        {:status :not-ready :players players :with-decks with-decks})

      :else
      (do
        (println "✅ Lobby ready! Auto-starting game...")
        (ws/send-message! :game/start {:gameid gameid})
        (Thread/sleep core/standard-delay)
        {:status :started}))))

(defn send-chat!
  "Send a chat message to the game
   Usage: (send-chat! \"Hello, world!\")"
  [message]
  (let [gs (state/get-game-state)
        gameid (:gameid gs)]
    (if gameid
      (ws/send-message! :game/say
        {:gameid (state/normalize-gameid gameid)
         :msg message})
      (println "❌ Not in a game"))))

(defn send-ping!
  "Send a 'ping' signal to wake up opponent's wait-for-relevant-diff.
   Use this for AI coordination when no game state change occurs.
   Usage: (send-ping!)"
  []
  (send-chat! "ping"))

;; ============================================================================
;; Dev/Testing Commands
;; ============================================================================

(defn change!
  "Dev command to modify game state - changes key by delta
   Example: (change! :credit 10) to add 10 credits
   Example: (change! :click 2) to add 2 clicks

   WARNING: This is a testing backdoor and will appear in game log!"
  [key delta]
  (let [client @state/client-state
        gameid (:gameid client)]
    (ws/send-message! :game/action
                      {:gameid gameid
                       :command "change"
                       :args {:key key :delta delta}})
    (Thread/sleep core/quick-delay)))

;; ============================================================================
;; Staleness Detection & Auto-Recovery
;; ============================================================================

(defn find-our-game
  "Look through lobby list to find a game where we're a player.
   Returns gameid if found, nil otherwise.
   Useful for auto-recovery when we've been marked as 'left' but can rejoin."
  []
  (let [lobby-list (:lobby-list @state/client-state)
        ;; Get username from client-id (server transforms 'ai-client-xxx' to 'AI-xxx')
        ;; or fall back to stored username if using session auth
        client-id (:client-id @state/client-state)
        my-name (or (:username @state/client-state)
                    (when client-id
                      (clojure.string/replace client-id "ai-client-" "AI-")))]
    (when (and lobby-list my-name)
      (->> lobby-list
           (filter (fn [game]
                    ;; Player structure is {:user {:username "AI-xxx" ...} :side "Corp" ...}
                    (some #(= my-name (get-in % [:user :username]))
                          (:players game))))
           first
           :gameid))))

(defn verify-in-game!
  "Verify we're actually in the game by checking lobby list.
   Returns true if we're in the game, false if we've been kicked.
   Refreshes lobby list as a side effect."
  []
  (let [my-gameid (:gameid @state/client-state)]
    (when my-gameid
      ;; Request fresh lobby list
      (request-lobby-list!)
      (Thread/sleep 500)
      (let [found-gameid (find-our-game)]
        (= (str my-gameid) (str found-gameid))))))

(defn- do-rejoin-resync!
  "Internal: Perform the rejoin and resync sequence"
  []
  ;; Request lobby list to find our game
  (request-lobby-list!)
  (Thread/sleep 500)

  (let [;; First check if we're still in game (maybe different game)
        my-gameid (:gameid @state/client-state)
        ;; Then look for a game we could be in
        found-gameid (find-our-game)
        ;; Use the game we found, or our current gameid as fallback
        target-gameid (or found-gameid my-gameid)]
    (if target-gameid
      (let [my-side (or (:side @state/client-state) "Runner")]
        (println "   Rejoining game:" target-gameid)
        ;; Rejoin the game
        (join-game! {:gameid target-gameid :side my-side})
        ;; Wait for server to confirm we're in the lobby (event-driven, not time-based)
        ;; This prevents a race condition where resync arrives before join is processed
        (if (wait-for-in-lobby 5000)
          (do
            ;; Now safe to request full state resync
            (resync-game! target-gameid)
            (Thread/sleep 1000)
            ;; Clear staleness flags
            (state/clear-stale-flag!)
            (println "✅ Resynced successfully")
            true)
          (do
            (println "❌ Join succeeded but not confirmed in lobby - resync skipped")
            false)))
      (do
        (println "❌ Could not find any game to rejoin")
        (println "   Try: list-lobbies + join + resync manually")
        false))))

(defn ensure-synced!
  "Check for staleness and auto-resync if detected.
   Returns true if we're synced (or successfully resynced), false if can't recover.

   Checks multiple staleness indicators:
   1. diff-mismatch flag (set when diffs for wrong game arrive)
   2. Kicked from game (we have gameid but aren't in lobby list)

   Performs these recovery steps when stale:
   1. Request lobby list to find our game
   2. Rejoin the game
   3. Request full state resync
   4. Clear staleness flags"
  []
  (cond
    ;; Check basic staleness indicators first (cheap check)
    (state/stale?)
    (do
      (println "⚠️  Stale state detected (diff mismatch) - auto-resyncing...")
      (do-rejoin-resync!))

    ;; If we think we're in a game, verify we actually are
    ;; This catches "kicked from game" scenarios
    (:gameid @state/client-state)
    (if (verify-in-game!)
      true
      (do
        (println "⚠️  Kicked from game detected - auto-resyncing...")
        (do-rejoin-resync!)))

    ;; No game state at all - nothing to sync
    :else true))
