(ns game.ai-approach-unrezzed-wire-test
  "Issue #244: approach to UNREZZED ice deadlocked whenever the Corp meant to pass.

   The Runner's run automation parked with :waiting-for-corp-rez before sending
   its own pass, while run-window-owner (and the Corp's client with it) said the
   Runner owes the first pass at a window nobody has passed. Two authorities,
   one window, nobody acting. A Corp REZ got through only because the engine
   takes a rez with nobody passed — which is why the marquee half-hit it.

   Driven end to end against the real engine: both seats' real continue-run!
   read the real serialized wire, and whatever they send goes back into the
   engine as that seat's action. The unit fixtures for this window carry no
   :prompt-type \"run\" prompt, which the engine always shows from initiation
   (game.core.prompts/show-run-prompts), so they cannot say what the live
   chain does after the Runner's handler stands aside."
  (:require [game.core :as core]
            [game.core.diffs :as diffs]
            [game.test-framework :refer :all]
            [ai-display]
            [ai-core :as core-ai]
            [ai-runs :as runs]
            [ai-run-runner-handlers :as runner-handlers]
            [ai-state :as ai-state]
            [ai-websocket-client-v2 :as ws]
            [cheshire.core :as json]
            [clojure.test :refer :all]))

(use-fixtures :each (fn [t]
                      (runs/reset-strategy!)
                      ;; A lost or withheld send waits out the confirm bound; keep
                      ;; it short. Lag tests stay well inside it.
                      (with-redefs [runner-handlers/approach-pass-confirm-ms 600]
                        (try (t) (finally (reset! ai-state/client-state {}))))))

(def ^:private gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000244"))

(defn- wire-state
  "The seat's view as the transport delivers it: the real serializer, then a
   JSON round trip (the client reads game state with json/parse-string, keys
   keywordized) — so VALUES arrive as strings: :prompt-type \"run\", :no-action
   \"runner\", :phase \"approach-ice\". Hand-stringifying one key at a time is how
   a fixture ends up disagreeing with the live wire about the field that matters."
  [state side]
  (let [gs (get (diffs/public-states state) (if (= side "corp") :corp-state :runner-state))]
    {:side side
     :gameid gameid
     :game-state (json/parse-string (json/generate-string gs) true)}))

(defn- tick!
  "One continue-run! step for `side`, as its own seat would take it: the seat's
   wire in client-state, its sends delivered to the engine as ITS actions.

   :deliver? false drops the send after the socket accepts it (a lost pass).
   :refresh? false delivers it but never pushes the diff (the seat keeps its old
   wire; it still gets one if it had none). :lag-ms N pushes the diff N ms after
   the send, from another thread, the way a slow transport does. Strategy flags
   are per-seat in real life (two REPLs), so the strategy atom is cleared between
   ticks."
  [state side & {:keys [flags deliver? refresh? lag-ms] :or {deliver? true refresh? true}}]
  (reset! runs/run-strategy {})
  (when (or refresh? (not= side (:side @ai-state/client-state)))
    (reset! ai-state/client-state (wire-state state side)))
  (let [sent (atom [])
        result (atom nil)
        out (with-redefs [ws/send-message! (fn [_evt {:keys [command args] :as data}]
                                             (swap! sent conj data)
                                             (when deliver?
                                               (core/process-action command state (keyword side) args))
                                             (cond
                                               lag-ms (future (Thread/sleep lag-ms)
                                                              (reset! ai-state/client-state (wire-state state side)))
                                               refresh? (reset! ai-state/client-state (wire-state state side)))
                                             true)]
              (with-out-str (reset! result (apply runs/continue-run! flags))))]
    {:result @result :sent (mapv :command @sent) :out out}))

(defmacro with-unrezzed-approach
  "Ice Wall installed unrezzed on HQ; the Runner runs HQ and is approaching it
   with nobody passed."
  [& body]
  `(do-game
     (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"] :credits 10}
                :runner {:hand ["Bank Job"]}})
     (play-from-hand ~'state :corp "Ice Wall" "HQ")
     (take-credits ~'state :corp)
     (run-on ~'state "HQ")
     (is (= :approach-ice (get-in @~'state [:run :phase])) "precondition: approaching")
     (is (not (get-in @~'state [:run :no-action])) "precondition: nobody has passed")
     (is (not (:rezzed (get-ice ~'state :hq 0))) "precondition: the ICE is unrezzed")
     ~@body))

(defn- drive!
  "Alternate the two seats until the approach window closes or `n` rounds pass.
   Returns the trail of [side result-status sends] for the failure message."
  [state first-side corp-flags n]
  (let [order (if (= first-side "runner") ["runner" "corp"] ["corp" "runner"])]
    (loop [i 0 trail []]
      (if (or (>= i n) (not= :approach-ice (get-in @state [:run :phase])))
        trail
        (let [side (nth order (mod i 2))
              {:keys [result sent]} (if (= side "corp")
                                      (tick! state side :flags corp-flags)
                                      (tick! state side))]
          (recur (inc i) (conj trail [side (:status result) sent])))))))

(deftest the-runners-loop-sends-the-first-pass
  (testing "#244: nobody has passed an unrezzed approach, so the Runner owes the first pass — and must send it"
    (with-unrezzed-approach
      (let [{:keys [result sent out]} (tick! state "runner")]
        (is (= ["continue"] sent)
            (str "the Runner owes this window; parking here was the deadlock. got " result))
        (is (re-find #"Passed the unrezzed-ICE approach" out)
            "and it is the approach handler that sent it, the one that latches. handle-auto-continue also passes here but with no latch, and the live wire has no :rezzed key for a some-> gate to read, which left a first version of this handler inert while the test stayed green")
        (is (= :runner (get-in @state [:run :no-action]))
            "and the engine records the Runner's pass")
        (is (= :approach-ice (get-in @state [:run :phase]))
            "which does not close the window — the Corp's rez decision is still to come")))))

(deftest the-runner-does-not-pass-twice
  (testing "the engine's advance branch has no side check: a second Runner continue would close the window over the Corp's rez decision"
    (with-unrezzed-approach
      (tick! state "runner")
      (let [{:keys [result sent]} (tick! state "runner")]
        (is (empty? sent) "the Corp owes the window now")
        (is (= :waiting-for-corp-rez (:status result)))
        (is (= :approach-ice (get-in @state [:run :phase]))
            "and the window is still open for the Corp")))))

(deftest a-corp-that-passes-lets-the-run-through
  (testing "#244 end to end, the case that deadlocked: the Corp declines to rez"
    (doseq [first-side ["runner" "corp"]]
      (with-unrezzed-approach
        (let [trail (drive! state first-side ["--no-rez"] 8)]
          (is (not= :approach-ice (get-in @state [:run :phase]))
              (str first-side " first: the window never closed. trail " trail))
          (is (not (:rezzed (get-ice state :hq 0)))
              "and the ICE was passed unrezzed"))))))

(deftest a-corp-that-rezzes-after-the-runner-passes-is-legal
  (testing "the Runner's pass does not take the rez away: the engine takes a rez with :no-action :runner and the Corp's pass then advances into the encounter"
    (doseq [first-side ["runner" "corp"]]
      (with-unrezzed-approach
        (let [trail (drive! state first-side ["--rez" "Ice Wall"] 8)]
          (is (:rezzed (get-ice state :hq 0))
              (str first-side " first: the Corp's rez got lost. trail " trail))
          (is (= :encounter-ice (get-in @state [:run :phase]))
              (str first-side " first: and the run reaches the encounter. trail " trail)))))))

(deftest an-undecided-corp-is-told-the-rez-is-its-call
  (testing "a Corp seat with no standing rez policy must SURFACE the decision once the Runner has passed, not wait on the Runner"
    (with-unrezzed-approach
      (tick! state "runner")
      (let [{:keys [result sent]} (tick! state "corp")]
        (is (empty? sent) "no policy, so nothing is decided for the seat")
        (is (not= :waiting-for-opponent (:status result))
            (str "the Corp owns this window; waiting here is the other half of the deadlock. got " result))))))

(deftest the-stall-breaker-never-skips-the-rez
  (testing "the Runner now reaches 'passed, Corp owes' here, which is exactly the state #31's self-advance acts on — a Corp model seat thinking past the grace must still get its rez decision"
    (with-unrezzed-approach
      (tick! state "runner")
      (with-redefs [runs/self-advance-grace-ms 0]
        (dotimes [_ 3]
          (let [{:keys [sent]} (tick! state "runner")]
            (is (empty? sent) "an unrezzed approached ICE IS a Corp decision, however long it takes"))))
      (is (= :approach-ice (get-in @state [:run :phase]))))))

(deftest a-lagging-wire-does-not-send-the-pass-twice
  (testing "two review seats: until the diff arrives the #98 guard still reads :no-action false, and a second Runner continue closes the window over the rez. The handler waits for the wire to show its pass before the next tick reads it"
    (with-unrezzed-approach
      (let [first  (tick! state "runner" :refresh? false :lag-ms 250)
            second (tick! state "runner" :refresh? false)]
        (is (= ["continue"] (:sent first)))
        (is (not (re-find #"No sign of that pass" (:out first))) "confirmed within the bound")
        (is (empty? (:sent second)) "the next tick reads a wire that has seen the pass")
        (is (= :waiting-for-corp-rez (get-in second [:result :status])))
        (is (= :approach-ice (get-in @state [:run :phase]))
            "the window is still open for the Corp")))))

(deftest a-lost-pass-is-owed-again
  (testing "a send the socket took but the engine never saw shows nothing on the wire, so the pass is still owed — whichever side passed first (round 2: a latch that remembered the ORDER left the Corp-first case waiting on both sides)"
    (doseq [corp-first? [false true]]
      (with-unrezzed-approach
        (when corp-first? (tick! state "corp" :flags ["--no-rez"]))
        (let [lost (tick! state "runner" :deliver? false)]
          (is (= ["continue"] (:sent lost)))
          (is (re-find #"No sign of that pass" (:out lost)) "and the seat is told it did not land"))
        (when-not corp-first? (tick! state "corp" :flags ["--no-rez"]))
        (is (= :corp (get-in @state [:run :no-action])) "precondition: only the Corp's pass is on the ledger")
        (is (= ["continue"] (:sent (tick! state "runner")))
            (str "corp-first " corp-first? ": the Runner's pass is still owed, and sent"))
        (is (not= :approach-ice (get-in @state [:run :phase])))))))

(deftest a-second-run-starts-owed
  (testing "same server, same ICE, same position, same turn: nothing from the first run's pass carries over"
    (with-unrezzed-approach
      (drive! state "runner" ["--no-rez"] 8)
      (is (not= :approach-ice (get-in @state [:run :phase])) "first run got past")
      (core/process-action "jack-out" state :runner nil)
      (is (nil? (:run @state)) "precondition: the first run is over")
      (run-on state "HQ")
      (is (= :approach-ice (get-in @state [:run :phase])))
      (is (= ["continue"] (:sent (tick! state "runner")))
          "the new run's first pass is owed and sent"))))

(deftest the-corp-is-not-told-plain-continue-passes-here
  (testing "#244: the marquee Corp re-ran `continue --single` because its hint said 'continue' passes priority; at an unrezzed approach the client answers plain continue with the same rez decision and sends nothing"
    (with-unrezzed-approach
      (tick! state "runner")
      (let [w (wire-state state "corp")
            run (get-in w [:game-state :run])
            out (with-out-str (#'ai-display/print-run-window-priority! w run (:phase run) "corp"))
            {:keys [sent result]} (tick! state "corp")]
        (is (empty? sent) "premise: a flagless Corp continue sends nothing here…")
        (is (= :decision-required (:status result)) "…and re-presents the decision")
        (is (not (re-find #"'continue' passes priority" out))
            "so the hint must not claim it passes (absence assertion: the true lines are present either way)")
        (is (re-find #"continue --single --no-rez" out) "name the one-window pass")
        (is (re-find #"continue --rez \"Ice Wall\"" out) "and the rez, with the card's name")))))

(deftest a-closing-pass-is-not-mistaken-for-a-lost-one
  (testing "the Corp passed FIRST, so the Runner's pass closes the window, and until the diff arrives the wire still shows approach-ice with only the Corp on the ledger — the same picture as a lost pass (round 2). The handler returns only once the wire has moved on, so the next tick cannot read that picture"
    (with-unrezzed-approach
      (tick! state "corp" :flags ["--no-rez"])
      (is (= :corp (get-in @state [:run :no-action])) "precondition: the Corp passed first")
      (let [first (tick! state "runner" :refresh? false :lag-ms 250)]
        (is (= ["continue"] (:sent first)))
        (is (not (re-find #"No sign of that pass" (:out first)))
            "a closing pass shows up as the window MOVING, not as our name on the ledger (set-phase resets it); waiting on the ledger alone would stall the full bound and then claim the pass was lost")
        (is (not= :approach-ice (get-in @state [:run :phase])) "our pass closed the window")
        (is (not= "approach-ice" (get-in @ai-state/client-state [:game-state :run :phase]))
            "and the seat's wire has seen that before the handler returned")
        (let [second (tick! state "runner" :refresh? false)]
          (is (not (re-find #"Passed the unrezzed-ICE approach" (:out second)))
              "so no tick passes the old approach again (whatever it does now, it does in the window it can see)"))))))

(deftest a-re-approach-in-the-same-run-is-owed-again
  (testing "round 2 (Astra, reproduced): Cell Portal sends the Runner back to the outer ICE in the SAME run — same run prompt, same position, same card. A pass remembered by window key deadlocked here; the ledger for the new window is fresh, and so is the obligation"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Cell Portal" "Ice Wall"] :credits 20}
                 :runner {:hand ["Bank Job"]}})
      (play-from-hand state :corp "Cell Portal" "HQ")
      (play-from-hand state :corp "Ice Wall" "HQ")
      (take-credits state :corp)
      (let [cp (get-ice state :hq 0)]
        (rez state :corp cp)
        (run-on state "HQ")
        (is (= 2 (get-in @state [:run :position])) "precondition: approaching the unrezzed Ice Wall")
        ;; First approach: the Corp passes first, the Runner's pass closes it.
        (tick! state "corp" :flags ["--no-rez"])
        (is (= ["continue"] (:sent (tick! state "runner"))))
        ;; Engine-driven through Cell Portal and back out.
        (run-continue-until state :encounter-ice cp)
        (card-subroutine state :corp cp 0)
        (click-prompt state :runner "No")
        (when (core/get-current-encounter state)
          (core/process-action "continue" state :corp nil)
          (core/process-action "continue" state :runner nil))
        (is (= :approach-ice (get-in @state [:run :phase])) "back at an approach")
        (is (= 2 (get-in @state [:run :position])) "to the SAME outer ICE")
        (is (not (:rezzed (get-ice state :hq 1))))
        (let [trail (drive! state "corp" ["--no-rez"] 8)]
          (is (not= [:approach-ice 2] [(get-in @state [:run :phase]) (get-in @state [:run :position])])
              (str "the re-approach closes like any other. trail " trail)))))))

(deftest plain-continue-can-advance-an-abandoned-window
  (testing "#102 item 7 round 3 (Fable, reproduced): `wait` tells a passed Runner that `continue` advances an abandoned decision-free window, but plain `continue` is monitor-run!, which RESET the abandon clock on entry, so the #31 self-advance always saw 0 ms and never fired"
    (core-ai/reset-window-grace!)
    (with-redefs [runs/self-advance-grace-ms 200
                  core-ai/window-abandon-grace-ms 200]
      (do-game
        (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"] :credits 10}
                   :runner {:hand ["Bank Job"]}})
        (play-from-hand state :corp "Ice Wall" "HQ")
        (take-credits state :corp)
        (run-on state "HQ")
        (rez state :corp (get-ice state :hq 0))
        (core/process-action "continue" state :runner nil)     ; the Runner passes; the Corp never answers
        (is (= :runner (get-in @state [:run :no-action])) "precondition: the Runner has passed")
        (reset! ai-state/client-state (wire-state state "runner"))
        (core-ai/wait-for-relevant-diff {:timeout 0 :verbose false})  ; the seat's wait observes the window
        (Thread/sleep 300)
        (let [sent (atom [])]
          (with-redefs [ws/send-message! (fn [_evt {:keys [command args] :as data}]
                                           (swap! sent conj command)
                                           (core/process-action command state :runner args)
                                           (reset! ai-state/client-state (wire-state state "runner"))
                                           true)]
            (with-out-str (runs/monitor-run!)))
          (is (some #{"continue"} @sent) (str "plain continue must self-advance the abandoned window, sent " @sent))
          (is (not= :approach-ice (get-in @state [:run :phase]))))))))

(deftest a-continue-only-seat-restarts-the-clock-at-a-re-approach
  (testing "round 4 (Opus, reproduced by mutation): continue-run! observes the window every tick. Without that, a seat that only uses `continue` recorded nothing at a fresh re-approach (the self-advance records only PASSED windows), so the same key inherited the old clock and a second pass went out at 0 ms"
    (core-ai/reset-window-grace!)
    (with-redefs [runs/self-advance-grace-ms 200
                  core-ai/window-abandon-grace-ms 200]
      (do-game
        (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"] :credits 10}
                   :runner {:hand ["Bank Job"]}})
        (play-from-hand state :corp "Ice Wall" "HQ")
        (take-credits state :corp)
        (run-on state "HQ")
        (rez state :corp (get-ice state :hq 0))
        (let [fresh (wire-state state "runner")]
          (core/process-action "continue" state :runner nil)
          (let [passed (wire-state state "runner")
                sent (atom [])
                tick (fn [w] (reset! ai-state/client-state w)
                       (with-redefs [ws/send-message! (fn [_ d] (swap! sent conj (:command d)) true)]
                         (with-out-str (runs/continue-run!))))]
            (tick passed)                ; the passed window: clock starts
            (Thread/sleep 300)
            (tick fresh)                 ; a different (fresh) window at the same position
            (reset! sent [])
            (tick passed)                ; same key as the first, but a NEW window
            (is (empty? @sent)
                (str "a re-seen window gets its own grace; sent " @sent))))))))
