(ns ai-display
  "Read-only display functions for game status, board state, and information"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-core :as core]
            [ai-basic-actions :as actions]
            [jinteki.cards :refer [all-cards]]))

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
                    title (if rezzed (core/format-card-name-with-index ice ice-list) (:title ice))
                    subtype (or (first (clojure.string/split (or (:subtype ice) "") #" - ")) "?")
                    strength (:current-strength ice)
                    status-icon (if rezzed "🔴" "⚪")]
                (println (str "  ICE #" idx ": " status-icon " "
                             (if rezzed title "Unrezzed ICE")
                             (when rezzed (str " (" subtype ")"))
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
              (doseq [card unrezzed-content]
                (println (str "  Content: ⚪ Unrezzed card" (format-counters card))))))
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
  (let [state @ws/client-state
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

(defn show-log
  "Display game log (natural language event history)"
  ([] (ws/show-game-log 20))
  ([n] (ws/show-game-log n)))

(defn show-log-compact
  "Display ultra-compact game log (recent N entries, one line each, no decorations)"
  ([] (show-log-compact 5))
  ([n]
   (let [state @ws/client-state
         log (get-in state [:game-state :log])
         recent (take-last n log)]
     (doseq [entry recent]
       (when (map? entry)
         (let [text (clojure.string/replace (:text entry "") "[hr]" "")]
           (println text))))
     nil)))

(defn status-compact
  "Show ultra-compact game status (1 line)"
  []
  (ws/show-status-compact))

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
  (let [my-side (:side @ws/client-state)]
    (println (str "💤 Waiting for my turn (" my-side ")..."))
    (loop [checks 0]
      (let [state (ws/get-game-state)
            active-player (ws/active-player)
            end-turn (get-in state [:end-turn])
            clicks (get-in state [(keyword my-side) :click])]
        (cond
          ;; My turn and I have clicks
          (and (= my-side active-player) (> clicks 0))
          (do
            (println (str "✅ Your turn! (" my-side " - " clicks " clicks remaining)"))
            {:status :ready :turn (ws/turn-number) :side my-side :clicks clicks})

          ;; End turn called, waiting for me to start
          (and end-turn (not= my-side active-player))
          (do
            (println (str "✅ Ready to start turn! (use 'start-turn')"))
            {:status :ready-to-start :turn (ws/turn-number) :side my-side})

          ;; Both at 0 clicks
          (and (= 0 (get-in state [:runner :click]))
               (= 0 (get-in state [:corp :click])))
          (do
            (println (str "✅ Ready to start turn! (use 'start-turn')"))
            {:status :ready-to-start :turn (ws/turn-number) :side my-side})

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
  (let [my-side (:side @ws/client-state)]
    (when (not= my-side "corp")
      (println "❌ wait-for-run is Corp-only (use wait-for-my-turn as Runner)")
      (throw (Exception. "wait-for-run is Corp-only")))

    (println "💤 Waiting for Runner to initiate run...")
    (loop [checks 0]
      (let [state (ws/get-game-state)
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
  "Show hand using side-aware state access. Returns nil to avoid printing raw data."
  []
  (let [state @ws/client-state
        side (:side state)
        hand (get-in state [:game-state (keyword (clojure.string/lower-case side)) :hand])]
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
          (println (str "  " idx ". " card-name " [" card-type subtypes "]" cost)))))
    nil))

(defn show-credits
  "Show current credits (side-aware). Returns credits value."
  []
  (let [state @ws/client-state
        side (:side state)
        credits (get-in state [:game-state (keyword (clojure.string/lower-case side)) :credit])]
    (println "💰 Credits:" credits)
    credits))

(defn show-clicks
  "Show remaining clicks (side-aware). Returns clicks value."
  []
  (let [state @ws/client-state
        side (:side state)
        clicks (get-in state [:game-state (keyword (clojure.string/lower-case side)) :click])]
    (println "⏱️  Clicks:" clicks)
    clicks))

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

(defn show-prompt-detailed
  "Show current prompt with detailed choices"
  []
  (let [state @ws/client-state
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
            (doseq [[idx card] (map-indexed vector selectable)]
              (let [title (core/format-card-name-with-index card selectable)
                    card-type (or (:type card) "?")
                    zone (or (:zone card) [])]
                (println (str "    " idx ". " title " [" card-type "]"
                            (when (seq zone) (str " (in " (clojure.string/join " > " (map name zone)) ")"))))))))
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
   (let [hand (ws/my-hand)
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
  (let [state @ws/client-state
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
          (let [card-name (core/format-card-name-with-index card hand)]
            (println (format "  - %s (%s, %d credits)"
                            card-name
                            (:type card)
                            (:cost card)))))))

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
