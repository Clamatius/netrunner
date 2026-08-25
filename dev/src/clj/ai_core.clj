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
               (not (state/waiting-prompt-type? (:prompt-type existing-prompt))))
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

(defn new-prompt?
  "Did a genuinely NEW prompt (a new decision point) appear, given the prompt
   seen before the action (`initial`) and the prompt seen now (`current`)?

   Structural inequality alone is unreliable: prompt-state is NOT cleared
   passively when a prompt resolves/cancels, so `initial` is often a stale
   leftover that is byte-identical to a freshly re-opened same-shaped prompt
   (e.g. using Red Team twice both open \"Choose a server\"). `(not= current
   initial)` is then false and the real prompt is missed -- surfacing a bogus
   \"Ability failed (timeout)\" for an ability that actually worked.

   Each prompt instance carries a unique :eid, so a differing eid is the
   reliable \"new decision\" signal; we fall back to structural inequality for
   the rare eid-less prompt."
  [initial current]
  (boolean
    (and current
         (or (not= (:eid current) (:eid initial))
             (not= current initial)))))

(defn classify-action-result
  "Pure classifier for card-action verification. Given the prompt/log/hand seen
   before the action and the current ones, decide the outcome. Returns a status
   map (:success / :waiting-input) or nil if nothing has changed yet (keep
   polling). Extracted from verify-action-in-log so the prompt-baseline logic is
   unit-testable without the live atom or wall clock."
  [card-name card-initial-zone
   {:keys [initial-prompt current-prompt initial-size current-log hand]}]
  (let [current-size (count current-log)
        ;; Is the card still sitting in the zone it started in (i.e. hand)?
        card-still-in-hand (some #(and (= (:title %) card-name)
                                       (= (:zone %) card-initial-zone))
                                 hand)
        card-in-log (let [recent-log (take-last 5 current-log)]
                      (some #(when (string? (:text %))
                               (str/includes? (:text %) card-name))
                            recent-log))]
    (cond
      ;; If card moved from hand AND log grew, it's a success
      (and (not card-still-in-hand)
           (or (> current-size initial-size)
               card-in-log))
      {:status :success}

      ;; If card is STILL in hand but a new prompt appeared, it's waiting for
      ;; input. This MUST be checked before the bare log-grew heuristic below:
      ;; the click-spend line can land while the location prompt is still open,
      ;; and reporting :success there is a false "installed".
      (and card-still-in-hand (new-prompt? initial-prompt current-prompt))
      {:status :waiting-input
       :prompt current-prompt
       :card-name card-name}

      ;; If log grew or card appears in log (even without zone change), might be success
      ;; (for Corp hidden cards where card name doesn't show)
      (or (> current-size initial-size) card-in-log)
      {:status :success}

      ;; Otherwise, no change yet
      :else
      nil)))

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

   IMPORTANT: Pass pre-log-size AND pre-prompt captured BEFORE sending the
   action message to avoid race conditions with fast WebSocket responses.

   The 4th argument used to be a bare initial-log-size integer. That legacy
   shape is still accepted (this var is re-exported by ai-actions and is
   reachable from `eval`), and is normalized to {:pre-log-size <n>}."
  ([card-name card-initial-zone max-wait-ms]
   (verify-action-in-log card-name card-initial-zone max-wait-ms {}))
  ([card-name card-initial-zone max-wait-ms opts]
  (let [;; Normalize the legacy positional initial-log-size. This must happen
        ;; BEFORE the contains? check below: contains? THROWS on a
        ;; non-associative argument (it does NOT return false the way `get`
        ;; returns nil), so an un-normalized integer would blow up rather than
        ;; degrade. (Guest review, GPT-5.6.)
        opts (if (map? opts) opts {:pre-log-size opts})
        {:keys [pre-log-size pre-prompt]} opts
        initial-size (or pre-log-size (get-log-size))
        ;; A nil pre-prompt is MEANINGFUL: "no prompt was open when the command
        ;; was sent". Reading the baseline here instead (i.e. AFTER the send)
        ;; meant that whenever the WebSocket reply beat the start of
        ;; verification, the freshly-opened prompt WAS the baseline -- it
        ;; compared equal to itself forever, the waiting-input branch could
        ;; never fire, and a perfectly good install false-failed as a timeout
        ;; (#105; same trap as #97 one function over). Only fall back to a live
        ;; read when the caller genuinely didn't supply the key.
        initial-prompt (if (contains? opts :pre-prompt)
                         pre-prompt
                         (state/get-prompt))
        deadline (+ (System/currentTimeMillis) max-wait-ms)
        check-result (fn []
                       (let [client-state @state/client-state
                             side (keyword (:side client-state))]
                         (classify-action-result
                           card-name card-initial-zone
                           {:initial-prompt initial-prompt
                            :current-prompt (state/get-prompt)
                            :initial-size initial-size
                            :current-log (get-in client-state [:game-state :log])
                            :hand (get-in client-state [:game-state side :hand])})))]
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

(defn classify-ability-result
  "Pure classifier for ability verification. Given the prompt/log seen before
   the action and the current prompt/log, decide the outcome. Returns a status
   map (:success / :waiting-input) or nil if nothing has changed yet (keep
   polling). Extracted from verify-ability-in-log so the eid-aware new-prompt
   logic is unit-testable without the live atom or wall clock."
  [card-name {:keys [initial-prompt current-prompt initial-size current-log]}]
  (let [current-size (count current-log)
        new-entries (drop initial-size current-log)
        card-in-new-entries (some #(when (string? (:text %))
                                     (str/includes? (:text %) card-name))
                                  new-entries)]
    (cond
      ;; Card name in NEW log entries = success
      card-in-new-entries
      {:status :success :card-name card-name}

      ;; New prompt created = waiting for input
      (new-prompt? initial-prompt current-prompt)
      {:status :waiting-input
       :prompt current-prompt
       :card-name card-name}

      ;; Log grew (but no card name visible) = might be success
      ;; (some abilities may not mention card name in log)
      (> current-size initial-size)
      {:status :success :card-name card-name}

      ;; No change yet
      :else nil)))

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
  [card-name max-wait-ms {:keys [pre-log-size pre-prompt] :as opts}]
  (let [initial-size (or pre-log-size (get-log-size))
        ;; A nil pre-prompt is MEANINGFUL: "no prompt was open when the command
        ;; was sent". `(or pre-prompt (state/get-prompt))` treated it as absent
        ;; and re-read the prompt AFTER the send — an ability whose prompt
        ;; arrives faster than verification starts (Red Team, ~250ms, #97) got
        ;; its own new prompt captured as the baseline, compared equal to
        ;; itself forever, and false-failed as a timeout. Only fall back to a
        ;; live read when the caller genuinely didn't supply the key.
        initial-prompt (if (contains? opts :pre-prompt)
                         pre-prompt
                         (state/get-prompt))
        deadline (+ (System/currentTimeMillis) max-wait-ms)
        check-result (fn []
                       (classify-ability-result
                         card-name
                         {:initial-prompt initial-prompt
                          :current-prompt (state/get-prompt)
                          :initial-size initial-size
                          :current-log (get-in @state/client-state [:game-state :log])}))]
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
;; Ability legality (#116)
;; ============================================================================
;; "Ability not confirmed in game log (timeout)" describes the DETECTION
;; MECHANISM — no confirmation appeared within N seconds — not the cause. A
;; timeout invites a retry; a rules violation requires a different action first.
;; The Luna seat hit this trying to break at approach-ice, guessed right, and
;; retrying at the wrong phase is the duplicate-send pattern that mints phantom
;; prompts (#75/#77). Same family as #104/#109.

(defn break-ability?
  "True when ABILITY breaks subroutines.

   Detected from the label, deliberately CONSERVATIVELY (`starts-with` \"break \",
   not `includes`). The engine builds these labels as `(str \"break \" ...)` in
   game.core.ice/break-sub, so the standard ones all match — but a card may
   supply a custom :label, and a false NEGATIVE only costs us a generic error
   message while a false POSITIVE prints a rules claim that isn't true. The
   engine's `:break-req` / `:breaks` keys would be unambiguous, but core/diffs
   `ability-keys` does not serialize them, so the label is what we have."
  [ability]
  (boolean
    (or (= :auto-pump-and-break (:dynamic ability))
        (some-> (:label ability) str/lower-case (str/starts-with? "break ")))))

(defn normalize-phase
  "Run phase as a plain string. The wire sends strings, the engine and our own
   fixtures use keywords (memory engine-rate-of-change: wire shape is the
   volatile coupling). Neither shape may be silently read as legal.

   Lives here rather than in ai-runs (which owned the only copy, privately)
   because ai-runs requires ai-core and not the reverse — the same layering that
   forced get-turn-status to grow a duplicate predicate in #117. One definition,
   at the bottom."
  [phase]
  (cond
    (nil? phase) nil
    (keyword? phase) (name phase)
    :else (str phase)))

(defn break-refusal-lines
  "Lines explaining why a BREAK ability cannot fire right now, or nil to allow
   the send.

   Keyed on the WIRE'S LIVE ENCOUNTER, not the run phase. game.core.ice's
   `break-sub` builds `:break-req` as `(and current-ice (peek (:encounters
   @state)) ...)` — it never mentions the run phase, or even a run. Those
   coincide in an ordinary run and diverge exactly where #100 already taught us
   they diverge: `runs/force-ice-encounter` calls `set-phase` only `(when
   new-state)`, and ALL SIX call sites in the card pool (Ganked!, Chrysalis and
   friends) pass four args, so a forced encounter is live while `:phase` still
   reads \"success\" — or while there is no `:run` at all, off a Gang Sign
   breach. Keying on phase refused a legal and often mandatory break there, and
   told the seat to 'continue', which during a forced encounter passes priority
   and lets the subs fire. Both guest seats found this independently.

   ALSO requires the server's own `:playable` to be absent. That is the
   Boomerang guard: hardware.clj gives it a hand-written `Break 0 subroutines`
   ability that is not built by `break-sub`, does not break anything, and is
   legal outside any encounter — a label-shaped false positive that this
   predicate cannot distinguish on its own. When such an ability really is
   legal, the server marks it `:playable`, so requiring both signals means a
   refusal needs the rules AND the server to agree.

   `:phase` is now used for WORDING ONLY. A wrong phase string can no longer
   refuse anything."
  [ability game-state]
  (let [live-encounter? (some? (get-in game-state [:encounters :ice]))
        run (:run game-state)
        phase (normalize-phase (:phase run))]
    (when (and (not live-encounter?)
               ;; Never refuse something the server has told us is legal.
               (not (:playable ability)))
      (concat
        (if run
          [(format "You are not encountering a piece of ice (run phase '%s')." phase)
           "Subroutines can only be broken during the ENCOUNTER — approaching a"
           "piece of ice is not encountering it."
           "→ 'continue' to advance to the encounter, then retry the break."]
          ["No run is active and you are not encountering any piece of ice."
           "→ Breaking is legal only while ENCOUNTERING ice."])
        ;; Both signals come from the SAME snapshot, so they can be stale
        ;; together — the case is the opponent advancing us into an encounter
        ;; before we saw the diff. Name the escape rather than dead-ending.
        ["   (This reads your latest snapshot. If you believe you ARE in an"
         "    encounter, re-check with 'status' or 'wait' and retry.)"]))))

(defn ability-failure-lines
  "Extra lines to print when an ability failed to confirm, or nil to leave the
   generic timeout wording alone.

   Only speaks up when the ENGINE's own per-ability `:playable` flag is absent.
   That flag is computed by core/diffs `ability-playable?` — the same value that
   greys the button out in the web UI (nr.gameboard.board/list-abilities) — and
   core/diffs `select-non-nil-keys` drops it when false, so ABSENT means 'the
   server would refuse this'. Verified live: with no run active, every `Break …`
   ability on the rig carries no :playable while every `Add N strength` pump
   carries `:playable true`.

   Deliberately does NOT refuse the send. A stale snapshot can show a legal
   ability as unplayable, and mis-refusing costs more than a wordy error.

   Requires an actual ability map. A nil ability (index out of range) has no
   :playable for the trivial reason that there is nothing there, and claiming
   'the server reports this as not usable' about it would be a fabricated rules
   explanation — the exact species of misleading output this issue is about."
  [ability]
  ;; `seq`, not truthiness: {} is truthy in Clojure, so a bare `(and ability ...)`
  ;; still fabricates the claim for an empty map.
  (when (and (seq ability) (not (:playable ability)))
    ["ℹ️  The server reported this ability as not usable WHEN WE LAST LOOKED (it"
     "   carries no :playable flag — the same value that greys the button out in"
     "   the web UI), so this is very likely a RULES refusal rather than a lost"
     "   message. Re-check state before retrying; a blind retry is the wrong move."
     "   Usual causes: cost you can't pay, once-per-turn already used, a timing"
     "   window that isn't open, or an icebreaker below the ice's strength."]))

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

(defn- format-card-for-choice
  "Format a card map for display in choice prompts.
   Returns title with type info, e.g., 'Carmen [Killer]' or 'Sure Gamble [Event]'"
  [card]
  (let [title (:title card)
        subtype (:subtype card)
        card-type (:type card)]
    (cond
      subtype (str title " [" subtype "]")
      card-type (str title " [" card-type "]")
      :else title)))

(defn format-choice
  "Format a choice for display, handling different prompt formats
   Used by prompt and display functions to consistently format choices

   Usage: (format-choice {:value \"HQ\"}) -> \"HQ\"
          (format-choice {:label \"Draw a card\"}) -> \"Draw a card\"
          (format-choice \"Done\") -> \"Done\"
          (format-choice {:value {:title \"Carmen\" :subtype \"Killer\"}}) -> \"Carmen [Killer]\""
  [choice]
  (cond
    ;; Map with :value key - check if value is a card object
    (and (map? choice) (contains? choice :value))
    (let [v (:value choice)]
      (if (and (map? v) (:title v))
        (format-card-for-choice v)
        (str v)))

    ;; Map without :value - might be a card object directly, or has :label/:title
    (map? choice)
    (cond
      (:title choice) (format-card-for-choice choice)
      (:label choice) (:label choice)
      :else (str "Option with keys: " (keys choice)))

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
   Therefore, use conservatively - if this returns agendas, assume they MIGHT be scorable.

   The 1-arg form answers about a client-state you already hold (see corp-servers)."
  ([] (find-scorable-agendas @state/client-state))
  ([client-state]
   (let [side (:side client-state)]
    (if (side= "Corp" side)
      (let [servers (state/corp-servers client-state)
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
      []))))

(defn find-eot-rezzable-cards
  "Installed Corp assets/upgrades that are still unrezzed and affordable RIGHT NOW.
   Returns a sequence of {:card :title :cost}.

   This is the end-of-turn paid-ability window in the only shape worth pausing for
   (#103): the Corp's last click is spent, and there is still a Nico Campaign sitting
   unrezzed in a remote. Auto-end used to fire the instant the click pool emptied,
   so that window went by unmentioned.

   Deliberately NARROW, because every entry here costs the seat a beat:
   - ICE is excluded. Rezzing ICE outside a run is legal but pointless — there is no
     approach to defend at end of turn, and including it would pause on virtually
     every Corp turn, which is how a helpful beat turns into noise the seat learns
     to ignore.
   - Affordability is checked, so an unpayable rez never holds the turn open.
   - Corp only. The Runner's end-of-turn paid window exists but has no comparable
     always-visible trigger, and a pause we can't justify is just a stall.

   Affordability errs GENEROUS, and deliberately so — the two errors are not
   symmetric. A false negative silently closes the window, which is precisely the
   bug #103 exists to fix; a false positive costs the seat one `end-turn`. So the
   spend pool includes recurring credits on rezzed cards (guest review: a Corp at 0
   credits with a rezzed Mumba Temple can still rez a 2-cost asset, and the
   pool-only check refused it). Recurring credits restricted to other uses are
   counted anyway for the same reason.

   Known residual, same direction: rez-cost REDUCERS are not modelled, so a card
   made cheaper by an effect can still be missed. Costs a beat, never a wrong
   action — worth revisiting if a seat reports it.

   The 1-arg form answers about a client-state you already hold (see corp-servers)."
  ([] (find-eot-rezzable-cards @state/client-state))
  ([client-state]
   (let [side (:side client-state)]
    (if (side= "Corp" side)
      (let [pool (or (get-in client-state [:game-state :corp :credit]) 0)
            servers (vals (state/corp-servers client-state))
            ;; Both content and ice can carry recurring credits.
            all-cards (mapcat #(concat (:content %) (:ices %)) servers)
            recurring (->> all-cards
                           (filter :rezzed)
                           (keep #(get-in % [:counter :recurring]))
                           (reduce + 0))
            spendable (+ pool recurring)]
        (->> all-cards
             (filter #(contains? #{"Asset" "Upgrade"} (:type %)))
             (remove :rezzed)
             ;; A missing :cost is unknown, not free — don't invent a rez we can't price.
             (filter #(when-let [c (:cost %)] (<= c spendable)))
             (map (fn [card]
                    {:card card
                     :title (:title card)
                     :cost (:cost card)}))))
      []))))

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
  "Create minimal card reference for server commands.

   Mirrors the reference client's single narrowing point
   (`nr.gameboard.actions/send-command`: `[:cid :zone :side :host :type]`).
   `:host` is load-bearing, not decoration: the engine's `get-card` uses it to
   decide whether to walk a host's `:hosted` collection, and a hosted card's zone
   is `[:onhost]` — not a real zone — so without `:host` the lookup returns nil
   and the action is silently discarded. `:title` is ours, for logging."
  [card]
  {:cid (:cid card)
   :zone (:zone card)
   :side (:side card)
   :host (:host card)
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
      ;; An EXPLICIT index is a claim about which copy, and it outranks the
      ;; single-match shortcut: with one Leech installed, "Leech [9]" used to act
      ;; on the Leech, silently ignoring an index the seat asked for and would
      ;; have been told about if it had owned two (guest re-review).
      explicit-index? (nth (vec matches) index nil)
      (= 1 match-count) (first matches)
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

(declare current-run-ice)

(defn find-installed-corp-card
  "Find an installed Corp card by title
   Supports [N] suffix for duplicate cards: \"Palisade [1]\"
   Searches all servers for ICE, assets, and upgrades
   Duplicate titles with no [N] suffix: if one copy is the ICE at the current
   run position, that copy wins (#100 — run-scoped commands like fire-subs/rez
   shouldn't demand a suffix when the encounter already disambiguates).
   Otherwise returns nil and prints the disambiguation list."
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
      ;; Explicit index before the single-match shortcut — same reason as
      ;; find-installed-card above.
      explicit-index? (nth (vec matches) index nil)
      (= 1 match-count) (first matches)
      :else
      (let [cs @state/client-state
            ;; A FORCED encounter can put the Runner on an ICE that :position
            ;; does not point at; the wire's encounter summary, not position,
            ;; is the authoritative copy then (guest review of #100).
            enc-ice (get-in cs [:game-state :encounters :ice])
            run-ice (or enc-ice (current-run-ice cs))
            run-match (when run-ice
                        (first (filter #(= (:cid run-ice) (:cid %)) matches)))]
        (if run-match
          (do
            (println (format "→ %d copies of '%s' installed — using the one in the active run (%s). Use \"%s [N]\" to target another."
                             match-count title
                             (or (card-server-location run-match) "?") title))
            run-match)
          (do
            (println (format "❓ Multiple copies of '%s' installed (%d found)" title match-count))
            (println "   Specify which one:")
            (doseq [[idx card] (map-indexed vector matches)]
              (let [location (card-server-location card)
                    rezzed? (:rezzed card)
                    status (if rezzed? "rezzed" "unrezzed")]
                (println (format "   → \"%s [%d]\" (%s, %s)" title idx location status))))
            nil))))))

(defn installed-title-match-count
  "How many INSTALLED cards in `scope` share the title parsed out of card-name
   (the [N] suffix is stripped first, so \"Leech [1]\" counts Leeches).
   `scope` is :runner (the rig) or :corp (every server's ICE + content).

   This is the question \"was that nil ambiguity or absence?\" — the two states
   a find-installed-* miss collapses into, which a caller cannot tell apart
   from the nil alone."
  [card-name scope]
  (let [{:keys [title]} (parse-card-reference card-name)
        installed (case scope
                    :runner (let [rig (state/runner-rig)]
                              (concat (:program rig) (:hardware rig) (:resource rig)))
                    :corp (let [servers (state/corp-servers)]
                            (concat (mapcat :ices (vals servers))
                                    (mapcat :content (vals servers))))
                    nil)]
    (count (filter #(= title (:title %)) installed))))

(defn report-installed-lookup-miss!
  "Print the honest reason a find-installed-* lookup returned nil and return an
   {:status :error :reason ...} map. `scopes` are the zones the caller actually
   searched (:runner / :corp), in any order; the largest match count decides.

   Ambiguity and absence are different facts. find-installed-card /
   find-installed-corp-card return nil for BOTH, having already printed the
   \"❓ Multiple copies\" list in the ambiguous case — so a caller that reads
   nil as absence prints \"❌ Card not found installed: Leech\" directly beneath
   a list of the seat's two Leeches (#151 item 5). Two verdicts, one card, and
   the louder one is false: the seat is left believing its board is wrong.

   The Corp half of this had a fix that counted CORP installs only, so from a
   Runner seat the count was always 0 and every Runner ambiguity fell straight
   through to the not-found lie."
  [card-name scopes]
  (let [{:keys [title index explicit-index?]} (parse-card-reference card-name)
        best (apply max 0 (map #(installed-title-match-count card-name %) scopes))]
    (cond
      ;; The seat DID specify an index and the lookup still missed — the index is
      ;; out of range. Telling it to "specify [N]" is advice it already took, and
      ;; the worked example built from card-name reads "Leech [9] [0]", which is
      ;; not a thing you can type (guest panel). Name the range instead.
      (and explicit-index? (pos? best))
      (do
        (println (format "❌ No copy [%d] of '%s' — %d installed, so the valid indices are 0..%d."
                         index title best (dec best)))
        (println (format "   Re-run as \"%s [0]\"%s"
                         title
                         (if (> best 1) (format " … \"%s [%d]\"" title (dec best)) "")))
        (flush)
        {:status :error
         :reason (format "Index out of range: %s has %d installed copies" title best)})

      (> best 1)
      (do
        (println (str "   Re-run with the [N] suffix, e.g. \"" title " [0]\""))
        (flush)
        {:status :error
         :reason (str "Ambiguous: multiple copies of " title " installed — specify [N]")})

      :else
      (do
        (println (str "❌ Card not found installed: " card-name))
        (flush)
        {:status :error :reason (str "Card not found: " card-name)}))))

(defn find-card-by-cid
  "Find a card by CID (card ID) anywhere in the game state.
   Walks the entire game state tree, so cards in less common zones
   (hosted on other cards, identity slots, scored, current, set-aside,
   RFG, decks, source-card refs) are all reachable. Returns the first
   map with matching :cid that also has a :title (to skip non-card maps
   that happen to carry :cid — effects registry entries, log refs, etc).
   Returns nil if no match."
  [cid]
  (let [gs (state/get-game-state)]
    (->> (tree-seq coll? seq gs)
         (filter #(and (map? %)
                       (= cid (:cid %))
                       (:title %)))
         first)))

(defn find-selectable-card-by-cid
  "Resolve a CID that came from a prompt's :selectable list to a card map.

   Unlike find-card-by-cid, this does NOT require :title. At a multi-card remote
   breach the engine lists FACE-DOWN Corp cards as selectable; in the Runner's
   view those cards are legitimately title-less yet are real, pickable cards —
   they carry the fields create-card-ref narrows to, which is all select-card!
   needs (the explicit list used to be spelled out here and went stale when
   :host was added — that drift is the #113 bug in miniature). A
   title-less match must carry :zone AND :side — both present on a real board
   card and among the fields select-card! consumes — so non-card maps that merely
   carry a :cid (effects-registry entries, log refs) are still skipped. When
   several maps share the CID, a named (:title) match is preferred so behavior for
   ordinary visible cards is unchanged. (issue #70)

   Returns nil if no card-shaped map matches.

   2-arity: resolve against a CAPTURED game-state map (snapshot rendering,
   #139 — the prompt renderer must not re-read the live atom mid-snapshot)."
  ([cid] (find-selectable-card-by-cid cid (state/get-game-state)))
  ([cid gs]
  (let [matches (->> (tree-seq coll? seq gs)
                     (filter #(and (map? %)
                                   (= cid (:cid %))
                                   (or (:title %) (and (:zone %) (:side %)))))
                     seq)]
    (or (some #(when (:title %) %) matches)
        (first matches)))))

(def ^:private credit-payment-prompt-re
  ;; game.core.pick-counters/pick-credit-providing-cards builds exactly:
  ;;   "Choose a credit providing card (N of M [Credits])"
  ;; with an optional ", X of Y stealth" clause before the closing paren. Anchor
  ;; on BOTH the phrase and the bracketed count so an unrelated prompt that
  ;; happens to say "credit" can't match.
  #"(?i)credit providing card\s*\((\d+) of (\d+) \[Credits\]")

(defn credit-payment-prompt
  "Classify a prompt message as the engine's PER-CREDIT payment prompt.

   This prompt re-asks once per credit: picking a source pays 1, then the engine
   re-issues the same prompt with the count advanced (0 of 2 → 1 of 2 → …). Two
   different models hit this as friction — 5 choose-card calls for Overclock
   (#104), 2 for Unity (#110) — because nothing said how many calls were coming
   or that one call could cover them all.

   Returns {:paid N :target M :remaining (- M N)} on a match, else nil."
  [msg]
  (when-let [[_ paid target] (re-find credit-payment-prompt-re (str msg))]
    (let [paid (Long/parseLong paid)
          target (Long/parseLong target)]
      {:paid paid :target target :remaining (max 0 (- target paid))})))

(defn resolve-selectable
  "Resolve a prompt's :selectable list, separating cards this seat can actually
   pick from PHANTOM entries — CIDs absent from the seat's visible game state
   (hidden/opponent cards the engine leaks into the selectable list, e.g. on the
   discard-to-hand-size prompt). Returns
   {:pickable [{:idx <n> :card <map>} ...] :phantom [<idx> ...]}.

   Indices are the ORIGINAL positions in :selectable — choose-card resolves by
   `(nth selectable index)`, so callers must never renumber the underlying list.

   A CID string is resolved with find-selectable-card-by-cid, so a FACE-DOWN card
   the seat is accessing at a breach (title-less but zone-resident) counts as
   pickable rather than phantom. Card-shape is judged by :title OR :zone. (#70)

   2-arity: resolve against a CAPTURED game-state map (#139 snapshot rendering)."
  ([selectable] (resolve-selectable selectable (state/get-game-state)))
  ([selectable gs]
   (reduce
    (fn [acc [idx s]]
      (let [card (if (string? s) (find-selectable-card-by-cid s gs) s)]
        (if (and (map? card) (or (:title card) (:zone card)))
          (update acc :pickable conj {:idx idx :card card})
          (update acc :phantom conj idx))))
    {:pickable [] :phantom []}
    (map-indexed vector selectable))))

(defn format-selectable-card
  "Format one resolved selectable card for display: title, type, zone, rez state.
   A title-less card (e.g. a face-down Corp card being accessed at a breach) is
   labelled 'face-down card' with its zone so the seat can still pick it. (#70)"
  [card]
  (let [named? (or (:title card) (:printed-title card))
        title (or named? "face-down card")
        card-type (:type card)
        zone (:zone card)
        rezzed? (:rezzed card)]
    (str title
         (when (and named? (seq (str card-type))) (str " [" card-type "]"))
         (when (seq zone)
           (str " (in " (str/join "/" (map name zone)) ")"))
         (when (some? rezzed?) (if rezzed? " (rezzed)" " (unrezzed)")))))

(defn print-selectable!
  "Print resolved :selectable parts truthfully: pickable cards with their TRUE
   indices, then a single warning line for any phantom entries (CIDs the seat
   can't see and can't select). Takes the {:pickable :phantom} map from
   resolve-selectable (resolve once, print once). `indent` is the leading
   whitespace per line."
  [{:keys [pickable phantom]} indent]
  (doseq [{:keys [idx card]} pickable]
    (println (str indent idx ". " (format-selectable-card card))))
  (when (seq phantom)
    (let [n (count phantom)
          first-pickable (:idx (first pickable))
          ;; #104 asked whether the phantoms land BEFORE or AFTER the pickable
          ;; rows. The engine answers it: compute-selectable builds the list from
          ;; get-all-cards, which walks `(for [side [corp runner]] ...)` — Corp
          ;; cards always precede Runner cards. So on the discard-to-hand-size
          ;; prompt (:choices {:card in-hand?} matches BOTH hands) the Corp seat
          ;; sees its own cards first (phantoms trailing, numbering intact) and
          ;; the Runner seat sees them last (phantoms leading, numbering offset).
          ;; Only the leading case has a gap to explain; saying "mind the gap"
          ;; on the trailing case is the noise this item was filed about.
          leading? (and first-pickable
                        (every? #(< % first-pickable) phantom))]
      (println (str indent "⚠️  " n
                    (if (= 1 n) " entry (index " " entries (indices ")
                    (str/join ", " phantom)
                    ") are cards you can't see (hidden/opponent) —"
                    " not selectable from this seat; ignore them."))
      (when leading?
        (println (str indent "   Your own rows therefore start at index "
                      first-pickable
                      " — that is the real index to pass to `choose-card`,"
                      " not a numbering bug."))))))

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
        ;; Pattern for 'new' server: new, remotenew, remote new, servernew,
        ;; server new, and the engine's own label phrasing 'new remote' /
        ;; 'new server' (a seat naturally types the label the game UI shows).
        new-pattern #"(?:remote\s*|server\s*)?new(?:\s*(?:remote|server))?"
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
     ;; Prefer the public count fields for zone sizes. On the wire a player's
     ;; OWN deck is fog-of-war-hidden (arrives as [] with the real size in
     ;; :deck-count), so (count deck) is a constant 0 and the deck never moves
     ;; in show-state-diff — even on a draw. Own hand IS visible, but read its
     ;; public :hand-count too for consistency. Fall back to (count zone) when
     ;; the count field is absent OR nil (older/synthetic states); `or` guards
     ;; present-but-nil, which a bare get-in default would not.
     :hand-size (or (get-in gs [side :hand-count]) (count (get-in gs [side :hand])))
     :deck-size (or (get-in gs [side :deck-count]) (count (get-in gs [side :deck])))
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

(defn- run-phase
  "Current run phase keyword/string (approach-ice, encounter-ice, movement,
   approach-server, etc.) or nil if no run."
  [state]
  (get-in state [:game-state :run :phase]))

(defn live-encounter?
  "True when the wire reports an ICE encounter in progress, WHATEVER the run
   phase says.

   This is the client-side mirror of the engine's own authority. game.core.runs
   dispatches `continue` on

       (if (get-current-encounter state) :encounter-ice (:phase (:run @state)))

   — the encounter outranks the phase there, and the encounter stack is POPPED
   when the encounter ends, so a present [:encounters :ice] means an encounter
   that is actually happening. A FORCED encounter (an on-access Archangel, a
   redirect) is live while [:run :phase] still reads \"success\": every client
   gate written as (= run-phase \"encounter-ice\") is therefore blind to exactly
   the case the engine handles fine (#160)."
  [state]
  (some? (get-in state [:game-state :encounters :ice])))

(defn encounter-window-gs?
  "encounter-window? on a BARE [:game-state] map, for the callers that already
   hold one (the park loop's wake reason, the display's window-passer). ONE
   definition of \"is this an encounter window\" rather than a second hand-rolled
   `(seq (:encounters gs))` per caller — the #127 ratchet is about exactly that
   drift, and #164 added three more call sites at once."
  [gs]
  (boolean (seq (:encounters gs))))

(defn encounter-window?
  "True when the current priority window is an ENCOUNTER's, so its pass ledger —
   [:encounters :no-action] — is the one that answers \"who has passed?\".

   The authoritative signal is the PRESENCE OF THE SUMMARY, not any field in it.
   game.core.diffs/encounters-summary emits a map only while a current encounter
   exists, and it always stamps :encounter-count — but BOTH other keys are
   optional. :ice is dropped when encounter-ice-summary cannot resolve the card,
   and :no-action is absent until somebody passes. So the honest minimum for a
   live encounter nobody has passed yet is exactly {:encounter-count 1}, and two
   narrower drafts of this predicate each missed a real board:

     * keying on :ice alone handed the recorded #150 boards
       ({:encounters {:no-action \"corp\"}}, no :ice) back to the run ledger;
     * adding :no-action still missed {:encounter-count 1}, which is the state a
       forced encounter is in for its whole first half (guest panel, 2nd pass).

   seq, not a truthiness test on a field: the ledger's meaningful states are
   ABSENT (nobody has passed) and naming-a-side, and reading absent as \"use the
   other ledger\" is the (or supplied (live-read)) trap this codebase keeps
   re-learning."
  [state]
  (encounter-window-gs? (:game-state state)))

(defn at-encounter?
  "True at any ICE encounter — the normal :encounter-ice phase OR a forced one
   the phase string does not name. The gate every encounter handler should use
   in place of a bare (= run-phase \"encounter-ice\").

   Tolerates a wire that reports the phase without an encounter summary (older
   serializations, a diff that has not landed yet), so switching a gate to this
   can only ever widen it, never drop the case that already worked."
  [state run-phase]
  (boolean (or (= run-phase "encounter-ice")
               (encounter-window? state))))

(defn- own-prompt
  "The given side's engine prompt-state (nil when it holds none). The ONE
   side-keyed prompt read in the wake path — has-prompt? and the #102 waiting-
   prompt guard both go through it (#127 ratchet: no new hand-rolled side
   derivations)."
  [state side]
  (get-in state [:game-state (keyword side) :prompt-state]))

(defn- has-prompt?
  "Check if the given side has an actionable prompt"
  [state side]
  (let [prompt (own-prompt state side)]
    (and prompt
         (not (state/waiting-prompt-type? (:prompt-type prompt)))
         (or (seq (:choices prompt))
             (seq (:selectable prompt))))))

(defn opponent-mulligan-pending?
  "True when the opponent has NOT finished their opening mulligan (#87).

   Delegates to ai-state, which owns the one definition — every surface that
   answers 'is it my move' (this wake path, the start-turn guard, and the turn
   indicator appended to every command) must give the SAME answer. A `defn`
   delegation, deliberately not `(def x state/x)`: the latter captures the function
   VALUE, so with-redefs in tests silently misses it and a REPL :reload of the
   owning namespace leaves this bound to the stale fn (the facade-unbound trap)."
  [client-state]
  (state/opponent-mulligan-pending? client-state))

(defn my-turn-to-act?
  "Check if it's our turn to act (need to start-turn or have clicks).

   MOVED to ai-state (#117) — see there for the wake conditions and why 'both
   players at 0 clicks' is not one of them. It had to go down a layer because
   `get-turn-status`, which backs every seat-facing turn surface, lives in
   ai-state and so could not call it here; it grew its own boundary heuristic
   instead, and that second copy is what #117 is.

   Kept as a delegating `defn` (not `(def x state/x)`, which captures the fn
   VALUE and defeats with-redefs / REPL :reload) so every existing caller and
   the umpire's `eval` recipe still resolve `ai-core/my-turn-to-act?`."
  [state side]
  (state/my-turn-to-act? state side))

(defn- turn-awaiting-start?
  "True when it's our turn at a boundary but the turn has NOT been started yet
   (we hold 0 clicks). The seat must call `start-turn` before it can act.

   This is the subset of `my-turn-to-act?` that is a turn boundary rather than a
   live, actionable turn. It lets `relevance-reason` wake with the distinct
   reason :my-turn-start instead of :my-turn, so the seat isn't misled into
   trying to act before starting (and so a boundary doesn't read like a stall).
   Both seats hit this confusion in the first rung-2 game (laundry-list #1)."
  [state side]
  ;; nil-safe on side (see my-turn-to-act?): no side => no pending turn start.
  (when-let [my-side (keyword side)]
   (let [active-player (get-in state [:game-state :active-player])
        my-clicks (get-in state [:game-state my-side :click] 0)
        end-turn (get-in state [:game-state :end-turn])
        turn-number (get-in state [:game-state :turn] 0)]
    (and (= 0 my-clicks)
         (or
           ;; Opponent ended their turn; active-player is still them until I start
           (and end-turn (not= (name my-side) active-player))
           ;; Post-mulligan turn 0: Corp goes first but hasn't started
           (and (= 0 turn-number) (= my-side :corp)))))))

(defn- ping-message?
  "Check if a log entry is a 'ping' wake signal.
   Returns true if the chat message body CONTAINS 'ping' (case-insensitive).
   Loosened from exact-match per michael-nr (forum ai-netrunner [162]): a human
   won't reliably send a bare 'ping' (he typed English and slept the seat in the
   HITL game-5 wedge), so any chat mentioning 'ping' — 'ping', 'ping your turn',
   'PING!' — now wakes. Still narrow enough that ordinary AI-vs-AI banter with no
   'ping' token is ignored, and a false wake is cheap (loop re-checks, finds no
   decision, sleeps again) while a MISSED wake is a silent wedge — so we bias
   toward waking. Used by AIs to wake each other without game state changes."
  [entry]
  (let [text (or (:text entry) "")]
    ;; Match chat messages (format "Username: message") whose body mentions "ping".
    ;; Capture only the body after the first colon so a 'ping' in the USERNAME
    ;; (e.g. "Pingu: hello") does not spuriously wake.
    (when-let [msg-part (second (re-find #":\s*(.+)" text))]
      (clojure.string/includes? (clojure.string/lower-case msg-part) "ping"))))

(defn ping-since?
  "True if any log entry at index `start-count` or later is an opponent `ping`
   wake signal. Shared wake predicate so both surfaces react to an explicit chat
   nudge: `wait-for-relevant-diff` (turn/boundary waits) and `monitor-run!`'s
   persistent defender loop (empty run-priority windows). `start-count` is the
   log length captured when the wait began, so only pings that arrive DURING the
   wait wake it — a stale ping from before the wait started is ignored. A `ping`
   is any opponent chat mentioning the word (see `ping-message?`)."
  [log start-count]
  (boolean (some ping-message? (drop start-count log))))

(declare current-run-ice)
(declare encountered-ice)
(declare encounter-ice-active?)

(defn- in-active-game?
  "True once a game has actually started: we hold a :side AND the game-state
   reports an :active-player. False in the lobby / pre-game, where the seat may
   have no :side yet and the turn predicates would `(name nil)` NPE (#46).
   `wait` uses this to poll for game-start instead of crashing or steering off
   nil turn data."
  [state]
  (boolean (and (:side state)
                (get-in state [:game-state :active-player]))))

(defn- run-pass-window?
  "Run phases whose advance is a PRIORITY PASS via `continue` (not an encounter
   break/tank decision — that is runner-encounter-decision-pending?'s job, and
   'success'/access is the Runner acting on access, not a pass). These are the
   windows where a seat must `continue` to advance the run.

   Exactly the set the seat's own run loop acts on (ai-runs `should-i-act?` /
   `advance-abandoned-window`, #{\"approach-ice\" \"movement\"}), so a :my-run-window
   wake always coincides with the seat deciding to act — that is what keeps it from
   spinning. NB the display's 'approach-server' is NOT a wire phase: the engine only
   ever set-phases :approach-ice / :encounter-ice / :movement / :success / :initiation
   (game.core.runs), and the approach-server window is {:phase \"movement\" :position 0}
   — covered here by \"movement\"."
  [phase]
  (contains? #{"approach-ice" "movement"} phase))

(defn ice-pass-index
  "Convert the run's `position` COUNTDOWN into the 1-based order the Runner meets
   the ICE — position = ice-count is the outermost ICE, i.e. ICE 1 of N. Returns
   nil when the inputs can't support an honest index (no count, past all ICE, or
   a position out of range — the wire is the volatile coupling, so drop the index
   rather than print a bogus one).

   Single source for the convention (#115): the run ladder printed 'ICE 1 of 2'
   while the approach handler printed 'position 2/2' two lines above it — the same
   ICE under two conventions that read as a contradiction."
  [position ice-count]
  (when (and ice-count position (pos? position) (<= position ice-count))
    (inc (- ice-count position))))

(defn describe-approached-ice
  "One line naming the ICE the Runner is approaching, in the ladder's convention.

   Renders the fog of war honestly instead of as breakage: an unrezzed ICE has no
   title on the Runner's wire, and the old format string put the literal default
   through %s to print `ICE: ICE` (#115), which reads as a display bug rather than
   as 'you are not allowed to know yet'."
  [ice-title position ice-count]
  (let [idx   (ice-pass-index position ice-count)
        where (if idx
                (format "ICE %d of %d (outermost first)" idx ice-count)
                "ICE")
        named (when (and ice-title (not= ice-title "ICE")) ice-title)]
    (if named
      (format "%s: %s — unrezzed" where named)
      (format "%s — unrezzed, identity hidden until the Corp rezzes it" where))))

(defn- run-window-owner
  "Who owns the FIRST un-passed pass at the current run pass-window, or nil when it
   is not a pass window or both seats have already passed. Ownership is read from
   run-level :no-action, the SAME source of truth as the seat's own `should-i-act?`:
     nil/false → active player (the Runner) owes the first pass
     :runner   → Runner passed; the Corp owes the second
     :corp     → Corp passed (e.g. corp-auto-no-action pressed it first); Runner owes it
   The check is deliberately order-AGNOSTIC (it asks who has *actually* passed, not
   who 'should' go first), so it is correct whether the Runner or an auto-no-action
   Corp passed first. This is the run-window analogue of the turn-boundary ownership
   my-turn-to-act? tracks — see #91: without it a seat that ACQUIRES priority at a new
   window is never woken, so a blocked `wait` sleeps through its own move and the
   un-babysat game deadlocks.

   Caveat: run-level :no-action is single-valued — it records only the FIRST passer,
   and the second `continue` advances the phase (set-phase resets it to false). So a
   transient 'both passed, advance in flight' is indistinguishable from 'second passer
   still owes' and would name the second passer as owner. That is safe here because the
   side that has already passed is driving the run via auto-continue-loop / park (whose
   own wake is park-wake-reason, not this), never sitting in a bare `wait` — so
   relevance-reason is not what advances it. Do not lean on :no-action to mean 'the
   only un-passed side' beyond that.

   Encounter-ice (#102 items 4/6, the Runner twin of #150): the encounter is ALSO
   a both-must-pass window, but its pass is recorded on the current encounter —
   serialized under [:game-state :encounters :no-action] — not on the run, and
   game.core.runs `continue :encounter-ice` never resets it when subs fire. So
   after `tank` → Corp fires → Corp passes, the Runner owes the closing continue
   while run-level :no-action still reads false. Reading only the run level made
   this window nobody's: `wait` slept 300s three times in one marquee game (or,
   with clicks in hand, fell through to :my-turn). Same ownership rule, read from
   the encounter's own key. While subs are still unresolved the Runner's 'pass' is
   the break/tank decision and :encounter-decision (ranked higher) reports it; this
   owner is what remains once nothing is left to authorize."
  [state]
  (let [phase (run-phase state)
        owner-from (fn [no-action]
                     (let [runner-passed? (contains? #{:runner "runner"} no-action)
                           corp-passed?   (contains? #{:corp "corp"} no-action)]
                       (cond
                         (not runner-passed?) :runner   ; nobody (or corp-only) has passed: Runner owes the first pass
                         (not corp-passed?)   :corp     ; Runner passed, Corp owes the second
                         :else nil)))]                  ; both passed — window is closing, nothing to own
    (cond
      ;; The ENCOUNTER outranks the phase, and it is tested first for the same
      ;; reason the engine tests it first: game.core.runs dispatches `continue`
      ;; on (if (get-current-encounter state) :encounter-ice (:phase ...)). A
      ;; FORCED encounter is a both-must-pass window too and records its pass on
      ;; the encounter, but [:run :phase] there reads "success" — or "movement",
      ;; which the run-pass-window? branch below would have answered from the
      ;; WRONG ledger. Phase-only left that window with no owner and `wait` slept
      ;; through it (#160, the forced twin of #102 items 4/6).
      (or (= phase "encounter-ice") (encounter-window? state))
      (owner-from (get-in state [:game-state :encounters :no-action]))

      (run-pass-window? phase)
      (owner-from (get-in state [:game-state :run :no-action]))

      :else nil)))

(defn i-already-passed-run-window?
  "True when the engine has recorded THIS side as the (first) passer of the
   current run/encounter priority window — [:run :no-action] or
   [:encounters :no-action] naming us. A further `continue` from us is then
   never legitimate: at best a no-op the engine ignores, at worst it re-fires
   a blocked checkpoint and mints duplicate opponent prompts (#75). The
   OPPONENT owes the window, so loops must report an opponent wait and park
   instead of spinning :action-taken into the stuck-detector's false
   'stuck in same state' alarm (#98 — 3+ marquee occurrences, each burning a
   persistent monitor call while the Runner was simply thinking).

   Single-valued caveat (see run-window-owner): :no-action records only the
   FIRST passer and set-phase resets it, so when it names the OPPONENT we may
   legitimately owe the closing pass — this predicate is true ONLY when it
   names US.

   ONE ledger at a time, chosen the way run-window-owner chooses it (#160, guest
   panel CRITICAL). This used to OR the two together, which is safe only while
   they describe the same window. A FORCED encounter breaks that: the run-level
   key still holds the passer of the SUSPENDED outer window — a redirect during
   movement, where we may well have passed movement already — and ORing it in
   answered \"you have already passed\" about an encounter we have not touched.
   Both send chokepoints consult this predicate, so the encounter's closing pass
   was suppressed while the Runner-side latch had already recorded it as sent:
   a silent deadlock, not a retry."
  [state side]
  (when side
    (let [side-name (str/lower-case (name (keyword side)))  ; "Corp"-tolerant (#69)
          mine #{(keyword side-name) side-name}
          no-action (if (encounter-window? state)
                      (get-in state [:game-state :encounters :no-action])
                      (get-in state [:game-state :run :no-action]))]
      (boolean (contains? mine no-action)))))

(defn opponent-passed-encounter?
  "True when the OPPONENT is recorded as the passer of the current encounter, so
   our own `continue` will END the encounter rather than merely pass priority.

   game.core.runs `continue :encounter-ice` reads: if the encounter's :no-action
   names the other side, `encounter-ends`. It does NOT check whether subroutines
   are still unbroken — the Corp declining to fire is the Corp's whole turn at
   this window, and the subs simply never resolve. Verified against the engine in
   game.ai-forced-encounter-wire-test for both a normal and a forced encounter:
   Bank Job survives, the sub is neither broken nor fired, the run moves on.

   This is why a seat facing unbroken subs cannot be told a flat \"continue does
   not pass an encounter\" (the #92 rule): true while the window is still open,
   false — and stalling — once the opponent has passed it."
  [state side]
  ;; Expressed as \"somebody passed this encounter and it was not us\" so the side
  ;; normalization stays in i-already-passed-run-window? — the #127 ratchet is
  ;; right that a second hand-rolled derivation is how the 45 got there.
  (boolean (and side
                (encounter-window? state)
                (contains? #{:corp "corp" :runner "runner"}
                           (get-in state [:game-state :encounters :no-action]))
                (not (i-already-passed-run-window? state side)))))

(defn- my-run-window?
  "True when THIS side currently owns the un-passed pass at an active run window.
   Waking on this is safe from the old :run-active spin (see relevance-reason): a
   side owns the window only while it still owes its pass, so it wakes once, passes
   (flipping :no-action), and then sleeps until it acquires the NEXT window.

   An ENCOUNTER counts as an active window even with no run behind it (#164):
   a forced encounter can outlive its run entirely (Quest Completed → Ganked!),
   and run-window-owner already names an owner there — this guard was the only
   thing throwing that answer away, so `wait` had no window to own and slept."
  [state side]
  (boolean (and (or (run-active? state) (encounter-window? state))
                (= (some-> (run-window-owner state) name) (name (keyword side))))))

(defn- runner-encounter-decision-pending?
  "True when the Runner is stopped at an ICE encounter that needs a break /
   tank / jack-out decision from us, but which the engine did NOT surface as a
   server :prompt — so `has-prompt?` misses it. Without this, a plain `wait`
   sleeps the full timeout and the pending 'N unbroken sub(s) - authorization
   required' only appears on the next `continue` (#47).

   Wakes when: Runner side, encounter-ice phase, a rezzed current ICE with
   unbroken+unfired subs, and the Runner has NOT already passed this encounter
   (once we pass, the decision is made and we're merely waiting on the Corp to
   fire — that is :waiting-for-opponent, not a pending decision). Strategy-level
   pre-auth (:tank) is intentionally NOT consulted — `wait` carries no strategy,
   and a live encounter is worth waking on regardless of how we'll resolve it.

   Three gates here each answered about a run rather than about the encounter,
   and a forced encounter fails all three (#160's rule, pushed up a level for
   #164):
     * the phase string — `at-encounter?` instead, the same swap the run
       automation got;
     * the position-derived `current-run-ice` and its bare `:rezzed` — an
       encounter's ICE need be neither at :position nor rezzed, so
       `encountered-ice` + `encounter-ice-active?`, which is the engine's own
       active-ice? rule;
     * the pass ledger — `i-already-passed-run-window?`, which picks ONE
       ledger the way run-window-owner picks it. The old read ORed run-level in,
       so a Runner who had already passed the SUSPENDED outer window was told it
       had passed an encounter it had not touched."
  [state side]
  (boolean
    (and (= (keyword side) :runner)
         (at-encounter? state (run-phase state))
         (let [current-ice (encountered-ice state)
               subs (:subroutines current-ice)
               unbroken (filter #(and (not (:broken %)) (not (:fired %))) subs)]
           (and current-ice (encounter-ice-active? state current-ice)
                (seq unbroken)
                (not (i-already-passed-run-window? state side)))))))

(defn- relevance-reason
  "Determine why we should wake up (or nil if not relevant).
   Returns keyword indicating wake reason.

   Wake reasons (in priority order):
     :run-started        — a run started this poll cycle
     :has-prompt         — we have an actionable prompt (encounter, rez
                           window, paid-ability window, access decision)
     :encounter-decision — Runner is at an ICE encounter with unbroken subs
                           that needs a break/tank/jack-out call, but which the
                           engine did NOT surface as a server prompt (#47)
     :run-ended          — a run that was in progress has ended
     :run-phase-change   — run still active but phase transitioned
                           (approach-ice → encounter-ice → movement →
                            approach-server). Catches the case where Runner
                            breaks all subs but the run keeps going and Corp
                            needs to participate via monitor-run.
     :my-turn-start      — it's our turn at a boundary; we must call start-turn
                           before acting (we hold 0 clicks until we do)
     :my-turn            — it's our turn and we have clicks; act now
     :my-turn-end        — our turn is out of clicks but has NOT ended (#120):
                           nobody owes a start-turn, my-turn-to-act? is false for
                           BOTH sides, and `end-turn` is the only move. Fires for
                           the turn's OWNER only
     :my-run-window      — we own the un-passed pass at an active run window
                           (approach-ice / movement / approach-server, and the
                           encounter's own close once its subs are resolved,
                           #102). #91: a
                           seat that had passed a PREVIOUS window otherwise sleeps
                           through the new window it now owns and the game
                           deadlocks. Outranks :my-turn (#102) — while a run is
                           live, an owed continue is the more specific fact, and
                           reporting :my-turn there sent the seat looking for a
                           turn to start instead of a run to finish.

   NB: there is intentionally no generic ':run-active' wake. A run merely
   being in progress is not a wake-worthy event for us — we wake when the
   run produces a prompt for our side (:has-prompt) or transitions on/off
   (:run-started / :run-ended) or moves between phases, or when a pass window
   becomes OURS to act in (:my-run-window). Otherwise we sit silently. The
   earlier :run-active behaviour caused wait-for-relevant-diff to return after
   one polling tick during every opponent run, defeating the wait; :my-run-window
   avoids that by firing only for the side that currently owes the pass, never
   for the side merely waiting on the opponent to pass.

   The 4-arg form (with initial-run-phase) enables :run-phase-change
   detection. The 3-arg form retains the old behavior for callers (mostly
   tests) that don't track phase."
  ([state side initial-run-active?]
   (relevance-reason state side initial-run-active? nil))
  ([state side initial-run-active? initial-run-phase]
   (let [current-run-active? (run-active? state)
         current-phase (run-phase state)
         has-actionable-prompt? (has-prompt? state side)]
     (cond
       ;; Game over - the match ended (winner declared, concession, or tie).
       ;; Highest priority: nothing else matters once the game is decided, and
       ;; a seat sitting in a long `wait` when the game ends would otherwise
       ;; burn the full timeout (default 300s) before noticing. Wake immediately
       ;; so the seat can tear down / report instead of hanging.
       (state/game-over? (:game-state state))
       :game-over

       ;; The server closed our lobby without the game reaching a decided
       ;; state (#93). Same urgency as :game-over: there is nothing left to
       ;; wait for, and a seat sleeping here would burn the full timeout on a
       ;; game that no longer exists.
       (state/lobby-gone? state)
       :game-gone

       ;; Run started - high priority, wake up!
       (and current-run-active? (not initial-run-active?))
       :run-started

       ;; We have a prompt to respond to
       has-actionable-prompt?
       :has-prompt

       ;; Guard (#102 item 5): our OWN prompt is a 'waiting' prompt — the engine is
       ;; blocked on the opponent's decision (Runner mid-Wildcat-Strike while the
       ;; Corp picks the mode, marquee 471ef829). Nothing of ours is actionable
       ;; until it clears: has-prompt? already excludes it and send-continue!'s
       ;; #75 chokepoint refuses to pass under it. But my-turn-to-act? is true for
       ;; the whole of our turn, so `wait --since` returned instantly and
       ;; repeatedly with :my-turn / '(no new entries)'. Everything below that
       ;; says "your move" — :encounter-decision, :my-run-window, the :my-turn
       ;; family — stays asleep; the transitions above still report, and the
       ;; prompt clearing is itself a wake (the next tick falls through).
       ;;
       ;; Placed ABOVE :encounter-decision (guest panel): an on-encounter Corp
       ;; choice (Saisentan's "choose a card type" hands the Runner a waiting
       ;; prompt, src/clj/game/cards/ice.clj) leaves the subs unbroken, so the
       ;; encounter wake fired instantly and repeatedly under the Corp's prompt —
       ;; the same spin as item 5, one branch higher. :run-ended and
       ;; :run-phase-change sit below this too. In the polling loop they are
       ;; DELAYED, not lost — computed against the wait call's own baseline, so
       ;; the tick on which the prompt clears reports them. The --since fast
       ;; path has no historical baseline (it passes initial-run-active? false
       ;; and no phase), so a transition that completed under a waiting prompt
       ;; is reported there only as whatever the post-transition state wakes on
       ;; — a limitation of that path that predates this guard.
       (state/waiting-prompt-type? (:prompt-type (own-prompt state side)))
       nil

       ;; Runner is at an ICE encounter with unbroken subs that needs our
       ;; break/tank/jack-out decision, but which the engine did NOT model as a
       ;; server :prompt. `has-prompt?` misses it, so wait would otherwise sleep
       ;; through the whole encounter (#47). Ranks just below a real prompt.
       (runner-encounter-decision-pending? state side)
       :encounter-decision

       ;; Run just ended - might need cleanup
       (and initial-run-active? (not current-run-active?))
       :run-ended

       ;; Run phase transitioned mid-run (e.g., subs broken, encounter ends).
       ;; Requires a baseline phase to compare against; nil baseline means
       ;; the caller isn't tracking phase, so skip this check.
       (and initial-run-active? current-run-active?
            initial-run-phase
            (not= initial-run-phase current-phase))
       :run-phase-change

       ;; We own an un-passed run pass-window (#91). Ranked ABOVE my-turn-to-act?
       ;; (#102): a run costs one click, so the Runner mid-run normally still holds
       ;; 2-3 — my-turn-to-act? is true throughout its own run and used to win this
       ;; cond, so `wait` returned :my-turn while a continue was owed at
       ;; movement/approach-server. That misdirects the seat into start-turn
       ;; thinking mid-run (both Fable runner sessions, marquee 30c4a1c0). Owning an
       ;; un-passed window is the strictly more specific fact, so it reports first.
       ;;
       ;; This reorder changes only the LABEL, never the wake set: both branches
       ;; wake, and every state that reached :my-turn before still wakes here or
       ;; below. It also still covers the original #91 case (window ours, 0 clicks
       ;; left, my-turn-to-act? false), and it does not spin — we own the window
       ;; only until we pass it, and it never fires for the side merely waiting on
       ;; the opponent to pass.
       (my-run-window? state side)
       :my-run-window

       ;; It's our turn. Distinguish a live actionable turn (:my-turn, we have
       ;; clicks) from a turn boundary where we must call start-turn first
       ;; (:my-turn-start, 0 clicks). Conflating them made a boundary look like
       ;; a stall and misled both seats in the first rung-2 game.
       (my-turn-to-act? state side)
       (if (turn-awaiting-start? state side) :my-turn-start :my-turn)

       ;; My turn is out of clicks but has NOT ended (#120). my-turn-to-act? is
       ;; false for BOTH sides here — its active-player arm needs clicks, and its
       ;; other two arms need :end-turn or turn 0 — so this cond used to fall
       ;; through to nil on every seat and both `wait` calls slept the full 300s.
       ;; A mutual deadlock, and the side that could break it (the one owed the
       ;; end-turn) was the one being told it had no move.
       ;;
       ;; This is an END-TURN OBLIGATION, not a click count. Waking on
       ;; 'both players at 0 clicks' is the bug my-turn-to-act?'s docstring
       ;; explicitly refuses to reintroduce — that shape fires on every run
       ;; started with the last click. my-turn-orphaned? is the narrower fact
       ;; (#117): active player, 0 clicks, :end-turn false, no run, no prompt of
       ;; ours, and none of the engine's other zero-click pauses (phase 1.2,
       ;; post-discard priority — both resolved by a PHASE command, not end-turn).
       ;;
       ;; Ranked LAST on purpose, and NOT because the earlier branches are
       ;; mutually exclusive with it — my first version of this comment claimed
       ;; that and the guest panel falsified it. The STATE branches (run, prompt,
       ;; game-over) are excluded by the predicate, but :run-ended and
       ;; :game-over/:game-gone are TRANSITIONS: a run that ends on the last click
       ;; leaves us orphaned AND reports :run-ended, which outranks this. That is
       ;; the right precedence — the transition is the news — so the obligation is
       ;; carried in :run-ended's guidance instead of by reordering the ladder
       ;; (see end-turn-obligation-lines). Reordering would suppress the run-end
       ;; report, which is a worse trade.
       ;;
       ;; Called with the side we were HANDED, and side-relative in the
       ;; predicate: an end-turn from the seat whose turn it isn't ends the
       ;; OPPONENT's turn and is unrecoverable. The opponent stays asleep here
       ;; and wakes on :my-turn-start once the flag latches.
       (state/my-turn-orphaned? state side)
       :my-turn-end

       ;; Nothing relevant
       :else nil))))

(defn- end-turn-obligation-lines
  "The seat owns a turn that is out of clicks but has NOT ended (#120). Emitted
   from TWO wake reasons — :my-turn-end, and :run-ended when the run that just
   ended was the last thing standing — so it is a function, not two literals.

   'Out of clicks' is NOT 'out of moves', and this is where a wake reason can lose
   a game in one line. `check-auto-end-turn!` refuses to auto-end at 0 clicks for
   TWO reasons, and both are still live in exactly this state:
     - a scorable agenda — scoring costs no click (guest panel, second pass; the
       first version of this function checked only the rez window)
     - the end-of-turn paid window (#103 — marquee ac71ce63 lost a Nico Campaign
       rez to an auto-end)
   Either one is named BEFORE the end-turn steer, and end-turn is described as
   what to do after. A wake that answers this state with a flat 'send end-turn'
   contradicts the auto-end surface, and the seat that believes the newer surface
   pays for it. Two surfaces, one answer: call the SAME detectors rather than
   deciding again here.

   Both detectors are handed `state` rather than re-reading the atom. The wait
   loop classifies a snapshot and formats guidance from it later; a diff landing
   in that gap would otherwise staple a card list from a newer board onto a
   description of the older one (guest panel). Both are Corp-only by construction,
   so a Runner never sees either list."
  [state]
  (let [scorables (find-scorable-agendas state)
        rezzables (find-eot-rezzable-cards state)
        closing ["      The opponent cannot move until you do, and another `wait`"
                 "      returns here unchanged — an empty game log is expected:"
                 "      they are already waiting on you."]]
    (if (or (seq scorables) (seq rezzables))
      (-> ["   👉 Your turn is out of clicks but has NOT ended — and 0 clicks does NOT"
           "      mean 0 moves. Still available to you right now:"]
          ;; "MAY", not "can". Both detectors are deliberately approximate and
          ;; say so in their own docstrings — find-scorable-agendas ignores
          ;; :cannot-score (Clot et al.) and asks callers to "assume they MIGHT
          ;; be scorable"; find-eot-rezzable-cards counts restricted recurring
          ;; credits on purpose. That hedge used to live only in a code comment
          ;; the seat never sees, while the printed line was flatly categorical.
          ;; Given what this repo has paid for guidance text that asserts more
          ;; than it knows, the surface should say what the detector means.
          (into (map (fn [{:keys [title counters requirement]}]
                       (format "        🎯 %s (%d/%d advancement) — you MAY be able to score this (no click needed) — check"
                               title counters requirement))
                     scorables))
          (into (map (fn [{:keys [title cost]}]
                       (format "        💰 %s may still be rezzable for %d¢ — check" title cost))
                     rezzables))
          ;; Mirrors check-auto-end-turn!'s own hedge. The rez detector errs
          ;; generous on purpose (it counts restricted recurring credits), so an
          ;; entry here is an opportunity to CHECK, never an instruction to obey.
          (conj "      Do those first if you want them, then `end-turn` — nothing to do? just `end-turn`.")
          (into closing))
      (into ["   👉 Your turn is out of clicks but has NOT ended — send `end-turn`."
             "      Nobody owes a start-turn."]
            closing))))

(defn wake-reason-guidance-lines
  "Lines that decode a wake `reason` into the action it demands, printed under the
   `⚡ Woke up: <reason>` line. `state` is the full client-state (only :game-state
   is read, for the winner). Pure; returns a (possibly empty) vector of strings.

   Why this is a function and not two inline conds (#115): the reason token is
   emitted from TWO places — the fast :already-advanced path and the polling
   loop — and only the polling copy had grown guidance. A seat whose cursor had
   already advanced got the bare token for every reason but :my-turn-start, so
   :game-over / :game-gone / :encounter-decision arrived undecoded there. Same
   shape as send-continue!'s three copies (#75/#77) and the prompt :eid fix that
   landed on the wrong sender (#113): N emitters, one contract. Both call here.
   (The fast path cannot currently report :my-run-window itself — it passes
   initial-run-active? false, so a live run always resolves to :run-started
   first — but that is a property of the caller, not something to encode twice.)

   :my-run-window in particular shipped with NO decoding anywhere — not here, not
   in a single seat brief — and two Luna seats independently read
   `⚡ Woke up: my-run-window` + `(no new entries)` as nothing-happened and
   re-blocked while owing the pass that advances the run (#115)."
  [reason state]
  (case reason
    :my-turn-start
    ["   👉 Turn boundary: call `start-turn` before acting (0 clicks until you do)"]

    ;; The seat OWNS the un-passed pass at an active run window (my-run-window?
    ;; = run-window-owner names us at approach-ice / movement). The run cannot
    ;; advance until we send it, so another `wait` returns here unchanged — the
    ;; precise trap this guidance exists to break.
    :my-run-window
    ["   👉 The run is stopped on YOU: you owe the pass at this run window."
     "      Act — `prompt` shows the window; the verb is usually `continue`"
     "      (Corp at an ICE approach: `continue --rez <ice>` to rez first;"
     "       Corp at an ICE encounter: `fire-subs <ice>` to fire unbroken subs, then `continue`)."
     "      Another `wait` cannot advance it, and an empty game log here means the"
     "      opponent is already waiting on you."]

    ;; #120. The seat is the ACTIVE player with 0 clicks and no :end-turn — the
    ;; state where every OTHER surface used to say "waiting". Nothing is going to
    ;; arrive: the opponent is blocked behind this turn ending, so a seat that
    ;; re-blocks here re-creates the deadlock the wake exists to break.
    :my-turn-end
    (end-turn-obligation-lines state)

    ;; A run ENDING is normally self-describing, so this stayed silent. But
    ;; :run-ended is a TRANSITION and outranks :my-turn-end in the cond, so a run
    ;; that ends on the last click wakes here while the seat now owes the
    ;; end-turn — and got a bare token for it (guest panel, #120; the same
    ;; undecoded-reason trap as #115, where two seats read a token they couldn't
    ;; decode as nothing-happened and re-blocked). The obligation is a fact about
    ;; the CURRENT state, so ask about the current state rather than assuming the
    ;; transition is the whole story.
    :run-ended
    (if (state/my-turn-orphaned? state) (end-turn-obligation-lines state) [])

    :encounter-decision
    ;; NOT jack-out: it is movement-window only, so at an encounter it is both
    ;; illegal and a subroutine-skip. `jack-out` refuses here with the same steer.
    ["   👉 ICE encounter with unbroken subs: `continue` to see options, then break / tank"]

    :game-over
    (let [winner (get-in state [:game-state :winner])]
      [(format "   🏁 Game over%s — stop acting; call `game-over-status` for the result, then tear down."
               (if winner (str " — " (clojure.string/capitalize (name winner)) " wins") ""))])

    :game-gone
    ["   🏚️  The server closed this game's lobby — the game is GONE, not paused. Stop acting; `game-over-status` will confirm (GAME-GONE)."]

    []))

(defn wait-for-relevant-diff
  "Block until something we care about happens, then return.

   Wake conditions (see `relevance-reason` for the authoritative list):
     - a run starts (:run-started)
     - we get an actionable prompt — encounter, rez window, paid-ability
       window, access decision, choice prompt, etc. (:has-prompt)
     - a run that was in progress ends (:run-ended)
     - it becomes our turn at a boundary, start-turn needed (:my-turn-start)
     - it becomes our turn to act with clicks in hand (:my-turn)
     - our own turn is out of clicks but has not ended, so we owe the
       end-turn (:my-turn-end, #120)
     - we acquire priority at a run pass-window we own (:my-run-window)
     - the opponent sends a 'ping' chat message (:ping wake — escape hatch
       for when an external observer wants to nudge us)
     - the game ends (:game-over) or the server closes our lobby without a
       result (:game-gone, #93) — both mean stop acting and tear down
     - the timeout expires (:timeout)

   Opponent economy/draws/installs that don't produce a prompt for us are
   ignored — they update internal state but do NOT wake the wait.

   The :since option enables race-condition-free waiting: pass the cursor
   you captured BEFORE your last action. If the game has already advanced
   past that cursor, the wait returns immediately. Use this for every
   wait that follows one of your own actions, to avoid the 'waiting for
   the opponent who already acted' problem.

   Usage:
     (wait-for-relevant-diff)                  ;; default 300s timeout
     (wait-for-relevant-diff 60)               ;; custom timeout (seconds)
     (wait-for-relevant-diff {:timeout 120 :verbose true})
     (wait-for-relevant-diff {:since 847})     ;; cursor-based wait

   Returns a map with :status (:relevant-change | :ping | :already-advanced
   | :timeout | :game-started), :reason (keyword), :cursor (long),
   :run-active? (bool), :has-prompt? (bool), and :new-log-entries (vector of
   log entries since the wait began).

   Called in the lobby / pre-game (no active game yet) it polls for game-start
   and returns :status :game-started, or :status :timeout with :reason :no-game
   if the timeout expires still in the lobby (#46)."
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

     (cond
       ;; Lobby / pre-game guard (#46). `wait` is often the first thing a seat
       ;; calls after joining; if the game hasn't started there is no :side and
       ;; the turn predicates used to `(name nil)` NPE. Honour the "wake me when
       ;; something relevant happens" contract by polling for game-start (which
       ;; IS the relevant event here) and waking with :game-started; if the
       ;; timeout expires still in the lobby, return :timeout/:no-game cleanly.
       (not (in-active-game? @state/client-state))
       (let [deadline (+ (System/currentTimeMillis) (* timeout-seconds 1000))]
         (when (:verbose opts)
           (println (format "🕓 Not in an active game yet (lobby/pre-game) — waiting up to %ds for the game to start..."
                            timeout-seconds)))
         (loop []
           (cond
             (in-active-game? @state/client-state)
             (do
               (when (:verbose opts) (println "🎬 Game started."))
               {:status :game-started
                :reason :game-started
                :cursor (state/get-cursor)
                :new-log-entries []
                :run-active? (run-active? @state/client-state)
                :has-prompt? false})

             (> (System/currentTimeMillis) deadline)
             (do
               (when (:verbose opts) (println "⏱️  Timeout - still in the lobby / no game started"))
               {:status :timeout
                :reason :no-game
                :cursor (state/get-cursor)
                :new-log-entries []})

             :else
             (do (Thread/sleep polling-delay) (recur)))))

       :else
     ;; Fast path: if the cursor advanced past :since AND something we actually
     ;; care about already happened in the race window, return immediately. A
     ;; bare cursor advance with no relevant reason is NOT a wake — the cursor
     ;; bumps on every server diff, including our own action echoing back and
     ;; opponent economy ticks, so short-circuiting on the raw advance
     ;; false-woke every --since wait that followed one of our own actions
     ;; (reason :cursor-advanced, no new log entries). When nothing is relevant
     ;; we fall through and keep waiting for a real event.
     ;;
     ;; ONE snapshot, classified and described. This used to deref the atom
     ;; twice — once to decide the reason, once to render it — which is exactly
     ;; the staple-a-newer-board-onto-an-older-description race the snapshot
     ;; arities below (corp-servers / find-scorable-agendas /
     ;; find-eot-rezzable-cards) were added to prevent. The polling loop was
     ;; converted; this fast path was missed, so a diff landing between the two
     ;; derefs could classify :my-turn-end off the old state and then print
     ;; guidance from a board where the turn had already ended. (Review MAJOR.)
     (let [current-state @state/client-state
           since-reason (when (and since-cursor (> current-cursor since-cursor))
                          (relevance-reason current-state side false))]
     (if since-reason
       (do
         (when (:verbose opts)
           (println (format "⚡ Cursor advanced (%d → %d), %s — returning immediately"
                           since-cursor current-cursor (name since-reason)))
           (doseq [line (wake-reason-guidance-lines since-reason current-state)]
             (println line)))
         {:status :already-advanced
          :reason since-reason
          :cursor current-cursor
          :run-active? (run-active? current-state)
          :has-prompt? (has-prompt? current-state side)})

       ;; Normal path: wait for state change
       (let [deadline (+ (System/currentTimeMillis) (* timeout-seconds 1000))
             initial-state @state/client-state
             initial-run-active? (run-active? initial-state)
             initial-run-phase (run-phase initial-state)
             initial-log-count (count (get-in initial-state [:game-state :log]))]

         (when (:verbose opts)
           (println (format "💤 Waiting for relevant events (timeout: %ds, cursor: %d)..."
                           timeout-seconds current-cursor))
           (when initial-run-active?
             (println (format "   ⚡ Run is in progress (phase: %s) — will wake on prompt, phase-change, run-end, or my-turn"
                             (or initial-run-phase "unknown")))))

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
                 reason (relevance-reason current-state side initial-run-active? initial-run-phase)]

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
                     (doseq [line (wake-reason-guidance-lines reason current-state)]
                       (println line))
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
               ;; #87: name the reason when we were blocked on a KNOWN boundary.
               ;; A bare :timeout here is indistinguishable from a genuine stall,
               ;; which is the same "should I escalate?" ambiguity the false-wake
               ;; caused — so say what we were waiting for when we know.
               (let [mulligan? (opponent-mulligan-pending? current-state)]
                 (when (:verbose opts)
                   (if mulligan?
                     (println "⏱️  Timeout - opponent has not finished their opening mulligan (not a stall; wait again)")
                     (println "⏱️  Timeout - no relevant events"))
                   (when (seq entries-since-start)
                     (println "")
                     (println "📜 Game log while you were waiting:")
                     (doseq [entry (summarize-log-entries entries-since-start)]
                       (println (format "  • %s" (:text entry))))))
                 (cond-> {:status :timeout
                          :cursor (state/get-cursor)
                          :new-log-entries entries-since-start}
                   mulligan? (assoc :reason :opponent-mulligan)))

               ;; State changed but not relevant - keep waiting silently
               ;; (full log shown on wake, no need to spam ignored entries)
               (> current-log-count last-log-count)
               (recur current-log-count)

               ;; No change yet
               :else
               (recur last-log-count))))))))))))

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
        position (:position run)]
    ;; Early return if no run or no position (run ended mid-loop)
    (when (and run position (pos? position))
      (let [server (:server run)
            ice-list (get-in state [:game-state :corp :servers (keyword (last server)) :ices])
            ice-count (count ice-list)
            ice-index (dec position)]
        (when (<= position ice-count)
          (nth ice-list ice-index nil))))))

(defn encountered-ice
  "The ICE the Runner is actually encountering: the wire's own encounter summary
   [:encounters :ice] first, the position-derived `current-run-ice` only as a
   fallback.

   A FORCED encounter puts the Runner on ICE that :position does not point at,
   so position is not the authority — the rule the card resolvers had to learn
   twice (#100, #152) and the run handlers a third time (#160). The summary is a
   full card-summary (game.core.diffs/encounter-ice-summary), so it carries :cid,
   :rezzed and :subroutines just like the installed card."
  [state]
  (or (get-in state [:game-state :encounters :ice])
      (current-run-ice state)))

(defn encounter-ice-active?
  "True when `ice` is one whose subroutines this seat may act on — the client
   mirror of the engine's own `active-ice?` (game.core.ice):

       \"Ice is active when installed and rezzed or is the current encounter\"

   The second clause is not a nicety. A forced encounter's ICE is very often NOT
   installed and never rezzed: an on-access Archangel is encountered straight out
   of HQ, and the wire summary for it carries `:zone [:hand]` and no `:rezzed`
   key at all. Every encounter handler guarded on a bare `(:rezzed ice)`, so at
   the case #160 is about they all fell through even after their phase gates were
   widened — the fix would have been a no-op against the real payload. Found by
   game.ai-forced-encounter-wire-test driving the actual engine; the hand-written
   mocks all said `:rezzed true`, which reality does not provide.

   Keep the `:rezzed` clause for the position-derived fallback, where there is no
   encounter on the wire to vouch for the card."
  [state ice]
  (boolean (and ice (or (:rezzed ice) (live-encounter? state)))))

(defn encounter-key
  "Latch key for the encountered CARD — the encountered ICE's :cid, falling back
   to :position when the wire gives us no encounter. Used by the \"I already
   signalled here\" / \"I already fired here\" latches.

   Strictly better than the :position it replaced, which a forced encounter
   leaves pointing somewhere else entirely (usually 0), so two different forced
   encounters in one run shared a key and the second was treated as already
   handled.

   NOT a per-ENCOUNTER identity, and deliberately no longer named as one (guest
   panel, #160): a :cid is stable across two encounters of the SAME physical
   card, which Sisyphus Protocol produces inside a single run. The wire carries
   no encounter id to key on — game.core.diffs/encounter-keys is
   [:encounter-count :ice :no-action], and the engine's own encounter :eid is not
   serialized — so closing that gap needs a client-side encounter-transition
   observer. Tracked separately; see the follow-up issue linked from #160. The
   Corp's fire latch is partly covered already, because
   runner-signaled-let-fire? independently requires a signal NEWER than this
   ice's most recent encounter marker in the log."
  [state]
  (let [ice (encountered-ice state)]
    (or (:cid ice)
        (get-in state [:game-state :run :position]))))

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
