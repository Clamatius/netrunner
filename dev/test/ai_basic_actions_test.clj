(ns ai-basic-actions-test
  "Regression tests for ai_basic_actions.clj.

   Locks down: post-discard pause guard in start-turn! (commit 4ad15ddbc).
   Upstream's two-phase end-turn pauses on :corp-post-discard / :runner-post-discard
   when a card sets :force-post-discard-{self,opponent}. Sending start-turn during
   that pause desyncs the engine."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [test-helpers :refer :all]
            [ai-basic-actions :as basic]
            [ai-state :as state]
            [ai-websocket-client-v2 :as ws]))

(deftest test-start-turn-blocks-on-corp-post-discard
  (testing "start-turn! returns :post-discard-pending error and sends nothing"
    (let [sent (atom [])
          game-state {:corp-post-discard {:active true}
                      :runner {:click 0 :credit 5 :hand []}
                      :corp {:click 0 :credit 5 :hand []}
                      :turn 5
                      :active-player "Corp"
                      :log []}]
      (with-mock-state (mock-client-state :side "runner" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (basic/start-turn!)]
            (is (= :error (:status result)))
            (is (= :post-discard-pending (:reason result)))
            (is (empty? @sent) "must not send start-turn while pause is active")))))))

(deftest test-start-turn-blocks-on-runner-post-discard
  (testing "Mirror guard for runner-post-discard"
    (let [sent (atom [])
          game-state {:runner-post-discard {:active true}
                      :runner {:click 0 :credit 5 :hand []}
                      :corp {:click 0 :credit 5 :hand []}
                      :turn 5
                      :active-player "Runner"
                      :log []}]
      (with-mock-state (mock-client-state :side "corp" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (basic/start-turn!)]
            (is (= :error (:status result)))
            (is (= :post-discard-pending (:reason result)))
            (is (empty? @sent))))))))

(deftest test-start-turn-no-guard-when-post-discard-flag-absent
  (testing "Guard only fires when :active key is true (not when key is missing)"
    (let [sent (atom [])
          ;; No post-discard flags. Should fall through to other guards (opp-clicks > 0 here),
          ;; NOT to the post-discard branch.
          game-state {:runner {:click 0 :credit 5 :hand []}
                      :corp {:click 2 :credit 5 :hand []}
                      :turn 5
                      :active-player "Corp"
                      :log []}]
      (with-mock-state (mock-client-state :side "runner" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (basic/start-turn!)]
            (is (= :error (:status result)))
            (is (not= :post-discard-pending (:reason result))
                "Without :active flag, post-discard branch must not fire")))))))

;; ============================================================================
;; Opening-mulligan race: Corp must not start turn 1 while the opponent's
;; mulligan is unresolved. The Corp can keep + start-turn before the Runner
;; finishes mulligan; the engine then grants Corp clicks but bounces every
;; action off the pending-mulligan prompt — a wedged half-started turn. Detected
;; from our OWN waiting prompt (the server tells us directly; no fog-of-war).
;; ============================================================================

(deftest test-start-turn-blocks-while-opponent-mulligan-pending
  (testing "Corp start-turn! is refused (sends nothing) while its own prompt is the pending-mulligan wait"
    (let [sent (atom [])
          game-state {:runner {:click 0 :credit 5 :hand []}
                      :corp {:click 0 :credit 5 :hand []
                             :prompt-state {:msg "Waiting for Runner to keep hand or mulligan"
                                            :prompt-type "waiting" :selectable []}}
                      :turn 0
                      :active-player "corp"
                      :log []}]
      (with-mock-state (mock-client-state :side "corp" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (basic/start-turn!)]
            (is (= :error (:status result)))
            (is (= :opponent-mulligan (:reason result)))
            (is (empty? @sent) "must not send start-turn while opponent mulligan pending")))))))

(deftest test-start-turn-allows-first-turn-when-no-mulligan-prompt
  (testing "Corp first turn is NOT blocked by the mulligan guard once the prompt is gone"
    (let [sent (atom [])
          ;; No pending-mulligan prompt: mulligan resolved, Corp may start.
          game-state {:runner {:click 0 :credit 5 :hand []}
                      :corp {:click 0 :credit 5 :hand []}
                      :turn 0
                      :active-player "corp"
                      :log []}]
      (with-mock-state (mock-client-state :side "corp" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (basic/start-turn!)]
            (is (not= :opponent-mulligan (:reason result))
                "mulligan guard must not fire when no pending-mulligan prompt is present")
            (is (seq @sent) "should attempt to send start-turn")))))))

(deftest test-can-start-turn-reports-opponent-mulligan
  (testing "can-start-turn? surfaces :opponent-mulligan for the pending-mulligan wait"
    (let [game-state {:runner {:click 0 :credit 5 :hand []}
                      :corp {:click 0 :credit 5 :hand []
                             :prompt-state {:msg "Waiting for Runner to keep hand or mulligan"
                                            :prompt-type "waiting" :selectable []}}
                      :turn 0
                      :active-player "corp"
                      :log []}]
      (with-mock-state (mock-client-state :side "corp" :game-state game-state)
        (let [result (basic/can-start-turn?)]
          (is (false? (:can-start result)))
          (is (= :opponent-mulligan (:reason result))))))))

;; The mirror: the mulligan *this* seat still owes. #87 guarded only the
;; opponent's half. Nothing stopped a seat from starting its own turn over its
;; own live 'Keep hand?' prompt — and the engine does not stop it either, so the
;; turn really begins and the mandatory draw really happens. Observed live on
;; game e753fdee: the Corp kept a SIX-card starting hand at Turn 1 / 3 clicks.
(def ^:private my-mulligan-game-state
  {:runner {:click 0 :credit 5 :hand [] :keep false}
   :corp {:click 0 :credit 5 :hand [] :keep false
          :prompt-state {:msg "Keep hand?" :prompt-type "mulligan"
                         :choices [{:value "Keep"} {:value "Mulligan"}]}}
   :turn 0
   :active-player "runner"
   :end-turn true
   :log []})

(deftest test-can-start-turn-reports-my-own-mulligan
  (testing "can-start-turn? refuses while I still owe my own opening mulligan"
    (with-mock-state (mock-client-state :side "corp" :game-state my-mulligan-game-state)
      (let [result (basic/can-start-turn?)]
        (is (false? (:can-start result)))
        (is (= :my-mulligan (:reason result)))))))

(deftest test-start-turn-refuses-with-no-game-state
  (testing "start-turn! sends NOTHING when :game-state is nil but :gameid survives"
    ;; Guest-panel CRITICAL. resync-game! clears :game-state and keeps :gameid.
    ;; Every input start-turn! reads then defaults falsy — turn 0, nil clicks,
    ;; empty log — which IS the is-first-turn? signature, so it sent on the
    ;; preserved gameid. The :keep guard cannot catch this: an absent flag is
    ;; not `false`, so my-mulligan-pending? correctly says "not pending".
    ;; Unknown state needs its own refusal.
    (let [sent (atom [])]
      (with-mock-state (assoc (mock-client-state :side "corp") :game-state nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (basic/start-turn!)]
            (is (= :error (:status result)))
            (is (= :no-game-state (:reason result)))
            (is (empty? @sent))))))))

;; ----------------------------------------------------------------------------
;; The refusal must not DIAGNOSE what it cannot see (review panel, MAJOR).
;;
;; :game-state nil has a fourth reading the enumeration missed: an ordinary
;; unstarted lobby, which has a :gameid, a side and no board just like a failed
;; resync does. Telling that seat the game "has ended, been purged, or the resync
;; did not complete" and offering ./dev/reset.sh points it at the one command that
;; destroys the healthy lobby it is sitting in — the #125 mistake, again.
;;
;; These tests assert the FRAMING, not that some token appears: the lobby case
;; must not offer the destructive remedy, and the genuinely-boardless case must
;; keep it. Both previous no-state tests omitted :lobby-state entirely, which is
;; why the suite stayed green through this.
;; ----------------------------------------------------------------------------

(deftest test-start-turn-in-an-unstarted-lobby-does-not-claim-the-game-died
  (testing "seated in a waiting room: refuse, but do not diagnose a teardown"
    (let [sent (atom [])]
      (with-mock-state (assoc (mock-client-state :side "corp")
                              :game-state nil
                              :lobby-state {:started false :title "test lobby"})
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (atom nil)
                out (with-out-str (reset! result (basic/start-turn!)))]
            (is (= :error (:status @result)))
            (is (= :no-game-state (:reason @result)))
            (is (empty? @sent) "an unstarted lobby has no turn to start")
            (is (not (str/includes? out "reset.sh"))
                (str "THE bug: reset.sh destroys the healthy lobby the seat is in. Got:\n" out))
            (is (not (str/includes? out "has ended"))
                (str "must not assert a teardown it cannot see. Got:\n" out))
            (is (str/includes? out "not started yet")
                (str "must name the state it IS in. Got:\n" out))))))))

(deftest test-start-turn-with-no-lobby-state-keeps-the-teardown-guidance
  (testing "board gone and no lobby: the ended/purged/resync enumeration is right here"
    ;; The complement of the test above — a fix that made every no-board refusal
    ;; say "waiting in a lobby" would trade one false claim for another.
    (let [sent (atom [])]
      (with-mock-state (assoc (mock-client-state :side "corp") :game-state nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (atom nil)
                out (with-out-str (reset! result (basic/start-turn!)))]
            (is (= :no-game-state (:reason @result)))
            (is (empty? @sent))
            (is (str/includes? out "game-over-status")
                (str "a started game that lost its board should be diagnosed. Got:\n" out))))))))

(deftest test-end-turn-in-an-unstarted-lobby-does-not-claim-the-game-died
  (testing "end-turn's identical refusal text needs the identical discrimination"
    ;; Same defect, second site: end-turn! printed the same four lines verbatim,
    ;; so fixing only start-turn! would leave the neighbouring command lying.
    (let [sent (atom [])]
      (with-mock-state (assoc (mock-client-state :side "corp")
                              :game-state nil
                              :lobby-state {:started false :title "test lobby"})
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (atom nil)
                out (with-out-str (reset! result (basic/end-turn!)))]
            (is (= :error (:status @result)))
            (is (= :no-game-state (:reason @result)))
            (is (empty? @sent))
            (is (not (str/includes? out "reset.sh"))
                (str "THE bug, second site. Got:\n" out))
            (is (str/includes? out "not started yet")
                (str "must name the state it IS in. Got:\n" out))))))))

(deftest test-start-turn-refuses-over-my-own-mulligan
  (testing "start-turn! sends NOTHING while my own mulligan is unresolved"
    ;; The assertion that matters is `(empty? @sent)`. A refusal that still puts
    ;; the message on the wire is not a refusal: the engine has no ordering check
    ;; of its own, so the turn would start regardless of what we printed.
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "corp" :game-state my-mulligan-game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (basic/start-turn!)]
            (is (= :error (:status result)))
            (is (= :my-mulligan (:reason result)))
            (is (empty? @sent)
                "THE bug: start-turn went out and the engine happily granted clicks + the mandatory draw")))))))

;; ============================================================================
;; smart-end-turn! over-hand-size: must END (to trigger discard prompt), not refuse
;; ============================================================================
;; Regression for the self-play deadlock: a side at 0 clicks but over hand size
;; with no active discard prompt. The old over-hand-size guard returned
;; :over-hand-size and sent nothing, so the autonomous loop spun forever - the
;; discard prompt only appears AFTER end-turn is sent. end-turn! itself handles
;; oversized hands (engine prompts for discard), so smart-end-turn! must defer to
;; it rather than block.

(deftest test-smart-end-turn-over-hand-size-ends-to-trigger-discard
  (testing "over hand size + 0 clicks + no prompt -> sends end-turn (not :over-hand-size)"
    (let [sent (atom [])
          hand6 (vec (repeat 6 {:title "Hedge Fund" :type "Operation"}))
          game-state {:corp {:click 0 :credit 5 :hand hand6 :hand-count 6
                             :hand-size {:total 5} :installed {} :prompt-state nil}
                      :runner {:click 0 :credit 5 :hand []}
                      :turn 8
                      :active-player "Corp"
                      :log []}]
      (with-mock-state (mock-client-state :side "corp" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ;; turn legitimately started; nothing in log says we ended
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (let [result (basic/smart-end-turn!)]
            (is (not= :over-hand-size (:status result))
                "must not refuse with :over-hand-size")
            (is (some #(= "end-turn" (get-in % [:data :command])) @sent)
                "must send end-turn to trigger the engine discard prompt")))))))

;; ============================================================================
;; check-auto-end-turn! must forewarn the discard prompt under fog of war.
;; Regression for marquee Opus↔Terra game B: it counted (count :hand), but our
;; own hand CONTENTS are hidden in wire state (only :hand-count is real), so the
;; count read 0, the "game will prompt for discard" line never printed, and the
;; seat — told "Auto-ending turn (0 clicks, no prompts)" — hit the engine's
;; discard prompt unannounced.

(deftest test-check-auto-end-turn-forewarns-discard-under-fog-of-war
  (testing "over hand size known only via :hand-count -> forewarns discard before auto-ending"
    (let [sent (atom [])
          ;; the live wire shape: hand contents hidden, only the count is real
          game-state {:runner {:click 0 :credit 5 :hand [] :hand-count 6
                               :hand-size {:total 5} :installed {} :prompt-state nil}
                      :corp {:click 0 :credit 5 :hand []}
                      :turn 8
                      :active-player "runner"
                      :log []}]
      (with-mock-state (mock-client-state :side "runner" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (let [out (with-out-str (basic/check-auto-end-turn!))]
            (is (re-find #"exceeds max" out)
                (str "must forewarn the coming discard prompt, got: " out))
            (is (some #(= "end-turn" (get-in % [:data :command])) @sent)
                "still auto-ends — the engine's discard prompt is the mechanism")))))))

;; ============================================================================
;; #103: auto-end must not swallow the final paid-ability window
;; ============================================================================
;; Marquee ac71ce63 (Fable Corp): the instant the last click was spent,
;; check-auto-end-turn! fired end-turn — no beat to rez Nico Campaign in the
;; end-of-turn paid window. The seat only recovered by rezzing AFTER the auto-end
;; (legal, but non-obvious enough that it flagged the timing as suspect).
;;
;; Only the AUTOMATIC path yields the beat. smart-end-turn! is the explicit "end
;; my turn" command — the heuristic bots call it after their action loop, so
;; pausing THAT would wedge autonomous self-play. Splitting the two is what makes
;; this deadlock-safe.

(defn- corp-eot-state
  "Corp at 0 clicks with one unrezzed asset installed in a remote."
  [& {:keys [credit cost rezzed] :or {credit 5 cost 2 rezzed false}}]
  {:corp {:click 0 :credit credit :hand [] :hand-count 3
          :hand-size {:total 5} :installed {} :prompt-state nil
          :servers {:remote1 {:content [{:cid 99 :title "Nico Campaign"
                                         :type "Asset" :cost cost :rezzed rezzed}]}}}
   :runner {:click 0 :credit 5 :hand []}
   :turn 8
   :active-player "corp"
   :log []})

(deftest test-check-auto-end-turn-offers-paid-window-beat
  (testing "#103: an affordable unrezzed asset pauses the auto-end and names it"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "corp" :game-state (corp-eot-state))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (let [out (with-out-str (basic/check-auto-end-turn!))]
            (is (re-find #"Nico Campaign" out)
                (str "must name the card the paid window is for, got: " out))
            (is (not (some #(= "end-turn" (get-in % [:data :command])) @sent))
                "must NOT auto-end over an available paid-ability window")))))))

(deftest test-check-auto-end-turn-ignores-unaffordable-rez
  (testing "#103 no false pause: a rez we cannot pay for is not a window worth holding"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "corp"
                                          :game-state (corp-eot-state :credit 1 :cost 4))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (let [_out (with-out-str (basic/check-auto-end-turn!))]
            (is (some #(= "end-turn" (get-in % [:data :command])) @sent)
                "unaffordable rez must still auto-end")))))))

;; Guest-review catch (GPT-5.6): affordability read the credit POOL only, so a
;; Corp at 0 credits holding a rezzed Mumba Temple (2 recurring, explicitly usable
;; for :rez) was told it could not afford a 2-cost asset — the window closed and
;; #103 reproduced for exactly the cards it was written to protect. The error is
;; asymmetric: a missed window IS the bug, while a spurious pause costs one
;; end-turn, so the predicate errs generous.

(deftest test-check-auto-end-turn-counts-recurring-credits
  (testing "#103: recurring credits on rezzed cards count toward rez affordability"
    (let [sent (atom [])
          game-state {:corp {:click 0 :credit 0 :hand [] :hand-count 3
                             :hand-size {:total 5} :installed {} :prompt-state nil
                             :servers {:remote1 {:content [{:cid 1 :title "Mumba Temple"
                                                            :type "Asset" :cost 0 :rezzed true
                                                            :counter {:recurring 2}}
                                                           {:cid 2 :title "Nico Campaign"
                                                            :type "Asset" :cost 2 :rezzed false}]}}}
                      :runner {:click 0 :credit 5 :hand []}
                      :turn 8 :active-player "corp" :log []}]
      (with-mock-state (mock-client-state :side "corp" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (let [out (with-out-str (basic/check-auto-end-turn!))]
            (is (re-find #"Nico Campaign" out)
                (str "2 recurring credits make a 2-cost rez affordable, got: " out))
            (is (not (some #(= "end-turn" (get-in % [:data :command])) @sent))
                "must hold the window open, not end the turn")))))))

(deftest test-check-auto-end-turn-ignores-already-rezzed
  (testing "#103 no false pause: an already-rezzed asset offers no rez window"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "corp"
                                          :game-state (corp-eot-state :rezzed true))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (let [_out (with-out-str (basic/check-auto-end-turn!))]
            (is (some #(= "end-turn" (get-in % [:data :command])) @sent)
                "already-rezzed asset must still auto-end")))))))

(deftest test-smart-end-turn-still-ends-over-paid-window
  (testing "#103 deadlock-safety: the EXPLICIT end-turn obeys, so bots never wedge"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "corp" :game-state (corp-eot-state))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (let [_out (with-out-str (basic/smart-end-turn!))]
            (is (some #(= "end-turn" (get-in % [:data :command])) @sent)
                "smart-end-turn! is the deliberate command — it must still end")))))))

;; #103 (second half) / Terra round [184] item 4: "Auto-ending turn (0 clicks, no
;; prompts)" printed in the same breath as the engine's discard prompt appearing.
;; The claim was a PRE-condition of the check, but read as a postcondition — so
;; the seat treated the discard prompt that followed as a desync.

(deftest test-check-auto-end-turn-does-not-claim-no-prompts-before-a-discard
  (testing "#103: when a discard is forewarned, the end line must not also claim 'no prompts'"
    (let [sent (atom [])
          game-state {:runner {:click 0 :credit 5 :hand [] :hand-count 6
                               :hand-size {:total 5} :installed {} :prompt-state nil}
                      :corp {:click 0 :credit 5 :hand []}
                      :turn 8
                      :active-player "runner"
                      :log []}]
      (with-mock-state (mock-client-state :side "runner" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (let [out (with-out-str (basic/check-auto-end-turn!))]
            (is (re-find #"exceeds max" out) "forewarning must stay")
            (is (not (re-find #"no prompts" out))
                (str "must not assert 'no prompts' while announcing a coming discard, got: " out))
            (is (some #(= "end-turn" (get-in % [:data :command])) @sent)
                "still auto-ends — the engine's discard prompt is the mechanism")))))))

;; ============================================================================
;; smart-end-turn! self-heal: rolled-back optimistic "is ending" line
;; ============================================================================
;; Regression for the agent-vs-agent deadlock found 2026-06-14 (Runner stuck at
;; 0 clicks after a last-click Wildcat Strike; Corp correctly waiting on an
;; end-turn that never landed). The "X is ending" log line can be an optimistic
;; client entry the server rolls back on a :game/error resync. The old guard
;; treated any such line in the last 3 log entries as a terminal "already ended",
;; permanently refusing the needed end-turn. smart-end-turn! now re-reads after a
;; short settle: a genuine line persists (do nothing); a rolled-back one vanishes
;; (re-send). end-turn!'s own guard stays the corruption backstop for genuine
;; double-ends.

(deftest test-end-turn-self-heal-decision
  (testing "any evidence the turn ended -> :confirmed-ended; nothing -> :resend"
    (is (= :confirmed-ended (basic/end-turn-self-heal-decision {:line-present? true
                                                                :opponent-underway? false})))
    ;; Codex gap: our line scrolled out of the window but the opponent has clearly
    ;; taken over -> must NOT re-send (would be the corrupting double-end).
    (is (= :confirmed-ended (basic/end-turn-self-heal-decision {:line-present? false
                                                                :opponent-underway? true})))
    ;; :turn advanced past entry -> the turn unambiguously ended.
    (is (= :confirmed-ended (basic/end-turn-self-heal-decision {:line-present? false
                                                                :opponent-underway? false
                                                                :turn-advanced? true})))
    (is (= :resend (basic/end-turn-self-heal-decision {:line-present? false
                                                       :opponent-underway? false
                                                       :turn-advanced? false})))))

(deftest test-recheck-end-turn-state-real-seam
  (testing "real recheck (sleep + deref) reports live signals"
    (let [game-state {:runner {:click 0 :credit 7 :hand [] :user {:username "ai-runner"}}
                      :corp {:click 0 :credit 13 :hand []}
                      :turn 4 :active-player "runner"
                      :log [{:text "ai-runner is ending their turn 4 with 7 [Credit]."}]}]
      (with-mock-state (mock-client-state :side "runner" :game-state game-state)
        (let [r (basic/recheck-end-turn-state 4)]
          (is (true? (:line-present? r)) "our end-turn line is present in the live log")
          (is (false? (:opponent-underway? r)) "opponent has not taken over")
          (is (false? (:turn-advanced? r)) "turn 4 == entry turn 4 -> not advanced"))))))

(deftest test-smart-end-turn-self-heals-rolled-back-end
  (testing "transient 'already ended' that vanishes on re-read -> re-sends end-turn"
    (let [sent (atom [])
          game-state {:runner {:click 0 :credit 7 :hand [] :hand-count 0
                               :hand-size {:total 5} :installed {} :prompt-state nil
                               :user {:username "ai-runner"}}
                      :corp {:click 0 :credit 13 :hand []}
                      :turn 4 :active-player "runner"
                      ;; optimistic line present at the INITIAL check -> enters branch
                      :log [{:text "ai-runner is ending their turn 4 with 7 [Credit]."}]}]
      (with-mock-state (mock-client-state :side "runner" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)
                      ;; the resync rolled the line back, opponent has NOT taken over
                      basic/recheck-end-turn-state
                      (fn [_] (swap! state/client-state assoc-in [:game-state :log] [])
                        {:line-present? false :opponent-underway? false :turn-advanced? false})]
          (let [_ (basic/smart-end-turn!)]
            (is (some #(= "end-turn" (get-in % [:data :command])) @sent)
                "rolled-back optimistic line -> smart-end-turn must re-send end-turn")))))))

(deftest test-smart-end-turn-confirmed-ended-no-resend
  (testing "genuine 'already ended' that persists on re-read -> no duplicate end-turn"
    (let [sent (atom [])
          game-state {:runner {:click 0 :credit 7 :hand [] :hand-count 0
                               :hand-size {:total 5} :installed {} :prompt-state nil
                               :user {:username "ai-runner"}}
                      :corp {:click 0 :credit 13 :hand []}
                      :turn 4 :active-player "runner"
                      :log [{:text "ai-runner is ending their turn 4 with 7 [Credit]."}]}]
      (with-mock-state (mock-client-state :side "runner" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)
                      basic/recheck-end-turn-state (fn [_] {:line-present? true
                                                            :opponent-underway? false
                                                            :turn-advanced? false})]
          (let [result (basic/smart-end-turn!)]
            (is (= :already-ended (:status result)) "must report already-ended")
            (is (empty? @sent) "genuine end -> must NOT send a duplicate end-turn")))))))

(deftest test-smart-end-turn-no-resend-when-opponent-took-over
  (testing "line scrolled out of window but opponent is underway -> no duplicate end-turn"
    (let [sent (atom [])
          game-state {:runner {:click 0 :credit 7 :hand [] :hand-count 0
                               :hand-size {:total 5} :installed {} :prompt-state nil
                               :user {:username "ai-runner"}}
                      :corp {:click 0 :credit 13 :hand []}
                      :turn 4 :active-player "runner"
                      :log [{:text "ai-runner is ending their turn 4 with 7 [Credit]."}]}]
      (with-mock-state (mock-client-state :side "runner" :game-state game-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)
                      basic/recheck-end-turn-state (fn [_] {:line-present? false
                                                            :opponent-underway? true
                                                            :turn-advanced? false})]
          (let [result (basic/smart-end-turn!)]
            (is (= :already-ended (:status result)) "must report already-ended")
            (is (empty? @sent)
                "opponent took over -> must NOT re-send (avoid corrupting double-end)")))))))

;; =============================================================================
;; repeat-action! — count arguments for burst commands
;; =============================================================================
;;
;; The command log showed take-credit/advance/draw arriving in BURSTS (125/74/56
;; back-to-back repeats at a 1-2s median), i.e. one intent typed N times because
;; the command took no count. These pin the loop's stopping rules — especially
;; click exhaustion, which is load-bearing rather than defensive: the underlying
;; actions call check-auto-end-turn!, so the click that empties the pool can END
;; THE TURN mid-loop, and continuing would fire actions into the opponent's turn.

(defn- clicking-state
  "Client state whose click count decrements on each successful action."
  [clicks]
  (mock-client-state :side "runner"
                     :game-state {:runner {:click clicks :credit 5 :hand []}
                                  :corp {:click 0 :credit 5 :hand []}
                                  :turn 5 :active-player "Runner" :log []}))

(deftest repeat-action-runs-exactly-n-times
  (testing "A count does what typing it N times did"
    (with-mock-state (clicking-state 4)
      (let [calls (atom 0)
            result (basic/repeat-action! 3 (fn [] (swap! calls inc) {:status :success}) "things")]
        (is (= 3 @calls))
        (is (= :success (:status result)))
        (is (= 3 (get-in result [:data :times])))))))

(deftest repeat-action-stops-when-clicks-run-out
  (testing "CRITICAL: the click that empties the pool can auto-end the turn, so
            the loop must stop rather than act into the opponent's turn."
    (with-mock-state (clicking-state 2)
      (let [calls (atom 0)
            action (fn []
                     (swap! calls inc)
                     ;; mimic the real actions: each one spends a click
                     (swap! state/client-state update-in [:game-state :runner :click] dec)
                     {:status :success})
            result (basic/repeat-action! 5 action "clicks")]
        (is (= 2 @calls) "Must spend only the clicks actually available")
        (is (= :partial (:status result)) "and report the shortfall, not claim success")
        (is (= 2 (get-in result [:data :times])))
        (is (= 5 (get-in result [:data :requested])))))))

(deftest repeat-action-stops-on-first-failure
  (testing "A failed step aborts the rest — never plough on through an error"
    (with-mock-state (clicking-state 4)
      (let [calls (atom 0)
            action (fn []
                     (swap! calls inc)
                     (if (= 2 @calls)
                       {:status :error :reason "blocked"}
                       {:status :success}))
            result (basic/repeat-action! 4 action "things")]
        (is (= 2 @calls) "Stops at the failure, does not attempt 3 or 4")
        (is (= :error (:status result)) "and surfaces the failure, not a success")
        (is (= 1 (get-in result [:data :times])) "reporting how many DID land")))))

(deftest repeat-action-first-call-is-not-blocked-by-zero-clicks
  (testing "The turn may legitimately not be started yet (the actions auto-start
            it), so the clicks guard must not fire before the first action."
    (with-mock-state (clicking-state 0)
      (let [calls (atom 0)
            result (basic/repeat-action! 1 (fn [] (swap! calls inc) {:status :success}) "things")]
        (is (= 1 @calls) "Must still attempt the first action at 0 clicks")
        (is (= :success (:status result)))))))

;; =============================================================================
;; No-op detection — the misleading-output class
;; =============================================================================
;;
;; Found by smoke-testing the count argument: `take-credit 2` during a run
;; printed "💰 Credits: 2 → 2", "⏱️  Clicks: 3 → 3" and then "✅ Completed 2
;; credit clicks". The engine had refused both actions. The count argument did
;; not cause this — it AMPLIFIED a pre-existing lie (same no-op seen in marquee
;; g1) into a confident summary, which is what made it visible.

(defn- no-op-state []
  (mock-client-state :side "runner"
                     :game-state {:runner {:click 3 :credit 2 :hand [{:cid 1 :title "Sure Gamble"}]}
                                  :corp {:click 0 :credit 5 :hand []}
                                  :turn 5 :active-player "Runner"
                                  :run {:phase "encounter-ice"} :log []}))

(deftest take-credit-reports-error-when-nothing-changed
  (testing "Refused action (neither credits NOR clicks moved) must not claim success"
    (with-mock-state (no-op-state)
      (with-redefs [ws/send-message! (fn [_ _] true)
                    basic/ensure-turn-started! (fn [] true)]
        (let [result (basic/take-credit!)]
          (is (= :error (:status result))
              "A no-op that reports :success is how a seat ends up repeating it")
          (is (re-find #"no effect" (str (:reason result)))))))))

(deftest draw-reports-error-when-nothing-changed
  (testing "A refused draw must not name the pre-existing last card as 'Drew:'"
    (with-mock-state (no-op-state)
      (with-redefs [ws/send-message! (fn [_ _] true)
                    basic/ensure-turn-started! (fn [] true)]
        (let [out (java.io.StringWriter.)
              result (binding [*out* out] (basic/draw-card!))]
          (is (= :error (:status result)))
          (is (not (re-find #"Drew:" (str out)))
              "Must not claim to have drawn a card it did not draw"))))))

(deftest repeat-action-does-not-amplify-a-no-op
  (testing "The count must stop on the refusal rather than repeat it N times
            and summarise with a confident '✅ Completed N'."
    (with-mock-state (no-op-state)
      (with-redefs [ws/send-message! (fn [_ _] true)
                    basic/ensure-turn-started! (fn [] true)]
        (let [out (java.io.StringWriter.)
              result (binding [*out* out] (basic/take-credit! 3))]
          (is (= :error (:status result)))
          (is (= 0 (get-in result [:data :times])) "Zero actions actually landed")
          (is (not (re-find #"Completed 3" (str out)))))))))

(deftest repeat-action-warns-when-count-exceeds-clicks
  (testing "Say up front that the count exceeds the clicks in hand, rather than
            discovering it N-1 actions in. (Michael's review of the count arg.)"
    (with-mock-state (clicking-state 2)
      (let [out (java.io.StringWriter.)]
        (binding [*out* out]
          (basic/repeat-action! 5 (fn [] {:status :success}) "clicks"))
        (is (re-find #"only 2 click" (str out))
            "Must warn before starting, naming the real ceiling")))))

(deftest repeat-action-does-not-warn-at-zero-clicks
  (testing "0 clicks cannot distinguish 'spent' from 'turn not started yet', and
            the actions auto-start the turn — warning there would be noise on
            the first action of every turn."
    (with-mock-state (clicking-state 0)
      (let [out (java.io.StringWriter.)]
        (binding [*out* out]
          (basic/repeat-action! 3 (fn [] {:status :success}) "clicks"))
        (is (not (re-find #"only 0 click" (str out))))))))

;; ---------------------------------------------------------------------------
;; Off-turn end-turn guard (game 02995207, turn 8)
;;
;; An end-turn sent while we are NOT the active player ends the OPPONENT's turn
;; and is logged under OUR name, leaving no "<opponent> is ending" line. Turn
;; state derived from the log then permanently disagrees with :end-turn and the
;; match wedges. These tests assert NOTHING IS SENT — asserting only on the
;; returned :status would stay green on a client that still transmits, which is
;; the whole failure mode.
;; ---------------------------------------------------------------------------

(defn- off-turn-state
  "Runner at 0 clicks during the CORP's turn, with the runner's own 'is ending'
   line already scrolled out of the 3-entry window that already-ended-this-turn?
   inspects — i.e. exactly the state at the wedge."
  []
  (mock-client-state
   :side "runner"
   :game-state {:runner {:click 0 :credit 5 :hand [] :hand-count 0}
                :corp {:click 0 :credit 12 :hand []}
                :turn 8
                :active-player "corp"
                :log [{:text "ai-runner is ending their turn 7 with 0 [Credit] and 5 cards in their Grip."}
                      {:text "ai-corp started their turn 8 with 12 [Credit] and 3 cards in HQ."}
                      {:text "ai-corp spends [Click] to install a card in the root of Server 1."}
                      {:text "ai-corp spends [Click] and pays 1 [Credits] to advance a card in Server 1."}]}))

(deftest end-turn-refuses-off-turn-and-sends-nothing
  (testing "end-turn! must not transmit while the opponent is the active player"
    (let [sent (atom [])]
      (with-mock-state (off-turn-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (binding [*out* (java.io.StringWriter.)] (basic/end-turn!))]
            (is (= :error (:status result)))
            (is (= :not-my-turn (:reason result)))
            (is (empty? @sent)
                "MUST NOT SEND — an off-turn end-turn ends the opponent's turn")))))))

(deftest smart-end-turn-refuses-off-turn-and-sends-nothing
  (testing "smart-end-turn! mirrors the guard, so its self-heal can never
            re-send into the opponent's turn"
    (let [sent (atom [])]
      (with-mock-state (off-turn-state)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (binding [*out* (java.io.StringWriter.)] (basic/smart-end-turn!))]
            (is (= :error (:status result)))
            (is (= :not-my-turn (:reason result)))
            (is (empty? @sent))))))))

(deftest off-turn-guard-survives-the-log-window-scrolling
  (testing "The pre-existing duplicate guard scans only the last 3 log entries, so
            our own 'is ending' line scrolls out once the opponent acts and the
            guard goes blind. The off-turn guard keys on :active-player, which does
            not scroll — this is the case that actually wedged game 02995207."
    (let [sent (atom [])]
      (with-mock-state (off-turn-state)
        ;; Precondition: the old guard IS blind here. If this ever goes false the
        ;; test has stopped covering the real bug.
        (is (not (#'basic/already-ended-this-turn? @state/client-state))
            "precondition: the 3-entry duplicate guard cannot see our end-turn")
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (binding [*out* (java.io.StringWriter.)] (basic/end-turn!))
          (is (empty? @sent) "off-turn guard must cover what the log-scan guard misses"))))))

(deftest end-turn-still-works-on-our-own-turn
  (testing "The guard must not block a legitimate end-turn (active-player = us)"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state
                        :side "runner"
                        :game-state {:runner {:click 0 :credit 5 :hand [] :hand-count 0}
                                     :corp {:click 0 :credit 12 :hand []}
                                     :turn 8
                                     :active-player "runner"
                                     :log [{:text "ai-runner spends [Click] to make a run on R&D."}]})
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (binding [*out* (java.io.StringWriter.)] (basic/end-turn!))]
            (is (= :success (:status result)))
            (is (= 1 (count @sent)) "legitimate end-turn must still be sent")))))))

;; ============================================================================
;; end-turn! on a GONE game: honest refusal, never a Java stack trace
;; ============================================================================
;; Live capture, 2026-08-07 polish round. The server had purged the lobby; the
;; auto-resync correctly diagnosed it and printed the GAME-GONE guidance — and
;; then end-turn! ran anyway against the cleared state and died:
;;
;;   ❌ Game appears to be gone — not found in the lobby, ...
;;   Execution error (NullPointerException) at ai-basic-actions/end-turn!
;;     (ai_basic_actions.clj:733).
;;   Cannot invoke "Object.getClass()" because "x" is null
;;
;; Line 733 is `(and (> clicks 0) (not force))`. Every other binding in the
;; let is nil-hardened — `hand-size` via count, `max-hand-size` via a default,
;; `active-player` via an explicit nil arm — but `clicks` was left bare, so
;; `(> nil 0)` throws. A raw stack trace is the worst output a seat can get:
;; it carries no verdict, no recovery, and nothing to pattern-match on.
;;
;; The refusal must also be a REFUSAL — with no game there is nothing to end,
;; and sending end-turn into the void is how off-turn end-turns get minted.

(deftest test-end-turn-no-game-state-refuses-without-crashing
  (testing "GAME-GONE: end-turn! returns an honest error instead of throwing"
    (let [sent (atom [])]
      (with-mock-state {:side "corp" :gameid nil :game-state nil}
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (atom nil)]
            (with-out-str (reset! result (basic/end-turn!)))
            (is (= :error (:status @result))
                (str "must report an error, got: " @result))
            (is (= :no-game-state (:reason @result))
                (str "must name the real cause, not clicks/hand-size, got: " @result))
            (is (empty? @sent)
                "must not send end-turn into a game that no longer exists")))))))

(deftest test-end-turn-nil-clicks-mid-resync-does-not-throw
  (testing "state present but click field nil (partial resync) still must not throw"
    ;; The narrower shape of the same hazard: a resync that delivered the game
    ;; map but not yet the side maps. `clicks` is nil here too.
    (let [sent (atom [])]
      (with-mock-state (mock-client-state
                        :side "corp"
                        :game-state {:corp {} :runner {} :active-player "corp"})
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (atom nil)]
            (with-out-str (reset! result (basic/end-turn!)))
            (is (map? @result)
                "must return a status map rather than throwing NPE")))))))

;; Guest-panel catch (GPT-5.6 Terra) on the first cut of the #109 fix: my
;; (nil? clicks) guard is UNREACHABLE when :side is nil. `let` bindings all
;; evaluate before the `cond`, and the `my-turn?` binding calls
;; `(str/lower-case (name side-kw))` — with :side nil that is `(name nil)`,
;; which throws before any guard runs. It only short-circuits when
;; :active-player is also nil, so a state carrying an active-player but no side
;; still dies with the same unpattern-matchable stack trace the guard was
;; added to eliminate.

(deftest test-end-turn-nil-side-does-not-throw
  (testing "no :side but a populated game-state must still refuse cleanly"
    (let [sent (atom [])]
      (with-mock-state {:side nil
                        :gameid nil
                        :game-state {:active-player "corp" :corp {:click 0}}}
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (atom nil)]
            (with-out-str (reset! result (basic/end-turn!)))
            (is (map? @result)
                "must return a status map, not throw on (name nil)")
            (is (= :error (:status @result))
                (str "no side = no seat = nothing to end, got: " @result))
            (is (empty? @sent)
                "must not send end-turn without knowing which seat we are")))))))

;; ============================================================================
;; #114: a turn whose last click hands the OPPONENT a decision must still end
;; ============================================================================
;; Luna-vs-Luna d840fc14 turn 10: the Corp spent its last click on Public Trail,
;; which gives the RUNNER the choice (take a tag or pay 8). The engine hands the
;; Corp a :waiting pseudo-prompt; check-auto-end-turn! read "prompt is non-nil"
;; as "blocking" and declined to end. When the Runner took the tag, nothing
;; re-ran the check — the turn was orphaned at 0 clicks and both seats deadlocked
;; until an umpire with eval access intervened.
;;
;; Two defects, two fixes:
;;   1. the guidance ("use 'choose' to respond") is unactionable — the Corp has
;;      no choices. Engine-pinned in game.ai-waiting-prompt-test: the prompt
;;      carries :prompt-type :waiting and an empty :choices.
;;   2. nothing re-checked when the block cleared. The turn is ARMED here and
;;      resumed by resume-deferred-auto-end! off the next diff.
;;
;; We do NOT simply end the turn over the waiting prompt (the issue's first
;; suggested bullet). board.cljs' button-pane renders the prompt div instead of
;; basic-actions whenever a prompt is up, so a HUMAN Corp holding this prompt has
;; no End Turn button at all — ending anyway would send what the reference client
;; cannot. Deferring and re-checking gets the same outcome inside the wire spec.

(defn- corp-waiting-on-runner-state
  "Corp at 0 clicks holding the 'Waiting for Runner to make a decision' prompt.
   PROMPT-TYPE defaults to the live wire form (the JSON string), not the keyword."
  [& {:keys [prompt-type turn] :or {prompt-type "waiting" turn 10}}]
  {:corp {:click 0 :credit 3 :hand [] :hand-count 3
          :hand-size {:total 5} :installed {} :servers {}
          :prompt-state {:msg "Waiting for Runner to make a decision"
                         :prompt-type prompt-type
                         :eid {:eid 4242}}}
   :runner {:click 0 :credit 5 :hand []}
   :turn turn
   :active-player "corp"
   :log []})

(deftest test-auto-end-waiting-prompt-does-not-coach-choose
  (testing "#114: an opponent-owed prompt must not be described as ours to resolve"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "corp"
                                          :game-state (corp-waiting-on-runner-state))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (let [out (with-out-str (basic/check-auto-end-turn!))]
            (is (not (re-find #"'choose'" out))
                (str "must not tell a seat with no choices to 'choose', got: " out))
            (is (not (re-find #"Active prompt must be resolved first" out))
                (str "we cannot resolve the opponent's decision, got: " out))
            (is (re-find #"(?i)runner" out)
                (str "must name who actually owes the decision, got: " out))
            (is (re-find #"(?i)automatic" out)
                (str "must promise the re-check so the seat doesn't hunt, got: " out))
            (is (empty? @sent)
                "must not end the turn while the reference client shows no End Turn button")))))))

(deftest test-auto-end-waiting-prompt-arms-the-recheck
  (testing "#114: deferring records enough to validate the resume later"
    (with-mock-state (mock-client-state :side "corp"
                                        :game-state (corp-waiting-on-runner-state))
      (with-redefs [ws/send-message! (fn [& _] nil)
                    basic/turn-started-since-last-opp-end? (fn [] true)]
        (with-out-str (basic/check-auto-end-turn!))
        (let [armed (:auto-end-deferred @state/client-state)]
          (is (some? armed) "the waiting branch must arm the deferred re-check")
          (is (= 10 (:turn armed)) "pinned to the turn it was armed on")
          (is (= :corp (:side armed)) "and to the seat that owes the end-turn"))))))

(deftest test-auto-end-waiting-prompt-arms-on-keyword-prompt-type
  (testing "#114: fixture/keyword :waiting is recognised too, not just the wire string"
    (with-mock-state (mock-client-state
                       :side "corp"
                       :game-state (corp-waiting-on-runner-state :prompt-type :waiting))
      (with-redefs [ws/send-message! (fn [& _] nil)
                    basic/turn-started-since-last-opp-end? (fn [] true)]
        (with-out-str (basic/check-auto-end-turn!))
        (is (some? (:auto-end-deferred @state/client-state)))))))

(deftest test-real-prompt-still-blocks-and-does-not-arm
  (testing "#114 no over-reach: a prompt WE owe keeps the original blocking behaviour"
    (let [sent (atom [])
          gs (assoc-in (corp-waiting-on-runner-state)
                       [:corp :prompt-state]
                       {:msg "Choose a card to discard" :prompt-type "select"
                        :choices [{:value "x"}] :eid {:eid 1}})]
      (with-mock-state (mock-client-state :side "corp" :game-state gs)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (let [out (with-out-str (basic/check-auto-end-turn!))]
            (is (re-find #"Active prompt must be resolved first" out)
                (str "an actionable prompt must still block loudly, got: " out))
            (is (nil? (:auto-end-deferred @state/client-state))
                "and must NOT arm a deferred end — we owe this one")
            (is (empty? @sent))))))))

;; --- the resume half -------------------------------------------------------

(defn- armed-state
  "Client state armed for a deferred auto-end, with GAME-STATE as the world now.
   The arm's :gameid is taken from the state being built, exactly as
   check-auto-end-turn! writes it — a hand-written gameid here would drift from
   the code and turn every one of these into a vacuous 'stale arm' pass."
  [game-state & {:keys [turn side] :or {turn 10 side :corp}}]
  (let [cs (mock-client-state :side (name side) :game-state game-state)]
    (assoc cs :auto-end-deferred {:turn turn :side side :gameid (:gameid cs)})))

(deftest test-resume-deferred-ends-turn-once-opponent-resolves
  (testing "#114: the waiting prompt clearing is what ends the orphaned turn"
    (let [sent (atom [])
          ;; exactly the post-resolution state pinned by game.ai-waiting-prompt-test:
          ;; prompt gone, 0 clicks, turn still open
          gs (assoc-in (corp-waiting-on-runner-state) [:corp :prompt-state] nil)]
      (with-mock-state (armed-state gs)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (with-out-str (basic/resume-deferred-auto-end!))
          (is (some #(= "end-turn" (get-in % [:data :command])) @sent)
              (str "the deadlocked turn must end itself, sent: " @sent))
          (is (nil? (:auto-end-deferred @state/client-state))
              "and disarm, so a later diff can't re-send end-turn"))))))

(deftest test-resume-deferred-noop-while-opponent-still-deciding
  (testing "#114: every other diff in the window must be a no-op"
    (let [sent (atom [])]
      (with-mock-state (armed-state (corp-waiting-on-runner-state))
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (with-out-str (basic/resume-deferred-auto-end!))
          (is (empty? @sent) "waiting prompt still up — nothing to do")
          (is (some? (:auto-end-deferred @state/client-state))
              "and stay armed for the diff that does clear it"))))))

(deftest test-resume-deferred-noop-when-not-armed
  (testing "#114: an unarmed client must not auto-end off arbitrary diffs"
    (let [sent (atom [])
          gs (assoc-in (corp-waiting-on-runner-state) [:corp :prompt-state] nil)]
      (with-mock-state (mock-client-state :side "corp" :game-state gs)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (with-out-str (basic/resume-deferred-auto-end!))
          (is (empty? @sent)
              "no arm = no end-turn: diffs arrive constantly, including on the opponent's turn"))))))

(deftest test-resume-deferred-expires-on-a-later-turn
  (testing "#114: a stale arm must never fire into a turn it wasn't armed for"
    (let [sent (atom [])
          ;; turn moved on and it's the Runner's turn now — the arm is garbage
          gs (-> (corp-waiting-on-runner-state :turn 11)
                 (assoc-in [:corp :prompt-state] nil)
                 (assoc :active-player "runner"))]
      (with-mock-state (armed-state gs :turn 10)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (with-out-str (basic/resume-deferred-auto-end!))
          (is (empty? @sent) "must not end-turn off a stale arm")
          (is (nil? (:auto-end-deferred @state/client-state))
              "and must clear the stale arm rather than carry it forward"))))))

(deftest test-smart-end-turn-waiting-prompt-guidance
  (testing "#114: the explicit command must also not blame us for the opponent's prompt"
    (with-mock-state (mock-client-state :side "corp"
                                        :game-state (corp-waiting-on-runner-state))
      (with-redefs [ws/send-message! (fn [& _] nil)
                    basic/turn-started-since-last-opp-end? (fn [] true)]
        (let [out (with-out-str (basic/smart-end-turn!))]
          (is (not (re-find #"Resolve the prompt first" out))
              (str "there is no prompt we can resolve, got: " out))
          (is (re-find #"(?i)runner" out)
              (str "must name who owes the decision, got: " out)))))))

;; --- guest-panel CRITICALs on the first cut of the #114 fix ----------------
;; All three were real. Recorded as tests because none of them is visible from
;; the happy path: the fix worked perfectly in every single-threaded run.

;; The interlock is claim-deferred-arm!: end-turn is gated on WINNING it, not on
;; having seen an arm. Tested as an invariant rather than by racing threads — a
;; race test here does NOT discriminate (verified by mutation: with the interlock
;; removed, 8 concurrent resumes still produced one end-turn, because the window
;; between reading the arm and clearing it is shorter than future-spawn overhead).
;; A test that cannot fail against the broken code is not a regression pin.
(def ^:private claim-arm! #'basic/claim-deferred-arm!)

(deftest test-arm-claim-is-exclusive
  (testing "#114 CRITICAL: exactly one caller may take the arm"
    (let [arm {:turn 10 :side :corp :gameid "g1"}]
      (with-mock-state (assoc (mock-client-state :side "corp") :auto-end-deferred arm)
        (is (true? (claim-arm! arm)) "first caller takes it")
        (is (false? (claim-arm! arm))
            "second caller must LOSE — if it proceeded we would send a duplicate end-turn")
        (is (nil? (:auto-end-deferred @state/client-state)))))))

(deftest test-two-arms-for-the-same-turn-are-distinguishable
  (testing "#114 CRITICAL: the claim must not be ABA-vulnerable"
    ;; A card can hand the opponent two decisions in a row, so the SAME
    ;; (gameid, turn, side) gets armed twice. If those two arms compare equal, a
    ;; caller whose claim was already consumed can match the re-armed value and
    ;; win a second claim — two end-turns, which is unrecoverable here.
    (let [gs (corp-waiting-on-runner-state)
          arm-twice (fn []
                      (with-mock-state (mock-client-state :side "corp" :game-state gs)
                        (with-redefs [ws/send-message! (fn [& _] nil)
                                      basic/turn-started-since-last-opp-end? (fn [] true)]
                          (with-out-str (basic/check-auto-end-turn!))
                          (let [first-arm (:auto-end-deferred @state/client-state)]
                            (with-out-str (basic/check-auto-end-turn!))
                            [first-arm (:auto-end-deferred @state/client-state)]))))
          [a b] (arm-twice)]
      (is (some? a))
      (is (some? b))
      (is (not= a b)
          "re-arming the same turn must produce a DISTINCT arm, or the claim can be won twice")
      (is (= (dissoc a :token) (dissoc b :token))
          "…distinct only by token: the (gameid, turn, side) pin must still match"))))

(deftest test-arm-claim-refuses-a-different-arm
  (testing "#114 CRITICAL: claiming is compare-and-clear, so it cannot eat a newer arm"
    (let [live {:turn 12 :side :corp :gameid "g1"}
          stale {:turn 10 :side :corp :gameid "g1"}]
      (with-mock-state (assoc (mock-client-state :side "corp") :auto-end-deferred live)
        (is (false? (claim-arm! stale)) "a late thread's stale arm must not win")
        (is (= live (:auto-end-deferred @state/client-state))
            "and turn 12's arm must survive — it is the only thing that will end turn 12")))))

(deftest test-concurrent-resumes-send-at-most-one-end-turn
  (testing "#114: stress check (not a regression pin — see comment above)"
    (let [sent (atom [])
          gs (assoc-in (corp-waiting-on-runner-state) [:corp :prompt-state] nil)]
      (with-mock-state (armed-state gs)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (let [threads (mapv (fn [_] (future (with-out-str (basic/resume-deferred-auto-end!))))
                              (range 8))]
            (doseq [t threads] (deref t 10000 :timeout))
            (is (= 1 (count (filter #(= "end-turn" (get-in % [:data :command])) @sent)))
                (str "exactly one end-turn must reach the wire, sent: " @sent))))))))

(deftest test-arm-from-a-different-game-never-fires
  (testing "#114 CRITICAL: the arm now survives clear-game-state!, so it must be game-pinned"
    (let [sent (atom [])
          gs (assoc-in (corp-waiting-on-runner-state) [:corp :prompt-state] nil)]
      (with-mock-state (assoc (mock-client-state :side "corp" :game-state gs)
                              :auto-end-deferred
                              {:turn 10 :side :corp
                               :gameid (java.util.UUID/fromString
                                         "ffffffff-ffff-ffff-ffff-ffffffffffff")})
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      basic/turn-started-since-last-opp-end? (fn [] true)]
          (with-out-str (basic/resume-deferred-auto-end!))
          (is (empty? @sent)
              "turn numbers repeat across games — an arm from a dead game must not end a live turn")
          (is (nil? (:auto-end-deferred @state/client-state))))))))

(deftest test-arm-survives-clear-game-state
  (testing "#114 CRITICAL: a resync must not forget that our turn is orphaned"
    (with-mock-state (assoc (mock-client-state :side "corp")
                            :auto-end-deferred {:turn 10 :side :corp :gameid "g1"})
      (with-out-str (state/clear-game-state!))
      (is (= {:turn 10 :side :corp :gameid "g1"} (:auto-end-deferred @state/client-state))
          "cleared with the rest of game state, the resync hook has nothing to act on"))))

(deftest test-can-start-turn-reports-no-game-state
  (testing "the preflight must refuse nil state too, not just the wire"
    ;; Guest-panel pass 2, HIGH — the half-applied half of the fix above. The
    ;; autonomous loops gate on THIS, not on start-turn!'s return:
    ;;   (let [c (can-start-turn?)] (when (:can-start c) ... (start-turn!)))
    ;; so a preflight that says :first-turn on nil state leaves the bot
    ;; announcing an auto-start, getting refused, and looping — wire safe, seat
    ;; stuck. With no board every input defaults INTO the first-turn signature,
    ;; which is exactly why it has to be refused before any of them are read.
    (with-mock-state (assoc (mock-client-state :side "corp") :game-state nil)
      (let [result (basic/can-start-turn?)]
        (is (false? (:can-start result)))
        (is (= :no-game-state (:reason result))
            "was :first-turn — nil defaults ARE the Corp-first-turn shape")))))
