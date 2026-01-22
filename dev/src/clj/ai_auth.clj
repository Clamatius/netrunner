(ns ai-auth
  "Authentication module for AI players - handles registration, login, and session management"
  (:require [clj-http.client :as http]
            [clj-http.cookies :as cookies]
            [cheshire.core :as json]
            [ai-state :as state]
            [ai-debug :as debug]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def default-base-url "http://localhost:1042")

(defn get-base-url
  "Get base URL from environment or default"
  []
  (or (System/getenv "AI_BASE_URL") default-base-url))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register!
  "Register a new AI player account.
   Returns {:status :success/:error, :message ...}

   Options:
     :username - required
     :password - required
     :email    - optional, defaults to username@ai.local
     :base-url - optional, defaults to localhost"
  [{:keys [username password email base-url]}]
  (let [base (or base-url (get-base-url))
        email (or email (str username "@ai.local"))
        url (str base "/register")
        ;; Use a cookie store to maintain session between GET and POST
        ;; Ring's wrap-anti-forgery sets a cookie on GET that must be sent with POST
        cookie-store (cookies/cookie-store)
        ;; First get CSRF token from main page (also gets anti-forgery cookie)
        csrf-response (try
                        (http/get base {:as :text :cookie-store cookie-store})
                        (catch Exception e
                          {:error (.getMessage e)}))
        csrf-token (when-not (:error csrf-response)
                     (second (re-find #"data-csrf-token=\"(.*?)\""
                                      (str (:body csrf-response)))))]
    (if-not csrf-token
      {:status :error
       :message (str "Failed to get CSRF token: " (:error csrf-response))}
      (try
        (let [response (http/post url
                                  {:form-params {:username username
                                                 :password password
                                                 :confirm-password password
                                                 :email email}
                                   :headers {"X-CSRF-Token" csrf-token}
                                   :cookie-store cookie-store  ; Use same cookie store
                                   :as :json
                                   :throw-exceptions false})]
          (case (:status response)
            200 (do
                  (println "✅ Registered account:" username)
                  {:status :success :message "Account created"})
            401 {:status :error :message (get-in response [:body :message] "Invalid request")}
            422 {:status :error :message (get-in response [:body :message] "Username taken")}
            424 {:status :error :message (get-in response [:body :message] "Email taken")}
            {:status :error
             :message (str "Registration failed: " (:status response) " - " (:body response))}))
        (catch Exception e
          {:status :error :message (str "Registration exception: " (.getMessage e))})))))

;; ============================================================================
;; Login
;; ============================================================================

(defn login!
  "Login and store session token.
   Returns {:status :success/:error, :session-token ...}

   On success, stores the session token in client-state for WebSocket auth.

   Options:
     :username - required
     :password - required
     :base-url - optional, defaults to localhost"
  [{:keys [username password base-url]}]
  (let [base (or base-url (get-base-url))
        url (str base "/login")
        ;; Use a cookie store to maintain session between GET and POST
        cookie-store (cookies/cookie-store)
        ;; First get CSRF token from main page (also gets anti-forgery cookie)
        csrf-response (try
                        (http/get base {:as :text :cookie-store cookie-store})
                        (catch Exception e
                          {:error (.getMessage e)}))
        csrf-token (when-not (:error csrf-response)
                     (second (re-find #"data-csrf-token=\"(.*?)\""
                                      (str (:body csrf-response)))))]
    (if-not csrf-token
      {:status :error
       :message (str "Failed to get CSRF token: " (:error csrf-response))}
      (try
        (let [response (http/post url
                                  {:form-params {:username username
                                                 :password password}
                                   :headers {"X-CSRF-Token" csrf-token}
                                   :cookie-store cookie-store  ; Use same cookie store
                                   :as :json
                                   :throw-exceptions false})
              ;; Extract session cookie from response
              session-cookie (get-in response [:cookies "session" :value])]
          (case (:status response)
            200 (if session-cookie
                  (do
                    (swap! state/client-state assoc
                           :session-token session-cookie
                           :username username)
                    (println "✅ Logged in as:" username)
                    {:status :success
                     :session-token session-cookie
                     :username username})
                  {:status :error :message "Login succeeded but no session cookie returned"})
            401 {:status :error :message (get-in response [:body :error] "Invalid credentials")}
            403 {:status :error :message (get-in response [:body :error] "Account locked")}
            {:status :error
             :message (str "Login failed: " (:status response) " - " (:body response))}))
        (catch Exception e
          {:status :error :message (str "Login exception: " (.getMessage e))})))))

;; ============================================================================
;; Logout
;; ============================================================================

(defn logout!
  "Clear session token and optionally notify server.
   Returns {:status :success/:error}"
  []
  (let [base (get-base-url)
        session-token (:session-token @state/client-state)]
    ;; Clear local state first
    (swap! state/client-state dissoc :session-token :username)
    (println "✅ Logged out (session cleared)")
    {:status :success}))

;; ============================================================================
;; Session Management
;; ============================================================================

(defn authenticated?
  "Check if we have a valid session token"
  []
  (some? (:session-token @state/client-state)))

(defn get-session-token
  "Get current session token, if any"
  []
  (:session-token @state/client-state))

(defn get-username
  "Get current logged-in username, if any"
  []
  (:username @state/client-state))

;; ============================================================================
;; Auto-Registration Flow
;; ============================================================================

(defn ensure-authenticated!
  "Ensure we're logged in, auto-registering if needed.

   Flow:
   1. If already authenticated, return success
   2. Try to login with provided credentials
   3. If login fails with 'invalid credentials', try to register
   4. If registration succeeds, login again

   Options:
     :username - required
     :password - required
     :base-url - optional

   Returns {:status :success/:error, :message ...}"
  [{:keys [username password base-url] :as opts}]
  (cond
    ;; Already authenticated
    (authenticated?)
    (do
      (debug/debug "AUTH" "Already authenticated as" (get-username))
      {:status :success :message "Already authenticated"})

    ;; Need to authenticate
    :else
    (let [login-result (login! opts)]
      (if (= :success (:status login-result))
        login-result
        ;; Login failed - try registering if it was a credential error
        (if (or (re-find #"(?i)invalid" (str (:message login-result)))
                (re-find #"401" (str (:message login-result))))
          (do
            (println "   Login failed, attempting registration...")
            (let [register-result (register! opts)]
              (if (= :success (:status register-result))
                ;; Registration succeeded, now login
                (login! opts)
                ;; Registration failed - check if username exists (already registered elsewhere)
                (if (re-find #"(?i)username taken" (str (:message register-result)))
                  {:status :error
                   :message (str "Username '" username "' exists but password is wrong")}
                  register-result))))
          ;; Some other login error (not credentials)
          login-result)))))

;; ============================================================================
;; REPL Helpers
;; ============================================================================

(comment
  ;; Manual registration
  (register! {:username "test-ai" :password "testpass"})

  ;; Manual login
  (login! {:username "test-ai" :password "testpass"})

  ;; Check auth status
  (authenticated?)
  (get-session-token)

  ;; Auto-register and login
  (ensure-authenticated! {:username "ai-player-1" :password "aipass123"})

  ;; Logout
  (logout!)
  )
