(ns game.ai-prompt-values-wire-test
  "Issue #325: real cards, public serializer, JSON round trip, and UI choice payloads."
  (:require [game.core.diffs :as diffs]
            [ai-prompts :as prompts]
            [game.main :as main]
            [game.test-framework :refer :all]
            [cheshire.core :as json]
            [clojure.test :refer :all]))

(defn- wire-prompt [state side]
  (-> (diffs/public-states state)
      (get (if (= side :corp) :corp-state :runner-state))
      json/generate-string
      (json/parse-string true)
      (get-in [side :prompt-state])))

(defn- answer! [state side prompt value]
  ;; board.cljs sends the primitive choice and this prompt's eid directly.
  (main/handle-action state side "choice" {:eid (:eid prompt) :choice value}))

(deftest ci-fund-number-wire
  (do-game
    (new-game {:corp {:hand ["C.I. Fund"] :credits 8}})
    (play-from-hand state :corp "C.I. Fund" "New remote")
    (let [ci (get-content state :remote1 0)]
      (rez state :corp ci)
      (card-ability state :corp ci 0)
      (let [p (wire-prompt state :corp)]
        (is (= "other" (:prompt-type p)))
        (is (= {:number 3 :minimum 0 :default 0} (:choices p)))
        (is (= "How many credits do you want to store?" (:msg p)))
        (answer! state :corp p "2")
        (is (= (:eid p) (:eid (wire-prompt state :corp)))
            "Wrong type is logged and leaves the prompt open")
        (answer! state :corp p 2)
        (is (= 2 (get-in (refresh ci) [:counter :credit])))))))

(deftest engine-does-not-enforce-number-selector-range
  ;; board.cljs offers 0..3, but resolve-prompt only checks number?. This
  ;; explains why the client must refuse a bad value before sending it.
  (do-game
    (new-game {:corp {:hand ["C.I. Fund"] :credits 8}})
    (play-from-hand state :corp "C.I. Fund" "New remote")
    (let [ci (get-content state :remote1 0)]
      (rez state :corp ci)
      (card-ability state :corp ci 0)
      (let [p (wire-prompt state :corp)]
        (is (= 3 (get-in p [:choices :number])))
        (answer! state :corp p 4)
        (is (= 4 (get-in (refresh ci) [:counter :credit]))
            "Engine accepts the out-of-range number; UI/client must bound it")))))

(deftest alexa-credit-wire
  (do-game
    (new-game {:corp {:hand ["Alexa Belsky"] :deck ["Hedge Fund" "Ice Wall"]}})
    (play-from-hand state :corp "Alexa Belsky" "New remote")
    (let [alexa (get-content state :remote1 0)]
      (rez state :corp alexa)
      (card-ability state :corp alexa 0)
      (let [p (wire-prompt state :runner)]
        (is (= "credit" (:choices p)))
        (is (= "How many credits do you want to pay?" (:msg p)))
        (answer! state :runner p 0)
        (is (nil? (wire-prompt state :runner)))))))

(deftest sealed-vault-counter-wire
  (do-game
    (new-game {:corp {:hand ["Sealed Vault"] :credits 10}})
    (play-from-hand state :corp "Sealed Vault" "New remote")
    (let [sv (get-content state :remote1 0)]
      (rez state :corp sv)
      (card-ability state :corp sv 0)
      (answer! state :corp (wire-prompt state :corp) 3)
      (card-ability state :corp sv 1)
      (let [p (wire-prompt state :corp)]
        (is (= {:counter "credit"} (:choices p)))
        (is (nil? (get-in p [:card :counter]))
            "Unmodified engine sends a small prompt card reference")
        (let [board (-> (diffs/public-states state) :corp-state
                        json/generate-string (json/parse-string true))
              card (get-in board [:corp :servers :remote1 :content 0])
              cs {:side "corp" :game-state (assoc board :corp
                                                  (assoc (:corp board) :prompt-state p))}]
          (is (= 3 (get-in card [:counter :credit])))
          (is (= [0 3] (prompts/value-prompt-range cs p))))
        (answer! state :corp p 2)
        (is (= 1 (get-in (refresh sv) [:counter :credit])))))))

(deftest targeted-marketing-card-name-wire
  (do-game
    (new-game {:corp {:hand ["Targeted Marketing"]}})
    (play-from-hand state :corp "Targeted Marketing")
    (let [p (wire-prompt state :corp)]
      (is (= "Name a Runner card" (:msg p)))
      (is (= "card-title" (:prompt-type p)))
      (is (string? (get-in p [:choices :card-title]))
          "JSON stringifies the server predicate; autocomplete is the usable list")
      (is (some #{"Sure Gamble"} (get-in p [:choices :autocomplete])))
      (is (not (some #{"Hedge Fund"} (get-in p [:choices :autocomplete]))))
      (answer! state :corp p "Hedge Fund")
      (is (= (:eid p) (:eid (wire-prompt state :corp)))
          "The card-title predicate rejects a real but wrong-side title")
      (answer! state :corp p "Sure Gamble")
      (is (nil? (wire-prompt state :corp))))))

(deftest resistor-trace-wire
  (do-game
    (new-game {:corp {:hand ["Resistor"] :credits 8}})
    (play-from-hand state :corp "Resistor" "HQ")
    (let [ice (get-ice state :hq 0)]
      (rez state :corp ice)
      (take-credits state :corp)
      (run-on state "HQ")
      (run-continue state)
      (fire-subs state ice)
      (let [p (wire-prompt state :corp)]
        (is (= "trace" (:prompt-type p)))
        (is (= 4 (:base p)))
        (is (= 0 (:bonus p)))
        (is (= 0 (:link p)))
        (is (= "corp" (:player p)))
        (is (= (:corp-credits p) (:choices p)))
        (is (= 2 (:unbeatable p)))
        (is (nil? (:strength p)) "Initiator has not yet set trace strength")
        (answer! state :corp p 0)
        (let [reply (wire-prompt state :runner)]
          (is (= "trace" (:prompt-type reply)))
          (is (= 4 (:strength reply)))
          (is (= (:runner-credits reply) (:choices reply)))
          (is (= 4 (:beat-trace reply)))
          (is (nil? (:unbeatable reply)))
          (answer! state :runner reply 0)
          (is (nil? (wire-prompt state :runner))))))))
