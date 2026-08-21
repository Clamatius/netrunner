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
            [ai-core :as core]
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

;; ============================================================================
;; remote-threat-counts — the servers map is KEYWORD-keyed on the wire (#137)
;; ============================================================================
;; The counts above were only ever tested as hand-fed integers, so the step that
;; DERIVES them from :servers had no fixture — and that step filtered on
;; `(string? (key %))` while the wire sends `:remote1`. Both numbers were
;; therefore hardcoded zero for the whole life of every game. These fixtures
;; carry the keyword keys the wire actually sends.

(def ^:private unrezzed-advanced-remote
  {:remote1 {:content [{:cid "a" :advance-counter 1}]}})

(deftest remote-threat-counts-reads-keyword-keyed-servers
  (testing "#137: a keyword-keyed remote holding an unrezzed advanced card is counted"
    (is (= {:unrezzed 1 :advanced 1}
           (display/remote-threat-counts unrezzed-advanced-remote))
        "the wire sends :remote1, not \"remote1\" — filtering on string? counted nothing"))
  (testing "string keys still work — the wire shape is the volatile coupling"
    (is (= {:unrezzed 1 :advanced 1}
           (display/remote-threat-counts {"remote1" {:content [{:cid "a" :advance-counter 1}]}}))))
  (testing "centrals are never remotes, however they are keyed"
    (is (= {:unrezzed 0 :advanced 0}
           (display/remote-threat-counts
            {:hq {:content [{:cid "a"}]}
             :rd {:content [{:cid "b" :advance-counter 2}]}
             :archives {:content [{:cid "c"}]}}))
        "an upgrade in a central must not inflate the remote threat count"))
  (testing "a rezzed remote card is visible, not a threat — it is not an unadvanced agenda"
    (is (= {:unrezzed 0 :advanced 0}
           (display/remote-threat-counts
            {:remote1 {:content [{:cid "a" :rezzed true :advance-counter 3}]}}))))
  (testing "unrezzed but unadvanced counts as unrezzed only"
    (is (= {:unrezzed 1 :advanced 0}
           (display/remote-threat-counts {:remote1 {:content [{:cid "a"}]}}))))
  (testing "counts are SERVERS, not cards — matching the line's own wording"
    (is (= {:unrezzed 2 :advanced 1}
           (display/remote-threat-counts
            {:remote1 {:content [{:cid "a" :advance-counter 1} {:cid "b"}]}
             :remote2 {:content [{:cid "c"}]}}))
        "remote1 holds two unrezzed cards but is one server"))
  (testing "an empty or absent servers map is zero, not a throw"
    (is (= {:unrezzed 0 :advanced 0} (display/remote-threat-counts {})))
    (is (= {:unrezzed 0 :advanced 0} (display/remote-threat-counts nil)))))

(deftest show-status-reports-real-remote-threat
  ;; #137: `status` said "Remotes 0 unrezzed / 0 advanced" in the same snapshot
  ;; where `board` printed "REMOTE1 ... Unrezzed card [1adv]". board was right.
  (testing "the runner threat line reflects a visibly advanced remote"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "corp" :turn 1
                                   :corp {:click 1 :credit 4 :hand [] :hand-count 5
                                          :deck-count 38 :discard [] :agenda-point 0
                                          :servers unrezzed-advanced-remote
                                          :user {:username "ai-corp"}}
                                   :runner {:click 0 :credit 5 :hand [] :hand-count 5
                                            :agenda-point 0 :rig {}
                                            :user {:username "ai-runner"}}})
      (let [out (with-out-str (display/show-status))]
        (is (str/includes? out "Remotes 1 unrezzed / 1 advanced")
            "the one actionable pair of numbers on the line must not be a constant zero")
        (is (not (str/includes? out "Remotes 0 unrezzed / 0 advanced")))))))

(deftest show-status-turn-zero-skips-agenda-threat-noise
  ;; #104: at turn 0 every input to the threat estimate is zero, so the line
  ;; rendered as 'Unaccounted: 18 agenda pts' over an empty board.
  (testing "runner status before any cards exist prints the plain points line"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 0
                                   :corp {:click 0 :credit 5 :hand [] :hand-count 0
                                          :deck-count 0 :discard [] :agenda-point 0
                                          :servers {} :user {:username "ai-corp"}}
                                   :runner {:click 0 :credit 5 :hand [] :hand-count 0
                                            :agenda-point 0 :rig {}
                                            :user {:username "ai-runner"}}})
      (let [out (with-out-str (display/show-status))]
        (is (not (str/includes? out "Unaccounted"))
            (str "turn-0 status must not show the vacuous threat line, got:\n" out))
        (is (str/includes? out "Agenda Points: 0 / 7")
            "the plain points line still shows"))))
  (testing "once the corp has cards the threat line comes back"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 2
                                   :corp {:click 0 :credit 5 :hand [] :hand-count 5
                                          :deck-count 30 :discard [] :agenda-point 0
                                          :servers {} :user {:username "ai-corp"}}
                                   :runner {:click 4 :credit 5 :hand [] :hand-count 3
                                            :agenda-point 0 :rig {}
                                            :user {:username "ai-runner"}}})
      (let [out (with-out-str (display/show-status))]
        (is (str/includes? out "Unaccounted")
            (str "a live board keeps the threat line, got:\n" out))))))

;; ============================================================================
;; sub-count-summary — fired subs are resolved, not unbroken (#99)
;; ============================================================================
;; Marquee ac71ce63 T8: after 'resolves 2 unbroken subroutines on Tithe', both
;; seats' displays still said '2 unbroken of 2' because unbroken was computed as
;; (not :broken), ignoring :fired. Each seat read the stale count as 'the
;; encounter hasn't resolved' and pinged the umpire.

(deftest sub-count-summary-excludes-fired-subs
  (testing "fired subs no longer count as unbroken, and are shown explicitly"
    (is (= "0 unbroken of 2 (2 fired)"
           (display/sub-count-summary [{:label "Gain 1c" :fired true}
                                       {:label "End the run" :fired true}]))))
  (testing "mixed fired/broken/actionable is broken out"
    (is (= "1 unbroken of 3 (1 fired, 1 broken)"
           (display/sub-count-summary [{:label "a" :fired true}
                                       {:label "b" :broken true}
                                       {:label "c"}]))))
  (testing "untouched subs keep the plain format"
    (is (= "2 unbroken of 2"
           (display/sub-count-summary [{:label "a"} {:label "b"}]))))
  (testing "broken-only keeps its parenthetical too"
    (is (= "1 unbroken of 2 (1 broken)"
           (display/sub-count-summary [{:label "a" :broken true}
                                       {:label "b"}])))))

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

  ;; #117 INVERTED. "Both sides at 0 clicks with no :end-turn flag" is NOT a
  ;; boundary — it is a turn that has run out of clicks without ending. Calling
  ;; it AWAITING-START named a next-player who could not act, and a boundary that
  ;; had not happened; the real game deadlocked behind exactly this line. It now
  ;; reports as IN-PROGRESS with clicks=0, which is both true and already the
  ;; same-turn/same-clicks spin signature tooling watches for.
  (testing "both sides at 0 clicks, no end-turn flag -> IN-PROGRESS, not a boundary (#117)"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "runner" :turn 6
                                   :corp {:click 0} :runner {:click 0}})
      (is (= "IN-PROGRESS turn=6 whose-turn=runner clicks=0"
             (str/trim (with-out-str (display/game-over-status))))
          "no owes=end-turn: this seat is the Corp, and the orphaned turn is the Runner's")))

  ;; #104: a boundary with our own prompt still open (end-of-turn discard) read
  ;; as a desync to both guest models. The blocker is named as an additive
  ;; machine-readable field; a mere waiting stub must not trigger it.
  (testing "our open prompt at the boundary is named, not hidden"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 7
                                   :end-turn true
                                   :corp {:click 0
                                          :prompt-state {:eid {:eid 1}
                                                         :prompt-type "select"
                                                         :msg "Discard down to maximum hand size"}}
                                   :runner {:click 0}})
      (is (= "AWAITING-START turn=7 next-player=runner open-prompt=mine"
             (str/trim (with-out-str (display/game-over-status)))))))

  (testing "a waiting-stub prompt does not claim open-prompt"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 7
                                   :end-turn true
                                   :corp {:click 0
                                          :prompt-state {:prompt-type "waiting"
                                                         :msg "Waiting for Runner"}}
                                   :runner {:click 0}})
      (is (= "AWAITING-START turn=7 next-player=runner"
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

(deftest test-status-runner-section-shows-runner-hand-not-mine
  ;; #85: the full-status RUNNER section read my-hand-count, so a Corp viewer
  ;; saw its OWN hand size printed as the Runner's grip — the one number a
  ;; meat-damage kill calculation depends on (marquee g1: snapshot said 2h,
  ;; status said 4; status was the liar). The RUNNER section must report the
  ;; RUNNER's public :hand-count regardless of viewer.
  (testing "corp seat: RUNNER section reports the runner's grip, not corp's hand"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 10
                                   :corp {:click 3 :credit 8 :hand [{:title "X"}]
                                          :hand-count 4 :agenda-point 0 :deck-count 20
                                          :servers {} :user {:username "ai-corp"}}
                                   :runner {:click 0 :credit 6 :hand [] :hand-count 2
                                            :agenda-point 2 :rig {}
                                            :user {:username "ai-runner"}}})
      (let [out (with-out-str (display/show-status))
            runner-section (subs out (str/index-of out "--- RUNNER ---")
                                (str/index-of out "--- CORP ---"))]
        (is (str/includes? runner-section "Hand: 2 cards")
            (str "RUNNER section must show the runner's grip (2), got: " runner-section))
        (is (not (str/includes? runner-section "Hand: 4 cards"))
            (str "RUNNER section must not show the corp's own hand (4), got: " runner-section))))))

(deftest test-status-compact-runner-tags-visible
  ;; #85 part 2: tags decide endgames (Orbital Superiority vs a tagged Runner
  ;; won marquee g1) but were invisible in the one-call snapshot. The compact
  ;; line must carry the runner's tag count when tagged — and stay clean when
  ;; not, so the common case adds no noise.
  (testing "tagged runner shows /Ntag in the runner stat segment (corp view)"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 10
                                   :corp {:click 3 :credit 8 :hand [] :hand-count 4 :agenda-point 0}
                                   :runner {:click 0 :credit 6 :hand [] :hand-count 2
                                            :agenda-point 2 :tag {:base 2}}})
      (let [line (str/trim (with-out-str (display/status-compact)))]
        (is (str/includes? line "Opp(R):6c/0cl/2h/2AP/2tag")
            (str "runner tag count must appear in the compact line, got: " line)))))
  (testing "tagged runner sees own tags too (runner view)"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 10
                                   :runner {:click 2 :credit 6 :hand [] :hand-count 2
                                            :agenda-point 2 :tag {:base 1} :rig {}}
                                   :corp {:click 0 :credit 8 :hand [] :hand-count 4 :agenda-point 0}})
      (let [line (str/trim (with-out-str (display/status-compact)))]
        (is (str/includes? line "Me(R):6c/2cl/2h/2AP/1tag")
            (str "runner's own tag count must appear, got: " line)))))
  (testing "untagged runner keeps the plain segment"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp" :turn 10
                                   :corp {:click 3 :credit 8 :hand [] :hand-count 4 :agenda-point 0}
                                   :runner {:click 0 :credit 6 :hand [] :hand-count 2
                                            :agenda-point 2 :tag {:base 0}}})
      (let [line (str/trim (with-out-str (display/status-compact)))]
        (is (str/includes? line "Opp(R):6c/0cl/2h/2AP")
            (str "untagged segment unchanged, got: " line))
        (is (not (str/includes? line "tag"))
            (str "no tag noise when untagged, got: " line))))))

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
        ;; Was: "should offer jack-out as the stall recovery". It must not — the
        ;; recovery is the umpire, and jack-out here throws the run away (and at
        ;; this initiation phase is not even legal).
        (is (str/includes? out "umpire-ping")
            (str "should point at the judge, not at bailing, got: " out))
        (is (not (str/includes? out "ACTIONABLE")))))))

(deftest test-priority-hint-runner-already-passed-escalates-not-bails
  ;; Renamed from ...-suggests-jackout. The old test asserted only that the token
  ;; "jack-out" appeared, so it kept passing once the text flipped to "Do NOT
  ;; 'jack-out'" — it could not tell a recommendation from a prohibition. Assert
  ;; the FRAMING, not the token.
  (testing "Runner already-passed hint prohibits jack-out and names the umpire as
            the recovery when the opponent seat isn't monitoring (issue #31/#20)"
    (let [out (str/join "\n" (display/run-priority-hint-lines
                              {:phase "initiation" :position 1 :server ["hq"] :no-action "runner"}
                              "runner"))]
      (is (str/includes? out "already passed priority"))
      (is (re-find #"Do NOT 'jack-out'" out)
          "jack-out must be named as prohibited here, not offered")
      (is (str/includes? out "umpire-ping")
          "and the sanctioned recovery must be present, or the seat invents one")
      (is (not (re-find #"(?i)jack-out (?:to|and) (?:recover|unstick|clear)" out))
          "no wording that reads as recommending jack-out as the escape hatch"))))

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
  ;; This test used to assert the Runner is told "Re-sending 'continue' does
  ;; nothing". A guest panel on #115 showed that sentence is false on this side:
  ;; the run loop's own #31 recovery IS a re-issued continue. The test had pinned
  ;; the bug. What must hold is that the seat is not steered back into an
  ;; immediate manual repeat as if the window were still its move.
  (testing "Side that already passed is told to wait, not that it's still its move"
    (let [out (str/join "\n" (display/run-priority-hint-lines
                              {:phase "movement" :position 0 :server ["hq"] :no-action "runner"}
                              "runner"))]
      (is (str/includes? out "already passed priority"))
      (is (str/includes? out "waiting for Corp"))
      (is (str/includes? out "use 'wait'"))
      (is (not (str/includes? out "It's YOUR move"))))))

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
;; The judge button: a seat stalled on an unanswered run window must be pointed
;; at the umpire (./dev/umpire-ping, issue #20), not left to invent a recovery.
;; ============================================================================
;; Replay 0b52266c (2026-07-08) twice shows the failure: the Runner passed
;; priority, the Corp never passed, the Runner pinged the opponent in game chat,
;; waited, then jacked out — once abandoning a run where it had already broken
;; Palisade for 3c and was one Corp pass from breaching Server 1. The umpire
;; channel landed 2026-07-15, a week later, so that seat genuinely had no judge to
;; call. It has one now, but this hint — the exact place a seat reads when it is
;; stuck in a window — still only mentioned `peer-status`, which answers "is the
;; opponent alive?" and not "I think we are wedged, adjudicate."

(deftest test-priority-hint-stalled-window-names-the-umpire
  (testing "Runner waiting on an unanswered window is given the escalation path"
    (let [out (str/join "\n" (display/run-priority-hint-lines
                              {:phase "movement" :position 0 :server ["remote1"]
                               :no-action :runner}
                              "runner"))]
      (is (str/includes? out "peer-status")
          "keep the liveness check — it is how you tell 'thinking' from 'gone'")
      (is (str/includes? out "umpire-ping")
          "and name the judge button for when the opponent is NOT coming back")))
  (testing "the Corp gets the same escalation when IT is the one left waiting"
    (let [out (str/join "\n" (display/run-priority-hint-lines
                              {:phase "movement" :position 0 :server ["hq"]
                               :no-action :corp}
                              "corp"))]
      (is (str/includes? out "umpire-ping")
          "the stall is symmetric; the judge button must be too"))))

;; ============================================================================
;; runner-encounter-decline-hint-lines — must not advertise an illegal action
;; ============================================================================
;; Jack out is disabled during encounter-ice in the human UI (board.cljs gates the
;; button on phase == "movement"). Offering it here taught seats an action that
;; skips unbroken subroutines — 11 of the 28 archived jack-outs fired at an
;; encounter. The real options at an encounter are break or tank.

(deftest test-encounter-decline-hint-does-not-offer-jack-out
  (testing "no jack-out in the encounter menu — it is illegal in this phase"
    (let [out (str/join "\n" (display/runner-encounter-decline-hint-lines "Palisade" 1))]
      (is (not (str/includes? out "jack-out"))
          "jack out is UI-disabled during an encounter; offering it teaches a sub-skip")
      (is (str/includes? out "tank"))
      (is (re-find #"(?i)break" out))))
  (testing "the menu says why bailing is not on it, so a seat does not go hunting"
    (let [out (str/join "\n" (display/runner-encounter-decline-hint-lines "Palisade" 2))]
      (is (re-find #"(?i)cannot jack out|can't jack out" out)))))

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
  ;; This test previously asserted `(str/includes? out "jack-out")` — "should
  ;; offer jack-out". That assertion was wrong and PINNED the bug: jack out is
  ;; movement-window only (board.cljs), so at an encounter it is both illegal and
  ;; a subroutine-skip. The #92 fix was right that this window must not steer to a
  ;; bare `continue`; it was wrong about what the third option is. There is no
  ;; third option — break or tank.
  (testing "the pure decline hint names tank with the ICE title, never a bare continue"
    (let [out (str/join "\n" (display/runner-encounter-decline-hint-lines "Whitespace" 2))]
      (is (str/includes? out "tank \"Whitespace\"")
          (str "should name 'tank \"<ice>\"' as the decline-and-pass command, got: " out))
      (is (not (str/includes? out "jack-out"))
          (str "must NOT offer jack-out — illegal mid-encounter, and it skips the subs, got: " out))
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

;; ============================================================================
;; Bioroid click-break discoverability (#95)
;; ============================================================================
;; Marquee 6d8f4cf8: during the Brân 1.0 encounter, neither `abilities` nor
;; `list-playables` surfaced the bioroid's runner-usable click-break. The
;; existing list-playables section read the PROMPT card's :runner-abilities —
;; prompt-state is stale-prone (memory: prompt-state-not-cleared-use-eid) and
;; it missed in the recorded game. The authoritative source during an
;; encounter is current-run-ice; both surfaces must print the exact
;; use-runner-ability invocation.

(def show-encounter-ice-info #'display/show-encounter-ice-info)

(def bran-ice
  {:cid 77 :title "Brân 1.0" :zone [:servers :rd :ices] :side "Corp" :type "ICE"
   :rezzed true :subtypes ["Bioroid" "Barrier"] :strength 4
   :subroutines [{:label "Install ice from HQ" :broken false}
                 {:label "End the run" :broken false}
                 {:label "End the run" :broken false}]
   :runner-abilities [{:label "Lose [click]: Break 1 subroutine"
                       :cost-label "Lose [click]"}]})

(defn- bran-encounter-state []
  (mock-client-state
   :side "runner"
   :game-state {:runner {:credit 5 :click 2
                         :rig {:program [] :hardware [] :resource []}}
                :corp {:servers {:rd {:ices [bran-ice] :content []}}}
                :run {:position 1 :server ["rd"] :phase "encounter-ice"}
                :active-player "runner"}))

(deftest encounter-info-surfaces-bioroid-click-break
  (testing "encounter display prints the runner-ability invocation from run state"
    (with-mock-state (bran-encounter-state)
      (let [state @ai-state/client-state
            run (get-in state [:game-state :run])
            out (with-out-str (show-encounter-ice-info state run "runner"))]
        (is (str/includes? out "Lose [click]: Break 1 subroutine")
            (str "bioroid ability label must appear, got:\n" out))
        (is (str/includes? out "use-runner-ability \"Brân 1.0\" 0")
            (str "exact invocation must be printed, got:\n" out))))))

(deftest encounter-info-no-bioroid-section-without-runner-abilities
  (testing "no runner-ability section for plain ICE"
    (with-mock-state
      (mock-client-state
       :side "runner"
       :game-state {:runner {:credit 5 :click 2
                             :rig {:program [] :hardware [] :resource []}}
                    :corp {:servers {:rd {:ices [{:cid 78 :title "Palisade"
                                                  :zone [:servers :rd :ices]
                                                  :side "Corp" :type "ICE"
                                                  :rezzed true}]
                                          :content []}}}
                    :run {:position 1 :server ["rd"] :phase "encounter-ice"}
                    :active-player "runner"})
      (let [state @ai-state/client-state
            run (get-in state [:game-state :run])
            out (with-out-str (show-encounter-ice-info state run "runner"))]
        (is (not (str/includes? out "use-runner-ability"))
            (str "no invocation hint for ICE without runner-abilities, got:\n" out))))))

(deftest abilities-falls-back-to-corp-card-for-runner
  (testing "show-card-abilities finds an opponent card and shows runner-abilities"
    (with-mock-state (bran-encounter-state)
      (let [out (with-out-str (display/show-card-abilities "Brân 1.0"))]
        (is (not (str/includes? out "Card not found"))
            (str "cross-side lookup must succeed, got:\n" out))
        (is (str/includes? out "Lose [click]: Break 1 subroutine")
            (str "runner-usable ability is listed, got:\n" out))
        (is (str/includes? out "use-runner-ability \"Brân 1.0\" 0")
            (str "exact invocation must be printed, got:\n" out))))))

(deftest playables-no-click-break-outside-encounter
  (testing "list-playables must not advertise use-runner-ability at approach-ice (out of window => engine silently refuses, client times out)"
    (with-mock-state
      (mock-client-state
       :side "runner"
       :game-state {:runner {:credit 5 :click 2
                             :rig {:program [] :hardware [] :resource []}
                             :prompt-state nil}
                    :corp {:servers {:rd {:ices [bran-ice] :content []}}}
                    :run {:position 1 :server ["rd"] :phase "approach-ice"}
                    :active-player "runner"})
      (let [out (with-out-str (display/list-playables))]
        (is (not (str/includes? out "use-runner-ability"))
            (str "click-break is only live during encounter-ice, got:\n" out))))))

(deftest playables-shows-click-break-during-encounter
  (testing "list-playables still advertises the click-break in the live window"
    (with-mock-state (bran-encounter-state)
      (let [out (with-out-str (display/list-playables))]
        (is (str/includes? out "use-runner-ability \"Brân 1.0\" 0")
            (str "encounter-ice window must surface the invocation, got:\n" out))))))

;; ============================================================================
;; #104: the same prompt block printed twice back-to-back
;; ============================================================================
;; Acting commands auto-append the resulting prompt (show-prompt-if-any, added
;; because continue->prompt ran P=0.48), but seats kept calling `prompt` right
;; after acting. Live repro on a fresh game: `install Palisade` printed the
;; server-select block, then `prompt` printed the byte-identical block again with
;; nothing distinguishing them — which reads as two stacked prompts and invites
;; answering twice. The second render must say it is the same one.

(defn- prompt-state-for [prompt]
  {:corp {:prompt-state prompt} :runner {:prompt-state prompt}})

(def ^:private select-server-prompt
  {:eid 4242 :msg "Choose a location to install Palisade" :prompt-type "other"
   :card {:title "Palisade" :type "ICE" :cid 7}
   :choices [{:value "HQ" :uuid "u1"} {:value "New remote" :uuid "u2"}]})

(deftest test-repeat-render-of-same-prompt-is-marked-unchanged
  (testing "#104: second render of the SAME prompt instance is labelled, first is not"
    (with-mock-state (mock-client-state :side "corp"
                                        :game-state (prompt-state-for select-server-prompt))
      (ai-state/reset-rendered-prompt!)
      (let [first-out (with-out-str (display/show-prompt-detailed))
            second-out (with-out-str (display/show-prompt-detailed))]
        (is (not (str/includes? first-out "unchanged"))
            (str "the first render must be a plain prompt block, got: " first-out))
        (is (str/includes? second-out "unchanged")
            (str "the repeat render must say it is the same prompt, got: " second-out))
        ;; the block itself must still be fully rendered — this is a label, not a suppression
        (is (str/includes? second-out "New remote")
            "choices must still print on the repeat render")))))

(deftest test-stacked-duplicate-prompt-is-not-marked-unchanged
  (testing "#104 vs #75: a NEW instance (same msg+card, new eid) must read as new"
    (with-mock-state (mock-client-state :side "corp"
                                        :game-state (prompt-state-for select-server-prompt))
      (ai-state/reset-rendered-prompt!)
      (with-out-str (display/show-prompt-detailed)))
    ;; engine minted a fresh copy of the same-looking prompt
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state (prompt-state-for (assoc select-server-prompt :eid 4243)))
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (not (str/includes? out "unchanged"))
            (str "a stacked duplicate is a separate prompt to answer, got: " out))))))

(deftest test-eidless-prompt-never-reads-as-unchanged
  (testing "#104: an unidentifiable prompt (no :eid) is always treated as new"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state (prompt-state-for (dissoc select-server-prompt :eid)))
      (ai-state/reset-rendered-prompt!)
      (with-out-str (display/show-prompt-detailed))
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (not (str/includes? out "unchanged"))
            (str "nil eid must not match nil eid (#75 lesson), got: " out))))))

(deftest test-rendered-prompt-marker-cleared-on-state-reset
  (testing "#104 lifecycle: the marker must not survive into a fresh game"
    (with-mock-state (mock-client-state :side "corp"
                                        :game-state (prompt-state-for select-server-prompt))
      (ai-state/reset-rendered-prompt!)
      (with-out-str (display/show-prompt-detailed))
      (ai-state/reset-rendered-prompt!)
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (not (str/includes? out "unchanged"))
            (str "a new game's first prompt is never 'unchanged', got: " out))))))

;; ============================================================================
;; `prompt` must not route a seat into an infinite wait when the game is GONE
;; ============================================================================
;; Live capture, 2026-08-07 polish round, on a game the server had already
;; purged (`game-over-status` correctly said NO-GAME, `status` correctly said
;; "Not in a game"):
;;
;;   $ ./dev/send_command corp prompt
;;   No active prompt — no decision is pending for you right now.
;;   ⏳ It's the opponent's turn, not yours → use 'wait'.
;;
;; Both lines are false and the second is actively harmful: a seat that follows
;; it blocks on `wait` for an opponent that does not exist. The no-prompt branch
;; derives everything from get-turn-status and never asks whether we are in a
;; game at all, so `my-turn?` is nil and the "not your turn" arm wins by default.
;;
;; Same lesson as the turn-boundary comment directly above that branch — "no
;; active prompt" is technically true and still misleads — one level further up.

(deftest test-prompt-no-game-does-not-advise-waiting
  (testing "GAME-GONE: `prompt` must not tell the seat to wait for an opponent"
    (with-mock-state {:side "corp" :game-state nil :lobby-state nil}
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (not (str/includes? out "use 'wait'"))
            (str "no game = nobody to wait for; steering a seat into `wait` here "
                 "is the stall we keep paying for. Got: " out))
        (is (not (str/includes? out "turn, not yours"))
            (str "there is no turn to attribute to an opponent. Got: " out)))))

  (testing "GAME-GONE: `prompt` says the game is gone and points at recovery"
    (with-mock-state {:side "corp" :game-state nil :lobby-state nil}
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (str/includes? out "Not in a game")
            (str "must name the real condition, matching `status`. Got: " out))
        (is (str/includes? out "reset.sh")
            (str "must point at the documented recovery. Got: " out))))))

(deftest test-prompt-still-answers-normally-in-a-live-game
  (testing "the no-game guard must not swallow ordinary turn-boundary advice"
    ;; Regression fence: the fix adds a branch ahead of the turn-status cond,
    ;; so the live-game arms must keep firing.
    (with-mock-state (mock-client-state :side "corp"
                                        :game-state {:active-player "runner"
                                                     :turn 4
                                                     :corp {:click 0}
                                                     :runner {:click 2}})
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (str/includes? out "use 'wait'")
            (str "a real opponent turn must still route to `wait`. Got: " out))))))

;; Guest-panel catch (GPT-5.6 Terra) on the first cut of the #109 fix.
;; My :in-game? predicate asked "is there a cached game-state?" — but #93
;; established that a purged lobby can leave the SNAPSHOT behind and announce
;; itself only via :lobby-gone?. In that shape the predicate says "in a game",
;; `prompt` takes the live arm, and the seat is told to `wait` all over again —
;; the fix reads as complete while being a no-op in the teardown case that
;; issue #93 exists to describe. game-over-status already treats :lobby-gone?
;; as authoritative; :in-game? has to agree with it or the surfaces re-diverge,
;; which is the exact failure the single-predicate rule is meant to prevent.

(deftest test-prompt-lobby-gone-with-cached-snapshot-does-not-advise-waiting
  (testing "#93 teardown shape: cached game-state + :lobby-gone? must not route to `wait`"
    (with-mock-state {:side "corp"
                      :lobby-gone? true
                      :game-state {:active-player "runner" :turn 9
                                   :corp {:click 0} :runner {:click 2}}}
      (let [out (with-out-str (display/show-prompt-detailed))]
        (is (not (str/includes? out "use 'wait'"))
            (str "game-over-status calls this GAME-GONE; `prompt` must not "
                 "call it someone's turn. Got: " out))))))

(deftest test-in-game-predicate-agrees-with-game-over-status
  (testing ":in-game? and game-over-status must not disagree about the same state"
    (let [dead {:side "corp"
                :lobby-gone? true
                :game-state {:active-player "runner" :turn 9
                             :corp {:click 0} :runner {:click 2}}}]
      (with-mock-state dead
        (is (str/starts-with? (str/trim (with-out-str (display/game-over-status)))
                              "GAME-GONE")
            "fixture sanity: this state is the one #93 calls GAME-GONE")
        (is (false? (:in-game? (ai-state/get-turn-status)))
            "a GAME-GONE state is not a game we are in")))))

;; ============================================================================
;; #115: approach-ice is a both-must-pass window for the Runner too.
;;
;; game.core.runs `continue :approach-ice` records the first passer in
;; [:run :no-action] exactly as movement does, so a Runner that has already
;; passed cannot advance it. This surface printed the terse "use 'continue' to
;; pass priority (advance the run)" at that seat — with none of the
;; already-passed / don't-jack-out / escalate guidance the identical situation
;; gets at #1 Run begins. Two Luna seats re-sent continue and then pinged the
;; umpire.
;; ============================================================================

(deftest test-approach-ice-runner-already-passed-gets-the-run-begins-guidance
  (testing "#115: a Runner that has passed approach-ice is told it has passed, that
            re-sending does nothing, and how to escalate — not to 'continue'"
    (let [run {:server ["rd"] :phase "approach-ice" :position 2 :no-action "runner"}
          out (with-out-str
                (display/print-run-window-priority!
                 {:game-state {:run run}} run "approach-ice" "runner"))]
      (is (str/includes? out "already passed priority")
          (str "the seat must learn its pass is already recorded, got:\n" out))
      (is (str/includes? out "use 'wait'")
          (str "and steered to wait rather than an immediate manual repeat, got:\n" out))
      (is (str/includes? out "umpire-ping")
          (str "and be given the sanctioned escalation instead of inventing one, got:\n" out))
      (is (not (re-find #"→ Use 'continue' to pass priority" out))
          (str "the terse steer that provably cannot act must be gone, got:\n" out)))))

(deftest test-approach-ice-runner-fresh-window-names-what-passing-buys
  (testing "#115: at a fresh approach-ice window the Runner is the first sub-step,
            and passing resolves THIS ICE — not 'the next ICE', which is the
            movement-window outcome"
    (let [run {:server ["rd"] :phase "approach-ice" :position 2 :no-action false}
          out (with-out-str
                (display/print-run-window-priority!
                 {:game-state {:run run}} run "approach-ice" "runner"))]
      (is (str/includes? out "active player goes first")
          (str "expected the sub-step framing, got:\n" out))
      (is (str/includes? out "resolve this ICE")
          (str "passing an approach resolves the ICE in front of you, got:\n" out))
      (is (not (str/includes? out "approach the next ICE"))
          (str "the movement-window outcome must not be claimed here, got:\n" out))
      (is (not (str/includes? out "ICE rez window"))
          (str "the rez verb is the Corp's, not the Runner's, got:\n" out)))))

(deftest test-approach-ice-runner-corp-passed-is-my-move
  (testing "#115: Corp has passed approach-ice — the Runner's continue advances it"
    (let [run {:server ["rd"] :phase "approach-ice" :position 2 :no-action "corp"}
          out (with-out-str
                (display/print-run-window-priority!
                 {:game-state {:run run}} run "approach-ice" "runner"))]
      (is (str/includes? out "It's YOUR move")
          (str "expected the opponent-has-passed branch, got:\n" out))
      (is (not (str/includes? out "already passed priority here"))
          (str "the Runner has NOT passed — must not read as its own pass, got:\n" out)))))

(deftest test-approach-ice-corp-already-passed-loses-the-rez-offer
  (testing "#115: a Corp that has already declined this window must not be offered
            a rez it can no longer take, nor told 'continue' passes priority"
    (let [run {:server ["rd"] :phase "approach-ice" :position 2 :no-action "corp"}
          out (with-out-str
                (display/print-run-window-priority!
                 {:game-state {:run run}} run "approach-ice" "corp"))]
      (is (str/includes? out "already passed priority")
          (str "the Corp's own pass is recorded; say so, got:\n" out))
      (is (not (str/includes? out "ICE rez window"))
          (str "the rez window is closed to a Corp that passed it, got:\n" out))
      (is (not (str/includes? out "DECLINE"))
          (str "it has already declined — offering the decline again is the lie, got:\n" out)))))

(deftest test-approach-ice-corp-owning-window-keeps-its-rez-guidance
  (testing "#115 regression guard: a Corp that still owns the window keeps the rez
            options — the fix must not cost the Corp its rez steer"
    (let [run {:server ["rd"] :phase "approach-ice" :position 2 :no-action false}
          out (with-out-str
                (display/print-run-window-priority!
                 {:game-state {:run run}} run "approach-ice" "corp"))]
      (is (str/includes? out "ICE rez window"))
      (is (str/includes? out "--no-rez"))
      (is (str/includes? out "DECLINE")))))

;; ============================================================================
;; #115 cosmetics: one indexing convention, and fog of war that reads as fog.
;; ============================================================================

(deftest test-ice-pass-index-counts-up-as-position-counts-down
  (testing "position is a countdown; the Runner meets position=ice-count FIRST"
    (is (= 1 (core/ice-pass-index 2 2)) "outermost ICE is 'ICE 1 of 2'")
    (is (= 2 (core/ice-pass-index 1 2)) "innermost ICE is 'ICE 2 of 2'"))
  (testing "no honest index available -> nil, never a bogus one"
    (is (nil? (core/ice-pass-index 0 2)) "past all ICE")
    (is (nil? (core/ice-pass-index nil 2)))
    (is (nil? (core/ice-pass-index 2 nil)) "unknown ICE count")
    (is (nil? (core/ice-pass-index 3 2)) "position out of range (wire drift)")))

(deftest test-describe-approached-ice-renders-fog-not-breakage
  (testing "#115: an unrezzed ICE has no title on the Runner's wire — the old
            format printed the literal default and read as 'ICE: ICE'"
    (let [line (core/describe-approached-ice "ICE" 2 2)]
      (is (not (str/includes? line "ICE: ICE"))
          (str "the placeholder-as-name rendering is gone, got: " line))
      (is (str/includes? line "hidden")
          (str "say WHY there is no name, got: " line))))
  (testing "the line uses the ladder's convention, not the raw countdown"
    (let [line (core/describe-approached-ice "ICE" 2 2)]
      (is (str/includes? line "ICE 1 of 2")
          (str "outermost ICE with 2 ICE = 'ICE 1 of 2', matching the run ladder, got: " line))
      (is (not (str/includes? line "position 2/2"))
          (str "the contradicting second convention is gone, got: " line))))
  (testing "a known title is still named"
    (is (str/includes? (core/describe-approached-ice "Whitespace" 2 2) "Whitespace"))))

;; The same lie lived in a SECOND emitter. `diagnose` is the surface a stuck seat
;; reaches for, and it carried its own inline copy of the both-pass phase set —
;; so fixing print-run-window-priority! alone would have left the seat that had
;; actually noticed it was stuck reading "Use: continue" at a window it had
;; already passed. Both now read the membership from both-pass-window?.

(deftest test-diagnose-approach-ice-runner-already-passed-agrees-with-prompt
  (testing "#115: diagnose at an approach-ice window the Runner has passed gives the
            already-passed guidance, not the generic continue steer"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner" :turn 4
                                   :run {:phase "approach-ice" :position 2
                                         :server ["rd"] :no-action "runner"}
                                   :runner {:click 2 :credit 5 :hand []
                                            :prompt-state {:msg "Waiting for Corp to rez"
                                                           :prompt-type "run"}}
                                   :corp {:click 0 :credit 5 :hand []
                                          :servers {:rd {:ices [{:title "Whitespace"}
                                                                {:title "Diviner"}]}}}})
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        (is (str/includes? out "already passed priority")
            (str "diagnose must agree with prompt/status that the pass is spent, got:\n" out))
        (is (not (re-find #"(?i)use: continue \(to pass priority\)" out))
            (str "the generic steer that cannot act must be gone from diagnose too, got:\n" out))))))

(deftest test-both-pass-window-membership
  (testing "#115: the Runner's pass windows include approach-ice — the engine's
            continue :approach-ice records [:run :no-action] exactly as movement does"
    (is (display/both-pass-window? "approach-ice" "runner"))
    (is (display/both-pass-window? "initiation" "runner"))
    (is (display/both-pass-window? "movement" "runner")))
  (testing "encounter-ice is NOT one: its passer lives on [:encounters :no-action]
            and the Runner's decision there is break/tank, not a pass (#92)"
    (is (not (display/both-pass-window? "encounter-ice" "runner")))
    (is (not (display/both-pass-window? "encounter-ice" "corp"))))
  (testing "the Corp keeps its own richer steer at the windows carrying a rez decision"
    (is (not (display/both-pass-window? "approach-ice" "corp")))
    (is (not (display/both-pass-window? "initiation" "corp")))
    (is (display/both-pass-window? "movement" "corp"))))

;; Guest-panel catch on the #115 fix: the already-passed block told the Runner
;; "Re-sending 'continue' does nothing" and then, two lines later, to escalate to
;; an umpire. Both cannot be right. The engine's advance branch has no side-check
;; (game.core.runs `continue`), and handle-stalled-window-self-advance uses that
;; deliberately — a re-issued `continue` after the grace period IS the sanctioned
;; #31 recovery for a decision-free abandoned window. The old wording steered the
;; Runner straight past its own recovery into the ping this block exists to avoid.

(deftest test-already-passed-runner-is-told-about-self-advance-before-escalating
  (testing "#115/panel: the Runner's already-passed guidance must not call a repeat
            continue futile — it is the #31 recovery — and must order it before the
            umpire escalation"
    (let [run {:server ["rd"] :phase "approach-ice" :position 2 :no-action "runner"}
          out (with-out-str
                (display/print-run-window-priority!
                 {:game-state {:run run}} run "approach-ice" "runner"))]
      (is (not (str/includes? out "Re-sending 'continue' does nothing"))
          (str "flatly false for the Runner: the run loop self-advances an abandoned
                window with a second pass, got:\n" out))
      (is (re-find #"(?i)re-issuing 'continue'.*self|advance the abandoned" out)
          (str "the seat must learn the recovery exists, got:\n" out))
      (is (< (.indexOf out "BEFORE escalating") (.indexOf out "umpire-ping"))
          (str "and must be told to try it BEFORE the umpire, got:\n" out)))))

(deftest test-already-passed-corp-keeps-the-flat-no-op-line
  (testing "#115/panel: self-advance is Runner-side only, so the Corp's repeat
            continue really is a no-op — it must not be told otherwise"
    (let [run {:server ["rd"] :phase "movement" :position 1 :no-action "corp"}
          out (with-out-str
                (display/print-run-window-priority!
                 {:game-state {:run run}} run "movement" "corp"))]
      (is (str/includes? out "Re-sending 'continue' does nothing")
          (str "the Corp has no self-advance path, got:\n" out))
      (is (not (str/includes? out "abandoned"))
          (str "must not promise the Corp a recovery it does not have, got:\n" out)))))

;; ============================================================================
;; #125: seat-facing surfaces must not throw a raw NPE when there is no side
;; ============================================================================
;; `:side` is nil in two real states: a REPL that has never joined, and the
;; state `leave-lobby!` leaves behind (it nils :gameid/:side, and a finished
;; game's teardown — save-replay.sh, concede — goes through exactly that path).
;; Three surfaces re-derived the side by hand as
;; `(keyword (clojure.string/lower-case (:side state)))`, which throws
;;   Cannot invoke "Object.toString()" because "s" is null
;; straight out of clojure.string/lower-case. A raw Java stack trace is
;; unpattern-matchable by a model seat, and it arrives at the exact moment the
;; seat is trying to work out what happened to its game.
;;
;; `ai-state/my-side-kw` is the guarded authority for this derivation and
;; `show-hand` already bails with a readable line; these must agree with it.

(def ^:private sideless-state
  "A client that never joined: no side, no gameid, no board."
  {:connected true :uid "test-user" :gameid nil :side nil :game-state nil})

(def ^:private left-game-state
  "What `leave-lobby!` ACTUALLY leaves behind. Second-pass panel catch: it nils
   :gameid/:side and dissocs :spectator but does NOT clear :game-state, so the
   ordinary post-teardown client — the state #125 was captured in — still holds
   a board. The first fixture modelled this as :game-state nil and hid the bug."
  {:connected true :uid "test-user" :gameid nil :side nil
   :game-state {:active-player "corp" :turn 10
                :corp {:click 0 :credit 35 :hand [] :hand-count 5}
                :runner {:click 0 :credit 5 :hand [] :hand-count 5 :rig {}}}})

(def ^:private pending-watch-state
  "`watch-game!` sets :spectator BEFORE any confirmation, so this is a watch
   that was refused or has not landed — spectator flag, no board."
  {:connected true :uid "watcher" :gameid "abc" :side nil
   :spectator true :spectator-perspective nil :game-state nil})

(def ^:private spectator-state
  "`watch-game!` output: a real board, :spectator true, and no :side at all."
  {:connected true :uid "watcher" :gameid "abc" :side nil
   :spectator true :spectator-perspective "Corp"
   :game-state {:active-player "corp" :turn 3
                :corp {:click 2 :credit 9 :hand [] :hand-count 3}
                :runner {:click 4 :credit 5 :hand [] :hand-count 5 :rig {}}}})

(deftest test-sideless-surfaces-do-not-throw-raw-npe
  (testing "#125: no seat-facing display throws a bare NPE when :side is nil"
    (doseq [[label f] [["list-playables" display/list-playables]
                       ["show-credits"   display/show-credits]
                       ["show-clicks"    display/show-clicks]]]
      (with-mock-state sideless-state
        (is (string? (try (with-out-str (f))
                          (catch Exception e
                            (str "THREW " (.getName (class e)) ": " (.getMessage e)))))
            (str label " returned nothing at all"))
        (let [out (try (with-out-str (f))
                       (catch Exception e
                         (str "THREW " (.getName (class e)) ": " (.getMessage e))))]
          (is (not (str/starts-with? out "THREW"))
              (str label " must not throw on a sideless state, got: " out))
          (is (re-find #"(?i)not in a game" out)
              (str label " must say plainly that there is no game, got:\n" out)))))))

(def ^:private boardless-seat-state
  "#139: the complement of the sideless fixtures — the side is KNOWN and the
   BOARD is the thing that is gone. This is exactly what a failed resync leaves
   behind (`resync-game!` clears the cache before requesting a replacement and
   :gameid survives), the state `boardless-started-game?` classifies and
   `sync-verdict!` calls :resync-failed. The action commands are already
   refused in it; the read surfaces described an empty game instead."
  {:connected true :uid "test-user" :gameid "abc" :side "corp" :game-state nil})

(deftest test-boardless-seat-surfaces-do-not-invent-a-board
  (testing "#139: a seat holding no board must not report one"
    (doseq [[label f] [["show-credits"   display/show-credits]
                       ["show-clicks"    display/show-clicks]
                       ["show-hand"      display/show-hand]
                       ["list-playables" display/list-playables]
                       ;; #139 remainder — the surfaces the first cut left
                       ;; inventing an empty game (issue table, worst first).
                       ["show-blocker-diagnosis" display/show-blocker-diagnosis]
                       ["status-compact"  display/status-compact]
                       ["show-archives"   display/show-archives]
                       ["show-heap"       display/show-heap]
                       ["show-board"      display/show-board]
                       ["board-compact"   display/board-compact]
                       ["show-snapshot"   display/show-snapshot]]]
      (with-mock-state boardless-seat-state
        (let [out (try (with-out-str (f))
                       (catch Exception e
                         (str "THREW " (.getName (class e)) ": " (.getMessage e))))]
          (is (not (str/starts-with? out "THREW"))
              (str label " must not throw on a boardless seat, got: " out))
          (is (re-find #"(?i)no board|board.*(gone|cleared)" out)
              (str label " must say the board is missing, got:\n" out))
          ;; The specific lies observed live.
          (is (not (re-find #"(?i):\s*nil" out))
              (str label " printed a nil where a number belongs, got:\n" out))
          (is (not (str/blank? out))
              (str label " said nothing at all — indistinguishable from an empty hand")))))))

(def ^:private unstarted-lobby-state
  "Guest-panel CRITICAL: a lobby that has not STARTED has no board by
   definition, and that is healthy — `sync-verdict!` deliberately calls it
   :synced so reset.sh's create → join → start path is not gated
   (ai_connection_test.clj: test-sync-verdict-unstarted-lobby-is-synced).
   Seated + boardless is therefore NOT enough to mean 'a resync cleared the
   cache'; the :started flag is the discriminator, exactly as
   `boardless-started-game?` uses it."
  {:connected true :uid "test-user" :gameid "abc" :side "corp" :game-state nil
   :lobby-state {:gameid "abc" :started false}})

(def ^:private diff-vector-board-state
  "Guest-panel CRITICAL: `apply-diff` returns the RAW diff when :last-state is
   nil, so a :game/diff that lands between `clear-game-state!` and the
   replacement full state leaves :game-state holding a `[alterations removals]`
   VECTOR. It is truthy but it is not a board, so a truthiness gate reopens and
   the surfaces print `Credits: nil` again."
  {:connected true :uid "test-user" :gameid "abc" :side "corp"
   :game-state [{:corp {:credit 5}} {}]})

(deftest test-unstarted-lobby-is-not-called-a-failed-resync
  (testing "a seat waiting in an unstarted lobby is not told its cache was cleared"
    (with-mock-state unstarted-lobby-state
      (let [out (with-out-str (display/show-credits))]
        (is (not (re-find #"(?i)resync cleared|cleared the cache" out))
            (str "nothing was cleared — the game has not started, got:\n" out))
        (is (re-find #"(?i)not started|hasn't started|has not started|waiting to start" out)
            (str "must name the real state: the game has not begun, got:\n" out))
        (is (not (re-find #"(?i)never joined" out))
            (str "this client is seated in the lobby, got:\n" out))))))

(deftest test-a-raw-diff-vector-is-not-a-board
  (testing "a truthy-but-not-a-map :game-state must not be treated as a board"
    (with-mock-state diff-vector-board-state
      (let [out (with-out-str (display/show-credits))]
        (is (not (re-find #"(?i):\s*nil" out))
            (str "the vector is not a board — must not read a nil out of it, got:\n" out))
        (is (re-find #"(?i)no board" out)
            (str "must report the board as missing, got:\n" out))))))

(deftest test-boardless-seat-is-not-described-as-never-joined
  ;; The :else arm of no-side-here! says "Never joined, or the lobby was left".
  ;; That is false for a client still holding a :gameid — it joined, and the
  ;; board is what went missing. Wrong diagnosis sends the seat to reset.sh,
  ;; which destroys a game a retry might still have recovered.
  (testing "#139: a seated client is not told it never joined"
    (with-mock-state boardless-seat-state
      (let [out (with-out-str (display/show-credits))]
        (is (not (re-find #"(?i)never joined" out))
            (str "the client holds a :gameid, got:\n" out))
        (is (re-find #"(?i)retry|resync|status" out)
            (str "must name a recovery that fits a transient empty board, got:\n" out))))))

(deftest test-boardless-seat-specific-lies
  ;; The exact fabrications observed live (#139 table), one assertion each, so
  ;; a regression names the surface that lied.
  (testing "show-blocker-diagnosis must not steer a boardless seat into `wait` (a hang on a game that isn't there)"
    (with-mock-state boardless-seat-state
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        (is (not (re-find #"(?i)use:\s*wait|→ wait" out))
            (str "the diagnosis command must not send a stuck seat to wait on a missing board, got:\n" out))
        (is (not (re-find #"(?i)waiting for unknown" out))
            (str "'Waiting for unknown to start their turn' was the live fabrication, got:\n" out)))))
  (testing "status-compact must not claim a game phase"
    (with-mock-state boardless-seat-state
      (let [out (with-out-str (display/status-compact))]
        (is (not (re-find #"(?i)awaiting-start|Tnull|Me\(C\)" out))
            (str "'awaiting-start' is a confident phase claim about a board that isn't there, got:\n" out)))))
  (testing "archives/heap must not assert '0 cards'"
    (with-mock-state boardless-seat-state
      (let [out (str (with-out-str (display/show-archives)) (with-out-str (display/show-heap)))]
        (is (not (re-find #"(?i)Faceup: 0|Total: 0 cards" out))
            (str "'0 cards' is an assertion, not 'unknown', got:\n" out)))))
  (testing "snapshot tells ONE story: no board fragments, no self-contradiction"
    (with-mock-state boardless-seat-state
      (let [out (with-out-str (display/show-snapshot))]
        (is (not (re-find #"(?i)awaiting-start|Me\(C\)|Corp:|Faceup" out))
            (str "snapshot must not print compact status/board fragments for a board it doesn't have, got:\n" out))
        (is (= 1 (count (re-seq #"(?i)NO BOARD" out)))
            (str "exactly one explainer — the partial fix made snapshot describe AND deny a board in one response, got:\n" out))
        (is (re-find #"cursor=" out)
            (str "the cursor line is still the one thing a seat can act on, got:\n" out))))))

(deftest test-status-boardless-started-game-does-not-say-reset
  ;; Issue comment: `status` printed "Not in a game → ./dev/reset.sh" to a
  ;; client holding a :gameid. reset.sh would destroy a game a retry might have
  ;; recovered. Two boardless states share this fixture shape; the vector one
  ;; is the raw-diff case from #142.
  (doseq [[label st] [["nil board" boardless-seat-state]
                      ["raw-diff vector board" diff-vector-board-state]]]
    (testing (str "status, " label ": seated in a started game with no board → retry/resync, never reset.sh")
      (with-mock-state st
        (let [out (with-out-str (display/show-status))]
          (is (not (re-find #"reset\.sh" out))
              (str label ": reset.sh destroys the game this client is still seated in, got:\n" out))
          (is (not (re-find #"(?i)not in a game" out))
              (str label ": the client holds a :gameid — it IS in a game, got:\n" out))
          (is (re-find #"(?i)no board|board.*(gone|cleared)" out)
              (str label ": must name the real state, got:\n" out))
          (is (re-find #"(?i)resync|retry" out)
              (str label ": must name the recovery that fits, got:\n" out))))))
  (testing "status with no gameid at all still says not in a game (the original branch)"
    (with-mock-state {:connected true :uid "test-user" :gameid nil :side nil :game-state nil}
      (is (re-find #"(?i)not in a game" (with-out-str (display/show-status)))))))

(def ^:private started-lobby-no-board-yet
  "Guest panel (pass 1 of the #139 remainder): the server sends the started
   :lobby/state BEFORE :game/start, so a game that has JUST started holds
   :lobby-state {:started true} and no board for a moment. That is healthy
   startup, not a failed resync — the explainer must not diagnose it as one."
  {:connected true :uid "test-user" :gameid "abc" :side "corp" :game-state nil
   :lobby-state {:gameid "abc" :started true}})

(deftest test-no-board-explainer-covers-startup-and-names-the-resync-id
  (testing "just-started window: the explainer names BOTH possibilities and the retry, not a confident 'resync cleared the cache'"
    (with-mock-state started-lobby-no-board-yet
      (let [out (with-out-str (display/show-credits))]
        (is (re-find #"(?i)still arriving|just started" out)
            (str "must allow for the first full state being in flight, got:\n" out))
        (is (re-find #"(?i)resync cleared|cleared the cache|replacement state has not arrived" out)
            (str "…AND still name the failed-resync reading — 'both states' is the contract (third pass), got:\n" out))
        (is (re-find #"(?i)retry" out)
            (str "retry is the move in both states, got:\n" out))
        (is (not (re-find #"(?i)never joined|reset" out))))))
  (testing "`resync` takes the game id — the advertised recovery must be runnable as printed"
    (with-mock-state boardless-seat-state
      (is (re-find #"resync abc" (with-out-str (display/show-credits)))
          "the explainer must print the id it holds, not a bare 'resync' that fails with usage help")
      (is (re-find #"resync abc" (with-out-str (display/show-status)))
          "status too"))))

(deftest test-snapshot-in-an-unstarted-lobby-tells-one-story
  ;; Second pass: the unstarted-lobby path of snapshot called show-board-compact*
  ;; directly, bypassing its guard — an empty rig beside the hand's "NOT STARTED".
  (testing "unstarted lobby: lobby line + the not-started explainer + cursor, no board fragments"
    (with-mock-state unstarted-lobby-state
      (let [out (with-out-str (display/show-snapshot))]
        (is (re-find #"Lobby:" out) (str "the lobby line IS the status here, got:\n" out))
        (is (re-find #"(?i)not started" out) (str "one story: the game has not begun, got:\n" out))
        (is (not (re-find #"(?i)Rig:|Corp:|Faceup" out))
            (str "no board/rig fragments for a board that does not exist, got:\n" out))
        (is (re-find #"cursor=" out))))))

(deftest test-status-started-lobby-no-board-yet-names-both-states-and-the-id
  ;; Second pass: :lobby-state non-nil bypassed the new boardless branch and hit
  ;; the old "Waiting for game state... Use 'resync <game-id>'" placeholder.
  (with-mock-state started-lobby-no-board-yet
    (let [out (with-out-str (display/show-status))]
      (is (re-find #"(?i)still arriving|just started" out)
          (str "must allow for the healthy just-started window, got:\n" out))
      (is (re-find #"resync abc" out)
          (str "must print the id it holds, not a '<game-id>' placeholder, got:\n" out))
      (is (not (re-find #"<game-id>" out)))
      (is (re-find #"(?i)no board" out)))))

(deftest test-prompt-detailed-renders-from-the-passed-state
  ;; Second pass: snapshot captured `cs` but show-prompt-detailed re-read the
  ;; atom — a resync mid-snapshot would print 'Not in a game' under a captured
  ;; board. The 1-arity must render from its argument, not the atom.
  (testing "atom holds no board; the passed state holds a board and a prompt → the prompt renders"
    (let [boarded {:connected true :uid "test-user" :gameid "abc" :side "corp"
                   :game-state {:active-player "corp" :turn 3
                                :corp {:click 2 :credit 5
                                       :prompt-state {:msg "Choose a server" :prompt-type "select"
                                                      :eid 77 :choices [] :selectable [1 2]}}
                                :runner {:click 0}}}]
      (with-mock-state boardless-seat-state
        (let [out (with-out-str (display/show-prompt-detailed boarded))]
          (is (re-find #"Choose a server" out)
              (str "must render the prompt from the argument, got:\n" out))
          (is (not (re-find #"(?i)no board|not in a game" out))
              (str "must not consult the (boardless) atom, got:\n" out))))))
  (testing "third pass: a STRING-cid selectable resolves against the passed board, not the live atom"
    ;; With the atom boardless, the 1-arity CID lookup found nothing and the
    ;; prompt renderer told the seat its one valid pick was 'hidden — ignore'.
    (let [boarded {:connected true :uid "test-user" :gameid "abc" :side "corp"
                   :game-state {:active-player "corp" :turn 3
                                :corp {:click 2 :credit 5
                                       :hand [{:cid "c1" :title "Hedge Fund" :type "Operation" :zone ["hand"] :side "Corp"}]
                                       :prompt-state {:msg "Choose a card to trash" :prompt-type "select"
                                                      :eid 78 :choices [] :selectable ["c1"]}}
                                :runner {:click 0}}}]
      (with-mock-state boardless-seat-state
        (let [out (with-out-str (display/show-prompt-detailed boarded))]
          (is (re-find #"Hedge Fund" out)
              (str "the selectable card must resolve from the passed board, got:\n" out))
          (is (not (re-find #"(?i)0 selectable|ignore them" out))
              (str "must not call the valid pick hidden, got:\n" out)))))))

(deftest test-board-surfaces-gate-on-the-board-not-the-side
  ;; A spectator has a board and NO side (no-side-here!'s own spectator branch
  ;; says "'board' / 'log' show the game you are watching"). Gating these
  ;; surfaces on a side would break exactly that promise.
  (let [spectator-with-board {:connected true :uid "watcher" :gameid "abc" :spectator true :side nil
                              :game-state {:corp {:servers {:hq {:ices [] :content []}} :discard []}
                                           :runner {:rig {} :discard []}}}]
    (testing "a spectator still gets the board"
      (with-mock-state spectator-with-board
        (let [out (with-out-str (display/show-board))]
          (is (re-find #"GAME BOARD" out) (str "spectators must keep the board view, got:\n" out))
          (is (not (re-find #"(?i)no board" out))))))
    (testing "a spectator still gets archives/heap"
      (with-mock-state spectator-with-board
        (is (re-find #"Archives" (with-out-str (display/show-archives))))
        (is (re-find #"Heap" (with-out-str (display/show-heap)))))))
  (testing "an UNSTARTED lobby: board surfaces say the game has not started, not that a cache was cleared"
    (with-mock-state unstarted-lobby-state
      (let [out (with-out-str (display/board-compact))]
        (is (re-find #"(?i)not started|has not started" out)
            (str "the lobby has not begun — that is the story, got:\n" out))
        (is (not (re-find #"(?i)cleared the cache|resync cleared" out)))))))

(deftest test-sideless-surfaces-agree-with-show-hand
  (testing "#125: show-hand was already guarded — the other surfaces must not
            invent a different story about the same state"
    (with-mock-state sideless-state
      (let [hand-out (with-out-str (display/show-hand))]
        (is (re-find #"(?i)not in a game" hand-out)
            (str "precondition: show-hand is the guarded sibling, got:\n" hand-out))))))

;; The two tests above pin the three surfaces #125 actually named. This one is
;; the reason the family should not come back: it walks the display surfaces and
;; asserts none throws on a sideless state, so a NEW surface that hand-rolls the
;; derivation fails here without anyone remembering to add a case.
;;
;; Guest-panel catch: the first cut swept every zero-arg public fn and excluded
;; action drivers by name. That is backwards on both counts — it would invoke a
;; future zero-arg action driver unless someone remembered to extend a denylist,
;; and it counted private-in-spirit helpers the CLI never exposes. The real
;; contract is dev/send_command's capture heuristic: only show-/list-/hand/
;; status/board names have their output captured, so those ARE the seat-facing
;; display surfaces. Selecting on it makes the sweep both safer (action drivers
;; like simple-corp-turn do not match) and honest about what it covers.

(def ^:private display-name-prefixes
  ["show-" "list-" "hand" "status" "board"])

(defn- cli-display-surfaces
  "Zero-arg public fns of ai-display whose output dev/send_command captures."
  []
  (->> (ns-publics 'ai-display)
       (filter (fn [[sym v]]
                 (and (some #(str/starts-with? (name sym) %) display-name-prefixes)
                      (some #(= 0 (count %)) (:arglists (meta v))))))
       (sort-by first)))

(deftest test-no-display-surface-throws-on-a-sideless-state
  (testing "#125: every CLI-captured ai-display surface survives :side nil"
    (let [surfaces (cli-display-surfaces)
          swept (set (map (comp name first) surfaces))]
      ;; The sweep is only worth anything if it provably covers the three
      ;; surfaces the issue named — a filter that silently emptied would
      ;; otherwise pass with flying colours.
      (doseq [required ["list-playables" "show-credits" "show-clicks" "show-hand"]]
        (is (contains? swept required)
            (str "the sweep must cover " required ", swept: " (sort swept))))
      (is (<= 15 (count surfaces))
          (str "sanity: the filter must not have collapsed, saw "
               (count surfaces) " surfaces"))
      (doseq [[label st] [["never joined" sideless-state]
                          ["after leaving (board still cached)" left-game-state]
                          ["spectating a live game" spectator-state]
                          ["watch requested, no board yet" pending-watch-state]]]
        (let [throwers (with-mock-state st
                         (doall
                          (for [[sym v] surfaces
                                :let [err (try (with-out-str (v)) nil
                                               (catch Throwable e
                                                 (str (.getSimpleName (class e)) ": "
                                                      (.getMessage e))))]
                                :when err]
                            (str sym " -> " err))))]
          (is (empty? throwers)
              (str "on a " label " state these surfaces throw a raw exception at a "
                   "model seat instead of explaining themselves:\n  "
                   (str/join "\n  " throwers))))))))

;; Guest-panel CRITICAL: `watch-game!` sets :gameid/:spectator and NEVER sets
;; :side, and `detect-side` cannot match a spectator's uid — so a client happily
;; watching a live game has a full board and a nil side. The first cut told it
;; "Not in a game ... or ./dev/reset.sh for a fresh game", which is both false
;; and destructive advice for the game it is watching.

(deftest test-spectator-is-not-told-it-is-out-of-a-game
  (testing "#125/panel: a sideless SPECTATOR has a board — the bail must not
            deny the game or offer reset.sh"
    (with-mock-state spectator-state
      (doseq [[label f] [["list-playables" display/list-playables]
                         ["show-credits"   display/show-credits]
                         ["show-clicks"    display/show-clicks]
                         ;; second-pass catch: `hand` is a CLI surface and kept
                         ;; its own bespoke false "not in a game yet" line
                         ["show-hand"      display/show-hand]]]
        (let [out (with-out-str (f))]
          (is (not (re-find #"(?i)not in a game" out))
              (str label " denies a game the client is actively watching:\n" out))
          (is (not (re-find #"(?i)reset\.sh" out))
              (str label " steers a spectator into destroying the watched game:\n" out))
          (is (re-find #"(?i)spectat" out)
              (str label " must name the actual state, got:\n" out))
          (is (re-find #"(?i)corp" out)
              (str label " should surface the perspective it is watching, got:\n" out)))))))

;; Second-pass panel, both confirmed against source:
;;   - `leave-lobby!` (ai_connection.clj:76) leaves :game-state cached, so the
;;     ordinary post-teardown client landed in the "In a game, but no seat" branch
;;     and was told to `resync` a game it had deliberately left.
;;   - `watch-game!` (ai_connection.clj:200) sets :spectator before the server
;;     confirms, so the flag alone must not promise a board.

(deftest test-post-leave-is-not-mistaken-for-a-live-seatless-game
  (testing "#125/panel2: a cached board after leaving is not evidence of a game"
    (with-mock-state left-game-state
      (doseq [[label f] [["list-playables" display/list-playables]
                         ["show-credits"   display/show-credits]
                         ["show-clicks"    display/show-clicks]
                         ["show-hand"      display/show-hand]]]
        (let [out (with-out-str (f))]
          (is (not (re-find #"(?i)resync" out))
              (str label " tells a client that LEFT to resync:\n" out))
          (is (not (re-find #"(?i)no seat identified" out))
              (str label " calls a left game a seat-identification problem:\n" out))
          (is (re-find #"(?i)not in a game" out)
              (str label " must say plainly there is no game, got:\n" out))
          (is (re-find #"(?i)stale" out)
              (str label " must warn the cached board is stale, got:\n" out)))))))

(deftest test-unconfirmed-watch-does-not-promise-a-board
  (testing "#125/panel2: :spectator is set before the server confirms, so it
            alone must not claim a game is being watched"
    (with-mock-state pending-watch-state
      (let [out (with-out-str (display/list-playables))]
        (is (not (re-find #"(?i)show the game you are watching" out))
            (str "promises a board that never arrived:\n" out))
        (is (re-find #"(?i)no board" out)
            (str "must name the missing board, got:\n" out))
        (is (not (re-find #"(?i)reset\.sh" out))
            (str "a pending watch is not a reason to nuke a game:\n" out))))))

;; ---------------------------------------------------------------------------
;; #132 — the basic-action block must name actions the SIDE can actually take,
;; using the verbs the CLI actually parses. These strings are not prose: a seat
;; reads them and types them verbatim, so a wrong side gate is a wasted click
;; and a wrong verb is an Unknown command.
;; ---------------------------------------------------------------------------

(defn- basic-actions-out [side]
  (with-mock-state (mock-client-state
                    :side side
                    :game-state {:active-player side :turn 5
                                 :corp {:click 3 :credit 5 :hand []}
                                 :runner {:click 4 :credit 5 :hand [] :rig {}}})
    (with-out-str (display/list-playables))))

(deftest test-list-playables-basic-actions-are-side-correct
  (testing "Corp is NOT offered run — the CLI refuses it (Only Runner can run on servers)"
    (let [out (basic-actions-out "corp")]
      (is (not (str/includes? out "run <server>"))
          (str "run is Runner-only, got: " out))))

  (testing "Runner IS offered run"
    (is (str/includes? (basic-actions-out "runner") "run <server>")))

  (testing "BOTH sides are offered draw — it is a basic action for each"
    ;; The Runner half is the bug: draw sat inside a (when (= side :corp) ...)
    ;; alongside purge, so the Runner was never told it could draw at all.
    (is (str/includes? (basic-actions-out "corp") "draw"))
    (is (str/includes? (basic-actions-out "runner") "draw")))

  (testing "the printed verb is `draw`, not the non-existent `draw-card`"
    ;; ./dev/send_command corp draw-card => "Unknown command: draw-card", and the
    ;; did-you-mean list does not even contain `draw`.
    (is (not (str/includes? (basic-actions-out "corp") "draw-card")))
    (is (not (str/includes? (basic-actions-out "runner") "draw-card"))))

  (testing "purge stays Corp-only — that gate was the correct one"
    (is (str/includes? (basic-actions-out "corp") "purge"))
    (is (not (str/includes? (basic-actions-out "runner") "purge")))))

;; ---------------------------------------------------------------------------
;; The Total line must BE the list, not a second claim about it (review MAJOR).
;;
;; It was a hardcoded literal — "4" for Corp, "2" for Runner — correct until the
;; #132 re-gating above changed what actually gets printed, at which point Corp
;; printed 3 and claimed 4 and Runner printed 3 and claimed 2. A seat doesn't
;; read the block as prose; the total is the line it trusts to know whether it
;; has seen everything.
;;
;; These assertions compare the total against the PRINTED lines rather than
;; against an expected number, so they keep holding when the set of basic
;; actions legitimately changes — the failure mode is drift, not any one count.
;; ---------------------------------------------------------------------------

(defn- offered-basic-actions
  "The basic-action lines actually printed, minus the informational
   UNAVAILABLE ones (those are not offers)."
  [out]
  (->> (str/split-lines out)
       (drop-while #(not (str/includes? % "Basic Actions:")))
       rest
       (take-while #(str/starts-with? % "  - "))
       (remove #(str/includes? % "UNAVAILABLE"))))

(defn- reported-basic-total [out]
  (some->> (re-find #"Total: \d+ playable cards, \d+ playable abilities, (\d+) basic actions" out)
           second
           Integer/parseInt))

(deftest test-list-playables-basic-action-total-matches-what-was-printed
  (doseq [side ["corp" "runner"]]
    (testing (str side ": the total counts the lines the seat can actually see")
      (let [out (basic-actions-out side)]
        (is (pos? (count (offered-basic-actions out)))
            (str "fixture printed no basic actions at all, got:\n" out))
        (is (= (count (offered-basic-actions out)) (reported-basic-total out))
            (str side " printed " (count (offered-basic-actions out))
                 " basic actions and reported " (reported-basic-total out)
                 ":\n" out))))))

;; ---------------------------------------------------------------------------
;; draw must be gated on the deck (review MAJOR). game/cards/basic.clj carries
;; :req (req (not-empty (:deck corp))) — basic.clj:42 Corp, :165 Runner — so
;; offering draw at 0 cards names an action the engine refuses, in precisely the
;; endgame state where a wasted command costs most.
;; ---------------------------------------------------------------------------

(defn- basic-actions-out-with-deck [side deck-count]
  (with-mock-state (mock-client-state
                    :side side
                    :game-state {:active-player side :turn 5
                                 :corp {:click 3 :credit 5 :hand [] :deck-count deck-count}
                                 :runner {:click 4 :credit 5 :hand [] :rig {}
                                          :deck-count deck-count}})
    (with-out-str (display/list-playables))))

(deftest test-list-playables-does-not-offer-draw-on-an-empty-deck
  (doseq [side ["corp" "runner"]]
    (testing (str side ": an empty deck is not a draw")
      (let [out (basic-actions-out-with-deck side 0)]
        (is (not-any? #(re-find #"^  - draw \(" %) (offered-basic-actions out))
            (str side " offered draw with an empty deck:\n" out))
        (is (str/includes? out "UNAVAILABLE")
            (str side " should say why draw is missing rather than silently dropping it:\n" out))
        (is (= (count (offered-basic-actions out)) (reported-basic-total out))
            (str side ": total must not count the unavailable draw:\n" out)))))

  (testing "a non-empty deck still offers draw"
    (doseq [side ["corp" "runner"]]
      (let [out (basic-actions-out-with-deck side 20)]
        (is (some #(re-find #"^  - draw \(" %) (offered-basic-actions out))
            (str side " must still offer draw with cards left:\n" out))
        (is (= (count (offered-basic-actions out)) (reported-basic-total out))))))

  (testing "an ABSENT deck-count is unknown, not empty — still offered"
    ;; Every previous fixture omits :deck-count; treating that as 0 would have
    ;; silently withdrawn draw from every one of them.
    (doseq [side ["corp" "runner"]]
      (is (some #(re-find #"^  - draw \(" %) (offered-basic-actions (basic-actions-out side)))
          (str side " must offer draw when deck-count is unknown")))))

;; Per-credit payment prompts (#110 §2, corroborating #104)
;;
;; "Choose a credit providing card (0 of 5 [Credits])" re-asks once per credit.
;; The count in the message was the ONLY signal that four more calls were
;; coming, and nothing named the one-call form — so a Fable seat spent 5 calls
;; on Overclock and a Luna seat 2 on Unity, each reporting it as friction.
;; ============================================================================

(defn- payment-prompt-output [msg]
  (with-mock-state
    (mock-client-state
     :side "runner"
     :game-state {:active-player "runner" :turn 5
                  :runner {:hand []
                           :prompt-state {:prompt-type "select"
                                          :eid "pay-1"
                                          :msg msg
                                          :selectable [{:cid "c1" :title "Overclock"}]}}
                  :corp {:hand []}})
    (with-out-str (display/show-prompt-detailed))))

(deftest show-prompt-detailed-explains-per-credit-payment
  (testing "the prompt states how many credits are still owed and names --all"
    (let [out (payment-prompt-output "Choose a credit providing card (0 of 5 [Credits])")]
      (is (str/includes? out "5 [Credits] still owed")
          (str "must say how many more picks are coming:\n" out))
      (is (str/includes? out "--all")
          (str "must name the one-call form:\n" out))
      ;; multi-choose selects several DIFFERENT cards; it is not the verb for
      ;; paying a cost out of one source, and steering there would waste a seat's
      ;; turn the same way the silence did.
      (is (not (str/includes? out "multi-choose"))
          (str "must not steer a payment to the multi-select verb:\n" out))))

  (testing "a partially-paid prompt counts the REMAINDER, not the target"
    (let [out (payment-prompt-output "Choose a credit providing card (3 of 5 [Credits])")]
      (is (str/includes? out "2 [Credits] still owed")
          (str "owed is target minus paid:\n" out))))

  (testing "an ordinary select is untouched — payment advice must not leak"
    (let [out (payment-prompt-output "Choose a card to trash")]
      (is (str/includes? out "Selectable cards")
          (str "normal prompts keep the plain header:\n" out))
      (is (not (str/includes? out "--all"))
          (str "--all does nothing here and must not be advertised:\n" out))
      (is (not (str/includes? out "still owed"))
          (str "no payment line on a non-payment prompt:\n" out)))))
