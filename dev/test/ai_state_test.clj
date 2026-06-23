(ns ai-state-test
  "Tests for defensive gamestate accessors in ai-state"
  (:require [clojure.test :refer :all]
            [ai-state :as state]
            [test-helpers :refer [mock-client-state with-mock-state]]))

;; ============================================================================
;; UUID Normalization Tests
;; ============================================================================

(deftest test-normalize-gameid
  (testing "converts string UUID to UUID object"
    (let [result (state/normalize-gameid "123e4567-e89b-12d3-a456-426614174000")]
      (is (instance? java.util.UUID result))))

  (testing "passes through UUID objects unchanged"
    (let [uuid (java.util.UUID/randomUUID)
          result (state/normalize-gameid uuid)]
      (is (= uuid result))))

  (testing "returns nil for nil input"
    (is (nil? (state/normalize-gameid nil)))))

;; ============================================================================
;; Hand Accessor Tests
;; ============================================================================

(deftest test-corp-hand
  (testing "returns cards when present"
    (with-mock-state (mock-client-state
                       :side "corp"
                       :hand [{:cid 1 :title "Hedge Fund"}
                              {:cid 2 :title "Ice Wall"}])
      (let [hand (state/corp-hand)]
        (is (= 2 (count hand)))
        (is (= "Hedge Fund" (:title (first hand)))))))

  (testing "returns empty vector when nil"
    (with-mock-state {:game-state {:corp {:hand nil}}}
      (is (= [] (state/corp-hand)))))

  (testing "returns empty vector when game-state is nil"
    (with-mock-state {:game-state nil}
      (is (= [] (state/corp-hand))))))

(deftest test-runner-hand
  (testing "returns cards when present"
    (with-mock-state (mock-client-state
                       :side "runner"
                       :hand [{:cid 1 :title "Sure Gamble"}])
      (let [hand (state/runner-hand)]
        (is (= 1 (count hand)))
        (is (= "Sure Gamble" (:title (first hand)))))))

  (testing "returns empty vector when nil"
    (with-mock-state {:game-state {:runner {:hand nil}}}
      (is (= [] (state/runner-hand))))))

(deftest test-hand-for-side
  (testing "returns corp hand for :corp"
    (with-mock-state (mock-client-state
                       :game-state {:corp {:hand [{:title "Test"}]}
                                   :runner {:hand []}})
      (is (= [{:title "Test"}] (state/hand-for-side :corp)))))

  (testing "returns runner hand for :runner"
    (with-mock-state (mock-client-state
                       :game-state {:corp {:hand []}
                                   :runner {:hand [{:title "Test2"}]}})
      (is (= [{:title "Test2"}] (state/hand-for-side :runner)))))

  (testing "accepts string side names"
    (with-mock-state (mock-client-state
                       :game-state {:corp {:hand [{:title "Test"}]}
                                   :runner {:hand []}})
      (is (= [{:title "Test"}] (state/hand-for-side "corp"))))))

;; ============================================================================
;; Installed Cards Accessor Tests
;; ============================================================================

(deftest test-corp-servers
  (testing "returns servers map when present"
    (with-mock-state (mock-client-state
                       :servers {:hq {:content [{:title "Adonis"}]}
                                :remote1 {:ices [{:title "Ice Wall"}]}})
      (let [servers (state/corp-servers)]
        (is (map? servers))
        (is (contains? servers :hq))
        (is (contains? servers :remote1)))))

  (testing "returns empty map when nil"
    (with-mock-state {:game-state {:corp {:servers nil}}}
      (is (= {} (state/corp-servers))))))

(deftest test-server-ice
  (testing "returns ICE list for server"
    (with-mock-state (mock-client-state
                       :servers {:hq {:ices [{:title "Ice Wall"} {:title "Enigma"}]}})
      (let [ice (state/server-ice :hq)]
        (is (= 2 (count ice)))
        (is (= "Ice Wall" (:title (first ice)))))))

  (testing "returns empty vector for missing server"
    (with-mock-state (mock-client-state :servers {:hq {}})
      (is (= [] (state/server-ice :remote99))))))

(deftest test-runner-rig
  (testing "returns rig map when present"
    (with-mock-state (mock-client-state
                       :installed {:program [{:title "Corroder"}]
                                  :hardware [{:title "Console"}]
                                  :resource []})
      (let [rig (state/runner-rig)]
        (is (map? rig))
        (is (= [{:title "Corroder"}] (:program rig))))))

  (testing "returns default rig when nil"
    (with-mock-state {:game-state {:runner {:rig nil}}}
      (let [rig (state/runner-rig)]
        (is (= [] (:program rig)))
        (is (= [] (:hardware rig)))
        (is (= [] (:resource rig)))))))

(deftest test-runner-programs
  (testing "returns programs from rig"
    (with-mock-state (mock-client-state
                       :installed {:program [{:title "Corroder"} {:title "Mimic"}]
                                  :hardware []
                                  :resource []})
      (is (= 2 (count (state/runner-programs))))))

  (testing "returns empty vector when no programs"
    (with-mock-state {:game-state {:runner {:rig {:program []}}}}
      (is (= [] (state/runner-programs))))))

;; ============================================================================
;; Run State Accessor Tests
;; ============================================================================

(deftest test-current-run
  (testing "returns run map when in run"
    (with-mock-state {:game-state {:run {:server [:hq] :position 1 :phase :approach-ice}}}
      (let [run (state/current-run)]
        (is (some? run))
        (is (= [:hq] (:server run))))))

  (testing "returns nil when no run"
    (with-mock-state {:game-state {:run nil}}
      (is (nil? (state/current-run))))))

(deftest test-run-server
  (testing "extracts server keyword from run"
    (with-mock-state {:game-state {:run {:server [:remote1]}}}
      (is (= :remote1 (state/run-server)))))

  (testing "returns nil when no run"
    (with-mock-state {:game-state {}}
      (is (nil? (state/run-server))))))

(deftest test-run-phase
  (testing "returns phase keyword"
    (with-mock-state {:game-state {:run {:phase :encounter-ice}}}
      (is (= :encounter-ice (state/run-phase)))))

  (testing "returns nil when no run"
    (with-mock-state {:game-state {}}
      (is (nil? (state/run-phase))))))

;; ============================================================================
;; Game Log Accessor Tests
;; ============================================================================

(deftest test-game-log
  (testing "returns log entries"
    (with-mock-state {:game-state {:log [{:text "Entry 1"} {:text "Entry 2"}]}}
      (let [log (state/game-log)]
        (is (= 2 (count log)))
        (is (= "Entry 1" (:text (first log)))))))

  (testing "returns empty vector when nil"
    (with-mock-state {:game-state {:log nil}}
      (is (= [] (state/game-log))))))

(deftest test-recent-log
  (testing "returns last n entries"
    (with-mock-state {:game-state {:log [{:text "1"} {:text "2"} {:text "3"} {:text "4"}]}}
      (let [recent (state/recent-log 2)]
        (is (= 2 (count recent)))
        (is (= "3" (:text (first recent))))
        (is (= "4" (:text (second recent)))))))

  (testing "handles request for more than available"
    (with-mock-state {:game-state {:log [{:text "Only"}]}}
      (is (= 1 (count (state/recent-log 10)))))))

;; ============================================================================
;; Game-Over Detection Tests
;; ============================================================================

(deftest test-get-turn-status-game-over
  (testing "detects winner and reports game-over"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:active-player "corp"
                                   :turn 27
                                   :winner :corp
                                   :loser :runner
                                   :winning-user "ai-corp"
                                   :reason "Agenda"
                                   :end-time "2026-01-01T00:00:00Z"
                                   :corp {:click 0 :agenda-point 7}
                                   :runner {:click 0 :agenda-point 0}})
      (let [status (state/get-turn-status)]
        (is (true? (:game-over? status)))
        (is (= :corp (:winner status)))
        (is (false? (:can-act? status)))
        (is (= "🏁" (:status-emoji status)))
        (is (= "Corp wins" (:status-text status))))))

  (testing "detects tie (reason + end-time, no winner)"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:active-player "runner"
                                   :turn 12
                                   :reason "Mutual destruction"
                                   :end-time "2026-01-01T00:00:00Z"
                                   :corp {:click 0}
                                   :runner {:click 0}})
      (let [status (state/get-turn-status)]
        (is (true? (:game-over? status)))
        (is (nil? (:winner status)))
        (is (false? (:can-act? status)))
        (is (= "Game over (tie)" (:status-text status))))))

  (testing "no game-over during normal play"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :active-player "corp"
                      :game-state {:active-player "corp"
                                   :turn 5
                                   :corp {:click 3}
                                   :runner {:click 0}})
      (let [status (state/get-turn-status)]
        (is (false? (:game-over? status)))
        (is (nil? (:winner status)))))))

(deftest test-get-turn-status-waiting-to-start
  ;; The turn boundary (end-turn flagged, or both sides at 0 clicks) is a clean
  ;; "next player to start" state, NOT a stall. get-turn-status must expose this
  ;; via :waiting-to-start? plus :next-player so machine consumers (umpire) can
  ;; tell it apart from a mid-turn spin.
  (testing "corp ended turn -> waiting-to-start?, next-player=runner"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :active-player "corp"
                      :game-state {:active-player "corp"
                                   :turn 5
                                   :end-turn true
                                   :corp {:click 0}
                                   :runner {:click 0}})
      (let [status (state/get-turn-status)]
        (is (true? (:waiting-to-start? status)))
        (is (= "runner" (:next-player status))))))

  (testing "both at 0 clicks -> waiting-to-start?, next-player=corp"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :active-player "runner"
                      :game-state {:active-player "runner"
                                   :turn 6
                                   :corp {:click 0}
                                   :runner {:click 0}})
      (let [status (state/get-turn-status)]
        (is (true? (:waiting-to-start? status)))
        (is (= "corp" (:next-player status))))))

  (testing "mid-turn (active player has clicks) -> not waiting-to-start?"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :active-player "corp"
                      :game-state {:active-player "corp"
                                   :turn 5
                                   :corp {:click 3}
                                   :runner {:click 0}})
      (let [status (state/get-turn-status)]
        (is (false? (:waiting-to-start? status))))))

  ;; A run started with the runner's last click leaves BOTH sides at 0 clicks
  ;; (corp is 0 during the runner's turn) but is mid-resolution, not a turn
  ;; boundary. An active run must NOT be reported as waiting-to-start, else a
  ;; wedged last-click run gets the patient boundary stall budget.
  (testing "mid-run at 0 clicks -> not waiting-to-start?"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :active-player "runner"
                      :game-state {:active-player "runner"
                                   :turn 6
                                   :run {:server [:hq] :position 1}
                                   :corp {:click 0}
                                   :runner {:click 0}})
      (let [status (state/get-turn-status)]
        (is (false? (:waiting-to-start? status)))))))

(deftest test-get-turn-status-waiting-prompt
  ;; The WIRE value of :prompt-type is the STRING "waiting", not the keyword
  ;; :waiting (see ai-stall comment + ai-core both-form match). A waiting
  ;; prompt on our OWN turn (e.g. waiting for a corp rez decision mid-run)
  ;; must report not-actable, not fall through to "your turn to act".
  (testing "wire-string \"waiting\" prompt on own turn -> not actable"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :active-player "corp"
                      :game-state {:active-player "corp"
                                   :turn 5
                                   :corp {:click 2
                                          :prompt-state {:prompt-type "waiting"
                                                         :msg "Waiting for Runner to resolve"}}
                                   :runner {:click 0}})
      (let [status (state/get-turn-status)]
        (is (false? (:can-act? status)))
        (is (= "⏳" (:status-emoji status))))))

  (testing "keyword :waiting prompt on own turn -> not actable (legacy form)"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :active-player "corp"
                      :game-state {:active-player "corp"
                                   :turn 5
                                   :corp {:click 2
                                          :prompt-state {:prompt-type :waiting
                                                         :msg "Waiting"}}
                                   :runner {:click 0}})
      (let [status (state/get-turn-status)]
        (is (false? (:can-act? status)))
        (is (= "⏳" (:status-emoji status)))))))

(deftest test-waiting-prompt-type?-predicate
  (testing "matches both wire-string and keyword forms"
    (is (true? (boolean (state/waiting-prompt-type? "waiting"))))
    (is (true? (boolean (state/waiting-prompt-type? :waiting)))))
  (testing "does not match non-waiting prompt types"
    (is (false? (boolean (state/waiting-prompt-type? "select"))))
    (is (false? (boolean (state/waiting-prompt-type? :select))))
    (is (false? (boolean (state/waiting-prompt-type? nil))))))

(deftest test-select-prompt-type?-predicate
  (testing "matches both wire-string and keyword forms"
    (is (true? (boolean (state/select-prompt-type? "select"))))
    (is (true? (boolean (state/select-prompt-type? :select)))))
  (testing "does not match non-select prompt types"
    (is (false? (boolean (state/select-prompt-type? "waiting"))))
    (is (false? (boolean (state/select-prompt-type? :waiting))))
    (is (false? (boolean (state/select-prompt-type? "run"))))
    (is (false? (boolean (state/select-prompt-type? nil))))))

(deftest test-game-over?-predicate
  (testing "winner set -> game over"
    (is (true? (state/game-over? {:winner "runner"})))
    (is (true? (state/game-over? {:winner "corp" :reason "Flatline"}))))
  (testing "reason + end-time set (tie / decked) -> game over"
    (is (true? (state/game-over? {:reason "Decked" :end-time "2026-06-20T00:00:00Z"}))))
  (testing "live game -> not over"
    (is (false? (state/game-over? {:active-player "runner" :turn 5})))
    ;; :reason without :end-time is NOT game-over (e.g. a mid-game annotation)
    (is (false? (state/game-over? {:reason "Decked"})))
    (is (false? (state/game-over? {:end-time "2026-06-20T00:00:00Z"})))
    (is (false? (state/game-over? nil))))
  (testing "zero-arg form reads current client game-state"
    (with-mock-state {:connected true :side "corp"
                      :game-state {:winner "runner" :turn 10}}
      (is (true? (state/game-over?))))
    (with-mock-state {:connected true :side "corp"
                      :game-state {:turn 5 :active-player "corp"}}
      (is (false? (state/game-over?))))))

;; ============================================================================
;; Hosted Run-Spendable Credits (issue #21)
;; ============================================================================

(deftest test-runner-hosted-credits
  (testing "no hosted credits -> total 0, empty sources"
    (is (= {:total 0 :sources []}
           (state/runner-hosted-credits {:runner {:rig {}}})))
    ;; cards present but none carry a credit counter
    (is (= {:total 0 :sources []}
           (state/runner-hosted-credits
             {:runner {:rig {:program [{:title "Leech" :counter {:virus 2}}]
                             :hardware [{:title "Cyberdelia"}]}}}))))

  (testing "Overclock parks credits in the play-area during a run"
    (let [gs {:runner {:rig {}
                       :play-area [{:title "Overclock" :counter {:credit 5}}]}}
          {:keys [total sources]} (state/runner-hosted-credits gs)]
      (is (= 5 total))
      (is (= [{:title "Overclock" :credits 5}] sources))))

  (testing "sums across rig zones and play-area"
    (let [gs {:runner {:rig {:resource [{:title "Ghost Runner" :counter {:credit 3}}]
                             :hardware [{:title "Cyberdelia" :counter {:credit 1}}]
                             :program [{:title "Leech" :counter {:virus 2}}]}
                       :play-area [{:title "Overclock" :counter {:credit 5}}]}}
          {:keys [total sources]} (state/runner-hosted-credits gs)]
      (is (= 9 total))
      (is (= 3 (count sources)))
      (is (= #{"Ghost Runner" "Cyberdelia" "Overclock"}
             (set (map :title sources))))))

  (testing "recurses into hosted cards (e.g. credits on a hosted card)"
    (let [gs {:runner {:rig {:hardware [{:title "Console"
                                         :hosted [{:title "Hosted Econ" :counter {:credit 2}}]}]}}}
          {:keys [total sources]} (state/runner-hosted-credits gs)]
      (is (= 2 total))
      (is (= [{:title "Hosted Econ" :credits 2}] sources))))

  (testing "zero credit counter is not surfaced"
    (is (= {:total 0 :sources []}
           (state/runner-hosted-credits
             {:runner {:play-area [{:title "Spent Overclock" :counter {:credit 0}}]}})))))
