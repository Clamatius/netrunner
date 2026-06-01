(ns ai-heuristic-runner-test
  "Regression tests for ai_heuristic_runner.clj.

   Locks down run-result->next-action: the autonomous loop's translation of a
   continue-run! result into the next action. The load-bearing case is
   :paused-cannot-break -> :tank, which keeps a fully-automated game from
   spinning forever on an unbreakable encounter (the self-play deadlock the
   full-break handler used to cause by waiting for a human)."
  (:require [clojure.test :refer :all]
            [ai-heuristic-runner :as h]))

(deftest test-decision-required-maps-to-handle-prompt
  (testing ":decision-required -> :handle-prompt"
    (is (= :handle-prompt (h/run-result->next-action {:status :decision-required})))))

(deftest test-paused-cannot-break-maps-to-tank
  (testing ":paused-cannot-break -> :tank (no human to decide; let subs fire)"
    (is (= :tank (h/run-result->next-action
                   {:status :paused-cannot-break :ice "Palisade" :reason :cant-afford})))
    (is (= :tank (h/run-result->next-action
                   {:status :paused-cannot-break :ice "Tithe" :reason :no-breaker})))))

(deftest test-other-statuses-map-to-continue
  (testing "progress / terminal statuses fall through to :continue"
    (is (= :continue (h/run-result->next-action {:status :ability-used})))
    (is (= :continue (h/run-result->next-action {:status :waiting-for-corp-fire})))
    (is (= :continue (h/run-result->next-action {:status :run-complete})))
    (is (= :continue (h/run-result->next-action {:status :no-run})))
    (is (= :continue (h/run-result->next-action {})))))

(defn -main []
  (let [results (run-tests 'ai-heuristic-runner-test)]
    (when (or (pos? (:fail results)) (pos? (:error results)))
      (System/exit 1))))
