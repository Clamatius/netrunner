(ns ai-wait-test
  "Regression tests for wait-for-relevant-diff's :since fast path.

   The cursor bumps on every server diff (our own action echoing back,
   opponent economy ticks, resyncs). The :since fast path must short-circuit
   ONLY when something actionable already happened in the race window — a bare
   cursor advance is not a wake, otherwise every --since wait that follows one
   of our own actions false-returns :cursor-advanced with no new log entries."
  (:require [clojure.test :refer :all]
            [test-helpers :refer [with-mock-state]]
            [ai-state :as state]
            [ai-core :as core]))

(defn- mock-game
  "Minimal client-state for wait tests."
  [side game-state]
  {:connected true :uid "test" :side side :game-state game-state})

(deftest test-since-phantom-advance-does-not-short-circuit
  ;; Cursor advanced past :since but nothing relevant happened (opponent still
  ;; mid-turn, no prompt, no run). The old fast path returned
  ;; :already-advanced/:cursor-advanced here on every self-action. It must now
  ;; fall through and wait — with timeout 0 that means a quick :timeout.
  (testing "bare cursor advance with no relevant event falls through to wait"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "runner" :turn 5
                                   :corp {:click 0} :runner {:click 2}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :timeout (:status result))
              (str "phantom cursor advance must not short-circuit, got: " result))
          (is (not= :already-advanced (:status result))))))))

(deftest test-since-relevant-advance-short-circuits-my-turn
  ;; A genuine race: opponent ended their turn in the gap, so it's our turn.
  ;; The fast path must still return immediately with the real reason.
  (testing "cursor advanced AND it's my turn -> :already-advanced :my-turn"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "corp" :turn 5
                                   :corp {:click 3} :runner {:click 0}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :my-turn (:reason result))
              (str "expected :my-turn, got: " result)))))))

(deftest test-since-relevant-advance-short-circuits-run-started
  ;; A run started in the race window — still a wake.
  (testing "cursor advanced AND a run is active -> :already-advanced :run-started"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "runner" :turn 5
                                   :run {:phase "approach-ice" :server "hq"}
                                   :corp {:click 0} :runner {:click 0}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :run-started (:reason result))
              (str "expected :run-started, got: " result)))))))

(deftest test-since-not-advanced-waits
  ;; Cursor has NOT advanced past :since — no fast path regardless of state.
  (testing "cursor not advanced -> normal wait (quick timeout here)"
    (with-redefs [state/get-cursor (fn [] 5)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "runner" :turn 5
                                   :corp {:click 0} :runner {:click 2}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :timeout (:status result))))))))
