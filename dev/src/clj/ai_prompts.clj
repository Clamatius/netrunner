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
  [old-eid & {:keys [timeout-ms old-msg] :as opts :or {timeout-ms 3000}}]
  ;; A nil/absent :old-msg used to fall back to a read taken AFTER the choice
  ;; was already sent — a same-eid prompt whose :msg advanced faster than this
  ;; call starts (Mutual Favor, #97) had the NEW msg captured as baseline, so
  ;; the change was invisible and a resolved choice was reported 'NOT treating
  ;; as resolved'. Callers now pass the pre-send msg; the live read remains
  ;; only for callers that genuinely didn't supply the key.
  (let [baseline-msg (if (contains? opts :old-msg)
                       old-msg
                       (:msg (state/get-prompt)))]
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
        ;; Wait for prompt to change instead of fixed sleep. Only run the
        ;; auto-end-turn hook if the prompt actually moved/resolved — an
        ;; unchanged prompt is still blocking, so auto-end could never fire and
        ;; the hook would only print a spurious "resolve the prompt" warning.
        ;; (See choose-card! for the partial-multi-select case this bites.)
        (when (wait-for-prompt-change! old-eid :old-msg (:msg prompt))
          (maybe-auto-end-turn-after-prompt!))
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
        old-eid (:eid prompt)
        old-msg (:msg prompt)
        old-cid (get-in prompt [:card :cid])
        old-choice-values (mapv :value (:choices prompt))]
    (ws/send-message! :game/action
                      {:gameid gameid
                       :command "choice"
                       :args {:choice {:uuid (:uuid choice)}}})
    ;; Report what actually happened, not what we attempted (#75): a choice the
    ;; engine rejects (e.g. an unpayable cost) or silently swallows leaves the
    ;; prompt unchanged — that must NOT read as success, and the success line
    ;; must not print before we know. Only run the auto-end hook if the prompt
    ;; actually moved (see choose-card!).
    (if (wait-for-prompt-change! old-eid :old-msg old-msg)
      ;; Capture the revealed prompt BEFORE the auto-end hook runs (guest
      ;; review): the hook can itself advance state, and duplicate detection
      ;; must observe the prompt the choice immediately revealed.
      (let [new-prompt (state/get-prompt)
            ;; Duplicate-instance detection (#75): the engine can mint STACKED
            ;; copies of the same prompt (marquee g2: five Manegarm 'Choose one'
            ;; prompts from Corp continue-spam). Resolving one pops it and an
            ;; identical-looking next instance surfaces (same msg + card, new
            ;; eid). Without saying so, the seat reads the stack as a no-op loop
            ;; and gives up — g2 was abandoned one answer short of draining it.
            ;; Fingerprint requires a PRESENT msg and card cid (guest review):
            ;; nil = nil must not identify two different card-less prompts that
            ;; share a generic msg like "Choose one".
            same-shape? (and new-prompt
                             (not= (:eid new-prompt) old-eid)
                             (some? old-msg)
                             (= (:msg new-prompt) old-msg)
                             (some? old-cid)
                             (= (get-in new-prompt [:card :cid]) old-cid))
            ;; A stacked duplicate re-poses the SAME decision, so its choice
            ;; VALUES match (uuids differ per instance — compare labels). A
            ;; same-shaped prompt whose choice list CHANGED is a legitimate
            ;; follow-up step of a repeating ability — e.g. the engine's
            ;; repeatable break flow (#96: Brân re-asks "Break a subroutine"
            ;; minus the sub just broken, another [click] per break). Calling
            ;; that a duplicate told seats to "drain" a prompt that was
            ;; actually offering more value, 9/9 breaks in marquee 30c4a1c0.
            new-choice-values (mapv :value (:choices new-prompt))
            duplicate? (and same-shape? (= new-choice-values old-choice-values))
            follow-up? (and same-shape? (not= new-choice-values old-choice-values))]
        (maybe-auto-end-turn-after-prompt!)
        (println (str "✅ Chose: " (core/format-choice choice)))
        (when duplicate?
          (println (str "ℹ️  Your choice RESOLVED, but an identical duplicate prompt "
                        "appeared (new instance of the same card prompt — the engine "
                        "minted copies). Answer it again to drain the stack.")))
        (when follow-up?
          (println (str "➡️  Your choice RESOLVED and " (get-in new-prompt [:card :title])
                        " is asking a follow-up: same prompt, but the choice list "
                        "CHANGED (a repeating ability — e.g. break another subroutine, "
                        "paying its cost again). Re-read the choices before answering; "
                        "answer 'Done' (if offered) to stop repeating.")))
        (core/with-cursor (cond-> {:status :success :choice choice}
                            duplicate? (assoc :duplicate-prompt true)
                            follow-up? (assoc :follow-up-prompt true))))
      (do
        (println (str "⚠️  Choice sent but the prompt did not change — NOT treating as "
                      "resolved: " (core/format-choice choice)))
        (core/with-cursor {:status :waiting-input
                           :choice choice
                           :reason "Prompt unchanged after choice — it may have been rejected (e.g. unpayable cost) or swallowed"})))))

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

(defn normalize-choice-text
  "Lowercase and strip [] so a paraphrase like \"Gain 3 credits\" matches the
   wire label \"Gain 3 [Credits]\" (#101: icon tokens made choose-value reject
   anything short of the exact bracketed label — both guest models hit it)."
  [s]
  (-> (str s)
      (clojure.string/lower-case)
      (clojure.string/replace #"[\[\]]" "")))

(defn choice-match-index
  "Pure: index of the first choice whose :value/:label contains `value-text`,
   comparing bracket-stripped lowercase text. nil when nothing matches."
  [choices value-text]
  (let [needle (normalize-choice-text value-text)]
    (first
     (keep-indexed
      (fn [idx choice]
        (let [choice-val (or (:value choice) (:label choice) "")]
          (when (clojure.string/includes? (normalize-choice-text choice-val) needle)
            idx)))
      choices))))

(defn choose-by-value!
  "Choose from prompt by matching value/label text (case-insensitive substring
   match, tolerant of [icon] brackets — see choice-match-index).
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
        matching-idx (choice-match-index choices value-text)]
    (if matching-idx
      (press-choice! (nth choices matching-idx))
      (do
        (println (str "❌ No choice matching \"" value-text "\" found"))
        (println "Available choices:")
        (doseq [[idx choice] (map-indexed vector choices)]
          (println (str "  " idx ". " (core/format-choice choice))))
        (println "   → Press by index instead with: choose <N>")
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
            ;; Selectable can be CID strings or card maps - resolve CIDs to cards.
            ;; Use the selectable-aware resolver so a FACE-DOWN card at a breach
            ;; (title-less in the Runner's view) still resolves — the title-gated
            ;; find-card-by-cid dropped it and wedged multi-card remote breaches. (#70)
            card (if (string? cid-or-card)
                   (core/find-selectable-card-by-cid cid-or-card)
                   cid-or-card)]
        ;; Card-shape guard mirrors resolve-selectable: a real pick has :title OR
        ;; :zone. This keeps a raw junk map that the engine might drop directly
        ;; into :selectable from reaching select-card! by index. (#70 review)
        (if (and (map? card) (or (:title card) (:zone card)))
          ;; Don't claim success before the prompt confirms registration: print
          ;; the confirmation only AFTER the prompt moves. A non-select prompt
          ;; that doesn't move means choose-card was the WRONG verb (the engine
          ;; wanted a text choice) — error + steer to `choose`, instead of the
          ;; old "📇 Selecting card …" + spurious :success on a stall. (issue #40)
          (let [select? (state/select-prompt-type? (:prompt-type prompt))]
            (ws/select-card! card eid)
            (cond
              ;; Prompt moved → selection registered and resolved. Only here is it
              ;; safe to run the auto-end-turn hook (a partial multi-select leaves
              ;; the prompt put — see the select? branch). (#18 tail)
              (wait-for-prompt-change! eid :old-msg (:msg prompt))
              (do
                (println (format "📇 Selected card: %s (index %d)" (:title card) index))
                (maybe-auto-end-turn-after-prompt!)
                (core/with-cursor {:status :success :card card}))

              ;; Unchanged but a genuine multi-select toggle (discard-to-N): the
              ;; server accumulates selections to :max and only then resolves.
              ;; wait-for-prompt-change! already printed the multi-choose tip.
              select?
              (do
                (println (format "📇 Toggled card: %s (index %d) — more selections needed"
                                (:title card) index))
                (core/with-cursor {:status :success :card card}))

              ;; Unchanged on a NON-select prompt: choose-card is the wrong verb
              ;; here (this prompt resolves by text choice). (issue #40)
              :else
              (do
                (println (format "↪️  choose-card %d did not register — this prompt isn't a card-select."
                                index))
                (if (seq (:choices prompt))
                  (println "   → It's a numbered CHOICE prompt. Use:  choose <N>")
                  (println "   → Use 'prompt' to inspect, then choose / choose-value."))
                (core/with-cursor {:status :error
                                   :reason "Not a card-select prompt — use choose"}))))
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
      (let [;; Resolve selectable CIDs to cards upfront. Use the selectable-aware
            ;; resolver so a title-less FACE-DOWN card at a breach still
            ;; index-selects, mirroring choose-card! (#70). Name matching against
            ;; such a card is impossible anyway (no title), so this only affects
            ;; by-index selection, which is what we want.
            resolved-selectable (map (fn [cid-or-card]
                                       (if (string? cid-or-card)
                                         (core/find-selectable-card-by-cid cid-or-card)
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
          (let [select? (state/select-prompt-type? (:prompt-type prompt))]
            (println (format "📇 Selecting %d card(s)..." (count cards-to-select)))
            (doseq [{:keys [card ref]} cards-to-select]
              (println (format "   → %s" (:title card)))
              (ws/select-card! card eid)
              (Thread/sleep core/short-delay))
            ;; Don't claim completion before the prompt confirms it. Only report
            ;; success once the prompt actually moves/resolves. If it stays put the
            ;; selection did NOT resolve — saying "Selection complete" there is the
            ;; misleading-output bug that drove a marquee discard-to-5 misplay
            ;; (cards still in hand, prompt still open). Mirrors choose-card! (#40).
            (cond
              (wait-for-prompt-change! eid :old-msg (:msg prompt))
              (do
                (println "✅ Selection complete")
                (core/with-cursor {:status :success :selected (count cards-to-select)}))

              ;; Select-type prompt still open: cards were toggled but :max not yet
              ;; reached. wait-for-prompt-change! already printed the steer.
              select?
              (do
                (println (format "⏳ %d card(s) toggled but the prompt is still open — selection not resolved."
                                (count cards-to-select)))
                (println "   → Select the remaining card(s), or use choose-value \"Done\" if the count is already right.")
                (core/with-cursor {:status :waiting-input :selected (count cards-to-select)}))

              ;; Non-select prompt that didn't move: multi-choose was the wrong verb.
              :else
              (do
                (println "↪️  multi-choose did not register — this prompt isn't a card-select.")
                (if (seq (:choices prompt))
                  (println "   → It's a numbered CHOICE prompt. Use:  choose <N>")
                  (println "   → Use 'prompt' to inspect, then choose / choose-value."))
                (core/with-cursor {:status :error
                                   :reason "Not a card-select prompt — use choose"})))))))))

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
   them' instead of the misleading 'No mulligan prompt active'.

   Delegates to ai-state's matcher rather than re-inlining the regex — #87 was
   caused by exactly this predicate existing in more than one place."
  [prompt]
  (state/mulligan-wait-prompt? prompt))

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
