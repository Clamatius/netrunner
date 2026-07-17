(ns ai-prompts-test
  "Tests for prompt-handling helpers in ai-prompts."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [ai-prompts :as prompts]
            [ai-state :as state]
            [ai-basic-actions :as basic]
            [ai-websocket-client-v2 :as ws]
            [ai-core :as core]
            [test-helpers :refer [mock-client-state with-mock-state make-prompt]]))

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

;; ============================================================================
;; multi-choose! — no premature "Selection complete" on an unresolved prompt
;;
;; multi-choose! sent every select then UNCONDITIONALLY printed "✅ Selection
;; complete" + returned :success, ignoring wait-for-prompt-change!. Found live on
;; a Corp discard-to-5 (marquee g1): `multi-choose 0 1` reported success while the
;; hand still held 7 cards and the prompt stayed open — directly under the ℹ️
;; "select prompt still open" tip, contradicting it. Same misleading-output class
;; already fixed for choose-card! (#40). Fix: only claim completion once the
;; prompt actually moves; a select that stays put is "toggled, not resolved"
;; (:waiting-input), and an unmoved NON-select prompt is the wrong verb (:error).
;; ============================================================================

(deftest multi-choose-unresolved-select-does-not-claim-complete
  (testing "a select prompt that stays open (not resolved) must NOT report Selection complete / :success"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :prompt {:prompt-type "select" :eid "disc-1"
                               :msg "Discard down to 5 cards"
                               :selectable [{:cid "c1" :title "Hedge Fund"}
                                            {:cid "c2" :title "IPO"}]})
      (with-redefs [ws/select-card! (fn [_card _eid] true)
                    ;; server toggled the cards but the prompt did not resolve
                    prompts/wait-for-prompt-change! (fn [_eid & _] false)]
        (let [out (with-out-str
                    (let [r (prompts/multi-choose! 0 1)]
                      ;; pin the contract: an unresolved real select is :waiting-input,
                      ;; not :success (and not silently downgraded to :error either).
                      (is (= :waiting-input (:status r))
                          (str "unresolved select must report :waiting-input, got: " r))))]
          (is (not (str/includes? out "Selection complete"))
              (str "must not print 'Selection complete' when the prompt is still open, got: " out)))))))

(deftest multi-choose-resolving-select-reports-complete
  (testing "a select that RESOLVES the prompt (moved) still reports Selection complete + :success"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :prompt {:prompt-type "select" :eid "disc-1"
                               :msg "Discard down to 5 cards"
                               :selectable [{:cid "c1" :title "Hedge Fund"}
                                            {:cid "c2" :title "IPO"}]})
      (with-redefs [ws/select-card! (fn [_card _eid] true)
                    prompts/wait-for-prompt-change! (fn [_eid & _] true)]
        (let [out (with-out-str
                    (let [r (prompts/multi-choose! 0 1)]
                      (is (= :success (:status r)) (str "expected success, got: " r))))]
          (is (str/includes? out "Selection complete")))))))

(deftest multi-choose-unmoved-nonselect-is-wrong-verb
  (testing "multi-choose on a non-select prompt that doesn't move errors and steers to choose"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :prompt {:prompt-type "other" :eid "mf-1"
                               :msg "Choose an icebreaker"
                               :choices [{:value "Corroder"}]
                               :selectable [{:cid "c1" :title "Corroder"}]})
      (with-redefs [ws/select-card! (fn [_card _eid] true)
                    prompts/wait-for-prompt-change! (fn [_eid & _] false)]
        (let [out (with-out-str
                    (let [r (prompts/multi-choose! 0)]
                      (is (= :error (:status r)) (str "expected error, got: " r))))]
          (is (not (str/includes? out "Selection complete")))
          (is (str/includes? out "choose")
              (str "should steer to choose, got: " out)))))))

(deftest multi-choose-selects-facedown-remote-card-by-index
  (testing "multi-choose by index index-selects a title-less face-down card, mirroring choose-card! (#70)"
    ;; choose-card! got find-selectable-card-by-cid in the merged #70 fix, but
    ;; multi-choose! still resolved CIDs with the title-gated find-card-by-cid —
    ;; a GPT-5.5-review consistency catch. Not the realistic breach path (a breach
    ;; is a run of single-selects), but the same latent trap on the multi-select
    ;; surface.
    (let [sent (atom [])]
      (with-mock-state (mock-client-state
                        :side "runner"
                        :game-state {:corp {:servers {:remote1 {:content
                                       [{:cid "fd-1"
                                         :zone ["servers" "remote1" "content"]
                                         :side "Corp" :type "Card"}]}}}
                                     :runner {:prompt-state
                                       {:prompt-type "select" :eid "brh-9"
                                        :msg "Click a card to access it."
                                        :selectable ["fd-1"]}}
                                     :active-player "runner"})
        (with-redefs [ws/select-card! (fn [card _eid] (swap! sent conj (:cid card)) true)
                      prompts/wait-for-prompt-change! (fn [_eid & _] true)]
          (let [out (with-out-str
                      (let [r (prompts/multi-choose! 0)]
                        (is (= :success (:status r))
                            (str "expected success selecting the face-down card, got: " r))))]
            (is (= ["fd-1"] @sent)
                (str "must index-select the face-down card, got: " @sent))
            (is (not (str/includes? out "Could not resolve card at index"))
                (str "must resolve the title-less selectable card, got: " out))))))))

;; ============================================================================
;; choose-card! — partial multi-select must not trigger the auto-end-turn hook
;;
;; choose-card! fires maybe-auto-end-turn-after-prompt! after every select. On a
;; partial multi-select (discard-to-N, prompt STAYS PUT while the server toggles
;; toward :max) nothing has resolved, yet the hook reaches check-auto-end-turn!,
;; which — clicks=0, prompt still blocking — prints a spurious
;;   "⚠️  Cannot auto-end turn: Active prompt must be resolved first
;;    💡 Use 'prompt' command to see choices, or 'choose' to respond"
;; directly UNDER the ℹ️ "card toggled … use `multi-choose`" steer, contradicting
;; it (choose vs multi-choose). Found live driving a Corp discard-to-5.
;;
;; Fix: only run the auto-end hook when wait-for-prompt-change! reports the prompt
;; actually moved/resolved. When unchanged the prompt is still blocking, so
;; auto-end could never fire anyway — gating loses nothing, removes the noise.
;; ============================================================================

(deftest choose-card-partial-multiselect-skips-auto-end-turn
  (testing "an unchanged (partial multi-select) prompt does NOT invoke the auto-end-turn check"
    (let [auto-end-called? (atom false)]
      (with-mock-state (mock-client-state
                        :side "corp"
                        :prompt {:prompt-type "select" :eid "disc-1"
                                 :msg "Discard down to 5 cards"
                                 :selectable [{:cid "c1" :title "Hedge Fund"}
                                              {:cid "c2" :title "IPO"}]})
        (with-redefs [ws/select-card! (fn [_card _eid] true)
                      ;; partial select: server toggles, prompt unchanged
                      prompts/wait-for-prompt-change! (fn [_eid & _] false)
                      basic/check-auto-end-turn! (fn [] (reset! auto-end-called? true))]
          (with-out-str (prompts/choose-card! 0))
          (is (false? @auto-end-called?)
              "partial multi-select must not reach the auto-end-turn check"))))))

(deftest choose-card-resolving-select-still-runs-auto-end-turn
  (testing "a select that RESOLVES the prompt (moved) still runs the auto-end-turn check"
    (let [auto-end-called? (atom false)]
      (with-mock-state (mock-client-state
                        :side "corp"
                        :prompt {:prompt-type "select" :eid "disc-1"
                                 :msg "Discard down to 5 cards"
                                 :selectable [{:cid "c1" :title "Hedge Fund"}]})
        (with-redefs [ws/select-card! (fn [_card _eid] true)
                      ;; final select reaches :max → prompt resolves/moves
                      prompts/wait-for-prompt-change! (fn [_eid & _] true)
                      basic/check-auto-end-turn! (fn [] (reset! auto-end-called? true))]
          (with-out-str (prompts/choose-card! 0))
          (is (true? @auto-end-called?)
              "a resolving select must still run the auto-end-turn check"))))))

;; ============================================================================
;; choose-card! — both-blocks ambiguity & no premature success (issue #40)
;;
;; Mutual Favor's "Choose an Icebreaker" carried BOTH a :choices block AND
;; :selectable cards. `choose-card <n>` printed "📇 Selecting card: X", fired
;; select-card!, then TIMED OUT (the engine wanted a text choice) — but still
;; returned :success, and the only right verb was `choose <n>`. Near game-losing
;; on both seats. Fix: a non-select prompt that doesn't move after a select means
;; choose-card was the wrong verb → error + steer to `choose`, and the
;; confirmation prints only AFTER the prompt actually moves.
;; ============================================================================

(deftest choose-card-on-nonselect-stall-steers-to-choose
  (testing "choose-card on a non-select prompt that doesn't move errors and steers to `choose` (#40)"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :prompt {:prompt-type "other" :eid "mf-1"
                               :msg "Choose an Icebreaker"
                               :choices [{:value "Unity"} {:value "Cleaver"}]
                               :selectable [{:cid "c1" :title "Unity"}
                                            {:cid "c2" :title "Cleaver"}]})
      (with-redefs [ws/select-card! (fn [_card _eid] true)
                    ;; engine wanted a text choice; the select never registered
                    prompts/wait-for-prompt-change! (fn [_eid & _] false)]
        (let [out (with-out-str
                    (let [r (prompts/choose-card! 0)]
                      (is (= :error (:status r))
                          (str "non-select stall must report error, not success: " r))))]
          (is (str/includes? out "choose <N>")
              (str "must steer to `choose <N>`: " out))
          (is (not (str/includes? out "📇 Selected"))
              (str "must not claim it selected the card: " out)))))))

(deftest choose-card-confirms-only-after-registration
  (testing "choose-card prints the card confirmation only AFTER the prompt moves (#40)"
    (with-mock-state (mock-client-state
                      :side "runner" :prompt other-typed-card-select-prompt)
      (with-redefs [ws/select-card! (fn [_card _eid] true)
                    prompts/wait-for-prompt-change! (fn [_eid & _] true)
                    basic/check-auto-end-turn! (fn [] nil)]
        (let [out (with-out-str
                    (let [r (prompts/choose-card! 1)]
                      (is (= :success (:status r)))))]
          (is (str/includes? out "Selected")
              (str "confirms the selection after registration: " out)))))))

;; ============================================================================
;; keep-hand / mulligan — opening-mulligan ergonomics (polish round 2026-06-22)
;;
;; Two related rough edges, both surfaced by playing a real game:
;;
;;  1. Opponent-mulligan-first. The Corp resolves its opening mulligan BEFORE the
;;     Runner gets a mulligan prompt at all. A Runner that calls keep-hand too
;;     early genuinely has no mulligan prompt — only a 'waiting for Corp' one. The
;;     old code reported the generic, alarming "No mulligan prompt active"; it now
;;     detects the waiting window and says "wait for them, then try again".
;;
;;  2. Genuine pre-sync gap. If NO prompt is cached at all (the brief window right
;;     after a client reconnect), keep-hand/mulligan now do a bounded
;;     wait-for-prompt before giving up, rather than false-negating immediately.
;; ============================================================================

(def ^:private waiting-for-corp-mulligan-prompt
  {:prompt-type "waiting"
   :eid "wait-mull-1"
   :msg "Waiting for Corp to keep hand or mulligan"
   :choices []})

(deftest keep-hand-absorbs-post-bounce-sync-race
  (testing "keep-hand waits for a late-arriving mulligan prompt instead of false-negating"
    (let [mull (make-prompt :prompt-type "mulligan"
                            :choices [{:value "Keep" :uuid "keep-uuid"}
                                      {:value "Mulligan" :uuid "mull-uuid"}])
          sent (atom nil)]
      ;; Cache starts with NO prompt (sync hasn't landed yet).
      (with-mock-state (mock-client-state :side "runner" :prompt nil)
        (with-redefs [;; wait-for-prompt simulates the async sync completing:
                      ;; it populates the cache and returns the prompt.
                      core/wait-for-prompt
                      (fn [_checks]
                        (swap! state/client-state assoc-in
                               [:game-state :runner :prompt-state] mull)
                        mull)
                      ws/send-message! (fn [_evt data] (reset! sent data) true)
                      prompts/wait-for-prompt-change! (fn [_eid & _] true)
                      basic/check-auto-end-turn! (fn [] nil)]
          (let [out (with-out-str
                      (let [r (prompts/keep-hand)]
                        (is (= :success (:status r))
                            (str "expected success after sync race, got: " r))))]
            ;; Pressed the Keep button (option 0), not bailed.
            (is (= {:choice {:uuid "keep-uuid"}} (:args @sent)))
            (is (not (str/includes? out "No mulligan prompt active")))))))))

(deftest keep-hand-still-errors-when-truly-no-prompt
  (testing "keep-hand reports no prompt when none is cached and none arrives"
    (with-mock-state (mock-client-state :side "runner" :prompt nil)
      (with-redefs [core/wait-for-prompt (fn [_checks] nil)]
        (let [result (atom nil)
              out (with-out-str (reset! result (prompts/keep-hand)))]
          (is (= :error (:status @result)))
          (is (str/includes? out "No mulligan prompt active")))))))

(deftest keep-hand-explains-when-waiting-on-opponent-mulligan
  (testing "keep-hand called before Corp mulligans gives a 'wait for them' message, not the generic error"
    (with-mock-state (mock-client-state :side "runner" :prompt waiting-for-corp-mulligan-prompt)
      ;; wait-for-prompt must NOT be consulted — a waiting prompt is already cached.
      (with-redefs [core/wait-for-prompt (fn [_checks]
                                           (throw (ex-info "should not wait when a prompt is cached" {})))]
        (let [result (atom nil)
              out (with-out-str (reset! result (prompts/keep-hand)))]
          (is (= :error (:status @result)))
          (is (= "Opponent mulligan pending" (:reason @result)))
          (is (str/includes? out "opening mulligan"))
          (is (not (str/includes? out "No mulligan prompt active"))
              (str "should not show the generic error for the waiting case, got: " out)))))))

(deftest mulligan-explains-when-waiting-on-opponent-mulligan
  (testing "mulligan called before Corp mulligans gives a 'wait for them' message, not the generic error"
    (with-mock-state (mock-client-state :side "runner" :prompt waiting-for-corp-mulligan-prompt)
      (with-redefs [core/wait-for-prompt (fn [_checks]
                                           (throw (ex-info "should not wait when a prompt is cached" {})))]
        (let [result (atom nil)
              out (with-out-str (reset! result (prompts/mulligan)))]
          (is (= :error (:status @result)))
          (is (= "Opponent mulligan pending" (:reason @result)))
          (is (str/includes? out "opening mulligan"))
          (is (not (str/includes? out "No mulligan prompt active"))))))))

(deftest mulligan-absorbs-post-bounce-sync-race
  (testing "mulligan waits for a late-arriving prompt instead of false-negating"
    (let [mull (make-prompt :prompt-type "mulligan"
                            :choices [{:value "Keep" :uuid "keep-uuid"}
                                      {:value "Mulligan" :uuid "mull-uuid"}])
          sent (atom nil)]
      (with-mock-state (mock-client-state :side "runner" :prompt nil)
        (with-redefs [core/wait-for-prompt
                      (fn [_checks]
                        (swap! state/client-state assoc-in
                               [:game-state :runner :prompt-state] mull)
                        mull)
                      ws/send-message! (fn [_evt data] (reset! sent data) true)
                      prompts/wait-for-prompt-change! (fn [_eid & _] true)
                      basic/check-auto-end-turn! (fn [] nil)]
          (let [out (with-out-str
                      (let [r (prompts/mulligan)]
                        (is (= :success (:status r))
                            (str "expected success after sync race, got: " r))))]
            ;; Pressed the Mulligan button (option 1).
            (is (= {:choice {:uuid "mull-uuid"}} (:args @sent)))
            (is (not (str/includes? out "No mulligan prompt active")))))))))

;; ============================================================================
;; press-choice! honesty (#75, marquee g2 Manegarm wedge)
;;
;; press-choice! used to print "✅ Chose:" BEFORE sending and return
;; {:status :success} unconditionally — a choice the engine rejected or
;; swallowed still reported success (misleading-output class). And when the
;; engine has minted DUPLICATE prompt instances (g2: five stacked Manegarm
;; "Choose one" prompts from Corp continue-spam), resolving one pops it and
;; reveals an identical-looking next instance; without surfacing "your choice
;; resolved, but an identical duplicate appeared", the seat reads the stack as
;; a no-op loop and gives up — that is exactly how g2 was abandoned one answer
;; short of draining the stack.
;; ============================================================================

(def manegarm-prompt
  {:msg "Choose one" :prompt-type "other" :eid {:eid 100}
   :card {:cid "mane-1" :title "Manegarm Skunkworks" :side "Corp"}
   :choices [{:uuid "u-pay" :value "Spend [Click][Click]" :idx 0}
             {:uuid "u-etr" :value "End the run" :idx 1}]})

(deftest choose-option-unchanged-prompt-is-not-success
  (testing "a choice after which the prompt does NOT move must not report :success (#75)"
    (with-mock-state (mock-client-state :side "runner" :prompt manegarm-prompt)
      (with-redefs [ws/send-message! (fn [_evt _data] true)
                    prompts/wait-for-prompt-change! (fn [_eid & _] false)
                    basic/check-auto-end-turn! (fn [] nil)]
        (let [result (atom nil)
              out (with-out-str (reset! result (prompts/choose-option! 1)))]
          (is (not= :success (:status @result))
              (str "prompt unchanged ⇒ the choice may have been rejected/swallowed; got: " @result))
          (is (not (str/includes? out "✅"))
              (str "must not print a success checkmark for an unresolved choice, got: " out)))))))

(deftest choose-option-duplicate-prompt-instance-is-surfaced
  (testing "a choice that resolves but reveals an IDENTICAL duplicate prompt (new eid, same msg+card) says so (#75)"
    (let [duplicate (assoc manegarm-prompt :eid {:eid 101}
                           :choices [{:uuid "u-pay2" :value "Spend [Click][Click]" :idx 0}
                                     {:uuid "u-etr2" :value "End the run" :idx 1}])]
      (with-mock-state (mock-client-state :side "runner" :prompt manegarm-prompt)
        (with-redefs [ws/send-message! (fn [_evt _data] true)
                      prompts/wait-for-prompt-change!
                      (fn [_eid & _]
                        ;; the engine pops the answered instance and the next
                        ;; stacked duplicate surfaces in its place
                        (swap! state/client-state assoc-in
                               [:game-state :runner :prompt-state] duplicate)
                        true)
                      basic/check-auto-end-turn! (fn [] nil)]
          (let [result (atom nil)
                out (with-out-str (reset! result (prompts/choose-option! 1)))]
            (is (= :success (:status @result))
                (str "the choice itself resolved — success, got: " @result))
            (is (:duplicate-prompt @result)
                "must flag the duplicate so a seat/bot can drain the stack instead of reading it as a no-op")
            (is (str/includes? out "identical")
                (str "must tell the seat an identical duplicate appeared, got: " out))))))))

(deftest choose-option-normal-resolution-unchanged
  (testing "a normally-resolving choice still reports plain :success with no duplicate flag"
    (with-mock-state (mock-client-state :side "runner" :prompt manegarm-prompt)
      (with-redefs [ws/send-message! (fn [_evt _data] true)
                    prompts/wait-for-prompt-change!
                    (fn [_eid & _]
                      (swap! state/client-state assoc-in
                             [:game-state :runner :prompt-state] nil)
                      true)
                    basic/check-auto-end-turn! (fn [] nil)]
        (let [result (atom nil)
              out (with-out-str (reset! result (prompts/choose-option! 1)))]
          (is (= :success (:status @result)))
          (is (not (:duplicate-prompt @result)))
          (is (str/includes? out "✅ Chose:")
              (str "normal resolution keeps the success line, got: " out)))))))

;; Review-panel tightening of the duplicate fingerprint (#75): nil = nil must
;; not identify two DIFFERENT prompts. Only a present msg AND present card cid
;; that both match may flag a duplicate.

(deftest choose-option-nil-fingerprint-is-not-a-duplicate
  (testing "two card-less prompts sharing only a nil/generic identity do not false-flag as duplicates"
    (let [old-prompt {:msg "Choose one" :prompt-type "other" :eid {:eid 200}
                      :choices [{:uuid "a1" :value "Yes" :idx 0}
                                {:uuid "a2" :value "No" :idx 1}]}
          ;; different prompt, also card-less, same generic msg, new eid
          next-prompt {:msg "Choose one" :prompt-type "other" :eid {:eid 201}
                       :choices [{:uuid "b1" :value "Left" :idx 0}
                                 {:uuid "b2" :value "Right" :idx 1}]}]
      (with-mock-state (mock-client-state :side "runner" :prompt old-prompt)
        (with-redefs [ws/send-message! (fn [_evt _data] true)
                      prompts/wait-for-prompt-change!
                      (fn [_eid & _]
                        (swap! state/client-state assoc-in
                               [:game-state :runner :prompt-state] next-prompt)
                        true)
                      basic/check-auto-end-turn! (fn [] nil)]
          (let [result (atom nil)]
            (with-out-str (reset! result (prompts/choose-option! 0)))
            (is (= :success (:status @result)))
            (is (not (:duplicate-prompt @result))
                "card-less prompts must not be identified by msg alone (nil cid = nil cid is not identity)")))))))
