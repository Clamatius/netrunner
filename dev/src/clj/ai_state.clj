(ns ai-state
  "Client state management and diff application for AI WebSocket connection"
  (:require [differ.core :as differ]
            [ai-debug :as debug]))

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
         :csrf-token nil
         ;; Authentication (proper login)
         :session-token nil    ; JWT from /login endpoint
         :username nil
         ;; Spectator mode
         :spectator false
         :spectator-perspective nil}))

;; ============================================================================
;; Gameid Normalization
;; ============================================================================

(defn normalize-gameid
  "Convert gameid to UUID. Accepts string or UUID, returns UUID.
   All gameid values should be normalized at entry points, so downstream
   code can assume gameid is always a UUID."
  [gameid]
  (when gameid
    (if (string? gameid)
      (java.util.UUID/fromString gameid)
      gameid)))

(defn get-gameid
  "Get gameid from client state, guaranteed to be UUID or nil."
  []
  (:gameid @client-state))

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
        detected-side (or existing-side (detect-side state our-uid))
        ;; Normalize to lowercase to match game state keys (:runner, :corp)
        side (some-> detected-side clojure.string/lower-case)]
    (swap! client-state assoc
           :game-state state
           :last-state state
           :side side)
    ;; Clear lobby-state when game state is set (game has started). A full
    ;; state also proves the server still hosts our game, so any lobby-gone
    ;; verdict from a previous teardown is stale — drop it (#93).
    (swap! client-state dissoc :lobby-state :lobby-gone?)
    (when side
      (println "   Detected side:" side))))

;; ============================================================================
;; Game State Queries
;; ============================================================================

(defn get-game-state [] (:game-state @client-state))

(defn get-game-state!
  "Get game state, throwing if disconnected (fail-fast on stale reads).
   Use this in commands where stale data would be misleading."
  []
  (when-not (:connected @client-state)
    (throw (ex-info "Cannot read game state: disconnected" {:stale true})))
  (:game-state @client-state))

(defn game-over?
  "True when the game has ended. Canonical predicate shared by status, the
   bot loops, and the `wait` wake logic. The engine signals game-over either
   by setting :winner (a side won/conceded) or by setting both :reason and
   :end-time (e.g. a tie or a timed-out match). Accepts an optional game-state
   map; defaults to the current client game-state."
  ([] (game-over? (get-game-state)))
  ([gs] (boolean (or (:winner gs) (and (:reason gs) (:end-time gs))))))

(defn mark-lobby-gone!
  "Record that the server closed our lobby out from under us (#93). The server
   announces this with a bare [:lobby/state] (no data) sent by close-lobby! /
   clear-lobby-state; nothing else invalidates the cached game snapshot, so
   without this flag every status command keeps answering from a game the
   server has already discarded — game-over-status reported IN-PROGRESS
   forever and a brief-obeying seat could never stop."
  []
  (swap! client-state assoc :lobby-gone? true))

(defn lobby-gone?
  "True when the server has closed our lobby but we still hold a cached game
   snapshot. Accepts an optional client-state map for pure/test use."
  ([] (lobby-gone? @client-state))
  ([state] (boolean (:lobby-gone? state))))

(defn active-player [] (get-in @client-state [:game-state :active-player]))
(defn my-turn? [] (= (:side @client-state) (active-player)))
(defn turn-number [] (get-in @client-state [:game-state :turn]))

(defn my-side-kw
  "Get current side as keyword, normalized to lowercase (:runner or :corp).
   Game state keys are always lowercase, so this ensures proper access."
  []
  (when-let [side (:side @client-state)]
    (keyword (clojure.string/lower-case side))))

(defn runner-state [] (get-in @client-state [:game-state :runner]))
(defn corp-state [] (get-in @client-state [:game-state :corp]))

;; Core game state accessors - single source of truth
(defn credits-for-side [side] (get-in @client-state [:game-state side :credit]))
(defn clicks-for-side [side] (get-in @client-state [:game-state side :click]))
(defn hand-count-for-side [side] (get-in @client-state [:game-state side :hand-count]))
;; A player's own deck contents are hidden (fog of war): [:side :deck] is an
;; empty collection on the wire. The server sends the real size as :deck-count
;; (public info - the opponent knows your deck size). Always use this to ask
;; "how many cards are left to draw", NOT (count (runner-deck)).
(defn deck-count-for-side [side] (or (get-in @client-state [:game-state side :deck-count]) 0))

;; Context-aware helpers (based on current client's side)
(defn my-credits []
  (credits-for-side (my-side-kw)))

(defn my-clicks []
  (clicks-for-side (my-side-kw)))

(defn my-hand []
  (get-in @client-state [:game-state (my-side-kw) :hand]))

(defn my-hand-count []
  (hand-count-for-side (my-side-kw)))

(defn my-installed []
  (let [side (my-side-kw)]
    (if (= side :runner)
      (get-in @client-state [:game-state :runner :rig])
      ;; Corp doesn't have a "rig", return servers
      (get-in @client-state [:game-state :corp :servers]))))

;; Absolute side helpers (always return specific side's data)
(defn runner-credits [] (credits-for-side :runner))
(defn runner-clicks [] (clicks-for-side :runner))
(defn runner-hand-count [] (hand-count-for-side :runner))
(defn runner-deck-count [] (deck-count-for-side :runner))

(defn corp-credits [] (credits-for-side :corp))
(defn corp-clicks [] (clicks-for-side :corp))
(defn corp-hand-count [] (hand-count-for-side :corp))

(defn- card-credit-counters
  "Recursively collect {:title :credits} for a card and any cards hosted on it
   that carry a positive :credit counter."
  [card]
  (let [n (get-in card [:counter :credit] 0)
        here (when (pos? n) [{:title (or (:title card) "Unknown") :credits n}])
        hosted (mapcat card-credit-counters (:hosted card))]
    (concat here hosted)))

(defn runner-hosted-credits
  "Credits hosted on the Runner's visible cards -- the rig (programs/hardware/
   resources, plus anything hosted on them) and the play-area, where run events
   like Overclock park their :credit counters for the duration of a run. Many of
   these are spendable (notably for run/break payments), but the status credit
   field reports only the pool, so an agent reasoning about affordability
   undercounts. Returns {:total N :sources [{:title :credits} ...]} (issue #21).

   We deliberately do NOT assert run-spendability here: that lives in server-side
   card-def :pay-credits interactions the wire client never sees. We surface the
   hosted credits and name their sources so the agent can judge -- honest about
   what's hosted without over-claiming what's spendable."
  ([] (runner-hosted-credits (get-game-state)))
  ([gs]
   (let [rig (get-in gs [:runner :rig])
         cards (concat (:program rig) (:hardware rig) (:resource rig)
                       (get-in gs [:runner :play-area]))
         sources (vec (mapcat card-credit-counters cards))]
     {:total (reduce + 0 (map :credits sources))
      :sources sources})))

(defn waiting-prompt-type?
  "True if a :prompt-type denotes a passive 'waiting for opponent' prompt.
   The WIRE value is the STRING \"waiting\"; older/fixture code uses the
   keyword :waiting. Match both so callers never silently miss the wire form
   (see ai-stall comment + ai-core/check-blocking-prompt)."
  [prompt-type]
  (contains? #{:waiting "waiting"} prompt-type))

(defn select-prompt-type?
  "True if a :prompt-type denotes a card-select (targeting cursor) prompt.
   The WIRE value is the STRING \"select\"; the server sets the keyword
   :select pre-serialization and fixtures may use either. Match both so
   callers never silently miss a form (mirrors waiting-prompt-type?; see the
   b5fcf0830 :prompt-type audit)."
  [prompt-type]
  (contains? #{:select "select"} prompt-type))

(defn mulligan-wait-prompt?
  "True when PROMPT is the opening-mulligan 'waiting for opponent to keep hand or
   mulligan' window (engine: show-wait-prompt \"<side> to keep hand or mulligan\",
   game.core.set-up).

   Prompt-level half of opponent-mulligan-pending?, split out so the several
   surfaces that phrase this boundary for the player (ai-prompts' keep-hand,
   ai-display's blocker diagnosis) share ONE matcher instead of re-inlining the
   regex. Text-matching is safe here and only here: no card title or card text in
   the pool contains \"mulligan\" or \"keep hand\", and only set-up.clj emits a
   matching :waiting message — so the usual engine-log-embeds-card-labels trap
   does not apply."
  [prompt]
  (boolean
    (and prompt
         (waiting-prompt-type? (:prompt-type prompt))
         (re-find #"(?i)mulligan|keep hand" (str (:msg prompt))))))

(defn opponent-mulligan-pending?
  "True when the opponent has NOT finished their opening mulligan, so we must not
   act (or be told we can).

   The Corp can keep + start-turn before the Runner finishes its opening mulligan;
   the engine then grants the Corp clicks but bounces every action off the
   still-pending mulligan prompt — a wedged, half-started turn.

   Lives HERE, at the bottom of the stack, because EVERY surface that answers
   'is it my move' must give the same answer: the `wait` wake path
   (core/my-turn-to-act?), the start-turn guard (basic-actions/can-start-turn?),
   AND the turn indicator appended to every command's output (get-turn-status,
   below). It used to be private to ai-basic-actions, so `wait` woke
   :my-turn-start at a boundary start-turn then refused (#87) — and the status
   surface said \"🟢 Ready to start your turn\" alongside. Same
   two-sources-of-truth family as #31/#68/#77: one predicate, one answer.

   Detected from our OWN prompt (the server tells us directly; no fog-of-war peek),
   but the engine's own resolution flag WINS when present: :keep is serialized for
   both players (game.core.diffs player-keys), so if it says the opponent has
   already kept/mulliganed we do NOT suppress, no matter what prompt we are still
   holding. That makes a stale or mis-cleared wait prompt unable to deadlock us —
   this check can only ever UNBLOCK relative to the prompt alone."
  [client-state]
  (let [my-side (keyword (:side client-state))
        opp-side (case my-side :corp :runner :runner :corp nil)
        opp-keep (get-in client-state [:game-state opp-side :keep])]
    (and (mulligan-wait-prompt?
           (get-in client-state [:game-state my-side :prompt-state]))
         ;; Engine says they've resolved => not pending, whatever our prompt says.
         (not opp-keep))))

(defn get-prompt
  "Get current prompt for our side, if any"
  []
  (let [side (:side @client-state)
        ;; Normalize to lowercase to match game state keys (:runner, :corp)
        side-kw (when side (keyword (clojure.string/lower-case side)))]
    (get-in @client-state [:game-state side-kw :prompt-state])))

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
        winner (:winner gs)
        game-over? (game-over? gs)
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
        ;; Check if I'm the next player (for both-zero-clicks case)
        i-am-next (and both-zero-clicks
                       my-side
                       (= (clojure.string/lower-case my-side) next-player))

        [emoji text can-act]
        (cond
          ;; Game over - winner declared (or tie)
          game-over?
          ["🏁" (if winner
                  (str (clojure.string/capitalize (name winner)) " wins")
                  "Game over (tie)")
           false]

          ;; Opponent's opening mulligan is unresolved (#87). MUST precede the
          ;; both-zero-clicks branch: at that boundary both sides ARE at 0 clicks
          ;; and turn 0 makes the Corp "next", so this read as "🟢 Ready to start
          ;; your turn" — on EVERY command, since show-turn-indicator appends it —
          ;; while start-turn refused and `wait` (correctly) blocked. Fixing only
          ;; the wake path would have moved the lie to this surface rather than
          ;; killing it: one predicate, one answer.
          (opponent-mulligan-pending? @client-state)
          ["⏳" "Waiting for opponent to finish their opening mulligan" false]

          ;; Both at 0 clicks - it's the next player's turn to start
          both-zero-clicks
          (if i-am-next
            ["🟢" "Ready to start your turn" true]
            ["⏳" (str "Waiting for " next-player " to start") false])

          (not my-turn)
          ["⏳" (str "Waiting for " active-side) false]

          end-turn
          ["🟢" "Ready to start turn" true]

          (waiting-prompt-type? prompt-type)
          ["⏳" (or (:msg prompt) "Waiting...") false]

          :else
          ["✅" "Your turn to act" true])]

    {:whose-turn active-side
     :next-player next-player
     ;; A clean turn boundary (a player ended their turn, or both sides are at
     ;; 0 clicks) is "next player to start", NOT a stall. Tooling uses this to
     ;; avoid false-positive stall detection while a slow opponent thinks about
     ;; its turn start. game-over takes precedence. An ACTIVE run is excluded:
     ;; a run started with the last click leaves both sides at 0 clicks but is
     ;; mid-resolution, not a turn boundary — a wedged run there must keep the
     ;; tight mid-turn stall budget, not the patient boundary one.
     :waiting-to-start? (boolean (and (not game-over?)
                                      (not run-state)
                                      (or end-turn both-zero-clicks)))
     :my-turn? my-turn
     :turn-number turn-num
     :can-act? can-act
     :game-over? game-over?
     :winner winner
     :in-run? (boolean run-state)
     :run-server (:server run-state)
     :status-emoji emoji
     :status-text text}))

;; ============================================================================
;; Defensive Gamestate Accessors
;; ============================================================================
;; These accessors centralize all game state access and provide:
;; - Nil-safety with sensible defaults
;; - Logging of unexpected structure (helps detect jinteki changes)
;; - Single source of truth for state paths
;;
;; If jinteki changes their gamestate format, fix it HERE once rather than
;; chasing down 20+ direct access points.

(defn- warn-unexpected
  "Log warning about unexpected game state structure"
  [accessor-name expected actual]
  (debug/debug "WARN" (str accessor-name " unexpected: expected " expected ", got " (type actual))))

;; === Card Zone Accessors ===

(defn corp-hand
  "Returns corp's hand as vector. Returns [] if unavailable."
  []
  (let [hand (get-in @client-state [:game-state :corp :hand])]
    (cond
      (nil? hand) []
      (sequential? hand) (vec hand)
      :else (do (warn-unexpected "corp-hand" "sequential" hand) []))))

(defn runner-hand
  "Returns runner's hand as vector. Returns [] if unavailable."
  []
  (let [hand (get-in @client-state [:game-state :runner :hand])]
    (cond
      (nil? hand) []
      (sequential? hand) (vec hand)
      :else (do (warn-unexpected "runner-hand" "sequential" hand) []))))

(defn corp-deck
  "Returns corp's deck as vector. Returns [] if unavailable."
  []
  (let [deck (get-in @client-state [:game-state :corp :deck])]
    (cond
      (nil? deck) []
      (sequential? deck) (vec deck)
      :else (do (warn-unexpected "corp-deck" "sequential" deck) []))))

(defn runner-deck
  "Returns runner's deck as vector. Returns [] if unavailable."
  []
  (let [deck (get-in @client-state [:game-state :runner :deck])]
    (cond
      (nil? deck) []
      (sequential? deck) (vec deck)
      :else (do (warn-unexpected "runner-deck" "sequential" deck) []))))

(defn corp-discard
  "Returns corp's discard (Archives) as vector. Returns [] if unavailable."
  []
  (let [discard (get-in @client-state [:game-state :corp :discard])]
    (cond
      (nil? discard) []
      (sequential? discard) (vec discard)
      :else (do (warn-unexpected "corp-discard" "sequential" discard) []))))

(defn runner-discard
  "Returns runner's discard (Heap) as vector. Returns [] if unavailable."
  []
  (let [discard (get-in @client-state [:game-state :runner :discard])]
    (cond
      (nil? discard) []
      (sequential? discard) (vec discard)
      :else (do (warn-unexpected "runner-discard" "sequential" discard) []))))

;; === Installed Cards ===

(defn corp-servers
  "Returns corp's servers map. Returns {} if unavailable.
   Structure: {:hq {...} :rd {...} :archives {...} :remote1 {...} ...}"
  []
  (let [servers (get-in @client-state [:game-state :corp :servers])]
    (cond
      (nil? servers) {}
      (map? servers) servers
      :else (do (warn-unexpected "corp-servers" "map" servers) {}))))

(defn server-cards
  "Returns cards installed in a server (content). Returns [] if unavailable.
   server-key is :hq, :rd, :archives, or :remote1 etc."
  [server-key]
  (let [content (get-in @client-state [:game-state :corp :servers server-key :content])]
    (cond
      (nil? content) []
      (sequential? content) (vec content)
      :else (do (warn-unexpected "server-cards" "sequential" content) []))))

(defn server-ice
  "Returns ICE protecting a server (outermost last). Returns [] if unavailable."
  [server-key]
  (let [ices (get-in @client-state [:game-state :corp :servers server-key :ices])]
    (cond
      (nil? ices) []
      (sequential? ices) (vec ices)
      :else (do (warn-unexpected "server-ice" "sequential" ices) []))))

(defn runner-rig
  "Returns runner's rig map. Returns {:program [] :hardware [] :resource []} if unavailable.
   Structure: {:program [...] :hardware [...] :resource [...]}"
  []
  (let [rig (get-in @client-state [:game-state :runner :rig])]
    (cond
      (nil? rig) {:program [] :hardware [] :resource []}
      (map? rig) rig
      :else (do (warn-unexpected "runner-rig" "map" rig) {:program [] :hardware [] :resource []}))))

(defn runner-programs
  "Returns runner's installed programs. Returns [] if unavailable."
  []
  (let [programs (:program (runner-rig))]
    (if (sequential? programs) (vec programs) [])))

(defn runner-hardware
  "Returns runner's installed hardware. Returns [] if unavailable."
  []
  (let [hardware (:hardware (runner-rig))]
    (if (sequential? hardware) (vec hardware) [])))

(defn runner-resources
  "Returns runner's installed resources. Returns [] if unavailable."
  []
  (let [resources (:resource (runner-rig))]
    (if (sequential? resources) (vec resources) [])))

;; === Run State ===

(defn current-run
  "Returns current run map, or nil if no run active."
  []
  (get-in @client-state [:game-state :run]))

(defn run-server
  "Returns current run server (keyword like :hq or :remote1), or nil if no run."
  []
  (when-let [run (current-run)]
    (let [server (:server run)]
      (if (sequential? server)
        (keyword (last server))
        server))))

(defn run-position
  "Returns current run ICE position (1-indexed from server), or nil if no run."
  []
  (:position (current-run)))

(defn run-phase
  "Returns current run phase keyword, or nil if no run.
   Phases: :approach-ice, :encounter-ice, :approach-server, etc."
  []
  (:phase (current-run)))

;; === Game Meta ===

(defn game-log
  "Returns game log entries as vector. Returns [] if unavailable."
  []
  (let [log (get-in @client-state [:game-state :log])]
    (cond
      (nil? log) []
      (sequential? log) (vec log)
      :else (do (warn-unexpected "game-log" "sequential" log) []))))

(defn recent-log
  "Returns last n game log entries. Returns [] if unavailable."
  [n]
  (vec (take-last n (game-log))))

(defn active-player-side
  "Returns active player as keyword (:corp or :runner), or nil."
  []
  (when-let [active (get-in @client-state [:game-state :active-player])]
    (keyword active)))

;; === Side-Aware Accessors ===

(defn hand-for-side
  "Returns hand for specified side. Returns [] if unavailable."
  [side]
  (case (keyword side)
    :corp (corp-hand)
    :runner (runner-hand)
    []))

(defn deck-for-side
  "Returns deck for specified side. Returns [] if unavailable."
  [side]
  (case (keyword side)
    :corp (corp-deck)
    :runner (runner-deck)
    []))

(defn discard-for-side
  "Returns discard for specified side. Returns [] if unavailable."
  [side]
  (case (keyword side)
    :corp (corp-discard)
    :runner (runner-discard)
    []))

;; ============================================================================
;; Staleness Detection
;; ============================================================================
;; Detect when client state is stale (out of sync with server).
;; This can happen when:
;; - Server marks us as "left" but WebSocket stays connected
;; - Diffs are received but filtered out due to gameid mismatch
;; - Connection hiccups cause missed diffs

(defn stale?
  "Returns true if client appears to have stale state.
   Checks:
   - diff-mismatch flag (set when we receive diffs for wrong game)
   - gameid mismatch (have game-state but no gameid)

   Can be extended with additional sensors as needed."
  []
  (let [{:keys [diff-mismatch gameid game-state]} @client-state]
    (or
      ;; Received a diff that didn't match our gameid
      diff-mismatch
      ;; Have game state but lost our gameid somehow
      (and (some? game-state) (nil? gameid)))))

(defn clear-stale-flag!
  "Clear staleness indicators after successful resync"
  []
  (swap! client-state dissoc :diff-mismatch))

;; ============================================================================
;; Seen Cards Tracking
;; ============================================================================
;; Track which card titles have been shown to the user this session.
;; On first encounter, display card text. Subsequent encounters are silent.

(defonce seen-cards (atom #{}))

(defn first-time-seeing?
  "Returns true if this card title hasn't been displayed yet this session."
  [card-title]
  (not (contains? @seen-cards card-title)))

(defn mark-card-seen!
  "Mark a card title as having been displayed."
  [card-title]
  (swap! seen-cards conj card-title))

(defn reset-seen-cards!
  "Reset seen cards tracking (e.g., for new game session)."
  []
  (reset! seen-cards #{}))

;; ============================================================================
;; State Cursor (for race-condition-free waiting)
;; ============================================================================
;; Monotonically increasing counter that bumps on relevant state changes.
;; Used by wait commands to detect if state has already advanced past
;; a known point, avoiding race conditions in model-vs-model play.
;;
;; The cursor is opaque to callers - they just pass it through.
;; This allows us to change the implementation without breaking callers.

(defonce state-cursor (atom 0))

;; ============================================================================
;; Replay Recording
;; ============================================================================
;; Accumulate game state for replay generation.
;; Records initial state on :game/start, then all diffs.
;; Format matches jinteki client expectations: {:history [init-state diff1 diff2 ...]}

(defonce replay-recording
  (atom {:enabled false
         :history []
         :gameid nil
         :start-time nil}))

(defn replay-enabled? []
  (:enabled @replay-recording))

(defn start-replay-recording!
  "Begin recording game state for replay. Call before joining game."
  []
  (reset! replay-recording {:enabled true
                            :history []
                            :gameid nil
                            :start-time (java.time.Instant/now)})
  (println "🎬 Replay recording started"))

(defn stop-replay-recording!
  "Stop recording but keep accumulated data."
  []
  (swap! replay-recording assoc :enabled false)
  (println "🎬 Replay recording stopped"))

(defn record-initial-state!
  "Record initial game state (called on :game/start).
   State should be the full game state, not a diff."
  [state gameid]
  (when (:enabled @replay-recording)
    (swap! replay-recording assoc
           :history [state]
           :gameid gameid)
    (println "🎬 Initial state recorded for gameid:" gameid)))

(defn capture-current-state!
  "Capture current game state as initial state for replay.
   Use when starting recording mid-game."
  []
  (when (:enabled @replay-recording)
    (let [gs (:game-state @client-state)
          gameid (:gameid @client-state)]
      (if gs
        (do
          (swap! replay-recording assoc
                 :history [gs]
                 :gameid gameid)
          (println "🎬 Captured current state as initial (gameid:" gameid ")"))
        (println "❌ No game state to capture")))))

(defn record-diff!
  "Record a game diff (called on :game/diff).
   Diffs are appended to history after initial state."
  [diff]
  (when (:enabled @replay-recording)
    (swap! replay-recording update :history conj diff)
    (debug/debug "🎬 Recorded diff #" (dec (count (:history @replay-recording))))))

(defn get-replay-data
  "Get current replay data as map. Returns nil if no recording."
  []
  (let [{:keys [history gameid start-time]} @replay-recording]
    (when (seq history)
      {:metadata {:gameid (str gameid)
                  :recorded-at (str start-time)
                  :saved-at (str (java.time.Instant/now))
                  :diff-count (dec (count history))}
       :history history})))

(defn save-replay!
  "Save current replay to file. Returns filename on success, nil on failure."
  ([] (save-replay! nil))
  ([filename]
   (if-let [replay-data (get-replay-data)]
     (let [gameid (:gameid @replay-recording)
           default-name (str "replay-" (or gameid "unknown") "-"
                            (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
                                     (java.time.LocalDateTime/now))
                            ".json")
           filepath (or filename (str "dev/replays/" default-name))]
       ;; Ensure replays directory exists
       (.mkdirs (java.io.File. "dev/replays"))
       (require '[cheshire.core :as json])
       (spit filepath ((resolve 'cheshire.core/generate-string) replay-data {:pretty true}))
       (println "💾 Replay saved to" filepath)
       (println "   Diffs recorded:" (dec (count (:history @replay-recording))))
       filepath)
     (do
       (println "❌ No replay data to save")
       nil))))

(defn clear-replay!
  "Clear replay recording state."
  []
  (reset! replay-recording {:enabled false :history [] :gameid nil :start-time nil})
  (println "🎬 Replay recording cleared"))

(defn get-cursor
  "Get current state cursor value. Opaque to callers."
  []
  @state-cursor)

(defn bump-cursor!
  "Increment state cursor. Called when relevant state changes occur.
   Returns the new cursor value."
  []
  (swap! state-cursor inc))

(defn reset-cursor!
  "Reset cursor to 0 (e.g., for new game session)."
  []
  (reset! state-cursor 0))

;; ============================================================================
;; State Clearing (for reconnect/resync)
;; ============================================================================

(defn clear-game-state!
  "Clear all cached game state before reconnect/resync.
   This prevents stale state from causing issues with diff application.
   Preserves connection info (socket, uid, session-token, username) and side hint.

   Call this BEFORE requesting a resync to ensure clean state."
  []
  (let [preserved-keys [:connected :socket :uid :session-token :username :csrf-token
                        :client-id :side :gameid :spectator :spectator-perspective]]
    ;; Clear game-specific state
    (swap! client-state
           (fn [s]
             (-> (select-keys s preserved-keys)
                 (assoc :game-state nil
                        :last-state nil
                        :lobby-state nil
                        :lobby-list nil))))
    ;; Reset auxiliary state atoms
    (reset-cursor!)
    (reset-seen-cards!)
    (clear-stale-flag!)
    (println "🧹 Cleared cached game state")))
