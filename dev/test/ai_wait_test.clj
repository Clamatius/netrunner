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

;; ---------------------------------------------------------------------------
;; #115: the wake REASON must decode itself.
;;
;; :my-run-window fires on every run and was documented nowhere — not in the
;; emitted output, not in any of the 13 seat briefs. Two Luna seats read
;;   ⚡ Woke up: my-run-window
;;   📜 Game log while you were waiting:
;;     (no new entries)
;; as "nothing happened", re-blocked, and sat on the pass that advances the run
;; until the umpire told them what the token meant. Every ping in the first half
;; of that game traced to this; they stopped completely after the explanation.
;;
;; These assert the FRAMING (does the seat learn it owes a move?), never that a
;; particular token appears — a green "the string is present" test is what let
;; the bare token ship in the first place.
;; ---------------------------------------------------------------------------

(deftest test-my-run-window-wake-tells-the-seat-it-owes-the-move
  (testing "#115: waking on an owned run window says the run is stopped on US and
            names an action — a bare reason token is not decodable by a seat"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner" approach-server-game-state)
        (let [out (with-out-str
                    (core/wait-for-relevant-diff {:timeout 0 :verbose true}))]
          (is (str/includes? out "my-run-window")
              (str "fixture sanity: this is the my-run-window wake, got:\n" out))
          (is (re-find #"(?i)you owe|stopped on you" out)
              (str "the seat must learn the move is OWED BY IT, got:\n" out))
          (is (str/includes? out "continue")
              (str "and must be pointed at the verb that advances the window, got:\n" out))
          (is (re-find #"(?i)another `?wait`? (cannot|can't)" out)
              (str "and must be told that re-blocking does not advance it — the exact
                    loop two seats fell into, got:\n" out)))))))

(deftest test-my-run-window-guidance-defuses-the-empty-log
  (testing "#115: an owned window with no new log entries is the trap shape —
            'no new entries' must not be the last word the seat reads"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner" approach-server-game-state)
        (let [out (with-out-str
                    (core/wait-for-relevant-diff {:timeout 0 :verbose true}))]
          (is (str/includes? out "(no new entries)")
              (str "fixture sanity: the empty-log rendering, got:\n" out))
          (is (re-find #"(?i)empty game log|no new entries.*waiting on you|waiting on you" out)
              (str "an empty log at an owned window means the OPPONENT is waiting on
                    us; say so rather than leaving 'no new entries' to be read as
                    'nothing happened', got:\n" out)))))))

;; The same reason token is printed from TWO places — the polling loop above and
;; the :since fast path. Only the polling copy had grown guidance, so every
;; reason but :my-turn-start arrived undecoded on the fast path. One contract,
;; both emitters (the #75/#77/#113 "N senders" shape).

(deftest test-fast-path-decodes-its-wake-reason-too
  (testing "#115: the :already-advanced fast path carries the same guidance as the
            polling loop — a GAME-GONE wake there must still say the game is gone"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (assoc (mock-game "corp"
                                         {:active-player "corp" :turn 5
                                          :corp {:click 3} :runner {:click 0}})
                              :lobby-gone? true)
        (let [out (with-out-str
                    (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose true}))]
          (is (str/includes? out "returning immediately")
              (str "fixture sanity: this is the fast path, got:\n" out))
          (is (re-find #"(?i)gone, not paused|game is GONE" out)
              (str "the fast path must decode the reason, not print a bare token, got:\n" out)))))))

(deftest test-wake-guidance-is-silent-for-reasons-with-nothing-to-add
  (testing "#115: guidance is per-reason, not a blanket paragraph — reasons that
            speak for themselves add no lines"
    (is (empty? (core/wake-reason-guidance-lines :run-started {}))
        "a run starting needs no decoding")
    (is (empty? (core/wake-reason-guidance-lines :has-prompt {}))
        "a prompt is self-describing — the prompt itself is the guidance")))

;; ---------------------------------------------------------------------------
;; #120: the orphaned turn is a MUTUAL deadlock in the wake ladder.
;;
;; State (verified live, game d840fc14): the active player is out of clicks but
;; has NOT latched :end-turn — their last click handed the opponent a decision,
;; auto-end-turn declined, and by the time the opponent resolved it nothing was
;; blocking any more.
;;
;;   active-player=corp, end-turn=false, turn=10, corp clicks=0, runner clicks=0
;;
;; my-turn-to-act? needs clicks>0 on its active-player arm, and its other two
;; arms need :end-turn or turn 0 — so it is false for BOTH seats and
;; relevance-reason returned nil for both. Neither `wait` could ever wake: the
;; Corp, whose only legal move is `end-turn`, was told it had no move.
;;
;; #117 fixed the DISPLAY half (status/prompt name the right side). This is the
;; wake half. The classification is not re-derived here — my-turn-orphaned? in
;; ai-state is the authority, and it is side-relative on purpose: an end-turn
;; sent by the player whose turn it isn't ends the OPPONENT's turn and is
;; unrecoverable.
;; ---------------------------------------------------------------------------

(def ^:private orphaned-turn-game-state
  "Corp's turn, 0 clicks, :end-turn NOT set, no run, no prompt on either seat."
  {:active-player "corp" :turn 10 :end-turn false
   :corp {:click 0} :runner {:click 0}})

(deftest test-wait-orphaned-turn-wakes-the-active-seat
  (testing "#120: the seat that OWES the end-turn wakes with :my-turn-end
            instead of sleeping the full timeout on its own move"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp" orphaned-turn-game-state)
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :relevant-change (:status result))
              (str "an orphaned turn must wake its owner, not time out, got: " result))
          (is (= :my-turn-end (:reason result))
              (str "expected :my-turn-end, got: " result)))))))

(deftest test-wait-orphaned-turn-does-not-wake-the-opponent
  (testing "#120 safety: the seat whose turn it ISN'T must stay asleep — an
            end-turn from the Runner here ends the CORP's turn, unrecoverably"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner" orphaned-turn-game-state)
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :timeout (:status result))
              (str "the non-active seat owes nothing here, got: " result))
          (is (not= :my-turn-end (:reason result))
              (str "never hand the opponent an end-turn hint, got: " result)))))))

(deftest test-wait-orphaned-turn-deadlock-actually-breaks
  (testing "#120 end-to-end: once the owner ends the turn, :end-turn latches and
            the OPPONENT wakes — the two halves compose into a live boundary"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner" (assoc orphaned-turn-game-state :end-turn true))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :my-turn-start (:reason result))
              (str "after the end-turn the Runner is owed a start-turn, got: " result)))))
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp" (assoc orphaned-turn-game-state :end-turn true))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (not= :my-turn-end (:reason result))
              (str "a turn that HAS ended is not orphaned — no second end-turn, got: " result)))))))

(deftest test-wait-last-click-run-does-not-report-my-turn-end
  (testing "#120 regression, the one my-turn-to-act?'s docstring guards: a run
            started with the last click is ALSO both-sides-at-0-clicks with
            :end-turn false. `continue` is the move there, never `end-turn`"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "runner"
                          (-> approach-server-game-state
                              (assoc :end-turn false)
                              (assoc-in [:run :no-action] "runner")))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :timeout (:status result))
              (str "a passed Runner mid-run must still sleep, got: " result))
          (is (not= :my-turn-end (:reason result))
              (str "ending a turn with a run live is not the move, got: " result)))))))

(deftest test-wait-zero-click-phase-windows-do-not-report-my-turn-end
  (testing "#120: the engine's OTHER zero-click pauses (phase 1.2 and the
            post-discard priority window) share this exact shape, and in both the
            resolving action is a PHASE command, not end-turn"
    (doseq [k [:corp-phase-12 :runner-phase-12 :corp-post-discard :runner-post-discard]]
      (with-redefs [state/get-cursor (fn [] 10)]
        (with-mock-state (mock-game "corp" (assoc orphaned-turn-game-state k true))
          (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
            (is (not= :my-turn-end (:reason result))
                (str "steering a seat to end-turn at " k
                     " skips a window the opponent is entitled to, got: " result))))))))

(deftest test-my-turn-end-wake-names-the-command-that-clears-it
  (testing "#120: `wait` returning a bare :my-turn-end token is the #115 trap —
            two Luna seats read an undecoded reason as nothing-happened and
            re-blocked. The wake must name end-turn"
    (let [lines (core/wake-reason-guidance-lines :my-turn-end {})]
      (is (seq lines) "an orphaned turn is exactly the reason that needs decoding")
      (is (re-find #"end-turn" (str/join " " lines))
          (str "the guidance must name the command that clears it, got: " lines)))))

;; --- #120 review panel (off-vendor guest, GPT-5.6): three confirmed findings ---
;;
;; The wake itself was right; what the guest killed was my claim that
;; my-turn-orphaned? is mutually exclusive with every earlier branch, and my
;; assumption that "0 clicks, turn not ended" always means "just end it".

(deftest test-my-turn-end-does-not-fire-under-our-own-blocking-prompt
  (testing "#120, the issue's own exclusion list: '...must exclude at minimum: an
            active run, and a genuinely blocking prompt of our own.' With a prompt
            up the honest answer is 'resolve your prompt', and it wakes as
            :has-prompt — never as an end-turn obligation"
    (with-redefs [state/get-cursor (fn [] 10)]
      (with-mock-state (mock-game "corp"
                          (assoc-in orphaned-turn-game-state [:corp :prompt-state]
                                    {:prompt-type "select" :msg "Choose a card to trash"
                                     :choices [{:value "a"}]}))
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :has-prompt (:reason result))
              (str "an open prompt outranks the end-turn obligation, got: " result))
          (is (not= :my-turn-end (:reason result))
              (str "never steer past our own live prompt, got: " result)))))))

(deftest test-my-turn-end-guidance-does-not-slam-the-eot-paid-window
  (testing "#120 vs #103: the end-of-turn paid window has the SAME signature —
            0 clicks, no prompt, :end-turn false, no run, no phase flag. Telling
            the Corp to `end-turn` there throws away the rez that check-auto-end-turn!
            deliberately holds the turn open for (marquee ac71ce63 lost a Nico
            Campaign to exactly this). Two seat-facing surfaces must not contradict"
    (with-mock-state (mock-game "corp"
                        (-> orphaned-turn-game-state
                            (assoc-in [:corp :credit] 5)
                            (assoc-in [:corp :servers :remote1 :content]
                                      [{:title "Nico Campaign" :type "Asset"
                                        :rezzed false :cost 2}])))
      (let [lines (core/wake-reason-guidance-lines :my-turn-end @state/client-state)
            text (str/join " " lines)]
        (is (str/includes? text "Nico Campaign")
            (str "the open paid window must be named before we say 'end-turn', got: " lines))
        (is (re-find #"(?i)rez" text)
            (str "and the seat must be told it can still rez, got: " lines))))))

(deftest test-my-turn-end-guidance-stays-terse-with-no-paid-window
  (testing "#120: the paid-window line is CONDITIONAL — a Corp with nothing
            rezzable gets the plain end-turn steer, not a phantom window"
    (with-mock-state (mock-game "corp" orphaned-turn-game-state)
      (let [text (str/join " " (core/wake-reason-guidance-lines :my-turn-end @state/client-state))]
        (is (re-find #"end-turn" text) "the steer itself is unconditional")
        (is (not (re-find #"(?i)rez" text))
            (str "no rezzables => no rez line, got: " text))))))

(deftest test-run-ended-into-an-orphaned-turn-says-so
  (testing "#120: :run-ended OUTRANKS :my-turn-end and is not mutually exclusive
            with it — a run that ends on the last click leaves us owing the
            end-turn, but the seat woke on the transition and :run-ended carried
            no guidance at all. That is the #115 undecoded-token trap: the seat
            reads 'run ended' as the opponent's cue and re-blocks"
    (with-mock-state (mock-game "runner"
                        (-> orphaned-turn-game-state
                            (assoc :active-player "runner")))
      (let [text (str/join " " (core/wake-reason-guidance-lines :run-ended @state/client-state))]
        (is (re-find #"end-turn" text)
            (str "a run ending into our own orphaned turn must name end-turn, got: " text)))))
  (testing "#120: and stays silent when the run ended with clicks still in hand"
    (with-mock-state (mock-game "runner"
                        (-> orphaned-turn-game-state
                            (assoc :active-player "runner")
                            (assoc-in [:runner :click] 2)))
      (is (empty? (core/wake-reason-guidance-lines :run-ended @state/client-state))
          "a normal run end is self-describing — no end-turn steer"))))

;; --- #120 review panel, SECOND pass on the fixes themselves ---
;;
;; The guest found the paid-window fix was half a fix: check-auto-end-turn! holds
;; the turn open for TWO reasons at 0 clicks, and I had only mirrored one.

(deftest test-my-turn-end-guidance-does-not-slam-a-live-score
  (testing "#120 vs the scorable-agenda hold: scoring costs no click, so a fully
            advanced agenda is still live at 0 clicks — check-auto-end-turn!
            refuses to auto-end for exactly this. Telling the seat `end-turn` here
            throws away the score, which is how you lose a game in one line"
    (with-mock-state (mock-game "corp"
                        (assoc-in orphaned-turn-game-state [:corp :servers :remote1 :content]
                                  [{:title "Offworld Office" :type "Agenda"
                                    :advance-counter 4 :advancementcost 4}]))
      (let [text (str/join " " (core/wake-reason-guidance-lines :my-turn-end @state/client-state))]
        (is (str/includes? text "Offworld Office")
            (str "the scorable agenda must be named before the end-turn steer, got: " text))
        (is (re-find #"(?i)score" text)
            (str "and the seat must be told it can still score, got: " text))
        ;; ...but HEDGED, not commanded. find-scorable-agendas is a counter check
        ;; that ignores :cannot-score (Clot et al.) and its own docstring says to
        ;; treat a hit as MIGHT-be-scorable. The printed line used to be flatly
        ;; categorical ("can still be SCORED") while the hedge lived only in a
        ;; code comment the seat never sees. Asserting the framing, not the token:
        ;; a categorical claim from an admittedly-approximate detector is the
        ;; guidance-text failure this repo keeps paying for.
        (is (re-find #"(?i)\bmay\b|might" text)
            (str "an approximate detector must not speak categorically, got: " text))
        (is (not (str/includes? text "can still be SCORED"))
            (str "the old categorical phrasing, got: " text))))))

(deftest test-end-turn-guidance-reads-the-snapshot-it-was-handed
  (testing "#120: the wait loop classifies a SNAPSHOT and formats guidance from it
            later. Reading the live atom instead means a diff landing in that gap
            can staple a card list from a different turn onto the wake reason —
            so the detectors take the state they are given"
    ;; The atom holds a board with nothing pending; the snapshot passed in has the
    ;; rezzable. The lines must describe the ARGUMENT, not the atom.
    (with-mock-state (mock-game "corp" orphaned-turn-game-state)
      (let [snapshot (mock-game "corp"
                       (-> orphaned-turn-game-state
                           (assoc-in [:corp :credit] 5)
                           (assoc-in [:corp :servers :remote1 :content]
                                     [{:title "Nico Campaign" :type "Asset"
                                       :rezzed false :cost 2}])))
            text (str/join " " (core/wake-reason-guidance-lines :my-turn-end snapshot))]
        (is (str/includes? text "Nico Campaign")
            (str "guidance must describe the state it was handed, got: " text))))))

;; ---------------------------------------------------------------------------
;; ...and the FAST PATH must do the same (review panel, MAJOR).
;;
;; The test above proves the detectors honour a snapshot they are handed. It
;; cannot see the bug below, because it calls wake-reason-guidance-lines
;; directly and never goes through `wait --since`. The fast path deref'd the
;; atom twice — once to classify the reason, once to render it — which is the
;; very race the snapshot arities exist to prevent. The polling loop was
;; converted; this path was missed.
;;
;; Catching it needs the atom to CHANGE between the two reads, so the state
;; here is not a map but a deref that answers differently once classification
;; has happened. With one snapshot the guidance names the agenda that was on
;; the board when the reason was decided; with two derefs it describes a board
;; the reason was never computed from.
;; ---------------------------------------------------------------------------

(def ^:private scorable-agenda-turn-state
  "The orphaned turn, plus an agenda that is already fully advanced."
  (-> orphaned-turn-game-state
      (assoc-in [:corp :credit] 5)
      (assoc-in [:corp :servers :remote1 :content]
                [{:title "Offworld Office" :type "Agenda"
                  :advance-counter 4 :advancementcost 4}])))

(deftest test-since-fast-path-describes-the-state-it-classified
  (testing "#120/review: one snapshot — the fast path must not classify off one
            board and describe another"
    (let [classified? (atom false)
          before (mock-game "corp" scorable-agenda-turn-state)
          ;; What a diff landing mid-decision would leave: same orphaned turn,
          ;; but the agenda is gone (scored, or trashed by the opponent).
          after (mock-game "corp" orphaned-turn-game-state)
          flipping (reify clojure.lang.IDeref
                     (deref [_] (if @classified? after before)))
          real-relevance @#'core/relevance-reason]
      (with-redefs-fn {#'core/relevance-reason
                       (fn [& args]
                         (let [r (apply real-relevance args)]
                           (reset! classified? true)
                           r))
                       #'state/get-cursor (constantly 10)
                       #'state/client-state flipping}
        (fn []
          (let [result (atom nil)
                out (with-out-str
                      (reset! result
                              (core/wait-for-relevant-diff {:since 5 :timeout 0 :verbose true})))]
            (is (= :already-advanced (:status @result))
                (str "fixture must actually exercise the fast path, got: " @result))
            (is (= :my-turn-end (:reason @result))
                (str "and classify off the pre-flip board, got: " @result))
            (is (str/includes? out "Offworld Office")
                (str "THE bug: the reason was decided on a board holding a scorable "
                     "agenda, and the guidance then described a board without it. "
                     "Got:\n" out))))))))

;; ============================================================================
;; #142: `wait` must not wake a seat for a diff vector
;; ============================================================================
;; The externally visible half of the turn-predicate fix, and the reason it is
;; part of #142 rather than a follow-up. `my-turn-to-act?` falls through to a
;; turn-0/0-clicks/Corp clause, and a non-map answers every lookup with that
;; default, so `relevance-reason` classified a cleared cache as :my-turn-start.
;; With can-start-turn? now refusing a non-board, waking here would be the
;; #87/#131 spin exactly: wake "your move", get refused, wait, repeat.
;;
;; Asserted through relevance-reason rather than the state predicate alone —
;; the helper-level test in ai-state-test cannot see a rewiring regression here
;; (guest review of the #142 fix).

(deftest test-relevance-reason-does-not-wake-on-a-diff-vector
  (testing "#142: a raw diff in :game-state is not 'your move'"
    (let [relevance @#'core/relevance-reason]
      (is (not= :my-turn-start
                (relevance (mock-game "corp" [{:corp {:credit 6}} {}]) "corp" false))
          "THE spin: the vector's default answers ARE the Corp opening-turn shape")))

  (testing "the real post-mulligan board still wakes the Corp"
    (let [relevance @#'core/relevance-reason]
      (is (= :my-turn-start
             (relevance (mock-game "corp" {:turn 0 :corp {:click 0} :runner {:click 0}})
                        "corp" false))
          "a false refusal here parks a seat that genuinely owes a start-turn"))))
