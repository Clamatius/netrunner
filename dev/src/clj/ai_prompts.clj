(ns ai-prompts
  "Prompt handling, choices, mulligan, and discard management"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-core :as core]))

;; ============================================================================
;; Prompts & Choices
;; ============================================================================

(defn choose!
  "Make a choice from current prompt
   Usage: (choose! 0) ; choose first option
          (choose! \"uuid\") ; choose by UUID"
  [choice]
  (let [prompt (state/get-prompt)]
    (if prompt
      (do
        (ws/choose! choice)
        (Thread/sleep core/quick-delay))
      (println "⚠️  No active prompt"))))

(defn choose-option!
  "Choose from prompt by index (side-aware)"
  [index]
  (let [client-state @state/client-state
        side (:side client-state)
        gameid (:gameid client-state)
        prompt (get-in client-state [:game-state (keyword side) :prompt-state])
        choice (nth (:choices prompt) index nil)
        choice-uuid (:uuid choice)]
    (if choice-uuid
      (do
        (println (str "✅ Chose: " (:value choice)))
        (ws/send-message! :game/action
                          {:gameid gameid
                           :command "choice"
                           :args {:choice {:uuid choice-uuid}}})
        (Thread/sleep core/standard-delay))
      (println (str "❌ Invalid choice index: " index)))))

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
          (println (str "  " idx ". " (core/format-choice choice))))))))

(defn choose-card!
  "Choose a card from selectable cards in current prompt by index.
   Used for select prompts like 'Send a Message' (choose card to trash).

   Usage: (choose-card! 0)  ; Select first selectable card
          (choose-card! 2)  ; Select third selectable card"
  [index]
  (let [client-state @state/client-state
        side (keyword (:side client-state))
        prompt (get-in client-state [:game-state side :prompt-state])
        selectable (:selectable prompt)
        eid (:eid prompt)]
    (cond
      (not= "select" (:prompt-type prompt))
      (do
        (println "❌ No select prompt active")
        (when prompt
          (println (format "   Current prompt type: %s" (:prompt-type prompt)))))

      (empty? selectable)
      (println "❌ No selectable cards in current prompt")

      (not (< -1 index (count selectable)))
      (println (format "❌ Invalid index: %d (only %d selectable cards, use 0-%d)"
                      index (count selectable) (dec (count selectable))))

      :else
      (let [card (nth selectable index)]
        (println (format "📇 Selecting card: %s (index %d)" (:title card) index))
        (ws/select-card! card eid)
        (Thread/sleep core/short-delay)))))

;; ============================================================================
;; Mulligan
;; ============================================================================

(defn keep-hand
  "Keep hand during mulligan"
  []
  (let [prompt (state/get-prompt)
        prompt-type (:prompt-type prompt)
        client-state @state/client-state
        side (keyword (:side client-state))
        hand-size (count (get-in client-state [:game-state side :hand]))]
    (if (and prompt (or (= "mulligan" prompt-type) (= :mulligan prompt-type)))
      ;; Mulligan prompts are just normal choice prompts
      ;; Option 0 is always "Keep", option 1 is always "Mulligan"
      (do
        (println (str "✅ Kept starting hand (" hand-size " cards)"))
        (choose-option! 0)
        {:status :success
         :data {:action :keep-hand}})
      (do
        (println "⚠️  No mulligan prompt active")
        {:status :error
         :reason "No mulligan prompt active"}))))

(defn mulligan
  "Mulligan (redraw) hand"
  []
  (let [prompt (state/get-prompt)
        prompt-type (:prompt-type prompt)
        client-state @state/client-state
        side (keyword (:side client-state))
        hand-size (count (get-in client-state [:game-state side :hand]))]
    (if (and prompt (or (= "mulligan" prompt-type) (= :mulligan prompt-type)))
      ;; Mulligan prompts are just normal choice prompts
      ;; Option 0 is always "Keep", option 1 is always "Mulligan"
      (do
        (println (str "🔄 Mulligan - redrawing " hand-size " cards"))
        (choose-option! 1)
        {:status :success
         :data {:action :mulligan}})
      (do
        (println "⚠️  No mulligan prompt active")
        {:status :error
         :reason "No mulligan prompt active"}))))

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
        side (keyword (:side client-state))
        discarded (ws/handle-discard-prompt! side)]
    (when (= discarded 0)
      (println "No cards to discard"))))

(defn discard-specific-cards!
  "Discard specific cards by index positions

   Usage: (discard-specific-cards! [0 2 4])  ; Discard cards at indices 0, 2, 4"
  [indices]
  (let [client-state @state/client-state
        side (keyword (:side client-state))
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
        (count valid-cards))
      (do
        (println "❌ No discard prompt active or no indices provided")
        0))))

(defn discard-by-names!
  "Discard specific cards by their names
   Supports [N] suffix for duplicates: \"Sure Gamble [1]\"

   Usage: (discard-by-names! [\"Sure Gamble\" \"Diesel\"])
          (discard-by-names! \"Sure Gamble [1]\")  ; Specific copy"
  [card-names]
  (let [names-vec (if (vector? card-names) card-names [card-names])
        client-state @state/client-state
        side (keyword (:side client-state))
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
            (count cards-to-discard))
          (do
            (println "❌ No matching cards found in hand for:" (clojure.string/join ", " names-vec))
            0)))
      (do
        (println "❌ No discard prompt active or no card names provided")
        0))))

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
  (when (ok-only-prompt?)
    (let [prompt (state/get-prompt)]
      (println (str "   ℹ️  " (:msg prompt)))
      (choose-option! 0)
      true)))
