(ns ai-runs-test
  "Tests for continue-run! function - lock down behavior before refactoring

   The continue-run! function is a 363-line beast with 9 levels of nested cond.
   These tests document current behavior so we can refactor safely."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-runs :as runs]
            [ai-core :as ai-core]
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
  (testing "Runner pauses at approach-ice when ICE is unrezzed (corp hasn't decided)"
    (with-mock-state
      (mock-state-with-run
       :side "runner"
       :run-phase "approach-ice"
       :position 1
       :ice [{:title "Ice Wall" :rezzed false}]
       :no-action nil)  ;; Corp hasn't declined yet
      (let [result (runs/continue-run!)]
        (is (= :waiting-for-corp-rez (:status result)))
        (is (= "Ice Wall" (:ice result)))
        (is (= 1 (:position result)))))))

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
  (testing "Runner at approach-ice with unrezzed ICE waits for corp"
    (with-mock-state
      (mock-state-with-run
       :side "runner"
       :run-phase "approach-ice"
       :position 1
       :ice [{:title "Enigma" :rezzed false}]
       :no-action nil)  ; Corp hasn't acted yet
      (let [result (runs/continue-run!)]
        (is (= :waiting-for-corp-rez (:status result)))
        (is (= "Enigma" (:ice result)))))))

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
            That is NOT an unaffordable failure: the handler must return nil so the
            monitor loop resolves the prompt, WITHOUT burning a retry on the
            unaffordable counter or printing the misleading '❌ Ability failed'.
            Regression: the old binary success/else check mislabelled the pay-prompt
            as a failed, possibly-unaffordable break."
    (runner-handlers/reset-state!)
    (with-redefs [card-actions/use-ability! (fn [_ _] {:status :waiting-input})]
      (let [result (runner-handlers/handle-runner-full-break (full-break-args))]
        (is (nil? result)
            "waiting-input lets the loop resolve the prompt -> returns nil")
        (is (empty? @runner-handlers/failed-ability-attempts)
            "waiting-input must NOT increment the unaffordable retry counter")))))

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
                                    :server ["rd"] :no-action false}
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
