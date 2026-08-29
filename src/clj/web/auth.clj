(ns web.auth
  (:require
   [buddy.sign.jwt :as jwt]
   [cljc.java-time.instant :as inst]
   [cljc.java-time.temporal.chrono-unit :as chrono]
   [clojure.string :as str]
   [crypto.password.bcrypt :as password]
   [jinteki.i18n :as i18n]
   [jinteki.settings :as settings]
   [jinteki.utils :refer [select-non-nil-keys]]
   [monger.collection :as mc]
   [monger.operators :refer :all]
   [monger.result :refer [acknowledged?]]
   [postal.core :as mail]
   [ring.util.response :refer [redirect]]
   [web.analytics :refer [update-analytics]]
   [web.app-state :as app-state]
   [web.mongodb :refer [->object-id find-one-as-map-case-insensitive]]
   [web.user :refer [active-user? create-user user-keys valid-username?]]
   [web.utils :refer [md5 response]]
   [web.versions :refer [banned-msg]])
  (:import
   java.security.SecureRandom))

(defn create-token [{:keys [expiration secret]}
                    {:keys [_id emailhash]}]
  (let [claims {:_id _id
                :emailhash emailhash
                :exp (inst/plus (inst/now) expiration chrono/days)}]
    (jwt/sign claims secret {:alg :hs512})))

(defn unsign-token [{:keys [secret]} token]
  (try (jwt/unsign token secret {:alg :hs512})
       (catch Exception _ (prn "Received invalid cookie " token))))

(defn wrap-authentication-required [handler]
  (fn [{user :user :as req}]
    (if (active-user? user)
      (handler req)
      (response 401 {:message "Not authorized"}))))

(defn wrap-authorization-required [handler]
  (fn [{user :user :as req}]
    (if (:isadmin user)
      (handler req)
      (response 401 {:message "Not authorized"}))))

(defn wrap-tournament-auth-required [handler]
  (fn [{user :user :as req}]
    (if (:tournament-organizer user)
      (handler req)
      (response 401 {:message "Not authorized"}))))

(def ^:private ai-client-id-prefix "ai-client-")

(def ^:private ai-client-fallback-uris
  "The only endpoints the AI seats need the client-id fallback on: the sente
   websocket handshake and its ajax-post twin, both served at /chsk. Every
   other route is reachable by a browser with a real session, so none of them
   has any business minting a user out of a query parameter."
  #{"/chsk"})

(defn- ai-client-fallback-user
  "TEMP: lets a dev AI seat connect without a login, by turning
   ?client-id=ai-client-<suffix> into the synthetic user AI-<suffix>.

   #157: this used to run on EVERY route, because wrap-user is global
   middleware — so a bare `?client-id=ai-client-runner` forged that user
   against anything behind ::auth (e.g. GET /profile/history/full/<gameid>
   returned the private replay of AI-runner's game). Two gates now stand in
   front of it:

     1. the request must be for the websocket endpoint the seats actually
        use (`ai-client-fallback-uris`). This is the gate that closes the
        reported hole, and it holds on every deployment; and
     2. :web/auth :allow-ai-client-fallback? must be true — an off switch for
        a deployment that wants no synthetic USERS.

   Two things gate 2 is NOT, both found by the #157 review panel:

     - it is not fail-closed. web.system/server-config deep-merges
       resources/prod.edn OVER resources/dev.edn, and dev.edn sets it true, so
       a deployment inherits `true` unless its own prod.edn says otherwise.
       Gate 1 is the one that holds everywhere; do not lean on gate 2 alone.
     - it does not stop an anonymous socket from CLAIMING an identity. sente's
       :user-id-fn (web.ws) has its own, separate client-id fallback and takes
       `?client-id=<anything>` as the uid with :authorized?-fn (constantly
       true) — and :game/action authorizes on that uid, not on :user. Turning
       this flag off suppresses the synthetic :user map and nothing else.
       Tracked as #173; this function cannot reach it.

   Returns nil when either gate is shut."
  [{auth :system/auth :keys [uri params]}]
  (let [client-id (str (:client-id params))]
    (when (and (:allow-ai-client-fallback? auth)
               (contains? ai-client-fallback-uris uri)
               (str/starts-with? client-id ai-client-id-prefix))
      ;; A bare "ai-client-" would mint the shared identity "AI-" for every
      ;; malformed client, and sente would route all their pushes to one uid.
      (when-let [client-suffix (not-empty (subs client-id (count ai-client-id-prefix)))]
        {:username (str "AI-" client-suffix)
         :emailhash "ai"
         :_id (str "ai-player-" client-suffix)
         :special true
         :options {:default-format "standard" :pronouns "none"}
         :stats {:games-started 0 :games-completed 0}}))))

(defn wrap-user [handler]
  (fn [{db :system/db
        auth :system/auth
        :keys [cookies] :as req}]
    ;; Session token comes from Cookie header (set by wrap-session middleware)
    (let [session-token (get-in cookies ["session" :value])
          user (some-> session-token
                       (->> (unsign-token auth))
                       (#(mc/find-one-as-map db "users" {:_id (->object-id (:_id %))
                                                         :emailhash (:emailhash %)}))
                       (select-keys user-keys)
                       (update :_id str))
          ai-user (when-not user (ai-client-fallback-user req))
          final-user (or user ai-user)]
      (if (or (active-user? final-user) ai-user)
        (handler (-> req
                     (assoc :user final-user)
                     (assoc-in [:session :uid] (:username final-user))))
        (handler req)))))

(defn register-handler
  [{db :system/db
    {:keys [username password confirm-password email]} :params}]
  (cond
    (not (valid-username? username))
    (response 401 {:message "Username is not valid"})

    (not= password confirm-password)
    (response 401 {:message "Passwords must match"})

    (find-one-as-map-case-insensitive db "users" {:username username})
    (response 422 {:message "Username taken"})

    (find-one-as-map-case-insensitive db "users" {:email email})
    (response 424 {:message "Email taken"})

    :else
    (let [first-user (not (mc/any? db "users"))
          demo-decks (mc/find-maps db "decks" {:username "__demo__"})]
      (mc/insert db "users" (create-user username password email :isadmin first-user))
      (when (not-empty demo-decks)
        (mc/insert-batch db "decks" (map #(-> %
                                              (dissoc :_id)
                                              (assoc :username username))
                                         demo-decks)))
      (update-analytics :engagement {:new-user 1})
      (response 200 {:message "ok"}))))

(defn find-non-banned-user
  [db query]
  (active-user? (find-one-as-map-case-insensitive db "users" query)))

(defn login-handler
  [{db :system/db
    auth :system/auth
    {:keys [username password]} :params
    remote-address :remote-addr
    headers :headers}]
  ;; note - if the user is behind a proxy, their IP will be the first on in x-forwarded-for
  ;; otherwise it will just be the remote-addr key for the request.
  ;; I'm hoping the nginx reverese proxy plays nice with this...
  (let [client-ip (or (some-> headers (get "x-forwarded-for") (str/split #",") first)
                      (some-> headers (get "x-real-ip"))
                      remote-address)
        user (mc/find-one-as-map db "users" {:username username})]
    (cond
      (and user (password/check password (:password user)) (:banned user))
      (response 403 {:error (or @banned-msg "Account Locked")})
      (and user (password/check password (:password user)) (mc/find-one-as-map db "ip-bans" {:ip-address client-ip}))
      (response 403 {:error (or @banned-msg "Account Locked")})
      (and user (password/check password (:password user)))
      (do (mc/update db "users"
                     {:username username}
                     {"$set" {:last-connection (inst/now)
                              :last-ip-address (str client-ip)}})
          (assoc (response 200 {:message "ok"})
                 :cookies {"session" (merge {:value (create-token auth user)}
                                            (:cookie auth))}))
      :else (response 401 {:error "Invalid login or password"}))))

(defn logout-handler [_]
  (assoc (response 200 {:message "ok"})
         :cookies {"session" {:value 0
                              :max-age -1}}))

(defn check-username-handler
  [{db :system/db
    {:keys [username]} :path-params}]
  (if (find-one-as-map-case-insensitive db "users" {:username username})
    (response 422 {:message "Username taken"})
    (response 200 {:message "OK"})))

(defn check-email-handler
  [{db :system/db
    {:keys [email]} :path-params}]
  (if (find-one-as-map-case-insensitive db "users" {:email email})
    (response 422 {:message "Email taken"})
    (response 200 {:message "OK"})))

(defn email-handler
  [{db :system/db
    {username :username :as user} :user}]
  (if (active-user? user)
    (let [{:keys [email]} (find-one-as-map-case-insensitive db "users" {:username username})]
      (response 200 {:email email}))
    (response 401 {:message "Unauthorized"})))

(defn change-email-handler
  [{db :system/db
    {username :username :as user} :user
    {email :email} :body}]
  (cond
    (not (active-user? user))
    (response 401 {:message "Unauthorized"})

    (mc/find-one-as-map db "users" {:email email})
    (response 400 {:message "Email address already in use"})

    (acknowledged?
      (mc/update db "users"
                 {:username username}
                 {"$set" {:email email
                          :emailhash (md5 email)}}))
    (response 200 {:message "Refresh your browser"})

    :else
    (response 404 {:message "Account not found"})))


(defn update-profile-handler
  [{db :system/db
    {username :username :as user} :user
    body :body}]
  (let [options (select-non-nil-keys body (settings/sync-keys))
        lang (:lang body)]
    (if (active-user? user)
      (if (acknowledged? (mc/update db "users"
                                    {:username username}
                                    {"$set" {:options options}}))
        (do (when (get-in @app-state/app-state [:users username])
              (swap! app-state/app-state assoc-in [:users username :options] options))
            (let [resp {:message "Refresh your browser"}
                  resp (cond-> resp
                         lang
                         (assoc :lang lang
                                :content (i18n/get-content lang)))]
              (response 200 resp)))
        (response 404 {:message "Account not found"}))
      (response 401 {:message "Unauthorized"}))))

(defn generate-secure-token
  [size]
  (let [seed (byte-array size)]
    (.nextBytes (SecureRandom/getInstance "SHA1PRNG") seed)
    seed))

(defn hexadecimalize
  "Converts a byte array to a hex string"
  [a-byte-array]
  (str/lower-case (str/join (map #(format "%02X" %) a-byte-array))))

(defn set-password-reset-code!
  "Generates a password-reset code for the given email address. Updates the user's info in the database with the code,
  and returns the code."
  [db email]
  (let [reset-code (hexadecimalize (generate-secure-token 20))
        reset-expires (inst/plus (inst/now) 1 chrono/hours)]
    (mc/update db "users"
               {:email email}
               {"$set" {:resetPasswordToken reset-code
                        :resetPasswordExpires reset-expires}})
    reset-code))

(defn forgot-password-handler
  [{db :system/db
    email-settings :system/email
    {:keys [email]} :params
    headers         :headers}]
  (if-let [user (find-non-banned-user db {:email email})]
    (let [code (set-password-reset-code! db email)
          msg (mail/send-message
                email-settings
                {:from    (get email-settings :from "support@jinteki.net")
                 :to      email
                 :subject (get email-settings :reset-subject "Jinteki Password Reset")
                 :body    (str "You are receiving this because you (or someone else) have requested the reset of the password for your account " (user :username) ".\n\n"
                               "Please click on the following link, or paste this into your browser to complete the process:\n\n"
                               "http://" (headers "host") "/reset/" code "\n\n"
                               "If you did not request this, please ignore this email and your password will remain unchanged.\n")})]
      (if (zero? (:code msg))
        (response 200 {:message "Email sent"})
        (response 500 {:message (:message msg)})))
    (response 421 {:message "No account with that email address"})))

(defn reset-password-handler
  [{db :system/db
    email-settings :system/email
    {:keys [password confirm]} :params
    {:keys [token]} :path-params}]
  (if-let [{:keys [username email]}
           (find-non-banned-user db {:resetPasswordToken   token
                                     :resetPasswordExpires {"$gt" (inst/now)}})]
    (if (and password (= password confirm))
      (let [hash-pw (password/encrypt password)]
        (mc/update db "users"
                   {:username username}
                   {"$set" {:password             hash-pw
                            :resetPasswordExpires nil
                            :resetPasswordToken   nil}})
        (mail/send-message
          email-settings
          {:from    (get email-settings :from "support@jinteki.net")
           :to      email
           :subject (get email-settings :confirm-reset-subject "Your password has been changed")
           :body    (str "Hello,\n\n"
                         "This is a confirmation that the password for your account "
                         email " has just been changed.\n")})
        (redirect "/"))
      (response 422 {:message "New Password and Confirm Password did not match"}))
    (response 404 {:message "No reset token found"})))
