(ns ai-prompt-values-test
  "Issue #325: value prompts must be rendered, answered and held mid-run."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [ai-prompts :as prompts]
            [ai-display :as display]
            [ai-state :as state]
            [ai-basic-actions :as basic]
            [ai-websocket-client-v2 :as ws]
            [ai-runs :as runs]
            [ai-run-pass :as run-pass]
            [ai-run-runner-handlers :as runner-handlers]
            [ai-heuristic-corp :as corp-bot]
            [ai-heuristic-runner :as runner-bot]
            [jinteki.cards :as cards]))

(def value-prompts
  [{:msg "Boost trace strength?" :prompt-type "trace" :choices 5
    :base 4 :bonus 1 :link 2 :player "corp" :corp-credits 5 :runner-credits 3}
   {:msg "Choose an amount" :prompt-type "other" :choices {:number 4 :minimum 2 :default 3}}
   {:msg "Pay credits" :prompt-type "other" :choices "credit"}
   {:msg "Move counters" :prompt-type "other" :choices {:counter "credit"}}
   {:msg "Name a Runner card" :prompt-type "card-title"
    :choices {:card-title true :autocomplete ["Sure Gamble"]}}])

(defn- seat-state [side prompt]
  {:side side :gameid "game-325"
   :game-state (assoc {:corp {:credit 5} :runner {:credit 3}}
                      (keyword side)
                      {:prompt-state (assoc prompt :eid {:eid 325}
                                            :card {:title "Sealed Vault"
                                                   :counter {:credit 3}})
                       :credit 5})})

(deftest value-prompt-displays-and-compact-summary
  (doseq [prompt value-prompts]
    (let [cs (seat-state "corp" prompt)
          out (with-out-str (display/show-prompt-detailed cs {:full? true}))]
      (is (str/includes? out (:msg prompt)))
      (is (str/includes? out "choose-value") (str prompt "\n" out))
      (is (not (str/includes? out "UUID: nil")) out)))
  (let [out (with-out-str (display/show-prompt-detailed
                            (seat-state "corp" (first value-prompts)) {:full? true}))]
    (doseq [s ["base" "link" "corp" "runner" "4" "2"]]
      (is (str/includes? (str/lower-case out) s) out)))
  (doseq [prompt value-prompts]
    (reset! state/client-state (seat-state "corp" prompt))
    (try
      (let [simple (with-out-str (display/show-prompt))
            compact (with-out-str (display/status-compact))]
        (is (str/includes? simple "choose-value") simple)
        (is (str/includes? compact "choose-value") compact))
      (finally (reset! state/client-state {})))))

(deftest value-prompt-send-uses-direct-wire-values
  (doseq [[prompt value] (map vector value-prompts [0 3 2 2 "Sure Gamble"])]
    (let [sent (atom nil)]
      (with-redefs [ws/send-message! (fn [_ data] (reset! sent data) true)
                    prompts/wait-for-prompt-change! (fn [& _] true)
                    basic/check-auto-end-turn! (fn [] nil)]
        (reset! state/client-state (seat-state "corp" prompt))
        (try
          (with-out-str (prompts/choose-by-value! (str value)))
          (is (= {:choice value :eid {:eid 325}} (:args @sent)) (str prompt " " @sent))
          (finally (reset! state/client-state {})))))))

(deftest value-prompt-refuses-out-of-range
  (doseq [[prompt invalid range-label]
          [[(first value-prompts) "6" "0–5"]
           [(second value-prompts) "1" "2–4"]
           [(nth value-prompts 2) "6" "0–5"]
           [(nth value-prompts 3) "4" "0–3"]]]
    (let [sent (atom nil)]
      (reset! state/client-state (seat-state "corp" prompt))
      (try
        (let [out (with-redefs [ws/send-message! (fn [_ data] (reset! sent data) true)]
                    (with-out-str (prompts/choose-by-value! invalid)))]
          (is (nil? @sent))
          (is (str/includes? out range-label) out))
        (finally (reset! state/client-state {}))))))

(deftest positional-choose-steers-to-value-verb
  (doseq [prompt value-prompts]
    (reset! state/client-state (seat-state "corp" prompt))
    (try
      (let [out (with-out-str (prompts/choose-option! 0))]
        (is (str/includes? out "choose-value") out))
      (finally (reset! state/client-state {})))))

(deftest card-name-validation-and-engine-rejection-are-visible
  (let [prompt (last value-prompts)
        sent (atom nil)]
    (reset! state/client-state (seat-state "corp" prompt))
    (try
      (with-redefs [cards/all-cards (atom {"Sure Gamble" {:title "Sure Gamble"}})
                    ws/send-message! (fn [_ data] (reset! sent data) true)]
        (let [out (with-out-str (prompts/choose-by-value! "Not a Card"))]
          (is (nil? @sent))
          (is (str/includes? out "not a valid card title") out))
        (with-redefs [prompts/wait-for-prompt-change! (fn [& _] false)]
          (let [out (with-out-str (prompts/choose-by-value! "Sure Gamble"))]
            (is (= "Sure Gamble" (get-in @sent [:args :choice])))
            (is (str/includes? out "prompt unchanged") out))))
      (finally (reset! state/client-state {})))))

(deftest every-value-shape-blocks-and-is-not-auto-answered
  (doseq [prompt value-prompts]
    (let [prompt (assoc prompt :eid {:eid 325})]
      (is (state/engine-blocking-prompt? prompt) (str prompt))
      (is (true? (runs/has-real-decision? prompt)) (str prompt))
      (is (true? (boolean (runs/seat-owns-trigger-decision? prompt))) (str prompt))
      (let [result (atom nil)
            out (with-out-str (reset! result (runs/handle-real-decision {:my-prompt prompt})))]
        (is (= :decision-required (:status @result)) (str prompt))
        (is (str/includes? out "choose-value") out))
      (reset! state/client-state (seat-state "corp" prompt))
      (try
        (let [result (atom nil)
              sent (atom nil)
              out (with-redefs [ws/send-message! (fn [& args] (reset! sent args) true)]
                    (with-out-str (reset! result (run-pass/pass-priority! "game-325"
                                                                     :window "encounter-ice"))))]
          (is (= :decision-required (:status @result)) (str prompt))
          (is (nil? @sent) "No continue may pass over an owned value prompt")
          (is (str/includes? out "choose-value") out))
        (finally (reset! state/client-state {})))
      (let [sent (atom nil)]
        (with-redefs [ws/send-message! (fn [& args] (reset! sent args) true)]
          (is (nil? (runs/handle-auto-choice {:my-prompt prompt :gameid "game-325"})))
          (is (nil? @sent) "Auto-choice must never send a primitive prompt answer")))
      (is (nil? (runner-handlers/handle-auto-select-single-card
                  {:side "runner" :my-prompt prompt :gameid "game-325"}))))))

(deftest heuristic-bots-answer-value-prompts
  (doseq [[side bot prompt expected]
          [["corp" corp-bot/handle-prompt-if-needed (first value-prompts) "0"]
           ["corp" corp-bot/handle-prompt-if-needed (second value-prompts) "3"]
           ["corp" corp-bot/handle-prompt-if-needed (nth value-prompts 2) "0"]
           ["corp" corp-bot/handle-prompt-if-needed (nth value-prompts 3) "0"]
           ["corp" corp-bot/handle-prompt-if-needed (nth value-prompts 4) "Sure Gamble"]
           ["runner" runner-bot/handle-prompt-if-needed
            {:msg "Name an agenda" :prompt-type "card-title"
             :choices {:card-title true :autocomplete ["Project Atlas"]}}
            "Project Atlas"]]]
    (let [answer (atom nil)]
      (reset! state/client-state (seat-state side prompt))
      (try
        (with-redefs [prompts/choose-by-value! (fn [v] (reset! answer v) {:status :success})]
          (is (true? (bot)) (str prompt))
          (is (= expected @answer) (str prompt)))
        (finally (reset! state/client-state {}))))))
