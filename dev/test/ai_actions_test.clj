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
   ;; :playable is what the wire actually carries (game.core.diffs/ability-keys)
   ;; and it is TRUE here: this fixture is mid-encounter, where a bioroid's
   ;; click-break is legal. The fixture omitted it, which was fine while nothing
   ;; read it and wrong the moment #116's gate did — the same
   ;; fixture-omits-the-field-that-matters trap as #31's missing :log.
   :runner-abilities [{:label "Lose [click]: Break 1 subroutine"
                       :cost-label "Lose [click]"
                       :playable true}]})

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

;; ============================================================================
;; #116 — a phase-legality refusal must not be reported as a log timeout
;; ============================================================================
;; The Luna Runner seat ran `use-ability "Mayfly" 0` at the approach window and
;; got `Ability not confirmed in game log (timeout).` That sentence describes a
;; HARNESS fault — lost message, slow server, desync — and the correct response
;; to it is a retry. The actual condition was a rules one, whose correct response
;; is `continue` first. The seat guessed right; retrying an illegal ability at
;; the wrong phase is the duplicate-send pattern that mints phantom prompts
;; (#75/#77).
;;
;; The engine already publishes the verdict: game.core.diffs/ability-playable?
;; assoc's :playable only when the ability is legal right now, and board.cljs
;; renders an ability without it as [:li.disabled] — no click handler, so the
;; human UI cannot send what we were sending. Pinned against the real engine in
;; game.ai-ability-legality-test: at approach-ice a Corroder's break ability has
;; NO :playable while its pump ability keeps it.

(def ^:private corroder-at-approach
  {:cid 90 :title "Corroder" :type "Program" :subtypes ["Icebreaker" "Fracter"]
   :zone [:program]
   :abilities [{:label "Break 1 Barrier subroutine" :cost-label "1 [Credits]"}
               {:label "Add 1 strength" :cost-label "1 [Credits]" :playable true}]})

(defn- approach-state
  "Runner mid-run at the APPROACH window (not encountering), rig holding a
   breaker whose printed break ability the engine has not marked playable."
  []
  (mock-client-state
   :side "runner"
   :game-state {:runner {:credit 5 :click 2
                         :rig {:program [corroder-at-approach]
                               :hardware [] :resource []}}
                :corp {:servers {:hq {:ices [] :content []}}}
                :run {:position 1 :server ["hq"] :phase "approach-ice"}
                :active-player "runner"}))

(deftest use-ability-refuses-unplayable-break-instead-of-blaming-the-log
  (testing "#116: nothing is sent, so the illegal action can't mint a prompt"
    (let [sent (atom [])]
      (with-mock-state (approach-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (ai-card-actions/use-ability! "Corroder" 0)]
            (is (= :error (:status result)))
            (is (zero? (count @sent))
                "an ability the human UI renders as disabled must not go on the wire"))))))

  (testing "#116: the message names the CAUSE, not the detection mechanism"
    (let [out (with-out-str
                (with-mock-state (approach-state)
                  (with-redefs [ws/send-message! (fn [& _] nil)]
                    (ai-card-actions/use-ability! "Corroder" 0))))]
      (is (not (str/includes? out "timeout"))
          (str "'timeout' invites the retry that mints phantom prompts; got:\n" out))
      (is (not (str/includes? out "game log"))
          (str "the log is how we noticed, not why it failed; got:\n" out))
      (is (str/includes? out "approach-ice")
          (str "name the phase the seat is actually at; got:\n" out))
      (is (re-find #"(?i)encounter" out)
          (str "and the rule: subroutines break during the encounter; got:\n" out))
      (is (str/includes? out "continue")
          (str "and the one command that fixes it; got:\n" out))))

  (testing "#116: the SAME card's pump ability still sends — the gate is per-ability"
    ;; A phase-level 'nothing works at approach' rule would block a legal action.
    ;; The engine marks this one :playable at approach (pinned in the engine test).
    (let [sent (atom [])]
      (with-mock-state (approach-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-core/verify-ability-in-log (fn [& _] {:status :success})]
          (let [result (ai-card-actions/use-ability! "Corroder" 1)]
            (is (= :success (:status result)))
            (is (= 1 (count @sent))
                "pumping outside an encounter is legal and must still be sent")))))))

(deftest use-ability-out-of-range-index-names-what-exists
  (testing "#116: reaching for the encounter-only 'Fully break' index at approach"
    ;; The dynamic ability is absent from the list until the encounter, so the
    ;; index is out of range — a different refusal from 'present but unplayable',
    ;; and previously also reported as a log timeout.
    (let [sent (atom [])
          out (with-out-str
                (with-mock-state (approach-state)
                  (with-redefs [ws/send-message! (mock-websocket-send! sent)]
                    (ai-card-actions/use-ability! "Corroder" 2))))]
      (is (zero? (count @sent)))
      (is (not (str/includes? out "timeout")) (str "got:\n" out))
      (is (str/includes? out "Break 1 Barrier subroutine")
          (str "list what the card actually offers; got:\n" out))
      (is (str/includes? out "not usable right now")
          (str "and mark which of those are currently blocked; got:\n" out)))))

(deftest use-runner-ability-shares-the-legality-gate
  (testing "#116: the bioroid sender refuses the same way — one rule, both senders"
    ;; N-senders-one-command: #75/#77 (three send-continue! copies), #113, #115.
    (let [sent (atom [])
          bran-unplayable (assoc-in bran [:runner-abilities 0 :playable] false)
          out (with-out-str
                (with-mock-state
                  (mock-client-state
                   :side "runner"
                   :game-state {:runner {:credit 5 :click 2
                                         :rig {:program [] :hardware [] :resource []}}
                                :corp {:servers {:rd {:ices [bran-unplayable] :content []}}}
                                :run {:position 1 :server ["rd"] :phase "approach-ice"}
                                :active-player "runner"})
                  (with-redefs [ws/send-message! (mock-websocket-send! sent)]
                    (ai-card-actions/use-runner-ability! "Brân 1.0" 0))))]
      (is (zero? (count @sent))
          "the click-break is as encounter-bound as a breaker's")
      (is (not (str/includes? out "timeout")) (str "got:\n" out))
      (is (str/includes? out "continue")
          (str "same recovery, same wording; got:\n" out))
      (is (= 1 (count (re-seq #"NOT sent" out)))
          (str "the diagnosis prints ONCE, not once per cond probe; got:\n" out)))))

;; ---------------------------------------------------------------------------
;; #116, guest-panel round: the four ways the first cut was wrong
;; ---------------------------------------------------------------------------

(defn- run-state-at [phase]
  (mock-client-state
   :side "runner"
   :game-state {:runner {:credit 5 :click 2
                         :rig {:program [corroder-at-approach]
                               :hardware [] :resource []}}
                :corp {:servers {:hq {:ices [] :content []}}}
                :run {:position 1 :server ["hq"] :phase phase}
                :active-player "runner"}))

(deftest use-ability-only-promises-the-encounter-when-it-is-actually-ahead
  ;; MAJOR: the first cut printed "you have not reached the ICE yet → continue to
  ;; enter the encounter" at EVERY non-encounter phase. The engine's run ladder is
  ;; approach-ice / encounter-ice / movement / pass-ice / success; at movement or
  ;; success the ICE is behind the Runner, so `continue` moves them further from
  ;; any recovery. Guidance text that asserts engine behaviour is code (#115).
  (testing "#116: at approach-ice the encounter IS ahead — promise it"
    (let [out (with-out-str
                (with-mock-state (run-state-at "approach-ice")
                  (with-redefs [ws/send-message! (fn [& _] nil)]
                    (ai-card-actions/use-ability! "Corroder" 0))))]
      (is (str/includes? out "have not reached the ICE yet") (str "got:\n" out))
      (is (str/includes? out "continue") (str "got:\n" out))))

  (doseq [phase ["movement" "pass-ice" "success"]]
    (testing (str "#116: at " phase " the ICE is behind us — do NOT say continue")
      (let [out (with-out-str
                  (with-mock-state (run-state-at phase)
                    (with-redefs [ws/send-message! (fn [& _] nil)]
                      (ai-card-actions/use-ability! "Corroder" 0))))]
        (is (not (str/includes? out "have not reached the ICE yet"))
            (str "false at " phase "; got:\n" out))
        (is (not (str/includes? out "Use 'continue' to enter the encounter"))
            (str "continuing cannot make this legal at " phase "; got:\n" out))
        (is (re-find #"(?i)encounter" out)
            (str "the rule itself still holds and must be stated; got:\n" out))))))

(deftest use-ability-generic-hint-names-real-commands
  ;; MINOR, but the exact class this project keeps re-learning: the first cut
  ;; pointed the seat at `show-card`, which `send_command` does not have. An
  ;; invented command name reads as authoritative and costs a wasted round trip.
  (testing "#116: a non-break unplayable ability points at commands that exist"
    (let [pump-unplayable (assoc-in corroder-at-approach [:abilities 1 :playable] false)
          out (with-out-str
                (with-mock-state
                  (mock-client-state
                   :side "runner"
                   :game-state {:runner {:credit 5 :click 2
                                         :rig {:program [pump-unplayable]
                                               :hardware [] :resource []}}
                                :active-player "runner"})
                  (with-redefs [ws/send-message! (fn [& _] nil)]
                    (ai-card-actions/use-ability! "Corroder" 1))))]
      (is (not (str/includes? out "show-card"))
          (str "no such command; got:\n" out))
      (is (str/includes? out "abilities")
          (str "point at the indexed menu; got:\n" out))
      (is (str/includes? out "card-text")
          (str "and the real card-lookup command; got:\n" out)))))

(deftest use-ability-lets-an-in-flight-diff-settle-before-refusing
  ;; CRITICAL: :playable is a CACHED negative. `continue` then `use-ability` can
  ;; read the pre-encounter snapshot and hard-refuse an action that is about to
  ;; be legal. board.cljs disables its button off the same cached field, but a
  ;; human re-reads a greyed button where a seat treats an error as final.
  (testing "#116: an ability that becomes playable mid-wait is SENT, not refused"
    (let [sent (atom [])
          reads (atom 0)
          ;; The diff lands on the THIRD read. Read 1 is use-ability!'s own card
          ;; lookup and read 2 is settle's first poll, so the ability only turns
          ;; playable after settle has had to sleep at least once — otherwise
          ;; this test passes with a 0ms window and pins nothing. (It did: the
          ;; first version put the flip at read 2 and survived that mutation.)
          card-now (fn [_]
                     (if (< (swap! reads inc) 3)
                       corroder-at-approach
                       (assoc-in corroder-at-approach [:abilities 0 :playable] true)))]
      (with-mock-state (run-state-at "approach-ice")
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-core/find-installed-card card-now
                      ai-core/verify-ability-in-log (fn [& _] {:status :success})]
          (let [result (ai-card-actions/use-ability! "Corroder" 0)]
            (is (= :success (:status result))
                "the settle window must not turn a race into a hard refusal")
            (is (= 1 (count @sent))))))))

  (testing "#116: a genuinely illegal ability still refuses after the wait"
    (let [sent (atom [])]
      (with-mock-state (run-state-at "approach-ice")
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (ai-card-actions/use-ability! "Corroder" 0)]
            (is (= :error (:status result)))
            (is (zero? (count @sent))
                "the flag never appears, so we pay the wait and then refuse")))))))

(deftest use-ability-timeout-is-re-explained-from-current-state
  ;; MAJOR / the issue's literal ask: "before falling through to the timeout
  ;; path, check whether the ability is legal in the current run phase." The
  ;; pre-send gate cannot catch the reverse race — a STALE POSITIVE, where the
  ;; ability still reads :playable, we send, the engine refuses, and the diff
  ;; explaining why lands while we are polling the log.
  (testing "#116: a timeout whose cause is visible in current state is re-worded"
    (let [reads (atom 0)
          ;; Playable for reads 1-2 (use-ability!'s card lookup, then settle's
          ;; single poll — settle returns at once when the flag is there) so the
          ;; pre-send gate PASSES and we actually send. The diff that explains
          ;; the refusal lands by read 3, which is the post-timeout re-read.
          ;; Flipping at read 2 instead makes the pre-send gate fire and the
          ;; assertions below pass without ever reaching the code under test.
          card-now (fn [_]
                     (if (< (swap! reads inc) 3)
                       (assoc-in corroder-at-approach [:abilities 0 :playable] true)
                       corroder-at-approach))
          out (with-out-str
                (with-mock-state (run-state-at "approach-ice")
                  (with-redefs [ws/send-message! (fn [& _] nil)
                                ai-core/find-installed-card card-now
                                ai-core/verify-ability-in-log
                                (fn [& _] {:status :error
                                           :reason "Ability not confirmed in game log (timeout)."})]
                    (ai-card-actions/use-ability! "Corroder" 0))))]
      (is (not (str/includes? out "timeout"))
          (str "current state explains it as a rules failure; got:\n" out))
      (is (re-find #"(?i)encounter" out)
          (str "and says which rule; got:\n" out))))

  (testing "#116: a timeout with no rules explanation still reports as a timeout"
    ;; A real harness fault must keep its own wording — this is the branch that
    ;; stops the re-diagnosis from swallowing genuine desyncs.
    (let [playable-card (assoc-in corroder-at-approach [:abilities 0 :playable] true)
          out (with-out-str
                (with-mock-state (run-state-at "encounter-ice")
                  (with-redefs [ws/send-message! (fn [& _] nil)
                                ai-core/find-installed-card (fn [_] playable-card)
                                ai-core/verify-ability-in-log
                                (fn [& _] {:status :error
                                           :reason "Ability not confirmed in game log (timeout)."})]
                    (ai-card-actions/use-ability! "Corroder" 0))))]
      (is (str/includes? out "timeout")
          (str "nothing in state contradicts the send; got:\n" out)))))
