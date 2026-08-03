(ns ai-connection-test
  "Regression tests for the #88 lobby lifecycle: a zombie lobby (kept alive by
   :keep-lobbies-on-disconnect? after an abandoned game) blocked all game
   creation, create-game reported the refusal as success, and leave-game was
   useless from a fresh client because it required a local gameid.

   The server-side shapes being simulated:
   - :lobby/list makes the server push :lobby/state for any lobby our uid is
     seated in (send-lobby-list) — the seat-discovery channel.
   - :lobby/create for an already-seated uid is refused SILENTLY: register-lobby
     returns the map unchanged and try-create-lobby sends nothing back.
   - :lobby/leave unseats us; the last player out closes the lobby."
  (:require [clojure.test :refer :all]
            [test-helpers :refer [mock-client-state with-mock-state]]
            [ai-connection :as conn]
            [ai-websocket-client-v2 :as ws]
            [ai-state :as state]))

(def zombie-id (java.util.UUID/fromString "00000000-0000-0000-0000-00000000dead"))

(defn- fresh-client-state
  "Client state as it looks right after a bounce: connected, no gameid."
  []
  (assoc (mock-client-state) :gameid nil :game-state nil :lobby-state nil))

(defn- mock-lobby-server
  "Stands in for ws/send-message! and simulates the server's lobby lifecycle.
   `seated` atom holds the gameid the server considers our uid seated in — a
   STARTED lobby, since only started lobbies survive a disconnect (#76).
   :refuse-create? mimics the silent seat-block refusal; :sticky-seat? mimics a
   leave that doesn't take; :slow-create? withholds the creation confirmation
   until the next :lobby/list (a confirmation landing after the poll window);
   :reveal-on-create? pushes the seated zombie's state during the create poll
   (as a concurrent :lobby/list reply from another driver would).
   Pushes are applied synchronously to client-state, which the polling loops
   observe just like the real async push."
  [sent seated & {:keys [refuse-create? sticky-seat? slow-create? reveal-on-create?]}]
  (let [pending-create (atom nil)
        push-seated! (fn []
                       (when-let [g @seated]
                         (swap! state/client-state assoc :gameid g :lobby-state {:gameid g :started true})))]
    (fn [event-type data]
      (swap! sent conj {:type event-type :data data})
      (case event-type
        :lobby/list (if-let [g @pending-create]
                      (do (reset! pending-create nil)
                          (reset! seated g)
                          (swap! state/client-state assoc :gameid g :lobby-state {:gameid g :started false}))
                      (push-seated!))
        :lobby/leave (when-not sticky-seat?
                       (reset! seated nil))
        :lobby/create (if refuse-create?
                        (when reveal-on-create? (push-seated!))
                        (let [g (java.util.UUID/randomUUID)]
                          (if slow-create?
                            (reset! pending-create g)
                            (do (reset! seated g)
                                (swap! state/client-state assoc :gameid g :lobby-state {:gameid g :started false})))))
        nil)
      nil)))

(defmacro with-fast-timeouts [& body]
  `(binding [conn/*seat-discovery-timeout-ms* 300
             conn/*create-confirm-timeout-ms* 300]
     ~@body))

;; ============================================================================
;; leave-game!
;; ============================================================================

(deftest test-leave-when-not-seated-anywhere
  (testing "unseated fresh client: discovery finds nothing, leave is a no-op"
    (let [sent (atom []) seated (atom nil)]
      (with-mock-state (fresh-client-state)
        (with-redefs [ws/send-message! (mock-lobby-server sent seated)]
          (with-fast-timeouts
            (let [out (with-out-str (is (true? (conn/leave-game!))))]
              (is (.contains out "Not seated") "must say there was nothing to leave")
              (is (= [:lobby/list] (map :type @sent)) "discovery only — no :lobby/leave sent"))))))))

(deftest test-leave-discovers-zombie-seat-after-bounce
  (testing "fresh client seated in a zombie lobby: discovers the seat via :lobby/list and leaves it"
    (let [sent (atom []) seated (atom zombie-id)]
      (with-mock-state (fresh-client-state)
        (with-redefs [ws/send-message! (mock-lobby-server sent seated)]
          (with-fast-timeouts
            (let [out (with-out-str (is (true? (conn/leave-game!))))]
              (is (.contains out "Left lobby") "must report the leave")
              (let [leave (first (filter #(= :lobby/leave (:type %)) @sent))]
                (is (some? leave) "must send :lobby/leave")
                (is (= zombie-id (:gameid (:data leave))) "must target the discovered lobby"))
              (is (nil? (:gameid @state/client-state)) "client seat cleared")
              (is (nil? @seated) "server seat cleared"))))))))

(deftest test-leave-reports-when-seat-persists
  (testing "a leave the server ignores is reported as failure, not success"
    (let [sent (atom []) seated (atom zombie-id)]
      (with-mock-state (fresh-client-state)
        (with-redefs [ws/send-message! (mock-lobby-server sent seated :sticky-seat? true)]
          (with-fast-timeouts
            (let [out (with-out-str (is (false? (conn/leave-game!))))]
              (is (.contains out "Leave did not take") "must report the persisting seat")
              (is (not (.contains out "✅")) "must not claim success"))))))))

(deftest test-leave-with-known-gameid-skips-discovery
  (testing "a client that knows its gameid leaves directly (old behaviour preserved)"
    (let [sent (atom []) seated (atom zombie-id)]
      (with-mock-state (assoc (fresh-client-state) :gameid zombie-id)
        (with-redefs [ws/send-message! (mock-lobby-server sent seated)]
          (with-fast-timeouts
            (with-out-str (is (true? (conn/leave-game!))))
            (is (= :lobby/leave (:type (first @sent)))
                "first message is the leave itself — no discovery round-trip")))))))

;; ============================================================================
;; create-lobby!
;; ============================================================================

(deftest test-create-success-reports-gameid
  (testing "confirmed creation reports the new gameid"
    (let [sent (atom []) seated (atom nil)]
      (with-mock-state (fresh-client-state)
        (with-redefs [ws/send-message! (mock-lobby-server sent seated)]
          (with-fast-timeouts
            (let [out (with-out-str (is (true? (conn/create-lobby! {:title "T"}))))]
              (is (.contains out "✅ Lobby created:") "must confirm with the gameid")
              (is (some? (:gameid @state/client-state))))))))))

(deftest test-create-silent-refusal-is-diagnosed
  (testing "server silence + a discovered seat = the real reason is printed (#88 defect 1)"
    (let [sent (atom []) seated (atom zombie-id)]
      (with-mock-state (fresh-client-state)
        (with-redefs [ws/send-message! (mock-lobby-server sent seated :refuse-create? true)]
          (with-fast-timeouts
            (let [out (with-out-str (is (false? (conn/create-lobby! {:title "T"}))))]
              (is (.contains out "already seated in lobby") "must name the actual cause")
              (is (.contains out (str zombie-id)) "must name the blocking lobby")
              (is (.contains out "leave-game") "must point at the exit")
              (is (not (.contains out "✅")) "must not claim success"))))))))

(deftest test-create-total-silence-is-not-success
  (testing "no confirmation and no seat found: reported as failure, not success"
    (let [sent (atom []) seated (atom nil)]
      (with-mock-state (fresh-client-state)
        (with-redefs [ws/send-message! (mock-lobby-server sent seated :refuse-create? true)]
          (with-fast-timeouts
            (let [out (with-out-str (is (false? (conn/create-lobby! {:title "T"}))))]
              (is (.contains out "No confirmation from server"))
              (is (not (.contains out "✅"))))))))))

(deftest test-create-refuses-early-when-locally-seated
  (testing "a client that already knows it is seated doesn't even send the doomed create"
    (let [sent (atom []) seated (atom zombie-id)]
      (with-mock-state (assoc (fresh-client-state) :gameid zombie-id)
        (with-redefs [ws/send-message! (mock-lobby-server sent seated)]
          (with-fast-timeouts
            (let [out (with-out-str (is (false? (conn/create-lobby! {:title "T"}))))]
              (is (.contains out "Already seated in lobby"))
              (is (empty? @sent) "no message sent — refusal is known locally"))))))))

;; ============================================================================
;; Review catches (panel round on the first cut of this fix)
;; ============================================================================

(deftest test-create-proceeds-on-lobby-gone-state
  (testing "post-#93 GAME-GONE state (stale :gameid retained) must not fabricate a refusal (review catch)"
    ;; The server closed our lobby; the GAME-GONE verdict keeps :gameid for
    ;; reporting. The server would ACCEPT a create — the early refusal must
    ;; not fire, and creation must go through.
    (let [sent (atom []) seated (atom nil)]
      (with-mock-state (assoc (mock-client-state) :lobby-gone? true :lobby-state nil)
        (with-redefs [ws/send-message! (mock-lobby-server sent seated)]
          (with-fast-timeouts
            (let [out (with-out-str (is (true? (conn/create-lobby! {:title "T"}))))]
              (is (not (.contains out "Already seated")) "must not claim the server would refuse")
              (is (some #(= :lobby/create (:type %)) @sent) "create must actually be sent")
              (is (.contains out "✅ Lobby created:")))))))))

(deftest test-create-not-fooled-by-zombie-reveal-during-poll
  (testing "a zombie seat revealed during the confirm poll is not our created lobby (review catch)"
    ;; A concurrent :lobby/list reply can push the STARTED zombie's state into
    ;; the client mid-poll. :started is the discriminator — a fresh lobby is
    ;; never started; without the check this printed '✅ Lobby created: <zombie>'.
    (let [sent (atom []) seated (atom zombie-id)]
      (with-mock-state (fresh-client-state)
        (with-redefs [ws/send-message! (mock-lobby-server sent seated
                                                          :refuse-create? true
                                                          :reveal-on-create? true)]
          (with-fast-timeouts
            (let [out (with-out-str (is (false? (conn/create-lobby! {:title "T"}))))]
              (is (not (.contains out "Lobby created")) "started lobby must not pass as our creation")
              (is (.contains out "already seated in lobby") "must diagnose the seat-block")
              (is (.contains out (str zombie-id))))))))))

(deftest test-create-late-confirmation-is-success-not-refusal
  (testing "a confirmation landing during the refusal probe is success, not a refusal naming the fresh lobby (review catch)"
    ;; Without the re-check, a slow create was reported as '❌ Create refused:
    ;; ... seated in lobby <THE FRESH LOBBY>' with advice to leave-game it —
    ;; failure reported on success, plus destructive advice.
    (let [sent (atom []) seated (atom nil)]
      (with-mock-state (fresh-client-state)
        (with-redefs [ws/send-message! (mock-lobby-server sent seated :slow-create? true)]
          (with-fast-timeouts
            (let [out (with-out-str (is (true? (conn/create-lobby! {:title "T"}))))]
              (is (.contains out "✅ Lobby created:"))
              (is (not (.contains out "Create refused")) "must not report the fresh lobby as a blocker"))))))))

(deftest test-leave-on-gone-lobby-narrates-honestly
  (testing "leaving a lobby the server already closed says so instead of claiming a leave happened (review catch)"
    (let [sent (atom []) seated (atom nil)]
      (with-mock-state (assoc (mock-client-state) :lobby-gone? true :lobby-state nil)
        (with-redefs [ws/send-message! (mock-lobby-server sent seated)]
          (with-fast-timeouts
            (let [out (with-out-str (is (true? (conn/leave-game!))))]
              (is (.contains out "already closed server-side") "must narrate the no-op truthfully")
              (is (not (.contains out "✅ Left lobby")) "must not claim an action that didn't happen")
              (is (some #(= :lobby/leave (:type %)) @sent)
                  "leave is still sent — the GAME-GONE verdict can be false"))))))))
