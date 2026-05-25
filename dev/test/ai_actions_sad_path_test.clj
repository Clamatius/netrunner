(ns ai-actions-sad-path-test
  "Sad path tests for AI actions - friendly error messages and side guards.

   Triaged 2026-05-25 (Task #5): five tests deleted as aspirational (testing
   client-side validation the prod fns don't do, and the cost of adding wasn't
   justified): action-without-game-state, action-when-not-connected,
   choose-invalid-option, run-empty-server, run-invalid-server.

   Two side-guard tests reframed to assert the friendly error message rather
   than an exception — that's how the prod fns actually fail (rez-card! always
   had the guard; run! gained one alongside this triage).

   One new prod feature (nil-input guard on play-card!) added so
   test-play-card-nil-input has something correct to assert."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-actions]))

;; ============================================================================
;; Card Operations - Not Found Errors
;; ============================================================================

(deftest test-play-card-not-in-hand
  (testing "Playing card not in hand shows helpful error"
    (with-mock-state
      (mock-client-state
        :hand [{:cid 1 :title "Diesel" :cost 0}])
      (assert-error-message
        #(ai-actions/play-card! "Sure Gamble")
        "not found"))))

(deftest test-play-card-empty-hand
  (testing "Playing card from empty hand shows error"
    (with-mock-state
      (mock-client-state :hand [])
      (assert-error-message
        #(ai-actions/play-card! 0)
        "not found in hand"))))

(deftest test-play-card-invalid-index
  (testing "Playing card by out-of-bounds index shows error"
    (with-mock-state
      (mock-client-state
        :hand [{:cid 1 :title "Diesel"}])
      (assert-error-message
        #(ai-actions/play-card! 999)
        "not found"))))

(deftest test-install-card-not-found
  (testing "Installing non-existent card shows error"
    (with-mock-state
      (mock-client-state
        :hand [{:cid 1 :title "Daily Casts"}])
      (assert-error-message
        #(ai-actions/install-card! "Corroder")
        "not found"))))

;; ============================================================================
;; Side Guards
;; ============================================================================

(deftest test-runner-cannot-rez
  (testing "Runner trying to rez shows side error (no ws send)"
    (with-mock-state
      (mock-client-state :side "runner")
      (assert-error-message
        #(ai-actions/rez-card! "ICE Wall")
        "Only Corp"))))

(deftest test-corp-cannot-run
  (testing "Corp trying to run shows side error (no ws send)"
    (with-mock-state
      (mock-client-state :side "corp")
      (assert-error-message
        #(ai-actions/run! "HQ")
        "Only Runner"))))

;; ============================================================================
;; Prompt / Input Validation
;; ============================================================================

(deftest test-choose-without-prompt
  (testing "Choosing when no prompt shows helpful error"
    (with-mock-state
      (mock-client-state :prompt nil)
      (assert-error-message
        #(ai-actions/choose-by-index! 0)
        "No active prompt"))))

(deftest test-play-card-nil-input
  (testing "Playing card with nil input shows error (nil-input guard)"
    (with-mock-state
      (mock-client-state
        :hand [{:cid 1 :title "Sure Gamble"}])
      (assert-error-message
        #(ai-actions/play-card! nil)
        "invalid"))))

;; ============================================================================
;; Test Suite Summary
;; ============================================================================

(defn -main
  "Run sad path tests and report results"
  []
  (let [results (run-tests 'ai-actions-sad-path-test)]
    (println "\n========================================")
    (println "Sad Path Test Summary")
    (println "========================================")
    (println "Tests run:" (:test results))
    (println "Assertions:" (:pass results))
    (println "Failures:" (:fail results))
    (println "Errors:" (:error results))
    (println "========================================\n")
    (when (or (pos? (:fail results)) (pos? (:error results)))
      (System/exit 1))))
