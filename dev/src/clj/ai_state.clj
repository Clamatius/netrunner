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
   Game state keys are always lowercase, so this ensures proper access.

   nil when this client has no side — a REPL that never joined, or the state
   `leave-lobby!` leaves behind (it nils :gameid/:side). The nil is meaningful,
   so this is the ONE place the derivation is allowed to happen: hand-rolling
   `(keyword (lower-case (:side state)))` throws a bare NPE out of
   clojure.string/lower-case on exactly those states (#125).

   The 1-arity takes an already-captured state map, so a caller that snapshotted
   the atom classifies the same state it renders instead of re-reading."
  ([] (my-side-kw @client-state))
  ([state]
   (when-let [side (:side state)]
     (keyword (clojure.string/lower-case side)))))

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

(defn my-mulligan-pending?
  "True when *I* have not yet answered my own opening mulligan.

   The mirror of opponent-mulligan-pending?, and the half that was missing. #87
   enumerated the opponent's mulligan and stopped; in the state where I am the
   one who still owes the decision that predicate is false, so the turn-boundary
   branch below ran and — at turn 0, where the Corp is legitimately the next
   player — announced \"🟢 Ready to start your turn\" on every surface.

   Unlike the #87 half, this one is not merely a wording bug. Nothing downstream
   refuses: game.core has no ordering check (the engine trusts the client), and
   the reference client's only guard is that build-start-box is a modal covering
   the board. A seat sending raw commands has neither, so taking the advice
   really does start the turn and take the mandatory draw with the mulligan
   still live — a KEPT six-card starting hand, observed on game e753fdee.

   Keyed on the engine's own flag, not on our prompt. game.core.player ships
   `:keep false` for both players at game creation and set-up.clj overwrites it
   with :keep/:mulligan the moment the player answers, so `false` means exactly
   'has not answered' — and it is serialized for both players (diffs.clj
   player-keys). `false?` rather than `not` is load-bearing: nil is 'no game
   state / not serialized yet', which must not read as a pending mulligan or we
   would block start-turn on every stale or mid-resync state.

   Derives the side through my-side-kw, NOT the house-style
   `(keyword (:side client-state))` that the sibling above still hand-rolls.
   reconnect-game! (the `make resume` path) writes :side capitalized until the
   resync full-state normalizes it, and `(keyword \"Corp\")` is :Corp, which
   misses every [:game-state side ...] lookup — the guard would read nil, not
   false, and silently fail OPEN in exactly the window where a resume lands
   mid-setup. Failing open here is the whole bug, so this one has to go through
   the authority (#129). The sibling has the identical gap; not touched here."
  [client-state]
  (false? (get-in client-state [:game-state (my-side-kw client-state) :keep])))

(defn my-turn-to-act?
  "Check if it's our turn to act (need to start-turn or have clicks).
   Handles Netrunner priority system where active-player doesn't flip until start-turn.

   Wake conditions (any one is sufficient):
     - I am the active player AND I have clicks remaining
     - opponent set the :end-turn flag and active-player is still them
       (engine in transition; my turn is up next)
     - turn 0, Corp side, 0 clicks (post-mulligan: Corp goes first)

   Crucially NOT a wake condition: 'both players at 0 clicks'. That
   scenario fires every time the Runner spends their last click on a run
   (Runner=0, Corp=0, but Runner is still resolving the run). An earlier
   duplicate predicate had that bug and woke spuriously on every
   opponent run-transition; this predicate is the authoritative source
   of truth for the `wait` command (via `relevance-reason`).

   Also NOT a wake condition: the opening-mulligan boundary. While our own prompt
   is the 'waiting for opponent to keep hand or mulligan' window, start-turn is
   refused (:opponent-mulligan), so reporting 'your move' here is a lie that
   returns instantly and repeatedly — #87. The guard is checked FIRST so this
   predicate agrees with can-start-turn? by construction.

   Lives HERE, at the bottom of the stack, for the same reason
   opponent-mulligan-pending? does (see its docstring): get-turn-status — which
   backs `status`, `prompt`, `game-over-status`, `snapshot` and
   `diagnose-blocker` — must answer 'whose move' with this predicate and not a
   look-alike of its own. It used to live in ai-core, one layer ABOVE
   get-turn-status, which is why get-turn-status grew a `both-zero-clicks`
   boundary heuristic instead: exactly the divergent second copy that produced
   #31, #68, and #117. ai-core re-exports the name, so every existing caller
   (and the umpire's `eval` recipe) still resolves."
  [state side]
  ;; nil-safe on side: in the lobby / pre-game the seat may have no :side yet,
  ;; and `(name nil)` would NPE (#46). No side => not our turn.
  (when-let [my-side (keyword side)]
   (let [active-player (get-in state [:game-state :active-player])
        my-clicks (get-in state [:game-state my-side :click] 0)
        end-turn (get-in state [:game-state :end-turn])
        turn-number (get-in state [:game-state :turn] 0)]
    (and
     ;; #87: never claim it's our move while the opponent's opening mulligan is
     ;; unresolved — start-turn will refuse, and `wait` would spin on the lie.
     (not (opponent-mulligan-pending? state))
     ;; #131, and the same sentence with the owner swapped. The turn-0/Corp/
     ;; 0-clicks clause below is commented "post-mulligan" but checks nothing of
     ;; the sort, so with our OWN mulligan unresolved this returned true: `wait`
     ;; woke :my-turn-start and can-start-turn? then refused :my-mulligan — the
     ;; #87 spin exactly, on the mirror side. The docstring above promises this
     ;; predicate agrees with can-start-turn? BY CONSTRUCTION; guarding only the
     ;; opponent's half is what made that promise false.
     (not (my-mulligan-pending? state))
     (or
      ;; My turn and I have clicks
      (and (= (name my-side) active-player) (> my-clicks 0))
      ;; Opponent ended turn, waiting for me to start
      ;; (active-player = opponent because end-turn was called, I'm next)
      (and end-turn (not= (name my-side) active-player))
      ;; Turn 0 with 0 clicks = post-mulligan, Corp needs to start
      ;; (Corp always goes first)
      (and (= 0 turn-number) (= 0 my-clicks) (= my-side :corp)))))))

(defn turn-boundary?
  "True when the engine is BETWEEN turns: someone has ended their turn and the
   next player owes a `start-turn`.

   The only signal is the engine's own `:end-turn` flag. That flag is exact —
   `game.core.turns/end-turn-continue` sets it true, `start-turn` sets it false,
   and a new game is built with it ALREADY true (game.core.state/new-state:
   `:active-player :runner, :end-turn true, :turn 0`), which is what makes the
   pre-first-turn boundary fall out of the same rule with no special case.

   This replaces the `both-zero-clicks` heuristic that used to stand in for it.
   That heuristic was never sound: 'both sides at 0 clicks' is ALSO the shape of
   a turn whose owner is out of clicks but has not ended it (#117), and of a
   run started with the last click. It cannot tell a boundary from either, which
   is how `game-over-status` came to report AWAITING-START next-player=runner
   while the Corp still owned the turn.

   Carries my-turn-to-act?'s turn-0/Corp/0-clicks clause too, and that is not
   belt-and-braces: dropping it would make THIS predicate disagree with that one
   on the post-mulligan boundary — a fresh divergence of exactly the kind #117
   is. (Real wire state has :end-turn true there, so the clause is unreachable in
   a live game and reachable in every test fixture that omits the flag. Agreeing
   with the authority on inputs the engine never produces still matters: it is
   what stops the next reader from concluding the two predicates are different
   things.)

   Consistent with my-turn-to-act? by construction: at a boundary that predicate
   is true for exactly the side that is NOT :active-player, and false for both
   sides when this is false and nobody has clicks.

   An active run is excluded: a run started with the last click is
   mid-resolution, and a wedged run there must keep the tight mid-turn stall
   budget rather than the patient boundary one."
  [client-state]
  (let [gs (:game-state client-state)]
    (boolean (and (not (:run gs))
                  (or (:end-turn gs)
                      (and (= 0 (get gs :turn 0))
                           (zero? (get-in gs [:corp :click] 0))))))))

(defn my-turn-orphaned?
  "True when MY turn is out of clicks but has NOT ended — so the turn still
   belongs to me, nobody is owed a `start-turn`, and my-turn-to-act? is false for
   BOTH sides.

   This is the #117 state, and it is a real one: it is what the board looks like
   between 'the active player spends their last click' and 'the turn actually
   ends'. It became visible when a last click handed the OPPONENT a decision
   (#114) — the auto-end deferred, and every surface then described the board as
   a turn boundary that had not happened.

   SIDE-RELATIVE on purpose, and that is a safety property, not a convenience.
   The only action that resolves this state is `end-turn`, and an end-turn sent
   by the player whose turn it ISN'T ends the OPPONENT's turn and is
   unrecoverable. Answering 'is a turn orphaned' for either seat would let any
   consumer that forgets to re-check ownership hand the Runner an end-turn hint
   during the Corp's turn. Ask it side-relative and the worst a forgetful
   consumer can do is stay quiet.

   Requires no open prompt of ours. With an actionable prompt the honest answer
   is 'resolve your prompt', and with a waiting prompt it is 'you are blocked on
   the opponent'; both are reported by their own branches, and both are states
   the player can still act out of."
  [client-state]
  (let [gs (:game-state client-state)
        active (:active-player gs)
        ;; :side arrives as \"corp\"/\"Corp\" depending on the path that set it;
        ;; game-state keys are always lowercase (see my-side-kw).
        my-side (some-> (:side client-state) clojure.string/lower-case)]
    (boolean (and gs
                  active
                  my-side
                  (= my-side (clojure.string/lower-case active))
                  ;; Defined as the COMPLEMENT of a boundary rather than by
                  ;; re-testing :end-turn, so the two can never both be true —
                  ;; a surface that asked "boundary?" and a surface that asked
                  ;; "orphaned?" disagreeing about the same instant is the whole
                  ;; failure this issue is about.
                  (not (turn-boundary? client-state))
                  ;; NOT implied by the line above: turn-boundary? excludes runs
                  ;; too, so its complement is true mid-run. A run started with
                  ;; the last click is neither a boundary nor an orphaned turn —
                  ;; `continue` is the move there, and offering end-turn instead
                  ;; would end a turn with a run still live.
                  (not (:run gs))
                  ;; The engine's other zero-click pauses (guest-panel CRITICAL).
                  ;; Both paid-ability windows sit at clicks=0 with :end-turn
                  ;; still false, so they match this shape exactly — and in both
                  ;; the resolving action is a PHASE command
                  ;; (post-discard-pass-priority / end-phase-12), not end-turn.
                  ;; Steering a seat to end-turn there re-enters
                  ;; game.core.turns/end-turn and skips the window the opponent
                  ;; is entitled to use.
                  ;;
                  ;; Not exotic: these are player-togglable settings, not card
                  ;; effects — see the "PAW" checkboxes in
                  ;; nr.gameboard.player-stats and the :force-phase-12-* /
                  ;; :force-post-discard-* keys in core/process-actions. All four
                  ;; state keys are serialized to us (core/diffs), so we can and
                  ;; must check them.
                  ;;
                  ;; My original enumeration of "zero-click non-boundary states"
                  ;; was clicks-out-but-not-ended and nothing else. It was
                  ;; incomplete, and the shape of that mistake — assuming the
                  ;; complement of one known state is a single other state — is
                  ;; the thing to distrust here.
                  (not (:corp-phase-12 gs))
                  (not (:runner-phase-12 gs))
                  (not (:corp-post-discard gs))
                  (not (:runner-post-discard gs))
                  (not (game-over? gs))
                  (zero? (get-in gs [(keyword active) :click] 0))
                  (nil? (get-in gs [(keyword my-side) :prompt-state]))))))

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
   - :waiting-to-start? - boolean (engine is between turns; someone owes start-turn)
   - :turn-orphaned? - boolean (#117: active player is out of clicks but has NOT
     ended their turn — nobody owes a start-turn, and my-turn-to-act? is false
     for both sides)
   - :in-run? - boolean
   - :run-server - server name if in run
   - :status-emoji - visual indicator
   - :status-text - human-readable status"
  []
  (let [gs (get-game-state)
        my-side (:side @client-state)
        active-side (active-player)
        turn-num (turn-number)
        winner (:winner gs)
        game-over? (game-over? gs)
        prompt (get-prompt)
        prompt-type (:prompt-type prompt)
        run-state (get-in gs [:run])
        ;; Compare case-insensitively since my-side is "Corp"/"Runner" but active-side is "corp"/"runner"
        my-turn (and my-side active-side
                     (= (clojure.string/lower-case my-side)
                        (clojure.string/lower-case active-side)))
        ;; #117: the boundary and the orphaned turn are now read off the ENGINE's
        ;; :end-turn flag (turn-boundary? / my-turn-orphaned?), not off a
        ;; both-sides-at-0-clicks heuristic. Those two shapes are indistinguishable
        ;; by click count, so the heuristic reported an orphaned turn as a boundary
        ;; and pointed every surface at the wrong player.
        boundary? (turn-boundary? @client-state)
        orphaned? (my-turn-orphaned? @client-state)
        ;; At a boundary the wire's :active-player still names the player who just
        ;; FINISHED, so "who acts next" is the other side. At turn 0 the engine
        ;; ships :active-player :runner / :end-turn true, so Corp-goes-first falls
        ;; out of the same flip with no special case.
        next-player (cond
                     (= turn-num 0) "corp"
                     (= active-side "corp") "runner"
                     (= active-side "runner") "corp"
                     :else "unknown")

        ;; Determine status
        ;; Am I the one owed the start-turn? CALLS the authority rather than
        ;; re-deriving "am I the next player" from side names (guest-panel:
        ;; agreeing by construction is not the same as asking).
        ;;
        ;; HONEST NOTE, because a comment that overstates this would be the same
        ;; species of bug as #117: today this is NOT behaviourally different from
        ;; the name comparison it replaced. The only input where the two diverge
        ;; is the opening mulligan — a boundary where my-turn-to-act? is
        ;; deliberately false for the Corp (#87) but the Corp IS the next player
        ;; by name — and the opponent-mulligan-pending? branch below already
        ;; catches that case first. Reverting this line to the name comparison
        ;; leaves the whole suite green; it is a structural guarantee against the
        ;; branch order changing, not a fix for a live symptom.
        i-am-next (and boundary? (boolean (my-turn-to-act? @client-state my-side)))

        [emoji text can-act]
        (cond
          ;; Game over - winner declared (or tie)
          game-over?
          ["🏁" (if winner
                  (str (clojure.string/capitalize (name winner)) " wins")
                  "Game over (tie)")
           false]

          ;; Opponent's opening mulligan is unresolved (#87). MUST precede the
          ;; boundary branch: the pre-first-turn state IS a boundary (:end-turn
          ;; ships true) and turn 0 makes the Corp "next", so this read as
          ;; "🟢 Ready to start your turn" — on EVERY command, since
          ;; show-turn-indicator appends it — while start-turn refused and `wait`
          ;; (correctly) blocked. Fixing only the wake path would have moved the
          ;; lie to this surface rather than killing it: one predicate, one answer.
          (opponent-mulligan-pending? @client-state)
          ["⏳" "Waiting for opponent to finish their opening mulligan" false]

          ;; MY own opening mulligan is unresolved. Must also precede the
          ;; boundary branch, and for a sharper reason than the #87 case: at
          ;; turn 0 the Corp genuinely IS the next player, so boundary? +
          ;; i-am-next both hold and this read "🟢 Ready to start your turn"
          ;; with a live "Keep hand?" prompt on the same screen. start-turn
          ;; then went through — see my-mulligan-pending?.
          (my-mulligan-pending? @client-state)
          ["🔔" "Answer your opening mulligan first — 'keep-hand' or 'mulligan'" false]

          ;; Between turns: someone owes a start-turn. Both arms are needed —
          ;; the player who just ended is still :active-player, so without the
          ;; else arm they were told "🟢 Ready to start turn" about the turn they
          ;; had only just finished.
          boundary?
          (if i-am-next
            ["🟢" "Ready to start your turn" true]
            ["⏳" (str "Waiting for " next-player " to start") false])

          (not my-turn)
          ["⏳" (str "Waiting for " active-side) false]

          (waiting-prompt-type? prompt-type)
          ["⏳" (or (:msg prompt) "Waiting...") false]

          ;; My turn, out of clicks, not ended, nothing pending (#117). Nobody is
          ;; owed a start-turn and my-turn-to-act? is false for BOTH sides, so
          ;; this must not be dressed up as a boundary — the turn is still mine
          ;; and ending it is the move.
          orphaned?
          ["⏳" "Your turn is out of clicks but has NOT ended yet — end it" false]

          :else
          ["✅" "Your turn to act" true])]

    {:whose-turn active-side
     :next-player next-player
     ;; Is there a LIVE game to reason about? Every other field here is derived
     ;; from `gs`, so with no game they all read as their falsy defaults and
     ;; consumers silently pick the "not my turn" arm — which is how `prompt`
     ;; came to tell a seat on a purged game to `wait` for an opponent that
     ;; doesn't exist.
     ;;
     ;; :lobby-gone? is part of the predicate, not an afterthought (guest-panel
     ;; catch). #93's teardown leaves the cached SNAPSHOT in place and announces
     ;; itself only through that flag, so "is there a :game-state?" answers YES
     ;; on exactly the state game-over-status calls GAME-GONE. Testing presence
     ;; alone made the fix a no-op in the case it was written for.
     ;;
     ;; A DECIDED game is deliberately NOT excluded here: normal endings tear the
     ;; lobby down too, and callers need :game-over? to win so the seat still
     ;; learns the result. Same precedence as game-over-status and wake-reason —
     ;; game-over first, lobby-gone second.
     :in-game? (boolean (and (or gs (:lobby-state @client-state))
                             (not (lobby-gone? @client-state))))
     ;; A clean turn boundary is "next player to start", NOT a stall. Tooling
     ;; uses this to avoid false-positive stall detection while a slow opponent
     ;; thinks about its turn start. game-over takes precedence; the active-run
     ;; exclusion lives in turn-boundary?.
     :waiting-to-start? (boolean (and (not game-over?) boundary?))
     ;; The turn is still the active player's, but it has no clicks left and has
     ;; not ended (#117). Distinct from :waiting-to-start? precisely BECAUSE the
     ;; two used to be conflated: nobody is owed a start-turn here, and telling a
     ;; seat otherwise is what sent the umpire's instruction to the wrong player.
     :turn-orphaned? orphaned?
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
;; Last prompt block we RENDERED (#104: the same prompt printed twice back-to-back)
;; ============================================================================
;; Every acting command now auto-appends the resulting prompt (see
;; show-prompt-if-any), because continue->prompt ran P=0.48. Seats kept their old
;; habit of calling `prompt` anyway, so the identical block printed twice in a row
;; with nothing to say it was one prompt and not two stacked ones — and a seat that
;; reads two blocks answers twice (exactly the double-acting #75 taught us to fear).
;;
;; Tracking what we last PRINTED — not what the server last sent — is the point:
;; the duplication is a rendering artifact, so the dedupe belongs at the render.

(defonce last-rendered-prompt (atom nil))

(defn prompt-render-fingerprint
  "Identity of a prompt AS RENDERED: [eid msg].

   Requires a present :eid. A nil eid must never match another nil eid — that is
   the #75 lesson (nil = nil once let two different card-less prompts share an
   identity); an unidentifiable prompt is simply always treated as new."
  [prompt]
  (when-let [eid (:eid prompt)]
    [eid (:msg prompt)]))

(defn prompt-already-rendered?
  "Is this the same prompt INSTANCE we most recently printed in full?

   False for a stacked duplicate (#75: same msg + card, NEW eid) — those are
   genuinely separate prompts the seat must answer separately, and calling one
   'unchanged' would re-create the bug that issue exists to fix."
  [prompt]
  (boolean (when-let [fp (prompt-render-fingerprint prompt)]
             (= fp @last-rendered-prompt))))

(defn mark-prompt-rendered!
  "Record that we just printed this prompt's full block."
  [prompt]
  (reset! last-rendered-prompt (prompt-render-fingerprint prompt)))

(defn reset-rendered-prompt!
  "Forget the last rendered prompt (new game / cleared state).

   Wired into the same teardown as reset-seen-cards!: a render marker surviving
   into a fresh game would mark that game's first prompt as 'unchanged'."
  []
  (reset! last-rendered-prompt nil))

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
                        :client-id :side :gameid :spectator :spectator-perspective
                        ;; #114: the deferred auto-end arm records that OUR turn is
                        ;; orphaned at 0 clicks behind an opponent-owed decision.
                        ;; Dropping it here made the resync hook dead on the only
                        ;; path that actually runs it (guest-panel CRITICAL #3):
                        ;; the seat came back, the arm was gone, and the turn stayed
                        ;; orphaned with no further diff coming. It is safe to keep:
                        ;; the arm is pinned to (gameid, turn, side) and re-validated
                        ;; against live state before it can fire.
                        :auto-end-deferred]]
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
    (reset-rendered-prompt!)
    (clear-stale-flag!)
    (println "🧹 Cleared cached game state")))
