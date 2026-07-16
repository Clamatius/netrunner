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
                           :log [{:text "ai-runner indicates to fire Enigma"}]))]
      (is (= :fire-unbroken (:kind decision)))
      (is (= 1 (get-in decision [:ice :unbroken-count]))))))

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
