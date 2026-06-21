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

(deftest test-since-advance-turn-boundary-is-my-turn-start
  ;; Opponent ended their turn in the race window: it's our turn but the turn
  ;; hasn't been started yet (0 clicks). This must wake with :my-turn-start, NOT
  ;; :my-turn — the seat needs to call start-turn first, and the distinct reason
  ;; keeps a clean boundary from looking like an actionable turn (or a stall).
  (testing "opponent ended turn, 0 clicks -> :already-advanced :my-turn-start"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "runner" :turn 5 :end-turn true
                                   :corp {:click 0} :runner {:click 0}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :my-turn-start (:reason result))
              (str "expected :my-turn-start, got: " result)))))))

(deftest test-since-advance-turn-zero-corp-is-my-turn-start
  ;; Post-mulligan, turn 0, Corp goes first but has 0 clicks until start-turn.
  ;; Same boundary semantics as a normal turn handoff.
  (testing "turn 0 corp, 0 clicks -> :already-advanced :my-turn-start"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "corp" :turn 0
                                   :corp {:click 0} :runner {:click 0}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :my-turn-start (:reason result))
              (str "expected :my-turn-start, got: " result)))))))

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

(deftest test-game-over-wakes-via-fast-path
  ;; Game ended in the race window (Runner won on their own turn while Corp was
  ;; in a `wait`). The cursor advanced; the fast path must short-circuit with
  ;; :game-over — NOT fall through, and NOT mistake a frozen non-our-turn for a
  ;; phantom advance. This is the #5 marquee rough edge: a finished game left a
  ;; waiting seat hanging the full timeout.
  (testing "game over + cursor advanced -> :already-advanced :game-over"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "runner" :turn 10 :winner "runner"
                                   :corp {:click 0} :runner {:click 1}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :game-over (:reason result))
              (str "expected :game-over, got: " result)))))))

(deftest test-game-over-wakes-without-since
  ;; No :since cursor — the normal poll loop must wake on :game-over rather than
  ;; burning the timeout. game-over outranks the timeout check in the cond.
  (testing "game over, no :since -> :relevant-change :game-over (not :timeout)"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "runner" :turn 10 :winner "runner"
                                   :corp {:click 0} :runner {:click 1}})
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :relevant-change (:status result))
              (str "game-over must wake, not time out, got: " result))
          (is (= :game-over (:reason result))))))))

(deftest test-game-over-via-reason-and-end-time
  ;; The other game-over signal: no :winner, but :reason + :end-time set
  ;; (tie / timed-out match). Must wake the same way.
  (testing "reason + end-time game over -> :game-over"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "corp" :turn 10
                                   :reason "Decked" :end-time "2026-06-20T00:00:00Z"
                                   :corp {:click 2} :runner {:click 0}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :game-over (:reason result))
              (str "expected :game-over, got: " result)))))))
