(ns ai-display-test
  "Regression tests for ai_display.

   Locks the machine-readable game-over-status output contract that tooling
   (dev/self-play-batch.sh) depends on. The harness previously screen-scraped
   the human status banner and broke when the banner format changed; this
   contract must stay stable."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [test-helpers :refer [mock-client-state with-mock-state]]
            [ai-display :as display]))

(deftest test-game-over-status-decided
  (testing "decided game prints GAME-OVER with lowercased winner and turn"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 18
                                   :winner :corp :reason "Agenda"
                                   :end-time "2026-01-01T00:00:00Z"
                                   :corp {:click 0} :runner {:click 0}})
      (is (= "GAME-OVER winner=corp turn=18"
             (str/trim (with-out-str (display/game-over-status))))))))

(deftest test-game-over-status-tie
  (testing "tie prints winner=tie"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 12
                                   :reason "Both decked"
                                   :end-time "2026-01-01T00:00:00Z"
                                   :corp {:click 0} :runner {:click 0}})
      (is (= "GAME-OVER winner=tie turn=12"
             (str/trim (with-out-str (display/game-over-status))))))))

(deftest test-game-over-status-in-progress
  (testing "running game prints IN-PROGRESS with turn, whose-turn, active clicks"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "runner" :turn 7
                                   :corp {:click 0} :runner {:click 3}})
      (is (= "IN-PROGRESS turn=7 whose-turn=runner clicks=3"
             (str/trim (with-out-str (display/game-over-status))))))))

(deftest test-game-over-status-awaiting-start
  ;; At a clean turn boundary (a player has ended their turn, or both sides are
  ;; at 0 clicks) the active-player wire field still names the player who just
  ;; finished. Reporting `IN-PROGRESS whose-turn=corp clicks=0` there is
  ;; indistinguishable from a corp stall, so the umpire's stall detector can
  ;; false-positive while a slow opponent is thinking about its turn start.
  ;; A distinct AWAITING-START token names who acts next so tooling can apply a
  ;; patient boundary budget instead of the tight mid-turn stall threshold.
  (testing "corp ended turn -> AWAITING-START next-player=runner"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 5
                                   :end-turn true
                                   :corp {:click 0} :runner {:click 0}})
      (is (= "AWAITING-START turn=5 next-player=runner"
             (str/trim (with-out-str (display/game-over-status)))))))

  (testing "both sides at 0 clicks (no end-turn flag yet) -> AWAITING-START"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "runner" :turn 6
                                   :corp {:click 0} :runner {:click 0}})
      (is (= "AWAITING-START turn=6 next-player=corp"
             (str/trim (with-out-str (display/game-over-status))))))))

(deftest test-game-over-status-no-game
  (testing "no game-state prints NO-GAME"
    (with-mock-state {:side "corp" :game-state nil}
      (is (= "NO-GAME"
             (str/trim (with-out-str (display/game-over-status))))))))

(deftest test-status-compact-awaiting-start
  ;; At a clean turn boundary the active-player wire field still names the player
  ;; who just finished, so the compact status line used to show the stale side
  ;; (e.g. "T5-corp ... | -"), disagreeing with game-over-status's
  ;; AWAITING-START next-player=runner. A model reading the compact line at the
  ;; boundary would mistake whose turn is starting. status-compact must flip to
  ;; the next player and flag the boundary, matching game-over-status.
  (testing "corp ended turn -> compact line names next-player runner + awaiting-start"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 5
                                   :end-turn true
                                   :corp {:click 0 :credit 5 :hand [] :agenda-point 0}
                                   :runner {:click 0 :credit 4 :hand [] :agenda-point 0}})
      (let [line (str/trim (with-out-str (display/status-compact)))]
        (is (str/starts-with? line "T5-runner")
            (str "expected boundary line to name next-player runner, got: " line))
        (is (str/includes? line "awaiting-start")
            (str "expected awaiting-start marker, got: " line))
        (is (not (str/starts-with? line "T5-corp"))
            (str "must not show stale finished side, got: " line)))))

  (testing "mid-turn (corp acting, has clicks) still shows active side, no marker"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 5
                                   :corp {:click 2 :credit 5 :hand [] :agenda-point 0}
                                   :runner {:click 0 :credit 4 :hand [] :agenda-point 0}})
      (let [line (str/trim (with-out-str (display/status-compact)))]
        (is (str/starts-with? line "T5-corp")
            (str "mid-turn should show active corp, got: " line))
        (is (not (str/includes? line "awaiting-start"))
            (str "mid-turn must not show boundary marker, got: " line))))))

(deftest test-run-server-display
  (testing "central server keys render human-readable"
    (is (= "R&D" (display/run-server-display "rd")))
    (is (= "HQ" (display/run-server-display "hq")))
    (is (= "Archives" (display/run-server-display "archives"))))
  (testing "remote keys render as Server N"
    (is (= "Server 1" (display/run-server-display "remote1"))))
  (testing "nil / unknown fall back gracefully"
    (is (= "the server" (display/run-server-display nil)))))

(deftest test-status-compact-run-target-not-raw-edn
  ;; The compact status used to print the raw :server vector -> `Run:["rd"]`,
  ;; which reads as leaked EDN rather than a server name. It must render the
  ;; human-readable target.
  (testing "active run shows Run:R&D, not Run:[\"rd\"]"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 3
                                   :run {:server ["rd"] :phase "initiation"}
                                   :runner {:click 2 :credit 5 :hand [] :agenda-point 0 :rig {}}
                                   :corp {:click 0 :credit 9 :hand [] :agenda-point 0}})
      (let [line (str/trim (with-out-str (display/status-compact)))]
        (is (str/includes? line "Run:R&D")
            (str "expected human-readable run target, got: " line))
        (is (not (str/includes? line "[\"rd\"]"))
            (str "must not leak raw EDN server vector, got: " line))))))

(deftest test-status-compact-hosted-credits
  ;; Credits hosted on rig/play-area cards (issue #21) must surface as (+N) so
  ;; the seat doesn't undercount affordability. The (+N) form avoids colliding
  ;; with the Nh hand suffix.
  (testing "runner with hosted credits shows pool(+hosted)c"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 3
                                   :runner {:click 2 :credit 0 :hand [] :agenda-point 0
                                            :rig {:resource [{:title "Red Team"
                                                              :counter {:credit 12}}]}}
                                   :corp {:click 0 :credit 9 :hand [] :agenda-point 0}})
      (let [line (str/trim (with-out-str (display/status-compact)))]
        (is (str/includes? line "0(+12)c")
            (str "expected hosted-credit annotation, got: " line)))))
  (testing "no hosted credits -> plain credit count, no parens"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 3
                                   :runner {:click 2 :credit 5 :hand [] :agenda-point 0 :rig {}}
                                   :corp {:click 0 :credit 9 :hand [] :agenda-point 0}})
      (let [line (str/trim (with-out-str (display/status-compact)))]
        (is (str/includes? line "5c/")
            (str "expected plain credit count, got: " line))
        (is (not (str/includes? line "(+"))
            (str "must not show hosted annotation when none, got: " line))))))

(deftest test-show-snapshot-bundles-read-loop
  ;; snapshot collapses the per-decision read-loop
  ;; (status-compact + prompt? + board-compact + hand + log + cursor) into one
  ;; call. The contract under test: a single show-snapshot faithfully contains
  ;; each part's output, so a seat can replace ~6 round-trips with one.
  (testing "no open prompt -> status + board + cursor present, no prompt section"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 5
                                   :corp {:click 2 :credit 5 :hand [] :agenda-point 0}
                                   :runner {:click 0 :credit 4 :hand [] :agenda-point 0}})
      (let [snap (with-out-str (display/show-snapshot 5))
            status (str/trim (with-out-str (display/show-status-compact)))
            board (str/trim (with-out-str (display/show-board-compact)))]
        (is (str/includes? snap status)
            (str "snapshot must contain the compact status line, got: " snap))
        ;; the bundling contract: each section must actually be present, so a
        ;; section can't silently drop out of the snapshot unnoticed.
        (is (and (seq board) (str/includes? snap board))
            (str "snapshot must bundle the board-compact section, got: " snap))
        (is (str/includes? snap "cursor=")
            (str "snapshot must contain a cursor= marker, got: " snap))
        ;; cursor is printed last (it's what you pass to `wait --since`)
        (is (> (str/index-of snap "cursor=") (str/index-of snap status))
            (str "cursor= must come after the status section, got: " snap))
        ;; no prompt-state in this mock -> the prompt section must be absent
        (is (not (str/includes? snap "Current Prompt:"))
            (str "no open prompt should mean no prompt section, got: " snap)))))

  (testing "open prompt -> snapshot includes the prompt section"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :prompt {:msg "Choose one" :prompt-type "choice"
                               :choices [{:value "Yes"} {:value "No"}]}
                      :game-state {:active-player "corp" :turn 5
                                   :corp {:click 2 :credit 5 :hand []
                                          :prompt-state {:msg "Choose one"
                                                         :prompt-type "choice"
                                                         :choices [{:value "Yes"} {:value "No"}]}}
                                   :runner {:click 0 :credit 4 :hand []}})
      (let [snap (with-out-str (display/show-snapshot 5))]
        (is (str/includes? snap "Choose one")
            (str "open prompt must appear in snapshot, got: " snap))
        (is (str/includes? snap "cursor=")
            (str "snapshot must still end with cursor=, got: " snap))))))

;; ============================================================================
;; list-playables — must flag that an active prompt BLOCKS the listed actions.
;; The GPT-5.5 seat read the playable list as actionable during a pending
;; mulligan wait and burned turns trying to act through it.
;; ============================================================================

(deftest test-list-playables-flags-waiting-prompt-block
  (testing "a waiting prompt marks the playable list as blocked / not actionable"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 0
                                   :corp {:click 0 :credit 5
                                          :hand [{:cid 1 :title "Hedge Fund" :type "Operation"
                                                  :cost 5 :playable true}]
                                          :prompt-state {:msg "Waiting for Runner to keep hand or mulligan"
                                                         :prompt-type "waiting"}}
                                   :runner {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/list-playables))]
        (is (str/includes? out "⛔") (str "must flag the block, got: " out))
        (is (str/includes? out "WAITING") (str "waiting prompt should say WAITING, got: " out))
        (is (str/includes? out "blocked by active prompt")
            (str "hand list should be marked blocked, got: " out))))))

(deftest test-list-playables-actionable-prompt-says-answer-first
  (testing "a choice prompt tells the seat to answer it first (not 'wait')"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 5
                                   :corp {:click 2 :credit 5
                                          :hand [{:cid 1 :title "Hedge Fund" :type "Operation"
                                                  :cost 5 :playable true}]
                                          :prompt-state {:msg "Choose one"
                                                         :prompt-type "choice"
                                                         :choices [{:value "Yes"} {:value "No"}]}}
                                   :runner {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/list-playables))]
        (is (str/includes? out "⛔"))
        (is (str/includes? out "Answer this prompt FIRST")
            (str "actionable prompt should steer to answering, got: " out))))))

(deftest test-list-playables-no-block-when-no-prompt
  (testing "with no active prompt, the playable list is not marked blocked"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 5
                                   :corp {:click 3 :credit 5
                                          :hand [{:cid 1 :title "Hedge Fund" :type "Operation"
                                                  :cost 5 :playable true}]}
                                   :runner {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/list-playables))]
        (is (not (str/includes? out "⛔")) (str "no block flag expected, got: " out))
        (is (str/includes? out "Hedge Fund"))))))

(deftest test-list-playables-run-window-corp-not-choose
  (testing "a passive run-priority prompt steers the Corp to continue/rez, NOT choose
            — regression for forum [093]: Corp told 'choose <N>' / '0 playables' during
            a run window read it as a dead end instead of a deliberate pass"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "runner" :turn 1
                                   :run {:phase "initiation" :position 1
                                         :server ["hq"] :no-action false}
                                   :corp {:click 0 :credit 7 :hand []
                                          :prompt-state {:msg "The Runner is running on HQ"
                                                         :prompt-type "run"}}
                                   :runner {:click 3 :credit 5 :hand []}})
      (let [out (with-out-str (display/list-playables))]
        (is (str/includes? out "RUN priority window")
            (str "should name the run priority window, got: " out))
        (is (not (str/includes? out "Answer this prompt FIRST"))
            (str "must NOT tell the Corp to answer a run window as a choose-prompt, got: " out))
        (is (not (str/includes? out "choose <N>"))
            (str "must NOT steer to choose during a run window, got: " out))
        (is (str/includes? out "continue")
            (str "should steer to continue (pass priority), got: " out))
        (is (str/includes? out "rez")
            (str "should surface the Corp's rez option, got: " out))))))

(deftest test-list-playables-run-window-runner-not-choose
  (testing "a passive run-priority prompt steers the Runner to continue/break, NOT choose"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 1
                                   :run {:phase "approach-server" :position 0
                                         :server ["hq"] :no-action false}
                                   :runner {:click 2 :credit 5 :hand []
                                            :prompt-state {:msg "You may use paid abilities"
                                                           :prompt-type "run"}}
                                   :corp {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/list-playables))]
        (is (str/includes? out "RUN priority window")
            (str "should name the run priority window, got: " out))
        (is (not (str/includes? out "Answer this prompt FIRST"))
            (str "must NOT tell the Runner to answer a run window as a choose-prompt, got: " out))
        (is (str/includes? out "continue")
            (str "should steer to continue (advance the run), got: " out))))))

;; ============================================================================
;; show-blocker-diagnosis — read-only "why can't I act + what next" (GPT-5.5 ask)
;; ============================================================================

(deftest test-blocker-diagnosis-waiting-mulligan
  (testing "pending-mulligan wait names the opponent as owner and steers to wait+start-turn"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 0
                                   :corp {:click 0 :credit 5 :hand []
                                          :prompt-state {:msg "Waiting for Runner to keep hand or mulligan"
                                                         :prompt-type "waiting"}}
                                   :runner {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        (is (str/includes? out "WAITING"))
        (is (str/includes? out "OPPONENT"))
        (is (str/includes? out "opening mulligan"))
        (is (str/includes? out "cursor="))))))

(deftest test-blocker-diagnosis-actionable-prompt
  (testing "an actionable prompt is flagged as ours to resolve, steering to choose"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 5
                                   :corp {:click 2 :credit 5 :hand []
                                          :prompt-state {:msg "Choose one"
                                                         :prompt-type "choice"
                                                         :choices [{:value "Yes"} {:value "No"}]}}
                                   :runner {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        (is (str/includes? out "ACTIONABLE"))
        (is (str/includes? out "choose"))))))

(deftest test-blocker-diagnosis-run-initiation-passive-prompt
  (testing "a passive (no-choices) run-priority prompt steers to continue, NOT choose
            — regression for the run-initiation diagnose-blocker/continue contradiction (backlog #4)"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 3
                                   :run {:phase "approach-ice" :position 1
                                         :server ["hq"] :no-action false}
                                   :runner {:click 2 :credit 5 :hand []
                                            ;; non-"waiting" prompt-type, but no choices/selectable:
                                            ;; the exact shape that used to be mislabeled "resolve via choose"
                                            :prompt-state {:msg "Waiting for Corp paid abilities (initiation phase)"
                                                           :prompt-type "run"}}
                                   :corp {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        (is (str/includes? out "priority") (str "should name a priority window, got: " out))
        (is (str/includes? out "continue") (str "should steer to continue, got: " out))
        (is (not (str/includes? out "ACTIONABLE"))
            (str "must NOT mislabel a passive run prompt as a choose-prompt, got: " out))))))

(deftest test-blocker-diagnosis-movement-window-uses-priority-hint
  (testing "a passive prompt in the movement phase uses the side-aware priority hint"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 4
                                   :run {:phase "movement" :position 0
                                         :server ["rd"] :no-action :corp}
                                   :runner {:click 1 :credit 5 :hand []
                                            :prompt-state {:msg "You may use paid abilities"
                                                           :prompt-type "run"}}
                                   :corp {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        ;; run-priority-hint-lines: opponent (Corp) already passed -> it's my move to breach
        (is (str/includes? out "YOUR move") (str "should use the priority hint, got: " out))
        (is (str/includes? out "breach R&D") (str "should explain what continue yields, got: " out))
        (is (not (str/includes? out "ACTIONABLE")))))))

(deftest test-blocker-diagnosis-turn-not-started
  (testing "my-turn boundary with 0 clicks steers to start-turn"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 0
                                   :corp {:click 0 :credit 5 :hand []}
                                   :runner {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        (is (str/includes? out "start-turn"))))))

(deftest test-blocker-diagnosis-can-act
  (testing "my turn with clicks and no prompt reports nothing blocking"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 5
                                   :corp {:click 3 :credit 5 :hand []}
                                   :runner {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        (is (str/includes? out "Nothing is blocking"))))))

;; ============================================================================
;; run-priority-hint-lines — side/priority-aware movement-window guidance
;; Regression for the cross-model deadlock: the symmetric "Use 'continue' to
;; pass priority" line told both seats the same thing, so each waited on the
;; other and the run stalled. The hint must name whose move it is (via the run's
;; :no-action) and tell the Runner that continuing is what yields its access.
;; ============================================================================

(deftest test-priority-hint-fresh-window-runner-naked-server
  (testing "Runner, nobody passed yet, no ICE left: continue -> breach & access"
    (let [lines (display/run-priority-hint-lines
                 {:phase "movement" :position 0 :server ["hq"] :no-action false}
                 "runner")
          out (str/join "\n" lines)]
      (is (str/includes? out "YOUR move"))
      (is (str/includes? out "breach HQ and access cards"))
      (is (str/includes? out "BOTH players must pass")))))

(deftest test-priority-hint-fresh-window-runner-more-ice
  (testing "Runner, fresh window, ICE still ahead: continue -> approach next ICE"
    (let [out (str/join "\n" (display/run-priority-hint-lines
                              {:phase "movement" :position 2 :server ["rd"] :no-action nil}
                              "runner"))]
      (is (str/includes? out "approach the next ICE on R&D"))
      (is (not (str/includes? out "access cards"))))))

(deftest test-priority-hint-i-already-passed-waits
  (testing "Side that already passed is told to wait, not re-continue"
    (let [out (str/join "\n" (display/run-priority-hint-lines
                              {:phase "movement" :position 0 :server ["hq"] :no-action "runner"}
                              "runner"))]
      (is (str/includes? out "already passed priority"))
      (is (str/includes? out "waiting for Corp"))
      (is (str/includes? out "Re-sending 'continue' does nothing")))))

(deftest test-priority-hint-opponent-passed-my-move
  (testing "When opponent already passed, it's my move to advance"
    (let [out (str/join "\n" (display/run-priority-hint-lines
                              {:phase "movement" :position 0 :server ["rd"] :no-action :corp}
                              "runner"))]
      (is (str/includes? out "YOUR move"))
      (is (str/includes? out "Corp has already passed"))
      (is (str/includes? out "breach R&D and access cards")))))

(deftest test-priority-hint-corp-side-no-access-language
  (testing "Corp seat gets whose-move guidance but no Runner 'access' phrasing"
    (let [out (str/join "\n" (display/run-priority-hint-lines
                              {:phase "movement" :position 0 :server ["hq"] :no-action false}
                              "corp"))]
      (is (str/includes? out "YOUR move"))
      (is (str/includes? out "wait for Runner"))
      (is (not (str/includes? out "access cards"))))))

;; ============================================================================
;; show-prompt-detailed with no prompt — turn-aware "what now?"
;; "No active prompt" alone misled at a turn boundary (reader concludes the game
;; isn't waiting on them when it's their turn to start). The no-prompt path now
;; appends the turn-aware next action.
;; ============================================================================

(deftest test-prompt-no-prompt-turn-not-started
  (testing "no prompt at my unstarted-turn boundary steers to start-turn"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 0
                                   :corp {:click 0 :credit 5 :hand []}
                                   :runner {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (str/includes? out "No active prompt"))
        (is (str/includes? out "start-turn"))))))

(deftest test-prompt-no-prompt-can-act
  (testing "no prompt with clicks in hand tells me it's my turn to act"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 5
                                   :corp {:click 3 :credit 5 :hand []}
                                   :runner {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (str/includes? out "your turn"))
        (is (str/includes? out "list-playables"))))))
