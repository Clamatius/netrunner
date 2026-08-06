(ns ai-wait-test
  "Regression tests for wait-for-relevant-diff's :since fast path.

   The cursor bumps on every server diff (our own action echoing back,
   opponent economy ticks, resyncs). The :since fast path must short-circuit
   ONLY when something actionable already happened in the race window — a bare
   cursor advance is not a wake, otherwise every --since wait that follows one
   of our own actions false-returns :cursor-advanced with no new log entries."
  (:require [clojure.test :refer :all]
            [test-helpers :refer [with-mock-state]]
            [ai-state :as state]
            [clojure.string :as str]
            [ai-core :as core]))

(defn- mock-game
  "Minimal client-state for wait tests."
  [side game-state]
  {:connected true :uid "test" :side side :game-state game-state})

(deftest test-since-phantom-advance-does-not-short-circuit
  ;; Cursor advanced past :since but nothing relevant happened (opponent still
  ;; mid-turn, no prompt, no run). The old fast path returned
  ;; :already-advanced/:cursor-advanced here on every self-action. It must now
  ;; fall through and wait — with timeout 0 that means a quick :timeout.
  (testing "bare cursor advance with no relevant event falls through to wait"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "runner" :turn 5
                                   :corp {:click 0} :runner {:click 2}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :timeout (:status result))
              (str "phantom cursor advance must not short-circuit, got: " result))
          (is (not= :already-advanced (:status result))))))))

(deftest test-since-advance-lobby-gone-wakes-game-gone
  ;; #93: the server closed our lobby out from under us. A seat blocked in
  ;; `wait` must wake with :game-gone instead of sleeping the full timeout on
  ;; a game that no longer exists.
  (testing "lobby-gone -> :game-gone wake"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (assoc (mock-game "runner"
                                         {:active-player "runner" :turn 9
                                          :corp {:click 0} :runner {:click 1}})
                              :lobby-gone? true)
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :game-gone (:reason result))
              (str "expected :game-gone, got: " result)))))))

(deftest test-since-relevant-advance-short-circuits-my-turn
  ;; A genuine race: opponent ended their turn in the gap, so it's our turn.
  ;; The fast path must still return immediately with the real reason.
  (testing "cursor advanced AND it's my turn -> :already-advanced :my-turn"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "corp" :turn 5
                                   :corp {:click 3} :runner {:click 0}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :my-turn (:reason result))
              (str "expected :my-turn, got: " result)))))))

(deftest test-since-advance-turn-boundary-is-my-turn-start
  ;; Opponent ended their turn in the race window: it's our turn but the turn
  ;; hasn't been started yet (0 clicks). This must wake with :my-turn-start, NOT
  ;; :my-turn — the seat needs to call start-turn first, and the distinct reason
  ;; keeps a clean boundary from looking like an actionable turn (or a stall).
  (testing "opponent ended turn, 0 clicks -> :already-advanced :my-turn-start"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "runner" :turn 5 :end-turn true
                                   :corp {:click 0} :runner {:click 0}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :my-turn-start (:reason result))
              (str "expected :my-turn-start, got: " result)))))))

(deftest test-since-advance-turn-zero-corp-is-my-turn-start
  ;; Post-mulligan, turn 0, Corp goes first but has 0 clicks until start-turn.
  ;; Same boundary semantics as a normal turn handoff.
  (testing "turn 0 corp, 0 clicks -> :already-advanced :my-turn-start"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "corp" :turn 0
                                   :corp {:click 0} :runner {:click 0}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :my-turn-start (:reason result))
              (str "expected :my-turn-start, got: " result)))))))

(deftest test-since-relevant-advance-short-circuits-run-started
  ;; A run started in the race window — still a wake.
  (testing "cursor advanced AND a run is active -> :already-advanced :run-started"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "runner" :turn 5
                                   :run {:phase "approach-ice" :server "hq"}
                                   :corp {:click 0} :runner {:click 0}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :run-started (:reason result))
              (str "expected :run-started, got: " result)))))))

(deftest test-since-not-advanced-waits
  ;; Cursor has NOT advanced past :since — no fast path regardless of state.
  (testing "cursor not advanced -> normal wait (quick timeout here)"
    (with-redefs [state/get-cursor (fn [] 5)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "runner" :turn 5
                                   :corp {:click 0} :runner {:click 2}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :timeout (:status result))))))))

(deftest test-game-over-wakes-via-fast-path
  ;; Game ended in the race window (Runner won on their own turn while Corp was
  ;; in a `wait`). The cursor advanced; the fast path must short-circuit with
  ;; :game-over — NOT fall through, and NOT mistake a frozen non-our-turn for a
  ;; phantom advance. This is the #5 marquee rough edge: a finished game left a
  ;; waiting seat hanging the full timeout.
  (testing "game over + cursor advanced -> :already-advanced :game-over"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "runner" :turn 10 :winner "runner"
                                   :corp {:click 0} :runner {:click 1}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :game-over (:reason result))
              (str "expected :game-over, got: " result)))))))

(deftest test-game-over-wakes-without-since
  ;; No :since cursor — the normal poll loop must wake on :game-over rather than
  ;; burning the timeout. game-over outranks the timeout check in the cond.
  (testing "game over, no :since -> :relevant-change :game-over (not :timeout)"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "runner" :turn 10 :winner "runner"
                                   :corp {:click 0} :runner {:click 1}})
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :relevant-change (:status result))
              (str "game-over must wake, not time out, got: " result))
          (is (= :game-over (:reason result))))))))

(deftest test-game-over-via-reason-and-end-time
  ;; The other game-over signal: no :winner, but :reason + :end-time set
  ;; (tie / timed-out match). Must wake the same way.
  (testing "reason + end-time game over -> :game-over"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                                  {:active-player "corp" :turn 10
                                   :reason "Decked" :end-time "2026-06-20T00:00:00Z"
                                   :corp {:click 2} :runner {:click 0}})
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (= :already-advanced (:status result)))
          (is (= :game-over (:reason result))
              (str "expected :game-over, got: " result)))))))

;; ---------------------------------------------------------------------------
;; #46 — wait must not NPE in the lobby / pre-game (nil :side, no active game)
;; ---------------------------------------------------------------------------

(deftest test-wait-in-lobby-does-not-npe
  ;; Repro for #46: calling `wait` after joining a lobby but before the game
  ;; starts. client-state has no :side and no :game-state, so the turn
  ;; predicates used to `(name nil)` and NPE. It must instead return cleanly.
  (testing "wait in lobby (nil side, nil game-state) returns cleanly, no NPE"
    (with-redefs [state/get-cursor (fn [] 0)]
      (with-mock-state {:connected true :uid "test" :side nil :game-state nil}
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :timeout (:status result))
              (str "lobby wait must not NPE, got: " result))
          (is (= :no-game (:reason result))
              (str "lobby timeout should be flagged :no-game, got: " result)))))))

(deftest test-wait-not-in-game-when-active-player-absent
  ;; A game-state exists but no :active-player yet (still resolving the lobby /
  ;; pre-mulligan). Treat as not-yet-started rather than acting on nil turn data.
  (testing "game-state present but no active-player -> lobby-safe :no-game"
    (with-redefs [state/get-cursor (fn [] 0)]
      (with-mock-state {:connected true :uid "test" :side "runner"
                        :game-state {:turn 0}}
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :timeout (:status result)))
          (is (= :no-game (:reason result))))))))

;; ---------------------------------------------------------------------------
;; #47 — wait must wake on an already-pending encounter decision (unbroken subs
;; requiring break/tank/jack-out) that is NOT modelled as a server prompt.
;; ---------------------------------------------------------------------------

(def ^:private encounter-game-state
  {:active-player "runner" :turn 3
   :runner {:click 0}
   :run {:phase "encounter-ice" :position 1 :server ["remote1"]}
   :corp {:click 0
          :servers {:remote1 {:ices [{:title "Tithe" :rezzed true
                                      :subroutines [{:broken false :fired false}
                                                    {:broken false :fired false}]}]}}}})

(deftest test-wait-wakes-on-runner-encounter-decision
  ;; Repro for #47: Runner at encounter-ice with a rezzed ICE that still has
  ;; unbroken subs. There is no server :prompt, so the old wait slept the full
  ;; timeout; the very next `continue` surfaced "N unbroken subs - authorization
  ;; required". wait must now wake immediately with :encounter-decision.
  (testing "Runner encounter with unbroken subs wakes wait (#47)"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner" encounter-game-state)
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :relevant-change (:status result))
              (str "encounter decision must wake, not time out, got: " result))
          (is (= :encounter-decision (:reason result))
              (str "expected :encounter-decision, got: " result)))))))

(deftest test-wait-no-encounter-wake-when-runner-passed
  ;; Once the Runner has passed priority in the encounter (encounter
  ;; :no-action = runner), the break/tank/jack-out choice is already made and we
  ;; are merely waiting on the Corp to fire subs — waiting-for-opponent, NOT a
  ;; pending Runner decision. Must not re-wake as :encounter-decision (Codex
  ;; review catch: previously suppressed on the wrong side + wrong state path).
  (testing "Runner already passed the encounter -> no wake, times out"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner"
                          (assoc encounter-game-state :encounters {:no-action "runner"}))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :timeout (:status result))
              (str "runner-passed encounter must not wake, got: " result)))))))

(deftest test-wait-encounter-wake-when-corp-passed
  ;; Corp passed first (encounter :no-action = corp) but the Runner has NOT — the
  ;; Runner can still break before their own pass ends the encounter, so this is
  ;; a live decision and must still wake. Regression guard against suppressing on
  ;; the corp side (the pre-review behaviour, which was backwards).
  (testing "Corp passed, Runner has not -> still wakes :encounter-decision"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner"
                          (assoc encounter-game-state :encounters {:no-action "corp"}))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :encounter-decision (:reason result))
              (str "corp-passed (runner active) must still wake, got: " result)))))))

(deftest test-wait-no-encounter-wake-when-subs-broken
  ;; All subs broken -> nothing to authorize. No wake.
  (testing "all subs broken -> no encounter wake"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner"
                          (assoc-in encounter-game-state
                                    [:corp :servers :remote1 :ices 0 :subroutines]
                                    [{:broken true :fired false} {:broken true :fired false}]))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :timeout (:status result))
              (str "all-broken encounter must not wake, got: " result)))))))

;; ---------------------------------------------------------------------------
;; #91: `wait` must wake a seat that ACQUIRES priority at a run pass-window it
;; owns. Repro: Runner runs server A, passes, waits on Corp; the run advances to
;; a pass window the Runner owns first — but with 0 clicks (spent on the run)
;; my-turn-to-act? is false, an empty run window is not an actionable prompt, and
;; if the wait began at that phase there is no phase-change. relevance-reason
;; returned nil → the seat slept through its own move → permanent deadlock
;; (confirmed on BOTH seats, marquee G2 turn 7).
;;
;; NB the "approach-server" the seats SEE is a display label, not a wire phase:
;; the engine set-phases only :approach-ice/:encounter-ice/:movement/:success/
;; :initiation, and the approach-server window is {:phase "movement" :position 0}.
;; These fixtures use that real wire shape (an earlier draft used a fictional
;; :phase "approach-server" — caught in review before it could "verify" #91
;; against a state that never occurs).
;; ---------------------------------------------------------------------------

(def ^:private approach-server-game-state
  "The real approach-server window — {:phase \"movement\" :position 0} — with the
   run active, Runner owning the first pass, holding 0 clicks (spent on the run)."
  {:active-player "runner" :turn 7
   :runner {:click 0
            :prompt-state {:prompt-type "run" :choices [] :selectable []}}
   :corp {:click 0}
   :run {:phase "movement" :position 0 :server ["archives"] :no-action false}})

(deftest test-wait-runner-owns-approach-server-window-wakes
  (testing "#91: Runner owning the un-passed approach-server window (movement/pos-0)
            wakes :my-run-window instead of sleeping through its own move"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner" approach-server-game-state)
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :relevant-change (:status result))
              (str "owned run window must wake, not time out, got: " result))
          (is (= :my-run-window (:reason result))
              (str "expected :my-run-window, got: " result)))))))

(deftest test-wait-runner-inter-ice-movement-window-wakes
  (testing "#91: an inter-ice movement window (position > 0) is also Runner-owned"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner"
                          (assoc-in approach-server-game-state [:run :position] 1))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :my-run-window (:reason result))
              (str "expected :my-run-window at inter-ice movement, got: " result)))))))

;; #102(1): the SAME owned window, but with clicks still in hand. A run costs one
;; click, so a Runner mid-run normally holds 2-3 — which made my-turn-to-act? true
;; and won the cond ahead of :my-run-window. `wait` then returned instantly with
;; :my-turn while a continue was owed at movement/approach-server, misdirecting the
;; seat into start-turn thinking (both Fable runner sessions, marquee 30c4a1c0).
;; Owning an un-passed run window is the more specific fact: report that.

(deftest test-wait-runner-owns-window-with-clicks-in-hand-is-run-aware
  (testing "#102: mid-run with clicks left, an owed continue reports :my-run-window, not :my-turn"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner"
                          (assoc-in approach-server-game-state [:runner :click] 2))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :my-run-window (:reason result))
              (str "a live run with an owed continue must not read as :my-turn, got: " result)))))))

(deftest test-wait-runner-owns-approach-ice-window-wakes
  (testing "#91: the approach-ice pass window is owned by the Runner first too"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner"
                          (-> approach-server-game-state
                              (assoc-in [:run :phase] "approach-ice")
                              (assoc-in [:run :position] 1)))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :my-run-window (:reason result))
              (str "expected :my-run-window at approach-ice, got: " result)))))))

(deftest test-wait-runner-does-not-wake-after-passing
  (testing "#91 no-spin: once the Runner has passed (run :no-action = runner) it
            owns nothing and must sleep, waiting on the Corp"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner"
                          (assoc-in approach-server-game-state [:run :no-action] "runner"))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :timeout (:status result))
              (str "a passed Runner must not re-wake on its own window, got: " result)))))))

(deftest test-wait-corp-wakes-after-runner-passes
  (testing "#91 symmetry: once the Runner has passed, the Corp OWNS the second
            pass and must wake to advance the run"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                          (assoc-in approach-server-game-state [:run :no-action] "runner"))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :my-run-window (:reason result))
              (str "Corp owning the second pass must wake, got: " result)))))))

(deftest test-wait-corp-does-not-wake-before-runner-passes
  (testing "#91 no-spurious-wake: while the Runner still owes the first pass, the
            Corp does not own the window and must keep waiting"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp" approach-server-game-state)
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :timeout (:status result))
              (str "Corp must not wake before the Runner passes, got: " result)))))))

(deftest test-wait-encounter-ice-not-treated-as-pass-window
  (testing "#91 non-interference: encounter-ice is handled by :encounter-decision,
            not :my-run-window (the break/tank flow must be untouched)"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner" encounter-game-state)
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :encounter-decision (:reason result))
              (str "encounter-ice must stay :encounter-decision, got: " result)))))))

;; ---------------------------------------------------------------------------
;; #87: `wait` must not false-wake at the opening-mulligan boundary.
;;
;; After keep-hand the Corp holds a "Waiting for Runner to keep hand or mulligan"
;; prompt. my-turn-to-act?'s turn-0/Corp/0-clicks branch fired anyway, so
;; relevance-reason returned :my-turn-start and `wait --since` returned INSTANTLY
;; and repeatedly — but start-turn then refuses (:opponent-mulligan), because
;; can-start-turn? consults a mulligan guard that `wait` did not. Two sources of
;; truth for "is it my move", the #31/#68/#77 family. The blocking primitive
;; failed at the ONE boundary where a seat has nothing to do but block, and the
;; false alarm cost a real umpire escalation in marquee G2.
;;
;; Fix: the wake reason and what start-turn accepts must AGREE — my-turn-to-act?
;; now consults the same core/opponent-mulligan-pending? guard.
;; ---------------------------------------------------------------------------

(def ^:private corp-awaiting-runner-mulligan
  "Corp kept its hand; the Runner has NOT resolved its opening mulligan yet.
   Exactly the state ai-basic-actions' start-turn! refuses with :opponent-mulligan."
  {:active-player "corp" :turn 0
   :corp {:click 0
          :prompt-state {:msg "Waiting for Runner to keep hand or mulligan"
                         :prompt-type "waiting" :selectable []}}
   :runner {:click 0}
   :log []})

(deftest test-wait-does-not-false-wake-at-mulligan-boundary
  (testing "#87: pending opponent mulligan must keep blocking, not wake :my-turn-start"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp" corp-awaiting-runner-mulligan)
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (not= :my-turn-start (:reason result))
              (str "must not false-wake while opponent mulligan is pending, got: " result))
          (is (= :timeout (:status result))
              (str "wait must keep blocking across the mulligan boundary, got: " result)))))))

(deftest test-wait-since-does-not-false-wake-at-mulligan-boundary
  (testing "#87: the --since fast path must not false-wake either (the reported symptom
            was `wait --since <cursor>` returning instantly and repeatedly)"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp" corp-awaiting-runner-mulligan)
        (let [result (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose false})]
          (is (not= :my-turn-start (:reason result))
              (str "--since must not short-circuit on a pending mulligan, got: " result))
          (is (= :timeout (:status result))
              (str "expected the fast path to fall through to a real wait, got: " result)))))))

(deftest test-wait-wakes-once-mulligan-resolves
  (testing "#87 liveness: once the mulligan prompt clears, the Corp's turn-start
            wake fires normally (the fix must not deadlock the boundary it guards)"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp" (dissoc corp-awaiting-runner-mulligan :corp))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :my-turn-start (:reason result))
              (str "with no pending-mulligan prompt the Corp must wake to start, got: " result)))))))

(deftest test-wait-mulligan-guard-does-not-suppress-a-real-prompt
  (testing "#87 non-interference: our OWN actionable mulligan prompt (keep/mulligan)
            still wakes — the guard only suppresses the waiting-on-opponent window"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                         (assoc-in corp-awaiting-runner-mulligan [:corp :prompt-state]
                                   {:msg "Keep hand?" :prompt-type "mulligan"
                                    :choices [{:value "Keep"} {:value "Mulligan"}]}))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :has-prompt (:reason result))
              (str "our own keep/mulligan choice must still wake, got: " result)))))))

(deftest test-wait-timeout-names-the-mulligan-boundary
  (testing "#87: a guarded block must say WHY it timed out — a bare :timeout is
            indistinguishable from a genuine stall, which is the same
            'should I escalate?' ambiguity the false-wake caused"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp" corp-awaiting-runner-mulligan)
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :timeout (:status result)))
          (is (= :opponent-mulligan (:reason result))
              (str "the seat must learn it was blocked on the mulligan, got: " result)))))))

(deftest test-mulligan-guard-suppresses-even-with-clicks-in-hand
  (testing "#87: the guard covers ALL my-turn-to-act? branches, including
            'active player WITH clicks' — that is the already-wedged half-started
            turn the engine can produce (clicks granted, actions bounce off the
            pending prompt). Pinned so the breadth is a decision, not an accident."
    (with-mock-state (mock-game "corp"
                       (-> corp-awaiting-runner-mulligan
                           (assoc-in [:corp :click] 3)))
      (is (not (core/my-turn-to-act? @state/client-state "corp"))
          "clicks in hand must not override the pending-mulligan guard"))))

(deftest test-turn-status-does-not-say-ready-during-opponent-mulligan
  (testing "#87 (the surface a seat reads FIRST): get-turn-status backs the turn
            indicator appended to EVERY command's output. Fixing only the wake path
            would relocate the lie here — both-zero-clicks + turn 0 made the Corp
            'next' and it printed '🟢 Ready to start your turn' while start-turn
            refused. One predicate, one answer."
    (with-mock-state (mock-game "corp" corp-awaiting-runner-mulligan)
      (let [ts (state/get-turn-status)]
        (is (false? (:can-act? ts))
            (str "must not report actionable at the mulligan boundary, got: " ts))
        (is (not (str/includes? (str (:status-text ts)) "Ready to start"))
            (str "must not tell the seat to start its turn, got: " ts))
        (is (str/includes? (str/lower-case (str (:status-text ts))) "mulligan")
            (str "should name the mulligan as the blocker, got: " ts))))))

(deftest test-turn-status-recovers-once-opponent-keeps
  (testing "#87 liveness on the status surface: with the prompt cleared the Corp is
            told it can start again"
    (with-mock-state (mock-game "corp" (dissoc corp-awaiting-runner-mulligan :corp))
      (let [ts (state/get-turn-status)]
        (is (true? (:can-act? ts))
            (str "must recover once the mulligan resolves, got: " ts))))))

(deftest test-engine-keep-flag-overrides-a-stale-mulligan-prompt
  (testing "#87 anti-deadlock: the engine's own :keep flag WINS over a lingering
            wait prompt. Prompt-text alone had no liveness cross-check, so a stale
            or mis-cleared prompt would have blocked the seat permanently."
    (with-mock-state (mock-game "corp"
                       (assoc-in corp-awaiting-runner-mulligan [:runner :keep] "keep"))
      (is (not (state/opponent-mulligan-pending? @state/client-state))
          "engine says the Runner resolved — a leftover prompt must not deadlock us")
      (is (true? (:can-act? (state/get-turn-status)))
          "and the status surface must agree"))))
