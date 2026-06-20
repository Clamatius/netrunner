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

;; ============================================================================
;; choose-card! / multi-choose! gate on :selectable, not the :prompt-type string
;; (backlog #3). Some engine prompts (e.g. Mutual Favor's stack search) carry
;; selectable cards under :prompt-type "other"; the old "select"-only gate
;; rejected them with "No select prompt active", stranding the seat. The gate is
;; now the presence of selectable cards. A pure text-choice prompt (no
;; selectable) is steered to `choose` instead of the misleading select error.
;; ============================================================================

(def ^:private other-typed-card-select-prompt
  {:prompt-type "other"
   :eid "mf-1"
   :msg "Choose a connection or virtual resource"
   :selectable [{:cid "c1" :title "Smartware Distributor"}
                {:cid "c2" :title "Telework Contract"}]})

(deftest choose-card-accepts-other-typed-selectable-prompt
  (testing "choose-card on an 'other'-typed prompt WITH selectable cards selects, not rejects"
    (let [sent (atom nil)]
      (with-mock-state (mock-client-state :side "runner" :prompt other-typed-card-select-prompt)
        (with-redefs [ws/select-card! (fn [card _eid] (reset! sent card) true)
                      prompts/wait-for-prompt-change! (fn [_eid & _] true)
                      basic/check-auto-end-turn! (fn [] nil)]
          (let [out (with-out-str
                      (let [r (prompts/choose-card! 1)]
                        (is (= :success (:status r)) (str "expected success, got: " r))))]
            (is (= "Telework Contract" (:title @sent))
                (str "expected the indexed card to be selected, got: " @sent))
            (is (not (str/includes? out "No select prompt active"))
                (str "must not reject an other-typed selectable prompt, got: " out))))))))

(deftest choose-card-steers-text-choice-prompt-to-choose
  (testing "choose-card on a pure text-choice prompt (no selectable) steers to `choose`"
    (let [sent (atom nil)]
      (with-mock-state (mock-client-state
                        :side "runner"
                        :prompt {:prompt-type "choice" :eid "ch-1" :msg "Pick one"
                                 :choices [{:value "A"} {:value "B"}]})
        (with-redefs [ws/select-card! (fn [_card _eid] (reset! sent :sent) true)]
          (let [out (with-out-str
                      (let [r (prompts/choose-card! 0)]
                        (is (= :error (:status r)))))]
            (is (nil? @sent) "must not send a select for a text-choice prompt")
            (is (str/includes? out "choose")
                (str "should steer to choose, got: " out))))))))

(deftest multi-choose-accepts-other-typed-selectable-prompt
  (testing "multi-choose on an 'other'-typed prompt WITH selectable cards selects, not rejects"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "runner" :prompt other-typed-card-select-prompt)
        (with-redefs [ws/select-card! (fn [card _eid] (swap! sent conj (:title card)) true)
                      prompts/wait-for-prompt-change! (fn [_eid & _] true)]
          (let [out (with-out-str
                      (let [r (prompts/multi-choose! 0 1)]
                        (is (= :success (:status r)) (str "expected success, got: " r))))]
            (is (= ["Smartware Distributor" "Telework Contract"] @sent))
            (is (not (str/includes? out "No select prompt active")))))))))
