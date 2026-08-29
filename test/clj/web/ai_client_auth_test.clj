(ns web.ai-client-auth-test
  "#157 (AI-player fork): `auth/wrap-user` carries a dev fallback that turns a
   bare `?client-id=ai-client-<name>` query parameter into the synthetic user
   `AI-<name>`, with no cookie. wrap-user is GLOBAL middleware, so that ran on
   every route — and `wrap-authentication-required` accepts the forged user.
   The reported exposure:

     GET /profile/history/full/<private-gameid>?client-id=ai-client-runner
       → 200, the private replay of AI-runner's game

   and the same shape anywhere behind ::auth that checks ownership by username.

   The fallback now has two gates: the request must be for /chsk (the sente
   websocket the seats actually connect to), and :web/auth
   :allow-ai-client-fallback? must be true. The URI gate is the load-bearing
   one — the flag is an opt-OUT, since web.system/server-config deep-merges
   prod.edn over dev.edn and dev.edn sets it true. Both gates are pinned here
   in both directions: a gate that never opens would break every AI seat just
   as silently as one that never closes.

   Out of scope here, and filed as #173: sente's own :user-id-fn accepts a raw
   ?client-id as the socket uid regardless of either gate, and :game/action
   authorizes on that uid. Neither gate below reaches it."
  (:require
   [clojure.test :refer [deftest is testing]]
   [monger.collection :as mc]
   [reitit.core :as r]
   [reitit.ring :as ring]
   [web.api :as api]
   [web.auth :as auth]))

(def ^:private dev-system
  "A system with the fallback switched on, as resources/dev.edn has it."
  {:web/auth {:allow-ai-client-fallback? true}})

(def ^:private no-flag-system
  "A system map with the flag missing. NB no real deployment reaches this by
   doing nothing: dev.edn is the base config and sets the flag true, so a
   deployment only lands here by overriding the key to false in its prod.edn."
  {:web/auth {:expiration 60 :secret "s"}})

(def ^:private private-replay
  "A replay owned by AI-runner and shared with nobody."
  {:corp {:player {:username "alice"}}
   :runner {:player {:username "AI-runner"}}
   :replay "{\"history\":[]}"
   :replay-shared false})

(defn- user-seen-by-handler
  "Runs `req` through the REAL global middleware stack (wrap-session,
   wrap-params, wrap-user, ...) built from `system`, and returns the :user the
   downstream handler was given — ::none if the handler never ran."
  [system req]
  (let [seen (atom ::none)
        app (ring/ring-handler
              (ring/router ["/{*path}" {:get (fn [r] (reset! seen (:user r)) {:status 200 :body ""})}])
              nil
              (api/make-middleware system))]
    (app (merge {:request-method :get :headers {}} req))
    @seen))

(deftest forged-client-id-cannot-reach-a-route-behind-auth
  ;; The bug as reported: full app stack, fallback enabled exactly as dev.edn
  ;; enables it, no cookie — just the query parameter.
  (let [app (api/make-app dev-system)]
    (testing "GET /profile/history/full/<private>?client-id=ai-client-runner is 401, not the owner's replay"
      (with-redefs [mc/find-one-as-map (fn [& _] private-replay)]
        (let [resp (app {:request-method :get
                         :uri "/profile/history/full/g1"
                         :query-string "client-id=ai-client-runner"
                         :headers {}})]
          (is (= 401 (:status resp))
              (str "a query parameter must not authenticate anyone off /chsk, got: "
                   (select-keys resp [:status :body]))))))))

(deftest fallback-is-confined-to-the-websocket-endpoint
  (testing "/chsk still mints the synthetic user — this is how the AI seats connect"
    (is (= "AI-runner"
           (:username (user-seen-by-handler dev-system
                                            {:uri "/chsk"
                                             :query-string "client-id=ai-client-runner"})))))
  (testing "the same client-id on any other route mints nobody"
    (doseq [uri ["/profile/history/full/g1" "/replay-data/g1" "/data/decks" "/"]]
      (is (nil? (user-seen-by-handler dev-system
                                      {:uri uri :query-string "client-id=ai-client-runner"}))
          (str "wrap-user minted a user on " uri)))))

(deftest fallback-is-off-when-the-flag-is-not-set
  (testing "without :allow-ai-client-fallback? even /chsk mints nobody"
    (is (nil? (user-seen-by-handler no-flag-system
                                    {:uri "/chsk"
                                     :query-string "client-id=ai-client-runner"}))))
  (testing "and with no :web/auth in the system at all"
    (is (nil? (user-seen-by-handler {} {:uri "/chsk"
                                        :query-string "client-id=ai-client-runner"})))))

(deftest the-gate-opens-on-chsk-and-only-chsk
  ;; Guest panel, two rounds. Sol: the gate and the router hold the same string
  ;; in two places, so a rename leaves the gate opening on a path nothing
  ;; serves. Terra: reading the set back and only checking each entry EXISTS
  ;; pins nothing — widen it to #{"/chsk" "/profile/history/full/g2"} and every
  ;; assertion here still passes while the original hole reopens. So state the
  ;; policy literally, then check the route behind it is the real websocket.
  (testing "the allow-set is exactly /chsk — widening it is the #157 regression"
    (is (= #{"/chsk"} @#'auth/ai-client-fallback-uris)))
  (testing "and /chsk really is served, by both the handshake GET and the ajax POST"
    (let [m (r/match-by-path (api/api-routes) "/chsk")]
      (is (some? m) "api-routes serves nothing at /chsk")
      (doseq [method [:get :post]]
        (is (contains? (:data m) method)
            (str "/chsk has no " method " endpoint — AI seats connect over both"))))))

(deftest ordinary-requests-are-untouched
  (testing "no cookie and no client-id leaves :user nil, fallback on or off"
    (is (nil? (user-seen-by-handler dev-system {:uri "/chsk"})))
    (is (nil? (user-seen-by-handler dev-system {:uri "/data/cards"}))))
  (testing "a client-id that is not an ai-client-* one mints nobody on /chsk"
    (is (nil? (user-seen-by-handler dev-system
                                    {:uri "/chsk" :query-string "client-id=browser-abc123"}))))
  (testing "a bare ai-client- with no suffix mints nobody — it used to mint the shared identity AI- (guest panel, both seats)"
    (is (nil? (user-seen-by-handler dev-system
                                    {:uri "/chsk" :query-string "client-id=ai-client-"})))))
