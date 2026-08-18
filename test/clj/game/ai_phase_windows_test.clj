(ns game.ai-phase-windows-test
  "Engine premise behind the AI seat's phase-1.2 and post-discard commands.

   Both windows are opened by the engine at every turn boundary and closed again
   immediately when nothing is holding them (turns.clj:136, :262). When a card IS
   holding one, the only exit is an explicit command: `end-phase-12` /
   `phase-12-pass-priority`, or `end-post-discard` /
   `post-discard-pass-priority`. There is no timer and no implicit exit. The
   reference client puts a button on each (`board.cljs/basic-actions`); our seat
   had none of the four, and `start-turn` told the seat to \"wait\" for a pause
   that nothing in its surface could clear.

   These tests pin what the seat is up against: the window is real, it persists,
   the whole start-of-turn (mandatory draw, every turn-begins trigger) is held
   behind it while ordinary actions are NOT blocked, and the consent variant needs
   BOTH sides. They are engine-level so they stay true if our client is rewritten."
  (:require [game.core :as core]
            [game.core.card :refer [get-counters]]
            [game.test-framework :refer :all]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(deftest phase-12-window-persists-until-commanded
  (testing "a rezzed start-of-turn card holds phase 1.2 open across the turn start"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Anson Rose"]}})
      (play-from-hand state :corp "Anson Rose" "New remote")
      (rez state :corp (get-content state :remote1 0))
      (take-credits state :corp)
      (take-credits state :runner)
      (is (:corp-phase-12 @state) "the window is open at the start of the Corp turn")
      (is (nil? (:requires-consent (:corp-phase-12 @state)))
          "and with no opponent-forcing card it is the Corp's alone to close")
      ;; The engine does not close it on its own: no amount of waiting helps.
      (core/process-action "credit" state :corp nil)
      (is (:corp-phase-12 @state) "still open after an unrelated action")
      (core/process-action "end-phase-12" state :corp nil)
      (is (nil? (:corp-phase-12 @state)) "only the command closes it"))))

(deftest phase-12-holds-back-the-whole-turn-start
  (testing "the mandatory draw AND every turn-begins trigger wait on the command"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Anson Rose"]}})
      (play-from-hand state :corp "Anson Rose" "New remote")
      (rez state :corp (get-content state :remote1 0))
      (take-credits state :corp)
      (take-credits state :runner)
      (let [anson (get-content state :remote1 0)
            hand-size (count (:hand (:corp @state)))
            ;; log entries are {<log-side> {:user … :text …}}, not bare messages.
            ;; Count rather than test for presence: turn 1's own mandatory draw is
            ;; already in the log, so a substring check would always be true.
            draw-lines (fn [] (count (filter #(when-let [t (get-in % [:public :text])]
                                                (str/includes? t "mandatory start of turn draw"))
                                             (:log @state))))
            draws-before (atom nil)]
        ;; The engine does NOT gate ordinary actions on the window — a seat can
        ;; spend its whole turn here without noticing (this is the "engine trusts
        ;; the client" seam; only board.cljs greys the buttons out).
        (let [before (:credit (:corp @state))]
          (core/process-action "credit" state :corp nil)
          (is (= (inc before) (:credit (:corp @state)))
              "the action itself goes through — the engine does not gate on the window")
          (is (:corp-phase-12 @state) "and taking it does not close the window"))
        ;; Meanwhile nothing that should happen at the start of a turn has.
        (is (zero? (get-counters (refresh anson) :advancement))
            "no :corp-turn-begins trigger has fired")
        (is (= hand-size (count (:hand (:corp @state))))
            "no mandatory draw")
        (reset! draws-before (draw-lines))
        (is (= @draws-before (draw-lines)) "and nothing new in the log says otherwise")
        (core/process-action "end-phase-12" state :corp nil)
        (is (= 1 (get-counters (refresh anson) :advancement))
            "the command is what fires the turn-begins triggers")
        (is (= (inc hand-size) (count (:hand (:corp @state))))
            "and takes the mandatory draw")
        (is (= (inc @draws-before) (draw-lines))
            "and says so in the log — which is where our seat reads turn boundaries")))))

(deftest phase-12-consent-needs-both-seats
  (testing "a force-phase-12-opponent card needs a pass from each side"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Anson Rose"]}})
      (play-from-hand state :corp "Anson Rose" "New remote")
      (rez state :corp (get-content state :remote1 0))
      (swap! state assoc-in [:runner :properties :force-phase-12-opponent] true)
      (take-credits state :corp)
      (take-credits state :runner)
      (is (:requires-consent (:corp-phase-12 @state))
          "the window now requires consent")
      (core/process-action "phase-12-pass-priority" state :corp nil)
      (is (:corp-phase-12 @state) "one pass is not enough")
      (core/process-action "phase-12-pass-priority" state :runner nil)
      (is (nil? (:corp-phase-12 @state))
          "the window closes only when both seats have passed — so the OPPONENT
           needs this command too, not just the active player"))))

(deftest post-discard-window-persists-until-commanded
  (testing "a forced post-discard window holds the turn boundary open"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Hedge Fund"]}})
      (swap! state assoc-in [:corp :properties :force-post-discard-self] true)
      (core/process-action "end-turn" state :corp nil)
      (is (:corp-post-discard @state) "the turn has not actually ended")
      (is (= :corp (:active-player @state)) "and it is still the Corp's turn")
      (core/process-action "end-post-discard" state :corp nil)
      (is (nil? (:corp-post-discard @state)) "only the command closes it")
      (is (:end-turn @state) "and the turn then ends normally"))))
