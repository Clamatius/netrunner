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
            [ai-goldfish-runner]
            [ai-goldfish-corp]
            [ai-heuristic-runner]
            [ai-heuristic-corp]))

;; ============================================================================
;; Pure core: classify
;; ============================================================================

(deftest test-classify-verdict-outranks-a-retained-board
  (testing "#144: a FAILED repair stays failed even though the old board is still there"
    ;; Caught by the guest panel as a CRITICAL, and it was: this file's first cut
    ;; let `board-after?` outrank the verdict, on the theory that a board in hand
    ;; answers the loop's question whatever route it came by. It does not.
    ;;
    ;; `do-rejoin-resync!` returns :resync-failed from a path that never reaches
    ;; `resync-game!` ("Rejoin not confirmed in lobby within 5s"), and
    ;; `teardown-verdict` returns :game-gone while deliberately READING the
    ;; cached snapshot. In both, the stale board is still sitting there. The old
    ;; precedence turned that into :act on a board known not to track the
    ;; server's, reset the budget to zero, and repeated every tick.
    (is (= :resync-failed (sync/classify :resync-failed true))
        "THE inverted-precedence bug: a retained board must not launder a failure")
    (is (= :game-gone (sync/classify :game-gone true))
        "worse still — this one kept a seat playing into a destroyed game")
    (is (= :game-over (sync/classify :game-over true))))
  (testing "#144: only :synced lets the board decide"
    (is (= :have-board (sync/classify :synced true)))
    (is (= :no-game (sync/classify :synced false)))))

(deftest test-classify-synced-without-a-board-is-not-a-failure
  (testing "#144: :synced with no board means there is nothing to act in YET"
    ;; sync-verdict! returns :synced for a client with no :gameid at all, and for
    ;; a seated-but-unstarted lobby. Both are healthy. Counting them as failures
    ;; would bail a loop that is merely waiting for its game to begin.
    (is (= :no-game (sync/classify :synced false)))))

(deftest test-classify-terminal-and-transient-verdicts
  (testing "#144: decided, gone, and transient are three different answers"
    (is (= :game-over (sync/classify :game-over false)))
    (is (= :game-gone (sync/classify :game-gone false)))
    (is (= :resync-failed (sync/classify :resync-failed false)))))

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
  (testing "#144: a failing resync retries, then stops — it does not rejoin forever"
    (is (= :retry (sync/recovery-action :resync-failed 1 3)))
    (is (= :retry (sync/recovery-action :resync-failed 2 3)))
    (is (= :stop (sync/recovery-action :resync-failed 3 3))
        "THE bound: at max-attempts the loop stops with a diagnostic")
    (is (= :stop (sync/recovery-action :resync-failed 9 3))
        "and stays stopped past it")))

(deftest test-recovery-action-idles-rather-than-bailing-pre-game
  (testing "#144: waiting for a game to start must not consume the retry budget"
    ;; :idle keeps the loop alive and takes no action. A loop started before its
    ;; game begins would otherwise burn its three attempts and stop.
    (is (= :idle (sync/recovery-action :no-game 0 3)))
    (is (= :idle (sync/recovery-action :no-game 0 1)))))

(deftest test-recovery-action-terminal-states-stop-immediately
  (testing "#144: decided/gone are not retryable — no attempt budget applies"
    (is (= :stop (sync/recovery-action :game-over 0 3)))
    (is (= :stop (sync/recovery-action :game-gone 0 3))))
  (testing "#144: a board means run the normal tick body"
    (is (= :act (sync/recovery-action :have-board 0 3)))))

;; ============================================================================
;; Diagnostics — a bail must leave an attributable artifact, like ai-stall's
;; ============================================================================

(deftest test-diagnostic-says-what-to-do-about-each-ending
  (testing "#144: game-over points at the RESULT, not at a reconnect"
    ;; Same precedence teardown-verdict uses: a seat that just won or lost wants
    ;; the result, not "run reset.sh".
    (let [d (sync/diagnostic "corp" :game-over 0)]
      (is (.contains d "game-over-status"))
      (is (not (.contains d "reset.sh")))))
  (testing "#144: game-gone is not recoverable from inside the loop"
    (let [d (sync/diagnostic "corp" :game-gone 0)]
      (is (.contains d "reset.sh"))))
  (testing "#144: a resync bail reports how many attempts were spent"
    (let [d (sync/diagnostic "runner" :resync-failed 3)]
      (is (.contains d "3 rejoin attempts"))
      (is (.contains d "runner")))))

;; ============================================================================
;; ensure-board! — the behaviour the loops depend on
;; ============================================================================
;;
;; These drive the 4-arity so the membership-TTL clock is explicit. `now` and
;; `verify-every-ms` are parameters precisely so a test never has to wait on, or
;; race, a wall clock.

(def ^:private fresh
  "A tracker whose membership check is NOT yet due at now=1000."
  {:attempts 0 :verified-at 1000})

(defn- counting-verdict
  "Stands in for sync-verdict!, recording each call so the fast path can be
   asserted by COST rather than by reading the source."
  [calls verdict]
  (fn [] (swap! calls inc) verdict))

(deftest test-ensure-board-fast-path-makes-no-round-trip
  (testing "#144: a healthy loop must never pay for sync-verdict!"
    ;; This is the property that makes the fix affordable. sync-verdict! costs a
    ;; lobby round trip plus a 500ms sleep with a :gameid present; at ~1 tick/s
    ;; for a whole game that is thousands of needless lobby requests.
    (let [calls (atom 0)]
      (with-mock-state (mock-client-state)
        (with-redefs [conn/sync-verdict! (counting-verdict calls :synced)]
          (let [r (sync/ensure-board! fresh 3 60000 1000)]
            (is (= :act (:action r)))
            (is (= :have-board (:outcome r)))
            (is (zero? @calls)
                "THE cost property: a cached board asks the network nothing")
            (is (nil? (:verdict r)) "no round trip ⇒ no verdict to report")))))))

(deftest test-ensure-board-repairs-a-boardless-seat
  (testing "#144: THE bug — a boardless loop now reaches the repair authority"
    ;; Before this, nothing in a loop called sync-verdict!, so this state
    ;; persisted until an external send_command happened to fix it.
    (let [calls (atom 0)
          boardless (assoc (mock-client-state) :game-state nil :last-state nil)]
      (with-mock-state boardless
        (with-redefs [conn/sync-verdict!
                      (fn []
                        (swap! calls inc)
                        ;; a successful repair installs a board, as do-rejoin-resync! does
                        (swap! state/client-state assoc :game-state {:turn 3})
                        :synced)]
          (let [r (sync/ensure-board! fresh 3 60000 1000)]
            (is (= 1 @calls) "the boardless seat DID reach the authority")
            (is (= :act (:action r)) "and may act again on the recovered board")
            (is (true? (:repaired? r)))
            (is (zero? (:attempts (:tracker r))) "a recovery clears the failure count")))))))

;; --- the local invalidation signals (guest panel, CRITICAL) -----------------
;;
;; `board?` + `stale?` alone were too weak: a board can be present and yet not
;; be a game to act in. Each of these is a purely local flag, free to check, and
;; each was a state the CLI gate caught and the loops did not.

(deftest test-ensure-board-honours-every-local-invalidation-signal
  (let [signals {"a server-closed lobby (mark-lobby-gone! RETAINS the board)"
                 {:lobby-gone? true}
                 "a recorded diff divergence"
                 {:diff-mismatch true}
                 "a dropped socket — a board cached across it is a snapshot, not a game"
                 {:connected false}}]
    (doseq [[what st] signals]
      (testing (str "#144: " what " must route through the repair, board or not")
        (let [calls (atom 0)]
          (with-mock-state (merge (mock-client-state) st)
            (with-redefs [conn/sync-verdict! (counting-verdict calls :synced)]
              (sync/ensure-board! fresh 3 60000 1000)
              (is (= 1 @calls) what))))))))

(deftest test-ensure-board-reverifies-membership-on-a-throttle
  (testing "#144: a loop holding a board still re-asks 'am I still seated?' eventually"
    ;; The CLI gate runs verify-in-game! on EVERY invocation; a loop with a board
    ;; would otherwise run it never. The local flags cannot close that gap alone —
    ;; a silent unseat announces nothing — so the TTL is the backstop.
    (let [calls (atom 0)]
      (with-mock-state (mock-client-state)
        (with-redefs [conn/sync-verdict! (counting-verdict calls :synced)]
          (testing "not yet due: still free"
            (sync/ensure-board! {:attempts 0 :verified-at 1000} 3 60000 30000)
            (is (zero? @calls)))
          (testing "TTL expired: one round trip, and the clock restarts"
            (let [r (sync/ensure-board! {:attempts 0 :verified-at 1000} 3 60000 61000)]
              (is (= 1 @calls))
              (is (= :act (:action r)) "a healthy verified seat carries on")
              (is (= 61000 (:verified-at (:tracker r)))))))))))

(deftest test-ensure-board-first-tick-verifies
  (testing "#144: a loop confirms its seat once at startup rather than trusting the cache"
    (let [calls (atom 0)]
      (with-mock-state (mock-client-state)
        (with-redefs [conn/sync-verdict! (counting-verdict calls :synced)]
          (sync/ensure-board! sync/initial-tracker 3 60000 500000)
          (is (= 1 @calls) "initial-tracker's :verified-at 0 is always due"))))))

;; --- the bound --------------------------------------------------------------

(deftest test-ensure-board-counts-consecutive-failures-to-a-stop
  (testing "#144: the retry is bounded — three failures stop the loop"
    (let [calls (atom 0)
          boardless (assoc (mock-client-state) :game-state nil :last-state nil)]
      (with-mock-state boardless
        (with-redefs [conn/sync-verdict! (counting-verdict calls :resync-failed)]
          (let [r1 (sync/ensure-board! fresh 3 60000 1000)
                r2 (sync/ensure-board! (:tracker r1) 3 60000 2000)
                r3 (sync/ensure-board! (:tracker r2) 3 60000 3000)]
            (is (= [:retry :retry :stop] (map :action [r1 r2 r3]))
                "THE bound: the loop stops rather than rejoining forever")
            (is (= [1 2 3] (map #(:attempts (:tracker %)) [r1 r2 r3])))
            (is (= 3 @calls))))))))

(deftest test-ensure-board-a-throwing-repair-is-a-failed-repair
  (testing "#144: an exception must SPEND an attempt, not slip past the bound"
    ;; Guest panel, CRITICAL. The loops' own catch blocks carry the attempt count
    ;; without incrementing it — right for a tick-body exception, fatal for a
    ;; recovery one: a sync-verdict! that throws every tick retried forever, and
    ;; neither stall backstop accumulates in that state (own-turn-key needs an
    ;; :active-player, and a boardless seat has none). So it is caught here.
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
    ;; sync-verdict! says :synced for an unstarted lobby / a client with no
    ;; gameid. Ten ticks of that must still leave the loop alive.
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
