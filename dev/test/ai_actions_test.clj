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
            [ai-core]
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

;; ============================================================================
;; score-agenda! — must NOT print phantom success (marquee game-2 finding).
;; GPT-5.5 Corp saw "🎯 Scored: Superconducting Hub (+1 points)" on an
;; under-advanced agenda that did NOT actually score. Two guards:
;;  (1) pre-check: refuse an agenda with fewer counters than its requirement;
;;  (2) verify by real Corp agenda-point DELTA, not by card name in the log.
;; ============================================================================

(deftest test-score-rejects-underadvanced-agenda
  (testing "score-agenda! refuses an under-advanced agenda WITHOUT sending a
            score command and WITHOUT printing a phantom 'Scored'"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state
          :side "corp"
          :servers {:remote1 {:content [{:cid 1 :title "Superconducting Hub"
                                         :type "Agenda" :advancementcost 3
                                         :advance-counter 2 :agendapoints 1
                                         :zone ["servers" "remote1" "content"]}]}})
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [out (with-out-str (ai-card-actions/score-agenda! "Superconducting Hub"))]
            (is (zero? (count @sent))
                "must not send a doomed score command for an under-advanced agenda")
            (is (str/includes? out "not scoreable")
                "must explain it is not scoreable")
            (is (str/includes? out "needs 1 more")
                "must state how many more advancements are needed")
            (is (not (str/includes? out "🎯 Scored"))
                "must NOT print a phantom success")))))))

(deftest test-score-verifies-by-score-delta-not-log-match
  (testing "a fully-advanced agenda whose score is refused by the engine (no
            agenda-point delta) must report 'did NOT score', not a phantom 'Scored'
            — even though the card name appears in the log"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state
          :side "corp"
          :servers {:remote1 {:content [{:cid 1 :title "Send a Message"
                                         :type "Agenda" :advancementcost 5
                                         :advance-counter 5 :agendapoints 3
                                         :zone ["servers" "remote1" "content"]}]}})
        ;; verify-action-in-log returns true (card name matched in log) but the
        ;; Corp's agenda-point never moved (stays 0) → must NOT claim a score.
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-core/verify-action-in-log (fn [& _] true)]
          (let [out (with-out-str (ai-card-actions/score-agenda! "Send a Message"))]
            (is (= 1 (count @sent)) "a fully-advanced agenda still sends the score command")
            (is (str/includes? out "did NOT score")
                "no agenda-point delta → must report the score did not happen")
            (is (not (str/includes? out "🎯 Scored"))
                "must NOT print a phantom success on a refused score")))))))

;; ============================================================================
;; fire-subs-report — honest output when a fired subroutine opens a prompt
;; ============================================================================
;; Regression for the live-found bug: firing Brân 1.0's "install an ice" sub
;; opens a Corp prompt and pauses resolution, so there are no new log entries
;; yet — and the old code wrongly reported "subs already broken or run already
;; ended", stalling the Corp on an unhandled prompt it believed was a no-op.

(deftest fire-subs-report-prompt-opened
  (testing "a subroutine that opens a new prompt is surfaced, not mislabeled as a no-op"
    (let [prompt {:msg "Choose an ice to install from Archives or HQ"
                  :prompt-type "select"}
          {:keys [lines result]} (ai-card-actions/fire-subs-report
                                  "Brân 1.0" 17 18 [] prompt)
          out (str/join "\n" lines)]
      (is (= :waiting-input (:status result))
          "an open prompt means we're waiting on input, not done")
      (is (= prompt (:prompt result)) "the prompt is threaded back to the caller")
      (is (str/includes? out "needs input before the rest can fire")
          "must tell the Corp a sub is mid-resolution")
      (is (str/includes? out "Choose an ice to install from Archives or HQ")
          "must echo the actual pending prompt message")
      (is (not (str/includes? out "already broken"))
          "must NOT claim the subs were already broken")
      (is (not (str/includes? out "run had already ended"))
          "must NOT claim the run already ended"))))

(deftest fire-subs-report-subs-fired
  (testing "new log entries (subs actually fired) are listed as success"
    (let [{:keys [lines result]} (ai-card-actions/fire-subs-report
                                  "Palisade" 5 6
                                  [{:text "Corp uses Palisade to end the run."}]
                                  nil)
          out (str/join "\n" lines)]
      (is (= :success (:status result)))
      (is (str/includes? out "Corp uses Palisade to end the run.")
          "fired-sub log lines are echoed"))))

(deftest fire-subs-report-genuine-noop
  (testing "no entries and no new prompt is a real no-op (e.g. subs already broken)"
    (let [{:keys [lines result]} (ai-card-actions/fire-subs-report
                                  "Ice Wall" 9 9 [] nil)
          out (str/join "\n" lines)]
      (is (= :success (:status result)))
      (is (str/includes? out "no new log entries")
          "the honest no-op message is preserved for the genuinely-empty case"))))

(comment
  ;; Run all happy path tests
  (run-tests 'ai-actions-test)

  ;; Run specific test
  (test-show-hand)

  ;; Run from main
  (-main)
  )
