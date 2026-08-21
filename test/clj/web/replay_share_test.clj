(ns web.replay-share-test
  "#89 (AI-player fork): shared replays were unviewable because the only route
   serving replay DATA (/profile/history/full/:gameid) sits under /profile's
   ::auth, which 401s a logged-out viewer before stats/fetch-replay — whose
   replay-shared / bug-reported branches exist precisely for that viewer — ever
   runs. The fix is a public twin, /replay-data/:gameid → fetch-shared-replay,
   outside ::auth and WITHOUT fetch-replay's owner clause.

   Three layers are pinned, because any one alone would let a bug ship:
     1. the HANDLER: shared / bug-reported pass with no :user; private is 401
        even when the request carries the owner as :user (no owner clause);
     2. the ROUTE through the real reitit router: anonymous GET
        /replay-data/<shared> is 200 while /profile/history/full/<shared> is
        still 401 (the original symptom, kept as the contrast);
     3. the FULL app stack (make-app, with this fork's wrap-user dev fallback
        that mints a user from ?client-id=ai-client-*): a forged client-id must
        NOT unlock a private replay on the public route (guest panel, CRITICAL
        against the first cut, which reused fetch-replay's owner clause)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [monger.collection :as mc]
   [reitit.ring :as ring]
   [web.api :as api]
   [web.stats :as stats]))

(def ^:private shared-replay
  {:corp {:player {:username "alice"}}
   :runner {:player {:username "AI-runner"}}
   :replay "{\"history\":[]}"
   :replay-shared true})

(def ^:private private-replay
  (assoc shared-replay :replay-shared false))

(def ^:private bug-reported-replay
  (assoc private-replay :bug-reported true))

(defn- anon-request [gameid]
  {:system/db :mock-db :path-params {:gameid gameid}})

(deftest fetch-shared-replay-handler
  (testing "a shared replay is served to a request with NO user"
    (with-redefs [mc/find-one-as-map (fn [& _] shared-replay)]
      (is (= 200 (:status (stats/fetch-shared-replay (anon-request "g1")))))))
  (testing "a bug-reported replay is served to a request with NO user"
    (with-redefs [mc/find-one-as-map (fn [& _] bug-reported-replay)]
      (is (= 200 (:status (stats/fetch-shared-replay (anon-request "g1")))))))
  (testing "a NONEXISTENT gameid is 404, not 401 (guest panel, pass 2): a missing record has falsey share flags, and 401 made the client treat a dead link as a private replay and ask for a login"
    (with-redefs [mc/find-one-as-map (fn [& _] nil)]
      (is (= 404 (:status (stats/fetch-shared-replay (anon-request "no-such-game")))))))
  (testing "a private replay is 401 for an anonymous viewer"
    (with-redefs [mc/find-one-as-map (fn [& _] private-replay)]
      (is (= 401 (:status (stats/fetch-shared-replay (anon-request "g1")))))))
  (testing "a private replay is 401 on the PUBLIC handler even for its owner — no owner clause here (owners use /profile/history/full)"
    (with-redefs [mc/find-one-as-map (fn [& _] private-replay)]
      (is (= 401 (:status (stats/fetch-shared-replay (assoc (anon-request "g1")
                                                             :user {:username "AI-runner" :_id "x"})))))))
  (testing "the owner route (fetch-replay) still serves a private replay to its logged-in player"
    (with-redefs [mc/find-one-as-map (fn [& _] private-replay)]
      (is (= 200 (:status (stats/fetch-replay (assoc (anon-request "g1")
                                                      :user {:username "alice" :_id "alice-id"}))))))))

(deftest replay-data-route-is-public-and-profile-route-is-not
  (let [handler (ring/ring-handler (api/api-routes))]
    (testing "GET /replay-data/<shared> with no session reaches the handler and is 200"
      (with-redefs [mc/find-one-as-map (fn [& _] shared-replay)]
        (let [resp (handler {:request-method :get :uri "/replay-data/g1" :system/db :mock-db})]
          (is (= 200 (:status resp))
              (str "the public route must bypass ::auth, got: " (select-keys resp [:status :body]))))))
    (testing "GET /replay-data/<private> with no session is 401 — public route, private data"
      (with-redefs [mc/find-one-as-map (fn [& _] private-replay)]
        (is (= 401 (:status (handler {:request-method :get :uri "/replay-data/g1" :system/db :mock-db}))))))
    (testing "CONTRAST: GET /profile/history/full/<shared> with no session is still 401 (the #89 symptom)"
      (with-redefs [mc/find-one-as-map (fn [& _] shared-replay)]
        (is (= 401 (:status (handler {:request-method :get :uri "/profile/history/full/g1" :system/db :mock-db})))
            "::auth in front of /profile refuses a logged-out viewer before the handler runs")))))

(deftest forged-ai-client-id-does-not-unlock-a-private-replay
  ;; Full stack: wrap-session / wrap-params / wrap-user etc. from make-app.
  ;; This fork's wrap-user (auth.clj) turns ?client-id=ai-client-runner into
  ;; the synthetic user "AI-runner" with no cookie at all. The first cut of
  ;; this fix reused fetch-replay (owner clause) on the public route, so this
  ;; request returned the private replay of AI-runner's game with 200.
  (let [app (api/make-app {})]
    (testing "anonymous GET /replay-data/<private>?client-id=ai-client-runner is 401, not the replay"
      (with-redefs [mc/find-one-as-map (fn [& _] private-replay)]
        (let [resp (app {:request-method :get :uri "/replay-data/g1"
                         :query-string "client-id=ai-client-runner" :headers {}})]
          (is (= 401 (:status resp))
              (str "a forged AI client-id must not pass as the owner on the public route, got: "
                   (select-keys resp [:status]))))))
    (testing "and the same stack serves a SHARED replay to a plain anonymous request"
      (with-redefs [mc/find-one-as-map (fn [& _] shared-replay)]
        (is (= 200 (:status (app {:request-method :get :uri "/replay-data/g1" :headers {}}))))))))
