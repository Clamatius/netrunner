(ns ai-actions-test
  "Unit tests for AI actions - validates function behavior with minimal mocking

   Tests (12): Fast unit tests that verify action functions send correct WebSocket messages

   Note: Behavioral/integration tests should test through real game API + log parsing,
   not mock game state (which is fragile to upstream Jinteki changes).

   Usage:
     make test                    - Run all unit tests
     lein test ai-actions-test    - Run this test namespace"
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [test-helpers :refer :all]
            [ai-actions]
            [ai-card-actions]
            [ai-websocket-client-v2 :as ws]))

;; Var-reference to test the private formatter without dropping defn-.
(def format-credit-line #'ai-card-actions/format-credit-line)

;; ============================================================================
;; State Query Tests
;; ============================================================================

(deftest test-show-hand
  (testing "show-hand returns current hand cards"
    (with-mock-state
      (mock-client-state
        :hand [{:cid 1 :title "Sure Gamble"}
               {:cid 2 :title "Diesel"}])
      (let [result (ai-actions/show-hand)]
        (is (= 2 (count result)))
        (is (= "Sure Gamble" (:title (first result))))))))

(deftest test-show-credits
  (testing "show-credits returns current credit count"
    (with-mock-state
      (mock-client-state :side "runner" :credits 10)
      (is (= 10 (ai-actions/show-credits))))))

(deftest test-show-clicks
  (testing "show-clicks returns current click count"
    (with-mock-state
      (mock-client-state :side "runner" :clicks 3)
      (is (= 3 (ai-actions/show-clicks))))))

(deftest test-status
  (testing "status returns comprehensive game state info"
    (with-mock-state
      (mock-client-state
        :side "runner"
        :credits 5
        :clicks 4
        :hand [{:cid 1 :title "Sure Gamble"}])
      (let [status (ai-actions/status)]
        (is (map? status))
        (is (contains? status :connected))
        (is (contains? status :side))))))

;; ============================================================================
;; Card Operations Tests
;; ============================================================================

(deftest test-play-card-by-name
  (testing "play-card! by name sends correct event"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state
          :side "runner"
          :hand [{:cid 1 :title "Sure Gamble" :cost 5}])
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (ai-actions/play-card! "Sure Gamble")
          (is (= 1 (count @sent)))
          (is (= :game/action (:type (first @sent)))))))))

(deftest test-play-card-by-index
  (testing "play-card! by index sends correct event"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state
          :side "runner"
          :hand [{:cid 1 :title "Sure Gamble"}
                 {:cid 2 :title "Diesel"}])
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (ai-actions/play-card! 0)
          (is (= 1 (count @sent))))))))

(deftest test-install-card-by-name
  (testing "install-card! by name works correctly"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state
          :side "runner"
          :hand [{:cid 1 :title "Daily Casts" :type "Resource"}])
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (ai-actions/install-card! "Daily Casts")
          (is (= 1 (count @sent))))))))

;; ============================================================================
;; Basic Action Tests
;; ============================================================================

(deftest test-take-credit
  (testing "take-credit! sends end turn action"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state :side "runner" :clicks 1)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (ai-actions/take-credit!)
          (is (= 1 (count @sent)))
          (is (= :game/action (:type (first @sent)))))))))

(deftest test-draw-card
  (testing "draw-card! sends draw action"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state :side "runner" :clicks 4)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (ai-actions/draw-card!)
          (is (= 1 (count @sent))))))))

;; ============================================================================
;; Run Tests (Runner-specific)
;; ============================================================================

(deftest test-run-hq
  (testing "run! on HQ sends run action"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state :side "runner" :clicks 4)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (ai-actions/run! "HQ")
          (is (= 1 (count @sent))))))))

(deftest test-run-normalized-server
  (testing "run! normalizes server names"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state :side "runner" :clicks 4)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          ;; Test that lowercase/variations are normalized
          (ai-actions/run! "hq")
          (is (= 1 (count @sent))))))))

;; ============================================================================
;; Corp-specific Actions
;; ============================================================================

(deftest test-advance-card
  (testing "advance-card! sends advance action"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state
          :side "corp"
          :clicks 3
          :servers {:remote1 {:content [{:cid 1 :title "Agenda"}]}})
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (ai-actions/advance-card! "Agenda")
          (is (= 1 (count @sent))))))))

;; ============================================================================
;; Test Suite Summary
;; ============================================================================

(defn -main
  "Run happy path tests and report results"
  []
  (let [results (run-tests 'ai-actions-test)]
    (println "\n========================================")
    (println "Happy Path Test Summary")
    (println "========================================")
    (println "Tests run:" (:test results))
    (println "Assertions:" (:pass results))
    (println "Failures:" (:fail results))
    (println "Errors:" (:error results))
    (println "========================================\n")
    (when (or (pos? (:fail results)) (pos? (:error results)))
      (System/exit 1))))

;; ============================================================================
;; format-credit-line — net-of-play-cost disclosure (laundry-list #6)
;; Creative Commission "Gain 5" costs 1 → nets +4; the line must make that
;; reconcilable instead of looking like an engine miscount.
;; ============================================================================

(deftest test-credit-line-discloses-play-cost
  (testing "a 'Gain 5' card costing 1 shows +4 net and discloses the 1 play cost"
    (let [line (format-credit-line 8 12 1)]
      (is (str/includes? line "8 → 12"))
      (is (str/includes? line "+4 net"))
      (is (str/includes? line "after 1 to play")
          (str "play cost should be disclosed, got: " line)))))

(deftest test-credit-line-zero-cost-omits-play-cost
  (testing "a free card shows the net gain with no play-cost clause"
    (let [line (format-credit-line 5 10 0)]
      (is (str/includes? line "+5 net"))
      (is (not (str/includes? line "to play"))))))

(deftest test-credit-line-no-change-returns-nil
  (testing "no credit movement => no line at all"
    (is (nil? (format-credit-line 7 7 3)))))

(deftest test-credit-line-negative-delta
  (testing "paying for a non-economy event shows a negative net and the cost"
    (let [line (format-credit-line 10 7 3)]
      (is (str/includes? line "-3 net"))
      (is (str/includes? line "after 3 to play")))))

(comment
  ;; Run all happy path tests
  (run-tests 'ai-actions-test)

  ;; Run specific test
  (test-show-hand)

  ;; Run from main
  (-main)
  )
