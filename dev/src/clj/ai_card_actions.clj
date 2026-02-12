(ns ai-card-actions
  "Card manipulation - play, install, use abilities, rez, trash, advance, score"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-core :as core]
            [ai-basic-actions :as basic]
            [ai-prompts :as prompts]
            [clojure.string :as str]))

;; ============================================================================
;; Card Actions
;; ============================================================================

(defn play-card!
  "Play a card from hand by name or index.
   Auto-starts turn if needed (opponent has ended and we haven't started yet).
   Usage: (play-card! \"Sure Gamble\")
          (play-card! 0)"
  [name-or-index]
  ;; Check for pre-existing blocking prompt before attempting action
  (let [existing-prompt (state/get-prompt)]
    (if (and existing-prompt
             (not= :waiting (:prompt-type existing-prompt)))
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
                      ;; Capture log size BEFORE sending to avoid race conditions
                      initial-log-size (core/get-log-size)]
                  (ws/send-message! :game/action
                                    {:gameid gameid
                                     :command "play"
                                     :args {:card card-ref}})
              ;; Wait and verify action - now returns status map
              (let [result (core/verify-action-in-log card-title card-zone core/action-timeout initial-log-size)]
                (case (:status result)
                  :success
                  (let [after-state @state/client-state
                        after-credits (get-in after-state [:game-state side :credit])
                        after-clicks (get-in after-state [:game-state side :click])
                        credit-delta (- after-credits before-credits)
                        ;; Check if playing created a prompt
                        new-prompt (state/get-prompt)]
                    (println (str "🃏 Played: " card-title))
                    (when (not= credit-delta 0)
                      (println (str "   💰 Credits: " before-credits " → " after-credits
                                   " (" (if (pos? credit-delta) "+" "") credit-delta ")")))
                    (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
                    ;; Show prompt if card created one (e.g., Jailbreak asking for server)
                    (when (and new-prompt (not= :waiting (:prompt-type new-prompt)))
                      (println (str "   📋 " (:msg new-prompt)))
                      (when-let [choices (:choices new-prompt)]
                        (println (str "      Choices: " (str/join ", "
                                       (map-indexed (fn [idx choice]
                                                     (str idx "." (core/format-choice choice)))
                                                   choices))))))
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
           :reason "Failed to start turn"}))))) ; close defn

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
        ;; Capture log size BEFORE sending to avoid race conditions
        initial-log-size (core/get-log-size)]
    (ws/send-message! :game/action
                      {:gameid gameid
                       :command "play"
                       :args args})
    ;; Wait and verify action
    (let [result (core/verify-action-in-log card-title card-zone core/action-timeout initial-log-size)]
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

            :error
            (println (str "❌ Ability failed: " card-name " - " (:reason result))))
          result))
      (do
        (println (str "❌ Card not found installed: " card-name))
        {:status :error :reason (str "Card not found: " card-name)}))))

(defn use-runner-ability!
  "Use a runner ability on a Corp card (e.g., bioroid click-to-break)
   Runner abilities are special abilities on Corp cards that the Runner can activate
   Most commonly used for bioroid ICE during encounters

   Usage: (use-runner-ability! \"Brân 1.0\" 0)
          During encounter, activates the bioroid's click-to-break ability"
  [card-name ability-index]
  (let [client-state @state/client-state
        ;; Find the Corp card (usually ICE during encounter)
        card (core/find-installed-corp-card card-name)]
    (if card
      (let [gameid (:gameid client-state)
            ;; Create card reference matching wire format
            card-ref {:cid (:cid card)
                     :zone (:zone card)
                     :side (:side card)
                     :type (:type card)}]
        (ws/send-message! :game/action
                          {:gameid gameid
                           :command "runner-ability"
                           :args {:card card-ref
                                  :ability ability-index}})
        (Thread/sleep core/medium-delay))
      (println (str "❌ Card not found: " card-name)))))

(defn trash-installed!
  "Trash an installed card (Corp: ICE/asset/upgrade, Runner: rig card)

   Usage: (trash-installed! \"Palisade\")
          (trash-installed! \"Daily Casts\")"
  [card-name]
  (let [client-state @state/client-state
        side (:side client-state)
        ;; Find card in appropriate location based on side
        card (if (= "Corp" side)
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
                ;; ICE can only be rezzed during approach-ice
                is-ice? (= card-type "ICE")
                valid-ice-rez? (or (not is-ice?)
                                   (nil? run)  ; No run = shouldn't happen but allow
                                   (= run-phase "approach-ice"))]
            (if (not valid-ice-rez?)
              (do
                (println (format "❌ Cannot rez ICE during %s phase" (or run-phase "this")))
                (println "   → ICE can only be rezzed during approach-ice phase")
                nil)
              (let [;; Capture log size BEFORE sending to avoid race conditions
                    initial-log-size (core/get-log-size)]
                (ws/send-message! :game/action
                                  {:gameid gameid
                                   :command "rez"
                                   :args {:card card-ref}})
                ;; Wait and verify action appeared in log
                (if (core/verify-action-in-log card-name (:zone card) core/action-timeout initial-log-size)
                  (let [after-state @state/client-state
                        after-credits (get-in after-state [:game-state :corp :credit])]
                    (println (str "🔴 Rezzed: " card-name))
                    (when rez-cost
                      (println (str "   💰 Cost: " rez-cost "₵ (remaining: " after-credits "₵)"))))
                  (println (str "⚠️  Sent rez command for: " card-name " - but action not confirmed in game log (may have failed)"))))))
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

(defn fire-unbroken-subs!
  "Fire unbroken subroutines on ICE (Corp only)
   Used during runs when Runner doesn't/can't break all subs

   Usage: (fire-unbroken-subs! \"Palisade\")
          (fire-unbroken-subs! \"IP Block\")"
  [ice-name]
  (let [client-state @state/client-state
        side (:side client-state)]
    (if (not (core/side= "Corp" side))
      (println "❌ Only Corp can fire ICE subroutines")
      (let [card (core/find-installed-corp-card ice-name)]
        (if card
          (let [gameid (:gameid client-state)
                card-ref {:cid (:cid card)
                         :zone (:zone card)
                         :side (:side card)
                         :type (:type card)}]
            (ws/send-message! :game/action
                              {:gameid gameid
                               :command "unbroken-subroutines"
                               :args {:card card-ref}})
            (Thread/sleep core/medium-delay))
          (println (str "❌ ICE not found installed: " ice-name)))))))

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
                  ;; Capture log size BEFORE sending to avoid race conditions
                  initial-log-size (core/get-log-size)]
              (ws/send-message! :game/action
                                {:gameid gameid
                                 :command "advance"
                                 :args {:card card-ref}})
              ;; Wait and verify action appeared in log
              ;; Note: Card doesn't change zones, so we pass its current zone
              (let [result (core/verify-action-in-log card-name (:zone card) 3000 initial-log-size)]
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

(defn score-agenda!
  "Score an installed agenda (Corp only)
   Agenda must have enough advancement counters to be scored

   Usage: (score-agenda! \"Project Vitruvius\")
          (score-agenda! \"Send a Message\")"
  [card-name]
  (let [client-state @state/client-state
        side (:side client-state)
        before-score (get-in client-state [:game-state :corp :agenda-point])]
    (if (not (core/side= "Corp" side))
      (println "❌ Only Corp can score agendas")
      (let [card (core/find-installed-corp-card card-name)]
        (if card
          (if (= "Agenda" (:type card))
            (let [gameid (:gameid client-state)
                  card-ref (core/create-card-ref card)
                  agenda-points (:agendapoints card)
                  ;; Capture log size BEFORE sending to avoid race conditions
                  initial-log-size (core/get-log-size)]
              (ws/send-message! :game/action
                                {:gameid gameid
                                 :command "score"
                                 :args {:card card-ref}})
              ;; Wait and verify action appeared in log (look for "score" or card name)
              (if (core/verify-action-in-log card-name (:zone card) core/action-timeout initial-log-size)
                (let [after-state @state/client-state
                      after-score (get-in after-state [:game-state :corp :agenda-point])
                      runner-score (get-in after-state [:game-state :runner :agenda-point])]
                  (println (str "🎯 Scored: " card-name
                               (when agenda-points (str " (+" agenda-points " points)"))))
                  (println (str "   📊 Score: Corp " after-score " - " runner-score " Runner")))
                (println (str "⚠️  Sent score command for: " card-name " - but action not confirmed in game log (may have failed)"))))
            (println (str "❌ Card is not an Agenda: " (:title card) " (type: " (:type card) ")")))
          (println (str "❌ Card not found installed: " card-name)))))))
