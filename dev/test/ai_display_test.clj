(ns ai-display-test
  "Regression tests for ai_display.

   Locks the machine-readable game-over-status output contract that tooling
   (dev/self-play-batch.sh) depends on. The harness previously screen-scraped
   the human status banner and broke when the banner format changed; this
   contract must stay stable."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [test-helpers :refer [mock-client-state with-mock-state]]
            [ai-state :as ai-state]
            [ai-display :as display]))

;; ============================================================================
;; format-runner-agenda-line — points vs cards must be unambiguous
;; ============================================================================
;; The old header `Missing: 18 (Drawn: ~0, HQ: 5, R&D: 38, Remotes: 0/0)` mixed
;; agenda *points* (Missing) with *card* counts (HQ/R&D) under one parenthetical,
;; reading as "18 agendas, 5 in HQ". The relabel must keep the unit of each
;; number legible.

(deftest format-runner-agenda-line-labels-units
  (testing "agenda points and card counts are each explicitly unit-labelled"
    (let [line (display/format-runner-agenda-line 0 18 0 5 38 0 0)]
      (is (str/includes? line "18 agenda pts")
          "the unaccounted count is marked as agenda POINTS")
      (is (str/includes? line "HQ 5 / R&D 38 cards")
          "HQ/R&D are marked as CARD counts (the haystack), not agenda counts")
      (is (str/includes? line "0 unrezzed")
          "remote breakdown spells out unrezzed vs advanced")
      (is (str/includes? line "0 advanced"))
      (is (str/includes? line "~0 agenda cards likely drawn")
          "the drawn estimate is labelled as an estimate of agenda cards")
      (is (not (str/includes? line "Missing:"))
          "the ambiguous bare 'Missing:' header is gone")))
  (testing "values land in the right slots (no arg-order regression)"
    (let [line (display/format-runner-agenda-line 4 11 3 6 30 2 1)]
      (is (str/includes? line "Agenda Points: 4 / 7"))
      (is (str/includes? line "11 agenda pts"))
      (is (str/includes? line "HQ 6 / R&D 30 cards"))
      (is (str/includes? line "Remotes 2 unrezzed / 1 advanced"))
      (is (str/includes? line "~3 agenda cards likely drawn")))))

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

;; #93: after close-lobby! the server's only announcement is a bare
;; [:lobby/state]; the cached snapshot must stop being reported as a live game.
(deftest test-game-over-status-lobby-gone
  (testing "undecided game + lobby-gone prints GAME-GONE, not IN-PROGRESS"
    (with-mock-state {:side "runner"
                      :lobby-gone? true
                      :game-state {:active-player "runner" :turn 9
                                   :corp {:click 0} :runner {:click 1}}}
      (is (= "GAME-GONE turn=9"
             (str/trim (with-out-str (display/game-over-status)))))))
  (testing "a DECIDED game still reports GAME-OVER even after lobby teardown"
    ;; Normal endings tear the lobby down too (concede / save-replay leave).
    ;; The real result must win over the teardown notice.
    (with-mock-state {:side "corp"
                      :lobby-gone? true
                      :game-state {:active-player "corp" :turn 11
                                   :winner :corp :reason "Flatline"
                                   :end-time "2026-01-01T00:00:00Z"
                                   :corp {:click 0} :runner {:click 0}}}
      (is (= "GAME-OVER winner=corp turn=11"
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

(deftest test-status-compact-opponent-hand-count-public
  ;; HQ/grip size is PUBLIC information in Netrunner. The compact header used
  ;; (count hand), but the opponent's :hand is fog-of-war hidden in the wire
  ;; state (arrives empty), so the Opp segment under-reported the opponent hand
  ;; as 0h while the full `status` correctly showed the real count. The header
  ;; must read the public :hand-count field for both seats, matching status.
  (testing "runner seat: corp hand hidden but :hand-count public -> Opp shows 5h"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 1
                                   :runner {:click 4 :credit 5 :hand [{:title "A"} {:title "B"}]
                                            :hand-count 2 :agenda-point 0 :rig {}}
                                   :corp {:click 0 :credit 5 :hand [] :hand-count 5 :agenda-point 0}})
      (let [line (str/trim (with-out-str (display/status-compact)))]
        (is (str/includes? line "Opp(C):5c/0cl/5h/0AP")
            (str "opponent hand count must come from public :hand-count, got: " line))
        (is (not (str/includes? line "Opp(C):5c/0cl/0h"))
            (str "must not under-report opp hand as 0h, got: " line)))))
  (testing "corp seat: runner grip hidden but :hand-count public -> Opp shows 5h"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 2
                                   :corp {:click 3 :credit 8 :hand [{:title "X"}]
                                          :hand-count 1 :agenda-point 0}
                                   :runner {:click 0 :credit 4 :hand [] :hand-count 5 :agenda-point 0}})
      (let [line (str/trim (with-out-str (display/status-compact)))]
        (is (str/includes? line "Opp(R):4c/0cl/5h/0AP")
            (str "opponent grip count must come from public :hand-count, got: " line))))))

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

(deftest test-blocker-diagnosis-initiation-already-passed-runner
  (testing "Runner that already passed the run-initiation both-pass window is told
            it has passed (wait / jack-out), NOT to re-send a no-op 'continue'
            — marquee g3 stall (issue #31): a Runner that passed initiation kept
            being told 'use continue', which loops back to the same wait."
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 6
                                   :run {:phase "initiation" :position 1
                                         :server ["hq"] :no-action "runner"}
                                   :runner {:click 1 :credit 5 :hand []
                                            :prompt-state {:msg "You are running on HQ"
                                                           :prompt-type "run"}}
                                   :corp {:click 0 :credit 5 :hand []}})
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        (is (str/includes? out "already passed priority")
            (str "should tell the Runner it already passed, got: " out))
        (is (str/includes? out "jack-out")
            (str "should offer jack-out as the stall recovery, got: " out))
        (is (not (str/includes? out "ACTIONABLE")))))))

(deftest test-priority-hint-runner-already-passed-suggests-jackout
  (testing "Runner already-passed hint names jack-out as recovery if the opponent
            seat isn't monitoring (issue #31: 'only jack-out cleared it')"
    (let [out (str/join "\n" (display/run-priority-hint-lines
                              {:phase "initiation" :position 1 :server ["hq"] :no-action "runner"}
                              "runner"))]
      (is (str/includes? out "already passed priority"))
      (is (str/includes? out "jack-out")))))

(deftest test-priority-hint-corp-already-passed-no-jackout
  (testing "Corp already-passed hint does NOT suggest jack-out (Corp can't jack out)"
    (let [out (str/join "\n" (display/run-priority-hint-lines
                              {:phase "movement" :position 0 :server ["hq"] :no-action "corp"}
                              "corp"))]
      (is (str/includes? out "already passed priority"))
      (is (not (str/includes? out "jack-out"))))))

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
  (testing "Runner (active player), nobody passed yet, no ICE left: continue ->
            breach & access, framed as the FIRST sub-step (forum [118]/[120])"
    (let [lines (display/run-priority-hint-lines
                 {:phase "movement" :position 0 :server ["hq"] :no-action false}
                 "runner")
          out (str/join "\n" lines)]
      (is (str/includes? out "active player goes first"))
      (is (str/includes? out "breach HQ and access cards"))
      ;; The opponent's sub-step comes AFTER the active player passes.
      (is (str/includes? out "AFTER you pass")))))

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
  (testing "Corp seat (non-active) in a fresh window is told the Runner (active
            player) passes first and to wait — no Runner 'access' phrasing, and
            (per [120]) NO 'you may rez / fire a paid ability now': the Corp acts
            in its OWN sub-step, after the Runner has passed."
    (let [out (str/join "\n" (display/run-priority-hint-lines
                              {:phase "movement" :position 0 :server ["hq"] :no-action false}
                              "corp"))]
      (is (str/includes? out "active player"))
      (is (str/includes? out "has priority first"))
      (is (str/includes? out "wait for the Runner"))
      (is (not (str/includes? out "access cards")))
      ;; [120] correction: the Corp does not rez / fire paid abilities during the
      ;; Runner's sub-step — that happens in the Corp's own sub-step afterwards.
      (is (not (re-find #"(?i)rez" out)))
      (is (not (re-find #"(?i)paid abilit" out))))))

(deftest test-priority-hint-corp-fresh-window-is-second-passer
  (testing "Corp fresh window does not claim 'it's YOUR move' — the Runner
            (active player) passes first, the Corp passes second (forum [118])"
    (let [out (str/join "\n" (display/run-priority-hint-lines
                              {:phase "approach-server" :position 0 :server ["rd"] :no-action nil}
                              "corp"))]
      (is (not (str/includes? out "YOUR move")))
      (is (str/includes? out "Runner (active player) has priority first"))
      (is (str/includes? out "Your sub-step comes next")))))

;; ============================================================================
;; run-status-headline — the top-level `Status:` line during a run. The
;; turn-level active-side ('it's the Runner's turn') misleads inside a run: the
;; Corp still owns its rez / upgrade sub-steps, so a Corp seat running `status`
;; at its own rez window used to read 'Waiting for runner to act' (Michael forum
;; [154]: surface waiting-on-X). Grounded purely in :no-action + the
;; Runner-is-active invariant, so it never contradicts run-priority-hint-lines.
;; ============================================================================

(deftest test-run-status-headline-corp-fresh-waits-on-runner
  (testing "Corp, fresh window (nobody passed): active-player Runner acts first,
            so the Corp is waiting — NOT told it's its move"
    (let [line (display/run-status-headline
                {:run {:server ["rd"] :phase "approach-ice" :position 1 :no-action false}}
                "corp")]
      (is (str/includes? line "Waiting on Runner"))
      (is (str/includes? line "active player acts first"))
      (is (not (str/includes? line "Your move"))))))

(deftest test-run-status-headline-runner-fresh-is-its-move
  (testing "Runner, fresh window: active player acts first -> it's the Runner's move"
    (let [line (display/run-status-headline
                {:run {:server ["rd"] :phase "movement" :position 0 :no-action nil}}
                "runner")]
      (is (str/includes? line "Your move"))
      (is (str/includes? line "active player")))))

(deftest test-run-status-headline-i-passed-waits-on-opponent
  (testing "Side that already passed is told it's waiting on the opponent, not that
            it's its move (mirrors run-priority-hint-lines' already-passed branch)"
    (let [line (display/run-status-headline
                {:run {:server ["hq"] :phase "movement" :position 0 :no-action "runner"}}
                "runner")]
      (is (str/includes? line "Waiting on Corp"))
      (is (str/includes? line "you've passed"))
      (is (not (str/includes? line "Your move"))))))

(deftest test-run-status-headline-opponent-passed-is-my-move
  (testing "When the opponent has already passed, it's my move"
    (let [line (display/run-status-headline
                {:run {:server ["rd"] :phase "approach-server" :position 0 :no-action :runner}}
                "corp")]
      (is (str/includes? line "Your move"))
      (is (str/includes? line "Runner has passed")))))

;; Encounter-ice regression (Codex/GPT-5.5 review of this change): during an
;; encounter the passer is recorded on the CURRENT ENCOUNTER
;; ([:encounters :no-action]), NOT [:run :no-action] — the engine resets the
;; run-level field on movement entry (runs.clj `continue :encounter-ice`). Using
;; the run-level field would tell the Corp 'Waiting on Runner' after the Runner
;; has already passed the encounter and it is the Corp's window to fire subs / end
;; the encounter.
(deftest test-run-status-headline-encounter-runner-passed-is-corp-move
  (testing "Encounter-ice, Runner passed the encounter -> it's the Corp's move,
            read from [:encounters :no-action] not the stale run-level field"
    (let [gs {:run {:server ["rd"] :phase "encounter-ice" :position 1 :no-action false}
              :encounters {:no-action "runner"}}]
      (is (str/includes? (display/run-status-headline gs "corp") "Your move")
          "Corp: Runner has passed the encounter, Corp acts")
      (let [runner-line (display/run-status-headline gs "runner")]
        (is (str/includes? runner-line "Waiting on Corp"))
        (is (str/includes? runner-line "you've passed"))))))

(deftest test-run-status-headline-encounter-fresh-runner-acts
  (testing "Encounter-ice, nobody passed yet: Runner (active) resolves the
            encounter first; the Corp waits"
    (let [gs {:run {:server ["rd"] :phase "encounter-ice" :position 1 :no-action false}
              :encounters {:no-action nil}}]
      (is (str/includes? (display/run-status-headline gs "runner") "Your move"))
      (is (str/includes? (display/run-status-headline gs "corp") "Waiting on Runner")))))

(deftest test-effective-window-passer-prefers-encounter-in-encounter-phase
  (testing "effective-window-passer reads the encounter field during encounter-ice
            and the run field otherwise"
    ;; encounter-ice: encounter field wins over the stale run field
    (is (= "runner" (display/effective-window-passer
                     {:run {:phase "encounter-ice" :no-action false}
                      :encounters {:no-action "runner"}})))
    ;; non-encounter: run field is used (encounters ignored / absent)
    (is (= "corp" (display/effective-window-passer
                   {:run {:phase "movement" :no-action :corp}})))
    (is (nil? (display/effective-window-passer
               {:run {:phase "approach-ice" :no-action false}})))))

;; ============================================================================
;; print-run-window-priority! — shared 'whose move + what continue does' block,
;; now used by BOTH `prompt` and `status` (status previously showed only the
;; ladder). Both-must-pass windows route through run-priority-hint-lines; other
;; windows get the terse decline/continue guidance (spelled out for the Corp).
;; ============================================================================

(deftest test-run-window-priority-corp-approach-ice-is-rez-window
  (testing "Corp at approach-ice: continue DECLINES (a rez), and the rez options
            are spelled out — not routed through the movement hint lines"
    (let [run {:server ["rd"] :phase "approach-ice" :position 1 :no-action false}
          out (with-out-str
                (display/print-run-window-priority!
                 {:game-state {:run run}} run "approach-ice" "corp"))]
      (is (str/includes? out "DECLINE"))
      (is (str/includes? out "ICE rez window"))
      (is (str/includes? out "--no-rez")))))

(deftest test-run-window-priority-movement-routes-through-hint-lines
  (testing "Both-must-pass movement window routes through run-priority-hint-lines
            (Runner told continuing yields its access)"
    (let [run {:server ["hq"] :phase "movement" :position 0 :no-action false}
          out (with-out-str
                (display/print-run-window-priority!
                 {:game-state {:run run}} run "movement" "runner"))]
      (is (str/includes? out "active player goes first"))
      (is (str/includes? out "breach HQ and access cards")))))

(deftest test-run-window-priority-runner-approach-ice-passes-priority
  (testing "Runner at approach-ice (not a both-pass window for the Runner) gets the
            terse pass-priority line, no Corp rez phrasing"
    (let [run {:server ["rd"] :phase "approach-ice" :position 1 :no-action false}
          out (with-out-str
                (display/print-run-window-priority!
                 {:game-state {:run run}} run "approach-ice" "runner"))]
      (is (str/includes? out "pass priority"))
      (is (not (str/includes? out "ICE rez window"))))))

;; Integration: show-status must surface the run-window read in its headline,
;; overriding the misleading turn-level line for the Corp mid-run.
(deftest test-show-status-run-headline-overrides-turn-level
  (testing "Corp seat during a run: headline reflects run-window priority, not the
            turn-level 'Waiting for runner to act'"
    (with-mock-state (mock-client-state
                      :side "Corp"
                      :game-state {:active-player "runner" :turn 5
                                   :run {:server ["rd"] :phase "approach-ice"
                                         :position 1 :no-action false}
                                   :runner {:user {:username "ai-runner"}
                                            :click 2 :credit 5 :hand [] :agenda-point 0 :rig {}}
                                   :corp {:user {:username "ai-corp"}
                                          :click 0 :credit 9 :hand [] :agenda-point 0 :servers {}}})
      (let [out (with-out-str (display/show-status))]
        (is (not (str/includes? out "Status: ⏳ Waiting for runner to act"))
            (str "run must override turn-level status headline, got:\n" out))
        (is (str/includes? out "Waiting on Runner")
            (str "expected run-window priority headline, got:\n" out))))))

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

;; ============================================================================
;; Run-phase ladder (forum [099]): make the run timing structure explicit with a
;; YOU-ARE-HERE marker so the seat sees the whole arc without reading the rules.
;; Pure function — exercised directly, no live state needed.
;; ============================================================================

(defn- ladder-str [opts]
  (str/join "\n" (display/run-phase-ladder-lines opts)))

(deftest test-ladder-marks-current-phase-once
  (testing "exactly one YOU ARE HERE marker, on the rung matching the live phase"
    (let [out (ladder-str {:phase "encounter-ice" :server-name "R&D"
                           :position 1 :ice-count 2 :ice-name "Tithe"})]
      ;; one and only one marker
      (is (= 1 (count (re-seq #"YOU ARE HERE" out)))
          (str "expected a single marker, got:\n" out))
      ;; it lands on the Encounter rung, not Approach
      (let [marked (->> (str/split-lines out)
                        (filter #(str/includes? % "YOU ARE HERE"))
                        first)]
        (is (str/includes? marked "Encounter")
            (str "marker should be on the Encounter rung, got: " marked))))))

(deftest test-ladder-shows-all-six-steps-and-server-name
  (testing "ladder renders the full 6-step arc with the human server name"
    (let [out (ladder-str {:phase "success" :server-name "HQ"
                           :position 0 :ice-count 1 :ice-name nil})]
      (doseq [step ["#1" "#2" "#3" "#4" "#5" "#6"]]
        (is (str/includes? out step) (str "missing " step " in:\n" out)))
      (is (str/includes? out "Access HQ"))
      ;; success => marker on Access
      (is (str/includes? (->> (str/split-lines out)
                              (filter #(str/includes? % "YOU ARE HERE")) first)
                         "Access")))))

(deftest test-ladder-ice-pass-order-and-name
  (testing "encounter shows pass-order index (outermost first) and rezzed ICE name"
    ;; 3 ICE on server, position 3 = outermost = '1 of 3'
    (let [outer (ladder-str {:phase "encounter-ice" :server-name "R&D"
                             :position 3 :ice-count 3 :ice-name "Ice Wall"})]
      (is (str/includes? outer "1 of 3") (str "outermost should be 1 of 3:\n" outer))
      (is (str/includes? outer "[Ice Wall]")))
    ;; position 1 = innermost = '3 of 3'
    (let [inner (ladder-str {:phase "encounter-ice" :server-name "R&D"
                             :position 1 :ice-count 3 :ice-name "Ice Wall"})]
      (is (str/includes? inner "3 of 3") (str "innermost should be 3 of 3:\n" inner)))))

(deftest test-ladder-pass-order-out-of-range-position
  (testing "position > ice-count drops the index rather than printing a bogus 'ICE 0 of N'"
    ;; Defensive: the wire is the volatile coupling; a position past the ICE count
    ;; must never render a non-positive / nonsense pass index (misleading output).
    (let [out (ladder-str {:phase "encounter-ice" :server-name "R&D"
                           :position 4 :ice-count 3 :ice-name "Ice Wall"})]
      (is (not (str/includes? out "0 of 3"))
          (str "must not print a zero index:\n" out))
      (is (not (re-find #"-\d+ of" out))
          (str "must not print a negative index:\n" out))
      ;; falls back to the bare "Encounter ICE" rung with no "N of M"
      (is (re-find #"Encounter ICE(?! \d)" out)
          (str "out-of-range position should drop the pass index entirely:\n" out)))))

(deftest test-ladder-approach-ice-hides-unrezzed-name
  (testing "approach-ice with unknown (unrezzed) ICE shows no card name"
    (let [out (ladder-str {:phase "approach-ice" :server-name "HQ"
                           :position 1 :ice-count 1 :ice-name nil})]
      (is (str/includes? out "Approach ICE 1 of 1"))
      (is (not (str/includes? out "["))
          (str "must not invent an ICE name when unrezzed:\n" out))
      (is (str/includes? (->> (str/split-lines out)
                              (filter #(str/includes? % "YOU ARE HERE")) first)
                         "Approach")))))

(deftest test-ladder-movement-vs-approach-server
  (testing "movement before innermost ICE marks Movement; past all ICE marks Approach server"
    (let [mid (ladder-str {:phase "movement" :server-name "R&D"
                           :position 1 :ice-count 2 :ice-name nil})]
      (is (str/includes? (->> (str/split-lines mid)
                              (filter #(str/includes? % "YOU ARE HERE")) first)
                         "Movement")))
    (let [past (ladder-str {:phase "movement" :server-name "R&D"
                            :position 0 :ice-count 2 :ice-name nil})]
      (is (str/includes? (->> (str/split-lines past)
                              (filter #(str/includes? % "YOU ARE HERE")) first)
                         "Approach server")))))

(deftest test-ladder-unknown-phase-returns-nil
  (testing "absent/unrecognised phase yields nil (caller falls back to bare line)"
    (is (nil? (display/run-phase-ladder-lines {:phase nil :server-name "R&D"})))
    (is (nil? (display/run-phase-ladder-lines {:phase "bogus" :server-name "R&D"})))))

;; ============================================================================
;; Board ICE encounter order (issue #39)
;; ============================================================================
;; The :ices vector is engine order: index 0 = innermost (closest to server,
;; encountered LAST), highest index = outermost (encountered FIRST). The board
;; used to list them low-index-first, so the line you read first was the ICE the
;; Runner meets last — inverting run-budget planning. The board must read in
;; Runner encounter order and label outermost/innermost.

(deftest ice-encounter-label-marks-ends
  (testing "single ICE has no ordering ambiguity"
    (is (= "" (display/ice-encounter-label 0 1))))
  (testing "outermost (highest index) is encountered first, with its 1-based run position"
    (let [lbl (display/ice-encounter-label 1 2)]
      (is (str/includes? lbl "outermost"))
      (is (str/includes? lbl "1st"))
      ;; run prompt is 1-based: outermost of 2 = position 2/2 (idx+1), NOT #1
      (is (str/includes? lbl "position 2/2")
          (str "outermost must show the run-time 1-based position 2/2: " lbl))))
  (testing "innermost (index 0) is encountered last and guards the server"
    (let [lbl (display/ice-encounter-label 0 2)]
      (is (str/includes? lbl "innermost"))
      (is (str/includes? lbl "last"))
      (is (str/includes? lbl "position 1/2")
          (str "innermost = run-time position 1/2 (idx+1): " lbl))))
  (testing "middle ICE gets its encounter ordinal and 1-based position"
    ;; 3 ICE: idx2=1st, idx1=2nd, idx0=3rd/last; idx1 run position = 2/3
    (let [lbl (display/ice-encounter-label 1 3)]
      (is (str/includes? lbl "2"))
      (is (str/includes? lbl "position 2/3")))))

(deftest show-board-lists-ice-in-encounter-order
  (testing "board renders outermost ICE first and annotates encounter order"
    (with-mock-state
      (mock-client-state
       :side "runner"
       :game-state {:active-player "runner" :turn 12
                    :corp {:servers {:remote1
                                     {:ices [{:title "Palisade" :rezzed true :subtypes [:barrier]}
                                             {:title "Karuna" :rezzed true :subtypes [:sentry]}]
                                      :content [{:title "Project Atlas" :rezzed false}]}}}
                    :runner {:rig {}}})
      (let [out (with-out-str (display/show-board))
            lines (str/split-lines out)
            karuna-idx (first (keep-indexed (fn [i l] (when (str/includes? l "Karuna") i)) lines))
            palisade-idx (first (keep-indexed (fn [i l] (when (str/includes? l "Palisade") i)) lines))]
        (is (and karuna-idx palisade-idx) "both ICE lines render")
        (is (< karuna-idx palisade-idx)
            (str "outermost (Karuna, encountered 1st) must list ABOVE innermost (Palisade):\n" out))
        ;; #idx must still equal engine position (runtime prompt shows "position N")
        (is (str/includes? (nth lines karuna-idx) "#1")
            "Karuna keeps engine index #1 (outermost = position 1)")
        (is (str/includes? (nth lines palisade-idx) "#0")
            "Palisade keeps engine index #0 (innermost = position 0)")
        (is (str/includes? (nth lines karuna-idx) "outermost"))
        (is (str/includes? (nth lines palisade-idx) "innermost"))))))

;; ============================================================================
;; Prompt with BOTH Choices and Selectable blocks — label which verb (issue #40)
;; ============================================================================
;; Mutual Favor showed a Choices: block AND a Selectable cards: block with no
;; signal which selector applied; both seats reached for the wrong verb. When
;; both blocks are present the display must say `choose <N>` belongs to Choices
;; and `choose-card <N>` belongs to Selectable.

(deftest show-prompt-detailed-disambiguates-both-blocks
  (testing "a prompt with both Choices and Selectable labels each block's verb"
    (with-mock-state
      (mock-client-state
       :side "runner"
       :game-state {:active-player "runner" :turn 5
                    :runner {:hand []
                             :prompt-state {:prompt-type "other"
                                            :eid "mf-1"
                                            :msg "Choose an Icebreaker"
                                            :choices [{:value "Unity"} {:value "Cleaver"}]
                                            :selectable [{:cid "c1" :title "Unity"}
                                                         {:cid "c2" :title "Cleaver"}]}}
                    :corp {:hand []}})
      (let [out (with-out-str (display/show-prompt-detailed))
            lines (str/split-lines out)
            choices-line (first (filter #(str/includes? % "Choices:") lines))]
        (is choices-line "renders a Choices header")
        (is (str/includes? choices-line "choose <N>")
            (str "Choices block must name the `choose <N>` verb when selectable also present:\n" out))
        (is (str/includes? out "choose-card")
            (str "Selectable block keeps its choose-card verb:\n" out))))))

(deftest show-prompt-detailed-select-both-blocks-uses-choose-value
  ;; Codex review of PR #41: on a "select"-typed prompt the :choices are
  ;; meta-buttons (e.g. Done) and `choose-option!` REJECTS `choose <N>` there,
  ;; demanding `choose-value "<label>"`. The both-blocks help must NOT tell the
  ;; player to use `choose <N>` for a select prompt's Choices block.
  (testing "select prompt with both blocks steers Choices to choose-value, not choose <N>"
    (with-mock-state
      (mock-client-state
       :side "corp"
       :game-state {:active-player "corp" :turn 5
                    :corp {:hand []
                           :prompt-state {:prompt-type "select"
                                          :eid "sel-1"
                                          :msg "Select cards to trash"
                                          :choices [{:value "Done"}]
                                          :selectable [{:cid "c1" :title "Hedge Fund"}
                                                       {:cid "c2" :title "IPO"}]}}
                    :runner {:hand []}})
      (let [out (with-out-str (display/show-prompt-detailed))
            lines (str/split-lines out)
            choices-line (first (filter #(str/includes? % "Choices:") lines))]
        (is choices-line "renders a Choices header")
        (is (str/includes? out "choose-value")
            (str "select-prompt Choices/meta block must steer to choose-value:\n" out))
        (is (not (str/includes? choices-line "choose <N>"))
            (str "must NOT advertise `choose <N>` for a select prompt's Choices block:\n" out))
        (is (str/includes? out "choose-card")
            (str "selectable cards still use choose-card:\n" out))))))

;; ============================================================================
;; Non-run paid-ability / waiting prompt — don't advertise run-only `continue`
;; (issue #38)
;; ============================================================================
;; After Wildcat Strike (a non-run paid-ability window), the prompt said
;; "Use 'continue' command to pass priority" — but `continue` is run-only and
;; errors "No active run to monitor". And "Waiting for Corp to make a decision"
;; read as a hard block though Runner actions still worked.

(deftest show-prompt-detailed-non-run-waiting-does-not-advertise-continue
  (testing "a non-run waiting prompt steers to wait, not the run-only `continue` (#38)"
    (with-mock-state
      (mock-client-state
       :side "runner"
       :game-state {:active-player "runner" :turn 6
                    :runner {:hand []
                             :prompt-state {:prompt-type "waiting"
                                            :eid "wc-1"
                                            :msg "Waiting for Corp to make a decision"
                                            :card {:title "Wildcat Strike"}}}
                    :corp {:hand []}})
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (not (str/includes? out "Use 'continue' command to pass priority"))
            (str "must NOT advertise run-only `continue` in a non-run window:\n" out))
        (is (str/includes? out "Corp")
            (str "should name the opponent we're waiting on:\n" out))
        (is (or (str/includes? out "wait") (str/includes? out "Other actions"))
            (str "should tell the player they can wait / still have actions:\n" out))))))

;; ============================================================================
;; show-card-abilities — Corp-seat card lookup (issue #69)
;;
;; Regression: `abilities` used a strict (= "Corp" side) check, but client-state
;; stores :side lowercase ("corp"). So (= "Corp" "corp") was always false, the
;; else branch searched the RUNNER rig, and every Corp card reported "Card not
;; found installed" — even installed+rezzed assets whose abilities `use-ability`
;; (which uses the normalizing core/side=) could fire fine. Must use side=.
;; ============================================================================

(deftest test-show-card-abilities-corp-seat-finds-corp-asset
  (testing "abilities resolves a rezzed Corp asset for a Corp seat (:side is lowercase)"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :servers {:remote1 {:content [{:cid "reg-1"
                                                     :title "Regolith Mining License"
                                                     :type "Asset" :rezzed true
                                                     :zone ["servers" "remote1" "content"]
                                                     :abilities [{:label "Take 3 [Credits]"
                                                                  :cost-label "[Click]"}]}]}})
      (let [out (with-out-str (display/show-card-abilities "Regolith Mining License"))]
        (is (not (str/includes? out "Card not found installed"))
            (str "a Corp seat's own installed card must resolve:\n" out))
        (is (str/includes? out "Take 3")
            (str "should list the card's ability:\n" out))))))

;; ============================================================================
;; show-prompt-if-any — collapse the "act, then ask what happened" round-trip
;; ============================================================================
;;
;; Command-log evidence: continue->prompt ran P=0.48 (n=186 in the marquee era),
;; with the same shape at score->prompt (0.40), choose-card->prompt (0.33) and
;; run->prompt (0.27). Roughly half of all `prompt` calls were a round-trip the
;; acting command could have answered itself. This runs after EVERY action, so
;; silence-when-there-is-nothing is the load-bearing part of the contract.

(defn- prompt-state [prompt]
  (mock-client-state :side "runner"
                     :game-state {:runner {:prompt-state prompt}
                                  :corp {} :log []}))

(deftest show-prompt-if-any-is-silent-with-no-prompt
  (testing "Runs after every action — a 'No active prompt' line would be noise on
            the majority of commands and would train seats to stop reading the
            tail of the output."
    (with-mock-state (prompt-state nil)
      (is (= "" (with-out-str (display/show-prompt-if-any)))))))

(deftest show-prompt-if-any-renders-a-real-prompt
  (testing "A decision must appear in the acting command's own output"
    (with-mock-state (prompt-state {:prompt-type "select"
                                    :msg "Choose a card to trash"
                                    :choices [{:value "Yes"} {:value "No"}]})
      (let [out (with-out-str (display/show-prompt-if-any))]
        (is (str/includes? out "Choose a card to trash"))
        (is (str/includes? out "Yes"))))))

(deftest show-prompt-if-any-collapses-a-waiting-prompt-to-one-line
  (testing "A passive wait carries no choices — the useful content is just
            'not your move', so it must not print the full decision block."
    (with-mock-state (prompt-state {:prompt-type "waiting"
                                    :msg "Waiting for Corp to rez"})
      (let [out (with-out-str (display/show-prompt-if-any))]
        (is (str/includes? out "Waiting for Corp to rez"))
        (is (< (count (str/split-lines (str/trim out))) 3)
            "One line, not the full prompt block")))))

;; ============================================================================
;; Encounter-ice decline guidance (#92): a Runner at an encounter it owns with
;; UNBROKEN subroutines it is not breaking cannot pass with `continue` —
;; handle-runner-encounter-ice surfaces a fire-decision, never a pass, so
;; `continue` is silently refused. The run-window surfaces (prompt display and
;; diagnose-blocker) used to tell that Runner to "use continue" anyway, a
;; contradiction that deadlocked marquee G2 for ~20 min. The real options are
;; break / tank / jack-out — never a bare `continue`.
;; ============================================================================

(defn- encounter-state
  "Runner mid-encounter with `subs` on a rezzed ICE at position 1 on R&D."
  [subs]
  (mock-client-state
   :side "runner"
   :game-state {:active-player "runner" :turn 4
                :run {:phase "encounter-ice" :position 1
                      :server ["rd"] :no-action false}
                :runner {:click 2 :credit 5 :hand []
                         :prompt-state {:msg "You are encountering Whitespace"
                                        :prompt-type "run"}}
                :corp {:click 0 :credit 5 :hand []
                       :servers {:rd {:ices [{:title "Whitespace" :rezzed true
                                              :subroutines subs}]}}}}))

(def two-unbroken
  [{:label "Make the Runner lose 3 [Credits]" :broken false :fired false}
   {:label "End the run if the Runner has 6 [Credits] or less" :broken false :fired false}])

(def all-broken
  [{:label "Make the Runner lose 3 [Credits]" :broken true :fired false}
   {:label "End the run if the Runner has 6 [Credits] or less" :broken true :fired false}])

(deftest test-encounter-decline-hint-names-tank-not-continue
  (testing "the pure decline hint names tank/jack-out with the ICE title, never a bare continue"
    (let [out (str/join "\n" (display/runner-encounter-decline-hint-lines "Whitespace" 2))]
      (is (str/includes? out "tank \"Whitespace\"")
          (str "should name 'tank \"<ice>\"' as the decline-and-pass command, got: " out))
      (is (str/includes? out "jack-out")
          (str "should offer jack-out, got: " out))
      (is (str/includes? out "2 unbroken")
          (str "should state how many subs are unbroken, got: " out))
      ;; The whole point of #92: this window must not steer to bare `continue`.
      (is (not (re-find #"(?i)use 'continue' to pass priority" out))
          (str "must NOT tell the seat to pass with continue here, got: " out)))))

(deftest test-run-window-priority-encounter-unbroken-steers-to-tank
  (testing "prompt run-window guidance at an encounter with unbroken subs names
            tank/break/jack-out, NOT the (refused) continue — the #92 wedge"
    (with-mock-state (encounter-state two-unbroken)
      (let [out (with-out-str
                  (display/print-run-window-priority!
                   @ai-state/client-state
                   (get-in @ai-state/client-state [:game-state :run])
                   "encounter-ice" "runner"))]
        (is (str/includes? out "tank \"Whitespace\"")
            (str "encounter-ice run window must steer to tank, got: " out))
        (is (not (str/includes? out "Use 'continue' to pass priority"))
            (str "must NOT tell the Runner continue passes here, got: " out))))))

(deftest test-run-window-priority-encounter-all-broken-still-continues
  (testing "once every sub is broken the Runner DOES pass with continue — the
            decline steer must not leak into the all-broken pass window"
    (with-mock-state (encounter-state all-broken)
      (let [out (with-out-str
                  (display/print-run-window-priority!
                   @ai-state/client-state
                   (get-in @ai-state/client-state [:game-state :run])
                   "encounter-ice" "runner"))]
        (is (str/includes? out "continue")
            (str "all subs broken: continue is the correct pass, got: " out))
        (is (not (str/includes? out "tank"))
            (str "no decline decision remains when all subs are broken, got: " out))))))

(deftest test-run-window-priority-movement-unchanged
  (testing "the movement window still routes to the side-aware continue hint —
            the encounter branch must not disturb the both-must-pass windows"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 4
                                   :run {:phase "movement" :position 0
                                         :server ["rd"] :no-action :corp}
                                   :runner {:click 1 :credit 5 :hand []}
                                   :corp {:click 0 :credit 5 :hand []}})
      (let [run (get-in @ai-state/client-state [:game-state :run])
            out (with-out-str
                  (display/print-run-window-priority!
                   @ai-state/client-state run "movement" "runner"))]
        (is (str/includes? out "continue"))
        (is (not (str/includes? out "tank")))))))

(deftest test-blocker-diagnosis-encounter-unbroken-steers-to-tank
  (testing "diagnose-blocker at an encounter with unbroken subs names tank, NOT
            continue — the third surface that agreed with the lie in #92"
    (with-mock-state (encounter-state two-unbroken)
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        (is (str/includes? out "tank \"Whitespace\"")
            (str "should steer to tank, got: " out))
        (is (not (re-find #"(?i)use: continue" out))
            (str "must NOT steer to continue at an unbroken-sub encounter, got: " out))
        (is (not (str/includes? out "ACTIONABLE")))))))

(deftest test-blocker-diagnosis-encounter-all-broken-steers-to-continue
  (testing "diagnose-blocker once every sub is broken DOES steer to continue —
            the decline branch must not leak into the all-broken pass window
            (symmetry with the print-run-window-priority! all-broken guard)"
    (with-mock-state (encounter-state all-broken)
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        (is (re-find #"(?i)continue" out)
            (str "all subs broken: continue is the correct pass, got: " out))
        (is (not (str/includes? out "tank"))
            (str "no decline decision remains when all subs are broken, got: " out))))))

;; ============================================================================
;; #84: a decision the OPPONENT'S card handed you must say the choice is yours
;; ============================================================================
;; Marquee G2 turn 1: the Runner played Wildcat Strike, whose mode the CORP
;; picks. The Corp saw a prompt titled with a Runner card whose every option
;; described a Runner outcome ("Runner gains 6 [Credits]") and reasonably read it
;; as mis-seated — "am I wedged?". The Runner meanwhile saw only the generic
;; "Waiting for Corp to make a decision", which cannot distinguish "opponent is
;; thinking" from "opponent is stuck on something it doesn't know it owns".
;; Both seats escalated within 90s; ~12 minutes of game time died on wording.
;; Neither seat did anything wrong — the prompt just never said who owed the move.

(deftest test-opponent-card-decision-names-the-owner
  (testing "#84: a Corp decision from a Runner card states the choice is the Corp's"
    (let [lines (display/opponent-card-decision-lines "Wildcat Strike" "runner" "corp" "runner")
          out (str/join "\n" lines)]
      (is (seq lines) "an opponent-played card must produce an ownership line")
      (is (str/includes? out "Wildcat Strike") "name the card that created the decision")
      (is (re-find #"(?i)yours" out)
          (str "must say the decision is YOURS, got: " out))
      (is (re-find #"(?i)opponent|runner played" out)
          (str "must say the opponent played it, got: " out)))))

(deftest test-own-card-decision-adds-no-ownership-noise
  (testing "#84: a decision from our OWN card needs no ownership disclaimer"
    (is (empty? (display/opponent-card-decision-lines "Hedge Fund" "corp" "corp" "corp"))
        "own-card prompts must not gain a spurious 'your opponent played this' line")))

(deftest test-unknown-card-side-adds-no-ownership-noise
  (testing "#84: an unknown card side stays silent rather than guessing wrong"
    (is (empty? (display/opponent-card-decision-lines "Mystery Card" nil "corp" "runner"))
        "never claim ownership we cannot establish")))

(deftest test-waiting-prompt-names-what-the-opponent-owes
  (testing "#84: the waiting seat is told WHICH card the opponent is deciding"
    (let [lines (display/waiting-on-opponent-lines "Corp" "Wildcat Strike")
          out (str/join "\n" lines)]
      (is (str/includes? out "Wildcat Strike")
          (str "waiting seat must learn what the opponent owes, got: " out))
      (is (str/includes? out "Corp")
          (str "and who owes it, got: " out)))))

(deftest test-waiting-prompt-without-card-stays-generic
  (testing "#84: with no card on the waiting prompt, fall back to the generic line"
    (let [lines (display/waiting-on-opponent-lines "Corp" nil)
          out (str/join "\n" lines)]
      (is (seq lines) "must still say who we're waiting on")
      (is (str/includes? out "Corp"))
      (is (not (str/includes? out "nil")) "must never render a nil card title"))))

(deftest test-prompt-detailed-surfaces-opponent-card-ownership
  (testing "#84 end-to-end: show-prompt-detailed states ownership for an opponent card"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:corp {:prompt-state
                                          {:msg "Choose one"
                                           :prompt-type "other"
                                           :card {:title "Wildcat Strike" :type "Event" :side "Runner"}
                                           :choices [{:value "Runner gains 6 [Credits]"}
                                                     {:value "Runner draws 4 cards"}]}}
                                   :runner {}
                                   :active-player "runner"})
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (re-find #"(?i)yours" out)
            (str "the deciding seat must be told the choice is theirs, got:\n" out))
        (is (str/includes? out "Wildcat Strike"))))))

(deftest test-access-prompt-gets-no-ownership-line
  (testing "#84 noise guard: the Runner ACCESSING a Corp card also holds a prompt
            carrying an opponent card — but the Runner is the ACTIVE player and knows
            the choice is theirs. Announcing it on every access would be noise, and
            'your opponent's Hedge Fund hands you this' is false for an R&D access."
    (is (empty? (display/opponent-card-decision-lines
                 "Hedge Fund" "corp" "runner" "runner"))
        "access prompts (we are the active player) must stay silent")))

(deftest test-corp-turn-decision-handed-to-runner-fires
  (testing "#84 symmetry: a Corp card on the CORP's turn that makes the RUNNER choose
            is the same bug wearing the other hat — it must fire"
    (let [out (str/join "\n" (display/opponent-card-decision-lines
                              "Punitive Counterstrike" "corp" "runner" "corp"))]
      (is (re-find #"(?i)yours" out)
          (str "the non-active seat must be told the decision is theirs, got: " out)))))

(deftest test-unknown-active-player-stays-silent
  (testing "#84: with no active player known, claim nothing"
    (is (empty? (display/opponent-card-decision-lines
                 "Wildcat Strike" "runner" "corp" nil)))))

(deftest test-run-prompt-gets-no-ownership-line
  (testing "#84 review catch: show-run-prompts pushes a CHOICE-LESS :run prompt
            carrying the Runner's run event (Jailbreak/Overclock — several per game
            in the shipped tutorial deck) onto the CORP's queue. Telling the Corp
            'this decision is YOURS' there, with no Choices block and the run
            actually blocked on the Runner, recreates #84 on the opposite seat."
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:corp {:prompt-state
                                          {:msg "The Runner is running on HQ"
                                           :prompt-type "run"
                                           :card {:title "Jailbreak" :type "Event" :side "Runner"}
                                           :choices [] :selectable []}}
                                   :runner {}
                                   :active-player "runner"})
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (not (re-find #"(?i)decision is YOURS" out))
            (str "a choice-less run prompt must not claim a decision, got:\n" out))
        (is (not (str/includes? out "blocked until you choose"))
            (str "and must not claim the game waits on us, got:\n" out))))))

(deftest test-off-turn-access-gets-no-ownership-line
  (testing "#84 review catch: on a CORP-turn run (An Offer You Can't Refuse) the
            active-player guard no longer suppresses access, but nobody 'handed' the
            Runner an R&D access — the access prompt must stay silent on ownership."
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:runner {:prompt-state
                                            {:msg "You accessed Hedge Fund."
                                             :prompt-type "other"
                                             :card {:title "Hedge Fund" :type "Operation" :side "Corp"}
                                             :choices [{:value "No action"}
                                                       {:value "Pay 0 [Credits] to trash"}]}}
                                   :corp {}
                                   :active-player "corp"})
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (not (re-find #"(?i)decision is YOURS" out))
            (str "access prompts must not claim the opponent handed us this, got:\n" out))))))

(deftest test-real-opponent-decision-still-fires-with-choices
  (testing "#84 regression guard: the true positive (a real choice from an opponent
            card on their turn) must survive both new guards"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:runner {:prompt-state
                                            {:msg "Choose one"
                                             :prompt-type "other"
                                             :card {:title "Punitive Counterstrike" :type "Operation" :side "Corp"}
                                             :choices [{:value "0"} {:value "1"}]}}
                                   :corp {}
                                   :active-player "corp"})
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (re-find #"(?i)decision is YOURS" out)
            (str "a genuine opponent-handed choice must still be surfaced, got:\n" out))))))
