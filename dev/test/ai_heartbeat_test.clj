(ns ai-heartbeat-test
  "#286 — the heuristic bot read as SILENT/disconnected to the opponent.

   The peer-liveness clock the other seat reads is a per-side file under
   logs/.heartbeats, owned by `dev/send_command`: every sided invocation touches
   it and forks a toucher bound to that invocation's lifetime. `bot-loop`
   launches a detached future INSIDE the REPL and returns, so the toucher died
   while the bot played on, and 300s later every waiting command told the
   opponent the bot had disconnected and to `conclude` — in the same output that
   showed the bot had just acted.

   Two separate things have to hold, and the design panel's MAJOR was that the
   first alone is worthless:
     1. ai-core/start-heartbeat-toucher! refreshes a file and respects the
        refresh-only rule.
     2. The bot loops actually CALL it. A helper that works plus a send_command
        that emits the path still leaves #286 live if the wiring is missing, so
        the loop tests below drive the REAL loop functions."
  (:require [clojure.test :refer :all]
            [test-helpers :refer [mock-client-state with-mock-state]]
            [ai-core :as core]
            [ai-heuristic-corp :as hcorp]
            [ai-heuristic-runner :as hrunner]))

;; ============================================================================
;; Helpers
;; ============================================================================

(def ^:private temp-dirs (atom []))

(defn- temp-dir! []
  (let [d (str (java.nio.file.Files/createTempDirectory
                "nr-heartbeat-test" (into-array java.nio.file.attribute.FileAttribute [])))]
    (swap! temp-dirs conj d)
    d))

(use-fixtures :once
  (fn [run]
    (run)
    ;; The shell test next door traps and rm -rf's; this suite was leaving a
    ;; directory per test under TMPDIR forever.
    (doseq [d @temp-dirs
            file (reverse (file-seq (java.io.File. d)))]
      (try (.delete file) (catch Throwable _ nil)))
    (reset! temp-dirs [])))

(defn- completes-without-throwing
  "Run thunk with its output swallowed; nil if it returned, the throwable if not.
   Exists so a test can say 'this does not blow up' and mean it."
  [thunk]
  (try (with-out-str (thunk)) nil (catch Throwable t t)))

(defn- backdate!
  "Set MTIME well into the past so 'was refreshed' is unambiguous. Returns the
   value set, so a test compares against what it actually wrote rather than
   against 'now' (which moves)."
  [f ms-ago]
  (let [t (- (System/currentTimeMillis) ms-ago)]
    (.setLastModified f t)
    t))

(defn- wait-until
  "Poll pred for up to timeout-ms. Returns truthy pred value or nil. Used instead
   of a flat sleep so a slow machine does not make these flaky."
  [timeout-ms pred]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 10)
            (recur))))))

;; ============================================================================
;; 1. The helper itself
;; ============================================================================

(deftest toucher-refreshes-an-existing-file
  (testing "an existing heartbeat is refreshed immediately, before any interval elapses"
    (let [dir (temp-dir!)
          f (java.io.File. dir "corp")]
      (.createNewFile f)
      (let [was (backdate! f 600000)
            stop! (core/start-heartbeat-toucher! (.getPath f) 50)]
        (try
          ;; SYNCHRONOUS first touch: a loop that exits on its first tick has
          ;; still reported itself alive, and this is what makes the loop tests
          ;; below observable without waiting out an interval.
          (is (> (.lastModified f) was)
              "the first touch must happen before start-heartbeat-toucher! returns")
          (let [after-first (.lastModified f)]
            (is (wait-until 2000 #(> (.lastModified f) after-first))
                "the thread must keep refreshing on its interval"))
          (finally (stop!)))))))

(deftest toucher-never-resurrects-an-absent-file
  (testing "an absent heartbeat is NOT created — clear-heartbeats must stay cleared"
    ;; This is the rule the bash toucher states and the reason it exists:
    ;; reset.sh deletes these files so a seat from the previous game cannot read
    ;; as live in a fresh one. A toucher that re-created its file would undo that
    ;; for as long as an old loop survived.
    (let [dir (temp-dir!)
          f (java.io.File. dir "corp")
          stop! (core/start-heartbeat-toucher! (.getPath f) 20)]
      (try
        (is (not (.exists f)) "nothing may be created by the synchronous first touch")
        (Thread/sleep 150)   ; several intervals
        (is (not (.exists f)) "nothing may be created by the thread either")
        (finally (stop!))))))

(deftest toucher-stops-when-the-file-is-deleted-mid-run
  (testing "a heartbeat deleted under a running loop (clear-heartbeats) ENDS the thread"
    ;; The first cut asserted only that the file was not re-created, and a seat
    ;; showed that passes with `(.exists f)` dropped from the loop condition —
    ;; touch! has its own exists guard, so nothing reappears and the thread just
    ;; keeps spinning. That leaves the rule unpinned, and the rule is
    ;; load-bearing: a surviving thread RESUMES touching the moment a fresh
    ;; game's keep-hand recreates the same path, which is exactly the
    ;; resurrection clear-heartbeats exists to prevent. So re-create the file
    ;; and prove nobody touches it.
    (let [dir (temp-dir!)
          f (java.io.File. dir "corp")]
      (.createNewFile f)
      (let [stop! (core/start-heartbeat-toucher! (.getPath f) 20)]
        (try
          (.delete f)
          (Thread/sleep 150)                     ; several intervals
          (is (not (.exists f)) "the file must stay deleted")
          ;; the fresh game's seat re-appears at the same path
          (.createNewFile f)
          (let [was (backdate! f 600000)]
            (Thread/sleep 200)                   ; several more intervals
            (is (= was (.lastModified f))
                "a toucher whose file was cleared must be GONE, not waiting to adopt the next one"))
          (finally (stop!)))))))

(deftest toucher-stop-fn-ends-the-refreshing
  (testing "after stop! no further touch lands"
    ;; Comparing mtime before/after stop! does NOT work, and the first cut of
    ;; this test was green against a mutant with BOTH stop guards removed:
    ;; File.lastModified has ~1s granularity on this filesystem, so a touch
    ;; landing 20ms after stop! is indistinguishable from no touch at all.
    ;;
    ;; Instead: a LONG interval so the thread is reliably mid-sleep at stop!,
    ;; then backdate the file by ten minutes and watch past when that sleep
    ;; would have ended. Any touch at all is then a ten-minute jump, far outside
    ;; any granularity.
    (let [dir (temp-dir!)
          f (java.io.File. dir "corp")]
      (.createNewFile f)
      (let [stop! (core/start-heartbeat-toucher! (.getPath f) 1500)]
        (Thread/sleep 100)        ; thread is now asleep for ~1.4s
        (stop!)
        (let [was (backdate! f 600000)]
          (Thread/sleep 2500)     ; past the end of that sleep
          (is (= was (.lastModified f))
              "a stopped loop must stop claiming to be alive"))))))

(deftest toucher-tolerates-no-path
  (testing "nil/blank path is a no-op that still returns a callable stop fn"
    ;; A loop driven straight from a REPL has no heartbeat and must still run,
    ;; so no call site may need to branch on this.
    (doseq [p [nil "" "   "]]
      (let [stop! (core/start-heartbeat-toucher! p 20)]
        (is (fn? stop!) (str "must return a fn for path " (pr-str p)))
        (is (nil? (stop!)) (str "stop fn must be callable for path " (pr-str p)))))))

(deftest toucher-tolerates-an-unusable-path
  (testing "an unwritable path throws nothing into the caller's loop"
    (let [stop! (core/start-heartbeat-toucher! "/dev/null/nope/corp" 20)]
      (try
        (Thread/sleep 60)
        (is true "no throw reached the caller")
        (finally (stop!))))))

;; ============================================================================
;; 2. The wiring — the loops must CALL it
;; ============================================================================
;; Design panel MAJOR (guest-unique): with only the tests above plus the
;; send_command emission test, a bot loop that never starts the toucher passes
;; everything and #286 is still live. These drive the real loop fns.
;;
;; Both loops exit on their first tick when the cached board already carries a
;; :winner (the game-over arm), and ai-loop-sync/ensure-board! makes no round
;; trip while the board is locally valid — so one tick is cheap and offline.

(defn- finished-game-state [side]
  (mock-client-state
   :side side
   :game-state {:active-player "corp"
                :turn 3
                :winner "corp"
                :corp {:click 0 :credit 5 :hand [] :servers {} :agenda-point 7}
                :runner {:click 0 :credit 5 :hand [] :rig {} :agenda-point 0}}))

(deftest corp-loop-wires-the-heartbeat
  (testing "start-autonomous! refreshes the heartbeat it was given, then stops"
    (let [dir (temp-dir!)
          f (java.io.File. dir "corp")]
      (.createNewFile f)
      (let [was (backdate! f 600000)]
        ;; The loops call the 1-arity, which reads this def at call time, so a
        ;; redef here reaches the real production call site without giving the
        ;; loops a test-only argument.
        (with-redefs [core/heartbeat-touch-interval-ms 50]
          (with-mock-state (finished-game-state "corp")
            (with-out-str (hcorp/start-autonomous! :heartbeat-file (.getPath f)))))
        (is (> (.lastModified f) was)
            "the Corp loop must refresh its own side's heartbeat — this is #286 itself")
        ;; ...and the `finally` must retire the toucher with the loop, or a dead
        ;; bot goes on looking alive. Observable only because the loop's toucher
        ;; was redefined to a short interval above: at the production 60s one,
        ;; no test-length sleep could tell a retired thread from a sleeping one.
        (let [after (backdate! f 600000)]
          (Thread/sleep 400)
          (is (= after (.lastModified f))
              "a loop that has RETURNED must not go on looking alive"))))))

(deftest runner-loop-wires-the-heartbeat
  (testing "loop! refreshes the heartbeat it was given, then stops"
    (let [dir (temp-dir!)
          f (java.io.File. dir "runner")]
      (.createNewFile f)
      (let [was (backdate! f 600000)]
        (with-redefs [core/heartbeat-touch-interval-ms 50]
          (with-mock-state (finished-game-state "runner")
            (with-out-str (hrunner/loop! :heartbeat-file (.getPath f)))))
        (is (> (.lastModified f) was)
            "the Runner loop has the identical hole and must refresh too")
        (let [after (backdate! f 600000)]
          (Thread/sleep 400)
          (is (= after (.lastModified f))
              "a loop that has RETURNED must not go on looking alive"))))))

(deftest loops-run-without-a-heartbeat-file
  (testing "a loop driven by hand (no :heartbeat-file) still runs"
    ;; The no-op stop fn exists so this needs no branch at the call site; if the
    ;; helper ever returned nil for a blank path, the finally would NPE here.
    (with-mock-state (finished-game-state "corp")
      ;; `with-out-str` always returns a string, so the only thing these can
      ;; fail on is a THROW — which is the whole point: a helper that returned
      ;; nil for a blank path would NPE in the loop's `finally`. Named for what
      ;; it checks rather than dressed up as an output-shape assertion.
      (is (nil? (completes-without-throwing #(hcorp/start-autonomous!)))
          "the Corp loop must run with no heartbeat path"))
    (with-mock-state (finished-game-state "runner")
      (is (nil? (completes-without-throwing #(hrunner/loop!)))
          "the Runner loop must run with no heartbeat path"))))
