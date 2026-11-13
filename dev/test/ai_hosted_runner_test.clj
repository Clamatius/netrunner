(ns ai-hosted-runner-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [test-helpers :refer [with-mock-state]]
            [ai-state :as state]
            [ai-core :as core]
            [ai-display :as display]
            [ai-run-tactics :as tactics]
            [ai-run-runner-handlers :as handlers]
            [ai-card-actions :as actions]
            [ai-websocket-client-v2 :as ws]))

;; diffs/card-summary nests :hosted on its host and preserves :host and :counter;
;; private-card-keys withholds the unrezzed Corp ICE title. Cheshire keywordizes
;; object keys, but JSON values such as :side, :type, :zone and :subtypes are strings.
(defn- wire [x] (json/parse-string (json/generate-string x) true))

(def ^:private botulus
  {:cid "bot" :title "Botulus" :side "Runner" :type "Program"
   :installed true :zone ["onhost"] :subtypes ["Virus" "Trojan"]
   :host {:cid "ice" :side "Corp" :zone ["servers" "hq" "ices"]}
   :counter {:virus 1} :current-strength 0
   :abilities [{:label "Break 1 subroutine" :playable true}]})

(def ^:private hosted-corroder
  {:cid "cor" :title "Corroder" :side "Runner" :type "Program"
   :installed true :zone ["onhost"] :subtypes ["Icebreaker" "Fracter"]
   :abilities [{:label "Break barrier subroutine" :playable true}]})

(def ^:private gs
  (wire {:corp {:servers {:hq {:ices [{:cid "ice" :side "Corp" :zone ["servers" "hq" "ices"]
                                      :hosted [botulus]}]}}}
         :runner {:rig {:program [{:cid "lep" :title "Leprechaun" :side "Runner"
                                   :type "Program" :installed true
                                   :hosted [hosted-corroder
                                            {:cid "park" :title "Clone Chip" :side "Runner"
                                             :type "Hardware" :zone ["onhost"]}] }]}
                  :credit 5 :click 2 :hand-count 0 :agenda-point 0}}))

(def ^:private client {:side "runner" :connected true :game-state gs})

(deftest installed-runner-walks-both-roots
  (let [walk (ns-resolve 'ai-state 'installed-runner-cards)]
    (is walk "one shared installed-card walk must exist")
    (when walk
      (is (= #{"Leprechaun" "Corroder" "Botulus"}
             (set (map :title (walk gs)))))
      (is (= #{"Leprechaun" "Corroder" "Botulus" "Nested"}
             (set (map :title (walk
                               (assoc-in gs [:corp :servers :hq :ices 0 :hosted 0 :hosted]
                                         [{:cid "nested" :title "Nested" :side "Runner"
                                           :type "Program" :installed true}])))))))))

(deftest hosted-cards-resolve-and-count
  (with-mock-state client
    (is (= "bot" (:cid (core/find-installed-card "Botulus"))))
    (is (= "cor" (:cid (core/find-installed-card "Corroder"))))
    (is (= 1 (core/installed-title-match-count "Botulus" :runner)))
    (is (= #{"lep" "cor" "bot"} (set (map :cid (state/runner-programs)))))
    (is (= 1 (:total (state/runner-virus-counters gs))))))

(deftest non-breaker-trojans-are-counted-and-rendered
  (let [trojans (for [[cid title n] [["tranq" "Tranquilizer" 2]
                                      ["tryp" "Trypano" 3]]]
                  {:cid cid :title title :type "Program" :side "Runner"
                   :installed true :zone ["onhost"] :counter {:virus n}
                   :host (:host botulus)})
        gs2 (wire (update-in gs [:corp :servers :hq :ices 0 :hosted]
                             into trojans))]
    (is (= #{"Botulus" "Tranquilizer" "Trypano"}
           (set (map :title (filter #(= "ice" (get-in % [:host :cid]))
                                   (state/runner-programs gs2))))))
    (is (= 6 (:total (state/runner-virus-counters gs2))))
    (with-mock-state (assoc client :game-state gs2)
      (let [line (with-out-str (display/show-board-compact))]
        (is (str/includes? line "Tranquilizer(2v)"))
        (is (str/includes? line "Trypano(3v)"))))))

(deftest duplicate-hosted-name-has-an-actionable-index
  (let [gs2 (update-in gs [:runner :rig :program]
                       conj {:cid "top-cor" :title "Corroder" :type "Program"
                             :side "Runner" :installed true :zone ["rig" "program"]})]
    (with-mock-state (assoc client :game-state gs2)
      (is (nil? (core/find-installed-card "Corroder")))
      (is (= "cor" (:cid (core/find-installed-card "Corroder [1]"))))
      (is (= "Corroder [1]"
             (core/installed-runner-reference
               (core/find-installed-card "Corroder [1]") gs2)))
      (is (str/includes? (with-out-str (display/show-board)) "Corroder [1]"))
      (is (str/includes? (with-out-str (display/show-board-compact)) "Corroder [1]"))
      (is (some #(= "Corroder [1]" (:reference %))
                (tactics/list-available-breakers (assoc client :game-state gs2))))
      (is (= 4 (:installed-count (core/capture-state-snapshot)))))))

(deftest hosted-breakers-appear-in-tactics
  (is (= #{"Corroder" "Botulus"}
         (set (map :title (tactics/list-available-breakers client))))))

(deftest hosted-trojan-only-breaks-its-host
  (let [ice (get-in gs [:corp :servers :hq :ices 0])
        other (assoc ice :cid "other")]
    (is (tactics/breaker-matches-ice? botulus ice))
    (is (not (tactics/breaker-matches-ice? botulus other)))
    (let [wrong-state (-> client
                          (assoc-in [:game-state :corp :servers :hq :ices 0]
                                    (assoc other :rezzed true))
                          (assoc-in [:game-state :run]
                                    {:phase "encounter-ice" :server ["hq"]
                                     :position 1}))]
      (is (not (some #(= "Botulus" (:title %))
                     (tactics/list-available-breakers wrong-state)))))
    ;; Living Mural's card-specific :req is same SERVER, not same ICE.
    (is (tactics/trojan-host-matches?
          (assoc botulus :title "Living Mural") other))
    (is (not (tactics/trojan-host-matches?
               (assoc botulus :title "Living Mural")
               (assoc other :zone ["servers" "rd" "ices"]))))))

(deftest use-ability-sends-hosted-reference
  (with-mock-state client
    (let [sent (atom nil)]
      (with-redefs [ws/send-message! (fn [_ payload] (reset! sent payload) true)
                    core/break-refusal-lines (fn [& _] nil)
                    core/verify-ability-in-log (fn [& _] {:status :success})]
        (is (= :success (:status (actions/use-ability! "Botulus" 0))))
        (is (= "ability" (:command @sent)))
        (is (= "bot" (get-in @sent [:args :card :cid])))
        (is (= "ice" (get-in @sent [:args :card :host :cid])))))))

(deftest full-break-selects-hosted-breaker
  (let [ice (assoc (get-in gs [:corp :servers :hq :ices 0])
                   :title "Ice Wall" :rezzed true :strength 2
                   :subtypes ["Barrier"]
                   :subroutines [{:label "End the run" :broken false :fired false}])
        dynamic-bot (assoc botulus :abilities [{:dynamic "auto-pump-and-break"
                                                :label "Fully break Ice Wall"}])
        client (-> client
                   (assoc-in [:game-state :corp :servers :hq :ices 0]
                             (assoc ice :hosted [dynamic-bot]))
                   (assoc-in [:game-state :run] {:phase "encounter-ice"
                                                  :server ["hq"] :position 1}))
        used (atom nil)]
    (handlers/reset-state!)
    (with-redefs [actions/use-ability! (fn [name idx]
                                        (reset! used [name idx]) {:status :success})]
      (is (= :ability-used (:status (handlers/handle-runner-full-break
                                     {:side "runner" :run-phase "encounter-ice"
                                      :state client :strategy {:full-break true}}))))
      (is (= ["Botulus" 0] @used)))
    (let [other (assoc ice :cid "other")]
      (is (nil? (tactics/try-manual-pump-and-break! client other [botulus]))))
    (with-redefs [actions/use-ability! (fn [name idx]
                                        (reset! used [name idx]) {:status :success})]
      (is (= :ability-used (:status (tactics/try-manual-pump-and-break!
                                     client ice [botulus]))))
      (is (= ["Botulus" 0] @used)))
    (reset! used nil)
    (let [wrong-client (-> client
                           (assoc-in [:game-state :corp :servers :hq :ices 0 :cid] "other")
                           (assoc-in [:game-state :run :phase] "encounter-ice"))]
      (with-redefs [actions/use-ability! (fn [& args]
                                          (reset! used args) {:status :success})]
        (is (= :tactic-failed (:status (tactics/handle-runner-tactics
                                        {:side "runner" :run-phase "encounter-ice"
                                         :state wrong-client
                                         :strategy {:tactics {:default {:card "Botulus"
                                                                        :action :use-ability
                                                                        :ability-index 0}}}}))))
        (is (nil? @used))))))

(deftest hosted-trojan-is-in-views
  (with-mock-state client
    (let [board (with-out-str (display/show-board))
          compact (with-out-str (display/show-board-compact))
          status (with-out-str (display/status-compact))]
      (is (str/includes? board "Botulus"))
      (is (str/includes? board "[1virus]"))
      (is (str/includes? compact "Botulus(1v)"))
      (is (str/includes? status "Botulus 1"))
      (is (not (str/includes? board "Ice Wall"))))))

(deftest corp-content-host-is-also-a-root
  (let [guest {:cid "guest" :title "Probe" :side "Runner" :type "Program"
               :installed true :zone ["onhost"] :counter {:virus 2}
               :host {:cid "asset" :side "Corp" :zone ["servers" "hq" "content"]}}
        gs2 (assoc-in gs [:corp :servers :hq :content]
                      [{:cid "asset" :title "Secret Asset" :side "Corp"
                        :zone ["servers" "hq" "content"]
                        :hosted [guest]}])]
    (is (= "unrezzed card #0 HQ" (state/runner-host-label gs2 guest)))
    (is (some #(= "Probe" (:title %)) (state/installed-runner-cards gs2)))
    (with-mock-state (assoc client :game-state gs2)
      (let [board (with-out-str (display/show-board))
            compact (with-out-str (display/show-board-compact))
            status (with-out-str (display/status-compact))]
        (is (str/includes? board "Probe (Program) [2virus]"))
        (is (str/includes? compact "Probe(2v)@unrezzed card #0 HQ"))
        (is (str/includes? status "Probe 2 on unrezzed card #0 HQ"))
        (is (not (str/includes? board "Secret Asset")))))))
