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
