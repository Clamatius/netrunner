(ns ai-run-corp-decisions-test
  (:require [clojure.test :refer :all]
            [ai-run-corp-decisions :as decisions]))

(defn- run-prompt []
  {:msg "You may use paid abilities"
   :prompt-type "run"
   :choices []
   :selectable []})

(defn- choice-prompt []
  {:msg "Use ability?"
   :prompt-type "other"
   :choices [{:value "Yes"} {:value "No"}]
   :selectable []})

(defn- ice
  [& {:keys [cid title rezzed subroutines]
      :or {cid 1 title "Ice Wall" rezzed false subroutines []}}]
  {:cid cid
   :title title
   :type "ICE"
   :rezzed rezzed
   :subroutines subroutines})

(defn- upgrade
  [& {:keys [cid title rezzed]
      :or {cid 10 title "Manegarm Skunkworks" rezzed false}}]
  {:cid cid
   :title title
   :type "Upgrade"
   :rezzed rezzed})

(defn- state
  [& {:keys [phase position server servers corp-prompt log]
      :or {phase "movement"
           position 0
           server [:hq]
           servers {}
           corp-prompt (run-prompt)
           log []}}]
  {:side "corp"
   :game-state
   {:run {:phase phase
          :position position
          :server server}
    :corp {:prompt-state corp-prompt
           :servers servers}
    :runner {:prompt-state nil}
    :log log}})

(deftest no-local-decision-sleeps
  (testing "no ICE and no attacked-server content is not a Corp decision"
    (is (= :none
           (:kind (decisions/corp-run-decision
                   (state :phase "movement" :position 0 :server [:hq])))))))

(deftest rezzed-ice-with-no-actionable-subs-sleeps
  (testing "rezzed ICE whose subs are already resolved does not wake Corp"
    (let [s (state :phase "encounter-ice"
                   :position 1
                   :server [:hq]
                   :servers {:hq {:ices [(ice :title "Enigma"
                                                :rezzed true
                                                :subroutines [{:label "End the run"
                                                               :broken true}])]}})]
      (is (= :none (:kind (decisions/corp-run-decision s)))))))

(deftest current-unrezzed-ice-wakes-for-rez
  (testing "unrezzed current ICE at approach wakes for a rez decision"
    (let [decision (decisions/corp-run-decision
                    (state :phase "approach-ice"
                           :position 1
                           :server [:hq]
                           :servers {:hq {:ices [(ice :title "Palisade"
                                                        :rezzed false)]}}))]
      (is (= :rez-ice (:kind decision)))
      (is (= "Palisade" (get-in decision [:ice :title]))))))

(deftest only-current-position-ice-wakes
  (testing "two unrezzed ICE wake for the currently approached position only"
    (let [decision (decisions/corp-run-decision
                    (state :phase "approach-ice"
                           :position 2
                           :server [:remote1]
                           :servers {:remote1 {:ices [(ice :title "Inner Ice"
                                                            :cid 1)
                                                      (ice :title "Outer Ice"
                                                            :cid 2)]}}))]
      (is (= :rez-ice (:kind decision)))
      (is (= "Outer Ice" (get-in decision [:ice :title])))
      (is (= 2 (get-in decision [:ice :position]))))))

(deftest other-server-ice-does-not-wake
  (testing "unrezzed ICE on another server is irrelevant to this run"
    (let [decision (decisions/corp-run-decision
                    (state :phase "approach-ice"
                           :position 1
                           :server [:hq]
                           :servers {:remote1 {:ices [(ice :title "Remote Ice")]}}))]
      (is (= :none (:kind decision))))))

(deftest unbroken-subs-wait-for-runner-signal
  (testing "unbroken subs before Runner signal are not yet a Corp decision"
    (let [decision (decisions/corp-run-decision
                    (state :phase "encounter-ice"
                           :position 1
                           :server [:hq]
                           :servers {:hq {:ices [(ice :title "Enigma"
                                                        :rezzed true
                                                        :subroutines [{:label "End the run"}])]}}))]
      (is (= :waiting-runner-signal (:kind decision))))))

(deftest runner-signal-makes-fire-decision
  (testing "after Runner signals, unbroken subs become a Corp fire decision"
    (let [decision (decisions/corp-run-decision
                    (state :phase "encounter-ice"
                           :position 1
                           :server [:hq]
                           :servers {:hq {:ices [(ice :title "Enigma"
                                                        :rezzed true
                                                        :subroutines [{:label "End the run"}])]}}
                           :log [{:text "ai-runner indicates to fire all unbroken subroutines on Enigma"}]))]
      (is (= :fire-unbroken (:kind decision)))
      (is (= 1 (get-in decision [:ice :unbroken-count]))))))

;; ---------------------------------------------------------------------------
;; #90: the fire signal must be CURRENT, not a stale substring match.
;;
;; The Corp fires unbroken subs only when the Runner explicitly signals ("indicates
;; to fire ... on <ice>"). But `runner-signaled-let-fire?` just substring-scanned the
;; last 20 log lines, so a signal was honoured even after the Runner BROKE the subs,
;; or when the signal was left over from an EARLIER encounter of the same-named ice.
;;
;; Michael's invariant: the Corp does not fire until the Runner says to — and a break
;; un-says it. If the Runner just pauses/breaks, they're the active player and it's a
;; stall (wait), never a fire.
;; ---------------------------------------------------------------------------

(deftest signal-then-break-is-not-a-fire-signal
  (testing "a signal the Runner superseded by breaking the same ice is stale — do NOT fire"
    (let [log [{:text "ai-runner indicates to fire all unbroken subroutines on Whitespace"}
               {:text "ai-runner pays 2 [Credits] to use Unity to break all 2 subroutines on Whitespace"}]]
      (is (false? (decisions/runner-signaled-let-fire?
                   {:game-state {:log log}} "Whitespace"))
          "Runner broke after signalling; the signal no longer authorises a fire"))))

(deftest break-then-signal-is-a-fire-signal
  (testing "partial break THEN tank on the same ice is a live signal — the later remaining subs fire"
    (let [log [{:text "ai-runner pays 1 [Credit] to use Unity to break 1 subroutine on Whitespace"}
               {:text "ai-runner indicates to fire all unbroken subroutines on Whitespace"}]]
      (is (true? (decisions/runner-signaled-let-fire?
                  {:game-state {:log log}} "Whitespace"))
          "the signal is the Runner's most recent action on this ice"))))

(deftest signal-survives-a-break-on-a-DIFFERENT-ice
  (testing "a break on another ice does not stale the signal for this ice"
    (let [log [{:text "ai-runner indicates to fire all unbroken subroutines on Whitespace"}
               {:text "ai-runner pays 2 [Credits] to use Cleaver to break 2 subroutines on Palisade"}]]
      (is (true? (decisions/runner-signaled-let-fire?
                  {:game-state {:log log}} "Whitespace"))
          "the break was on Palisade, so Whitespace's signal is still current"))))

(deftest plain-signal-still-fires
  (testing "a signal with no later break is honoured (regression guard on the happy path)"
    (let [log [{:text "ai-runner indicates to fire all unbroken subroutines on Enigma"}]]
      (is (true? (decisions/runner-signaled-let-fire?
                  {:game-state {:log log}} "Enigma"))))))

(deftest all-broken-subs-with-stale-signal-do-not-wake-a-fire
  (testing "#90: fully-broken subs + a superseded signal classify as no fire decision"
    (let [decision (decisions/corp-run-decision
                    (state :phase "encounter-ice"
                           :position 0
                           :server [:rd]
                           :servers {:rd {:ices [(ice :title "Whitespace"
                                                       :rezzed true
                                                       :subroutines [{:label "Make the Runner lose 3 [Credits]"
                                                                      :broken true}
                                                                     {:label "End the run if the Runner has 6 [Credits] or less"
                                                                      :broken true}])]}}
                           :log [{:text "ai-runner indicates to fire all unbroken subroutines on Whitespace"}
                                 {:text "ai-runner pays 2 [Credits] to use Unity to break all 2 subroutines on Whitespace"}]))]
      (is (not= :fire-unbroken (:kind decision))
          "broken subs must never resolve as a fire, even with a lingering signal"))))

(deftest stale-signal-from-a-prior-encounter-does-not-fire
  (testing "#90 Finding 1: a signal resolved in an EARLIER encounter of the same ice does
            not authorise firing a fresh encounter the Runner hasn't acted in yet"
    ;; Two runs on R&D in one turn. Enc 1: Runner tanks Ice Wall, Corp fires, run ends.
    ;; Enc 2: Ice Wall again, subs unbroken, Runner has NOT signalled or broken yet.
    (let [log [{:text "ai-runner encounters Ice Wall protecting R&D at position 0"}
               {:text "ai-runner indicates to fire all unbroken subroutines on Ice Wall"}
               {:text "ai-corp resolves 1 unbroken subroutine on Ice Wall (\"End the run\")"}
               {:text "ai-runner spends [Click] to make a run on R&D"}
               {:text "ai-runner encounters Ice Wall protecting R&D at position 0"}]]
      (is (false? (decisions/runner-signaled-let-fire?
                   {:game-state {:log log}} "Ice Wall"))
          "the only signal predates the current encounter's marker — the Corp must wait"))))

(deftest fresh-signal-in-the-current-encounter-still-fires
  (testing "a signal AFTER the current encounter's marker is honoured (regression guard)"
    (let [log [{:text "ai-runner encounters Ice Wall protecting R&D at position 0"}
               {:text "ai-corp resolves 1 unbroken subroutine on Ice Wall (\"End the run\")"}
               {:text "ai-runner spends [Click] to make a run on R&D"}
               {:text "ai-runner encounters Ice Wall protecting R&D at position 0"}
               {:text "ai-runner indicates to fire all unbroken subroutines on Ice Wall"}]]
      (is (true? (decisions/runner-signaled-let-fire?
                  {:game-state {:log log}} "Ice Wall"))))))

(deftest signal-for-a-longer-named-ice-does-not-fire-the-shorter
  (testing "#90 Finding 2: 'Fairchild' must not match a signal/marker for 'Fairchild 3.0'"
    (let [log [{:text "ai-runner encounters Fairchild protecting HQ at position 0"}
               {:text "ai-runner indicates to fire all unbroken subroutines on Fairchild 3.0"}]]
      (is (false? (decisions/runner-signaled-let-fire?
                   {:game-state {:log log}} "Fairchild"))
          "the signal was for Fairchild 3.0, not the bare Fairchild being encountered"))))

(deftest signal-for-the-exact-longer-named-ice-fires
  (testing "the same board fires correctly for the ice that was actually signalled"
    (let [log [{:text "ai-runner encounters Fairchild 3.0 protecting HQ at position 0"}
               {:text "ai-runner indicates to fire all unbroken subroutines on Fairchild 3.0"}]]
      (is (true? (decisions/runner-signaled-let-fire?
                  {:game-state {:log log}} "Fairchild 3.0"))))))

(deftest blank-ice-title-never-signals
  (testing "#90 Finding 3: a blank/nil ice title never matches (no NPE, no match-all)"
    (let [log [{:text "ai-runner indicates to fire all unbroken subroutines on Enigma"}]]
      (is (false? (decisions/runner-signaled-let-fire? {:game-state {:log log}} "")))
      (is (false? (decisions/runner-signaled-let-fire? {:game-state {:log log}} nil))))))

(deftest attacked-server-upgrade-wakes-pre-access
  (testing "unrezzed upgrade in the attacked server wakes before access"
    (let [decision (decisions/corp-run-decision
                    (state :phase "movement"
                           :position 0
                           :server [:remote1]
                           :servers {:remote1 {:content [(upgrade)]}}))]
      (is (= :server-upgrade (:kind decision)))
      (is (= "Manegarm Skunkworks" (get-in decision [:card :title]))))))

(deftest success-phase-upgrade-does-not-wake
  ;; Issue #67. The engine's "success" phase is AFTER approach-server has fired,
  ;; so rezzing an approach-triggered upgrade (Manegarm Skunkworks: "whenever the
  ;; Runner approaches this server") there is TOO LATE — its ability never fires
  ;; (proven at the engine level in manegarm_timing_scratch_test). Surfacing a
  ;; "rez before access" upgrade window at success is therefore a dead window that
  ;; lures the Corp into a no-op rez. The only effective rez window is
  ;; movement/position-0 (pre-approach-server), covered above. Success must NOT be
  ;; classified as a pre-access upgrade decision.
  (testing "unrezzed upgrade at success does NOT wake (approach-server has passed)"
    (let [decision (decisions/corp-run-decision
                    (state :phase "success"
                           :position 0
                           :server [:remote1]
                           :servers {:remote1 {:content [(upgrade)]}}))]
      (is (not= :server-upgrade (:kind decision))
          "success is post-approach-server; rezzing there is too late to fire the approach ability"))))

(deftest other-server-upgrade-does-not-wake
  (testing "upgrade on another server does not wake for this run"
    (let [decision (decisions/corp-run-decision
                    (state :phase "movement"
                           :position 0
                           :server [:hq]
                           :servers {:remote1 {:content [(upgrade)]}}))]
      (is (= :none (:kind decision))))))

(deftest already-rezzed-upgrade-does-not-wake
  (testing "already rezzed upgrade is not a rez decision by itself"
    (let [decision (decisions/corp-run-decision
                    (state :phase "movement"
                           :position 0
                           :server [:remote1]
                           :servers {:remote1 {:content [(upgrade :rezzed true)]}}))]
      (is (= :none (:kind decision))))))

(deftest real-corp-prompt-wakes-conservatively
  (testing "unknown choices/selectables wake as unsupported prompt"
    (let [decision (decisions/corp-run-decision
                    (state :phase "movement"
                           :position 0
                           :server [:hq]
                           :corp-prompt (choice-prompt)))]
      (is (= :unsupported-prompt (:kind decision)))
      (is (= :decision-required (:wake-reason decision))))))

(deftest unaffordable-ice-still-wakes
  (testing "affordability does not suppress the rez decision"
    (let [decision (decisions/corp-run-decision
                    (state :phase "approach-ice"
                           :position 1
                           :server [:hq]
                           :servers {:hq {:ices [(assoc (ice :title "Archer")
                                                        :cost 4)]}}))]
      (is (= :rez-ice (:kind decision))))))

(deftest slept-log-summary-keeps-material-events
  (testing "sleep summary filters no-action spam and keeps run events"
    (let [log [{:text "old event before monitor"}
               {:text "ai-corp has no further action"}
               {:text "ai-runner passes Ice Wall"}
               {:text "Corp rezzes Manegarm Skunkworks"}
               {:text "ai-runner has no further action"}]]
      (is (= ["ai-runner passes Ice Wall"
              "Corp rezzes Manegarm Skunkworks"]
             (decisions/summarize-slept-log log 1))))))
