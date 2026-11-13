(ns game.ai-pending-card-action-wire-test
  "#326 item 32: card actions against engine-built, JSON-round-tripped seat state."
  (:require [ai-card-actions :as actions]
            [ai-core :as ai-core]
            [ai-state :as ai-state]
            [ai-websocket-client-v2 :as ws]
            [cheshire.core :as json]
            [clojure.test :refer :all]
            [game.core :as game-core]
            [game.core.diffs :as diffs]
            [game.main :as main]
            [game.test-framework :refer :all]))

(defn- seat-state [state side]
  {:side (name side)
   :gameid "pending-wire-test"
   :game-state (-> (diffs/public-states state)
                   (get (if (= side :corp) :corp-state :runner-state))
                   json/generate-string
                   (json/parse-string true))})

(defn- send-from-seat [state side f]
  (reset! ai-state/client-state (seat-state state side))
  (let [before (:game-state @ai-state/client-state)]
  (with-redefs [ws/send-message! (fn [event {:keys [command args]}]
                                   (is (= :game/action event))
                                   (main/handle-action state side command args)
                                   (reset! ai-state/client-state (seat-state state side))
                                   :confirmed)
                ai-core/show-turn-indicator (fn [] nil)]
    (let [result (atom nil)
          out (with-out-str (reset! result (f)))
          board (:game-state @ai-state/client-state)
          prompt (get-in board [side :prompt-state])]
      {:result @result :out out :before before :board board :prompt prompt}))))

(use-fixtures :each (fn [t] (try (t) (finally (reset! ai-state/client-state {})))))

(deftest psychographics-number-pending-wire
  (do-game
    (new-game {:corp {:hand ["Psychographics" "Project Junebug"]
                      :deck [(qty "Hedge Fund" 10)] :credits 10}})
    (gain-tags state :runner 4)
    (play-from-hand state :corp "Project Junebug" "New remote")
    (let [{:keys [result out before board prompt]} (send-from-seat state :corp
                                        #(actions/play-card! "Psychographics"))]
      (is (= (:log before) (:log board)) "no confirming play log line yet")
      (is (some #(= "Psychographics" (:title %)) (get-in before [:corp :hand])))
      (is (not-any? #(= "Psychographics" (:title %)) (get-in board [:corp :hand])))
      (is (some #(= "Psychographics" (:title %)) (get-in board [:corp :play-area])))
      (is (not= (get-in before [:corp :prompt-state :eid]) (:eid prompt)))
      (is (= "Psychographics" (get-in prompt [:card :title])))
      (is (= "other" (:prompt-type prompt)))
      (is (= {:number 4 :default 0 :minimum 0} (:choices prompt)))
      (is (= :waiting-input (:status result)) out)
      (is (re-find #"(?i)pending|waiting for input" out) out)
      (is (not (re-find #"Failed" out)) out)
      (is (re-find #"choose-value" out) out)
      (is (= "How many credits do you want to spend?" (:msg prompt))))))

(deftest paladin-install-payment-pending-wire
  (do-game
    (new-game {:corp {:hand ["Hedge Fund"] :deck [(qty "Hedge Fund" 10)]}
               :runner {:hand ["Paladin Poemu" "Corroder"] :credits 10}})
    (take-credits state :corp)
    (play-from-hand state :runner "Paladin Poemu")
    (game-core/add-counter state :runner (game-core/make-eid state)
                           (get-resource state 0) :credit 2)
    (let [{:keys [result out before board prompt]} (send-from-seat state :runner
                                        #(actions/install-card! "Corroder"))]
      (is (= (:log before) (:log board)) "no confirming install log line yet")
      (is (some #(= "Corroder" (:title %)) (get-in before [:runner :hand])))
      (is (some #(= "Corroder" (:title %)) (get-in board [:runner :play-area])))
      (is (not= (get-in before [:runner :prompt-state :eid]) (:eid prompt)))
      (is (= "Corroder" (get-in prompt [:card :title])))
      (is (= "select" (:prompt-type prompt)))
      (is (= "Done" (get-in prompt [:choices 0 :value])))
      (is (= :waiting-input (:status result)) out)
      (is (re-find #"(?i)pending|waiting for input" out) out)
      (is (not (re-find #"Failed" out)) out)
      (is (re-find #"choose-card <N>" out) out)
      (is (seq (:selectable prompt))))))

(deftest patchwork-event-pending-wire
  (do-game
    (new-game {:corp {:hand ["Hedge Fund"] :deck [(qty "Hedge Fund" 10)]}
               :runner {:hand ["Patchwork" "Dirty Laundry" "Easy Mark"] :credits 12}})
    (take-credits state :corp)
    (play-from-hand state :runner "Patchwork")
    (let [{:keys [result out before board prompt]} (send-from-seat state :runner
                                        #(actions/play-card! "Dirty Laundry"))]
      (is (= (:log before) (:log board)) "no confirming event log line yet")
      (is (some #(= "Dirty Laundry" (:title %)) (get-in before [:runner :hand])))
      (is (some #(= "Dirty Laundry" (:title %)) (get-in board [:runner :play-area])))
      (is (not= (get-in before [:runner :prompt-state :eid]) (:eid prompt)))
      (is (= "Dirty Laundry" (get-in prompt [:card :title])))
      (is (= "select" (:prompt-type prompt)))
      (is (= "Done" (get-in prompt [:choices 0 :value])))
      (is (= :waiting-input (:status result)) out)
      (is (re-find #"(?i)pending|waiting for input" out) out)
      (is (not (re-find #"Failed" out)) out)
      (is (re-find #"choose-card <N>" out) out)
      (is (seq (:selectable prompt)))
      (let [blocked (with-redefs [ws/send-message! (fn [& _]
                                                   (throw (ex-info "blocked action sent" {})))]
                      (actions/play-card! "Easy Mark"))]
        (is (= :error (:status blocked)) "an already open prompt still blocks the next action")
        (is (= (:log board) (:log (:game-state (seat-state state :runner))))
            "the blocked second play sent nothing")))))
