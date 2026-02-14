(ns ai-prompts
  "Prompt handling, choices, mulligan, and discard management"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-core :as core]))

;; ============================================================================
;; Prompts & Choices
;; ============================================================================

(defn choose-by-index!
  "Make a choice from current prompt by index or UUID.
   Usage: (choose-by-index! 0)        ; choose first option
          (choose-by-index! \"uuid\")  ; choose by UUID

   For choosing by value text (e.g. \"Keep\", \"Steal\"), use choose-by-value! instead."
  [choice]
  (let [prompt (state/get-prompt)]
    (if prompt
      (do
        (ws/choose! choice)
        (Thread/sleep core/quick-delay)
        (core/with-cursor {:status :success}))
      (do
        (println "⚠️  No active prompt")
        (core/with-cursor {:status :error :reason "No active prompt"})))))

(defn choose-option!
  "Choose from prompt by index (side-aware)"
  [index]
  (let [client-state @state/client-state
        side (:side client-state)
        ;; Normalize side to lowercase to match game state keys (:runner, :corp)
        side-kw (when side (keyword (clojure.string/lower-case side)))
        gameid (:gameid client-state)
        prompt (get-in client-state [:game-state side-kw :prompt-state])
        choice (nth (:choices prompt) index nil)
        choice-uuid (:uuid choice)]
    (if choice-uuid
      (do
        (println (str "✅ Chose: " (:value choice)))
        (ws/send-message! :game/action
                          {:gameid gameid
                           :command "choice"
                           :args {:choice {:uuid choice-uuid}}})
        (Thread/sleep core/standard-delay)
        (core/with-cursor {:status :success :choice choice}))
      (do
        (println (str "❌ Invalid choice index: " index))
        (core/with-cursor {:status :error :reason "Invalid choice index"})))))

(defn choose-by-value!
  "Choose from prompt by matching value/label text (case-insensitive substring match).
   Usage: (choose-by-value! \"steal\") or (choose-by-value! \"keep\")"
  [value-text]
  (let [client-state @state/client-state
        side (:side client-state)
        prompt (get-in client-state [:game-state (keyword side) :prompt-state])
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
      (let [choice (nth choices matching-idx)]
        (println (str "✅ Chose: " (:value choice)))
        (choose-option! matching-idx))
      (do
        (println (str "❌ No choice matching \"" value-text "\" found"))
        (println "Available choices:")
        (doseq [[idx choice] (map-indexed vector choices)]
          (println (str "  " idx ". " (core/format-choice choice))))
        (core/with-cursor {:status :error :reason "No matching choice"})))))

(defn choose-card!
  "Choose a card from selectable cards in current prompt by index.
   Used for select prompts like 'Send a Message' (choose card to trash).

   Usage: (choose-card! 0)  ; Select first selectable card
          (choose-card! 2)  ; Select third selectable card"
  [index]
  (let [client-state @state/client-state
        side-str (:side client-state)
        side (when side-str (keyword (clojure.string/lower-case side-str)))
        prompt (get-in client-state [:game-state side :prompt-state])
        selectable (:selectable prompt)
        eid (:eid prompt)]
    (cond
      (not= "select" (:prompt-type prompt))
      (do
        (println "❌ No select prompt active")
        (when prompt
          (println (format "   Current prompt type: %s" (:prompt-type prompt))))
        (core/with-cursor {:status :error :reason "No select prompt"}))

      (empty? selectable)
      (do
        (println "❌ No selectable cards in current prompt")
        (core/with-cursor {:status :error :reason "No selectable cards"}))

      (not (< -1 index (count selectable)))
      (do
        (println (format "❌ Invalid index: %d (only %d selectable cards, use 0-%d)"
                        index (count selectable) (dec (count selectable))))
        (core/with-cursor {:status :error :reason "Invalid index"}))

      :else
      (let [cid-or-card (nth selectable index)
            ;; Selectable can be CID strings or card maps - resolve CIDs to cards
            card (if (string? cid-or-card)
                   (core/find-card-by-cid cid-or-card)
                   cid-or-card)]
        (if card
          (do
            (println (format "📇 Selecting card: %s (index %d)" (:title card) index))
            (ws/select-card! card eid)
            (Thread/sleep core/short-delay)
            (core/with-cursor {:status :success :card card}))
          (do
            (println (format "❌ Could not resolve card at index %d" index))
            (core/with-cursor {:status :error :reason "Card resolution failed"})))))))

(defn- find-card-in-selectable
  "Find a card in the selectable list by name (case-insensitive substring match).
   Returns the resolved card map or nil."
  [card-name selectable]
  (let [name-lower (clojure.string/lower-case (str card-name))]
    (first
     (keep (fn [cid-or-card]
             (let [card (if (string? cid-or-card)
                          (core/find-card-by-cid cid-or-card)
                          cid-or-card)]
               (when (and card
                          (clojure.string/includes?
                           (clojure.string/lower-case (str (:title card)))
                           name-lower))
                 card)))
           selectable))))

(defn multi-choose!
  "Select multiple cards from a select prompt (e.g., discard to hand size).
   Cards can be specified by name (substring match) or index.

   Usage: (multi-choose! \"Hedge Fund\" \"IPO\" \"Rashida\")    ; By name
          (multi-choose! 0 1 2 3)                              ; By index
          (multi-choose! \"Hedge Fund\" 1 \"IPO\")             ; Mixed

   The prompt auto-resolves when enough cards are selected."
  [& card-refs]
  (let [client-state @state/client-state
        side-str (:side client-state)
        side (when side-str (keyword (clojure.string/lower-case side-str)))
        prompt (get-in client-state [:game-state side :prompt-state])
        selectable (:selectable prompt)
        eid (:eid prompt)]
    (cond
      (not= "select" (:prompt-type prompt))
      (do
        (println "❌ No select prompt active")
        (when prompt
          (println (format "   Current prompt type: %s" (:prompt-type prompt))))
        (core/with-cursor {:status :error :reason "No select prompt active"}))

      (empty? selectable)
      (do
        (println "❌ No selectable cards in current prompt")
        (core/with-cursor {:status :error :reason "No selectable cards"}))

      (empty? card-refs)
      (do
        (println "❌ No cards specified")
        (println "   Usage: (multi-choose! \"Card Name\" \"Another Card\" ...)")
        (println "      or: (multi-choose! 0 1 2 ...)  ; by index")
        (core/with-cursor {:status :error :reason "No cards specified"}))

      :else
      (let [;; Resolve selectable CIDs to cards upfront for name matching
            resolved-selectable (map (fn [cid-or-card]
                                       (if (string? cid-or-card)
                                         (core/find-card-by-cid cid-or-card)
                                         cid-or-card))
                                     selectable)
            ;; Track cards to select - find each referenced card
            cards-to-select
            (reduce
             (fn [acc card-ref]
               (cond
                 ;; By index
                 (number? card-ref)
                 (if (< -1 card-ref (count selectable))
                   (let [card (nth resolved-selectable card-ref)]
                     (if card
                       (conj acc {:card card :ref card-ref})
                       (do (println (format "⚠️  Could not resolve card at index %d" card-ref))
                           acc)))
                   (do (println (format "⚠️  Invalid index: %d" card-ref))
                       acc))

                 ;; By name
                 (string? card-ref)
                 (if-let [card (find-card-in-selectable card-ref resolved-selectable)]
                   (conj acc {:card card :ref card-ref})
                   (do (println (format "⚠️  No selectable card matching: %s" card-ref))
                       acc))

                 :else
                 (do (println (format "⚠️  Invalid card reference: %s" card-ref))
                     acc)))
             []
             card-refs)]

        (if (empty? cards-to-select)
          (do
            (println "❌ No valid cards found to select")
            (core/with-cursor {:status :error :reason "No valid cards found"}))
          (do
            (println (format "📇 Selecting %d card(s)..." (count cards-to-select)))
            (doseq [{:keys [card ref]} cards-to-select]
              (println (format "   → %s" (:title card)))
              (ws/select-card! card eid)
              (Thread/sleep core/short-delay))
            (println "✅ Selection complete")
            (core/with-cursor {:status :success :selected (count cards-to-select)})))))))

;; ============================================================================
;; Mulligan
;; ============================================================================

(defn keep-hand
  "Keep hand during mulligan"
  []
  (let [prompt (state/get-prompt)
        prompt-type (:prompt-type prompt)
        client-state @state/client-state
        side-str (:side client-state)
        ;; Normalize side to lowercase to match game state keys (:runner, :corp)
        side (when side-str (keyword (clojure.string/lower-case side-str)))
        hand (get-in client-state [:game-state side :hand])
        hand-size (count hand)]
    (if (and prompt (or (= "mulligan" prompt-type) (= :mulligan prompt-type)))
      ;; Mulligan prompts are just normal choice prompts
      ;; Option 0 is always "Keep", option 1 is always "Mulligan"
      (do
        (println (str "✅ Kept starting hand (" hand-size " cards)"))
        ;; Show card text for each card in hand (first time only)
        (doseq [card hand]
          (core/show-card-on-first-sight! (:title card)))
        (choose-option! 0)
        (core/with-cursor
          {:status :success
           :data {:action :keep-hand}}))
      (do
        (println "⚠️  No mulligan prompt active")
        (core/with-cursor
          {:status :error
           :reason "No mulligan prompt active"})))))

(defn mulligan
  "Mulligan (redraw) hand"
  []
  (let [prompt (state/get-prompt)
        prompt-type (:prompt-type prompt)
        client-state @state/client-state
        side-str (:side client-state)
        ;; Normalize side to lowercase to match game state keys (:runner, :corp)
        side (when side-str (keyword (clojure.string/lower-case side-str)))
        hand-size (count (get-in client-state [:game-state side :hand]))]
    (if (and prompt (or (= "mulligan" prompt-type) (= :mulligan prompt-type)))
      ;; Mulligan prompts are just normal choice prompts
      ;; Option 0 is always "Keep", option 1 is always "Mulligan"
      (do
        (println (str "🔄 Mulligan - redrawing " hand-size " cards"))
        (choose-option! 1)
        ;; Wait for state to update with new hand
        (Thread/sleep core/standard-delay)
        ;; Show card text for each card in new hand (first time only)
        (let [new-hand (get-in @state/client-state [:game-state side :hand])]
          (doseq [card new-hand]
            (core/show-card-on-first-sight! (:title card))))
        (core/with-cursor
          {:status :success
           :data {:action :mulligan}}))
      (do
        (println "⚠️  No mulligan prompt active")
        (core/with-cursor
          {:status :error
           :reason "No mulligan prompt active"})))))

(defn auto-keep-mulligan
  "Automatically handle mulligan by keeping hand"
  []
  (loop [checks 0]
    (when (< checks 20)
      (Thread/sleep core/short-delay)
      (let [prompt (state/get-prompt)
            prompt-type (:prompt-type prompt)]
        (if (and prompt (or (= "mulligan" prompt-type) (= :mulligan prompt-type)))
          (keep-hand)
          (recur (inc checks)))))))

;; ============================================================================
;; Discard Handling
;; ============================================================================

(defn discard-to-hand-size!
  "Discard cards down to maximum hand size
   Auto-detects side and discards until at or below max hand size"
  []
  (let [client-state @state/client-state
        side-str (:side client-state)
        side (when side-str (keyword (clojure.string/lower-case side-str)))
        discarded (ws/handle-discard-prompt! side)]
    (when (= discarded 0)
      (println "No cards to discard"))))

(defn discard-specific-cards!
  "Discard specific cards by index positions

   Usage: (discard-specific-cards! [0 2 4])  ; Discard cards at indices 0, 2, 4"
  [indices]
  (let [client-state @state/client-state
        side-str (:side client-state)
        side (when side-str (keyword (clojure.string/lower-case side-str)))
        gs (state/get-game-state)
        prompt (get-in gs [side :prompt-state])
        hand (get-in gs [side :hand])]
    (if (and (= "select" (:prompt-type prompt))
             (seq indices))
      (let [cards-to-discard (map #(nth hand % nil) indices)
            valid-cards (filter some? cards-to-discard)]
        (doseq [card valid-cards]
          (ws/select-card! card (:eid prompt))
          (Thread/sleep core/quick-delay))
        (core/with-cursor {:status :success :discarded (count valid-cards)}))
      (do
        (println "❌ No discard prompt active or no indices provided")
        (core/with-cursor {:status :error :reason "No discard prompt or no indices"})))))

(defn discard-by-names!
  "Discard specific cards by their names
   Supports [N] suffix for duplicates: \"Sure Gamble [1]\"

   Usage: (discard-by-names! [\"Sure Gamble\" \"Diesel\"])
          (discard-by-names! \"Sure Gamble [1]\")  ; Specific copy"
  [card-names]
  (let [names-vec (if (vector? card-names) card-names [card-names])
        client-state @state/client-state
        side-str (:side client-state)
        side (when side-str (keyword (clojure.string/lower-case side-str)))
        gs (state/get-game-state)
        prompt (get-in gs [side :prompt-state])
        hand (get-in gs [side :hand])]
    (if (and (= "select" (:prompt-type prompt))
             (seq names-vec))
      (let [;; Find cards in hand matching the requested names with [N] support
            cards-to-discard (remove nil?
                               (for [card-ref names-vec]
                                 (let [{:keys [title index]} (core/parse-card-reference card-ref)
                                       matches (filter #(= (:title %) title) hand)]
                                   (nth (vec matches) index nil))))
            _ (when (seq cards-to-discard)
                (println "Discarding:" (clojure.string/join ", " (map :title cards-to-discard))))]
        (if (seq cards-to-discard)
          (do
            (doseq [card cards-to-discard]
              (ws/select-card! card (:eid prompt))
              (Thread/sleep core/quick-delay))
            (println "✅ Discarded" (count cards-to-discard) "card(s)")
            (core/with-cursor {:status :success :discarded (count cards-to-discard)}))
          (do
            (println "❌ No matching cards found in hand for:" (clojure.string/join ", " names-vec))
            (core/with-cursor {:status :error :reason "No matching cards found"}))))
      (do
        (println "❌ No discard prompt active or no card names provided")
        (core/with-cursor {:status :error :reason "No discard prompt or no card names"})))))

;; ============================================================================
;; Auto-resolve Info Prompts
;; ============================================================================

(defn ok-only-prompt?
  "Check if current prompt is an info-only prompt with just 'OK' as option"
  []
  (let [prompt (state/get-prompt)
        choices (:choices prompt)]
    (and prompt
         (= 1 (count choices))
         (= "OK" (:value (first choices))))))

(defn auto-resolve-ok-prompt!
  "Auto-resolve any 'OK-only' info prompt (like trash confirmation).
   Returns true if a prompt was resolved, false otherwise."
  []
  (if (ok-only-prompt?)
    (let [prompt (state/get-prompt)]
      (println (str "   ℹ️  " (:msg prompt)))
      (choose-option! 0)
      (core/with-cursor {:status :success :resolved true}))
    (core/with-cursor {:status :no-op :resolved false})))
