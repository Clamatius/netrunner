(ns ai-websocket-diff-test
  (:require [clojure.test :refer :all]
            [differ.core :as differ]))

;; Test the diff handling logic that was failing in the live game

(defn apply-diff
  "Current implementation - apply a diff to current state using differ library"
  [old-state diff]
  (if old-state
    (differ/patch old-state diff)
    diff))

(deftest test-mulligan-diff
  (testing "Handle mulligan diff from real game"
    (let [;; Initial state after :game/start
          initial-state {:gameid "ed201e24-edde-48ec-9931-61043442f35e"
                        :turn 0
                        :active-player "runner"
                        :runner {:credit 5
                                :click 0
                                :hand [{:cid 1 :title "Card 1"}
                                       {:cid 2 :title "Card 2"}
                                       {:cid 3 :title "Card 3"}
                                       {:cid 4 :title "Card 4"}
                                       {:cid 5 :title "Card 5"}]}
                        :corp {:credit 5
                               :click 0}}

          ;; Actual diff received from server (with "+" converted to :+)
          ;; This is what arrived in the :game/diff message
          received-diff [{:runner {:prompt-state {:msg "Keep hand?"
                                                   :choices [{:value "Keep"
                                                             :uuid "fac4dd16-cf10-451d-a729-241c83c30635"
                                                             :idx 0}
                                                            {:value "Mulligan"
                                                             :uuid "f951a28f-12f5-4783-bfe5-cd609714bc01"
                                                             :idx 1}]
                                                   :prompt-type "mulligan"
                                                   :eid {:eid 359}}}
                         :corp {:aid 1
                                :keep "mulligan"}
                         :log [:+ {:user "__system__"
                                   :text "Clamatius takes a mulligan."
                                   :timestamp "2025-11-08T23:53:56.404917Z"}]}
                        {}]

          ;; This is what we expect after applying the diff
          ;; Note: The second {} in the diff vector shouldn't break things
          result (apply-diff initial-state received-diff)]

      ;; The result should have the prompt state for runner
      (is (= "Keep hand?" (get-in result [:runner :prompt-state :msg])))
      (is (= "mulligan" (get-in result [:runner :prompt-state :prompt-type])))
      (is (= "mulligan" (get-in result [:corp :keep])))

      ;; Original state should be preserved where not overwritten
      (is (= 5 (get-in result [:runner :credit])))
      (is (= 0 (get-in result [:runner :click]))))))

(deftest test-simple-map-diff
  (testing "Handle simple map diff"
    (let [initial-state {:runner {:credit 5 :click 4}
                        :corp {:credit 5 :click 3}}
          ;; Diff format: [alterations removals]
          diff [{:runner {:credit 6 :click 3}} {}]
          result (apply-diff initial-state diff)]

      ;; Updated values
      (is (= 6 (get-in result [:runner :credit])))
      (is (= 3 (get-in result [:runner :click])))

      ;; Unchanged values
      (is (= 5 (get-in result [:corp :credit])))
      (is (= 3 (get-in result [:corp :click]))))))

(deftest test-vector-of-diffs
  (testing "Handle vector containing multiple diff maps"
    (let [initial-state {:runner {:credit 5}
                        :corp {:credit 5}}
          ;; Diff format: [alterations removals]
          ;; Both changes go in the alterations map
          diff [{:runner {:credit 6}
                 :corp {:credit 4}} {}]
          result (apply-diff initial-state diff)]

      (is (= 6 (get-in result [:runner :credit])))
      (is (= 4 (get-in result [:corp :credit]))))))

(deftest test-log-array-update
  (testing "Handle log updates with :+ marker"
    (let [initial-state {:log [{:text "Game started"}]}
          ;; Diff format: [alterations removals]
          diff [{:log [:+ {:text "New event"}]} {}]
          result (apply-diff initial-state diff)]

      ;; Should have both log entries
      (is (= 2 (count (:log result))))
      (is (= "Game started" (get-in result [:log 0 :text])))
      (is (= "New event" (get-in result [:log 1 :text]))))))

(comment
  ;; Run tests
  (run-tests 'ai-websocket-diff-test)

  ;; Run specific test
  (test-mulligan-diff)
  )
