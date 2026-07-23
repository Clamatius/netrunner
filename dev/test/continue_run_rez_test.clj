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
            [ai-state :as state]
            [ai-basic-actions :as basic]
            [ai-run-runner-handlers :as runner-handlers]
            [ai-run-corp-handlers :as corp-handlers]
            [ai-websocket-client-v2 :as ws]))

;; `handle-events` keeps a per-game memory of already-reported log entries
;; (#31), so it is no longer a pure function. Without this fixture the
;; event-labeling tests below pass on a fresh JVM and FAIL on a second run in
;; the same REPL, because their entries are already marked as reported.
(use-fixtures :each (fn [t] (runs/reset-reported-events!) (t)))

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
            (str "No damage was dealt: " line)))))

  (testing "The negation guard does NOT swallow a real ability whose effect is prevention"
    ;; "uses <card> to prevent/avoid ..." is a genuine ability activation — the
    ;; ability fired, so it must surface as :ability-event even though its text
    ;; contains 'prevent'. The guard is scoped to state-change events only
    ;; (Codex review of #54; EMP Device is a real run-gated ability).
    (let [events (runs/extract-run-events
                  [{:text "old"} {:text "old"}
                   {:text "Runner uses EMP Device to prevent the Corp from rezzing more than 1 piece of ice for the remainder of the run"}])]
      (is (some? (:ability-event events))
          "Ability activation must survive even when its effect text negates something")
      (is (nil? (:rez-event events))
          "...and 'rezzing' in an ability effect line is not itself a rez event")))

  (testing "A fired sub whose EMBEDDED label contains a negation word still fires"
    ;; The umbrella fired log embeds the subroutine labels (ice.clj), and real
    ;; ICE labels contain 'cannot' (e.g. Whirlpool: 'The Runner cannot jack
    ;; out...'). The sub really fired, so :fired-event must survive — the
    ;; negation guard is NOT applied to fired subs (Codex review of #54).
    (let [events (runs/extract-run-events
                  [{:text "old"} {:text "old"}
                   {:text "Corp resolves 1 unbroken subroutine on Whirlpool (\"[subroutine] The Runner cannot jack out for the remainder of this run\")"}])]
      (is (some? (:fired-event events))
          "A fired sub with 'cannot' in its embedded label must still surface"))))

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
;; Test: `continue` is refused at an unbroken-sub encounter — the invariant the
;; #92 display fix relies on. The prompt/diagnose surfaces now steer a Runner
;; with unbroken subs to break/tank/jack-out instead of `continue`; those hints
;; are only truthful if the seat's `continue` genuinely cannot pass here.
;;
;; This also FALSIFIES a plausible review finding (GPT-5.6 Sol, #92 panel) that
;; the decline hint is wrong once the Corp has passed the encounter: the AI
;; seat's refusal is decided by handle-runner-encounter-ice, which reads
;; RUN-level :no-action — and set-phase (engine runs.clj:98) resets that to false
;; on entering encounter-ice, while `continue :encounter-ice` (runs.clj:426) only
;; ever writes the ENCOUNTER-level passer. So corp-passed? is false throughout an
;; encounter and `continue` is refused regardless of the encounter-level passer.
;; The hint is correct in every state the seat can actually reach.
;; =============================================================================

(defn- runner-encounter-ctx-state
  "Runner mid-encounter on rezzed R&D ICE with `subs`. `encounter-passer` sets
   the ENCOUNTER-level passer ([:encounters :no-action]) — the field the engine
   actually tracks during an encounter (runs.clj:426). Run-level :no-action is
   held false, as the engine keeps it (set-phase resets it on phase entry)."
  [subs encounter-passer]
  {:connected true
   :uid "test-user"
   :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
   :side "runner"
   :game-state {:active-player "runner"
                :encounters {:no-action encounter-passer}
                :run {:phase "encounter-ice" :position 1
                      :server [:rd] :no-action false}
                :runner {:credit 5 :click 2
                         :prompt-state {:msg "You are encountering Whitespace"
                                        :prompt-type "run"}}
                :corp {:credit 5
                       :servers {:rd {:ices [{:cid 55 :title "Whitespace" :rezzed true
                                              :subroutines subs}]}}}}})

(def ^:private two-unbroken-subs
  [{:label "Make the Runner lose 3 [Credits]" :broken false :fired false}
   {:label "End the run if the Runner has 6 [Credits] or less" :broken false :fired false}])

(deftest encounter-with-unbroken-subs-refuses-continue
  (testing "at an encounter with unbroken subs, continue-run! surfaces a fire
            decision and sends NO continue — the #92 decline hint is truthful"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-mock-state (runner-encounter-ctx-state two-unbroken-subs nil)
          (with-out-str
            (let [r (ai/continue-run!)]
              (is (= :fire-decision-required (:status r))
                  (str "continue must be refused (a break/tank decision), got: " r))))
          (is (not-any? #(= "continue" (:command %)) @sent)
              "no continue may reach the engine while unbroken subs remain"))))))

(deftest encounter-refuses-continue-even-when-corp-passed-the-window
  (testing "FALSIFIES the corp-passed review finding (#92 panel): with the Corp
            recorded as the encounter passer, the AI seat STILL cannot pass with
            continue — handle-runner-encounter-ice gates on RUN-level :no-action
            (held false all encounter), so it refuses regardless of the
            encounter-level passer. The decline hint is correct in this state too."
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-mock-state (runner-encounter-ctx-state two-unbroken-subs "corp")
          (with-out-str
            (let [r (ai/continue-run!)]
              (is (= :fire-decision-required (:status r))
                  (str "continue must still be refused when Corp passed the encounter, got: " r))))
          (is (not-any? #(= "continue" (:command %)) @sent)
              "the seat must not silently pass an unbroken-sub encounter"))))))

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

;; =============================================================================
;; Test: --no-rez must DECLINE the pre-access server-upgrade window instead of
;; re-waking every iteration (#57, cross-model marquee g1, Opus Corp).
;;
;; At the pre-access "Server upgrade decision" window (an unrezzed Upgrade in the
;; attacked server, e.g. Manegarm Skunkworks), handle-corp-server-upgrade-decision
;; used to return :decision-required UNCONDITIONALLY — it never consulted the run
;; strategy. So an autonomous Corp seat running `monitor-run --persistent --no-rez`
;; (a standing "decline everything" commitment) got the identical window
;; re-presented every iteration; only a raw pass advanced the run — a wedge risk
;; in an un-babysat game. --no-rez must now fall through (nil) so the normal
;; empty-run-window auto-pass advances past the upgrade, exactly like an
;; approach-ice rez window under --no-rez. The default (no strategy) wake is
;; preserved so a meaningful pre-access rez is never silently skipped.
;; =============================================================================

(defn- server-upgrade-ctx
  "Handler context at a pre-access server-upgrade window (an unrezzed upgrade in
   the attacked remote), carrying the given run strategy."
  [strategy]
  {:side "corp"
   :strategy strategy
   :state {:game-state
           {:run {:phase "movement" :position 0 :server [:remote1]}
            :corp {:prompt-state {:msg "You may use paid abilities"
                                  :prompt-type "run" :choices [] :selectable []}
                   :servers {:remote1 {:content [{:cid 10 :title "Manegarm Skunkworks"
                                                  :type "Upgrade" :rezzed false}]}}}
            :runner {:prompt-state nil}
            :log []}}})

(deftest server-upgrade-window-wakes-by-default
  (testing "with no rez strategy, the pre-access upgrade window still wakes the seat (unchanged)"
    (with-out-str
      (let [r (corp-handlers/handle-corp-server-upgrade-decision (server-upgrade-ctx {}))]
        (is (= :decision-required (:status r))
            (str "an unrezzed pre-access upgrade must still wake by default, got: " r))
        (is (= "Manegarm Skunkworks" (:card r))
            "should name the upgrade the Corp must decide on")))))

(deftest server-upgrade-window-no-rez-declines-instead-of-looping
  (testing "--no-rez falls through (nil) so the normal auto-pass advances the run, not re-waking forever (#57)"
    (with-out-str
      (let [r (corp-handlers/handle-corp-server-upgrade-decision
               (server-upgrade-ctx {:no-rez true}))]
        (is (nil? r)
            (str "--no-rez is a standing decline; the upgrade handler must fall through "
                 "to the empty-window auto-pass, got: " r))))))

;; Chain-level lock (Codex review of #57): the handler-in-isolation tests above
;; prove it falls through, but the point of falling through is that the REST of
;; the continue-run! chain then does the right thing in both priority states.
;; These drive the full chain via ai/continue-run! "--no-rez" at the pre-access
;; upgrade window:
;;   (a) Runner has NOT yet passed (:no-action nil) → Corp must WAIT, not
;;       auto-pass into access ahead of the Runner's own pre-access window.
;;   (b) Runner HAS passed (:no-action "runner") → Corp auto-passes the empty
;;       window, advancing the run — the actual decline that was looping (#57).

(defn- upgrade-window-state
  "Runner running a remote holding an unrezzed upgrade, parked at the pre-access
   empty run window. :no-action controls who has already passed priority."
  [no-action]
  (mock-client-state
   :side "corp"
   :game-state
   {:run (cond-> {:phase "movement" :position 0 :server [:remote1]}
           no-action (assoc :no-action no-action))
    :corp {:prompt-state {:msg "You may use paid abilities"
                          :prompt-type "run" :choices [] :selectable []}
           :servers {:remote1 {:content [{:cid 10 :title "Manegarm Skunkworks"
                                          :type "Upgrade" :rezzed false}]}}}
    :runner {:prompt-state nil}
    :log []}))

(deftest no-rez-upgrade-chain-waits-before-runner-passes
  (testing "--no-rez at the upgrade window waits for the Runner (does not race ahead into access) when Runner hasn't passed (#57)"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-mock-state (upgrade-window-state nil)   ; fresh window, nobody passed
          (with-out-str
            (let [r (ai/continue-run! "--no-rez")]
              (is (not= :decision-required (:status r))
                  (str "must not re-present the upgrade decision under --no-rez, got: " r))
              (is (not-any? #(= "continue" (:command %)) @sent)
                  "must NOT pass Corp priority before the Runner has passed its own pre-access window"))))))))

(deftest no-rez-upgrade-chain-auto-passes-after-runner-passes
  (testing "--no-rez at the upgrade window auto-passes once the Runner has passed, advancing the run (#57)"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-mock-state (upgrade-window-state "runner")   ; Runner already passed
          (with-out-str
            (let [r (ai/continue-run! "--no-rez")]
              (is (= :action-taken (:status r))
                  (str "Corp must auto-pass the empty upgrade window once it holds priority, got: " r))
              (is (some #(= "continue" (:command %)) @sent)
                  "the decline is a real priority pass — a continue must reach the engine"))))))))

;; =============================================================================
;; Test: monitor-run --persistent wakes on an explicit opponent `ping` chat
;; nudge (#50 recovery net). `wait` (wait-for-relevant-diff) already wakes on a
;; ping; the persistent defender loop must too, so a seat parked mid-run at an
;; empty priority window the opponent believes is ours can be un-stalled by the
;; opponent pinging. The ping returns control (:ping) — it does NOT auto-advance
;; the run.
;; =============================================================================

(defn- continue-then-log
  "continue-run! stand-in: on its FIRST call appends `chat-line` to the game log
   (simulating an opponent chat message arriving during the wait) and reports an
   empty opponent-priority wait; subsequent calls report run-complete so a loop
   that does NOT wake on the message still terminates."
  [chat-line call-count]
  (fn [& _]
    (let [i @call-count]
      (swap! call-count inc)
      (if (zero? i)
        (do
          (swap! state/client-state update-in [:game-state :log]
                 (fnil conj []) {:text chat-line})
          {:status :waiting-for-opponent :wake-reason :waiting-for-opponent})
        {:status :run-complete :wake-reason :run-complete}))))

(deftest persistent-monitor-wakes-on-opponent-ping
  (testing "--persistent returns :ping when an opponent ping arrives during an empty priority-window wait (#50)"
    (let [calls (atom 0)]
      (with-mock-state (run-active-corp-state)
        (with-redefs [runs/continue-run! (continue-then-log "AI_Runner: ping" calls)
                      basic/check-auto-end-turn! (fn [] nil)
                      runner-handlers/reset-state! (fn [] nil)
                      core/quick-delay 0]
          (with-out-str
            (let [result (runs/auto-continue-loop! :persistent true
                                                   :persistent-wait-delay-ms 0)]
              (is (= :ping (:status result))
                  (str "an opponent ping during a persistent wait must wake the loop, got: " result))
              (is (= 1 @calls)
                  "must return on the ping without rechecking into another continue-run! call"))))))))

(deftest persistent-monitor-ignores-non-ping-chat
  (testing "--persistent does NOT wake on ordinary opponent chit-chat with no 'ping' token (no #50 false positive)"
    (let [calls (atom 0)]
      (with-mock-state (run-active-corp-state)
        (with-redefs [runs/continue-run! (continue-then-log "AI_Runner: good luck" calls)
                      basic/check-auto-end-turn! (fn [] nil)
                      runner-handlers/reset-state! (fn [] nil)
                      core/quick-delay 0]
          (with-out-str
            (let [result (runs/auto-continue-loop! :persistent true
                                                   :persistent-wait-delay-ms 0)]
              (is (= :run-complete (:status result))
                  (str "ordinary chat must be slept through to run end, not woken on, got: " result))
              (is (>= @calls 2)
                  "must recheck past the chat message to reach run-complete"))))))))

;; =============================================================================
;; Test: run-initiation both-pass window auto-advances (#31, step 1)
;; =============================================================================
;; The initiation window is a both-must-pass window with no run-start paid
;; ability either side uses in System Gateway. `run!` sends the Runner's first
;; continue by default, so the Runner passes (:no-action "runner"). The Corp
;; then becomes the ACTIVE player at an empty initiation window with NO prompt —
;; so `can-auto-continue?` (which requires a "run" prompt) never fires, no
;; handler matches, and continue-run! falls through to handle-unexpected-state,
;; returning a FALSE :waiting-for-opponent. Both seats then wait on each other:
;; the #31 initiation wedge. The fix auto-passes the initiation window whenever
;; the send_command seat is the active player (should-i-act?) with no real
;; decision — it only ever passes its OWN window, never acts for the opponent.
;; =============================================================================

(defn- initiation-state
  "A run parked at the initiation window. `no-action` controls who has already
   passed priority. Neither side has a run prompt (initiation surfaces none)."
  [side no-action]
  (mock-client-state
   :side side
   :game-state
   {:run (cond-> {:phase "initiation" :position 1 :server [:hq]}
           no-action (assoc :no-action no-action))
    :corp {:prompt-state nil}
    :runner {:prompt-state nil}
    :log []}))

(deftest initiation-corp-active-auto-passes
  (testing "Corp auto-passes the empty initiation window once it holds priority — the #31 wedge (Runner passed, Corp is active, no prompt)"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-mock-state (initiation-state "corp" "runner")   ; Runner already passed
          (with-out-str
            (let [r (ai/continue-run!)]
              (is (= :action-taken (:status r))
                  (str "Corp holds priority at an empty initiation window and must pass to advance, got: " r))
              (is (some #(= "continue" (:command %)) @sent)
                  "a real continue must reach the engine — this is the second pass that advances initiation"))))))))

(deftest initiation-runner-active-auto-passes
  (testing "Runner auto-passes a fresh initiation window (active player acts first, no prompt) — symmetric with the Corp path"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-mock-state (initiation-state "runner" nil)   ; fresh window, nobody passed
          (with-out-str
            (let [r (ai/continue-run!)]
              (is (= :action-taken (:status r))
                  (str "Runner is the active player at a fresh initiation window and passes its own window, got: " r))
              (is (some #(= "continue" (:command %)) @sent)
                  "the Runner's first pass must reach the engine"))))))))

(deftest initiation-already-passed-does-not-double-pass
  (testing "The seat that has ALREADY passed initiation does not pass again — guards against double-continue"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-mock-state (initiation-state "corp" "corp")   ; Corp already passed, waiting on Runner
          (with-out-str
            (let [r (ai/continue-run!)]
              (is (not= :action-taken (:status r))
                  (str "Corp already passed (no-action=corp); it must wait, not pass again, got: " r))
              (is (not-any? #(= "continue" (:command %)) @sent)
                  "no second continue from the side that already passed"))))))))

;; =============================================================================
;; Test: --rez "<upgrade>" auto-rezzes an approach-triggered upgrade at the
;; pre-approach-server window (issue #67).
;;
;; Manegarm Skunkworks fires "whenever the Runner approaches this server", which
;; the engine resolves at the movement/position-0 window (proven in
;; game.ai-upgrade-rez-timing-test). handle-corp-rez-strategy only auto-rezzes
;; ICE at approach-ice, so before this an autonomous Corp that committed
;; `--rez "Manegarm Skunkworks"` never actually rezzed it — the window just
;; surfaced and paused. The upgrade handler now honours the --rez list at the
;; pre-access window (with a cid-keyed wedge guard, since position is always 0
;; here so the ICE's position key cannot disambiguate a failed retry).
;; =============================================================================

(defn- upgrade-decision-ctx
  "Context for handle-corp-server-upgrade-decision at movement/pos-0 with an
   unrezzed upgrade in the attacked remote. Defaults to the realistic window where
   the Runner has already passed (:no-action \"runner\"), i.e. the Corp holds
   priority — the only state in which the Corp actually gets the empty-run-window
   prompt at movement/pos-0 (verified live). Pass :no-action to override."
  [strategy & {:keys [no-action] :or {no-action "runner"}}]
  {:side "corp"
   :run-phase "movement"
   :strategy strategy
   :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000003")
   :state {:game-state
           {:run {:phase "movement" :position 0 :server [:remote1] :no-action no-action}
            :corp {:prompt-state {:msg "You may use paid abilities"
                                  :prompt-type "run" :choices [] :selectable []}
                   :servers {:remote1 {:content [{:cid 77 :title "Manegarm Skunkworks"
                                                  :type "Upgrade" :rezzed false
                                                  :zone ["servers" "remote1" "content"]
                                                  :side "Corp"}]}}}
            :runner {:prompt-state nil}
            :log []}}})

(deftest upgrade-rez-strategy-auto-rezzes-listed-upgrade
  (testing "--rez <upgrade> sends a rez at the pre-access window and marks the cid"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-out-str
          (let [result (corp-handlers/handle-corp-server-upgrade-decision
                        (upgrade-decision-ctx {:rez #{"Manegarm Skunkworks"}}))]
            (is (= :action-taken (:status result)))
            (is (= :auto-rezzed-upgrade (:action result)))
            (is (= 77 (:upgrade-rez-attempted result))
                "must report the upgrade cid so the wrapper can persist the attempt")
            (is (some #(= "rez" (:command %)) @sent)
                "should send a rez command for the listed upgrade")))))))

(deftest upgrade-rez-strategy-unaffordable-declines-instead-of-looping
  (testing "second pass with the upgrade still unrezzed declines (can't afford) rather than re-rezzing"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (let [out (with-out-str
                    (let [result (corp-handlers/handle-corp-server-upgrade-decision
                                  ;; prior attempt already recorded for this cid
                                  (upgrade-decision-ctx {:rez #{"Manegarm Skunkworks"}
                                                         :upgrade-rez-attempted 77}))]
                      (is (= :upgrade-rez-failed-declined (:action result))
                          (str "must gracefully decline (continue), not re-rez, got: " result))))]
          (is (not-any? #(= "rez" (:command %)) @sent)
              "must NOT re-send a rez command when the prior attempt failed")
          (is (some #(= "continue" (:command %)) @sent)
              "should continue past the unrezzed upgrade so the run proceeds")
          (is (re-find #"(?i)can't afford|did not take" out)
              (str "should explain why it declined, got: " out)))))))

(deftest upgrade-rez-failed-does-not-pass-when-not-corp-priority
  ;; Guard for the one priority-ADVANCING action this handler can take (guest
  ;; review, #67). A failed/unaffordable upgrade rez must NOT send `continue`
  ;; unless the Corp actually holds priority (Runner has passed, :no-action
  ;; "runner"). Otherwise a stale/transient empty-run prompt seen at a fresh
  ;; window could make the Corp pass on the Runner's behalf and skip the Runner's
  ;; pre-access window. It must also never fall through to a re-rez.
  (testing "already-attempted upgrade at a window the Corp doesn't own sends neither continue nor rez"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-out-str
          (let [result (corp-handlers/handle-corp-server-upgrade-decision
                        (upgrade-decision-ctx {:rez #{"Manegarm Skunkworks"}
                                               :upgrade-rez-attempted 77}
                                              :no-action false))]  ; fresh window, Runner active
            (is (nil? result)
                (str "not our priority → hold, don't act, got: " result))
            (is (not-any? #(= "continue" (:command %)) @sent)
                "must NOT pass on the Runner's behalf at a window the Corp doesn't own")
            (is (not-any? #(= "rez" (:command %)) @sent)
                "must NOT re-rez a failed attempt")))))))

(deftest upgrade-not-in-rez-list-still-surfaces-decision
  (testing "an upgrade the Corp hasn't --rez-listed still pauses for a decision (not auto-rezzed, not silently passed)"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-out-str
          (let [result (corp-handlers/handle-corp-server-upgrade-decision
                        (upgrade-decision-ctx {:rez #{"Palisade"}}))]
            (is (= :decision-required (:status result)))
            (is (not-any? #(= "rez" (:command %)) @sent)
                "must not rez an upgrade that isn't in the --rez list")))))))

(deftest upgrade-no-strategy-surfaces-decision
  (testing "with no --rez/--no-rez strategy the pre-access upgrade window still surfaces for a decision"
    (with-redefs [ws/send-message! (fn [_evt _data] true)]
      (with-out-str
        (let [result (corp-handlers/handle-corp-server-upgrade-decision
                      (upgrade-decision-ctx {}))]
          (is (= :decision-required (:status result))))))))

;; =============================================================================
;; Test: Corp continue-spam at a blocked checkpoint must not happen (#75).
;;
;; Marquee g2 (GPT-5.6-Terra Corp vs Opus Runner) wedged unrecoverably: at
;; movement/pos-0 the Runner passed, the Corp's continue triggered the
;; :approach-server checkpoint and the engine BLOCKED on the Runner's Manegarm
;; Skunkworks "Choose one" prompt — run phase stays "movement", :no-action stays
;; "runner", and the Corp's own prompt becomes prompt-type "waiting". The engine
;; has no in-flight guard: each additional Corp `continue` re-fired approach-server
;; and minted a FRESH duplicate Manegarm prompt (replay frames 255-259: five
;; stacked prompts, five "approaches Server 1" log lines). The Runner paid the
;; top one, stole, and was left draining no-op duplicates — game lost to tooling.
;;
;; Client-side rule: a seat holding a "waiting" prompt must NEVER send continue —
;; the engine is mid-checkpoint on the opponent. Guarded in three layers:
;;   (a) --fire-if-asked's empty-window auto-continue requires a "run"-type
;;       prompt (a waiting prompt has empty :choices/:selectable and matched);
;;   (b) handle-corp-all-subs-resolved must not RE-pass after the Corp already
;;       passed (the earlier burst, frames 248-252);
;;   (c) belt-and-braces: send-continue! itself suppresses when the live state
;;       shows our own prompt is a waiting prompt.
;; =============================================================================

(defn- fire-if-asked-blocked-checkpoint-ctx
  "Corp --fire-if-asked at movement/pos-0 where the engine is blocked on the
   Runner's Manegarm prompt: Corp holds the mirrored 'waiting' prompt, run
   :no-action still says 'runner'. Exactly the #75 wedge window."
  []
  {:side "corp"
   :run-phase "movement"
   :strategy {:fire-if-asked true}
   :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000075")
   :my-prompt {:msg "Waiting for Runner to make a decision"
               :prompt-type "waiting" :choices [] :selectable []}
   :state {:game-state
           {:run {:phase "movement" :position 0 :server [:remote1] :no-action "runner"}
            :corp {:prompt-state {:msg "Waiting for Runner to make a decision"
                                  :prompt-type "waiting" :choices [] :selectable []}
                   :servers {:remote1 {:content [{:cid 11 :title "Manegarm Skunkworks"
                                                  :type "Upgrade" :rezzed true}]}}}
            :runner {:prompt-state {:msg "Choose one" :prompt-type "other"
                                    :choices [{:uuid "u0" :value "Spend [Click][Click]"}
                                              {:uuid "u1" :value "End the run"}]}}
            :log []}}})

(deftest fire-if-asked-does-not-continue-on-waiting-prompt
  (testing "--fire-if-asked must NOT auto-continue while holding a 'waiting' prompt — each continue re-fires the blocked approach-server checkpoint and mints a duplicate upgrade prompt (#75)"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-out-str
          (let [r (corp-handlers/handle-corp-fire-if-asked
                   (fire-if-asked-blocked-checkpoint-ctx))]
            (is (not-any? #(= "continue" (:command %)) @sent)
                "a continue here re-fires approach-server and mints a duplicate Manegarm prompt")
            (is (not= :action-taken (:status r))
                (str "must not claim an action was taken at a blocked checkpoint, got: " r))))))))

(deftest fire-if-asked-still-continues-empty-run-window
  (testing "--fire-if-asked still auto-continues a genuine empty 'run'-type paid-ability window (no over-blocking)"
    (let [sent (atom [])
          ctx (-> (fire-if-asked-blocked-checkpoint-ctx)
                  (assoc :my-prompt {:msg "You may use paid abilities"
                                     :prompt-type "run" :choices [] :selectable []})
                  (assoc-in [:state :game-state :corp :prompt-state]
                            {:msg "You may use paid abilities"
                             :prompt-type "run" :choices [] :selectable []}))]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-out-str
          (let [r (corp-handlers/handle-corp-fire-if-asked ctx)]
            (is (some #(= "continue" (:command %)) @sent)
                "an empty run-type window is exactly what --fire-if-asked should sleep through")
            (is (= :action-taken (:status r)))))))))

(defn- all-subs-resolved-ctx
  "Corp at encounter-ice with every Palisade sub broken. :no-action controls
   whether the Corp has already passed this window."
  [no-action]
  {:side "corp"
   :run-phase "encounter-ice"
   :strategy {}
   :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000075")
   :my-prompt {:msg "You may use paid abilities" :prompt-type "run" :choices [] :selectable []}
   :state {:game-state
           {:run (cond-> {:phase "encounter-ice" :position 1 :server [:remote1]}
                   no-action (assoc :no-action no-action))
            :corp {:prompt-state {:msg "You may use paid abilities"
                                  :prompt-type "run" :choices [] :selectable []}
                   :servers {:remote1 {:ices [{:cid 21 :title "Palisade" :rezzed true
                                               :subroutines [{:label "End the run" :broken true}]}]}}}
            :runner {:prompt-state nil}
            :log []}}})

(deftest all-subs-resolved-does-not-repass-after-corp-passed
  (testing "handle-corp-all-subs-resolved must not RE-send continue after the Corp already passed (:no-action corp) — the frames-248-252 spam burst of #75"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-out-str
          (let [r (corp-handlers/handle-corp-all-subs-resolved (all-subs-resolved-ctx "corp"))]
            (is (not-any? #(= "continue" (:command %)) @sent)
                "the Corp already passed this window; a second continue is spam the engine may amplify")
            (is (not= :action-taken (:status r))
                (str "must not claim an action, got: " r))))))))

(deftest all-subs-resolved-still-passes-fresh-window
  (testing "handle-corp-all-subs-resolved still passes a window the Corp hasn't passed yet (no over-blocking)"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-out-str
          (let [r (corp-handlers/handle-corp-all-subs-resolved (all-subs-resolved-ctx nil))]
            (is (some #(= "continue" (:command %)) @sent)
                "all subs broken and Corp hasn't spoken — passing is correct")
            (is (= :action-taken (:status r)))))))))

(deftest send-continue-chokepoint-suppresses-on-live-waiting-prompt
  (testing "belt-and-braces: even when a handler's own ctx qualifies, send-continue! consults the LIVE state and refuses to send while our prompt is a waiting prompt (#75)"
    (let [sent (atom [])
          ;; ctx satisfies all-subs-resolved (fresh window, subs broken), but the
          ;; LIVE mirror — refreshed after the ctx snapshot was taken — shows the
          ;; engine has since blocked on the Runner (Corp prompt went 'waiting').
          ctx (all-subs-resolved-ctx nil)
          live (mock-client-state
                :side "corp"
                :game-state (assoc-in (get-in ctx [:state :game-state])
                                      [:corp :prompt-state]
                                      {:msg "Waiting for Runner to make a decision"
                                       :prompt-type "waiting" :choices [] :selectable []}))]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-mock-state live
          (with-out-str
            (let [r (corp-handlers/handle-corp-all-subs-resolved ctx)]
              (is (not-any? #(= "continue" (:command %)) @sent)
                  "the live waiting prompt is the engine saying 'opponent is deciding' — no continue may be sent")
              (is (= :waiting-for-opponent (:status r))
                  (str "suppressed continue should report an opponent wait so loops idle instead of spinning, got: " r)))))))))

(deftest ai-runs-send-continue-chokepoint-suppresses-on-live-waiting-prompt
  (testing "the ai-runs copy of send-continue! has the same waiting-prompt chokepoint — via handle-initiation-auto-pass with a live waiting prompt (#75)"
    (let [sent (atom [])
          live (mock-client-state
                :side "corp"
                :game-state
                {:run {:phase "initiation" :position 1 :server [:hq] :no-action "runner"}
                 :corp {:prompt-state {:msg "Waiting for Runner to make a decision"
                                       :prompt-type "waiting" :choices [] :selectable []}}
                 :runner {:prompt-state nil}
                 :log []})]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (with-mock-state live
          (with-out-str
            (let [r (ai/continue-run!)]
              (is (not-any? #(= "continue" (:command %)) @sent)
                  "no continue may be sent while our own live prompt is a waiting prompt")
              (is (not= :action-taken (:status r))
                  (str "must not claim an action was taken, got: " r)))))))))

;; =============================================================================
;; Review findings on the #75 fix (panel: Claude + GPT-5.5/Devin):
;; the SAME duplicate-continue spam class existed mirrored on the Runner seat.
;; handle-runner-pass-fired-ice had no pass-once guard (unlike its sibling
;; handle-runner-pass-broken-ice), so while the Corp's window stayed open the
;; subs-resolved log heuristic stayed true and the Runner re-sent continue every
;; loop iteration; and the runner-handlers copy of send-continue! had no
;; waiting-prompt chokepoint.
;; =============================================================================

(defn- pass-fired-ice-ctx
  "Runner at encounter-ice with the ICE's subs all fired and the log recording
   the resolution — the state handle-runner-pass-fired-ice keys on."
  []
  {:side "runner"
   :run-phase "encounter-ice"
   :strategy {}
   :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000076")
   :my-prompt {:msg "You may use paid abilities" :prompt-type "run" :choices [] :selectable []}
   :state {:game-state
           {:run {:phase "encounter-ice" :position 1 :server [:hq]}
            :corp {:servers {:hq {:ices [{:cid 31 :title "Tithe" :rezzed true
                                          :subroutines [{:label "Do 1 net damage" :fired true}
                                                        {:label "Gain 1 credit" :fired true}]}]}}}
            :runner {:prompt-state {:msg "You may use paid abilities"
                                    :prompt-type "run" :choices [] :selectable []}}
            :log [{:text "Corp resolves 2 unbroken subroutines on Tithe"}]}}})

(deftest pass-fired-ice-passes-at-most-once
  (testing "handle-runner-pass-fired-ice sends ONE continue then waits — no re-send while the Corp's window is open (#75 review finding)"
    (let [sent (atom [])]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (runner-handlers/reset-state!)
        (with-out-str
          (let [r1 (runner-handlers/handle-runner-pass-fired-ice (pass-fired-ice-ctx))
                r2 (runner-handlers/handle-runner-pass-fired-ice (pass-fired-ice-ctx))]
            (is (= :action-taken (:status r1))
                (str "first pass is legitimate, got: " r1))
            (is (= :waiting-for-opponent (:status r2))
                (str "second pass at the same [position ice] is spam; must wait, got: " r2))))
        (is (= 1 (count (filter #(= "continue" (:command %)) @sent)))
            "exactly one continue may reach the engine for this window")
        (runner-handlers/reset-state!)))))

(deftest runner-send-continue-chokepoint-suppresses-on-live-waiting-prompt
  (testing "the runner-handlers copy of send-continue! suppresses while the LIVE state shows a waiting prompt (#75 review finding)"
    (let [sent (atom [])
          live (mock-client-state
                :side "runner"
                :game-state (assoc-in (get-in (pass-fired-ice-ctx) [:state :game-state])
                                      [:runner :prompt-state]
                                      {:msg "Waiting for Corp to make a decision"
                                       :prompt-type "waiting" :choices [] :selectable []}))]
      (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
        (runner-handlers/reset-state!)
        (with-mock-state live
          (with-out-str
            (let [r (runner-handlers/handle-runner-pass-fired-ice (pass-fired-ice-ctx))]
              (is (not-any? #(= "continue" (:command %)) @sent)
                  "no continue may be sent while our own live prompt is a waiting prompt")
              (is (= :waiting-for-opponent (:status r))
                  (str "suppressed continue reports an opponent wait, got: " r)))))
        (runner-handlers/reset-state!)))))
