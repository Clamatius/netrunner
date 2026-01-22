(ns ai-turn-validation-test
  "Characterization tests for turn validation logic in ai_basic_actions.clj

   These tests lock down the behavior of can-start-turn? and related functions
   before any refactoring. The log analysis is complex and appears 3x in the file."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-basic-actions :as actions]
            [ai-core :as core]
            [ai-state :as state]))

;; ============================================================================
;; Test Helpers - Log Entry Builders
;; ============================================================================

(defn make-log-entry
  "Create a log entry for testing"
  [text]
  {:text text})

(defn make-end-turn-entry
  "Create 'is ending their turn' log entry"
  [username turn]
  (make-log-entry (format "%s is ending their turn %d" username turn)))

(defn make-start-turn-entry
  "Create 'started their turn' log entry"
  [username turn]
  (make-log-entry (format "%s started their turn %d" username turn)))

(defn make-game-state-with-log
  "Create game state with specified log entries and click counts"
  [& {:keys [my-side my-clicks opp-clicks log turn my-username]
      :or {my-side :runner my-clicks 0 opp-clicks 0 log [] turn 1 my-username "AI-runner"}}]
  (let [opp-side (if (= my-side :runner) :corp :runner)]
    {:connected true
     :uid my-username
     :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
     :side (name my-side)
     :game-state {my-side {:click my-clicks
                           :user {:username my-username}}
                  opp-side {:click opp-clicks}
                  :turn turn
                  :log log}}))

;; ============================================================================
;; ai_core log analysis helpers tests
;; ============================================================================

(deftest test-core-extract-turn-number-basic
  (testing "extracts turn number from standard format"
    (is (= 5 (core/extract-turn-number "started their turn 5")))
    (is (= 14 (core/extract-turn-number "is ending their turn 14")))))

(deftest test-core-extract-turn-number-nil
  (testing "nil input returns nil"
    (is (nil? (core/extract-turn-number nil)))))

(deftest test-core-extract-turn-number-no-match
  (testing "text without turn number returns nil"
    (is (nil? (core/extract-turn-number "clicked for credit")))
    (is (nil? (core/extract-turn-number "played Sure Gamble")))))

(deftest test-find-end-turn-indices-basic
  (testing "finds end turn indices"
    (let [log [(make-log-entry "AI-corp is ending their turn 1")
               (make-log-entry "AI-runner took credit")
               (make-log-entry "AI-runner is ending their turn 1")]]
      (is (= [0 2] (vec (core/find-end-turn-indices log nil)))))))

(deftest test-find-end-turn-indices-exclude-username
  (testing "excludes entries with specified username"
    (let [log [(make-log-entry "AI-corp is ending their turn 1")
               (make-log-entry "AI-runner is ending their turn 1")]]
      (is (= [0] (vec (core/find-end-turn-indices log "AI-runner"))))
      (is (= [1] (vec (core/find-end-turn-indices log "AI-corp")))))))

(deftest test-find-start-turn-indices-basic
  (testing "finds start turn indices"
    (let [log [(make-log-entry "AI-corp started their turn 1")
               (make-log-entry "AI-corp took credit")
               (make-log-entry "AI-runner started their turn 1")]]
      (is (= [0 2] (vec (core/find-start-turn-indices log)))))))

(deftest test-find-start-turn-indices-include-username
  (testing "includes only entries with specified username"
    (let [log [(make-log-entry "AI-corp started their turn 1")
               (make-log-entry "AI-runner started their turn 1")]]
      (is (= [0] (vec (core/find-start-turn-indices log :include-username "AI-corp"))))
      (is (= [1] (vec (core/find-start-turn-indices log :include-username "AI-runner")))))))

(deftest test-find-start-turn-indices-exclude-username
  (testing "excludes entries with specified username"
    (let [log [(make-log-entry "AI-corp started their turn 1")
               (make-log-entry "AI-runner started their turn 1")]]
      (is (= [1] (vec (core/find-start-turn-indices log :exclude-username "AI-corp"))))
      (is (= [0] (vec (core/find-start-turn-indices log :exclude-username "AI-runner")))))))

;; ============================================================================
;; extract-turn-from-log tests (private function - legacy, kept for coverage)
;; ============================================================================

(deftest test-extract-turn-basic
  (testing "extracts turn number from standard format"
    (is (= 5 (#'actions/extract-turn-from-log "started their turn 5")))
    (is (= 14 (#'actions/extract-turn-from-log "is ending their turn 14")))))

(deftest test-extract-turn-nil-input
  (testing "nil input returns nil"
    (is (nil? (#'actions/extract-turn-from-log nil)))))

(deftest test-extract-turn-no-match
  (testing "text without turn number returns nil"
    (is (nil? (#'actions/extract-turn-from-log "clicked for credit")))
    (is (nil? (#'actions/extract-turn-from-log "played Sure Gamble")))))

(deftest test-extract-turn-first-match
  (testing "extracts first turn number if multiple exist"
    ;; This shouldn't happen in practice, but tests the regex behavior
    (is (= 1 (#'actions/extract-turn-from-log "turn 1 and turn 2")))))

;; ============================================================================
;; can-start-turn? tests
;; ============================================================================

(deftest test-can-start-already-have-clicks
  (testing "cannot start turn when already have clicks"
    (with-mock-state
      (make-game-state-with-log :my-side :runner :my-clicks 4 :opp-clicks 0)
      (let [result (actions/can-start-turn?)]
        (is (false? (:can-start result)))
        (is (= :turn-already-started (:reason result)))))))

(deftest test-can-start-opponent-has-clicks
  (testing "cannot start turn when opponent has clicks"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :opp-clicks 3
       :log [(make-end-turn-entry "AI-corp" 1)])
      (let [result (actions/can-start-turn?)]
        (is (false? (:can-start result)))
        (is (= :opponent-has-clicks (:reason result)))))))

(deftest test-can-start-opponent-not-ended
  (testing "cannot start turn when opponent hasn't ended"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :opp-clicks 0
       :log [])  ; No end turn in log
      (let [result (actions/can-start-turn?)]
        (is (false? (:can-start result)))
        (is (= :opponent-not-ended (:reason result)))))))

(deftest test-can-start-first-turn-corp
  (testing "Corp can start first turn (turn 0, no prior activity)"
    (with-mock-state
      (make-game-state-with-log
       :my-side :corp
       :my-clicks 0
       :opp-clicks 0
       :turn 0
       :log []
       :my-username "AI-corp")
      (let [result (actions/can-start-turn?)]
        (is (true? (:can-start result)))
        (is (= :first-turn (:reason result)))))))

(deftest test-can-start-first-turn-runner-blocked
  (testing "Runner cannot start first turn (Corp goes first)"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :opp-clicks 0
       :turn 0
       :log []
       :my-username "AI-runner")
      (let [result (actions/can-start-turn?)]
        (is (false? (:can-start result)))
        (is (= :not-first-player (:reason result)))))))

(deftest test-can-start-ready
  (testing "can start turn when all conditions met"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :opp-clicks 0
       :turn 1
       :log [(make-end-turn-entry "AI-corp" 1)]
       :my-username "AI-runner")
      (let [result (actions/can-start-turn?)]
        (is (true? (:can-start result)))
        (is (= :ready (:reason result)))))))

(deftest test-can-start-opponent-restarted
  (testing "cannot start when opponent started new turn after ending"
    (with-mock-state
      (make-game-state-with-log
       :my-side :corp
       :my-clicks 0
       :opp-clicks 0
       :turn 2
       :log [(make-end-turn-entry "AI-runner" 1)      ; idx 0 - Runner ended
             (make-log-entry "AI-corp took credit")   ; idx 1
             (make-end-turn-entry "AI-corp" 1)        ; idx 2 - Corp ended
             (make-start-turn-entry "AI-runner" 2)]   ; idx 3 - Runner started again!
       :my-username "AI-corp")
      (let [result (actions/can-start-turn?)]
        (is (false? (:can-start result)))
        (is (= :opponent-restarted (:reason result)))))))

;; ============================================================================
;; turn-started-since-last-opp-end? tests
;; ============================================================================

(deftest test-turn-started-no-opp-end
  (testing "when no opponent end found, returns true if my start exists"
    ;; This covers turn 1 for Corp (no prior Runner end)
    (with-mock-state
      (make-game-state-with-log
       :my-side :corp
       :my-clicks 3
       :log [(make-start-turn-entry "AI-corp" 1)]
       :my-username "AI-corp")
      (is (true? (actions/turn-started-since-last-opp-end?))))))

(deftest test-turn-started-no-my-start
  (testing "when no my start found, returns false"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :log [(make-end-turn-entry "AI-corp" 1)]
       :my-username "AI-runner")
      (is (false? (actions/turn-started-since-last-opp-end?))))))

(deftest test-turn-started-normal-case
  (testing "normal case: my start is after opponent end"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 4
       :log [(make-end-turn-entry "AI-corp" 1)        ; idx 0
             (make-start-turn-entry "AI-runner" 1)]   ; idx 1 (after)
       :my-username "AI-runner")
      (is (true? (actions/turn-started-since-last-opp-end?))))))

(deftest test-turn-started-start-before-end-same-turn-runner
  (testing "edge case: start logged before end, same turn, runner side"
    ;; This happens with async log ordering
    ;; Runner starting T1 is "since" Corp ending T1
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 4
       :log [(make-start-turn-entry "AI-runner" 1)    ; idx 0 (logged first!)
             (make-end-turn-entry "AI-corp" 1)]       ; idx 1 (logged second)
       :my-username "AI-runner")
      (is (true? (actions/turn-started-since-last-opp-end?))))))

(deftest test-turn-started-start-before-end-same-turn-corp
  (testing "edge case: start logged before end, same turn, corp side"
    ;; Corp starting T1 is NOT "since" Runner ending T1
    ;; (Corp starts T1 before Runner ends T1)
    (with-mock-state
      (make-game-state-with-log
       :my-side :corp
       :my-clicks 3
       :log [(make-start-turn-entry "AI-corp" 1)      ; idx 0 (logged first)
             (make-end-turn-entry "AI-runner" 1)]     ; idx 1 (logged second)
       :my-username "AI-corp")
      (is (false? (actions/turn-started-since-last-opp-end?))))))

(deftest test-turn-started-async-race-later-turn
  (testing "async race: my start is for later turn"
    ;; Start T2 logged before Opp End T1 - I already started
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 4
       :turn 2
       :log [(make-start-turn-entry "AI-runner" 2)    ; idx 0 - later turn
             (make-end-turn-entry "AI-corp" 1)]       ; idx 1 - earlier turn
       :my-username "AI-runner")
      (is (true? (actions/turn-started-since-last-opp-end?))))))

;; ============================================================================
;; Integration: Log patterns that appear 3x in the codebase
;; ============================================================================
;; These tests document the log analysis patterns that will be extracted
;; to helper functions in Phase 2.1

(deftest test-opp-end-detection-excludes-my-username
  (testing "opponent end detection excludes entries with my username"
    ;; The pattern: (not (str/includes? text my-username))
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :opp-clicks 0
       :log [(make-end-turn-entry "AI-runner" 1)      ; This is MY end, not opponent
             (make-end-turn-entry "AI-corp" 1)]       ; This is opponent end
       :my-username "AI-runner")
      (let [result (actions/can-start-turn?)]
        ;; Should be able to start because Corp (opponent) ended
        (is (true? (:can-start result)))))))

(deftest test-my-start-detection-includes-my-username
  (testing "my start detection includes entries with my username"
    ;; The pattern: (str/includes? text my-username)
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 4
       :log [(make-end-turn-entry "AI-corp" 1)
             (make-start-turn-entry "AI-runner" 1)    ; This is MY start
             (make-start-turn-entry "AI-corp" 2)]     ; This is NOT my start
       :my-username "AI-runner")
      (is (true? (actions/turn-started-since-last-opp-end?))))))

;; ============================================================================
;; Test Suite Main
;; ============================================================================

(defn -main []
  (let [results (run-tests 'ai-turn-validation-test)]
    (println "\n========================================")
    (println "Turn Validation Test Summary")
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
  (run-tests 'ai-turn-validation-test)

  ;; Run specific test
  (test-can-start-ready)
  )
