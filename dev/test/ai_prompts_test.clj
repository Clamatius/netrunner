(ns ai-prompts-test
  "Tests for prompt-handling helpers in ai-prompts."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [ai-prompts :as prompts]
            [ai-state :as state]
            [ai-basic-actions :as basic]
            [ai-websocket-client-v2 :as ws]
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

;; ============================================================================
;; choose-by-value! reaches a SELECT prompt's meta-button (laundry-list #5)
;;
;; A select prompt carries selectable cards AND meta-buttons (Done / decline) in
;; :choices. choose-card only picks cards; choose <N> is refused as ambiguous.
;; That left "Done" unreachable, forcing the seat to over-select to escape. The
;; Done button is pressed via the same `choice`/:uuid command as ordinary
;; buttons, so choose-value "Done" must work even on a select prompt.
;; ============================================================================

(def ^:private select-prompt-with-done
  {:prompt-type "select"
   :eid "sel-1"
   :msg "Choose a card to place advancement counters on"
   :selectable ["cid-bran"]
   :choices [{:uuid "done-uuid" :value "Done"}]})

(defn- capture-choose-value
  "Run choose-by-value! against a mocked select prompt, stubbing the wire send /
   prompt-wait / auto-end. Returns {:sent <captured payload> :out <stdout>}."
  [value-text]
  (let [sent (atom nil)]
    (with-mock-state (mock-client-state :side "corp" :prompt select-prompt-with-done)
      (with-redefs [ws/send-message! (fn [_evt data] (reset! sent data) true)
                    prompts/wait-for-prompt-change! (fn [_eid & _] true)
                    basic/check-auto-end-turn! (fn [] nil)]
        (let [out (with-out-str (prompts/choose-by-value! value-text))]
          {:sent @sent :out out})))))

(deftest choose-by-value-presses-select-done-button
  (testing "choose-value \"Done\" on a select prompt sends the Done choice by uuid"
    (let [{:keys [sent out]} (capture-choose-value "Done")]
      (is (= "choice" (:command sent))
          (str "expected a choice command, got: " sent))
      (is (= {:choice {:uuid "done-uuid"}} (:args sent))
          (str "expected Done's uuid in args, got: " sent))
      (is (str/includes? out "Done")))))

(deftest choose-by-value-no-match-on-select-does-not-send
  (testing "a value matching no meta-button reports no-match and sends nothing"
    (let [{:keys [sent out]} (capture-choose-value "Nonexistent")]
      (is (nil? sent) "must not send a wire message when no choice matches")
      (is (str/includes? out "No choice matching")))))

(deftest choose-option-index-still-refuses-select-and-points-to-choose-value
  (testing "choose <N> on a select prompt is refused and steers to choose-value"
    (with-mock-state (mock-client-state :side "corp" :prompt select-prompt-with-done)
      (let [out (with-out-str
                  (let [r (prompts/choose-option! 0)]
                    (is (= :error (:status r)))))]
        (is (str/includes? out "SELECT prompt"))
        (is (str/includes? out "choose-value"))))))
