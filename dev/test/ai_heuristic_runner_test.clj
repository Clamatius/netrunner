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

(deftest test-fire-decision-required-maps-to-tank
  (testing ":fire-decision-required -> :tank (non-full-break encounter, no human)"
    ;; handle-runner-encounter-ice (the NOT-full-break path) returns
    ;; :fire-decision-required when a rezzed ICE has unbroken subs and no tank
    ;; authorization - 'a human would type tank or jack-out here'. The autonomous
    ;; loop has no human, so it must convert this to :tank exactly like the
    ;; full-break path's :paused-cannot-break. Without this branch it falls to
    ;; :continue and the loop spins forever on the encounter.
    (is (= :tank (h/run-result->next-action
                   {:status :fire-decision-required :ice "Karunā" :unbroken-count 2})))))

(deftest test-other-statuses-map-to-continue
  (testing "progress / terminal statuses fall through to :continue"
    (is (= :continue (h/run-result->next-action {:status :ability-used})))
    (is (= :continue (h/run-result->next-action {:status :waiting-for-corp-fire})))
    (is (= :continue (h/run-result->next-action {:status :run-complete})))
    (is (= :continue (h/run-result->next-action {:status :no-run})))
    (is (= :continue (h/run-result->next-action {})))))

;; ----------------------------------------------------------------------------
;; decide-action* : pure decision core. The load-bearing regression is the
;; empty-stack draw guard - an unguarded :draw on an empty stack re-decides the
;; same impossible action every tick, clicks never drop, and the issue #19
;; own-turn spin backstop bails the loop (live repro: self-play T30, runner at
;; 6/7 with a full rig and an empty stack chose SAFETY-draw forever).
;; ----------------------------------------------------------------------------

(def base-ctx
  "A do-nothing context; override per test."
  {:clicks 3 :credits 6 :hand-size 5 :missing []
   :threat nil :can-break-threat? false :can-break-rd? false
   :stack-empty? false :econ-card nil :installable-breaker nil})

(deftest test-empty-stack-low-hand-does-not-draw
  (testing "T30 stall repro: empty stack + low hand must NOT return :draw"
    ;; Exact stall state: 3 clicks, 3 credits, 3 cards, full rig, can break R&D,
    ;; empty stack. Old code returned {:action :draw} (SAFETY) -> spin.
    (let [decision (h/decide-action*
                     (merge base-ctx {:clicks 3 :credits 3 :hand-size 3
                                      :can-break-rd? true :stack-empty? true}))]
      (is (not= :draw (:action decision))
          "drawing from an empty stack would spin the loop")
      ;; credits 3 < min-credits buffer, no econ card -> click for a credit
      ;; (makes progress toward an affordable run; the click is consumed).
      (is (= :credit (:action decision))))))

(deftest test-empty-stack-prefers-running-rd-when-flush
  (testing "empty stack + enough credits + can break R&D -> pressure R&D, not draw"
    (let [decision (h/decide-action*
                     (merge base-ctx {:credits 8 :hand-size 2
                                      :can-break-rd? true :stack-empty? true}))]
      (is (= {:action :run :args {:server "R&D"}} decision)))))

(deftest test-empty-stack-no-options-ends-turn
  (testing "empty stack, can't break R&D, no breaker to install -> end turn (nil)"
    (is (nil? (h/decide-action*
                (merge base-ctx {:credits 8 :hand-size 1 :missing ["Sentry"]
                                 :can-break-rd? false :stack-empty? true}))))))

(deftest test-low-hand-with-cards-still-draws
  (testing "no regression: low hand + non-empty stack still draws for safety"
    (is (= {:action :draw}
           (h/decide-action*
             (merge base-ctx {:hand-size 2 :can-break-rd? true :stack-empty? false}))))))

(deftest test-zero-clicks-returns-nil
  (testing "no clicks -> no decision"
    (is (nil? (h/decide-action* (merge base-ctx {:clicks 0}))))))

(defn -main []
  (let [results (run-tests 'ai-heuristic-runner-test)]
    (when (or (pos? (:fail results)) (pos? (:error results)))
      (System/exit 1))))
