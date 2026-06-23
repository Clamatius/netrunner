(ns ai-prompts
  "Prompt handling, choices, mulligan, and discard management"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-core :as core]))

;; ============================================================================
;; Prompts & Choices
;; ============================================================================

(defn- maybe-auto-end-turn-after-prompt!
  "After a prompt resolves, the run may have completed implicitly (e.g.,
   Conduit virus-counter prompt that fires as the run's last event). If the
   resolver doesn't trigger check-auto-end-turn! here, the runner client
   never sends end-turn and the engine deadlocks on the next turn cycle.
   See Bug #3 (Run #5). Deferred require avoids the ai_basic_actions ↔
   ai_prompts circular dependency."
  []
  (require '[ai-basic-actions :as basic])
  ((resolve 'ai-basic-actions/check-auto-end-turn!)))

(defn wait-for-prompt-change!
  "Wait for prompt state to change after making a choice.
   Returns true if prompt changed (different eid, disappeared, or message
   updated), false on timeout.

   This fixes the multi-step prompt sync issue (e.g., Mutual Favor): server
   processes choice → sends diff with new prompt → we wait for it.

   For multi-step prompts that re-use the same eid (e.g., credit-source
   prompts: \"Choose a credit providing card (0 of 4)\" → \"1 of 4\" → ...),
   the eid stays constant across iterations but the :msg field updates. We
   treat a :msg change as progress so we don't spuriously print 'Timeout'
   after each successful pay-step."
  [old-eid & {:keys [timeout-ms old-msg] :or {timeout-ms 3000}}]
  (let [baseline-msg (or old-msg (:msg (state/get-prompt)))]
    (loop [waited 0]
      (if (>= waited timeout-ms)
        (do
          ;; Only react if the prompt truly didn't move at all. Same eid AND
          ;; same msg AND still present.
          (let [final-prompt (state/get-prompt)]
            (when (and final-prompt
                       (= (:eid final-prompt) old-eid)
                       (= (:msg final-prompt) baseline-msg))
              (if (state/select-prompt-type? (:prompt-type final-prompt))
                ;; Expected for a partial multi-select: the server TOGGLES the
                ;; chosen card and only resolves once :max cards are selected,
                ;; so the prompt stays put. This is not a failure — do not warn
                ;; as if the select was rejected (issue #18).
                (println (str "ℹ️  Select prompt still open — card toggled but "
                              "selection not yet resolved. For multi-card selects "
                              "(e.g. discard down to N) use `multi-choose <i> <j> …` "
                              "to select all at once."))
                ;; Non-select prompt that genuinely didn't move = real stall.
                (println "⚠️  Timeout waiting for prompt change (prompt unchanged)"))))
          false)
        (let [current-prompt (state/get-prompt)
              current-eid (:eid current-prompt)
              current-msg (:msg current-prompt)]
          (if (or (nil? current-prompt)
                  (not= current-eid old-eid)
                  (not= current-msg baseline-msg))
            true  ; Prompt changed, disappeared, or message updated
            (do
              (Thread/sleep 100)
              (recur (+ waited 100)))))))))

(defn choose-by-index!
  "Make a choice from current prompt by index or UUID.
   Usage: (choose-by-index! 0)        ; choose first option
          (choose-by-index! \"uuid\")  ; choose by UUID

   For choosing by value text (e.g. \"Keep\", \"Steal\"), use choose-by-value! instead.
   Waits for prompt state to change after sending choice."
  [choice]
  (let [prompt (state/get-prompt)
        old-eid (:eid prompt)]
    (if prompt
      (do
        (ws/choose! choice)
        ;; Wait for prompt to change instead of fixed sleep
        (wait-for-prompt-change! old-eid)
        (maybe-auto-end-turn-after-prompt!)
        (core/with-cursor {:status :success}))
      (do
        (println "⚠️  No active prompt")
        (core/with-cursor {:status :error :reason "No active prompt"})))))

(defn- press-choice!
  "Send a choice-button press (by :uuid) for the current prompt, then wait for it
   to resolve. Works on ANY prompt type, including `select` prompts — their
   Done / decline / meta buttons live in :choices and are pressed via the same
   `choice` command as ordinary button prompts.

   `choose-option!` keeps its index-based select guard (a bare index is ambiguous
   between :choices and the selectable cards), so this shared helper is what lets
   `choose-by-value!` reach a NAMED button (e.g. \"Done\") on a select prompt —
   previously such a meta-option was unreachable, forcing the seat to over-select
   just to escape the prompt (laundry-list #5)."
  [choice]
  (let [client-state @state/client-state
        side (:side client-state)
        side-kw (when side (keyword (clojure.string/lower-case side)))
        gameid (:gameid client-state)
        prompt (get-in client-state [:game-state side-kw :prompt-state])
        old-eid (:eid prompt)]
    (println (str "✅ Chose: " (core/format-choice choice)))
    (ws/send-message! :game/action
                      {:gameid gameid
                       :command "choice"
                       :args {:choice {:uuid (:uuid choice)}}})
    (wait-for-prompt-change! old-eid)
    (maybe-auto-end-turn-after-prompt!)
    (core/with-cursor {:status :success :choice choice})))

(defn choose-option!
  "Choose from prompt by index (side-aware).
   Waits for prompt state to change after sending choice.

   For prompts of type 'select' (card-selection prompts like Mutual Favor or
   Send a Message), use `choose-card!` to pick a card. To press a NAMED meta
   button on a select prompt (e.g. \"Done\" to stop selecting), use
   `choose-value \"Done\"` — a bare index here is refused because it's ambiguous
   between the choice buttons and the selectable cards."
  [index]
  (let [client-state @state/client-state
        side (:side client-state)
        side-kw (when side (keyword (clojure.string/lower-case side)))
        prompt (get-in client-state [:game-state side-kw :prompt-state])
        prompt-type (:prompt-type prompt)
        choices (:choices prompt)
        choice (nth choices index nil)
        choice-uuid (:uuid choice)]
    (cond
      ;; Select prompts need choose-card, not choose. Warn LOUDLY rather than
      ;; silently picking a meta-choice (e.g. "Done") from :choices.
      (= "select" prompt-type)
      (let [selectable (:selectable prompt)]
        (println (format "⚠️  This is a SELECT prompt (%d selectable card(s)) — use choose-card <N>, not choose <N>."
                        (count selectable)))
        (when (seq choices)
          (println "    To press a meta-option below (e.g. stop selecting), use choose-value \"<label>\":")
          (doseq [[i c] (map-indexed vector choices)]
            (println (format "      • %s" (:value c)))))
        (println "    Selectable cards:")
        (core/print-selectable! (core/resolve-selectable selectable) "      ")
        (core/with-cursor {:status :error :reason "Use choose-card for select prompts"}))

      choice-uuid
      (press-choice! choice)

      :else
      (do
        (println (str "❌ Invalid choice index: " index))
        (when (seq choices)
          (println "    Available choices:")
          (doseq [[i c] (map-indexed vector choices)]
            (println (format "      %d. %s" i (:value c)))))
        (core/with-cursor {:status :error :reason "Invalid choice index"})))))

(defn choose-by-value!
  "Choose from prompt by matching value/label text (case-insensitive substring match).
   Usage: (choose-by-value! \"steal\") or (choose-by-value! \"keep\")

   Works on `select` prompts too: the Done / decline / meta buttons live in
   :choices, so `choose-value \"Done\"` presses them by name — the only way to
   reach a select prompt's meta-option (`choose <N>` is refused there as
   ambiguous, `choose-card <N>` only picks selectable cards)."
  [value-text]
  (let [client-state @state/client-state
        side (:side client-state)
        side-kw (when side (keyword (clojure.string/lower-case side)))
        prompt (get-in client-state [:game-state side-kw :prompt-state])
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
      (press-choice! (nth choices matching-idx))
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
      ;; choose-card resolves a card-targeting prompt. The canonical wire type is
      ;; "select", but some engine prompts (e.g. Mutual Favor's stack search) carry
      ;; :selectable cards under a different :prompt-type ("other"). Gate on the
      ;; PRESENCE of selectable cards, not the type string, so choose-card works
      ;; wherever there are cards to pick — and steer text-choice prompts to
      ;; `choose` rather than the misleading "No select prompt active". (backlog #3)
      (empty? selectable)
      (do
        (if (and prompt (seq (:choices prompt)))
          (do (println "❌ This prompt has text choices, not selectable cards.")
              (println "   → Use: choose <N>  (or choose-value \"<text>\")"))
          (println "❌ No selectable cards in current prompt"))
        (when prompt
          (println (format "   Current prompt type: %s" (:prompt-type prompt))))
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
            ;; Wait for prompt to change instead of fixed sleep
            (wait-for-prompt-change! eid)
            (maybe-auto-end-turn-after-prompt!)
            (core/with-cursor {:status :success :card card}))
          (let [{:keys [pickable]} (core/resolve-selectable selectable)
                pickable-idxs (map :idx pickable)]
            (println (format "❌ Index %d isn't a card you can select — it's hidden/opponent (not in your view)." index))
            (if (seq pickable-idxs)
              (println (format "   Selectable indices: %s. Use 'prompt' to see them by name."
                              (clojure.string/join ", " pickable-idxs)))
              (println "   No selectable cards resolve from this seat — use 'prompt' / choose-value for meta-options."))
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
      ;; Gate on the PRESENCE of selectable cards, not the :prompt-type string,
      ;; so multi-choose works on card-targeting prompts that aren't typed
      ;; "select" (mirrors choose-card!; see backlog #3).
      (empty? selectable)
      (do
        (if (and prompt (seq (:choices prompt)))
          (do (println "❌ This prompt has text choices, not selectable cards.")
              (println "   → Use: choose <N>  (or choose-value \"<text>\")"))
          (println "❌ No selectable cards in current prompt"))
        (when prompt
          (println (format "   Current prompt type: %s" (:prompt-type prompt))))
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
            ;; Wait for prompt to change after all selections
            (wait-for-prompt-change! eid)
            (println "✅ Selection complete")
            (core/with-cursor {:status :success :selected (count cards-to-select)})))))))

;; ============================================================================
;; Mulligan
;; ============================================================================

(def ^:private mulligan-sync-wait-checks
  "How many polling checks (~500ms each) keep-hand/mulligan wait for an opening
   prompt to appear when NONE is cached yet. Only fires on a genuinely-empty
   prompt-state (e.g. the brief window right after a client reconnect, before the
   initial game-state has synced). The far more common 'no mulligan prompt' cause
   — the opponent hasn't finished their own mulligan yet — leaves a 'waiting'
   prompt in cache, so get-prompt returns non-nil and this wait is skipped.
   4 checks ≈ 2s."
  4)

(defn- waiting-for-opponent-mulligan?
  "True when `prompt` is the opening-mulligan 'waiting for opponent to keep hand
   or mulligan' window. The Corp resolves its opening mulligan first, so a Runner
   that calls keep-hand/mulligan before the Corp has decided genuinely has no
   mulligan prompt yet — just this waiting one. Detecting it lets us say 'wait for
   them' instead of the misleading 'No mulligan prompt active'."
  [prompt]
  (boolean
    (and prompt
         (= "waiting" (str (:prompt-type prompt)))
         (re-find #"(?i)mulligan|keep hand" (str (:msg prompt))))))

(defn keep-hand
  "Keep hand during mulligan"
  []
  (let [prompt (or (state/get-prompt)
                   (core/wait-for-prompt mulligan-sync-wait-checks))
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
      (if (waiting-for-opponent-mulligan? prompt)
        (do
          (println "⏳ Opponent hasn't finished their opening mulligan yet (Corp decides first).\n   Wait for them to keep/mulligan, then run keep-hand again.")
          (core/with-cursor
            {:status :error
             :reason "Opponent mulligan pending"}))
        (do
          (println "⚠️  No mulligan prompt active")
          (core/with-cursor
            {:status :error
             :reason "No mulligan prompt active"}))))))

(defn mulligan
  "Mulligan (redraw) hand"
  []
  (let [prompt (or (state/get-prompt)
                   (core/wait-for-prompt mulligan-sync-wait-checks))
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
      (if (waiting-for-opponent-mulligan? prompt)
        (do
          (println "⏳ Opponent hasn't finished their opening mulligan yet (Corp decides first).\n   Wait for them to keep/mulligan, then run mulligan again.")
          (core/with-cursor
            {:status :error
             :reason "Opponent mulligan pending"}))
        (do
          (println "⚠️  No mulligan prompt active")
          (core/with-cursor
            {:status :error
             :reason "No mulligan prompt active"}))))))

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
          (discard-by-names! \"Sure Gamble [1]\")  ; Specific copy

   NOTE: During end-of-turn discard prompts, the selectable cards are in the
   prompt's :selectable list, not the raw :hand. Use choose-card or multi-choose
   for those prompts instead."
  [card-names]
  (let [names-vec (if (vector? card-names) card-names [card-names])
        client-state @state/client-state
        side-str (:side client-state)
        side (when side-str (keyword (clojure.string/lower-case side-str)))
        gs (state/get-game-state)
        prompt (get-in gs [side :prompt-state])
        hand (get-in gs [side :hand])]
    ;; Check if there's a select prompt - if so, guide user to choose-card instead
    (if (= "select" (:prompt-type prompt))
      (do
        (println "⚠️  Active select prompt detected.")
        (println "   Use `choose-card <index>` or `multi-choose` instead.")
        (println "   Run `prompt` to see available cards.")
        (core/with-cursor {:status :error :reason "Use choose-card for select prompts"}))
      (do
        (println "❌ No select prompt active - nothing to discard")
        (core/with-cursor {:status :error :reason "No select prompt active"})))))

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
