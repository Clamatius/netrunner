(ns ai-stall-test
  "Regression tests for ai_stall.clj — the on-stall nudge backstop's pure core.

   Locks down: which statuses count as 'waiting on opponent', the persistence
   tracker (reset on nil/changed key, increment on same key), and the
   none/nudge/bail decision (nudge fires exactly once, bail after threshold)."
  (:require [clojure.test :refer :all]
            [ai-stall :as stall]))

(deftest test-waiting-on-opponent?
  (testing "waiting-for-* and monitor give-up statuses count; progress does not"
    (is (stall/waiting-on-opponent? :waiting-for-corp-fire))
    (is (stall/waiting-on-opponent? :waiting-for-corp-rez))
    (is (stall/waiting-on-opponent? :waiting-for-opponent))
    (is (stall/waiting-on-opponent? :waiting-for-opponent-paid-abilities))
    (is (stall/waiting-on-opponent? :stuck))
    (is (stall/waiting-on-opponent? :max-iterations))
    ;; progress / self-decision statuses are NOT opponent-waits
    (is (not (stall/waiting-on-opponent? :action-taken)))
    (is (not (stall/waiting-on-opponent? :decision-required)))
    (is (not (stall/waiting-on-opponent? :paused-cannot-break)))
    (is (not (stall/waiting-on-opponent? :fire-decision-required)))
    (is (not (stall/waiting-on-opponent? :run-complete)))
    (is (not (stall/waiting-on-opponent? nil)))))

(deftest test-stall-key
  (testing "nil unless in an active run AND holding an opponent-wait status"
    (let [run {:phase "encounter-ice" :position 1}]
      (is (= ["encounter-ice" 1 :waiting-for-corp-fire]
             (stall/stall-key :waiting-for-corp-fire run)))
      ;; no run -> nil even if waiting
      (is (nil? (stall/stall-key :waiting-for-corp-fire nil)))
      ;; progress status -> nil even in a run
      (is (nil? (stall/stall-key :action-taken run)))
      (is (nil? (stall/stall-key :decision-required run))))))

(deftest test-update-tracker
  (testing "nil key resets; same key increments; changed key restarts at 1"
    (is (= {:key nil :count 0} (stall/update-tracker {:key nil :count 0} nil)))
    (is (= {:key nil :count 0} (stall/update-tracker {:key [:a 1 :x] :count 5} nil))
        "nil current-key resets a running count")
    (is (= {:key [:a 1 :x] :count 1} (stall/update-tracker {:key nil :count 0} [:a 1 :x])))
    (is (= {:key [:a 1 :x] :count 2} (stall/update-tracker {:key [:a 1 :x] :count 1} [:a 1 :x]))
        "same key increments")
    (is (= {:key [:b 2 :y] :count 1} (stall/update-tracker {:key [:a 1 :x] :count 9} [:b 2 :y]))
        "changed key restarts at 1")))

(deftest test-stall-action
  (testing "none below nudge-at, nudge EXACTLY at nudge-at, none between, bail at/after bail-at"
    (let [t {:nudge-at 10 :bail-at 120}]
      (is (= :none (stall/stall-action 1 t)))
      (is (= :none (stall/stall-action 9 t)))
      (is (= :nudge (stall/stall-action 10 t)) "nudge fires exactly at threshold")
      (is (= :none (stall/stall-action 11 t)) "no repeat nudge after firing once")
      (is (= :none (stall/stall-action 119 t)))
      (is (= :bail (stall/stall-action 120 t)))
      (is (= :bail (stall/stall-action 500 t)) "stays bailed past threshold"))))

(deftest test-nudge-text
  (testing "renders a readable 'your move?' line with side names and stall point"
    (let [msg (stall/nudge-text "ai-runner" "ai-corp"
                                ["encounter-ice" 1 :waiting-for-corp-fire])]
      (is (re-find #"ai-runner" msg))
      (is (re-find #"ai-corp" msg))
      (is (re-find #"waiting-for-corp-fire" msg))
      (is (re-find #"encounter-ice" msg)))))

(defn -main []
  (let [results (run-tests 'ai-stall-test)]
    (when (or (pos? (:fail results)) (pos? (:error results)))
      (System/exit 1))))
