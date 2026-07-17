(ns web.lobby-disconnect-test
  "Regression tests for #76: a websocket close must not destroy a started game.

   Reproduced live against the dev server (game 8679a498, 2026-07-16): stopping both
   AI clients (what `make resume` / ai-bounce.sh does first) took the lobby from
   healthy-with-both-players to LOBBY-COUNT 0. `handle-leave-lobby` dissocs the lobby
   once the last *player* leaves, and a socket close is routed straight into it by
   `:chsk/uidport-close`. The game is then unrecoverable: :game/resync and :lobby/join
   both silently no-op on a nil lobby, so the seat times out forever.

   The fix is a dev-only flag (`:keep-lobbies-on-disconnect?` in resources/dev.edn):
   for a *started* lobby, a dropped socket leaves membership untouched. Because the
   sente uid is the stable username, the player stays `in-lobby?` and a reconnect can
   resync immediately. Explicit leaves (:lobby/leave, concede, save-replay teardown)
   are unaffected and still close the lobby, so stats/replays still flush."
  (:require
   [cljc.java-time.instant :as inst]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [web.app-state :as app-state]
   [web.game]
   [web.lobby :as lobby]
   [web.ws :as ws]))

(def ^:private gameid "test-game-76")

(defn- started-lobby []
  {:gameid gameid
   :started true
   :players [{:uid "ai-corp" :side "Corp" :user {:username "ai-corp"}}
             {:uid "ai-runner" :side "Runner" :user {:username "ai-runner"}}]
   :original-players [{:uid "ai-corp" :side "Corp" :user {:username "ai-corp"}}
                      {:uid "ai-runner" :side "Runner" :user {:username "ai-runner"}}]
   :spectators [{:uid "umpire" :user {:username "umpire"}}]})

(defn- reset-flag-fixture [f]
  (let [original @lobby/keep-started-lobbies-on-disconnect?]
    (try (f)
         (finally (reset! lobby/keep-started-lobbies-on-disconnect? original)))))

(use-fixtures :each reset-flag-fixture)

(deftest retain-lobby-on-disconnect?-honours-flag-started-and-player
  (testing "flag off: never retain (upstream/prod behaviour is preserved)"
    (reset! lobby/keep-started-lobbies-on-disconnect? false)
    (is (false? (lobby/retain-lobby-on-disconnect? "ai-runner" (started-lobby)))
        "with the flag off a disconnect must behave exactly as upstream does")
    (is (false? (lobby/retain-lobby-on-disconnect? "ai-runner" nil))))

  (testing "flag on: retain a seated player in a started lobby"
    (reset! lobby/keep-started-lobbies-on-disconnect? true)
    (is (true? (lobby/retain-lobby-on-disconnect? "ai-runner" (started-lobby)))
        "a dropped socket must not unseat a player mid-game")
    (is (false? (lobby/retain-lobby-on-disconnect? "ai-runner" (assoc (started-lobby) :started false)))
        "an unstarted lobby is just a waiting room - normal leave semantics apply")
    (is (false? (lobby/retain-lobby-on-disconnect? "ai-runner" nil))
        "no lobby, nothing to retain"))

  (testing "flag on: spectators are NOT retained"
    ;; handle-leave-lobby only counts *players* when deciding to keep the lobby, so
    ;; retaining a spectator holds nothing open and leaks a stale :spectators entry.
    (reset! lobby/keep-started-lobbies-on-disconnect? true)
    (is (false? (lobby/retain-lobby-on-disconnect? "umpire" (started-lobby)))
        "a spectator dropping must leave as usual - retaining it only leaks stale entries")
    (is (false? (lobby/retain-lobby-on-disconnect? "nobody" (started-lobby)))
        "an unknown uid is not a player")))

(deftest last-player-disconnect-destroys-started-lobby
  (testing "documents the #76 mechanism: last player out dissocs the whole game"
    (let [lobbies {gameid (started-lobby)}
          after-corp (lobby/handle-leave-lobby lobbies "ai-corp" nil)
          after-both (lobby/handle-leave-lobby after-corp "ai-runner" nil)]
      (is (some? (get after-corp gameid))
          "one seat dropping is survivable - the other player holds the lobby open")
      (is (nil? (get after-both gameid))
          "but the second disconnect destroys the game: this is what make resume does"))))

(defn- close-socket!
  "Drives the real :chsk/uidport-close handler and waits for its lobby-thread future.
   leave-lobby! is stubbed so we can observe whether the handler decided to unseat,
   without needing a mongo db for the close-lobby! stats flush."
  [uid left]
  (with-redefs [lobby/leave-lobby! (fn [_db _user uid* _reply lobby]
                                     (swap! left conj uid*)
                                     (swap! app-state/app-state update :lobbies
                                            #(lobby/handle-leave-lobby % uid* nil))
                                     (app-state/get-lobby (:gameid lobby)))
                web.game/handle-message-and-send-diffs! (fn [& _] nil)
                lobby/broadcast-lobby-list (fn [& _] nil)]
    @(ws/-msg-handler {:id :chsk/uidport-close
                       :uid uid
                       :timestamp (inst/now)
                       :ring-req {:system/db nil :user {:username uid}}})))

(deftest uidport-close-retains-players-when-flag-on
  (testing "the real handler keeps both seats when both sockets drop (the make resume case)"
    (reset! lobby/keep-started-lobbies-on-disconnect? true)
    (reset! app-state/app-state {:lobbies {gameid (started-lobby)} :lobby-updates {} :users {}})
    (let [left (atom #{})]
      (close-socket! "ai-corp" left)
      (close-socket! "ai-runner" left)
      (is (empty? @left)
          "the handler must not route a player's socket close into leave-lobby!")
      (let [lobby (app-state/get-lobby gameid)]
        (is (some? lobby)
            "the lobby must survive both sockets closing - else resync can never recover it")
        (is (= #{"ai-corp" "ai-runner"} (set (map :uid (:players lobby))))
            "both seats stay seated, so a reconnect can resync immediately")
        (is (some? (lobby/in-lobby? "ai-runner" lobby))
            "in-lobby? is the gate :game/resync checks - it must still pass after a drop")))))

(deftest uidport-close-still-leaves-when-flag-off
  (testing "flag off: the handler behaves exactly as upstream - last player out kills it"
    (reset! lobby/keep-started-lobbies-on-disconnect? false)
    (reset! app-state/app-state {:lobbies {gameid (assoc (started-lobby) :state (atom {}))}
                                 :lobby-updates {} :users {}})
    (let [left (atom #{})]
      (close-socket! "ai-corp" left)
      (close-socket! "ai-runner" left)
      (is (= #{"ai-corp" "ai-runner"} @left)
          "with the flag off every socket close must still go through leave-lobby!")
      (is (nil? (app-state/get-lobby gameid))
          "and the game is still destroyed - upstream behaviour is untouched"))))

(deftest uidport-close-does-not-retain-spectators
  (testing "flag on: a spectator's socket close still leaves, so :spectators cannot go stale"
    (reset! lobby/keep-started-lobbies-on-disconnect? true)
    (reset! app-state/app-state {:lobbies {gameid (assoc (started-lobby) :state (atom {}))}
                                 :lobby-updates {} :users {}})
    (let [left (atom #{})]
      (close-socket! "umpire" left)
      (is (= #{"umpire"} @left)
          "a spectator is not a seat to hold - it must be removed on disconnect")
      (let [lobby (app-state/get-lobby gameid)]
        (is (some? lobby) "the players still hold the lobby open")
        (is (empty? (:spectators lobby))
            "the disconnected spectator must not linger in :spectators")))))
