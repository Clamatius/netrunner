(ns ai-state
  "Client state management and diff application for AI WebSocket connection"
  (:require [differ.core :as differ]))

;; ============================================================================
;; Client State Atom
;; ============================================================================

(defonce client-state
  (atom {:connected false
         :game-state nil
         :last-state nil
         :gameid nil
         :side nil
         :uid nil
         :socket nil
         :lobby-list nil
         :client-id nil
         :csrf-token nil}))

;; ============================================================================
;; State Diff Application
;; ============================================================================

(defn apply-diff
  "Apply a diff to current state to get new state using differ library"
  [old-state diff]
  (if old-state
    ;; Use differ/patch which properly handles sparse array updates
    ;; like hand: [0 {:playable true} 1 {:playable true} ...]
    (differ/patch old-state diff)
    diff))

(defn update-game-state!
  "Update game state from a diff - matches web client implementation"
  [diff]
  (try
    (let [old-state (:last-state @client-state)
          ;; Log state BEFORE applying diff
          _ (println "\n📝 Applying diff to state")
          _ (println "   BEFORE - Runner credits:" (get-in old-state [:runner :credit]))
          _ (println "   BEFORE - Runner clicks:" (get-in old-state [:runner :click]))
          _ (println "   BEFORE - Runner hand size:" (count (get-in old-state [:runner :hand])))
          ;; Apply diff directly using differ/patch
          ;; Diff format from server is [alterations removals]
          new-state (apply-diff old-state diff)
          ;; Log state AFTER applying diff
          _ (println "   AFTER  - Runner credits:" (get-in new-state [:runner :credit]))
          _ (println "   AFTER  - Runner clicks:" (get-in new-state [:runner :click]))
          _ (println "   AFTER  - Runner hand size:" (count (get-in new-state [:runner :hand])))]
      (swap! client-state assoc
             :game-state new-state
             :last-state new-state))
    (catch Exception e
      (println "❌ Error in update-game-state!:" (.getMessage e))
      (println "   Diff type:" (type diff))
      (println "   Diff:" (pr-str (take 200 (pr-str diff))))
      (.printStackTrace e))))

(defn detect-side
  "Detect which side we are playing by matching UID to game state"
  [game-state our-uid]
  (let [corp-username (get-in game-state [:corp :user :username])
        runner-username (get-in game-state [:runner :user :username])]
    (cond
      (= our-uid corp-username) "corp"
      (= our-uid runner-username) "runner"
      :else nil)))

(defn set-full-state!
  "Set initial game state and detect which side we are"
  [state]
  (let [our-uid (:uid @client-state)
        existing-side (:side @client-state)
        ;; Only detect side if not already set, otherwise preserve it
        ;; This prevents re-detection on resync when server strips opponent user info
        side (or existing-side (detect-side state our-uid))]
    (swap! client-state assoc
           :game-state state
           :last-state state
           :side side)
    ;; Clear lobby-state when game state is set (game has started)
    (swap! client-state dissoc :lobby-state)
    (when side
      (println "   Detected side:" side))))

;; ============================================================================
;; Game State Queries
;; ============================================================================

(defn get-game-state [] (:game-state @client-state))

(defn active-player [] (get-in @client-state [:game-state :active-player]))
(defn my-turn? [] (= (:side @client-state) (active-player)))
(defn turn-number [] (get-in @client-state [:game-state :turn]))

(defn runner-state [] (get-in @client-state [:game-state :runner]))
(defn corp-state [] (get-in @client-state [:game-state :corp]))

;; Core game state accessors - single source of truth
(defn credits-for-side [side] (get-in @client-state [:game-state side :credit]))
(defn clicks-for-side [side] (get-in @client-state [:game-state side :click]))
(defn hand-count-for-side [side] (get-in @client-state [:game-state side :hand-count]))

;; Context-aware helpers (based on current client's side)
(defn my-credits []
  (credits-for-side (keyword (:side @client-state))))

(defn my-clicks []
  (clicks-for-side (keyword (:side @client-state))))

(defn my-hand []
  (let [side (keyword (:side @client-state))]
    (get-in @client-state [:game-state side :hand])))

(defn my-hand-count []
  (hand-count-for-side (keyword (:side @client-state))))

(defn my-installed []
  (let [side (keyword (:side @client-state))]
    (if (= side :runner)
      (get-in @client-state [:game-state :runner :rig])
      ;; Corp doesn't have a "rig", return nil or servers
      nil)))

;; Absolute side helpers (always return specific side's data)
(defn runner-credits [] (credits-for-side :runner))
(defn runner-clicks [] (clicks-for-side :runner))
(defn runner-hand-count [] (hand-count-for-side :runner))

(defn corp-credits [] (credits-for-side :corp))
(defn corp-clicks [] (clicks-for-side :corp))
(defn corp-hand-count [] (hand-count-for-side :corp))

(defn get-prompt
  "Get current prompt for our side, if any"
  []
  (let [side (:side @client-state)]
    (get-in @client-state [:game-state (keyword side) :prompt-state])))

(defn get-turn-status
  "Get structured turn status information
   Returns map with:
   - :whose-turn - 'runner', 'corp', or 'none'
   - :my-turn? - boolean
   - :turn-number - integer
   - :can-act? - boolean (my turn AND not waiting prompt)
   - :in-run? - boolean
   - :run-server - server name if in run
   - :status-emoji - visual indicator
   - :status-text - human-readable status"
  []
  (let [gs (get-game-state)
        my-side (:side @client-state)
        active-side (active-player)
        turn-num (turn-number)
        end-turn (get-in gs [:end-turn])
        prompt (get-prompt)
        prompt-type (:prompt-type prompt)
        run-state (get-in gs [:run])
        runner-clicks (get-in gs [:runner :click])
        corp-clicks (get-in gs [:corp :click])
        both-zero-clicks (and (= 0 runner-clicks) (= 0 corp-clicks))
        ;; Compare case-insensitively since my-side is "Corp"/"Runner" but active-side is "corp"/"runner"
        my-turn (and my-side active-side
                     (= (clojure.string/lower-case my-side)
                        (clojure.string/lower-case active-side)))
        ;; When both have 0 clicks, determine who should go next
        ;; At turn 0: Corp goes first
        ;; Otherwise: opposite of whoever is currently active
        next-player (cond
                     (= turn-num 0) "corp"
                     (= active-side "corp") "runner"
                     (= active-side "runner") "corp"
                     :else "unknown")

        ;; Determine status
        [emoji text can-act]
        (cond
          both-zero-clicks
          ["🟢" (str "Waiting to start " next-player " turn") false]

          (not my-turn)
          ["⏳" (str "Waiting for " active-side) false]

          end-turn
          ["🟢" "Ready to start turn" true]

          (= :waiting prompt-type)
          ["⏳" (or (:msg prompt) "Waiting...") false]

          :else
          ["✅" "Your turn to act" true])]

    {:whose-turn active-side
     :my-turn? my-turn
     :turn-number turn-num
     :can-act? can-act
     :in-run? (boolean run-state)
     :run-server (:server run-state)
     :status-emoji emoji
     :status-text text}))
