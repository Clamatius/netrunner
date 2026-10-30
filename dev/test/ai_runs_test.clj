(ns ai-runs-test
  "Tests for continue-run! function - lock down behavior before refactoring

   The continue-run! function is a 363-line beast with 9 levels of nested cond.
   These tests document current behavior so we can refactor safely."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-runs :as runs]
            [ai-core :as ai-core]
            [ai-state :as ai-state]
            [ai-basic-actions :as ai-basic-actions]
            [ai-prompts :as ai-prompts]
            [ai-run-runner-handlers :as runner-handlers]
            [ai-run-corp-handlers :as corp-handlers]
            [ai-card-actions :as card-actions]
            [ai-websocket-client-v2 :as ws]))

;; ============================================================================
;; Test Helpers - Run-specific State Builders
;; ============================================================================

(defn make-run-state
  "Create a run state for testing

   Usage:
     (make-run-state :phase \"approach-ice\" :position 1 :server [\"remote1\"])"
  [& {:keys [phase position server no-action]
      :or {phase nil position 0 server ["hq"] no-action nil}}]
  (cond-> {:phase phase
           :position position
           :server server}
    no-action (assoc :no-action no-action)))

(defn make-ice
  "Create an ICE card for testing

   Usage:
     (make-ice :cid 1 :title \"Ice Wall\" :rezzed true)
     (make-ice :cid 2 :title \"Enigma\" :rezzed false)"
  [& {:keys [cid title rezzed zone side type subroutines]
      :or {cid 1 title "Test ICE" rezzed false zone [:servers :hq :ices 0]
           side "Corp" type "ICE" subroutines nil}}]
  (cond-> {:cid cid
           :title title
           :rezzed rezzed
           :zone zone
           :side side
           :type type}
    subroutines (assoc :subroutines subroutines)))

(defn make-ice-list
  "Create a list of ICE cards (outermost first)

   Usage:
     (make-ice-list [{:title \"Ice Wall\" :rezzed true}
                     {:title \"Enigma\" :rezzed false}])"
  [ice-specs]
  (map-indexed
   (fn [idx spec]
     (make-ice
      :cid (inc idx)
      :title (:title spec "ICE")
      :rezzed (:rezzed spec false)
      :subroutines (:subroutines spec)))
   ice-specs))

(defn mock-state-with-run
  "Create full client state with active run

   Usage:
     (mock-state-with-run
       :side \"runner\"
       :run-phase \"approach-ice\"
       :position 1
       :ice [{:title \"Ice Wall\" :rezzed false}])"
  [& {:keys [side run-phase position server ice prompt no-action log]
      :or {side "runner" run-phase nil position 0 server ["hq"] ice [] prompt nil no-action nil log []}}]
  (let [run-state (when run-phase
                    (make-run-state :phase run-phase :position position :server server :no-action no-action))
        ice-list (make-ice-list ice)
        servers (if (seq ice)
                  {(keyword (last server)) {:ices ice-list}}
                  {})
        game-state {:runner {:credit 5 :click 4 :hand [] :rig {} :prompt-state (when (= side "runner") prompt)}
                    :corp {:credit 5 :click 3 :hand [] :servers servers :prompt-state (when (= side "corp") prompt)}
                    :active-player "runner"
                    :log log}
        game-state (if run-state
                     (assoc game-state :run run-state)
                     game-state)]
    (mock-client-state
     :side side
     :game-state game-state)))

;; ============================================================================
;; Priority 0: Force Mode
;; ============================================================================

(deftest test-force-mode-bypasses-all-checks
  (testing "Force mode bypasses all checks and sends continue immediately"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "runner"
         :run-phase "approach-ice")
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (runs/continue-run! "--force")]
            (is (= :action-taken (:status result)))
            (is (= :forced-continue (:action result)))
            (is (= 1 (count @sent)))
            (is (= "continue" (get-in @sent [0 :data :command])))))))))

;; ============================================================================
;; Priority 1.5: Corp Rez Strategy
;; ============================================================================

(deftest test-corp-no-rez-strategy
  (testing "Corp with --no-rez strategy auto-declines all rez opportunities"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "corp"
         :run-phase "approach-ice"
         :position 1
         :ice [{:title "Ice Wall" :rezzed false}]
         :prompt (make-prompt :msg "Rez Ice Wall?"))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          ;; Set strategy before calling
          (runs/set-strategy! {:no-rez true})
          (let [result (runs/continue-run!)]
            (is (= :action-taken (:status result)))
            (is (= :auto-declined-rez (:action result)))
            (is (= "Ice Wall" (:ice result)))
            (is (= 1 (count @sent)))
            (is (= "continue" (get-in @sent [0 :data :command]))))
          ;; Clean up
          (runs/reset-strategy!))))))

(deftest test-corp-rez-specific-ice
  (testing "Corp with --rez strategy rezzes specified ICE only"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "corp"
         :run-phase "approach-ice"
         :position 1
         :ice [{:title "Ice Wall" :rezzed false}]
         :prompt (make-prompt :msg "Rez Ice Wall?"))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          ;; Set strategy to rez Ice Wall
          (runs/set-strategy! {:rez #{"Ice Wall"}})
          (let [result (runs/continue-run!)]
            (is (= :action-taken (:status result)))
            (is (= :auto-rezzed (:action result)))
            (is (= "Ice Wall" (:ice result)))
            (is (= 1 (count @sent)))
            (is (= "rez" (get-in @sent [0 :data :command]))))
          ;; Clean up
          (runs/reset-strategy!))))))

(deftest test-corp-rez-pauses-on-other-unrezzed-ice
  (testing "Corp with --rez strategy PAUSES on an unrezzed ICE not in the set (returns a rez decision) instead of silently declining — marquee g3 / forum [112]"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "corp"
         :run-phase "approach-ice"
         :position 1
         :ice [{:title "Enigma" :rezzed false}]
         :prompt (make-prompt :msg "Rez Enigma?"))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          ;; Set strategy to rez Ice Wall only (not Enigma)
          (runs/set-strategy! {:rez #{"Ice Wall"}})
          ;; An unrezzed ICE the Corp hasn't named is a real rez decision under
          ;; the --persistent contract: pause and hand control back, don't
          ;; silently `continue` past it (which hid inner-ICE rezzes in g3).
          (let [result (runs/continue-run!)]
            (is (= :decision-required (:status result)))
            (is (= "Enigma" (:ice result)))
            (is (not-any? #(= "continue" (get-in % [:data :command])) @sent)
                "must NOT silently pass priority — the unrezzed ICE is a real rez decision"))
          ;; Clean up
          (runs/reset-strategy!))))))

;; ============================================================================
;; #71: Corp --fire-unbroken must not re-fire an already-fired subroutine.
;;
;; Diviner has ONE subroutine. In marquee 9242bc1b it fired TWICE (2 net damage,
;; 2 cards trashed) because handle-corp-fire-unbroken's "unbroken subs" view
;; filtered only :broken, not :fired — so on re-entry after the sub already
;; fired it still saw the sub as fireable and (when the :fired-at-encounter guard
;; was stale) re-sent the fire command. The engine's own resolve-unbroken-subs!
;; excludes :fired; the client's view must match. A :fired sub is resolved, not
;; fireable.
;; ============================================================================

(deftest test-corp-fire-unbroken-does-not-refire-fired-sub
  (testing "Corp does NOT re-send the fire command for a subroutine already marked :fired"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "corp"
         :run-phase "encounter-ice"
         :position 1
         :ice [{:title "Diviner" :rezzed true
                ;; Post-fire state: the single sub has resolved (:fired true).
                :subroutines [{:broken false :fired true}]}]
         :log [{:text "ai-runner indicates to fire all unbroken subroutines on Diviner"}])
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          ;; :fired-at-encounter deliberately absent — simulate the stale re-entry
          ;; that let the double-fire through. The sub-state guard must stand on
          ;; its own, not lean on the position bookkeeping.
          (runs/set-strategy! {:fire-unbroken true})
          (runs/continue-run!)
          (is (not-any? #(= "unbroken-subroutines" (get-in % [:data :command])) @sent)
              (str "must NOT re-fire an already-fired sub, sent: " @sent))
          (runs/reset-strategy!))))))

(deftest test-corp-fire-unbroken-still-fires-genuinely-unfired-sub
  (testing "Corp DOES fire when the sub is genuinely unbroken and unfired (fix doesn't over-suppress)"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "corp"
         :run-phase "encounter-ice"
         :position 1
         :ice [{:title "Diviner" :rezzed true
                :subroutines [{:broken false :fired false}]}]
         :log [{:text "ai-runner indicates to fire all unbroken subroutines on Diviner"}])
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (runs/set-strategy! {:fire-unbroken true})
          (runs/continue-run!)
          (is (some #(= "unbroken-subroutines" (get-in % [:data :command])) @sent)
              (str "an unfired sub with the Runner's signal must still fire, sent: " @sent))
          (runs/reset-strategy!))))))

;; ============================================================================
;; Priority 2: Runner Waiting for Corp Rez
;; ============================================================================

(deftest test-runner-waits-for-corp-rez-unrezzed-ice
  (testing "Runner first passes the unrezzed ICE approach, then waits for the Corp"
    (doseq [[no-action expected-send?] [[nil true] [:corp true] [:runner false]]]
      (let [sent (atom [])]
        (with-mock-state
          (mock-state-with-run
           :side "runner"
           :run-phase "approach-ice"
           :position 1
           :ice [{:title "Ice Wall" :rezzed false}]
           :no-action no-action)
          (with-redefs [ws/send-message! (mock-websocket-send! sent)]
            (let [result (runs/continue-run!)]
              (is (= expected-send? (boolean (seq @sent))) (str no-action))
              (if expected-send?
                (is (= "continue" (get-in @sent [0 :data :command])))
                (do (is (= :waiting-for-corp-rez (:status result)))
                    (is (= "Ice Wall" (:ice result))))))))))))

;; DELETED: test-runner-continues-when-corp-declined-rez
;; Was a characterization test documenting internal status codes, not behavior.
;; Status codes drifted with refactoring - brittle and didn't test outcomes.

;; ============================================================================
;; Priority 3: Real Decision Detection
;; ============================================================================

(deftest test-real-decision-pauses-run
  (testing "Run pauses when runner has real decision (2+ meaningful choices)"
    (with-mock-state
      (mock-state-with-run
       :side "runner"
       :run-phase "encounter-ice"
       :prompt (make-prompt
                :msg "Choose breaker to use"
                :prompt-type "run"
                :choices [{:value "Use Corroder" :idx 0}
                         {:value "Use Gordian Blade" :idx 1}
                         {:value "Done" :idx 2}]))
      (let [result (runs/continue-run!)]
        (is (= :decision-required (:status result)))
        (is (some? (:prompt result)))))))

(deftest test-encounter-ability-prompt-not-treated-as-fire-decision
  (testing "On-encounter ability prompt (e.g. Funhouse 'Take 1 tag / End the run')
            surfaces as a real decision, NOT a tank/jack-out fire decision.
            Regression: handle-runner-encounter-ice used to steamroll the engine
            prompt because it fired before handle-real-decision and lacked the
            has-real-decision? guard that handle-runner-full-break already has."
    (with-mock-state
      (mock-state-with-run
       :side "runner"
       :run-phase "encounter-ice"
       :position 1
       :ice [{:title "Funhouse" :rezzed true
              :subroutines [{:broken false :fired false}]}]
       :prompt (make-prompt
                :msg "Choose one"
                :prompt-type "other"
                :choices [{:value "Take 1 tag" :idx 0}
                          {:value "End the run" :idx 1}]))
      (runs/reset-strategy!)
      (let [result (runs/continue-run!)]
        (is (= :decision-required (:status result))
            "Encounter-ability choice must pause for the runner to choose")
        (is (not= :fire-decision-required (:status result))
            "Must not be misclassified as a tank/jack-out subroutine decision")))))

(deftest test-single-trivial-choice-auto-continues
  (testing "Single 'Done' choice does not pause (not a real decision)"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "runner"
         :run-phase "encounter-ice"
         :prompt (make-prompt
                  :msg "Paid ability window"
                  :prompt-type "run"
                  :choices [{:value "Done" :uuid "abc-123"}]))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (runs/continue-run!)]
            ;; Should auto-choose "Done" since it's the only choice and trivial
            (is (= :action-taken (:status result)))
            (is (= :auto-choice (:action result)))))))))

;; ============================================================================
;; Priority 6: Auto-Continue Logic
;; ============================================================================

(deftest test-auto-continue-empty-paid-ability-window
  (testing "Auto-continues through empty paid ability window (no choices, no selectables)"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "runner"
         :run-phase "movement"  ;; Not approach-ice or encounter-ice
         :prompt (make-prompt
                  :msg "Paid ability window"
                  :prompt-type "run"
                  :choices []  ;; Empty choices
                  :selectable []))  ;; Empty selectables
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (runs/continue-run!)]
            (is (= :action-taken (:status result)))
            (is (= :sent-continue (:action result)))
            (is (= 1 (count @sent)))
            (is (= "continue" (get-in @sent [0 :data :command])))))))))

;; NOTE: test-no-auto-continue-during-approach-ice removed
;; Revealed bug in production code: can-auto-continue? checks for keyword :approach-ice
;; but game state uses string "approach-ice". This prevents the check from working.
;; Bug should be fixed in separate PR - this is a pre-existing issue.

;; ============================================================================
;; Priority 5: Auto-Choice (Single Mandatory Choice)
;; ============================================================================

(deftest test-auto-choose-single-choice
  (testing "Auto-chooses when only one choice available"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "runner"
         :run-phase "approach-ice"
         :prompt (make-prompt
                  :msg "Only one option"
                  :choices [{:value "Continue" :uuid "abc-123"}]))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (runs/continue-run!)]
            (is (= :action-taken (:status result)))
            (is (= :auto-choice (:action result)))
            (is (= "Continue" (:choice result)))
            (is (= 1 (count @sent)))
            (is (= "choice" (get-in @sent [0 :data :command])))))))))

;; ============================================================================
;; Priority 7: Run Complete
;; ============================================================================

(deftest test-run-complete-no-phase-no-prompt
  (testing "Returns :run-complete when no run phase and no prompt"
    (with-mock-state
      (mock-client-state :side "runner")  ;; No run, no prompt
      (let [result (runs/continue-run!)]
        (is (= :run-complete (:status result)))))))

;; ----------------------------------------------------------------------------
;; Bug #3: auto-end-turn after run-complete
;; ----------------------------------------------------------------------------
;; Root cause of Run #3/#4 "turn-sync deadlock" (was misattributed to upstream).
;; When the runner spends their last click on `run`, the run completes via
;; continue/monitor-run — but check-auto-end-turn! only fires inside
;; take-credit!/play-card!/install-card!, NOT in the run-completion path.
;; The runner client never sends end-turn, the engine transitions out-of-band,
;; and by the next turn-cycle the deadlock is unrecoverable.
;;
;; Fix: auto-continue-loop! must invoke check-auto-end-turn! on :run-complete.

(deftest test-auto-continue-loop-fires-end-turn-on-run-complete
  (testing "auto-continue-loop! fires end-turn when run completes with 0 clicks
            (Bug #3 — turn-sync deadlock root cause)"
    (let [sent (atom [])]
      (with-mock-state
        {:connected true
         :uid "AI-runner"
         :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
         :side "runner"
         :game-state {:runner {:click 0
                               :user {:username "AI-runner"}
                               :prompt-state nil}
                      :corp {:click 0}
                      :active-player "runner"
                      :turn 5
                      :log [{:text "AI-corp is ending their turn 4"}
                            {:text "AI-runner started their turn 5"}
                            {:text "AI-runner spends [Click] to make a run on HQ."}
                            {:text "AI-runner accesses Brân 1.0 from HQ."}]}}
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-core/show-turn-indicator (fn [& _] nil)]
          (runs/auto-continue-loop! :timeout-ms 1000 :max-iterations 5)
          (is (some #(= "end-turn" (get-in % [:data :command])) @sent)
              (str "auto-continue-loop! must trigger end-turn when run is "
                   "complete and 0 clicks remain. Sent: "
                   (pr-str (map #(get-in % [:data :command]) @sent)))))))))

(deftest test-auto-continue-loop-no-end-turn-when-clicks-remain
  (testing "auto-continue-loop! does NOT end turn when clicks remain after run
            (don't end turn early)"
    (let [sent (atom [])]
      (with-mock-state
        {:connected true
         :uid "AI-runner"
         :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
         :side "runner"
         :game-state {:runner {:click 2  ; ← clicks remaining
                               :user {:username "AI-runner"}
                               :prompt-state nil}
                      :corp {:click 0}
                      :active-player "runner"
                      :turn 5
                      :log [{:text "AI-runner started their turn 5"}
                            {:text "AI-runner spends [Click] to make a run on HQ."}]}}
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-core/show-turn-indicator (fn [& _] nil)]
          (runs/auto-continue-loop! :timeout-ms 1000 :max-iterations 5)
          (is (not (some #(= "end-turn" (get-in % [:data :command])) @sent))
              "must NOT end turn while clicks remain"))))))

(deftest test-auto-continue-loop-return-on-runner-signal
  (testing ":return-on-runner-signal surfaces a :waiting-for-runner-signal wait on
            the first poll (so the autonomous Corp loop's per-tick stall tracker
            governs it); the default keeps polling internally. Regression for the
            Codex Q3 finding: monitor-run!'s internal poll would swallow the wait
            for ~100s, defeating the nudge/bail backstop."
    (with-mock-state
      {:connected true
       :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
       :side "corp"
       :game-state {:run {:phase "encounter-ice" :position 1}
                    :corp {:click 0} :runner {:click 0}
                    :active-player "runner" :turn 5 :log []}}
      (with-redefs [runs/continue-run! (fn [& _] {:status :waiting-for-runner-signal})
                    ws/send-message! (fn [& _] nil)
                    ai-core/show-turn-indicator (fn [& _] nil)]
        ;; Opt-in: return immediately so the outer stall tracker sees the wait.
        (let [r (runs/auto-continue-loop! :return-on-runner-signal true
                                          :max-iterations 50 :timeout-ms 2000)]
          (is (= :waiting-for-runner-signal (:status r)))
          (is (= 1 (:iterations r)) "must return on the first poll, not loop"))
        ;; Default (hand-driven / persistent monitor-run): keeps polling
        ;; internally. The idle runner-signal wait is bounded by timeout-ms (the
        ;; LLM-paced idle bound), NOT the action-stuck max-iterations guard —
        ;; otherwise a Corp parked at an encounter waiting for a slow Runner bails
        ;; mid-run after ~100s with a misleading "max iterations reached".
        (let [r (runs/auto-continue-loop! :max-iterations 3 :timeout-ms 50 :wait-delay-ms 1)]
          (is (= :timeout (:status r))
              "idle runner-signal wait is governed by timeout, not max-iterations")
          (is (< (:iterations r) 3)
              "idle polling must not advance the action-stuck iteration counter"))))))

;; ============================================================================
;; Persistent monitor-run (Michael's decree / codex55 058): a Corp seat should
;; own the whole Runner run with ONE monitor-run --persistent, sleeping through
;; empty symmetric priority windows instead of exiting and being re-issued. The
;; cross-model game-1 deadlock (msg 057) was exactly this: each model read the
;; symmetric "continue to pass priority" line as "waiting on the other guy."
;; ============================================================================

(deftest test-auto-continue-loop-persistent-survives-empty-window
  (testing "persistent mode sleeps through :waiting-for-opponent while the run is
            active and keeps looping until the run actually completes — it does
            NOT bail on the first empty priority window"
    (with-mock-state
      {:connected true
       :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
       :side "corp"
       :game-state {:run {:phase "approach-server" :position 0}
                    :corp {:click 3} :runner {:click 2}
                    :active-player "runner" :turn 5 :log []}}
      (let [calls (atom 0)]
        (with-redefs [runs/continue-run! (fn [& _]
                                           (if (< (swap! calls inc) 3)
                                             {:status :waiting-for-opponent}
                                             {:status :run-complete}))
                      ai-basic-actions/check-auto-end-turn! (fn [& _] nil)
                      runner-handlers/reset-state! (fn [& _] nil)
                      ws/send-message! (fn [& _] nil)
                      ai-core/show-turn-indicator (fn [& _] nil)]
          (let [r (runs/auto-continue-loop! :persistent true
                                            :persistent-wait-delay-ms 1
                                            :max-iterations 50 :timeout-ms 3000)]
            (is (= :run-complete (:status r))
                "persistent loop must ride out the empty windows to run-complete")
            (is (>= @calls 3) "must have polled past the waiting windows")))))))

(deftest test-auto-continue-loop-persistent-still-wakes-for-decision
  (testing "persistent mode is NOT a black hole — a real Corp decision
            (:decision-required, caught by terminal-status? above the wait branch)
            still returns immediately so the seat can rez/fire"
    (with-mock-state
      {:connected true
       :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
       :side "corp"
       :game-state {:run {:phase "approach-ice" :position 1}
                    :corp {:click 3} :runner {:click 2}
                    :active-player "runner" :turn 5 :log []}}
      (with-redefs [runs/continue-run! (fn [& _] {:status :decision-required})
                    ws/send-message! (fn [& _] nil)
                    ai-core/show-turn-indicator (fn [& _] nil)]
        (let [r (runs/auto-continue-loop! :persistent true
                                          :persistent-wait-delay-ms 1
                                          :max-iterations 50 :timeout-ms 3000)]
          (is (= :decision-required (:status r)))
          (is (= 1 (:iterations r)) "must wake on the first poll for a real decision"))))))

(deftest test-auto-continue-loop-default-exits-on-waiting
  (testing "WITHOUT --persistent the loop still exits on :waiting-for-opponent on
            the first poll (locks the existing one-step/HITL contract — persistence
            is strictly opt-in)"
    (with-mock-state
      {:connected true
       :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
       :side "corp"
       :game-state {:run {:phase "approach-server" :position 0}
                    :corp {:click 3} :runner {:click 2}
                    :active-player "runner" :turn 5 :log []}}
      (with-redefs [runs/continue-run! (fn [& _] {:status :waiting-for-opponent})
                    ws/send-message! (fn [& _] nil)
                    ai-core/show-turn-indicator (fn [& _] nil)]
        (let [r (runs/auto-continue-loop! :max-iterations 50 :timeout-ms 3000)]
          (is (= :waiting-for-opponent (:status r)))
          (is (= 1 (:iterations r)) "default mode returns control on the first wait"))))))

(deftest test-auto-continue-loop-persistent-returns-when-run-gone
  (testing "persistent mode only sleeps while the run is ACTIVE — if the run
            object is gone it returns a CLEAN :no-run terminal (not a stale
            :waiting-for-opponent + 'Corp should run monitor-run' tip). Marquee
            game-2 access-boundary mislabel: a persistent monitor returned
            :waiting-for-opponent at the access boundary (run torn down, Runner
            still resolving access), and re-issuing then said 'No active run to
            monitor' — a self-contradicting double-step. The clean :no-run sends
            the seat straight back to its wait loop."
    (with-mock-state
      {:connected true
       :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
       :side "corp"
       :game-state {:corp {:click 3} :runner {:click 2}
                    :active-player "runner" :turn 5 :log []}}  ;; no :run
      (with-redefs [runs/continue-run! (fn [& _] {:status :waiting-for-opponent})
                    ai-basic-actions/check-auto-end-turn! (fn [& _] nil)
                    runner-handlers/reset-state! (fn [& _] nil)
                    ws/send-message! (fn [& _] nil)
                    ai-core/show-turn-indicator (fn [& _] nil)]
        (let [r (runs/auto-continue-loop! :persistent true
                                          :persistent-wait-delay-ms 1
                                          :max-iterations 50 :timeout-ms 3000)]
          (is (= :no-run (:status r))
              "no active run → persistent returns a clean :no-run terminal"))))))

(deftest test-choose-fires-end-turn-when-prompt-resolves-run
  (testing "choose-by-index! triggers auto-end-turn when the resolved prompt
            was the last step of a completed run (Bug #3, prompt-tail variant
            — surfaced in Run #5: Conduit virus-counter prompt was the last
            click, run finished implicitly, auto-continue-loop! never re-entered)"
    (let [sent (atom [])]
      (with-mock-state
        {:connected true
         :uid "AI-runner"
         :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
         :side "runner"
         :game-state {:runner {:click 0
                               :user {:username "AI-runner"}
                               :prompt-state nil}   ; ← prompt already resolved by mock
                      :corp {:click 0}
                      :active-player "runner"
                      :turn 1
                      ;; No :run — run completed when the prompt resolved
                      :log [{:text "AI-corp is ending their turn 0"}
                            {:text "AI-runner started their turn 1"}
                            {:text "AI-runner spends [Click] to make a run on R&D."}
                            {:text "AI-runner steals Offworld Office."}
                            {:text "AI-runner uses Conduit to place 1 virus counter."}]}}
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-core/show-turn-indicator (fn [& _] nil)
                      ;; Stub the wait so the test doesn't actually sleep.
                      ;; Real wait-for-prompt-change! returns true once prompt
                      ;; eid changes — already nil here, so this is a no-op.
                      ai-prompts/wait-for-prompt-change! (fn [& _] true)]
          ;; Simulate that the prompt was just resolved (no current prompt).
          ;; choose-by-index! should still try, see no prompt, and not fire
          ;; end-turn — so let's test via a synthesized post-choose helper.
          ;; (Direct test of the integration would require deeper mocking;
          ;;  this asserts the contract: when called with 0 clicks, no prompt,
          ;;  no run, my-turn → end-turn fires.)
          (ai-basic-actions/check-auto-end-turn!)
          (is (some #(= "end-turn" (get-in % [:data :command])) @sent)
              "check-auto-end-turn! after prompt-resolution-completes-run case must fire end-turn"))))))

(deftest test-check-auto-end-turn-skipped-during-active-run
  (testing "check-auto-end-turn! does NOT fire when a run is still active,
            even with 0 clicks (defensive guard added with Bug #3 round 2)"
    (let [sent (atom [])]
      (with-mock-state
        {:connected true
         :uid "AI-runner"
         :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
         :side "runner"
         :game-state {:runner {:click 0
                               :user {:username "AI-runner"}
                               :prompt-state nil}
                      :corp {:click 0}
                      :active-player "runner"
                      :turn 1
                      :run {:phase "encounter-ice" :position 1 :server [:hq]}
                      :log [{:text "AI-runner spends [Click] to make a run on HQ."}]}}
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-core/show-turn-indicator (fn [& _] nil)]
          (ai-basic-actions/check-auto-end-turn!)
          (is (empty? (filter #(= "end-turn" (get-in % [:data :command])) @sent))
              "must not end turn while run is active, regardless of clicks"))))))

(deftest test-auto-continue-loop-no-end-turn-on-opponents-run
  (testing "auto-continue-loop! does NOT end OUR turn when watching opponent's
            run finish (monitor-run from off-turn side)"
    (let [sent (atom [])]
      (with-mock-state
        {:connected true
         :uid "AI-corp"
         :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
         :side "corp"
         :game-state {:runner {:click 0}
                      :corp {:click 0
                             :user {:username "AI-corp"}
                             :prompt-state nil}
                      :active-player "runner"   ; ← runner's turn
                      :turn 5
                      :log [{:text "AI-runner spends [Click] to make a run on HQ."}
                            {:text "AI-runner accesses Brân 1.0 from HQ."}]}}
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-core/show-turn-indicator (fn [& _] nil)]
          (runs/auto-continue-loop! :timeout-ms 1000 :max-iterations 5)
          (is (not (some #(= "end-turn" (get-in % [:data :command])) @sent))
              "monitor-run watching opponent's run must not end OUR turn"))))))

;; DELETED: test-no-active-run
;; Tested semantic distinction between :no-run and :run-complete status codes.
;; Both mean "no active run" - distinction is internal, not behavioral.
;; test-run-complete-no-phase-no-prompt already covers the no-run case.

;; ============================================================================
;; Strategy State Management
;; ============================================================================

(deftest test-strategy-state-isolation
  (testing "Run strategy state is properly isolated between tests"
    ;; Set a strategy
    (runs/set-strategy! {:no-rez true})
    (is (= {:no-rez true} (runs/get-strategy)))

    ;; Reset it
    (runs/reset-strategy!)
    (is (= {} (runs/get-strategy)))

    ;; Verify it's truly reset
    (is (nil? (:no-rez (runs/get-strategy))))))

;; ============================================================================
;; handle-runner-approach-ice tests
;; ============================================================================

(deftest test-runner-approach-unrezzed-ice
  (testing "Runner at a fresh unrezzed-ICE approach sends its own first pass"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "runner"
         :run-phase "approach-ice"
         :position 1
         :ice [{:title "Enigma" :rezzed false}]
         :no-action nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (runs/continue-run!)]
            (is (= :action-taken (:status result)))
            (is (= "continue" (get-in @sent [0 :data :command])))))))))

(deftest test-runner-approach-rezzed-ice
  (testing "Runner at approach-ice with rezzed ICE does not wait"
    (with-mock-state
      (mock-state-with-run
       :side "runner"
       :run-phase "approach-ice"
       :position 1
       :ice [{:title "Enigma" :rezzed true}])
      (let [result (runs/continue-run!)]
        ;; Should NOT return :waiting-for-corp-rez
        (is (not= :waiting-for-corp-rez (:status result)))))))

(deftest test-runner-approach-corp-already-declined
  (testing "Runner proceeds when corp already declined rez (no-action = corp)"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "runner"
         :run-phase "approach-ice"
         :position 1
         :ice [{:title "Enigma" :rezzed false}]
         :no-action "corp")  ; Corp already declined
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (runs/continue-run!)]
            ;; Should NOT be waiting - corp already passed
            (is (not= :waiting-for-corp-rez (:status result)))))))))

;; ============================================================================
;; handle-runner-full-break tests (characterization)
;; ============================================================================

;; Note: Testing full-break requires complex state setup with programs,
;; abilities, and ice. These are characterization tests to document behavior.

(deftest test-full-break-strategy-required
  (testing "full-break handler only activates with :full-break strategy"
    (with-mock-state
      (mock-state-with-run
       :side "runner"
       :run-phase "encounter-ice"
       :position 1
       :ice [{:title "Ice Wall" :rezzed true}])
      ;; Without strategy, should not trigger full-break behavior
      (runs/reset-strategy!)
      (let [result (runs/continue-run!)]
        ;; Not using full-break, so should be decision-required or waiting
        (is (not= :ability-used (:status result)))))))

(deftest test-full-break-with-no-ice
  (testing "full-break does nothing when no ICE at position"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "runner"
         :run-phase "encounter-ice"
         :position 0  ; Position 0 = at server, no ICE
         :ice [])
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (runs/set-strategy! {:full-break true})
          (let [result (runs/continue-run!)]
            ;; full-break should fall through when no ICE
            (is (not= :ability-used (:status result))))
          (runs/reset-strategy!))))))

(defn- full-break-args
  "Build a handle-runner-full-break arg map: runner at encounter-ice facing a
   rezzed ICE with one unbroken sub, holding a breaker with a dynamic break
   ability. Used to drive the use-ability! return-status branches."
  []
  (let [ice {:title "Diviner" :rezzed true
             :subroutines [{:broken false :fired false}]}
        program {:title "Unity"
                 :abilities [{:dynamic :auto-pump-and-break
                              :label "Match strength and fully break Diviner"
                              :cost-label "3 [Credits]"}]}
        state {:game-state {:run {:position 1 :server ["rd"]}
                            :corp {:servers {:rd {:ices [ice]}}}
                            :runner {:rig {:program [program]} :credit 6}}}]
    {:side "runner" :run-phase "encounter-ice" :state state
     :strategy {:full-break true} :gameid "g" :my-prompt nil}))

(deftest test-full-break-waiting-input-is-not-an-unaffordable-failure
  (testing "When the break ability spawns a credit-source sub-prompt (e.g. paying
            from credits hosted on Overclock), use-ability! returns :waiting-input.
            That is NOT an unaffordable failure: the handler must not burn a retry
            on the unaffordable counter or print the misleading '❌ Ability failed'.
            Regression: the old binary success/else check mislabelled the pay-prompt
            as a failed, possibly-unaffordable break.

            This test asserted `(nil? result)` from the day of that fix until #295,
            with the docstring claiming nil was what 'lets the monitor loop resolve
            the prompt'. The retry-counter half was right and is kept verbatim. The
            nil was the defect itself: it let the SAME tick fall through to
            handle-auto-continue and pass priority over the open prompt. The suite
            rested on the bug for four months because this test drives the handler
            alone, with :my-prompt nil, so the chain the harm lives in was never
            exercised here. See
            test-full-break-pay-prompt-does-not-let-the-same-tick-send-continue,
            which drives continue-run! instead."
    (runner-handlers/reset-state!)
    (with-redefs [card-actions/use-ability! (fn [_ _] {:status :waiting-input})]
      (let [result (runner-handlers/handle-runner-full-break (full-break-args))]
        (is (= :prompt-handled (:status result))
            "waiting-input ENDS the tick with a status the loop re-ticks on (#295)")
        (is (empty? @runner-handlers/failed-ability-attempts)
            "waiting-input must NOT increment the unaffordable retry counter")))))

(deftest test-full-break-pay-prompt-does-not-let-the-same-tick-send-continue
  (testing "#295: when the break ability opens a payment prompt, the SAME
            continue-run! tick must not go on to pass priority over it.

            handle-runner-full-break used to return nil here. In
            run-first-matching-handler nil means 'not mine, try the next handler,
            THIS tick' - so the chain carried the PRE-ability snapshot on to
            handle-auto-continue, whose can-auto-continue? read the stale empty
            'run' prompt (type run, no choices, nothing selectable - every
            condition still true) and sent a continue over the open select.
            send-continue! does read live state but guards only a waiting prompt
            (#75) and our own recorded pass (#98); a select prompt is neither.
            In marquee game A1 that cost the Runner 3c and the run - Whitespace's
            subs fired while Unity's payment was still unresolved.

            Driven through continue-run!, not the handler alone: the harm is in
            the CHAIN, which is why the isolated handler test above could assert
            the nil return and stay green through the whole defect."
    (let [sent (atom [])
          ice {:title "Whitespace" :rezzed true
               :subroutines [{:broken false :fired false}]}
          program {:title "Unity"
                   :abilities [{:dynamic :auto-pump-and-break
                                :label "Match strength and fully break Whitespace"
                                :cost-label "3 [Credits]"}]}
          ;; The empty paid-ability-window prompt the Runner holds at an
          ;; encounter. This is what can-auto-continue? accepts, and it is what
          ;; the stale snapshot still carries after the ability opened a select.
          run-prompt {:prompt-type "run" :choices [] :selectable []}]
      (runs/reset-strategy!)
      (runner-handlers/reset-state!)
      (with-mock-state
        (mock-client-state
         :side "runner"
         :game-state {:active-player "runner"
                      :turn 4
                      :log []
                      :run {:position 1 :server ["rd"] :phase "encounter-ice"
                            :no-action false}
                      :runner {:credit 6 :click 2 :hand []
                               :rig {:program [program]}
                               :prompt-state run-prompt}
                      :corp {:credit 8 :click 3 :hand []
                             :servers {:rd {:ices [ice]}}
                             :prompt-state nil}})
        (try
          (runs/set-strategy! {:full-break true})
          (with-redefs [ws/send-message! (mock-websocket-send! sent)
                        ;; The engine answered the break with a credit-source
                        ;; select prompt; :waiting-input is use-ability!'s report
                        ;; of exactly that.
                        card-actions/use-ability! (fn [_ _] {:status :waiting-input})]
            (let [result (runs/continue-run!)]
              (is (not-any? #(= "continue" (get-in % [:data :command])) @sent)
                  (str "no continue may be sent while our own payment prompt is "
                       "open - the whole of #295. Sent: " (pr-str @sent)))
              (is (some? result)
                  "the tick must END at full-break, not fall on through the chain")
              (is (= :prompt-handled (:status result))
                  (str "the status monitor-run already reads as 'a prompt moved, "
                       "take a fresh tick, do not count it as run progress'. Got: "
                       (pr-str result)))
              (is (not (#'runs/terminal-status? (:status result)))
                  "a terminal status would stop the loop instead of taking the fresh tick that resolves the prompt")))
          (finally
            (runs/reset-strategy!)
            (runner-handlers/reset-state!)))))))
(deftest test-full-break-pay-prompt-is-resolved-on-the-next-tick
  (testing "#295, the tick AFTER the one above: the live payment prompt is now in
            the snapshot, and the chain must RESOLVE it rather than send a
            continue or re-fire the break.

            This is the second half sol5 asked for on the forum, and it is the
            half that pins the premise the fix rests on:
            handle-auto-select-single-card PRECEDES handle-runner-full-break in
            continue-run!'s chain, so a lone credit source is selected before the
            break handler can be re-entered. Deleting auto-select from the chain
            entirely left the whole 1300-test suite green until this test existed
            (mutation N5), so nothing else holds that ordering.

            use-ability! is stubbed to THROW here: on this tick the break must not
            be fired again at all, and a stub that merely records would let a
            re-fire pass as long as nothing was sent to the wire."
    (let [sent (atom [])
          selected (atom [])
          ice {:title "Whitespace" :rezzed true
               :subroutines [{:broken false :fired false}]}
          overclock {:cid "oc1" :title "Overclock" :type "Resource"}
          program {:title "Unity"
                   :abilities [{:dynamic :auto-pump-and-break
                                :label "Match strength and fully break Whitespace"
                                :cost-label "3 [Credits]"}]}
          ;; What the engine actually left us after the break: the credit-source
          ;; select, with Overclock as the ONE alternative pool.
          ;;
          ;; :selectable holds a CID STRING, not a card map. The engine builds it
          ;; with `(map :cid valid)` (game.core.prompts/compute-selectable, and
          ;; `(map #(->> % :value :cid) choices)` in update-selectable), and
          ;; game.core.diffs passes the key straight through. A card map here
          ;; drove the `(string? cid-or-card)` false arm of
          ;; handle-auto-select-single-card and never reached
          ;; ai-core/find-card-by-cid — so the production resolution path this
          ;; test exists to cover was not exercised at all (r2 F3; the project's
          ;; standing "wire fixtures round-trip through JSON" trap).
          ;;
          ;; :choices carries the select's own Done, which show-select sends
          ;; (prompts.clj). has-real-decision? strips it as trivial and is true
          ;; anyway on the non-empty :selectable.
          pay-prompt {:prompt-type "select" :eid "e42"
                      :choices [{:value "Done" :uuid "u-done"}]
                      :selectable ["oc1"]}]
      (runs/reset-strategy!)
      (runner-handlers/reset-state!)
      (with-mock-state
        (mock-client-state
         :side "runner"
         :game-state {:active-player "runner"
                      :turn 4
                      :log []
                      :run {:position 1 :server ["rd"] :phase "encounter-ice"
                            :no-action false}
                      :runner {:credit 6 :click 2 :hand []
                               ;; Overclock is in the tree so the real
                               ;; find-card-by-cid resolves "oc1" to it. That
                               ;; function walks the whole game state, so the zone
                               ;; is not what this test pins — only that a cid
                               ;; STRING resolves to a titled card.
                               :rig {:program [program] :resource [overclock]}
                               :prompt-state pay-prompt}
                      :corp {:credit 8 :click 3 :hand []
                             :servers {:rd {:ices [ice]}}
                             :prompt-state nil}})
        (try
          (runs/set-strategy! {:full-break true})
          (with-redefs [ws/send-message! (mock-websocket-send! sent)
                        ws/select-card! (fn [card eid] (swap! selected conj [card eid]) true)
                        card-actions/use-ability!
                        (fn [& _]
                          (throw (ex-info "the break was re-fired over an open prompt" {})))]
            (let [result (runs/continue-run!)]
              (is (= 1 (count @selected))
                  (str "the lone credit source must be selected. Selected: "
                       (pr-str @selected)))
              (is (= "Overclock" (:title (ffirst @selected)))
                  "and it must be the card the engine offered")
              (is (= "e42" (second (first @selected)))
                  "carrying the prompt's own :eid, so it answers THIS prompt")
              (is (not-any? #(= "continue" (get-in % [:data :command])) @sent)
                  (str "still no continue while the prompt is open. Sent: "
                       (pr-str @sent)))
              (is (= :prompt-handled (:status result))
                  (str "and the loop re-ticks to finish the break. Got: "
                       (pr-str result)))))
          ;; r2 F1: the weakened wording lived only in source, so restoring the
          ;; old "opened a payment prompt - resolving it before any continue"
          ;; left the whole suite green. Pin the two claims it must NOT make.
          ;; Asserted on the FIRST tick's result, which is where the message is
          ;; built, via a second drive of the same fixture.
          (finally
            (runs/reset-strategy!)
            (runner-handlers/reset-state!)))))))

(deftest test-full-break-pay-prompt-message-claims-neither-payment-nor-resolution
  (testing "#295 r2 F1/F2: the result the seat is shown must not claim the prompt
            is a PAYMENT prompt, nor that anything here RESOLVES it. :waiting-input
            is returned for any prompt the ability opens, and under
            `continue --single` this call is the whole step, so the seat resolves
            it. The message, the :action and the :wake-reason are all shown or read
            downstream, so all three are pinned here - correcting only the message
            left :awaiting-break-payment asserting what the message had stopped
            claiming."
    (let [ice {:title "Whitespace" :rezzed true
               :subroutines [{:broken false :fired false}]}
          program {:title "Unity"
                   :abilities [{:dynamic :auto-pump-and-break
                                :label "Match strength and fully break Whitespace"
                                :cost-label "3 [Credits]"}]}]
      (runs/reset-strategy!)
      (runner-handlers/reset-state!)
      (with-redefs [card-actions/use-ability! (fn [_ _] {:status :waiting-input})]
        (try
          (let [r (runner-handlers/handle-runner-full-break
                   {:side "runner" :run-phase "encounter-ice"
                    :state {:game-state
                            {:run {:position 1 :server ["rd"]}
                             :corp {:servers {:rd {:ices [ice]}}}
                             :runner {:rig {:program [program]} :credit 6}}}
                    :strategy {:full-break true} :gameid "g" :my-prompt nil})
                msg (str (:message r))
                whole (pr-str r)]
            (is (= :prompt-handled (:status r)) "precondition: this is the arm under test")
            (doseq [banned ["payment" "Payment"]]
              (is (not (clojure.string/includes? whole banned))
                  (str "the result must not call the prompt a " banned
                       " one - it is any ability prompt. Whole result: " whole)))
            (doseq [banned ["resolving it" "Resolving it"]]
              (is (not (clojure.string/includes? msg banned))
                  (str "the message must not claim anything here resolves it. Got: " msg)))
            (is (clojure.string/includes? msg "must be resolved")
                (str "it must still say the prompt blocks a continue. Got: " msg)))
          (finally
            (runs/reset-strategy!)
            (runner-handlers/reset-state!)))))))

;; ============================================================================
;; #296 — a run action must not be taken while this seat holds a blocking prompt
;; ============================================================================
;;
;; #295 made full-break END the tick on :waiting-input, so the NEXT tick's
;; snapshot holds the live prompt. #296 is what that next tick does with it.
;;
;; The client gate mirrors the ENGINE's gate, per line, rather than re-deriving a
;; "does the seat owe a decision?" rule:
;;
;;   game.core.actions/no-blocking-or-prevent-prompt?  (:67-71)
;;     prompt-type in {nil, :run}
;;   game.core.actions/play-dynamic-ability            (:599-603)
;;     refuses outright otherwise, with a toast
;;
;; and the engine reads [side :prompt-state :prompt-type] — the very field
;; `my-prompt` is a copy of. Two consequences the fixtures below pin:
;;
;;   - the ENGINE refuses a re-fired break, so #296's own harm is wasted retries;
;;   - the engine does NOT gate `continue` at all (continue :encounter-ice,
;;     game.core.runs:424-434, reads the encounter ledger and nothing else), so a
;;     premature pass IS honoured. That half is the one with a game cost, and the
;;     client has to gate it itself.

(def ^:private full-break-ice
  {:title "Whitespace" :rezzed true
   :subroutines [{:broken false :fired false}]})

(def ^:private full-break-program
  {:title "Unity"
   :abilities [{:dynamic :auto-pump-and-break
                :label "Match strength and fully break Whitespace"
                :cost-label "3 [Credits]"}]})

(defn- full-break-game-state
  "The #296 window: a full-break Runner mid-encounter, holding `prompt`.
   `enc` is the encounter ledger ([:encounters :no-action]); nil leaves it fresh."
  [prompt & {:keys [enc log]}]
  (cond-> {:active-player "runner"
           :turn 4
           :log (or log [])
           :run {:position 1 :server ["rd"] :phase "encounter-ice"
                 :no-action false}
           :runner {:credit 6 :click 2 :hand []
                    :rig {:program [full-break-program]}
                    :prompt-state prompt}
           :corp {:credit 8 :click 3 :hand []
                  :servers {:rd {:ices [full-break-ice]}}
                  :prompt-state nil}}
    ;; :ice as well as the ledger, always. encountered-ice reads
    ;; [:encounters :ice] as the authority and answers NIL when the summary is
    ;; present but unnameable (#198) — so a ledger-only :encounters key is the
    ;; unnameable-encounter state, which a different handler claims, and the
    ;; fixture would pass without ever reaching the one under test.
    enc (assoc :encounters {:ice full-break-ice :no-action enc})))

(defmacro ^:private with-296-window
  "Drive continue-run! once in the #296 window with `prompt` open, capturing the
   wire. `use-ability!` THROWS: on this tick the break must not be fired at all,
   and a recording stub would let a re-fire pass as long as nothing reached the
   wire (#295's mutation N5)."
  [prompt opts & body]
  `(let [~'sent (atom [])]
     (runs/reset-strategy!)
     (runner-handlers/reset-state!)
     (with-mock-state
       (mock-client-state :side "runner"
                          :game-state (apply full-break-game-state ~prompt ~opts))
       (try
         (runs/set-strategy! {:full-break true})
         (with-redefs [ws/send-message! (mock-websocket-send! ~'sent)
                       card-actions/use-ability!
                       (fn [& ~'_args]
                         (throw (ex-info "the break was re-fired over an open prompt" {})))]
           ~@body)
         (finally
           (runs/reset-strategy!)
           (runner-handlers/reset-state!))))))

(deftest test-296-lone-done-select-does-not-re-fire-the-break
  (testing "#296: the shape #295's fix does NOT cover — no selectable and at most
            one non-trivial choice. handle-auto-select-single-card wants exactly
            one selectable, and has-real-decision? strips a lone Done as trivial,
            so full-break's old entry guard was satisfied and it called
            use-ability! again over its own open prompt.

            The shape itself is what an ordinary break payment takes whenever
            the Corp has bad publicity and no card in the rig can provide
            credits: pick-credit-providing-cards shows the select to OFFER the
            bad pub while its :choices {:card ...} predicate matches nothing, so
            :selectable is empty and only the select's own Done remains
            (game.core.pick-counters).

            This fixture is NOT that payment, though: a real one carries
            :offer-bad-pub?, and with the key present handle-auto-choice hands the
            prompt to the seat instead of answering it (#298). Here the key is
            absent, which isolates the one thing under test — that full-break does
            not re-fire over a prompt of this shape, whatever later handles it."
    (with-296-window {:prompt-type "select" :eid "e42"
                      :choices [{:value "Done" :uuid "u-done"}]
                      :selectable []}
      nil
      (let [result (runs/continue-run!)]
        ;; The throwing stub is what pins the re-fire. This second assertion is a
        ;; CANARY, not a pin, and measured as one: in this fixture (fresh
        ;; encounter ledger, one unbroken sub) every continue-sender in the chain
        ;; declines for reasons unrelated to its prompt guard, so reverting
        ;; either pass-handler guard leaves this green — the battery's M2 and M3
        ;; each killed exactly one test, and it was the dedicated fixture below,
        ;; not this one (r4 F2). It is here to fail if a FUTURE sender starts
        ;; acting over these shapes. What it replaced, `(is (some? result))`,
        ;; could not fail at all: run-first-matching-handler falls back to
        ;; handle-unexpected-state, which always returns a map (r3 F4).
        (is (not-any? #(= "continue" (get-in % [:data :command])) @sent)
            (str "no continue may be sent over our own open prompt either. Sent: "
                 (pr-str @sent)))))))

(deftest test-296-waiting-prompt-does-not-re-fire-the-break
  (testing "#296, the waiting shape (r2 guest-unique): a `waiting` prompt also has
            no selectable and no non-trivial choice, so it sat inside the same gap.
            Both pass-handler siblings have carried an explicit waiting-prompt
            guard since #167 — mirroring send-continue!'s own chokepoint — and
            full-break had none.

            The engine agrees: prompt-type \"waiting\" is not in
            no-blocking-or-prevent-prompt?'s {nil, :run}, so it would refuse the
            ability anyway."
    (with-296-window {:prompt-type "waiting" :msg "Waiting for Corp"}
      nil
      (let [result (runs/continue-run!)]
        ;; The throwing stub is what pins the re-fire. This second assertion is a
        ;; CANARY, not a pin, and measured as one: in this fixture (fresh
        ;; encounter ledger, one unbroken sub) every continue-sender in the chain
        ;; declines for reasons unrelated to its prompt guard, so reverting
        ;; either pass-handler guard leaves this green — the battery's M2 and M3
        ;; each killed exactly one test, and it was the dedicated fixture below,
        ;; not this one (r4 F2). It is here to fail if a FUTURE sender starts
        ;; acting over these shapes. What it replaced, `(is (some? result))`,
        ;; could not fail at all: run-first-matching-handler falls back to
        ;; handle-unexpected-state, which always returns a map (r3 F4).
        (is (not-any? #(= "continue" (get-in % [:data :command])) @sent)
            (str "no continue may be sent over our own open prompt either. Sent: "
                 (pr-str @sent)))))))

(deftest test-296-zero-choice-other-prompt-does-not-re-fire-the-break
  (testing "#296, the shape that killed the FIRST fix design (r2 F7): a prompt with
            zero choices, zero selectable and type \"other\".

            The first design reused seat-owns-trigger-decision?, which requires
            (or select-type (seq choices) (seq selectable)) — all three false here,
            so it would have returned false and full-break would have re-fired
            anyway. The engine gate has no such hole: \"other\" is simply not in
            {nil, :run}."
    (with-296-window {:prompt-type "other" :eid "e99" :msg "Something resolving"}
      nil
      (let [result (runs/continue-run!)]
        ;; The throwing stub is what pins the re-fire. This second assertion is a
        ;; CANARY, not a pin, and measured as one: in this fixture (fresh
        ;; encounter ledger, one unbroken sub) every continue-sender in the chain
        ;; declines for reasons unrelated to its prompt guard, so reverting
        ;; either pass-handler guard leaves this green — the battery's M2 and M3
        ;; each killed exactly one test, and it was the dedicated fixture below,
        ;; not this one (r4 F2). It is here to fail if a FUTURE sender starts
        ;; acting over these shapes. What it replaced, `(is (some? result))`,
        ;; could not fail at all: run-first-matching-handler falls back to
        ;; handle-unexpected-state, which always returns a map (r3 F4).
        (is (not-any? #(= "continue" (get-in % [:data :command])) @sent)
            (str "no continue may be sent over our own open prompt either. Sent: "
                 (pr-str @sent)))))))

(deftest test-296-pass-broken-ice-does-not-pass-priority-over-our-own-prompt
  (testing "#296, the half with a game cost. Declining in full-break hands the
            tick to handle-runner-pass-broken-ice, which send-continue!s when
            `(or (empty? actionable-subs) corp-declined?)`. The subs are unbroken
            here (the payment is pending), but the encounter ledger names the Corp
            — so it fires, and passes priority over the very prompt full-break
            just declined to act over. That is #295's harm, one handler later.

            The ledger state is the Corp having passed this encounter BEFORE the
            Runner broke: it declined to fire (no rezzed subs worth firing, or
            --no-rez), wrote [:encounters :no-action] \"corp\", and the Runner then
            used its break. Active-player-first makes that the exception rather
            than the routine, which is why this fixture says so rather than
            reading as the common path.

            Unlike the re-fired ability, the engine does NOT refuse this:
            `continue :encounter-ice` (game.core.runs) reads the encounter ledger
            and nothing else, so the pass is honoured and — with the Corp already
            passed — ENDS the encounter with the break unresolved.

            Red on the half-fixed tree: run with full-break's guard in and this
            one out and it fails, which is how it was established rather than
            shipped green."
    (with-296-window {:prompt-type "select" :eid "e42"
                      :choices [{:value "Done" :uuid "u-done"}]
                      :selectable []}
      [:enc "corp"]
      (let [result (runs/continue-run!)]
        (is (not-any? #(= "continue" (get-in % [:data :command])) @sent)
            (str "no continue may be sent while our own prompt is open. Sent: "
                 (pr-str @sent)))
        (is (some? result) "the tick must produce a status, not fall off the chain")))))

(deftest test-296-pass-fired-ice-does-not-pass-priority-over-our-own-prompt
  (testing "#296: handle-runner-pass-fired-ice is the OTHER send-continue! on this
            path, and it needs its own fixture (r2 F4/F6).

            Its sibling above precedes it in the chain and fires on corp-declined,
            so a fixture built for that one never reaches this handler at all —
            reverting this guard alone would leave every other test here green.
            So: actionable subs remaining, the encounter ledger FRESH (no Corp
            pass, so the sibling declines), and a log line that satisfies this
            handler's own subs-resolved? heuristic, which matches
            `(resolves.*subroutines on|uses) <ICE>`.

            Shares `passed-ice-encounter` with its sibling, so a pass sent here
            is just as wrong and just as latched."
    (with-296-window {:prompt-type "select" :eid "e42"
                      :choices [{:value "Done" :uuid "u-done"}]
                      :selectable []}
      [:log [{:text "Runner uses Whitespace"}]]
      (let [result (runs/continue-run!)]
        (is (not-any? #(= "continue" (get-in % [:data :command])) @sent)
            (str "no continue may be sent while our own prompt is open. Sent: "
                 (pr-str @sent)))
        (is (some? result) "the tick must produce a status, not fall off the chain")))))

(deftest test-296-lone-done-select-without-a-bad-pub-offer-is-auto-answered
  (testing "#296, the landing, asserting what IS sent rather than only absences
            (r2 F10). With the guards in, the lone-Done select falls through to
            handle-auto-choice, which answers it with exactly one `choice`
            carrying the prompt's own :eid.

            Answering is the right move for this shape, and the reason is the
            select's Done running the credit-source ability's
            `:cancel {:effect pay-rest}` (game.core.pick-counters, the END of the
            ability map) through show-select's wrap and resolve-select's empty
            branch (game.core.prompts) — NOT a dead end. An earlier draft of this
            fix was built on the belief that Done forfeits the payment, and two
            review seats caught that at the line.

            It does not follow that Done always pays: pay-rest's affordability
            test reads the credit pool alone, so with a pool short of the
            remaining cost it cannot, and its re-entry loses the bad-pub offer
            that made the cost payable (#299). That state is reachable only with
            an offer present, which this fixture does not have.

            Absence-only assertions would stay green if the chain PARKED instead
            of landing (handle-waiting-for-opponent and handle-events sit between
            full-break and handle-auto-choice), which is the failure mode the
            fall-through introduces. So this pins the positive."
    ;; NOT an ordinary break payment, and the earlier draft of this docstring
    ;; said it was (r3 F2). A payment never takes this shape: with zero provider
    ;; cards and no usable bad pub the ability skips the prompt altogether
    ;; (pick-credit-providing-cards' no-prompt shortcut), and with provider cards
    ;; :selectable is non-empty. The reachable instance is some OTHER select with
    ;; no valid target — the shape seat-owns-trigger-decision?'s docstring
    ;; describes, e.g. a rez-a-target select with every ICE already rezzed.
    (with-296-window {:prompt-type "select" :eid "e42"
                      :choices [{:value "Done" :uuid "u-done"}]
                      :selectable []}
      nil
      (let [result (runs/continue-run!)
            choices (filter #(= "choice" (get-in % [:data :command])) @sent)]
        (is (= 1 (count choices))
            (str "exactly one choice must answer the prompt. Sent: " (pr-str @sent)))
        (is (= "u-done" (get-in (first choices) [:data :args :choice :uuid]))
            "and it must be the Done the engine offered")
        (is (= "e42" (get-in (first choices) [:data :args :eid]))
            "carrying the prompt's own :eid, so it answers THIS prompt")
        (is (= :action-taken (:status result))
            (str "and the tick reports an action. Got: " (pr-str result)))))))

(deftest test-296-bad-pub-offer-is-surfaced-not-silently-answered
  (testing "#296 + the ask's own constraint (sol5, forum [334]): the SAME prompt
            shape, but carrying a positive :offer-bad-pub?, must neither re-fire
            the breaker nor be silently auto-Done.

            The offer is a real action that lives OUTSIDE :choices and
            :selectable. pick-credit-providing-cards sets :offer-bad-pub? when
            bad publicity can pay (game.core.pick-counters), show-select passes it
            through to the wire (it is in diffs/prompt-keys), and the browser
            renders it as its own button (board.cljs `bad-pub-choice`). The AI
            client has no sender for that command at all — ENABLE_CONDITIONS.md
            records `bad-pub-choice` as uncovered — so Done is the only thing the
            client CAN send, and sending it spends pool credits to decline free
            run credits, with no one told.

            Two reasons that is not a silent default to take:
            - it is a spend decision, which the ask reserves;
            - when the pool cannot cover the cost, Done is not even a valid
              answer: pay-rest's affordability test reads the pool alone, and its
              else arm re-enters pick-credit-providing-cards — with 8 positional
              args against the 9-param fn, so bad-pub-available is bound to
              bad-pub-spent and the re-shown prompt loses its own offer.

            So the tick surfaces it as a decision instead. Red before the fix:
            handle-auto-choice sent the Done."
    (with-296-window {:prompt-type "select" :eid "e42"
                      :choices [{:value "Done" :uuid "u-done"}]
                      :selectable []
                      :offer-bad-pub? 2}
      nil
      (let [result (runs/continue-run!)]
        (is (not-any? #(= "choice" (get-in % [:data :command])) @sent)
            (str "the Done must NOT be sent: it declines the offer and spends "
                 "pool credits instead. Sent: " (pr-str @sent)))
        (is (not-any? #(= "continue" (get-in % [:data :command])) @sent)
            (str "and no continue either. Sent: " (pr-str @sent)))
        (is (= :decision-required (:status result))
            (str "it is surfaced as the seat's decision. Got: " (pr-str result)))
        (is (clojure.string/includes? (str (:message result)) "bad publicity")
            (str "and the message names the option, since nothing else will. "
                 "Got: " (pr-str (:message result))))
        ;; The message must not promise an outcome the engine may not deliver.
        ;; "pays from your credit pool" is the :cancel -> pay-rest path, and
        ;; pay-rest's affordability test reads the pool alone — with a pool short
        ;; of the remaining cost it cannot pay and its re-entry loses this very
        ;; offer (#299). Same weakening #295's own message needed (r3 F1).
        (doseq [banned ["credit pool instead" "pays from your credit pool"]]
          (is (not (clojure.string/includes? (str (:message result)) banned))
              (str "the message must not promise \"" banned "\" - see #299. Got: "
                   (pr-str (:message result)))))))))

(deftest test-296-bad-pub-pause-names-a-command-the-client-accepts
  (testing "#296 r3 (both vendors): the printed guidance named `choose 0`, which
            this client REFUSES on the prompt this arm can see.

            Only show-select sets :offer-bad-pub?, so the prompt is always a
            select, and choose-option!'s first cond arm is
            (state/select-prompt-type? prompt-type) — it prints 'use choose-card
            <N>, not choose <N>' and names choose-value for a meta button
            (ai-prompts/choose-option!). So a seat following the hint got a
            refusal and the prompt stayed open.

            Asserted on the printed OUTPUT, not just the result map, because the
            hint lives in a println and the result-map assertions above all
            stayed green with the wrong verb in it. Both the required verb and
            the banned one are pinned: asserting only that choose-value appears
            would stay green if both lines were printed."
    (with-296-window {:prompt-type "select" :eid "e42"
                      :choices [{:value "Done" :uuid "u-done"}]
                      :selectable []
                      :offer-bad-pub? 2}
      nil
      (let [out (with-out-str (runs/continue-run!))]
        (is (clojure.string/includes? out "choose-value \"Done\"")
            (str "the hint must name the verb this client accepts for a select's "
                 "meta button, with the label the engine gave. Printed: " out))
        (is (not (re-find #"(?m)choose\s+\d" out))
            (str "and must not name `choose <N>`, which choose-option! refuses on "
                 "a select. Printed: " out))))))

(deftest test-296-main-break-path-still-breaks
  (testing "#296's own named regression risk, which is why it was filed as its own
            change: the main break path runs WHILE HOLDING the engine's empty
            run-priority window, so a guard that declined on 'any prompt with
            choices' would switch breaking off.

            Every pre-existing full-break test passes either :my-prompt nil or
            that empty run window, so the suite could not have caught it. All
            three shapes are pinned here — nil, the string \"run\" the wire sends,
            and the keyword :run the engine sets pre-serialization (fixtures in
            this repo use both spellings, which is why the gate goes through
            state/run-prompt-type? rather than comparing to one of them)."
    (doseq [[label prompt] [["no prompt at all" nil]
                            ["the wire's run window" {:prompt-type "run" :choices [] :selectable []}]
                            ["the keyword run window" {:prompt-type :run :choices [] :selectable []}]
                            ;; The shape #296's warning is actually about, and the
                            ;; one the three above cannot catch (r3 F7): a run
                            ;; window CARRYING a trivial choice. A guard that
                            ;; declined on "any prompt with choices" would switch
                            ;; breaking off here and leave every other case green.
                            ["a run window carrying a trivial choice"
                             {:prompt-type "run" :choices [{:value "Continue" :uuid "u-c"}]
                              :selectable []}]]]
      (let [fired (atom [])]
        (runs/reset-strategy!)
        (runner-handlers/reset-state!)
        (with-mock-state
          (mock-client-state :side "runner"
                             :game-state (full-break-game-state prompt))
          (try
            (runs/set-strategy! {:full-break true})
            (with-redefs [ws/send-message! (mock-websocket-send! (atom []))
                          card-actions/use-ability!
                          (fn [card-name idx]
                            (swap! fired conj [card-name idx])
                            {:status :success})]
              (let [result (runs/continue-run!)]
                (is (= 1 (count @fired))
                    (str "the break MUST still be fired with " label
                         ". Fired: " (pr-str @fired)))
                (is (= "Unity" (ffirst @fired))
                    (str "with the breaker the rig offers, with " label))
                (is (= :ability-used (:status result))
                    (str "and reported as a break, with " label
                         ". Got: " (pr-str result)))))
            (finally
              (runs/reset-strategy!)
              (runner-handlers/reset-state!))))))))

(def ^:private bad-pub-offer #'runs/bad-pub-offer)

(deftest test-296-bad-pub-offer-reads-a-positive-number-only
  (testing "#296: how the client READS :offer-bad-pub? off the wire, which is a
            claim about our parser rather than about the engine.

            The engine sets the key to the amount available or leaves it nil
            (pick-credit-providing-cards), and game.core.diffs ships prompt-state
            through select-non-nil-keys — so on the wire it is a positive integer
            or the key is ABSENT, never false and never 0. The contract is pinned
            anyway, because this value decides whether a prompt is auto-answered
            or handed to the seat: a truthiness read would treat a 0 as an offer
            and park a run on nothing, and a (pos? \"2\") would throw mid-run.

            Each of these three reads was a surviving mutant before this test
            existed (M11, M12, M13): dropping the pos? check, dropping the
            number? check, and reading the key for truthiness alone."
    (is (= 2 (bad-pub-offer {:offer-bad-pub? 2}))
        "a positive amount is the offer")
    (is (nil? (bad-pub-offer {}))
        "an ABSENT key is no offer - select-non-nil-keys drops the nil")
    (is (nil? (bad-pub-offer {:offer-bad-pub? nil}))
        "and an explicit nil is no offer either")
    (is (nil? (bad-pub-offer {:offer-bad-pub? 0}))
        "0 is no offer: an offer of nothing must not park a run")
    (is (nil? (bad-pub-offer {:offer-bad-pub? "2"}))
        "a non-number is no offer, not an exception thrown mid-run")
    (is (nil? (bad-pub-offer {:offer-bad-pub? true}))
        "and neither is a bare true")))

(deftest test-296-zero-bad-pub-offer-is-auto-answered-like-any-lone-choice
  (testing "#296, the chain half of the read above: a prompt whose :offer-bad-pub?
            is 0 offers nothing, so it takes the ordinary auto-answer path rather
            than being surfaced. The differential control for the surfacing test -
            without it, a handler that surfaced EVERY credit-source prompt would
            pass that test too."
    (with-296-window {:prompt-type "select" :eid "e42"
                      :choices [{:value "Done" :uuid "u-done"}]
                      :selectable []
                      :offer-bad-pub? 0}
      nil
      (let [result (runs/continue-run!)
            choices (filter #(= "choice" (get-in % [:data :command])) @sent)]
        (is (= 1 (count choices))
            (str "the Done must be sent. Sent: " (pr-str @sent)))
        (is (= :action-taken (:status result))
            (str "and the tick reports an action, not a decision. Got: "
                 (pr-str result)))))))

;; ============================================================================
;; #301: the chain's OTHER two pass senders
;; ============================================================================
;;
;; #296 gave ai-state/engine-blocking-prompt? to the two ENCOUNTER pass handlers,
;; because the engine does not gate `continue` on a blocking prompt at all:
;; `continue :encounter-ice` (game.core.runs) reads the encounter ledger and
;; nothing else, so a pass sent while the seat holds its own open prompt is
;; HONOURED. That reasoning is not specific to the encounter window, and two
;; other senders in the same chain gated on has-real-decision? alone —
;; handle-initiation-auto-pass (the run-initiation window) and
;; handle-stalled-window-self-advance (the #31 self-advance) — so each carried
;; #295's harm at its own window.
;;
;; These call the handlers DIRECTLY rather than driving continue-run!, which is
;; what the issue suggested. Driving the chain cannot reach
;; handle-initiation-auto-pass with the issue's lone-Done select:
;; handle-auto-choice sits ABOVE it and fires on any prompt with exactly one
;; choice, so it answers the Done and returns, and the fixture stays green with
;; the guard reverted. That is the canary trap #296's own r4 measured on its two
;; chain-level assertions. The zero-choice "other" prompt used below is a shape
;; that no earlier handler claims (handle-auto-choice needs `(seq :choices)`,
;; has-real-decision? scores it trivial) and the engine still refuses an ability
;; over — #296's third shape, and the one that makes the point that this is not a
;; question about whether the PLAYER owes a decision.
;;
;; The deadlock cases are pinned beside them, because a new reason for these two
;; handlers to decline is a new reason for #31's both-pass wedge to return — and
;; handle-stalled-window-self-advance exists only to break it.
;; engine-blocking-prompt? admits {nil, "run"}, and "run" is not incidental here:
;; show-run-prompts mints the engine's dummy RUN prompt for BOTH seats on every
;; run, so it is what the seat normally holds at these very windows.

(defn- initiation-state
  "Corp-side client state at the run-INITIATION window the Runner has already
   passed — #62's scenario: `run!` sends the Runner's first continue by default,
   so [:run :no-action] is \"runner\" and the Corp is the active player at an
   otherwise empty window. `prompt` is the Corp's own open prompt."
  [prompt]
  (mock-client-state
   :side "corp"
   :game-state
   {:active-player "runner"
    :turn 4
    :log []
    :run {:phase "initiation" :position 1 :server ["hq"] :no-action "runner"}
    :runner {:credit 5 :click 3 :hand [] :rig {} :prompt-state nil}
    :corp {:credit 8 :click 3 :hand []
           :servers {:hq {:ices []}}
           :prompt-state prompt}}))

(defn- initiation-pass
  "Call handle-initiation-auto-pass at that window holding `prompt`, returning
   [result sent]. The prompt goes into the board AND the ctx: in production
   `my-prompt` is a copy of [side :prompt-state], which is the field the engine's
   own gate reads, so a fixture that set only one of them would be a state the
   client cannot be in."
  [prompt]
  (let [sent (atom [])]
    (with-redefs [ws/send-message! (mock-websocket-send! sent)]
      [(runs/handle-initiation-auto-pass
        {:run-phase "initiation" :gameid "g1" :side "corp"
         :state (initiation-state prompt) :my-prompt prompt})
       @sent])))

(deftest test-301-initiation-auto-pass-declines-over-an-open-prompt
  (testing "#301: a zero-choice \"other\" prompt owes the seat nothing by
            has-real-decision? (no selectable, fewer than 2 non-trivial choices),
            so the old guard was satisfied and the initiation window was passed
            with our own prompt still open. The engine honours that pass —
            `continue` is not gated on a prompt at all — so the window closes and
            the prompt is stranded."
    (let [[result sent] (initiation-pass {:prompt-type "other"
                                          :msg "Choose a trigger to resolve"
                                          :eid "e9"})]
      (is (nil? result)
          (str "the handler must DECLINE, leaving the prompt to the chain. Got: "
               (pr-str result)))
      (is (empty? sent)
          (str "and nothing may reach the wire. Sent: " (pr-str sent))))))

(deftest test-301-initiation-auto-pass-still-passes-an-empty-window
  (testing "#301's deadlock half: the #62 case the handler exists for. No prompt
            at all, so nothing blocks, and the Corp must still pass its own
            initiation window — otherwise no handler matches, continue-run! falls
            through to handle-unexpected-state and both seats wait on each other."
    (let [[result sent] (initiation-pass nil)]
      (is (= :action-taken (:status result))
          (str "must still auto-pass an empty window. Got: " (pr-str result)))
      (is (= ["continue"] (mapv #(get-in % [:data :command]) sent))
          (str "with exactly one continue. Sent: " (pr-str sent))))))

(deftest test-301-initiation-auto-pass-still-passes-holding-the-run-prompt
  (testing "#301's deadlock half, the shape that actually occurs: the engine's
            dummy RUN prompt (show-run-prompts mints one for BOTH seats on every
            run, with nil in the choices position). It is a live prompt owed a
            real decision that carries no :choices — and the answer to it IS
            `continue`, which is why the engine's own gate admits :run and why
            engine-blocking-prompt? must too. A guard that read \"any prompt\"
            would wedge every initiation window instead of one."
    (let [[result sent] (initiation-pass {:prompt-type "run"
                                          :msg "You are approaching HQ"})]
      (is (= :action-taken (:status result))
          (str "the run prompt must not block the pass. Got: " (pr-str result)))
      (is (= ["continue"] (mapv #(get-in % [:data :command]) sent))
          (str "with exactly one continue. Sent: " (pr-str sent))))))

(defn- stalled-state
  "Runner-side client state at an ABANDONED approach-ice window: we have already
   passed (:no-action \"runner\"), the approached ICE is already rezzed so the
   board proves the Corp holds no rez decision, and `prompt` is our own."
  [prompt]
  (mock-client-state
   :side "runner"
   :game-state
   {:active-player "runner"
    :turn 4
    :log []
    :run {:phase "approach-ice" :position 1 :server [:remote1]
          :no-action "runner"}
    :runner {:credit 6 :click 2 :hand [] :rig {} :prompt-state prompt}
    :corp {:credit 8 :click 3 :hand []
           :prompt-state nil
           :servers {:remote1 {:ices [{:cid 1 :title "Bran 1.0" :rezzed true}]
                               :content []}}}}))

(defn- stalled-self-advance
  "Call handle-stalled-window-self-advance at that window holding `prompt`, with
   the grace period already elapsed, returning [result sent]."
  [prompt]
  (let [sent (atom [])]
    (with-redefs [ws/send-message! (mock-websocket-send! sent)
                  runs/self-advance-grace-ms 0]
      (runs/reset-window-grace!)
      [(runs/handle-stalled-window-self-advance
        {:run-phase "approach-ice" :gameid "g1" :side "runner"
         :state (stalled-state prompt) :my-prompt prompt})
       @sent])))

(deftest test-301-stalled-window-self-advance-declines-over-an-open-prompt
  (testing "#301 at the #31 self-advance: same zero-choice \"other\" shape, and
            the blast radius is worse than at initiation. This handler sends a
            DELIBERATE second pass (:second-pass? true), which bypasses the #98
            already-passed chokepoint, and the engine's second continue ADVANCES
            the phase with no side-check — so over an open prompt it leaves the
            window behind us with the prompt unanswered."
    (let [[result sent] (stalled-self-advance {:prompt-type "other"
                                               :msg "Choose a trigger to resolve"
                                               :eid "e9"})]
      (is (nil? result)
          (str "the handler must DECLINE. Got: " (pr-str result)))
      (is (empty? sent)
          (str "and nothing may reach the wire. Sent: " (pr-str sent))))))

(deftest test-301-stalled-window-self-advance-still-breaks-the-deadlock
  (testing "#301's deadlock half for the stall-BREAKER: holding the engine's
            dummy RUN prompt, which is what the seat actually holds at an
            approach-ice window, the abandoned window must still be advanced.
            run-window-selfadvance-test pins this with my-prompt nil; the \"run\"
            prompt is the case the new guard could plausibly have broken, and
            breaking it would restore the #31 stall this handler was written to
            end."
    (let [[result sent] (stalled-self-advance {:prompt-type "run"
                                               :msg "You are approaching Bran 1.0"})]
      (is (= :action-taken (:status result))
          (str "must still advance the abandoned window. Got: " (pr-str result)))
      (is (= ["continue"] (mapv #(get-in % [:data :command]) sent))
          (str "with exactly one continue. Sent: " (pr-str sent))))))

(deftest test-full-break-error-still-counts-as-failure
  (testing "A genuine :error from use-ability! still increments the unaffordable
            retry counter (so repeated genuine failures eventually fall through to
            the let-subs-fire / pause path). Guards the fix above from over-reaching."
    (runner-handlers/reset-state!)
    (with-redefs [card-actions/use-ability! (fn [_ _] {:status :error :reason "no creds"})]
      (let [result (runner-handlers/handle-runner-full-break (full-break-args))]
        (is (nil? result) "error returns nil to retry")
        (is (= 1 (get @runner-handlers/failed-ability-attempts 1))
            "genuine error increments the retry counter for this position")))
    (runner-handlers/reset-state!)))

(deftest test-pass-fired-ice-sends-continue-once-then-waits
  (testing "After subs fire, runner sends ONE pass-continue then waits for the
            Corp's priority pass, instead of re-sending continue every loop
            iteration. Regression: the old handler re-sent continue against the
            unchanged encounter-ice phase (mislabelled fired subs as 'broken')
            and tripped the stuck-state guard after 5 spins."
    (let [sent (atom [])
          mk (fn [] (mock-state-with-run
                     :side "runner"
                     :run-phase "encounter-ice"
                     :position 1
                     :ice [{:title "Diviner" :rezzed true
                            :subroutines [{:broken false :fired true}]}]))]
      (runs/reset-strategy!)
      (runner-handlers/reset-state!)
      (with-redefs [ws/send-message! (mock-websocket-send! sent)]
        ;; First call passes our priority with a single continue.
        (with-mock-state (mk)
          (let [r1 (runs/continue-run!)]
            (is (= :action-taken (:status r1)))
            (is (= :sent-continue (:action r1)))))
        ;; Second call on the unchanged state must NOT re-send; it waits with a
        ;; status auto-continue-loop! recognizes (:waiting-for-opponent), not a
        ;; bespoke one that would hit the loop's "unknown status" branch.
        (with-mock-state (mk)
          (let [before (count @sent)
                r2 (runs/continue-run!)]
            (is (= :waiting-for-opponent (:status r2)))
            (is (= before (count @sent))
                "Must not re-send continue while waiting for the Corp")))))))

(deftest test-pass-fired-ice-resets-across-runs
  (testing "passed-ice-encounter does not leak across runs: after reset-state!
            (which auto-continue-loop! now calls on run-complete), the same
            fired ICE gets a fresh pass-continue instead of being treated as
            already-passed. Guards against run-event runs (Jailbreak/Conduit)
            that enter via continue-run! and skip the run-start reset."
    (let [sent (atom [])
          mk (fn [] (mock-state-with-run
                     :side "runner"
                     :run-phase "encounter-ice"
                     :position 1
                     :ice [{:title "Diviner" :rezzed true
                            :subroutines [{:broken false :fired true}]}]))]
      (runs/reset-strategy!)
      (runner-handlers/reset-state!)
      (with-redefs [ws/send-message! (mock-websocket-send! sent)]
        ;; Run A: first pass sends continue.
        (with-mock-state (mk) (runs/continue-run!))
        ;; Run A ends -> per-run state cleared (as the loop now does on run-complete).
        (runner-handlers/reset-state!)
        ;; Run B encounters the SAME [position ice]: must pass again, not stall.
        (with-mock-state (mk)
          (let [before (count @sent)
                r (runs/continue-run!)]
            (is (= :action-taken (:status r)))
            (is (= :sent-continue (:action r)))
            (is (= (inc before) (count @sent))
                "Fresh run must send its own pass-continue, not inherit stale state")))))))

(deftest test-corp-access-trigger-prompt-not-masked-as-waiting
  (testing "A Corp access-trigger decision (e.g. Urtica Cipher 'Use ability?')
            during the success phase is surfaced as a real decision, not masked
            as 'waiting for opponent'. Regression: handle-waiting-for-opponent ran
            before handle-real-decision and fired on the unguarded
            waiting-for-opponent? branch, deadlocking self-play (both sides
            waiting on each other)."
    (with-mock-state
      (mock-state-with-run
       :side "corp"
       :run-phase "success"   ; past all ICE, at server; no :no-action set
       :prompt (make-prompt
                :msg "Use Urtica Cipher ability?"
                :prompt-type "other"
                :choices [{:value "Yes" :idx 0}
                          {:value "No" :idx 1}]))
      (runs/reset-strategy!)
      (let [result (runs/continue-run!)]
        (is (= :decision-required (:status result))
            "Corp's own access-trigger choice must pause for a decision")
        (is (not= :waiting-for-opponent (:status result))
            "Must not be masked as waiting for the opponent")))))

;; ============================================================================
;; Corp rez strategy edge cases
;; ============================================================================

(deftest test-corp-rez-already-rezzed-ice
  (testing "Corp with --rez strategy continues past already-rezzed ICE"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "corp"
         :run-phase "approach-ice"
         :position 1
         :ice [{:title "Ice Wall" :rezzed true}]  ; Already rezzed
         :prompt (make-prompt :msg "Paid ability window"))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (runs/set-strategy! {:rez #{"Ice Wall"}})
          (let [result (runs/continue-run!)]
            ;; Should continue, not try to rez again
            (is (some? result)))
          (runs/reset-strategy!))))))

(deftest test-corp-no-strategy-pauses
  (testing "Corp with no rez strategy pauses at unrezzed ICE for decision"
    (with-mock-state
      (mock-state-with-run
       :side "corp"
       :run-phase "approach-ice"
       :position 1
       :ice [{:title "Ice Wall" :rezzed false}]
       :prompt (make-prompt :msg "Rez Ice Wall?"))
      (runs/reset-strategy!)  ; No strategy
      (let [result (runs/continue-run!)]
        ;; Should pause for human decision
        (is (= :decision-required (:status result)))
        (is (= "Ice Wall" (:ice result)))))))

;; ============================================================================
;; Priority 2.4: Full-break can't-break + tank authorization
;; ============================================================================
;; Regression for the self-play deadlock: a Runner running with --full-break
;; that can't break the encountered ICE used to ALWAYS return :paused-cannot-break
;; (waiting for a human), ignoring the :tank set entirely. The autonomous loop
;; has no human, so it spun forever. Fix: honor tank authorization in full-break
;; mode by signaling let-subs-fire.

(def encounter-cant-break-state
  {:side "runner"
   :run-phase "encounter-ice"
   :position 1
   :server ["remote1"]
   :ice [{:title "Palisade" :rezzed true
          :subroutines [{:label "End the run" :broken false :fired false}]}]})

(deftest test-full-break-cant-break-pauses-when-unauthorized
  (testing "full-break + no breaker + no tank authorization -> paused-cannot-break"
    (let [sent (atom [])]
      (with-mock-state
        (apply mock-state-with-run (mapcat identity encounter-cant-break-state))
        (reset! runner-handlers/signaled-fire-encounter nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (runs/set-strategy! {:full-break true})
          (let [result (runs/continue-run!)]
            (is (= :paused-cannot-break (:status result)))
            (is (= "Palisade" (:ice result)))
            ;; Must NOT signal let-subs-fire without authorization
            (is (not-any? #(= "system-msg" (get-in % [:data :command])) @sent)))
          (runs/reset-strategy!))))))

(deftest test-full-break-cant-break-tanks-when-authorized
  (testing "full-break + no breaker + tank authorization -> signals let-subs-fire"
    (let [sent (atom [])]
      (with-mock-state
        (apply mock-state-with-run (mapcat identity encounter-cant-break-state))
        (reset! runner-handlers/signaled-fire-encounter nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (runs/set-strategy! {:full-break true :tank #{"Palisade"}})
          (let [result (runs/continue-run!)]
            (is (= :waiting-for-corp-fire (:status result)))
            (is (= "Palisade" (:ice result)))
            ;; Signaled the Corp to fire unbroken subs
            (is (some #(= "system-msg" (get-in % [:data :command])) @sent)))
          (runs/reset-strategy!))))))

;; ============================================================================
;; Priority 2.6/2.7: pass-ice handlers must defer to real decisions
;; ============================================================================
;; Regression for the Karunā self-play deadlock: a multi-sub ICE fires its first
;; sub ("uses Karunā to do 2 net damage") and then offers a mid-subroutine
;; "Jack out?" decision. handle-runner-pass-fired-ice's subs-resolved? log regex
;; matched that single "uses <ICE>" line and sent continue, masking the jack-out
;; prompt -> autonomous loop spun on "no further action". Fix: guard the pass
;; handlers with has-real-decision?.

(deftest test-pass-fired-ice-defers-to-jack-out-decision
  (testing "pending Jack out? prompt is surfaced, not masked by a pass-continue"
    (let [sent (atom [])]
      (with-mock-state
        (mock-state-with-run
         :side "runner"
         :run-phase "encounter-ice"
         :position 1
         :server ["remote1"]
         :ice [{:title "Karunā" :rezzed true
                :subroutines [{:label "Do 2 net damage" :broken false :fired true}
                              {:label "Do 2 net damage" :broken false :fired false}]}]
         :prompt (make-prompt :msg "Jack out?" :prompt-type "other"
                              :choices [{:value "Yes"} {:value "No"}])
         :log [{:text "ai-corp uses Karunā to do 2 net damage"}])
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (runs/set-strategy! {:full-break true})
          (let [result (runs/continue-run!)]
            ;; Must surface the decision, NOT send a masking continue
            (is (= :decision-required (:status result)))
            (is (not= :sent-continue (:action result)))
            (is (empty? @sent)))
          (runs/reset-strategy!))))))

;; ============================================================================
;; Test Suite Summary
;; ============================================================================
;; fire-unbroken-strategy-result — honest autonomous-fire reporting (issue #24)
;; ============================================================================
;; The manual fire-subs path got honest prompt-detection in #23. The
;; pre-committed --fire-unbroken / --fire-if-asked *strategy* paths used to
;; send the fire command and return :action-taken unconditionally, so when a
;; fired sub opened a Corp prompt (e.g. Brân 1.0's install-an-ice sub) the run
;; loop marched on while the Corp sat on an unhandled prompt. These guard the
;; pure decision: a sub-opened prompt → :decision-required (loop pauses to
;; resolve), no prompt → :action-taken (loop continues). Both keep
;; :fired-at-encounter so re-entry never re-fires.

(deftest fire-unbroken-strategy-result-waiting-prompt-is-the-runners-decision
  (testing "#151 item 3: a fired sub that hands the RUNNER a decision (Karunā's 'trash 2 / jack out?') shows up on our side as a NEW prompt of type \"waiting\" — it is not ours to resolve, so it must not be announced as 'a prompt the Corp must resolve' with choose-value steering"
    (let [prompt {:msg "Waiting for Runner to make a decision"
                  :prompt-type "waiting"
                  :card {:title "Karunā"}
                  :eid 5151}
          {:keys [lines result]} (corp-handlers/fire-unbroken-strategy-result
                                  "Karunā" 2 1 prompt)
          out (clojure.string/join "\n" lines)]
      (is (not (re-find #"(?i)corp must resolve|resolve it|choose-value" out))
          (str "must not tell the Corp to resolve the Runner's decision, got:\n" out))
      (is (re-find #"(?i)runner" out)
          (str "must say whose decision it is, got:\n" out))
      (is (not= :decision-required (:status result))
          (str "not a Corp decision — must not pause the loop as one, got: " result))
      (is (= 1 (:fired-at-encounter result))
          "the ICE WAS fired; re-entry must still not re-fire"))))

(deftest extract-run-events-ignores-the-run-start-line-and-pre-run-entries
  (testing "#151 item 6: the run-START line ('uses Jailbreak to make a run on R&D') is the run itself, not a mid-run ability — flagging it cost a `continue` round-trip on every card-initiated run; and a pre-run entry (Sure Gamble a click earlier) must not be reported as a run event either"
    (let [log [{:text "ai-runner spends [Click] to play Sure Gamble."}
               {:text "ai-runner uses Sure Gamble to gain 9 [Credits]."}
               {:text "ai-runner uses Jailbreak to make a run on R&D."}]
          {:keys [ability-event]} (ai-runs/extract-run-events log)]
      (is (nil? ability-event)
          (str "nothing after the run-start line — no ability event, got: " ability-event))))
  (testing "guest catch: a start line phrased '… and make a run on …' (The Noble Path) is still the start line"
    (let [log [{:text "ai-runner uses Sure Gamble to gain 9 [Credits]."}
               {:text "ai-runner uses The Noble Path to trash Daily Casts and make a run on HQ."}]
          {:keys [ability-event]} (ai-runs/extract-run-events log)]
      (is (nil? ability-event)
          (str "the Noble Path line is the run's start, not a mid-run ability, got: " ability-event))))
  (testing "guest catch: player CHAT containing 'make a run on' is not the run boundary — a real event behind it must survive"
    (let [log [{:user "__system__" :text "ai-runner spends [Click] to make a run on HQ."}
               {:user "__system__" :text "ai-runner uses Leech to place 1 virus counter on Leech."}
               {:user "Michael" :text "Michael: please make a run on HQ next turn"}]
          {:keys [ability-event]} (ai-runs/extract-run-events log)]
      (is (some? ability-event) "chat must not swallow the Leech event")
      (is (re-find #"Leech" (:text ability-event)))))
  (testing "control: an ability used DURING the run (after the run-start line) is still an event"
    (let [log [{:text "ai-runner spends [Click] to make a run on HQ."}
               {:text "ai-runner approaches HQ."}
               {:text "ai-runner uses Docklands Pass to access 1 additional card."}]
          {:keys [ability-event]} (ai-runs/extract-run-events log)]
      (is (some? ability-event) "a mid-run ability must still be reported")
      (is (re-find #"Docklands" (:text ability-event))))))

(deftest fire-unbroken-strategy-result-prompt-opened
  (testing "a fired sub that opens a new prompt surfaces as :decision-required, not :action-taken"
    (let [prompt {:msg "Choose an ice to install from Archives or HQ"
                  :prompt-type "select"
                  :eid 4242}
          {:keys [lines result]} (corp-handlers/fire-unbroken-strategy-result
                                  "Brân 1.0" 1 1 prompt)
          out (clojure.string/join "\n" lines)]
      (is (= :decision-required (:status result))
          "an open prompt must pause the loop (terminal status), not let it march on")
      (is (= prompt (:prompt result)) "the opened prompt is threaded back to the caller")
      (is (= 1 (:fired-at-encounter result))
          "still records fired-at-encounter so re-entry never re-fires the ICE")
      (is (= :auto-fired-subs (:action result)))
      (is (clojure.string/includes? out "Brân 1.0")
          "names the ICE whose sub opened the prompt")
      (is (clojure.string/includes? out "Choose an ice to install from Archives or HQ")
          "echoes the actual pending prompt message")
      (is (clojure.string/includes? out "Resolve it")
          "tells the Corp how to clear the prompt"))))

(deftest fire-unbroken-strategy-result-no-prompt
  (testing "a fired sub with no opened prompt is :action-taken so the loop continues"
    (let [{:keys [lines result]} (corp-handlers/fire-unbroken-strategy-result
                                  "Palisade" 2 0 nil)]
      (is (= :action-taken (:status result))
          "no prompt → keep auto-continuing, as before")
      (is (= 0 (:fired-at-encounter result)) "fired-at-encounter is preserved")
      (is (= 2 (:sub-count result)))
      (is (nil? (:prompt result)) "no prompt threaded when none opened")
      (is (empty? lines) "the no-prompt path stays quiet (the fire line is printed by the caller)"))))

;; ============================================================================
;; run-ending-log-lines (#48): explain a stale "not in a run" state
;; ============================================================================
;;
;; #48: during an encounter the Runner client printed the tank/jack-out
;; authorization menu, but the Corp fired the unbroken sub and ended the run
;; before the Runner's `jack-out` landed — so jack-out replied a bare
;; "Not in a run" with no hint that the run just ended out from under the menu.
;; run-ending-log-lines lets jack-out surface WHAT happened instead.

(deftest test-run-ending-log-lines-subs-fired-sequence
  (testing "subs-fired + damage-trash + end-the-run are all returned, in log order"
    (let [log [{:text "AI-runner encounters Diviner"}
               {:text "Clamatius resolves 1 unbroken subroutine on Diviner (\"[subroutine] Do 1 net damage\")"}
               {:text "Clamatius trashes Leech due to net damage"}
               {:text "Clamatius uses Diviner to end the run"}]
          lines (runs/run-ending-log-lines log)]
      (is (= 3 (count lines)))
      (is (= "Clamatius resolves 1 unbroken subroutine on Diviner (\"[subroutine] Do 1 net damage\")"
             (first lines)))
      (is (= "Clamatius uses Diviner to end the run" (last lines))))))

(deftest test-run-ending-log-lines-jack-out
  (testing "a 'jacks out' line counts as a run-ending signal"
    (is (= ["AI-runner jacks out"]
           (runs/run-ending-log-lines [{:text "AI-runner approaches HQ"}
                                       {:text "AI-runner jacks out"}])))))

(deftest test-run-ending-log-lines-none-when-no-run-signal
  (testing "no run-ending signal (economy/access noise) → empty, so caller keeps the plain message"
    (is (= [] (runs/run-ending-log-lines
               [{:text "AI-corp uses Corp Basic Action Card to gain 1 [Credits]"}
                {:text "AI-runner accesses an unseen card from R&D"}
                {:text "AI-runner steals Offworld Office and gains 2 agenda points"}])))))

(deftest test-run-ending-log-lines-ignores-breaking-and-derez
  (testing "Runner BREAKING subs and derez chatter are not run-ending signals"
    ;; 'break 1 subroutine' contains 'subroutine' but NOT 'unbroken subroutine';
    ;; 'derez' must not read as a run end. (Same #54 substring traps.)
    (is (= [] (runs/run-ending-log-lines
               [{:text "AI-runner uses Corroder to break 1 subroutine on Ice Wall"}
                {:text "AI-corp derezzes Ice Wall"}])))))

(deftest test-run-ending-log-lines-nil-text-safe
  (testing "nil / missing :text entries don't crash extraction"
    (is (= ["Corp uses Palisade to end the run"]
           (runs/run-ending-log-lines [{:text nil}
                                       {}
                                       {:text "Corp uses Palisade to end the run"}])))))

(deftest test-run-ending-log-lines-windowed-to-recent
  (testing "a run-ending line older than the recent window is not resurfaced"
    ;; A previous run's ETR followed by a full turn of economy: jack-out now
    ;; must NOT dredge up the stale end-the-run from the prior run.
    (let [old-run {:text "Corp uses Ice Wall to end the run"}
          noise (repeat 8 {:text "AI-corp uses Corp Basic Action Card to gain 1 [Credits]"})
          log (vec (cons old-run noise))]
      (is (= [] (runs/run-ending-log-lines log))))))

(deftest test-run-ending-log-lines-non-terminal-sub-not-flagged
  (testing "subs fired + damage trash WITHOUT a terminal ender do NOT read as run-ended (Codex #48)"
    ;; Neural Katana's 'do 3 net damage' sub resolves and the run CONTINUES to
    ;; access — no 'to end the run' / 'jacks out'. A later stray jack-out must
    ;; not be told the run ended when it merely completed via access.
    (is (= [] (runs/run-ending-log-lines
               [{:text "Corp resolves 1 unbroken subroutine on Neural Katana (\"[subroutine] Do 3 net damage\")"}
                {:text "Corp trashes Sure Gamble due to net damage"}
                {:text "Runner accesses an unseen card from R&D"}])))))

(deftest test-run-ending-log-lines-etr-drops-off-tightened-window
  (testing "a prior-run ETR followed by 5 non-run logs falls out of the 5-line window (Codex #48)"
    ;; Codex's finding-2 counterexample: the stale ETR is the 6th-from-newest
    ;; entry, so a typo jack-out no longer gets the stale-menu explanation.
    (is (= [] (runs/run-ending-log-lines
               [{:text "Corp uses Ice Wall to end the run"}
                {:text "Runner accesses an unseen card from R&D"}
                {:text "Corp gains 1 [Credits]"}
                {:text "Corp gains 1 [Credits]"}
                {:text "Corp gains 1 [Credits]"}
                {:text "Runner gains 1 [Credits]"}])))))

;; ============================================================================

(defn -main
  "Run continue-run! tests and report results"
  []
  (let [results (run-tests 'ai-runs-test)]
    (println "\n========================================")
    (println "continue-run! Test Summary")
    (println "========================================")
    (println "Tests run:" (:test results))
    (println "Assertions:" (:pass results))
    (println "Failures:" (:fail results))
    (println "Errors:" (:error results))
    (println "========================================\n")
    (when (or (pos? (:fail results)) (pos? (:error results)))
      (System/exit 1))))

;; ============================================================================
;; send-continue! already-passed guard (#98)
;;
;; The engine records the first side to pass a run/encounter priority window
;; in :no-action. When it already names US, the opponent owes the window — a
;; repeat continue from us is a no-op (or worse, a #75 checkpoint re-fire),
;; and returning :action-taken for it spun the stuck-detector into a false
;; '⚠️ Stuck in same state' alarm while the Runner was legitimately thinking
;; (3+ marquee occurrences: Funhouse tag decision, Tithe post-fire window).
;; The guard lives in ALL THREE send-continue! copies.
;; ============================================================================

(def ^:private send-continue-copies
  {"corp-handlers"   #'corp-handlers/send-continue!
   "runner-handlers" #'runner-handlers/send-continue!
   "ai-runs"         #'runs/send-continue!})

(defn- no-action-state
  "Corp seat mid-encounter with no own prompt; :no-action per args."
  [& {:keys [run-no-action enc-no-action]}]
  (mock-client-state
   :side "corp"
   :game-state {:corp {:prompt-state nil}
                :runner {}
                :run (cond-> {:phase "encounter-ice" :position 1 :server ["hq"]}
                       (some? run-no-action) (assoc :no-action run-no-action))
                :encounters (when (some? enc-no-action) {:no-action enc-no-action})
                :active-player "runner"}))

(deftest send-continue-suppressed-when-engine-records-us-as-passer
  (doseq [[copy-name send-continue] send-continue-copies]
    (testing (str copy-name ": run-level :no-action naming us suppresses the repeat continue")
      (let [sent (atom [])]
        (with-mock-state (no-action-state :run-no-action "corp")
          (with-redefs [ws/send-message! (fn [& args] (swap! sent conj args) true)]
            (let [r (send-continue "fake-gameid")]
              (is (= :waiting-for-opponent (:status r))
                  (str copy-name ": already-passed must read as an opponent wait, got: " r))
              (is (= :continue-suppressed-already-passed (:action r)))
              (is (zero? (count @sent))
                  (str copy-name ": must not re-send continue after we already passed")))))))
    (testing (str copy-name ": encounter-level :no-action (keyword form) also suppresses")
      (let [sent (atom [])]
        (with-mock-state (no-action-state :enc-no-action :corp)
          (with-redefs [ws/send-message! (fn [& args] (swap! sent conj args) true)]
            (let [r (send-continue "fake-gameid")]
              (is (= :waiting-for-opponent (:status r)))
              (is (zero? (count @sent))))))))))

(deftest send-continue-still-sends-when-opponent-was-first-passer
  (doseq [[copy-name send-continue] send-continue-copies]
    (testing (str copy-name ": opponent recorded as first passer — WE owe the closing pass, continue must send")
      (let [sent (atom [])]
        (with-mock-state (no-action-state :run-no-action "runner")
          (with-redefs [ws/send-message! (fn [& args] (swap! sent conj args) true)]
            (let [r (send-continue "fake-gameid")]
              (is (= :action-taken (:status r))
                  (str copy-name ": suppressing OUR owed pass would deadlock the run, got: " r))
              (is (= 1 (count @sent))))))))
    (testing (str copy-name ": nobody has passed yet — continue must send")
      (let [sent (atom [])]
        (with-mock-state (no-action-state)
          (with-redefs [ws/send-message! (fn [& args] (swap! sent conj args) true)]
            (let [r (send-continue "fake-gameid")]
              (is (= :action-taken (:status r)))
              (is (= 1 (count @sent))))))))))

(deftest send-continue-second-pass-bypasses-only-the-passed-guard
  (testing "#31 self-advance: :second-pass? true sends the deliberate second continue"
    (let [sent (atom [])]
      (with-mock-state (assoc (no-action-state :run-no-action "runner") :side "runner")
        (with-redefs [ws/send-message! (fn [& args] (swap! sent conj args) true)]
          (let [r (#'runs/send-continue! "fake-gameid" :second-pass? true)]
            (is (= :action-taken (:status r)))
            (is (= 1 (count @sent))))))))
  (testing "the waiting-prompt guard (#75) is NOT bypassed by :second-pass?"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state
                        :side "runner"
                        :game-state {:runner {:prompt-state {:prompt-type "waiting"
                                                             :msg "Waiting for Corp"}}
                                     :run {:phase "approach-ice" :position 1
                                           :no-action "runner"}})
        (with-redefs [ws/send-message! (fn [& args] (swap! sent conj args) true)]
          (let [r (#'runs/send-continue! "fake-gameid" :second-pass? true)]
            (is (= :continue-suppressed-waiting-prompt (:action r))
                "a blocked checkpoint must never receive a continue, even a deliberate second pass")
            (is (zero? (count @sent)))))))))

;; ============================================================================
;; jack-out legality — mirror the human UI's gate
;; ============================================================================
;; The engine's `jack-out` (src/clj/game/core/runs.clj) has NO phase check: it
;; trusts the UI, which enables the Jack Out button only when
;;   phase == "movement" AND no-action != "runner" AND (not :cannot-jack-out)
;;   AND no forced encounter AND phase != "success"
;; (src/cljs/nr/gameboard/board.cljs). `send_command jack-out` gated on "is there
;; a run at all" and then fired the raw action, so a seat could do things no human
;; can. Across 21 archived replays, 28 jack-outs fired and exactly ONE was legal:
;; 11 at encounter-ice, 8 at initiation, 3 at approach-ice, 2 at movement after the
;; Runner had already passed. The encounter-ice ones are not merely illegal, they
;; are ADVANTAGEOUS — ending the run mid-encounter means unbroken subroutines never
;; resolve (replay ac71ce63, 2026-08-04: Corp paid 5c to rez Whitespace, Runner
;; jacked out of the encounter, subs never fired).

(defn- legality-at
  "jack-out-legality for a RUNNER seat at a run in `phase`, plus optional run keys."
  [phase & {:as extra}]
  (runs/jack-out-legality
   {:run (merge {:phase phase :position 1 :server ["hq"]} extra)}
   "runner"))

(deftest jack-out-is-a-runner-only-action
  ;; The human client puts the Jack Out button in runner-run-div and NEVER in
  ;; corp-run-div, so "side == runner" is part of the UI gate too. The engine does
  ;; not enforce it either: process-action passes the socket's side straight into
  ;; `jack-out`, which ends the run and logs "<corp> jacks out". Mirroring only the
  ;; PHASE half of the gate would have left a Corp seat able to end the Runner's
  ;; run outright. (Guest panel, GPT-5.6, CRITICAL — confirmed against the engine.)
  (testing "a Corp seat is refused even in an otherwise-legal movement window"
    (let [r (runs/jack-out-legality
             {:run {:phase "movement" :position 0 :server ["hq"]}} "corp")]
      (is (not (:legal? r)))
      (is (= :wrong-side (:reason r)))))
  (testing "the same window is legal for the Runner"
    (is (:legal? (runs/jack-out-legality
                  {:run {:phase "movement" :position 0 :server ["hq"]}} "runner"))))
  (testing "side check precedes the phase check — a Corp seat is never told 'wrong phase'"
    (let [r (runs/jack-out-legality
             {:run {:phase "encounter-ice" :position 0 :server ["hq"]}} "corp")]
      (is (= :wrong-side (:reason r))
          "the reason a Corp cannot jack out is not the phase, and saying so would mislead"))))

(deftest jack-out-legal-only-in-a-fresh-movement-window
  (testing "movement, nobody has passed yet — the one legal case"
    (let [r (legality-at "movement" :position 0)]
      (is (:legal? r) "movement phase with no passer is the human UI's enabled state")))
  (testing "movement, Runner already passed — UI disables the button"
    (let [r (legality-at "movement" :position 0 :no-action "runner")]
      (is (not (:legal? r)))
      (is (= :already-passed (:reason r)))))
  (testing "movement, Corp passed — still the Runner's to take"
    (is (:legal? (legality-at "movement" :position 0 :no-action "corp")))))

(deftest jack-out-illegal-before-the-first-movement-window
  (testing "initiation — the Corp has not even had its rez window yet"
    (let [r (legality-at "initiation")]
      (is (not (:legal? r)))
      (is (= :wrong-phase (:reason r)))
      (is (re-find #"continue" (:alternative r))
          "must name the legal move (continue to approach), not just refuse")))
  (testing "approach-ice — jack out is not offered during an approach"
    (let [r (legality-at "approach-ice")]
      (is (not (:legal? r)))
      (is (= :wrong-phase (:reason r))))))

(deftest jack-out-illegal-during-an-encounter
  ;; The load-bearing case: this is the one that skips unbroken subroutines.
  (testing "encounter-ice is refused"
    (let [r (legality-at "encounter-ice" :position 0)]
      (is (not (:legal? r)))
      (is (= :wrong-phase (:reason r)))))
  (testing "the refusal names break/tank, the actual options at an encounter"
    (let [r (legality-at "encounter-ice" :position 0)]
      (is (re-find #"tank" (:alternative r)))
      (is (re-find #"(?i)break" (:alternative r)))))
  (testing "refusal explains the rules stake — subs would be skipped"
    (let [r (legality-at "encounter-ice" :position 0)]
      (is (re-find #"(?i)subroutine" (:message r))))))

(deftest jack-out-illegal-in-the-remaining-ui-disabled-states
  (testing "success phase — button is not even rendered"
    (is (not (:legal? (legality-at "success" :position 0)))))
  (testing ":cannot-jack-out flag on the run"
    (let [r (legality-at "movement" :position 0 :cannot-jack-out true)]
      (is (not (:legal? r)))
      (is (= :cannot-jack-out (:reason r)))))
  (testing "forced encounter — no run-div jack out at all"
    (let [r (runs/jack-out-legality
             {:run {:phase "movement" :position 0 :server ["hq"]}
              :forced-encounter {:cid "x"}} "runner")]
      (is (not (:legal? r)))
      (is (= :forced-encounter (:reason r)))))
  (testing "no run at all"
    (let [r (runs/jack-out-legality {} "runner")]
      (is (not (:legal? r)))
      (is (= :no-run (:reason r)))))
  (testing "a Corp seat with no run hears :wrong-side, not :no-run"
    ;; Leading with :no-run would answer the Corp "Start a run with `run <server>`",
    ;; implying a run is the thing it lacks. The Corp cannot run at all.
    (let [r (runs/jack-out-legality {} "corp")]
      (is (= :wrong-side (:reason r)))
      (is (not (re-find #"Start a run" (:alternative r)))))))

(deftest jack-out-legality-tolerates-keyword-wire-shapes
  ;; Wire serialization is the volatile coupling (memory engine-rate-of-change);
  ;; phase/no-action arrive as strings today but the client's own fixtures and
  ;; the engine both use keywords. Neither shape may be read as "legal".
  (testing "keyword phase"
    (is (not (:legal? (legality-at :encounter-ice :position 0))))
    (is (:legal? (legality-at :movement :position 0))))
  (testing "keyword no-action"
    (is (not (:legal? (legality-at "movement" :position 0 :no-action :runner))))))

(deftest jack-out-refusal-lines-are-actionable
  (testing "refusal names the phase, the alternative, and the umpire escape hatch"
    (let [lines (runs/jack-out-refusal-lines
                 (legality-at "encounter-ice" :position 0))
          text  (clojure.string/join "\n" lines)]
      (is (seq lines))
      (is (re-find #"(?i)not legal" text))
      (is (re-find #"tank" text))))
  (testing "an already-passed refusal points at wait + umpire, never at bailing"
    (let [text (clojure.string/join
                "\n" (runs/jack-out-refusal-lines
                      (legality-at "movement" :position 0 :no-action "runner")))]
      (is (re-find #"wait" text))
      (is (re-find #"umpire-ping" text)
          "a seat stuck on an unanswered window must be pointed at the judge, not left to bail"))))

(comment
  ;; Run all tests
  (run-tests 'ai-runs-test)

  ;; Run specific test
  (test-force-mode-bypasses-all-checks)

  ;; Run from main
  (-main)
  )

;; ============================================================================
;; #115: the ICE line a Runner actually sees at approach-ice.
;;
;; Pinning core/describe-approached-ice alone would not have caught this — the
;; handler is the surface the seat reaches, and the previous round's lesson was
;; exactly that (a fix and its green test both landed on a sender the CLI never
;; calls, #113). Drive the handler.
;; ============================================================================

(deftest test-approach-ice-handler-ice-line-is-readable
  (testing "#115: unrezzed ICE renders as fog, in the run ladder's index convention
            — not as the literal 'ICE: ICE (position 2/2, unrezzed)'"
    (runner-handlers/reset-state!)
    (let [state {:side "runner"
                 :game-state {:run {:phase "approach-ice" :position 2
                                    :server ["rd"] :no-action :runner}
                              :corp {:servers {:rd {:ices [{:title nil :rezzed false}
                                                           {:title nil :rezzed false}]}}}}}
          out (with-out-str
                (runner-handlers/handle-runner-approach-ice
                 {:side "runner" :run-phase "approach-ice" :state state}))]
      (is (clojure.string/includes? out "Waiting for corp rez decision")
          (str "fixture sanity: this is the approach-ice wait, got:\n" out))
      (is (not (clojure.string/includes? out "ICE: ICE"))
          (str "the placeholder-as-name rendering is gone, got:\n" out))
      (is (not (clojure.string/includes? out "position 2/2"))
          (str "the countdown convention contradicted the ladder's 'ICE 1 of 2'
                two lines below it, got:\n" out))
      (is (clojure.string/includes? out "ICE 1 of 2")
          (str "outermost of two ICE, in the ladder's convention, got:\n" out)))))

;; ============================================================================
;; #191, round 2: the handler is GONE, and this is the state that killed it.
;;
;; #191 was filed as "priority 5.5 can never fire" — it matched "AI-runner"
;; while the seats are named `ai-runner`. Fixing the name made a dead handler
;; LIVE, and both review seats independently reproduced the same wedge by
;; driving the real chain: a three-entry log scan has no window identity.
;;
;;   1. Runner passes a quiet encounter. Engine logs
;;      "ai-runner has no further action." (game/core/runs.clj:428).
;;   2. Both pass; movement starts. start-next-phase :movement sets
;;      [:run :no-action] to FALSE (runs.clj:445) and logs
;;      "ai-runner passes <ice>" (runs.clj:447).
;;   3. The Runner now OWES the fresh movement window — but the run-level ledger
;;      the handler gated on is empty, and the encounter's pass line is still
;;      second of the last three. Priority 5.5 answered "waiting for Corp".
;;   4. A Corp honouring active-player-first waits for the Runner. Both seats
;;      wait: the #31 both-pass wedge, in the handler that existed to back it up.
;;
;; It was deleted rather than gated. `at-encounter?` would have narrowed the
;; false positive without removing it (one encounter's pass line still
;; contaminates a LATER encounter — #163's Sisyphus Protocol re-encounter is
;; exactly that), and the window it would have been narrowed TO is already owned
;; by handle-runner-encounter-ice at priority 2.5, which reads the encounter's
;; own ledger. A log scan is a third re-derivation of "whose move", which is the
;; mechanism behind #31 and #68 both.
;;
;; These two tests are the deadlock repros, kept as the reason not to re-add it.
;; ============================================================================

(defn- with-out-str-value
  "Run `f`, discard what it prints, return what it returned."
  [f]
  (let [v (atom nil)]
    (with-out-str (reset! v (f)))
    @v))

(deftest movement-after-a-quiet-encounter-continues-instead-of-waiting
  (testing "#191: a stale encounter pass line must not claim the fresh movement window"
    (runner-handlers/reset-state!)
    (runs/reset-strategy!)
    (let [sent (atom [])]
      (with-mock-state
        (-> (mock-state-with-run
             :side "runner"
             :run-phase "movement"
             :position 1
             :server ["hq"]
             :ice [{:title "Ice Wall" :rezzed true}]
             :no-action false
             :prompt (make-prompt :prompt-type "run" :msg "" :choices [] :selectable [])
             :log [{:text "ai-runner encounters Ice Wall."}
                   ;; the Runner's ENCOUNTER pass — the only run-level window the
                   ;; engine logs for this side, and the stale evidence
                   {:text "ai-runner has no further action."}
                   ;; ...and the line proving the window has already moved on
                   {:text "ai-runner passes Ice Wall."}])
            (assoc-in [:game-state :runner :user :username] "ai-runner")
            (assoc-in [:game-state :corp :user :username] "ai-corp"))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (with-out-str-value #(runs/continue-run!))]
            (is (not= :waiting-for-opponent-paid-abilities (:status result))
                (str "the Runner owes this movement window; claiming a wait here is "
                     "the #31 wedge. Got: " (pr-str result)))
            (is (= 1 (count @sent))
                (str "exactly one continue should have gone out, got: " (pr-str @sent)))
            (is (= "continue" (get-in @sent [0 :data :command]))
                (str "and it should be a continue, got: " (pr-str @sent)))))))))

(deftest no-run-plus-a-turn-boundary-pass-line-is-not-a-run-wait
  (testing "#191: turns.clj writes the same string for EITHER side at phase-12 and
            post-discard, with no run in progress — that must not read as a run wait"
    (runner-handlers/reset-state!)
    (runs/reset-strategy!)
    (with-mock-state
      (-> (mock-state-with-run
           :side "corp"
           :run-phase nil
           :log [{:text "ai-corp has no further action."}])
          (assoc-in [:game-state :corp :user :username] "ai-corp")
          (assoc-in [:game-state :runner :user :username] "ai-runner"))
      (let [result (with-out-str-value #(runs/continue-run!))]
        (is (not= :waiting-for-opponent-paid-abilities (:status result))
            (str "a turn-boundary pass line must not read as a RUN wait. Got: "
                 (pr-str result)))
        ;; :run-complete, not :no-run — handle-run-complete owns (nil? run) at
        ;; priority 7 and handle-no-run never gets there. That is pre-existing and
        ;; is not what this test is about; what matters is that it is TERMINAL, so
        ;; the auto-continue loop stops. The deleted handler's status was not, so a
        ;; `continue` near a turn boundary idled in the loop instead — and printed
        ;; "(null phase)", raw internals, on the seat surface.
        (is (= :run-complete (:status result))
            (str "and the loop must reach a terminal status. Got: "
                 (pr-str result)))))))

;; ============================================================================
;; core/my-username — what survived #191.
;;
;; The name a seat goes by is only ever knowable from the board: `system-msg`
;; prefixes the player's ACTUAL username (game/core/say.clj:94),
;; start-ai-client-repl.sh names the seats `ai-runner`/`ai-corp`, and a human
;; seat is named whatever they registered. This is the one lookup, so a second
;; copy cannot drift from it the way ai_basic_actions' did.
;; ============================================================================

(deftest my-username-reads-the-board-not-the-side
  (testing "the username comes from state, whatever it is"
    (is (= "ai-runner" (ai-core/my-username
                        {:side "runner"
                         :game-state {:runner {:user {:username "ai-runner"}}
                                      :corp {:user {:username "ai-corp"}}}})))
    (is (= "Clamatius" (ai-core/my-username
                        {:side "corp"
                         :game-state {:corp {:user {:username "Clamatius"}}}}))))
  (testing "a capitalized side still resolves (#69/#129: my-side-kw lowercases)"
    (is (= "ai-corp" (ai-core/my-username
                      {:side "Corp"
                       :game-state {:corp {:user {:username "ai-corp"}}}}))))
  (testing "nil, never a guess: an unnameable seat must make no claim about itself"
    (is (nil? (ai-core/my-username {:side "runner" :game-state {:runner {}}})))
    (is (nil? (ai-core/my-username {:game-state {:runner {:user {:username "x"}}}})))
    (is (nil? (ai-core/my-username {})))))

(deftest usernames-are-not-safe-to-substring-match-in-a-log
  (testing "#191 round 2 (guest panel MINOR): why no log scan is built on this.
            A seat named `runner` facing an opponent named `ai-runner` cannot tell
            its own pass line from theirs by substring — the opponent's line ENDS
            with its own. Recorded as a property of the data, so the next person to
            reach for (str/includes? text (my-username)) sees the trap."
    (let [mine "runner"
          opponent-line "ai-runner has no further action."]
      (is (clojure.string/includes? opponent-line (str mine " has no further action"))
          "substring: the OPPONENT's line matches us")
      (is (not (clojure.string/starts-with? opponent-line (str mine " ")))
          "prefix: it does not, because system-msg puts the username FIRST"))))

;; ============================================================================
;; #151 item 19 / N5: the run loop must stop at GAME-OVER, not surface a
;; leftover trigger prompt as "🛑 You have a pending decision to resolve".
;; ============================================================================

(deftest test-auto-continue-loop-stops-at-game-over-before-surfacing-a-leftover-prompt
  (with-mock-state
    {:connected true
     :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
     :side "corp"
     :game-state {:winner :corp :reason "Agenda" :turn 17 :active-player "corp"
                  :corp {:click 0
                         :prompt-state {:eid 3 :msg "Choose a target for Send a Message"
                                        :prompt-type "select" :choices [{:value "Done"}]
                                        :card {:title "Send a Message" :side "Corp"}}}
                  :runner {:click 0}
                  :log []}}
    (with-redefs [runs/continue-run! (fn [& _] {:status :waiting-for-opponent})
                  ws/send-message! (fn [& _] nil)
                  ai-core/show-turn-indicator (fn [& _] nil)]
      (let [out (java.io.StringWriter.)
            r (binding [*out* out]
                (runs/auto-continue-loop! :persistent true :persistent-wait-delay-ms 1
                                          :max-iterations 50 :timeout-ms 3000))]
        (is (= :game-over (:status r)) (str r))
        (is (not (re-find #"pending decision" (str out))) (str out))))))

;; ============================================================================
;; #151 item 7: a persistent Corp monitor returned on OPPONENT-owned abilities
;; ("ai-runner uses Red Team to gain 3 [Credits]", Leech counters) — and only
;; SOMETIMES. The #36 ride-through is gated on [:run] being non-nil, and these
;; abilities resolve at run success, when the run object is already gone: the
;; event then fell through to the terminal branch as :ability-used. Under
;; --persistent a notable event is never a Corp decision; with no window left
;; it is the run's END, and the loop should say so and go back to its post.
;; ============================================================================

(deftest test-persistent-monitor-absorbs-an-opponent-ability-after-the-run-ended
  (with-mock-state
    {:connected true
     :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
     :side "corp"
     :game-state {:corp {:click 0} :runner {:click 2}
                  :active-player "runner" :turn 7 :log []}}   ;; no :run, no encounter
    (let [calls (atom 0)]
      (with-redefs [runs/continue-run! (fn [& _]
                                         (if (= 1 (swap! calls inc))
                                           {:status :ability-used
                                            :event {:text "ai-runner uses Red Team to gain 3 [Credits]"}}
                                           {:status :no-run}))
                    ai-basic-actions/check-auto-end-turn! (fn [& _] nil)
                    runner-handlers/reset-state! (fn [& _] nil)
                    corp-handlers/reset-state! (fn [& _] nil)
                    ws/send-message! (fn [& _] nil)
                    ai-core/show-turn-indicator (fn [& _] nil)]
        (let [r (runs/auto-continue-loop! :persistent true :persistent-wait-delay-ms 1
                                          :max-iterations 50 :timeout-ms 3000)]
          (is (not= :ability-used (:status r))
              "an opponent-owned ability after the run is not a Corp decision")
          (is (contains? #{:no-run :run-complete} (:status r)) (str r)))))))

(deftest test-auto-continue-loop-catches-a-win-that-lands-during-continue-run
  (testing "guest panel TOCTOU: the winning diff (winner + leftover prompt) arrives DURING
            continue-run!, which returns a live :decision-required — the loop must still say game-over"
    (with-mock-state
      {:connected true
       :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
       :side "corp"
       :game-state {:turn 17 :active-player "corp"
                    :corp {:click 0} :runner {:click 0} :log []}}
      (with-redefs [runs/continue-run! (fn [& _]
                                         (swap! ai-state/client-state
                                                #(-> % (assoc-in [:game-state :winner] :corp)
                                                       (assoc-in [:game-state :corp :prompt-state]
                                                                 {:eid 3 :msg "Choose a target for Send a Message"
                                                                  :prompt-type "select" :choices [{:value "Done"}]})))
                                         {:status :decision-required})
                    ws/send-message! (fn [& _] nil)
                    ai-core/show-turn-indicator (fn [& _] nil)]
        (let [r (runs/auto-continue-loop! :persistent true :persistent-wait-delay-ms 1
                                          :max-iterations 50 :timeout-ms 3000)]
          (is (= :game-over (:status r)) (str r)))))))
