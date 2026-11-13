(ns ai-run-tactics
  "Run Tactics System (V1) - Per-ICE breaking strategies

   Tactics allow specifying per-ICE breaking strategies:

   {:tactics {\"Tithe\"      {:card \"Unity\" :action :break-dynamic}
              \"Whitespace\" {:card \"Carmen\" :action :break-subs :subs [0 2]}
              :default     :pause}}

   Supported actions (V1):
   - :break-dynamic  - Use card's \"Match strength and fully break\" ability
   - :use-ability    - Use specific ability by index {:ability-index N}

   V2 stubs — recognized, and NOT all of them no-ops (they differ, and this
   docstring used to flatten all three into \"no-op\"):
   - :break-subs     - Selective sub breaking (not yet implemented) — PAUSES the
                       run (pause-with-context -> :tactic-paused, terminal)
   - :script         - Multi-step sequences (not yet implemented) — PAUSES too
   - :prep           - Pre-encounter actions (not yet implemented) — returns nil
                       and falls through to the next handler

   Also note :pause (and :default :pause) is NOT a stub: it works in V1."
  (:require [ai-card-actions :as actions]
            [ai-state :as ai-state]
            [ai-core :as core]))

;; ============================================================================
;; ICE & Breaker Inspection
;; ============================================================================

;; Use core/current-run-ice for ICE lookup (single source of truth)

(declare trojan-host-matches?)

(def host-only-breaker-titles #{"Botulus" "Slap Vandal" "Knight"})

(defn list-available-breakers
  "List all installed Runner icebreakers with their break abilities."
  [state]
  (let [programs (ai-state/runner-programs (:game-state state))
        ice (core/encountered-ice state)]
    (for [prog programs
          :when (or (nil? ice) (trojan-host-matches? prog ice))
          :when (or (some #{"Icebreaker"} (:subtypes prog))
                    (some core/break-ability? (:abilities prog)))]
      {:title (:title prog)
       :reference (core/installed-runner-reference prog (:game-state state))
       :strength (:current-strength prog)
       :abilities (map-indexed
                   (fn [idx ab]
                     {:index idx
                      :label (:label ab)
                      :playable (:playable ab)
                      :dynamic (:dynamic ab)})
                   (:abilities prog))})))

;; ============================================================================
;; ICE Type Matching
;; ============================================================================

(defn ice-primary-type
  "Get the primary ICE type (Barrier, Code Gate, Sentry) from ICE subtypes."
  [ice]
  (let [subtypes (set (:subtypes ice))]
    (cond
      (subtypes "Barrier") "Barrier"
      (subtypes "Code Gate") "Code Gate"
      (subtypes "Sentry") "Sentry"
      :else nil)))

(defn breaker-ice-type
  "Get the ICE type this breaker can break based on its subtypes."
  [breaker]
  (let [subtypes (set (:subtypes breaker))]
    (cond
      (subtypes "Fracter") "Barrier"
      (subtypes "Decoder") "Code Gate"
      (subtypes "Killer") "Sentry"
      (subtypes "AI") :ai  ; AI breakers can break any type
      :else nil)))

(defn trojan-host-matches?
  "Apply the host restrictions in the named break abilities."
  [breaker ice]
  (cond
    (= "Living Mural" (:title breaker))
    (= (take 2 (get-in breaker [:host :zone])) (take 2 (:zone ice)))

    (host-only-breaker-titles (:title breaker))
    (= (get-in breaker [:host :cid]) (:cid ice))

    :else true))

(defn breaker-matches-ice?
  "Check if breaker can break the ICE type."
  [breaker ice]
  (let [ice-type (ice-primary-type ice)
        breaker-type (breaker-ice-type breaker)]
    (and (trojan-host-matches? breaker ice)
         (or (= breaker-type :ai)
             (= breaker-type ice-type)
             ;; Botulus is Virus - Trojan, not subtype Icebreaker. Its
             ;; break-sub :req allows only the host ICE and ignores strength.
             (and (= "Botulus" (:title breaker))
                  (some core/break-ability? (:abilities breaker)))))))

;; ============================================================================
;; Ability Detection
;; ============================================================================

(defn find-pump-ability
  "Find a playable pump ability on the breaker. Returns {:index n :label s} or nil.
   Pump abilities typically have labels like '+X strength' or 'X[c]: +Y strength'."
  [breaker]
  (first
   (keep-indexed
    (fn [idx ab]
      (when (and (:playable ab)
                 (not (:dynamic ab))  ; Skip dynamic abilities
                 (let [label (str (:label ab))]
                   (or (clojure.string/includes? label "+1 strength")
                       (clojure.string/includes? label "+2 strength")
                       (clojure.string/includes? label "+3 strength")
                       (re-find #"\+\d+ strength" label))))
        {:index idx :label (:label ab)}))
    (:abilities breaker))))

(defn find-break-ability
  "Find a playable break ability on the breaker. Returns {:index n :label s} or nil.
   Break abilities typically have 'break' in the label."
  [breaker ice-type]
  (first
   (keep-indexed
    (fn [idx ab]
      (when (and (:playable ab)
                 (not (:dynamic ab))  ; Skip dynamic abilities
                 (let [label (clojure.string/lower-case (str (:label ab)))]
                   (and (clojure.string/includes? label "break")
                        ;; Check type matches if not AI
                        (or (= (breaker-ice-type breaker) :ai)
                            (clojure.string/includes? label (clojure.string/lower-case ice-type))
                            ;; Generic "break subroutine" labels
                            (and (clojure.string/includes? label "subroutine")
                                 (not (clojure.string/includes? label "barrier"))
                                 (not (clojure.string/includes? label "code gate"))
                                 (not (clojure.string/includes? label "sentry")))))))
        {:index idx :label (:label ab)}))
    (:abilities breaker))))

;; ============================================================================
;; Manual Pump + Break Fallback (when dynamic abilities unavailable)
;; ============================================================================

(defn try-manual-pump-and-break!
  "Fallback: manually pump breaker to match ICE strength, then break.
   Returns status map or nil if not possible."
  [state ice all-programs]
  (let [ice-title (:title ice "ICE")
        ice-type (ice-primary-type ice)
        ice-strength (or (:current-strength ice) (:strength ice) 0)
        ;; Find a compatible breaker
        compatible-breakers
        (filter #(breaker-matches-ice? % ice) all-programs)]
    (when (and ice-type (seq compatible-breakers))
      (let [;; Find a breaker that can pump to strength or is already strong enough
            usable-breaker
            (first
             (for [breaker compatible-breakers
                   :let [breaker-strength (or (:current-strength breaker) (:strength breaker) 0)
                         needs-pump? (and (some #{"Icebreaker"} (:subtypes breaker))
                                          (< breaker-strength ice-strength))
                         pump-ability (when needs-pump? (find-pump-ability breaker))
                         break-ability (find-break-ability breaker ice-type)]
                   ;; Must have break ability, and either strong enough or can pump
                   :when (and break-ability
                              (or (not needs-pump?) pump-ability))]
               {:breaker breaker
                :needs-pump? needs-pump?
                :pump-ability pump-ability
                :break-ability break-ability}))]
        (when usable-breaker
          (let [{:keys [breaker needs-pump? pump-ability break-ability]} usable-breaker
                breaker-name (core/installed-runner-reference breaker (:game-state state))]
            ;; Note: last-full-break-warning is reset by the caller on success
            (if needs-pump?
              ;; Need to pump first
              (do
                (println (format "🔧 Manual pump+break: %s needs pumping for %s" breaker-name ice-title))
                (println (format "   Using pump: %s (ability %d)" (:label pump-ability) (:index pump-ability)))
                (let [pump-result (actions/use-ability! breaker-name (:index pump-ability))]
                  (if (= :success (:status pump-result))
                    {:status :ability-used
                     :message (format "Pumped %s (may need more pumps, then break)" breaker-name)
                     :action :pump
                     :ice ice-title
                     :breaker breaker-name}
                    ;; Pump failed
                    nil)))
              ;; Strong enough, break directly
              (do
                (println (format "🔨 Manual break: %s breaking %s" breaker-name ice-title))
                (println (format "   Using: %s (ability %d)" (:label break-ability) (:index break-ability)))
                (let [break-result (actions/use-ability! breaker-name (:index break-ability))]
                  (if (= :success (:status break-result))
                    {:status :ability-used
                     :message (format "Broke subroutine on %s with %s" ice-title breaker-name)
                     :action :break
                     :ice ice-title
                     :breaker breaker-name}
                    ;; Break failed
                    nil))))))))))

;; ============================================================================
;; Tactic Execution
;; ============================================================================

(defn- pause-with-context
  "Return a pause status with rich context for manual intervention."
  [state ice-name reason]
  (let [ice (core/current-run-ice state)
        subroutines (:subroutines ice)
        ;; :fired subs are resolved, not pending — same predicate as the run
        ;; handlers (#99: counting them as unbroken misreads a resolved encounter)
        ;; NOT core/fireable-subs-of: this tactic is about BREAKING, and a
        ;; `:resolve false` sub is still breakable and can still be worth it
        ;; (Mass-Driver retriggers on a full break, and the engine's
        ;; all-subs-broken? counts it) — guest panel CRITICAL, round 4.
        unbroken (filter #(and (not (:broken %)) (not (:fired %))) subroutines)
        breakers (list-available-breakers state)
        credits (get-in state [:game-state :runner :credit])]
    (println (format "⏸️  Tactics pause: %s" reason))
    (println (format "   ICE: %s (strength %s, %d unbroken subs)"
                     ice-name
                     (or (:current-strength ice) "?")
                     (count unbroken)))
    (println (format "   Credits: %d" (or credits 0)))
    (when (seq breakers)
      (println "   Breakers:")
      (doseq [b breakers]
        (println (format "     - %s (str %s)" (:reference b) (:strength b)))))
    {:status :tactic-paused
     :reason reason
     :context {:ice-name ice-name
               :ice-strength (:current-strength ice)
               :unbroken-subs (mapv :label unbroken)
               :breakers breakers
               :credits credits}}))

(defn- resolve-tactic-card
  "Resolve an indexed card first; for a bare title, narrow by the host rule."
  [card-name state]
  (let [installed (ai-state/installed-runner-cards (:game-state state))
        {:keys [title explicit-index? index]} (core/parse-card-reference card-name)
        matches (filter #(= title (:title %)) installed)
        ice (core/encountered-ice state)
        card (if explicit-index?
               (core/find-installed-card card-name)
               (let [eligible (filter #(trojan-host-matches? % ice) matches)]
                 (when (= 1 (count eligible)) (first eligible))))]
    (cond
      (and card (trojan-host-matches? card ice)) {:card card}
      (and card (= "Living Mural" (:title card)))
      {:reason (str card-name " can break only ICE on its host server")}
      card {:reason (str card-name " can break only its host ICE")}
      (empty? matches) {:reason (str "Card not found installed: " card-name)}
      (and explicit-index? (>= index (count matches)))
      {:reason (str "No installed copy " card-name " (valid indices 0.." (dec (count matches)) ")")}
      explicit-index? {:reason (str "Card not found installed: " card-name)}
      (> (count (filter #(trojan-host-matches? % ice) matches)) 1)
      {:reason (str "Multiple copies of " title " can break here; name one by index [N]")}
      (= "Living Mural" title)
      {:reason (str card-name " can break only ICE on its host server")}
      :else {:reason (str card-name " can break only its host ICE")})))

(defn- execute-break-dynamic!
  "Execute a :break-dynamic tactic - find and use the card's full-break ability."
  [card-name state]
  (let [{target-prog :card reason :reason} (resolve-tactic-card card-name state)]
    (if-not target-prog
      {:status :tactic-failed
       :reason reason}
      ;; Find the dynamic break ability
      ;; NOTE: Dynamic abilities have playable=null, so don't require it
      (let [abilities (:abilities target-prog)
            break-ability (first
                           (keep-indexed
                            (fn [idx ab]
                              (when (and (:dynamic ab)
                                         (clojure.string/includes? (str (:dynamic ab)) "break"))
                                {:index idx :label (:label ab)}))
                            abilities))]
        (if-not break-ability
          {:status :tactic-failed
           :reason (format "%s has no playable break ability" card-name)}
          (do
            (println (format "🎯 Tactic: %s using %s" card-name (:label break-ability)))
            (let [result (actions/use-ability!
                           (core/installed-runner-reference target-prog (:game-state state))
                           (:index break-ability))]
              (if (= :success (:status result))
                {:status :action-taken
                 :action :tactic-executed
                 :card card-name
                 :ability (:label break-ability)}
                {:status :tactic-failed
                 :reason (format "Ability failed: %s" (:reason result))}))))))))

(defn- execute-tactic!
  "Dispatch and execute a tactic based on its :action type."
  [tactic ice-name state]
  (let [{:keys [card action]} tactic]
    (case action
      ;; V1 Actions
      :break-dynamic
      (execute-break-dynamic! card state)

      :use-ability
      (let [ability-index (:ability-index tactic)
            {target :card reason :reason} (resolve-tactic-card card state)]
        (if-not target
          {:status :tactic-failed :reason reason}
          (do
            (println (format "🎯 Tactic: %s ability %d" card ability-index))
            (let [result (actions/use-ability!
                           (core/installed-runner-reference target (:game-state state))
                           ability-index)]
              (if (= :success (:status result))
                {:status :action-taken :action :tactic-executed :card card}
                {:status :tactic-failed :reason (:reason result)})))))

      ;; V2 Stubs - recognized but not implemented
      :break-subs
      (do
        (println "⚠️  V2: :break-subs not yet implemented - pausing")
        (pause-with-context state ice-name ":break-subs requires V2"))

      :prep
      (do
        (println "⚠️  V2: :prep not yet implemented - continuing")
        nil)  ; Return nil to fall through

      :script
      (do
        (println "⚠️  V2: :script not yet implemented - pausing")
        (pause-with-context state ice-name ":script requires V2"))

      ;; Unknown action
      (do
        (println (format "⚠️  Unknown tactic action: %s" action))
        (pause-with-context state ice-name (format "Unknown action: %s" action))))))

;; ============================================================================
;; Handler (called from ai-runs handler chain)
;; ============================================================================

(defn handle-runner-tactics
  "Priority 2.35: Execute tactics during encounter-ice phase.
   Looks up ICE by name in tactics map, executes if found.
   Falls through to full-break handler if no tactics defined or no match."
  [{:keys [side run-phase state strategy]}]
  (when (and (= side "runner")
             ;; Same encounter authority as the rest of the run automation
             ;; (#160): gated on the phase string, a tactic configured for a
             ;; forced-encounter ICE was silently ignored (guest panel).
             (core/at-encounter? state run-phase)
             (:tactics strategy))
    (let [ice (core/encountered-ice state)
          ice-name (:title ice)]
      (when (core/encounter-ice-active? state ice)
        (let [tactics-map (:tactics strategy)
              tactic (or (get tactics-map ice-name)
                         (get tactics-map :default))]
          (cond
            ;; Explicit :pause or no tactic and no :default
            (= tactic :pause)
            (pause-with-context state ice-name "Tactic specifies :pause")

            ;; No tactic defined - fall through to full-break or manual
            (nil? tactic)
            nil

            ;; Execute the tactic
            :else
            (execute-tactic! tactic ice-name state)))))))
