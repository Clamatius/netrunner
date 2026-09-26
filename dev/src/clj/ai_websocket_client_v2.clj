(ns ai-websocket-client-v2
  "WebSocket client using gniazdo (JVM WebSocket library)"
  (:require
   [ai-core :as core]
   [ai-debug :as debug]
   [ai-state :as state]
   [ai-hud-utils :as hud]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [differ.core :as differ]
   [gniazdo.core :as ws]
   [jinteki.cards :refer [all-cards]]
   [time-literals.read-write :as time-literals])
  (:import [java.net URLEncoder]
           [org.eclipse.jetty.websocket.client WebSocketClient]
           [org.eclipse.jetty.util.ssl SslContextFactory]))

(declare ensure-connected! send-message!)

;; #114: the deferred auto-end resume lives in ai-basic-actions, which requires
;; THIS namespace — so it can only be reached by late var lookup (same pattern as
;; ai-prompts' maybe-auto-end-turn-after-prompt!). Held in a delay so the lookup
;; happens once, at the first diff, long after both namespaces have loaded.
;; requiring-resolve returns the Var, so calls still see with-redefs in tests.
(def ^:private resume-deferred-auto-end-var
  (delay (requiring-resolve 'ai-basic-actions/resume-deferred-auto-end!)))

(defn- resume-deferred-auto-end-async!
  "Fire the #114 deferred auto-end check off an incoming diff, if one is armed.

   Off-thread deliberately: this runs on the websocket RECEIVE thread, and
   end-turn! sleeps a full standard-delay (1s) before reading state back. Doing
   that inline would stall every message behind it — including the diff that
   confirms the end-turn we just sent."
  []
  (when (:auto-end-deferred @state/client-state)
    (future
      (try
        (@resume-deferred-auto-end-var)
        (catch Throwable t
          (debug/debug "WARN" (str "deferred auto-end resume failed: " t)))))))

;; ============================================================================
;; Action Synchronization
;; ============================================================================
;; Only one :game/action is in flight at a time, and it is not over until the
;; engine has acknowledged it.
;;
;; The acknowledgement is the engine's own action id: game.main/handle-action
;; bumps [side :aid] whenever it processes one of our commands, in the SAME diff
;; that carries the command's effects. When our side's :aid has risen, the wire
;; already shows what our action did. The web UI's lock waits on exactly this
;; (nr.gameboard.state/check-lock?). The cursor is not an ack: any diff moves it,
;; the opponent's included (#245).
;;
;; A send whose ack does not arrive within ack-wait-ms is left PENDING, and the
;; next action waits for that ack before it goes out; if it still has not come,
;; the next action is refused. Otherwise a lagging wire lets a loop re-read a
;; board that does not show its last action and send it again (#245: a second
;; continue closed the Corp's window, the next two passed an encounter unbroken).
;; The pending ack is dropped on :game/error (the server rolled the action back,
;; so it will never be acknowledged), for a different game, and after
;; unacked-expiry-ms, so a lost ack is a bounded stall rather than a wedge.
;; A diff lost to a dead socket does not reach the expiry unnoticed: reconnecting
;; rejoins with a full resync, and its :aid settles the pending ack either way.

(def ^:private action-lock (Object.))

(def ack-wait-ms
  "How long a :game/action send waits for its own ack before reporting :unconfirmed."
  1500)

(def unacked-expiry-ms
  "How long an unacknowledged send keeps blocking later ones."
  10000)

;; {:gameid :side :aid :at} of the last send whose ack has not been seen, or nil.
(defonce ^:private unacked (atom nil))

(defn clear-unacked! [] (reset! unacked nil))

;; ============================================================================
;; Configuration
;; ============================================================================

(defn get-base-url
  "Get the server base URL from AI_BASE_URL env var, default to localhost:1042"
  []
  (or (System/getenv "AI_BASE_URL") "http://localhost:1042"))

(defn get-ws-url
  "Get the WebSocket URL, derived from base URL"
  []
  (let [base (get-base-url)]
    (-> base
        (str/replace #"^https?://" "ws://")
        (str "/chsk"))))

;; ============================================================================
;; Message Handling
;; ============================================================================

(defn parse-message
  "Parse incoming WebSocket message - returns vector of parsed events"
  [msg]
  (try
    (let [data (if (string? msg)
                 ;; The server prints java.time values via time-literals, so read
                 ;; them with its own tag table. Hand-listing tags missed one:
                 ;; #time/date-time (a seated deck's :date — only Constructed
                 ;; lobbies have one) threw, and the whole push was dropped.
                 (edn/read-string {:readers time-literals/tags} msg)
                 msg)]
      (debug/debug "🔍 RAW RECEIVED:" (pr-str data))

      (cond
        ;; Batched events: [[[event1] [event2] ...]]
        ;; Example: [[[:lobby/list []] [:lobby/state]]]
        (and (vector? data)
             (= 1 (count data))
             (vector? (first data))
             (every? vector? (first data)))
        (let [events (first data)]
          (println "📦 BATCH of" (count events) "events")
          (mapv (fn [[type payload]]
                  {:type type :data payload})
                events))

        ;; Single wrapped event: [[:chsk/handshake [...]]]
        (and (vector? data) (= 1 (count data)) (vector? (first data)))
        (let [[type payload] (first data)]
          [{:type type :data payload}])

        ;; Normal message: [:event-type data]
        (vector? data)
        [{:type (first data)
          :data (second data)}]

        ;; Unknown format
        :else
        [{:type :unknown :data data}]))
    (catch Exception e
      (println "❌ Error parsing message:" (.getMessage e))
      (.printStackTrace e)
      nil)))

(defn handle-message
  "Handle parsed message"
  [{:keys [type data] :as msg}]
  (when (not= type :chsk/ws-ping)
    (debug/debug "🔧 HANDLING MESSAGE:" type))

  ;; Record all messages (except pings)
  (when (not= type :chsk/ws-ping)
    (swap! state/client-state update :messages
           (fn [msgs] (conj (vec msgs) (state/sanitize-cached-message msg)))))

  (case type
    :chsk/handshake
    (do
      (println "✅ Connected! UID:" (first data))
      (swap! state/client-state assoc :uid (first data) :connected true))

    :game/start
    (let [state (if (string? data)
                  (json/parse-string data true)
                  data)]
      (println "\n🎮 GAME STARTING!")
      (println "  GameID:" (:gameid state))
      ;; Initialize this client's section in shared HUD
      (hud/update-hud-section "Game Status"
                         (str "Game starting...\nGameID: " (:gameid state)))
      (state/set-full-state! state)
      (swap! state/client-state assoc :gameid (state/normalize-gameid (:gameid state)))
      ;; Auto-start replay recording for every game
      (state/start-replay-recording!)
      (state/record-initial-state! state (:gameid state))
      ;; Bump cursor for wait synchronization
      (state/bump-cursor!))

    :game/diff
    (let [diff-data (if (string? data)
                      (json/parse-string data true)
                      data)
          {:keys [gameid diff]} diff-data
          client-gameid (:gameid @state/client-state)]
      ;; FIX: Compare as strings since JSON parses UUIDs as strings
      (if (= (str gameid) (str client-gameid))
        (do
          (println "\n🔄 GAME/DIFF received")
          (println "   GameID:" gameid)
          (println "   Diff type:" (type diff))
          (println "   Diff keys (if map):" (when (map? diff) (keys diff)))
          (println "   Diff sample:" (pr-str (if (coll? diff)
                                                (take 5 diff)
                                                diff)))
          ;; #142: every line below is SUCCESS bookkeeping, and none of it is
          ;; true of a diff we could not apply. When the cache has been cleared
          ;; (a resync in flight) `update-game-state!` ignores the diff and says
          ;; so — announcing "✓ Diff applied successfully" over that, retracting
          ;; the stale/lobby-gone verdicts on no evidence, and bumping the wait
          ;; cursor so a parked seat wakes for a no-op, are four separate lies
          ;; about a state that did not change. (Guest review, confirmed.)
          ;; Recorded UNCONDITIONALLY, outside the branch: the replay recorder
          ;; captures the SERVER's diff stream, which is continuous whatever our
          ;; local cache managed to do with it. Dropping one because we could not
          ;; apply it corrupts the recording — `:game/resync` writes no checkpoint
          ;; (only `:game/start` calls record-initial-state!), so the next diff
          ;; would replay against a predecessor that never existed. (2nd-pass
          ;; guest review, MAJOR — and a regression this fix introduced.)
          (state/record-diff! diff)
          (if (state/update-game-state! diff)
            (do
              ;; Clear lobby-state once game has started (receiving diffs means game
              ;; is active — which also retracts any stale lobby-gone verdict, #93)
              (swap! state/client-state dissoc :lobby-state :diff-mismatch :lobby-gone?)
              ;; Track last successful diff time
              (swap! state/client-state assoc :last-diff-time (System/currentTimeMillis))
              ;; Announce newly revealed cards in Archives
              (hud/announce-revealed-archives diff)
              ;; Auto-update game log HUD
              (hud/write-game-log-to-hud 30)
              ;; Bump cursor for wait synchronization
              (state/bump-cursor!)
              ;; #114: if our turn is orphaned behind an opponent-owed decision, this
              ;; is the only event that can tell us it cleared.
              (resume-deferred-auto-end-async!)
              (println "   ✓ Diff applied successfully"))
            (println "   ⏭️  Diff NOT applied — state unchanged (recorded for replay)")))
        ;; Diff doesn't match our game - we might be stale
        (do
          (swap! state/client-state assoc :diff-mismatch true)
          (debug/debug "WARN" (str "Diff dropped - gameid mismatch. Diff: " gameid " Client: " client-gameid)))))

    :game/resync
    (let [state (if (string? data)
                  (json/parse-string data true)
                  data)]
      (println "🔄 Game resync")
      (state/set-full-state! state)
      ;; Bump cursor for wait synchronization
      (state/bump-cursor!)
      ;; #114: the arm lives in the client atom, so it survives a reconnect. A
      ;; resync can be the state update that reveals the block cleared, and if we
      ;; only listened for diffs there might not be another one — the game would
      ;; be waiting on the very end-turn we're sitting on.
      (resume-deferred-auto-end-async!))

    :game/error
    (do
      (println "❌ SERVER ERROR RECEIVED!")
      (println "   Data:" (pr-str data))
      ;; The server caught an exception processing our last command and rolled
      ;; its state back to before that command (see web/game.clj). Our optimistic
      ;; local state may now diverge from the authoritative (rolled-back) state,
      ;; which can strand the autonomous loop grinding on a stale view. Re-fetch
      ;; authoritative state so the next decision is made against ground truth.
      ;; A rolled-back action is never acknowledged; stop waiting for it.
      (clear-unacked!)
      (when-let [gameid (:gameid @state/client-state)]
        (println "   ↻ Requesting resync to recover authoritative state")
        (state/clear-game-state!)
        (send-message! :game/resync {:gameid gameid})))

    :lobby/list
    (do
      (swap! state/client-state assoc :lobby-list data)
      (println "📋 Received" (count data) "game(s)"))

    :lobby/state
    (if data
      (do
        (println "🎮 Lobby state update")
        (when-let [gameid (:gameid data)]
          (swap! state/client-state assoc :gameid (state/normalize-gameid gameid) :lobby-state data)
          ;; Fresh lobby data means the server hosts us again — a stale
          ;; lobby-gone verdict from a previous teardown no longer applies.
          (swap! state/client-state dissoc :lobby-gone?)
          (println "   GameID:" gameid)))
      ;; A BARE [:lobby/state] (no data) is how the server announces it closed
      ;; our lobby (close-lobby! → clear-lobby-state). Dropping it — the old
      ;; behaviour — left the cached game snapshot answering every status query
      ;; for a game that no longer exists (#93: game-over-status said
      ;; IN-PROGRESS forever). Mark the lobby gone and bump the cursor so a
      ;; seat blocked in `wait` wakes up and sees the verdict.
      ;;
      ;; The signal is NOT uniquely teardown (codex review catch): the server
      ;; also sends a bare [:lobby/state] whenever a :lobby/list request finds
      ;; our uid in no lobby (send-lobby-list), which can happen while a game
      ;; we were unseated from is still alive for the opponent. Two defences:
      ;; only react when a game had actually STARTED for us (we hold both a
      ;; gameid and game-state — a waiting-room lobby is not a game), and send
      ;; a :game/resync probe after marking: if the server still hosts us, the
      ;; resync reply (set-full-state!) retracts the verdict; if it doesn't,
      ;; the probe dies silently and GAME-GONE stands.
      (let [{:keys [gameid game-state]} @state/client-state]
        (when (and gameid game-state)
          (println "🏚️  Lobby closed by server — game is gone (probing with resync to confirm)")
          (state/mark-lobby-gone!)
          (state/bump-cursor!)
          (send-message! :game/resync {:gameid gameid}))))

    :lobby/notification
    (println "🔔 Lobby notification:" data)

    ;; The server's explicit refusal channel (e.g. block-game-creation sends
    ;; :lobby_creation-paused). Previously fell through to "Unhandled message
    ;; type" — surface it as what it is.
    :lobby/toast
    (println (str "🔔 Server toast [" (:type data) "]: " (:message data)))

    :chsk/ws-ping
    ;; Respond to ping with pong to keep connection alive
    ;; Echo back ping data if present (some Sente versions use ping IDs)
    (when-let [socket (:socket @state/client-state)]
      (try
        (let [pong-msg (if data
                         (pr-str [[:chsk/ws-pong data]])
                         (pr-str [[:chsk/ws-pong]]))]
          (ws/send-msg socket pong-msg)
          (println "📤 Sent pong" (if data (str "(id: " data ")") "")))
        (catch Exception e
          (println "❌ Failed to send pong:" (.getMessage e)))))

    ;; Default
    (when (not= type :chsk/ws-ping)
      (println "Unhandled message type:" type))))

(defn on-receive
  "Called when WebSocket receives a message"
  [msg & _rest-args]
  (when-let [events (parse-message msg)]
    ;; parse-message now returns a vector of events
    (doseq [event events]
      (handle-message event))))

;; ============================================================================
;; Authentication
;; ============================================================================

(defn get-csrf-token!
  "Get CSRF token from the main page for WebSocket connection"
  []
  (try
    (let [get-res (http/get (get-base-url) {:as :text})]
      (if (:error get-res)
        (do
          (println "❌ Failed to get CSRF token:" (:error get-res))
          nil)
        (let [csrf-token (second (re-find #"data-csrf-token=\"(.*?)\"" (str (:body get-res))))]
          (if csrf-token
            (do
              (swap! state/client-state assoc :csrf-token csrf-token)
              (println "✅ Got CSRF token")
              csrf-token)
            (do
              (println "❌ Could not extract CSRF token from page")
              nil)))))
    (catch Exception e
      (println "❌ CSRF token fetch exception:" (.getMessage e))
      nil)))

;; ============================================================================
;; WebSocket Connection
;; ============================================================================

(defn connect!
  "Connect to WebSocket server.

   Authentication modes:
   1. If :session-token is set in state, use proper cookie-based auth
   2. Otherwise fall back to client-id auth (requires server-side hack)

   The session token is obtained by calling ai-auth/login! before connect."
  [url]
  (println "🔌 Connecting to" url "...")
  (try
    ;; Close old connection if it exists to prevent leaks
    (when-let [old-socket (:socket @state/client-state)]
      (println "🧹 Closing old socket connection...")
      (try
        (ws/close old-socket)
        (catch Exception e
          (println "⚠️  Error closing old socket:" (.getMessage e)))))

    (when-let [old-client (:ws-client @state/client-state)]
      (println "🧹 Stopping old WebSocket client...")
      (try
        (.stop old-client)
        (catch Exception e
          (println "⚠️  Error stopping old client:" (.getMessage e)))))

    ;; Authentication: Match web client behavior
    ;; - client-id and csrf-token go in URL params
    ;; - session cookie goes in Cookie header (like browser sends automatically)
    (let [session-token (:session-token @state/client-state)
          csrf-token (:csrf-token @state/client-state)
          ;; Always generate a client-id (web client does this too)
          existing-id (:client-id @state/client-state)
          client-id (or existing-id (str "ai-client-" (java.util.UUID/randomUUID)))
          _ (swap! state/client-state assoc :client-id client-id)
          ;; Build URL with client-id and csrf-token (like web client)
          full-url (str url "?client-id=" client-id
                        (when csrf-token
                          (str "&csrf-token=" (URLEncoder/encode csrf-token "UTF-8"))))
          ;; Create custom WebSocket client with longer idle timeout (10 minutes instead of 5)
          ws-client (if (.startsWith url "wss://")
                      (WebSocketClient. (SslContextFactory.))
                      (WebSocketClient.))
          _ (doto (.getPolicy ws-client)
              (.setIdleTimeout 600000))
          _ (.start ws-client)
          ;; Build headers - include session cookie if authenticated
          headers (when session-token
                    {"Cookie" (str "session=" session-token)})
          ;; Prepare connection options
          conn-opts (cond-> {:on-receive on-receive
                             :on-connect (fn [& _args]
                                           (println "⏳ WebSocket connected, waiting for handshake..."))
                             :on-close (fn [& args]
                                         (println "❌ Disconnected:" (first args))
                                         (.stop ws-client)
                                         ;; Clear game state to prevent stale reads, but keep gameid for rejoin
                                         (swap! state/client-state assoc
                                                :connected false
                                                :game-state nil
                                                :last-state nil))
                             :on-error (fn [& args]
                                         (println "❌ Error:" (first args)))
                             :client ws-client}
                      headers (assoc :headers headers))
          socket (ws/connect full-url conn-opts)]
      (swap! state/client-state assoc
             :socket socket
             :ws-client ws-client)
      (if session-token
        (println "✨ WebSocket connected with session auth (user:" (:username @state/client-state) ")")
        (println "✨ WebSocket connected with client-id auth (fallback mode)"))
      socket)
    (catch Exception e
      (println "❌ Connection failed:" (.getMessage e))
      nil)))

(defn disconnect!
  "Disconnect from server"
  []
  (when-let [socket (:socket @state/client-state)]
    (ws/close socket)
    (println "👋 Socket disconnected"))

  (when-let [ws-client (:ws-client @state/client-state)]
    (try
      (.stop ws-client)
      (println "👋 WebSocket client stopped")
      (catch Exception e
        (println "⚠️  Error stopping client:" (.getMessage e)))))

  (swap! state/client-state assoc :socket nil :ws-client nil :connected false))

;; ============================================================================
;; Sending Messages
;; ============================================================================

(defn- send-message-impl!
  "Internal: actually send the message."
  [event-type data]
  (when (ensure-connected!)
    (if-let [socket (:socket @state/client-state)]
      (try
        ;; Sente expects double-wrapped messages: [[:event-type data]]
        (let [msg (pr-str [[event-type data]])]
          (ws/send-msg socket msg)
          true)
        (catch Exception e
          (println "❌ Send failed:" (.getMessage e))
          ;; Try one reconnect and retry
          (println "♻️  Attempting reconnect and retry...")
          (when (ensure-connected!)
            (if-let [new-socket (:socket @state/client-state)]
              (try
                (let [msg (pr-str [[event-type data]])]
                  (ws/send-msg new-socket msg)
                  (println "✅ Retry successful")
                  true)
                (catch Exception e2
                  (println "❌ Retry failed:" (.getMessage e2))
                  false))
              false))))
      (do
        (println "❌ Not connected (after ensure-connected!)")
        false))))

(defn- own-aid
  "Our side's action id as the wire shows it, or nil if there is no board."
  []
  (when-let [side (state/my-side-kw)]
    (get-in @state/client-state [:game-state side :aid])))

(defn- acked?
  "Has the wire shown an ack for the action sent when our :aid read `before`?
   A nil `before` (no board at send time) cannot be confirmed."
  [before]
  (let [now (own-aid)]
    (boolean (and (number? before) (number? now) (> now before)))))

(defn- await-ack
  [before]
  (let [deadline (+ (System/currentTimeMillis) ack-wait-ms)]
    (loop []
      (cond
        (acked? before) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 25) (recur))))))

(defn- await-any-diff
  "The pre-#245 wait, kept for a send with no board to read :aid from (a resync
   in flight): return on the first diff rather than burn the whole ack wait."
  [cursor-before]
  (let [deadline (+ (System/currentTimeMillis) ack-wait-ms)]
    (while (and (= (state/get-cursor) cursor-before)
                (< (System/currentTimeMillis) deadline))
      (Thread/sleep 25))))

(defn ack-pending?
  "Is an action we sent still waiting for its ack? While it is, the next
   :game/action will be refused, so a loop that sees this is waiting on the
   wire, not failing to make progress."
  []
  (let [{:keys [gameid side aid at] :as p} @unacked]
    (boolean (and p
                  (= gameid (:gameid @state/client-state))
                  (= side (state/my-side-kw))
                  (< (- (System/currentTimeMillis) at) unacked-expiry-ms)
                  (not (acked? aid))))))

(defn- pending-ack-cleared?
  "Called under action-lock before a send. True when nothing we sent is still
   waiting for its ack (clearing a stale, foreign, or acknowledged entry)."
  []
  (let [{:keys [gameid side aid at] :as p} @unacked]
    (cond
      (nil? p) true
      (or (not= gameid (:gameid @state/client-state))
          (not= side (state/my-side-kw))
          (>= (- (System/currentTimeMillis) at) unacked-expiry-ms))
      (do (clear-unacked!) true)
      (await-ack aid) (do (clear-unacked!) true)
      :else false)))

(defn send-message!
  "Send a message to server.
   Auto-reconnects if disconnected or send fails.

   A :game/action is sent under action-lock and then waits for the engine's
   ack (see the section comment above action-lock). It returns :confirmed when
   the wire shows the ack, :unconfirmed when the socket took it but no ack came
   within ack-wait-ms, and false when it was not sent: the socket failed, or an
   earlier action is still unacknowledged. Both keywords are truthy, so callers
   that ask only whether it went out are unchanged. Other messages return the
   socket's answer."
  [event-type data]
  (if (= event-type :game/action)
    (locking action-lock
      (if-not (pending-ack-cleared?)
        (do
          (println (str "⏳ Not sent: the engine has not acknowledged our previous action yet"
                        " (the wire is lagging, or that action was lost). Sending now could repeat it;"
                        " this clears when the ack arrives, or after " (quot unacked-expiry-ms 1000) "s."))
          false)
        (let [before (own-aid)
              cursor-before (state/get-cursor)]
          ;; Armed BEFORE the write: a :game/error for this action normally lands
          ;; while we are still waiting below, and its clear must have something
          ;; to hit. Arming after the wait re-armed an ack the server had already
          ;; told us will never come (review round 1).
          (when (number? before)
            (reset! unacked {:gameid (:gameid @state/client-state)
                             :side (state/my-side-kw)
                             :aid before
                             :at (System/currentTimeMillis)}))
          (cond
            (not (send-message-impl! event-type data))
            (do (clear-unacked!) false)

            (not (number? before))
            (do (await-any-diff cursor-before) :unconfirmed)

            (await-ack before)
            (do (clear-unacked!) :confirmed)

            :else :unconfirmed))))
    ;; Non-action messages don't need synchronization
    (send-message-impl! event-type data)))

(defn send-action!
  "Send a game action"
  [command args]
  (let [gameid (:gameid @state/client-state)]
    (if gameid
      (send-message! :game/action
                       {:gameid gameid
                        :command command
                        :args args})
      (println "❌ No active game"))))

;; ============================================================================
;; High-Level API
;; ============================================================================
;; Note: Lobby operations moved to ai-connection namespace

(defn get-current-state [] (:game-state @state/client-state))
(defn get-my-side [] (:side @state/client-state))
(defn connected? [] (:connected @state/client-state))
(defn in-game? [] (some? (:gameid @state/client-state)))
(defn get-lobby-list [] (:lobby-list @state/client-state))

(defn socket-healthy?
  "Check if the WebSocket socket is actually usable.
   The :connected flag can be true even when the socket object is broken.
   This function tries to verify the socket can send a message."
  []
  (when-let [socket (:socket @state/client-state)]
    (try
      ;; Try to send a ping message - if socket is broken, this will throw
      ;; We use ws-pong as a lightweight message that won't affect game state
      (ws/send-msg socket (pr-str [[:chsk/ws-ping]]))
      true
      (catch Exception e
        (debug/debug "WARN" (str "Socket health check failed: " (.getMessage e)))
        false))))

;; ============================================================================
;; Connection Management
;; ============================================================================

(defn rejoin-game!
  "Rejoin a game after reconnection using gameid from client state root"
  []
  (when-let [gameid (:gameid @state/client-state)]
    (let [side (or (:side @state/client-state) "Runner")]
      (println "♻️  Rejoining game:" gameid "as" side)
      (send-message! :lobby/join
                     {:gameid (state/normalize-gameid gameid)
                      :request-side (str/capitalize side)})
      (Thread/sleep 2000)
      true)))

(defn ensure-connected!
  "Check connection and reconnect if needed. Returns true if connected.
   Also checks socket health - the :connected flag can be stale.
   After reconnect, auto-rejoins game if we had one."
  ([] (ensure-connected! (get-ws-url)))
  ([url]
   (let [had-gameid (:gameid @state/client-state)
         reconnected
         (cond
           ;; Socket exists but is broken - clear it and reconnect
           (and (connected?) (not (socket-healthy?)))
           (do
             (println "⚠️  Socket broken, clearing and reconnecting...")
             (swap! state/client-state assoc :socket nil :ws-client nil :connected false)
             (Thread/sleep 200)
             (connect! url)
             (Thread/sleep 2000)
             :reconnected)

           ;; Not connected at all
           (not (connected?))
           (do
             (println "⚠️  Not connected, reconnecting...")
             (connect! url)
             (Thread/sleep 2000)
             :reconnected)

           ;; Connected and healthy
           :else :already-connected)]
     ;; Auto-rejoin if we reconnected and had a game
     (when (and (= reconnected :reconnected) had-gameid (connected?))
       (rejoin-game!))
     (connected?))))

;; ============================================================================
;; Action Helpers (Used by ai-prompts)
;; ============================================================================
;; Note: Prompt display moved to ai-display namespace
;; Note: take-credits!, draw-card!, end-turn!, run-server!, play-card! moved to specialized modules

(defn safe-action!
  "Send action with connection check"
  [command args]
  (when (ensure-connected!)
    (send-action! command args)))

(defn choose!
  "Make a choice from a prompt by index or UUID.

   Always names the prompt via :eid, as the reference client does. Without it the
   engine's resolve-prompt falls back to the HEAD of the prompt queue, which is
   not necessarily the prompt the seat was shown."
  [choice]
  (if (number? choice)
    ;; Choice by index
    (let [prompt (state/get-prompt)
          uuid (get-in prompt [:choices choice :uuid])]
      (when uuid
        (safe-action! "choice" {:choice {:uuid uuid} :eid (:eid prompt)})))
    ;; Choice by UUID string
    (safe-action! "choice" {:choice {:uuid choice} :eid (:eid (state/get-prompt))})))

(defn select-card!
  "Select a card for prompts like discard.
   Takes a card object and prompt eid.
   The card is narrowed by `ai-core/create-card-ref`, which mirrors the reference
   client's key list — including `:host`, without which a hosted card cannot be
   resolved server-side and the select is silently swallowed (#112 sweep).

   `shift-key-held` mirrors the human UI's shift-click (board.cljs
   `handle-card-click`). The engine stores it at [side :shift-key-select]
   (game.core.actions/select) and ONE consumer reads it:
   pick-credit-providing-cards' `should-auto-repeat?`, which keeps taking
   credits from the SAME card until the cost is met instead of re-prompting per
   credit. Its own comment: \"taking 5cr from miss bones with one click, instead
   of waiting for 5 server round-trips\".

   We hardcoded false here, so seats had no way to do what a human does in one
   click — 5 separate choose-card calls for Overclock (#104), 2 for Unity
   (#110). Nothing else in the engine reads the flag, so on a non-payment
   prompt (a discard select) it is inert; and because `select` re-stamps the key
   on EVERY selection, a later plain choose-card resets it to false. Default
   stays false so existing callers are unchanged."
  ([card eid] (select-card! card eid false))
  ([card eid shift-key-held]
   (safe-action! "select"
                 {:card (core/create-card-ref card)
                  :eid eid
                  :shift-key-held (boolean shift-key-held)})))

(defn handle-discard-prompt!
  "Handle discard down to hand size prompt.
   Discards cards one at a time until hand size is acceptable.
   Returns number of cards discarded.

   Returns 0 without selecting anything when there is no side, no board, or no
   readable hand-size max (#127). The subtraction used to happen in this `let`,
   i.e. BEFORE the \"is there a select prompt?\" test, so a nil hand-size-max
   threw a bare NPE out of clojure.lang.Numbers.minus on a sideless state.

   Declining is deliberately NOT what the sibling lookup in
   ai_basic_actions/check-auto-end-turn! does — that one defaults the max to 5.
   The asymmetry is correct, not an oversight: there the value only feeds a
   forewarning line, where a wrong guess costs a wrong hint, whereas here it
   decides which cards get binned. An unreadable hand size is a reason to
   refuse, not to guess: guessing 5 for a Runner under a hand-size modifier
   discards real cards off a board we have just admitted we cannot read."
  [side]
  (let [gs (when side (state/get-game-state))
        prompt (get-in gs [side :prompt-state])
        hand (get-in gs [side :hand])
        hand-size-max (get-in gs [side :hand-size :total])
        cards-to-discard (when hand-size-max (- (count hand) hand-size-max))]
    (if (and (= "select" (:prompt-type prompt))
             cards-to-discard
             (> cards-to-discard 0))
      (do
        (println (format "Need to discard %d cards from hand of %d (max %d)"
                        cards-to-discard (count hand) hand-size-max))
        ;; Snapshot hand once - local state doesn't update immediately after each select
        (let [cards-to-discard-list (take cards-to-discard hand)]
          (doseq [card cards-to-discard-list]
            (println (format "Discarding: %s" (:title card)))
            (select-card! card (:eid prompt))
            (Thread/sleep 500)))
        (println (format "✅ Discarded %d card(s)" cards-to-discard))
        cards-to-discard)
      (if (or (nil? side) (nil? cards-to-discard))
        ;; DECLINED — nil, not 0. Second-pass guest catch (#127): printing
        ;; "declining" while still returning 0 left the ambiguity intact where
        ;; it does damage. ai_heuristic_runner's prompt dispatcher was
        ;; `(do (discard-to-hand-size!) true)` — handled unconditionally — so a
        ;; decline reported success, the loop came round, met the same prompt,
        ;; and spun forever. That is the house autonomous-deadlock shape: a
        ;; shared handler returning a pause the bot loop never converts to an
        ;; action. nil is falsey and 0 is TRUTHY in Clojure, so this single
        ;; distinction is exactly what every caller needs to branch on.
        (do
          (when (and (= "select" (:prompt-type prompt)) (nil? hand-size-max))
            (println "⚠️  A discard prompt is up but this board reports no max hand size — declining rather than guessing which cards to bin.")
            (println "💡 'status' to check the board landed; 'resync' if it did not; 'discard' with explicit indices to choose yourself."))
          nil)
        ;; Genuinely nothing to do: a real board that is at or under hand size.
        0))))

;; Note: Status display functions moved to ai-display namespace
;; Note: announce-revealed-archives and write-game-log-to-hud moved to ai-display namespace

;; ============================================================================
;; Usage
;; ============================================================================

(comment
  ;; Connect to local server
  (connect! "ws://localhost:1042/chsk")

  ;; Check status
  (connected?)
  @client-state

  ;; Send action
  (send-action! "credit" nil)

  ;; Disconnect
  (disconnect!)
  )
