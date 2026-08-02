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
            [clojure.string]
            [test-helpers :refer :all]
            [ai-actions]
            [ai-core]
            [ai-websocket-client-v2 :as ws]))

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

(deftest test-rez-ice-refused-outside-run
  (testing "Corp rezzing ICE with no active run is refused (Bug #1 from Run #4)
            — engine accepts it but it's a strict waste of credits and reveals
            information for nothing; the prior 'allow' clause was an oversight"
    (with-mock-state
      (mock-client-state
        :side "corp"
        :game-state
        {:corp {:credit 10
                :servers {:hq {:ices [{:cid 1 :title "Brân 1.0"
                                       :type "ICE" :cost 6
                                       :rezzed false :side "Corp"
                                       :zone [:servers :hq :ices 0]}]}}}
         :runner {}
         :active-player "corp"})
      (assert-error-message
        #(ai-actions/rez-card! "Brân 1.0")
        "no active run"))))

(deftest test-rez-ice-allowed-during-approach
  (testing "Corp rezzing ICE during approach-ice phase is allowed (golden path)"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state
          :side "corp"
          :game-state
          {:corp {:credit 10
                  :servers {:hq {:ices [{:cid 1 :title "Brân 1.0"
                                         :type "ICE" :cost 6
                                         :rezzed false :side "Corp"
                                         :zone [:servers :hq :ices 0]}]}}}
           :runner {}
           :run {:phase "approach-ice" :position 1 :server [:hq]}
           :active-player "runner"})
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ;; Stub the rez ground-truth check so we don't poll for a
                      ;; state change the test isn't producing.
                      ai-core/find-card-by-cid (fn [_] {:cid 1 :rezzed true})]
          (let [out (with-out-str (ai-actions/rez-card! "Brân 1.0"))]
            (is (some #(= "rez" (get-in % [:data :command])) @sent)
                "rez command should be sent during approach-ice phase")
            (is (clojure.string/includes? out "🔴 Rezzed: Brân 1.0")
                (str "confirmed rez must print the confirmation, got: " out))))))))

(deftest test-rez-refused-reports-failure-not-success
  ;; #86: verify-action-in-log's name check scanned the last 5 log lines, which
  ;; routinely already mention the card (a derez, a rez-decision hint, embedded
  ;; effect text), and its result map was always truthy — so a rez the engine
  ;; REFUSED (e.g. can't afford it mid-run) printed "🔴 Rezzed" instantly.
  ;; Reproduced live 2026-08-02: corp at 0c, rez cost 2, card stayed unrezzed,
  ;; seat was told "🔴 Rezzed … (remaining: 0₵)". The verdict must come from the
  ;; card's own :rezzed flag, and a refusal must be reported as a failure.
  (testing "refused rez (card never flips to rezzed) reports failure, not 🔴 Rezzed"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state
          :side "corp"
          :game-state
          {:corp {:credit 0
                  :servers {:remote1 {:content [{:cid 7 :title "Manegarm Skunkworks"
                                                 :type "Upgrade" :cost 2
                                                 :rezzed false :side "Corp"
                                                 :zone [:servers :remote1 :content]}]}}}
           :runner {}
           ;; Recent log already mentions the card — the exact false-positive
           ;; trigger for the old name-in-last-5-lines check.
           :log [{:text "ai-corp derezzes Manegarm Skunkworks"}
                 {:text "ai-runner approaches Server 1"}]
           :run {:phase "movement" :position 0 :server [:remote1]}
           :active-player "runner"})
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ;; The engine refuses: state never changes, so the card
                      ;; stays unrezzed no matter how often we look.
                      ai-core/find-card-by-cid (fn [_] {:cid 7 :rezzed false})
                      ;; Keep the failure poll fast in tests.
                      ai-core/action-timeout 50]
          (let [out (with-out-str (ai-actions/rez-card! "Manegarm Skunkworks"))]
            (is (not (clojure.string/includes? out "🔴 Rezzed"))
                (str "a refused rez must NOT print the success banner, got: " out))
            (is (clojure.string/includes? out "Rez NOT confirmed")
                (str "the failure must be stated plainly, got: " out))
            ;; A timeout doesn't prove WHY (could be latency, could be a
            ;; refusal) — the message must not assert a cause as fact.
            (is (not (clojure.string/includes? out "Likely can't afford"))
                (str "no unproven affordability claim, got: " out))))))))

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
