(ns ai-ability-zones-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [ai-state :as state]
            [ai-core :as core]
            [ai-card-actions :as actions]
            [ai-display :as display]
            [ai-websocket-client-v2 :as ws]))

(defn- card [title zone cid]
  {:title title :zone zone :cid cid :side "Corp" :type "Agenda"
   :abilities [{:label "Gain 3 credits" :playable true}]})

(defn- fixture-state []
  {:side "corp" :gameid "offline"
   :game-state {:corp {:click 3 :credit 5 :deck-count 1
                       :identity (assoc (card "Acme Consulting: The Truth You Need" nil 1) :type "Identity")
                       :scored [(card "Gila Hands Arcology" [:scored] 2)]
                       :current [(assoc (card "Current Test" [:current] 3) :type "Operation")]
                       :play-area [(assoc (card "Play Area Test" [:play-area] 4) :type "Operation")]
                       :hand [(card "Hand Test" [:hand] 5)]
                       :discard [(card "Discard Test" [:discard] 6)]
                       :rfg [(card "RFG Test" [:rfg] 7)]
                       :servers {}}}})

(deftest scored-agenda-is-an-ability-target
  (let [original @state/client-state
        sent (atom nil)]
    (try
      (reset! state/client-state (fixture-state))
      (is (= 2 (:cid (core/find-ability-card "Gila Hands Arcology"))))
      (with-redefs [ws/send-message! (fn [_ payload] (reset! sent payload) true)
                    core/verify-ability-in-log (fn [& _] {:status :success})]
        (is (= :success (:status (actions/use-ability! "Gila Hands Arcology" 0))))
        (is (= "ability" (:command @sent)))
        (is (= [:scored] (get-in @sent [:args :card :zone]))))
      (is (str/includes? (with-out-str (display/show-card-abilities "Gila Hands Arcology"))
                         "Gain 3 credits"))
      (let [out (with-out-str (display/list-playables))]
        (is (str/includes? out "Gila Hands Arcology"))
        (is (str/includes? out "playable abilities")))
      (is (str/includes? (with-out-str (display/show-board)) "Gila Hands Arcology [0]"))
      (finally (reset! state/client-state original)))))

(deftest own-clickable-zones-share-one-lookup
  (let [original @state/client-state]
    (try
      (reset! state/client-state (fixture-state))
      (doseq [title ["Acme Consulting: The Truth You Need" "Gila Hands Arcology"
                     "Current Test" "Play Area Test"]]
        (is (= title (:title (core/find-ability-card title)))))
      (doseq [title ["Hand Test" "Discard Test" "RFG Test"]]
        (is (nil? (core/find-ability-card title)) title))
      (finally (reset! state/client-state original)))))

(deftest duplicate-across-zones-refuses-once
  (let [original @state/client-state]
    (try
      (reset! state/client-state
              (assoc-in (fixture-state) [:game-state :corp :servers :remote1 :content]
                        [(assoc (card "Gila Hands Arcology" [:servers :remote1 :content] 9)
                                :installed true)]))
      (let [out (with-out-str (actions/use-ability! "Gila Hands Arcology" 0))]
        (is (= 1 (count (re-seq #"Multiple copies" out))) out)
        (is (not (str/includes? out "Card not found")) out)
        (is (= 2 (:cid (core/find-ability-card "Gila Hands Arcology [1]")))))
      (finally (reset! state/client-state original)))))

(deftest opponent-card-in-my-scored-area-is-not-my-ability
  (let [original @state/client-state]
    (try
      (reset! state/client-state
              {:side "runner"
               :game-state {:runner {:scored [(card "Gila Hands Arcology" [:scored] 2)]
                                     :rig {:program [] :hardware [] :resource []}}
                            :corp {:servers {}}}})
      (is (nil? (core/find-ability-card "Gila Hands Arcology")))
      (finally (reset! state/client-state original)))))
