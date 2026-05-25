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
