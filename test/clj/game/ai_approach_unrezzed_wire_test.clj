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
            [ai-runs :as runs]
            [ai-state :as ai-state]
            [ai-websocket-client-v2 :as ws]
            [cheshire.core :as json]
            [clojure.test :refer :all]))

(use-fixtures :each (fn [t]
                      (runs/reset-strategy!)
                      (try (t) (finally (reset! ai-state/client-state {})))))

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
   Strategy is per-seat in real life (two REPLs), so it is reset around every
   tick rather than shared between the two sides here."
  [state side & flags]
  (runs/reset-strategy!)
  (reset! ai-state/client-state (wire-state state side))
  (let [sent (atom [])
        result (atom nil)]
    (with-redefs [ws/send-message! (fn [_evt {:keys [command args] :as data}]
                                     (swap! sent conj data)
                                     (core/process-action command state (keyword side) args)
                                     ;; the transport would now push the new wire
                                     (reset! ai-state/client-state (wire-state state side))
                                     true)]
      (with-out-str (reset! result (apply runs/continue-run! flags))))
    {:result @result :sent (mapv :command @sent)}))

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
                                      (apply tick! state side corp-flags)
                                      (tick! state side))]
          (recur (inc i) (conj trail [side (:status result) sent])))))))

(deftest the-runners-loop-sends-the-first-pass
  (testing "#244: nobody has passed an unrezzed approach, so the Runner owes the first pass — and must send it"
    (with-unrezzed-approach
      (let [{:keys [result sent]} (tick! state "runner")]
        (is (= ["continue"] sent)
            (str "the Runner owes this window; parking here was the deadlock. got " result))
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
