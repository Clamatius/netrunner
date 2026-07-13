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
  [& {:keys [phase position no-action ices content prompt]
      :or {phase "approach-ice" position 1 ices [] content []}}]
  (mock-client-state
   :side "runner"
   :game-state
   {:run {:phase phase
          :position position
          :no-action no-action
          :server [:remote1]}
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

(deftest self-advance-fires-when-opponent-has-no-decision
  (testing "Runner already passed, ICE is rezzed -> send the 2nd continue to advance"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (let [st (runner-state :phase "approach-ice" :position 1 :no-action "runner"
                               :ices [{:cid 1 :title "Bran 1.0" :rezzed true}])
              result (runs/handle-stalled-window-self-advance
                      {:run-phase "approach-ice" :gameid "g1" :side "runner"
                       :state st :my-prompt nil})]
          (is (= :action-taken (:status result)) "Should advance the stalled window")
          (is (= 1 (count @sent)) "Should send exactly one continue")
          (is (= "continue" (:command (first @sent)))))))))

(deftest self-advance-NEVER-skips-a-live-rez-decision
  (testing "SAFETY: unrezzed ICE = the Corp's rez decision. Self-advancing here
            would be the blunt corp-auto-no-action bug. Must return nil."
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
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
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
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
                           :prompt prompt)
          result (runs/handle-stalled-window-self-advance
                  {:run-phase "approach-ice" :gameid "g1" :side "runner"
                   :state st :my-prompt prompt})]
      (is (nil? result)))))

;; =============================================================================
;; B. Integration through continue-run! — the actual marquee stall
;; =============================================================================

(deftest continue-run-advances-instead-of-stalling-at-no-decision-window
  (testing "REGRESSION (marquee d6962df4): Runner passed, ICE rezzed, Corp silent.
            Old behavior: :waiting-for-opponent forever -> jack-out -> lose."
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-mock-state
          (runner-state :phase "approach-ice" :position 1 :no-action "runner"
                        :ices [{:cid 1 :title "Bran 1.0" :rezzed true}])
          (let [result (runs/continue-run!)]
            (is (= :action-taken (:status result))
                "Must advance, not stall waiting on a Corp with nothing to decide")))))))

(deftest continue-run-still-waits-when-corp-genuinely-must-rez
  (testing "SAFETY: unrezzed ICE -> Corp is genuinely on the clock. Keep waiting."
    (with-mock-state
      (runner-state :phase "approach-ice" :position 1 :no-action "runner"
                    :ices [{:cid 1 :title "Whitespace" :rezzed false}])
      (let [result (runs/continue-run!)]
        (is (contains? #{:waiting-for-corp-rez :waiting-for-opponent
                         :waiting-for-opponent-paid-abilities}
                       (:status result))
            "Must still wait for a real Corp rez decision")))))

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
              :game-state {:run nil :active-player "runner"})]
      (is (= :park (runs/park-wake-reason st "corp"))))))

(deftest park-returns-on-my-turn
  (testing "Opponent's turn ended -> hand control back to the seat"
    (let [st (mock-client-state
              :side "corp"
              :game-state {:run nil :active-player "corp"})]
      (is (= :my-turn (runs/park-wake-reason st "corp"))))))

(deftest park-returns-on-game-over
  (testing "Game over -> never park (would hang the seat forever)"
    (let [st (mock-client-state
              :side "corp"
              :game-state {:run nil :active-player "runner" :winner "runner"})]
      (is (= :game-over (runs/park-wake-reason st "corp"))))))

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
      (is (re-find #"(?i)peer-status|keep waiting|wait" text)
          "Should point at the patience/liveness signal instead"))))
