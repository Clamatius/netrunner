(ns ai-connection
  "Lobby management, game connection, and dev/testing commands"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-core :as core]))

;; ============================================================================
;; Lobby Management
;; ============================================================================

(defn create-lobby!
  "Create a new game lobby
   Usage: (create-lobby! \"My Test Game\")
          (create-lobby! {:title \"My Game\" :side \"Corp\" :format \"startup\"})"
  ([title-or-options]
   (let [options (if (map? title-or-options)
                  title-or-options
                  {:title title-or-options :side "Any Side"})]
     (ws/create-lobby! options)
     (Thread/sleep core/standard-delay))))

(defn list-lobbies
  "Request and display the list of available games"
  []
  (println "Requesting lobby list...")
  (ws/request-lobby-list!)
  (Thread/sleep core/short-delay)
  (ws/show-games))

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

;; ============================================================================
;; Game Connection
;; ============================================================================

(defn connect-game!
  "Join a game by ID
   Usage: (connect-game! \"game-uuid\" \"Corp\")
          (connect-game! \"game-uuid\") ; defaults to Corp"
  ([gameid] (connect-game! gameid "Corp"))
  ([gameid side]
   ;; Convert string UUID to java.util.UUID if needed
   (let [uuid (if (string? gameid)
                (java.util.UUID/fromString gameid)
                gameid)]
     (ws/join-game! {:gameid uuid :side side}))
   
   (if (wait-for-condition ws/in-game? 5000 "game join")
     (ws/show-status)
     (println "❌ Failed to join game"))))

(defn resync-game!
  "Rejoin an already-started game by requesting full state resync
   Usage: (resync-game! \"game-uuid\")"
  [gameid]
  (ws/resync-game! gameid)
  (if (wait-for-condition ws/in-game? 5000 "state resync")
     (ws/show-status)
     (println "❌ Failed to resync game state")))

(defn lobby-ready-to-start?
  "Check if the current lobby is ready to start a game.
   Validates: 2 players, both have decks with identities, one Corp + one Runner."
  []
  (let [state @ws/client-state
        lobby (:lobby-state state)]
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
  (let [state @ws/client-state
        lobby (:lobby-state state)
        gameid (:gameid state)]
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
  (let [state (ws/get-game-state)
        gameid (:gameid state)]
    (if gameid
      (ws/send-message! :game/say
        {:gameid (if (string? gameid)
                   (java.util.UUID/fromString gameid)
                   gameid)
         :msg message})
      (println "❌ Not in a game"))))

;; ============================================================================
;; Dev/Testing Commands
;; ============================================================================

(defn change!
  "Dev command to modify game state - changes key by delta
   Example: (change! :credit 10) to add 10 credits
   Example: (change! :click 2) to add 2 clicks

   WARNING: This is a testing backdoor and will appear in game log!"
  [key delta]
  (let [state @ws/client-state
        gameid (:gameid state)]
    (ws/send-message! :game/action
                      {:gameid (if (string? gameid)
                                (java.util.UUID/fromString gameid)
                                gameid)
                       :command "change"
                       :args {:key key :delta delta}})
    (Thread/sleep core/quick-delay)))
