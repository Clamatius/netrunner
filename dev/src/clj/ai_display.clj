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

;; Forward declaration: run-server-display and print-run-phase-ladder! (defined
;; below, near the run-priority helpers) are used by the status displays above
;; their definitions.
(declare run-server-display)
(declare print-run-phase-ladder!)

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

(defn format-runner-agenda-line
  "Runner's-eye agenda threat line, with units made explicit.

   The old single header — `Missing: 18 (Drawn: ~0, HQ: 5, R&D: 38, Remotes: 0/0)`
   — conflated two different units under one parenthetical, so it read as
   '18 agendas, 5 of them in HQ, 38 in R&D'. In fact:
     missing         - agenda *POINTS* not yet scored or stolen (Corp needs 7)
     expected-drawn  - estimated agenda *CARDS* the Corp has likely drawn into HQ
     hq-size/rd-size - total *CARD* counts in HQ / R&D — the haystack, not agendas
     unrezzed/advanced - remote-server counts the Runner can see
   Labelling the points-vs-cards split removes the misread."
  [agenda-points missing expected-drawn hq-size rd-size unrezzed-count advanced-count]
  (format "Agenda Points: %d / 7  │  Unaccounted: %d agenda pts — hiding among HQ %d / R&D %d cards, Remotes %d unrezzed / %d advanced (~%d agenda cards likely drawn)"
          agenda-points missing hq-size rd-size unrezzed-count advanced-count expected-drawn))

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
      (cond
        ;; Game has ended - show winner / tie banner
        (and gs (or (:winner gs) (and (:reason gs) (:end-time gs))))
        (let [winner (:winner gs)
              winner-str (when winner (str/capitalize (name winner)))
              winning-user (:winning-user gs)
              reason (:reason gs)]
          (println "📊 GAME STATUS")
          (println (str "\n🏁 GAME OVER (Turn: " (or (:turn gs) "?") ")"))
          (if winner
            (println (str "\n🏆 " winner-str " wins"
                          (when winning-user (str " (" winning-user ")"))))
            (println "\n🤝 The game is a tie"))
          (when reason
            (println "Reason:" reason))
          (let [runner-pts (get-in gs [:runner :agenda-point])
                corp-pts (get-in gs [:corp :agenda-point])]
            (when (or runner-pts corp-pts)
              (println (format "Agenda points - Corp: %s, Runner: %s"
                               (or corp-pts 0) (or runner-pts 0))))))

        ;; Lobby exists but game not started - show lobby status
        (and lobby (not (:started lobby)))
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

        ;; Game started but no game state yet (post-join, pre-resync)
        (nil? gs)
        (do
          (println "📊 GAME STATUS")
          (println "\n⏳ Waiting for game state...")
          (println "💡 Use 'resync <game-id>' to fetch game state"))

        ;; Show game status
        :else
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
              (state/waiting-prompt-type? prompt-type)
              (println "Status: ⏳" (:msg prompt))

              ;; My turn and active
              :else
              (println "Status: ✅ Your turn to act"))

            ;; Run status
            (when run-state
              (println "\n🏃 ACTIVE RUN:")
              (println "  Server:" (run-server-display (last (:server run-state))))
              ;; Explicit phase ladder (forum [099]) — a YOU-ARE-HERE marker over
              ;; the whole run arc beats a bare "Phase: movement" line that makes
              ;; the seat reconstruct where it is from the rules. Falls back to the
              ;; bare phase/position lines if the phase is unmodelled.
              (when-not (print-run-phase-ladder! @state/client-state run-state
                                                 (clojure.string/lower-case (or my-side "runner")))
                (println "  Phase:" (:phase run-state))
                (when-let [pos (:position run-state)]
                  (println "  Position:" pos)))
              ;; Show ICE info during encounter-ice
              (when (= "encounter-ice" (:phase run-state))
                (when-let [current-ice (core/current-run-ice @state/client-state)]
                  (when (:rezzed current-ice)
                    (let [ice-title (:title current-ice)
                          ice-str (or (:current-strength current-ice) (:strength current-ice))
                          ice-subtypes (clojure.string/join " " (or (:subtypes current-ice) []))
                          subs (:subroutines current-ice)
                          unbroken (count (filter #(not (:broken %)) subs))]
                      (println (format "  🧊 ICE: %s (str %s)" ice-title ice-str))
                      (println (format "     Type: %s" ice-subtypes))
                      (println (format "     Subs: %d unbroken of %d" unbroken (count subs))))))))

            (println "\n--- RUNNER ---")
            (let [hosted (state/runner-hosted-credits)]
              (if (pos? (:total hosted))
                (println (format "Credits: %d (+%d hosted: %s)"
                                 (state/runner-credits)
                                 (:total hosted)
                                 (clojure.string/join ", "
                                   (map #(format "%s %d" (:title %) (:credits %))
                                        (:sources hosted)))))
                (println "Credits:" (state/runner-credits))))
            (let [clicks runner-clicks]
              (if (and (= "runner" active-side) (zero? clicks) (not end-turn) (not both-zero-clicks))
                (do
                  (println "Clicks:" clicks "(End of Turn)")
                  (println "💡 Use 'end-turn' to finish your turn"))
                (println "Clicks:" clicks)))
            (let [hand-count (state/my-hand-count)
                  max-hand-size (get-in gs [:runner :hand-size-modification] 5)
                  tags (get-in gs [:runner :tag :base] 0)]
              (println "Hand:" hand-count "cards")
              (when (pos? tags)
                (println (str "🏷️  Tags: " tags)))
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
                (println (format-runner-agenda-line
                          agenda-points missing expected-drawn hq-size rd-size unrezzed-count advanced-count))
                (println "Agenda Points:" agenda-points "/ 7")))
            (println "\n--- CORP ---")
            (println "Credits:" (state/corp-credits))
            (let [clicks corp-clicks]
              (if (and (= "corp" active-side) (zero? clicks) (not end-turn) (not both-zero-clicks))
                (do
                  (println "Clicks:" clicks "(End of Turn)")
                  (println "💡 Use 'end-turn' to finish your turn"))
                (println "Clicks:" clicks)))
            (let [hand-count (state/corp-hand-count)
                  max-hand-size (get-in gs [:corp :hand-size-modification] 5)]
              (println "Hand:" hand-count "cards")
              (when (and (= "corp" my-side) (> hand-count max-hand-size))
                (println "⚠️  Over hand size! Discard to" max-hand-size "at end of turn")))
            (let [agenda-points (get-in gs [:corp :agenda-point] 0)
                  runner-tags (get-in gs [:runner :tag :base] 0)]
              (println "Agenda Points:" agenda-points "/ 7")
              (when (and (= "corp" my-side) (pos? runner-tags))
                (println (str "🏷️  Runner tagged! (" runner-tags " tag" (when (> runner-tags 1) "s") ")"))
                (println "   💡 trash-resource ($2 + click to trash a resource)")))
            (when (and prompt (not (state/waiting-prompt-type? prompt-type)))
              (println "\n🔔 Active Prompt:" (:msg prompt)))

            ;; Show recent log entries (summarized)
            (when-let [log (get-in gs [:log])]
              (let [recent-log (take-last 4 log)
                    summarized (core/summarize-log-entries recent-log)]
                (when (seq summarized)
                  (println "\n--- RECENT LOG ---")
                  (doseq [entry summarized]
                    (println " " (:text entry))))))

            nil)))))))

(defn status
  "Show current game status and return client state"
  []
  (show-status)
  @state/client-state)

(defn game-over-status
  "Print a single machine-readable line describing game-over state.
   Intended for tooling (e.g. the self-play regression harness) so it does
   not have to screen-scrape the human-facing status banner.

   Output (one line):
     NO-GAME                                  - no game state loaded
     GAME-OVER winner=corp turn=18                     - decided (winner = corp|runner)
     GAME-OVER winner=tie turn=12                      - tied game
     AWAITING-START turn=12 next-player=runner         - clean turn boundary
     IN-PROGRESS turn=12 whose-turn=runner clicks=3    - game still running

   AWAITING-START marks a clean turn boundary (a player ended their turn, or
   both sides are at 0 clicks) and names who acts next, so tooling can apply a
   patient boundary budget instead of mistaking a slow opponent's turn-start
   think-time for a stall.

   The clicks field is the active player's remaining clicks, so tooling can
   distinguish a within-turn spin (same turn + same clicks, not progressing)
   from normal play."
  []
  (let [gs (state/get-game-state)]
    (if (nil? gs)
      (println "NO-GAME")
      (let [{:keys [game-over? winner turn-number whose-turn
                    waiting-to-start? next-player]} (state/get-turn-status)
            clicks (when whose-turn (get-in gs [(keyword whose-turn) :click]))]
        (cond
          game-over?
          (println (format "GAME-OVER winner=%s turn=%s"
                           (if winner (str/lower-case (name winner)) "tie")
                           (or turn-number "?")))

          waiting-to-start?
          (println (format "AWAITING-START turn=%s next-player=%s"
                           (or turn-number "?")
                           (or next-player "?")))

          :else
          (println (format "IN-PROGRESS turn=%s whose-turn=%s clicks=%s"
                           (or turn-number "?")
                           (or whose-turn "?")
                           (if (some? clicks) clicks "?"))))))))

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
      (println))
    (do
      (println "No games available.")
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

            ;; At a clean turn boundary the active-player wire field still names
            ;; the player who just finished, so flip to who acts next (matching
            ;; game-over-status's AWAITING-START next-player). Otherwise tooling
            ;; and models reading this line mistake whose turn is starting.
            turn-status (state/get-turn-status)
            waiting-start? (:waiting-to-start? turn-status)
            display-side (if waiting-start? (:next-player turn-status) active-side)

            ;; Runner state
            runner-credits (get-in gs [:runner :credit] 0)
            runner-clicks (get-in gs [:runner :click] 0)
            runner-hand (get-in gs [:runner :hand] [])
            ;; Hand/HQ size is PUBLIC info, but the opponent's :hand is fog-of-war
            ;; hidden in wire state (arrives empty). Read the public :hand-count
            ;; field so the Opp segment doesn't under-report the opponent's hand
            ;; as 0h; fall back to (count hand) only if the count field is absent.
            runner-hand-ct (get-in gs [:runner :hand-count] (count runner-hand))
            runner-ap (get-in gs [:runner :agenda-point] 0)
            ;; Credits hosted on rig/play-area cards (e.g. Overclock during a run)
            ;; are spendable but omitted from the pool field -- surface as (+N)
            ;; so the seat doesn't undercount affordability (issue #21).
            runner-hosted (:total (state/runner-hosted-credits gs))
            runner-cred-str (if (pos? runner-hosted)
                              (format "%d(+%d)" runner-credits runner-hosted)
                              (str runner-credits))

            ;; Corp state
            corp-credits (get-in gs [:corp :credit] 0)
            corp-clicks (get-in gs [:corp :click] 0)
            corp-hand (get-in gs [:corp :hand] [])
            corp-hand-ct (get-in gs [:corp :hand-count] (count corp-hand))
            corp-ap (get-in gs [:corp :agenda-point] 0)

            ;; Format: T3-Corp | Me(R): 4c/2cl/5h/0AP | Opp(C): 5c/0cl/4h/0AP
            my-stats (if (= my-side "runner")
                      (format "%sc/%dcl/%dh/%dAP" runner-cred-str runner-clicks runner-hand-ct runner-ap)
                      (format "%dc/%dcl/%dh/%dAP" corp-credits corp-clicks corp-hand-ct corp-ap))
            opp-stats (if (= my-side "runner")
                       (format "%dc/%dcl/%dh/%dAP" corp-credits corp-clicks corp-hand-ct corp-ap)
                       (format "%sc/%dcl/%dh/%dAP" runner-cred-str runner-clicks runner-hand-ct runner-ap))
            my-label (if (= my-side "runner") "R" "C")
            opp-label (if (= my-side "runner") "C" "R")

            prompt-str (cond
                        run-state (format "Run:%s" (run-server-display (last (:server run-state))))
                        waiting-start? "awaiting-start"
                        prompt (let [msg (:msg prompt)]
                                (if (> (count msg) 30)
                                  (str (subs msg 0 27) "...")
                                  msg))
                        :else "-")]

        (println (format "T%d-%s | Me(%s):%s | Opp(%s):%s | %s"
                        turn
                        display-side
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
            trash-cost (:trash card-data)
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

(defn- extract-icebreaker-abilities
  "Extract break/pump costs from icebreaker card text. Returns string like '1¢:break, 2¢:+1str' or nil."
  [card-title]
  (when-let [card (get @all-cards card-title)]
    (let [text (or (:text card) "")
          ;; Extract break cost: <strong>X[credit]:</strong> Break...
          break-match (re-find #"<strong>(\d+)\[credit\]:</strong>\s*Break\s+(?:up to\s+)?(\d+)?" text)
          ;; Extract pump cost: <strong>X[credit]:</strong> +Y strength
          pump-match (re-find #"<strong>(\d+)\[credit\]:</strong>\s*\+(\d+)\s+strength" text)]
      (when (or break-match pump-match)
        (let [parts (cond-> []
                      break-match (conj (str (nth break-match 1) "¢:break"
                                            (when-let [n (nth break-match 2 nil)]
                                              (str " " n))))
                      pump-match (conj (str (nth pump-match 1) "¢:+" (nth pump-match 2) "str")))]
          (when (seq parts)
            (str/join ", " parts)))))))

(defn- format-card-for-hand
  "Format a single card for hand display with type-specific info."
  [card]
  (let [card-type (:type card)
        subtypes (when-let [st (:subtypes card)]
                   (when (seq st)
                     (str ": " (clojure.string/join ", " st))))
        ;; Type-specific formatting
        cost-info (case card-type
                    ;; Agendas: show advancement requirement and points
                    "Agenda" (let [adv (:advancementcost card)
                                   pts (:agendapoints card)]
                               (str " (" adv "⬆ → " pts "pts)"))
                    ;; ICE: show rez cost (not play cost)
                    "ICE" (str " (" (:cost card) "¢)")
                    ;; Programs: show cost, MU, and strength if icebreaker
                    "Program" (let [cost (:cost card)
                                    mu (:memoryunits card 1)
                                    strength (:strength card)]
                                (str " (" cost "¢, " mu "MU"
                                     (when strength (str ", str " strength))
                                     ")"))
                    ;; Hardware: show cost
                    "Hardware" (str " (" (:cost card) "¢)")
                    ;; Resources: show cost
                    "Resource" (str " (" (:cost card) "¢)")
                    ;; Assets: show cost and trash cost
                    "Asset" (let [cost (:cost card)
                                  trash (:trash card)]
                              (str " (" cost "¢"
                                   (when trash (str ", 🗑" trash))
                                   ")"))
                    ;; Upgrades: show cost and trash cost
                    "Upgrade" (let [cost (:cost card)
                                    trash (:trash card)]
                                (str " (" cost "¢"
                                     (when trash (str ", 🗑" trash))
                                     ")"))
                    ;; Operations/Events: show cost
                    (if-let [c (:cost card)]
                      (str " (" c "¢)")
                      ""))]
    (str "[" card-type subtypes "]" cost-info)))

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
          (println (str "🃏 " (clojure.string/capitalize side) " Hand:"))
          (doseq [[idx card] (map-indexed vector hand)]
            (let [card-name (core/format-card-name-with-index card hand)
                  formatted (format-card-for-hand card)
                  ;; For icebreakers, show ability costs
                  is-icebreaker? (some #{"Icebreaker"} (:subtypes card))
                  ability-info (when is-icebreaker?
                                (extract-icebreaker-abilities (:title card)))]
              (println (str "  " idx ". " card-name " " formatted))
              (when ability-info
                (println (str "     → " ability-info)))
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

(defn show-heap
  "Show Runner's Heap (discard pile)"
  []
  (let [state @state/client-state
        heap (get-in state [:game-state :runner :discard])]
    (println "\n🗑️  Heap:")
    (println (str "  Total: " (count heap) " cards"))
    (when (seq heap)
      (println "")
      (doseq [card heap]
        (let [cost-str (if-let [cost (:cost card)] (str cost "¢") "")
              type-str (:type card)
              subtype-str (when-let [st (:subtype card)] (str " - " st))
              subtitle (str type-str subtype-str (when (not-empty cost-str) (str ", " cost-str)))]
          (println (str "    • " (:title card) " (" subtitle ")")))))))

(defn- show-encounter-ice-info
  "Display ICE encounter info: current ICE and playable icebreakers"
  [state run my-side]
  (when-let [current-ice (core/current-run-ice state)]
    (when (:rezzed current-ice)
      (let [ice-title (:title current-ice)
            ice-str (or (:current-strength current-ice) (:strength current-ice))
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

(defn run-server-display
  "Human-readable name for a run's target server, given the last element of the
   run's :server vector (e.g. \"rd\", \"hq\", \"archives\", \"remote1\").
   Falls back to a generic phrase if the key is unrecognised/nil."
  [server-key]
  (let [k (when server-key (clojure.string/lower-case (str server-key)))]
    (cond
      (= k "rd") "R&D"
      (= k "hq") "HQ"
      (= k "archives") "Archives"
      (and k (re-matches #"remote\d+" k)) (str "Server " (subs k 6))
      (seq k) (str "the " k " server")
      :else "the server")))

(defn run-priority-hint-lines
  "Side-aware hint lines for a run priority window (movement / approach-server).

   A run only advances when BOTH players pass priority (`continue`); the engine
   records the first side to pass in the run's `:no-action`. The old rendering
   printed an identical symmetric line to both seats ('Use continue to pass
   priority'), so each side read it as 'I'm waiting on the other' and the run
   deadlocked — and the Runner never learned that continuing here is what gets it
   its access. This disambiguates whose move it is now and what `continue` does.

   `run` is the [:game-state :run] map; `my-side` is \"runner\"/\"corp\".
   Returns a vector of lines to println."
  [run my-side]
  (let [position  (:position run)
        past-ice? (or (nil? position) (zero? position))
        server    (run-server-display (last (:server run)))
        na        (let [v (:no-action run)]
                    (cond (keyword? v) (name v) (string? v) v :else nil))
        opp       (if (= my-side "runner") "Corp" "Runner")
        ;; The run timing splits each priority window into two sub-steps: the
        ;; ACTIVE player passes first, then the opponent (forum [118]). During a
        ;; run the active player is ALWAYS the Runner — runs only happen on the
        ;; Runner's turn (rule invariant, memory run-priority-active-player-first),
        ;; so we encode that directly rather than trusting the volatile
        ;; :active-player wire field. Render: Runner = first sub-step, Corp = second.
        i-am-active? (= my-side "runner")
        ;; What the Runner specifically gains by continuing this window.
        gain      (when (= my-side "runner")
                    (if past-ice?
                      (str "breach " server " and access cards")
                      (str "approach the next ICE on " server)))]
    (cond
      ;; I have already passed — waiting on the opponent; no action from me.
      (= na my-side)
      (into
        [(str "    ⏸️  You have already passed priority here — waiting for " opp
              " to pass before the run advances.")
         (str "      (No action needed from you; use 'wait'. Re-sending 'continue' does nothing.)")]
        ;; Stall recovery (issue #31): a both-must-pass window only advances once
        ;; the opponent also passes. In cross-model play the opposing seat must be
        ;; actively monitoring the run to pass an empty window; if it isn't, the
        ;; run stalls here and 'wait' never resolves. The Runner can break out with
        ;; 'jack-out' (the marquee g3 escape hatch — "only jack-out cleared it").
        (when (= my-side "runner")
          [(str "      If " opp " isn't actively monitoring the run, it can stall here — "
                "'jack-out' ends the run to recover.")]))

      ;; Opponent already passed — my continue advances the run now.
      (and na (not= na my-side))
      [(str "    → It's YOUR move: " opp " has already passed priority. Use 'continue' to "
            (or gain "advance the run") ".")]

      ;; Fresh window — nobody has passed yet. Render the two sub-steps explicitly
      ;; so each seat knows whether it's the first passer (active player) or the
      ;; second (forum [118]/[120]).
      i-am-active?
      ;; Active player (Runner): your sub-step is FIRST.
      [(str "    → YOUR sub-step (active player goes first): use 'continue' to pass priority"
            (when gain (str " (this is what gets you your access: " gain ")")) ".")
       (str "      (Both players pass to advance — " opp " gets its sub-step AFTER you pass.)")]

      ;; Opponent of the active player (Corp): your sub-step is SECOND. You do NOT
      ;; act in the Runner's sub-step — if you want to rez / fire a paid ability,
      ;; you do it in your own sub-step, AFTER the Runner has passed (forum [120]).
      :else
      [(str "    ⏸️  " opp " (active player) has priority first here and hasn't passed yet.")
       (str "      → Your sub-step comes next: wait for the " opp " to 'continue', then you 'continue' to advance the run.")])))

(def ^:private run-ladder-rungs
  "Conceptual run-timing ladder (jinteki run structure), in order. Steps #2–#4
   repeat per piece of ICE; the live :phase + :position pick the current rung."
  [:begin :approach :encounter :movement :approach-server :access])

(defn run-phase-ladder-lines
  "Render the run's timing ladder with a YOU-ARE-HERE marker on the live rung, so
   the seat sees the whole run arc at a glance without consulting the rules
   (forum [099]).

   Pure: the caller extracts the live facts and passes them in.
     :phase       engine phase string — \"initiation\" | \"approach-ice\" |
                  \"encounter-ice\" | \"movement\" | \"approach-server\" | \"success\"
     :server-name display name of the attacked server (e.g. \"R&D\")
     :position    ICE countdown — N = at ICE index N-1; 0/nil = past all ICE
     :ice-count   number of ICE protecting the server (nil if unknown)
     :ice-name    title of the current ICE (nil if unknown/unrezzed — never invent
                  a name the Runner can't legally see)

   Returns a vector of lines (header + 6 rungs), or nil when :phase is absent or
   unrecognised (the caller then falls back to the bare phase line)."
  [{:keys [phase server-name position ice-count ice-name]}]
  (let [cur (case phase
              "initiation"      :begin
              "approach-ice"    :approach
              "encounter-ice"   :encounter
              ;; movement before the innermost ICE is passed is still :movement;
              ;; once past all ICE (position 0/nil) the Runner is approaching the
              ;; server. The wire phase stays "movement" across both, so split on
              ;; position to keep the marker honest.
              "movement"        (if (and position (pos? position)) :movement :approach-server)
              "approach-server" :approach-server
              "success"         :access
              nil)]
    (when cur
      (let [srv      (or server-name "the server")
            ;; Pass order: the Runner meets the OUTERMOST ICE (position = ice-count)
            ;; first, so pass-index counts up as position counts down. Guard the
            ;; upper bound too: a position > ice-count (shouldn't happen, but the
            ;; wire is the volatile coupling) would otherwise print a bogus
            ;; "ICE 0 of N" / negative index — drop the index rather than lie.
            pass-idx (when (and ice-count position (pos? position) (<= position ice-count))
                       (inc (- ice-count position)))
            ice-of   (if (and pass-idx ice-count) (format " %d of %d" pass-idx ice-count) "")
            ice-tag  (if ice-name (format " [%s]" ice-name) "")
            label    {:begin           "#1 Run begins"
                      :approach        (str "#2 Approach ICE" ice-of ice-tag)
                      :encounter       (str "#3 Encounter ICE" ice-of ice-tag)
                      :movement        "#4 Movement — pass ICE, then approach the next"
                      :approach-server (str "#5 Approach server (" srv ")")
                      :access          (str "#6 Access " srv)}
            mark     (fn [rung]
                       (if (= rung cur)
                         (str "   ▶ " (label rung) "   ← YOU ARE HERE")
                         (str "     " (label rung))))]
        (into ["  📍 Run timing (steps #2–#4 repeat per ICE):"]
              (map mark run-ladder-rungs))))))

(defn print-run-phase-ladder!
  "Derive run-phase-ladder facts from live client `state` and print the ladder.
   Returns true if a ladder was printed, nil/false otherwise (caller may fall
   back to a bare phase line)."
  [state run my-side]
  (let [current-ice (core/current-run-ice state)
        server-key  (last (:server run))
        ice-count   (count (get-in state [:game-state :corp :servers
                                          (keyword server-key) :ices]))
        lines (run-phase-ladder-lines
               {:phase       (:phase run)
                :server-name (run-server-display server-key)
                :position    (:position run)
                :ice-count   (when (pos? ice-count) ice-count)
                ;; Only name a rezzed ICE — fog of war hides unrezzed identities.
                :ice-name    (when (and current-ice (:rezzed current-ice))
                               (:title current-ice))})]
    (when (seq lines)
      (doseq [line lines] (println line))
      true)))

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
          (println (str "  Card: " (:title card)
                        (when (:type card) (str " (" (:type card) ")")))))
        (when has-choices
          (println "  Choices:")
          (doseq [[idx choice] (map-indexed vector (:choices prompt))]
            (println (str "    " idx ". " (core/format-choice choice)))))
        (when has-selectable
          (let [selectable (:selectable prompt)
                prompt-msg (or (:msg prompt) "")
                ;; Detect if this is a multi-select prompt
                ;; Pattern 1: "choose N cards" in message
                choose-n-match (re-find #"[Cc]hoose (\d+) cards?" prompt-msg)
                ;; Pattern 2: Discard prompt - check hand vs max
                gs (state/get-game-state)
                hand-size (count (get-in gs [side :hand]))
                max-hand-size (get-in gs [side :hand-size :total] 5)
                is-discard? (str/includes? (str/lower-case prompt-msg) "discard")
                cards-to-discard (when is-discard? (max 0 (- hand-size max-hand-size)))
                cards-required (cond
                                 choose-n-match (Integer/parseInt (second choose-n-match))
                                 (and is-discard? (pos? cards-to-discard)) cards-to-discard
                                 :else nil)]
            ;; Show multi-select warning if applicable
            (if cards-required
              (do
                (println (str "  ⚠️  MULTI-SELECT: Choose " cards-required " card(s)"))
                (println "     Use: multi-choose <card1> <card2> ... OR multi-choose 0 1 2 ..."))
              (println "  Selectable cards: (Use choose-card to select by index)"))
            ;; Render via the shared helper: pickable cards with their true
            ;; indices + a single warning line for phantom (unresolvable) CIDs,
            ;; instead of dumping raw "CID: <uuid>" lines that confuse indexing.
            (let [{:keys [pickable phantom] :as parts} (core/resolve-selectable selectable)]
              (println (str "  Available ("
                            (if (seq phantom)
                              (str (count pickable) " selectable; " (count phantom) " hidden/unselectable")
                              (str (count selectable) " cards"))
                            "):"))
              (core/print-selectable! parts "    "))))
        ;; Handle paid ability windows / passive prompts
        (when (and (not has-choices) (not has-selectable))
          (let [run (get-in state [:game-state :run])
                run-phase (when run (:phase run))
                my-side (clojure.string/lower-case (or side "runner"))]
            (if run-phase
              ;; Show run phase context
              (do
                ;; Explicit YOU-ARE-HERE ladder (forum [099]); falls back to the
                ;; bare phase line for any phase the ladder doesn't model.
                (when-not (print-run-phase-ladder! state run my-side)
                  (println (str "  Run Phase: " run-phase)))
                ;; During encounter-ice, show ICE and breaker info
                (when (= run-phase "encounter-ice")
                  (show-encounter-ice-info state run my-side))
                ;; Movement / approach-server are both-must-pass priority windows;
                ;; spell out whose move it is and what continuing does, so neither
                ;; seat assumes the run advances on its own (the symmetric
                ;; 'pass priority' text deadlocked cross-model play).
                ;; `initiation` is a both-must-pass window too (issue #31): the
                ;; Runner routes through the already-passed-aware hint so a passed
                ;; Runner is told to wait / jack-out rather than re-`continue` (a
                ;; no-op loop). The Corp keeps its rich rez/decline guidance below.
                (if (contains? (if (= my-side "runner")
                                 #{"initiation" "movement" "approach-server"}
                                 #{"movement" "approach-server"})
                               run-phase)
                  (doseq [line (run-priority-hint-lines run my-side)]
                    (println line))
                  ;; Other run windows (initiation / approach-ice / encounter).
                  ;; "Use continue to pass priority" alone reads to the Corp as
                  ;; "that's the only thing" — but continuing here is a CHOICE to
                  ;; decline action. Spell out the rez / paid-ability options so a
                  ;; passing Corp knows it passed up something, not that it was
                  ;; forced. (re forum [093] — Corp told it can continue, not that
                  ;; it has other options when nothing looks interesting.)
                  (if (= my-side "corp")
                    (do
                      (println "    → 'continue' passes priority here (you DECLINE to act this window).")
                      (println "    → Other options: rez a card / fire a paid ability if useful.")
                      (when (= run-phase "approach-ice")
                        (println "    → This is the ICE rez window: continue --rez <ice> to rez, or --no-rez to decline.")))
                    (println "    → Use 'continue' to pass priority (advance the run)."))))
              ;; Not in a run
              (do
                (println "  Action: Paid ability window")
                (println "    → No choices required")
                (println "    → Use 'continue' command to pass priority"))))))
      ;; No prompt object. "No active prompt" alone is technically true but
      ;; misleads at a turn boundary (a reader concludes the game isn't waiting on
      ;; them when it's actually their turn to start). Append the turn-aware next
      ;; action so `prompt` reliably answers "what do I do now?".
      (let [ts (state/get-turn-status)
            side (:side @state/client-state)
            next-lc (clojure.string/lower-case (or (:next-player ts) ""))
            my-lc (clojure.string/lower-case (or side ""))]
        (println "No active prompt — no decision is pending for you right now.")
        (cond
          (:game-over? ts)
          (println (format "🏁 Game over — %s." (:status-text ts)))

          (:in-run? ts)
          (println (format "🏃 A run is in progress on %s → use 'monitor-run' / 'continue'."
                           (or (:run-server ts) "?")))

          ;; Turn boundary, my turn to start.
          (and (:waiting-to-start? ts) (= next-lc my-lc))
          (println "🟢 It's YOUR turn but it hasn't started yet (0 clicks) → use 'start-turn'.")

          ;; Turn boundary, opponent starts next.
          (:waiting-to-start? ts)
          (println (format "⏳ Waiting for %s to start their turn → use 'wait'." (:next-player ts)))

          (not (:my-turn? ts))
          (println (format "⏳ It's %s's turn, not yours → use 'wait'."
                           (or (:whose-turn ts) "the opponent")))

          (:can-act? ts)
          (println "✅ It's your turn with clicks in hand → act (see 'list-playables').")

          :else
          (println (format "ℹ️  %s" (:status-text ts))))))))

(defn show-snapshot
  "One-shot per-decision snapshot: compact status, the current prompt (only when
   one is open), compact board, hand, the last N log lines, and the state cursor
   -- i.e. the whole status/prompt/board/hand/log/get-cursor read-loop collapsed
   into a single call (one round-trip, one model-facing turn). Read-only; N
   defaults to 5 log lines. The trailing `cursor=<n>` is the value to pass to
   `wait --since` before acting."
  ([] (show-snapshot 5))
  ([n]
   (show-status-compact)
   (when (state/get-prompt)
     (println)
     (show-prompt-detailed))
   (println)
   (show-board-compact)
   (println)
   (show-hand)
   (println)
   (show-log-compact n)
   (println (str "cursor=" (core/get-cursor)))
   nil))

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
        (println "Type:" (str (:type card)
                              (when (:subtype card) (str " - " (:subtype card)))))
        (println "Side:" (:side card))
        (when (:faction card)
          (println "Faction:" (:faction card)))
        (when-let [cost (:cost card)]
          (println "Cost:" cost))
        (when-let [strength (:strength card)]
          (println "Strength:" strength))
        (when-let [trash (:trash card)]
          (println "Trash Cost:" trash))
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
               (println "Type:" (str (:type card)
                                     (when (:subtype card) (str " - " (:subtype card)))))
               (when-let [cost (:cost card)] (println "Cost:" cost))
               (when-let [strength (:strength card)] (println "Strength:" strength))
               (when-let [trash (:trash card)] (println "Trash Cost:" trash))
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

    ;; Check for active prompts. An active prompt BLOCKS the actions listed
    ;; below — the engine may still mark cards :playable, but you cannot play
    ;; them until the prompt is resolved (or, for a `waiting` prompt, until the
    ;; opponent acts). Say so plainly: the GPT-5.5 seat read the playable list as
    ;; actionable during a pending-mulligan wait and burned turns trying to act.
    (let [prompt-state (:prompt-state my-state)
          prompt (first (:prompt my-state))
          active-prompt (or prompt-state prompt)
          blocked? (boolean active-prompt)
          waiting? (state/waiting-prompt-type? (:prompt-type active-prompt))
          msg (or (:msg prompt-state) (:msg prompt))
          ;; A passive run prompt (run in progress, no choices/selectable) is a
          ;; priority / paid-ability window — the next action is `continue`/`rez`,
          ;; NOT `choose`. The old "answer this prompt FIRST → choose <N>" advice
          ;; here was flatly wrong for a run window (same contradictory-guidance
          ;; family as the diagnose-blocker fix): the Corp can't `choose` an
          ;; opponent's run, and reads "0 playables / choose <N>" as a dead end.
          run (:run gs)
          run-phase (:phase run)
          run-window? (and run active-prompt
                           (not (seq (:choices active-prompt)))
                           (not (seq (:selectable active-prompt))))]
      (when blocked?
        (println (format "\n⚠️  Active Prompt: %s" (or msg "(no message)")))
        (cond
          waiting?
          (do
            (println "   ⛔ You are WAITING on the opponent — the actions below are NOT playable right now.")
            (println "   Use 'wait' until this clears; don't try to act through it."))

          run-window?
          (do
            (println (format "   ⏸️  This is a RUN priority window%s — NOT a choose-prompt; 'choose' does not apply."
                             (if run-phase (str " (phase: " run-phase ")") "")))
            (if (= side :corp)
              (do
                (println "   Your options here: rez a card / fire a paid ability if useful, or 'continue' to pass priority (decline to act).")
                (println "   • ICE rez happens at the approach-ice window — there: continue --rez <ice>  (or --no-rez to decline).")
                (println "   • 'monitor-run' auto-passes boring windows and stops only when a real decision is owed."))
              (do
                (println "   Your options here: 'continue' to advance the run (pass priority), or fire a break / paid ability if useful.")
                (println "   • 'monitor-run' or 'continue --full-break' can auto-handle routine windows."))))

          :else
          (do
            (println "   ⛔ Answer this prompt FIRST — the actions below are blocked until you resolve it.")
            (println "   Use 'prompt' to see choices, then 'choose <N>' / 'choose-card <N>' / 'choose-value <text>'.")))))

    ;; Playable hand cards
    (let [blocked? (boolean (or (:prompt-state my-state) (first (:prompt my-state))))
          playable-cards (filter :playable hand)]
      (when (seq playable-cards)
        (println (if blocked?
                   "\n📋 Hand Cards (⛔ blocked by active prompt — see above):"
                   "\n📋 Hand Cards:"))
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

(defn show-blocker-diagnosis
  "Read-only diagnosis of why you can/can't act right now and the ONE next
   command to run. Safe — never mutates state. Answers the GPT-5.5 seat's ask
   for a 'diagnose-blocker' that names who owns the blocking prompt and whether
   it's actionable, instead of guessing from contradictory-looking status lines."
  []
  (let [ts (state/get-turn-status)
        prompt (state/get-prompt)
        ptype (:prompt-type prompt)
        waiting? (state/waiting-prompt-type? ptype)
        side (:side @state/client-state)
        side-kw (when side (keyword (clojure.string/lower-case side)))
        my-clicks (get-in @state/client-state [:game-state side-kw :click])
        next-lc (clojure.string/lower-case (or (:next-player ts) ""))
        run (get-in @state/client-state [:game-state :run])
        run-phase (:phase run)
        my-side-lc (when side (clojure.string/lower-case side))
        ;; A prompt is resolvable-via-choose only if it carries actual choices or
        ;; selectable cards. A passive run priority / paid-ability window has a
        ;; prompt object (sometimes a non-"waiting" :prompt-type) but NO options —
        ;; the next action there is `continue`, not `choose`.
        has-options? (boolean (or (seq (:choices prompt)) (seq (:selectable prompt))))]
    (println "\n=== BLOCKER DIAGNOSIS ===")
    (println (format "Side: %s | Turn %s, active: %s | your clicks: %s"
                     (or side "?") (:turn-number ts) (or (:whose-turn ts) "?")
                     (if (nil? my-clicks) "?" my-clicks)))
    (cond
      (:game-over? ts)
      (println (format "🏁 Game over — %s. Nothing to do." (:status-text ts)))

      ;; Actionable prompt with real options owned by us — resolve via choose.
      (and prompt (not waiting?) has-options?)
      (do
        (println (format "📋 You have an ACTIONABLE prompt [%s]: %s" ptype (:msg prompt)))
        (println "   → Owner: YOU. Resolve it before any other action.")
        (println "   → Use: prompt (see choices), then choose <N> / choose-card <N> / choose-value \"<text>\""))

      ;; Passive prompt during a run = a priority / paid-ability window. The next
      ;; action is `continue`, NOT `choose`. This is the branch that used to be
      ;; missing: a non-"waiting" passive run prompt (e.g. the run-initiation
      ;; "Waiting for Corp paid abilities" window) fell through to the actionable
      ;; branch above and told the seat to `choose`, contradicting what `prompt`
      ;; and `continue` say. Now diagnose-blocker agrees with them. (backlog #4)
      (and (:in-run? ts) prompt (not waiting?))
      (do
        (println (format "⏸️  Run priority / paid-ability window%s: %s"
                         (if run-phase (str " (" run-phase ")") "") (:msg prompt)))
        (println "   → Owner: this is a both-must-pass priority window, not a choose prompt.")
        ;; `initiation` is a both-must-pass window too (engine: continue :initiation
        ;; needs BOTH sides), but only the Runner gets the already-passed-aware hint
        ;; here — once it has passed, "use continue" is a no-op loop (issue #31 / g3).
        ;; The Corp keeps the generic continue/monitor-run steer at initiation (it
        ;; may still want to rez/fire a paid ability there).
        (if (contains? (if (= my-side-lc "runner")
                         #{"initiation" "movement" "approach-server"}
                         #{"movement" "approach-server"})
                       run-phase)
          (doseq [line (run-priority-hint-lines run my-side-lc)]
            (println line))
          (println "   → Use: continue (to pass priority) — or monitor-run to participate in the run.")))

      ;; Waiting prompt — blocked on the opponent, NOT a stall.
      (and prompt waiting?)
      (do
        (println (format "⛔ You have a WAITING prompt: %s" (:msg prompt)))
        (println "   → Owner: OPPONENT. You are blocked until they act — this is NOT a stall.")
        (if (re-find #"(?i)mulligan|keep hand" (str (:msg prompt)))
          (println "   → They're still on their opening mulligan. Use: wait, then start-turn once it clears.")
          (println "   → Use: wait --since <cursor>")))

      ;; Active run, no prompt for us yet.
      (:in-run? ts)
      (do
        (println (format "🏃 A run is in progress on %s." (or (:run-server ts) "?")))
        (println "   → Use: monitor-run (or continue-run) to participate / advance it."))

      ;; Turn boundary — my turn but not started (0 clicks).
      (and (:waiting-to-start? ts) (= next-lc (clojure.string/lower-case (or side ""))))
      (do
        (println "🟢 It's your turn but it has NOT started yet (0 clicks until you do).")
        (println "   → Use: start-turn"))

      ;; Boundary, but the other player starts next.
      (:waiting-to-start? ts)
      (do
        (println (format "⏳ Waiting for %s to start their turn." (:next-player ts)))
        (println "   → Use: wait --since <cursor>"))

      ;; Not my turn.
      (not (:my-turn? ts))
      (do
        (println (format "⏳ It's %s's turn, not yours." (or (:whose-turn ts) "the opponent")))
        (println "   → Use: wait --since <cursor>"))

      ;; My turn with clicks, nothing blocking.
      (:can-act? ts)
      (println "✅ Nothing is blocking you — your turn, clicks in hand. Act (see list-playables).")

      :else
      (println (format "ℹ️  %s" (:status-text ts))))
    (println (str "cursor=" (core/get-cursor)))
    nil))

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
  (println "  (wait-for-relevant-diff)           - Block until anything we care about happens")
  (println "  (wait-for-relevant-diff 120)       - Custom timeout (default 300s)")
  (println "  (wait-for-relevant-diff {:since N}) - Race-free wait from a cursor")
  (println "\nWorkflows:")
  (println "  (simple-corp-turn)                 - 3x credit, end")
  (println "  (simple-runner-turn)               - 4x credit, end")
  (println "\nDebugging:")
  (println "  (inspect-state)                    - Show raw state")
  (println "  (inspect-prompt)                   - Show raw prompt")
  (println "  (help)                             - This help\n"))
