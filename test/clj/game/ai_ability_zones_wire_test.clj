(ns game.ai-ability-zones-wire-test
  "The UI's ordinary ability-click zones, through the actual seat serializer and
   the same card payload the browser sends."
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [ai-card-actions :as actions]
            [ai-core :as ai-core]
            [ai-display :as display]
            [ai-state :as ai-state]
            [ai-websocket-client-v2 :as ws]
            [game.core :as core]
            [game.core.diffs :as diffs]
            [game.test-framework :refer :all]))

(defn- wire [state]
  (-> state diffs/public-states :corp-state json/generate-string
      (json/parse-string true)))

(defn- ui-ref [card]
  (select-keys card [:cid :side :host :type :zone :ghost :flashback-fake-in-hand]))

(defn- fires? [state card]
  (let [before (get-in @state [:corp :credit])]
    (core/process-action "ability" state :corp {:card (ui-ref card) :ability 0})
    (= 3 (- (get-in @state [:corp :credit]) before))))

(deftest installed-rig-wire-and-ability
  (do-game
    (new-game {:corp {:deck [(qty "Hedge Fund" 5)]}
               :runner {:hand ["Smartware Distributor"]}})
    (take-credits state :corp)
    (play-from-hand state :runner "Smartware Distributor")
    (let [card (first (get-in (-> state diffs/public-states :runner-state
                                   json/generate-string (json/parse-string true))
                              [:runner :rig :resource]))]
      (is (= ["rig" "resource"] (:zone card)))
      (is (true? (get-in card [:abilities 0 :playable])))
      (core/process-action "ability" state :runner {:card (ui-ref card) :ability 0})
      (is (= 3 (get-in (get-resource state 0) [:counter :credit]))))))

(deftest installed-server-wire-and-ability
  (do-game
    (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Spin Doctor"]}})
    (play-from-hand state :corp "Spin Doctor" "New remote")
    (rez state :corp (get-content state :remote1 0))
    (let [card (first (get-in (wire state) [:corp :servers :remote1 :content]))]
      (is (= ["servers" "remote1" "content"] (:zone card)))
      (is (true? (get-in card [:abilities 0 :playable])))
      (core/process-action "ability" state :corp {:card (ui-ref card) :ability 0})
      (is (= "Spin Doctor" (:title (first (get-in @state [:corp :rfg]))))))))

(deftest scored-agenda-wire-and-ability
  (do-game
    (new-game {:corp {:deck [(qty "Hedge Fund" 5)]
                      :hand ["Gila Hands Arcology"]}})
    (play-and-score state "Gila Hands Arcology")
    (core/gain state :corp :click 2)
    (let [card (first (get-in (wire state) [:corp :scored]))]
      (is (= "Gila Hands Arcology" (:title card)))
      (is (= ["scored"] (:zone card)))
      (is (true? (get-in card [:abilities 0 :playable])))
      (let [original @ai-state/client-state
            before (get-in @state [:corp :credit])]
        (try
          (reset! ai-state/client-state {:side "corp" :gameid "offline"
                                         :game-state (wire state)})
          (is (re-find #"Gain 3" (with-out-str (display/show-card-abilities "Gila Hands Arcology"))))
          (is (re-find #"Gain 3" (with-out-str (display/list-playables))))
          (is (not (re-find #"Own playable abilities" (with-out-str (display/show-board)))))
          (with-redefs [ws/send-message! (fn [_ {:keys [command args]}]
                                            (core/process-action command state :corp args)
                                            (swap! ai-state/client-state assoc :game-state (wire state))
                                            true)]
            (let [result (atom nil)
                  out (with-out-str (reset! result (actions/use-ability! "Gila Hands Arcology" 0)))]
              (is (= :success (:status @result)))
              (is (re-find #"⚡ Used ability: Gila Hands Arcology" out) out)))
          (is (= 3 (- (get-in @state [:corp :credit]) before))
              "the client's mirrored payload fired the scored ability")
          (is (re-find #"Corp spends .* to use Gila Hands Arcology to gain 3"
                       (:text (last (:log (wire state))))))
          (finally (reset! ai-state/client-state original)))))))

(deftest identity-wire-and-ability
  (do-game
    (new-game {:corp {:id "Earth Station: SEA Headquarters"
                      :deck [(qty "Hedge Fund" 5)]}})
    (let [card (get-in (wire state) [:corp :identity])]
      (is (= "Identity" (:type card)))
      (is (true? (get-in card [:abilities 0 :playable])))
      (core/process-action "ability" state :corp {:card (ui-ref card) :ability 0})
      (is (:flipped (get-in @state [:corp :identity]))))))

(deftest current-and-play-area-wire-and-ability
  ;; The engine has the same active-card lookup for both zones. Stage a real
  ;; card with a simple paid ability there to isolate the wire/lookup contract.
  (doseq [zone [:current :play-area]]
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)]
                        :hand ["Gila Hands Arcology"]}})
      (core/move state :corp (find-card "Gila Hands Arcology" (get-in @state [:corp :hand])) zone)
      (let [card (first (get-in (wire state) [:corp zone]))]
        (is (= "Gila Hands Arcology" (:title card)))
        (is (= [(name zone)] (:zone card)))
        (is (true? (get-in card [:abilities 0 :playable])))
        (is (fires? state card))))))

(deftest non-click-zones-still-carry-card-data
  (do-game
    (new-game {:corp {:deck [(qty "Hedge Fund" 5)]
                      :hand ["Gila Hands Arcology"]}})
    (let [hand-card (first (get-in (wire state) [:corp :hand]))
          basic (get-in (wire state) [:corp :basic-action-card])]
      (is (= ["hand"] (:zone hand-card)))
      (is (seq (:abilities hand-card)))
      (is (not (true? (get-in hand-card [:abilities 0 :playable]))))
      (is (seq (:abilities basic)))
      (is (true? (get-in basic [:abilities 0 :playable]))))
    (core/move state :corp (find-card "Gila Hands Arcology" (get-in @state [:corp :hand])) :discard)
    (let [card (first (get-in (wire state) [:corp :discard]))]
      (is (= ["discard"] (:zone card)))
      (is (seq (:abilities card)))
      (is (not (true? (get-in card [:abilities 0 :playable])))))
    (core/move state :corp (find-card "Gila Hands Arcology" (get-in @state [:corp :discard])) :rfg)
    (let [card (first (get-in (wire state) [:corp :rfg]))]
      (is (= ["rfg"] (:zone card)))
      (is (seq (:abilities card)))
      (is (not (true? (get-in card [:abilities 0 :playable])))))))
