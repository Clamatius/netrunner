(ns ai-display
  "Display functions for game status, board state, and HUD file management"
  (:require [ai-state :as state]
            [ai-hud-utils :as hud]
            [ai-core :as core]
            [ai-basic-actions :as actions]
            [clojure.string :as str]
            [jinteki.cards :refer [all-cards]]))

;; ============================================================================
;; HUD File Management (Delegated to ai-hud-utils)
;; ============================================================================

(def update-hud-section hud/update-hud-section)

;; ============================================================================
;; Status & Information
;; ============================================================================

(defn- format-counters
  "Format counters on a card for display. Returns string like '[3adv][2virus]' or empty string if no counters."
  [card]
  (let [adv (:advance-counter card 0)
        counters (:counter card {})
        parts (cond-> []
                (pos? adv) (conj (str adv "adv"))
                (:power counters) (conj (str (:power counters) "power"))
                (:virus counters) (conj (str (:virus counters) "virus"))
                (:credit counters) (conj (str (:credit counters) "credits")))]
    (if (seq parts)
      (str " [" (clojure.string/join "][" parts) "]")
      "")))

(defn show-status
  "Display current game status or lobby state"
  []
  (let [lobby (:lobby-state @state/client-state)
        gs (state/get-game-state)]
    ;; Not in a game or lobby
    (if (and (nil? lobby) (nil? gs))
      (do
        (println "📊 STATUS")
        (println "\n⚠️  Not in a game")
        (println "\n💡 To start a new game:")
        (println "   ./dev/reset.sh")
        (println "\n   Or join an existing game:")
        (println "   ./dev/send_command <side> list-lobbies")
        (println "   ./dev/send_command <side> join <game-id> <Side>"))
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
                 :else "⏳ Waiting...")))
      ;; Show game status
      (let [my-side (:side @state/client-state)
            game-id (:gameid @state/client-state)
            active-side (state/active-player)
            turn-num (state/turn-number)
            end-turn (get-in gs [:end-turn])
            prompt (state/get-prompt)
            prompt-type (:prompt-type prompt)
            run-state (get-in gs [:run])
            runner-clicks (get-in gs [:runner :click])
            corp-clicks (get-in gs [:corp :click])
            both-zero-clicks (and (= 0 runner-clicks) (= 0 corp-clicks))
            next-player (cond
                         (= turn-num 0) "corp"
                         (= active-side "corp") "runner"
                         (= active-side "runner") "corp"
                         :else "unknown")
            runner-missing? (and gs (nil? (get-in gs [:runner :user])))
            corp-missing? (and gs (nil? (get-in gs [:corp :user])))]

        ;; If a player has left, show recovery message
        (if (or runner-missing? corp-missing?)
          (do
            (println "📊 GAME STATUS")
            (println "\n⚠️  PLAYER DISCONNECTED")
            (when runner-missing?
              (println "\n❌ Runner has left the game"))
            (when corp-missing?
              (println "\n❌ Corp has left the game"))
            (when my-side
              (println "\n💡 To reconnect:")
              (println "   ./dev/send_command" (str/lower-case my-side) "join" game-id my-side))
            (println "\nOr use ai-bounce.sh to restart both clients:")
            (println "   ./dev/ai-bounce.sh" game-id))

          ;; Normal game status display
          (do
            (println "📊 GAME STATUS")
            (println "\nTurn:" turn-num "-" active-side)

            ;; Active player / waiting status
            (cond
              ;; End-turn was called, and it's my side's turn to start
              (and end-turn (not= my-side active-side))
              (do
                (println "Status: 🟢 Waiting to start" my-side "turn (use 'start-turn' command)")
                (println "💡 Use 'start-turn' to begin your turn"))

              ;; End-turn was called, waiting for opponent to start
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
              (println "Status: ✅ Your turn to act"))

            ;; Run status
            (when run-state
              (println "\n🏃 ACTIVE RUN:")
              (println "  Server:" (:server run-state))
              (println "  Phase:" (:phase run-state))
              (when-let [pos (:position run-state)]
                (println "  Position:" pos))
              ;; Show ICE info during encounter-ice
              (when (= "encounter-ice" (:phase run-state))
                (let [server (:server run-state)
                      position (:position run-state)
                      ice-list (get-in gs [:corp :servers (keyword (last server)) :ices])
                      ice-count (count ice-list)
                      ice-index (when (and position (pos? position)) (- ice-count position))
                      current-ice (when (and ice-index (>= ice-index 0) (< ice-index ice-count))
                                    (nth ice-list ice-index nil))]
                  (when (and current-ice (:rezzed current-ice))
                    (let [ice-title (:title current-ice)
                          ice-str (:strength current-ice)
                          ice-subtypes (clojure.string/join " " (or (:subtypes current-ice) []))
                          subs (:subroutines current-ice)
                          unbroken (count (filter #(not (:broken %)) subs))]
                      (println (format "  🧊 ICE: %s (str %s)" ice-title ice-str))
                      (println (format "     Type: %s" ice-subtypes))
                      (println (format "     Subs: %d unbroken of %d" unbroken (count subs))))))))

            (println "\n--- RUNNER ---")
            (println "Credits:" (state/runner-credits))
            (let [clicks runner-clicks]
              (if (and (= "runner" active-side) (zero? clicks) (not end-turn))
                (do
                  (println "Clicks:" clicks "(End of Turn)")
                  (println "💡 Use 'end-turn' to finish your turn"))
                (println "Clicks:" clicks)))
            (let [hand-count (state/my-hand-count)
                  max-hand-size (get-in gs [:runner :hand-size-modification] 5)]
              (println "Hand:" hand-count "cards")
              (when (and (= "runner" my-side) (> hand-count max-hand-size))
                (println "⚠️  Over hand size! Discard to" max-hand-size "at end of turn")))
            (let [agenda-points (get-in gs [:runner :agenda-point] 0)
                  corp-scored (get-in gs [:corp :agenda-point] 0)
                  runner-stolen agenda-points
                  hq-size (get-in gs [:corp :hand-count] 0)
                  rd-size (get-in gs [:corp :deck-count] 0)
                  discard-size (count (get-in gs [:corp :discard] []))
                  initial-deck-size (+ rd-size hq-size discard-size (* corp-scored 1))
                  total-agendas (cond
                                 (<= initial-deck-size 44) 18
                                 (<= initial-deck-size 49) 20
                                 (<= initial-deck-size 54) 22
                                 :else (+ 22 (* 2 (quot (- initial-deck-size 50) 5))))
                  accounted (+ corp-scored runner-stolen)
                  missing (- total-agendas accounted)
                  cards-drawn (max 0 turn-num)
                  agenda-density (if (pos? initial-deck-size)
                                  (/ (float total-agendas) initial-deck-size)
                                  0)
                  expected-drawn (int (* cards-drawn agenda-density))
                  servers (get-in gs [:corp :servers] {})
                  remotes (filter #(and (string? (key %))
                                      (re-matches #"remote\d+" (key %)))
                                servers)
                  unrezzed-remotes (filter (fn [[_ server]]
                                            (let [content (get-in server [:content])]
                                              (some #(not (:rezzed %)) content)))
                                          remotes)
                  unrezzed-count (count unrezzed-remotes)
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
            (println "Credits:" (state/corp-credits))
            (let [clicks corp-clicks]
              (if (and (= "corp" active-side) (zero? clicks) (not end-turn))
                (do
                  (println "Clicks:" clicks "(End of Turn)")
                  (println "💡 Use 'end-turn' to finish your turn"))
                (println "Clicks:" clicks)))
            (let [hand-count (state/corp-hand-count)
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

            nil)))))))

(defn status
  "Show current game status and return client state"
  []
  (show-status)
  @state/client-state)

(defn show-board
  "Display full game board: all servers with ICE, Corp installed cards, Runner rig"
  []
  (let [state @state/client-state
        gs (:game-state state)
        my-side (:side state)  ;; "corp" or "runner" (lowercase) - determines what we can see
        is-corp? (= "corp" (some-> my-side clojure.string/lower-case))
        corp-servers (:servers (:corp gs))
        runner-rig (get-in gs [:runner :rig])
        corp-deck-count (get-in gs [:corp :deck-count])
        corp-discard (get-in gs [:corp :discard])
        runner-deck-count (get-in gs [:runner :deck-count])
        runner-discard (get-in gs [:runner :discard])]
    (println "\n" (clojure.string/join "" (repeat 70 "=")))
    (println "🎮 GAME BOARD")
    (println (clojure.string/join "" (repeat 70 "=")))

    ;; Corp Servers
    (println "\n--- CORP SERVERS ---")
    (doseq [[server-key server] (sort-by first corp-servers)]
      (let [server-name (name server-key)
            ice-list (:ices server)
            content-list (:content server)]
        (when (or (seq ice-list) (seq content-list))
          (println (str "\n📍 " (clojure.string/upper-case server-name)))

          ;; Show ICE
          (if (seq ice-list)
            (doseq [[idx ice] (map-indexed vector ice-list)]
              (let [rezzed (:rezzed ice)
                    title (core/format-card-name-with-index ice ice-list)
                    subtypes (:subtypes ice)
                    subtype-str (if (seq subtypes)
                                  (clojure.string/join " " (map name subtypes))
                                  "?")
                    strength (:current-strength ice)
                    status-icon (if rezzed "🔴" "⚪")
                    ;; Corp sees their own unrezzed ICE, Runner sees "Unrezzed ICE"
                    display-name (cond
                                   rezzed title
                                   is-corp? (str title " [unrezzed]")
                                   :else "Unrezzed ICE")]
                (println (str "  ICE #" idx ": " status-icon " "
                             display-name
                             (when rezzed (str " (" subtype-str ")"))
                             (when (and rezzed strength) (str " (str: " strength ")"))
                             (format-counters ice)))))
            (println "  (No ICE)"))

          ;; Show Content (assets/agendas)
          (when (seq content-list)
            (let [rezzed-content (filter :rezzed content-list)
                  unrezzed-content (filter (complement :rezzed) content-list)]
              (doseq [card rezzed-content]
                (let [card-name (core/format-card-name-with-index card content-list)]
                  (println (str "  Content: 🔴 " card-name " (" (:type card) ")" (format-counters card)))))
              ;; Corp sees their own unrezzed cards, Runner sees "Unrezzed card"
              (doseq [card unrezzed-content]
                (let [card-name (core/format-card-name-with-index card content-list)
                      display-name (if is-corp?
                                     (str card-name " [unrezzed]")
                                     "Unrezzed card")]
                  (println (str "  Content: ⚪ " display-name (format-counters card)))))))
          )))

    ;; Runner Rig
    (println "\n--- RUNNER RIG ---")
    (let [programs (:program runner-rig)
          hardware (:hardware runner-rig)
          resources (:resource runner-rig)]
      (if (seq programs)
        (do
          (println "\n💾 Programs:")
          (doseq [prog programs]
            (let [prog-name (core/format-card-name-with-index prog programs)]
              (println (str "  • " prog-name
                           (when-let [strength (:current-strength prog)] (str " (str: " strength ")"))
                           (format-counters prog))))))
        (println "\n💾 Programs: (none)"))

      (if (seq hardware)
        (do
          (println "\n🔧 Hardware:")
          (doseq [hw hardware]
            (let [hw-name (core/format-card-name-with-index hw hardware)]
              (println (str "  • " hw-name (format-counters hw))))))
        (println "🔧 Hardware: (none)"))

      (if (seq resources)
        (do
          (println "\n📦 Resources:")
          (doseq [res resources]
            (let [res-name (core/format-card-name-with-index res resources)]
              (println (str "  • " res-name (format-counters res))))))
        (println "📦 Resources: (none)")))

    ;; Deck/Discard counts
    (println "\n--- DECK STATUS ---")
    (println (str "Corp Deck: " corp-deck-count " | Discard: " (count corp-discard)))
    (println (str "Runner Deck: " runner-deck-count " | Discard: " (count runner-discard)))

    (println (clojure.string/join "" (repeat 70 "=")))
    nil))

(defn show-board-compact
  "Display ultra-compact board state (2-5 lines, no decorations)"
  []
  (let [state @state/client-state
        gs (:game-state state)
        corp-servers (:servers (:corp gs))
        runner-rig (get-in gs [:runner :rig])]

    ;; Corp servers - one line per server with activity
    (print "Corp:")
    (doseq [[server-key server] (sort-by first corp-servers)]
      (let [server-name (name server-key)
            ice-list (:ices server)
            content-list (:content server)
            rezzed-ice (filter :rezzed ice-list)
            unrezzed-ice-count (- (count ice-list) (count rezzed-ice))
            rezzed-content (filter :rezzed content-list)
            unrezzed-content-count (- (count content-list) (count rezzed-content))]
        (when (or (seq ice-list) (seq content-list))
          (print (str " " (clojure.string/upper-case server-name) "["))
          ;; ICE summary
          (when (seq rezzed-ice)
            (print (clojure.string/join "," (map #(core/format-card-name-with-index % ice-list) rezzed-ice))))
          (when (> unrezzed-ice-count 0)
            (print (if (seq rezzed-ice) "," ""))
            (print (str unrezzed-ice-count "?ice")))
          (print "|")
          ;; Content summary
          (when (seq rezzed-content)
            (print (clojure.string/join "," (map #(core/format-card-name-with-index % content-list) rezzed-content))))
          (when (> unrezzed-content-count 0)
            (print (if (seq rezzed-content) "," ""))
            (print (str unrezzed-content-count "?")))
          (print "]"))))
    (println)

    ;; Runner rig - one line
    (let [programs (:program runner-rig)
          hardware (:hardware runner-rig)
          resources (:resource runner-rig)]
      (println (format "Rig: Prog[%d] HW[%d] Res[%d]%s"
                      (count programs)
                      (count hardware)
                      (count resources)
                      (if (seq programs)
                        (str " - " (clojure.string/join "," (map #(core/format-card-name-with-index % programs) programs)))
                        ""))))
    nil))

(defn get-game-log
  "Get the game log from current game state"
  []
  (state/game-log))

(defn show-game-log
  "Display game log in readable format"
  ([] (show-game-log 20))
  ([n]
   (if-let [log (get-game-log)]
     (do
       (println "\n📜 GAME LOG (last" n "entries)")
       (doseq [entry (take-last n log)]
         (when (map? entry)
           (let [text (str/replace (:text entry "") "[hr]" "")]
             (println (str "  " text)))))
       nil)
     (println "No game log available"))))

(defn show-log
  "Display game log (natural language event history)"
  ([] (show-game-log 20))
  ([n] (show-game-log n)))

(defn get-lobby-list
  "Get the current lobby list from state"
  []
  (:lobby-list @state/client-state))

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

(defn show-log-compact
  "Display ultra-compact game log (recent N entries, one line each, no decorations)"
  ([] (show-log-compact 5))
  ([n]
   (let [recent (state/recent-log n)]
     (doseq [entry recent]
       (when (map? entry)
         (let [text (clojure.string/replace (:text entry "") "[hr]" "")]
           (println text))))
     nil)))

(defn show-status-compact
  "Display ultra-compact game status (1-2 lines, no decorations)"
  []
  (let [lobby (:lobby-state @state/client-state)
        gs (state/get-game-state)]
    (if (and lobby (not (:started lobby)))
      ;; Lobby compact status
      (let [players (:players lobby)
            player-count (count players)
            ready? (and (= 2 player-count) (every? :deck players))]
        (println (format "Lobby: %d/2 players%s"
                        player-count
                        (if ready? " [READY]" ""))))
      ;; Game compact status
      (let [my-side (:side @state/client-state)
            active-side (state/active-player)
            turn (state/turn-number)
            prompt (state/get-prompt)
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

            ;; Format: T3-Corp | Me(R): 4c/2cl/5h/0AP | Opp(C): 5c/0cl/4h/0AP
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

(defn status-compact
  "Show ultra-compact game status (1 line)"
  []
  (show-status-compact))

(defn board-compact
  "Show ultra-compact board state (2-3 lines)"
  []
  (show-board-compact))

;; ============================================================================
;; Snooze / Wait Commands (Token Optimization)
;; ============================================================================

(defn wait-for-my-turn
  "Wait until it's my turn to act. Polls every 2s and returns when active.

   Returns: {:status :ready :turn N :side \"Corp\"}

   Usage: (wait-for-my-turn)"
  []
  (let [my-side (:side @state/client-state)]
    (println (str "💤 Waiting for my turn (" my-side ")..."))
    (loop [checks 0]
      (let [state (state/get-game-state)
            active-player (state/active-player)
            end-turn (get-in state [:end-turn])
            clicks (get-in state [(keyword my-side) :click])]
        (cond
          ;; My turn and I have clicks
          (and (= my-side active-player) (> clicks 0))
          (do
            (println (str "✅ Your turn! (" my-side " - " clicks " clicks remaining)"))
            {:status :ready :turn (state/turn-number) :side my-side :clicks clicks})

          ;; End turn called, waiting for me to start
          (and end-turn (not= my-side active-player))
          (do
            (println (str "✅ Ready to start turn! (use 'start-turn')"))
            {:status :ready-to-start :turn (state/turn-number) :side my-side})

          ;; Both at 0 clicks
          (and (= 0 (get-in state [:runner :click]))
               (= 0 (get-in state [:corp :click])))
          (do
            (println (str "✅ Ready to start turn! (use 'start-turn')"))
            {:status :ready-to-start :turn (state/turn-number) :side my-side})

          ;; Still waiting
          :else
          (do
            (when (= 0 (mod checks 5))
              (println (str "  ...waiting (opponent's turn: " active-player ")")))
            (Thread/sleep core/standard-delay)
            (recur (inc checks))))))))

(defn wait-for-run
  "Wait until Runner initiates a run, then return run details.
   Corp-only command. Polls every 1s.

   Returns: {:status :run-active :server \"HQ\" :phase \"approach-ice\"}

   Usage: (wait-for-run)"
  []
  (let [my-side (:side @state/client-state)]
    (when (not= my-side "corp")
      (println "❌ wait-for-run is Corp-only (use wait-for-my-turn as Runner)")
      (throw (Exception. "wait-for-run is Corp-only")))

    (println "💤 Waiting for Runner to initiate run...")
    (loop [checks 0]
      (let [state (state/get-game-state)
            run-state (get-in state [:run])]
        (if run-state
          (do
            (println (str "🏃 Run started on " (:server run-state) " (phase: " (:phase run-state) ")"))
            {:status :run-active
             :server (:server run-state)
             :phase (:phase run-state)
             :position (:position run-state)})
          (do
            (when (= 0 (mod checks 10))
              (println "  ...waiting for run"))
            (Thread/sleep core/short-delay)
            (recur (inc checks))))))))

;; ============================================================================
;; Access Prompt Display
;; ============================================================================

(defn- access-prompt?
  "Detect if prompt is an access prompt by checking for 'steal' or 'trash' keywords"
  [prompt]
  (when-let [choices (:choices prompt)]
    (let [choice-values (map :value choices)
          choice-text (str/lower-case (str/join " " choice-values))]
      (or (str/includes? choice-text "steal")
          (str/includes? choice-text "trash")))))

(defn- extract-card-name
  "Extract card name from access prompt message
   Format: 'You accessed Regolith Mining License'"
  [msg]
  (when msg
    (let [msg-lower (str/lower-case msg)]
      (when (str/includes? msg-lower "you accessed")
        (when-let [match (re-find #"(?i)you accessed\s+(.+?)(?:\.|$)" msg)]
          (second match))))))

(defn- show-access-prompt
  "Display access prompt with enhanced card metadata"
  [prompt]
  (let [msg (:msg prompt)
        card-name (extract-card-name msg)
        _ (when card-name (core/show-card-on-first-sight! card-name))  ; Show text on first access
        card-data (when card-name (get @all-cards card-name))
        choices (:choices prompt)
        has-steal? (some #(str/includes?
                           (str/lower-case (str (:value %)))
                           "steal")
                        choices)]

    ;; Display header with card metadata
    (if card-data
      (let [card-type (or (:type card-data) "unknown")
            trash-cost (:cost card-data)
            points (:agendapoints card-data)
            metadata (cond
                       points (str "[" card-type ", points=" points "]")
                       trash-cost (str "[" card-type ", trash=" trash-cost "]")
                       :else (str "[" card-type "]"))]
        (println (str "\n❓ You accessed: " card-name " " metadata)))
      (println (str "\n❓ " msg)))

    ;; Show full card text ONLY when "steal" keyword present
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
  (if-let [prompt (state/get-prompt)]
    (if (access-prompt? prompt)
      (show-access-prompt prompt)
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

(defn hand
  "Show my hand"
  []
  (let [hand (state/my-hand)]
    (println "\n=== MY HAND ===" (count hand) "cards ===")
    (doseq [[idx card] (map-indexed vector hand)]
      (println (format "  %d. %s [%s]" idx (:title card) (:type card))))
    hand))

(defn show-hand
  "Show hand using side-aware state access. Returns hand vector."
  []
  (let [state @state/client-state
        side (:side state)]
    (if-not side
      (do (println "⚠️  No game state - not in a game yet")
          nil)
      (let [hand (get-in state [:game-state (keyword (clojure.string/lower-case side)) :hand])]
        (when hand
          (println (str "\n🃏 " (clojure.string/capitalize side) " Hand:"))
          (doseq [[idx card] (map-indexed vector hand)]
            (let [card-name (core/format-card-name-with-index card hand)
                  card-type (:type card)
                  subtypes (when-let [st (:subtypes card)]
                            (when (seq st)
                              (str ": " (clojure.string/join ", " st))))
                  cost (if-let [c (:cost card)]
                        (str " (" c "¢)")
                        "")]
              (println (str "  " idx ". " card-name " [" card-type subtypes "]" cost))
              ;; Show card text for first-seen cards
              (core/show-card-on-first-sight! (:title card)))))
        hand))))

(defn show-credits
  "Show current credits (side-aware). Returns credits value."
  []
  (let [state @state/client-state
        side (:side state)
        credits (get-in state [:game-state (keyword (clojure.string/lower-case side)) :credit])]
    (println "💰 Credits:" credits)
    credits))

(defn show-clicks
  "Show remaining clicks (side-aware). Returns clicks value."
  []
  (let [state @state/client-state
        side (:side state)
        clicks (get-in state [:game-state (keyword (clojure.string/lower-case side)) :click])]
    (println "⏱️  Clicks:" clicks)
    clicks))

(defn show-archives
  "Show Corp's Archives (discard pile) with faceup/facedown counts"
  []
  (let [state @state/client-state
        archives (get-in state [:game-state :corp :discard])
        faceup (filter :seen archives)
        facedown-count (- (count archives) (count faceup))]
    (println "\n📂 Archives:")
    (println (str "  Faceup: " (count faceup) " | Facedown: " facedown-count))
    (when (seq faceup)
      (println "\n  Revealed Cards:")
      (doseq [card faceup]
        (let [cost-str (if-let [cost (:cost card)] (str cost "¢") "")
              type-str (:type card)
              subtitle (if (not-empty cost-str)
                        (str type-str ", " cost-str)
                        type-str)]
          (println (str "    • " (:title card) " (" subtitle ")")))))
    (when (> facedown-count 0)
      (println (str "\n  " facedown-count " card(s) facedown (hidden)")))))

(defn- show-encounter-ice-info
  "Display ICE encounter info: current ICE and playable icebreakers"
  [state run my-side]
  (let [server (:server run)
        position (:position run)
        ice-list (get-in state [:game-state :corp :servers (keyword (last server)) :ices])
        ice-count (count ice-list)
        ice-index (when (and position (pos? position)) (- ice-count position))
        current-ice (when (and ice-index (>= ice-index 0) (< ice-index ice-count))
                      (nth ice-list ice-index nil))]
    (when (and current-ice (:rezzed current-ice))
      (let [ice-title (:title current-ice)
            ice-str (:strength current-ice)
            ice-subtypes (clojure.string/join " " (or (:subtypes current-ice) []))
            subs (:subroutines current-ice)
            unbroken (count (filter #(not (:broken %)) subs))]
        (println (format "  🧊 ICE: %s (str %s, %s)" ice-title ice-str ice-subtypes))
        (println (format "     Subroutines: %d unbroken of %d" unbroken (count subs)))
        ;; Show playable icebreakers for Runner
        (when (= my-side "runner")
          (let [programs (get-in state [:game-state :runner :rig :program])
                ;; Check :subtypes vector for "Icebreaker"
                breakers (filter #(some (fn [st] (= (str st) "Icebreaker"))
                                        (or (:subtypes %) []))
                                 programs)
                playable-breakers (filter #(some :playable (:abilities %)) breakers)]
            (when (seq playable-breakers)
              (println "  💪 Icebreakers with playable abilities:")
              (doseq [b playable-breakers]
                (let [playable-abs (filter :playable (:abilities b))]
                  (println (format "     • %s (str %s)" (:title b) (or (:current-strength b) (:strength b))))
                  (doseq [ab playable-abs]
                    (println (format "       → %s" (:label ab)))))))))))))

(defn show-prompt-detailed
  "Show current prompt with detailed choices"
  []
  (let [state @state/client-state
        side (:side state)
        prompt (when side
                 (get-in state [:game-state (keyword (clojure.string/lower-case side)) :prompt-state]))]
    (if prompt
      (let [has-choices (seq (:choices prompt))
            has-selectable (seq (:selectable prompt))]
        (println "\n🔔 Current Prompt:")
        (println "  Message:" (:msg prompt))
        (println "  Type:" (:prompt-type prompt))
        (when-let [card (:card prompt)]
          (println "  Card:" (:title card) (when (:type card) (str "(" (:type card) ")"))))
        (when has-choices
          (println "  Choices:")
          (doseq [[idx choice] (map-indexed vector (:choices prompt))]
            (println (str "    " idx ". " (core/format-choice choice)))))
        (when has-selectable
          (println "  Selectable cards:" (count (:selectable prompt))
                   "- Use choose-card! to select by index")
          (let [selectable (:selectable prompt)]
            (doseq [[idx cid-or-card] (map-indexed vector selectable)]
              ;; Selectable can be CID strings or card maps - resolve CIDs to cards
              (let [card (if (string? cid-or-card)
                           (core/find-card-by-cid cid-or-card)
                           cid-or-card)
                    title (or (:title card) (:printed-title card)
                             (when (seq (:zone card)) (str "Card in " (clojure.string/join "/" (map name (:zone card)))))
                             (str "CID: " (if (string? cid-or-card) cid-or-card "?")))
                    card-type (or (:type card) "")
                    zone (:zone card)
                    rezzed? (:rezzed card)]
                (println (str "    " idx ". " title
                            (when (seq card-type) (str " [" card-type "]"))
                            (when (and (seq zone) (:title card)) (str " (in " (clojure.string/join "/" (map name zone)) ")"))
                            (when (some? rezzed?) (if rezzed? " (rezzed)" " (unrezzed)"))))))))
        ;; Handle paid ability windows / passive prompts
        (when (and (not has-choices) (not has-selectable))
          (let [run (get-in state [:game-state :run])
                run-phase (when run (:phase run))
                my-side (clojure.string/lower-case (or side "runner"))]
            (if run-phase
              ;; Show run phase context
              (do
                (println (str "  Run Phase: " run-phase))
                ;; During encounter-ice, show ICE and breaker info
                (when (= run-phase "encounter-ice")
                  (show-encounter-ice-info state run my-side))
                (println "    → Use 'continue' to pass priority"))
              ;; Not in a run
              (do
                (println "  Action: Paid ability window")
                (println "    → No choices required")
                (println "    → Use 'continue' command to pass priority"))))))
      (println "No active prompt"))))

(defn show-card-text
  "Display full card information including text, cost, and abilities
   Usage: (show-card-text \"Sure Gamble\")
          (show-card-text \"Tithe\")"
  [card-name]
  ;; Auto-load cards if not already loaded
  (core/load-cards-from-api!)

  (if (empty? @all-cards)
    (do
      (println "❌ Failed to load card database")
      (println "   Make sure the game server is running on localhost:1042"))
    (if-let [card (get @all-cards card-name)]
      (let [text (or (:text card) "")
            ;; Strip formatting markup for readability
            clean-text (-> text
                          (clojure.string/replace #"\[Click\]" "[Click]")
                          (clojure.string/replace #"\[Credit\]" "[Credit]")
                          (clojure.string/replace #"\[Subroutine\]" "[Subroutine]")
                          (clojure.string/replace #"\[Trash\]" "[Trash]")
                          (clojure.string/replace #"\[Recurring Credits\]" "[Recurring Credits]")
                          (clojure.string/replace #"\[mu\]" "[MU]")
                          (clojure.string/replace #"<[^>]+>" ""))] ;; Remove HTML-like tags
        (println "\n" (clojure.string/join "" (repeat 70 "=")))
        (println "📄" (:title card))
        (println (clojure.string/join "" (repeat 70 "=")))
        (println "Type:" (:type card)
                 (when (:subtype card) (str "- " (:subtype card))))
        (println "Side:" (:side card))
        (when (:faction card)
          (println "Faction:" (:faction card)))
        (when-let [cost (:cost card)]
          (println "Cost:" cost))
        (when-let [strength (:strength card)]
          (println "Strength:" strength))
        (when-let [mu (:memoryunits card)]
          (println "Memory:" mu))
        (when-let [agenda-points (:agendapoints card)]
          (println "Agenda Points:" agenda-points))
        (when-let [adv-cost (:advancementcost card)]
          (println "Advancement Requirement:" adv-cost))
        (when (not-empty clean-text)
          (println "\nText:")
          (println clean-text))
        (println (clojure.string/join "" (repeat 70 "="))))
      (println "❌ Card not found:" card-name))))

(defn show-cards
  "Display multiple cards in compact or full format
   Usage: (show-cards [\"Sure Gamble\" \"Diesel\" \"Dirty Laundry\"])
          (show-cards [\"Sure Gamble\" \"Diesel\"] true) ; full format"
  ([card-names] (show-cards card-names false))
  ([card-names full?]
   ;; Auto-load cards if not already loaded
   (core/load-cards-from-api!)

   (if (empty? @all-cards)
     (do
       (println "❌ Failed to load card database")
       (println "   Make sure the game server is running on localhost:1042"))
     (do
       (println (str "\n📚 Card Reference (" (count card-names) " cards):"))
       (println (clojure.string/join "" (repeat 70 "─")))

       (doseq [card-name card-names]
         (if-let [card (get @all-cards card-name)]
           (if full?
             ;; Full format - same as show-card-text
             (let [text (or (:text card) "")
                   clean-text (-> text
                                 (clojure.string/replace #"\[Click\]" "[Click]")
                                 (clojure.string/replace #"\[Credit\]" "[Credit]")
                                 (clojure.string/replace #"\[Subroutine\]" "[Subroutine]")
                                 (clojure.string/replace #"\[Trash\]" "[Trash]")
                                 (clojure.string/replace #"\[Recurring Credits\]" "[Recurring Credits]")
                                 (clojure.string/replace #"\[mu\]" "[MU]")
                                 (clojure.string/replace #"<[^>]+>" ""))]
               (println (str "\n📄 " (:title card)))
               (println "Type:" (:type card)
                       (when (:subtype card) (str "- " (:subtype card))))
               (when-let [cost (:cost card)] (println "Cost:" cost))
               (when-let [strength (:strength card)] (println "Strength:" strength))
               (when (not-empty clean-text)
                 (println "Text:" clean-text)))

             ;; Compact format - one line per card
             (let [type-str (:type card)
                   cost-str (when-let [c (:cost card)] (str c "¢"))
                   subtitle (if cost-str
                             (str type-str ", " cost-str)
                             type-str)
                   text (or (:text card) "")
                   ;; Get first sentence or first 60 chars
                   short-text (let [first-sentence (first (clojure.string/split text #"\." ))]
                               (if (> (count first-sentence) 60)
                                 (str (subs first-sentence 0 57) "...")
                                 first-sentence))]
               (println (str "📄 " (:title card) " (" subtitle ")"))
               (when (not-empty short-text)
                 (println (str "   " short-text)))))
           (println (str "❌ Card not found: " card-name))))

       (println (clojure.string/join "" (repeat 70 "─")))))))

(defn show-hand-cards
  "Display information for all cards currently in hand
   Usage: (show-hand-cards)
          (show-hand-cards true) ; full format"
  ([] (show-hand-cards false))
  ([full?]
   (let [hand (state/my-hand)
         card-names (map :title hand)]
     (if (empty? card-names)
       (println "🃏 Hand is empty")
       (show-cards card-names full?)))))

(defn show-card-abilities
  "Show available abilities for an installed card by name
   Works for both Runner and Corp cards
   Usage: (show-card-abilities \"Smartware Distributor\")
          (show-card-abilities \"Cleaver\")"
  [card-name]
  (let [state @state/client-state
        side (:side state)
        ;; Find card in appropriate location
        card (if (= "Corp" side)
               (core/find-installed-corp-card card-name)
               (core/find-installed-card card-name))]
    (if card
      (let [abilities (:abilities card)]
        (println "\n" (clojure.string/join "" (repeat 70 "=")))
        (println "🎯" (:title card) "- ABILITIES")
        (println (clojure.string/join "" (repeat 70 "=")))
        (if (seq abilities)
          (doseq [[idx ability] (map-indexed vector abilities)]
            (let [label (or (:label ability) (:cost-label ability) (str "Ability " idx))
                  action-icon (if (:action ability) "[Click] " "")
                  once-str (when (:once ability)
                            (str " (Once " (if (keyword? (:once ability))
                                             (name (:once ability))
                                             (:once ability)) ")"))]
              (println (str "\n  [" idx "] " label))
              (when-let [cost-label (:cost-label ability)]
                (println (str "      Cost: " action-icon cost-label)))
              (when once-str
                (println (str "      " once-str)))))
          (println "No abilities available"))
        (println (clojure.string/join "" (repeat 70 "="))))
      (println "❌ Card not found installed:" card-name))))

;; ============================================================================
;; High-Level Workflows
;; ============================================================================

(defn simple-corp-turn
  "Execute a simple Corp turn: click for credit 3 times, end turn"
  []
  (println "\n=== SIMPLE CORP TURN ===")
  (dotimes [i 3]
    (actions/take-credits))
  (actions/end-turn)
  (println "=== TURN COMPLETE ===\n"))

(defn simple-runner-turn
  "Execute a simple Runner turn: click for credit 4 times, end turn"
  []
  (println "\n=== SIMPLE RUNNER TURN ===")
  (dotimes [i 4]
    (actions/take-credits))
  (actions/end-turn)
  (println "=== TURN COMPLETE ===\n"))

;; ============================================================================
;; Debugging
;; ============================================================================

(defn inspect-state
  "Show raw game state (for debugging)"
  []
  (clojure.pprint/pprint @state/client-state))

(defn inspect-prompt
  "Show raw prompt data (for debugging)"
  []
  (clojure.pprint/pprint (state/get-prompt)))

;; ============================================================================
;; Help
;; ============================================================================

(defn list-playables
  "List all currently playable actions (cards, abilities, basic actions)
   Useful for AI decision-making - shows exactly what can be done right now"
  []
  (let [state @state/client-state
        side (keyword (clojure.string/lower-case (:side state)))
        gs (:game-state state)
        my-state (get gs side)
        clicks (:click my-state)
        credits (:credit my-state)
        hand (:hand my-state)
        rig (:rig my-state)]

    (println "\n=== PLAYABLE ACTIONS ===")
    (println (format "Clicks: %s  Credits: %s"
                     (or clicks "?")
                     (or credits "?")))

    ;; Check for active prompts
    (let [prompt (first (:prompt my-state))
          prompt-state (:prompt-state my-state)]
      (when (or prompt prompt-state)
        (let [msg (or (:msg prompt-state) (:msg prompt))]
          (when msg
            (println (format "\n⚠️  Active Prompt: %s" msg))
            (println "   Use 'send_command prompt' to see choices, or 'send_command choose <N>' to resolve")))))

    ;; Playable hand cards
    (let [playable-cards (filter :playable hand)]
      (when (seq playable-cards)
        (println "\n📋 Hand Cards:")
        (doseq [card playable-cards]
          (let [card-name (core/format-card-name-with-index card hand)
                cost (:cost card)
                cost-str (if cost (str cost " credits") "free")]
            (println (format "  - %s (%s, %s)"
                            card-name
                            (:type card)
                            cost-str))))))

    ;; Playable installed abilities
    (let [all-installed (concat
                         (get rig :hardware [])
                         (get rig :program [])
                         (get rig :resource []))
          playable-abilities (for [card all-installed
                                  [idx ability] (map-indexed vector (:abilities card))
                                  :when (:playable ability)]
                              {:card card
                               :card-name (core/format-card-name-with-index card all-installed)
                               :idx idx
                               :label (:label ability)
                               :cost (:cost-label ability)})]
      (when (seq playable-abilities)
        (println "\n⚙️  Installed Abilities:")
        (doseq [{:keys [card-name idx label cost]} playable-abilities]
          (println (format "  - %s: Ability %d - %s%s"
                          card-name
                          idx
                          label
                          (if cost (str " (" cost ")") ""))))))

    ;; Runner abilities on Corp cards (e.g., bioroid click-to-break)
    ;; Check prompt-state for runner abilities during encounters
    (when (= side :runner)
      (let [prompt-state (get-in gs [:runner :prompt-state])
            prompt-card (:card prompt-state)
            runner-abilities (:runner-abilities prompt-card)]
        (when (seq runner-abilities)
          (println "\n🔓 Runner Abilities (Bioroid/Corp cards):")
          (doseq [[idx ability] (map-indexed vector runner-abilities)]
            (println (format "  - %s: Runner-Ability %d - %s%s"
                            (:title prompt-card)
                            idx
                            (:label ability)
                            (if-let [cost (:cost-label ability)]
                              (str " (" cost ")")
                              "")))))))

    ;; Basic actions (always available if clicks > 0)
    (when (and clicks (pos? clicks))
      (println "\n🎯 Basic Actions:")
      (println "  - take-credit (gain 1 credit, costs 1 click)")
      (println "  - run <server> (initiate run, costs 1 click)")
      (when (= side :corp)
        (println "  - draw-card (draw 1 card, costs 1 click)")
        (println "  - purge (remove all virus counters, costs 3 clicks)")))

    ;; Always available
    (println "\n⏭️  Other Actions:")
    (println "  - end-turn (end current turn)")
    (println "")

    ;; Return count of playable actions
    (let [card-count (count (filter :playable hand))
          ability-count (count (for [card (concat (get rig :hardware [])
                                                  (get rig :program [])
                                                  (get rig :resource []))
                                    ability (:abilities card)
                                    :when (:playable ability)]
                                ability))]
      (println (format "Total: %d playable cards, %d playable abilities, %s basic actions"
                      card-count
                      ability-count
                      (if (and clicks (pos? clicks)) (if (= side :corp) "4" "2") "0")))
      {:playable-cards card-count
       :playable-abilities ability-count
       :clicks clicks})))

(defn help
  "Show available commands"
  []
  (println "\n=== AI ACTIONS HELP ===\n")
  (println "Lobby Management:")
  (println "  (create-lobby! \"Game Name\")       - Create a new game lobby")
  (println "  (create-lobby! {:title \"Game\"})   - Create with options map")
  (println "  (list-lobbies)                     - List available games")
  (println "\nConnection:")
  (println "  (connect-game! \"game-id\" \"Corp\") - Join a game")
  (println "\nInformation:")
  (println "  (status)                           - Show game status")
  (println "  (show-prompt)                      - Show current prompt")
  (println "  (hand)                             - Show my hand")
  (println "  (list-playables)                   - List all playable actions")
  (println "\nMulligan:")
  (println "  (keep-hand)                        - Keep hand")
  (println "  (mulligan)                         - Redraw hand")
  (println "  (auto-keep-mulligan)               - Auto-handle mulligan")
  (println "\nBasic Actions:")
  (println "  (take-credits)                     - Click for credit")
  (println "  (draw-card)                        - Draw a card")
  (println "  (end-turn)                         - End turn")
  (println "\nPrompts:")
  (println "  (choose 0)                         - Choose first option")
  (println "  (wait-for-prompt 10)               - Wait for prompt")
  (println "  (wait-for-my-turn 30)              - Wait for my turn")
  (println "  (wait-for-diff)                    - Wait for any state change (shows recent log)")
  (println "  (wait-for-diff 120)                - Wait with custom timeout")
  (println "  (wait-for-log-past \"marker text\")  - Wait for log entries after marker")
  (println "\nWorkflows:")
  (println "  (simple-corp-turn)                 - 3x credit, end")
  (println "  (simple-runner-turn)               - 4x credit, end")
  (println "\nDebugging:")
  (println "  (inspect-state)                    - Show raw state")
  (println "  (inspect-prompt)                   - Show raw prompt")
  (println "  (help)                             - This help\n"))
