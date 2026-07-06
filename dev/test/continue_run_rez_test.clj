(ns continue-run-rez-test
  "Tests for continue-run rez decision detection.

   These tests focus on the CRITICAL bug: continue-run must pause when corp
   has a rez decision, even when called from runner's side (where runner has
   no prompt and is just waiting)."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-actions :as ai]
            [ai-runs :as runs]
            [ai-core :as core]
            [ai-basic-actions :as basic]
            [ai-run-runner-handlers :as runner-handlers]
            [ai-run-corp-handlers :as corp-handlers]
            [ai-websocket-client-v2 :as ws]))

;; =============================================================================
;; Test: Waiting for Opponent's Rez Decision
;; =============================================================================

(deftest test-runner-waits-for-corp-rez-decision
  (testing "Runner calls continue-run while corp has rez decision - must pause (not auto-continue)"
    (with-mock-state
      (mock-client-state
       :side "runner"
       :prompt nil  ; Runner has NO prompt - just waiting
       :game-state
       {:run {:phase "approach-ice"
              :position 1  ; approaching outermost ICE (1 = first ICE, 0 = at server)
              :server [:hq]}
        :runner {:prompt-state nil}  ; No runner prompt
        :corp {:prompt-state {:msg "Rez Tithe?"
                             :prompt-type "run"
                             :choices []  ; Empty choices = paid ability window
                             :selectable [{:cid 123 :title "Tithe" :rezzed false}]}
               :servers {:hq {:ices [{:cid 123 :title "Tithe" :rezzed false}]}}}})

      ;; This is the BUG: current code will auto-continue through this
      ;; Expected: detect corp has rez opportunity and PAUSE
      (let [result (ai/continue-run!)]
        (is (= :waiting-for-corp-rez (:status result))
            "Should pause when waiting for corp rez decision")
        (is (= "Waiting for corp to decide: rez Tithe or continue" (:message result))
            "Should show what we're waiting for")))))

(deftest test-corp-has-rez-opportunity-at-approach
  (testing "Detect when corp has rez opportunity at approach-ice phase"
    (let [state (mock-client-state
                 :side "corp"
                 :game-state
                 {:run {:phase "approach-ice"
                        :position 1  ; approaching outermost ICE
                        :server [:hq]}
                  :corp {:prompt-state {:msg "Rez Tithe?"
                                       :prompt-type "run"
                                       :choices []
                                       :selectable [{:cid 123 :title "Tithe" :rezzed false}]}
                         :servers {:hq {:ices [{:cid 123 :title "Tithe" :rezzed false}]}}}})]

      (is (ai/corp-has-rez-opportunity? state)
          "Should detect corp has rez opportunity at approach-ice"))))

(deftest test-no-rez-opportunity-when-ice-rezzed
  (testing "No rez opportunity when ICE already rezzed"
    (let [state (mock-client-state
                 :side "corp"
                 :game-state
                 {:run {:phase "approach-ice"
                        :position 0
                        :server [:hq]}
                  :corp {:prompt-state {:msg "Paid ability window"
                                       :prompt-type "run"
                                       :choices []}
                         :servers {:hq {:ices [{:cid 123 :title "Tithe" :rezzed true}]}}}})]

      (is (not (ai/corp-has-rez-opportunity? state))
          "Should NOT detect rez opportunity when ICE already rezzed"))))

(deftest test-no-rez-opportunity-wrong-phase
  (testing "No rez opportunity when not in approach-ice phase"
    (let [state (mock-client-state
                 :side "corp"
                 :game-state
                 {:run {:phase "encounter-ice"  ; Wrong phase
                        :position 0
                        :server [:hq]}
                  :corp {:prompt-state {:msg "Rez Tithe?"
                                       :prompt-type "run"
                                       :choices []
                                       :selectable [{:cid 123 :title "Tithe" :rezzed false}]}
                         :servers {:hq {:ices [{:cid 123 :title "Tithe" :rezzed false}]}}}})]

      (is (not (ai/corp-has-rez-opportunity? state))
          "Should NOT detect rez opportunity in encounter-ice phase"))))

;; =============================================================================
;; Test: Information Leakage Prevention
;; =============================================================================

(deftest test-must-pause-even-if-cant-afford
  (testing "Must pause at rez decision even if corp can't afford to rez - prevent info leakage"
    (let [state (mock-client-state
                 :side "corp"
                 :credits 0  ; Can't afford to rez
                 :game-state
                 {:run {:phase "approach-ice"
                        :position 1  ; approaching outermost ICE
                        :server [:rd]}
                  :corp {:credit 0  ; Broke
                         :prompt-state {:msg "Rez Archer?"
                                       :prompt-type "run"
                                       :choices []
                                       :selectable [{:cid 456 :title "Archer" :cost 4 :rezzed false}]}
                         :servers {:rd {:ices [{:cid 456 :title "Archer" :cost 4 :rezzed false}]}}}})]

      ;; CRITICAL: Must still pause even though corp can't afford
      ;; Otherwise runner learns "corp is broke" for free
      (is (ai/corp-has-rez-opportunity? state)
          "Must detect rez opportunity even when can't afford (info leakage prevention)"))))

;; =============================================================================
;; Test: Rez Event Detection
;; =============================================================================

(deftest test-detect-rez-in-log
  (testing "Detect when ICE was rezzed from game log"
    (let [log-entries [{:text "Runner runs HQ"}
                       {:text "Corp rezzes Tithe"}  ; <-- This is the rez event
                       {:text "Approaching Tithe"}]
          rez-event (ai/get-rez-event log-entries)]

      (is (some? rez-event)
          "Should find rez event in log")
      (is (= "Corp rezzes Tithe" (:text rez-event))
          "Should return the rez event entry"))))

(deftest test-no-rez-event-in-empty-log
  (testing "No rez event when log has no rez entries"
    (let [log-entries [{:text "Runner runs HQ"}
                       {:text "Approaching ICE"}]
          rez-event (ai/get-rez-event log-entries)]

      (is (nil? rez-event)
          "Should return nil when no rez event"))))

;; =============================================================================
;; Test: extract-run-events reads the NEWEST log entries (issue #52)
;; =============================================================================
;;
;; `log` is chronological. The old code did `(take 3 log)` — the three OLDEST
;; (game-start) entries — so any rez/ability/subs/tag event in the *recent* log
;; was never seen from continue-run!'s handler context. These lock down that the
;; window is the newest entries and is nil-:text safe.

(deftest test-extract-run-events-reads-newest-not-oldest
  (testing "A rez landing in the NEWEST log entries surfaces, despite older noise (#52)"
    (let [log [{:text "Corp starts their turn"}       ; oldest — the old (take 3) window
               {:text "Runner draws a card"}
               {:text "Runner spends [Click] to run HQ"}
               {:text "Approaching Tithe"}
               {:text "Corp rezzes Tithe"}]            ; newest — the actual event
          events (runs/extract-run-events log)]
      (is (some? (:rez-event events))
          "Rez in the newest entries must be detected (was missed when window took oldest 3)")
      (is (= "Corp rezzes Tithe" (:text (:rez-event events)))))))

(deftest test-extract-run-events-detects-ability-fired-tag
  (testing "Ability / subs-fired / tag-damage events are all read from the newest window"
    (let [ability (runs/extract-run-events
                   [{:text "old"} {:text "old"} {:text "old"}
                    {:text "Corp uses Rototurret to trash a program"}])
          ;; Real engine wording from resolve-unbroken-subs! (game/core/ice.clj):
          ;; "resolves N unbroken subroutine on <ice> (...)" — the old matcher
          ;; keyed on "fire", which the engine NEVER logs, so it caught this
          ;; never (#54 false-negative).
          fired   (runs/extract-run-events
                   [{:text "old"} {:text "old"} {:text "old"}
                    {:text "Corp resolves 2 unbroken subroutines on Enigma (\"[subroutine] End the run\" and \"[subroutine] End the run\")"}])
          tag     (runs/extract-run-events
                   [{:text "old"} {:text "old"} {:text "old"}
                    {:text "Runner takes 1 tag"}])]
      (is (some? (:ability-event ability)))
      (is (some? (:fired-event fired)))
      (is (some? (:tag-damage-event tag))))))

;; =============================================================================
;; Test: classifiers reject false positives (issue #54)
;; =============================================================================
;;
;; #52 made the window live, which turned naive substring matching
;; (includes? "rez"/"fire"/"tag"/"damage") into a false-positive hazard: card
;; names and flavor text share those substrings. These pin the word-boundaried,
;; engine-wording-anchored matchers against the concrete traps found in the
;; engine card/log source.

(deftest test-extract-run-events-rejects-false-positives
  (testing "'derez' is not a rez event (word-boundaried, excludes de-rez)"
    (let [events (runs/extract-run-events
                  [{:text "old"} {:text "old"}
                   {:text "Corp uses Chief Slee to derez Ice Wall"}])]
      (is (nil? (:rez-event events))
          "'derez' must not count as a rez — old includes? \"rez\" matched it")))

  (testing "A Runner BREAKING subs is not a subs-fired event"
    ;; break-subroutines-msg logs "use Corroder to break 1 subroutine on X",
    ;; which also contains 'subroutine' — but breaking PREVENTS firing, so it
    ;; must not surface as :fired-event. Anchoring on 'unbroken subroutine'
    ;; distinguishes the two.
    (let [events (runs/extract-run-events
                  [{:text "old"} {:text "old"}
                   {:text "Runner uses Corroder to break 1 subroutine on Ice Wall (\"[subroutine] End the run\")"}])]
      (is (nil? (:fired-event events))
          "Breaking subroutines is the opposite of firing them")))

  (testing "Card name 'Foxfire' does not trip the subs-fired classifier"
    (let [events (runs/extract-run-events
                  [{:text "old"} {:text "old"}
                   {:text "Runner uses Foxfire to trash a card"}])]
      (is (nil? (:fired-event events))
          "'Foxfire' contains 'fire' but is a card name, not a fired sub")))

  (testing "Card name 'Donut Taganes' does not trip the tag classifier"
    (let [events (runs/extract-run-events
                  [{:text "old"} {:text "old"}
                   {:text "Runner installs Donut Taganes"}])]
      (is (nil? (:tag-damage-event events))
          "'Taganes' contains 'tag' as a substring but is not a tag event")))

  (testing "A negated/prevented damage line is not a damage event"
    ;; Real engine lines that mention 'damage' but deal none.
    (doseq [line ["Corp does not do core damage with Zed 1.0"
                  "Runner uses Feedback Filter to prevent 1 net damage"]]
      (let [events (runs/extract-run-events [{:text "old"} {:text "old"} {:text line}])]
        (is (nil? (:tag-damage-event events))
            (str "No damage was dealt: " line))))))

;; =============================================================================
;; Test: handle-events labels a fired subroutine as :subs-fired, not
;;        :ability-used, when both co-occur in the window (issue #54)
;; =============================================================================
;;
;; A firing subroutine and its own "uses <ice> to ..." effect line can both
;; land in the recent window. :subs-fired is the more specific headline, so
;; handle-events checks it before :ability-used. (All four event statuses pause
;; identically downstream, so this only affects the label — never behaviour.)

(deftest test-handle-events-fired-beats-ability
  (testing "Fired subroutine wins the label over a co-occurring ability line"
    (let [context {:rez-event nil
                   :ability-event {:text "Corp uses Ice Wall to end the run"}
                   :fired-event {:text "Corp resolves 1 unbroken subroutine on Ice Wall"}
                   :tag-damage-event nil}
          result (runs/handle-events context)]
      (is (= :subs-fired (:status result))
          "Subs-fired is more specific than the ability effect line it emits"))))

(deftest test-extract-run-events-tolerates-nil-text
  (testing "A log entry with nil :text does not NPE (defensive, matches sibling fns)"
    (let [log [{:text nil} {:foo :bar} {:text "Corp rezzes Ice Wall"}]]
      (is (= "Corp rezzes Ice Wall"
             (:text (:rez-event (runs/extract-run-events log))))
          "nil / missing :text entries are skipped, not fatal"))))

;; =============================================================================
;; Test: Integration - Full Run with Rez Decision
;; =============================================================================

(deftest test-bug-12-reproduction
  (testing "Bug #12: continue-run must pause at rez decision, not bypass ICE"
    (with-mock-state
      (mock-client-state
       :side "runner"
       :game-state
       {:run {:phase "approach-ice"
              :position 0
              :server [:hq]}
        :runner {:prompt-state nil}  ; Runner waiting
        :corp {:prompt-state {:msg "Rez Tithe?"
                             :prompt-type "run"
                             :choices []
                             :selectable [{:cid 123 :title "Tithe" :rezzed false}]}
               :servers {:hq {:ices [{:cid 123 :title "Tithe" :rezzed false}]
                             :hand [{:cid 789 :title "Nico Campaign"}]}}}}
       :log [])

      ;; The bug: this currently auto-continues through corp's rez decision
      ;; and accesses HQ without encountering Tithe
      (let [result (ai/continue-run!)]

        ;; Expected behavior: PAUSE at rez decision
        (is (not= :run-complete (:status result))
            "Should NOT complete run - must pause for corp rez decision")

        (is (or (= :waiting-for-opponent (:status result))
                (= :rez-decision-required (:status result)))
            "Should pause with waiting-for-opponent or rez-decision-required status")

        ;; Should NOT have accessed cards
        (is (nil? (:accessed result))
            "Should not have accessed any cards without corp making rez decision")))))

(deftest test-continue-after-corp-chooses-not-to-rez
  (testing "After corp chooses not to rez, continue-run proceeds past unrezzed ICE"
    (with-mock-state
      (mock-client-state
       :side "runner"
       :game-state
       {:run {:phase "movement"  ; Corp continued, now in movement phase
              :position 0
              :server [:hq]}
        :runner {:prompt-state {:msg "Paid ability window" :prompt-type "run" :choices []}}
        :corp {:prompt-state nil
               :servers {:hq {:ices [{:cid 123 :title "Tithe" :rezzed false}]  ; Still unrezzed
                             :hand [{:cid 789 :title "Hedge Fund"}]}}}}
       :log [{:text "Corp does not rez Tithe"}])

      ;; Now continue-run should proceed
      ;; We're in movement phase (past the unrezzed ice)
      ;; Should auto-continue through paid ability window
      (let [result (ai/continue-run!)]
        (is (= :action-taken (:status result))
            "Should auto-continue through paid ability window after corp declined to rez")))))

;; =============================================================================
;; Test: --rez that can't be afforded must not loop forever (polish 2026-06-22)
;;
;; A Corp with `--rez <ice>` whose effective rez cost it can't pay (e.g. a
;; Tread Lightly run adds +3 to every ICE) used to re-send the rez every monitor
;; iteration — the ICE never rezzes, state never changes, and the run wedged
;; until the stuck-detector tripped after 5 spins. handle-corp-rez-strategy now
;; marks the attempt (:rez-attempted-at) and, on the next pass with the ICE
;; still unrezzed, reports "can't afford" once and declines so the run proceeds.
;; =============================================================================

(defn- rez-strategy-ctx
  "Build a handler context for handle-corp-rez-strategy at approach-ice."
  [strategy]
  {:side "corp"
   :run-phase "approach-ice"
   :my-prompt {:msg "Rez Funhouse?" :prompt-type "run" :choices []}
   :strategy strategy
   :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
   :state {:game-state
           {:run {:phase "approach-ice" :position 1 :server [:hq]}
            :corp {:credit 7
                   :servers {:hq {:ices [{:cid 99 :title "Funhouse" :cost 5 :rezzed false}]}}}}}})

(deftest rez-strategy-first-attempt-sends-rez-and-marks-position
  (testing "first --rez pass sends the rez and reports the attempt position for tracking"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (let [result (corp-handlers/handle-corp-rez-strategy
                      (rez-strategy-ctx {:rez #{"Funhouse"}}))]
          (is (= :action-taken (:status result)))
          (is (= :auto-rezzed (:action result)))
          (is (= 1 (:rez-attempted-at result))
              "must report the position so the wrapper can persist the attempt")
          (is (some #(= "rez" (:command %)) @sent)
              "should send a rez command on the first attempt"))))))

(deftest rez-strategy-unaffordable-declines-instead-of-looping
  (testing "second pass with ICE still unrezzed declines (can't afford) rather than re-rezzing"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (let [out (with-out-str
                    (let [result (corp-handlers/handle-corp-rez-strategy
                                  ;; rez-attempted-at already == current position (1)
                                  (rez-strategy-ctx {:rez #{"Funhouse"} :rez-attempted-at 1}))]
                      (is (= :rez-failed-declined (:action result))
                          (str "expected a graceful decline, got: " result))))]
          (is (not-any? #(= "rez" (:command %)) @sent)
              "must NOT re-send a rez command when the prior attempt failed")
          (is (some #(= "continue" (:command %)) @sent)
              "should continue past the unrezzed ICE so the run proceeds")
          (is (re-find #"(?i)can't afford|did not take" out)
              (str "should explain why it declined, got: " out)))))))

;; =============================================================================
;; Test: --rez "X" must PAUSE on an unrezzed ICE that is NOT in the whitelist,
;; not silently auto-decline it (marquee g3 finding, forum [112]).
;;
;; On a multi-ICE server the Corp commits `--rez "Palisade"` (outer). When the
;; Runner reaches the inner unrezzed Tithe, the old :else branch sent `continue`
;; and returned :auto-declined-rez WITHOUT handing control back — so the Corp
;; never got to rez/fire its inner ICE. That violates the --persistent contract
;; ("returns to you for a real rez/fire decision"): a 2nd unrezzed ICE the Corp
;; hasn't spoken to IS a real decision. It now pauses (:decision-required) like
;; the no-strategy approach-ice path, so the inner ICE gets its own decision.
;; =============================================================================

(defn- rez-strategy-ctx-ice
  "Like rez-strategy-ctx but with a named, unrezzed ICE being approached."
  [strategy ice-title]
  {:side "corp"
   :run-phase "approach-ice"
   :my-prompt {:msg (str "Rez " ice-title "?") :prompt-type "run" :choices []}
   :strategy strategy
   :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000002")
   :state {:game-state
           {:run {:phase "approach-ice" :position 1 :server [:hq]}
            :corp {:credit 7
                   :servers {:hq {:ices [{:cid 77 :title ice-title :cost 1 :rezzed false}]}}}}}})

(deftest rez-strategy-unlisted-unrezzed-ice-pauses-for-decision
  (testing "an unrezzed ICE not in the --rez whitelist pauses (returns a rez decision) instead of silently auto-declining"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-out-str
          (let [r (corp-handlers/handle-corp-rez-strategy
                   ;; whitelist names Palisade; the run is approaching an unrezzed Tithe
                   (rez-strategy-ctx-ice {:rez #{"Palisade"}} "Tithe"))]
            (is (= :decision-required (:status r))
                (str "an unrezzed ICE outside the --rez list is a real rez decision; must pause, got: " r))
            (is (= "Tithe" (:ice r))
                "should name the ICE the Corp must decide on")))
        (is (not-any? #(= "continue" (:command %)) @sent)
            "must NOT silently pass priority — pausing returns control to the Corp to decide on this ICE")))))

(deftest rez-strategy-already-rezzed-unlisted-ice-still-continues
  (testing "an ALREADY-rezzed ICE outside the --rez list just continues (no decision needed)"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (let [ctx (-> (rez-strategy-ctx-ice {:rez #{"Palisade"}} "Tithe")
                      (assoc-in [:state :game-state :corp :servers :hq :ices 0 :rezzed] true))]
          (with-out-str
            (let [r (corp-handlers/handle-corp-rez-strategy ctx)]
              (is (= :action-taken (:status r))
                  (str "a rezzed unlisted ICE needs no decision; should continue, got: " r))))
          (is (some #(= "continue" (:command %)) @sent)
              "a rezzed ICE the Corp isn't rezzing should pass priority and proceed"))))))

;; =============================================================================
;; Test: monitor-run --persistent must NOT drop the defender loop after a rez
;; commit (#36, cross-model marquee g1).
;;
;; The --rez auto-rez returns :action-taken, but the NEXT iteration sees
;; "Corp rezzes X" in the log and handle-events returns :ice-rezzed. That
;; status is terminal-status? — correct for hand-driven monitor-run (pausing to
;; show the user IS the point), but in --persistent mode it dropped the loop
;; after the Corp's OWN rez, leaving the Runner holding priority at a window the
;; Corp was no longer watching (soft deadlock; the Runner could only recover by
;; jacking out, abandoning HQ pressure). The --persistent contract is "wakes
;; only for a real rez/fire/access decision or run end", so a notable event must
;; NOT terminate the persistent loop while the run is still active.
;; =============================================================================

(defn- scripted-continue-run
  "A continue-run! stand-in that pops one scripted result per call (clamping to
   the last once exhausted) and records the call count."
  [results call-count]
  (fn [& _]
    (let [i @call-count]
      (swap! call-count inc)
      (nth results i (last results)))))

(defn- run-active-corp-state []
  (mock-client-state
   :side "corp"
   :game-state {:run {:phase "approach-ice" :position 1 :server [:hq]}
                :corp {:prompt-state nil}
                :runner {:prompt-state nil}
                :log []}))

(deftest persistent-monitor-survives-own-rez-event
  (testing "--persistent rides through its own :ice-rezzed event and terminates only on run end (#36)"
    (let [calls (atom 0)
          ;; iteration 1: the rez event the loop's own auto-rez produced;
          ;; iteration 2: the run finishes. Persistent mode must ride through #1.
          script [{:status :ice-rezzed :wake-reason :ice-rezzed}
                  {:status :run-complete :wake-reason :run-complete}]]
      (with-mock-state (run-active-corp-state)
        (with-redefs [runs/continue-run! (scripted-continue-run script calls)
                      basic/check-auto-end-turn! (fn [] nil)
                      runner-handlers/reset-state! (fn [] nil)]
          (with-out-str
            (let [result (runs/auto-continue-loop! :persistent true)]
              (is (= :run-complete (:status result))
                  (str "persistent loop must ride through its own rez event to run end, got: " result))
              (is (>= @calls 2)
                  "loop must have continued past the :ice-rezzed iteration to reach run-complete"))))))))

(deftest hand-driven-monitor-still-pauses-on-rez-event
  (testing "without --persistent, an :ice-rezzed event is still terminal (hand-driven pause is the point)"
    (let [calls (atom 0)
          script [{:status :ice-rezzed :wake-reason :ice-rezzed}
                  {:status :run-complete :wake-reason :run-complete}]]
      (with-mock-state (run-active-corp-state)
        (with-redefs [runs/continue-run! (scripted-continue-run script calls)
                      basic/check-auto-end-turn! (fn [] nil)
                      runner-handlers/reset-state! (fn [] nil)]
          (with-out-str
            (let [result (runs/auto-continue-loop!)]   ; non-persistent
              (is (= :ice-rezzed (:status result))
                  (str "hand-driven monitor-run must still pause on a rez event, got: " result))
              (is (= 1 @calls)
                  "should stop after the first (rez-event) iteration"))))))))

(deftest persistent-monitor-still-pauses-on-real-decision
  (testing "--persistent still terminates on a genuine rez DECISION (not a should-pause-for-event? status)"
    (let [calls (atom 0)
          script [{:status :decision-required :wake-reason :rez-ice :ice "Tithe"}]]
      (with-mock-state (run-active-corp-state)
        (with-redefs [runs/continue-run! (scripted-continue-run script calls)
                      basic/check-auto-end-turn! (fn [] nil)
                      runner-handlers/reset-state! (fn [] nil)]
          (with-out-str
            (let [result (runs/auto-continue-loop! :persistent true)]
              (is (= :decision-required (:status result))
                  (str "a real rez decision must still wake the persistent seat, got: " result))
              (is (= 1 @calls)
                  "should return immediately on the decision"))))))))

;; Spin-safety (Codex review of #36): a degenerate event that NEVER advances —
;; continue-run! keeps returning :ice-rezzed with the run still active and the
;; log unchanged — must NOT livelock the persistent loop. Because the event
;; branch advances `iteration`, the max-iterations backstop bounds it. (In real
;; play this never happens — the run moves and the rez line scrolls out of the
;; 3-entry recent-log window, or the next status is :waiting-for-opponent — but
;; the bound must hold regardless.)
(deftest persistent-monitor-event-spin-is-bounded-by-max-iterations
  (testing "a never-advancing :ice-rezzed in --persistent terminates via the max-iterations backstop, not a livelock"
    (let [calls (atom 0)
          ;; Always :ice-rezzed, run never ends — the pathological case.
          always-rezzed (fn [& _] (swap! calls inc) {:status :ice-rezzed :wake-reason :ice-rezzed})]
      (with-mock-state (run-active-corp-state)
        (with-redefs [runs/continue-run! always-rezzed
                      basic/check-auto-end-turn! (fn [] nil)
                      runner-handlers/reset-state! (fn [] nil)
                      ;; keep the test fast: tiny delay, low ceiling
                      core/quick-delay 0]
          (with-out-str
            (let [result (runs/auto-continue-loop! :persistent true :max-iterations 5)]
              (is (= :max-iterations (:status result))
                  (str "an unending event must hit the max-iterations backstop, got: " result))
              (is (<= @calls 6)
                  (str "must be bounded near max-iterations, not spinning unbounded; calls=" @calls)))))))))

;; =============================================================================
;; Test: monitor-run --persistent must RETURN on this seat's own on-steal/on-score
;; agenda-trigger prompt, not sleep on it until the 300s timeout (#43, cross-model
;; marquee g1 terminal wedge).
;;
;; When the Runner steals an agenda with an on-steal Corp ability (e.g. Send a
;; Message: "you may rez a piece of ice, ignoring all costs"), the engine opens a
;; CORP `select` prompt. If all Corp ICE are already rezzed the prompt has no
;; selectable targets and only an implicit Done — so has-real-decision? is false
;; and handle-real-decision doesn't fire. handle-waiting-for-opponent then labels
;; it an opponent-wait via the :no-action heuristic (which ignores that the CORP
;; itself is holding the prompt). In --persistent mode the loop slept & rechecked
;; that "wait" forever, hitting the 300000ms timeout, while the Runner was hard-
;; blocked on "Waiting for Corp to resolve pending triggers" and eventually gave
;; up (game stranded at Corp 6 – Runner 5). The persistent loop must instead
;; surface such a seat-owned trigger prompt as a decision.
;; =============================================================================

(defn- corp-state-with-prompt
  "Run-active Corp mock state carrying a given Corp prompt-state."
  [corp-prompt]
  (mock-client-state
   :side "corp"
   :game-state {:run {:phase "access" :position 0 :server [:remote1]}
                :corp {:prompt-state corp-prompt}
                :runner {:prompt-state {:msg "Waiting for Corp to resolve pending triggers"
                                        :prompt-type "waiting"}}
                :log []}))

(deftest persistent-monitor-returns-on-corp-on-steal-trigger-prompt
  (testing "--persistent surfaces the Corp's own on-steal trigger select prompt as a decision instead of looping to timeout (#43)"
    (let [calls (atom 0)
          ;; continue-run! mislabels the trigger as an opponent wait
          ;; (handle-waiting-for-opponent via the :no-action heuristic).
          script [{:status :waiting-for-opponent :wake-reason :waiting-for-opponent}]]
      (with-mock-state (corp-state-with-prompt
                        {:msg "Choose a target for Send a Message"
                         :prompt-type "select"
                         :choices []
                         :selectable []})   ; no valid rez targets — all ICE already rezzed
        (with-redefs [runs/continue-run! (scripted-continue-run script calls)
                      basic/check-auto-end-turn! (fn [] nil)
                      runner-handlers/reset-state! (fn [] nil)
                      core/quick-delay 0]
          (with-out-str
            (let [result (runs/auto-continue-loop! :persistent true
                                                   :timeout-ms 2000
                                                   :persistent-wait-delay-ms 0)]
              (is (= :decision-required (:status result))
                  (str "persistent loop must return the Corp's own trigger as a decision, not timeout, got: " result))
              (is (= 1 @calls)
                  "should return on the first waiting status that reveals our pending trigger"))))))))

(deftest persistent-monitor-empty-wait-without-trigger-still-rides-through
  (testing "--persistent with NO seat-owned prompt still sleeps through an empty opponent wait to run end (no #43 false positive)"
    (let [calls (atom 0)
          script [{:status :waiting-for-opponent :wake-reason :waiting-for-opponent}
                  {:status :run-complete :wake-reason :run-complete}]]
      (with-mock-state (corp-state-with-prompt nil)   ; Corp holds no prompt
        (with-redefs [runs/continue-run! (scripted-continue-run script calls)
                      basic/check-auto-end-turn! (fn [] nil)
                      runner-handlers/reset-state! (fn [] nil)
                      core/quick-delay 0]
          (with-out-str
            (let [result (runs/auto-continue-loop! :persistent true
                                                   :persistent-wait-delay-ms 0)]
              (is (= :run-complete (:status result))
                  (str "an empty opponent wait with no seat prompt must ride through to run end, got: " result))
              (is (>= @calls 2)
                  "must recheck past the empty wait to reach run-complete"))))))))

(deftest persistent-monitor-normal-defender-wait-rides-through
  (testing "the normal Corp defender wait (passed priority mid-encounter, holding a 'run' window) is NOT a trigger decision — persistent rides through (no #36/#42 regression)"
    (let [calls (atom 0)
          script [{:status :waiting-for-opponent :wake-reason :waiting-for-opponent}
                  {:status :run-complete :wake-reason :run-complete}]]
      ;; Representative shape of "Corp passed, waiting for Runner to break ICE":
      ;; mid-encounter run, Corp already passed (:no-action corp), holding the
      ;; run paid-ability window (prompt-type "run"). This is exactly the window
      ;; the persistent loop must sleep through, not surface as a decision.
      (with-mock-state
        (mock-client-state
         :side "corp"
         :game-state {:run {:phase "encounter-ice" :position 1 :server [:hq] :no-action "corp"}
                      :corp {:prompt-state {:msg "Paid ability window" :prompt-type "run"
                                            :choices [] :selectable []}}
                      :runner {:prompt-state nil}
                      :log []})
        (with-redefs [runs/continue-run! (scripted-continue-run script calls)
                      basic/check-auto-end-turn! (fn [] nil)
                      runner-handlers/reset-state! (fn [] nil)
                      core/quick-delay 0]
          (with-out-str
            (let [result (runs/auto-continue-loop! :persistent true
                                                   :persistent-wait-delay-ms 0)]
              (is (= :run-complete (:status result))
                  (str "a normal defender wait must ride through to run end, not return a decision, got: " result))
              (is (>= @calls 2)
                  "must recheck past the empty defender wait to reach run-complete"))))))))

(deftest persistent-monitor-run-type-window-is-not-a-trigger-decision
  (testing "a Corp 'run'-type paid-ability window during an opponent wait is NOT a seat-owned trigger — rides through (no #43 false positive)"
    (let [calls (atom 0)
          script [{:status :waiting-for-opponent :wake-reason :waiting-for-opponent}
                  {:status :run-complete :wake-reason :run-complete}]]
      (with-mock-state (corp-state-with-prompt
                        {:msg "Paid ability window" :prompt-type "run" :choices [] :selectable []})
        (with-redefs [runs/continue-run! (scripted-continue-run script calls)
                      basic/check-auto-end-turn! (fn [] nil)
                      runner-handlers/reset-state! (fn [] nil)
                      core/quick-delay 0]
          (with-out-str
            (let [result (runs/auto-continue-loop! :persistent true
                                                   :persistent-wait-delay-ms 0)]
              (is (= :run-complete (:status result))
                  (str "a 'run'-type window must not be mistaken for a trigger decision, got: " result))
              (is (>= @calls 2)
                  "must recheck past the empty run-window wait to reach run-complete"))))))))
