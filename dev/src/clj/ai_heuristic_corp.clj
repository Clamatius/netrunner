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

(declare keep-or-mull)

;; ============================================================================
;; Configuration
;; ============================================================================

(def config
  {:min-credits 8          ; Below this, prioritize economy
   :min-hand-size 3        ; Below this, consider drawing
   :ice-hq-target 1        ; Just one ICE on HQ (single-remote strategy)
   :ice-rd-target 2        ; Layer R&D
   :win-points 7           ; Points needed to win (6 for tutorial, 7 for full)
   :log-decisions true})   ; Print decision reasoning

(defn log-message [& args]
  (let [msg (str "🤖 " (str/join " " args))]
    (.println System/out msg)
    (println msg)))

(defn log-decision [& args]
  (when (:log-decisions config)
    (apply log-message args)))

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

(defn upgrades-in-hand []
  (cards-of-type (my-hand) "Upgrade"))

(defn traps-in-hand
  "Find assets that are traps (Urtica Cipher, etc.) - advanceable bluffs"
  []
  (filter (fn [card]
            (or (str/includes? (str (:title card)) "Urtica")
                (str/includes? (str (:title card)) "Cerebral")
                (str/includes? (str (:title card)) "Snare")
                (str/includes? (str (:title card)) "NGO Front")
                ;; Ambush subtype
                (str/includes? (str (:subtype card)) "Ambush")))
          (assets-in-hand)))

(defn economy-assets-in-hand
  "Find assets that generate money (Regolith, PAD, Nico, etc.)"
  []
  (filter (fn [card]
            (or (str/includes? (str (:title card)) "Regolith")
                (str/includes? (str (:title card)) "PAD Campaign")
                (str/includes? (str (:title card)) "Nico Campaign")
                (str/includes? (str (:title card)) "Marilyn")
                (str/includes? (str (:title card)) "Rashida")))
          (assets-in-hand)))


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

(defn protected-empty-remote
  "Find a remote that has ICE and no content. Returns server-key or nil.
   For single-remote strategy, we only use one remote."
  []
  (let [remotes (get-remote-servers)]
    (first
      (for [[server-key _] remotes
            :let [content (server-content server-key)
                  has-ice (server-has-ice? server-key)]
            :when (and has-ice (empty? content))]
        server-key))))

(defn protected-remote-for-agenda
  "Find a remote that has ICE and can accept an agenda.
   Agenda can share remote with upgrades, but not with other agendas/assets.
   Returns server-key or nil."
  []
  (let [remotes (get-remote-servers)]
    (first
      (for [[server-key _] remotes
            :let [content (server-content server-key)
                  has-ice (server-has-ice? server-key)
                  has-agenda-or-asset (some #(#{"Agenda" "Asset"} (:type %)) content)]
            :when (and has-ice (not has-agenda-or-asset))]
        server-key))))

(defn protected-remote-with-asset
  "Find a remote that has ICE and contains an asset (not an agenda).
   Used to find remotes where we could overwrite the asset with an agenda.
   Returns server-key or nil."
  []
  (let [remotes (get-remote-servers)]
    (first
      (for [[server-key _] remotes
            :let [content (server-content server-key)
                  has-ice (server-has-ice? server-key)
                  has-asset (some #(= "Asset" (:type %)) content)
                  has-agenda (some #(= "Agenda" (:type %)) content)]
            :when (and has-ice has-asset (not has-agenda))]
        server-key))))

(defn the-remote
  "Get THE remote server (single-remote strategy).
   Returns the first remote if it exists, or nil."
  []
  (first (keys (get-remote-servers))))

(defn remote-server-name
  "Convert server-key like :remote1 to 'Server 1'"
  [server-key]
  (when server-key
    (let [num (Integer/parseInt (str/replace (name server-key) "remote" ""))]
      (str "Server " num))))

;; ============================================================================
;; Scoring Economics - Can we afford to install and score?
;; ============================================================================

(defn my-agenda-points
  "Get Corp's current agenda points"
  []
  (or (get-in @state/client-state [:game-state :corp :agenda-point]) 0))

(defn server-rez-cost
  "Calculate total rez cost of unrezzed ICE on a server"
  [server-key]
  (let [ice-list (state/server-ice server-key)]
    (->> ice-list
         (filter #(not (:rezzed %)))
         (map #(or (:cost %) 0))
         (reduce + 0))))

(defn agenda-advancement-cost
  "Get the advancement cost of an agenda"
  [agenda]
  (or (:advancementcost agenda) 0))

(defn can-afford-agenda-install?
  "Check if we can afford to install agenda in remote and score it.
   Needs: credits > rez cost of unrezzed ICE + advancement cost
   This ensures we can rez ICE and advance to score."
  [agenda server-key]
  (let [rez-cost (server-rez-cost server-key)
        adv-cost (agenda-advancement-cost agenda)
        total-needed (+ rez-cost adv-cost)
        credits (my-credits)]
    (> credits total-needed)))

(defn can-win-with-agenda?
  "Check if scoring an agenda would win the game"
  [agenda]
  (let [current-points (my-agenda-points)
        agenda-points (or (:agendapoints agenda) 0)
        win-points (:win-points config)]
    (>= (+ current-points agenda-points) win-points)))

(defn winning-agenda-for-remote
  "Find an agenda in hand that would win the game if installed in the remote.
   Returns the agenda if we can afford to install and score it, nil otherwise."
  []
  (when-let [remote (the-remote)]
    (first (filter #(and (can-win-with-agenda? %)
                         (can-afford-agenda-install? % remote))
                   (agendas-in-hand)))))

(defn affordable-agenda-for-remote
  "Find an agenda in hand that we can afford to install and score.
   Only returns an agenda if there's a protected remote that can accept it
   (has ICE, no existing agenda/asset - upgrades are fine).
   Returns the agenda, or nil if none affordable or no suitable remote."
  []
  (when-let [remote (protected-remote-for-agenda)]
    (first (filter #(can-afford-agenda-install? % remote)
                   (agendas-in-hand)))))

(defn affordable-agenda-to-overwrite-asset
  "Find an agenda we can afford to install by overwriting an asset.
   Used when the remote is blocked by an economy asset.
   Returns the agenda, or nil if none affordable or no asset to overwrite."
  []
  (when-let [remote (protected-remote-with-asset)]
    (first (filter #(can-afford-agenda-install? % remote)
                   (agendas-in-hand)))))

(defn central-ice-counts
  "Return map of central server -> ICE count"
  []
  {:hq (count (state/server-ice :hq))
   :rd (count (state/server-ice :rd))
   :archives (count (state/server-ice :archives))})

(defn remote-ice-count
  "Count ICE on THE remote (or 0 if no remote)"
  []
  (if-let [remote (the-remote)]
    (count (state/server-ice remote))
    0))

(defn next-ice-target
  "Determine where to install next ICE. Single-remote strategy:
   1. R&D needs at least 1 ICE first (most important)
   2. HQ needs exactly 1 ICE
   3. Then layer on remote
   4. Then keep layering R&D
   Returns server name string or nil if nowhere needs ICE."
  []
  (let [{:keys [hq rd]} (central-ice-counts)
        remote-ice (remote-ice-count)
        remote (the-remote)]
    (cond
      ;; R&D needs first ICE (critical)
      (zero? rd) "R&D"
      ;; HQ needs its one ICE
      (zero? hq) "HQ"
      ;; Remote needs ICE (if remote exists)
      (and remote (< remote-ice 2)) (remote-server-name remote)
      ;; Layer more on R&D
      (< rd (:ice-rd-target config)) "R&D"
      ;; Keep layering remote
      (and remote (< remote-ice 3)) (remote-server-name remote)
      ;; All good
      :else nil)))

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
  (protected-empty-remote))

;; ============================================================================
;; Advance Logic Helper
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
;; Rule Registry
;; ============================================================================
;;
;; Each rule is a map with:
;;   :id          - Keyword identifier
;;   :label       - Short display label (for dashboard)
;;   :description - What this rule does
;;   :condition-fn - (fn [ctx] bool) - Returns true if rule applies
;;   :action-fn    - (fn [ctx] action-map) - Returns {:action :args}
;;   :reason-fn    - (fn [ctx] string) - Explains why condition failed/passed

(defn make-ctx
  "Build context map for rule evaluation. Caches expensive lookups."
  []
  {:clicks (my-clicks)
   :credits (my-credits)
   :hand (my-hand)
   :hand-size (count (my-hand))
   :scorables (scorable-agendas)
   :advanceables (advanceable-agendas)
   :unprotected-agenda-servers (unprotected-remotes-with-agendas)
   :ice-in-hand (ice-in-hand)
   :agendas-in-hand (agendas-in-hand)
   :economy-ops (economy-operations)
   :economy-assets (economy-assets-in-hand)
   :traps-in-hand (traps-in-hand)
   :upgrades-in-hand (upgrades-in-hand)
   :remotes (get-remote-servers)
   :the-remote (the-remote)
   :protected-empty (protected-empty-remote)
   :protected-for-agenda (protected-remote-for-agenda)
   :protected-with-asset (protected-remote-with-asset)
   :click-assets (rezzed-assets-with-click-abilities)
   :click-econ-unrezzed (click-economy-assets)
   :ice-target (next-ice-target)
   :my-points (my-agenda-points)
   :min-credits (:min-credits config)
   :min-hand-size (:min-hand-size config)})

(def rules
  "Ordered list of Corp decision rules. First matching rule wins."
  [{:id :score
    :label "SCORE"
    :description "Score agenda that's ready"
    :condition-fn (fn [ctx] (seq (:scorables ctx)))
    :action-fn (fn [ctx]
                 (let [agenda (first (:scorables ctx))]
                   {:action :score :args {:card-name (:title agenda)}}))
    :reason-fn (fn [ctx]
                 (if (seq (:scorables ctx))
                   (str (:title (first (:scorables ctx))) " is scorable")
                   "no scorable agendas"))}

   {:id :protect-naked-agenda
    :label "PROTECT"
    :description "ICE unprotected agenda in remote"
    :condition-fn (fn [ctx]
                    (and (seq (:unprotected-agenda-servers ctx))
                         (seq (:ice-in-hand ctx))))
    :action-fn (fn [ctx]
                 (let [server-key (first (:unprotected-agenda-servers ctx))
                       ice (first (:ice-in-hand ctx))]
                   {:action :install-ice
                    :args {:card-name (:title ice)
                           :server (remote-server-name server-key)}}))
    :reason-fn (fn [ctx]
                 (cond
                   (empty? (:unprotected-agenda-servers ctx)) "no unprotected agendas"
                   (empty? (:ice-in-hand ctx)) "no ICE in hand"
                   :else (str "protect " (name (first (:unprotected-agenda-servers ctx))))))}

   {:id :install-for-win
    :label "INSTALL FOR WIN"
    :description "Install agenda that wins the game"
    :condition-fn (fn [_] (winning-agenda-for-remote))
    :action-fn (fn [_]
                 (let [agenda (winning-agenda-for-remote)
                       server (remote-server-name (the-remote))]
                   {:action :install :args {:card-name (:title agenda) :server server}}))
    :reason-fn (fn [ctx]
                 (let [agenda (winning-agenda-for-remote)]
                   (if agenda
                     (str (:title agenda) " wins at " (+ (:my-points ctx) (or (:agendapoints agenda) 0)) " pts")
                     (str (:my-points ctx) " pts + hand agendas < " (:win-points config)))))}

   {:id :create-remote
    :label "CREATE REMOTE"
    :description "Create remote if none exists"
    :condition-fn (fn [ctx]
                    (and (empty? (:remotes ctx))
                         (seq (:ice-in-hand ctx))))
    :action-fn (fn [ctx]
                 (let [ice (first (:ice-in-hand ctx))]
                   {:action :install-ice
                    :args {:card-name (:title ice) :server "New remote"}}))
    :reason-fn (fn [ctx]
                 (cond
                   (seq (:remotes ctx)) "remote already exists"
                   (empty? (:ice-in-hand ctx)) "no ICE in hand"
                   :else "creating new remote"))}

   {:id :install-agenda
    :label "INSTALL AGENDA"
    :description "Install agenda to protected remote"
    :condition-fn (fn [_] (affordable-agenda-for-remote))
    :action-fn (fn [_]
                 (let [agenda (affordable-agenda-for-remote)
                       remote (protected-remote-for-agenda)
                       server (remote-server-name remote)]
                   {:action :install :args {:card-name (:title agenda) :server server}}))
    :reason-fn (fn [ctx]
                 (let [agenda (affordable-agenda-for-remote)
                       remote (protected-remote-for-agenda)]
                   (cond
                     (nil? remote) "no protected empty remote"
                     (empty? (:agendas-in-hand ctx)) "no agendas in hand"
                     (nil? agenda) "can't afford to score"
                     :else (str (:title agenda) " to " (remote-server-name remote)))))}

   {:id :overwrite-asset
    :label "OVERWRITE ASSET"
    :description "Replace econ asset with agenda"
    :condition-fn (fn [_] (affordable-agenda-to-overwrite-asset))
    :action-fn (fn [_]
                 (let [agenda (affordable-agenda-to-overwrite-asset)
                       remote (protected-remote-with-asset)
                       server (remote-server-name remote)]
                   {:action :install :args {:card-name (:title agenda) :server server}}))
    :reason-fn (fn [ctx]
                 (let [agenda (affordable-agenda-to-overwrite-asset)
                       remote (protected-remote-with-asset)]
                   (cond
                     (nil? remote) "no remote with asset"
                     (empty? (:agendas-in-hand ctx)) "no agendas in hand"
                     (nil? agenda) "can't afford to score"
                     :else (str (:title agenda) " overwrites in " (remote-server-name remote)))))}

   {:id :install-trap
    :label "INSTALL TRAP"
    :description "Install trap as bluff"
    :condition-fn (fn [ctx]
                    (and (:protected-empty ctx)
                         (>= (:credits ctx) 5)
                         (seq (:traps-in-hand ctx))))
    :action-fn (fn [ctx]
                 (let [trap (first (:traps-in-hand ctx))
                       server (remote-server-name (:protected-empty ctx))]
                   {:action :install :args {:card-name (:title trap) :server server}}))
    :reason-fn (fn [ctx]
                 (cond
                   (nil? (:protected-empty ctx)) "no protected empty remote"
                   (< (:credits ctx) 5) "need 5+ credits for bluff"
                   (empty? (:traps-in-hand ctx)) "no traps in hand"
                   :else (str (:title (first (:traps-in-hand ctx))) " as bluff")))}

   {:id :install-econ-asset
    :label "INSTALL ECON"
    :description "Install economy asset to remote"
    :condition-fn (fn [ctx]
                    (and (:protected-empty ctx)
                         (seq (:economy-assets ctx))))
    :action-fn (fn [ctx]
                 (let [asset (first (:economy-assets ctx))
                       server (remote-server-name (:protected-empty ctx))]
                   {:action :install :args {:card-name (:title asset) :server server}}))
    :reason-fn (fn [ctx]
                 (cond
                   (nil? (:protected-empty ctx)) "no protected empty remote"
                   (empty? (:economy-assets ctx)) "no econ assets in hand"
                   :else (str (:title (first (:economy-assets ctx))) " to remote")))}

   {:id :install-upgrade
    :label "INSTALL UPGRADE"
    :description "Install upgrade to remote"
    :condition-fn (fn [ctx]
                    (and (:protected-empty ctx)
                         (seq (:upgrades-in-hand ctx))))
    :action-fn (fn [ctx]
                 (let [upgrade (first (:upgrades-in-hand ctx))
                       server (remote-server-name (:protected-empty ctx))]
                   {:action :install :args {:card-name (:title upgrade) :server server}}))
    :reason-fn (fn [ctx]
                 (cond
                   (nil? (:protected-empty ctx)) "no protected empty remote"
                   (empty? (:upgrades-in-hand ctx)) "no upgrades in hand"
                   :else (str (:title (first (:upgrades-in-hand ctx))) " to remote")))}

   {:id :advance
    :label "ADVANCE"
    :description "Advance installed agenda"
    :condition-fn (fn [ctx] (should-advance? (:clicks ctx) (:credits ctx)))
    :action-fn (fn [ctx]
                 (let [agenda (should-advance? (:clicks ctx) (:credits ctx))]
                   {:action :advance :args {:card-name (:title agenda)}}))
    :reason-fn (fn [ctx]
                 (let [agenda (should-advance? (:clicks ctx) (:credits ctx))]
                   (if agenda
                     (str (:title agenda) " "
                          (or (:advance-counter agenda) 0) "/" (:advancementcost agenda))
                     (cond
                       (empty? (:advanceables ctx)) "no advanceable agendas"
                       (< (:credits ctx) 1) "need 1+ credit"
                       :else "would strand at scorable"))))}

   {:id :use-asset-low
    :label "USE ASSET"
    :description "Use click asset when poor"
    :condition-fn (fn [ctx]
                    (and (< (:credits ctx) (:min-credits ctx))
                         (seq (:click-assets ctx))))
    :action-fn (fn [ctx]
                 (let [asset (first (:click-assets ctx))
                       ability-idx (:ability-idx asset)]
                   {:action :use-ability
                    :args {:card-name (:title asset) :ability-idx ability-idx}}))
    :reason-fn (fn [ctx]
                 (cond
                   (>= (:credits ctx) (:min-credits ctx)) (str "have " (:credits ctx) " >= " (:min-credits ctx))
                   (empty? (:click-assets ctx)) "no usable click assets"
                   :else (str "use " (:title (first (:click-assets ctx))))))}

   {:id :econ-op-low
    :label "ECON OP"
    :description "Play economy operation when poor"
    :condition-fn (fn [ctx]
                    (and (< (:credits ctx) (:min-credits ctx))
                         (seq (:economy-ops ctx))))
    :action-fn (fn [ctx]
                 (let [op (first (:economy-ops ctx))]
                   {:action :play :args {:card-name (:title op)}}))
    :reason-fn (fn [ctx]
                 (cond
                   (>= (:credits ctx) (:min-credits ctx)) (str "have " (:credits ctx) " >= " (:min-credits ctx))
                   (empty? (:economy-ops ctx)) "no econ operations"
                   :else (str "play " (:title (first (:economy-ops ctx))))))}

   {:id :click-credit-low
    :label "CLICK CREDIT"
    :description "Take credit when poor"
    :condition-fn (fn [ctx] (< (:credits ctx) (:min-credits ctx)))
    :action-fn (fn [_] {:action :credit :args {}})
    :reason-fn (fn [ctx]
                 (if (< (:credits ctx) (:min-credits ctx))
                   (str (:credits ctx) " < " (:min-credits ctx))
                   (str "have " (:credits ctx) " >= " (:min-credits ctx))))}

   {:id :use-asset
    :label "USE ASSET"
    :description "Drain click assets even when not poor"
    :condition-fn (fn [ctx] (seq (:click-assets ctx)))
    :action-fn (fn [ctx]
                 (let [asset (first (:click-assets ctx))
                       ability-idx (:ability-idx asset)]
                   {:action :use-ability
                    :args {:card-name (:title asset) :ability-idx ability-idx}}))
    :reason-fn (fn [ctx]
                 (if (seq (:click-assets ctx))
                   (str "drain " (:title (first (:click-assets ctx))))
                   "no usable click assets"))}

   {:id :rez-click-asset
    :label "REZ ASSET"
    :description "Rez click economy asset"
    :condition-fn (fn [ctx]
                    (and (seq (:click-econ-unrezzed ctx))
                         (can-rez-asset? (first (:click-econ-unrezzed ctx)) (:credits ctx))))
    :action-fn (fn [ctx]
                 (let [asset (first (:click-econ-unrezzed ctx))]
                   {:action :rez :args {:card-name (:title asset)}}))
    :reason-fn (fn [ctx]
                 (let [asset (first (:click-econ-unrezzed ctx))]
                   (cond
                     (empty? (:click-econ-unrezzed ctx)) "no unrezzed click assets"
                     (not (can-rez-asset? asset (:credits ctx))) "can't afford rez"
                     :else (str "rez " (:title asset)))))}

   {:id :install-ice
    :label "ICE"
    :description "Install ICE on next priority target"
    :condition-fn (fn [ctx]
                    (and (seq (:ice-in-hand ctx))
                         (:ice-target ctx)))
    :action-fn (fn [ctx]
                 (let [ice (first (:ice-in-hand ctx))
                       server (:ice-target ctx)]
                   {:action :install-ice
                    :args {:card-name (:title ice) :server server}}))
    :reason-fn (fn [ctx]
                 (cond
                   (empty? (:ice-in-hand ctx)) "no ICE in hand"
                   (nil? (:ice-target ctx)) "all servers covered"
                   :else (str (:title (first (:ice-in-hand ctx))) " on " (:ice-target ctx))))}

   {:id :econ-op
    :label "ECON OP"
    :description "Play economy operation"
    :condition-fn (fn [ctx] (seq (:economy-ops ctx)))
    :action-fn (fn [ctx]
                 (let [op (first (:economy-ops ctx))]
                   {:action :play :args {:card-name (:title op)}}))
    :reason-fn (fn [ctx]
                 (if (seq (:economy-ops ctx))
                   (str "play " (:title (first (:economy-ops ctx))))
                   "no econ operations"))}

   {:id :draw
    :label "DRAW"
    :description "Draw if hand is small"
    :condition-fn (fn [ctx] (< (:hand-size ctx) (:min-hand-size ctx)))
    :action-fn (fn [_] {:action :draw :args {}})
    :reason-fn (fn [ctx]
                 (if (< (:hand-size ctx) (:min-hand-size ctx))
                   (str (:hand-size ctx) " cards < " (:min-hand-size ctx))
                   (str "have " (:hand-size ctx) " >= " (:min-hand-size ctx))))}

   {:id :default-credit
    :label "DEFAULT"
    :description "Take credit as fallback"
    :condition-fn (fn [_] true)
    :action-fn (fn [_] {:action :credit :args {}})
    :reason-fn (fn [_] "fallback action")}])

(defn evaluate-all-rules
  "Evaluate all rules against current context.
   Returns list of {:rule :met? :reason :action :priority}"
  ([] (evaluate-all-rules (make-ctx)))
  ([ctx]
   (map-indexed
     (fn [idx rule]
       (let [met? (try
                    (boolean ((:condition-fn rule) ctx))
                    (catch Exception e
                      (println "⚠️ Error evaluating rule" (:id rule) ":" (.getMessage e))
                      false))
             reason (try
                      ((:reason-fn rule) ctx)
                      (catch Exception _ "error"))]
         {:rule rule
          :priority (inc idx)
          :met? met?
          :reason reason
          :action (when met?
                    (try
                      ((:action-fn rule) ctx)
                      (catch Exception _ nil)))}))
     rules)))

(defn dashboard
  "Return formatted string showing all ranked options.
   Shows available actions with ✓ and blocked ones with ✗."
  ([] (dashboard (make-ctx)))
  ([ctx]
   (let [evaluated (evaluate-all-rules ctx)
         lines (for [{:keys [rule priority met? reason]} evaluated]
                 (let [icon (if met? "✓" "✗")
                       label (format "%-18s" (:label rule))
                       rule-id (str "[" (name (:id rule)) "]")]
                   (str (format "%2d. " priority) icon " " label " " (format "%-24s" rule-id) " " reason)))]
     (str "\n"
          "BASELINE RANKING (tutorial-level heuristic)\n"
          "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
          "Credits: " (:credits ctx) " | Clicks: " (:clicks ctx) " | Hand: " (:hand-size ctx) "\n"
          "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
          (str/join "\n" lines)
          "\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"))))

(defn dashboard-compact
  "Return compact dashboard showing top N available actions."
  ([n] (dashboard-compact n (make-ctx)))
  ([n ctx]
   (let [evaluated (evaluate-all-rules ctx)
         available (->> evaluated
                        (filter :met?)
                        (take n))]
     (str "\n"
          "AVAILABLE OPTIONS (top " n ")\n"
          "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
          (str/join "\n"
                    (for [{:keys [rule priority reason]} available]
                      (str (format "%2d. " priority) (:label rule) " - " reason)))
          "\n"))))

(defn first-matching-rule
  "Find first rule whose condition is met. Returns evaluated rule map or nil."
  ([] (first-matching-rule (make-ctx)))
  ([ctx]
   (first (filter :met? (evaluate-all-rules ctx)))))

;; ============================================================================
;; Decision Logic
;; ============================================================================

(defn decide-action
  "Analyze game state and decide what action to take.
   Uses the rule registry - first matching rule wins.

   Returns a map with :action and :args keys, or nil if nothing to do."
  []
  (let [clicks (my-clicks)]
    ;; No clicks = can't do anything
    (when (and clicks (pos? clicks))
      (let [ctx (make-ctx)
            match (first-matching-rule ctx)]
        (when match
          (let [{:keys [rule reason action]} match]
            (log-decision (:label rule) ":" reason)
            action))))))

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

        ;; Keep/Mulligan prompt
        (or (str/includes? msg "Keep hand")
            (str/includes? msg "Mulligan"))
        (do
          (keep-or-mull)
          true)

        ;; Regular choice prompts
        (seq choices)
        (do
          (log-decision "PROMPT: Handling" msg)
          ;; Simple heuristic: choose first option
          (prompts/choose-by-index! 0)
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
  (let [has-ice (seq (ice-in-hand))
        has-econ (or (seq (economy-operations))
                     (>= (my-credits) 5))
        has-agenda (seq (agendas-in-hand))]
    (if (and has-ice (or has-econ has-agenda))
      (do
        (log-decision "KEEP: Have ICE and economy/agenda")
        (prompts/choose-by-value! "Keep"))
      (do
        (log-decision "MULLIGAN: Missing ICE or economy")
        (prompts/choose-by-value! "Mulligan")))))

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
  (println (dashboard-compact 5)))

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
  (log-message "HEURISTIC CORP - Starting autonomous loop")
  (loop [iter 0]
    (when (zero? (mod iter 10))
      (let [gs (:game-state @state/client-state)]
        (log-message (str "💓 Corp Loop | Turn: " (:turn gs)
                      " | Active: " (:active-player gs)
                      " | Clicks: " (my-clicks)
                      " | Credits: " (my-credits)))))

    (let [continue? (try
                      (let [game-state @state/client-state
                            winner (get-in game-state [:game-state :winner])]

                        (if winner
                          (do
                            (log-message "HEURISTIC CORP - Game over (Winner:" winner ") - Stopping loop.")
                            false)
                          (let [my-turn? (= "corp" (:active-player (:game-state game-state)))]

                            ;; 1. Handle Prompts (Priority)
                            (when (handle-prompt-if-needed)
                              (Thread/sleep 500))

                            ;; 1.5 Attempt to start turn if valid (e.g. opponent ended)
                            (let [start-check (actions/can-start-turn?)]
                              (when (:can-start start-check)
                                (log-message "HEURISTIC CORP - Auto-starting turn")
                                (actions/start-turn!)
                                (Thread/sleep 500)))

                            ;; 2. If my turn, play
                            (when (and my-turn? (not (state/get-prompt)))
                              (if (pos? (my-clicks))
                                (play-turn)
                                (do
                                  ;; Only log this occasionally to reduce spam
                                  (when (zero? (mod iter 20))
                                    (log-message "HEURISTIC CORP - 0 clicks detected in loop, attempting end-turn"))
                                  (actions/smart-end-turn!))))

                            ;; 3. If opponent turn, watch for runs
                            (when (and (not my-turn?) (has-active-run?))
                              (respond-to-run!)
                              (Thread/sleep 500))

                            true))) ;; Continue loop
                      (catch Exception e
                        (log-message "❌ HEURISTIC CORP ERROR:" (.getMessage e))
                        (.printStackTrace e)
                        (Thread/sleep 5000)
                        true))] ;; Continue loop on error
      (when continue?
        (Thread/sleep 500)
        (recur (inc iter))))))
