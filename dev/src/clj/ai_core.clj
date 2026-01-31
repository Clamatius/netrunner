(ns ai-core
  "Core utility functions for AI player - shared helpers used across modules"
  (:require [ai-state :as state]
            [jinteki.cards :refer [all-cards]]
            [clj-http.client :as http]
            [clojure.string :as str]))

;; ============================================================================
;; Timing Constants
;; ============================================================================

;; Delay constants (milliseconds)
(def polling-delay
  "Very brief delay for polling loops (100ms)"
  100)

(def quick-delay
  "Quick delay for UI responsiveness (200ms)"
  200)

(def short-delay
  "Short delay for waiting on state changes (500ms)"
  500)

(def medium-delay
  "Medium delay for card actions to process (750ms)"
  750)

(def standard-delay
  "Standard delay for most game actions (1s)"
  1000)

;; Timeout constants (milliseconds)
(def action-timeout
  "Timeout for action verification in game log (3s)"
  3000)

(def extended-timeout
  "Extended timeout for complex operations (5s)"
  5000)

;; ============================================================================
;; Return Value Conventions
;; ============================================================================
;;
;; ACTION FUNCTIONS return values follow these patterns:
;;
;; 1. STATUS MAP PATTERN (preferred for new code):
;;    Functions that need to communicate success/failure return maps:
;;
;;    {:status :success
;;     :data {...}}           ; Success with optional data
;;
;;    {:status :error
;;     :reason "..."}          ; Error with reason string
;;
;;    {:status :waiting-input
;;     :prompt {...}}          ; Waiting for user input (prompt created)
;;
;;    {:status :waiting-for-opponent
;;     :message "..."}         ; Waiting for opponent action
;;
;;    Examples: play-card!, install-card!, start-turn!, take-credit!
;;              continue-run!, choose-card!, choose-by-value!
;;
;; 2. NIL RETURN PATTERN (legacy, simpler actions):
;;    Functions that just perform actions and print feedback return nil:
;;
;;    Examples: rez-card!, trash-card!, advance-card!, score-agenda!
;;              draw-card!, end-turn!, mulligan, keep-hand
;;
;; 3. VALUE RETURN PATTERN (queries):
;;    Read-only query functions return the requested value:
;;
;;    Examples: show-hand (returns hand vector)
;;              show-credits (returns number)
;;              show-clicks (returns number)
;;              status (returns state map)
;;
;; GUIDELINE: Use status maps when the caller needs to know if the action
;; succeeded or failed. Use nil returns for simple actions where printing
;; feedback is sufficient. New code should prefer status maps for better
;; composability and error handling.
;;
;; ============================================================================
;; Result Builders
;; ============================================================================
;; Standardized constructors for status maps. Use these instead of hand-crafting
;; maps to ensure consistency across the codebase.

(defn success
  "Build a success result map. Merges any additional data into the result.

   Usage:
     (success)                           ; => {:status :success}
     (success :card \"Sure Gamble\")     ; => {:status :success :card \"Sure Gamble\"}
     (success :action :installed :zone :rig)"
  [& {:as data}]
  (merge {:status :success} data))

(defn error
  "Build an error result map with a reason. Merges any additional data.

   Usage:
     (error :card-not-found)
     (error :insufficient-credits :cost 5 :available 3)"
  [reason & {:as data}]
  (merge {:status :error :reason reason} data))

(defn waiting-input
  "Build a waiting-for-input result map. Indicates action created a prompt.

   Usage:
     (waiting-input my-prompt)"
  [prompt]
  {:status :waiting-input :prompt prompt})

(defn waiting-opponent
  "Build a waiting-for-opponent result map.

   Usage:
     (waiting-opponent \"Corp must rez or continue\")"
  [message]
  {:status :waiting-for-opponent :message message})

;; ============================================================================
;; Side Comparison
;; ============================================================================

(defn side=
  "Case-insensitive side comparison
   Handles that client-state stores side as lowercase 'corp'/'runner'

   Usage: (side= \"Corp\" side)
          (side= \"Runner\" side)"
  [expected-side actual-side]
  (= (str/lower-case expected-side)
     (str/lower-case (or actual-side ""))))

;; ============================================================================
;; Error Response Helpers
;; ============================================================================

(defn get-active-prompt-summary
  "Get a brief summary of the active prompt, if any.
   Returns nil if no prompt, or a map with :msg and :type"
  []
  (let [side (:side @state/client-state)
        prompt (get-in @state/client-state [:game-state (keyword side) :prompt-state])]
    (when prompt
      {:msg (:msg prompt)
       :type (:prompt-type prompt)
       :card-title (get-in prompt [:card :title])
       :choices-count (count (:choices prompt))})))

(defn with-prompt-hint
  "Enriches an error result with active prompt info if present.
   If result is an error and there's an active prompt, adds :active-prompt key
   and prints a hint to the user.

   Usage: (with-prompt-hint {:status :error :reason \"something failed\"})"
  [result]
  (if (and (= :error (:status result))
           (get-active-prompt-summary))
    (let [prompt-summary (get-active-prompt-summary)]
      (println (format "💡 Note: There's an active prompt: %s"
                      (or (:msg prompt-summary) "Unknown")))
      (println "   Use 'prompt' to see details, or resolve it before retrying")
      (assoc result :active-prompt prompt-summary))
    result))

(defn check-blocking-prompt
  "Check if a blocking prompt exists that would prevent an action.

   Returns an error map if a blocking prompt exists, nil if safe to proceed.
   'Waiting' type prompts are not considered blocking.

   Usage:
     (if-let [err (check-blocking-prompt \"install card\")]
       err  ; Return the error
       ... proceed with action ...)"
  [action-name]
  (let [existing-prompt (state/get-prompt)]
    (when (and existing-prompt
               (not= :waiting (:prompt-type existing-prompt))
               (not= "waiting" (:prompt-type existing-prompt)))
      (println (str "❌ Cannot " action-name ": Active prompt must be answered first"))
      (println (str "   Prompt: " (:msg existing-prompt)))
      (flush)
      {:status :error
       :reason "Active prompt must be answered first"
       :prompt existing-prompt})))

;; ============================================================================
;; Card Database Management
;; ============================================================================

(defn load-cards-from-api!
  "Fetch card database from server API and populate all-cards atom
   Only fetches once - subsequent calls are no-ops if cards already loaded"
  []
  (when (empty? @all-cards)
    (try
      (let [response (http/get "http://localhost:1042/data/cards"
                              {:as :json
                               :socket-timeout 10000
                               :connection-timeout 5000})
            cards (:body response)
            cards-map (into {} (map (juxt :title identity)) cards)]
        (reset! all-cards cards-map))
      (catch Exception e
        (println "❌ Failed to load cards from API:" (.getMessage e))
        (println "   Make sure the game server is running on localhost:1042")))))

;; ============================================================================
;; Log Helpers
;; ============================================================================

(defn get-log-size
  "Get current size of the game log"
  []
  (let [client-state @state/client-state
        log (get-in client-state [:game-state :log])]
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
              (Thread/sleep polling-delay)
              (recur))
            false))))))

;; ============================================================================
;; Log Analysis Helpers (Pure Functions)
;; ============================================================================
;; These functions analyze game log entries to determine turn state.
;; They are pure functions for testability - pass log and username explicitly.

(defn find-end-turn-indices
  "Find indices of 'is ending' log entries, optionally filtered by username.

   Parameters:
   - log: vector of log entries (each with :text key)
   - exclude-username: if provided, exclude entries containing this username

   Returns sequence of indices where end-turn entries appear."
  [log exclude-username]
  (keep-indexed
   (fn [idx entry]
     (let [text (:text entry)]
       (when (and text
                  (str/includes? text "is ending")
                  (or (nil? exclude-username)
                      (not (str/includes? text exclude-username))))
         idx)))
   log))

(defn find-start-turn-indices
  "Find indices of 'started their turn' log entries, filtered by username inclusion/exclusion.

   Parameters:
   - log: vector of log entries (each with :text key)
   - include-username: if provided, only include entries containing this username
   - exclude-username: if provided (and include-username nil), exclude entries with this username

   Returns sequence of indices where start-turn entries appear."
  [log & {:keys [include-username exclude-username]}]
  (keep-indexed
   (fn [idx entry]
     (let [text (:text entry)]
       (when (and text
                  (str/includes? text "started their turn")
                  (cond
                    include-username (str/includes? text include-username)
                    exclude-username (not (str/includes? text exclude-username))
                    :else true))
         idx)))
   log))

(defn extract-turn-number
  "Extract turn number from log text like 'started their turn 5' or 'is ending their turn 14'.
   Returns the integer turn number, or nil if not found."
  [text]
  (when text
    (when-let [match (re-find #"turn (\d+)" text)]
      (Integer/parseInt (second match)))))

(defn verify-action-in-log
  "Check if a card action appears in recent game log entries
   Returns status map with:
   - :status - :success (action completed), :waiting-input (prompt created), :error (failed)
   - :prompt - the prompt that was created (if :waiting-input)
   - :card-name - the card name being verified

   Distinguishes between:
   - Action completed: Card moved zones, log entry added, state changed
   - Action waiting for input: Only prompt created, card still in hand
   - Action failed: Nothing happened

   Waits up to max-wait-ms for the log entry to appear

   IMPORTANT: Pass initial-log-size captured BEFORE sending the action message
   to avoid race conditions with fast WebSocket responses."
  ([card-name card-initial-zone max-wait-ms]
   (verify-action-in-log card-name card-initial-zone max-wait-ms nil))
  ([card-name card-initial-zone max-wait-ms initial-log-size]
  (let [initial-size (or initial-log-size (get-log-size))
        initial-prompt (state/get-prompt)
        deadline (+ (System/currentTimeMillis) max-wait-ms)
        check-result (fn []
                      (let [client-state @state/client-state
                            log (get-in client-state [:game-state :log])
                            current-size (count log)
                            current-prompt (state/get-prompt)
                            ;; Check if card is still in original zone (hand)
                            side (keyword (:side client-state))
                            hand (get-in client-state [:game-state side :hand])
                            card-still-in-hand (some #(and (= (:title %) card-name)
                                                           (= (:zone %) card-initial-zone))
                                                    hand)
                            ;; Check if new prompt was created
                            new-prompt-created (and current-prompt
                                                   (not= current-prompt initial-prompt))
                            ;; Check if log entry mentions the card
                            card-in-log (let [recent-log (take-last 5 log)]
                                         (some #(when (string? (:text %))
                                                 (str/includes? (:text %) card-name))
                                              recent-log))]

                        (cond
                          ;; If card moved from hand AND log grew, it's a success
                          (and (not card-still-in-hand)
                               (or (> current-size initial-size)
                                   card-in-log))
                          {:status :success}

                          ;; If card is STILL in hand but new prompt created, it's waiting for input
                          (and card-still-in-hand new-prompt-created)
                          {:status :waiting-input
                           :prompt current-prompt
                           :card-name card-name}

                          ;; If log grew or card appears in log (even without zone change), might be success
                          ;; (for Corp hidden cards where card name doesn't show)
                          (or (> current-size initial-size) card-in-log)
                          {:status :success}

                          ;; Otherwise, no change yet
                          :else
                          nil)))]
    ;; Poll until we get a result or timeout
    (loop []
      (if-let [result (check-result)]
        result
        (if (< (System/currentTimeMillis) deadline)
          (do
            (Thread/sleep polling-delay)
            (recur))
          {:status :error
           :reason "Action not confirmed in game log (timeout)"
           :card-name card-name}))))))

(defn verify-ability-in-log
  "Check if ability usage appears in game log.
   Unlike verify-action-in-log, doesn't check zone change (card stays installed).
   Returns status map with:
   - :status - :success (ability fired), :waiting-input (prompt created), :error (failed)
   - :prompt - the prompt that was created (if :waiting-input)
   - :card-name - the card name being verified

   IMPORTANT: Only checks NEW log entries (after initial-size) to avoid false positives
   when abilities are used repeatedly (e.g., Regolith Mining License).

   IMPORTANT: Pass pre-log-size and pre-prompt captured BEFORE sending the command
   to avoid race conditions where the response arrives before we start polling.

   Waits up to max-wait-ms for the log entry to appear"
  [card-name max-wait-ms {:keys [pre-log-size pre-prompt]}]
  (let [initial-size (or pre-log-size (get-log-size))
        initial-prompt (or pre-prompt (state/get-prompt))
        deadline (+ (System/currentTimeMillis) max-wait-ms)
        check-result (fn []
                       (let [client-state @state/client-state
                             log (get-in client-state [:game-state :log])
                             current-size (count log)
                             current-prompt (state/get-prompt)
                             ;; Check if new prompt was created
                             new-prompt-created (and current-prompt
                                                     (not= current-prompt initial-prompt))
                             ;; Only check NEW log entries (added after initial-size)
                             new-entries (drop initial-size log)
                             card-in-new-entries (some #(when (string? (:text %))
                                                          (str/includes? (:text %) card-name))
                                                       new-entries)]
                         (cond
                           ;; Card name in NEW log entries = success
                           card-in-new-entries
                           {:status :success :card-name card-name}

                           ;; New prompt created = waiting for input
                           new-prompt-created
                           {:status :waiting-input
                            :prompt current-prompt
                            :card-name card-name}

                           ;; Log grew (but no card name visible) = might be success
                           ;; (some abilities may not mention card name in log)
                           (> current-size initial-size)
                           {:status :success :card-name card-name}

                           ;; No change yet
                           :else nil)))]
    ;; Poll until we get a result or timeout
    (loop []
      (if-let [result (check-result)]
        result
        (if (< (System/currentTimeMillis) deadline)
          (do
            (Thread/sleep polling-delay)
            (recur))
          {:status :error
           :reason "Ability not confirmed in game log (timeout). Check card text for restrictions (once per turn, cost requirements, etc.)"
           :card-name card-name})))))

;; ============================================================================
;; Card Name Parsing and Formatting
;; ============================================================================

(defn parse-card-reference
  "Parse card name with optional [N] index suffix
   Examples:
     \"Palisade\" -> {:title \"Palisade\" :index 0 :explicit-index? false}
     \"Palisade [1]\" -> {:title \"Palisade\" :index 1 :explicit-index? true}
   Returns map with :title, :index (0-based), and :explicit-index?"
  [card-name]
  (if-let [[_ title idx] (re-matches #"(.+?)\s*\[(\d+)\]" card-name)]
    {:title title :index (Integer/parseInt idx) :explicit-index? true}
    {:title card-name :index 0 :explicit-index? false}))

(defn format-card-name-with-index
  "Format card name with [N] suffix if duplicates exist in collection
   Uses 0-based indexing: first copy is [0], second is [1], etc.
   Examples:
     Single card: \"Palisade\" -> \"Palisade\"
     2+ copies: \"Palisade\" -> \"Palisade [0]\", \"Palisade [1]\""
  [card all-cards]
  (let [card-title (:title card)
        same-name-cards (filter #(= (:title %) card-title) all-cards)
        card-count (count same-name-cards)]
    (if (> card-count 1)
      (let [index (.indexOf (vec same-name-cards) card)]
        (str card-title " [" index "]"))
      card-title)))

;; ============================================================================
;; Display and Formatting Helpers
;; ============================================================================

(defn format-choice
  "Format a choice for display, handling different prompt formats
   Used by prompt and display functions to consistently format choices

   Usage: (format-choice {:value \"HQ\"}) -> \"HQ\"
          (format-choice {:label \"Draw a card\"}) -> \"Draw a card\"
          (format-choice \"Done\") -> \"Done\""
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

;; ============================================================================
;; Agenda Helpers
;; ============================================================================

(defn find-scorable-agendas
  "Find all installed Corp agendas that have enough advancement counters to score.
   Returns sequence of maps with :card, :title, :counters, :requirement

   Note: This does a simple counter check (counters >= requirement).
   It does NOT detect effects like 'cannot score this turn' or similar restrictions.
   Therefore, use conservatively - if this returns agendas, assume they MIGHT be scorable."
  []
  (let [side (:side @state/client-state)]
    (if (side= "Corp" side)
      (let [servers (state/corp-servers)
            ;; Get all content (assets/upgrades/agendas) from all servers
            all-content (mapcat :content (vals servers))
            ;; Filter for agendas only
            agendas (filter #(= "Agenda" (:type %)) all-content)
            ;; Check which are scorable (counters >= requirement)
            scorable (filter (fn [agenda]
                              (let [counters (or (:advance-counter agenda) 0)
                                    requirement (:advancementcost agenda)]
                                (and requirement (>= counters requirement))))
                            agendas)]
        ;; Return useful info about each scorable agenda
        (map (fn [agenda]
               {:card agenda
                :title (:title agenda)
                :counters (or (:advance-counter agenda) 0)
                :requirement (:advancementcost agenda)})
            scorable))
      ;; Not Corp, return empty
      [])))

;; ============================================================================
;; Install Validation (Baby-proofing)
;; ============================================================================
;; The jinteki server is permissive (allows illegal moves for manual state fixes).
;; These functions validate installs client-side to prevent our AI from cheating.

(defn- server-name->key
  "Convert server name string to keyword for state lookup.
   'Server 1' -> :remote1, 'HQ' -> :hq, 'New remote' -> :remoteNew, etc."
  [server-name]
  (when server-name
    (let [lower (str/lower-case server-name)]
      (cond
        (= lower "hq") :hq
        (= lower "r&d") :rd
        (= lower "archives") :archives
        ;; Handle "New remote" server (creates new remote)
        (= lower "new remote") :remoteNew
        (re-matches #"server \d+" lower)
        (keyword (str "remote" (second (re-find #"server (\d+)" lower))))
        (re-matches #"remote\d+" lower)
        (keyword lower)
        :else nil))))

(defn- central-server?
  "Check if server name refers to a central server"
  [server-name]
  (contains? #{:hq :rd :archives} (server-name->key server-name)))

(defn- root-card-type?
  "Check if card type is a 'root' card (asset or agenda) that occupies the server slot"
  [card-type]
  (contains? #{"Asset" "Agenda"} card-type))

(defn server-has-root-card?
  "Check if a server already has an asset or agenda installed.
   Returns the existing root card if found, nil otherwise."
  [server-name]
  (when-let [server-key (server-name->key server-name)]
    (let [content (state/server-cards server-key)]
      (->> content
           (filter #(root-card-type? (:type %)))
           first))))

(defn get-existing-remote-names
  "Returns a set of existing remote server names from game state.
   Returns names like 'Server 1', 'Server 2', etc."
  []
  (let [servers (state/corp-servers)
        remote-keys (filter #(str/starts-with? (name %) "remote") (keys servers))
        ;; Convert :remote1 → 'Server 1', :remote2 → 'Server 2'
        remote-names (map #(let [num (second (re-find #"remote(\d+)" (name %)))]
                             (str "Server " num))
                          remote-keys)]
    (set remote-names)))

;; Forward declaration for mutual dependencies
(declare normalize-server-name)

(defn validate-server-name
  "Validate a server name is valid and exists.
   Returns nil if valid, error map if invalid.

   Rules:
   - Central servers (HQ, R&D, Archives) are always valid
   - 'New remote' is always valid (creates new remote)
   - Remote server names must reference existing servers
   - Rejects malformed names (single letters, invalid patterns)"
  [server-name]
  (let [normalized (:normalized (normalize-server-name server-name))
        original-lower (str/lower-case (str/trim server-name))]
    (cond
      ;; Nil or empty - caller decides if this is ok
      (or (nil? server-name) (str/blank? server-name))
      nil

      ;; Central servers - always valid
      (central-server? normalized)
      nil

      ;; "New remote" - always valid (creates new remote)
      (= normalized "New remote")
      nil

      ;; Check for obviously invalid names (single letter, etc.)
      ;; These would pass through normalize-server-name unchanged
      (and (< (count original-lower) 3)
           (not (re-matches #"r\d+|s\d+" original-lower)))  ; Allow r1, s1 shorthand
      {:error true
       :reason (str "Invalid server name: '" server-name "'")
       :hint (str "Valid servers: HQ, R&D, Archives, 'new', or existing remote (Server 1, Server 2...)")
       :existing (get-existing-remote-names)}

      ;; Remote server - check it exists
      (re-matches #"Server \d+" normalized)
      (let [existing (get-existing-remote-names)]
        (if (contains? existing normalized)
          nil  ; Server exists
          {:error true
           :reason (str "Server '" normalized "' does not exist")
           :hint (str "Use 'new' to create a new remote, or choose existing: "
                      (if (empty? existing)
                        "(no remotes yet)"
                        (str/join ", " (sort existing))))
           :existing existing}))

      ;; Unrecognized format that passed through normalize unchanged
      ;; This catches things like "R" (from "R&D" parsing issue)
      (= normalized server-name)
      {:error true
       :reason (str "Unrecognized server name: '" server-name "'")
       :hint "Valid servers: HQ, R&D, Archives, 'new', or existing remote (Server 1, Server 2...)"
       :existing (get-existing-remote-names)}

      ;; Otherwise valid
      :else nil)))

(defn validate-corp-install
  "Validate a Corp install is legal. Returns nil if valid, error map if invalid.

   Rules enforced:
   - Assets/Agendas can only be installed in remotes (not centrals)
   - Only one asset/agenda per remote server
   - ICE can be installed on any server (multiple allowed)
   - Upgrades can be installed anywhere (multiple allowed)"
  [card server-name]
  (let [card-type (:type card)
        card-title (:title card)]
    (cond
      ;; ICE and Upgrades are always allowed
      (contains? #{"ICE" "Upgrade"} card-type)
      nil

      ;; Assets and Agendas need special checks
      (root-card-type? card-type)
      (cond
        ;; Can't install in centrals
        (central-server? server-name)
        {:error true
         :reason (str "Cannot install " card-type " in central server " server-name)
         :hint "Assets and Agendas can only be installed in remote servers"}

        ;; Check if "New remote" - always allowed
        (and server-name
             (= "New remote" (str/trim server-name)))
        nil

        ;; Check if remote already has a root card
        :else
        (when-let [existing (server-has-root-card? server-name)]
          {:error true
           :reason (str "Cannot install " card-title " - " server-name " already has " (:title existing))
           :hint "Each remote can only have one asset or agenda"}))

      ;; Unknown card type - allow (don't block unexpected things)
      :else nil)))

;; ============================================================================
;; Card Lookup Helpers
;; ============================================================================

(defn find-card-in-hand
  "Find card in hand by name or index
   Supports [N] suffix for duplicate cards: \"Sure Gamble [1]\"
   Returns card object or nil if not found"
  [name-or-index]
  (let [side (:side @state/client-state)
        hand (state/hand-for-side side)]
    (cond
      (number? name-or-index)
      (nth hand name-or-index nil)

      (string? name-or-index)
      (let [{:keys [title index]} (parse-card-reference name-or-index)
            matches (filter #(= title (:title %)) hand)]
        (nth (vec matches) index nil))

      :else nil)))

(defn create-card-ref
  "Create minimal card reference for server commands"
  [card]
  {:cid (:cid card)
   :zone (:zone card)
   :side (:side card)
   :type (:type card)
   :title (:title card)})

(defn find-installed-card
  "Find an installed card by title in the rig
   Supports [N] suffix for duplicate cards: \"Corroder [1]\"
   Searches programs, hardware, and resources
   Returns nil and prints disambiguation message if multiple copies and no index specified"
  [card-name]
  (let [rig (state/runner-rig)
        all-installed (concat (:program rig) (:hardware rig) (:resource rig))
        {:keys [title index explicit-index?]} (parse-card-reference card-name)
        matches (filter #(= title (:title %)) all-installed)
        match-count (count matches)]
    (cond
      (zero? match-count) nil
      (= 1 match-count) (first matches)
      explicit-index? (nth (vec matches) index nil)
      :else
      (do
        (println (format "❓ Multiple copies of '%s' installed (%d found)" title match-count))
        (println "   Specify which one:")
        (doseq [[idx card] (map-indexed vector matches)]
          (let [zone-name (name (last (:zone card)))]
            (println (format "   → \"%s [%d]\" (%s)" title idx zone-name))))
        nil))))

(defn- card-server-location
  "Get human-readable server location for a Corp card"
  [card]
  (let [zone (:zone card)]
    (when (and zone (>= (count zone) 2))
      (let [server-key (nth zone 1)]
        (case server-key
          :hq "HQ"
          :rd "R&D"
          :archives "Archives"
          ;; Remote servers
          (if (and (keyword? server-key) (clojure.string/starts-with? (name server-key) "remote"))
            (str "Server " (subs (name server-key) 6))
            (name server-key)))))))

(defn find-installed-corp-card
  "Find an installed Corp card by title
   Supports [N] suffix for duplicate cards: \"Palisade [1]\"
   Searches all servers for ICE, assets, and upgrades
   Returns nil and prints disambiguation message if multiple copies and no index specified"
  [card-name]
  (let [servers (state/corp-servers)
        ;; Get all ICE from all servers
        all-ice (mapcat :ices (vals servers))
        ;; Get all content (assets/upgrades) from all servers
        all-content (mapcat :content (vals servers))
        all-installed (concat all-ice all-content)
        {:keys [title index explicit-index?]} (parse-card-reference card-name)
        matches (filter #(= title (:title %)) all-installed)
        match-count (count matches)]
    (cond
      (zero? match-count) nil
      (= 1 match-count) (first matches)
      explicit-index? (nth (vec matches) index nil)
      :else
      (do
        (println (format "❓ Multiple copies of '%s' installed (%d found)" title match-count))
        (println "   Specify which one:")
        (doseq [[idx card] (map-indexed vector matches)]
          (let [location (card-server-location card)
                rezzed? (:rezzed card)
                status (if rezzed? "rezzed" "unrezzed")]
            (println (format "   → \"%s [%d]\" (%s, %s)" title idx location status))))
        nil))))

(defn find-card-by-cid
  "Find a card by CID (card ID) anywhere in the game state.
   Searches Corp servers (ICE, content), Runner rig, hands, play areas, and discard piles.
   Returns the card map or nil if not found."
  [cid]
  (let [gs (state/get-game-state)
        ;; Corp servers - ICE and content
        servers (get-in gs [:corp :servers])
        all-ice (mapcat :ices (vals servers))
        all-content (mapcat :content (vals servers))
        ;; Runner rig
        rig (get-in gs [:runner :rig])
        runner-cards (concat (:program rig) (:hardware rig) (:resource rig))
        ;; Hands
        corp-hand (get-in gs [:corp :hand])
        runner-hand (get-in gs [:runner :hand])
        ;; Play areas (for events like Overclock with active effects during runs)
        corp-play-area (get-in gs [:corp :play-area])
        runner-play-area (get-in gs [:runner :play-area])
        ;; Discard piles (Archives/Heap)
        corp-discard (get-in gs [:corp :discard])
        runner-discard (get-in gs [:runner :discard])
        ;; All searchable cards
        all-cards (concat all-ice all-content runner-cards corp-hand runner-hand
                          corp-play-area runner-play-area
                          corp-discard runner-discard)]
    (first (filter #(= cid (:cid %)) all-cards))))

;; ============================================================================
;; Server Name Normalization
;; ============================================================================

(defn normalize-server-name
  "Normalize user-friendly server names to game-expected format.
   Accepts common variants and typos, provides helpful feedback.

   Examples:
   - 'hq', 'HQ' → 'HQ'
   - 'rd', 'r&d', 'R&D' → 'R&D'
   - 'archives', 'Archives' → 'Archives'
   - 'remote1', 'remote 1', 'r1', 'server1', 'server 1' → 'Server 1'
   - 'new', 'remotenew', 'server new' → 'New remote' (create new remote server)

   Returns: {:normalized <game-name> :original <input> :changed? <bool>}"
  [server-input]
  (let [s (clojure.string/lower-case (clojure.string/trim server-input))
        remote-pattern #"(?:remote|r|server)\s*(\d+)"
        ;; Pattern for 'new' server: new, remotenew, remote new, servernew, server new
        new-pattern #"(?:remote\s*|server\s*)?new"
        normalized (cond
                     ;; Central servers
                     (= s "hq") "HQ"
                     (or (= s "rd") (= s "r&d")) "R&D"
                     (= s "archives") "Archives"

                     ;; 'New' server - tells game to create new remote
                     (re-matches new-pattern s) "New remote"

                     ;; Remote servers - handle various formats
                     ;; remote1, remote 1, r1, server1, server 1 → Server 1
                     (re-matches remote-pattern s)
                     (let [num (second (re-matches remote-pattern s))]
                       (str "Server " num))

                     ;; Already correct format - pass through
                     :else server-input)]
    {:normalized normalized
     :original server-input
     :changed? (not= normalized server-input)}))

;; ============================================================================
;; Display Helpers
;; ============================================================================

(defn show-before-after
  "Display before/after state change"
  [label before after]
  (println (str label ": " before " → " after)))

(defn show-turn-indicator
  "Display turn status indicator after command execution"
  []
  (let [status (state/get-turn-status)
        emoji (:status-emoji status)
        text (:status-text status)
        _ (:turn-number status)
        in-run (:in-run? status)
        run-server (:run-server status)
        clicks (state/my-clicks)]
    (if in-run
      (println (str emoji " " text " | In run on " run-server))
      (if (:can-act? status)
        (println (str emoji " " text " - " clicks " clicks remaining"))
        (println (str emoji " " text))))))

(defn capture-state-snapshot
  "Capture current game state for before/after comparison
   Returns map with key state values"
  []
  (let [client-state @state/client-state
        side (keyword (:side client-state))
        gs (:game-state client-state)
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
           (println (str/join "  " changes))))))))

;; ============================================================================
;; Wait Helpers
;; ============================================================================

(defn wait-for-prompt
  "Wait for a prompt to appear (up to max-seconds)
   Returns prompt or nil if timeout"
  [max-seconds]
  (loop [checks 0]
    (if (< checks max-seconds)
      (if-let [prompt (state/get-prompt)]
        prompt
        (do
          (Thread/sleep short-delay)
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
         initial-state @state/client-state
         initial-log (get-in initial-state [:game-state :log])
         initial-log-count (count initial-log)
         deadline (+ (System/currentTimeMillis) (* timeout-seconds 1000))]

     (println (format "⏳ Waiting for game state change (timeout: %ds)..." timeout-seconds))

     (loop [checks 0]
       (Thread/sleep quick-delay)
       (let [current-state @state/client-state
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

;; ============================================================================
;; Log Summarization
;; ============================================================================

(defn- run-start-entry?
  "Check if entry is a run start"
  [text]
  (or (re-find #"makes a run on" text)
      (re-find #"to make a run on" text)))

(defn- run-detail-entry?
  "Check if entry is run detail (ICE encounter, breaking, etc.) that should be collapsed"
  [text]
  (or (re-find #"is encountered" text)
      (re-find #"breaks? .* subroutine" text)
      (re-find #"passes? " text)
      (re-find #"approaches?" text)
      (re-find #"fires? no unbroken" text)
      (re-find #"Runner has no further action" text)
      (re-find #"Corp has no further action" text)
      (re-find #"continue|Continue" text)
      (re-find #"jacks out" text)
      (re-find #"Run ends" text)))

(defn- run-end-entry?
  "Check if entry marks run end (success or failure)"
  [text]
  (or (re-find #"Run on .* successful" text)
      (re-find #"Run ends" text)
      (re-find #"jacks out" text)))

(defn- access-entry?
  "Check if entry is an access"
  [text]
  (re-find #"accesses?" text))

(defn- extract-run-server
  "Extract server name from run start entry"
  [text]
  (when-let [match (or (re-find #"makes a run on ([^.]+)" text)
                       (re-find #"to make a run on ([^.]+)" text))]
    (second match)))

(defn- simplify-basic-action
  "Remove 'to use X Basic Action Card' ceremony from log text"
  [text]
  (-> text
      (str/replace #" to use (Corp|Runner) Basic Action Card to" " to")
      (str/replace #"\s+" " ")))

(defn summarize-log-entries
  "Summarize log entries, collapsing run details into single lines.
   Returns a sequence of {:text ...} maps suitable for display."
  [entries]
  (loop [remaining entries
         result []
         in-run? false
         run-server nil
         accesses 0
         ice-passed []]
    (if (empty? remaining)
      ;; End of entries - close any open run
      (if in-run?
        (let [summary (str "  [Run on " run-server
                          (when (seq ice-passed) (str " - passed " (str/join ", " ice-passed)))
                          (when (pos? accesses) (str " - accessed " accesses " card" (when (> accesses 1) "s")))
                          "]")]
          (conj result {:text summary}))
        result)

      (let [entry (first remaining)
            text (or (:text entry) "")
            simplified (simplify-basic-action text)]
        (cond
          ;; Run start - begin tracking
          (run-start-entry? text)
          (let [server (extract-run-server text)]
            (recur (rest remaining)
                   (if in-run?
                     ;; Close previous run first
                     (conj result {:text (str "  [Run on " run-server " completed]")})
                     result)
                   true
                   server
                   0
                   []))

          ;; In a run - track details
          in-run?
          (cond
            ;; ICE encounter - track name
            (re-find #"(\S+) is encountered" text)
            (let [ice-name (second (re-find #"(\S+) is encountered" text))]
              (recur (rest remaining) result true run-server accesses (conj ice-passed ice-name)))

            ;; Access - count them
            (access-entry? text)
            (recur (rest remaining) result true run-server (inc accesses) ice-passed)

            ;; Run end - emit summary
            (run-end-entry? text)
            (let [success? (re-find #"successful" text)
                  summary (str (if success? "✓ " "✗ ") "Run on " run-server
                              (when (seq ice-passed) (str " (passed " (str/join ", " ice-passed) ")"))
                              (when (pos? accesses) (str " → accessed " accesses)))]
              (recur (rest remaining)
                     (conj result {:text summary})
                     false nil 0 []))

            ;; Other run detail - skip
            (run-detail-entry? text)
            (recur (rest remaining) result true run-server accesses ice-passed)

            ;; Non-run entry during run (unusual) - emit it
            :else
            (recur (rest remaining)
                   (conj result {:text simplified})
                   true run-server accesses ice-passed))

          ;; Not in run - emit simplified entry
          :else
          (recur (rest remaining)
                 (conj result {:text simplified})
                 false nil 0 []))))))

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
      (Thread/sleep quick-delay)
      (let [current-log (get-in @state/client-state [:game-state :log])
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

;; ============================================================================
;; Cursor Helpers (for race-condition-free waiting)
;; ============================================================================

(defn with-cursor
  "Enrich a status map with the current cursor value.
   Use this when returning from action functions to enable
   cursor-based waiting.

   Usage: (with-cursor {:status :success :data foo})"
  [status-map]
  (assoc status-map :cursor (state/get-cursor)))

(defn get-cursor
  "Get current state cursor. Delegates to ai-state."
  []
  (state/get-cursor))

(defn clear-game-state!
  "Clear all cached game state. Delegates to ai-state.
   Call before reconnect/resync to prevent stale data issues."
  []
  (state/clear-game-state!))

;; ============================================================================
;; Relevant Diff Waiting (for model-vs-model coordination)
;; ============================================================================

(defn- run-active?
  "Check if a run is currently in progress"
  [state]
  (some? (get-in state [:game-state :run])))

(defn- has-prompt?
  "Check if the given side has an actionable prompt"
  [state side]
  (let [prompt (get-in state [:game-state (keyword side) :prompt-state])]
    (and prompt
         (not= (:prompt-type prompt) "waiting")
         (or (seq (:choices prompt))
             (seq (:selectable prompt))))))

(defn- my-turn-to-act?
  "Check if it's our turn to act (need to start-turn or have clicks).
   Handles Netrunner priority system where active-player doesn't flip until start-turn."
  [state side]
  (let [my-side (keyword side)
        active-player (get-in state [:game-state :active-player])
        my-clicks (get-in state [:game-state my-side :click] 0)
        end-turn (get-in state [:game-state :end-turn])
        turn-number (get-in state [:game-state :turn] 0)]
    (or
      ;; My turn and I have clicks
      (and (= (name my-side) active-player) (> my-clicks 0))
      ;; Opponent ended turn, waiting for me to start
      ;; (active-player = opponent because end-turn was called, I'm next)
      (and end-turn (not= (name my-side) active-player))
      ;; Turn 0 with 0 clicks = post-mulligan, Corp needs to start
      ;; (Corp always goes first)
      (and (= 0 turn-number) (= 0 my-clicks) (= my-side :corp)))))

(defn- ping-message?
  "Check if a log entry is a 'ping' wake signal.
   Returns true for exact match 'ping' (case-insensitive, trimmed).
   Used by AIs to wake each other without game state changes."
  [entry]
  (let [text (or (:text entry) "")]
    ;; Match chat messages that are just "ping" (with optional username prefix)
    ;; Chat format is "Username: message" for player messages
    (when-let [msg-part (second (re-find #":\s*(.+)" text))]
      (= "ping" (clojure.string/lower-case (clojure.string/trim msg-part))))))

(defn- relevance-reason
  "Determine why we should wake up (or nil if not relevant).
   Returns keyword indicating wake reason."
  [state side initial-run-active?]
  (let [current-run-active? (run-active? state)
        has-actionable-prompt? (has-prompt? state side)]
    (cond
      ;; Run started - high priority, wake up!
      (and current-run-active? (not initial-run-active?))
      :run-started

      ;; Run is active and state changed - stay alert
      current-run-active?
      :run-active

      ;; We have a prompt to respond to
      has-actionable-prompt?
      :has-prompt

      ;; Run just ended - might need cleanup
      (and initial-run-active? (not current-run-active?))
      :run-ended

      ;; It's our turn to act (need to start-turn or take action)
      (my-turn-to-act? state side)
      :my-turn

      ;; Nothing relevant
      :else nil)))

(defn wait-for-relevant-diff
  "Wait for game state changes that are relevant to our side.
   Unlike wait-for-diff, this filters for events we care about:
   - Any change while a run is active
   - When we have a prompt to respond to
   - When a run starts or ends

   Sleeps through opponent economy/draw actions that don't affect us.

   The :since option enables race-condition-free waiting:
   - Pass the cursor from a previous action's response
   - If state has already advanced past that cursor, returns immediately
   - This prevents the 'waiting for opponent who already acted' problem

   Usage: (wait-for-relevant-diff)           ;; default 300s timeout
          (wait-for-relevant-diff 60)        ;; custom timeout
          (wait-for-relevant-diff {:timeout 120 :verbose true})
          (wait-for-relevant-diff {:since 847})  ;; cursor-based wait"
  ([]
   (wait-for-relevant-diff 300))
  ([timeout-or-opts]
   (let [opts (if (number? timeout-or-opts)
                {:timeout timeout-or-opts :verbose true}
                (merge {:timeout 300 :verbose true} timeout-or-opts))
         timeout-seconds (:timeout opts)
         since-cursor (:since opts)
         current-cursor (state/get-cursor)
         side (:side @state/client-state)]

     ;; Fast path: if cursor has advanced past :since, return immediately
     (if (and since-cursor (> current-cursor since-cursor))
       (let [current-state @state/client-state
             reason (relevance-reason current-state side false)]
         (when (:verbose opts)
           (println (format "⚡ Cursor advanced (%d → %d), returning immediately"
                           since-cursor current-cursor)))
         {:status :already-advanced
          :reason (or reason :cursor-advanced)
          :cursor current-cursor
          :run-active? (run-active? current-state)
          :has-prompt? (has-prompt? current-state side)})

       ;; Normal path: wait for state change
       (let [deadline (+ (System/currentTimeMillis) (* timeout-seconds 1000))
             initial-run-active? (run-active? @state/client-state)
             initial-log-count (count (get-in @state/client-state [:game-state :log]))]

         (when (:verbose opts)
           (println (format "💤 Waiting for relevant events (timeout: %ds, cursor: %d)..."
                           timeout-seconds current-cursor))
           (when initial-run-active?
             (println "   ⚡ Run already active - watching closely")))

         (loop [last-log-count initial-log-count]
           (Thread/sleep polling-delay)
           (let [current-state @state/client-state
                 current-log (get-in current-state [:game-state :log])
                 current-log-count (count current-log)
                 ;; Filter out AI debug chat messages (start with robot emoji)
                 ;; Log is oldest-first, so take-last gets the newest entries
                 new-entries-raw (when (> current-log-count last-log-count)
                                   (take-last (- current-log-count last-log-count) current-log))
                 new-entries (remove #(clojure.string/starts-with? (or (:text %) "") "🤖") new-entries-raw)
                 reason (relevance-reason current-state side initial-run-active?)]

             ;; Calculate ALL entries since we started waiting (not just last poll)
             ;; Log is oldest-first, so take-last gets newest entries
             (let [entries-since-start-raw (when (> current-log-count initial-log-count)
                                             (take-last (- current-log-count initial-log-count) current-log))
                   entries-since-start (remove #(clojure.string/starts-with? (or (:text %) "") "🤖") entries-since-start-raw)]

               (cond
                 ;; Found something relevant (game state)
                 reason
                 (do
                   (when (:verbose opts)
                     (println (format "⚡ Woke up: %s" (name reason)))
                     (println "")
                     (println "📜 Game log while you were waiting:")
                     (if (seq entries-since-start)
                       ;; Summarize run sequences, simplify basic action text
                       (doseq [entry (summarize-log-entries entries-since-start)]
                         (println (format "  • %s" (:text entry))))
                       (println "  (no new entries)")))
                   {:status :relevant-change
                    :reason reason
                    :cursor (state/get-cursor)
                    :new-log-entries entries-since-start
                    :run-active? (run-active? current-state)
                    :has-prompt? (has-prompt? current-state side)})

               ;; Check for "ping" wake signal in chat
               (some ping-message? new-entries-raw)
               (do
                 (when (:verbose opts)
                   (println "🏓 Woke up: ping")
                   (println "")
                   (println "📜 Game log while you were waiting:")
                   (if (seq entries-since-start)
                     (doseq [entry (summarize-log-entries entries-since-start)]
                       (println (format "  • %s" (:text entry))))
                     (println "  (no new entries)")))
                 {:status :ping
                  :reason :ping
                  :cursor (state/get-cursor)
                  :new-log-entries entries-since-start
                  :run-active? (run-active? current-state)
                  :has-prompt? (has-prompt? current-state side)})

               ;; Timeout
               (> (System/currentTimeMillis) deadline)
               (do
                 (when (:verbose opts)
                   (println "⏱️  Timeout - no relevant events")
                   (when (seq entries-since-start)
                     (println "")
                     (println "📜 Game log while you were waiting:")
                     (doseq [entry (summarize-log-entries entries-since-start)]
                       (println (format "  • %s" (:text entry))))))
                 {:status :timeout
                  :cursor (state/get-cursor)
                  :new-log-entries entries-since-start})

               ;; State changed but not relevant - keep waiting silently
               ;; (full log shown on wake, no need to spam ignored entries)
               (> current-log-count last-log-count)
               (recur current-log-count)

               ;; No change yet
               :else
               (recur last-log-count))))))))))

;; ============================================================================
;; Run Helper Functions
;; ============================================================================

(defn other-side
  "Return the opposite side"
  [side]
  (if (= side "runner") "corp" "runner"))

(defn current-run-ice
  "Get the ICE at the current run position from game state.

   During a run, position counts down as runner moves inward.
   Position N means you're at ICE index (N-1). Position 0 = at server.
   ICE list is indexed from innermost (0) to outermost (count-1).

   Parameters:
   - state: Client state map containing [:game-state :run] and [:game-state :corp :servers]

   Returns the ICE card map at current position, or nil if:
   - No active run
   - Position is 0 (at server)
   - Position is out of bounds
   - No ICE on the server"
  [state]
  (let [run (get-in state [:game-state :run])
        server (:server run)
        position (:position run)
        ice-list (get-in state [:game-state :corp :servers (keyword (last server)) :ices])
        ice-count (count ice-list)
        ice-index (dec position)]
    (when (and ice-list (> position 0) (<= position ice-count))
      (nth ice-list ice-index nil))))

;; ============================================================================
;; First-Seen Card Display
;; ============================================================================

(defn show-card-on-first-sight!
  "Display card text if this is the first time seeing it this session.
   Returns true if card was shown, false/nil if already seen or not found."
  [card-title]
  (when (and card-title (state/first-time-seeing? card-title))
    (load-cards-from-api!)
    (when-let [card (get @all-cards card-title)]
      (let [card-type (:type card)
            cost (:cost card)
            text (or (:text card) "")
            ;; Clean text: remove HTML tags, collapse whitespace
            clean-text (-> text
                          (clojure.string/replace #"<[^>]+>" "")
                          (clojure.string/replace #"\s+" " ")
                          clojure.string/trim)]
        (println (format "   📖 %s [%s%s]%s"
                        card-title
                        card-type
                        (if cost (str ", " cost "¢") "")
                        (if (not-empty clean-text)
                          (str ": " (if (> (count clean-text) 150)
                                      (str (subs clean-text 0 147) "...")
                                      clean-text))
                          "")))
        (state/mark-card-seen! card-title)
        true))))
