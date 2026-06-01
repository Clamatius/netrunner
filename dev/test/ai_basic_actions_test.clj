(ns ai-basic-actions-test
  "Regression tests for ai_basic_actions.clj.

   Locks down: post-discard pause guard in start-turn! (commit 4ad15ddbc).
   Upstream's two-phase end-turn pauses on :corp-post-discard / :runner-post-discard
   when a card sets :force-post-discard-{self,opponent}. Sending start-turn during
   that pause desyncs the engine."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-basic-actions :as basic]
            [ai-websocket-client-v2 :as ws]))

(deftest test-start-turn-blocks-on-corp-post-discard
  (testing "start-turn! returns :post-discard-pending error and sends nothing"
    (let [sent (atom [])
          game-state {:corp-post-discard {:active true}
                      :runner {:click 0 :credit 5 :hand []}
                      :corp {:click 0 :credit 5 :hand []}
                      :turn 5
                      :active-player "Corp"
                      :log []}]
      (with-mock-state (mock-client-state :side "runner" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (basic/start-turn!)]
            (is (= :error (:status result)))
            (is (= :post-discard-pending (:reason result)))
            (is (empty? @sent) "must not send start-turn while pause is active")))))))

(deftest test-start-turn-blocks-on-runner-post-discard
  (testing "Mirror guard for runner-post-discard"
    (let [sent (atom [])
          game-state {:runner-post-discard {:active true}
                      :runner {:click 0 :credit 5 :hand []}
                      :corp {:click 0 :credit 5 :hand []}
                      :turn 5
                      :active-player "Runner"
                      :log []}]
      (with-mock-state (mock-client-state :side "corp" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (basic/start-turn!)]
            (is (= :error (:status result)))
            (is (= :post-discard-pending (:reason result)))
            (is (empty? @sent))))))))

(deftest test-start-turn-no-guard-when-post-discard-flag-absent
  (testing "Guard only fires when :active key is true (not when key is missing)"
    (let [sent (atom [])
          ;; No post-discard flags. Should fall through to other guards (opp-clicks > 0 here),
          ;; NOT to the post-discard branch.
          game-state {:runner {:click 0 :credit 5 :hand []}
                      :corp {:click 2 :credit 5 :hand []}
                      :turn 5
                      :active-player "Corp"
                      :log []}]
      (with-mock-state (mock-client-state :side "runner" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (basic/start-turn!)]
            (is (= :error (:status result)))
            (is (not= :post-discard-pending (:reason result))
                "Without :active flag, post-discard branch must not fire")))))))

;; ============================================================================
;; smart-end-turn! over-hand-size: must END (to trigger discard prompt), not refuse
;; ============================================================================
;; Regression for the self-play deadlock: a side at 0 clicks but over hand size
;; with no active discard prompt. The old over-hand-size guard returned
;; :over-hand-size and sent nothing, so the autonomous loop spun forever - the
;; discard prompt only appears AFTER end-turn is sent. end-turn! itself handles
;; oversized hands (engine prompts for discard), so smart-end-turn! must defer to
;; it rather than block.

(deftest test-smart-end-turn-over-hand-size-ends-to-trigger-discard
  (testing "over hand size + 0 clicks + no prompt -> sends end-turn (not :over-hand-size)"
    (let [sent (atom [])
          hand6 (vec (repeat 6 {:title "Hedge Fund" :type "Operation"}))
          game-state {:corp {:click 0 :credit 5 :hand hand6 :hand-count 6
                             :hand-size {:total 5} :installed {} :prompt-state nil}
                      :runner {:click 0 :credit 5 :hand []}
                      :turn 8
                      :active-player "Corp"
                      :log []}]
      (with-mock-state (mock-client-state :side "corp" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ;; turn legitimately started; nothing in log says we ended
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (let [result (basic/smart-end-turn!)]
            (is (not= :over-hand-size (:status result))
                "must not refuse with :over-hand-size")
            (is (some #(= "end-turn" (get-in % [:data :command])) @sent)
                "must send end-turn to trigger the engine discard prompt")))))))
