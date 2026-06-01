(ns ai-heuristic-corp-test
  "Regression tests for ai_heuristic_corp.clj.

   Locks down: economy-operations filtering by explicit title set after
   commit 743a5f0a9. Previous version relied on a :text \"Gain\" substring
   fallback that never fired because :text isn't in card-keys on the wire,
   making Government Subsidy and Predictive Planogram (starter Corp deck)
   invisible to the dashboard."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-heuristic-corp :as h]))

(deftest test-econ-operation-titles-contains-starter-decks
  (testing "Government Subsidy and Predictive Planogram are in the explicit set"
    (is (contains? h/econ-operation-titles "Government Subsidy"))
    (is (contains? h/econ-operation-titles "Predictive Planogram")))
  (testing "Classic Hedge Fund / IPO / Beanstalk also present"
    (is (contains? h/econ-operation-titles "Hedge Fund"))
    (is (contains? h/econ-operation-titles "IPO"))
    (is (contains? h/econ-operation-titles "Beanstalk Royalties"))))

(deftest test-economy-operations-finds-playable-by-title
  (testing "Returns only playable Operations whose title is in the set"
    (with-mock-state (mock-client-state
                       :side "corp"
                       :hand [{:title "Hedge Fund" :type "Operation" :playable true}
                              {:title "Government Subsidy" :type "Operation" :playable true}
                              {:title "Hedge Fund" :type "Operation" :playable false}
                              {:title "Sure Gamble" :type "Operation" :playable true}
                              {:title "Rashida Jaheem" :type "Asset" :playable true}])
      (let [titles (sort (mapv :title (h/economy-operations)))]
        (is (= ["Government Subsidy" "Hedge Fund"] titles))))))

(deftest test-economy-operations-ignores-unknown-titles
  (testing "Unknown-title Operations don't slip through via :text"
    (with-mock-state (mock-client-state
                       :side "corp"
                       :hand [{:title "MadeUpOp" :type "Operation" :playable true
                               :text "Gain 9 [Credits]"}])
      (is (empty? (h/economy-operations))
          "Pre-fix, :text 'Gain' would have matched this. Post-fix, only the explicit title set counts."))))

;; ----------------------------------------------------------------------------
;; install-for-win must not pick an occupied remote (own-turn spin regression)
;;
;; Bug (2026-06-01 self-play, T14): Corp at 6/7 with a winning agenda in hand and
;; a single protected remote already holding an econ asset (Urtica Cipher). The
;; :install-for-win rule emitted a PLAIN install into that server; install-card!
;; correctly returns :blocked ("Server N already has X"), spending no click and
;; raising no prompt — so the autonomous loop re-decided the identical blocked
;; install every tick. The corp loop's stall backstop only watches run-status
;; (nil on the corp's own turn), so nothing caught it. Fix: when the single
;; remote is occupied by an asset, install-for-win overwrites it (we're winning
;; anyway, the asset's job is done).
;; ----------------------------------------------------------------------------

(def ^:private win-agenda
  {:title "Offworld Office" :type "Agenda" :agendapoints 2 :advancementcost 4})

(defn- corp-win-state
  "Corp at 6 pts, 16 credits, winning agenda in hand, one ICE-protected remote
   whose :content is supplied by the caller."
  [remote-content]
  (-> (mock-client-state
        :side "corp" :credits 16 :clicks 3 :active-player "corp"
        :hand [win-agenda]
        :servers {:remote1 {:ices [{:title "Tithe" :rezzed false :cost 1}]
                            :content remote-content}})
      (assoc-in [:game-state :corp :agenda-point] 6)))

(deftest test-install-for-win-overwrites-occupied-remote
  (testing "Occupied-by-asset remote → install-for-win sets :overwrite true"
    (with-mock-state (corp-win-state [{:title "Urtica Cipher" :type "Asset"}])
      (let [{:keys [action args]} (h/decide-action)]
        (is (= :install action))
        (is (= "Offworld Office" (:card-name args)))
        (is (= "Server 1" (:server args)))
        (is (true? (:overwrite args))
            "Without overwrite, install-card! blocks and the loop spins forever")))))

(deftest test-install-for-win-empty-remote-no-overwrite
  (testing "Empty protected remote → plain install, no overwrite"
    (with-mock-state (corp-win-state [])
      (let [{:keys [action args]} (h/decide-action)]
        (is (= :install action))
        (is (= "Offworld Office" (:card-name args)))
        (is (not (:overwrite args))
            "Empty remote needs no overwrite — don't request a spurious trash")))))
