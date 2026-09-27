(ns game.ai-send-ack-wire-test
  "Issue #245: a lagging wire double-passed a run window. With Ice Wall rezzed and
   the Runner approaching, four continue-run! ticks over a stale wire sent four
   continues. The second one closed the Corp's approach window and the next two
   passed the ENCOUNTER with its subroutine unbroken.

   ws/send-message! held a lock and then waited for the cursor to move, which any
   diff does (the opponent's included), and returned true on timeout anyway. The
   engine already acknowledges each action: handle-action bumps [side :aid] in
   the diff for that action, and the web UI's lock waits for exactly that
   (nr.gameboard.state/check-lock?).

   These tests drive the REAL send-message! (lock, ack wait, resync). Only the
   socket write (send-message-impl!) is stubbed: it delivers actions to the real
   engine as this seat's, and answers :game/resync through the real
   handle-message the way the server does. The seat reads the real serializer's
   output after a JSON round trip."
  (:require [game.core :as core]
            [game.core.diffs :as diffs]
            [game.main :as main]
            [game.test-framework :refer :all]
            [ai-runs :as runs]
            [ai-state :as ai-state]
            [ai-websocket-client-v2 :as ws]
            [cheshire.core :as json]
            [clojure.test :refer :all]))

(use-fixtures :each (fn [t]
                      (runs/reset-strategy!)
                      ;; Keep the silent-wire waits short. Lag tests stay well
                      ;; inside ack-wait-ms.
                      (with-redefs [ws/ack-wait-ms 400
                                    ws/resync-wait-ms 400]
                        (try (t) (finally (reset! ai-state/client-state {}))))))

(def ^:private gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000245"))

(defn- side-state [state side]
  (get (diffs/public-states state) (if (= side "corp") :corp-state :runner-state)))

(defn- wire-state
  [state side]
  {:side side
   :gameid gameid
   :game-state (json/parse-string (json/generate-string (side-state state side)) true)})

(defn- with-socket
  "Run `f` with the socket write stubbed. Actions reach the engine through
   main/handle-action, the path web/game.clj uses, so the :aid bump is real.

   The downlink is modelled the way the design depends on it (review round 4):
   the server builds each reply WHEN IT PROCESSES the request (actions and
   resyncs share one FIFO game thread), and the socket delivers replies IN
   ORDER, each no earlier than its own delay. A late message holds up the ones
   behind it, as on one TCP stream. A diff that lands on a cleared board is
   dropped, as update-game-state! drops it.

   `wire` says what comes back:
     :now          the action's diff lands before the write returns
     [:lag N]      its diff lands N ms later; a resync is answered 100ms after it is asked
     :stale        the engine applies it but its diff never arrives; a resync is answered
     :dead         the engine applies it and nothing ever comes back, resync included
     :lost         the engine never sees it; a resync is answered
     :opponent     ours is lost, an opponent diff lands, and a resync is answered
     [:delays ds]  every message is delivered, the nth after (nth ds n) ms (last repeats)"
  [state side wire f]
  (let [delivered (atom [])
        resyncs (atom 0)
        downlink (agent nil)
        n (atom -1)
        delay-for (fn [kind]
                    (let [i (swap! n inc)]
                      (cond
                        (and (vector? wire) (= :delays (first wire))) (let [ds (second wire)] (nth ds (min i (dec (count ds)))))
                        (and (vector? wire) (= :lag (first wire))) (if (= kind :diff) (second wire) 100)
                        (= kind :resync) (when-not (= wire :dead) 100)
                        :else nil)))
        ;; Built now (server processing time), delivered in order after `ms`.
        send-down! (fn [ms deliver!]
                     (when ms
                       (let [at (+ (System/currentTimeMillis) ms)]
                         (send-off downlink (fn [_] (let [wait (- at (System/currentTimeMillis))]
                                                      (when (pos? wait) (Thread/sleep wait)))
                                              (deliver!) nil)))))
        diff-down! (fn [ms]
                     (let [snap (wire-state state side)]
                       (send-down! ms #(when (:game-state @ai-state/client-state)
                                         (swap! ai-state/client-state merge snap)
                                         (ai-state/bump-cursor!)))))
        resync-down! (fn [ms]
                       (let [snap (json/generate-string (side-state state side))]
                         (send-down! ms #(with-out-str
                                           (ws/handle-message {:type :game/resync :data snap})))))]
    (with-redefs-fn
      {#'ws/send-message-impl!
       (fn [evt {:keys [command args]}]
         (case evt
           :game/action
           (do
             (when-not (#{:lost :opponent} wire)
               (swap! delivered conj command)
               (main/handle-action state (keyword side) command args))
             (cond
               (= wire :now) (do (swap! ai-state/client-state merge (wire-state state side))
                                 (ai-state/bump-cursor!))
               (= wire :opponent) (do (main/handle-action state (if (= side "runner") :corp :runner) "credit" nil)
                                      (swap! ai-state/client-state merge (wire-state state side))
                                      (ai-state/bump-cursor!))
               (not (#{:stale :dead :lost} wire)) (diff-down! (delay-for :diff))))
           :game/resync
           (do (swap! resyncs inc)
               (resync-down! (delay-for :resync)))
           nil)
         true)}
      (fn [] (let [value (atom nil)
                   out (with-out-str (reset! value (f)))]
               ;; Drain the downlink, so nothing lands in the next test's board.
               (is (await-for 10000 downlink) "the downlink drained")
               {:value @value :delivered @delivered :resyncs @resyncs :out out})))))

(defn- tick!
  "One continue-run! step, output swallowed."
  [& flags]
  (reset! runs/run-strategy {})
  (with-out-str (apply runs/continue-run! flags)))

(defn- send-continue! []
  (ws/send-message! :game/action {:gameid gameid :command "continue" :args nil}))

(defmacro with-rezzed-approach
  [& body]
  `(do-game
     (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"] :credits 10}
                :runner {:hand ["Bank Job"]}})
     (play-from-hand ~'state :corp "Ice Wall" "HQ")
     (take-credits ~'state :corp)
     (run-on ~'state "HQ")
     (rez ~'state :corp (get-ice ~'state :hq 0))
     (is (= :approach-ice (get-in @~'state [:run :phase])) "precondition: approaching")
     (is (:rezzed (get-ice ~'state :hq 0)) "precondition: the ICE is rezzed")
     (is (not (get-in @~'state [:run :no-action])) "precondition: nobody has passed")
     (reset! ai-state/client-state (wire-state ~'state "runner"))
     ~@body))

(deftest a-stale-wire-does-not-pass-the-encounter
  (testing "#245 as reproduced: four ticks, and the action's diff never arrives"
    (with-rezzed-approach
      (let [{:keys [delivered resyncs]} (with-socket state "runner" :stale
                                          #(dotimes [_ 4] (tick!)))]
        (is (= ["continue"] delivered)
            "one pass is owed; the rest went out over a board that did not show the first")
        (is (= 1 resyncs) "the silence was answered by fetching the engine's board")
        (is (= :approach-ice (get-in @state [:run :phase]))
            "the Corp's approach window is still open")
        (is (= :runner (get-in @state [:run :no-action]))
            "with the Runner's pass on the ledger")))))

(deftest a-dead-wire-does-not-pass-the-encounter
  (testing "nothing comes back at all, not even the resync: the board is cleared, and nothing decides on it"
    (with-rezzed-approach
      (let [{:keys [delivered]} (with-socket state "runner" :dead
                                  #(dotimes [_ 4] (tick!)))]
        (is (= ["continue"] delivered))
        (is (nil? (:game-state @ai-state/client-state)) "the stale board is gone")
        (is (= :approach-ice (get-in @state [:run :phase])))))))

(deftest a-lagging-wire-sends-one-pass
  (testing "the review seat's 300ms: the send waits for its ack, so the next tick reads a board that has it"
    (with-rezzed-approach
      (let [{:keys [delivered resyncs]} (with-socket state "runner" [:lag 150]
                                          #(dotimes [_ 4] (tick!)))]
        (is (= ["continue"] delivered))
        (is (zero? resyncs) "an ack inside the wait needs no resync")
        (is (= :approach-ice (get-in @state [:run :phase])))
        (is (= :runner (get-in @state [:run :no-action])))))))

(deftest the-ack-is-our-aid-not-any-diff
  (with-rezzed-approach
    (testing "a diff arrives and the cursor moves, but it is the opponent's; ours was lost"
      (is (= :unconfirmed (:value (with-socket state "runner" :opponent send-continue!)))))
    (testing "the send that is acknowledged says so"
      (is (= :confirmed (:value (with-socket state "runner" :now send-continue!)))))))

(deftest a-resync-that-shows-our-action-confirms-it
  (with-rezzed-approach
    (is (= :confirmed (:value (with-socket state "runner" :stale send-continue!))))
    (is (= "runner" (get-in @ai-state/client-state [:game-state :run :no-action]))
        "and the board the seat reads next shows the pass")))

(deftest a-lost-action-is-owed-again
  (testing "the engine never saw it: the resynced board says so, and the next tick sends it"
    (with-rezzed-approach
      ;; Two ticks: the first pauses on the ICE-rezzed event, the second passes.
      (let [lost (with-socket state "runner" :lost #(dotimes [_ 2] (tick!)))]
        (is (= [] (:delivered lost)))
        (is (= 1 (:resyncs lost))))
      (is (not (get-in @state [:run :no-action])) "precondition: nobody has passed")
      (is (= ["continue"] (:delivered (with-socket state "runner" :now #(dotimes [_ 2] (tick!)))))
          "the pass is still owed, and sent once")
      (is (= :runner (get-in @state [:run :no-action]))))))

(deftest a-corp-rez-over-a-silent-wire-still-rezzes
  (testing "round 2 (reproduced by a seat against the gate design): a refused rez was latched as attempted and the ICE passed unrezzed. Nothing is refused now; the rez goes out and the resynced board shows it"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Ice Wall"] :credits 12}
                 :runner {:hand ["Bank Job"]}})
      (play-from-hand state :corp "Ice Wall" "HQ")
      (take-credits state :corp)
      (run-on state "HQ")
      (core/process-action "continue" state :runner nil)
      (is (= :runner (get-in @state [:run :no-action])) "precondition: the Runner passed; the rez is the Corp's")
      (reset! ai-state/client-state (wire-state state "corp"))
      (with-socket state "corp" :stale
        #(dotimes [_ 3] (tick! "--rez" "Ice Wall")))
      (is (:rezzed (get-ice state :hq 0)) "the Corp's rez landed")
      (is (= :encounter-ice (get-in @state [:run :phase])) "and the run met a rezzed Ice Wall"))))

(deftest a-boardless-send-waits-only-for-a-diff
  (testing "no board, no :aid to compare: the send returns on the first cursor move, as before #245, without resyncing"
    (do-game
      (new-game {:corp {:hand ["Hedge Fund"]}})
      (reset! ai-state/client-state {:side "corp" :gameid gameid})
      (let [t0 (System/currentTimeMillis)
            cursor0 (ai-state/get-cursor)
            ;; With no board, a diff is dropped; what moves the cursor is a
            ;; full state landing (a resync already in flight), 50ms in.
            {[value cursor-at-return] :value resyncs :resyncs}
            (with-socket state "corp" :stale
              #(let [landing (future (Thread/sleep 50) (ai-state/bump-cursor!))]
                 (let [v (ws/send-message! :game/action {:gameid gameid :command "credit" :args nil})
                       c (ai-state/get-cursor)]
                   @landing
                   [v c])))]
        (is (= :unconfirmed value))
        (is (zero? resyncs))
        (is (not= cursor0 cursor-at-return) "it waited for the diff")
        (is (< (- (System/currentTimeMillis) t0) 300) "not the whole ack wait")))))

(deftest a-resync-reply-is-not-left-for-the-next-send
  (testing "round 4 (Opus, reproduced): with the downlink slowing down, returning on our ack left our resync's reply in flight. The next send counted it as its own, read a board from before itself, and the caller clicked again"
    (do-game
      (new-game {:runner {:hand ["Bank Job"]}})
      (take-credits state :corp)
      (reset! ai-state/client-state (wire-state state "runner"))
      (let [c0 (:credit (get-runner))
            target (+ c0 2)
            board-credit #(get-in @ai-state/client-state [:game-state :runner :credit])]
        (with-redefs [ws/resync-wait-ms 3000]
          (with-socket state "runner" [:delays [450 700 800]]
            #(loop [i 0]
               (when (and (< i 4) (number? (board-credit)) (< (board-credit) target))
                 (ws/send-message! :game/action {:gameid gameid :command "credit" :args nil})
                 (recur (inc i))))))
        (is (= target (:credit (get-runner)))
            "the caller clicked until its board showed the target, so every extra click is one it decided on a stale board")))))
