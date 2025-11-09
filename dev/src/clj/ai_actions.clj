(ns ai-actions
  "High-level AI player actions - simple API for common game operations"
  (:require [ai-websocket-client-v2 :as ws]))

;; ============================================================================
;; Helper Functions
;; ============================================================================

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

;; ============================================================================
;; Status & Information
;; ============================================================================

(defn status
  "Show current game status"
  []
  (ws/show-status))

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
      (println "   Credits:" credits))))

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
      (show-before-after "⏱️  Clicks" before-clicks after-clicks))))

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
    (println "🏁 Turn ended")))

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
      (let [state @ws/client-state
            gameid (:gameid state)
            card-ref (create-card-ref card)]
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "play"
                           :args {:card card-ref}})
        (Thread/sleep 2000)
        (println (str "✅ Played: " (:title card))))
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
       (let [state @ws/client-state
             gameid (:gameid state)
             card-ref (create-card-ref card)
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
         (Thread/sleep 2000)
         (if server
           (println (str "✅ Installing: " (:title card) " on " server))
           (println (str "✅ Installing: " (:title card)))))
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

(defn use-ability!
  "Use an installed card's ability

   Usage: (use-ability! \"Smartware Distributor\" 0)
          (use-ability! \"Sure Gamble\" 1)"
  [card-name ability-index]
  (let [card (find-installed-card card-name)]
    (if card
      (let [state @ws/client-state
            gameid (:gameid state)
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
      (println (str "❌ Card not found in rig: " card-name)))))

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
    (if (not= "Corp" side)
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
            (Thread/sleep 1500)
            (println (str "✅ Rezzed: " (:title card))))
          (println (str "❌ Card not found installed: " card-name)))))))

(defn fire-unbroken-subs!
  "Fire unbroken subroutines on ICE (Corp only)
   Used during runs when Runner doesn't/can't break all subs

   Usage: (fire-unbroken-subs! \"Palisade\")
          (fire-unbroken-subs! \"IP Block\")"
  [ice-name]
  (let [state @ws/client-state
        side (:side state)]
    (if (not= "Corp" side)
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
    (if (not= "Corp" side)
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
            (Thread/sleep 1500)
            (println (str "✅ Advanced: " (:title card))))
          (println (str "❌ Card not found installed: " card-name)))))))

(defn score-agenda!
  "Score an installed agenda (Corp only)
   Agenda must have enough advancement counters to be scored

   Usage: (score-agenda! \"Project Vitruvius\")
          (score-agenda! \"Send a Message\")"
  [card-name]
  (let [state @ws/client-state
        side (:side state)]
    (if (not= "Corp" side)
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
              (Thread/sleep 1500)
              (println (str "✅ Scored: " (:title card))))
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
  (println "\nWorkflows:")
  (println "  (simple-corp-turn)                 - 3x credit, end")
  (println "  (simple-runner-turn)               - 4x credit, end")
  (println "\nDebugging:")
  (println "  (inspect-state)                    - Show raw state")
  (println "  (inspect-prompt)                   - Show raw prompt")
  (println "  (help)                             - This help\n"))

(println "✨ AI Actions loaded. Type (ai-actions/help) for commands")
