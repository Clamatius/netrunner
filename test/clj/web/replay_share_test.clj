(ns web.replay-share-test
  "#89 (AI-player fork): shared replays were unviewable because the only route
   serving replay DATA (/profile/history/full/:gameid) sits under /profile's
   ::auth, which 401s a logged-out viewer before stats/fetch-replay — whose
   replay-shared / bug-reported branches exist precisely for that viewer — ever
   runs. The fix is a public twin, /replay-data/:gameid, outside ::auth.

   Two layers are pinned here, because either alone would let the bug ship:
     1. the HANDLER's semantics for an anonymous request (no :user) — shared
        and bug-reported pass, private is 401, a logged-in owner still passes;
     2. the ROUTE: through the real reitit router, an anonymous GET of
        /replay-data/<shared> is 200, while /profile/history/full/<shared> is
        still 401 (the original symptom, kept as the contrast so the public
        route cannot be quietly folded back under ::auth)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [monger.collection :as mc]
   [reitit.ring :as ring]
   [web.api :as api]
   [web.stats :as stats]))

(def ^:private shared-replay
  {:corp {:player {:username "alice"}}
   :runner {:player {:username "bob"}}
   :replay "{\"history\":[]}"
   :replay-shared true})

(def ^:private private-replay
  (assoc shared-replay :replay-shared false))

(def ^:private bug-reported-replay
  (assoc private-replay :bug-reported true))

(defn- anon-request [gameid]
  {:system/db :mock-db :path-params {:gameid gameid}})

(deftest fetch-replay-anonymous-viewer
  (testing "a shared replay is served to a request with NO user"
    (with-redefs [mc/find-one-as-map (fn [& _] shared-replay)]
      (is (= 200 (:status (stats/fetch-replay (anon-request "g1")))))))
  (testing "a bug-reported replay is served to a request with NO user"
    (with-redefs [mc/find-one-as-map (fn [& _] bug-reported-replay)]
      (is (= 200 (:status (stats/fetch-replay (anon-request "g1")))))))
  (testing "a private replay is still 401 for an anonymous viewer"
    (with-redefs [mc/find-one-as-map (fn [& _] private-replay)]
      (is (= 401 (:status (stats/fetch-replay (anon-request "g1")))))))
  (testing "a private replay is still served to a logged-in player of that game"
    (with-redefs [mc/find-one-as-map (fn [& _] private-replay)]
      (is (= 200 (:status (stats/fetch-replay (assoc (anon-request "g1")
                                                      :user {:username "bob" :_id "bob-id"}))))))))

(deftest replay-data-route-is-public-and-profile-route-is-not
  (let [handler (ring/ring-handler (api/api-routes))]
    (testing "GET /replay-data/<shared> with no session reaches fetch-replay and is 200"
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
