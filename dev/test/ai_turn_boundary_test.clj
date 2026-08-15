(ns ai-turn-boundary-test
  "#117 — the turn-boundary surfaces must not contradict each other, or the engine.

   Ground truth in the Luna-vs-Luna deadlock (game d840fc14) was
   `active-player=corp, end-turn=false, corp clicks=0`: the Corp's turn was out
   of clicks but had not ended. Every seat-facing surface pointed at the Runner:

     game-over-status | AWAITING-START turn=10 next-player=runner
     Runner `prompt`  | 🟢 It's YOUR turn but it hasn't started yet -> start-turn
     Corp `prompt`    | ⏳ Waiting for runner to start their turn -> wait
     Corp `status`    | Turn: 10 - corp  AND  🟢 Waiting to start runner turn

   The umpire read the Runner's `prompt`, believed it, and issued a wrong
   instruction. The cause was `both-zero-clicks` standing in for a turn boundary
   in get-turn-status (and again, separately inlined, in show-status). Both sides
   at 0 clicks is ALSO the shape of an orphaned turn, so the heuristic cannot
   tell them apart; the engine's own `:end-turn` flag can, and does.

   These tests construct that exact state and assert (a) the predicates agree,
   (b) every surface agrees with them, and (c) a REAL boundary still reports as
   one — the failure mode of an over-correction here is a seat that never learns
   its turn has started."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [test-helpers :refer [mock-client-state with-mock-state]]
            [ai-state :as state]
            [ai-core :as core]
            [ai-display :as display]))

;; ---------------------------------------------------------------------------
;; The three states under test, as game-state maps.
;; ---------------------------------------------------------------------------

;; show-status reports PLAYER DISCONNECTED unless both seats carry a :user, so
;; the fixtures have to be seated for it to render a turn line at all.
(def ^:private seated-corp {:click 0 :hand-count 0 :user {:username "ai-corp"}})
(def ^:private seated-runner {:click 0 :hand-count 0 :user {:username "ai-runner"}})

(def orphaned-turn
  "#117: Corp's turn, no clicks left, :end-turn NOT set, nothing pending.
   Nobody is owed a start-turn. This is the deadlock shape."
  {:active-player "corp" :turn 10 :end-turn false
   :corp seated-corp :runner seated-runner})

(def real-boundary
  "Corp ENDED its turn. :active-player still names the finisher; the Runner is
   owed the start-turn."
  {:active-player "corp" :turn 10 :end-turn true
   :corp seated-corp :runner seated-runner})

(def pre-first-turn
  "A freshly built game. game.core.state/new-state ships
   `:active-player :runner, :end-turn true, :turn 0` — so Corp-goes-first needs
   no special case, it is just the boundary rule."
  {:active-player "runner" :turn 0 :end-turn true
   :corp seated-corp :runner seated-runner})

(defn- with-side [gs side] (mock-client-state :side side :game-state gs))

;; ---------------------------------------------------------------------------
;; 1. The predicates
;; ---------------------------------------------------------------------------

(deftest orphaned-turn-is-not-a-boundary
  (testing "my-turn-to-act? is false for BOTH sides — the deadlock signature"
    ;; This is the check that would have saved the umpire. If it is false on both
    ;; seats, no `wait` on either side can ever wake: a true deadlock, not a slow
    ;; opponent. Every surface below has to be consistent with THIS.
    (let [cs (with-side orphaned-turn "corp")]
      (is (not (core/my-turn-to-act? cs "corp"))
          "corp is active but has 0 clicks and has not ended -> cannot act")
      (is (not (core/my-turn-to-act? cs "runner"))
          "runner is not active and no end-turn flag -> cannot act")))

  (testing "turn-boundary? is false — no start-turn is owed"
    (is (false? (state/turn-boundary? (with-side orphaned-turn "corp"))))
    (is (false? (state/turn-boundary? (with-side orphaned-turn "runner")))))

  (testing "my-turn-orphaned? is true for the ACTIVE side only"
    ;; Side-relative on purpose: the resolving action is end-turn, and an
    ;; end-turn from the player whose turn it isn't ends the OPPONENT's turn
    ;; and is unrecoverable.
    (is (true? (state/my-turn-orphaned? (with-side orphaned-turn "corp"))))
    (is (false? (state/my-turn-orphaned? (with-side orphaned-turn "runner")))
        "the Runner must never be told a turn is orphaned - it cannot end it")))

(deftest real-boundary-still-reads-as-a-boundary
  (testing "my-turn-to-act? is true for exactly the side owed the start-turn"
    (let [cs (with-side real-boundary "runner")]
      (is (boolean (core/my-turn-to-act? cs "runner")))
      (is (not (core/my-turn-to-act? cs "corp"))
          "the finisher does not get to start the turn it just ended")))

  (testing "turn-boundary? true, my-turn-orphaned? false"
    (is (true? (state/turn-boundary? (with-side real-boundary "corp"))))
    (is (false? (state/my-turn-orphaned? (with-side real-boundary "corp")))
        "a turn that HAS ended is not orphaned"))

  (testing "pre-first-turn is a boundary owed to the Corp"
    (let [cs (with-side pre-first-turn "corp")]
      (is (true? (state/turn-boundary? cs)))
      (is (boolean (core/my-turn-to-act? cs "corp")))
      (is (= "corp" (:next-player (with-mock-state cs (state/get-turn-status))))))))

(deftest mid-run-at-zero-clicks-is-neither
  ;; A run started with the last click leaves both sides at 0 clicks but is
  ;; mid-resolution. It must not get the patient boundary stall budget, and it
  ;; must not be called orphaned either — `continue` is the move, not end-turn.
  (let [gs {:active-player "runner" :turn 6 :end-turn false
            :run {:server [:hq] :position 1}
            :corp seated-corp :runner seated-runner}]
    (is (false? (state/turn-boundary? (with-side gs "runner"))))
    (is (false? (state/my-turn-orphaned? (with-side gs "runner"))))))

(deftest an-open-prompt-outranks-orphaned
  ;; At 0 clicks with a prompt still up, "end your turn" is the wrong advice:
  ;; with an actionable prompt the move is to resolve it, and with a waiting
  ;; prompt the seat is blocked on the opponent (and board.cljs gives a human
  ;; holding one no End Turn button at all). Both have their own branches.
  (testing "actionable prompt -> not orphaned"
    (let [gs (assoc orphaned-turn
                    :corp {:click 0 :user {:username "ai-corp"} :prompt-state {:prompt-type "select"
                                                   :msg "Choose a card to trash"}})]
      (is (false? (state/my-turn-orphaned? (with-side gs "corp"))))))

  (testing "waiting-for-opponent prompt -> not orphaned (this is the #114 window)"
    (let [gs (assoc orphaned-turn
                    :corp {:click 0 :user {:username "ai-corp"} :prompt-state {:prompt-type "waiting"
                                                   :msg "Waiting for Runner to make a decision"}})]
      (is (false? (state/my-turn-orphaned? (with-side gs "corp")))))))

(deftest paid-ability-windows-are-not-orphaned-turns
  ;; Guest-panel CRITICAL. The engine's two paid-ability windows sit at
  ;; clicks=0 with :end-turn still false — the orphaned shape exactly — but the
  ;; resolving action is a PHASE command, not end-turn. Sending end-turn there
  ;; re-enters game.core.turns/end-turn and skips a window the opponent is
  ;; entitled to use.
  ;;
  ;; These are player-togglable settings ("PAW" checkboxes in
  ;; nr.gameboard.player-stats, :force-post-discard-* / :force-phase-12-* in
  ;; core/process-actions), not card effects, so any opponent can turn them on.
  ;; All four keys are serialized to us (core/diffs), so we can see them.
  (testing "post-discard window (end-turn would skip it)"
    (let [gs (assoc orphaned-turn :corp-post-discard {:active true})]
      (is (false? (state/my-turn-orphaned? (with-side gs "corp"))))
      (with-mock-state (with-side gs "corp")
        (is (not (str/includes? (with-out-str (display/show-status)) "smart-end-turn"))
            "no surface may offer end-turn during a post-discard window")
        (is (not (str/includes? (with-out-str (display/show-prompt-detailed)) "end-turn"))))))

  (testing "runner's post-discard window, seen from the runner seat"
    (let [gs (assoc orphaned-turn :active-player "runner"
                    :runner-post-discard {:active true})]
      (is (false? (state/my-turn-orphaned? (with-side gs "runner"))))))

  (testing "phase 1.2 window at 0 clicks"
    ;; Normally unreachable — start-turn grants clicks BEFORE it sets the phase
    ;; flag — but a negative :extra-click-temp can zero them, and the guard costs
    ;; nothing. The lesson the panel actually delivered is that my enumeration of
    ;; zero-click non-boundary states was incomplete, not that this one input is
    ;; common.
    (let [gs (assoc orphaned-turn :corp-phase-12 {:active true})]
      (is (false? (state/my-turn-orphaned? (with-side gs "corp")))))))

(deftest boundary-blocked-by-the-opening-mulligan
  ;; Guest-panel HIGH. The opening mulligan IS a boundary (:end-turn ships true
  ;; on a new game) but my-turn-to-act? is deliberately false for the Corp until
  ;; the Runner finishes (#87). Deriving "am I next" by comparing side names
  ;; agreed with the predicate everywhere EXCEPT here, so the surfaces split:
  ;; game-over-status said the Corp was up, the Corp's own prompt said it was
  ;; waiting.
  ;;
  ;; The fix that this test actually pins is the `blocked=` field. The companion
  ;; change — get-turn-status calling my-turn-to-act? instead of comparing side
  ;; names — is NOT pinned by anything here and cannot be: the
  ;; opponent-mulligan-pending? branch (#87) runs before the boundary branch and
  ;; already absorbs this input. Reverting that line leaves the suite green. It
  ;; is a structural guard against the branch order changing, and it is labelled
  ;; as such rather than credited with a red it does not produce.
  (let [gs (assoc pre-first-turn
                  :corp {:click 0 :hand-count 0 :user {:username "ai-corp"}
                         :prompt-state {:prompt-type "waiting"
                                        :msg "Waiting for Runner to keep hand or mulligan"}})]
    (testing "corp cannot act, and is not told it can"
      (with-mock-state (with-side gs "corp")
        (let [ts (state/get-turn-status)]
          (is (false? (:can-act? ts)))
          (is (not (core/my-turn-to-act? (with-side gs "corp") "corp"))))))

    (testing "the machine line names the blocker instead of just pointing at corp"
      (with-mock-state (with-side gs "corp")
        (is (= "AWAITING-START turn=0 next-player=corp blocked=opponent-mulligan"
               (str/trim (with-out-str (display/game-over-status)))))))))

;; ---------------------------------------------------------------------------
;; 1b. The MIRROR of the above: the mulligan *I* still owe.
;; ---------------------------------------------------------------------------

;; Both fixtures below carry `:keep`, which every fixture in this file used to
;; omit. That omission is exactly why the suite stayed green through the bug:
;; the engine ships `:keep false` for BOTH players at turn 0 (set-up.clj writes
;; :keep/:mulligan only once the player answers), so a fixture without the field
;; models a state that cannot occur — and it is the field the bug lives in.
(def ^:private my-mulligan-unresolved
  "Turn 0, nobody has answered yet. The Corp holds its OWN 'Keep hand?' decision
   prompt — not the 'waiting for opponent' window that #87 covers."
  (assoc pre-first-turn
         :corp {:click 0 :hand-count 5 :keep false :user {:username "ai-corp"}
                :prompt-state {:prompt-type "mulligan" :msg "Keep hand?"
                               :choices [{:value "Keep"} {:value "Mulligan"}]}}
         :runner {:click 0 :hand-count 5 :keep false :user {:username "ai-runner"}
                  :prompt-state {:prompt-type "mulligan" :msg "Keep hand?"}}))

(def ^:private both-kept
  "Both players have answered. This is the first state in which the Corp really
   is owed a start-turn — the over-correction guard for the branch added below."
  (assoc pre-first-turn
         :corp {:click 0 :hand-count 5 :keep "keep" :user {:username "ai-corp"}}
         :runner {:click 0 :hand-count 5 :keep "keep" :user {:username "ai-runner"}}))

(deftest boundary-blocked-by-my-own-opening-mulligan
  ;; The mirror image of boundary-blocked-by-the-opening-mulligan, and the half
  ;; that was missing. #87 enumerated "the OPPONENT still owes a mulligan" and
  ;; stopped; when *I* am the one who owes it, opponent-mulligan-pending? is
  ;; false, the boundary branch runs, and at turn 0 the Corp is i-am-next — so
  ;; every surface said "🟢 Ready to start your turn".
  ;;
  ;; Unlike the #87 half this is not only a wording bug. The engine enforces no
  ;; mulligan ordering (it trusts the client), and the reference client's only
  ;; guard is that build-start-box is a modal covering the board — which a seat
  ;; sending raw commands does not have. Taking the advice really does start the
  ;; turn and take the mandatory draw with the mulligan still live: observed on
  ;; game e753fdee as Turn 1 / 3 clicks / a KEPT SIX-CARD starting hand.
  (testing "the predicate: pending for the side that has not answered, not the side that has"
    (is (true? (state/my-mulligan-pending? (with-side my-mulligan-unresolved "corp"))))
    (is (true? (state/my-mulligan-pending? (with-side my-mulligan-unresolved "runner"))))
    (is (false? (state/my-mulligan-pending? (with-side both-kept "corp")))))

  (testing "a capitalized :side must not fail the guard OPEN"
    ;; reconnect-game! (`make resume`) writes :side as "Corp"/"Runner" until the
    ;; resync full-state normalizes it. Hand-rolling (keyword (:side cs)) yields
    ;; :Corp, misses the [:game-state side ...] lookup, and reads nil — which is
    ;; NOT false, so the guard would silently allow the very thing it exists to
    ;; stop. Failing open is the whole bug, so this one goes through my-side-kw.
    (is (true? (state/my-mulligan-pending? (with-side my-mulligan-unresolved "Corp"))))
    (is (false? (state/my-mulligan-pending? (with-side both-kept "Corp")))))

  (testing "corp is not told it can act while it still owes the decision"
    (with-mock-state (with-side my-mulligan-unresolved "corp")
      (let [ts (state/get-turn-status)]
        (is (false? (:can-act? ts))
            "THE bug: this was true, and the 💡 hint said 'use start-turn'")
        (is (not (str/includes? (:status-text ts) "Ready to start your turn"))
            "the headline must not advise start-turn over a live mulligan")
        (is (str/includes? (str/lower-case (:status-text ts)) "mulligan")
            "it must name the decision the seat actually owes"))))

  (testing "the machine line names this blocker too"
    ;; open-prompt=mine rides along: the mulligan decision IS an open prompt of
    ;; ours, so both fields are true and the contract for this line is additive.
    (with-mock-state (with-side my-mulligan-unresolved "corp")
      (is (= "AWAITING-START turn=0 next-player=corp open-prompt=mine blocked=my-mulligan"
             (str/trim (with-out-str (display/game-over-status)))))))

  (testing "over-correction guard: once both have kept, the Corp IS ready"
    (with-mock-state (with-side both-kept "corp")
      (let [ts (state/get-turn-status)]
        (is (true? (:can-act? ts)))
        (is (str/includes? (:status-text ts) "Ready to start your turn"))))))

;; ---------------------------------------------------------------------------
;; 2. get-turn-status — the one derivation the surfaces share
;; ---------------------------------------------------------------------------

(deftest get-turn-status-on-an-orphaned-turn
  (testing "corp seat: not waiting-to-start, orphaned, cannot act"
    (with-mock-state (with-side orphaned-turn "corp")
      (let [ts (state/get-turn-status)]
        (is (false? (:waiting-to-start? ts))
            "THE bug: this was true, which is what made every surface point at the runner")
        (is (true? (:turn-orphaned? ts)))
        (is (false? (:can-act? ts)))
        (is (true? (:my-turn? ts)) "the turn is still ours")
        (is (str/includes? (:status-text ts) "has NOT ended")))))

  (testing "runner seat: not waiting-to-start, not orphaned, not my turn"
    (with-mock-state (with-side orphaned-turn "runner")
      (let [ts (state/get-turn-status)]
        (is (false? (:waiting-to-start? ts)))
        (is (false? (:turn-orphaned? ts)))
        (is (false? (:my-turn? ts)))
        (is (false? (:can-act? ts)))))))

(deftest get-turn-status-on-a-real-boundary
  (testing "the side owed the start-turn can act"
    (with-mock-state (with-side real-boundary "runner")
      (let [ts (state/get-turn-status)]
        (is (true? (:waiting-to-start? ts)))
        (is (= "runner" (:next-player ts)))
        (is (true? (:can-act? ts))))))

  (testing "the side that just ENDED its turn cannot"
    ;; Regression pin: with `both-zero-clicks` gone, this state falls to the
    ;; boundary branch, whose else-arm is the only thing standing between the
    ;; finisher and "🟢 Ready to start turn" about the turn it just finished.
    (with-mock-state (with-side real-boundary "corp")
      (let [ts (state/get-turn-status)]
        (is (true? (:waiting-to-start? ts)))
        (is (false? (:can-act? ts)))
        (is (false? (:turn-orphaned? ts)))
        (is (str/includes? (:status-text ts) "Waiting for runner"))))))

;; ---------------------------------------------------------------------------
;; 3. The surfaces themselves — the actual #117 ask
;; ---------------------------------------------------------------------------

(deftest surfaces-agree-on-an-orphaned-turn
  (testing "corp seat: game-over-status / prompt / status all say the turn is still the Corp's"
    (with-mock-state (with-side orphaned-turn "corp")
      (let [gos (str/trim (with-out-str (display/game-over-status)))
            prm (with-out-str (display/show-prompt-detailed))
            sts (with-out-str (display/show-status))]
        (is (= "IN-PROGRESS turn=10 whose-turn=corp clicks=0 owes=end-turn" gos)
            "was: AWAITING-START turn=10 next-player=runner")
        (is (str/includes? prm "still YOUR turn")
            "was: ⏳ Waiting for runner to start their turn")
        (is (str/includes? sts "Turn: 10 - corp"))
        (is (not (str/includes? sts "Waiting to start runner"))
            "the contradictory second line, printed in the same block as Turn: 10 - corp")
        ;; No surface may route the seat to a start-turn or a wait here: both
        ;; sleep forever, which is precisely how the match deadlocked.
        (is (not (str/includes? prm "start-turn")))
        (is (not (str/includes? prm "hasn't started yet"))))))

  (testing "runner seat: told it is the Corp's turn, and NEVER offered end-turn"
    (with-mock-state (with-side orphaned-turn "runner")
      (let [gos (str/trim (with-out-str (display/game-over-status)))
            prm (with-out-str (display/show-prompt-detailed))
            sts (with-out-str (display/show-status))]
        (is (= "IN-PROGRESS turn=10 whose-turn=corp clicks=0" gos)
            "no owes=end-turn on the seat that must not send one")
        (is (str/includes? prm "corp's turn, not yours")
            "was: 🟢 It's YOUR turn but it hasn't started yet -> use 'start-turn'")
        (is (not (str/includes? prm "start-turn")))
        (is (not (str/includes? (str prm sts) "end-turn"))
            "an end-turn from the Runner here ends the CORP's turn: unrecoverable")
        (is (str/includes? sts "Turn: 10 - corp"))))))

(deftest surfaces-agree-on-a-real-boundary
  (testing "runner seat is told to start its turn"
    (with-mock-state (with-side real-boundary "runner")
      (let [gos (str/trim (with-out-str (display/game-over-status)))
            prm (with-out-str (display/show-prompt-detailed))
            sts (with-out-str (display/show-status))]
        (is (= "AWAITING-START turn=10 next-player=runner" gos))
        (is (str/includes? prm "start-turn"))
        (is (str/includes? sts "Turn: 10 - runner")
            "the Turn line shows who is UP, not the finisher (matches snapshot)")
        (is (str/includes? sts "start-turn")))))

  (testing "corp seat is told to wait, and is not offered end-turn again"
    (with-mock-state (with-side real-boundary "corp")
      (let [prm (with-out-str (display/show-prompt-detailed))
            sts (with-out-str (display/show-status))]
        (is (str/includes? prm "Waiting for runner to start"))
        (is (not (str/includes? sts "smart-end-turn"))
            "the turn already ended; re-sending end-turn would end the RUNNER's")))))

(deftest diagnose-blocker-names-the-orphaned-turn
  ;; diagnose-blocker is the surface a stuck seat actually reaches for, so it is
  ;; the one that must not answer "wait" here — no wait on either side can wake.
  (testing "corp seat gets the end-turn steer, not a wait"
    (with-mock-state (with-side orphaned-turn "corp")
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        (is (str/includes? out "has NOT ended"))
        (is (str/includes? out "smart-end-turn"))
        (is (not (str/includes? out "Waiting for runner to start their turn"))))))

  (testing "runner seat is not offered end-turn"
    (with-mock-state (with-side orphaned-turn "runner")
      (let [out (with-out-str (display/show-blocker-diagnosis))]
        (is (not (str/includes? out "end-turn")))
        (is (str/includes? out "corp's turn, not yours"))))))
