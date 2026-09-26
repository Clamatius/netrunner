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

   These tests drive the REAL send-message! (lock, ack wait, gate). Only the
   socket write (send-message-impl!) is stubbed, and it delivers to the real
   engine as this seat's action. The seat reads the real serializer's output
   after a JSON round trip."
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
                      (ws/clear-unacked!)
                      ;; A withheld ack waits out the bound; keep it short. Lag
                      ;; tests stay well inside it.
                      (with-redefs [ws/ack-wait-ms 400]
                        (try (t) (finally (reset! ai-state/client-state {})
                                          (ws/clear-unacked!))))))

(def ^:private gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000245"))

(defn- wire-state
  [state side]
  (let [gs (get (diffs/public-states state) (if (= side "corp") :corp-state :runner-state))]
    {:side side
     :gameid gameid
     :game-state (json/parse-string (json/generate-string gs) true)}))

(defn- push-wire!
  "The diff lands: new wire, and the cursor moves the way handle-message's
   :game/diff branch moves it."
  [state side]
  (swap! ai-state/client-state merge (wire-state state side))
  (ai-state/bump-cursor!))

(defn- with-socket
  "Run `f` with the socket write stubbed. The engine sees the action through
   main/handle-action, the path web/game.clj uses, so the :aid bump is real.
   `wire` says what the seat sees afterwards: :stale (nothing ever arrives),
   :lag N (the diff lands N ms later, from another thread), :now,
   :lost (the socket took it and the engine never saw it), :error N (lost, and
   the server's :game/error frame lands N ms later, from another thread, while
   the sender is still waiting), or :opponent (ours is lost, but an opponent
   diff lands)."
  [state side wire f]
  (let [delivered (atom [])]
    (with-redefs-fn
      {#'ws/send-message-impl!
       (fn [_evt {:keys [command args]}]
         (when-not (or (#{:opponent :lost} wire) (and (vector? wire) (= :error (first wire))))
           (swap! delivered conj command)
           (main/handle-action state (keyword side) command args))
         (cond
           (= wire :now) (push-wire! state side)
           (= wire :opponent) (do (main/handle-action state (if (= side "runner") :corp :runner) "credit" nil)
                                  (push-wire! state side))
           (and (vector? wire) (= :lag (first wire)))
           (future (Thread/sleep (second wire)) (push-wire! state side))
           (and (vector? wire) (= :error (first wire)))
           (future (Thread/sleep (second wire))
                   (with-out-str (ws/handle-message {:type :game/error :data nil}))))
         true)}
      (fn [] {:value (f) :delivered @delivered}))))

(defn- tick!
  "One continue-run! step, output swallowed."
  []
  (reset! runs/run-strategy {})
  (let [out (with-out-str (runs/continue-run!))]
    out))

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
  (testing "#245 as reproduced: four ticks over a wire that never moves"
    (with-rezzed-approach
      (let [{:keys [delivered]} (with-socket state "runner" :stale
                                  #(dotimes [_ 4] (tick!)))]
        (is (= ["continue"] delivered)
            "one pass is owed; the rest went out over a wire that had not shown the first")
        (is (= :approach-ice (get-in @state [:run :phase]))
            "the Corp's approach window is still open")
        (is (= :runner (get-in @state [:run :no-action]))
            "with the Runner's pass on the ledger")))))

(deftest a-lagging-wire-sends-one-pass
  (testing "the review seat's 300ms: the send waits for its ack, so the next tick reads a wire that has it"
    (with-rezzed-approach
      (let [{:keys [delivered]} (with-socket state "runner" [:lag 150]
                                  #(dotimes [_ 4] (tick!)))]
        (is (= ["continue"] delivered))
        (is (= :approach-ice (get-in @state [:run :phase])))
        (is (= :runner (get-in @state [:run :no-action])))))))

(deftest the-ack-is-our-aid-not-any-diff
  (with-rezzed-approach
    (testing "a diff arrives and the cursor moves, but it is the opponent's; ours was lost"
      (let [{:keys [value]} (with-socket state "runner" :opponent
                              #(ws/send-message! :game/action {:gameid gameid :command "continue" :args nil}))]
        (is (= :unconfirmed value))))
    (testing "the send that is acknowledged says so"
      (ws/clear-unacked!)
      (let [{:keys [value]} (with-socket state "runner" :now
                              #(ws/send-message! :game/action {:gameid gameid :command "continue" :args nil}))]
        (is (= :confirmed value))))))

(deftest an-unacknowledged-send-blocks-the-next
  (with-rezzed-approach
    (let [send! #(ws/send-message! :game/action {:gameid gameid :command "continue" :args nil})]
      (with-socket state "runner" :stale send!)
      (testing "the engine has not answered the first action, so the second is refused, not sent"
        (let [{:keys [value delivered]} (with-socket state "runner" :stale send!)]
          (is (false? value))
          (is (empty? delivered))))
      (testing "once the wire shows the ack, sending resumes"
        (push-wire! state "runner")
        (let [{:keys [value delivered]} (with-socket state "runner" :now
                                          #(ws/send-message! :game/action {:gameid gameid :command "credit" :args nil}))]
          (is (= ["credit"] delivered))
          (is (= :confirmed value)))))))

(deftest a-server-error-releases-the-gate
  (testing "the server rolled our action back, so its ack will never come"
    (with-rezzed-approach
      (let [send! #(ws/send-message! :game/action {:gameid gameid :command "continue" :args nil})]
        (with-socket state "runner" :lost send!)
        (is (false? (:value (with-socket state "runner" :now send!)))
            "precondition: the lost action's ack is still pending and blocks")
        (with-socket state "runner" :lost
          #(with-out-str (ws/handle-message {:type :game/error :data nil})))
        (reset! ai-state/client-state (wire-state state "runner"))
        (is (= ["continue"] (:delivered (with-socket state "runner" :now send!))))))))

(deftest an-old-pending-ack-expires
  (testing "a bound, not a wedge: an ack that never comes stops blocking eventually"
    (with-rezzed-approach
      (let [send! #(ws/send-message! :game/action {:gameid gameid :command "continue" :args nil})]
        (with-socket state "runner" :stale send!)
        (with-redefs [ws/unacked-expiry-ms 0]
          (is (= ["continue"] (:delivered (with-socket state "runner" :stale send!)))))))))

(deftest a-late-ack-lets-the-next-send-through
  (testing "the previous ack arrives while the next send is waiting on it: that send goes out, it is not refused"
    (with-rezzed-approach
      (with-socket state "runner" :stale
        #(ws/send-message! :game/action {:gameid gameid :command "continue" :args nil}))
      (future (Thread/sleep 150) (push-wire! state "runner"))
      (let [{:keys [value delivered]} (with-socket state "runner" :now
                                        #(ws/send-message! :game/action {:gameid gameid :command "credit" :args nil}))]
        (is (= ["credit"] delivered))
        (is (= :confirmed value))))))

(deftest an-error-during-the-ack-wait-releases-the-gate
  (testing "round 1 (both seats; one reproduced): the error frame lands while the sender is still waiting, which is the normal order. Clearing then and arming after the wait blocked the next action for the full expiry"
    (with-rezzed-approach
      (let [send! #(ws/send-message! :game/action {:gameid gameid :command "continue" :args nil})]
        (is (= :unconfirmed (:value (with-socket state "runner" [:error 50] send!))))
        (reset! ai-state/client-state (wire-state state "runner"))   ; the resync lands, rolled back
        (is (not (ws/ack-pending?)))
        (is (= ["continue"] (:delivered (with-socket state "runner" :now send!)))
            "the rolled-back action is owed again, and sent")))))

(deftest a-pending-ack-is-not-a-stuck-loop
  (testing "round 1 (both seats; one reproduced): refused ticks reported :action-taken, and five of them on one board ended the persistent monitor as :stuck (a handler sending without progress) about 9s in, before the pending ack could expire"
    (with-rezzed-approach
      (with-redefs [ws/ack-wait-ms 100
                    ws/unacked-expiry-ms 60000]
        (let [{:keys [value delivered]}
              (with-socket state "runner" :lost
                #(let [r (atom nil)]
                   (with-out-str (reset! r (runs/auto-continue-loop! :timeout-ms 2000 :persistent true)))
                   @r))]
          (is (empty? delivered) "precondition: the one send was lost")
          (is (not= :stuck (:status value))
              (str "an unacknowledged send is a wait, not a stuck handler. got " (select-keys value [:status :iterations]))))))))

(deftest a-boardless-send-does-not-arm-the-gate
  (testing "no board, no :aid to compare: the send cannot be confirmed, but it must not block the next one"
    (do-game
      (new-game {:corp {:hand ["Hedge Fund"]}})
      (reset! ai-state/client-state {:side "corp" :gameid gameid})
      (let [send! #(ws/send-message! :game/action {:gameid gameid :command "credit" :args nil})]
        (is (= :unconfirmed (:value (with-socket state "corp" :stale send!))))
        (is (not (ws/ack-pending?)))
        (is (= ["credit"] (:delivered (with-socket state "corp" :stale send!))))))))
