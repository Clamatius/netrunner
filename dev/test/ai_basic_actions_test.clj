(ns ai-basic-actions-test
  "Regression tests for ai_basic_actions.clj.

   Locks down: post-discard pause guard in start-turn! (commit 4ad15ddbc).
   Upstream's two-phase end-turn pauses on :corp-post-discard / :runner-post-discard
   when a card sets :force-post-discard-{self,opponent}. Sending start-turn during
   that pause desyncs the engine."
  (:require [clojure.test :refer :all]
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
