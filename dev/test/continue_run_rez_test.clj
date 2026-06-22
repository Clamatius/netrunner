(ns continue-run-rez-test
  "Tests for continue-run rez decision detection.

   These tests focus on the CRITICAL bug: continue-run must pause when corp
   has a rez decision, even when called from runner's side (where runner has
   no prompt and is just waiting)."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-actions :as ai]
            [ai-run-corp-handlers :as corp-handlers]
            [ai-websocket-client-v2 :as ws]))

;; =============================================================================
;; Test: Waiting for Opponent's Rez Decision
;; =============================================================================

(deftest test-runner-waits-for-corp-rez-decision
  (testing "Runner calls continue-run while corp has rez decision - must pause (not auto-continue)"
    (with-mock-state
      (mock-client-state
       :side "runner"
       :prompt nil  ; Runner has NO prompt - just waiting
       :game-state
       {:run {:phase "approach-ice"
              :position 1  ; approaching outermost ICE (1 = first ICE, 0 = at server)
              :server [:hq]}
        :runner {:prompt-state nil}  ; No runner prompt
        :corp {:prompt-state {:msg "Rez Tithe?"
                             :prompt-type "run"
                             :choices []  ; Empty choices = paid ability window
                             :selectable [{:cid 123 :title "Tithe" :rezzed false}]}
               :servers {:hq {:ices [{:cid 123 :title "Tithe" :rezzed false}]}}}})

      ;; This is the BUG: current code will auto-continue through this
      ;; Expected: detect corp has rez opportunity and PAUSE
      (let [result (ai/continue-run!)]
        (is (= :waiting-for-corp-rez (:status result))
            "Should pause when waiting for corp rez decision")
        (is (= "Waiting for corp to decide: rez Tithe or continue" (:message result))
            "Should show what we're waiting for")))))

(deftest test-corp-has-rez-opportunity-at-approach
  (testing "Detect when corp has rez opportunity at approach-ice phase"
    (let [state (mock-client-state
                 :side "corp"
                 :game-state
                 {:run {:phase "approach-ice"
                        :position 1  ; approaching outermost ICE
                        :server [:hq]}
                  :corp {:prompt-state {:msg "Rez Tithe?"
                                       :prompt-type "run"
                                       :choices []
                                       :selectable [{:cid 123 :title "Tithe" :rezzed false}]}
                         :servers {:hq {:ices [{:cid 123 :title "Tithe" :rezzed false}]}}}})]

      (is (ai/corp-has-rez-opportunity? state)
          "Should detect corp has rez opportunity at approach-ice"))))

(deftest test-no-rez-opportunity-when-ice-rezzed
  (testing "No rez opportunity when ICE already rezzed"
    (let [state (mock-client-state
                 :side "corp"
                 :game-state
                 {:run {:phase "approach-ice"
                        :position 0
                        :server [:hq]}
                  :corp {:prompt-state {:msg "Paid ability window"
                                       :prompt-type "run"
                                       :choices []}
                         :servers {:hq {:ices [{:cid 123 :title "Tithe" :rezzed true}]}}}})]

      (is (not (ai/corp-has-rez-opportunity? state))
          "Should NOT detect rez opportunity when ICE already rezzed"))))

(deftest test-no-rez-opportunity-wrong-phase
  (testing "No rez opportunity when not in approach-ice phase"
    (let [state (mock-client-state
                 :side "corp"
                 :game-state
                 {:run {:phase "encounter-ice"  ; Wrong phase
                        :position 0
                        :server [:hq]}
                  :corp {:prompt-state {:msg "Rez Tithe?"
                                       :prompt-type "run"
                                       :choices []
                                       :selectable [{:cid 123 :title "Tithe" :rezzed false}]}
                         :servers {:hq {:ices [{:cid 123 :title "Tithe" :rezzed false}]}}}})]

      (is (not (ai/corp-has-rez-opportunity? state))
          "Should NOT detect rez opportunity in encounter-ice phase"))))

;; =============================================================================
;; Test: Information Leakage Prevention
;; =============================================================================

(deftest test-must-pause-even-if-cant-afford
  (testing "Must pause at rez decision even if corp can't afford to rez - prevent info leakage"
    (let [state (mock-client-state
                 :side "corp"
                 :credits 0  ; Can't afford to rez
                 :game-state
                 {:run {:phase "approach-ice"
                        :position 1  ; approaching outermost ICE
                        :server [:rd]}
                  :corp {:credit 0  ; Broke
                         :prompt-state {:msg "Rez Archer?"
                                       :prompt-type "run"
                                       :choices []
                                       :selectable [{:cid 456 :title "Archer" :cost 4 :rezzed false}]}
                         :servers {:rd {:ices [{:cid 456 :title "Archer" :cost 4 :rezzed false}]}}}})]

      ;; CRITICAL: Must still pause even though corp can't afford
      ;; Otherwise runner learns "corp is broke" for free
      (is (ai/corp-has-rez-opportunity? state)
          "Must detect rez opportunity even when can't afford (info leakage prevention)"))))

;; =============================================================================
;; Test: Rez Event Detection
;; =============================================================================

(deftest test-detect-rez-in-log
  (testing "Detect when ICE was rezzed from game log"
    (let [log-entries [{:text "Runner runs HQ"}
                       {:text "Corp rezzes Tithe"}  ; <-- This is the rez event
                       {:text "Approaching Tithe"}]
          rez-event (ai/get-rez-event log-entries)]

      (is (some? rez-event)
          "Should find rez event in log")
      (is (= "Corp rezzes Tithe" (:text rez-event))
          "Should return the rez event entry"))))

(deftest test-no-rez-event-in-empty-log
  (testing "No rez event when log has no rez entries"
    (let [log-entries [{:text "Runner runs HQ"}
                       {:text "Approaching ICE"}]
          rez-event (ai/get-rez-event log-entries)]

      (is (nil? rez-event)
          "Should return nil when no rez event"))))

;; =============================================================================
;; Test: Integration - Full Run with Rez Decision
;; =============================================================================

(deftest test-bug-12-reproduction
  (testing "Bug #12: continue-run must pause at rez decision, not bypass ICE"
    (with-mock-state
      (mock-client-state
       :side "runner"
       :game-state
       {:run {:phase "approach-ice"
              :position 0
              :server [:hq]}
        :runner {:prompt-state nil}  ; Runner waiting
        :corp {:prompt-state {:msg "Rez Tithe?"
                             :prompt-type "run"
                             :choices []
                             :selectable [{:cid 123 :title "Tithe" :rezzed false}]}
               :servers {:hq {:ices [{:cid 123 :title "Tithe" :rezzed false}]
                             :hand [{:cid 789 :title "Nico Campaign"}]}}}}
       :log [])

      ;; The bug: this currently auto-continues through corp's rez decision
      ;; and accesses HQ without encountering Tithe
      (let [result (ai/continue-run!)]

        ;; Expected behavior: PAUSE at rez decision
        (is (not= :run-complete (:status result))
            "Should NOT complete run - must pause for corp rez decision")

        (is (or (= :waiting-for-opponent (:status result))
                (= :rez-decision-required (:status result)))
            "Should pause with waiting-for-opponent or rez-decision-required status")

        ;; Should NOT have accessed cards
        (is (nil? (:accessed result))
            "Should not have accessed any cards without corp making rez decision")))))

(deftest test-continue-after-corp-chooses-not-to-rez
  (testing "After corp chooses not to rez, continue-run proceeds past unrezzed ICE"
    (with-mock-state
      (mock-client-state
       :side "runner"
       :game-state
       {:run {:phase "movement"  ; Corp continued, now in movement phase
              :position 0
              :server [:hq]}
        :runner {:prompt-state {:msg "Paid ability window" :prompt-type "run" :choices []}}
        :corp {:prompt-state nil
               :servers {:hq {:ices [{:cid 123 :title "Tithe" :rezzed false}]  ; Still unrezzed
                             :hand [{:cid 789 :title "Hedge Fund"}]}}}}
       :log [{:text "Corp does not rez Tithe"}])

      ;; Now continue-run should proceed
      ;; We're in movement phase (past the unrezzed ice)
      ;; Should auto-continue through paid ability window
      (let [result (ai/continue-run!)]
        (is (= :action-taken (:status result))
            "Should auto-continue through paid ability window after corp declined to rez")))))

;; =============================================================================
;; Test: --rez that can't be afforded must not loop forever (polish 2026-06-22)
;;
;; A Corp with `--rez <ice>` whose effective rez cost it can't pay (e.g. a
;; Tread Lightly run adds +3 to every ICE) used to re-send the rez every monitor
;; iteration — the ICE never rezzes, state never changes, and the run wedged
;; until the stuck-detector tripped after 5 spins. handle-corp-rez-strategy now
;; marks the attempt (:rez-attempted-at) and, on the next pass with the ICE
;; still unrezzed, reports "can't afford" once and declines so the run proceeds.
;; =============================================================================

(defn- rez-strategy-ctx
  "Build a handler context for handle-corp-rez-strategy at approach-ice."
  [strategy]
  {:side "corp"
   :run-phase "approach-ice"
   :my-prompt {:msg "Rez Funhouse?" :prompt-type "run" :choices []}
   :strategy strategy
   :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
   :state {:game-state
           {:run {:phase "approach-ice" :position 1 :server [:hq]}
            :corp {:credit 7
                   :servers {:hq {:ices [{:cid 99 :title "Funhouse" :cost 5 :rezzed false}]}}}}}})

(deftest rez-strategy-first-attempt-sends-rez-and-marks-position
  (testing "first --rez pass sends the rez and reports the attempt position for tracking"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (let [result (corp-handlers/handle-corp-rez-strategy
                      (rez-strategy-ctx {:rez #{"Funhouse"}}))]
          (is (= :action-taken (:status result)))
          (is (= :auto-rezzed (:action result)))
          (is (= 1 (:rez-attempted-at result))
              "must report the position so the wrapper can persist the attempt")
          (is (some #(= "rez" (:command %)) @sent)
              "should send a rez command on the first attempt"))))))

(deftest rez-strategy-unaffordable-declines-instead-of-looping
  (testing "second pass with ICE still unrezzed declines (can't afford) rather than re-rezzing"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (let [out (with-out-str
                    (let [result (corp-handlers/handle-corp-rez-strategy
                                  ;; rez-attempted-at already == current position (1)
                                  (rez-strategy-ctx {:rez #{"Funhouse"} :rez-attempted-at 1}))]
                      (is (= :rez-failed-declined (:action result))
                          (str "expected a graceful decline, got: " result))))]
          (is (not-any? #(= "rez" (:command %)) @sent)
              "must NOT re-send a rez command when the prior attempt failed")
          (is (some #(= "continue" (:command %)) @sent)
              "should continue past the unrezzed ICE so the run proceeds")
          (is (re-find #"(?i)can't afford|did not take" out)
              (str "should explain why it declined, got: " out)))))))
