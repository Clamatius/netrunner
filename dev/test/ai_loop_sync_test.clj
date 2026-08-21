(ns ai-loop-sync-test
  "Issue #144: the autonomous loops had no resync path.

   `sync-verdict!` is both the authority on \"is there a game to act in?\" and
   the thing that REPAIRS a boardless seat, and its only caller was the CLI
   gate. A loop that landed boardless refused to act — correct — and then had no
   way to fix itself: it refused, waited, refused again, until an external
   `send_command` happened to resync it or `ai-stall` stopped it.

   Two properties are load-bearing and pinned here:

   1. The repair is BOUNDED. An unbounded rejoin loop is exactly the silent-hang
      failure `ai-stall` exists to stop.
   2. The repair is CHEAP when healthy. `sync-verdict!` costs a lobby round trip
      plus a hard 500ms sleep whenever a :gameid is present, and loop ticks are
      ~500-1000ms — so a per-tick call (the issue's first-sketch fix) would
      double every tick and lobby-spam the server for a whole marquee game. The
      fast path must make NO round trip, and that is asserted by counting calls,
      not by inspecting the source."
  (:require [clojure.test :refer :all]
            [test-helpers :refer [mock-client-state with-mock-state]]
            [ai-loop-sync :as sync]
            [ai-connection :as conn]
            [ai-state :as state]
            [ai-basic-actions :as actions]
            [ai-goldfish-runner]
            [ai-goldfish-corp]
            [ai-heuristic-runner]
            [ai-heuristic-corp]))

;; ============================================================================
;; Pure core: classify — the verdict outranks the board
;; ============================================================================

(deftest test-classify-verdict-outranks-a-retained-board
  (testing "#144: a FAILED repair stays failed even though the old board is still there"
    ;; Guest panel, CRITICAL, and it was: the first cut let the board outrank the
    ;; verdict, on the theory that a board in hand answers the loop's question
    ;; whatever route it came by. It does not. `do-rejoin-resync!` returns
    ;; :resync-failed from an arm that never reaches `resync-game!`, and
    ;; `teardown-verdict` returns :game-gone while deliberately READING the
    ;; cached snapshot. In both, the stale board is still sitting there.
    (is (= :resync-failed (sync/classify :resync-failed true true))
        "THE inverted-precedence bug: a retained board must not launder a failure")
    (is (= :game-gone (sync/classify :game-gone true true))
        "worse still — this one kept a seat playing into a destroyed game")
    (is (= :game-over (sync/classify :game-over true true)))))

(deftest test-classify-synced-must-also-be-actable
  (testing "#144: :synced while a local signal is STILL set has not repaired anything"
    ;; Guest panel, MAJOR: a delayed lobby-list reply can make `verify-in-game!`
    ;; answer from a stale list, so sync-verdict! says :synced over a seat that is
    ;; still disconnected / still flagged lobby-gone. Counting that as a failed
    ;; attempt keeps it bounded instead of looping.
    (is (= :resync-failed (sync/classify :synced false true))
        "a board, but a signal the repair did not clear"))
  (testing "#144: a clean :synced with a board is the only thing that is actable"
    (is (= :have-board (sync/classify :synced true true))))
  (testing "#144: :synced with no board at all means nothing to act in YET"
    ;; sync-verdict! answers :synced for a client with no :gameid, and for a
    ;; seated-but-unstarted lobby. Both healthy; nothing was attempted.
    (is (= :no-game (sync/classify :synced false false)))))

;; ============================================================================
;; Pure core: the bound
;; ============================================================================

(deftest test-next-attempts-counts-only-consecutive-failures
  (testing "#144: the bound is on a RUN of failures, not a lifetime total"
    (is (= 1 (sync/next-attempts :resync-failed 0)))
    (is (= 3 (sync/next-attempts :resync-failed 2)))
    (testing "any other outcome resets — a seat that recovered has spent nothing"
      (is (= 0 (sync/next-attempts :have-board 2)))
      (is (= 0 (sync/next-attempts :no-game 2)))
      (is (= 0 (sync/next-attempts :game-over 2))))))

(deftest test-recovery-action-bails-at-the-bound
  (testing "#144: a failing repair retries, then stops — it does not rejoin forever"
    (is (= :retry (sync/recovery-action :resync-failed 1 3)))
    (is (= :retry (sync/recovery-action :resync-failed 2 3)))
    (is (= :stop (sync/recovery-action :resync-failed 3 3))
        "THE bound: at max-attempts the loop stops with a diagnostic")
    (is (= :stop (sync/recovery-action :resync-failed 9 3))
        "and stays stopped past it")))

(deftest test-recovery-action-idles-rather-than-bailing-pre-game
  (testing "#144: waiting for a game to start must not consume the retry budget"
    (is (= :idle (sync/recovery-action :no-game 0 3)))
    (is (= :idle (sync/recovery-action :no-game 0 1)))))

(deftest test-recovery-action-terminal-states-stop-immediately
  (testing "#144: decided/gone are not retryable — no attempt budget applies"
    (is (= :stop (sync/recovery-action :game-over 0 3)))
    (is (= :stop (sync/recovery-action :game-gone 0 3))))
  (testing "#144: an actable seat means run the normal tick body"
    (is (= :act (sync/recovery-action :have-board 0 3)))))

;; ============================================================================
;; Pure core: what counts as a local invalidation
;; ============================================================================

(deftest test-local-invalidation-names-every-signal
  (testing "#144: board? + stale? was too weak a guard (guest panel, CRITICAL)"
    ;; `mark-lobby-gone!` records a server-closed lobby while deliberately
    ;; RETAINING the board, and `stale?` never looks at that flag — so the one
    ;; state most worth catching sailed straight through the fast path.
    (with-mock-state (mock-client-state)
      (is (nil? (sync/local-invalidation @state/client-state))
          "a healthy connected seat has nothing wrong with it"))
    (doseq [[expected st] {:no-board     {:game-state nil}
                           :diverged     {:diff-mismatch true}
                           :lobby-gone   {:lobby-gone? true}
                           :disconnected {:connected false}}]
      (with-mock-state (merge (mock-client-state) st)
        (is (= expected (sync/local-invalidation @state/client-state))
            (str "must be named: " expected))))))

(deftest test-next-step-never-samples-while-a-repair-is-in-progress
  (testing "#144: a repair in progress must not be laundered by a later tick"
    ;; Guest 2nd pass, CRITICAL — and it took three tries to close. A failing
    ;; check used to advance the TTL, so the next tick's fast path reset the
    ;; budget to zero. Routing positive attempts through the PROBE instead was
    ;; still wrong: the probe's one-absence-is-survivable branch answers :act, so
    ;; a seat whose membership check always failed still acted forever.
    (is (= :repair (sync/next-step nil {:attempts 1 :verified-at 1000} 1000 60000))
        "THE hole: positive attempts must go straight to the repair, not sample")
    (is (= :repair (sync/next-step :lobby-gone {:attempts 0 :verified-at 1000} 1000 60000))
        "a local signal is a fact, not a noisy reading")
    (is (= :probe (sync/next-step nil {:attempts 0 :verified-at 1000 :suspect? true} 1000 60000))
        "an armed suspicion re-checks next tick, not next minute")
    (is (= :probe (sync/next-step nil {:attempts 0 :verified-at 1000} 61001 60000))
        "and the TTL eventually samples a healthy-looking seat")
    (is (= :free (sync/next-step nil {:attempts 0 :verified-at 1000} 1000 60000))
        "a clean, freshly-verified seat costs nothing")))

;; ============================================================================
;; Diagnostics — a bail must leave an attributable artifact, like ai-stall's
;; ============================================================================

(deftest test-diagnostic-says-what-to-do-about-each-ending
  (testing "#144: game-over points at the RESULT, not at a reconnect"
    (let [d (sync/diagnostic "corp" :game-over 0)]
      (is (.contains d "game-over-status"))
      (is (not (.contains d "reset.sh")))))
  (testing "#144: game-gone is not recoverable from inside the loop"
    (is (.contains (sync/diagnostic "corp" :game-gone 0) "reset.sh")))
  (testing "#144: a resync bail reports how many attempts were spent"
    (let [d (sync/diagnostic "runner" :resync-failed 3)]
      (is (.contains d "3 rejoin attempts"))
      (is (.contains d "runner")))))

;; ============================================================================
;; ensure-board! — the behaviour the loops depend on
;; ============================================================================
;;
;; These drive the 4-arity so the membership-TTL clock is explicit: `now` and
;; `verify-every-ms` are parameters precisely so a test never waits on, or
;; races, a wall clock.

(def ^:private fresh
  "A tracker with nothing outstanding, verified recently enough at now=1000."
  {:attempts 0 :verified-at 1000 :suspect? false})

(defn- counting
  [calls v] (fn [] (swap! calls inc) v))

(deftest test-ensure-board-fast-path-makes-no-round-trip
  (testing "#144: a healthy loop must never pay for the authority"
    ;; The property that makes the fix affordable. sync-verdict! costs a lobby
    ;; round trip plus a 500ms sleep with a :gameid present; at ~1 tick/s for a
    ;; whole game that is thousands of needless requests.
    (let [repairs (atom 0) probes (atom 0)]
      (with-mock-state (mock-client-state)
        (with-redefs [conn/sync-verdict!  (counting repairs :synced)
                      sync/membership      (counting probes :seated)]
          (let [r (sync/ensure-board! fresh 3 60000 1000)]
            (is (= :act (:action r)))
            (is (zero? @repairs) "THE cost property: no repair")
            (is (zero? @probes)  "and no probe either")
            (is (= fresh (:tracker r)) "a free tick changes nothing")))))))

(deftest test-ensure-board-repairs-a-boardless-seat
  (testing "#144: THE bug — a boardless loop now reaches the repair authority"
    (let [calls (atom 0)
          boardless (assoc (mock-client-state) :game-state nil :last-state nil)]
      (with-mock-state boardless
        (with-redefs [conn/sync-verdict!
                      (fn []
                        (swap! calls inc)
                        (swap! state/client-state assoc :game-state {:turn 3})
                        :synced)]
          (let [r (sync/ensure-board! fresh 3 60000 1000)]
            (is (= 1 @calls) "the boardless seat DID reach the authority")
            (is (= :act (:action r)) "and may act again on the recovered board")
            (is (true? (:repaired? r)))
            (is (zero? (:attempts (:tracker r))))))))))

(deftest test-ensure-board-local-signals-repair-immediately
  (testing "#144: a local signal is a FACT about this seat — repair now, don't sample it"
    (doseq [[what st] {"a server-closed lobby (mark-lobby-gone! RETAINS the board)"
                       {:lobby-gone? true}
                       "a recorded diff divergence"
                       {:diff-mismatch true}
                       "a dropped socket — a board cached across it is a snapshot"
                       {:connected false}}]
      (let [repairs (atom 0) probes (atom 0)]
        (with-mock-state (merge (mock-client-state) st)
          (with-redefs [conn/sync-verdict!   (counting repairs :synced)
                        sync/membership      (counting probes :seated)]
            (sync/ensure-board! fresh 3 60000 1000)
            (is (= 1 @repairs) (str what " → straight to the repair"))
            (is (zero? @probes) (str what " → not merely probed"))))))))

;; --- the membership TTL, and why one absence is not enough -------------------

(deftest test-ensure-board-reverifies-membership-on-a-throttle
  (testing "#144: a loop holding a board still re-asks 'am I still seated?' eventually"
    (let [probes (atom 0)]
      (with-mock-state (mock-client-state)
        (with-redefs [sync/membership      (counting probes :seated)]
          (testing "not yet due: free"
            (sync/ensure-board! fresh 3 60000 30000)
            (is (zero? @probes)))
          (testing "TTL expired: one probe, and the clock restarts"
            (let [r (sync/ensure-board! fresh 3 60000 61000)]
              (is (= 1 @probes))
              (is (= :act (:action r)) "a healthy verified seat carries on")
              (is (= 61000 (:verified-at (:tracker r)))))))))))

(deftest test-ensure-board-first-tick-verifies
  (testing "#144: a loop confirms its seat at startup rather than trusting the cache"
    (let [probes (atom 0)]
      (with-mock-state (mock-client-state)
        (with-redefs [sync/membership      (counting probes :seated)]
          (sync/ensure-board! sync/initial-tracker 3 60000 500000)
          (is (= 1 @probes) "initial-tracker's :verified-at 0 is always due"))))))

(deftest test-ensure-board-one-absence-does-not-destroy-a-live-board
  (testing "#144: a single uncorrelated 'not seated' must NOT trigger a destructive resync"
    ;; Guest 2nd pass, CRITICAL. `verify-in-game!` requests a lobby list, sleeps a
    ;; fixed 500ms, then reads whichever cached list is there — it never proves the
    ;; read belongs to the request. A slow reply reads as "not seated", and the
    ;; repair path CLEARS the board before asking for a replacement. On a schedule,
    ;; that would wreck healthy in-flight encounters about once a minute.
    (let [repairs (atom 0)]
      (with-mock-state (mock-client-state)
        (with-redefs [sync/membership      (constantly :absent)
                      conn/sync-verdict!   (counting repairs :synced)]
          (let [r (sync/ensure-board! fresh 3 60000 61000)]
            (is (zero? @repairs) "THE hazard: one sample must not clear a live board")
            (is (= :act (:action r)) "the seat keeps playing")
            (is (true? (:suspect? (:tracker r))) "but suspicion is armed")))))))

(deftest test-ensure-board-a-second-absence-does-repair
  (testing "#144: two independent absences a tick apart ARE evidence — then repair"
    (let [repairs (atom 0)]
      (with-mock-state (mock-client-state)
        (with-redefs [sync/membership      (constantly :absent)
                      conn/sync-verdict!   (counting repairs :game-gone)]
          (let [r1 (sync/ensure-board! fresh 3 60000 61000)
                r2 (sync/ensure-board! (:tracker r1) 3 60000 62000)]
            (is (= :act (:action r1)))
            (is (= 1 @repairs) "the second consecutive absence escalates")
            (is (= :stop (:action r2)) "and the verdict is honoured")))))))

(deftest test-ensure-board-suspicion-clears-on-a-good-sample
  (testing "#144: a transient empty read must not leave the seat armed forever"
    (with-mock-state (mock-client-state)
      (with-redefs [sync/membership      (constantly :seated)]
        (let [r (sync/ensure-board! (assoc fresh :suspect? true) 3 60000 61000)]
          (is (= :act (:action r)))
          (is (false? (:suspect? (:tracker r)))))))))

;; --- the bound, including the cross-tick hole -------------------------------

(deftest test-ensure-board-counts-consecutive-failures-to-a-stop
  (testing "#144: the retry is bounded — three failures stop the loop"
    (let [calls (atom 0)
          boardless (assoc (mock-client-state) :game-state nil :last-state nil)]
      (with-mock-state boardless
        (with-redefs [conn/sync-verdict! (counting calls :resync-failed)]
          (let [r1 (sync/ensure-board! fresh 3 60000 1000)
                r2 (sync/ensure-board! (:tracker r1) 3 60000 2000)
                r3 (sync/ensure-board! (:tracker r2) 3 60000 3000)]
            (is (= [:retry :retry :stop] (map :action [r1 r2 r3]))
                "THE bound: the loop stops rather than rejoining forever")
            (is (= [1 2 3] (map #(:attempts (:tracker %)) [r1 r2 r3])))
            (is (= 3 @calls))))))))

(deftest test-ensure-board-a-failure-cannot-be-laundered-by-the-next-tick
  (testing "#144: a repair in progress survives into the next tick and still bails"
    ;; Guest 2nd pass, CRITICAL. The failing tick used to advance :verified-at, so
    ;; the NEXT tick — healthy board, no local flag, TTL unexpired — took the fast
    ;; path and reset attempts to zero. A seat whose membership check always failed
    ;; therefore acted on its cache forever and never reached the three-attempt stop.
    ;; Note the fixture RETAINS a board throughout: that is the state that hid it.
    (let [repairs (atom 0)]
      (with-mock-state (mock-client-state)
        (with-redefs [sync/membership      (constantly :absent)
                      conn/sync-verdict!   (counting repairs :resync-failed)]
          (let [;; two absences to escalate, then the failure must stay sticky
                rs (reductions (fn [prev i]
                                 (sync/ensure-board! (:tracker prev) 3 60000 (+ 61000 (* 1000 i))))
                               {:tracker fresh}
                               (range 1 6))
                actions (map :action (rest rs))]
            (is (some #{:stop} actions)
                "THE laundering bug: this used to :act forever, budget untouched")
            (is (= [:act :retry :retry :stop] (take 4 actions))
                "one absence survivable, the second escalates, then the budget runs out")
            ;; 62000 is the FIRST probe's timestamp. Ticks run to 66000, so the
            ;; clock is pinned at the last check that actually happened — the
            ;; failures after it advance nothing.
            (is (= 62000 (:verified-at (:tracker (last rs))))
                "a FAILED repair must not restart the TTL clock")))))))

(deftest test-ensure-board-a-throwing-repair-is-a-failed-repair
  (testing "#144: an exception must SPEND an attempt, not slip past the bound"
    ;; The loops' own catch blocks carry the attempt count without incrementing —
    ;; right for a tick-body exception, fatal for a recovery one, and neither stall
    ;; backstop accumulates in that state (own-turn-key needs an :active-player,
    ;; and a boardless seat has none). So it is caught here.
    (let [calls (atom 0)
          boardless (assoc (mock-client-state) :game-state nil :last-state nil)]
      (with-mock-state boardless
        (with-redefs [conn/sync-verdict!
                      (fn [] (swap! calls inc) (throw (RuntimeException. "socket died")))]
          (let [r1 (sync/ensure-board! fresh 3 60000 1000)
                r2 (sync/ensure-board! (:tracker r1) 3 60000 2000)
                r3 (sync/ensure-board! (:tracker r2) 3 60000 3000)]
            (is (= [:retry :retry :stop] (map :action [r1 r2 r3]))
                "THE bug: a throwing recovery used to retry forever, budget untouched")
            (is (= :resync-failed (:outcome r3)) "a throw IS a failed repair")
            (is (= 3 @calls))))))))

(deftest test-ensure-board-does-not-swallow-an-interrupt
  (testing "#144: bot-loop-stop cancels with an interrupt — it must not read as a failed repair"
    ;; Guest 2nd pass, CRITICAL. `future-cancel` interrupts the loop, most likely
    ;; mid-Thread/sleep inside verify-in-game!. Classifying that as :resync-failed
    ;; would let a cancelled loop keep running, and a later bot-loop would then put
    ;; TWO loops on one seat.
    (let [boardless (assoc (mock-client-state) :game-state nil :last-state nil)]
      (with-mock-state boardless
        (with-redefs [conn/sync-verdict! (fn [] (throw (InterruptedException. "cancelled")))]
          (is (thrown? InterruptedException
                       (sync/ensure-board! fresh 3 60000 1000))
              "the interrupt must propagate, not be counted"))))
    (testing "and the probe path too"
      (with-mock-state (mock-client-state)
        (with-redefs [sync/membership      (fn [] (throw (InterruptedException. "cancelled")))]
          (is (thrown? InterruptedException
                       (sync/ensure-board! fresh 3 60000 61000))))))))

(deftest test-ensure-board-a-synced-that-did-not-clear-the-signal-is-a-failure
  (testing "#144: :synced over a seat that is STILL disconnected has repaired nothing"
    ;; Guest 2nd pass, MAJOR: verify-in-game! reading a stale lobby list can answer
    ;; true while the socket is down, so sync-verdict! says :synced over a seat that
    ;; is not actable. Counting it as a failed attempt keeps it bounded.
    (let [st (assoc (mock-client-state) :connected false)]
      (with-mock-state st
        (with-redefs [conn/sync-verdict! (constantly :synced)]
          (let [r (sync/ensure-board! fresh 3 60000 1000)]
            (is (= :resync-failed (:outcome r))
                "a board plus an uncleared signal is not a repaired seat")
            (is (= :retry (:action r)))
            (is (false? (:repaired? r))
                "and it must not announce a recovery that did not happen")))))))

(deftest test-ensure-board-terminal-verdicts-stop-without-spending-attempts
  (testing "#144: a decided or torn-down game stops the loop on the first tick"
    (let [boardless (assoc (mock-client-state) :game-state nil :last-state nil)]
      (doseq [v [:game-over :game-gone]]
        (with-mock-state boardless
          (with-redefs [conn/sync-verdict! (constantly v)]
            (let [r (sync/ensure-board! fresh 3 60000 1000)]
              (is (= :stop (:action r)) (str v " must not be retried"))
              (is (= v (:outcome r)))
              (is (zero? (:attempts (:tracker r)))))))))))

(deftest test-ensure-board-pre-game-idles-indefinitely
  (testing "#144: a loop waiting for its game to start must not bail"
    (let [boardless (assoc (mock-client-state) :game-state nil :last-state nil)]
      (with-mock-state boardless
        (with-redefs [conn/sync-verdict! (constantly :synced)]
          (let [results (reduce (fn [acc i]
                                  (conj acc (sync/ensure-board!
                                              (:tracker (last acc)) 3 60000 (* 1000 (inc i)))))
                                [{:tracker fresh}]
                                (range 10))]
            (is (every? #(= :idle (:action %)) (rest results))
                "pre-game idling never becomes a bail")
            (is (every? #(zero? (:attempts (:tracker %))) (rest results))
                "and never consumes the retry budget")))))))

;; ============================================================================
;; The wiring — which loop actually reaches the authority
;; ============================================================================
;;
;; The module tests above would all pass with the four loops left untouched,
;; which is the exact trap #144 is: `sync-verdict!` was correct and complete the
;; whole time, and nothing called it. A green test for a shared fix proves
;; nothing until you check that the caller you care about reaches it.
;;
;; These drive the real loop functions. Each asserts the property that was
;; missing: a loop holding no board TERMINATES on an unrecoverable verdict
;; instead of refusing forever. Before the wiring, every one of these spins
;; until the deref timeout — that is what the bug looked like from outside.

(defn- run-with-timeout
  "Run a loop fn, giving it a bounded window to return. `:spun` means it never
   did — the #144 behaviour."
  [f ms]
  (let [fut (future (with-out-str (f)) :returned)]
    (try
      (deref fut ms :spun)
      (finally (future-cancel fut)))))

(def ^:private loops
  [["goldfish-runner" #(ai-goldfish-runner/loop!)]
   ["goldfish-corp"   #(ai-goldfish-corp/start-autonomous!)]
   ["heuristic-runner" #(ai-heuristic-runner/loop!)]
   ["heuristic-corp"  #(ai-heuristic-corp/start-autonomous!)]])

(deftest test-every-loop-stops-on-an-unrecoverable-verdict
  (testing "#144: a boardless loop must reach the authority and honour :game-gone"
    (doseq [[nm f] loops]
      (with-mock-state (assoc (mock-client-state) :game-state nil :last-state nil)
        (with-redefs [conn/sync-verdict! (constantly :game-gone)]
          (is (= :returned (run-with-timeout f 15000))
              (str nm ": THE bug — before the wiring this loop refused, waited, "
                   "and refused again forever")))))))

(deftest test-every-loop-stops-when-the-repair-runs-out-of-attempts
  (testing "#144: a repair that never lands stops the loop rather than rejoining forever"
    (doseq [[nm f] loops]
      (let [calls (atom 0)]
        (with-mock-state (assoc (mock-client-state) :game-state nil :last-state nil)
          (with-redefs [conn/sync-verdict! (fn [] (swap! calls inc) :resync-failed)]
            (is (= :returned (run-with-timeout f 20000))
                (str nm ": must bail, not rejoin forever"))
            (is (= sync/default-max-attempts @calls)
                (str nm ": spent exactly the attempt budget — no more, no fewer"))))))))

(deftest test-every-loop-resumes-after-a-successful-repair
  (testing "#144: the point of the repair is to keep PLAYING, not just to stop cleanly"
    ;; A loop that only ever learned to give up would be a worse marquee seat, not
    ;; a better one. Here the first tick is boardless, the resync lands a board,
    ;; and the loop must then run its normal body — which sees a winner and ends.
    (doseq [[nm f] loops]
      (let [repaired (atom false)]
        (with-mock-state (assoc (mock-client-state) :game-state nil :last-state nil)
          (with-redefs [conn/sync-verdict!
                        (fn []
                          (reset! repaired true)
                          ;; a decided board: proves the loop ran its real body
                          (swap! state/client-state assoc
                                 :game-state {:winner "Corp" :active-player "corp"})
                          :synced)]
            (is (= :returned (run-with-timeout f 15000))
                (str nm ": reached its normal body after the repair"))
            (is (true? @repaired) (str nm ": did attempt the repair"))))))))


(def ^:private loops-with-opponent
  [["goldfish-runner"  #(ai-goldfish-runner/loop!)          "corp"]
   ["goldfish-corp"    #(ai-goldfish-corp/start-autonomous!) "runner"]
   ["heuristic-runner" #(ai-heuristic-runner/loop!)          "corp"]
   ["heuristic-corp"   #(ai-heuristic-corp/start-autonomous!) "runner"]])

(deftest test-a-healthy-loop-consults-the-authority-once-not-every-tick
  (testing "#144: the tracker must SURVIVE an :act tick, or the TTL is no throttle at all"
    ;; Guest 2nd pass, CRITICAL. Every continuing normal-body result omitted
    ;; :resync-next, so the recur substituted `initial-tracker`, whose
    ;; :verified-at 0 is instantly due. All four loops therefore ran a membership
    ;; check EVERY tick — the exact per-tick cost the design exists to avoid,
    ;; reintroduced by a dropped key. Counting is the only honest way to see it:
    ;; the module tests pass either way, because the defect lives in what the
    ;; LOOP carries between ticks.
    ;;
    ;; `can-start-turn?` is the body hook: it counts real iterations and then
    ;; ends the game, so the loop RETURNS. A test that only proved "did not
    ;; return" would also pass if the loop blocked forever on its first tick
    ;; (guest 3rd pass, MINOR).
    (doseq [[nm f opponent] loops-with-opponent]
      (let [probes (atom 0) repairs (atom 0) ticks (atom 0)]
        (with-mock-state (assoc-in (mock-client-state) [:game-state :active-player] opponent)
          (with-redefs [sync/membership    (fn [] (swap! probes inc) :seated)
                        conn/sync-verdict! (fn [] (swap! repairs inc) :synced)
                        actions/can-start-turn?
                        (fn []
                          (when (>= (swap! ticks inc) 4)
                            (swap! state/client-state assoc-in [:game-state :winner] "Corp"))
                          {:can-start false})]
            (is (= :returned (run-with-timeout f 25000))
                (str nm ": should end once the game is decided"))
            (is (>= @ticks 4)
                (str nm ": several real iterations must have elapsed"))
            (is (zero? @repairs)
                (str nm ": a healthy seat is never destructively repaired"))
            (is (= 1 @probes)
                (str nm ": THE bug — the startup check only, not one per tick"))))))))

(deftest test-the-tracker-survives-a-tick-body-exception
  (testing "#144: a throwing tick body must not discard what the sync check learned"
    ;; Guest 3rd pass, MAJOR. The sync call used to sit INSIDE the tick body's
    ;; try, and its result reached the recur through the body's return map — so
    ;; the broad catch, which returns its own map, silently reverted the tracker.
    ;; A repeatable body exception therefore re-armed the membership check every
    ;; tick, and worse, kept throwing away an armed `:suspect?` so a genuine
    ;; absence could never reach its second strike. `ensure-board!` now sits
    ;; OUTSIDE that try and the tracker is bound before it.
    (doseq [[nm f opponent] loops-with-opponent]
      (let [probes (atom 0) throws (atom 0)]
        (with-mock-state (assoc-in (mock-client-state) [:game-state :active-player] opponent)
          (with-redefs [sync/membership (fn [] (swap! probes inc) :seated)
                        actions/can-start-turn?
                        (fn [] (swap! throws inc) (throw (RuntimeException. "body blew up")))]
            (run-with-timeout f 9000)
            (is (>= @throws 2)
                (str nm ": the body really ran more than once"))
            (is (= 1 @probes)
                (str nm ": THE bug — a body exception used to revert the tracker"))))))))
