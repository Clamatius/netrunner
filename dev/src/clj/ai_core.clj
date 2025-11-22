(ns ai-core
  "Core utility functions for AI player - shared helpers used across modules"
  (:require [ai-websocket-client-v2 :as ws]
            [jinteki.cards :refer [all-cards]]
            [clj-http.client :as http]
            [cheshire.core :as json]))

;; ============================================================================
;; Side Comparison
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
   Returns status map with:
   - :status - :success (action completed), :waiting-input (prompt created), :error (failed)
   - :prompt - the prompt that was created (if :waiting-input)
   - :card-name - the card name being verified

   Distinguishes between:
   - Action completed: Card moved zones, log entry added, state changed
   - Action waiting for input: Only prompt created, card still in hand
   - Action failed: Nothing happened

   Waits up to max-wait-ms for the log entry to appear"
  [card-name card-initial-zone max-wait-ms]
  (let [initial-size (get-log-size)
        initial-prompt (ws/get-prompt)
        deadline (+ (System/currentTimeMillis) max-wait-ms)
        check-result (fn []
                      (let [state @ws/client-state
                            log (get-in state [:game-state :log])
                            current-size (count log)
                            current-prompt (ws/get-prompt)
                            ;; Check if card is still in original zone (hand)
                            side (keyword (:side state))
                            hand (get-in state [:game-state side :hand])
                            card-still-in-hand (some #(and (= (:title %) card-name)
                                                           (= (:zone %) card-initial-zone))
                                                    hand)
                            ;; Check if new prompt was created
                            new-prompt-created (and current-prompt
                                                   (not= current-prompt initial-prompt))
                            ;; Check if log entry mentions the card
                            card-in-log (let [recent-log (take-last 5 log)]
                                         (some #(when (string? (:text %))
                                                 (clojure.string/includes? (:text %) card-name))
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
            (Thread/sleep 200)
            (recur))
          {:status :error
           :reason "Action not confirmed in game log (timeout)"
           :card-name card-name})))))

;; ============================================================================
;; Card Name Parsing and Formatting
;; ============================================================================

(defn parse-card-reference
  "Parse card name with optional [N] index suffix
   Examples:
     \"Palisade\" -> {:title \"Palisade\" :index 0}
     \"Palisade [1]\" -> {:title \"Palisade\" :index 1}
   Returns map with :title and :index (0-based)"
  [card-name]
  (if-let [[_ title idx] (re-matches #"(.+?)\s*\[(\d+)\]" card-name)]
    {:title title :index (Integer/parseInt idx)}
    {:title card-name :index 0}))

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
;; Agenda Helpers
;; ============================================================================

(defn find-scorable-agendas
  "Find all installed Corp agendas that have enough advancement counters to score.
   Returns sequence of maps with :card, :title, :counters, :requirement

   Note: This does a simple counter check (counters >= requirement).
   It does NOT detect effects like 'cannot score this turn' or similar restrictions.
   Therefore, use conservatively - if this returns agendas, assume they MIGHT be scorable."
  []
  (let [state @ws/client-state
        side (:side state)]
    (if (side= "Corp" side)
      (let [servers (get-in state [:game-state :corp :servers])
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
;; Card Lookup Helpers
;; ============================================================================

(defn find-card-in-hand
  "Find card in hand by name or index
   Supports [N] suffix for duplicate cards: \"Sure Gamble [1]\"
   Returns card object or nil if not found"
  [name-or-index]
  (let [state @ws/client-state
        side (:side state)
        hand (get-in state [:game-state (keyword side) :hand])]
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
   :type (:type card)})

(defn find-installed-card
  "Find an installed card by title in the rig
   Supports [N] suffix for duplicate cards: \"Corroder [1]\"
   Searches programs, hardware, and resources"
  [card-name]
  (let [state @ws/client-state
        rig (get-in state [:game-state :runner :rig])
        all-installed (concat (:program rig) (:hardware rig) (:resource rig))
        {:keys [title index]} (parse-card-reference card-name)
        matches (filter #(= title (:title %)) all-installed)]
    (nth (vec matches) index nil)))

(defn find-installed-corp-card
  "Find an installed Corp card by title
   Supports [N] suffix for duplicate cards: \"Palisade [1]\"
   Searches all servers for ICE, assets, and upgrades"
  [card-name]
  (let [state @ws/client-state
        servers (get-in state [:game-state :corp :servers])
        ;; Get all ICE from all servers
        all-ice (mapcat :ices (vals servers))
        ;; Get all content (assets/upgrades) from all servers
        all-content (mapcat :content (vals servers))
        all-installed (concat all-ice all-content)
        {:keys [title index]} (parse-card-reference card-name)
        matches (filter #(= title (:title %)) all-installed)]
    (nth (vec matches) index nil)))

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

   Returns: {:normalized <game-name> :original <input> :changed? <bool>}"
  [server-input]
  (let [s (clojure.string/lower-case (clojure.string/trim server-input))
        normalized (cond
                     ;; Central servers
                     (= s "hq") "HQ"
                     (or (= s "rd") (= s "r&d")) "R&D"
                     (= s "archives") "Archives"

                     ;; Remote servers - handle various formats
                     ;; remote1, remote 1, r1, server1, server 1 → Server 1
                     (re-matches #"(?:remote|r|server)\s*(\d+)" s)
                     (let [num (second (re-matches #"(?:remote|r|server)\s*(\d+)" s))]
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
;; Wait Helpers
;; ============================================================================

(defn wait-for-prompt
  "Wait for a prompt to appear (up to max-seconds)
   Returns prompt or nil if timeout"
  [max-seconds]
  (loop [checks 0]
    (if (< checks max-seconds)
      (if-let [prompt (ws/get-prompt)]
        prompt
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

;; ============================================================================
;; Run Helper Functions
;; ============================================================================

(defn other-side
  "Return the opposite side"
  [side]
  (if (= side "runner") "corp" "runner"))
