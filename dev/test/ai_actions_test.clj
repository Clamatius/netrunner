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

(deftest fire-subs-report-waiting-prompt-is-the-runners-decision
  (testing "#151 item 3 (manual path): a sub that hands the RUNNER a decision appears on our side as a new \"waiting\" prompt — say so; do not steer the Corp at choose-value/choose-card"
    (let [prompt {:msg "Waiting for Runner to make a decision"
                  :prompt-type "waiting" :card {:title "Karunā"} :eid 5152}
          {:keys [lines result]} (ai-card-actions/fire-subs-report
                                  "Karunā" 40 41 [{:text "ai-corp uses Karunā to do 2 net damage"}] prompt)
          out (str/join "\n" lines)]
      (is (not (re-find #"(?i)resolve it|choose-value|choose-card" out))
          (str "must not tell the Corp to resolve the Runner's decision, got:\n" out))
      (is (re-find #"(?i)runner" out)
          (str "must say whose decision it is, got:\n" out))
      (is (not= :waiting-input (:status result))
          (str "not OUR input that is awaited, got: " result)))))

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

;; ============================================================================
;; Bioroid click-break reachability (#95)
;; ============================================================================
;; Marquee 6d8f4cf8: the Runner seat reserved a click for Brân 1.0's printed
;; "Lose [click]: Break 1 subroutine" and found no path to it — `use-ability`
;; searches only the rig, so a Corp-owned card with :runner-abilities was
;; unreachable and the seat tanked a sub instead. use-ability must route
;; runner→corp-card calls to the engine's "runner-ability" command, and
;; use-runner-ability! must report honestly (status map) instead of
;; fire-and-forget silence.

(def bran
  {:cid 77 :title "Brân 1.0" :zone [:servers :rd :ices] :side "Corp" :type "ICE"
   :rezzed true :subtypes ["Bioroid" "Barrier"] :strength 4
   :subroutines [{:label "Install ice from HQ" :broken false}
                 {:label "End the run" :broken false}
                 {:label "End the run" :broken false}]
   :runner-abilities [{:label "Lose [click]: Break 1 subroutine"
                       :cost-label "Lose [click]"}]})

(defn- encounter-state-vs-bran []
  (mock-client-state
   :side "runner"
   :game-state {:runner {:credit 5 :click 2
                         :rig {:program [] :hardware [] :resource []}}
                :corp {:servers {:rd {:ices [bran] :content []}}}
                :run {:position 1 :server ["rd"] :phase "encounter-ice"}
                :active-player "runner"}))

(deftest use-ability-routes-to-runner-ability-on-corp-card
  (testing "runner use-ability on a Corp card with :runner-abilities sends runner-ability"
    (let [sent (atom [])]
      (with-mock-state (encounter-state-vs-bran)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-core/verify-ability-in-log (fn [& _] {:status :success})]
          (let [result (ai-card-actions/use-ability! "Brân 1.0" 0)]
            (is (= :success (:status result))
                "routed call reports the verified status, not a not-found error")
            (is (= 1 (count @sent)))
            (let [{:keys [data]} (first @sent)]
              (is (= "runner-ability" (:command data))
                  "engine command is runner-ability, not ability")
              (is (= 77 (get-in data [:args :card :cid]))
                  "card ref targets the encountered ICE")
              (is (= 0 (get-in data [:args :ability]))
                  "index addresses the :runner-abilities vector"))))))))

(deftest use-ability-still-errors-on-truly-missing-card
  (testing "a card installed nowhere still reports not-found and sends nothing"
    (let [sent (atom [])]
      (with-mock-state (encounter-state-vs-bran)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (ai-card-actions/use-ability! "Fenris" 0)]
            (is (= :error (:status result)))
            (is (zero? (count @sent)))))))))

(deftest use-ability-errors-on-corp-card-without-runner-abilities
  (testing "runner use-ability on a Corp card with no :runner-abilities errors without sending"
    (let [sent (atom [])
          palisade {:cid 78 :title "Palisade" :zone [:servers :rd :ices]
                    :side "Corp" :type "ICE" :rezzed true}]
      (with-mock-state (mock-client-state
                        :side "runner"
                        :game-state {:runner {:credit 5 :click 2
                                              :rig {:program [] :hardware [] :resource []}}
                                     :corp {:servers {:rd {:ices [palisade] :content []}}}
                                     :active-player "runner"})
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (ai-card-actions/use-ability! "Palisade" 0)]
            (is (= :error (:status result)))
            (is (zero? (count @sent))
                "no runner-ability send for a card the Runner can't use")))))))

(deftest use-runner-ability-returns-verified-status
  (testing "use-runner-ability! verifies and returns a status map (no more fire-and-forget)"
    (let [sent (atom [])]
      (with-mock-state (encounter-state-vs-bran)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-core/verify-ability-in-log (fn [& _] {:status :success})]
          (let [result (ai-card-actions/use-runner-ability! "Brân 1.0" 0)]
            (is (= :success (:status result)))
            (is (= "runner-ability" (:command (:data (first @sent)))))))))))

(deftest use-runner-ability-errors-on-missing-card
  (testing "use-runner-ability! on an absent card returns an error status map"
    (with-mock-state (mock-client-state :side "runner")
      (let [result (ai-card-actions/use-runner-ability! "Brân 1.0" 0)]
        (is (= :error (:status result)))))))

(deftest use-runner-ability-ambiguous-duplicate-is-not-a-not-found-lie
  (testing "two copies of the same Corp card: error says disambiguate, never 'not found'"
    ;; No :run here — an active encounter on one copy now resolves the tie
    ;; (#100), so genuine ambiguity means no run context.
    (let [bran2 (assoc bran :cid 78 :zone [:servers :hq :ices])]
      (with-mock-state (mock-client-state
                        :side "runner"
                        :game-state {:runner {:credit 5 :click 2
                                              :rig {:program [] :hardware [] :resource []}}
                                     :corp {:servers {:rd {:ices [bran] :content []}
                                                      :hq {:ices [bran2] :content []}}}
                                     :active-player "runner"})
        (let [out (java.io.StringWriter.)
              result (binding [*out* out] (ai-card-actions/use-runner-ability! "Brân 1.0" 0))]
          (is (= :error (:status result)))
          (is (not (clojure.string/includes? (str out) "not found"))
              (str "a card the disambiguation list just proved installed must not be called 'not found', got:\n" out))
          (is (clojure.string/includes? (str out) "[0]")
              "the disambiguation list with index syntax is shown"))))))

;; ============================================================================
;; verify-ability-in-log wiring (#97): a nil pre-prompt is a real baseline
;;
;; use-ability! captures pre-prompt BEFORE sending; when no prompt was open
;; that capture is nil. The old `(or pre-prompt (state/get-prompt))` treated
;; nil as "not supplied" and re-read AFTER the send — an ability whose only
;; observable effect is a fast prompt (Red Team's server choice, ~250ms) got
;; its own prompt captured as the baseline and false-failed as '❌ timeout'
;; while the prompt sat live (100% repro, marquee 30c4a1c0). The pure
;; classifier was already eid-aware; the bug lived in this wiring, which
;; every other test stubbed out.
;; ============================================================================

(def ^:private red-team-prompt
  {:eid {:eid 9575} :msg "Choose a server" :prompt-type "other"
   :choices [{:value "Archives"} {:value "R&D"} {:value "HQ"} {:value "Cancel"}]})

(deftest verify-ability-nil-pre-prompt-sees-fast-prompt
  (testing "explicit nil pre-prompt + live prompt in state -> :waiting-input, not timeout"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:runner {:prompt-state red-team-prompt}
                                   :log [{:text "a"} {:text "b"}]})
      (let [result (ai-core/verify-ability-in-log "Red Team" 300
                                                  {:pre-log-size 2 :pre-prompt nil})]
        (is (= :waiting-input (:status result))
            "the prompt that appeared after send IS the ability's effect")
        (is (= red-team-prompt (:prompt result)))))))

(deftest verify-ability-omitted-pre-prompt-still-falls-back
  (testing "omitting the :pre-prompt key entirely keeps the live-read fallback"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:runner {:prompt-state red-team-prompt}
                                   :log [{:text "a"} {:text "b"}]})
      (let [result (ai-core/verify-ability-in-log "Red Team" 60
                                                  {:pre-log-size 2})]
        (is (= :error (:status result))
            "with no baseline supplied the live prompt is the baseline; nothing new happens")))))

;; ============================================================================
;; find-installed-corp-card — active-run copy breaks title ties (#100)
;;
;; Marquee 30c4a1c0 T9: two Funhouse copies installed, one in the active
;; encounter — fire-subs "Funhouse" still failed on ambiguity and forced the
;; --fire-unbroken workaround. The run context is the natural tiebreak; the
;; [N]-suffix path stays for genuinely ambiguous non-run cases.
;; ============================================================================

(def ^:private funhouse-rd {:cid "fun-rd" :title "Funhouse" :rezzed true
                            :zone [:servers :rd :ices]})
(def ^:private funhouse-r3 {:cid "fun-r3" :title "Funhouse" :rezzed true
                            :zone [:servers :remote3 :ices]})

(defn- corp-state-with-two-funhouses [run]
  (mock-client-state
   :side "corp"
   :game-state {:corp {:servers {:rd {:ices [funhouse-rd]}
                                 :remote3 {:ices [funhouse-r3]}}}
                :run run}))

(deftest find-corp-card-prefers-active-run-copy
  (testing "with an active encounter on one copy, the encountered copy wins"
    (with-mock-state (corp-state-with-two-funhouses
                      {:server ["servers" "rd"] :position 1 :phase "encounter-ice"})
      (let [out (java.io.StringWriter.)
            card (binding [*out* out] (ai-core/find-installed-corp-card "Funhouse"))]
        (is (= "fun-rd" (:cid card))
            "must return the copy at the current run position")
        (is (str/includes? (str out) "active run")
            "must say the run context made the pick")))))

(deftest find-corp-card-still-ambiguous-outside-run
  (testing "no active run: duplicate titles still print disambiguation and return nil"
    (with-mock-state (corp-state-with-two-funhouses nil)
      (let [out (java.io.StringWriter.)
            card (binding [*out* out] (ai-core/find-installed-corp-card "Funhouse"))]
        (is (nil? card))
        (is (str/includes? (str out) "Multiple copies"))))))

(deftest find-corp-card-run-elsewhere-keeps-ambiguity
  (testing "a run whose current ICE is NOT one of the matches doesn't fake a tiebreak"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:corp {:servers {:hq {:ices [{:cid "pal" :title "Palisade"
                                                                 :zone [:servers :hq :ices]}]}
                                                    :rd {:ices [funhouse-rd]}
                                                    :remote3 {:ices [funhouse-r3]}}}
                                   :run {:server ["servers" "hq"] :position 1
                                         :phase "encounter-ice"}})
      (let [out (java.io.StringWriter.)
            card (binding [*out* out] (ai-core/find-installed-corp-card "Funhouse"))]
        (is (nil? card))
        (is (str/includes? (str out) "Multiple copies"))))))

(deftest find-corp-card-forced-encounter-beats-position
  (testing "a forced encounter's ICE (wire :encounters summary) outranks the
            position-derived ICE as the tiebreak (guest review of #100)"
    ;; Position points at the R&D copy, but the engine says the actual
    ;; encounter is the Server 3 copy (e.g. a redirected/forced encounter).
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:corp {:servers {:rd {:ices [funhouse-rd]}
                                                    :remote3 {:ices [funhouse-r3]}}}
                                   :encounters {:ice funhouse-r3 :encounter-count 1}
                                   :run {:server ["servers" "rd"] :position 1
                                         :phase "encounter-ice"}})
      (let [card (binding [*out* (java.io.StringWriter.)]
                   (ai-core/find-installed-corp-card "Funhouse"))]
        (is (= "fun-r3" (:cid card))
            "the encountered copy, not the positional copy, must win")))))

(deftest find-corp-card-explicit-index-still-wins
  (testing "an explicit [N] suffix bypasses the run tiebreak"
    (with-mock-state (corp-state-with-two-funhouses
                      {:server ["servers" "rd"] :position 1 :phase "encounter-ice"})
      (let [card (binding [*out* (java.io.StringWriter.)]
                   (ai-core/find-installed-corp-card "Funhouse [1]"))]
        (is (= "fun-r3" (:cid card)))))))

(comment
  ;; Run all happy path tests
  (run-tests 'ai-actions-test)

  ;; Run specific test
  (test-show-hand)

  ;; Run from main
  (-main)
  )

;; ---------------------------------------------------------------------------
;; #152 enable-conditions inventory (dev/ENABLE_CONDITIONS.md): manual fire-subs
;; board.cljs enables "Fire unbroken subroutines" only during an encounter with
;; THAT ice and only while it has an unbroken, unfired, resolvable sub. The
;; engine's play-unbroken-subroutines checks neither (only "no blocking
;; prompt"), so an unguarded send fires the subs of any rezzed ice at any time.
;; ---------------------------------------------------------------------------

(def ^:private corp-with-two-ice
  {:active-player "runner" :turn 6
   :corp {:click 0 :credit 5
          :servers {:hq {:ices [{:cid 11 :title "Tithe" :rezzed true :zone ["servers" "hq" "ices"]
                                 :subroutines [{:label "Do 1 net damage"} {:label "Gain 1 [Credits]"}]}]}
                    :rd {:ices [{:cid 12 :title "Whitespace" :rezzed true :zone ["servers" "rd" "ices"]
                                 :subroutines [{:label "Lose 3 [Credits]"}]}]}}}
   :runner {:click 2 :credit 5}
   :log []})

(deftest fire-subs-refuses-outside-an-encounter-with-that-ice
  (testing "no run at all → refused, nothing sent"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "corp" :game-state corp-with-two-ice)
        (with-redefs [ws/send-message! (fn [_e d] (swap! sent conj d) true)]
          (let [out (with-out-str (ai-card-actions/fire-unbroken-subs! "Tithe"))]
            (is (not-any? #(= "unbroken-subroutines" (:command %)) @sent)
                (str "must not fire outside an encounter, sent: " @sent))
            (is (re-find #"(?i)not encountering|encounter" out)
                (str "must say why, got:\n" out)))))))
  (testing "a run encountering a DIFFERENT ice → refused and names the encountered one"
    (let [sent (atom [])
          gs (assoc corp-with-two-ice
                    :run {:phase "encounter-ice" :position 1 :server [:rd]}
                    :encounters {:ice {:cid 12 :title "Whitespace"} :no-action false})]
      (with-mock-state (mock-client-state :side "corp" :game-state gs)
        (with-redefs [ws/send-message! (fn [_e d] (swap! sent conj d) true)]
          (let [out (with-out-str (ai-card-actions/fire-unbroken-subs! "Tithe"))]
            (is (empty? @sent) (str "must not fire Tithe while Whitespace is encountered, sent: " @sent))
            (is (re-find #"Whitespace" out) (str "must name the ICE actually being encountered, got:\n" out)))))))
  (testing "control: encountering Tithe with unbroken subs → the fire goes out"
    (let [sent (atom [])
          gs (assoc corp-with-two-ice
                    :run {:phase "encounter-ice" :position 1 :server [:hq]}
                    :encounters {:ice {:cid 11 :title "Tithe"} :no-action false})]
      (with-mock-state (mock-client-state :side "corp" :game-state gs)
        (with-redefs [ws/send-message! (fn [_e d] (swap! sent conj d) true)]
          (with-out-str (ai-card-actions/fire-unbroken-subs! "Tithe"))
          (is (some #(= "unbroken-subroutines" (:command %)) @sent)
              "the legitimate fire must still be sent")))))
  (testing "all subs already fired/broken → refused (nothing to fire)"
    (let [sent (atom [])
          gs (-> corp-with-two-ice
                 (assoc :run {:phase "encounter-ice" :position 1 :server [:hq]}
                        :encounters {:ice {:cid 11 :title "Tithe"} :no-action false})
                 (assoc-in [:corp :servers :hq :ices 0 :subroutines]
                           [{:label "Do 1 net damage" :fired true} {:label "Gain 1 [Credits]" :broken true}]))]
      (with-mock-state (mock-client-state :side "corp" :game-state gs)
        (with-redefs [ws/send-message! (fn [_e d] (swap! sent conj d) true)]
          (let [out (with-out-str (ai-card-actions/fire-unbroken-subs! "Tithe"))]
            (is (empty? @sent))
            (is (re-find #"(?i)no unbroken" out) (str "got:\n" out))))))))

(deftest fire-subs-allows-a-forced-encounter-outside-a-run
  ;; Guest panel CRITICAL on the first cut: the encountered ICE is the wire's
  ;; [:encounters :ice] FIRST; a forced encounter (Ganked!, Archangel on access) has
  ;; it with the run absent / at position 0 / in success. Requiring phase
  ;; encounter-ice blocked a legal Corp fire.
  (let [sent (atom [])
        gs (assoc corp-with-two-ice :encounters {:ice {:cid 11 :title "Tithe"} :no-action false})]
    (with-mock-state (mock-client-state :side "corp" :game-state gs)
      (with-redefs [ws/send-message! (fn [_e d] (swap! sent conj d) true)]
        (with-out-str (ai-card-actions/fire-unbroken-subs! "Tithe"))
        (is (some #(= "unbroken-subroutines" (:command %)) @sent)
            "a forced encounter with no run must still let the Corp fire")))))

(deftest trash-installed-offers-only-ice-and-programs
  ;; Guest panel: board.cljs offers the plain trash action only for ICE and
  ;; Programs; the engine's trash-button trashes whatever it is handed.
  (let [gs {:active-player "corp" :turn 3
            :corp {:click 2 :credit 5
                   :servers {:remote1 {:ices [{:cid 21 :title "Palisade" :type "ICE" :rezzed true :zone ["servers" "remote1" "ices"]}]
                                       :content [{:cid 22 :title "Nico Campaign" :type "Asset" :rezzed true :zone ["servers" "remote1" "content"]}]}}}
            :runner {:click 0}}]
    (testing "an own asset is refused, nothing sent"
      (let [sent (atom [])]
        (with-mock-state (mock-client-state :side "corp" :game-state gs)
          (with-redefs [ws/send-message! (fn [_e d] (swap! sent conj d) true)]
            (let [out (with-out-str (ai-card-actions/trash-installed! "Nico Campaign"))]
              (is (empty? @sent) (str "must not trash an asset via the plain action, sent: " @sent))
              (is (re-find #"(?i)ICE and Programs" out) (str "must say what the action is for, got:\n" out)))))))
    (testing "control: ICE still trashes"
      (let [sent (atom [])]
        (with-mock-state (mock-client-state :side "corp" :game-state gs)
          (with-redefs [ws/send-message! (fn [_e d] (swap! sent conj d) true)]
            (with-out-str (ai-card-actions/trash-installed! "Palisade"))
            (is (some #(= "trash" (:command %)) @sent))))))))

(deftest score-uses-the-current-advancement-requirement
  ;; Guest panel CRITICAL: board.cljs and the engine score against
  ;; :current-advancement-requirement (SanSan City Grid lowers it); comparing to
  ;; the printed :advancementcost refused a legal score.
  (let [sent (atom [])
        gs {:active-player "corp" :turn 5
            :corp {:click 2 :credit 5
                   :servers {:remote1 {:ices [] :content [{:cid 31 :title "Offworld Office" :type "Agenda"
                                                          :advancementcost 3 :current-advancement-requirement 2
                                                          :advance-counter 2 :agendapoints 2
                                                          :zone ["servers" "remote1" "content"]}]}}}
            :runner {:click 0}
            :log []}]
    (with-mock-state (mock-client-state :side "corp" :game-state gs)
      (with-redefs [ws/send-message! (fn [_e d] (swap! sent conj d) true)]
        (with-out-str (ai-card-actions/score-agenda! "Offworld Office"))
        (is (some #(= "score" (:command %)) @sent)
            "2 counters against a current requirement of 2 is scoreable — the printed 3 must not block it")))))

(deftest fire-subs-resolves-a-non-installed-forced-encounter
  ;; Second guest pass, CRITICAL: Archangel / Chrysalis / Herald / Sapper force
  ;; an encounter ON ACCESS — the accessed card sits in R&D/HQ/Archives, not in
  ;; any server's :ices, and the wire supplies it under [:encounters :ice] with
  ;; :cid and subroutines. Looking it up among INSTALLED ice found nothing.
  (let [sent (atom [])
        gs {:active-player "runner" :turn 7
            :run {:phase "success" :position 0 :server [:rd]}
            :encounters {:ice {:cid 77 :title "Archangel" :type "ICE" :rezzed true :zone ["deck"]
                               :subroutines [{:label "Trace 6 - add an installed card to the grip"}]}
                         :no-action false :encounter-count 1}
            :corp {:click 0 :credit 5 :servers {:rd {:ices []}}}
            :runner {:click 2 :credit 5}
            :log []}]
    (with-mock-state (mock-client-state :side "corp" :game-state gs)
      (with-redefs [ws/send-message! (fn [_e d] (swap! sent conj d) true)]
        (let [out (with-out-str (ai-card-actions/fire-unbroken-subs! "Archangel"))]
          (is (some #(= "unbroken-subroutines" (:command %)) @sent)
              (str "the encountered-on-access ICE must be fireable, got:\n" out))
          (is (not (re-find #"(?i)not found installed" out))
              (str "must resolve the encounter summary, not only installed ice, got:\n" out)))))))
