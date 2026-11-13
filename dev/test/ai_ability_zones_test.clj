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
        (is (str/includes? out "  - Gila Hands Arcology: Ability 0 - Gain 3 credits") out))
      (is (not (str/includes? (with-out-str (display/show-board)) "Own playable abilities")))
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

(deftest installed-and-scored-copies-have-shared-numbering
  (let [original @state/client-state]
    (try
      (reset! state/client-state
              (-> (fixture-state)
                  (assoc-in [:game-state :corp :scored]
                            [(card "Gila Hands Arcology" ["scored"] 2)])
                  (assoc-in [:game-state :corp :servers :remote1 :content]
                            [(assoc (card "Gila Hands Arcology" ["servers" "remote1" "content"] 9)
                                    :installed true :rezzed false
                                    :abilities [{:label "Gain 3 credits" :playable false}])])))
      (let [sent (atom [])
            expected (str "❓ Multiple copies of 'Gila Hands Arcology' in your ability zones (2 found)\n"
                          "   Specify which one:\n"
                          "   → \"Gila Hands Arcology [0]\" (remote1, unrezzed)\n"
                          "   → \"Gila Hands Arcology [1]\" (scored area)\n"
                          "   Re-run with the [N] suffix, e.g. \"Gila Hands Arcology [0]\"\n")]
        (with-redefs [ws/send-message! (fn [_ payload] (swap! sent conj payload) true)
                      core/verify-ability-in-log (fn [& _] {:status :success})]
          (is (= expected (with-out-str (actions/use-ability! "Gila Hands Arcology" 0))))
          (is (empty? @sent))
          (is (= expected (with-out-str (display/show-card-abilities "Gila Hands Arcology"))))
          (is (= :success (:status (actions/use-ability! "Gila Hands Arcology [1]" 0))))
          (is (= 2 (get-in (last @sent) [:args :card :cid])))
          (is (= ["scored"] (get-in (last @sent) [:args :card :zone])))
          (is (= :success (:status (actions/use-ability! "Gila Hands Arcology [0]" 0))))
          (is (= 9 (get-in (last @sent) [:args :card :cid]))))
        (is (= 9 (:cid (core/find-ability-card "Gila Hands Arcology [0]"))))
        (is (= 2 (:cid (core/find-ability-card "Gila Hands Arcology [1]"))))
        (let [out (with-out-str (display/list-playables))]
          (is (str/includes? out "  - Gila Hands Arcology [1]: Ability 0 - Gain 3 credits\n") out)
          (is (not (str/includes? out "  - Gila Hands Arcology [0]: Ability 0 - Gain 3 credits\n")) out)
          (is (str/includes? out "Total: 0 playable cards, 4 playable abilities, 3 basic actions") out)))
      (finally (reset! state/client-state original)))))

(deftest duplicate-scored-copies-refuse-once
  (let [original @state/client-state]
    (try
      (reset! state/client-state
              (assoc-in (fixture-state) [:game-state :corp :scored]
                        [(card "Gila Hands Arcology" [:scored] 2)
                         (card "Gila Hands Arcology" [:scored] 8)]))
      (let [out (with-out-str (actions/use-ability! "Gila Hands Arcology" 0))]
        (is (= 1 (count (re-seq #"Multiple copies" out))) out)
        (is (str/includes? out "\"Gila Hands Arcology [0]\" (scored area)") out)
        (is (str/includes? out "\"Gila Hands Arcology [1]\" (scored area)") out)
        (is (not (str/includes? out "Card not found")) out)
        (is (= 8 (:cid (core/find-ability-card "Gila Hands Arcology [1]")))))
      (finally (reset! state/client-state original)))))

(deftest parent-installed-resolution-is-preserved
  ;; Captured by loading 184a467f6~1:dev/src/clj/ai_core.clj and invoking its
  ;; resolver on these same fixtures (ai-parent-capture-test, run offline).
  (let [original @state/client-state
        ice1 {:title "Palisade" :cid 1 :zone [:servers :remote1 :ices] :side "Corp" :rezzed true}
        ice2 {:title "Palisade" :cid 2 :zone [:servers :remote2 :ices] :side "Corp" :rezzed false}
        asset1 {:title "Nico Campaign" :cid 3 :zone [:servers :remote1 :content] :side "Corp" :rezzed true}
        asset2 {:title "Nico Campaign" :cid 4 :zone [:servers :remote2 :content] :side "Corp" :rezzed false}
        host {:title "Host" :cid 5 :zone [:servers :remote3 :content] :side "Corp" :rezzed true
              :hosted [{:title "Hosted Corp" :cid 6 :zone [:onhost] :side "Corp" :installed true}]}]
    (try
      (reset! state/client-state {:side "corp"
                                   :game-state {:encounters {:ice ice2}
                                                :corp {:servers {:remote1 {:ices [ice1] :content [asset1]}
                                                                 :remote2 {:ices [ice2] :content [asset2]}
                                                                 :remote3 {:content [host]}}}}})
      (let [picked (atom nil)
            out (with-out-str (reset! picked (core/find-ability-card "Palisade")))]
        (is (= 2 (:cid @picked)))
        (is (= "→ 2 copies of 'Palisade' installed — using the one in the active run (Server 2). Use \"Palisade [N]\" to target another.\n"
               out)))
      (is (= 1 (:cid (core/find-ability-card "Palisade [0]"))))
      (is (= 2 (:cid (core/find-ability-card "Palisade [1]"))))
      (let [picked (atom nil)
            out (with-out-str (reset! picked (core/find-ability-card "Nico Campaign")))]
        (is (nil? @picked))
        (is (= (str "❓ Multiple copies of 'Nico Campaign' installed (2 found)\n"
                    "   Specify which one:\n"
                    "   → \"Nico Campaign [0]\" (Server 1, rezzed)\n"
                    "   → \"Nico Campaign [1]\" (Server 2, unrezzed)\n") out)))
      (let [expected (str "❓ Multiple copies of 'Nico Campaign' installed (2 found)\n"
                          "   Specify which one:\n"
                          "   → \"Nico Campaign [0]\" (Server 1, rezzed)\n"
                          "   → \"Nico Campaign [1]\" (Server 2, unrezzed)\n"
                          "   Re-run with the [N] suffix, e.g. \"Nico Campaign [0]\"\n")]
        (is (= expected (with-out-str (actions/use-ability! "Nico Campaign" 0))))
        (is (= expected (with-out-str (display/show-card-abilities "Nico Campaign")))))
      (is (= 3 (:cid (core/find-ability-card "Nico Campaign [0]"))))
      (let [picked (atom nil)
            out (with-out-str (reset! picked (core/find-ability-card "Hosted Corp")))]
        (is (nil? @picked))
        (is (= "" out)))
      (finally (reset! state/client-state original)))))

(deftest nowhere-miss-names-searched-zones
  (let [original @state/client-state]
    (try
      (reset! state/client-state (fixture-state))
      (let [out (with-out-str (actions/use-ability! "Absent" 0))]
        (is (= "❌ No card of yours with a usable ability by that name found in installed cards, your scored area, identity, current, or play area: Absent\n" out)))
      (finally (reset! state/client-state original)))))

(deftest runner-installed-copy-order-stays-ahead-of-fallback
  (let [original @state/client-state
        r1 {:title "Leech" :cid 11 :zone [:rig :program] :side "Runner"}
        r2 {:title "Leech" :cid 12 :zone [:rig :program] :side "Runner"}]
    (try
      (reset! state/client-state
              {:side "runner"
               :game-state {:runner {:rig {:program [r1 r2] :hardware [] :resource []}
                                     :scored [{:title "Leech" :cid 13 :zone [:scored] :side "Runner"}]}
                            :corp {:servers {}}}})
      (let [out (with-out-str (core/find-ability-card "Leech"))]
        (is (= (str "❓ Multiple copies of 'Leech' in your ability zones (3 found)\n"
                    "   Specify which one:\n"
                    "   → \"Leech [0]\" (program)\n"
                    "   → \"Leech [1]\" (program)\n"
                    "   → \"Leech [2]\" (scored area)\n") out)))
      (is (= 11 (:cid (core/find-ability-card "Leech [0]"))))
      (is (= 12 (:cid (core/find-ability-card "Leech [1]"))))
      (is (= 13 (:cid (core/find-ability-card "Leech [2]"))))
      (finally (reset! state/client-state original)))))

(deftest opponent-card-in-my-scored-area-is-not-my-ability
  (let [original @state/client-state]
    (try
      (reset! state/client-state
              {:side "runner"
               :game-state {:runner {:scored [(card "Gila Hands Arcology" ["scored"] 2)]
                                     :rig {:program [] :hardware [] :resource []}}
                            :corp {:servers {}}}})
      (is (nil? (core/find-ability-card "Gila Hands Arcology")))
      (is (= (str "❌ No card of yours with a usable ability by that name found in installed cards, your scored area, identity, current, or play area: Gila Hands Arcology\n"
                  "   A same-titled card in one of your zones belongs to the opponent; its abilities are not yours to use.\n")
             (with-out-str (actions/use-ability! "Gila Hands Arcology" 0))))
      (finally (reset! state/client-state original)))))
