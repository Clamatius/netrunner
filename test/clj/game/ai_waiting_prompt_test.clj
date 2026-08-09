(ns game.ai-waiting-prompt-test
  "Issue #114: a Corp turn whose LAST click hands the Runner a decision never ends.

   The client's auto-end guard treats any non-nil :prompt-state as blocking, so the
   Corp-side 'Waiting for Runner to make a decision' pseudo-prompt stops the turn
   from ending — and nothing re-runs the check once the Runner resolves it. Both
   seats then deadlock permanently (Luna-vs-Luna game d840fc14, turn 10).

   This namespace pins the two ENGINE facts the client fix depends on, so the unit
   fixtures in ai-basic-actions-test can't drift away from the wire:

   1. The pseudo-prompt the Corp holds really does carry :prompt-type :waiting —
      i.e. `state/waiting-prompt-type?` is the right discriminator, not a message
      regex. (The #109 lesson: copying a predicate isn't inheriting its authority.)
   2. It really does clear on its own when the Runner resolves, leaving the Corp
      at 0 clicks with NO prompt — the state the deferred re-check must act on."
  (:require [game.core :as core]
            [game.core.diffs :as diffs]
            [game.test-framework :refer :all]
            [cheshire.core :as json]
            [ai-state]
            [clojure.test :refer :all]))

(defn- corp-prompt
  "The Corp's :prompt-state as the server privatizes it — still Clojure values."
  [state]
  (get-in (diffs/public-states state) [:corp-state :corp :prompt-state]))

(defn- over-the-wire
  "Round-trip through the client's actual transport encoding.

   public-states alone stops short of the boundary that matters: the diff is
   JSON-encoded on the way out and parsed with keywordized keys on the way in, so
   the server's KEYWORD :waiting reaches the seat as the STRING \"waiting\". A test
   that asserts the keyword and stops has not pinned what the client sees — it
   would stay green while the live seat fell through to the actionable-prompt
   branch. (Guest-panel MINOR on the first cut of this file.)"
  [x]
  (json/parse-string (json/generate-string x) true))

(deftest corp-last-click-opponent-decision-leaves-a-waiting-prompt
  (testing "#114: Public Trail on the last click -> Corp holds a :waiting pseudo-prompt at 0 clicks"
    (do-game
      (new-game {:corp {:hand ["Public Trail"]}
                 :runner {:hand ["Sure Gamble"]}})
      ;; Public Trail requires the Runner to have made a successful run last turn.
      (take-credits state :corp)
      (run-empty-server state "Archives")
      (take-credits state :runner)
      ;; Burn down to a single click, then spend it on the card that hands the
      ;; Runner the decision — the exact shape from the seat log.
      (core/gain state :corp :credit 10)
      (click-credit state :corp)
      (click-credit state :corp)
      (play-from-hand state :corp "Public Trail")

      (is (zero? (get-in @state [:corp :click]))
          "precondition: the last click is spent")
      (is (not (:end-turn @state))
          "precondition: the turn has not ended")

      (let [prompt (corp-prompt state)
            wire (over-the-wire prompt)]
        (is (some? prompt)
            "Corp holds a prompt — this is what blocks the client's auto-end")
        (is (= :waiting (:prompt-type prompt))
            (str "server-side, the blocking prompt must be tagged :waiting, got: " (pr-str prompt)))
        ;; The assertion that actually protects the fix: the discriminator the
        ;; client keys on must accept the value the client RECEIVES, after JSON.
        (is (ai-state/waiting-prompt-type? (:prompt-type wire))
            (str "waiting-prompt-type? must recognise the post-JSON value, got: "
                 (pr-str (:prompt-type wire))))
        (is (re-find #"(?i)waiting for" (str (:msg wire)))
            "and it reads as a waiting message")
        (is (empty? (:choices wire))
            "the Corp has NO choices here — 'use choose to respond' is unactionable advice"))

      (testing "the Runner resolving it clears the Corp's prompt, with the turn still open"
        (click-prompt state :runner "Take 1 tag")
        (is (nil? (corp-prompt state))
            "Corp's waiting prompt clears on its own — nothing left to resolve")
        (is (zero? (get-in @state [:corp :click])))
        (is (not (:end-turn @state))
            "but the turn is STILL not ended: this is the orphaned state a re-check must catch")))))
