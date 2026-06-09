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

(deftest test-update-tracker-wallclock
  (testing "3-arity records a first-seen wall-clock stamp for patient-mode bail"
    ;; new key stamps :since = now
    (is (= {:key [:a 1 :x] :count 1 :since 5000}
           (stall/update-tracker {:key nil :count 0} [:a 1 :x] 5000)))
    ;; same key increments count but PRESERVES the original :since (elapsed grows)
    (is (= {:key [:a 1 :x] :count 2 :since 5000}
           (stall/update-tracker {:key [:a 1 :x] :count 1 :since 5000} [:a 1 :x] 5500))
        "since is the first-seen time, not refreshed each tick")
    ;; changed key restarts count AND re-stamps :since to now
    (is (= {:key [:b 2 :y] :count 1 :since 9000}
           (stall/update-tracker {:key [:a 1 :x] :count 9 :since 5000} [:b 2 :y] 9000)))
    ;; nil key clears both count and :since
    (is (= {:key nil :count 0 :since nil}
           (stall/update-tracker {:key [:a 1 :x] :count 9 :since 5000} nil 9000)))))

(deftest test-slow-opponent-wait?
  (testing "only true opponent-act waits get the patient window"
    ;; opponent could still legitimately act -> patient-eligible
    (is (stall/slow-opponent-wait? :waiting-for-opponent))
    (is (stall/slow-opponent-wait? :waiting-for-corp-rez))
    (is (stall/slow-opponent-wait? :waiting-for-corp-fire))
    (is (stall/slow-opponent-wait? :waiting-for-opponent-paid-abilities))
    (is (stall/slow-opponent-wait? :waiting-for-runner-signal))
    ;; monitor-run's OWN 'genuinely wedged' give-up signals are NOT opponent
    ;; slowness — they keep the tight iteration-count bail even in patient mode
    (is (not (stall/slow-opponent-wait? :stuck)))
    (is (not (stall/slow-opponent-wait? :max-iterations)))
    ;; progress / nil -> not a slow-opponent wait
    (is (not (stall/slow-opponent-wait? :action-taken)))
    (is (not (stall/slow-opponent-wait? nil))))
  (testing "every slow-opponent wait is also a general opponent-wait (subset)"
    (doseq [s [:waiting-for-opponent :waiting-for-corp-rez :waiting-for-corp-fire
               :waiting-for-opponent-paid-abilities :waiting-for-runner-signal]]
      (is (stall/waiting-on-opponent? s)))))

(deftest test-patient-bail?
  (testing "wall-clock bail: true once the wait has persisted >= bail-after-ms"
    (let [ms stall/default-patient-bail-ms]
      ;; just started waiting -> no elapsed -> no bail
      (is (not (stall/patient-bail? {:key [:a 1 :x] :count 1 :since 1000} 1000 ms)))
      ;; one tick short of the window
      (is (not (stall/patient-bail? {:key [:a 1 :x] :count 9 :since 1000} (+ 1000 ms -1) ms)))
      ;; exactly at the window -> bail
      (is (stall/patient-bail? {:key [:a 1 :x] :count 9 :since 1000} (+ 1000 ms) ms))
      ;; well past -> still bailed
      (is (stall/patient-bail? {:key [:a 1 :x] :count 9 :since 1000} (+ 1000 ms 999999) ms))
      ;; not waiting (nil :since) -> never bails, however large now is
      (is (not (stall/patient-bail? {:key nil :count 0 :since nil} 999999999 ms)))))
  (testing "default patience is a generous multi-minute window, not seconds"
    (is (>= stall/default-patient-bail-ms 300000) "at least 5 minutes")))

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

;; ----------------------------------------------------------------------------
;; Own-turn spin backstop (issue #19)
;; ----------------------------------------------------------------------------

(deftest test-own-turn-key
  (testing "nil unless it is MY turn AND no run is active"
    (let [gs {:turn 14 :active-player "corp" :corp {:click 3} :runner {:click 0}}]
      (is (= [14 3] (stall/own-turn-key gs "corp")))
      ;; not my turn -> nil (opponent's turn is the run-side tracker's domain)
      (is (nil? (stall/own-turn-key gs "runner")))
      ;; my turn but a run is active -> nil (run handshakes owned elsewhere)
      (is (nil? (stall/own-turn-key
                  (assoc gs :run {:phase "approach-ice" :position 1}) "corp")))))
  (testing "key changes when clicks change, so progress resets the tracker"
    (let [g1 {:turn 14 :active-player "corp" :corp {:click 3}}
          g2 {:turn 14 :active-player "corp" :corp {:click 2}}]
      (is (not= (stall/own-turn-key g1 "corp")
                (stall/own-turn-key g2 "corp")))))
  (testing "nil when a :waiting prompt is parked on our side (opponent resolving)"
    ;; A slow/LLM opponent resolving a cross-turn ability posts a :waiting prompt
    ;; to us while we're active with no run. That's a legitimate idle, not a
    ;; spin — must not accumulate toward a bail.
    (let [gs {:turn 14 :active-player "corp"
              :corp {:click 3 :prompt-state {:prompt-type :waiting}}}]
      (is (nil? (stall/own-turn-key gs "corp"))))
    ;; A non-:waiting prompt we can't resolve IS a genuine stuck — still keys.
    (let [gs {:turn 14 :active-player "corp"
              :corp {:click 3 :prompt-state {:prompt-type :select}}}]
      (is (= [14 3] (stall/own-turn-key gs "corp")))))
  (testing "nil after we have cleanly ended our turn (waiting for a slow opponent to start)"
    ;; After smart-end-turn! the engine sets :end-turn true but keeps
    ;; :active-player on us until the OPPONENT takes their start-of-turn. With a
    ;; slow (thinking-model) opponent that inter-turn gap can run minutes — it is
    ;; a legitimate opponent-wait, NOT a spin, so it must not accumulate toward a
    ;; bail. A genuine issue-#19 spin has clicks > 0 and :end-turn false.
    (let [gs {:turn 14 :active-player "corp" :end-turn true :corp {:click 0}}]
      (is (nil? (stall/own-turn-key gs "corp"))))
    ;; :end-turn false at 0 clicks IS still a genuine stuck (end-turn itself
    ;; failing) — must keep keying so the backstop catches it.
    (let [gs {:turn 14 :active-player "corp" :end-turn false :corp {:click 0}}]
      (is (= [14 0] (stall/own-turn-key gs "corp")))))
  (testing "a frozen own-turn drives update-tracker to accumulate"
    (let [gs {:turn 14 :active-player "corp" :corp {:click 3}}
          k  (stall/own-turn-key gs "corp")
          t1 (stall/update-tracker {:key nil :count 0} k)
          t2 (stall/update-tracker t1 k)]
      (is (= 2 (:count t2)) "same frozen key increments"))))

(deftest test-own-turn-spinning?
  (testing "true only once the frozen count crosses the bail threshold"
    (is (not (stall/own-turn-spinning? 1 60)))
    (is (not (stall/own-turn-spinning? 59 60)))
    (is (stall/own-turn-spinning? 60 60))
    (is (stall/own-turn-spinning? 999 60)))
  (testing "default-threshold arity uses own-turn-spin-bail-at"
    (is (not (stall/own-turn-spinning? 0)))
    (is (stall/own-turn-spinning? stall/own-turn-spin-bail-at))))

(deftest test-own-turn-diagnostic
  (testing "renders a frozen-own-turn bail with side, count and log tail"
    (let [msg (stall/own-turn-diagnostic
                "ai-corp" [14 3] 60
                [{:text "ai-corp started their turn 14"}
                 {:text "blocked install: Server 1 already has Urtica Cipher"}])]
      (is (re-find #"ai-corp" msg))
      (is (re-find #"OWN-TURN SPIN" msg))
      (is (re-find #"60" msg))
      (is (re-find #"blocked install" msg)))))

(defn -main []
  (let [results (run-tests 'ai-stall-test)]
    (when (or (pos? (:fail results)) (pos? (:error results)))
      (System/exit 1))))
