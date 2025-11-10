(ns ai-actions
  "High-level AI player actions - simple API for common game operations"
  (:require [ai-websocket-client-v2 :as ws]
            [jinteki.cards :refer [all-cards]]
            [clj-http.client :as http]
            [cheshire.core :as json]))

;; Forward declarations
(declare find-installed-corp-card)

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn side=
  "Case-insensitive side comparison
   Handles that client-state stores side as lowercase 'corp'/'runner'

   Usage: (side= \"Corp\" side)
          (side= \"Runner\" side)"
  [expected-side actual-side]
  (= (clojure.string/lower-case expected-side)
     (clojure.string/lower-case (or actual-side ""))))

;; ============================================================================
;; Card Database Management
;; ============================================================================

(defn load-cards-from-api!
  "Fetch card database from server API and populate all-cards atom
   Only fetches once - subsequent calls are no-ops if cards already loaded"
  []
  (when (empty? @all-cards)
    (try
      (println "📦 Fetching card database from server API...")
      (let [response (http/get "http://localhost:1042/data/cards"
                              {:as :json
                               :socket-timeout 10000
                               :connection-timeout 5000})
            cards (:body response)
            cards-map (into {} (map (juxt :title identity)) cards)]
        (reset! all-cards cards-map)
        (println "✅ Loaded" (count cards-map) "cards from API"))
      (catch Exception e
        (println "❌ Failed to load cards from API:" (.getMessage e))
        (println "   Make sure the game server is running on localhost:1042")))))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn get-log-size
  "Get current size of the game log"
  []
  (let [state @ws/client-state
        log (get-in state [:game-state :log])]
    (count log)))

(defn verify-new-log-entry
  "Check if a new log entry was added (log size increased)
   Waits up to max-wait-ms for a new entry to appear
   initial-size: the log size before the action was sent"
  [initial-size max-wait-ms]
  (let [deadline (+ (System/currentTimeMillis) max-wait-ms)]
    ;; Poll until log size increases or timeout
    (loop []
      (let [current-size (get-log-size)]
        (if (> current-size initial-size)
          true
          (if (< (System/currentTimeMillis) deadline)
            (do
              (Thread/sleep 200)
              (recur))
            false))))))

(defn verify-action-in-log
  "Check if a card action appears in recent game log entries
   Returns true if card name found OR if log size increased, false otherwise
   Waits up to max-wait-ms for the log entry to appear

   NOTE: For Corp hidden cards, card names don't appear in log (shown as 'a card')
         so we check for ANY new log entry as confirmation"
  [card-name max-wait-ms]
  (let [initial-size (get-log-size)
        deadline (+ (System/currentTimeMillis) max-wait-ms)
        check-log (fn []
                   (let [state @ws/client-state
                         log (get-in state [:game-state :log])
                         current-size (count log)]
                     ;; Success if: log grew OR card name appears in recent entries
                     (or (> current-size initial-size)
                         (let [recent-log (take-last 5 log)]
                           (some #(when (string? (:text %))
                                   (clojure.string/includes? (:text %) card-name))
                                 recent-log)))))]
    ;; Poll until we find it or timeout
    (loop []
      (if (check-log)
        true
        (if (< (System/currentTimeMillis) deadline)
          (do
            (Thread/sleep 200)
            (recur))
          false)))))

(defn find-card-in-hand
  "Find card in hand by name or index
   Returns card object or nil if not found"
  [name-or-index]
  (let [state @ws/client-state
        side (:side state)
        hand (get-in state [:game-state (keyword side) :hand])]
    (cond
      (number? name-or-index)
      (nth hand name-or-index nil)

      (string? name-or-index)
      (first (filter #(= name-or-index (:title %)) hand))

      :else nil)))

(defn create-card-ref
  "Create minimal card reference for server commands"
  [card]
  {:cid (:cid card)
   :zone (:zone card)
   :side (:side card)
   :type (:type card)})

(defn show-before-after
  "Display before/after state change"
  [label before after]
  (println (str label ": " before " → " after)))

(defn show-turn-indicator
  "Display turn status indicator after command execution"
  []
  (let [status (ws/get-turn-status)
        emoji (:status-emoji status)
        text (:status-text status)
        turn-num (:turn-number status)
        in-run (:in-run? status)
        run-server (:run-server status)
        clicks (ws/my-clicks)]
    (if in-run
      (println (str emoji " " text " | In run on " run-server))
      (if (:can-act? status)
        (println (str emoji " " text " - " clicks " clicks remaining"))
        (println (str emoji " " text))))))

(defn capture-state-snapshot
  "Capture current game state for before/after comparison
   Returns map with key state values"
  []
  (let [state @ws/client-state
        side (keyword (:side state))
        gs (:game-state state)
        runner-state (:runner gs)
        corp-state (:corp gs)
        rig (:rig runner-state)
        servers (:servers corp-state)]
    {:credits (get-in gs [side :credit])
     :clicks (get-in gs [side :click])
     :hand-size (count (get-in gs [side :hand]))
     :deck-size (count (get-in gs [side :deck]))
     :discard-size (count (get-in gs [side :discard]))
     :installed-count (if (= side :runner)
                       (+ (count (:program rig))
                          (count (:hardware rig))
                          (count (:resource rig)))
                       ;; Corp: count all content + ICE across servers
                       (reduce + (map #(+ (count (:content %))
                                         (count (:ices %)))
                                     (vals servers))))}))

(defn show-state-diff
  "Display state changes between two snapshots
   Compact mode shows single line, detailed shows multi-line"
  ([before after] (show-state-diff before after false))
  ([before after detailed?]
   (let [credit-diff (- (:credits after) (:credits before))
         click-diff (- (:clicks after) (:clicks before))
         hand-diff (- (:hand-size after) (:hand-size before))
         installed-diff (- (:installed-count after) (:installed-count before))
         deck-diff (- (:deck-size after) (:deck-size before))
         discard-diff (- (:discard-size after) (:discard-size before))]

     (if detailed?
       ;; Detailed mode: multi-line
       (do
         (when (not= credit-diff 0)
           (println (str "💰 Credits: " (:credits before) " → " (:credits after))))
         (when (not= click-diff 0)
           (println (str "⏱️  Clicks: " (:clicks before) " → " (:clicks after))))
         (when (not= hand-diff 0)
           (println (str "🃏 Hand: " (:hand-size before) " → " (:hand-size after) " cards")))
         (when (not= installed-diff 0)
           (println (str "📊 Installed: " (:installed-count before) " → " (:installed-count after))))
         (when (not= deck-diff 0)
           (println (str "📚 Deck: " (:deck-size before) " → " (:deck-size after))))
         (when (not= discard-diff 0)
           (println (str "🗑️  Discard: " (:discard-size before) " → " (:discard-size after)))))

       ;; Compact mode: single line
       (let [changes (filter identity
                            [(when (not= credit-diff 0)
                               (str "💰 " (:credits before) "→" (:credits after)))
                             (when (not= click-diff 0)
                               (str "⏱️ " (:clicks before) "→" (:clicks after)))
                             (when (not= hand-diff 0)
                               (str "🃏 " (:hand-size before) "→" (:hand-size after)))
                             (when (not= installed-diff 0)
                               (str "📊 " (:installed-count before) "→" (:installed-count after)))])]
         (when (seq changes)
           (println (clojure.string/join "  " changes))))))))

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
     (println "Creating lobby...")
     (ws/create-lobby! options)
     (Thread/sleep 2000)
     (println "✅ Lobby creation request sent")
     (println "Check lobby list with: (list-lobbies)"))))

(defn list-lobbies
  "Request and display the list of available games"
  []
  (println "Requesting lobby list...")
  (ws/request-lobby-list!)
  (Thread/sleep 1000)
  (ws/show-games))

;; ============================================================================
;; Game Connection
;; ============================================================================

(defn connect-game!
  "Join a game by ID
   Usage: (connect-game! \"game-uuid\" \"Corp\")
          (connect-game! \"game-uuid\") ; defaults to Corp"
  ([gameid] (connect-game! gameid "Corp"))
  ([gameid side]
   (println "Joining game" gameid "as" side "...")
   ;; Convert string UUID to java.util.UUID if needed
   (let [uuid (if (string? gameid)
                (java.util.UUID/fromString gameid)
                gameid)]
     (ws/join-game! {:gameid uuid :side side}))
   (Thread/sleep 2000)
   (if (ws/in-game?)
     (do
       (println "✅ Joined game successfully")
       (ws/show-status))
     (println "❌ Failed to join game"))))

(defn resync-game!
  "Rejoin an already-started game by requesting full state resync
   Usage: (resync-game! \"game-uuid\")"
  [gameid]
  (ws/resync-game! gameid)
  (Thread/sleep 2000)
  (if (ws/in-game?)
    (do
      (println "✅ Game state resynced successfully")
      (ws/show-status))
    (println "❌ Failed to resync game state")))

(defn send-chat!
  "Send a chat message to the game
   Usage: (send-chat! \"Hello, world!\")"
  [message]
  (let [state (ws/get-game-state)
        gameid (:gameid state)]
    (if gameid
      (do
        (ws/send-message! :game/say
          {:gameid (if (string? gameid)
                     (java.util.UUID/fromString gameid)
                     gameid)
           :msg message})
        (println "💬 Sent chat message"))
      (println "❌ Not in a game"))))

;; ============================================================================
;; Status & Information
;; ============================================================================

(defn status
  "Show current game status"
  []
  (ws/show-status))

(defn show-board
  "Display full game board: all servers with ICE, Corp installed cards, Runner rig"
  []
  (let [state @ws/client-state
        gs (:game-state state)
        corp-servers (:servers (:corp gs))
        runner-rig (get-in gs [:runner :rig])
        corp-deck (get-in gs [:corp :deck])
        corp-discard (get-in gs [:corp :discard])
        runner-deck (get-in gs [:runner :deck])
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
                    title (:title ice)
                    subtype (or (first (clojure.string/split (or (:subtype ice) "") #" - ")) "?")
                    status-icon (if rezzed "🔴" "⚪")]
                (println (str "  ICE #" idx ": " status-icon " "
                             (if rezzed title "Unrezzed ICE")
                             (when rezzed (str " (" subtype ")"))))))
            (println "  (No ICE)"))

          ;; Show Content (assets/agendas)
          (when (seq content-list)
            (let [rezzed-content (filter :rezzed content-list)
                  unrezzed-count (- (count content-list) (count rezzed-content))]
              (doseq [card rezzed-content]
                (println (str "  Content: 🔴 " (:title card) " (" (:type card) ")")))
              (when (> unrezzed-count 0)
                (println (str "  Content: " unrezzed-count " unrezzed card(s)"))))))))

    ;; Runner Rig
    (println "\n--- RUNNER RIG ---")
    (let [programs (:program runner-rig)
          hardware (:hardware runner-rig)
          resources (:resource runner-rig)]
      (if (seq programs)
        (do
          (println "\n💾 Programs:")
          (doseq [prog programs]
            (println (str "  • " (:title prog)
                         (when-let [strength (:current-strength prog)] (str " (str: " strength ")"))))))
        (println "\n💾 Programs: (none)"))

      (if (seq hardware)
        (do
          (println "\n🔧 Hardware:")
          (doseq [hw hardware]
            (println (str "  • " (:title hw)))))
        (println "🔧 Hardware: (none)"))

      (if (seq resources)
        (do
          (println "\n📦 Resources:")
          (doseq [res resources]
            (println (str "  • " (:title res)))))
        (println "📦 Resources: (none)")))

    ;; Deck/Discard counts
    (println "\n--- DECK STATUS ---")
    (println (str "Corp Deck: " (count corp-deck) " | Discard: " (count corp-discard)))
    (println (str "Runner Deck: " (count runner-deck) " | Discard: " (count runner-discard)))

    (println (clojure.string/join "" (repeat 70 "=")))
    nil))

(defn show-log
  "Display game log (natural language event history)"
  ([] (ws/show-game-log 20))
  ([n] (ws/show-game-log n)))

(defn show-prompt
  "Display current prompt"
  []
  (ws/show-prompt))

(defn hand
  "Show my hand"
  []
  (let [hand (ws/my-hand)]
    (println "\n=== MY HAND ===" (count hand) "cards ===")
    (doseq [[idx card] (map-indexed vector hand)]
      (println (format "  %d. %s [%s]" idx (:title card) (:type card))))
    hand))

(defn show-hand
  "Show hand using side-aware state access"
  []
  (let [state @ws/client-state
        side (:side state)
        hand (get-in state [:game-state (keyword side) :hand])]
    (if hand
      (do
        (println (str "\n🃏 " (clojure.string/capitalize side) " Hand:"))
        (doseq [[idx card] (map-indexed vector hand)]
          (println (str "  " idx ". " (:title card) " [" (:type card) "]"))))
      (println "No hand data available"))))

(defn show-credits
  "Show current credits (side-aware)"
  []
  (let [state @ws/client-state
        side (:side state)
        credits (get-in state [:game-state (keyword side) :credit])]
    (println "💰 Credits:" credits)))

(defn show-clicks
  "Show remaining clicks (side-aware)"
  []
  (let [state @ws/client-state
        side (:side state)
        clicks (get-in state [:game-state (keyword side) :click])]
    (println "⏱️  Clicks:" clicks)))

(defn show-archives
  "Show Corp's Archives (discard pile) with faceup/facedown counts"
  []
  (let [state @ws/client-state
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

(defn- format-choice
  "Format a choice for display, handling different prompt formats"
  [choice]
  (cond
    ;; Map with :value key (most common)
    (and (map? choice) (:value choice))
    (:value choice)

    ;; Map without :value - try :label or show keys
    (map? choice)
    (or (:label choice)
        (:title choice)
        (str "Option with keys: " (keys choice)))

    ;; String or number - show as-is
    :else
    (str choice)))

(defn show-prompt-detailed
  "Show current prompt with detailed choices"
  []
  (let [state @ws/client-state
        side (:side state)
        prompt (get-in state [:game-state (keyword side) :prompt-state])]
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
            (println (str "    " idx ". " (format-choice choice)))))
        (when has-selectable
          (println "  Selectable cards:" (count (:selectable prompt))
                   "- Use choose-card! to select by index"))
        ;; Handle paid ability windows / passive prompts
        (when (and (not has-choices) (not has-selectable))
          (println "  Action: Paid ability window")
          (println "    → No choices required")
          (println "    → Use 'continue' command to pass priority")))
      (println "No active prompt"))))

(defn show-card-text
  "Display full card information including text, cost, and abilities
   Usage: (show-card-text \"Sure Gamble\")
          (show-card-text \"Tithe\")"
  [card-name]
  ;; Auto-load cards if not already loaded
  (load-cards-from-api!)

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
   (load-cards-from-api!)

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
   (let [hand (ws/my-hand)
         card-names (map :title hand)]
     (if (empty? card-names)
       (println "🃏 Hand is empty")
       (show-cards card-names full?)))))

;; ============================================================================
;; Basic Actions
;; ============================================================================

(defn start-turn!
  "Start your turn (gains clicks, Corp draws mandatory card)"
  []
  (let [state @ws/client-state
        gameid (:gameid state)]
    (ws/send-message! :game/action
                      {:gameid (if (string? gameid)
                                (java.util.UUID/fromString gameid)
                                gameid)
                       :command "start-turn"
                       :args nil})
    (Thread/sleep 2000)
    (let [state @ws/client-state
          side (:side state)
          clicks (get-in state [:game-state (keyword side) :click])
          credits (get-in state [:game-state (keyword side) :credit])]
      (println "✅ Turn started!")
      (println "   Clicks:" clicks)
      (println "   Credits:" credits)
      (show-turn-indicator))))

(defn indicate-action!
  "Signal you want to use a paid ability (pauses game for priority window)"
  []
  (let [state @ws/client-state
        gameid (:gameid state)]
    (ws/send-message! :game/action
                      {:gameid (if (string? gameid)
                                (java.util.UUID/fromString gameid)
                                gameid)
                       :command "indicate-action"
                       :args nil})
    (println "✅ Indicated paid ability")))

(defn take-credit!
  "Click for credit (shows before/after)"
  []
  (let [state @ws/client-state
        side (:side state)
        before-credits (get-in state [:game-state (keyword side) :credit])
        before-clicks (get-in state [:game-state (keyword side) :click])
        gameid (:gameid state)]
    (ws/send-message! :game/action
                      {:gameid (if (string? gameid)
                                (java.util.UUID/fromString gameid)
                                gameid)
                       :command "credit"
                       :args nil})
    (Thread/sleep 1500)
    (let [state @ws/client-state
          side (:side state)
          after-credits (get-in state [:game-state (keyword side) :credit])
          after-clicks (get-in state [:game-state (keyword side) :click])]
      (show-before-after "💰 Credits" before-credits after-credits)
      (show-before-after "⏱️  Clicks" before-clicks after-clicks)
      (show-turn-indicator))))

(defn draw-card!
  "Draw a card (shows before/after)"
  []
  (let [state @ws/client-state
        side (:side state)
        before-hand (count (get-in state [:game-state (keyword side) :hand]))
        before-clicks (get-in state [:game-state (keyword side) :click])
        gameid (:gameid state)]
    (ws/send-message! :game/action
                      {:gameid (if (string? gameid)
                                (java.util.UUID/fromString gameid)
                                gameid)
                       :command "draw"
                       :args nil})
    (Thread/sleep 1500)
    (let [state @ws/client-state
          side (:side state)
          after-hand (count (get-in state [:game-state (keyword side) :hand]))
          after-clicks (get-in state [:game-state (keyword side) :click])]
      (println (str "🃏 Hand: " before-hand " → " after-hand " cards"))
      (show-before-after "⏱️  Clicks" before-clicks after-clicks))))

(defn end-turn!
  "End turn (validates all clicks used)"
  []
  (let [state @ws/client-state
        side (:side state)
        clicks (get-in state [:game-state (keyword side) :click])
        gameid (:gameid state)]
    (if (> clicks 0)
      (do
        (println "⚠️  WARNING: You still have" clicks "click(s) remaining!")
        (println "   This is wasteful. Use all clicks before ending turn.")
        (println "   Proceeding anyway..."))
      (println "✅ All clicks used"))
    (ws/send-message! :game/action
                      {:gameid (if (string? gameid)
                                (java.util.UUID/fromString gameid)
                                gameid)
                       :command "end-turn"
                       :args nil})
    (Thread/sleep 2000)
    (println "🏁 Turn ended")
    (show-turn-indicator)))

;; Keep old function names for backwards compatibility
(defn take-credits []
  (take-credit!))

(defn draw-card []
  (draw-card!))

(defn end-turn []
  (end-turn!))

;; ============================================================================
;; Prompts & Choices
;; ============================================================================

(defn choose
  "Make a choice from current prompt
   Usage: (choose 0) ; choose first option
          (choose \"uuid\") ; choose by UUID"
  [choice]
  (let [prompt (ws/get-prompt)]
    (if prompt
      (do
        (println "Making choice:" choice)
        (ws/choose! choice)
        (Thread/sleep 500)
        (println "✅ Choice made"))
      (println "⚠️  No active prompt"))))

(defn choose-option!
  "Choose from prompt by index (side-aware)"
  [index]
  (let [state @ws/client-state
        side (:side state)
        gameid (:gameid state)
        prompt (get-in state [:game-state (keyword side) :prompt-state])
        choice (nth (:choices prompt) index nil)
        choice-uuid (:uuid choice)]
    (if choice-uuid
      (do
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "choice"
                           :args {:choice {:uuid choice-uuid}}})
        (Thread/sleep 2000)
        (println (str "✅ Chose: " (:value choice))))
      (println (str "❌ Invalid choice index: " index)))))

(defn choose-by-value!
  "Choose from prompt by matching value/label text (case-insensitive substring match).
   Usage: (choose-by-value! \"steal\") or (choose-by-value! \"keep\")"
  [value-text]
  (let [state @ws/client-state
        side (:side state)
        prompt (get-in state [:game-state (keyword side) :prompt-state])
        choices (:choices prompt)
        value-lower (clojure.string/lower-case (str value-text))
        ;; Find first choice whose value contains the search text
        matching-idx (first
                      (keep-indexed
                       (fn [idx choice]
                         (let [choice-val (or (:value choice) (:label choice) "")]
                           (when (clojure.string/includes?
                                  (clojure.string/lower-case (str choice-val))
                                  value-lower)
                             idx)))
                       choices))]
    (if matching-idx
      (do
        (println (str "🔍 Found match: \"" (:value (nth choices matching-idx)) "\" at index " matching-idx))
        (choose-option! matching-idx))
      (do
        (println (str "❌ No choice matching \"" value-text "\" found"))
        (println "Available choices:")
        (doseq [[idx choice] (map-indexed vector choices)]
          (println (str "  " idx ". " (format-choice choice))))))))

;; ============================================================================
;; Mulligan
;; ============================================================================

(defn keep-hand
  "Keep hand during mulligan"
  []
  (let [prompt (ws/get-prompt)]
    (if (and prompt (= "mulligan" (:prompt-type prompt)))
      ;; Mulligan prompts are just normal choice prompts
      ;; Option 0 is always "Keep", option 1 is always "Mulligan"
      (choose-option! 0)
      (println "⚠️  No mulligan prompt active"))))

(defn mulligan
  "Mulligan (redraw) hand"
  []
  (let [prompt (ws/get-prompt)]
    (if (and prompt (= "mulligan" (:prompt-type prompt)))
      ;; Mulligan prompts are just normal choice prompts
      ;; Option 0 is always "Keep", option 1 is always "Mulligan"
      (choose-option! 1)
      (println "⚠️  No mulligan prompt active"))))

;; ============================================================================
;; Card Actions
;; ============================================================================

(defn play-card!
  "Play a card from hand by name or index
   Usage: (play-card! \"Sure Gamble\")
          (play-card! 0)"
  [name-or-index]
  (let [card (find-card-in-hand name-or-index)]
    (if card
      (let [before-state (capture-state-snapshot)
            state @ws/client-state
            gameid (:gameid state)
            card-ref (create-card-ref card)
            card-title (:title card)]
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "play"
                           :args {:card card-ref}})
        ;; Wait and verify action appeared in log
        (if (verify-action-in-log card-title 3000)
          (do
            (println (str "✅ Played: " card-title))
            (let [after-state (capture-state-snapshot)]
              (show-state-diff before-state after-state false)))
          (println (str "⚠️  Sent play command for: " card-title " - but action not confirmed in game log (may have failed)")))
        (show-turn-indicator))
      (println (str "❌ Card not found in hand: " name-or-index)))))

(defn install-card!
  "Install a card from hand by name or index
   For Corp: must specify server location
   For Runner: server is optional (auto-installs to appropriate location)

   Server values:
   - Central servers: \"HQ\", \"R&D\", \"Archives\"
   - New remote: \"New remote\"
   - Existing remotes: \"Server 1\", \"Server 2\", etc.

   Usage: (install-card! \"Palisade\" \"HQ\")         ; Corp ICE on HQ
          (install-card! \"Urtica Cipher\" \"New remote\") ; Corp asset in new remote
          (install-card! 0 \"R&D\")                   ; Corp install by index
          (install-card! \"Unity\")                   ; Runner install"
  ([name-or-index]
   (install-card! name-or-index nil))
  ([name-or-index server]
   (let [card (find-card-in-hand name-or-index)]
     (if card
       (let [before-state (capture-state-snapshot)
             state @ws/client-state
             gameid (:gameid state)
             card-ref (create-card-ref card)
             card-title (:title card)
             ;; Both Corp and Runner use "play" command
             ;; Corp requires :server, Runner installs without :server arg
             args (if server
                   {:card card-ref :server server}
                   {:card card-ref})]
         (ws/send-message! :game/action
                           {:gameid (if (string? gameid)
                                     (java.util.UUID/fromString gameid)
                                     gameid)
                            :command "play"
                            :args args})
         ;; Wait and verify action appeared in log
         (if (verify-action-in-log card-title 3000)
           (do
             (if server
               (println (str "✅ Installed: " card-title " on " server))
               (println (str "✅ Installed: " card-title)))
             (let [after-state (capture-state-snapshot)]
               (show-state-diff before-state after-state false)))
           (println (str "⚠️  Sent install command for: " card-title " - but action not confirmed in game log (may have failed)")))
         (show-turn-indicator))
       (println (str "❌ Card not found in hand: " name-or-index))))))

(defn run!
  "Run on a server (Runner only)
   Server names must match game format exactly:
   - Central servers: \"HQ\", \"R&D\", \"Archives\"
   - Remote servers: \"remote1\", \"remote2\", etc.

   Usage: (run! \"HQ\")
          (run! \"R&D\")
          (run! \"Archives\")
          (run! \"remote1\")"
  [server]
  (let [state @ws/client-state
        gameid (:gameid state)]
    (ws/send-message! :game/action
                      {:gameid (if (string? gameid)
                                (java.util.UUID/fromString gameid)
                                gameid)
                       :command "run"
                       :args {:server server}})
    (Thread/sleep 2000)
    (println (str "🏃 Running on " server))))

;; ============================================================================
;; Card Abilities
;; ============================================================================

(defn find-installed-card
  "Find an installed card by title in the rig
   Searches programs, hardware, and resources"
  [card-name]
  (let [state @ws/client-state
        rig (get-in state [:game-state :runner :rig])
        all-installed (concat (:program rig) (:hardware rig) (:resource rig))]
    (first (filter #(= card-name (:title %)) all-installed))))

(defn show-card-abilities
  "Show available abilities for an installed card by name
   Works for both Runner and Corp cards
   Usage: (show-card-abilities \"Smartware Distributor\")
          (show-card-abilities \"Cleaver\")"
  [card-name]
  (let [state @ws/client-state
        side (:side state)
        ;; Find card in appropriate location
        card (if (= "Corp" side)
               (find-installed-corp-card card-name)
               (find-installed-card card-name))]
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

(defn use-ability!
  "Use an installed card's ability

   Usage: (use-ability! \"Smartware Distributor\" 0)
          (use-ability! \"Sure Gamble\" 1)"
  [card-name ability-index]
  (let [state @ws/client-state
        side (:side state)
        ;; Find card in appropriate location based on side
        card (if (side= "Corp" side)
               (find-installed-corp-card card-name)
               (find-installed-card card-name))]
    (if card
      (let [gameid (:gameid state)
            ;; Create card reference matching wire format
            card-ref {:cid (:cid card)
                     :zone (:zone card)
                     :side (:side card)
                     :type (:type card)}]
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "ability"
                           :args {:card card-ref
                                  :ability ability-index}})
        (Thread/sleep 1500)
        (println (str "✅ Used ability " ability-index " on " (:title card))))
      (println (str "❌ Card not found installed: " card-name)))))

(defn use-runner-ability!
  "Use a runner ability on a Corp card (e.g., bioroid click-to-break)
   Runner abilities are special abilities on Corp cards that the Runner can activate
   Most commonly used for bioroid ICE during encounters

   Usage: (use-runner-ability! \"Brân 1.0\" 0)
          During encounter, activates the bioroid's click-to-break ability"
  [card-name ability-index]
  (let [state @ws/client-state
        ;; Find the Corp card (usually ICE during encounter)
        card (find-installed-corp-card card-name)]
    (if card
      (let [gameid (:gameid state)
            ;; Create card reference matching wire format
            card-ref {:cid (:cid card)
                     :zone (:zone card)
                     :side (:side card)
                     :type (:type card)}]
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "runner-ability"
                           :args {:card card-ref
                                  :ability ability-index}})
        (Thread/sleep 1500)
        (println (str "✅ Used runner-ability " ability-index " on " (:title card))))
      (println (str "❌ Card not found: " card-name)))))

(defn find-installed-corp-card
  "Find an installed Corp card by title
   Searches all servers for ICE, assets, and upgrades"
  [card-name]
  (let [state @ws/client-state
        servers (get-in state [:game-state :corp :servers])
        ;; Get all ICE from all servers
        all-ice (mapcat :ices (vals servers))
        ;; Get all content (assets/upgrades) from all servers
        all-content (mapcat :content (vals servers))
        all-installed (concat all-ice all-content)]
    (first (filter #(= card-name (:title %)) all-installed))))

(defn trash-installed!
  "Trash an installed card (Corp: ICE/asset/upgrade, Runner: rig card)

   Usage: (trash-installed! \"Palisade\")
          (trash-installed! \"Daily Casts\")"
  [card-name]
  (let [state @ws/client-state
        side (:side state)
        ;; Find card in appropriate location based on side
        card (if (= "Corp" side)
               (find-installed-corp-card card-name)
               (find-installed-card card-name))]
    (if card
      (let [gameid (:gameid state)
            card-ref {:cid (:cid card)
                     :zone (:zone card)
                     :side (:side card)
                     :type (:type card)}]
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "trash"
                           :args {:card card-ref}})
        (Thread/sleep 1500)
        (println (str "✅ Trashed: " (:title card))))
      (println (str "❌ Card not found installed: " card-name)))))

(defn rez-card!
  "Rez an installed Corp card (ICE, asset, or upgrade)

   Usage: (rez-card! \"Prisec\")
          (rez-card! \"IP Block\")"
  [card-name]
  (let [state @ws/client-state
        side (:side state)]
    (if (not (side= "Corp" side))
      (println "❌ Only Corp can rez cards")
      (let [card (find-installed-corp-card card-name)]
        (if card
          (let [gameid (:gameid state)
                card-ref {:cid (:cid card)
                         :zone (:zone card)
                         :side (:side card)
                         :type (:type card)}]
            (ws/send-message! :game/action
                              {:gameid (if (string? gameid)
                                        (java.util.UUID/fromString gameid)
                                        gameid)
                               :command "rez"
                               :args {:card card-ref}})
            ;; Wait and verify action appeared in log
            (if (verify-action-in-log card-name 3000)
              (println (str "✅ Rezzed: " card-name))
              (println (str "⚠️  Sent rez command for: " card-name " - but action not confirmed in game log (may have failed)"))))
          (println (str "❌ Card not found installed: " card-name)))))))

(defn let-subs-fire!
  "Signal intent to let unbroken subroutines fire (Runner only)
   Sends a system message to indicate Runner is allowing subs to fire

   Usage: (let-subs-fire! \"Whitespace\")
          (let-subs-fire! \"IP Block\")"
  [ice-name]
  (let [state @ws/client-state
        side (:side state)
        gameid (:gameid state)]
    (if (not (side= "Runner" side))
      (println "❌ Only Runner can let subroutines fire")
      (do
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "system-msg"
                           :args {:msg (str "indicates to fire all unbroken subroutines on " ice-name)}})
        (Thread/sleep 1000)
        (println (str "✅ Signaled intent to let subs fire on: " ice-name))))))

(defn toggle-auto-no-action!
  "Toggle auto-pass priority during runs (Corp only)
   When enabled, automatically passes on all rez/paid ability windows
   Prompt changes to 'Stop Auto-passing Priority' when active

   Usage: (toggle-auto-no-action!)"
  []
  (let [state @ws/client-state
        side (:side state)
        gameid (:gameid state)]
    (if (not (side= "Corp" side))
      (println "❌ Only Corp can toggle auto-pass priority")
      (do
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "toggle-auto-no-action"
                           :args nil})
        (Thread/sleep 500)
        (println "✅ Toggled auto-pass priority")))))

(defn fire-unbroken-subs!
  "Fire unbroken subroutines on ICE (Corp only)
   Used during runs when Runner doesn't/can't break all subs

   Usage: (fire-unbroken-subs! \"Palisade\")
          (fire-unbroken-subs! \"IP Block\")"
  [ice-name]
  (let [state @ws/client-state
        side (:side state)]
    (if (not (side= "Corp" side))
      (println "❌ Only Corp can fire ICE subroutines")
      (let [card (find-installed-corp-card ice-name)]
        (if card
          (let [gameid (:gameid state)
                card-ref {:cid (:cid card)
                         :zone (:zone card)
                         :side (:side card)
                         :type (:type card)}]
            (ws/send-message! :game/action
                              {:gameid (if (string? gameid)
                                        (java.util.UUID/fromString gameid)
                                        gameid)
                               :command "unbroken-subroutines"
                               :args {:card card-ref}})
            (Thread/sleep 1500)
            (println (str "✅ Fired unbroken subroutines on: " (:title card))))
          (println (str "❌ ICE not found installed: " ice-name)))))))

(defn advance-card!
  "Advance an installed Corp card (agenda, ICE, or asset)
   Costs 1 click and 1 credit per advancement counter

   Usage: (advance-card! \"Project Vitruvius\")
          (advance-card! \"Oaktown Renovation\")"
  [card-name]
  (let [state @ws/client-state
        side (:side state)]
    (if (not (side= "Corp" side))
      (println "❌ Only Corp can advance cards")
      (let [card (find-installed-corp-card card-name)]
        (if card
          (let [gameid (:gameid state)
                card-ref {:cid (:cid card)
                         :zone (:zone card)
                         :side (:side card)
                         :type (:type card)}]
            (ws/send-message! :game/action
                              {:gameid (if (string? gameid)
                                        (java.util.UUID/fromString gameid)
                                        gameid)
                               :command "advance"
                               :args {:card card-ref}})
            ;; Wait and verify action appeared in log
            (if (verify-action-in-log card-name 3000)
              (println (str "✅ Advanced: " card-name))
              (println (str "⚠️  Sent advance command for: " card-name " - but action not confirmed in game log (may have failed)"))))
          (println (str "❌ Card not found installed: " card-name)))))))

(defn score-agenda!
  "Score an installed agenda (Corp only)
   Agenda must have enough advancement counters to be scored

   Usage: (score-agenda! \"Project Vitruvius\")
          (score-agenda! \"Send a Message\")"
  [card-name]
  (let [state @ws/client-state
        side (:side state)]
    (if (not (side= "Corp" side))
      (println "❌ Only Corp can score agendas")
      (let [card (find-installed-corp-card card-name)]
        (if card
          (if (= "Agenda" (:type card))
            (let [gameid (:gameid state)
                  card-ref {:cid (:cid card)
                           :zone (:zone card)
                           :side (:side card)
                           :type (:type card)}]
              (ws/send-message! :game/action
                                {:gameid (if (string? gameid)
                                          (java.util.UUID/fromString gameid)
                                          gameid)
                                 :command "score"
                                 :args {:card card-ref}})
              ;; Wait and verify action appeared in log (look for "score" or card name)
              (if (verify-action-in-log card-name 3000)
                (println (str "✅ Scored: " card-name))
                (println (str "⚠️  Sent score command for: " card-name " - but action not confirmed in game log (may have failed)"))))
            (println (str "❌ Card is not an Agenda: " (:title card) " (type: " (:type card) ")")))
          (println (str "❌ Card not found installed: " card-name)))))))

;; ============================================================================
;; Discard Handling
;; ============================================================================

(defn discard-to-hand-size!
  "Discard cards down to maximum hand size
   Auto-detects side and discards until at or below max hand size"
  []
  (let [state @ws/client-state
        side (keyword (:side state))
        discarded (ws/handle-discard-prompt! side)]
    (if (> discarded 0)
      (println (str "✅ Discarded " discarded " card(s)"))
      (println "No cards to discard"))))

(defn discard-specific-cards!
  "Discard specific cards by index positions

   Usage: (discard-specific-cards! [0 2 4])  ; Discard cards at indices 0, 2, 4"
  [indices]
  (let [state @ws/client-state
        side (keyword (:side state))
        gs (ws/get-game-state)
        prompt (get-in gs [side :prompt-state])
        hand (get-in gs [side :hand])]
    (if (and (= "select" (:prompt-type prompt))
             (seq indices))
      (let [cards-to-discard (map #(nth hand % nil) indices)
            valid-cards (filter some? cards-to-discard)]
        (println (format "Discarding %d specific cards by index..." (count valid-cards)))
        (doseq [card valid-cards]
          (println (format "  Discarding: %s" (:title card)))
          (ws/select-card! card (:eid prompt))
          (Thread/sleep 500))
        (println (format "✅ Discarded %d card(s)" (count valid-cards)))
        (count valid-cards))
      (do
        (println "❌ No discard prompt active or no indices provided")
        0))))

(defn auto-keep-mulligan
  "Automatically handle mulligan by keeping hand"
  []
  (println "Waiting for mulligan prompt...")
  (loop [checks 0]
    (when (< checks 20)
      (Thread/sleep 1000)
      (let [prompt (ws/get-prompt)]
        (if (and prompt (= "button" (:prompt-type prompt)))
          (do
            (println "Mulligan prompt detected")
            (keep-hand))
          (recur (inc checks))))))
  (println "Mulligan complete"))

;; ============================================================================
;; Wait Helpers
;; ============================================================================

(defn wait-for-prompt
  "Wait for a prompt to appear (up to max-seconds)
   Returns prompt or nil if timeout"
  [max-seconds]
  (println (format "Waiting for prompt (max %d seconds)..." max-seconds))
  (loop [checks 0]
    (if (< checks max-seconds)
      (if-let [prompt (ws/get-prompt)]
        (do
          (println "✅ Prompt detected")
          prompt)
        (do
          (Thread/sleep 1000)
          (recur (inc checks))))
      (do
        (println "⏱️  Timeout waiting for prompt")
        nil))))

(defn wait-for-diff
  "Wait for game state to change, return what changed
   Monitors game-state updates via WebSocket diffs
   Useful for waiting for opponent actions, run phases, etc.

   Usage: (wait-for-diff)                    ;; default 60s timeout, verbose
          (wait-for-diff 120)                ;; custom timeout seconds
          (wait-for-diff {:verbose false})   ;; quiet mode"
  ([]
   (wait-for-diff 60))
  ([timeout-or-opts]
   (let [opts (if (number? timeout-or-opts)
                {:timeout timeout-or-opts :verbose true}
                (merge {:timeout 60 :verbose true} timeout-or-opts))
         timeout-seconds (:timeout opts)
         initial-state @ws/client-state
         initial-log (get-in initial-state [:game-state :log])
         initial-log-count (count initial-log)
         deadline (+ (System/currentTimeMillis) (* timeout-seconds 1000))]

     (println (format "⏳ Waiting for game state change (timeout: %ds)..." timeout-seconds))

     (loop [checks 0]
       (Thread/sleep 500)
       (let [current-state @ws/client-state
             current-log (get-in current-state [:game-state :log])
             current-log-count (count current-log)
             new-entries (drop initial-log-count current-log)
             state-changed? (not= initial-state current-state)]

         (cond
           state-changed?
           (do
             (when (:verbose opts)
               (println "✅ Game state changed - recent actions:")
               (doseq [entry (take-last 3 new-entries)]
                 (println (format "  • %s" (:text entry)))))
             {:status :state-changed
              :new-log-entries new-entries
              :log-count {:before initial-log-count :after current-log-count}})

           (> (System/currentTimeMillis) deadline)
           (do
             (println "⏱️  Timeout waiting for state change")
             {:status :timeout})

           :else
           (recur (inc checks))))))))

(defn wait-for-log-past
  "Wait until log has entries AFTER the given text marker
   Useful for avoiding race conditions when opponent is mid-turn

   Usage: (wait-for-log-past \"Clamatius makes his mandatory start of turn draw\")
          (wait-for-log-past \"ending his turn\" 120)  ;; custom timeout"
  [marker-text & [timeout]]
  (let [timeout-seconds (or timeout 60)
        deadline (+ (System/currentTimeMillis) (* timeout-seconds 1000))]

    (println (format "⏳ Waiting for log entries past marker: \"%s\"" (subs marker-text 0 (min 50 (count marker-text)))))

    (loop []
      (Thread/sleep 500)
      (let [current-log (get-in @ws/client-state [:game-state :log])
            marker-idx (first (keep-indexed
                               #(when (clojure.string/includes? (:text %2) marker-text) %1)
                               current-log))
            entries-after (when marker-idx (drop (inc marker-idx) current-log))]

        (cond
          (and marker-idx (seq entries-after))
          (do
            (println (format "✅ Found %d new log entries:" (count entries-after)))
            (doseq [entry (take 5 entries-after)]
              (println (format "  • %s" (:text entry))))
            {:status :new-entries
             :entries entries-after})

          (> (System/currentTimeMillis) deadline)
          (do
            (println "⏱️  Timeout")
            {:status :timeout})

          :else
          (recur))))))

(defn wait-for-my-turn
  "Wait for it to be my turn (up to max-seconds)"
  [max-seconds]
  (println (format "Waiting for my turn (max %d seconds)..." max-seconds))
  (loop [checks 0]
    (if (< checks max-seconds)
      (if (ws/my-turn?)
        (do
          (println "✅ It's my turn!")
          true)
        (do
          (Thread/sleep 1000)
          (recur (inc checks))))
      (do
        (println "⏱️  Timeout waiting for turn")
        false))))

;; ============================================================================
;; High-Level Workflows
;; ============================================================================

(defn simple-corp-turn
  "Execute a simple Corp turn: click for credit 3 times, end turn"
  []
  (println "\n=== SIMPLE CORP TURN ===")
  (dotimes [i 3]
    (take-credits))
  (end-turn)
  (println "=== TURN COMPLETE ===\n"))

(defn simple-runner-turn
  "Execute a simple Runner turn: click for credit 4 times, end turn"
  []
  (println "\n=== SIMPLE RUNNER TURN ===")
  (dotimes [i 4]
    (take-credits))
  (end-turn)
  (println "=== TURN COMPLETE ===\n"))

;; ============================================================================
;; Debugging
;; ============================================================================

(defn inspect-state
  "Show raw game state (for debugging)"
  []
  (clojure.pprint/pprint @ws/client-state))

(defn inspect-prompt
  "Show raw prompt data (for debugging)"
  []
  (clojure.pprint/pprint (ws/get-prompt)))

;; ============================================================================
;; Help
;; ============================================================================

(defn list-playables
  "List all currently playable actions (cards, abilities, basic actions)
   Useful for AI decision-making - shows exactly what can be done right now"
  []
  (let [state @ws/client-state
        side (keyword (:side state))
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
          (println (format "  - %s (%s, %d credits)"
                          (:title card)
                          (:type card)
                          (:cost card))))))

    ;; Playable installed abilities
    (let [all-installed (concat
                         (get rig :hardware [])
                         (get rig :program [])
                         (get rig :resource []))
          playable-abilities (for [card all-installed
                                  [idx ability] (map-indexed vector (:abilities card))
                                  :when (:playable ability)]
                              {:card (:title card)
                               :idx idx
                               :label (:label ability)
                               :cost (:cost-label ability)})]
      (when (seq playable-abilities)
        (println "\n⚙️  Installed Abilities:")
        (doseq [{:keys [card idx label cost]} playable-abilities]
          (println (format "  - %s: Ability %d - %s%s"
                          card
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

(println "✨ AI Actions loaded. Type (ai-actions/help) for commands")
