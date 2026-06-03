(ns ai-websocket-error-recovery-test
  "Regression tests for :game/error recovery in ai_websocket_client_v2.

   When the server catches an exception processing a command it rolls its
   state back and sends [:game/error] (web/game.clj). The client must not
   silently keep its now-divergent optimistic state — it must resync so the
   autonomous loop decides against authoritative ground truth."
  (:require [clojure.test :refer :all]
            [test-helpers :refer [mock-client-state with-mock-state mock-websocket-send!]]
            [ai-websocket-client-v2 :as ws]
            [ai-state :as state]))

(deftest test-game-error-triggers-resync
  (testing "receiving :game/error requests a full resync for the current game"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "corp")
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [gameid (:gameid @state/client-state)]
            (ws/handle-message {:type :game/error :data nil})
            (is (= 1 (count @sent)) "exactly one message sent on error")
            (let [{:keys [type data]} (first @sent)]
              (is (= :game/resync type) "must request a resync")
              (is (= gameid (:gameid data)) "resync must target the current game"))))))))

(deftest test-game-error-clears-stale-state
  (testing "resync path clears cached game-state but preserves gameid"
    (let [sent (atom [])]
      (with-mock-state (mock-client-state :side "corp")
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [gameid (:gameid @state/client-state)]
            (is (some? (:game-state @state/client-state)) "precondition: game-state present")
            (ws/handle-message {:type :game/error :data nil})
            (is (nil? (:game-state @state/client-state)) "stale game-state cleared before resync")
            (is (= gameid (:gameid @state/client-state)) "gameid preserved for resync")))))))

(deftest test-game-error-without-gameid-does-not-send
  (testing "no resync attempted when we have no game to resync"
    (let [sent (atom [])]
      (with-mock-state (assoc (mock-client-state :side "corp") :gameid nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (ws/handle-message {:type :game/error :data nil})
          (is (empty? @sent) "must not send resync with no gameid"))))))
