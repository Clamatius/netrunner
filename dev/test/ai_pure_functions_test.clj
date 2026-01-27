(ns ai-pure-functions-test
  "Characterization tests for pure functions in ai_runs.clj and ai_run_tactics.clj

   These tests lock down behavior before refactoring. They test functions
   that have no side effects and are easy to verify in isolation."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-runs :as runs]
            [ai-run-tactics :as tactics]
            [ai-core :as core]))

;; ============================================================================
;; normalize-side tests
;; ============================================================================

(deftest test-normalize-side-nil
  (testing "nil returns nil"
    (is (nil? (runs/normalize-side nil)))))

(deftest test-normalize-side-false
  (testing "false returns nil (treated as 'no one passed')"
    (is (nil? (runs/normalize-side false)))))

(deftest test-normalize-side-keyword
  (testing "keywords are converted to strings"
    (is (= "runner" (runs/normalize-side :runner)))
    (is (= "corp" (runs/normalize-side :corp)))))

(deftest test-normalize-side-string
  (testing "strings are returned as-is"
    (is (= "runner" (runs/normalize-side "runner")))
    (is (= "corp" (runs/normalize-side "corp")))))

(deftest test-normalize-side-other
  (testing "other values are stringified"
    (is (= "42" (runs/normalize-side 42)))))

;; ============================================================================
;; has-real-decision? tests
;; ============================================================================

(deftest test-has-real-decision-nil-prompt
  (testing "nil prompt returns falsy"
    (is (not (runs/has-real-decision? nil)))))

(deftest test-has-real-decision-trivial-choices-only
  (testing "only trivial choices (Done/Continue/Ok) is not a real decision"
    (let [prompt {:choices [{:value "Done"}
                            {:value "Continue"}
                            {:value "Ok"}]}]
      (is (not (runs/has-real-decision? prompt))))))

(deftest test-has-real-decision-single-done
  (testing "single 'Done' choice is not a real decision"
    (let [prompt {:choices [{:value "Done"}]}]
      (is (not (runs/has-real-decision? prompt))))))

(deftest test-has-real-decision-two-meaningful-choices
  (testing "2+ meaningful choices is a real decision"
    (let [prompt {:choices [{:value "Use Corroder"}
                            {:value "Use Gordian Blade"}
                            {:value "Done"}]}]
      (is (runs/has-real-decision? prompt)))))

(deftest test-has-real-decision-selectable-cards
  (testing "having selectable cards is a real decision"
    (let [prompt {:choices []
                  :selectable [{:cid 1 :title "Some Card"}]}]
      (is (runs/has-real-decision? prompt)))))

(deftest test-has-real-decision-empty
  (testing "empty choices and selectables is not a real decision"
    (let [prompt {:choices [] :selectable []}]
      (is (not (runs/has-real-decision? prompt))))))

(deftest test-has-real-decision-case-insensitive
  (testing "trivial choice detection is case-insensitive"
    (let [prompt {:choices [{:value "DONE"}
                            {:value "done"}
                            {:value "DoNe"}]}]
      (is (not (runs/has-real-decision? prompt))))))

;; ============================================================================
;; is-waiting-prompt? tests
;; ============================================================================

(deftest test-is-waiting-prompt-nil
  (testing "nil prompt returns falsy"
    (is (not (runs/is-waiting-prompt? nil)))))

(deftest test-is-waiting-prompt-true
  (testing "waiting type prompt returns true"
    (let [prompt {:prompt-type "waiting" :msg "Waiting for opponent"}]
      (is (runs/is-waiting-prompt? prompt)))))

(deftest test-is-waiting-prompt-false
  (testing "non-waiting prompts return false"
    (is (not (runs/is-waiting-prompt? {:prompt-type "select"})))
    (is (not (runs/is-waiting-prompt? {:prompt-type "run"})))
    (is (not (runs/is-waiting-prompt? {:prompt-type "choice"})))))

;; ============================================================================
;; should-i-act? tests
;; ============================================================================

(defn make-run-state-for-should-i-act [no-action]
  "Create minimal state with run and no-action value"
  {:game-state {:run {:phase "approach-ice"
                      :no-action no-action}}})

(deftest test-should-i-act-no-run
  (testing "no run returns nil"
    (let [state {:game-state {}}]
      (is (nil? (runs/should-i-act? state "runner")))
      (is (nil? (runs/should-i-act? state "corp"))))))

(deftest test-should-i-act-fresh-phase-runner-acts
  (testing "fresh phase (nil no-action) - runner should act"
    (let [state (make-run-state-for-should-i-act nil)]
      (is (true? (runs/should-i-act? state "runner")))
      (is (false? (runs/should-i-act? state "corp"))))))

(deftest test-should-i-act-runner-passed-corp-acts
  (testing "runner passed - corp should act"
    (let [state (make-run-state-for-should-i-act "runner")]
      (is (false? (runs/should-i-act? state "runner")))
      (is (true? (runs/should-i-act? state "corp"))))))

(deftest test-should-i-act-corp-passed-runner-acts
  (testing "corp passed - runner should act"
    (let [state (make-run-state-for-should-i-act "corp")]
      (is (true? (runs/should-i-act? state "runner")))
      (is (false? (runs/should-i-act? state "corp"))))))

(deftest test-should-i-act-keyword-no-action
  (testing "keyword no-action values work (server sometimes sends keywords)"
    (let [state (make-run-state-for-should-i-act :runner)]
      (is (false? (runs/should-i-act? state "runner")))
      (is (true? (runs/should-i-act? state "corp"))))))

(deftest test-should-i-act-false-no-action
  (testing "false no-action treated as fresh phase"
    (let [state (make-run-state-for-should-i-act false)]
      (is (true? (runs/should-i-act? state "runner")))
      (is (false? (runs/should-i-act? state "corp"))))))

;; ============================================================================
;; can-auto-continue? tests
;; ============================================================================

(defn make-state-for-auto-continue
  "Create state for can-auto-continue? testing"
  [& {:keys [prompt-type choices selectable no-action run-phase ice-rezzed side]
      :or {prompt-type "run" choices [] selectable [] no-action nil
           run-phase "movement" ice-rezzed true side "runner"}}]
  (let [ice-list (when (not ice-rezzed)
                   [{:cid 1 :title "Test ICE" :rezzed false}])]
    {:game-state {:run {:phase run-phase
                        :no-action no-action
                        :position 0
                        :server ["hq"]}
                  :corp {:servers {:hq {:ices ice-list}}}}}))

(deftest test-can-auto-continue-empty-window
  (testing "empty paid ability window can auto-continue"
    (let [prompt {:prompt-type "run" :choices [] :selectable []}
          state (make-state-for-auto-continue :no-action nil)]
      (is (runs/can-auto-continue? prompt "movement" "runner" state)))))

(deftest test-can-auto-continue-has-choices
  (testing "prompt with choices cannot auto-continue"
    (let [prompt {:prompt-type "run" :choices [{:value "Some Option"}] :selectable []}
          state (make-state-for-auto-continue)]
      (is (not (runs/can-auto-continue? prompt "movement" "runner" state))))))

(deftest test-can-auto-continue-has-selectables
  (testing "prompt with selectables cannot auto-continue"
    (let [prompt {:prompt-type "run" :choices [] :selectable [{:cid 1}]}
          state (make-state-for-auto-continue)]
      (is (not (runs/can-auto-continue? prompt "movement" "runner" state))))))

(deftest test-can-auto-continue-not-run-prompt
  (testing "non-run prompt cannot auto-continue"
    (let [prompt {:prompt-type "select" :choices [] :selectable []}
          state (make-state-for-auto-continue)]
      (is (not (runs/can-auto-continue? prompt "movement" "runner" state))))))

(deftest test-can-auto-continue-already-passed
  (testing "cannot auto-continue if I already passed"
    (let [prompt {:prompt-type "run" :choices [] :selectable []}
          state (make-state-for-auto-continue :no-action "runner")]
      (is (not (runs/can-auto-continue? prompt "movement" "runner" state))))))

(deftest test-can-auto-continue-corp-unrezzed-ice
  (testing "corp at approach-ice with unrezzed ICE should NOT auto-continue"
    (let [prompt {:prompt-type "run" :choices [] :selectable []}
          state (make-state-for-auto-continue :run-phase "approach-ice"
                                              :ice-rezzed false
                                              :no-action nil)]
      (is (not (runs/can-auto-continue? prompt "approach-ice" "corp" state))))))

(deftest test-can-auto-continue-corp-rezzed-ice
  (testing "corp at approach-ice with rezzed ICE CAN auto-continue"
    (let [prompt {:prompt-type "run" :choices [] :selectable []}
          state (make-state-for-auto-continue :run-phase "approach-ice"
                                              :ice-rezzed true
                                              :no-action "runner")]  ; runner passed, corp's turn
      (is (runs/can-auto-continue? prompt "approach-ice" "corp" state)))))

;; ============================================================================
;; ice-primary-type tests (now in ai-run-tactics)
;; ============================================================================

(deftest test-ice-primary-type-barrier
  (testing "Barrier ICE returns Barrier"
    (let [ice {:subtypes ["Barrier"]}]
      (is (= "Barrier" (tactics/ice-primary-type ice))))))

(deftest test-ice-primary-type-code-gate
  (testing "Code Gate ICE returns Code Gate"
    (let [ice {:subtypes ["Code Gate"]}]
      (is (= "Code Gate" (tactics/ice-primary-type ice))))))

(deftest test-ice-primary-type-sentry
  (testing "Sentry ICE returns Sentry"
    (let [ice {:subtypes ["Sentry"]}]
      (is (= "Sentry" (tactics/ice-primary-type ice))))))

(deftest test-ice-primary-type-multi-subtype
  (testing "Multi-subtype ICE returns first matching primary type"
    ;; Barrier checked first, then Code Gate, then Sentry
    (let [ice {:subtypes ["Sentry" "Barrier"]}]
      (is (= "Barrier" (tactics/ice-primary-type ice))))))

(deftest test-ice-primary-type-no-primary
  (testing "ICE with no primary type returns nil"
    (let [ice {:subtypes ["Trap" "AP"]}]
      (is (nil? (tactics/ice-primary-type ice))))))

(deftest test-ice-primary-type-empty
  (testing "ICE with no subtypes returns nil"
    (let [ice {:subtypes []}]
      (is (nil? (tactics/ice-primary-type ice))))))

;; ============================================================================
;; breaker-ice-type tests (now in ai-run-tactics)
;; ============================================================================

(deftest test-breaker-ice-type-fracter
  (testing "Fracter breaks Barrier"
    (let [breaker {:subtypes ["Icebreaker" "Fracter"]}]
      (is (= "Barrier" (tactics/breaker-ice-type breaker))))))

(deftest test-breaker-ice-type-decoder
  (testing "Decoder breaks Code Gate"
    (let [breaker {:subtypes ["Icebreaker" "Decoder"]}]
      (is (= "Code Gate" (tactics/breaker-ice-type breaker))))))

(deftest test-breaker-ice-type-killer
  (testing "Killer breaks Sentry"
    (let [breaker {:subtypes ["Icebreaker" "Killer"]}]
      (is (= "Sentry" (tactics/breaker-ice-type breaker))))))

(deftest test-breaker-ice-type-ai
  (testing "AI breaker returns :ai (can break any type)"
    (let [breaker {:subtypes ["Icebreaker" "AI"]}]
      (is (= :ai (tactics/breaker-ice-type breaker))))))

(deftest test-breaker-ice-type-no-type
  (testing "Non-breaker program returns nil"
    (let [program {:subtypes ["Virus"]}]
      (is (nil? (tactics/breaker-ice-type program))))))

;; ============================================================================
;; breaker-matches-ice? tests (now in ai-run-tactics)
;; ============================================================================

(deftest test-breaker-matches-ice-fracter-barrier
  (testing "Fracter matches Barrier"
    (let [breaker {:subtypes ["Fracter"]}
          ice {:subtypes ["Barrier"]}]
      (is (tactics/breaker-matches-ice? breaker ice)))))

(deftest test-breaker-matches-ice-decoder-code-gate
  (testing "Decoder matches Code Gate"
    (let [breaker {:subtypes ["Decoder"]}
          ice {:subtypes ["Code Gate"]}]
      (is (tactics/breaker-matches-ice? breaker ice)))))

(deftest test-breaker-matches-ice-killer-sentry
  (testing "Killer matches Sentry"
    (let [breaker {:subtypes ["Killer"]}
          ice {:subtypes ["Sentry"]}]
      (is (tactics/breaker-matches-ice? breaker ice)))))

(deftest test-breaker-matches-ice-ai-any
  (testing "AI breaker matches any ICE type"
    (let [ai-breaker {:subtypes ["AI"]}
          barrier {:subtypes ["Barrier"]}
          code-gate {:subtypes ["Code Gate"]}
          sentry {:subtypes ["Sentry"]}]
      (is (tactics/breaker-matches-ice? ai-breaker barrier))
      (is (tactics/breaker-matches-ice? ai-breaker code-gate))
      (is (tactics/breaker-matches-ice? ai-breaker sentry)))))

(deftest test-breaker-matches-ice-mismatch
  (testing "Mismatched breaker/ICE returns false"
    (let [fracter {:subtypes ["Fracter"]}
          sentry {:subtypes ["Sentry"]}]
      (is (not (tactics/breaker-matches-ice? fracter sentry))))))

;; ============================================================================
;; ping-message? tests (ai-core)
;; ============================================================================

;; Access private function for testing
(def ping-message? #'core/ping-message?)

(deftest test-ping-message-exact-match
  (testing "exact 'ping' message wakes the sleeper"
    (is (ping-message? {:text "TestPlayer: ping"}))
    (is (ping-message? {:text "AI_Corp: ping"}))))

(deftest test-ping-message-case-insensitive
  (testing "ping is case-insensitive"
    (is (ping-message? {:text "Player: PING"}))
    (is (ping-message? {:text "Player: Ping"}))
    (is (ping-message? {:text "Player: pInG"}))))

(deftest test-ping-message-with-whitespace
  (testing "ping with surrounding whitespace still matches"
    (is (ping-message? {:text "Player:  ping "}))
    (is (ping-message? {:text "Player: ping  "}))))

(deftest test-ping-message-not-chat
  (testing "non-chat messages don't trigger ping (no colon format)"
    (is (not (ping-message? {:text "ping"})))))

(deftest test-ping-message-chit-chat
  (testing "normal chat doesn't wake the sleeper"
    (is (not (ping-message? {:text "Player: hello"})))
    (is (not (ping-message? {:text "Player: I'm thinking about my move"})))
    (is (not (ping-message? {:text "Player: nice play!"})))))

(deftest test-ping-message-contains-ping
  (testing "messages containing 'ping' but not exact match don't trigger"
    (is (not (ping-message? {:text "Player: ping pong"})))
    (is (not (ping-message? {:text "Player: I'll ping you later"})))
    (is (not (ping-message? {:text "Player: pinging..."})))))

(deftest test-ping-message-nil-entry
  (testing "nil or missing text handled gracefully"
    (is (not (ping-message? nil)))
    (is (not (ping-message? {})))
    (is (not (ping-message? {:text nil})))))

;; ============================================================================
;; Test Suite Main
;; ============================================================================

(defn -main []
  (let [results (run-tests 'ai-pure-functions-test)]
    (println "\n========================================")
    (println "Pure Functions Test Summary")
    (println "========================================")
    (println "Tests run:" (:test results))
    (println "Assertions:" (:pass results))
    (println "Failures:" (:fail results))
    (println "Errors:" (:error results))
    (println "========================================\n")
    (when (or (pos? (:fail results)) (pos? (:error results)))
      (System/exit 1))))

(comment
  ;; Run all tests
  (run-tests 'ai-pure-functions-test)

  ;; Run specific test
  (test-normalize-side-keyword)
  )
