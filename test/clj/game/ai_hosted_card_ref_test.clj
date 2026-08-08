(ns game.ai-hosted-card-ref-test
  "Engine premise behind the AI seat's wire card-reference shape.

   The reference client narrows a card to `(select-keys card [:cid :zone :side :host :type])`
   before putting it on the wire (`nr.gameboard.actions/send-command`). Our seat builders
   used the same list MINUS `:host`.

   `get-card` (card.cljc) branches on `:host`: with it, it walks the host's `:hosted`
   collection; without it, it looks the cid up in `(get-in @state [side zone])`. A hosted
   card's zone is `[:onhost]`, which is not a real zone — so the lookup misses and the
   action resolves against `nil`.

   These tests pin what that costs, so the `:host` key can never be dropped again without
   a red test: `select` silently no-ops and `ability` is refused."
  (:require [game.core :as core]
            [game.test-framework :refer :all]
            [clojure.test :refer :all]))

(defn- seat-ref
  "The card reference our seat builders put on the wire."
  [card]
  (select-keys card [:cid :zone :side :host :type]))

(defn- seat-ref-without-host
  "The pre-fix reference: the same list with `:host` dropped."
  [card]
  (dissoc (seat-ref card) :host))

(deftest hosted-card-zone-is-not-a-real-zone
  (testing "a hosted card lives at [:onhost], so a cid+zone lookup cannot find it"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"]}
                 :runner {:credits 15 :hand ["Botulus"]}})
      (play-from-hand state :corp "Ice Wall" "HQ")
      (take-credits state :corp)
      (play-from-hand state :runner "Botulus")
      (click-card state :runner (get-ice state :hq 0))
      (let [bot (first (:hosted (refresh (get-ice state :hq 0))))]
        (is (= [:onhost] (vec (:zone bot))) "hosted cards are zoned :onhost")
        (is (some? (:host bot)) "and carry a :host back-pointer")
        (is (nil? (core/get-card state (seat-ref-without-host bot)))
            "dropping :host makes the card unresolvable")
        (is (= (:cid bot) (:cid (core/get-card state (seat-ref bot))))
            "keeping :host resolves it — this is the only difference")))))

(deftest hosted-card-ability-needs-host-on-the-wire
  (testing "firing a hosted program's ability: refused without :host, works with it"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"]}
                 :runner {:credits 15 :hand ["Botulus"]}})
      (play-from-hand state :corp "Ice Wall" "HQ")
      (take-credits state :corp)
      (play-from-hand state :runner "Botulus")
      (click-card state :runner (get-ice state :hq 0))
      (let [iw (get-ice state :hq 0)
            bot (first (:hosted (refresh iw)))]
        (run-on state :hq)
        (rez state :corp iw)
        (run-continue state)
        ;; pre-fix payload: get-card returns nil, so (:abilities nil) is nil and
        ;; play-ability bails before any prompt is made.
        (core/process-action "ability" state :runner
                             {:card (seat-ref-without-host bot) :ability 0})
        (is (= :run (:prompt-type (get-prompt state :runner)))
            "no :host => no break prompt; the seat is still just sitting in the run")
        (is (= 1 (count (remove :broken (:subroutines (refresh iw)))))
            "the subroutine is still unbroken")
        ;; post-fix payload: identical but for :host.
        (core/process-action "ability" state :runner
                             {:card (seat-ref bot) :ability 0})
        (click-prompt state :runner "End the run")
        (is (zero? (count (remove :broken (:subroutines (refresh iw)))))
            "with :host the ability fires and breaks the subroutine")))))

(deftest hosted-card-select-needs-host-on-the-wire
  (testing "selecting a hosted card at a select prompt: silent no-op without :host"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"]}
                 :runner {:credits 15 :hand ["Botulus" "Scavenge"]}})
      (play-from-hand state :corp "Ice Wall" "HQ")
      (take-credits state :corp)
      (play-from-hand state :runner "Botulus")
      (click-card state :runner (get-ice state :hq 0))
      (let [bot (first (:hosted (refresh (get-ice state :hq 0))))
            ;; NB: identity here is by title, not :cid. A card leaving play for
            ;; deck/hand/discard is given a fresh cid (moving.clj:139), so the
            ;; trashed copy is deliberately not the same object as `bot`.
            still-hosted? (fn [] (some? (seq (:hosted (refresh (get-ice state :hq 0))))))
            in-heap? (fn [] (some #(= "Botulus" (:title %)) (:discard (:runner @state))))]
        (play-from-hand state :runner "Scavenge")
        (is (= :select (:prompt-type (get-prompt state :runner)))
            "Scavenge opens a select prompt over installed programs")
        (let [eid (:eid (get-prompt state :runner))]
          (core/process-action "select" state :runner
                               {:card (seat-ref-without-host bot)
                                :eid eid
                                :shift-key-held false})
          (is (still-hosted?) "no :host => the select is swallowed; nothing was trashed")
          (is (= :select (:prompt-type (get-prompt state :runner)))
              "and the same prompt is still up, with no refusal for the seat to read")
          (core/process-action "select" state :runner
                               {:card (seat-ref bot)
                                :eid eid
                                :shift-key-held false})
          (is (not (still-hosted?)) "with :host the selection lands")
          (is (in-heap?) "and Scavenge trashes the program"))))))
