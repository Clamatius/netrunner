(ns ai-card-actions
  "Card manipulation - play, install, use abilities, rez, trash, advance, score"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-core :as core]
            [ai-basic-actions :as basic]))

;; ============================================================================
;; Card Actions
;; ============================================================================

(defn play-card!
  "Play a card from hand by name or index.
   Auto-starts turn if needed (opponent has ended and we haven't started yet).
   Usage: (play-card! \"Sure Gamble\")
          (play-card! 0)"
  [name-or-index]
  (if (basic/ensure-turn-started!)
    (let [card (core/find-card-in-hand name-or-index)]
      (if card
        (let [before-state (core/capture-state-snapshot)
              state @ws/client-state
              side (keyword (:side state))
              before-credits (get-in state [:game-state side :credit])
              before-clicks (get-in state [:game-state side :click])
              gameid (:gameid state)
              card-ref (core/create-card-ref card)
              card-title (:title card)]
          (ws/send-message! :game/action
                            {:gameid (if (string? gameid)
                                      (java.util.UUID/fromString gameid)
                                      gameid)
                             :command "play"
                             :args {:card card-ref}})
          ;; Wait and verify action appeared in log
          (if (core/verify-action-in-log card-title 3000)
            (let [after-state @ws/client-state
                  after-credits (get-in after-state [:game-state side :credit])
                  after-clicks (get-in after-state [:game-state side :click])
                  credit-delta (- after-credits before-credits)]
              (println (str "🃏 Played: " card-title))
              (when (not= credit-delta 0)
                (println (str "   💰 Credits: " before-credits " → " after-credits
                             " (" (if (pos? credit-delta) "+" "") credit-delta ")")))
              (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
              (core/show-turn-indicator)
              {:status :success
               :data {:card-title card-title}})
            (do
              (println (str "⚠️  Sent play command for: " card-title " - but action not confirmed in game log (may have failed)"))
              (core/show-turn-indicator)
              {:status :error
               :reason (str "Play command for " card-title " not confirmed in game log")})))
        (do
          (println (str "❌ Card not found in hand: " name-or-index))
          {:status :error
           :reason (str "Card not found in hand: " name-or-index)})))
    {:status :error
     :reason "Failed to start turn"}))

(defn install-card!
  "Install a card from hand by name or index.
   Auto-starts turn if needed (opponent has ended and we haven't started yet).
   For Corp: must specify server location
   For Runner: server is optional (auto-installs to appropriate location)

   Server values:
   - Central servers: \"HQ\", \"R&D\", \"Archives\"
   - New remote: \"New remote\"
   - Existing remotes: \"Server 1\", \"Server 2\", etc.

   Usage: (install-card! \"Palisade\" \"HQ\")         ; Corp ICE on HQ
          (install-card! \"Urtica Cipher\" \"New remote\") ; Corp asset in new remote
          (install-card! 0 \"R&D\")                   ; Corp install by index
          (install-card! \"Unity\")                   ; Runner install"
  ([name-or-index]
   (install-card! name-or-index nil))
  ([name-or-index server]
   (if (basic/ensure-turn-started!)
     (let [card (core/find-card-in-hand name-or-index)]
       (if card
         (let [before-state (core/capture-state-snapshot)
               state @ws/client-state
               side (keyword (:side state))
               before-clicks (get-in state [:game-state side :click])
               gameid (:gameid state)
               card-ref (core/create-card-ref card)
               card-title (:title card)
               card-type (:type card)
               ;; Normalize server name (remote2 → Server 2, hq → HQ, etc.)
               normalized-server (when server
                                  (:normalized (core/normalize-server-name server)))
               ;; Both Corp and Runner use "play" command
               ;; Corp requires :server, Runner installs without :server arg
               args (if normalized-server
                     {:card card-ref :server normalized-server}
                     {:card card-ref})]
           (ws/send-message! :game/action
                             {:gameid (if (string? gameid)
                                       (java.util.UUID/fromString gameid)
                                       gameid)
                              :command "play"
                              :args args})
           ;; Wait and verify action appeared in log
           (if (core/verify-action-in-log card-title 3000)
             (let [after-state @ws/client-state
                   after-clicks (get-in after-state [:game-state side :click])]
               (if normalized-server
                 (println (str "📥 Installed: " card-title " on " normalized-server))
                 (println (str "📥 Installed: " card-title " (" card-type ")")))
               (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
               (core/show-turn-indicator)
               {:status :success
                :data {:card-title card-title :server normalized-server}})
             (do
               (println (str "⚠️  Sent install command for: " card-title " - but action not confirmed in game log (may have failed)"))
               (core/show-turn-indicator)
               {:status :error
                :reason (str "Install command for " card-title " not confirmed in game log")})))
         (do
           (println (str "❌ Card not found in hand: " name-or-index))
           {:status :error
            :reason (str "Card not found in hand: " name-or-index)})))
     {:status :error
      :reason "Failed to start turn"})))

;; ============================================================================
;; Card Abilities
;; ============================================================================

(defn use-ability!
  "Use an installed card's ability

   Usage: (use-ability! \"Smartware Distributor\" 0)
          (use-ability! \"Sure Gamble\" 1)"
  [card-name ability-index]
  (let [state @ws/client-state
        side (:side state)
        ;; Find card in appropriate location based on side
        card (if (core/side= "Corp" side)
               (core/find-installed-corp-card card-name)
               (core/find-installed-card card-name))]
    (if card
      (let [gameid (:gameid state)
            ;; Create card reference matching wire format
            card-ref {:cid (:cid card)
                     :zone (:zone card)
                     :side (:side card)
                     :type (:type card)}
            ;; Check if this ability is dynamic (e.g., auto-pump, auto-pump-and-break)
            abilities (:abilities card)
            ability (when (and abilities (< ability-index (count abilities)))
                     (nth abilities ability-index))
            dynamic-type (:dynamic ability)]
        (if dynamic-type
          ;; Use dynamic-ability command for abilities with :dynamic field
          (ws/send-message! :game/action
                            {:gameid (if (string? gameid)
                                      (java.util.UUID/fromString gameid)
                                      gameid)
                             :command "dynamic-ability"
                             :args {:card card-ref
                                    :dynamic dynamic-type}})
          ;; Use regular ability command for normal abilities
          (ws/send-message! :game/action
                            {:gameid (if (string? gameid)
                                      (java.util.UUID/fromString gameid)
                                      gameid)
                             :command "ability"
                             :args {:card card-ref
                                    :ability ability-index}}))
        (Thread/sleep 1500)
        (let [ability-label (when abilities
                             (let [ab (nth abilities ability-index nil)]
                               (:label ab)))]
          (if ability-label
            (println (str "⚡ Used ability: " card-name " - " ability-label))
            (println (str "⚡ Used ability #" ability-index " on " card-name)))))
      (println (str "❌ Card not found installed: " card-name)))))

(defn use-runner-ability!
  "Use a runner ability on a Corp card (e.g., bioroid click-to-break)
   Runner abilities are special abilities on Corp cards that the Runner can activate
   Most commonly used for bioroid ICE during encounters

   Usage: (use-runner-ability! \"Brân 1.0\" 0)
          During encounter, activates the bioroid's click-to-break ability"
  [card-name ability-index]
  (let [state @ws/client-state
        ;; Find the Corp card (usually ICE during encounter)
        card (core/find-installed-corp-card card-name)]
    (if card
      (let [gameid (:gameid state)
            ;; Create card reference matching wire format
            card-ref {:cid (:cid card)
                     :zone (:zone card)
                     :side (:side card)
                     :type (:type card)}]
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "runner-ability"
                           :args {:card card-ref
                                  :ability ability-index}})
        (Thread/sleep 1500))
      (println (str "❌ Card not found: " card-name)))))

(defn trash-installed!
  "Trash an installed card (Corp: ICE/asset/upgrade, Runner: rig card)

   Usage: (trash-installed! \"Palisade\")
          (trash-installed! \"Daily Casts\")"
  [card-name]
  (let [state @ws/client-state
        side (:side state)
        ;; Find card in appropriate location based on side
        card (if (= "Corp" side)
               (core/find-installed-corp-card card-name)
               (core/find-installed-card card-name))]
    (if card
      (let [gameid (:gameid state)
            card-ref {:cid (:cid card)
                     :zone (:zone card)
                     :side (:side card)
                     :type (:type card)}
            card-type (:type card)
            card-zone (:zone card)]
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "trash"
                           :args {:card card-ref}})
        (Thread/sleep 1500)
        (println (str "🗑️  Trashed: " card-name " (" card-type ")")))
      (println (str "❌ Card not found installed: " card-name)))))

(defn rez-card!
  "Rez an installed Corp card (ICE, asset, or upgrade)

   Supports [N] suffix for multiple copies: \"Palisade [1]\"

   Usage: (rez-card! \"Prisec\")
          (rez-card! \"Palisade [1]\")"
  [card-name]
  (let [state @ws/client-state
        side (:side state)
        before-credits (get-in state [:game-state :corp :credit])]
    (if (not (core/side= "Corp" side))
      (println "❌ Only Corp can rez cards")
      (let [card (core/find-installed-corp-card card-name)]
        (if card
          (let [gameid (:gameid state)
                card-ref {:cid (:cid card)
                         :zone (:zone card)
                         :side (:side card)
                         :type (:type card)}
                rez-cost (:cost card)]
            (ws/send-message! :game/action
                              {:gameid (if (string? gameid)
                                        (java.util.UUID/fromString gameid)
                                        gameid)
                               :command "rez"
                               :args {:card card-ref}})
            ;; Wait and verify action appeared in log
            (if (core/verify-action-in-log card-name 3000)
              (let [after-state @ws/client-state
                    after-credits (get-in after-state [:game-state :corp :credit])]
                (println (str "🔴 Rezzed: " card-name))
                (when rez-cost
                  (println (str "   💰 Cost: " rez-cost "₵ (remaining: " after-credits "₵)"))))
              (println (str "⚠️  Sent rez command for: " card-name " - but action not confirmed in game log (may have failed)"))))
          (println (str "❌ Card not found installed: " card-name)))))))

(defn let-subs-fire!
  "Signal intent to let unbroken subroutines fire (Runner only)
   Sends a system message to indicate Runner is allowing subs to fire

   Usage: (let-subs-fire! \"Whitespace\")
          (let-subs-fire! \"IP Block\")"
  [ice-name]
  (let [state @ws/client-state
        side (:side state)
        gameid (:gameid state)]
    (if (not (core/side= "Runner" side))
      (println "❌ Only Runner can let subroutines fire")
      (do
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "system-msg"
                           :args {:msg (str "indicates to fire all unbroken subroutines on " ice-name)}})
        (Thread/sleep 1000)))))

(defn toggle-auto-no-action!
  "Toggle auto-pass priority during runs (Corp only)
   When enabled, automatically passes on all rez/paid ability windows
   Prompt changes to 'Stop Auto-passing Priority' when active

   Usage: (toggle-auto-no-action!)"
  []
  (let [state @ws/client-state
        side (:side state)
        gameid (:gameid state)]
    (if (not (core/side= "Corp" side))
      (println "❌ Only Corp can toggle auto-pass priority")
      (do
        (ws/send-message! :game/action
                          {:gameid (if (string? gameid)
                                    (java.util.UUID/fromString gameid)
                                    gameid)
                           :command "toggle-auto-no-action"
                           :args nil})
        (Thread/sleep 500)))))

(defn fire-unbroken-subs!
  "Fire unbroken subroutines on ICE (Corp only)
   Used during runs when Runner doesn't/can't break all subs

   Usage: (fire-unbroken-subs! \"Palisade\")
          (fire-unbroken-subs! \"IP Block\")"
  [ice-name]
  (let [state @ws/client-state
        side (:side state)]
    (if (not (core/side= "Corp" side))
      (println "❌ Only Corp can fire ICE subroutines")
      (let [card (core/find-installed-corp-card ice-name)]
        (if card
          (let [gameid (:gameid state)
                card-ref {:cid (:cid card)
                         :zone (:zone card)
                         :side (:side card)
                         :type (:type card)}]
            (ws/send-message! :game/action
                              {:gameid (if (string? gameid)
                                        (java.util.UUID/fromString gameid)
                                        gameid)
                               :command "unbroken-subroutines"
                               :args {:card card-ref}})
            (Thread/sleep 1500))
          (println (str "❌ ICE not found installed: " ice-name)))))))

(defn advance-card!
  "Advance an installed Corp card (agenda, ICE, or asset).
   Auto-starts turn if needed (opponent has ended and we haven't started yet).
   Costs 1 click and 1 credit per advancement counter

   Usage: (advance-card! \"Project Vitruvius\")
          (advance-card! \"Oaktown Renovation\")"
  [card-name]
  (when (basic/ensure-turn-started!)
    (let [state @ws/client-state
          side (:side state)]
      (if (not (core/side= "Corp" side))
        (println "❌ Only Corp can advance cards")
        (let [card (core/find-installed-corp-card card-name)
              before-counters (or (:advance-counter card) 0)
              before-credits (get-in state [:game-state :corp :credit])
              before-clicks (get-in state [:game-state :corp :click])
              advancement-requirement (:advancementcost card)]
          (if card
            (let [gameid (:gameid state)
                  card-ref {:cid (:cid card)
                           :zone (:zone card)
                           :side (:side card)
                           :type (:type card)}]
              (ws/send-message! :game/action
                                {:gameid (if (string? gameid)
                                          (java.util.UUID/fromString gameid)
                                          gameid)
                                 :command "advance"
                                 :args {:card card-ref}})
              ;; Wait and verify action appeared in log
              (if (core/verify-action-in-log card-name 3000)
                (let [after-state @ws/client-state
                      updated-card (core/find-installed-corp-card card-name)
                      after-counters (or (:advance-counter updated-card) 0)
                      after-credits (get-in after-state [:game-state :corp :credit])
                      after-clicks (get-in after-state [:game-state :corp :click])]
                  (println (str "⏫ Advanced: " card-name " (" after-counters
                               (when advancement-requirement (str "/" advancement-requirement))
                               " counters)"))
                  (core/show-before-after "💰 Credits" before-credits after-credits)
                  (core/show-before-after "⏱️  Clicks" before-clicks after-clicks))
                (println (str "⚠️  Sent advance command for: " card-name " - but action not confirmed in game log (may have failed)"))))
            (println (str "❌ Card not found installed: " card-name))))))))

(defn score-agenda!
  "Score an installed agenda (Corp only)
   Agenda must have enough advancement counters to be scored

   Usage: (score-agenda! \"Project Vitruvius\")
          (score-agenda! \"Send a Message\")"
  [card-name]
  (let [state @ws/client-state
        side (:side state)
        before-score (get-in state [:game-state :corp :agenda-point])]
    (if (not (core/side= "Corp" side))
      (println "❌ Only Corp can score agendas")
      (let [card (core/find-installed-corp-card card-name)]
        (if card
          (if (= "Agenda" (:type card))
            (let [gameid (:gameid state)
                  card-ref {:cid (:cid card)
                           :zone (:zone card)
                           :side (:side card)
                           :type (:type card)}
                  agenda-points (:agendapoints card)]
              (ws/send-message! :game/action
                                {:gameid (if (string? gameid)
                                          (java.util.UUID/fromString gameid)
                                          gameid)
                                 :command "score"
                                 :args {:card card-ref}})
              ;; Wait and verify action appeared in log (look for "score" or card name)
              (if (core/verify-action-in-log card-name 3000)
                (let [after-state @ws/client-state
                      after-score (get-in after-state [:game-state :corp :agenda-point])
                      runner-score (get-in after-state [:game-state :runner :agenda-point])]
                  (println (str "🎯 Scored: " card-name
                               (when agenda-points (str " (+" agenda-points " points)"))))
                  (println (str "   📊 Score: Corp " after-score " - " runner-score " Runner")))
                (println (str "⚠️  Sent score command for: " card-name " - but action not confirmed in game log (may have failed)"))))
            (println (str "❌ Card is not an Agenda: " (:title card) " (type: " (:type card) ")")))
          (println (str "❌ Card not found installed: " card-name)))))))
