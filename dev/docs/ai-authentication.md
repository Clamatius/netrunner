# AI Client Authentication

This document covers how AI clients authenticate with the game server, replacing the legacy client-id fallback with proper account-based authentication.

## Overview

AI clients authenticate using the same flow as browser clients:
1. GET page to obtain CSRF token
2. POST `/register` (first run) or `/login` with credentials
3. Extract session JWT from response cookie
4. Pass session cookie in WebSocket connection headers

**Source:** `dev/src/clj/ai_auth.clj`

## Authentication Flow

```
┌─────────────┐     GET /        ┌─────────────┐
│  AI Client  │ ───────────────> │   Server    │
│             │ <─────────────── │             │
│             │   CSRF token     │             │
│             │                  │             │
│             │  POST /login     │             │
│             │ ───────────────> │             │
│             │ <─────────────── │             │
│             │  session=JWT     │             │
│             │                  │             │
│             │  WS /chsk        │             │
│             │  Cookie: session │             │
│             │ ───────────────> │             │
│             │ <═══════════════ │             │
│             │   WebSocket      │             │
└─────────────┘                  └─────────────┘
```

## Key Functions

### `ai-auth/login!`

Authenticates with existing account credentials.

```clojure
(ai-auth/login! {:username "ai-corp"
                 :password "dev-password-corp"
                 :base-url "http://localhost:1042"})
;; => {:status :success, :username "ai-corp"}
```

**Implementation notes:**
- Uses `clj-http.cookies/cookie-store` to maintain session between CSRF GET and login POST
- Extracts `session` cookie from response and stores in `ai-state/client-state`
- Returns `{:status :error, :message "..."}` on failure

### `ai-auth/register!`

Creates a new account. Used automatically on first run.

```clojure
(ai-auth/register! {:username "ai-corp"
                    :password "dev-password-corp"
                    :email "ai-corp@ai.local"
                    :base-url "http://localhost:1042"})
;; => {:status :success, :username "ai-corp"}
```

### `ai-auth/ensure-authenticated!`

High-level function that handles the full auth flow:
1. Try login with provided credentials
2. If login fails, attempt registration
3. Store session token on success

```clojure
(ai-auth/ensure-authenticated!
  {:username "ai-player"
   :password "secret"
   :base-url "http://localhost:1042"})
```

### `ai-auth/logout!`

Clears session state. Call before re-authenticating as different user.

```clojure
(ai-auth/logout!)
;; Clears :session-token, :csrf-token, :username from state
```

## WebSocket Connection with Auth

**Source:** `dev/src/clj/ai_websocket_client_v2.clj` `[connect!]`

The WebSocket connection passes the session cookie via HTTP headers:

```clojure
;; In connect! function (simplified)
(let [session-token (:session-token @state/client-state)
      csrf-token (:csrf-token @state/client-state)
      full-url (str ws-url "?client-id=" client-id
                    "&csrf-token=" (URLEncoder/encode csrf-token))
      headers (when session-token
                {"Cookie" (str "session=" session-token)})]
  (ws/connect full-url {:headers headers ...}))
```

**Key insight:** The session cookie goes in HTTP headers (like a browser), NOT as a URL parameter. The server's `wrap-user` middleware extracts it from cookies.

## Server-Side Auth

**Source:** `src/clj/web/auth.clj` `[wrap-user]`

The middleware chain processes authentication:

```clojure
;; wrap-user extracts user from session cookie
(let [session-token (get-in cookies ["session" :value])
      user (-> session-token
               (unsign-token auth)          ; Verify JWT
               (lookup-user db)             ; Find in MongoDB
               (select-keys user-keys))]    ; Return safe subset
  (handler (assoc req :user user)))
```

**Source:** `src/clj/web/ws.clj` `[user-id-fn]`

Sente identifies WebSocket connections by username:

```clojure
:user-id-fn (fn [ring-req]
              (or (-> ring-req :user :username)      ; Authenticated user
                  (-> ring-req :session :uid)        ; Session UID
                  (-> ring-req :params :client-id))) ; Fallback
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `AI_USERNAME` | `ai-{client-name}` | Account username |
| `AI_PASSWORD` | `dev-password-{client-name}` | Account password |
| `AI_BASE_URL` | `http://localhost:1042` | Server base URL |

Set in `dev/start-ai-client-repl.sh`:
```bash
export AI_USERNAME=${AI_USERNAME:-"ai-$CLIENT_NAME"}
export AI_PASSWORD=${AI_PASSWORD:-"dev-password-$CLIENT_NAME"}
```

## Fallback Mode

If `AI_USERNAME` is not set, the client falls back to client-id authentication:

```clojure
;; In ai_client_init.clj
(if (System/getenv "AI_USERNAME")
  (ai-auth/ensure-authenticated! {...})
  (do
    (println "⚠️  No AI_USERNAME set - using client-id auth (fallback mode)")
    (ws/connect! ws-url)))
```

The server's `wrap-user` has a fallback that creates synthetic users for `ai-client-*` prefixed client IDs. This exists for backwards compatibility but should not be relied upon for production use.

## Connecting to Remote Servers

To connect AI clients to a remote server:

```bash
AI_BASE_URL=https://jinteki.net \
AI_USERNAME=your-account \
AI_PASSWORD=your-password \
./dev/send_command corp status
```

**Note:** Remote servers may have rate limiting or other restrictions on automated clients.

## Troubleshooting

### "Login failed" errors
- Check credentials are correct
- Verify server is running: `curl http://localhost:1042`
- Check CSRF token retrieval: server must return HTML with `data-csrf-token`

### "403 Forbidden" on login
- CSRF token mismatch - ensure using same cookie store for GET and POST
- Session cookie not being sent - check `Cookie` header in request

### WebSocket connects but user is anonymous
- Session cookie not in headers - verify `headers` map passed to gniazdo
- JWT expired - re-authenticate before connecting
