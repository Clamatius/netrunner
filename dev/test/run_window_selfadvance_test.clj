(ns run-window-selfadvance-test
  "Tests for issue #31 residual: the both-must-pass run windows that STILL stall
   after #62 shipped the initiation auto-pass.

   Marquee game d6962df4 (2026-07-12, Opus Corp vs GPT-5.5 Runner) was decided by
   this: 5 jack-outs, 1 encounter, 1 rez in the whole game. Every Runner run died
   at a Corp rez window nobody was home to answer.

   Three fixes under test:
   A. PARK MODE — `monitor-run --persistent` must be able to wait for a run to
      START (own the opponent's whole turn), not return :no-run and leave the
      window unattended.
   B. SELF-ADVANCE (§1) — when the opponent PROVABLY has no decision at a
      both-pass window (board-derivable, no hidden info), the side holding the
      window advances it rather than stalling. Critically, it must NOT fire when
      the opponent does have a real decision (unrezzed ICE = a live rez choice).
   C. JACK-OUT SMELL — the client must stop COACHING jack-out as the stall escape
      hatch. Jack-out is a netrunner smell: the only legit tactical cases are
      misjudging entry cost and Karuna's jack-out subroutine. Our own hint text
      told GPT-5.5 to bail, and it threw away a broken Bran (8 credits) and the
      game."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [test-helpers :refer :all]
            [ai-runs :as runs]
            [ai-display :as display]
            [ai-state :as state]
            [ai-websocket-client-v2 :as ws]))

;; =============================================================================
;; B. Board-derivable "does the OPPONENT hold a real decision at this window?"
;; =============================================================================

(defn- runner-state
  "Runner-side client state at a run window."
  [& {:keys [phase position no-action ices content prompt log]
      :or {phase "approach-ice" position 1 ices [] content [] log []}}]
  (mock-client-state
   :side "runner"
   :game-state
   {:run {:phase phase
          :position position
          :no-action no-action
          :server [:remote1]}
    :log log
    :runner {:prompt-state prompt}
    :corp {:prompt-state nil
           :servers {:remote1 {:ices ices :content content}}}}))

(deftest approach-ice-unrezzed-ice-is-a-real-corp-decision
  (testing "Corp may rez the approached ICE -> opponent HAS a decision, never skip it"
    (let [st (runner-state :phase "approach-ice" :position 1
                           :ices [{:cid 1 :title "Whitespace" :rezzed false}])]
      (is (true? (runs/opponent-has-run-decision? st "runner" "approach-ice"))
          "Unrezzed current ICE = live Corp rez choice"))))

(deftest approach-ice-rezzed-ice-is-not-a-decision
  (testing "Already-rezzed ICE -> Corp has no rez choice here -> window is skippable"
    (let [st (runner-state :phase "approach-ice" :position 1
                           :ices [{:cid 1 :title "Bran 1.0" :rezzed true}])]
      (is (false? (runs/opponent-has-run-decision? st "runner" "approach-ice"))))))

(deftest movement-unrezzed-upgrade-is-a-real-corp-decision
  (testing "Unrezzed card in the attacked server root = a live upgrade rez choice"
    (let [st (runner-state :phase "movement" :position 0
                           :content [{:cid 9 :title "Manegarm Skunkworks" :rezzed false}])]
      (is (true? (runs/opponent-has-run-decision? st "runner" "movement"))))))

(deftest movement-empty-root-is-not-a-decision
  (testing "No cards in the server root -> nothing for Corp to rez -> skippable"
    (let [st (runner-state :phase "movement" :position 0 :content [])]
      (is (false? (runs/opponent-has-run-decision? st "runner" "movement"))))))

(deftest movement-all-rezzed-root-is-not-a-decision
  (testing "Root cards already rezzed -> no pending rez choice"
    (let [st (runner-state :phase "movement" :position 0
                           :content [{:cid 9 :title "Manegarm Skunkworks" :rezzed true}])]
      (is (false? (runs/opponent-has-run-decision? st "runner" "movement"))))))

(deftest corp-side-never-self-advances
  (testing "As Corp, the opponent is the RUNNER, who always has options (jack out,
            break, abilities). Not board-derivable -> stay conservative."
    (let [st (mock-client-state
              :side "corp"
              :game-state
              {:run {:phase "approach-ice" :position 1 :no-action "corp"
                     :server [:remote1]}
               :corp {:prompt-state nil
                      :servers {:remote1 {:ices [{:cid 1 :title "Bran 1.0" :rezzed true}]}}}})]
      (is (true? (runs/opponent-has-run-decision? st "corp" "approach-ice"))
          "Corp must never self-advance a window on the Runner's behalf"))))

;; =============================================================================
;; B. The self-advance handler
;; =============================================================================

(deftest self-advance-fires-only-after-the-grace-period
  (testing "Stall-BREAKER, not window-skipper: the Corp gets self-advance-grace-ms
            to answer. Present Corp answers in ms (its monitor is a loop); only an
            ABANDONED window is advanced. First sight must NOT advance."
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (runs/reset-window-grace!)
        (let [st (runner-state :phase "approach-ice" :position 1 :no-action "runner"
                               :ices [{:cid 1 :title "Bran 1.0" :rezzed true}])
              ctx {:run-phase "approach-ice" :gameid "g1" :side "runner"
                   :state st :my-prompt nil}
              first-look (runs/handle-stalled-window-self-advance ctx)]
          (is (nil? first-look)
              "Must give the Corp its window before advancing")
          (is (empty? @sent))
          ;; …but a window still unanswered after the grace is abandoned.
          (with-redefs [runs/self-advance-grace-ms 0]
            (let [result (runs/handle-stalled-window-self-advance ctx)]
              (is (= :action-taken (:status result)) "Should advance the abandoned window")
              (is (= 1 (count @sent)) "Should send exactly one continue")
              (is (= "continue" (:command (first @sent)))))))))))

(deftest self-advance-NEVER-skips-a-live-rez-decision
  (testing "SAFETY: unrezzed ICE = the Corp's rez decision. Self-advancing here
            would be the blunt corp-auto-no-action bug. Must return nil EVEN when
            the window has been stalled long past the grace period."
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)
                    runs/self-advance-grace-ms 0]
        (let [st (runner-state :phase "approach-ice" :position 1 :no-action "runner"
                               :ices [{:cid 1 :title "Whitespace" :rezzed false}])
              result (runs/handle-stalled-window-self-advance
                      {:run-phase "approach-ice" :gameid "g1" :side "runner"
                       :state st :my-prompt nil})]
          (is (nil? result) "Must NOT skip the Corp's live rez decision")
          (is (empty? @sent) "Must not send a continue"))))))

(deftest self-advance-requires-that-i-already-passed
  (testing "Fresh window (no-action nil) -> normal auto-continue owns the first
            pass; self-advance must not double-continue."
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)
                    runs/self-advance-grace-ms 0]
        (let [st (runner-state :phase "approach-ice" :position 1 :no-action nil
                               :ices [{:cid 1 :title "Bran 1.0" :rezzed true}])
              result (runs/handle-stalled-window-self-advance
                      {:run-phase "approach-ice" :gameid "g1" :side "runner"
                       :state st :my-prompt nil})]
          (is (nil? result))
          (is (empty? @sent)))))))

(deftest self-advance-yields-to-my-own-real-decision
  (testing "If I hold a real prompt, that must be surfaced, not auto-passed"
    (let [prompt {:prompt-type "select" :msg "Break subroutine?"
                  :choices [{:value "Yes"} {:value "No"}]}
          st (runner-state :phase "approach-ice" :position 1 :no-action "runner"
                           :ices [{:cid 1 :title "Bran 1.0" :rezzed true}]
                           :prompt prompt)]
      (with-redefs [runs/self-advance-grace-ms 0]
        (let [result (runs/handle-stalled-window-self-advance
                      {:run-phase "approach-ice" :gameid "g1" :side "runner"
                       :state st :my-prompt prompt})]
          (is (nil? result)))))))

;; =============================================================================
;; B. Integration through continue-run! — the actual marquee stall
;; =============================================================================

(deftest continue-run-advances-instead-of-stalling-at-no-decision-window
  (testing "REGRESSION (marquee d6962df4): Runner passed, ICE rezzed, Corp silent.
            Old behavior: :waiting-for-opponent forever -> jack-out -> lose."
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)
                    runs/self-advance-grace-ms 0]
        (with-mock-state
          (runner-state :phase "approach-ice" :position 1 :no-action "runner"
                        :ices [{:cid 1 :title "Bran 1.0" :rezzed true}])
          (let [result (runs/continue-run!)]
            (is (= :action-taken (:status result))
                "Must advance, not stall waiting on a Corp with nothing to decide")))))))

(deftest continue-run-still-waits-when-corp-genuinely-must-rez
  (testing "SAFETY: unrezzed ICE -> Corp is genuinely on the clock. Keep waiting,
            and — the part that actually matters — send NO continue."
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)
                    runs/self-advance-grace-ms 0]
        (with-mock-state
          (runner-state :phase "approach-ice" :position 1 :no-action "runner"
                        :ices [{:cid 1 :title "Whitespace" :rezzed false}])
          (let [result (runs/continue-run!)]
            (is (contains? #{:waiting-for-corp-rez :waiting-for-opponent
                             :waiting-for-opponent-paid-abilities}
                           (:status result))
                "Must still wait for a real Corp rez decision")
            (is (empty? @sent)
                "Must NOT send a continue — that would skip the Corp's rez window")))))))

;; --- F2: the predicate must fail CLOSED on "I can't see it" -------------------

(deftest predicate-fails-closed-when-ice-not-visible
  (testing "current-run-ice returns nil for out-of-bounds position / missing ices —
            i.e. for every state where we CANNOT SEE the approached ICE. Folding
            that into 'no decision' would skip a live rez window on a wire
            transient. Unknown must mean WAIT."
    ;; position 3 but only 1 ICE on the server -> current-run-ice = nil
    (let [st (runner-state :phase "approach-ice" :position 3 :no-action "runner"
                           :ices [{:cid 1 :title "Whitespace" :rezzed false}])]
      (is (true? (runs/opponent-has-run-decision? st "runner" "approach-ice"))
          "Unknown ICE must be treated as a possible Corp decision"))))

(deftest predicate-fails-closed-when-server-not-resolvable
  (testing "If we cannot even resolve the attacked server, we know nothing — a
            lookup miss must not read as 'the root is empty, skip the Corp'."
    (let [st (mock-client-state
              :side "runner"
              :game-state
              {:run {:phase "movement" :position 0 :no-action "runner"
                     :server [:remote99]}          ; server not in :servers
               :runner {:prompt-state nil}
               :corp {:prompt-state nil :servers {:remote1 {:ices [] :content []}}}})]
      (is (true? (runs/opponent-has-run-decision? st "runner" "movement"))
          "Unresolvable server must be treated as a possible Corp decision"))))

(deftest self-advance-does-not-fire-when-ice-not-visible
  (testing "End to end: the fail-closed predicate must stop the handler"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)
                    runs/self-advance-grace-ms 0]
        (let [st (runner-state :phase "approach-ice" :position 3 :no-action "runner"
                               :ices [{:cid 1 :title "Whitespace" :rezzed false}])
              result (runs/handle-stalled-window-self-advance
                      {:run-phase "approach-ice" :gameid "g1" :side "runner"
                       :state st :my-prompt nil})]
          (is (nil? result))
          (is (empty? @sent)))))))

;; =============================================================================
;; A. Park mode — the Corp must be able to wait for a run to START
;; =============================================================================

(deftest park-wakes-on-run-start
  (testing "A run is active -> stop parking, go monitor it"
    (let [st (mock-client-state
              :side "corp"
              :game-state {:run {:phase "initiation" :server [:hq]}
                           :active-player "runner"})]
      (is (= :run (runs/park-wake-reason st "corp"))))))

(deftest park-keeps-waiting-when-no-run-and-opponents-turn
  (testing "THE BUG: no run yet, still the Runner's turn -> PARK (stay home at the
            window). Old behavior returned :no-run and abandoned the post."
    (let [st (mock-client-state
              :side "corp"
              :game-state {:run nil :active-player "runner"
                           :turn 3 :corp {:click 0} :runner {:click 2}})]
      (is (= :park (runs/park-wake-reason st "corp"))))))

(deftest park-returns-on-my-turn
  (testing "It IS the Corp's live turn (active + clicks in hand) -> hand control
            back to the seat. (The turn-boundary variants — opponent ended, or
            AWAITING-START — are covered by the dedicated tests below.)"
    (let [st (mock-client-state
              :side "corp"
              :game-state {:run nil :active-player "corp"
                           :turn 3 :corp {:click 2} :runner {:click 0}})]
      (is (= :my-turn (runs/park-wake-reason st "corp"))))))

(deftest park-wakes-on-a-corp-prompt-with-no-run
  (testing "F1 (CRITICAL): the Corp can be prompted on the RUNNER'S turn with NO
            RUN ACTIVE — Wildcat Strike and ~30 other Runner cards carry
            :player :corp. The flow park replaced (sitting in `wait`) woke on
            :has-prompt. A prompt-blind park sleeps through it while the Runner is
            hard-blocked, times out, and re-parks: an unbreakable deadlock."
    (let [st (mock-client-state
              :side "corp"
              :game-state
              {:run nil
               :active-player "runner"
               :corp {:prompt-state {:msg "Wildcat Strike: Corp chooses"
                                     :prompt-type "choice"
                                     :choices [{:value "Runner draws 4"}
                                               {:value "Runner gains 6 credits"}]}}})]
      (is (= :decision-required (runs/park-wake-reason st "corp"))
          "Must surface the prompt, not sleep on it"))))

(deftest park-wakes-on-leftover-trigger-prompt-after-run-end
  (testing "F3: the #43 shape — a select with NO valid targets, which
            has-real-decision? does not consider real. After a run ends the loop
            re-enters park-wake-reason; this must be surfaced, not re-parked on,
            or the opponent hard-blocks on 'waiting for Corp to resolve triggers'."
    (let [st (mock-client-state
              :side "corp"
              :game-state
              {:run nil
               :active-player "runner"
               :corp {:prompt-state {:msg "Select a card to rez"
                                     :prompt-type "select"
                                     :choices []
                                     :selectable []}}})]
      (is (= :decision-required (runs/park-wake-reason st "corp"))))))

(deftest park-does-not-bounce-the-corp-that-just-ended-its-turn
  (testing "LIVE-CAUGHT: at a turn boundary the engine sets :end-turn and LEAVES
            :active-player pointing at the player who just ended. Observed live:
            {:active-player \"corp\" :end-turn true :corp-click 0} while
            game-over-status read AWAITING-START next-player=runner. Reading
            :active-player raw told a Corp that had JUST ENDED ITS TURN — exactly
            when the brief says take your post — 'your move', bouncing it out of
            the park it was entering. The Corp could never take its post at all."
    (let [st (mock-client-state
              :side "corp"
              :game-state {:run nil :active-player "corp" :end-turn true
                           :turn 5 :corp {:click 0} :runner {:click 0}})]
      (is (= :park (runs/park-wake-reason st "corp"))
          "Corp that just ended its turn must PARK, not be told 'your move'"))))

(deftest park-returns-my-turn-when-opponent-ended-their-turn
  (testing "The mirror: Runner ended, so :active-player is a stale \"runner\" with
            :end-turn set. The Corp is the one who must act (start its turn)."
    (let [st (mock-client-state
              :side "corp"
              :game-state {:run nil :active-player "runner" :end-turn true
                           :turn 5 :corp {:click 0} :runner {:click 0}})]
      (is (= :my-turn (runs/park-wake-reason st "corp"))))))

(deftest park-does-not-say-your-move-at-awaiting-start-boundary
  (testing "#68 (LIVE-CAUGHT, marquee 14bb5405): the OTHER turn-boundary residual.
            A parked Corp saw '🔔 Opponent's turn ended — your move' ~3x while
            game-over-status authoritatively read AWAITING-START next-player=runner.
            Repro'd live: {:active-player \"corp\" :end-turn FALSE :turn 1
            :corp-click 0}. Unlike #31 the engine had NOT set :end-turn, so the old
            bespoke my-turn? fell into its (= active side) branch and said 'your
            move' — telling the Corp to leave the post while the Runner had not yet
            started. next-player=runner means the Corp must stay at its post. Fixed
            by deferring to core/my-turn-to-act? (the same predicate game-over-status
            agrees with) instead of a second, divergent copy of boundary logic."
    (let [st (mock-client-state
              :side "corp"
              :game-state {:run nil :active-player "corp" :end-turn false
                           :turn 1 :corp {:click 0} :runner {:click 0}})]
      (is (= :park (runs/park-wake-reason st "corp"))
          "AWAITING-START next-player=runner: Corp parks, not 'your move'"))))

(deftest runner-never-parks
  (testing "Runs only happen on the Runner's turn, so a Runner parking for the
            opponent to start a run waits for something that CANNOT happen — it
            would wedge until timeout while the heartbeat reported it healthy."
    (let [st (mock-client-state
              :side "runner"
              :game-state {:run nil :active-player "corp"})]
      (is (= :no-run (runs/park-wake-reason st "runner"))))))

(deftest park-returns-on-game-over
  (testing "Game over -> never park (would hang the seat forever)"
    (let [st (mock-client-state
              :side "corp"
              :game-state {:run nil :active-player "runner" :winner "runner"})]
      (is (= :game-over (runs/park-wake-reason st "corp"))))))

;; =============================================================================
;; A. Park mode through monitor-run! — the actual command behavior
;;
;; park-wake-reason above is pure classification; these drive monitor-run! itself,
;; which is where the bug lived (it returned :no-run and left the post).
;; =============================================================================

(deftest monitor-run-persistent-does-not-abandon-the-post
  (testing "THE BUG (#31 Fix A): --persistent with no run must PARK, not return
            :no-run. Parks until a run appears, then owns it."
    (let [ticks (atom 0)
          st (mock-client-state
              :side "corp"
              :game-state {:run nil :active-player "runner"})]
      (with-mock-state st
        ;; After 2 park ticks a run appears; the parked monitor must pick it up.
        (with-redefs [runs/monitor-active-run!
                      (fn [_flags]
                        ;; the run we picked up ends, and so does the Runner's turn
                        (swap! state/client-state
                               #(-> %
                                    (assoc-in [:game-state :run] nil)
                                    (assoc-in [:game-state :active-player] "corp")))
                        {:status :run-complete})
                      state/get-cursor (fn [] 1)
                      runs/park-sleep! (fn []
                                         (swap! ticks inc)
                                         ;; a run starts on the 2nd idle tick
                                         (when (= @ticks 2)
                                           (swap! state/client-state
                                                  assoc-in [:game-state :run]
                                                  {:phase "initiation" :server [:hq]})))]
          (let [result (runs/monitor-run! "--persistent")]
            (is (not= :no-run (:status result))
                "Must NOT abandon the post with :no-run")
            (is (= :my-turn (:status result))
                "Parks through the run, returns only when the opponent's turn ends")
            (is (pos? @ticks) "Should actually have parked")))))))

(deftest monitor-run-without-persistent-keeps-old-no-run
  (testing "Hand-driven monitor-run is unchanged: no run -> :no-run immediately"
    (with-mock-state
      (mock-client-state :side "corp"
                         :game-state {:run nil :active-player "runner"})
      (let [result (runs/monitor-run!)]
        (is (= :no-run (:status result)))))))

(deftest monitor-run-persistent-returns-immediately-on-game-over
  (testing "Never park through a finished game — that hangs the seat forever"
    (with-mock-state
      (mock-client-state :side "corp"
                         :game-state {:run nil :active-player "runner" :winner "runner"})
      (with-redefs [state/get-cursor (fn [] 1)]
        (let [result (runs/monitor-run! "--persistent")]
          (is (= :game-over (:status result))))))))

(deftest monitor-run-persistent-returns-immediately-on-own-turn
  (testing "Parking on my OWN turn would deadlock the game (I owe the moves)"
    (with-mock-state
      (mock-client-state :side "corp"
                         :game-state {:run nil :active-player "corp"})
      (with-redefs [state/get-cursor (fn [] 1)]
        (let [result (runs/monitor-run! "--persistent")]
          (is (= :my-turn (:status result))))))))

(deftest park-idle-budget-is-not-reset-by-re-entry
  (testing "One idle-park deadline per invocation: a Runner making runs faster
            than the timeout must not keep the command (and its liveness) alive
            forever. An ALREADY-EXPIRED deadline must time out at once."
    (with-mock-state
      (mock-client-state :side "corp"
                         :game-state {:run nil :active-player "runner"})
      (with-redefs [state/get-cursor (fn [] 1)]
        (let [expired (- (System/currentTimeMillis) 1000)
              result (#'runs/park-and-monitor! {} expired)]
          (is (= :timeout (:status result))))))))

;; =============================================================================
;; C. Jack-out smell — stop coaching the losing move
;; =============================================================================

(deftest hint-does-not-coach-jack-out-as-stall-recovery
  (testing "Michael's smell heuristic: jack-out is almost never right. Our hint
            text told the Runner to bail out of a stalled window; it did, 5x,
            including on a run where it had already broken Bran for 8 credits."
    (let [lines (display/run-priority-hint-lines
                 {:phase "approach-ice" :position 1 :no-action "runner"
                  :server [:remote1]}
                 "runner")
          text (clojure.string/join " " lines)]
      (is (not (re-find #"(?i)jack-out ends the run to recover" text))
          "Must not recommend jack-out as the stall escape hatch")
      (is (re-find #"(?i)peer-status" text)
          "Should point at the patience/liveness signal instead")
      (is (re-find #"(?i)smell" text)
          "Should name jack-out as the smell it is"))))

;; =============================================================================
;; D. THE EVENT-PAUSE LATCH — the real #31 (game 4a6aef71, 2026-07-18)
;; =============================================================================
;;
;; The both-pass handlers above (initiation auto-pass, self-advance) are correct
;; and were never the problem: they are simply NEVER REACHED. `handle-events`
;; sits ahead of them in the chain and is a pure function of the newest 3 log
;; entries, with no memory of what it has already reported. So when the run
;; stops advancing, the log stops moving, and the same event re-fires forever:
;;
;;   pause on event -> nothing passes -> log frozen -> same newest-3 -> pause ...
;;
;; A latch that manufactures the very condition that sustains it. The printed
;; advice ("use continue-run again to proceed") is precisely what cannot work.
;;
;; Why the suite stayed green through two #31 fixes: every test above builds a
;; state with NO :log, so extract-run-events finds nothing and handle-events
;; never fires. The tests omitted the one field the bug lives in.

(def ^:private overclock-entry
  {:user "__system__"
   :text "ai-runner uses Overclock to make a run on R&D."
   :timestamp "2026-07-18T04:13:59.335700Z"})

(def ^:private wedged-log
  ;; Verbatim newest-3 from the wedged marquee game. Note the latched event is
  ;; NOT even the newest line — the run had visibly moved past it.
  [{:user "__system__" :text "ai-runner spends [Click] and pays 1 [Credits] to play Overclock."
    :timestamp "2026-07-18T04:13:52.187223Z"}
   overclock-entry
   {:user "__system__" :text "ai-runner approaches Brân 1.0 protecting R&D at position 1."
    :timestamp "2026-07-18T04:20:30.132582Z"}])

(defn- wedged-state []
  (runner-state :phase "approach-ice" :position 2 :no-action false
                :ices [{:cid 1 :title "Brân 1.0" :rezzed true}]
                :prompt {:prompt-type "run" :msg "You are running on R&D" :selectable []}
                :log wedged-log))

(deftest event-pause-reports-each-event-exactly-once
  (testing "An event pauses the seat ONCE. Offered the same entry again, the
            handler must yield so the pass handlers behind it get their turn."
    (runs/reset-reported-events!)
    (let [ctx {:ability-event overclock-entry}]
      (is (= :ability-used (:status (runs/handle-events ctx)))
          "First sight: pause and tell the seat")
      (is (nil? (runs/handle-events ctx))
          "Second sight: same entry, already reported -> must NOT re-latch"))))

(deftest a-genuinely-new-event-still-pauses
  (testing "SAFETY: dedupe must be per-entry, not a global mute. A real rez
            arriving later must still stop the Runner."
    (runs/reset-reported-events!)
    (is (= :ability-used (:status (runs/handle-events {:ability-event overclock-entry}))))
    (let [rez {:user "__system__" :text "ai-corp rezzes Brân 1.0 protecting R&D."
               :timestamp "2026-07-18T04:21:00.000000Z"}]
      (is (= :ice-rezzed (:status (runs/handle-events {:rez-event rez})))
          "A new entry is a new event and must still pause"))))

(deftest event-pause-banner-is-honest-about-upgrades-and-side
  ;; #104: the Manegarm rez printed '⚠️ Run paused - ICE rezzed!' plus the
  ;; runner-flavored continue-run hint on the CORP side.
  (testing "a rezzed UPGRADE isn't announced as 'ICE rezzed!', and the Corp
            side doesn't get the runner-flavored continue-run hint"
    (runs/reset-reported-events!)
    (let [manegarm-rez {:user "__system__"
                        :text "ai-corp rezzes Manegarm Skunkworks protecting Server 2."
                        :timestamp "2026-08-04T04:21:00.000000Z"}
          state {:game-state {:corp {:servers {:remote2 {:ices [{:cid 9 :title "Palisade"}]
                                                         :content [{:cid 7 :title "Manegarm Skunkworks"}]}}}}}
          out (with-out-str (runs/handle-events {:rez-event manegarm-rez
                                                 :state state
                                                 :side "corp"}))]
      (is (str/includes? out "Upgrade/asset rezzed!")
          (str "a non-ICE rez must not claim to be ICE, got:\n" out))
      (is (not (str/includes? out "ICE rezzed!")))
      (is (not (str/includes? out "continue-run"))
          "the Corp side must not be told to use the Runner's continue-run"))
    (runs/reset-reported-events!)
    (let [ice-rez {:user "__system__"
                   :text "ai-corp rezzes Palisade protecting Server 2."
                   :timestamp "2026-08-04T04:22:00.000000Z"}
          state {:game-state {:corp {:servers {:remote2 {:ices [{:cid 9 :title "Palisade"}]
                                                         :content [{:cid 7 :title "Manegarm Skunkworks"}]}}}}}
          out (with-out-str (runs/handle-events {:rez-event ice-rez
                                                 :state state
                                                 :side "runner"}))]
      (is (str/includes? out "ICE rezzed!") "a real ICE rez keeps its banner")
      (is (str/includes? out "continue-run") "the Runner keeps its hint"))))

(deftest reset-reported-events-rearms-the-pause
  (testing "A fresh run starts with a clean slate (run! resets)."
    (runs/reset-reported-events!)
    (is (= :ability-used (:status (runs/handle-events {:ability-event overclock-entry}))))
    (is (nil? (runs/handle-events {:ability-event overclock-entry})))
    (runs/reset-reported-events!)
    (is (= :ability-used (:status (runs/handle-events {:ability-event overclock-entry})))
        "After reset the event is reportable again")))

(deftest continue-run-passes-the-window-after-reporting-the-event
  (testing "THE MARQUEE REPRO (game 4a6aef71): Runner holds priority at a
            both-pass approach-ice window, ICE already rezzed, decision-free run
            prompt — i.e. can-auto-continue? is TRUE. Observed live: three
            consecutive `continue --single` calls each re-printed the same
            7-minute-old Overclock line and no-action stayed false forever.
            After the fix the event is reported once, then the window is PASSED."
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (runs/reset-reported-events!)
        (with-mock-state (wedged-state)
          (let [first-call (runs/continue-run!)]
            (is (= :ability-used (:status first-call))
                "First call may pause to report the event — that is the feature")
            (is (empty? @sent) "Reporting an event sends nothing")
            (let [second-call (runs/continue-run!)]
              (is (= :action-taken (:status second-call))
                  "Second call MUST pass the window instead of re-latching (#31)")
              (is (= 1 (count @sent)) "Exactly one continue")
              (is (= "continue" (:command (first @sent)))))))))))

(deftest a-decision-arriving-after-an-event-is-reported-still-blocks-the-pass
  (testing "SAFETY, in the order that can actually bite. handle-real-decision
            sits AHEAD of handle-events (chain ~1399/1400), so while I hold a
            decision the event is never even reached — asserting that proves
            nothing. The dangerous sequence is the reverse: an ICE rezzes at a
            window where I hold NOTHING (event reported, entry now deduped),
            and only THEN does the break decision appear. On that second call
            handle-events is silent, so nothing stops fall-through to
            handle-auto-continue except the decision itself."
    (let [sent (atom [])
          rez-log [{:text "ai-corp rezzes Bran 1.0 protecting R&D." :timestamp "T1"}]
          break-prompt {:prompt-type "select" :msg "Break subroutine?"
                        :choices [{:value "Yes"} {:value "No"}]}]
      (runs/reset-reported-events!)
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-mock-state
          (runner-state :phase "encounter-ice" :position 1 :no-action false
                        :ices [{:cid 1 :title "Bran 1.0" :rezzed true}]
                        :prompt nil :log rez-log)
          ;; 1. Rez lands while I hold nothing -> reported, entry now deduped.
          (is (= :ice-rezzed (:status (runs/continue-run!)))
              "The rez must stop me the first time")
          (is (nil? (runs/handle-events (runs/extract-run-events rez-log)))
              "…and is now deduped, so it can no longer block anything")
          ;; 2. NOW the break decision appears on the same (deduped) log.
          (swap! state/client-state assoc-in
                 [:game-state :runner :prompt-state] break-prompt)
          (let [second-call (runs/continue-run!)]
            (is (not= :action-taken (:status second-call))
                "My decision must block the pass even with the event spent")
            (is (empty? @sent)
                "and no continue may be sent while I hold a real decision")))))))


;; --- Event identity: the two failure modes the guest review flagged ----------

(deftest the-same-text-twice-is-two-events
  (testing "IDENTITY: a repeated action (same card, same engine wording) must
            pause BOTH times. Dedupe is per log ENTRY, not per message text —
            otherwise the client goes blind to a genuine second occurrence."
    (runs/reset-reported-events!)
    (let [line "ai-corp rezzes Brân 1.0 protecting R&D."
          log-after-first  [{:text "x"} {:text line :timestamp "T1"}]
          log-after-second [{:text "x"} {:text line :timestamp "T1"}
                            {:text "y"} {:text line :timestamp "T1"}]]
      ;; Identical text AND identical timestamp — the worst case for a
      ;; [timestamp text] key. Distinct log positions make them distinct events.
      (is (= :ice-rezzed (:status (runs/handle-events (runs/extract-run-events log-after-first)))))
      (is (nil? (runs/handle-events (runs/extract-run-events log-after-first)))
          "Same entry re-read -> no re-latch")
      (is (= :ice-rezzed (:status (runs/handle-events (runs/extract-run-events log-after-second))))
          "A SECOND occurrence is a new event and must pause again"))))

(deftest entries-without-timestamps-still-dedupe-and-still-distinguish
  (testing "IDENTITY: the engine always stamps log entries (say.clj defaults
            :timestamp), but the key must not depend on that to stay correct."
    (runs/reset-reported-events!)
    (let [line "ai-runner uses Overclock to make a run on R&D."
          log1 [{:text line}]
          log2 [{:text line} {:text "z"} {:text line}]]
      (is (= :ability-used (:status (runs/handle-events (runs/extract-run-events log1)))))
      (is (nil? (runs/handle-events (runs/extract-run-events log1)))
          "No timestamp -> still deduped, no latch")
      (is (= :ability-used (:status (runs/handle-events (runs/extract-run-events log2))))
          "No timestamp -> distinct occurrences still distinguished"))))

(deftest reported-events-are-scoped-to-the-game-not-the-run
  (testing "A new game restarts the log at index 0, so a previous game's keys
            would collide with the new game's first entries and silently
            suppress them. Scoping to gameid is self-healing. Conversely the set
            must SURVIVE within a game: a seat re-issues monitor-run repeatedly
            during one run, and forgetting on each re-issue would re-report the
            same event every time — the #31 latch, one grain coarser.
            (Guest review of #31.)"
    (runs/reset-reported-events!)
    (let [log [{:text "ai-corp rezzes Bran 1.0 protecting R&D." :timestamp "T1"}]]
      (with-mock-state
        (assoc (mock-client-state :side "corp" :game-state {:log log}) :gameid "game-A")
        (is (= :ice-rezzed (:status (runs/handle-events (runs/extract-run-events log)))))
        (is (nil? (runs/handle-events (runs/extract-run-events log)))
            "WITHIN a game the report must stick across re-issues"))
      (with-mock-state
        (assoc (mock-client-state :side "corp" :game-state {:log log}) :gameid "game-B")
        (is (= :ice-rezzed (:status (runs/handle-events (runs/extract-run-events log))))
            "A DIFFERENT game must start from a clean slate")))))
