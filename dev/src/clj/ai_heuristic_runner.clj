(ns ai-heuristic-runner
  "Heuristic-based Runner AI for tutorial decks.
   Implements the 'Runner Playbook' from 20260111_problemset.txt.

   Core Strategy:
   1. Economy & Rig Building (Early Game)
   2. Efficient Breaking (Click through Bioroids)
   3. Targeted Pressure (R&D > Remote if scoring > HQ)
   4. Damage Safety (Draw before running against damage)

   Decision Priority:
   1. Flatline prevention (Draw if hand < expected damage + 2)
   2. Contest Remote (if 3+ advancements and affordable)
   3. Build Economy (if poor < 5)
   4. Build Rig (Install needed breakers)
   5. Pressure Centrals (R&D default)
   6. Draw (if looking for pieces)"
  (:require [ai-state :as state]
            [ai-card-actions :as cards]
            [ai-basic-actions :as actions]
            [ai-prompts :as prompts]
            [ai-runs :as runs]
            [ai-connection :as conn]
            [ai-loop-sync :as loop-sync]
            [ai-stall :as stall]
            [clojure.string :as str]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def config
  {:min-credits 5          ; Keep buffer for Sure Gamble / interaction
   :safe-hand-size 4       ; Minimum hand size against damage decks
   :log-decisions true})

(defn log-decision [& args]
  (when (:log-decisions config)
    (apply println "🤖" args)))

;; ============================================================================
;; State Queries
;; ============================================================================

(defn my-credits [] (state/runner-credits))
(defn my-clicks [] (state/runner-clicks))
(defn my-hand [] (state/runner-hand))
(defn my-rig [] 
  (let [rig (state/runner-rig)]
    (apply concat (vals rig))))

(defn cards-in-hand-by-type [type]
  (filter #(= type (:type %)) (my-hand)))

(defn cards-in-rig-by-type [type]
  (filter #(= type (:type %)) (my-rig)))

(defn has-breaker-for? 
  "Do we have a breaker for this ICE type installed?"
  [ice-type]
  (let [breakers (cards-in-rig-by-type "Program")]
    (some (fn [card]
            (let [subtypes (:subtypes card)]
              (cond
                (= ice-type "Barrier") (some #{"Fracter"} subtypes)
                (= ice-type "Code Gate") (some #{"Decoder"} subtypes)
                (= ice-type "Sentry") (some #{"Killer"} subtypes)
                :else false)))
          breakers)))

(defn missing-breakers []
  (let [needed ["Barrier" "Code Gate" "Sentry"]
        missing (filter #(not (has-breaker-for? %)) needed)]
    missing))

(defn breaker-in-hand-for? [ice-type]
  (let [programs (cards-in-hand-by-type "Program")]
    (some (fn [card]
            (let [subtypes (:subtypes card)]
              (when (cond
                      (= ice-type "Barrier") (some #{"Fracter"} subtypes)
                      (= ice-type "Code Gate") (some #{"Decoder"} subtypes)
                      (= ice-type "Sentry") (some #{"Killer"} subtypes)
                      :else false)
                card)))
          programs)))

(defn economy-cards-in-hand []
  (filter (fn [card]
            (let [title (:title card)
                  text (:text card)]
              (or (and (= "Event" (:type card)) (str/includes? title "Gamble"))
                  (and (= "Event" (:type card)) (str/includes? title "Creative Commission"))
                  (and (= "Event" (:type card)) (str/includes? (str text) "Gain")) ; Generic gain events
                  (and (= "Resource" (:type card)) (str/includes? title "Daily Casts"))
                  (and (= "Resource" (:type card)) (str/includes? title "Contract"))
                  (and (= "Resource" (:type card)) (str/includes? title "Distributor")))))
          (my-hand)))

;; ============================================================================
;; Board Analysis
;; ============================================================================

(defn get-rezzed-ice [server-key]
  (filter :rezzed (state/server-ice server-key)))

(defn server-ice-types [server-key]
  "Returns known ICE types for rezzed ICE on a server."
  (let [ice (get-rezzed-ice server-key)]
    (map (fn [card]
           (let [subtypes (:subtypes card)]
             (cond
               (some #{"Barrier"} subtypes) "Barrier"
               (some #{"Code Gate"} subtypes) "Code Gate"
               (some #{"Sentry"} subtypes) "Sentry"
               :else "Unknown")))
         ice)))

(defn can-break-server? [server-key]
  "Check if runner can safely run this server.
   For unrezzed ICE, assumes worst case (could be any type).
   Returns true only if we have breakers for all rezzed types
   AND full rig for any unrezzed ICE."
  (let [all-ice (state/server-ice server-key)
        rezzed-ice (filter :rezzed all-ice)
        unrezzed-ice (filter #(not (:rezzed %)) all-ice)
        rezzed-types (server-ice-types server-key)
        missing (missing-breakers)]
    (if (seq unrezzed-ice)
      ;; Unrezzed ICE present - need full rig (no missing breakers)
      (empty? missing)
      ;; All rezzed - just check the known types
      (every? (fn [t] (not (some #{t} missing))) rezzed-types))))

(defn remote-advancements [server-key]
  (let [content (state/server-cards server-key)
        card (first content)] ; Usually only one card in root
    (or (:advance-counter card) 0)))

(defn dangerous-remote?
  "Find a remote with 3+ advancements (likely agenda)"
  []
  (let [remotes (keys (state/corp-servers))]
    (some (fn [k]
            (when (and (str/starts-with? (name k) "remote")
                       (>= (remote-advancements k) 3))
              k))
          remotes)))

;; ============================================================================
;; Decision Logic
;; ============================================================================

(defn decide-action*
  "Pure decision core for the Runner heuristic. Takes a context map of
   pre-computed facts (so it is testable without touching global state) and
   returns a decision map, or nil to end the turn.

   ctx keys:
     :clicks :credits :hand-size  - counts
     :missing                     - seq of missing breaker types
     :threat                      - dangerous-remote server key, or nil
     :can-break-threat?           - can we break into :threat
     :can-break-rd?               - can we break R&D
     :stack-empty?                - true when the deck has no cards to draw
     :econ-card                   - first economy card in hand, or nil
     :installable-breaker         - a breaker card in hand for a missing type

   Draw decisions are guarded by (not stack-empty?): drawing from an empty
   stack is impossible, so an unguarded :draw makes the autonomous loop
   re-decide the same impossible action every tick - clicks never drop, the
   issue #19 own-turn spin backstop fires, and the game stalls. (Live repro:
   self-play T30, runner at 6/7 with a full rig and an empty stack chose
   SAFETY-draw forever until the backstop bailed.)"
  [{:keys [clicks credits hand-size missing threat
           can-break-threat? can-break-rd? stack-empty?
           econ-card installable-breaker]}]
  (when (pos? clicks)
    (cond
      ;; 1. Safety First: Draw if low on cards (vs damage) - but only when the
      ;;    stack actually has cards to draw.
      (and (< hand-size (:safe-hand-size config)) (not stack-empty?))
      (do
        (log-decision "SAFETY: Drawing up to safe hand size")
        {:action :draw})

      ;; 2. Contest Dangerous Remote
      (and threat can-break-threat?)
      (let [server-name (str/replace (name threat) "remote" "Server ")]
        (log-decision "THREAT: Contesting" server-name "with 3+ advancements")
        {:action :run :args {:server server-name}})

      ;; 3. Economy (if poor)
      (< credits (:min-credits config))
      (if (and econ-card (>= credits (:cost econ-card)))
        (do
          (log-decision "ECONOMY: Playing" (:title econ-card))
          {:action :play :args {:card-name (:title econ-card)}})
        (do
          (log-decision "ECONOMY: Clicking for credit (too poor for cards)")
          {:action :credit}))

      ;; 4. Install Breakers (if in hand)
      (and (seq missing) installable-breaker)
      (if (>= credits (:cost installable-breaker))
        (do
          (log-decision "RIG: Installing" (:title installable-breaker))
          {:action :install :args {:card-name (:title installable-breaker)}})
        (do
          (log-decision "RIG: Need credits for" (:title installable-breaker))
          {:action :credit}))

      ;; 5. Pressure R&D (Default Win Con)
      can-break-rd?
      (do
        (log-decision "PRESSURE: Running R&D")
        {:action :run :args {:server "R&D"}})

      ;; 6. Dig for Breakers (if missing and we can still draw)
      (and (seq missing) (not stack-empty?))
      (do
        (log-decision "RIG: Digging for breakers")
        {:action :draw})

      ;; 7. Default: Draw for options if we can; otherwise nothing useful is
      ;;    left this turn (empty stack, can't break R&D) - end the turn rather
      ;;    than spin on an impossible draw.
      (not stack-empty?)
      (do
        (log-decision "DEFAULT: Drawing for options")
        {:action :draw})

      :else
      (do
        (log-decision "DEFAULT: Nothing useful (empty stack) - ending turn")
        nil))))

(defn decide-action
  "Gather live state and delegate to the pure decide-action* core."
  []
  (let [missing (missing-breakers)
        threat  (dangerous-remote?)]
    (decide-action*
      {:clicks              (my-clicks)
       :credits             (my-credits)
       :hand-size           (count (my-hand))
       :missing             missing
       :threat              threat
       :can-break-threat?   (boolean (when threat (can-break-server? threat)))
       :can-break-rd?       (can-break-server? :rd)
       ;; Use :deck-count, NOT (runner-deck): the runner's own deck contents are
       ;; hidden, so (runner-deck) is always empty on the wire and would make
       ;; stack-empty? wrongly true every turn (runner would never draw).
       :stack-empty?        (zero? (state/runner-deck-count))
       :econ-card           (first (economy-cards-in-hand))
       :installable-breaker (some #(breaker-in-hand-for? %) missing)})))

;; ============================================================================
;; Execution & Prompt Handling
;; ============================================================================

(defn execute-decision [{:keys [action args]}]
  (case action
    :run     (runs/run! (:server args) "--full-break")
    :play    (cards/play-card! (:card-name args))
    :install (cards/install-card! (:card-name args))
    :credit  (actions/take-credit!)
    :draw    (actions/draw-card!)
    :end-turn (actions/end-turn!)
    (println "❌ Unknown action:" action)))

(defn handle-prompt-if-needed []
  (when-let [prompt (state/get-prompt)]
    (let [msg (:msg prompt)
          prompt-type (:prompt-type prompt)]
      (cond
        ;; Ignore Run and Waiting prompts (handled by runs/continue-run! or just waiting)
        (or (= prompt-type "run") 
            (= prompt-type "waiting"))
        false

        ;; Brân 1.0 click ability (Runner Playbook: "Almost always bypass")
        (str/includes? msg "Lose [Click]")
        (do
          (log-decision "BIROID: Clicking through (Bypass)")
          (prompts/choose-by-value! "Yes")
          true)
        
        ;; Discard
        (str/includes? msg "Discard")
        ;; #127: propagate, don't hardcode true. discard-to-hand-size! returns
        ;; nil when it DECLINED (unreadable board) and a count otherwise — and 0
        ;; is truthy in Clojure, so this reports "handled" for a real no-op and
        ;; "not handled" only for a decline. Reporting a decline as handled sent
        ;; this loop straight back to the same unresolved prompt, forever.
        (some? (prompts/discard-to-hand-size!))

        ;; Jack out decision
        (str/includes? msg "Jack out")
        (do
          (log-decision "DECISION: Staying in run")
          (prompts/choose-by-value! "No") ; Usually stay unless critical
          true)

        ;; Access decision (e.g. steal/trash)
        (str/includes? msg "You accessed")
        (do
          (log-decision "ACCESS: Deciding on accessed card")
          ;; Default to first option (often Steal or Pay to Trash)
          ;; TODO: Add smarter trash logic based on credits/card type
          (prompts/choose-by-index! 0)
          true)

        :else
        (do
          (log-decision "PROMPT: Choosing first option for" msg)
          (prompts/choose-by-index! 0)
          true)))))

;; ============================================================================
;; Main Loop
;; ============================================================================

(defn play-turn []
  (println "\n" (str/join "" (repeat 50 "-")))
  (println "🏃 HEURISTIC RUNNER - Thinking...")
  (println (str/join "" (repeat 50 "-")))

  (when (handle-prompt-if-needed)
    (Thread/sleep 500))

  (if-let [decision (decide-action)]
    (execute-decision decision)
    (actions/smart-end-turn!)))

(defn run-result->next-action
  "Pure decision: given a continue-run! result map, decide what the autonomous
   Runner should do next. Returns one of :handle-prompt, :tank, :continue.

   The autonomous loop has no human to resolve a 'can't break, you decide'
   pause, so it must convert that into a concrete action. Mid-encounter the
   Runner can't jack out, so the only way to make progress is to let the subs
   fire (tank). Two handler statuses mean the same 'no human to decide' thing:
     - :paused-cannot-break   - full-break path, unbreakable/unaffordable ICE
     - :fire-decision-required - NOT-full-break path (handle-runner-encounter-ice),
                                 rezzed ICE with unbroken subs and no tank auth
   Both must map to :tank, or the loop falls to :continue and spins forever."
  [result]
  (case (:status result)
    :decision-required      :handle-prompt
    :paused-cannot-break    :tank
    :fire-decision-required :tank
    :continue))

(defn- player-names
  "[my-name opp-name] from the game-state user maps (for stall nudges)."
  []
  (let [gs (:game-state @state/client-state)]
    [(get-in gs [:runner :user :username])
     (get-in gs [:corp :user :username])]))

(defn- send-stall-nudge!
  "On-stall nudge: a readable 'your move?' chat for humans/LLMs watching, plus a
   bare 'ping' to wake a wait-for-relevant-diff-blocked opponent via the existing
   ping channel (ai-core/ping-message?)."
  [stall-key]
  (let [[me opp] (player-names)]
    (println (str "⏳ STALL: " (stall/nudge-text me opp stall-key)))
    (conn/send-chat! (stall/nudge-text me opp stall-key))
    (conn/send-ping!)))

(defn loop!
  "Autonomous Runner loop. Options mirror ai-heuristic-corp/start-autonomous!:
     :patient?   - run-wait bail uses a generous WALL-CLOCK window (:patient-ms)
                   instead of the tight iteration count, so a SLOW (thinking-model)
                   Corp opponent isn't killed mid-decision. Own-turn spin bail
                   (catches OUR bugs) stays tight either way.
     :patient-ms - patience window in ms (default stall/default-patient-bail-ms)."
  [& {:keys [patient? patient-ms]
      :or {patient? false patient-ms stall/default-patient-bail-ms}}]
  (println "🏃 HEURISTIC RUNNER - Starting autonomous loop"
           (if patient? (str "(PATIENT: run-wait bail after "
                             (long (/ patient-ms 1000)) "s wall-clock)") ""))
  (loop [stall {:key nil :count 0}
         spin {:key nil :count 0}
         resync 0]
    (let [{:keys [continue? run-status resync-next]}
          (try
            ;; #144: reach the SAME authority the CLI gate uses before acting.
            ;; Cheap when healthy (no round trip while a board is cached), it
            ;; REPAIRS a boardless seat, and it is bounded — a seat that cannot
            ;; be repaired stops with a diagnostic instead of refusing forever.
            ;; Sits ahead of the run/prompt/turn priorities on purpose: every one
            ;; of them reads the board, so none of them is meaningful without one.
            (let [{:keys [action attempts]}
                  (loop-sync/report! "runner" (loop-sync/ensure-board! resync))]
              (if (not= :act action)
                {:continue? (not= :stop action) :run-status nil :resync-next attempts}
                (let [game-state @state/client-state
                      winner (get-in game-state [:game-state :winner])]
                  (if winner
                    (do (println "Runner Loop Ends - Winner:" winner) {:continue? false})
                    (let [my-turn? (= "runner" (:active-player (:game-state game-state)))
                          in-run? (state/current-run)]

                      ;; Priority 1: Handle active runs FIRST (runs create prompts)
                      ;; continue-run! handles run-related prompts internally
                      (if in-run?
                        (do
                          (println "🏃 HEURISTIC RUNNER - In run, continuing...")
                          (let [result (runs/continue-run!)
                                post (case (run-result->next-action result)
                                       :handle-prompt
                                       (do
                                         (println "🏃 HEURISTIC RUNNER - Decision required during run, handling prompt...")
                                         (handle-prompt-if-needed)
                                         nil)

                                       :tank
                                       ;; Can't break and no human to decide. Authorize tank
                                       ;; (let subs fire) on this ICE and continue; the handler
                                       ;; signals the Corp to fire, resolving the encounter.
                                       (let [ice (:ice result)]
                                         (println (format "🏃 HEURISTIC RUNNER - Can't break %s, authorizing tank (let subs fire)" ice))
                                         (runs/set-strategy!
                                           (update (runs/get-strategy) :tank (fnil conj #{}) ice))
                                         (runs/continue-run!))

                                       ;; :continue - nothing special this tick
                                       nil)]
                            (Thread/sleep 500)
                            ;; Surface the run status so the stall tracker can see if
                            ;; we're stuck waiting on the Corp. After a tank authorization
                            ;; `post` holds the follow-up continue-run! result (its
                            ;; :waiting-for-corp-fire), so the stall clock starts on the
                            ;; tick that signalled - not one poll later.
                            {:continue? true :run-status (:status (if (map? post) post result))}))

                        ;; Priority 2: Handle non-run prompts
                        (do
                          (when (handle-prompt-if-needed)
                            (Thread/sleep 500))

                          ;; Priority 3: Auto-start turn if needed
                          (let [start-check (actions/can-start-turn?)]
                            (when (:can-start start-check)
                              (actions/start-turn!)
                              (Thread/sleep 500)))

                          ;; Priority 4: Take actions if it's our turn
                          (when (and my-turn? (not (state/get-prompt)))
                            (if (pos? (my-clicks))
                              (play-turn)
                              (do
                                (println "🏃 HEURISTIC RUNNER - 0 clicks, ending turn")
                                (actions/smart-end-turn!))))
                          ;; Not an in-run wait: turn-alternation idling is normal, so
                          ;; nil run-status keeps the stall tracker reset.
                          {:continue? true :run-status nil})))))))
            (catch Exception e
              (println "❌ RUNNER ERROR:" (.getMessage e))
              (.printStackTrace e)
              (Thread/sleep 5000)
              ;; An exception is not a failed resync — carry the count, don't spend it.
              {:continue? true :run-status nil :resync-next resync}))

          ;; Stall backstop: track 'same opponent-wait for N ticks' and nudge /
          ;; bail. Only in-run opponent-waits qualify (run-status nil resets it).
          now (System/currentTimeMillis)
          stall-k (stall/stall-key run-status (state/current-run))
          next-stall (stall/update-tracker stall stall-k now)
          action (stall/stall-action (:count next-stall) stall/default-thresholds)
          ;; Run-wait bail: patient mode (slow/model opponent) uses a generous
          ;; wall-clock window; default uses the tight iteration-count bail for
          ;; fast self-play. The nudge still fires on the iteration count. The
          ;; patient window is scoped to genuine opponent-act waits — monitor-run!'s
          ;; own :stuck/:max-iterations "wedged" verdicts keep the tight bail.
          run-bail? (if (and patient? (stall/slow-opponent-wait? run-status))
                      (stall/patient-bail? next-stall now patient-ms)
                      (= action :bail))
          ;; Catch-all: own-turn self-blocked spin (issue #19). The run-side
          ;; tracker only sees opponent-waits during a run; this catches a loop
          ;; re-emitting a rejected own-turn action (no run, no nudge helps).
          ;; Stays tight even in patient mode — it catches OUR bugs, not opponent speed.
          spin-k (stall/own-turn-key (:game-state @state/client-state) "runner")
          next-spin (stall/update-tracker spin spin-k)
          spin-bail? (stall/own-turn-spinning? (:count next-spin))
          bail? (or run-bail? spin-bail?)]

      (when (= action :nudge)
        (send-stall-nudge! stall-k))
      (when bail?
        (let [log (get-in @state/client-state [:game-state :log])]
          (println (if spin-bail?
                     (stall/own-turn-diagnostic (first (player-names)) spin-k (:count next-spin) log)
                     (stall/diagnostic (first (player-names)) stall-k (:count next-stall) log)))
          (println "🛑 Runner loop stopping (stall backstop). Inspect state / restart with bot-loop.")))

      (when (and continue? (not bail?))
        (Thread/sleep 500)
        (recur next-stall next-spin (or resync-next 0))))))

;; ============================================================================
;; Turn Driver + Status (send_command parity with ai-heuristic-corp)
;; ============================================================================

(defn play-full-turn
  "Play a full Runner turn until no clicks remain.
   Mirrors ai-heuristic-corp/play-full-turn: ensure turn started, loop actions,
   handle EOT prompts (e.g. discard), then end turn.
   Note: runs consume the click inside execute-decision, so the loop naturally
   advances as clicks drop."
  []
  (println "\n" (str/join "" (repeat 60 "=")))
  (println "🏃 HEURISTIC RUNNER - Starting Full Turn")
  (println (str/join "" (repeat 60 "=")))

  (actions/ensure-turn-started!)

  (loop [actions-taken 0]
    (let [clicks (my-clicks)]
      (if (and clicks (pos? clicks) (not (state/get-prompt)))
        (do
          (println (str "🏃 Loop: Actions taken " actions-taken " | Clicks remaining: " clicks))
          (play-turn)
          (Thread/sleep 500)
          (recur (inc actions-taken)))
        (do
          (println "\n" (str/join "" (repeat 60 "=")))
          (println (str "🏃 Turn complete. Took " actions-taken " actions."))
          (println (str/join "" (repeat 60 "=")))

          ;; Handle any EOT prompts (like discard to hand size)
          (loop [prompts-handled 0]
            (if (handle-prompt-if-needed)
              (do
                (Thread/sleep 300)
                (recur (inc prompts-handled)))
              (when (pos? prompts-handled)
                (println (str "🏃 Handled " prompts-handled " EOT prompt(s)")))))

          (actions/smart-end-turn!)
          {:actions-taken actions-taken})))))

(defn status
  "Show current decision-relevant Runner state."
  []
  (println "\n🏃 HEURISTIC RUNNER STATUS")
  (println (str "Credits: " (my-credits) " | Clicks: " (my-clicks)
                " | Hand: " (count (my-hand)) " cards"))
  (println (str "Missing breakers: " (or (seq (missing-breakers)) "none")))
  (let [run (state/current-run)]
    (println (str "Active run: " (if run (or (:server run) run) "none"))))
  (let [winner (get-in @state/client-state [:game-state :winner])]
    (when winner (println (str "🏁 Winner: " winner)))))
