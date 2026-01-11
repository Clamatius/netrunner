(ns ai-heuristic-corp
  "Heuristic-based Corp AI player for tutorial decks.

   Decision priority (highest first):
   1. Score scorable agenda
   2. Protect unprotected agenda with ICE
   3. Install agenda to protected/empty remote
   4. Play economy if credits low (before advancing - prevents stranding broke)
   4.5. Use rezzed click assets when low (Regolith gives 3¢, better than advancing)
   5. Advance installed agenda (smart: won't strand at scorable with 0 clicks)
   6. Use rezzed asset abilities (drain Regolith even when not low)
   7. Rez CLICK assets (Regolith) - rez before using, minimize exposure
      (Drip assets like PAD Campaign should rez at opponent EOT - TODO)
   8. Install economy assets to remotes
   9. Install ICE on centrals if needed
   10. Play economy operations (fallback if not low)
   11. Take credits if low
   12. Draw if hand is small
   13. Default: take credit

   Run Response (during Runner's turn):
   - respond-to-run! monitors active runs and makes rez/fire decisions
   - Heuristic: rez ICE if protecting valuable content and affordable
   - Always fire unbroken subroutines (for tutorial decks)

   Usage:
     (require '[ai-heuristic-corp :as bot])
     (bot/play-turn)      ; Make one decision and execute
     (bot/play-full-turn) ; Play until no clicks remain
     (bot/respond-to-run!) ; React to Runner's run (call during their turn)"
  (:require [ai-state :as state]
            [ai-core :as core]
            [ai-card-actions :as cards]
            [ai-basic-actions :as actions]
            [ai-prompts :as prompts]
            [ai-runs :as runs]
            [clojure.string :as str]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def config
  {:min-credits 8          ; Below this, prioritize economy (high enough to afford Hedge Fund)
   :min-hand-size 3        ; Below this, consider drawing
   :central-ice-target 1   ; Desired ICE per central
   :log-decisions true})   ; Print decision reasoning

(defn log-decision [& args]
  (when (:log-decisions config)
    (apply println "🤖" args)))

;; ============================================================================
;; State Query Helpers
;; ============================================================================

(defn my-credits []
  (state/corp-credits))

(defn my-clicks []
  (state/corp-clicks))

(defn my-hand []
  (state/corp-hand))

(defn cards-of-type
  "Filter cards by type from a collection"
  [cards card-type]
  (filter #(= card-type (:type %)) cards))

(defn agendas-in-hand []
  (cards-of-type (my-hand) "Agenda"))

(defn ice-in-hand []
  (cards-of-type (my-hand) "ICE"))

(defn operations-in-hand []
  (cards-of-type (my-hand) "Operation"))

(defn assets-in-hand []
  (cards-of-type (my-hand) "Asset"))

(defn economy-operations
  "Find playable economy operations (gain credits)"
  []
  (let [ops (operations-in-hand)
        credits (my-credits)]
    ;; Filter to operations we can afford and that are economy
    ;; Economy ops typically have "Gain" in their text
    (filter (fn [card]
              (and (:playable card)
                   (or (str/includes? (str (:title card)) "Hedge Fund")
                       (str/includes? (str (:title card)) "IPO")
                       (str/includes? (str (:title card)) "Beanstalk")
                       ;; Generic check: card text mentions gaining credits
                       (str/includes? (str (:text card)) "Gain"))))
            ops)))

(defn get-remote-servers
  "Get all remote servers with their content and ICE"
  []
  (let [servers (state/corp-servers)]
    (->> servers
         (filter (fn [[k _]] (str/starts-with? (name k) "remote")))
         (into {}))))

(defn server-has-ice?
  "Check if a server has at least one ICE installed"
  [server-key]
  (pos? (count (state/server-ice server-key))))

(defn server-content
  "Get content (agendas/assets) installed in a server"
  [server-key]
  (state/server-cards server-key))

(defn installed-agendas
  "Find all installed agendas in remotes"
  []
  (let [remotes (get-remote-servers)]
    (for [[server-key _] remotes
          card (server-content server-key)
          :when (= "Agenda" (:type card))]
      (assoc card :server server-key))))

(defn scorable-agendas
  "Find agendas that have enough advancement counters to score"
  []
  (filter (fn [agenda]
            (let [counters (or (:advance-counter agenda) 0)
                  required (or (:advancementcost agenda) 999)]
              (>= counters required)))
          (installed-agendas)))

(defn advanceable-agendas
  "Find installed agendas that need more advancement to be scored"
  []
  (filter (fn [agenda]
            (let [counters (or (:advance-counter agenda) 0)
                  required (or (:advancementcost agenda) 999)]
              (< counters required)))
          (installed-agendas)))

(defn unprotected-remotes-with-agendas
  "Find remotes that have agendas but no ICE"
  []
  (let [remotes (get-remote-servers)]
    (for [[server-key _] remotes
          :let [content (server-content server-key)
                has-agenda (some #(= "Agenda" (:type %)) content)
                has-ice (server-has-ice? server-key)]
          :when (and has-agenda (not has-ice))]
      server-key)))

(defn protected-or-empty-remotes
  "Find remotes that either have ICE or are empty (good for installing agendas)"
  []
  (let [remotes (get-remote-servers)]
    (for [[server-key _] remotes
          :let [content (server-content server-key)
                has-ice (server-has-ice? server-key)]
          :when (and has-ice (empty? content))]
      server-key)))

(defn central-ice-counts
  "Return map of central server -> ICE count"
  []
  {:hq (count (state/server-ice :hq))
   :rd (count (state/server-ice :rd))
   :archives (count (state/server-ice :archives))})

(defn weakest-central
  "Find the central with least ICE (nil if all meet target)"
  []
  (let [counts (central-ice-counts)
        target (:central-ice-target config)
        under-target (filter (fn [[_ cnt]] (< cnt target)) counts)]
    (when (seq under-target)
      (key (apply min-key val under-target)))))

;; ============================================================================
;; Asset Helpers
;; ============================================================================

(defn installed-assets
  "Find all installed assets in remotes"
  []
  (let [remotes (get-remote-servers)]
    (for [[server-key _] remotes
          card (server-content server-key)
          :when (= "Asset" (:type card))]
      (assoc card :server server-key))))

(defn rezzed-assets-with-click-abilities
  "Find rezzed assets that have usable click abilities.
   Returns assets with :ability-idx for the first playable click ability.

   Note: Server sends :cost-label like '[Click]' rather than structured data.
   We detect click abilities by checking if cost-label contains '[Click]'."
  []
  (for [asset (installed-assets)
        :when (:rezzed asset)
        :let [abilities (:abilities asset)
              ;; Find first ability that costs a click
              ;; Check cost-label for [Click] since :action isn't always present
              click-ability-idx (first
                                  (keep-indexed
                                    (fn [idx ab]
                                      (when (let [cost-label (str (:cost-label ab ""))
                                                  has-click-cost (str/includes? cost-label "[Click]")]
                                              has-click-cost)
                                        idx))
                                    abilities))]
        :when click-ability-idx]
    (assoc asset :ability-idx click-ability-idx)))

(defn click-economy-assets
  "Find unrezzed 'click' economy assets (Regolith, etc.)
   These should be rezzed immediately before using their click ability."
  []
  (filter (fn [asset]
            (and (not (:rezzed asset))
                 (or (str/includes? (str (:title asset)) "Regolith")
                     (str/includes? (str (:title asset)) "Rashida")
                     ;; Assets with click abilities that give credits
                     (str/includes? (str (:title asset)) "NGO Front"))))
          (installed-assets)))

(defn drip-economy-assets
  "Find unrezzed 'drip' economy assets (PAD Campaign, etc.)
   These should be rezzed at opponent's EOT to minimize trash window."
  []
  (filter (fn [asset]
            (and (not (:rezzed asset))
                 (or (str/includes? (str (:title asset)) "PAD Campaign")
                     (str/includes? (str (:title asset)) "Marilyn")
                     (str/includes? (str (:title asset)) "Nico Campaign")
                     ;; Generic: "When your turn begins" suggests drip
                     (str/includes? (str (:text asset)) "When your turn begins"))))
          (installed-assets)))

(defn can-rez-asset?
  "Check if we can afford to rez an asset"
  [asset credits]
  (let [rez-cost (or (:cost asset) 0)]
    (>= credits rez-cost)))

(defn empty-remote-for-asset
  "Find an empty remote (with ICE) for installing an asset, or nil"
  []
  (first (protected-or-empty-remotes)))

;; ============================================================================
;; Advance Logic Helpers
;; ============================================================================

(defn should-advance?
  "Determine if we should advance an agenda this click.
   Returns the agenda to advance, or nil if we shouldn't.

   Key insights:
   - Don't advance if it would make the agenda scorable but leave us with 0 clicks
   - Don't advance to 0 credits if we have economy operations available"
  [clicks credits]
  (when (and (pos? clicks) (>= credits 1))
    (let [agendas (advanceable-agendas)
          has-econ (seq (economy-operations))
          would-be-broke (= credits 1)]  ; advancing costs 1, would leave us at 0
      (when (seq agendas)
        (let [agenda (first agendas)
              current-counters (or (:advance-counter agenda) 0)
              required (:advancementcost agenda)
              advances-needed (- required current-counters)]
          (cond
            ;; If advancing would leave us broke and we have economy, do economy first
            (and would-be-broke has-econ)
            nil

            ;; If we need exactly 1 more advance and have 2+ clicks,
            ;; advance now (next iteration will score)
            (and (= advances-needed 1) (>= clicks 2))
            agenda

            ;; If we need exactly 1 more advance but only 1 click,
            ;; DON'T advance - we'd be scorable with 0 clicks
            ;; Better to take credit and score next turn
            (and (= advances-needed 1) (= clicks 1))
            nil

            ;; If we need 2+ advances, safe to advance
            (> advances-needed 1)
            agenda

            ;; Shouldn't happen but be safe
            :else nil))))))

;; ============================================================================
;; Decision Logic
;; ============================================================================

(defn decide-action
  "Analyze game state and decide what action to take.
   Returns a map with :action and :args keys, or nil if nothing to do."
  []
  (let [clicks (my-clicks)
        credits (my-credits)
        hand (my-hand)
        hand-size (count hand)]

    ;; No clicks = can't do anything
    (when (and clicks (pos? clicks))
      (cond
        ;; 1. Score scorable agenda (top priority!)
        (seq (scorable-agendas))
        (let [agenda (first (scorable-agendas))]
          (log-decision "SCORE:" (:title agenda) "is scorable!")
          {:action :score :args {:card-name (:title agenda)}})

        ;; 2. Protect unprotected agenda with ICE
        (and (seq (unprotected-remotes-with-agendas))
             (seq (ice-in-hand)))
        (let [server-key (first (unprotected-remotes-with-agendas))
              ice (first (ice-in-hand))
              server-num (Integer/parseInt (str/replace (name server-key) "remote" ""))
              server-name (str "Server " server-num)]
          (log-decision "PROTECT: Installing" (:title ice) "on" server-name)
          {:action :install-ice :args {:card-name (:title ice) :server server-name}})

        ;; 3. Install agenda to protected or empty remote
        (and (seq (agendas-in-hand))
             (or (seq (protected-or-empty-remotes))
                 ;; Or create new remote if no remotes exist
                 (empty? (get-remote-servers))))
        (let [agenda (first (agendas-in-hand))
              ;; Prefer protected remote, else new remote
              server (if-let [protected (first (protected-or-empty-remotes))]
                       (let [num (Integer/parseInt (str/replace (name protected) "remote" ""))]
                         (str "Server " num))
                       "New remote")]
          (log-decision "INSTALL AGENDA:" (:title agenda) "to" server)
          {:action :install :args {:card-name (:title agenda) :server server}})

        ;; 4. Play economy if low on credits (before advancing)
        ;; This prevents stranding ourselves broke while advancing
        (and (< credits (:min-credits config))
             (seq (economy-operations)))
        (let [op (first (economy-operations))]
          (log-decision "ECON FIRST: Playing" (:title op) "(credits low:" credits ")")
          {:action :play :args {:card-name (:title op)}})

        ;; 4.5. Use rezzed click assets when low on credits (Regolith gives 3¢)
        ;; This is better than taking 1¢ or advancing (costs 1¢)
        (and (< credits (:min-credits config))
             (seq (rezzed-assets-with-click-abilities)))
        (let [asset (first (rezzed-assets-with-click-abilities))
              ability-idx (:ability-idx asset)]
          (log-decision "USE ASSET (low credits):" (:title asset) "ability" ability-idx)
          {:action :use-ability :args {:card-name (:title asset) :ability-idx ability-idx}})

        ;; 5. Advance installed agenda (with smart click management)
        (should-advance? clicks credits)
        (let [agenda (should-advance? clicks credits)]
          (log-decision "ADVANCE:" (:title agenda)
                       (str "(" (or (:advance-counter agenda) 0)
                            "/" (:advancementcost agenda) ")"))
          {:action :advance :args {:card-name (:title agenda)}})

        ;; 6. Use rezzed asset abilities (like Regolith click ability)
        ;; Even when not low on credits - draining Regolith is still good
        (seq (rezzed-assets-with-click-abilities))
        (let [asset (first (rezzed-assets-with-click-abilities))
              ability-idx (:ability-idx asset)]
          (log-decision "USE ASSET:" (:title asset) "ability" ability-idx)
          {:action :use-ability :args {:card-name (:title asset) :ability-idx ability-idx}})

        ;; 7. Rez CLICK economy assets (Regolith, etc.) - rez before using
        ;; Don't rez drip assets during our turn - wait for opponent EOT
        (and (seq (click-economy-assets))
             (can-rez-asset? (first (click-economy-assets)) credits))
        (let [asset (first (click-economy-assets))]
          (log-decision "REZ CLICK ASSET:" (:title asset) "(will use next)")
          {:action :rez :args {:card-name (:title asset)}})

        ;; 8. Install economy assets to remotes
        (and (seq (assets-in-hand))
             (or (empty-remote-for-asset)
                 ;; Create new remote if we have ICE and no remotes
                 (and (seq (ice-in-hand)) (empty? (get-remote-servers)))))
        (let [asset (first (assets-in-hand))
              server (if-let [remote (empty-remote-for-asset)]
                       (let [num (Integer/parseInt (str/replace (name remote) "remote" ""))]
                         (str "Server " num))
                       "New remote")]
          (log-decision "INSTALL ASSET:" (:title asset) "to" server)
          {:action :install :args {:card-name (:title asset) :server server}})

        ;; 9. Install ICE on weak centrals
        (and (seq (ice-in-hand))
             (weakest-central)
             (>= credits (:cost (first (ice-in-hand)) 0)))
        (let [ice (first (ice-in-hand))
              central (weakest-central)
              server-name (str/upper-case (name central))]
          (log-decision "FORTIFY:" server-name "with" (:title ice))
          {:action :install-ice :args {:card-name (:title ice) :server server-name}})

        ;; 10. Play economy operations (fallback if not already played)
        (seq (economy-operations))
        (let [op (first (economy-operations))]
          (log-decision "ECONOMY: Playing" (:title op))
          {:action :play :args {:card-name (:title op)}})

        ;; 11. Take credits if low
        (< credits (:min-credits config))
        (do
          (log-decision "LOW CREDITS: Taking credit (" credits "credits)")
          {:action :credit :args {}})

        ;; 12. Draw if hand is small
        (< hand-size (:min-hand-size config))
        (do
          (log-decision "LOW CARDS: Drawing (" hand-size "cards)")
          {:action :draw :args {}})

        ;; 13. Default: take credit
        :else
        (do
          (log-decision "DEFAULT: Taking credit")
          {:action :credit :args {}})))))

;; ============================================================================
;; Action Execution
;; ============================================================================

(defn execute-decision
  "Execute a decision returned by decide-action.
   Returns result map from the action function."
  [{:keys [action args]}]
  (case action
    :score       (cards/score-agenda! (:card-name args))
    :advance     (cards/advance-card! (:card-name args))
    :install     (cards/install-card! (:card-name args) (:server args))
    :install-ice (cards/install-card! (:card-name args) (:server args))
    :play        (cards/play-card! (:card-name args))
    :rez         (cards/rez-card! (:card-name args))
    :use-ability (cards/use-ability! (:card-name args) (:ability-idx args))
    :credit      (actions/take-credit!)
    :draw        (actions/draw-card!)
    :end-turn    (actions/end-turn!)
    (do
      (println "❌ Unknown action:" action)
      {:status :error :reason (str "Unknown action: " action)})))

(defn handle-prompt-if-needed
  "Check for and handle any prompts that need resolution.
   Returns true if a prompt was handled, false otherwise."
  []
  (when-let [prompt (state/get-prompt)]
    (let [prompt-type (:prompt-type prompt)
          msg (or (:msg prompt) "")
          choices (:choices prompt)
          selectable (:selectable prompt)]
      (cond
        ;; Waiting prompts - nothing to do
        (= :waiting prompt-type)
        false

        ;; Discard prompt - select cards to discard
        ;; Heuristic: discard duplicates first, then lowest-value cards
        (and (str/includes? msg "Discard")
             (seq selectable))
        (do
          (log-decision "DISCARD: Selecting card to discard")
          ;; For now, just pick the first selectable card
          ;; TODO: smarter discard selection (duplicates, low-value)
          (prompts/choose-card! 0)
          true)

        ;; Regular choice prompts
        (seq choices)
        (do
          (log-decision "PROMPT: Handling" msg)
          ;; Simple heuristic: choose first option
          (prompts/choose! 0)
          true)

        ;; Select prompt without discard message - pick first
        (seq selectable)
        (do
          (log-decision "SELECT: Choosing first option for" msg)
          (prompts/choose-card! 0)
          true)

        :else false))))

;; ============================================================================
;; Main Entry Points
;; ============================================================================

(defn play-turn
  "Make one decision and execute it.
   Handles prompts if present.
   Returns the result of the action taken."
  []
  (println "\n" (str/join "" (repeat 50 "-")))
  (println "🤖 HEURISTIC CORP - Thinking...")
  (println (str/join "" (repeat 50 "-")))

  ;; First, handle any pending prompts
  (when (handle-prompt-if-needed)
    (Thread/sleep 500))  ; Brief pause after prompt

  ;; Then decide and execute
  (if-let [decision (decide-action)]
    (let [result (execute-decision decision)]
      (println (str/join "" (repeat 50 "-")))
      result)
    (do
      (println "🤖 No action available (no clicks?)")
      {:status :no-action})))

(defn play-full-turn
  "Play the full turn until no clicks remain.
   Handles the mulligan if it's turn 0.
   Also handles EOT prompts like discard."
  []
  (println "\n" (str/join "" (repeat 60 "=")))
  (println "🤖 HEURISTIC CORP - Starting Full Turn")
  (println (str/join "" (repeat 60 "=")))

  ;; Ensure turn is started
  (actions/ensure-turn-started!)

  ;; Main action loop
  (loop [actions-taken 0]
    (let [clicks (my-clicks)]
      (if (and clicks (pos? clicks))
        (do
          (play-turn)
          (Thread/sleep 300)  ; Brief pause between actions
          (recur (inc actions-taken)))
        (do
          (println "\n" (str/join "" (repeat 60 "=")))
          (println (str "🤖 Turn complete. Took " actions-taken " actions."))
          (println (str/join "" (repeat 60 "=")))

          ;; Handle any EOT prompts (like discard)
          (loop [prompts-handled 0]
            (if (handle-prompt-if-needed)
              (do
                (Thread/sleep 300)
                (recur (inc prompts-handled)))
              (when (pos? prompts-handled)
                (println (str "🤖 Handled " prompts-handled " EOT prompt(s)")))))

          ;; End turn
          (actions/smart-end-turn!)
          {:actions-taken actions-taken})))))

(defn keep-or-mull
  "Decide whether to keep or mulligan based on hand contents.
   Simple heuristic: keep if we have at least 1 ICE and 1 economy card."
  []
  (let [hand (my-hand)
        has-ice (seq (ice-in-hand))
        has-econ (or (seq (economy-operations))
                     (>= (my-credits) 5))
        has-agenda (seq (agendas-in-hand))]
    (if (and has-ice (or has-econ has-agenda))
      (do
        (log-decision "KEEP: Have ICE and economy/agenda")
        (prompts/choose! "Keep"))
      (do
        (log-decision "MULLIGAN: Missing ICE or economy")
        (prompts/choose! "Mulligan")))))

;; ============================================================================
;; Convenience/Debug
;; ============================================================================

(defn status
  "Show current decision-relevant state"
  []
  (println "\n🤖 HEURISTIC CORP STATUS")
  (println "Credits:" (my-credits) "| Clicks:" (my-clicks) "| Hand:" (count (my-hand)))
  (println "\nHand contents:")
  (doseq [[type cards] (group-by :type (my-hand))]
    (println (str "  " type ": " (str/join ", " (map :title cards)))))
  (println "\nInstalled agendas:")
  (doseq [agenda (installed-agendas)]
    (println (str "  " (:title agenda)
                 " (" (or (:advance-counter agenda) 0)
                 "/" (:advancementcost agenda) ")"
                 " in " (name (:server agenda)))))
  (println "\nInstalled assets:")
  (doseq [asset (installed-assets)]
    (println (str "  " (:title asset)
                 (if (:rezzed asset) " [REZZED]" " [unrezzed]")
                 " in " (name (:server asset)))))
  (println "\nScorables:" (map :title (scorable-agendas)))
  (println "Usable assets:" (map :title (rezzed-assets-with-click-abilities)))
  (println "Unprotected agendas in:" (map name (unprotected-remotes-with-agendas)))
  (println "Central ICE:" (central-ice-counts))
  (println "\nDecision:" (decide-action)))

;; ============================================================================
;; Run Response (During Runner's Turn)
;; ============================================================================

(defn get-run-server
  "Get the server being run on, or nil if no active run"
  []
  (let [run (get-in @state/client-state [:game-state :run])]
    (when run
      (let [server (:server run)]
        ;; Server is like ["HQ"] or ["Server" "1"]
        (if (= 1 (count server))
          (keyword (str/lower-case (first server)))
          (keyword (str "remote" (second server))))))))

(defn get-run-ice
  "Get list of ICE on the server being run"
  []
  (when-let [server-key (get-run-server)]
    (state/server-ice server-key)))

(defn get-current-run-ice
  "Get the ICE currently being approached/encountered"
  []
  (let [run (get-in @state/client-state [:game-state :run])
        server-key (get-run-server)]
    (when (and run server-key)
      (let [position (:position run)
            ice-list (state/server-ice server-key)
            ice-count (count ice-list)
            ;; Position counts from server outward (1 = outermost)
            ;; ice-list is indexed 0 = innermost
            ice-index (- ice-count position)]
        (when (and (pos? position) (<= position ice-count))
          (nth ice-list ice-index nil))))))

(defn server-has-agenda?
  "Check if a server has an agenda installed"
  [server-key]
  (let [content (server-content server-key)]
    (some #(= "Agenda" (:type %)) content)))

(defn server-has-valuable-content?
  "Check if server has valuable content worth protecting.
   Agendas are always valuable. Unrezzed assets might be too."
  [server-key]
  (let [content (server-content server-key)]
    (or (some #(= "Agenda" (:type %)) content)
        ;; Unrezzed assets could be valuable (or bluffs)
        (some #(and (= "Asset" (:type %)) (not (:rezzed %))) content))))

(defn should-rez-ice?
  "Heuristic: should we rez this ICE?

   Currently simple logic for tutorial decks:
   - Always rez if we can afford it and server has valuable content
   - Don't rez if server is empty/only rezzed assets
   - Don't rez if we can't afford it

   Future improvements:
   - Consider runner's credits vs rez cost
   - Consider ICE quality (ETR vs taxing)
   - Consider if runner can break it"
  [ice]
  (let [credits (my-credits)
        rez-cost (or (:cost ice) 0)
        can-afford? (>= credits rez-cost)
        server-key (get-run-server)
        has-value? (when server-key (server-has-valuable-content? server-key))
        ;; Central servers are always worth protecting
        is-central? (contains? #{:hq :rd :archives} server-key)]
    (cond
      ;; Already rezzed - nothing to decide
      (:rezzed ice)
      false

      ;; Can't afford - don't rez
      (not can-afford?)
      (do
        (log-decision "REZ DECISION: Can't afford" (:title ice) "(" rez-cost "¢, have" credits "¢)")
        false)

      ;; Central servers - always rez if affordable
      is-central?
      (do
        (log-decision "REZ DECISION: Rezing" (:title ice) "protecting central")
        true)

      ;; Remote with valuable content - rez
      has-value?
      (do
        (log-decision "REZ DECISION: Rezing" (:title ice) "protecting valuable remote")
        true)

      ;; Empty remote - don't waste credits
      :else
      (do
        (log-decision "REZ DECISION: Declining rez on" (:title ice) "(server has no value)")
        false))))

(defn calculate-rez-strategy
  "Calculate which ICE to rez on the current run.
   Returns a set of ICE titles to rez, or nil for --no-rez."
  []
  (let [ice-list (get-run-ice)]
    (if (empty? ice-list)
      nil  ; No ICE, nothing to decide
      (let [ice-to-rez (->> ice-list
                            (filter #(not (:rezzed %)))
                            (filter should-rez-ice?)
                            (map :title)
                            set)]
        (if (empty? ice-to-rez)
          :no-rez  ; Don't rez anything
          ice-to-rez)))))

(defn respond-to-run!
  "React to an active run. Call this during Runner's turn.

   Monitors the run and makes rez/fire decisions based on heuristics.
   Uses the existing monitor-run! infrastructure with calculated strategy.

   Returns when run ends or no active run.

   Usage:
     ;; In a loop during Runner's turn:
     (while (has-active-run?)
       (respond-to-run!)
       (Thread/sleep 500))"
  []
  (let [run (get-in @state/client-state [:game-state :run])]
    (if (nil? run)
      (do
        (println "🤖 No active run to respond to")
        {:status :no-run})
      (let [rez-strategy (calculate-rez-strategy)]
        (println "\n" (str/join "" (repeat 50 "-")))
        (println "🤖 HEURISTIC CORP - Responding to Run")
        (println (str/join "" (repeat 50 "-")))
        (println "Server:" (get-run-server))
        (println "ICE:" (count (get-run-ice)) "installed")
        (println "Strategy:" (if (= :no-rez rez-strategy)
                               "no-rez (decline all)"
                               (str "rez " (str/join ", " rez-strategy))))

        ;; Build flags for monitor-run!
        (let [args (cond
                     (= :no-rez rez-strategy)
                     ["--no-rez" "--fire-unbroken"]

                     (set? rez-strategy)
                     (concat (mapcat (fn [title] ["--rez" title]) rez-strategy)
                             ["--fire-unbroken"])

                     :else
                     ["--fire-unbroken"])]
          (println "Flags:" (str/join " " args))
          (apply runs/monitor-run! args))))))

(defn has-active-run?
  "Check if there's an active run in progress"
  []
  (some? (get-in @state/client-state [:game-state :run])))

(defn watch-for-runs!
  "Continuously monitor for runs and respond to them.
   Blocks until interrupted or game ends.

   Usage:
     ;; In a separate thread or background process:
     (future (watch-for-runs!))"
  []
  (println "🤖 HEURISTIC CORP - Watching for runs...")
  (loop []
    (when (has-active-run?)
      (respond-to-run!)
      (Thread/sleep 200))
    (Thread/sleep 300)  ; Poll interval when no run
    (recur)))

(defn start-autonomous!
  "Main autonomous loop for Match Orchestration.
   Handles both playing turns and responding to runs."
  []
  (println "🤖 HEURISTIC CORP - Starting autonomous loop")
  (loop []
    (try
      (let [game-state @state/client-state
            my-turn? (= "corp" (:active-player (:game-state game-state)))]

        ;; 1. Handle Prompts (Priority)
        (when (handle-prompt-if-needed)
          (Thread/sleep 500))

        ;; 1.5 Attempt to start turn if valid (e.g. opponent ended)
        (let [start-check (actions/can-start-turn?)]
          (when (:can-start start-check)
            (println "🤖 HEURISTIC CORP - Auto-starting turn")
            (actions/start-turn!)
            (Thread/sleep 500)))

        ;; 2. If my turn, play
        (when (and my-turn? (not (state/get-prompt)))
          ;; Make moves if we have clicks
          (when (pos? (my-clicks))
            (play-turn)))

        ;; 3. If opponent turn, watch for runs
        (when (and (not my-turn?) (has-active-run?))
          (respond-to-run!)
          (Thread/sleep 500))

        ;; Sleep
        (Thread/sleep 500))
      (catch Exception e
        (println "❌ HEURISTIC CORP ERROR:" (.getMessage e))
        (.printStackTrace e)
        (Thread/sleep 5000)))
    (recur)))
