(ns ai-card-actions
  "Card manipulation - play, install, use abilities, rez, trash, advance, score"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-core :as core]
            [ai-basic-actions :as basic]
            [ai-prompts :as prompts]))

;; ============================================================================
;; Card Actions
;; ============================================================================

(defn- format-credit-line
  "Format the post-play credit-change line, or nil when credits didn't move.

   The delta is NET: it already has the card's play cost subtracted. A card that
   reads 'Gain 5' but costs 1 to play nets +4, which previously looked like an
   engine miscount to the seat (laundry-list #6). When the card had a play cost
   we now spell that out — 'after N to play' — so the gross card text and the net
   swing reconcile without mental math."
  [before after card-cost]
  (let [delta (- after before)]
    (when (not= delta 0)
      (str "   💰 Credits: " before " → " after
           " (" (if (pos? delta) "+" "") delta " net"
           (when (and card-cost (pos? card-cost))
             (str ", after " card-cost " to play"))
           ")"))))

(defn play-card!
  "Play a card from hand by name or index.
   Auto-starts turn if needed (opponent has ended and we haven't started yet).
   Usage: (play-card! \"Sure Gamble\")
          (play-card! 0)"
  [name-or-index]
  (if (nil? name-or-index)
    (do
      (println "❌ Cannot play card: invalid input (nil)")
      {:status :error :reason :invalid-input})
  ;; Check for pre-existing blocking prompt before attempting action
  (let [existing-prompt (state/get-prompt)]
    (if (and existing-prompt
             (not (state/waiting-prompt-type? (:prompt-type existing-prompt))))
      (do
        (println (str "❌ Cannot play card: Active prompt must be answered first"))
        (println (str "   Prompt: " (:msg existing-prompt)))
        (flush)
        {:status :error
         :reason "Active prompt must be answered first"
         :prompt existing-prompt})
      (if (basic/ensure-turn-started!)
        (let [card (core/find-card-in-hand name-or-index)]
          (if card
            (let [client-state @state/client-state
                  side (keyword (:side client-state))
                  credits (get-in client-state [:game-state side :credit])
                  card-cost (:cost card)
                  card-title (:title card)]
              ;; Pre-check: can we afford this card?
              (if (and card-cost (> card-cost credits))
                (do
                  (println (str "❌ Cannot play: " card-title))
                  (println (str "   Insufficient credits: need " card-cost ", have " credits))
                  {:status :error
                   :reason (str "Insufficient credits: need " card-cost ", have " credits)})
                ;; Can afford (or card is free), proceed
                (let [before-state (core/capture-state-snapshot)
                      before-credits credits
                      before-clicks (get-in client-state [:game-state side :click])
                      gameid (:gameid client-state)
                      card-ref (core/create-card-ref card)
                      card-zone (:zone card)
                      ;; Capture log size AND prompt BEFORE sending to avoid race
                      ;; conditions: a reply that beats verification would
                      ;; otherwise become its own baseline (#105).
                      initial-log-size (core/get-log-size)
                      pre-prompt (state/get-prompt)]
                  (ws/send-message! :game/action
                                    {:gameid gameid
                                     :command "play"
                                     :args {:card card-ref}})
              ;; Wait and verify action - now returns status map
              (let [result (core/verify-action-in-log card-title card-zone core/action-timeout
                                                      {:pre-log-size initial-log-size
                                                       :pre-prompt pre-prompt})]
                (case (:status result)
                  :success
                  (let [after-state @state/client-state
                        after-credits (get-in after-state [:game-state side :credit])
                        after-clicks (get-in after-state [:game-state side :click])
                        ;; Check if playing created a prompt
                        new-prompt (state/get-prompt)]
                    (println (str "🃏 Played: " card-title))
                    (when-let [credit-line (format-credit-line before-credits after-credits card-cost)]
                      (println credit-line))
                    (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
                    ;; Show prompt if card created one (e.g., Jailbreak asking for server)
                    (when (and new-prompt (not (state/waiting-prompt-type? (:prompt-type new-prompt))))
                      (println (str "   📋 " (:msg new-prompt)))
                      (when-let [choices (:choices new-prompt)]
                        (println (str "      Choices: " (clojure.string/join ", "
                                       (map-indexed #(str %1 "." (core/format-choice %2)) choices))))))
                    ;; Show turn indicator only if we won't auto-end (which shows its own)
                    (when (and (> after-clicks 0) (nil? new-prompt))
                      (core/show-turn-indicator))
                    (flush)
                    ;; Auto-end turn if no clicks remaining (will show its own indicator)
                    (basic/check-auto-end-turn!)
                    {:status :success
                     :data {:card-title card-title}})

                  :waiting-input
                  (let [prompt (:prompt result)]
                    (println (str "⏸️  Played: " card-title " - waiting for input"))
                    (println (str "   Prompt: " (:msg prompt)))
                    (core/show-turn-indicator)
                    (flush)
                    {:status :waiting-input
                     :card-title card-title
                     :prompt prompt})

                  :error
                  (do
                    (println (str "❌ Failed to play: " card-title))
                    (println (str "   Reason: " (:reason result)))
                    (core/show-turn-indicator)
                    (flush)
                    result)))))) ; close let, if (afford check)
            (do
              (println (str "❌ Card not found in hand: " name-or-index))
              (flush)
              {:status :error
               :reason (str "Card not found in hand: " name-or-index)})))
        (do
          (flush)
          {:status :error
           :reason "Failed to start turn"})))))) ; close defn

;; ============================================================================
;; Install Card Helpers (extracted to reduce nesting)
;; ============================================================================

(defn- validate-install-server
  "Validate server name for Corp installs. Returns error map or nil."
  [server client-state]
  (when (and server (core/side= "Corp" (:side client-state)))
    (core/validate-server-name server)))

(defn- validate-install-rules
  "Validate Corp install rules (baby-proofing). Returns error map or nil."
  [card normalized-server overwrite? client-state]
  (when (and (core/side= "Corp" (:side client-state))
             (not overwrite?))
    (core/validate-corp-install card normalized-server)))

(defn- handle-install-success!
  "Handle successful install: print feedback, auto-resolve prompts, check auto-end."
  [card-title card-type normalized-server before-clicks before-credits card-cost side overwrite?]
  (let [after-state @state/client-state
        after-clicks (get-in after-state [:game-state side :click])
        after-credits (get-in after-state [:game-state side :credit])]
    (if normalized-server
      (println (str "📥 Installed: " card-title " on " normalized-server))
      (println (str "📥 Installed: " card-title " (" card-type ")")))
    ;; Show credits spent if any
    (when (and card-cost (> card-cost 0))
      (core/show-before-after "💰 Credits" before-credits after-credits))
    (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
    ;; Auto-resolve any OK-only prompts (e.g., trash confirmation from overwrite)
    (when overwrite?
      (prompts/auto-resolve-ok-prompt!))
    ;; Show turn indicator only if we won't auto-end (which shows its own)
    (when (> after-clicks 0)
      (core/show-turn-indicator))
    (flush)
    ;; Auto-end turn if no clicks remaining (will show its own indicator)
    (basic/check-auto-end-turn!)
    {:status :success
     :data {:card-title card-title :server normalized-server}}))

(defn- handle-install-waiting!
  "Handle install waiting for input (e.g., server selection prompt)."
  [card-title prompt]
  (println (str "⏸️  Installed: " card-title " - waiting for server selection"))
  (println (str "   Prompt: " (:msg prompt)))
  (core/show-turn-indicator)
  (flush)
  {:status :waiting-input
   :card-title card-title
   :prompt prompt})

(defn- handle-install-error!
  "Handle failed install."
  [card-title result]
  (println (str "❌ Failed to install: " card-title))
  (println (str "   Reason: " (:reason result)))
  (core/show-turn-indicator)
  (flush)
  result)

(defn- execute-install!
  "Execute the install action and handle the result."
  [card normalized-server overwrite?]
  (let [client-state @state/client-state
        side (keyword (:side client-state))
        card-title (:title card)
        card-type (:type card)
        card-cost (or (:cost card) 0)
        before-clicks (get-in client-state [:game-state side :click])
        before-credits (get-in client-state [:game-state side :credit])
        gameid (:gameid client-state)
        card-ref (core/create-card-ref card)
        card-zone (:zone card)
        args (if normalized-server
               {:card card-ref :server normalized-server}
               {:card card-ref})
        ;; Capture log size AND prompt BEFORE sending to avoid race conditions.
        ;; Installing ICE/assets/upgrades without a server opens a location
        ;; prompt; if that reply beat verification, the prompt became its own
        ;; baseline and a working install false-failed as a timeout (#105).
        initial-log-size (core/get-log-size)
        pre-prompt (state/get-prompt)]
    (ws/send-message! :game/action
                      {:gameid gameid
                       :command "play"
                       :args args})
    ;; Wait and verify action
    (let [result (core/verify-action-in-log card-title card-zone core/action-timeout
                                            {:pre-log-size initial-log-size
                                             :pre-prompt pre-prompt})]
      (case (:status result)
        :success      (handle-install-success! card-title card-type normalized-server before-clicks before-credits card-cost side overwrite?)
        :waiting-input (handle-install-waiting! card-title (:prompt result))
        :error        (handle-install-error! card-title result)))))

(defn install-card!
  "Install a card from hand by name or index.
   Auto-starts turn if needed (opponent has ended and we haven't started yet).
   For Corp: must specify server location
   For Runner: server is optional (auto-installs to appropriate location)

   Server values:
   - Central servers: \"HQ\", \"R&D\", \"Archives\"
   - New remote: \"New remote\"
   - Existing remotes: \"Server 1\", \"Server 2\", etc.

   Options:
   - :overwrite true - Allow overwriting existing asset/agenda in remote

   Usage: (install-card! \"Palisade\" \"HQ\")         ; Corp ICE on HQ
          (install-card! \"Urtica Cipher\" \"New remote\") ; Corp asset in new remote
          (install-card! 0 \"R&D\")                   ; Corp install by index
          (install-card! \"Unity\")                   ; Runner install
          (install-card! \"Agenda\" \"Server 1\" :overwrite true) ; Overwrite existing"
  ([name-or-index]
   (install-card! name-or-index nil {}))
  ([name-or-index server]
   (install-card! name-or-index server {}))
  ([name-or-index server opts]
   (let [opts (if (keyword? opts) {opts true} opts)
         overwrite? (:overwrite opts)]
     ;; Check for blocking prompt
     (if-let [prompt-error (core/check-blocking-prompt "install card")]
       prompt-error
       ;; Ensure turn is started
       (if-not (basic/ensure-turn-started!)
         {:status :error :reason "Failed to start turn"}
         ;; Find the card
         (if-let [card (core/find-card-in-hand name-or-index)]
           (let [client-state @state/client-state
                 normalized-server (when server
                                     (:normalized (core/normalize-server-name server)))
                 server-error (validate-install-server server client-state)
                 install-error (when-not server-error
                                 (validate-install-rules card normalized-server overwrite? client-state))]
             ;; Check validations
             (cond
               server-error
               (do
                 (println (str "❌ Invalid server: " (:reason server-error)))
                 (when-let [hint (:hint server-error)]
                   (println (str "   💡 " hint)))
                 (flush)
                 {:status :error
                  :reason (:reason server-error)
                  :existing (:existing server-error)})

               install-error
               (do
                 (println (str "⚠️  Blocked install: " (:reason install-error)))
                 (when-let [hint (:hint install-error)]
                   (println (str "   💡 " hint)))
                 (println "   Use --overwrite flag to proceed anyway")
                 (flush)
                 {:status :blocked
                  :reason (:reason install-error)
                  :hint "Use --overwrite to install anyway (will trash existing card)"})

               :else
               (execute-install! card normalized-server overwrite?)))
           ;; Card not found
           (do
             (println (str "❌ Card not found in hand: " name-or-index))
             (flush)
             {:status :error
              :reason (str "Card not found in hand: " name-or-index)})))))))

;; ============================================================================
;; Card Abilities
;; ============================================================================

(declare use-runner-ability!)

(defn- corp-title-match-count
  "How many installed Corp cards share the parsed title. >1 means
   find-installed-corp-card just printed its disambiguation list and returned
   nil because the reference is AMBIGUOUS — not because the card is absent.
   Callers must not follow that list with a 'not found' lie (review catch on
   #95; misleading-output class)."
  [card-name]
  (let [{:keys [title]} (core/parse-card-reference card-name)
        servers (state/corp-servers)]
    (->> (concat (mapcat :ices (vals servers))
                 (mapcat :content (vals servers)))
         (filter #(= title (:title %)))
         count)))

(defn- ambiguous-or-missing-error
  "Honest error result for a nil corp-card lookup: ambiguity gets a
   disambiguate hint (the list is already printed), absence gets not-found."
  [card-name]
  (if (> (corp-title-match-count card-name) 1)
    (do
      (println (str "   Re-run with the [N] suffix, e.g. \"" card-name " [0]\""))
      (flush)
      {:status :error
       :reason (str "Ambiguous: multiple copies of " card-name " installed — specify [N]")})
    (do
      (println (str "❌ Card not found installed: " card-name))
      (flush)
      {:status :error :reason (str "Card not found: " card-name)})))

(defn- break-ability?
  "Does this ability's label read as a subroutine-break?

   Used ONLY to pick which hint to print after the engine has already ruled the
   ability unplayable — never to decide legality itself. The engine's own
   `:playable` flag is the authority (see unplayable-ability-error), so a false
   positive here costs a slightly-off suggestion, not a wrong verdict.

   `:dynamic :auto-pump-and-break` is the encounter-only compound ability the
   engine SYNTHESISES during an encounter; the plain 'Break N ... subroutine'
   label is the printed one, which is what a seat reaches for at approach."
  [ability]
  (boolean
    (or (= :auto-pump-and-break (:dynamic ability))
        (when-let [label (:label ability)]
          (re-find #"(?i)\bbreak\b.*\bsubroutine" label)))))

(defn- unplayable-ability-error
  "Explain an ability the engine has marked NOT playable, and DON'T send it.

   Why this gate exists (#116). `use-ability` used to send unconditionally and
   then infer failure from the absence of a log entry, reporting
   `Ability not confirmed in game log (timeout)`. That sentence describes a
   HARNESS fault — a lost message, a slow server, a desync — and invites a retry.
   The actual condition is usually a rules one, and retrying an illegal ability
   is the duplicate-send pattern that mints phantom prompts (#75/#77).

   The engine already tells us. `game.core.diffs/ability-playable?` runs
   can-pay? + can-trigger? (which for a break ability includes the encounter
   requirement) and assoc's `:playable true` only when the ability is legal
   RIGHT NOW; the summary reaches us over the wire on every card. board.cljs
   renders an ability without it as `[:li.disabled label]` — no click handler at
   all — so the human UI physically cannot send what we were sending. Mirroring
   that enable condition is the same lesson as [[board-cljs-is-the-wire-spec]]:
   the UI is the rules layer, and anything its buttons refuse to send, we must
   refuse too.

   Verified against the engine (game.ai-ability-legality-test): a Corroder at
   `approach-ice` reports NO :playable on 'Break 1 Barrier subroutine' while
   'Add 1 strength' keeps it. So this is per-ability, not per-phase — a blanket
   'nothing works at approach' would wrongly block the pump."
  [card-name ability-index ability]
  (let [run (get-in @state/client-state [:game-state :run])
        phase (:phase run)
        encountering? (= "encounter-ice" phase)
        label (:label ability)
        breaking? (and run (break-ability? ability) (not encountering?))
        ;; Only at APPROACH is the encounter still ahead of us. The other
        ;; non-encounter phases are real and reachable — the engine's run ladder
        ;; is approach-ice / encounter-ice / movement / pass-ice / success — and
        ;; at movement or success the ICE is already behind the Runner, so
        ;; "continue to enter the encounter" would push them FURTHER from the
        ;; recovery. Guest-panel MAJOR: the first cut printed it at every
        ;; non-encounter phase. Guidance text that asserts engine behaviour is
        ;; code, and this project has been bitten by exactly this before (#115).
        encounter-ahead? (and breaking? (= "approach-ice" phase))]
    (println (format "❌ %s ability %d%s is not usable right now — NOT sent."
                     card-name ability-index
                     (if label (str " (\"" label "\")") "")))
    (println "   The game reports this ability as unavailable in the current state;")
    (println "   this is a rules/timing condition, not a lost message — retrying as-is will fail again.")
    (cond
      encounter-ahead?
      (do
        (println (format "   You are at run phase '%s' — you have not reached the ICE yet." phase))
        (println "   → Subroutines can only be broken during the ENCOUNTER.")
        (println "     Use 'continue' to enter the encounter, then retry this ability."))

      breaking?
      (do
        (println (format "   You are at run phase '%s', not encountering an ICE." phase))
        (println "   → Subroutines can only be broken during an ENCOUNTER.")
        (println (if (= "success" phase)
                   "     This run has passed the ICE — there is nothing left to break."
                   "     'board' / 'status' to see where in the run you are.")))

      :else
      (do
        (println "   Common causes: not enough credits, once-per-turn already used,")
        (println "   a condition on the card that isn't met, or the wrong game phase.")
        (when run
          (println (format "   (You are in a run, phase '%s'.)" phase)))
        ;; Real command spellings — `send_command` has card-text and abilities;
        ;; there is no `show-card`. (Guest-panel MINOR, and the same class as the
        ;; line above: an invented command name reads as authoritative.)
        (println (format "   → 'abilities \"%s\"' for the indexed menu, 'card-text \"%s\"' for the card,"
                         card-name card-name))
        (println "     or 'list-playables' for what IS usable right now.")))
    (flush)
    {:status :error
     :reason (if breaking?
               (format "Ability not legal at run phase %s (subroutines break during the encounter)" phase)
               "Ability not currently playable (engine reports it unavailable)")
     :card-name card-name
     :unplayable true}))

(def ^:private playable-settle-ms
  "How long to let an in-flight diff land before trusting a NEGATIVE :playable.

   Our flag is a CACHED negative — whatever the last applied diff said. board.cljs
   disables its button off the same cached field, so the gate is no staler than
   the human UI; but a human re-reads a greyed button while a seat treats a hard
   error as final. The dangerous window is `continue` immediately followed by
   `use-ability`: if the encounter diff hasn't applied, the pre-send gate would
   refuse an action that is about to be legal (guest-panel CRITICAL).

   Polling costs nothing in the case the gate exists for — a genuinely illegal
   ability never gains the flag, so we always pay the full wait and then refuse.
   It is still an order of magnitude under the 3s log timeout it replaces."
  600)

(defn- settle-ability
  "Re-read the ability at INDEX, giving an in-flight diff up to
   `playable-settle-ms` to make it playable. Returns [abilities ability] as of
   the freshest read. `get-abilities` re-looks-up the card each poll, so a diff
   that replaces the whole card map is picked up too.

   Returns immediately once the ability is playable; only a refusal pays the wait."
  [get-abilities ability-index]
  (let [deadline (+ (System/currentTimeMillis) playable-settle-ms)]
    (loop []
      (let [abilities (get-abilities)
            ability (when (and abilities (< ability-index (count abilities)))
                      (nth abilities ability-index))]
        (if (or (:playable ability)
                (>= (System/currentTimeMillis) deadline))
          [abilities ability]
          (do (Thread/sleep core/polling-delay)
              (recur)))))))

(defn- ability-blocked
  "Return an error map when the request can be refused BEFORE sending, else nil.

   `unplayable-ability-error` is shared with `use-runner-ability!`, which does
   the same two checks in its own `cond`. Both senders must agree: this codebase
   keeps paying for per-sender copies of a shared rule (#75/#77 three
   send-continue! copies, #113 the sender the CLI never calls, #115 the inlined
   phase set).

   Two refusable conditions, both knowable from state we already hold:
   - the index addresses no ability (the seat guessed, or is reaching for the
     encounter-only dynamic 'Fully break X' that the engine has not synthesised
     yet — it appears in the list ONLY during an encounter);
   - the ability exists but the engine has not marked it :playable.

   Both are re-checked against settled state first (see `settle-ability`) so an
   unapplied diff can't produce a false refusal. With no abilities vector at all
   we know nothing, so we send and let the existing verification path answer."
  [card-name ability-index get-abilities]
  (let [[abilities ability] (settle-ability get-abilities ability-index)]
   (cond
    (and (seq abilities) (>= ability-index (count abilities)))
    (do
      (println (format "❌ %s has no ability %d — it has %d (0-%d). NOT sent."
                       card-name ability-index (count abilities) (dec (count abilities))))
      (doseq [[i a] (map-indexed vector abilities)]
        (println (format "     %d. %s%s" i (or (:label a) "?")
                         (if (:playable a) "" "   [not usable right now]"))))
      (println "   Note: a breaker's 'Fully break <ICE>' ability only exists DURING an encounter.")
      (flush)
      {:status :error
       :reason (format "No ability %d on %s (has %d)" ability-index card-name (count abilities))
       :card-name card-name})

    (and ability (not (:playable ability)))
    (unplayable-ability-error card-name ability-index ability))))

(defn- rules-explanation-after-timeout
  "When verification timed out, look at state as it stands NOW and replace the
   timeout wording if the current state explains the failure as a rules one.

   The pre-send gate can't catch everything (#116, guest-panel MAJOR): the
   reverse of the stale-negative race is a stale POSITIVE. If a `continue` moved
   the server out of the encounter but that diff hadn't applied, the ability
   still read :playable, we sent it, the engine refused — and the diff that
   arrives moments later plainly shows why. The issue asked for exactly this:
   'before falling through to the timeout path, check whether the ability is
   legal in the current run phase.' Returns nil when the state offers no
   explanation, so a genuine harness fault still reports as one."
  [card-name ability-index get-abilities]
  (let [abilities (get-abilities)
        ability (when (and abilities (< ability-index (count abilities)))
                  (nth abilities ability-index))]
    (when (and abilities (not (:playable ability)))
      (unplayable-ability-error card-name ability-index ability))))

(defn use-ability!
  "Use an installed card's ability. Returns status map:
   - {:status :success} - ability fired
   - {:status :waiting-input :prompt ...} - created a prompt (e.g., choose target)
   - {:status :error :reason ...} - failed

   Usage: (use-ability! \"Smartware Distributor\" 0)
          (use-ability! \"Sure Gamble\" 1)"
  [card-name ability-index]
  (let [client-state @state/client-state
        side (:side client-state)
        ;; Find card in appropriate location based on side
        card (if (core/side= "Corp" side)
               (core/find-installed-corp-card card-name)
               (core/find-installed-card card-name))]
    (if card
      (let [gameid (:gameid client-state)
            card-ref (core/create-card-ref card)
            ;; Re-looks-up the card each call, so the legality gate and the
            ;; post-timeout explanation both read LIVE state rather than the
            ;; snapshot this fn opened with (#116, guest-panel CRITICAL).
            get-abilities (fn []
                            (:abilities
                              (if (core/side= "Corp" side)
                                (core/find-installed-corp-card card-name)
                                (core/find-installed-card card-name))))
            ;; Check if this ability is dynamic (e.g., auto-pump, auto-pump-and-break)
            abilities (:abilities card)
            ability (when (and abilities (< ability-index (count abilities)))
                     (nth abilities ability-index))
            ability-label (when ability (:label ability))
            dynamic-type (:dynamic ability)
            ;; Capture state BEFORE sending to avoid race condition where
            ;; response arrives before we start polling (fixes false timeouts)
            pre-log-size (core/get-log-size)
            pre-prompt (state/get-prompt)]
        (if-let [blocked (ability-blocked card-name ability-index get-abilities)]
          blocked
          (do
        ;; Send the ability command
        (if dynamic-type
          ;; Use dynamic-ability command for abilities with :dynamic field
          (ws/send-message! :game/action
                            {:gameid gameid
                             :command "dynamic-ability"
                             :args {:card card-ref
                                    :dynamic dynamic-type}})
          ;; Use regular ability command for normal abilities
          (ws/send-message! :game/action
                            {:gameid gameid
                             :command "ability"
                             :args {:card card-ref
                                    :ability ability-index}}))
        ;; Verify the ability fired by checking game log
        (let [result (core/verify-ability-in-log card-name core/action-timeout
                                                  {:pre-log-size pre-log-size
                                                   :pre-prompt pre-prompt})]
          (case (:status result)
            :success
            (do
              (if ability-label
                (println (str "⚡ Used ability: " card-name " - " ability-label))
                (println (str "⚡ Used ability #" ability-index " on " card-name)))
              ;; Auto-end turn if this was a click ability and no clicks remaining
              ;; Skip during runs (breaker abilities) and for non-click abilities
              (let [cost-label (str (:cost-label ability ""))]
                (when (and (clojure.string/includes? cost-label "[Click]")
                           (not (some? (get-in @state/client-state [:game-state :run]))))
                  (basic/check-auto-end-turn!))))

            :waiting-input
            (println (str "⏳ Ability triggered prompt: " card-name " - "
                          (or ability-label (str "#" ability-index))))

            ;; #116: prefer a rules explanation from CURRENT state over the
            ;; timeout wording, which describes only how we noticed.
            :error
            nil)
          (if (= :error (:status result))
            (or (rules-explanation-after-timeout card-name ability-index get-abilities)
                (do (println (str "❌ Ability failed: " card-name " - " (:reason result)))
                    result))
            result)))))
      ;; Own-side lookup missed. From the Runner seat the card may be a Corp
      ;; card whose printed ability is Runner-usable (bioroid click-to-break,
      ;; issue #95) — route to the runner-ability command instead of
      ;; dead-ending; a Corp card's :abilities aren't the Runner's to use.
      (if-let [corp-card (and (not (core/side= "Corp" side))
                              (core/find-installed-corp-card card-name))]
        (if (seq (:runner-abilities corp-card))
          (do
            (println (str "ℹ️  " card-name " is a Corp card — using its Runner-usable ability"))
            (use-runner-ability! card-name ability-index))
          (do
            (println (str "❌ " card-name " is a Corp card with no Runner-usable abilities"))
            (flush)
            {:status :error :reason (str "No runner-abilities on Corp card: " card-name)}))
        (ambiguous-or-missing-error card-name)))))

(defn use-runner-ability!
  "Use a Runner-usable ability printed on a Corp card (e.g. bioroid
   click-to-break during an encounter). The index addresses the card's
   :runner-abilities vector, NOT its :abilities vector.

   Returns a status map like use-ability!:
   - {:status :success} - ability fired (verified in game log)
   - {:status :waiting-input :prompt ...} - created a prompt
   - {:status :error :reason ...} - failed

   Usage: (use-runner-ability! \"Brân 1.0\" 0)"
  [card-name ability-index]
  (let [client-state @state/client-state
        card (core/find-installed-corp-card card-name)
        runner-abilities (:runner-abilities card)
        ability (when card (nth runner-abilities ability-index nil))
        ;; Live re-read, same as use-ability! — see settle-ability.
        get-abilities (fn [] (:runner-abilities (core/find-installed-corp-card card-name)))]
    (cond
      (not card)
      (ambiguous-or-missing-error card-name)

      (not ability)
      (do
        (if (seq runner-abilities)
          (println (format "❌ %s has no runner-ability %d (it has %d: 0-%d)"
                           card-name ability-index
                           (count runner-abilities) (dec (count runner-abilities))))
          (println (str "❌ " card-name " has no Runner-usable abilities"
                        " (only bioroids and similar cards do)")))
        (flush)
        {:status :error :reason (str "No runner-ability " ability-index " on " card-name)})

      ;; #116: same legality gate as use-ability!. A bioroid's click-to-break is
      ;; exactly as encounter-bound as a breaker's, and this sender had the same
      ;; send-then-blame-the-log shape. Goes through the SHARED helper (which
      ;; settles in-flight diffs first) rather than testing :playable inline, so
      ;; the two senders can't drift — the N-senders tax this repo keeps paying.
      :else
      (if-let [blocked (ability-blocked card-name ability-index get-abilities)]
        blocked
      (let [gameid (:gameid client-state)
            card-ref (core/create-card-ref card)
            ;; Capture state BEFORE sending (same race guard as use-ability!)
            pre-log-size (core/get-log-size)
            pre-prompt (state/get-prompt)]
        (ws/send-message! :game/action
                          {:gameid gameid
                           :command "runner-ability"
                           :args {:card card-ref
                                  :ability ability-index}})
        (let [result (core/verify-ability-in-log card-name core/action-timeout
                                                 {:pre-log-size pre-log-size
                                                  :pre-prompt pre-prompt})]
          (case (:status result)
            :success
            (println (str "⚡ Used runner ability: " card-name " - " (:label ability)))

            :waiting-input
            (println (str "⏳ Runner ability triggered prompt: " card-name " - " (:label ability)))

            ;; #116: same post-timeout re-diagnosis as use-ability!.
            :error
            nil)
          (flush)
          (if (= :error (:status result))
            (or (rules-explanation-after-timeout card-name ability-index get-abilities)
                (do (println (str "❌ Runner ability failed: " card-name " - " (:reason result)))
                    result))
            result)))))))

(defn trash-installed!
  "Trash an installed card (Corp: ICE/asset/upgrade, Runner: rig card)

   Usage: (trash-installed! \"Palisade\")
          (trash-installed! \"Daily Casts\")"
  [card-name]
  (let [client-state @state/client-state
        side (:side client-state)
        ;; Find card in appropriate location based on side. Use core/side= —
        ;; client-state stores :side lowercase ("corp"), so a strict
        ;; (= "Corp" side) is always false and would search the Runner rig,
        ;; missing every Corp card to trash. (issue #69, same defect)
        card (if (core/side= "Corp" side)
               (core/find-installed-corp-card card-name)
               (core/find-installed-card card-name))]
    (if card
      (let [gameid (:gameid client-state)
            card-ref (core/create-card-ref card)
            card-type (:type card)
            card-zone (:zone card)]
        (ws/send-message! :game/action
                          {:gameid gameid
                           :command "trash"
                           :args {:card card-ref}})
        (Thread/sleep core/medium-delay)
        (println (str "🗑️  Trashed: " card-name " (" card-type ")")))
      (println (str "❌ Card not found installed: " card-name)))))

(defn rez-card!
  "Rez an installed Corp card (ICE, asset, or upgrade)

   Supports [N] suffix for multiple copies: \"Palisade [1]\"

   Phase validation: ICE can only be rezzed during approach-ice phase.

   Usage: (rez-card! \"Prisec\")
          (rez-card! \"Palisade [1]\")"
  [card-name]
  (let [client-state @state/client-state
        side (:side client-state)
        before-credits (get-in client-state [:game-state :corp :credit])]
    (if (not (core/side= "Corp" side))
      (println "❌ Only Corp can rez cards")
      (let [card (core/find-installed-corp-card card-name)]
        (if card
          (let [gameid (:gameid client-state)
                card-ref (core/create-card-ref card)
                rez-cost (:cost card)
                card-type (:type card)
                ;; Phase validation for ICE rez
                run (get-in client-state [:game-state :run])
                run-phase (when run
                            (or (:phase run)
                                (some-> run :run-phase name)))
                ;; Bug #1 (Run #4): ICE may only be rezzed during approach-ice.
                ;; Rezzing ICE outside a run strictly wastes credits and leaks
                ;; information for nothing. The prior `(nil? run)` allow-clause
                ;; was annotated "shouldn't happen but allow" — Run #4 proved
                ;; it does happen and needs blocking.
                is-ice? (= card-type "ICE")
                valid-ice-rez? (or (not is-ice?)
                                   (= run-phase "approach-ice"))]
            (if (not valid-ice-rez?)
              (do
                (if (nil? run)
                  (do
                    (println "❌ Cannot rez ICE: no active run")
                    (println "   → ICE may only be rezzed during approach-ice phase"))
                  (do
                    (println (format "❌ Cannot rez ICE during %s phase" run-phase))
                    (println "   → ICE can only be rezzed during approach-ice phase")))
                nil)
              ;; Ground truth for a rez is the card's own :rezzed flag flipping
              ;; in client state — NOT the log. verify-action-in-log's name
              ;; check scans the last 5 log lines, which routinely already
              ;; mention the card (a derez, a rez-decision hint, embedded
              ;; effect text), and its result map is always truthy besides —
              ;; so a REFUSED rez (e.g. can't afford it mid-run) printed
              ;; "🔴 Rezzed" instantly (#86). False success on a critical,
              ;; time-sensitive action is exactly the shape that tempts the
              ;; banned re-send.
              (let [cid (:cid card)
                    deadline (+ (System/currentTimeMillis) core/action-timeout)]
                (ws/send-message! :game/action
                                  {:gameid gameid
                                   :command "rez"
                                   :args {:card card-ref}})
                (loop []
                  (cond
                    (:rezzed (core/find-card-by-cid cid))
                    (let [after-credits (get-in @state/client-state [:game-state :corp :credit])]
                      (println (str "🔴 Rezzed: " card-name))
                      (when rez-cost
                        (println (str "   💰 Cost: " rez-cost "₵ (remaining: " after-credits "₵)")))
                      {:status :success :card-name card-name})

                    (< (System/currentTimeMillis) deadline)
                    (do (Thread/sleep core/polling-delay) (recur))

                    :else
                    ;; Timeout only proves the client did not SEE the rez land
                    ;; — don't assert why (codex review: an affordability claim
                    ;; here would itself be misleading output). State what is
                    ;; known, give the context a seat needs to diagnose, and
                    ;; warn off the blind re-send.
                    (let [credits (get-in @state/client-state [:game-state :corp :credit])]
                      (println (str "⚠️  Rez NOT confirmed: " card-name " still shows unrezzed."))
                      (println (format "   Either the server refused it (can you afford it? base cost %s, you have %s — mid-run surcharges like Tread Lightly's +3 raise the real cost) or the update is delayed."
                                       (or rez-cost "?") (or credits "?")))
                      (println "   Check `board` and the log before deciding — do not re-send blindly.")
                      {:status :error :reason :rez-not-confirmed :card-name card-name}))))))
          (println (str "❌ Card not found installed: " card-name)))))))

(defn let-subs-fire!
  "Signal intent to let unbroken subroutines fire (Runner only)
   Sends a system message to indicate Runner is allowing subs to fire

   Usage: (let-subs-fire! \"Whitespace\")
          (let-subs-fire! \"IP Block\")"
  [ice-name]
  (let [client-state @state/client-state
        side (:side client-state)
        gameid (:gameid client-state)]
    (if (not (core/side= "Runner" side))
      (println "❌ Only Runner can let subroutines fire")
      (do
        (ws/send-message! :game/action
                          {:gameid gameid
                           :command "system-msg"
                           :args {:msg (str "indicates to fire all unbroken subroutines on " ice-name)}})
        (Thread/sleep core/short-delay)))))

(defn toggle-auto-no-action!
  "Toggle auto-pass priority during runs (Corp only)
   When enabled, automatically passes on all rez/paid ability windows
   Prompt changes to 'Stop Auto-passing Priority' when active

   Usage: (toggle-auto-no-action!)"
  []
  (let [client-state @state/client-state
        side (:side client-state)
        gameid (:gameid client-state)]
    (if (not (core/side= "Corp" side))
      (println "❌ Only Corp can toggle auto-pass priority")
      (do
        (ws/send-message! :game/action
                          {:gameid gameid
                           :command "toggle-auto-no-action"
                           :args nil})
        (Thread/sleep core/quick-delay)))))

(defn fire-subs-report
  "Pure: decide what to print and return after firing unbroken subroutines.

   The subtle case this exists for: a fired subroutine can OPEN A PROMPT (e.g.
   Brân 1.0's \"install an ice from HQ/Archives\" sub), which pauses sub
   resolution until the Corp resolves it. In that window there are often no new
   *log* entries yet, so the naive \"no new log entries\" branch wrongly reports
   \"subs already broken or run already ended\" — leaving the Corp sitting on an
   unhandled prompt while believing the fire was a no-op (a flow stall on the
   blessed Corp defense path). Detecting the newly-opened prompt and surfacing it
   is the honest signal.

   Inputs:
     ice-title    - ICE name (string)
     old-cursor   - cursor before firing (int)
     new-cursor   - cursor after firing (int)
     new-entries  - already-sliced new log entries (seq of {:text ...})
     new-prompt   - a genuinely NEW prompt our side must resolve, or nil
                    (caller computes via core/new-prompt? to dodge stale state)
   Returns {:lines [str...] :result <status-map>}."
  [ice-title old-cursor new-cursor new-entries new-prompt]
  (let [header (format "✅ Fire request acknowledged (cursor %d → %d)" old-cursor new-cursor)
        entry-lines (map #(str "  • " (:text %)) new-entries)]
    (cond
      ;; A subroutine opened a prompt; the rest can't fire until it's resolved.
      (some? new-prompt)
      {:lines (concat [header]
                      entry-lines
                      [(str "⏸️  A subroutine needs input before the rest can fire: "
                            (:msg new-prompt))
                       "   Resolve it (choose-value \"<label>\" / choose-card <N>), then firing continues."])
       :result {:status :waiting-input :ice ice-title :prompt new-prompt}}

      (seq new-entries)
      {:lines (cons header entry-lines)
       :result {:status :success :ice ice-title}}

      :else
      {:lines [header
               "  (no new log entries — subs were already broken, or the run had already ended)"]
       :result {:status :success :ice ice-title}})))

(defn fire-unbroken-subs!
  "Fire unbroken subroutines on ICE (Corp only)
   Used during runs when Runner doesn't/can't break all subs

   Usage: (fire-unbroken-subs! \"Palisade\")
          (fire-unbroken-subs! \"IP Block\")"
  [ice-name]
  (let [client-state @state/client-state
        side (:side client-state)]
    (if (not (core/side= "Corp" side))
      (do (println "❌ Only Corp can fire ICE subroutines")
          (core/with-cursor {:status :error :reason "Wrong side"}))
      (let [card (core/find-installed-corp-card ice-name)]
        (if-not card
          (do (println (str "❌ ICE not found installed: " ice-name))
              (core/with-cursor {:status :error :reason "ICE not found"}))
          (let [gameid (:gameid client-state)
                card-ref {:cid (:cid card)
                         :zone (:zone card)
                         :side (:side card)
                         :type (:type card)}
                old-cursor (state/get-cursor)
                old-prompt (state/get-prompt)
                old-log-count (count (get-in client-state [:game-state :log]))]
            (println (str "⚡ Firing unbroken subroutines on " (:title card) "..."))
            (ws/send-message! :game/action
                              {:gameid gameid
                               :command "unbroken-subroutines"
                               :args {:card card-ref}})
            (Thread/sleep core/medium-delay)
            (let [new-state @state/client-state
                  new-cursor (state/get-cursor)
                  new-log (get-in new-state [:game-state :log])
                  new-entries (drop old-log-count new-log)
                  cur-prompt (state/get-prompt)
                  ;; eid-aware so a stale leftover prompt isn't mistaken for a
                  ;; sub-opened one (see core/new-prompt? rationale).
                  new-prompt (when (core/new-prompt? old-prompt cur-prompt) cur-prompt)
                  {:keys [lines result]} (fire-subs-report
                                          (:title card) old-cursor new-cursor
                                          new-entries new-prompt)]
              (doseq [l lines] (println l))
              (core/with-cursor result))))))))

(defn advance-card!
  "Advance an installed Corp card (agenda, ICE, or asset).
   Auto-starts turn if needed (opponent has ended and we haven't started yet).
   Costs 1 click and 1 credit per advancement counter.

   By default, blocks advancing past the requirement (overadvancement).
   Use {:overadvance true} to allow advancing past requirement.

   Usage: (advance-card! \"Project Vitruvius\")
          (advance-card! \"Oaktown Renovation\")
          (advance-card! \"Send a Message\" {:overadvance true})"
  ([card-name] (advance-card! card-name {}))
  ([card-name opts]
  (when (basic/ensure-turn-started!)
    (let [client-state @state/client-state
          side (:side client-state)
          overadvance? (:overadvance opts)]
      (if (not (core/side= "Corp" side))
        (println "❌ Only Corp can advance cards")
        (let [card (core/find-installed-corp-card card-name)
              before-counters (or (:advance-counter card) 0)
              before-credits (get-in client-state [:game-state :corp :credit])
              before-clicks (get-in client-state [:game-state :corp :click])
              advancement-requirement (:advancementcost card)
              ;; Check for overadvancement (already at or past requirement)
              would-overadvance? (and advancement-requirement
                                      (>= before-counters advancement-requirement))]
          (cond
            ;; Card not found
            (not card)
            (println (str "❌ Card not found installed: " card-name))

            ;; Would overadvance but flag not set
            (and would-overadvance? (not overadvance?))
            (do
              (println (str "⚠️  Blocked: " card-name " already at " before-counters
                           "/" advancement-requirement " counters (fully advanced)"))
              (println "    Use --overadvance to advance past requirement")
              (flush)
              {:status :blocked :reason :overadvance})

            ;; Proceed with advance
            :else
            (let [gameid (:gameid client-state)
                  card-ref {:cid (:cid card)
                           :zone (:zone card)
                           :side (:side card)
                           :type (:type card)}
                  ;; Capture log size AND prompt BEFORE sending to avoid race conditions (#105)
                  initial-log-size (core/get-log-size)
                  pre-prompt (state/get-prompt)]
              (ws/send-message! :game/action
                                {:gameid gameid
                                 :command "advance"
                                 :args {:card card-ref}})
              ;; Wait and verify action appeared in log
              ;; Note: Card doesn't change zones, so we pass its current zone
              (let [result (core/verify-action-in-log card-name (:zone card) 3000
                                                      {:pre-log-size initial-log-size
                                                       :pre-prompt pre-prompt})]
                (if (= :success (:status result))
                  (let [after-state @state/client-state
                        updated-card (core/find-installed-corp-card card-name)
                        after-counters (or (:advance-counter updated-card) 0)
                        after-credits (get-in after-state [:game-state :corp :credit])
                        after-clicks (get-in after-state [:game-state :corp :click])
                        ;; Check if agenda is now scorable
                        is-agenda (= "Agenda" (:type card))
                        is-scorable (and is-agenda
                                        advancement-requirement
                                        (>= after-counters advancement-requirement))]
                    (println (str "⏫ Advanced: " card-name " (" after-counters
                                 (when advancement-requirement (str "/" advancement-requirement))
                                 " counters)"))
                    ;; Show scorable indicator if applicable
                    (when is-scorable
                      (println (str "   🎯 " card-name " is now SCORABLE!"))
                      (println (format "   💡 Use: score \"%s\"" card-name)))
                    (core/show-before-after "💰 Credits" before-credits after-credits)
                    (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
                    (flush)
                    ;; Auto-end turn if no clicks remaining
                    ;; (will be blocked if agenda is scorable)
                    (basic/check-auto-end-turn!))
                  (do
                    (println (str "⚠️  Sent advance command for: " card-name " - but action not confirmed in game log (may have failed)"))
                    (flush))))))))))))

(defn advance-card-times!
  "Advance `card-name` up to `n` times, stopping early on failure or when the
   clicks run out.

   Advancing is the burstiest command in the log: 74 back-to-back repeats at a
   1s median, and `advance` is followed by `score` P=0.33 (n=45) — the whole
   sequence is one intent — get this to scorable — typed one click at a time.
   Overadvance protection still applies per step via advance-card!, so a count
   cannot push a card past its requirement unless {:overadvance true} is set."
  ([card-name n] (advance-card-times! card-name n {}))
  ([card-name n opts]
   (basic/repeat-action! n #(advance-card! card-name opts) "advancements")))

(defn score-agenda!
  "Score an installed agenda (Corp only)
   Agenda must have enough advancement counters to be scored

   Usage: (score-agenda! \"Project Vitruvius\")
          (score-agenda! \"Send a Message\")"
  [card-name]
  (let [client-state @state/client-state
        side (:side client-state)
        before-score (or (get-in client-state [:game-state :corp :agenda-point]) 0)]
    (if (not (core/side= "Corp" side))
      (println "❌ Only Corp can score agendas")
      (let [card (core/find-installed-corp-card card-name)]
        (if card
          (if (= "Agenda" (:type card))
            (let [requirement (:advancementcost card)
                  counters (or (:advance-counter card) 0)]
              ;; Pre-check: an agenda with fewer advancement counters than its
              ;; requirement is not scoreable — the engine will refuse. Catch it
              ;; here so we never send a doomed score command (and never print a
              ;; phantom "Scored"). Marquee game-2 surfaced score printing
              ;; "🎯 Scored (+N points)" on an under-advanced Superconducting Hub
              ;; that did NOT actually score.
              (if (and requirement (< counters requirement))
                (println (str "❌ " card-name " is not scoreable yet: " counters "/" requirement
                              " advancement counters (needs " (- requirement counters) " more)."))
                (let [gameid (:gameid client-state)
                      card-ref (core/create-card-ref card)
                      agenda-points (:agendapoints card)
                      ;; Capture log size AND prompt BEFORE sending to avoid race conditions (#105)
                      initial-log-size (core/get-log-size)
                      pre-prompt (state/get-prompt)]
                  (ws/send-message! :game/action
                                    {:gameid gameid
                                     :command "score"
                                     :args {:card card-ref}})
                  ;; Let the action settle, then VERIFY BY SCORE DELTA — not just
                  ;; by the card name appearing in the log (which false-positives
                  ;; on prior scores / "cannot score" messages). Only a real
                  ;; increase in Corp agenda points means the agenda scored.
                  (core/verify-action-in-log card-name (:zone card) core/action-timeout
                                             {:pre-log-size initial-log-size
                                              :pre-prompt pre-prompt})
                  (let [after-state @state/client-state
                        after-score (or (get-in after-state [:game-state :corp :agenda-point]) 0)
                        runner-score (or (get-in after-state [:game-state :runner :agenda-point]) 0)]
                    (if (> after-score before-score)
                      (do
                        (println (str "🎯 Scored: " card-name
                                     (when agenda-points (str " (+" agenda-points " points)"))))
                        (println (str "   📊 Score: Corp " after-score " - " runner-score " Runner")))
                      (println (str "⚠️  " card-name " did NOT score (Corp agenda points unchanged at "
                                   before-score "). The engine refused it — check advancement counters/timing.")))))))
            (println (str "❌ Card is not an Agenda: " (:title card) " (type: " (:type card) ")")))
          (println (str "❌ Card not found installed: " card-name)))))))
