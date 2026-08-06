(ns ai-pure-functions-test
  "Characterization tests for pure functions in ai_runs.clj and ai_run_tactics.clj

   These tests lock down behavior before refactoring. They test functions
   that have no side effects and are easy to verify in isolation."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-runs :as runs]
            [ai-run-tactics :as tactics]
            [ai-core :as core]))

;; ============================================================================
;; normalize-side tests
;; ============================================================================

(deftest test-normalize-side-nil
  (testing "nil returns nil"
    (is (nil? (runs/normalize-side nil)))))

(deftest test-normalize-side-false
  (testing "false returns nil (treated as 'no one passed')"
    (is (nil? (runs/normalize-side false)))))

(deftest test-normalize-side-keyword
  (testing "keywords are converted to strings"
    (is (= "runner" (runs/normalize-side :runner)))
    (is (= "corp" (runs/normalize-side :corp)))))

(deftest test-normalize-side-string
  (testing "strings are returned as-is"
    (is (= "runner" (runs/normalize-side "runner")))
    (is (= "corp" (runs/normalize-side "corp")))))

(deftest test-normalize-side-other
  (testing "other values are stringified"
    (is (= "42" (runs/normalize-side 42)))))

;; ============================================================================
;; has-real-decision? tests
;; ============================================================================

(deftest test-has-real-decision-nil-prompt
  (testing "nil prompt returns falsy"
    (is (not (runs/has-real-decision? nil)))))

(deftest test-has-real-decision-trivial-choices-only
  (testing "only trivial choices (Done/Continue/Ok) is not a real decision"
    (let [prompt {:choices [{:value "Done"}
                            {:value "Continue"}
                            {:value "Ok"}]}]
      (is (not (runs/has-real-decision? prompt))))))

(deftest test-has-real-decision-single-done
  (testing "single 'Done' choice is not a real decision"
    (let [prompt {:choices [{:value "Done"}]}]
      (is (not (runs/has-real-decision? prompt))))))

(deftest test-has-real-decision-two-meaningful-choices
  (testing "2+ meaningful choices is a real decision"
    (let [prompt {:choices [{:value "Use Corroder"}
                            {:value "Use Gordian Blade"}
                            {:value "Done"}]}]
      (is (runs/has-real-decision? prompt)))))

(deftest test-has-real-decision-selectable-cards
  (testing "having selectable cards is a real decision"
    (let [prompt {:choices []
                  :selectable [{:cid 1 :title "Some Card"}]}]
      (is (runs/has-real-decision? prompt)))))

(deftest test-has-real-decision-empty
  (testing "empty choices and selectables is not a real decision"
    (let [prompt {:choices [] :selectable []}]
      (is (not (runs/has-real-decision? prompt))))))

(deftest test-has-real-decision-case-insensitive
  (testing "trivial choice detection is case-insensitive"
    (let [prompt {:choices [{:value "DONE"}
                            {:value "done"}
                            {:value "DoNe"}]}]
      (is (not (runs/has-real-decision? prompt))))))

;; ============================================================================
;; is-waiting-prompt? tests
;; ============================================================================

(deftest test-is-waiting-prompt-nil
  (testing "nil prompt returns falsy"
    (is (not (runs/is-waiting-prompt? nil)))))

(deftest test-is-waiting-prompt-true
  (testing "waiting type prompt returns true"
    (let [prompt {:prompt-type "waiting" :msg "Waiting for opponent"}]
      (is (runs/is-waiting-prompt? prompt)))))

(deftest test-is-waiting-prompt-false
  (testing "non-waiting prompts return false"
    (is (not (runs/is-waiting-prompt? {:prompt-type "select"})))
    (is (not (runs/is-waiting-prompt? {:prompt-type "run"})))
    (is (not (runs/is-waiting-prompt? {:prompt-type "choice"})))))

;; ============================================================================
;; should-i-act? tests
;; ============================================================================

(defn make-run-state-for-should-i-act [no-action]
  "Create minimal state with run and no-action value"
  {:game-state {:run {:phase "approach-ice"
                      :no-action no-action}}})

(deftest test-should-i-act-no-run
  (testing "no run returns nil"
    (let [state {:game-state {}}]
      (is (nil? (runs/should-i-act? state "runner")))
      (is (nil? (runs/should-i-act? state "corp"))))))

(deftest test-should-i-act-fresh-phase-runner-acts
  (testing "fresh phase (nil no-action) - runner should act"
    (let [state (make-run-state-for-should-i-act nil)]
      (is (true? (runs/should-i-act? state "runner")))
      (is (false? (runs/should-i-act? state "corp"))))))

(deftest test-should-i-act-runner-passed-corp-acts
  (testing "runner passed - corp should act"
    (let [state (make-run-state-for-should-i-act "runner")]
      (is (false? (runs/should-i-act? state "runner")))
      (is (true? (runs/should-i-act? state "corp"))))))

(deftest test-should-i-act-corp-passed-runner-acts
  (testing "corp passed - runner should act"
    (let [state (make-run-state-for-should-i-act "corp")]
      (is (true? (runs/should-i-act? state "runner")))
      (is (false? (runs/should-i-act? state "corp"))))))

(deftest test-should-i-act-keyword-no-action
  (testing "keyword no-action values work (server sometimes sends keywords)"
    (let [state (make-run-state-for-should-i-act :runner)]
      (is (false? (runs/should-i-act? state "runner")))
      (is (true? (runs/should-i-act? state "corp"))))))

(deftest test-should-i-act-false-no-action
  (testing "false no-action treated as fresh phase"
    (let [state (make-run-state-for-should-i-act false)]
      (is (true? (runs/should-i-act? state "runner")))
      (is (false? (runs/should-i-act? state "corp"))))))

;; ============================================================================
;; can-auto-continue? tests
;; ============================================================================

(defn make-state-for-auto-continue
  "Create state for can-auto-continue? testing"
  [& {:keys [prompt-type choices selectable no-action run-phase ice-rezzed side]
      :or {prompt-type "run" choices [] selectable [] no-action nil
           run-phase "movement" ice-rezzed true side "runner"}}]
  (let [ice-list (when (not ice-rezzed)
                   [{:cid 1 :title "Test ICE" :rezzed false}])]
    {:game-state {:run {:phase run-phase
                        :no-action no-action
                        :position 0
                        :server ["hq"]}
                  :corp {:servers {:hq {:ices ice-list}}}}}))

(deftest test-can-auto-continue-empty-window
  (testing "empty paid ability window can auto-continue"
    (let [prompt {:prompt-type "run" :choices [] :selectable []}
          state (make-state-for-auto-continue :no-action nil)]
      (is (runs/can-auto-continue? prompt "movement" "runner" state)))))

(deftest test-can-auto-continue-has-choices
  (testing "prompt with choices cannot auto-continue"
    (let [prompt {:prompt-type "run" :choices [{:value "Some Option"}] :selectable []}
          state (make-state-for-auto-continue)]
      (is (not (runs/can-auto-continue? prompt "movement" "runner" state))))))

(deftest test-can-auto-continue-has-selectables
  (testing "prompt with selectables cannot auto-continue"
    (let [prompt {:prompt-type "run" :choices [] :selectable [{:cid 1}]}
          state (make-state-for-auto-continue)]
      (is (not (runs/can-auto-continue? prompt "movement" "runner" state))))))

(deftest test-can-auto-continue-not-run-prompt
  (testing "non-run prompt cannot auto-continue"
    (let [prompt {:prompt-type "select" :choices [] :selectable []}
          state (make-state-for-auto-continue)]
      (is (not (runs/can-auto-continue? prompt "movement" "runner" state))))))

(deftest test-can-auto-continue-already-passed
  (testing "cannot auto-continue if I already passed"
    (let [prompt {:prompt-type "run" :choices [] :selectable []}
          state (make-state-for-auto-continue :no-action "runner")]
      (is (not (runs/can-auto-continue? prompt "movement" "runner" state))))))

(deftest test-can-auto-continue-corp-unrezzed-ice
  (testing "corp at approach-ice with unrezzed ICE should NOT auto-continue"
    (let [prompt {:prompt-type "run" :choices [] :selectable []}
          state (make-state-for-auto-continue :run-phase "approach-ice"
                                              :ice-rezzed false
                                              :no-action nil)]
      (is (not (runs/can-auto-continue? prompt "approach-ice" "corp" state))))))

(deftest test-can-auto-continue-corp-rezzed-ice
  (testing "corp at approach-ice with rezzed ICE CAN auto-continue"
    (let [prompt {:prompt-type "run" :choices [] :selectable []}
          state (make-state-for-auto-continue :run-phase "approach-ice"
                                              :ice-rezzed true
                                              :no-action "runner")]  ; runner passed, corp's turn
      (is (runs/can-auto-continue? prompt "approach-ice" "corp" state)))))

;; ============================================================================
;; ice-primary-type tests (now in ai-run-tactics)
;; ============================================================================

(deftest test-ice-primary-type-barrier
  (testing "Barrier ICE returns Barrier"
    (let [ice {:subtypes ["Barrier"]}]
      (is (= "Barrier" (tactics/ice-primary-type ice))))))

(deftest test-ice-primary-type-code-gate
  (testing "Code Gate ICE returns Code Gate"
    (let [ice {:subtypes ["Code Gate"]}]
      (is (= "Code Gate" (tactics/ice-primary-type ice))))))

(deftest test-ice-primary-type-sentry
  (testing "Sentry ICE returns Sentry"
    (let [ice {:subtypes ["Sentry"]}]
      (is (= "Sentry" (tactics/ice-primary-type ice))))))

(deftest test-ice-primary-type-multi-subtype
  (testing "Multi-subtype ICE returns first matching primary type"
    ;; Barrier checked first, then Code Gate, then Sentry
    (let [ice {:subtypes ["Sentry" "Barrier"]}]
      (is (= "Barrier" (tactics/ice-primary-type ice))))))

(deftest test-ice-primary-type-no-primary
  (testing "ICE with no primary type returns nil"
    (let [ice {:subtypes ["Trap" "AP"]}]
      (is (nil? (tactics/ice-primary-type ice))))))

(deftest test-ice-primary-type-empty
  (testing "ICE with no subtypes returns nil"
    (let [ice {:subtypes []}]
      (is (nil? (tactics/ice-primary-type ice))))))

;; ============================================================================
;; breaker-ice-type tests (now in ai-run-tactics)
;; ============================================================================

(deftest test-breaker-ice-type-fracter
  (testing "Fracter breaks Barrier"
    (let [breaker {:subtypes ["Icebreaker" "Fracter"]}]
      (is (= "Barrier" (tactics/breaker-ice-type breaker))))))

(deftest test-breaker-ice-type-decoder
  (testing "Decoder breaks Code Gate"
    (let [breaker {:subtypes ["Icebreaker" "Decoder"]}]
      (is (= "Code Gate" (tactics/breaker-ice-type breaker))))))

(deftest test-breaker-ice-type-killer
  (testing "Killer breaks Sentry"
    (let [breaker {:subtypes ["Icebreaker" "Killer"]}]
      (is (= "Sentry" (tactics/breaker-ice-type breaker))))))

(deftest test-breaker-ice-type-ai
  (testing "AI breaker returns :ai (can break any type)"
    (let [breaker {:subtypes ["Icebreaker" "AI"]}]
      (is (= :ai (tactics/breaker-ice-type breaker))))))

(deftest test-breaker-ice-type-no-type
  (testing "Non-breaker program returns nil"
    (let [program {:subtypes ["Virus"]}]
      (is (nil? (tactics/breaker-ice-type program))))))

;; ============================================================================
;; breaker-matches-ice? tests (now in ai-run-tactics)
;; ============================================================================

(deftest test-breaker-matches-ice-fracter-barrier
  (testing "Fracter matches Barrier"
    (let [breaker {:subtypes ["Fracter"]}
          ice {:subtypes ["Barrier"]}]
      (is (tactics/breaker-matches-ice? breaker ice)))))

(deftest test-breaker-matches-ice-decoder-code-gate
  (testing "Decoder matches Code Gate"
    (let [breaker {:subtypes ["Decoder"]}
          ice {:subtypes ["Code Gate"]}]
      (is (tactics/breaker-matches-ice? breaker ice)))))

(deftest test-breaker-matches-ice-killer-sentry
  (testing "Killer matches Sentry"
    (let [breaker {:subtypes ["Killer"]}
          ice {:subtypes ["Sentry"]}]
      (is (tactics/breaker-matches-ice? breaker ice)))))

(deftest test-breaker-matches-ice-ai-any
  (testing "AI breaker matches any ICE type"
    (let [ai-breaker {:subtypes ["AI"]}
          barrier {:subtypes ["Barrier"]}
          code-gate {:subtypes ["Code Gate"]}
          sentry {:subtypes ["Sentry"]}]
      (is (tactics/breaker-matches-ice? ai-breaker barrier))
      (is (tactics/breaker-matches-ice? ai-breaker code-gate))
      (is (tactics/breaker-matches-ice? ai-breaker sentry)))))

(deftest test-breaker-matches-ice-mismatch
  (testing "Mismatched breaker/ICE returns false"
    (let [fracter {:subtypes ["Fracter"]}
          sentry {:subtypes ["Sentry"]}]
      (is (not (tactics/breaker-matches-ice? fracter sentry))))))

;; ============================================================================
;; ping-message? tests (ai-core)
;; ============================================================================

;; Access private function for testing
(def ping-message? #'core/ping-message?)

(deftest test-ping-message-exact-match
  (testing "exact 'ping' message wakes the sleeper"
    (is (ping-message? {:text "TestPlayer: ping"}))
    (is (ping-message? {:text "AI_Corp: ping"}))))

(deftest test-ping-message-case-insensitive
  (testing "ping is case-insensitive"
    (is (ping-message? {:text "Player: PING"}))
    (is (ping-message? {:text "Player: Ping"}))
    (is (ping-message? {:text "Player: pInG"}))))

(deftest test-ping-message-with-whitespace
  (testing "ping with surrounding whitespace still matches"
    (is (ping-message? {:text "Player:  ping "}))
    (is (ping-message? {:text "Player: ping  "}))))

(deftest test-ping-message-not-chat
  (testing "non-chat messages don't trigger ping (no colon format)"
    (is (not (ping-message? {:text "ping"})))))

(deftest test-ping-message-chit-chat
  (testing "normal chat doesn't wake the sleeper"
    (is (not (ping-message? {:text "Player: hello"})))
    (is (not (ping-message? {:text "Player: I'm thinking about my move"})))
    (is (not (ping-message? {:text "Player: nice play!"})))))

(deftest test-ping-message-contains-ping
  (testing "messages CONTAINING 'ping' now wake (loosened per michael-nr [162])"
    ;; A goldfish human types English around the word rather than a bare "ping".
    (is (ping-message? {:text "Player: ping pong"}))
    (is (ping-message? {:text "Player: I'll ping you later"}))
    (is (ping-message? {:text "Player: pinging..."}))
    (is (ping-message? {:text "Player: ping your turn"}))
    (is (ping-message? {:text "Player: PING!"}))))

(deftest test-ping-message-username-not-body
  (testing "'ping' in the username alone does not wake — only the body counts"
    (is (not (ping-message? {:text "Pingu: your turn"})))
    (is (not (ping-message? {:text "PingBot: hello"})))))

(deftest test-ping-message-nil-entry
  (testing "nil or missing text handled gracefully"
    (is (not (ping-message? nil)))
    (is (not (ping-message? {})))
    (is (not (ping-message? {:text nil})))))

;; ============================================================================
;; relevance-reason tests (ai-core)
;; ============================================================================

(def relevance-reason #'core/relevance-reason)

(defn- state-with
  "Build a minimal client-state shape for relevance testing."
  [{:keys [run? prompt? side active-player my-clicks end-turn turn run-phase]
    :or   {side "runner" active-player "runner" my-clicks 0 turn 1}}]
  (let [my-key (keyword side)]
    {:game-state (cond-> {:active-player active-player
                          :turn          turn
                          my-key         {:click my-clicks}}
                   end-turn  (assoc :end-turn true)
                   run?      (assoc :run (cond-> {:server [:hq] :position 0}
                                           run-phase (assoc :phase run-phase)))
                   prompt?   (assoc-in [my-key :prompt-state]
                               {:prompt-type "select"
                                :choices [{:value "A"} {:value "B"}]}))}))

(deftest test-relevance-run-started
  (testing "transition from no-run to run-active wakes with :run-started"
    (let [state (state-with {:run? true :side "corp"})]
      (is (= :run-started (relevance-reason state "corp" false))))))

(deftest test-relevance-run-ended
  (testing "transition from run-active to no-run wakes with :run-ended"
    (let [state (state-with {:run? false :side "corp"})]
      (is (= :run-ended (relevance-reason state "corp" true))))))

(deftest test-relevance-has-prompt-during-run
  (testing "an actionable prompt during a run wakes with :has-prompt"
    (let [state (state-with {:run? true :prompt? true :side "corp"})]
      ;; initial-run-active? true (run was already in progress) and we have
      ;; a prompt to act on — should wake with :has-prompt, not :run-active.
      (is (= :has-prompt (relevance-reason state "corp" true))))))

(deftest test-relevance-has-prompt-no-run
  (testing "an actionable prompt outside a run wakes with :has-prompt"
    (let [state (state-with {:prompt? true :side "runner"})]
      (is (= :has-prompt (relevance-reason state "runner" false))))))

(deftest test-relevance-my-turn
  (testing "my turn with clicks remaining wakes with :my-turn"
    (let [state (state-with {:side "runner" :active-player "runner" :my-clicks 3})]
      (is (= :my-turn (relevance-reason state "runner" false))))))

(deftest test-relevance-opponent-ended-turn
  (testing "opponent has called end-turn; I'm up but at 0 clicks — :my-turn-start"
    ;; Turn boundary, not a live actionable turn: the seat must call start-turn
    ;; first. Distinct reason so it doesn't read as a stall or mislead the seat.
    (let [state (state-with {:side "runner" :active-player "corp"
                             :my-clicks 0 :end-turn true})]
      (is (= :my-turn-start (relevance-reason state "runner" false))))))

(deftest test-relevance-no-reason-when-nothing-changed
  (testing "no run transition, no prompt, not my turn → nil (don't wake)"
    (let [state (state-with {:side "corp" :active-player "runner"
                             :my-clicks 0 :turn 3})]
      (is (nil? (relevance-reason state "corp" false))))))

(deftest test-relevance-active-run-without-prompt-does-not-wake
  ;; REGRESSION: prior to fix, an active run with NO prompt for us still woke
  ;; with :run-active on every poll, making wait-for-relevant-diff useless
  ;; during opponent runs. Both Opus agents in run #2 had to fall back to raw
  ;; `sleep` because of this. Now the wait should sit silently until something
  ;; actionable happens (prompt, run-ended, my-turn).
  (testing "run already in progress, no prompt for us → nil (don't wake)"
    (let [state (state-with {:run? true :side "corp"
                             :active-player "runner" :my-clicks 0})]
      (is (nil? (relevance-reason state "corp" true))
          "active run without actionable prompt should not wake"))))

(deftest test-relevance-run-phase-change
  ;; REGRESSION: in run #3, Corp's `wait --since N 120` timed out at 120s
  ;; despite Runner finishing the break action on Palisade. Subs were broken
  ;; but the run kept going (engine waiting for Corp paid-ability ack), so
  ;; :run-ended didn't fire, :has-prompt didn't fire (no actionable prompt
  ;; for Corp yet), and Corp's wait stayed asleep through the whole encounter
  ;; resolution. The wake reason we want is :run-phase-change.
  (testing "phase transition during an active run wakes with :run-phase-change"
    (let [state (state-with {:run? true :side "corp" :run-phase "movement"
                             :active-player "runner" :my-clicks 0})]
      (is (= :run-phase-change
             (relevance-reason state "corp" true "encounter-ice"))
          "phase change while run still active should wake"))))

(deftest test-relevance-same-phase-does-not-wake
  (testing "same phase across polls → nil (don't wake)"
    (let [state (state-with {:run? true :side "corp" :run-phase "encounter-ice"
                             :active-player "runner" :my-clicks 0})]
      (is (nil? (relevance-reason state "corp" true "encounter-ice"))
          "same phase should not wake"))))

(deftest test-relevance-phase-change-skipped-when-no-baseline
  ;; The 3-arg form (no initial-phase) should not fire phase-change wake —
  ;; it has no baseline to compare against. This preserves the old test
  ;; surface for callers that only track run-active.
  (testing "3-arg form (no initial-phase) skips phase-change check"
    (let [state (state-with {:run? true :side "corp" :run-phase "movement"
                             :active-player "runner" :my-clicks 0})]
      (is (nil? (relevance-reason state "corp" true))
          "3-arg form with no phase baseline should not wake on phase"))))

(deftest test-relevance-prompt-still-priority-over-phase-change
  (testing "actionable prompt takes priority over phase-change (more specific signal)"
    (let [state (state-with {:run? true :prompt? true :side "corp"
                             :run-phase "approach-server"})]
      (is (= :has-prompt
             (relevance-reason state "corp" true "encounter-ice"))))))

;; ============================================================================
;; my-turn-to-act? tests (ai-core)
;;
;; This predicate is the authoritative "is it my turn yet" check used by
;; relevance-reason (the wake decision for the `wait` command). Prior to
;; 2026-05-23, a now-removed duplicate predicate in wait-for-my-turn fired
;; spuriously when BOTH players were at 0 clicks (which happens every time
;; the Runner spends their last click on a run — Runner is still resolving
;; the run, but the duplicate said "ready to start turn"). Both Opus agents
;; in run #2 reported this on every transition. wait-for-my-turn has since
;; been removed in favor of the unified `wait` command.
;; ============================================================================

(deftest test-my-turn-to-act-my-turn-with-clicks
  (testing "I am active player and have clicks → true"
    (let [state (state-with {:side "corp" :active-player "corp" :my-clicks 2})]
      (is (boolean (core/my-turn-to-act? state "corp"))))))

(deftest test-my-turn-to-act-end-turn-flag-set
  (testing "opponent set :end-turn flag, active-player still them → true (my turn next)"
    (let [state (state-with {:side "runner" :active-player "corp"
                             :my-clicks 0 :end-turn true})]
      (is (boolean (core/my-turn-to-act? state "runner"))))))

(deftest test-my-turn-to-act-corp-post-mulligan
  (testing "turn 0, Corp side, 0 clicks → true (Corp goes first)"
    (let [state (state-with {:side "corp" :active-player "corp"
                             :my-clicks 0 :turn 0})]
      (is (boolean (core/my-turn-to-act? state "corp"))))))

(deftest test-my-turn-to-act-opponents-turn
  (testing "opponent is active, has clicks, no end-turn → false"
    (let [state (state-with {:side "corp" :active-player "runner"
                             :my-clicks 0 :turn 3})]
      (is (false? (boolean (core/my-turn-to-act? state "corp")))))))

(deftest test-my-turn-to-act-opponent-mid-run-zero-clicks
  ;; REGRESSION: prior to fix, wait-for-my-turn's duplicate predicate fired
  ;; "Ready to start turn!" here because both players were at 0 clicks.
  ;; my-turn-to-act? (the correct predicate) returns false because no
  ;; :end-turn flag is set — Runner is still mid-run.
  (testing "opponent mid-run with 0 clicks, no end-turn → false (run still resolving)"
    (let [state (state-with {:run? true :side "corp"
                             :active-player "runner" :my-clicks 0})]
      (is (false? (boolean (core/my-turn-to-act? state "corp")))
          "must not wake while opponent run is mid-resolution"))))

(deftest test-my-turn-to-act-my-turn-but-zero-clicks
  (testing "I am active but spent all clicks, no end-turn yet → false"
    ;; This is a brief window before auto-end-turn fires. We shouldn't say
    ;; "ready" here — the engine hasn't transitioned yet.
    (let [state (state-with {:side "corp" :active-player "corp"
                             :my-clicks 0 :turn 3})]
      (is (false? (boolean (core/my-turn-to-act? state "corp")))))))

;; ============================================================================
;; find-card-by-cid (ai_core.clj)
;; ============================================================================
;; REGRESSION: prior enumeration-based version (commits before this) walked
;; specific zones — Corp servers ICE/content, Runner rig, hands, play-areas,
;; discard piles — and missed hosted cards, identity slots, scored agendas,
;; currents, RFG, set-aside, and source-card refs on :run. The Runner discard
;; prompt fell back to printing "CID: <uuid>" for any selectable the lookup
;; missed. New version walks the full state tree so all zones are reachable.

(deftest test-find-card-by-cid-finds-hosted-card
  (testing "hosted card on an ICE is reachable"
    (let [host-ice {:cid "ice-1" :title "Ice Wall" :type "ICE"
                    :hosted [{:cid "parasite-1" :title "Parasite" :type "Program"}]}
          gs {:corp {:servers {:hq {:ices [host-ice]}}}}]
      (with-mock-state (mock-client-state :game-state gs)
        (is (= "Parasite" (:title (core/find-card-by-cid "parasite-1"))))))))

(deftest test-find-card-by-cid-finds-identity
  (testing "identity slot is reachable"
    (let [gs {:runner {:identity {:cid "id-1" :title "Az McCaffrey" :type "Identity"}}
              :corp {:servers {}}}]
      (with-mock-state (mock-client-state :game-state gs)
        (is (= "Az McCaffrey" (:title (core/find-card-by-cid "id-1"))))))))

(deftest test-find-card-by-cid-finds-scored-agenda
  (testing "scored agenda zone is reachable"
    (let [gs {:corp {:scored [{:cid "agenda-1" :title "Project Atlas" :type "Agenda"}]
                     :servers {}}}]
      (with-mock-state (mock-client-state :game-state gs)
        (is (= "Project Atlas" (:title (core/find-card-by-cid "agenda-1"))))))))

(deftest test-find-card-by-cid-backcompat-hand
  (testing "cards in hand still resolve (backwards compat with prior impl)"
    (let [gs {:corp {:hand [{:cid "hand-1" :title "Hedge Fund" :type "Operation"}]
                     :servers {}}}]
      (with-mock-state (mock-client-state :game-state gs)
        (is (= "Hedge Fund" (:title (core/find-card-by-cid "hand-1"))))))))

(deftest test-find-card-by-cid-backcompat-discard
  (testing "cards in discard still resolve"
    (let [gs {:runner {:discard [{:cid "trash-1" :title "Diesel" :type "Event"}]}
              :corp {:servers {}}}]
      (with-mock-state (mock-client-state :game-state gs)
        (is (= "Diesel" (:title (core/find-card-by-cid "trash-1"))))))))

(deftest test-find-card-by-cid-returns-nil-for-unknown
  (testing "unknown CID returns nil"
    (let [gs {:corp {:hand [{:cid "hand-1" :title "Hedge Fund"}] :servers {}}}]
      (with-mock-state (mock-client-state :game-state gs)
        (is (nil? (core/find-card-by-cid "nonexistent")))))))

(deftest test-find-card-by-cid-skips-titleless-cid-maps
  (testing "non-card maps with matching :cid but no :title are skipped (effects, log refs, etc)"
    (let [gs {:corp {:hand [] :servers {}}
              :effects [{:cid "fake-effect" :type :rez-cost :value 3}]}]
      (with-mock-state (mock-client-state :game-state gs)
        (is (nil? (core/find-card-by-cid "fake-effect"))
            "non-card map with :cid but no :title must not be returned as a card")))))

;; ============================================================================
;; normalize-server-name tests
;; ============================================================================

(deftest test-normalize-server-centrals
  (testing "central server variants normalize to canonical names"
    (is (= "HQ" (:normalized (core/normalize-server-name "hq"))))
    (is (= "HQ" (:normalized (core/normalize-server-name "HQ"))))
    (is (= "R&D" (:normalized (core/normalize-server-name "rd"))))
    (is (= "R&D" (:normalized (core/normalize-server-name "r&d"))))
    (is (= "Archives" (:normalized (core/normalize-server-name "archives"))))))

(deftest test-normalize-server-remotes
  (testing "remote variants normalize to 'Server N'"
    (is (= "Server 1" (:normalized (core/normalize-server-name "remote1"))))
    (is (= "Server 1" (:normalized (core/normalize-server-name "remote 1"))))
    (is (= "Server 2" (:normalized (core/normalize-server-name "server 2"))))))

(deftest test-normalize-server-new
  (testing "the various 'new remote' spellings all create a new remote"
    (is (= "New remote" (:normalized (core/normalize-server-name "new"))))
    (is (= "New remote" (:normalized (core/normalize-server-name "remotenew"))))
    (is (= "New remote" (:normalized (core/normalize-server-name "server new"))))))

(deftest test-normalize-server-new-remote-phrasing
  (testing "the game's own UI label 'New remote' (and 'new server') is accepted"
    ;; Regression: a seat naturally types the label the engine shows ("New remote").
    ;; Previously re-matches on the new-pattern required a full-string match, so
    ;; the trailing ' remote' fell through to a literal nonexistent server name.
    (is (= "New remote" (:normalized (core/normalize-server-name "new remote"))))
    (is (= "New remote" (:normalized (core/normalize-server-name "New remote"))))
    (is (= "New remote" (:normalized (core/normalize-server-name "new server"))))
    (is (= "New remote" (:normalized (core/normalize-server-name "new  remote"))))))

(deftest test-normalize-server-passthrough
  (testing "unrecognized names pass through unchanged (no false 'new' match)"
    (is (= "Server 1" (:normalized (core/normalize-server-name "Server 1"))))
    (is (= "Newfoundland" (:normalized (core/normalize-server-name "Newfoundland"))))))

;; ============================================================================
;; format-choice tests
;; ============================================================================
;; Regression: card-valued choices (e.g. Mutual Favor's "Choose an Icebreaker")
;; were rendered as raw EDN maps ("{:cid \"...\", :title \"Carmen\", ...}") at the
;; inline post-play prompt display and the "✅ Chose:" confirmation, because those
;; sites printed (:value choice) directly instead of going through format-choice.
;; These lock the human/agent-readable contract both sites now depend on.

(deftest test-format-choice-card-map-in-value
  (testing "a choice whose :value is a card map renders the card name, not raw EDN"
    (let [choice {:uuid "u1"
                  :value {:cid "abc" :title "Carmen" :printed-title "Carmen"}}
          out (core/format-choice choice)]
      (is (not (clojure.string/includes? out ":cid"))
          "must not leak raw EDN map keys")
      (is (clojure.string/includes? out "Carmen")
          "must surface the card title"))))

(deftest test-format-choice-bare-card-map
  (testing "a bare card map (no :value wrapper) also renders the title"
    (is (clojure.string/includes?
         (core/format-choice {:cid "abc" :title "Cleaver"}) "Cleaver"))))

(deftest test-format-choice-string-value-unchanged
  (testing "plain string-valued choices (e.g. server names) still render as-is"
    (is (= "R&D" (core/format-choice {:uuid "u" :value "R&D"})))))

(deftest test-format-choice-label-choice
  (testing "label-only choices (e.g. action buttons) render the label"
    (is (= "Done" (core/format-choice {:label "Done"})))))

;; ============================================================================
;; Test Suite Main
;; ============================================================================

;; ============================================================================
;; resolve-selectable / format-selectable-card tests
;;
;; Regression for the discard-prompt phantom-CID bug: the engine's
;; discard-to-hand-size :selectable leaks hidden/opponent CIDs the seat can't
;; resolve. They must be partitioned out (as :phantom) with their ORIGINAL
;; indices preserved on the pickable cards, since choose-card uses (nth ...).
;; String CIDs not present in game state resolve to nil -> phantom; card maps
;; with :title resolve directly without needing live game state.
;; ============================================================================

(deftest test-resolve-selectable-all-cards
  (testing "all card maps resolve as pickable with their indices"
    (let [sel [{:cid "a" :title "Unity" :type "Program" :zone ["hand"]}
               {:cid "b" :title "Cleaver" :type "Program" :zone ["hand"]}]
          {:keys [pickable phantom]} (core/resolve-selectable sel)]
      (is (empty? phantom))
      (is (= [0 1] (map :idx pickable)))
      (is (= ["Unity" "Cleaver"] (map (comp :title :card) pickable))))))

(deftest test-resolve-selectable-phantom-cids
  (testing "string CIDs absent from game state are phantom; card maps stay pickable with TRUE indices"
    (let [sel ["phantom-cid-1" "phantom-cid-2"
               {:cid "c" :title "Tread Lightly" :type "Event" :zone ["hand"]}
               {:cid "d" :title "Carmen" :type "Program" :zone ["hand"]}]
          {:keys [pickable phantom]} (core/resolve-selectable sel)]
      ;; phantom keeps original positions 0,1 (the leaked hidden CIDs)
      (is (= [0 1] phantom))
      ;; pickable keeps original positions 2,3 — NOT renumbered to 0,1,
      ;; because choose-card resolves by (nth selectable index)
      (is (= [2 3] (map :idx pickable)))
      (is (= ["Tread Lightly" "Carmen"] (map (comp :title :card) pickable))))))

(deftest test-resolve-selectable-empty
  (testing "empty selectable yields empty parts"
    (let [{:keys [pickable phantom]} (core/resolve-selectable [])]
      (is (empty? pickable))
      (is (empty? phantom)))))

(deftest test-format-selectable-card
  (testing "formats title, type, zone, and rez state"
    (is (= "Unity [Program] (in hand)"
           (core/format-selectable-card {:title "Unity" :type "Program" :zone ["hand"]})))
    (is (= "Ice Wall [ICE] (in servers/hq/ices) (unrezzed)"
           (core/format-selectable-card {:title "Ice Wall" :type "ICE"
                                         :zone ["servers" "hq" "ices"] :rezzed false})))
    (is (= "Eli 1.0 [ICE] (in servers/rd/ices) (rezzed)"
           (core/format-selectable-card {:title "Eli 1.0" :type "ICE"
                                         :zone ["servers" "rd" "ices"] :rezzed true}))))
  (testing "a title-less face-down card reads as 'face-down card' with its zone (#70)"
    (is (= "face-down card (in servers/remote1/content)"
           (core/format-selectable-card {:type "Card"
                                         :zone ["servers" "remote1" "content"]})))))

;; ============================================================================
;; find-selectable-card-by-cid / face-down access at a remote breach (issue #70)
;;
;; BLOCKER regression: at a multi-card remote breach the engine sends :selectable
;; as CID strings for FACE-DOWN Corp cards. In the Runner's view those cards carry
;; :cid/:zone/:side/:type but NO :title. find-card-by-cid filters on :title, so it
;; returned nil, choose-card rejected the pick as "hidden/opponent (not in your
;; view)", and the breach — the exact game-winning access — could not be resolved
;; except via the eval escape hatch. A title-less card genuinely present in the
;; seat's game state (it lives in a :zone) must resolve; non-card junk that merely
;; carries a :cid (effects-registry entries, log refs — no :zone) must not.
;; ============================================================================

(defn- facedown-breach-state
  "Game state with a face-down Corp card (no :title) in a remote server root,
   as the Runner sees it at a breach."
  [cid]
  {:corp {:servers {:remote1 {:content [{:cid cid
                                         :zone ["servers" "remote1" "content"]
                                         :side "Corp" :type "Card"}]}}}
   :runner {}
   :active-player "runner"})

(deftest test-resolve-selectable-facedown-cid-pickable
  (testing "a face-down CID at a breach is PICKABLE, not phantom (issue #70)"
    (with-mock-state (mock-client-state :game-state (facedown-breach-state "fd-9"))
      (let [{:keys [pickable phantom]} (core/resolve-selectable ["fd-9"])]
        (is (empty? phantom)
            "the face-down card must NOT be treated as a phantom/hidden CID")
        (is (= [0] (map :idx pickable)))
        (is (= "fd-9" (:cid (:card (first pickable)))))))))

(deftest test-find-selectable-card-by-cid
  (testing "a title-less face-down card present in state resolves by cid"
    (with-mock-state (mock-client-state :game-state (facedown-breach-state "fd-1"))
      (let [card (core/find-selectable-card-by-cid "fd-1")]
        (is (some? card) "face-down card must resolve (was nil under the :title filter)")
        (is (= "fd-1" (:cid card)))
        (is (nil? (:title card))))))
  (testing "find-card-by-cid (title-gated) is unchanged — still nil for the same card"
    (with-mock-state (mock-client-state :game-state (facedown-breach-state "fd-1"))
      (is (nil? (core/find-card-by-cid "fd-1")))))
  (testing "a named card still resolves and is preferred over any junk sharing its cid"
    (with-mock-state (mock-client-state
                      :game-state {:runner {:rig {:program [{:cid "p-1" :title "Corroder"
                                                             :zone ["rig" "program"]}]}}})
      (is (= "Corroder" (:title (core/find-selectable-card-by-cid "p-1"))))))
  (testing "non-card junk sharing a :cid (no :title, no :zone) does not resolve"
    (with-mock-state (mock-client-state
                      :game-state {:effects [{:cid "fx-1" :duration :end-of-turn}]})
      (is (nil? (core/find-selectable-card-by-cid "fx-1"))
          "effects-registry entries carry :cid but no :zone — must stay unresolved"))))

;; ============================================================================
;; new-prompt? / classify-ability-result (ai-core) -- eid-aware prompt detection
;; Regression: use-ability! reported "Ability failed (timeout)" for abilities
;; that opened a prompt (e.g. Red Team's "Choose a server"), because prompt-state
;; isn't cleared on resolve so the stale leftover compared structurally-equal to
;; the freshly re-opened same-shaped prompt.
;; ============================================================================

(deftest test-new-prompt?
  (testing "no current prompt -> not new"
    (is (false? (core/new-prompt? nil nil)))
    (is (false? (core/new-prompt? {:eid {:eid 1} :msg "x"} nil))))

  (testing "first prompt after a nil baseline -> new"
    (is (true? (core/new-prompt? nil {:eid {:eid 1} :msg "Choose a server"}))))

  (testing "same eid (genuinely the same ongoing decision) -> not new"
    (let [p {:eid {:eid 10371} :msg "Choose a server"}]
      (is (false? (core/new-prompt? p p)))))

  (testing "THE BUG: stale leftover byte-identical to a freshly re-opened prompt"
    ;; Without eid-awareness these compare structurally equal and the real
    ;; prompt is missed. Different :eid is the reliable new-decision signal.
    (let [stale   {:eid {:eid 100} :msg "Choose a server"
                   :choices [{:value "HQ"} {:value "R&D"}]}
          re-open {:eid {:eid 200} :msg "Choose a server"
                   :choices [{:value "HQ"} {:value "R&D"}]}]
      (is (true? (core/new-prompt? stale re-open)))))

  (testing "eid-less prompts fall back to structural inequality"
    (is (true? (core/new-prompt? {:msg "old"} {:msg "new"})))
    (is (false? (core/new-prompt? {:msg "same"} {:msg "same"})))))

(deftest test-classify-ability-result
  (testing "card name in NEW log entries -> success"
    (is (= {:status :success :card-name "Red Team"}
           (core/classify-ability-result "Red Team"
             {:initial-prompt nil :current-prompt nil
              :initial-size 1
              :current-log [{:text "old"} {:text "ai-runner uses Red Team to make a run"}]}))))

  (testing "log entry only counted when AFTER initial-size (no stale match)"
    (is (nil? (core/classify-ability-result "Red Team"
                {:initial-prompt nil :current-prompt nil
                 :initial-size 1
                 :current-log [{:text "ai-runner uses Red Team to make a run"}]}))))

  (testing "new prompt (re-opened same-shaped) -> waiting-input"
    (let [stale   {:eid {:eid 100} :msg "Choose a server"}
          re-open {:eid {:eid 200} :msg "Choose a server"}
          r (core/classify-ability-result "Red Team"
              {:initial-prompt stale :current-prompt re-open
               :initial-size 5 :current-log [{:text "a"} {:text "b"} {:text "c"}
                                             {:text "d"} {:text "e"}]})]
      (is (= :waiting-input (:status r)))
      (is (= re-open (:prompt r)))))

  (testing "log grew without card name -> success"
    (is (= {:status :success :card-name "Mystery"}
           (core/classify-ability-result "Mystery"
             {:initial-prompt nil :current-prompt nil
              :initial-size 1 :current-log [{:text "a"} {:text "b"}]}))))

  (testing "nothing changed -> nil (keep polling / eventual timeout)"
    (let [p {:eid {:eid 7} :msg "stale"}]
      (is (nil? (core/classify-ability-result "Red Team"
                  {:initial-prompt p :current-prompt p
                   :initial-size 2 :current-log [{:text "a"} {:text "b"}]}))))))

;; ============================================================================
;; classify-action-result / verify-action-in-log prompt baseline (issue #105)
;;
;; install! reported "❌ Failed to install: Karunā / Action not confirmed in
;; game log (timeout)" while printing, in the same breath, the very prompt the
;; install had just successfully opened. Reproduced 3 times in 4 installs on a
;; fresh game -- a RACE, which is worse than a hard bug because the same
;; command teaches a seat two different lessons about what its output means.
;;
;; Cause: the caller captured initial-LOG-SIZE before sending, but the PROMPT
;; baseline was read inside verify-action-in-log, i.e. AFTER the send. When the
;; WebSocket reply beat the start of verification, the freshly-opened prompt WAS
;; the baseline, compared equal to itself forever, and the "new prompt created"
;; branch could never fire -> poll to timeout -> :error.
;;
;; This is the same defect, one function over, as #97 (verify-ABILITY-in-log):
;; the meaningful-nil `or`-fallback trap. verify-action-in-log never got the fix.
;; ============================================================================

(defn- install-prompt
  "The location prompt a Corp install opens (ICE/asset/upgrade = the common case)."
  [eid card-name]
  {:eid {:eid eid}
   :msg (str "Choose a location to install " card-name)
   :prompt-type "select"
   :choices [{:value "HQ"} {:value "R&D"} {:value "New remote"}]})

(deftest test-classify-action-result-install-opens-prompt
  (testing "card still in hand + a genuinely new prompt -> waiting-input, not error"
    (let [p (install-prompt 200 "Karunā")
          r (core/classify-action-result "Karunā" ["hand"]
              {:initial-prompt nil :current-prompt p
               :initial-size 3
               :current-log [{:text "a"} {:text "b"} {:text "c"}]
               :hand [{:title "Karunā" :zone ["hand"]}]})]
      (is (= :waiting-input (:status r)))
      (is (= p (:prompt r)))
      (is (= "Karunā" (:card-name r)))))

  (testing "stale leftover prompt of the SAME shape must still read as new (eid)"
    ;; Install Palisade, choose a server, install Palisade again: prompt-state
    ;; isn't cleared on resolve, so the leftover is byte-identical to the
    ;; re-opened one. Structural inequality alone misses it.
    (let [stale   (install-prompt 100 "Palisade")
          re-open (install-prompt 300 "Palisade")
          r (core/classify-action-result "Palisade" ["hand"]
              {:initial-prompt stale :current-prompt re-open
               :initial-size 3
               :current-log [{:text "a"} {:text "b"} {:text "c"}]
               :hand [{:title "Palisade" :zone ["hand"]}]})]
      (is (= :waiting-input (:status r)))
      (is (= re-open (:prompt r)))))

  (testing "card left hand and log grew -> success (unchanged)"
    (is (= {:status :success}
           (core/classify-action-result "Hedge Fund" ["hand"]
             {:initial-prompt nil :current-prompt nil
              :initial-size 3
              :current-log [{:text "a"} {:text "b"} {:text "c"}
                            {:text "ai-corp plays Hedge Fund."}]
              :hand []}))))

  (testing "waiting-input wins over the bare log-grew heuristic"
    ;; The click-spend line can land in the log while the location prompt is
    ;; still open. Reporting :success there would be a false 'installed'.
    (let [p (install-prompt 400 "Karunā")
          r (core/classify-action-result "Karunā" ["hand"]
              {:initial-prompt nil :current-prompt p
               :initial-size 3
               :current-log [{:text "a"} {:text "b"} {:text "c"}
                             {:text "ai-corp spends [Click] to install a card."}]
               :hand [{:title "Karunā" :zone ["hand"]}]})]
      (is (= :waiting-input (:status r)))))

  (testing "nothing happened yet -> nil (keep polling)"
    (is (nil? (core/classify-action-result "Karunā" ["hand"]
                {:initial-prompt nil :current-prompt nil
                 :initial-size 3
                 :current-log [{:text "a"} {:text "b"} {:text "c"}]
                 :hand [{:title "Karunā" :zone ["hand"]}]})))))

(deftest test-verify-action-in-log-prompt-arrives-before-verification
  (testing "THE #105 RACE: prompt already in state when verification starts"
    ;; Stage the losing side of the race: the WebSocket reply (prompt) has
    ;; already landed, the card is still in hand, and the log has NOT grown.
    ;; The caller captured pre-log-size AND pre-prompt (nil) before sending.
    (let [p (install-prompt 200 "Karunā")
          gs {:corp {:credit 5 :click 3
                     :hand [{:title "Karunā" :zone ["hand"] :cid 1}]
                     :prompt-state p}
              :log [{:text "a"} {:text "b"} {:text "c"}]
              :active-player "corp"}]
      (with-mock-state (mock-client-state :side "corp" :game-state gs)
        (let [r (core/verify-action-in-log "Karunā" ["hand"] 200
                  {:pre-log-size 3 :pre-prompt nil})]
          (is (= :waiting-input (:status r))
              "install that opened a prompt must report :waiting-input, never a timeout :error")
          (is (= p (:prompt r)))))))

  (testing "a nil :pre-prompt is MEANINGFUL and must not trigger a live re-read"
    ;; Same staging: if `(or pre-prompt (get-prompt))` re-reads, the baseline
    ;; becomes the new prompt itself and this false-fails as a timeout.
    (let [p (install-prompt 201 "Palisade")
          gs {:corp {:credit 5 :click 3
                     :hand [{:title "Palisade" :zone ["hand"] :cid 2}]
                     :prompt-state p}
              :log [{:text "a"}]
              :active-player "corp"}]
      (with-mock-state (mock-client-state :side "corp" :game-state gs)
        (is (= :waiting-input
               (:status (core/verify-action-in-log "Palisade" ["hand"] 200
                          {:pre-log-size 1 :pre-prompt nil})))))))

  (testing "legacy 4th-arg integer (bare initial-log-size) must not blow up"
    ;; This var is re-exported by ai-actions and reachable from `eval`, so the
    ;; old positional shape can still arrive. contains? THROWS on a
    ;; non-associative arg -- it does NOT return false the way get returns nil
    ;; -- so an un-normalized integer would be an IllegalArgumentException
    ;; rather than a graceful degrade. (Guest review, GPT-5.6.)
    (let [gs {:corp {:credit 5 :click 3
                     :hand []
                     :prompt-state nil}
              :log [{:text "a"} {:text "b"} {:text "c"}
                    {:text "ai-corp plays Hedge Fund."}]
              :active-player "corp"}]
      (with-mock-state (mock-client-state :side "corp" :game-state gs)
        (is (= :success
               (:status (core/verify-action-in-log "Hedge Fund" ["hand"] 200 3)))
            "an integer 4th arg is normalized to {:pre-log-size n}, not thrown on"))))

  (testing "genuinely nothing happened still times out as :error"
    (let [gs {:corp {:credit 5 :click 3
                     :hand [{:title "Karunā" :zone ["hand"] :cid 1}]
                     :prompt-state nil}
              :log [{:text "a"} {:text "b"} {:text "c"}]
              :active-player "corp"}]
      (with-mock-state (mock-client-state :side "corp" :game-state gs)
        (let [r (core/verify-action-in-log "Karunā" ["hand"] 100
                  {:pre-log-size 3 :pre-prompt nil})]
          (is (= :error (:status r)))
          (is (= "Karunā" (:card-name r))))))))

;; ============================================================================
;; capture-state-snapshot / show-state-diff tests (ai-core)
;;
;; The before/after verification snapshot must read PUBLIC count fields for
;; zone sizes, not (count zone-vector). On the wire, a player's OWN deck is
;; fog-of-war-hidden: it arrives as [] with the real size carried in the
;; public :deck-count field (see game.core.diffs/deck-summary). Counting the
;; empty vector reports a constant 0, so show-state-diff could never surface a
;; draw. (Same bug class as the snapshot-header :hand-count fix, forum #28.)
;; ============================================================================

(defn- corp-snapshot-gs
  "Wire-shaped game-state for the corp seat: own deck hidden as [] with the
   real size in :deck-count; own hand is visible."
  [deck-count hand-count]
  {:corp   {:credit 7 :click 2
            :hand (vec (repeat hand-count {:cid 0 :title "X"}))
            :hand-count hand-count
            :deck []                ; fog-of-war: own deck contents hidden
            :deck-count deck-count  ; public size field
            :discard [] :servers {}}
   :runner {:credit 5 :click 0 :hand [] :rig {}}
   :active-player "corp"})

(deftest test-capture-snapshot-deck-size-uses-public-count
  (testing "own deck is fog-hidden ([]) on the wire; :deck-size reads :deck-count"
    (with-mock-state (mock-client-state :side "corp"
                                        :game-state (corp-snapshot-gs 30 2))
      (let [snap (core/capture-state-snapshot)]
        (is (= 30 (:deck-size snap))
            "deck-size must reflect the public deck-count, not the empty fog-hidden vector")
        (is (= 2 (:hand-size snap))
            "hand-size for own (visible) hand")))))

(deftest test-show-state-diff-surfaces-draw
  (testing "a draw (deck 30->29, hand 2->3) shows the deck line in detailed diff"
    (let [before (with-mock-state (mock-client-state :side "corp"
                                                     :game-state (corp-snapshot-gs 30 2))
                   (core/capture-state-snapshot))
          after  (with-mock-state (mock-client-state :side "corp"
                                                     :game-state (corp-snapshot-gs 29 3))
                   (core/capture-state-snapshot))
          out    (with-out-str (core/show-state-diff before after true))]
      (is (re-find #"Deck: 30 . 29" out)
          "detailed diff must surface deck movement on a draw")
      (is (re-find #"Hand: 2 . 3" out)
          "detailed diff must surface hand movement on a draw"))))

(defn -main []
  (let [results (run-tests 'ai-pure-functions-test)]
    (println "\n========================================")
    (println "Pure Functions Test Summary")
    (println "========================================")
    (println "Tests run:" (:test results))
    (println "Assertions:" (:pass results))
    (println "Failures:" (:fail results))
    (println "Errors:" (:error results))
    (println "========================================\n")
    (when (or (pos? (:fail results)) (pos? (:error results)))
      (System/exit 1))))

(comment
  ;; Run all tests
  (run-tests 'ai-pure-functions-test)

  ;; Run specific test
  (test-normalize-side-keyword)
  )
