(ns game.probe3-test
  (:require [game.core :as core]
            [game.test-framework :refer :all]
            [clojure.test :refer :all]))

(deftest probe-log
  (do-game
    (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Archangel"]}
               :runner {:hand ["Bank Job"]}})
    (take-credits state :corp)
    (run-empty-server state "HQ")
    (click-prompt state :corp "Yes")
    (doseq [e (take-last 6 (:log @state))]
      (println "LOG>" (pr-str (or (get-in e [:public :text]) (get-in e [:runner :text]) (get-in e [:corp :text])))))
    (is true)))

(deftest probe-log-installed
  (do-game
    (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"]}})
    (play-from-hand state :corp "Ice Wall" "HQ")
    (rez state :corp (get-ice state :hq 0))
    (take-credits state :corp)
    (run-on state "HQ")
    (run-continue state)
    (doseq [e (take-last 4 (:log @state))]
      (println "ILOG>" (pr-str (or (get-in e [:public :text]) (get-in e [:runner :text])))))
    (is true)))
