(ns game.ai-duplicate-continue-test
  "Issue #77: duplicate Corp `continue` at movement/pos-0 re-fires :approach-server.

   While the :approach-server checkpoint is blocked on a Runner decision (e.g.
   Manegarm Skunkworks' \"Choose one\"), the run stays {:phase :movement,
   :no-action :runner, :position 0} - which is exactly the state that made
   `continue :movement` advance in the first place. `approach-server` sets nothing
   synchronously (no :phase, no :next-phase, no :no-action change) and only reaches
   `set-next-phase :success` after the prompt resolves, so every additional Corp
   continue in that window re-dispatches and calls approach-server again: another
   \"approaches Server 1\" log line and another duplicate prompt on the Runner's stack.

   Seen live in marquee g2 (replay 92c28ed5, frames 255-259): five Corp actions, five
   \"approaches Server 1\" lines, five distinct Manegarm prompts (eids 229824/230686/
   231548/232410/233272). The Runner paid the top prompt, breached and stole, then
   faced four stale duplicates whose resolution is a visible no-op post-success.

   Contrast `continue :approach-ice`, which calls set-next-phase *synchronously before*
   any awaited work - a re-entrant continue there sees the marker. approach-server is
   the one transition that awaits without marking, which is why only this checkpoint
   amplifies (the encounter-ice duplicate burst in the same replay was harmless)."
  (:require [game.core :as core]
            [game.core.diffs :as diffs]
            [game.test-framework :refer :all]
            [clojure.test :refer :all]))

(defn- approach-log-count
  "How many times the engine has announced approaching the remote server."
  [state]
  (count (re-seq #"approaches Server 1" (log-str state))))

(defn- manegarm-prompt-count
  "How many Manegarm \"Choose one\" prompts are stacked on the Runner."
  [state]
  (count (filter #(= "Choose one" (:msg %)) (get-in @state [:runner :prompt]))))

(deftest duplicate-corp-continue-at-approach-server-is-idempotent
  (testing "extra Corp continues while the approach-server prompt is pending are no-ops"
    (do-game
      (new-game {:corp {:hand ["Manegarm Skunkworks" "Ice Wall"]}})
      (play-from-hand state :corp "Manegarm Skunkworks" "New remote")
      (play-from-hand state :corp "Ice Wall" "Server 1")
      (take-credits state :corp)
      (let [mane (get-content state :remote1 0)]
        (run-on state "Server 1")
        (run-continue-until state :movement)
        (is (= :movement (:phase (:run @state))))
        (is (zero? (:position (:run @state))))
        (rez state :corp mane)

        ;; Runner passes, Corp continues -> movement transitions into approach-server,
        ;; whose :approach-server event raises Manegarm's "Choose one" and suspends.
        (core/continue state :runner nil)
        (core/continue state :corp nil)
        (is (= "Choose one" (:msg (first (get-in @state [:runner :prompt]))))
            "Manegarm's approach ability fired and is waiting on the Runner")

        (is (= 1 (approach-log-count state)) "the server was approached exactly once")
        (is (= 1 (manegarm-prompt-count state)) "exactly one Manegarm decision is pending")

        ;; The wedge: a Corp client retrying on timeout sends continue again while
        ;; the Runner decision is still pending.
        (dotimes [_ 4] (core/continue state :corp nil))

        (is (= 1 (approach-log-count state))
            "duplicate continues must not re-announce approaching the server")
        (is (= 1 (manegarm-prompt-count state))
            "duplicate continues must not mint stacked duplicate prompts")))))

(deftest approach-server-still-completes-after-duplicate-continues
  (testing "the guard must not strand the run: resolving the prompt still reaches success"
    (do-game
      (new-game {:corp {:hand ["Manegarm Skunkworks" "Ice Wall"]}})
      (play-from-hand state :corp "Manegarm Skunkworks" "New remote")
      (play-from-hand state :corp "Ice Wall" "Server 1")
      (take-credits state :corp)
      (let [mane (get-content state :remote1 0)]
        (run-on state "Server 1")
        (run-continue-until state :movement)
        (rez state :corp mane)
        (core/continue state :runner nil)
        (core/continue state :corp nil)
        (dotimes [_ 3] (core/continue state :corp nil))
        ;; Pay the tax (Manegarm: end the run, or pay 2 clicks / 5 credits)
        (click-prompt state :runner "Pay 5 [Credits]")
        (is (= :success (:phase (:run @state)))
            "the run reaches success exactly once after the Runner resolves the prompt")
        (is (= 1 (approach-log-count state))
            "the server was approached exactly once despite the duplicate continues")
        (is (zero? (manegarm-prompt-count state))
            "no stale duplicate 'Choose one' prompts survive into success (#77: the
             Runner used to face four of them, each a visible no-op post-success)")))))

(deftest duplicate-runner-continue-at-approach-server-is-idempotent
  (testing "the guard is side-independent: Runner retries are no-ops too"
    (do-game
      (new-game {:corp {:hand ["Manegarm Skunkworks" "Ice Wall"]}})
      (play-from-hand state :corp "Manegarm Skunkworks" "New remote")
      (play-from-hand state :corp "Ice Wall" "Server 1")
      (take-credits state :corp)
      (let [mane (get-content state :remote1 0)]
        (run-on state "Server 1")
        (run-continue-until state :movement)
        (rez state :corp mane)
        (core/continue state :runner nil)
        (core/continue state :corp nil)
        (is (= 1 (manegarm-prompt-count state)))
        (dotimes [_ 3] (core/continue state :runner nil))
        (is (= 1 (approach-log-count state))
            "a Runner retry must not re-announce the approach either")
        (is (= 1 (manegarm-prompt-count state))
            "a Runner retry must not stack prompts either")))))

(deftest ending-the-run-from-the-approach-prompt-cleans-up
  (testing "the marker must not strand the run when the approach prompt ends it"
    (do-game
      (new-game {:corp {:hand ["Manegarm Skunkworks" "Ice Wall"]}})
      (play-from-hand state :corp "Manegarm Skunkworks" "New remote")
      (play-from-hand state :corp "Ice Wall" "Server 1")
      (take-credits state :corp)
      (let [mane (get-content state :remote1 0)]
        (run-on state "Server 1")
        (run-continue-until state :movement)
        (rez state :corp mane)
        (core/continue state :runner nil)
        (core/continue state :corp nil)
        (dotimes [_ 3] (core/continue state :corp nil))
        (click-prompt state :runner "End the run")
        (is (nil? (:run @state)) "the run is cleaned up, not stranded")
        (is (zero? (manegarm-prompt-count state)) "no stale prompts survive the end")
        (is (not (contains? (get-in @state [:runner :register :last-run]) :approaching-server))
            "the internal marker must not leak into :last-run (replay/NAN consumers)")))))

(deftest approaching-server-marker-is-not-serialized-to-clients
  (testing "the internal marker stays out of the client run summary"
    (do-game
      (new-game {:corp {:hand ["Manegarm Skunkworks" "Ice Wall"]}})
      (play-from-hand state :corp "Manegarm Skunkworks" "New remote")
      (play-from-hand state :corp "Ice Wall" "Server 1")
      (take-credits state :corp)
      (let [mane (get-content state :remote1 0)]
        (run-on state "Server 1")
        (run-continue-until state :movement)
        (rez state :corp mane)
        (core/continue state :runner nil)
        (core/continue state :corp nil)
        (is (true? (get-in @state [:run :approaching-server]))
            "precondition: the marker is set while the approach is in flight")
        (is (not (contains? (diffs/run-summary state) :approaching-server))
            "the marker is engine-internal and must not reach the client")))))

(deftest normal-continue-at-movement-still-advances
  (testing "no regression: an unblocked movement continue still approaches the server"
    (do-game
      ;; Manegarm left UNREZZED: content keeps the server non-empty (an ice-only
      ;; remote is an empty server and the run just ends), but nothing prompts.
      (new-game {:corp {:hand ["Manegarm Skunkworks" "Ice Wall"]}})
      (play-from-hand state :corp "Manegarm Skunkworks" "New remote")
      (play-from-hand state :corp "Ice Wall" "Server 1")
      (take-credits state :corp)
      (run-on state "Server 1")
      (run-continue-until state :movement)
      (core/continue state :runner nil)
      (core/continue state :corp nil)
      (is (= 1 (approach-log-count state)) "the server is approached")
      (is (= :success (:phase (:run @state)))
          "with nothing to prompt on, approach-server runs straight through to success"))))
