;; Full game test: 2 AI clients playing against each other
(ns full-game-test
  (:require
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [gniazdo.core :as ws]))

;; ============================================================================
;; State Management (based on ai_websocket_client_v2.clj)
;; ============================================================================

(defonce clients (atom {}))

(defn deep-merge [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(defn apply-diff [old-state diff]
  (if old-state
    (deep-merge old-state diff)
    diff))

(defn create-client [name]
  {:name name
   :connected false
   :socket nil
   :client-id nil
   :uid nil
   :gameid nil
   :game-state nil
   :last-state nil
   :messages []})

(defn log [client-name & msgs]
  (println (str "[" client-name "] ") (apply str msgs)))

(defn update-game-state! [client-name diff]
  (try
    (let [old-state (get-in @clients [client-name :last-state])
          ;; Handle array diffs: filter empty maps
          diffs (cond
                  (and (vector? diff) (empty? diff)) []
                  (and (vector? diff) (> (count diff) 0) (every? #(or (map? %) (nil? %)) diff)) diff
                  (map? diff) [diff]
                  (nil? diff) []
                  :else [diff])
          filtered-diffs (filter #(and (some? %) (not (and (map? %) (empty? %)))) diffs)
          new-state (reduce apply-diff old-state filtered-diffs)]
      (swap! clients update client-name assoc
             :game-state new-state
             :last-state new-state))
    (catch Exception e
      (log client-name "❌ State update error:" (.getMessage e)))))

;; ============================================================================
;; Message Handling
;; ============================================================================

(defn parse-edn-message [msg]
  (try
    (let [data (if (string? msg) (edn/read-string msg) msg)]
      (cond
        ;; Batched events: [[[event1] [event2]]]
        (and (vector? data) (= 1 (count data)) (vector? (first data)) (every? vector? (first data)))
        (mapv (fn [[type payload]] {:type type :data payload}) (first data))

        ;; Single event: [[:event data]]
        (and (vector? data) (= 1 (count data)) (vector? (first data)))
        [{:type (first (first data)) :data (second (first data))}]

        ;; Direct event: [:event data]
        (vector? data)
        [{:type (first data) :data (second data)}]

        :else
        [{:type :unknown :data data}]))
    (catch Exception e
      (println "❌ Parse error:" (.getMessage e))
      [])))

(defn handle-message [client-name {:keys [type data]}]
  (swap! clients update-in [client-name :messages] conj {:type type :data data})

  (case type
    :chsk/handshake
    (let [uid (first data)]
      (log client-name "✅ Connected! UID:" uid)
      (swap! clients update client-name assoc :connected true :uid uid))

    :lobby/state
    (when-let [gameid (:gameid data)]
      (log client-name "🎮 Joined game:" gameid)
      (swap! clients update client-name assoc :gameid gameid))

    :game/start
    (log client-name "🎮 GAME STARTED!")

    :game/resync
    (let [state (if (string? data) (json/parse-string data true) data)]
      (log client-name "🔄 Full state resync")
      (swap! clients update client-name assoc :game-state state :last-state state))

    :game/diff
    (let [diff-data (if (string? data) (json/parse-string data true) data)
          {:keys [diff]} diff-data]
      (update-game-state! client-name diff))

    :game/error
    (log client-name "❌ Server error!")

    :chsk/ws-ping nil  ; ignore

    ; default
    (when (not= type :chsk/ws-ping)
      (log client-name "📨" type))))

(defn on-receive [client-name msg]
  (doseq [parsed (parse-edn-message msg)]
    (handle-message client-name parsed)))

;; ============================================================================
;; Connection & Commands
;; ============================================================================

(defn connect! [client-name]
  (let [client-id (str "ai-client-" client-name)
        url (str "ws://localhost:1042/chsk?client-id=" client-id)]
    (log client-name "🔌 Connecting...")
    (try
      (let [socket (ws/connect url
                               :on-receive (fn [msg & _] (on-receive client-name msg))
                               :on-connect (fn [& _] (log client-name "⏳ Handshake..."))
                               :on-close (fn [& _]
                                          (log client-name "❌ Disconnected")
                                          (swap! clients update client-name assoc :connected false))
                               :on-error (fn [& args] (log client-name "❌ Error:" (first args))))]
        (swap! clients update client-name assoc :socket socket :client-id client-id)
        true)
      (catch Exception e
        (log client-name "❌ Connection failed:" (.getMessage e))
        false))))

(defn send! [client-name event-type data]
  (if-let [socket (get-in @clients [client-name :socket])]
    (try
      (let [msg (pr-str [[event-type data]])]
        (ws/send-msg socket msg)
        true)
      (catch Exception e
        (log client-name "❌ Send failed:" (.getMessage e))
        false))
    false))

(defn send-action! [client-name command args]
  (let [gameid-str (get-in @clients [client-name :gameid])
        gameid (if (string? gameid-str) (java.util.UUID/fromString gameid-str) gameid-str)]
    (send! client-name :game/action {:gameid gameid :command command :args args})))

;; ============================================================================
;; Full Game Test
;; ============================================================================

(defn run-full-game-test! []
  (println "\n========================================")
  (println "FULL GAME TEST: Corp vs Runner")
  (println "========================================\n")

  ;; Initialize clients
  (swap! clients assoc
         :corp (create-client :corp)
         :runner (create-client :runner))

  ;; Step 1: Connect both
  (println "Step 1: Connecting both clients...")
  (connect! :corp)
  (connect! :runner)
  (Thread/sleep 3000)

  ;; Step 2: Corp creates System Gateway game
  (println "\nStep 2: Creating System Gateway game...")
  (send! :corp :lobby/create
         {:title "AI Test Game"
          :side "Corp"
          :format "system-gateway"
          :options {:spectatorhands false}})
  (Thread/sleep 2000)

  ;; Step 3: Runner joins
  (when-let [gameid-raw (get-in @clients [:corp :gameid])]
    (let [gameid (if (string? gameid-raw) (java.util.UUID/fromString gameid-raw) gameid-raw)]
      (println "\nStep 3: Runner joining game" gameid "...")
      (send! :runner :lobby/join
             {:gameid gameid
              :password nil
              :options {:side "Runner"}})
      (Thread/sleep 2000)

      ;; Step 4: Start game
      (println "\nStep 4: Starting game...")
      (send! :corp :game/start {:gameid gameid})
      (Thread/sleep 2000)

      (println "\n✅ Game started! Both clients should have state")
      (println "Corp gameid:" (get-in @clients [:corp :gameid]))
      (println "Runner gameid:" (get-in @clients [:runner :gameid]))
      (println "\nReady for next steps (mulligan, turns, etc.)")))

  @clients)

;; ============================================================================
;; Helper: Check prompts
;; ============================================================================

(defn check-prompts []
  (println "\n=== CURRENT PROMPTS ===")
  (doseq [client-name [:corp :runner]]
    (let [gs (get-in @clients [client-name :game-state])
          side (name client-name)
          prompt (get-in gs [(keyword side) :prompt-state])]
      (println "\n[" client-name "]")
      (if prompt
        (do
          (println "  Message:" (:msg prompt))
          (println "  Type:" (:prompt-type prompt))
          (println "  Choices:" (mapv :value (:choices prompt))))
        (println "  No prompt")))))

(defn check-state
  "Check current game state for both sides"
  []
  (println "\n=== GAME STATE ===")
  (doseq [client-name [:corp :runner]]
    (let [gs (get-in @clients [client-name :game-state])]
      (when gs
        (let [side (keyword (name client-name))
              player-state (get gs side)]
          (println "\n[" client-name "]")
          (println "  Credits:" (:credit player-state))
          (println "  Clicks:" (:click player-state))
          (println "  Hand size:" (:hand-count player-state))
          (println "  Deck size:" (:deck-count player-state)))))))

(comment
  ;; Run the test
  (run-full-game-test!)

  ;; Check what prompts we have
  (check-prompts)

  ;; Check client state
  @clients
  (get-in @clients [:corp :game-state])

  ;; Clean up
  (doseq [name [:corp :runner]]
    (when-let [socket (get-in @clients [name :socket])]
      (ws/close socket)))
  (reset! clients {})
  )
