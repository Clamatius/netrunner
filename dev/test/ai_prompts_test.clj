(ns ai-prompts-test
  "Tests for prompt-handling helpers in ai-prompts."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [ai-prompts :as prompts]
            [ai-state :as state]
            [test-helpers :refer [mock-client-state with-mock-state]]))

;; ============================================================================
;; wait-for-prompt-change! — select-prompt false-timeout (issue #18)
;;
;; The server `select` handler TOGGLES a card and only resolves the prompt once
;; :max cards are chosen. For a partial multi-select the prompt stays put, so
;; choose-card!'s wait times out with the prompt unchanged. That is EXPECTED, not
;; a failure — it must not read as "Timeout ... (prompt unchanged)". Instead it
;; should steer the caller to `multi-choose`.
;; ============================================================================

(defn- run-wait
  "Invoke wait-for-prompt-change! against a mocked current prompt, returning the
   captured stdout. timeout-ms 0 hits the timeout branch immediately (no sleep)."
  [prompt-state old-eid]
  (with-mock-state (mock-client-state :prompt prompt-state)
    (with-out-str
      (prompts/wait-for-prompt-change! old-eid :timeout-ms 0))))

(deftest wait-for-prompt-change!-unchanged-select-prompt
  (testing "an unchanged SELECT prompt steers to multi-choose, not a timeout warning"
    (let [out (run-wait {:prompt-type "select" :eid "eid-1" :msg "Choose"} "eid-1")]
      (is (str/includes? out "multi-choose")
          (str "Expected steer to multi-choose, got: " out))
      (is (not (str/includes? out "Timeout"))
          (str "Select prompt must not warn 'Timeout', got: " out))))

  (testing "matches the keyword :select wire form too"
    (let [out (run-wait {:prompt-type :select :eid "eid-1" :msg "Choose"} "eid-1")]
      (is (str/includes? out "multi-choose")
          (str "Expected steer to multi-choose for keyword form, got: " out))
      (is (not (str/includes? out "Timeout"))))))

(deftest wait-for-prompt-change!-unchanged-non-select-prompt
  (testing "a genuinely-stalled non-select prompt still warns 'Timeout'"
    (let [out (run-wait {:prompt-type "credit" :eid "eid-1" :msg "Choose credit"} "eid-1")]
      (is (str/includes? out "Timeout waiting for prompt change")
          (str "Non-select stall should still warn, got: " out))
      (is (not (str/includes? out "multi-choose"))))))

(deftest wait-for-prompt-change!-prompt-moved
  (testing "no warning of any kind when the prompt actually changed (eid differs)"
    (let [out (run-wait {:prompt-type "select" :eid "eid-2" :msg "Choose"} "eid-1")]
      (is (not (str/includes? out "Timeout")))
      (is (not (str/includes? out "multi-choose"))))))
