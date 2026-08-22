(ns ai-actions-sad-path-test
  "Sad path tests for AI actions - friendly error messages and side guards.

   Triaged 2026-05-25 (Task #5): five tests deleted as aspirational (testing
   client-side validation the prod fns don't do, and the cost of adding wasn't
   justified): action-without-game-state, action-when-not-connected,
   choose-invalid-option, run-empty-server, run-invalid-server.

   Two side-guard tests reframed to assert the friendly error message rather
   than an exception — that's how the prod fns actually fail (rez-card! always
   had the guard; run! gained one alongside this triage).

   One new prod feature (nil-input guard on play-card!) added so
   test-play-card-nil-input has something correct to assert."
  (:require [clojure.test :refer :all]
            [clojure.string]
            [test-helpers :refer :all]
            [ai-actions]
            [ai-core]
            [ai-websocket-client-v2 :as ws]))

;; ============================================================================
;; Card Operations - Not Found Errors
;; ============================================================================

(deftest test-play-card-not-in-hand
  (testing "Playing card not in hand shows helpful error"
    (with-mock-state
      (mock-client-state
        :hand [{:cid 1 :title "Diesel" :cost 0}])
      (assert-error-message
        #(ai-actions/play-card! "Sure Gamble")
        "not found"))))

(deftest test-play-card-empty-hand
  (testing "Playing card from empty hand shows error"
    (with-mock-state
      (mock-client-state :hand [])
      (assert-error-message
        #(ai-actions/play-card! 0)
        "not found in hand"))))

(deftest test-play-card-invalid-index
  (testing "Playing card by out-of-bounds index shows error"
    (with-mock-state
      (mock-client-state
        :hand [{:cid 1 :title "Diesel"}])
      (assert-error-message
        #(ai-actions/play-card! 999)
        "not found"))))

(deftest test-install-card-not-found
  (testing "Installing non-existent card shows error"
    (with-mock-state
      (mock-client-state
        :hand [{:cid 1 :title "Daily Casts"}])
      (assert-error-message
        #(ai-actions/install-card! "Corroder")
        "not found"))))

;; ============================================================================
;; Side Guards
;; ============================================================================

(deftest test-runner-cannot-rez
  (testing "Runner trying to rez shows side error (no ws send)"
    (with-mock-state
      (mock-client-state :side "runner")
      (assert-error-message
        #(ai-actions/rez-card! "ICE Wall")
        "Only Corp"))))

(deftest test-corp-cannot-run
  (testing "Corp trying to run shows side error (no ws send)"
    (with-mock-state
      (mock-client-state :side "corp")
      (assert-error-message
        #(ai-actions/run! "HQ")
        "Only Runner"))))

(deftest test-rez-ice-refused-outside-run
  (testing "Corp rezzing ICE with no active run is refused (Bug #1 from Run #4)
            — engine accepts it but it's a strict waste of credits and reveals
            information for nothing; the prior 'allow' clause was an oversight"
    (with-mock-state
      (mock-client-state
        :side "corp"
        :game-state
        {:corp {:credit 10
                :servers {:hq {:ices [{:cid 1 :title "Brân 1.0"
                                       :type "ICE" :cost 6
                                       :rezzed false :side "Corp"
                                       :zone [:servers :hq :ices 0]}]}}}
         :runner {}
         :active-player "corp"})
      (assert-error-message
        #(ai-actions/rez-card! "Brân 1.0")
        "no active run"))))

(deftest test-rez-ice-allowed-during-approach
  (testing "Corp rezzing ICE during approach-ice phase is allowed (golden path)"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state
          :side "corp"
          :game-state
          {:corp {:credit 10
                  :servers {:hq {:ices [{:cid 1 :title "Brân 1.0"
                                         :type "ICE" :cost 6
                                         :rezzed false :side "Corp"
                                         :zone [:servers :hq :ices 0]}]}}}
           :runner {}
           :run {:phase "approach-ice" :position 1 :server [:hq]}
           :active-player "runner"})
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ;; Stub the rez ground-truth check so we don't poll for a
                      ;; state change the test isn't producing.
                      ai-core/find-card-by-cid (fn [_] {:cid 1 :rezzed true})]
          (let [out (with-out-str (ai-actions/rez-card! "Brân 1.0"))]
            (is (some #(= "rez" (get-in % [:data :command])) @sent)
                "rez command should be sent during approach-ice phase")
            (is (clojure.string/includes? out "🔴 Rezzed: Brân 1.0")
                (str "confirmed rez must print the confirmation, got: " out))))))))

(deftest test-rez-refused-reports-failure-not-success
  ;; #86: verify-action-in-log's name check scanned the last 5 log lines, which
  ;; routinely already mention the card (a derez, a rez-decision hint, embedded
  ;; effect text), and its result map was always truthy — so a rez the engine
  ;; REFUSED (e.g. can't afford it mid-run) printed "🔴 Rezzed" instantly.
  ;; Reproduced live 2026-08-02: corp at 0c, rez cost 2, card stayed unrezzed,
  ;; seat was told "🔴 Rezzed … (remaining: 0₵)". The verdict must come from the
  ;; card's own :rezzed flag, and a refusal must be reported as a failure.
  (testing "refused rez (card never flips to rezzed) reports failure, not 🔴 Rezzed"
    (let [sent (atom [])]
      (with-mock-state
        (mock-client-state
          :side "corp"
          :game-state
          {:corp {:credit 0
                  :servers {:remote1 {:content [{:cid 7 :title "Manegarm Skunkworks"
                                                 :type "Upgrade" :cost 2
                                                 :rezzed false :side "Corp"
                                                 :zone [:servers :remote1 :content]}]}}}
           :runner {}
           ;; Recent log already mentions the card — the exact false-positive
           ;; trigger for the old name-in-last-5-lines check.
           :log [{:text "ai-corp derezzes Manegarm Skunkworks"}
                 {:text "ai-runner approaches Server 1"}]
           :run {:phase "movement" :position 0 :server [:remote1]}
           :active-player "runner"})
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ;; The engine refuses: state never changes, so the card
                      ;; stays unrezzed no matter how often we look.
                      ai-core/find-card-by-cid (fn [_] {:cid 7 :rezzed false})
                      ;; Keep the failure poll fast in tests.
                      ai-core/action-timeout 50]
          (let [out (with-out-str (ai-actions/rez-card! "Manegarm Skunkworks"))]
            (is (not (clojure.string/includes? out "🔴 Rezzed"))
                (str "a refused rez must NOT print the success banner, got: " out))
            (is (clojure.string/includes? out "Rez NOT confirmed")
                (str "the failure must be stated plainly, got: " out))
            ;; A timeout doesn't prove WHY (could be latency, could be a
            ;; refusal) — the message must not assert a cause as fact.
            (is (not (clojure.string/includes? out "Likely can't afford"))
                (str "no unproven affordability claim, got: " out))))))))

;; ============================================================================
;; Prompt / Input Validation
;; ============================================================================

(deftest test-choose-without-prompt
  (testing "Choosing when no prompt shows helpful error"
    (with-mock-state
      (mock-client-state :prompt nil)
      (assert-error-message
        #(ai-actions/choose-by-index! 0)
        "No active prompt"))))

(deftest test-play-card-nil-input
  (testing "Playing card with nil input shows error (nil-input guard)"
    (with-mock-state
      (mock-client-state
        :hand [{:cid 1 :title "Sure Gamble"}])
      (assert-error-message
        #(ai-actions/play-card! nil)
        "invalid"))))

;; ============================================================================
;; Test Suite Summary
;; ============================================================================

(defn -main
  "Run sad path tests and report results"
  []
  (let [results (run-tests 'ai-actions-sad-path-test)]
    (println "\n========================================")
    (println "Sad Path Test Summary")
    (println "========================================")
    (println "Tests run:" (:test results))
    (println "Assertions:" (:pass results))
    (println "Failures:" (:fail results))
    (println "Errors:" (:error results))
    (println "========================================\n")
    (when (or (pos? (:fail results)) (pos? (:error results)))
      (System/exit 1))))

;; ============================================================================
;; #127: no ACTION surface may throw a raw exception on a sideless state
;; ============================================================================
;; #125 gave the DISPLAY family a sweep (ai_display_test/
;; test-no-display-surface-throws-on-a-sideless-state). #127 asks for the
;; equivalent over the action namespaces, and it is the more important half:
;; a display is something the seat chose to run, whereas
;; check-auto-end-turn! fires automatically after every clicks-consuming
;; action, so its throw lands inside an install/play/advance.
;;
;; Guest-panel catch (GPT-5.6): the first cut of this work shipped a source-text
;; ratchet INSTEAD of this sweep, and the ratchet is not equivalent —
;; `smart-end-turn!` (the CLI's *recommended* end-turn, dev/send_command:357,
;; and what the heuristic bots call) still threw on both fixtures, and the
;; ratchet budgeted it rather than failing on it. Behaviour is the contract;
;; the source ratchet only guards against the family SPREADING.
;;
;; Selection note: this sweeps the SOURCE namespaces, not the `ai-actions`
;; facade the CLI requires. The facade re-exports with `(def f other/f)`, which
;; copies the value but NOT `:arglists` — so an arity filter over `ns-publics`
;; of `ai-actions` silently selects nothing. That failure is invisible (a green
;; test that swept zero fns), hence the non-empty assertions below.

(def ^:private action-namespaces
  '[ai-basic-actions ai-card-actions ai-prompts ai-runs])

(def ^:private sideless-fixtures
  [["never joined"
    {:connected true :uid "test-user" :gameid nil :side nil :game-state nil}]
   ;; What `leave-lobby!` ACTUALLY leaves behind: it nils :gameid/:side but does
   ;; NOT clear :game-state. This is the state #125 was captured in, and a guard
   ;; that only checked for a missing BOARD would sail past the first fixture
   ;; and still throw here.
   ["after leaving (board still cached)"
    {:connected true :uid "test-user" :gameid nil :side nil
     :game-state {:active-player "corp" :turn 10
                  :corp {:click 0 :credit 35 :hand [] :hand-count 5
                         :hand-size {:total 5} :installed {} :prompt-state nil}
                  :runner {:click 0 :credit 5 :hand [] :hand-count 5
                           :hand-size {:total 5} :installed {} :rig {}}
                  :log []}}]])

(defn- zero-arg-callable?
  "True when this arglist can be invoked with no arguments: either it is empty,
   or it is variadic with no fixed params before the `&`.

   Second-pass guest catch: the first cut tested only the empty arglist, which
   silently skipped every `[& opts]` surface — `ai-runs/monitor-run!` among them
   — while the count sanity-check below still passed. An exclusion nobody can
   see is the same defect as the facade's missing :arglists."
  [al]
  (or (= 0 (count al))
      (= '& (first al))))

(defn- zero-arg-action-surfaces
  "Public fns of the action namespaces that a seat can call with no arguments,
   as [label var] pairs."
  []
  (doall
   (for [ns- action-namespaces
         :let [_ (require ns-)]
         [sym v] (sort-by first (ns-publics ns-))
         :when (some zero-arg-callable? (:arglists (meta v)))]
     [(str ns- "/" (name sym)) v])))

(defn- call-bounded
  "Invoke f, swallowing stdout. Returns nil on a clean return, or a describing
   string on a throw or a hang. Bounded because a fn that fails to bail on a
   sideless state may sit in a wait loop, which is itself a defect worth
   reporting rather than a suite that never finishes."
  [label f]
  (let [fut (future (try (with-out-str (f)) nil
                         (catch Throwable e
                           (str (.getSimpleName (class e)) ": " (.getMessage e)))))
        r (deref fut 5000 ::timeout)]
    (cond
      (= ::timeout r) (do (future-cancel fut)
                          (str label " -> did not return within 5s"))
      (some? r) (str label " -> " r)
      :else nil)))

(deftest test-no-action-surface-throws-on-a-sideless-state
  (testing "#127: every zero-arg action surface survives :side nil without a raw throw"
    (let [surfaces (zero-arg-action-surfaces)
          labels (set (map first surfaces))]
      ;; A filter that silently emptied would pass with flying colours, and the
      ;; facade's missing :arglists is exactly how that happens.
      (is (<= 20 (count surfaces))
          (str "sanity: the sweep collapsed, saw " (count surfaces) " surfaces"))
      (doseq [required ["ai-basic-actions/check-auto-end-turn!"
                        "ai-basic-actions/smart-end-turn!"
                        "ai-basic-actions/start-turn!"
                        "ai-basic-actions/end-turn"
                        "ai-prompts/discard-to-hand-size!"
                        ;; variadic `[& opts]` — pins zero-arg-callable?, which
                        ;; the empty-arglist-only filter silently skipped
                        "ai-runs/monitor-run!"]]
        (is (contains? labels required)
            (str "the sweep must cover " required ", swept: " (sort labels))))
      (doseq [[fixture-label st] sideless-fixtures]
        (let [throwers (with-mock-state st
                         ;; short-delay is pinned to 1ms for the sweep only.
                         ;; `auto-keep-mulligan` legitimately POLLS — 20 checks
                         ;; at the real 500ms — waiting for a mulligan prompt to
                         ;; arrive, so at production timing it trips the bound
                         ;; below without being a hang. It is deliberately NOT
                         ;; given a sideless bail: at game start the seat is
                         ;; polling for exactly the state that has not landed
                         ;; yet, and refusing early there would break the
                         ;; opening mulligan. Pinning the delay keeps the sweep
                         ;; fast while still proving the loop terminates.
                         (with-redefs [ws/send-message! (fn [_ _] true)
                                       ai-core/short-delay 1]
                           (doall (keep (fn [[label v]] (call-bounded label v))
                                        surfaces))))]
          (is (empty? throwers)
              (str "on a \"" fixture-label "\" state these ACTION surfaces throw a raw "
                   "exception at a model seat — and unlike a display, several of "
                   "these run automatically from inside other actions:\n  "
                   (clojure.string/join "\n  " throwers))))))))

;; ============================================================================
;; Ambiguity is not absence (#151 item 5)
;; ============================================================================
;; A duplicate-title lookup prints "❓ Multiple copies of 'X' installed" and
;; returns nil. The caller then read that nil as "no such card" and printed
;; "❌ Card not found installed: X" underneath — two contradictory verdicts on
;; one card, from a seat that was holding two of them. The Corp half of this was
;; already fixed (ambiguous-or-missing-error), but it counted CORP installs
;; only, so from a Runner seat the count was always 0 and every Runner
;; ambiguity fell through to the not-found lie.

(def ^:private two-leeches
  {:program [{:cid 101 :title "Leech" :zone [:rig :program] :counter {:virus 2}
              :abilities [{:label "Spend 1 hosted virus counter"}]}
             {:cid 102 :title "Leech" :zone [:rig :program] :counter {:virus 0}
              :abilities [{:label "Spend 1 hosted virus counter"}]}]})

(defn- runner-rig-state [rig]
  (mock-client-state
   :side "runner"
   :game-state {:runner {:click 3 :credit 5 :hand [] :rig rig}
                :corp {:click 0 :credit 5 :hand [] :servers {}}
                :active-player "runner"}))

(deftest test-use-ability-ambiguous-runner-card-is-not-not-found
  (testing "two copies installed: say which to specify, never 'not found'"
    (with-mock-state (runner-rig-state two-leeches)
      (let [out (with-out-str
                  (with-redefs [ws/send-message! (fn [_ _] true)]
                    (ai-actions/use-ability! "Leech" 0)))]
        (is (clojure.string/includes? out "Multiple copies")
            (str "expected the disambiguation list, got: " out))
        (is (not (clojure.string/includes? out "Card not found installed"))
            (str "a card the seat has TWO of is not missing, got: " out))
        (is (clojure.string/includes? out "[0]")
            (str "expected the [N] suffix hint, got: " out)))))
  (testing "the returned status names ambiguity, not absence"
    (with-mock-state (runner-rig-state two-leeches)
      (let [result (atom nil)
            _ (with-out-str
                (with-redefs [ws/send-message! (fn [_ _] true)]
                  (reset! result (ai-actions/use-ability! "Leech" 0))))
            result @result]
        (is (= :error (:status result)))
        (is (clojure.string/includes? (str (:reason result)) "Ambiguous")
            (str "expected an ambiguity reason, got: " (:reason result))))))
  (testing "a genuinely absent card still reports not-found"
    (with-mock-state (runner-rig-state {:program [{:cid 103 :title "Buzzsaw"
                                                   :zone [:rig :program]}]})
      (let [out (with-out-str
                  (with-redefs [ws/send-message! (fn [_ _] true)]
                    (ai-actions/use-ability! "Leech" 0)))]
        (is (clojure.string/includes? out "Card not found installed")
            (str "absence must still be reported as absence, got: " out))))))

(deftest test-trash-installed-ambiguous-runner-card-is-not-not-found
  (testing "trash-installed on a duplicate title does not claim the card is missing"
    (with-mock-state (runner-rig-state two-leeches)
      (let [out (with-out-str
                  (with-redefs [ws/send-message! (fn [_ _] true)]
                    (ai-actions/trash-installed! "Leech")))]
        (is (clojure.string/includes? out "Multiple copies")
            (str "expected the disambiguation list, got: " out))
        (is (not (clojure.string/includes? out "Card not found installed"))
            (str "ambiguity is not absence, got: " out))))))

(deftest test-abilities-ambiguous-runner-card-is-not-not-found
  (testing "the abilities display does not claim a duplicated card is missing"
    (with-mock-state (runner-rig-state two-leeches)
      (let [out (with-out-str (ai-actions/show-card-abilities "Leech"))]
        (is (clojure.string/includes? out "Multiple copies")
            (str "expected the disambiguation list, got: " out))
        (is (not (clojure.string/includes? out "Card not found installed"))
            (str "ambiguity is not absence, got: " out))))))

(deftest test-explicit-index-out-of-range-is-not-ambiguity
  ;; Guest panel: "Leech [9]" with two Leeches missed the lookup, and the honest-
  ;; error helper stripped the suffix, counted two, and told the seat to "specify
  ;; [N]" — advice it had just taken — with a worked example of `"Leech [9] [0]"`,
  ;; which is not something you can type. An out-of-range index is its own state.
  (testing "the range is named, and the example is typeable"
    (with-mock-state (runner-rig-state two-leeches)
      (let [out (with-out-str
                  (with-redefs [ws/send-message! (fn [_ _] true)]
                    (ai-actions/use-ability! "Leech [9]" 0)))]
        (is (clojure.string/includes? out "0..1")
            (str "expected the valid index range, got: " out))
        (is (not (clojure.string/includes? out "[9] [0]"))
            (str "must not suggest a doubled suffix, got: " out))
        (is (not (clojure.string/includes? out "Card not found installed"))
            (str "the card IS installed — twice, got: " out)))))
  (testing "an explicit index on a title with no copies is still not-found"
    (with-mock-state (runner-rig-state {:program [{:cid 103 :title "Buzzsaw"
                                                   :zone [:rig :program]}]})
      (let [out (with-out-str
                  (with-redefs [ws/send-message! (fn [_ _] true)]
                    (ai-actions/use-ability! "Leech [1]" 0)))]
        (is (clojure.string/includes? out "Card not found installed")
            (str "no Leech at all is absence, got: " out))))))

(deftest test-explicit-index-outranks-the-single-match-shortcut
  ;; With ONE copy installed, "Leech [9]" used to resolve to the Leech: the
  ;; single-match branch ran before the explicit-index branch, so an index the
  ;; seat deliberately typed was silently discarded — and it would have been
  ;; told about it if it had owned two (guest re-review).
  (testing "an out-of-range index on a single copy is refused, not silently used"
    (with-mock-state (runner-rig-state
                      {:program [{:cid 101 :title "Leech" :zone [:rig :program]
                                  :abilities [{:label "Spend 1 hosted virus counter"}]}]})
      (let [out (with-out-str
                  (with-redefs [ws/send-message! (fn [_ _] true)]
                    (ai-actions/use-ability! "Leech [9]" 0)))]
        (is (clojure.string/includes? out "0..0")
            (str "expected the valid range for one copy, got: " out)))))
  (testing "an in-range explicit index on a single copy still works"
    (with-mock-state (runner-rig-state
                      {:program [{:cid 101 :title "Leech" :zone [:rig :program]
                                  :abilities [{:label "Spend 1 hosted virus counter"}]}]})
      (let [out (with-out-str
                  (with-redefs [ws/send-message! (fn [_ _] true)]
                    (ai-actions/use-ability! "Leech [0]" 0)))]
        (is (not (clojure.string/includes? out "Card not found"))
            (str "\"Leech [0]\" names the only Leech, got: " out))
        (is (not (clojure.string/includes? out "0..0"))
            (str "an in-range index is not an error, got: " out))))))
