(ns ai-basic-actions
  "Turn management and basic game actions (credit, draw, end turn)"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-core :as core]
            [clojure.string :as str]))

;; Forward declaration for function used in take-credit! and draw-card!
(declare check-auto-end-turn!)
(declare start-turn!)
(declare turn-started-since-last-opp-end?)
(declare get-my-username)
(declare open-phase-window)

(defn- refuse-no-seat!
  "One refusal for 'this client holds no side, so there is no turn of ours to
   act on' (#127). Deliberately does NOT claim the game is over: :side is nil
   for a spectator watching a live game and for a resync that landed a board
   before matching our uid, as well as for a REPL that never joined. Asserting
   a teardown in those states is the #125 mistake — a confident false claim
   whose remedy (reset.sh) destroys the game."
  [what]
  (println (format "⛔ Refusing %s: this client has no side — no turn of yours to act on." what))
  (if (get-in @state/client-state [:game-state])
    (println "   A board is cached, so you may be spectating or awaiting a seat: try 'status', then 'resync'.")
    (println "   Not in a game. Join one, or ./dev/reset.sh for a fresh game."))
  (core/with-cursor {:status :error :reason :no-side}))

;; ============================================================================
;; Auto-Start Turn Helpers
;; ============================================================================

(defn- get-my-username
  "Get the username for the current side from game state.
   Falls back to UID if game state/username is missing.
   Crucial for correctly identifying own log messages.

   The lookup itself lives in core/my-username — #191 was a SECOND, hand-rolled
   copy of it drifting (it rebuilt the name from the side instead of reading it),
   which is #129's argument in miniature. Only the uid fallback is local, because
   only this caller wants one: a log scan that cannot name us should make no
   claim, but these callers are asking \"which lines are mine\" and the uid is a
   better-than-nothing answer."
  []
  (let [client-state @state/client-state]
    (or (core/my-username client-state)
        (:uid client-state))))

(defn- print-no-board-cause!
  "Say why there is no board, claiming only as much as the state actually shows.

   `:game-state` nil has a FOURTH reading the old enumeration missed: an ordinary
   UNSTARTED LOBBY. A seated player in a waiting room has a :gameid, a side and no
   board — the same signature — and telling it the game 'has ended, been purged, or
   the resync did not complete', with `reset.sh` as the remedy, destroys the healthy
   lobby it is sitting in. That is the #125 mistake exactly: a confident false claim
   whose suggested recovery is the destructive one.

   `:lobby-state` is the discriminator, the same one ai-connection's
   boardless-started-game? keys on: it is dissoc'd the moment a full game state
   arrives, so an unstarted lobby still carries it with :started false, while a
   started game that lost its board has none."
  [client-state]
  (let [lobby (:lobby-state client-state)]
    (if (and lobby (not (:started lobby)))
      (do
        (println "   The game has not started yet — you are seated in a lobby.")
        (println "💡 Check the seats with: ./dev/send_command <side> status")
        (println "   Both players ready:    ./dev/send_command <side> start-game"))
      (do
        (println "   The game has ended, been purged, or the resync did not complete.")
        (println "💡 Confirm with: ./dev/send_command <side> game-over-status")
        (println "   Fresh game:    ./dev/reset.sh")))))

(defn- opponent-mulligan-pending?
  "True when the opponent has NOT finished their opening mulligan. Delegates to the
   single definition in ai-state (see it for the why).

   A delegating `defn-`, NOT `(def x core/x)`: the latter captures the function
   VALUE, so with-redefs in tests silently misses it and a REPL :reload of the
   owning namespace leaves this bound to the stale fn. A real duplicate here is
   what drifted from the wake path and produced #87 (wait woke :my-turn-start,
   start-turn then errored :opponent-mulligan). One predicate, one answer."
  [client-state]
  (core/opponent-mulligan-pending? client-state))

(defn- my-mulligan-pending?
  "True when WE have not yet answered our own opening mulligan. Same delegating
   `defn-` discipline as its sibling above, for the same reason."
  [client-state]
  (state/my-mulligan-pending? client-state))

(defn can-start-turn?
  "Check if we CAN legally start our turn right now.

   Returns map with:
   - :can-start (boolean) - whether we can start turn
   - :reason (keyword) - why we can/can't start

   Reasons:
   - :turn-already-started - we already have clicks
   - :turn-already-played - we already started/played this turn (checked via logs)
   - :opponent-restarted - opponent started a new turn after ending (we missed window)
   - :opponent-mulligan - opponent hasn't finished their opening mulligan yet
   - :not-first-player - Runner trying to start first turn (Corp goes first)
   - :first-turn - Corp can start first turn
   - :opponent-has-clicks - opponent still has clicks remaining
   - :opponent-not-ended - opponent hasn't ended turn (not in recent log)
   - :no-game-state - nothing to reason about (purged game, or resync in flight)
   - :ready - all checks passed, can start turn"
  []
  (let [client-state @state/client-state
        my-side (keyword (:side client-state))
        opp-side (if (= my-side :runner) :corp :runner)
        my-clicks (get-in client-state [:game-state my-side :click])
        opp-clicks (get-in client-state [:game-state opp-side :click])
        turn-number (get-in client-state [:game-state :turn] 0)
        log (get-in client-state [:game-state :log])
        recent-log (vec (take-last 100 log))
        my-username (get-my-username)

        ;; Use extracted log analysis helpers
        opp-end-indices (core/find-end-turn-indices recent-log my-username)
        last-opp-end-idx (last opp-end-indices)

        opp-start-indices (core/find-start-turn-indices recent-log :exclude-username my-username)
        last-opp-start-idx (last opp-start-indices)

        ;; Check if opponent started AGAIN after ending (they're playing again, we missed window)
        opp-restarted? (and last-opp-end-idx
                            last-opp-start-idx
                            (> last-opp-start-idx last-opp-end-idx))

        is-first-turn? (and (= turn-number 0)
                            (or (nil? my-clicks) (= my-clicks 0))
                            (or (nil? opp-clicks) (= opp-clicks 0))
                            (empty? opp-end-indices))

        ;; Check if we effectively already played this turn
        already-played? (turn-started-since-last-opp-end?)]

    (cond
      ;; NO GAME STATE — must mirror start-turn!'s first branch, or the fix is
      ;; only half applied. With :game-state nil every input below defaults to
      ;; the Corp-first-turn signature (turn 0, nil clicks, empty log), so this
      ;; answered {:can-start true :reason :first-turn}. That is the PREFLIGHT
      ;; the autonomous loops gate on — `(when (:can-start check) (start-turn!))`
      ;; in ai-goldfish-corp / ai-heuristic-corp — so guarding only the wire
      ;; leaves the bot announcing "Auto-starting turn", being refused, and
      ;; going round again: the house autonomous-spin shape, with the wire safe
      ;; and the seat still stuck. (Guest-panel pass 2, HIGH: half-applied fix.)
      ;; #142: `map?`, not `nil?`. A raw [alterations removals] diff vector is
      ;; truthy, so `nil?` waved it through and every field below still read as
      ;; its falsy default — the first-turn signature again, one step further out.
      (not (state/board? (:game-state client-state)))
      {:can-start false :reason :no-game-state}

      ;; Already have clicks - turn already started
      (and my-clicks (> my-clicks 0))
      {:can-start false :reason :turn-already-started}

      ;; Already played this turn (0 clicks but log shows we started)
      already-played?
      {:can-start false :reason :turn-already-played}

      ;; Opponent hasn't finished their opening mulligan — starting now races
      ;; ahead of mulligan resolution and wedges the turn (clicks granted, but
      ;; every action bounces off the pending-mulligan prompt).
      (opponent-mulligan-pending? client-state)
      {:can-start false :reason :opponent-mulligan}

      ;; We have not answered our OWN mulligan. The engine will not stop us —
      ;; it grants the clicks and takes the mandatory draw with the decision
      ;; still live, which is how a Corp came to keep a six-card starting hand.
      (my-mulligan-pending? client-state)
      {:can-start false :reason :my-mulligan}

      ;; Opponent started a new turn after ending the previous one
      opp-restarted?
      {:can-start false :reason :opponent-restarted}

      ;; First turn for Runner - can't start (Corp goes first)
      (and is-first-turn? (= my-side :runner))
      {:can-start false :reason :not-first-player}

      ;; First turn for Corp - can start
      is-first-turn?
      {:can-start true :reason :first-turn}

      ;; Opponent still has clicks
      (and opp-clicks (> opp-clicks 0))
      {:can-start false :reason :opponent-has-clicks}

      ;; Opponent hasn't ended
      (empty? opp-end-indices)
      {:can-start false :reason :opponent-not-ended}

      ;; All checks passed
      :else
      {:can-start true :reason :ready})))

(defn ensure-turn-started!
  "Check if turn is started, and if not but we CAN start, auto-start it.

   This implements auto-start-turn behavior:
   - If turn already started (we have clicks), returns true
   - If turn not started but we CAN start (opponent ended), auto-starts and returns true
   - If turn not started and we CAN'T start, prints error and returns false

   Returns:
   - true if ready to proceed with action (turn is started)
   - false if cannot proceed (turn not started and can't auto-start)"
  []
  (let [client-state @state/client-state
        my-side (keyword (:side client-state))
        my-clicks (get-in client-state [:game-state my-side :click] 0)
        can-start-result (can-start-turn?)]
    (cond
      ;; Already have clicks - turn started, ready to go
      (> my-clicks 0)
      true

      ;; Can start turn - auto-start it
      (:can-start can-start-result)
      (do
        (println "")
        (println "💡 Auto-starting turn (opponent has ended, you haven't started yet)")
        (let [result (start-turn!)]
          (if (= (:status result) :success)
            (do
              (println "✅ Turn started successfully")
              true)
            (do
              (println "❌ Auto-start failed")
              false))))

      ;; Cannot start turn - show specific error
      :else
      (do
        (println "")
        (case (:reason can-start-result)
          :opponent-has-clicks
          (println "❌ Cannot perform action: Opponent still has clicks remaining\n   Wait for their turn to end first")

          :opponent-not-ended
          (println "❌ Cannot perform action: Opponent hasn't ended their turn yet\n   Wait for opponent to complete their turn")

          :not-first-player
          (println "❌ Cannot perform action: Corp goes first\n   Wait for Corp to start and complete their turn")

          :opponent-mulligan
          (println "❌ Cannot perform action: Opponent hasn't finished their opening mulligan\n   Wait until they keep/mulligan, then start your turn")

          ;; Default
          (println "❌ Cannot perform action: Turn not ready"))
        false))))

(defn- extract-turn-from-log
  "Extract turn number from log text like 'started their turn 5'"
  [text]
  (when text
    (let [match (re-find #"turn (\d+)" text)]
      (when match
        (Integer/parseInt (second match))))))

(defn turn-started-since-last-opp-end?
  "Check if we have effectively started our turn since the last time the opponent ended theirs.
   Uses robust log index and turn number comparison to handle:
   - Corp/Runner turn structure asymmetry
   - Async log ordering race conditions"
  []
  (let [client-state @state/client-state
        my-side (keyword (:side client-state))
        log (get-in client-state [:game-state :log])
        recent-log (vec (take-last 100 log))
        my-username (get-my-username)

        ;; Use extracted log analysis helpers
        opp-end-indices (core/find-end-turn-indices recent-log my-username)
        last-opp-end-idx (last opp-end-indices)
        last-opp-end-turn (when last-opp-end-idx
                            (core/extract-turn-number (:text (get recent-log last-opp-end-idx))))

        my-start-indices (core/find-start-turn-indices recent-log :include-username my-username)
        last-my-start-idx (last my-start-indices)
        last-my-start-turn (when last-my-start-idx
                             (core/extract-turn-number (:text (get recent-log last-my-start-idx))))]

    (cond
      ;; No opponent end found (e.g. Game Start, Corp Turn 1)
      (nil? last-opp-end-idx)
      (boolean last-my-start-idx)

      ;; No start found at all?
      (nil? last-my-start-idx)
      false

      ;; Normal case: Start is after End
      (> last-my-start-idx last-opp-end-idx)
      true

      ;; Async/Edge case: Start is before End (in logs)
      (< last-my-start-idx last-opp-end-idx)
      (if (nil? last-my-start-turn)
        false
        (cond
          ;; I started a later turn (Async race: Start T2 logged before Opp End T1)
          (> last-my-start-turn last-opp-end-turn)
          true

          ;; Same turn numbers
          (= last-my-start-turn last-opp-end-turn)
          (if (= my-side :runner)
            true  ; Runner: Corp End T1 -> I Start T1. My Start T1 is "since" Corp End T1.
            false) ; Corp: I Start T1 -> Runner End T1. My Start T1 is NOT "since" Runner End T1.

          :else
          false)))))


(defn start-turn!
  "Start your turn (gains clicks, Corp draws mandatory card).
   Validates that opponent has finished their turn to prevent desync.

   Validates:
   - It's actually your turn (checks :active-player)
   - Opponent has 0 clicks remaining
   - Opponent's end-turn appears in recent log
   - You don't already have clicks (prevents double-start)

   Returns {:status :error} if validation fails, {:status :success} if successful."
  []
  ;; ONE snapshot: the guard and the body must classify the same state. Reading
  ;; the atom twice reopened the check/use race — a leave/resync landing between
  ;; the two reads gives the body a nil side and the NPE is back (second-pass
  ;; guest catch).
  (let [snapshot @state/client-state]
   (if-not (state/my-side-kw snapshot)
    ;; #127 (behavioural sweep): with a board cached but no seat, `my-clicks`
    ;; read nil and the arithmetic below NPE'd before any guard could refuse.
    (refuse-no-seat! "start-turn")
    (let [client-state snapshot
        gameid (:gameid client-state)
        my-side (state/my-side-kw client-state)
        opp-side (if (= my-side :runner) :corp :runner)
        my-clicks (get-in client-state [:game-state my-side :click])
        opp-clicks (get-in client-state [:game-state opp-side :click])
        turn-number (get-in client-state [:game-state :turn] 0)
        log (get-in client-state [:game-state :log])
        recent-log (take-last 50 log)
        ;; IMPORTANT: Check that OPPONENT ended, not just that someone ended
        ;; This prevents Corp from ending and immediately starting again
        my-username (get-my-username)
        opp-ended? (some #(let [text (:text %)]
                            (and text
                                 (str/includes? text "is ending")
                                 (or (nil? my-username)
                                     (not (str/includes? text my-username)))))
                        recent-log)
        ;; Upstream's two-phase end-turn pauses on :corp-post-discard / :runner-post-discard
        ;; when a card sets :force-post-discard-{self,opponent}. While active, end-turn-continue
        ;; has not run, so starting our turn would desync. Narrower than the removed prompt
        ;; guard (keyed on a specific state flag, not any prompt) so autoresolve-fisk-ftt is
        ;; unaffected.
        post-discard-active? (or (get-in client-state [:game-state :corp-post-discard :active])
                                 (get-in client-state [:game-state :runner-post-discard :active]))
        ;; Opponent's opening mulligan still pending (our own prompt is the
        ;; "waiting for opponent to keep/mulligan" window). Starting now wedges
        ;; the turn — see opponent-mulligan-pending?.
        opp-mulligan-pending? (opponent-mulligan-pending? client-state)
        ;; Our OWN opening mulligan, still unanswered. Nothing downstream
        ;; refuses this — see my-mulligan-pending? — so this guard is the only
        ;; thing standing between the seat and a six-card starting hand.
        own-mulligan-pending? (my-mulligan-pending? client-state)
        ;; Turn 0 special case: no end-turn yet, both at 0 clicks (or nil before game starts)
        ;; CRITICAL: Must check turn = 0, otherwise Corp ending turn 1 looks like first-turn!
        is-first-turn? (and (= turn-number 0)
                           (or (nil? my-clicks) (= my-clicks 0))
                           (or (nil? opp-clicks) (= opp-clicks 0))
                           (not opp-ended?))]

    (cond
      ;; NO GAME STATE — the guard end-turn! already has, and start-turn! did not.
      ;; resync-game! clears :game-state but PRESERVES :gameid, so in that window
      ;; every value below reads as its falsy default: turn defaults to 0, clicks
      ;; are nil, no opponent end-turn is in the (empty) log — which is precisely
      ;; the is-first-turn? signature. start-turn then went out on the preserved
      ;; gameid, and if the real game is still at the mulligan it reproduces #131
      ;; through the back door.
      ;;
      ;; This is why the :keep guard alone is not enough: my-mulligan-pending? is
      ;; keyed on `false?`, so an ABSENT flag reads "not pending". That is the
      ;; right answer to "does the flag say unresolved?" and the wrong answer to
      ;; "may I send?". Unknown state is not permission — it needs its own
      ;; refusal, ahead of every branch that sends. (Guest-panel CRITICAL.)
      ;; #142: `map?`, not `nil?` — see can-start-turn?. A truthy non-board got
      ;; past this guard and put `start-turn` on the wire.
      (not (state/board? (:game-state client-state)))
      (do
        (println "⛔ Refusing start-turn: no game state — there is no turn to start.")
        (print-no-board-cause! client-state)
        (core/with-cursor {:status :error :reason :no-game-state}))

      ;; ERROR: Post-discard consent phase still active — opponent (or we) haven't acknowledged
      ;; the end-of-turn pause yet, so end-turn-continue hasn't run.
      post-discard-active?
      (do
        (println "❌ ERROR: Previous turn still resolving post-discard phase")
        (println "   A card requires both players to pass priority before the turn truly ends")
        ;; This window has no timer: 'wait' alone never clears it (see
        ;; game.ai-phase-windows-test). Name the command that does.
        (println "   Use 'end-post-discard' to pass — then 'wait' if the opponent still has to")
        (core/with-cursor {:status :error :reason :post-discard-pending}))

      ;; ERROR: Bug #11 fix - Runner trying to start first turn (Corp always goes first)
      (and is-first-turn?
           (= my-side :runner))
      (do
        (println "❌ ERROR: It's not your turn")
        (println "   Corp always goes first in turn 1")
        (println "   Wait for Corp to start and complete their turn")
        (core/with-cursor {:status :error :reason :not-your-turn :expected-side "corp"}))

      ;; ERROR: Opponent hasn't finished their opening mulligan yet. Starting
      ;; now races ahead of mulligan resolution and wedges the turn.
      opp-mulligan-pending?
      (do
        (println "❌ ERROR: Opponent hasn't finished their opening mulligan yet")
        (println "   Starting now would race ahead of mulligan resolution and wedge your turn")
        (println "   Use 'wait' until they keep/mulligan, then start-turn")
        (core/with-cursor {:status :error :reason :opponent-mulligan}))

      ;; ERROR: WE haven't answered our own opening mulligan. This must refuse
      ;; before the is-first-turn? branch below, which sends unconditionally:
      ;; the engine has no ordering check, so the send really starts the turn
      ;; and really takes the mandatory draw while "Keep hand?" is still live.
      ;; The seat then keeps a six-card hand — a permanent, game-affecting
      ;; advantage taken by following our own "Ready to start your turn" hint.
      own-mulligan-pending?
      (do
        (println "❌ ERROR: You haven't answered your own opening mulligan yet")
        (println "   Starting now would take your mandatory draw with 'Keep hand?' still open")
        (println "   Use 'keep-hand' (or 'mulligan') first, then start-turn")
        (core/with-cursor {:status :error :reason :my-mulligan}))

      ;; ALLOW: First turn (turn 0) - no prior end-turn exists
      is-first-turn?
      (let [before-hand (count (get-in client-state [:game-state my-side :hand]))
            sent? (ws/send-message! :game/action
                                    {:gameid gameid
                                     :command "start-turn"
                                     :args nil})]
        (if-not sent?
          (do
            (println "❌ ERROR: Failed to send start-turn (server unreachable?)")
            (println "   Check the game server is running, then retry")
            (core/with-cursor {:status :error :reason :send-failed}))
          (do
            (Thread/sleep core/standard-delay)
            (core/show-turn-indicator)
            ;; For Corp, show what was drawn (mandatory draw) with card text
            (when (= my-side :corp)
              (let [after-state @state/client-state
                    hand (get-in after-state [:game-state :corp :hand])
                    after-hand (count hand)
                    new-card (last hand)
                    card-title (get new-card :title "Unknown")]
                (when (> after-hand before-hand)
                  (println (str "🃏 Drew: " card-title))
                  (core/show-card-on-first-sight! card-title))))
            (when-let [w (open-phase-window :phase-12)]
              (when (= (:owner w) my-side)
                (println "⏸️  Start-of-turn (phase 1.2) window is open — a card is holding it.")
                (println "   Your mandatory draw and ALL start-of-turn triggers have NOT happened yet.")
                (println "   Use any start-of-turn paid abilities now, then 'end-phase-12'.")))
            (core/with-cursor {:status :success}))))

      ;; ERROR: Already have clicks (turn already started)
      (> my-clicks 0)
      (do
        (println (format "❌ ERROR: Turn already started (%d clicks remaining)" my-clicks))
        (println "   Complete your turn before starting a new one")
        (core/with-cursor {:status :error :reason :turn-already-started :clicks my-clicks}))

      ;; ERROR: Opponent hasn't ended turn yet
      (> opp-clicks 0)
      (do
        (println (format "❌ ERROR: Opponent still has %d click(s)" opp-clicks))
        (println (format "   Wait for %s to finish their turn first" (name opp-side)))
        (core/with-cursor {:status :error :reason :opponent-has-clicks :opp-clicks opp-clicks}))

      ;; ERROR: Opponent end-turn not in recent log
      (not opp-ended?)
      (do
        (println "❌ ERROR: Opponent hasn't ended their turn yet")
        (println (format "   Recent log doesn't show %s ending turn" (name opp-side)))
        (println "   Wait for opponent to complete their turn")
        (core/with-cursor {:status :error :reason :opponent-not-ended}))

      ;; OK: All validations passed
      ;; Note: We don't check active-player because it doesn't switch until start-turn succeeds.
      ;; After opponent's end-turn, active-player is still opponent (Netrunner priority system).
      ;; The other checks (opp-clicks, opp-ended, my-clicks) are sufficient to prevent turn stealing.
      :else
      (let [before-hand (count (get-in client-state [:game-state my-side :hand]))
            sent? (ws/send-message! :game/action
                                    {:gameid gameid
                                     :command "start-turn"
                                     :args nil})]
        (if-not sent?
          ;; Send failed (e.g. server unreachable). Don't print the stale
          ;; "Ready to start your turn" indicator — that falsely looks like success.
          (do
            (println "❌ ERROR: Failed to send start-turn (server unreachable?)")
            (println "   Check the game server is running, then retry")
            (core/with-cursor {:status :error :reason :send-failed}))
          (do
            (Thread/sleep core/standard-delay)
            (core/show-turn-indicator)
            ;; For Corp, show what was drawn (mandatory draw) with card text
            (when (= my-side :corp)
              (let [after-state @state/client-state
                    hand (get-in after-state [:game-state :corp :hand])
                    after-hand (count hand)
                    new-card (last hand)
                    card-title (get new-card :title "Unknown")]
                (when (> after-hand before-hand)
                  (println (str "🃏 Drew: " card-title))
                  (core/show-card-on-first-sight! card-title))))
            (when-let [w (open-phase-window :phase-12)]
              (when (= (:owner w) my-side)
                (println "⏸️  Start-of-turn (phase 1.2) window is open — a card is holding it.")
                (println "   Your mandatory draw and ALL start-of-turn triggers have NOT happened yet.")
                (println "   Use any start-of-turn paid abilities now, then 'end-phase-12'.")))
            (core/with-cursor {:status :success})))))))))

;; ============================================================================
;; Phase windows (start-of-turn 1.2, and the forced post-discard pause)
;; ============================================================================
;;
;; The engine opens both windows on every turn boundary and, when no card is
;; holding one, closes it again in the same breath (turns.clj:136, :262) — which
;; is why tutorial-deck games never saw one. When a card DOES hold it open there
;; is no timer and no implicit exit: an explicit command is the only way out. The
;; reference client puts a button on each (`board.cljs/basic-actions`); we had
;; none, so a seat that hit one waited forever. Pinned in
;; game.ai-phase-windows-test.
;;
;; Which command to send is not a free choice — it mirrors the button:
;;   active player, no consent needed -> the plain "end" command
;;   consent needed (either seat)     -> the "pass-priority" command, and the
;;                                       window closes only once BOTH have passed.

(def ^:private phase-windows
  {:phase-12
   {:name "start-of-turn (phase 1.2)"
    :zone-keys {:corp :corp-phase-12 :runner :runner-phase-12}
    :end-command "end-phase-12"
    :pass-command "phase-12-pass-priority"
    :what-it-unblocks {:corp "the mandatory draw and every start-of-turn trigger"
                       :runner "the Runner's first click and every start-of-turn trigger"}}
   :post-discard
   {:name "end-of-turn (post-discard)"
    :zone-keys {:corp :corp-post-discard :runner :runner-post-discard}
    :end-command "end-post-discard"
    :pass-command "post-discard-pass-priority"
    :what-it-unblocks {:corp "the end of the Corp's turn"
                       :runner "the end of the Runner's turn"}}})

(defn open-phase-window
  "The open window of the given kind (:phase-12 / :post-discard), or nil.

   Returns {:owner :corp|:runner  ; whose phase it is — the active player
            :window {...}          ; the raw state map
            :requires-consent? bool
            :i-passed? bool}"
  [kind]
  (let [{:keys [zone-keys]} (get phase-windows kind)
        gs (get-in @state/client-state [:game-state])
        my-side (state/my-side-kw)]
    (some (fn [[owner k]]
            (let [w (get gs k)]
              (when (:active w)
                {:owner owner
                 :window w
                 :requires-consent? (boolean (:requires-consent w))
                 :i-passed? (boolean (get w my-side))})))
          zone-keys)))

(defn- close-phase-window!
  "Send the command that closes an open phase window, mirroring board.cljs."
  [kind]
  (let [{:keys [name end-command pass-command what-it-unblocks]} (get phase-windows kind)
        my-side (state/my-side-kw)
        ;; `name` is destructured above as the window's display String, so the
        ;; core fn must be qualified here (as it is at the not-my-window arm).
        opponent (some-> my-side clojure.core/name core/other-side)
        gameid (:gameid @state/client-state)
        {:keys [owner requires-consent? i-passed?] :as open} (open-phase-window kind)]
    (cond
      ;; No seat, no turn of ours to act on. This must come FIRST: with my-side
      ;; nil the two guards below both fall through on a consent-required window
      ;; (i-passed? reads (get w nil) => false, and the not-my-window arm is
      ;; disabled by the consent flag), so the seat would send a pass-priority
      ;; the server discards for a spectator and then report "✅ Passed".
      (nil? my-side)
      (refuse-no-seat! (:end-command (get phase-windows kind)))

      (nil? open)
      (do
        (println (format "❌ ERROR: No %s window is open" name))
        (println "   Nothing to close — check 'status' for what is actually blocking you")
        (core/with-cursor {:status :error :reason :no-window-open}))

      i-passed?
      (do
        (println (format "⏳ You have already passed the %s window" name))
        (println (format "   Waiting on %s to pass too — use 'wait'" opponent))
        (core/with-cursor {:status :waiting-input :reason :already-passed}))

      ;; The opponent's window, with no consent required, is not ours to close.
      (and (not= owner my-side) (not requires-consent?))
      (do
        (println (format "❌ ERROR: This %s window belongs to %s alone" name (clojure.core/name owner)))
        (println "   No card is forcing a shared window, so only they can close it — use 'wait'")
        (core/with-cursor {:status :error :reason :not-my-window}))

      :else
      (let [command (if requires-consent? pass-command end-command)
            sent? (ws/send-message! :game/action
                                    {:gameid gameid :command command :args nil})]
        (if-not sent?
          (do
            (println "❌ ERROR: Failed to send (server unreachable?)")
            (core/with-cursor {:status :error :reason :send-failed}))
          (do
            (Thread/sleep core/standard-delay)
            (let [still-open (open-phase-window kind)]
              (cond
                (nil? still-open)
                (do
                  (println (format "✅ Closed the %s window — this releases %s"
                                   name (get what-it-unblocks owner)))
                  (core/with-cursor {:status :success}))

                (:requires-consent? still-open)
                (do
                  (println (format "✅ Passed on the %s window" name))
                  (println (format "   It stays open until %s passes as well — use 'wait'" opponent))
                  (core/with-cursor {:status :waiting-input :reason :awaiting-opponent-pass}))

                :else
                (do
                  (println (format "⚠️  Sent '%s' but the %s window is still open" command name))
                  (println "   Check 'status'; this should not happen")
                  (core/with-cursor {:status :error :reason :window-still-open}))))))))))

(defn end-phase-12!
  "Close the start-of-turn (phase 1.2) window.

   Until this is sent, the active player's mandatory draw and ALL start-of-turn
   triggers have not happened, however many clicks have been spent."
  []
  (close-phase-window! :phase-12))

(defn end-post-discard!
  "Close the forced end-of-turn (post-discard) window, so the turn can actually end."
  []
  (close-phase-window! :post-discard))

(defn indicate-action!
  "Signal you want to use a paid ability (pauses game for priority window)"
  []
  (let [client-state @state/client-state
        gameid (:gameid client-state)]
    (ws/send-message! :game/action
                      {:gameid gameid
                       :command "indicate-action"
                       :args nil})))

(defn- clicks-left
  "Clicks remaining for my side, or nil if unknown."
  []
  (let [s @state/client-state]
    (get-in s [:game-state (keyword (:side s)) :click])))

(defn- my-vitals
  "[credits clicks hand-size] for my side — the cheap observable proof that an
   action actually did something."
  []
  (let [s @state/client-state
        side (keyword (:side s))]
    [(get-in s [:game-state side :credit])
     (get-in s [:game-state side :click])
     (count (get-in s [:game-state side :hand]))]))

(defn- wait-for-vitals-change!
  "Poll until my vitals differ from `before`, or the timeout expires. Returns
   true if something moved.

   Replaces a bare `(Thread/sleep medium-delay)` + compare. That pattern cannot
   tell 'the engine refused this' from 'the state diff has not landed yet': the
   client applies each diff atomically via differ/patch, so a not-yet-arrived
   update leaves EVERY field at its old value, which is indistinguishable from a
   refusal by inspection. Waiting for a change and only then concluding
   'refused' makes the negative meaningful — and it also returns as soon as the
   diff lands, so the happy path gets faster, not slower."
  [before timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (not= before (my-vitals)) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 50) (recur))))))

(defn repeat-action!
  "Run a click action up to `n` times, stopping early on failure or when the
   clicks run out.

   The command log showed these arrive in BURSTS, not as deliberate repeats:
   take-credit ran back-to-back 125 times at a 2s median (98% inside 5s),
   advance 74 times at 1s, draw 56 times. That is one intent typed N times
   because the command took no count. (Contrast `status`, which repeats at a
   15s median — that is a seat waiting, a different problem.)

   Stopping on click exhaustion is load-bearing, not defensive: the underlying
   actions call check-auto-end-turn!, so the click that empties the pool can END
   THE TURN mid-loop. Continuing would then fire actions into the opponent's
   turn. The clicks check is skipped before the first action because the turn may
   legitimately not be started yet (the actions auto-start it)."
  [n action! label]
  ;; Pre-flight: say up front that the count exceeds the clicks in hand, rather
  ;; than discovering it N-1 actions in. Only when the turn is already started
  ;; (clicks > 0) — at 0 clicks we cannot distinguish 'spent' from 'not started
  ;; yet', and the actions auto-start the turn, so refusing there would break
  ;; the first action of a turn.
  (let [avail (clicks-left)]
    (when (and (some? avail) (pos? avail) (> n avail))
      (println (format "⚠️  Asked for %d %s but only %d click(s) in hand — will stop at %d"
                       n label avail avail))))
  (loop [done 0]
    (cond
      (>= done n)
      (do (when (> n 1) (println (format "✅ Completed %d %s" done label)))
          (core/with-cursor {:status :success :data {:times done :requested n}}))

      (and (pos? done) (some? (clicks-left)) (not (pos? (clicks-left))))
      (do (println (format "⏹️  Stopped after %d of %d %s — no clicks left" done n label))
          (core/with-cursor {:status :partial :data {:times done :requested n}}))

      :else
      (let [r (action!)]
        (if (= :success (:status r))
          (recur (inc done))
          (do (when (pos? done)
                (println (format "⏹️  Stopped after %d of %d %s" done n label)))
              (update r :data merge {:times done :requested n})))))))

(defn phase-locked?
  "True while a phase-1.2 or post-discard window is open — board.cljs's
   `phase-locked`, which hides EVERY basic-action button. The engine does not
   check it, and `start-turn` grants the clicks BEFORE opening the phase-1.2
   window (game.core.turns), so 'clicks>0' is not evidence the action phase has
   begun (#152 guest panel: a credit click inside an Anson Rose window goes
   through)."
  []
  (boolean (or (open-phase-window :phase-12) (open-phase-window :post-discard))))

(defn ensure-can-act!
  "ensure-turn-started! AND not phase-locked — the pre-send predicate of every
   basic action (credit / draw / purge / remove-tag / trash-resource / run),
   mirroring board.cljs's `(and (not phase-locked) (playable? …))`. Prints the
   refusal and returns false when locked."
  []
  (and (ensure-turn-started!)
       (if (phase-locked?)
         (let [w (or (open-phase-window :phase-12) (open-phase-window :post-discard))
               kind (if (open-phase-window :phase-12) "start-of-turn (phase 1.2)" "end-of-turn (post-discard)")
               closer (if (open-phase-window :phase-12) "end-phase-12" "end-post-discard")]
           (println (format "⛔ Refusing: the %s window is open — basic actions are locked until it closes." kind))
           (println (format "   Use '%s' (the active player) or 'wait' if it is the opponent's window to close.%s"
                            closer (if (:requires-consent? w) " This one needs both players to pass." "")))
           false)
         true)))

(defn take-credit!
  "Click for credit (shows before/after).
   Auto-starts turn if needed (opponent has ended and we haven't started yet).

   With `n`, clicks for credit up to n times (stops early if clicks run out)."
  ([n] (repeat-action! n take-credit! "credit clicks"))
  ([]
  (if (ensure-can-act!)
    (let [client-state @state/client-state
          side (:side client-state)
          before-credits (get-in client-state [:game-state (keyword side) :credit])
          before-clicks (get-in client-state [:game-state (keyword side) :click])
          vitals-before (my-vitals)
          gameid (:gameid client-state)]
      (ws/send-message! :game/action
                        {:gameid gameid
                         :command "credit"
                         :args nil})
      (wait-for-vitals-change! vitals-before core/action-timeout)
      (let [client-state @state/client-state
            side (:side client-state)
            after-credits (get-in client-state [:game-state (keyword side) :credit])
            after-clicks (get-in client-state [:game-state (keyword side) :click])]
        (core/show-before-after "💰 Credits" before-credits after-credits)
        (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
        ;; NEITHER credits NOR clicks moved => the engine refused the action
        ;; (e.g. clicking for credit during a run). Reporting :success here is
        ;; the misleading-output bug: `take-credit` mid-run printed
        ;; "💰 Credits: 2 → 2" and still claimed success. Harmless-looking alone,
        ;; but a count argument then repeats the no-op and cheerfully reports
        ;; "Completed 2 credit clicks". Both signals together, because a state
        ;; sync arriving late could leave one of them briefly stale.
        (if (and (= before-credits after-credits)
                 (= before-clicks after-clicks))
          (do (println "❌ No credit gained — action was refused (in a run? not your turn?)")
              (core/with-cursor {:status :error
                                 :reason "Credit action had no effect"
                                 :data {:before-credits before-credits
                                        :before-clicks before-clicks}}))
          (do
            ;; Show turn indicator only if we won't auto-end (which shows its own)
            (when (> after-clicks 0)
              (core/show-turn-indicator))
            (check-auto-end-turn!)
            (core/with-cursor
              {:status :success
               :data {:before-credits before-credits
                      :after-credits after-credits
                      :before-clicks before-clicks
                      :after-clicks after-clicks}})))))
    (core/with-cursor {:status :error :reason "Failed to start turn"}))))

(defn draw-card!
  "Draw a card (shows before/after).
   Auto-starts turn if needed (opponent has ended and we haven't started yet).

   With `n`, draws up to n times (stops early if clicks run out)."
  ([n] (repeat-action! n draw-card! "draws"))
  ([]
  (if (ensure-can-act!)
    (let [client-state @state/client-state
          side (:side client-state)
          before-hand (count (get-in client-state [:game-state (keyword side) :hand]))
          before-clicks (get-in client-state [:game-state (keyword side) :click])
          ;; #127: through the authority, not a bare keyword-of-side.
          deck-count (get-in client-state [:game-state (state/my-side-kw client-state) :deck-count])
          vitals-before (my-vitals)
          gameid (:gameid client-state)]
     ;; #152: board.cljs enables Draw only while (pos? (:deck-count @me)).
     ;; (The basic action card's draw has :req (not-empty deck), so the engine
     ;; itself refuses the click-draw — only MANDATORY/effect draws deck the
     ;; Corp. The guard is a pure UI mirror that turns a silent engine no-op
     ;; into a named refusal; guest panel corrected my first, wrong rationale.)
     (if (and (some? deck-count) (not (pos? deck-count)))
       (do
         (println (format "❌ Cannot draw: your deck is empty (%s)."
                          (if (core/side= "Corp" side)
                            "R&D has no cards to draw"
                            "the stack has no cards to draw")))
         (core/with-cursor {:status :error :reason :deck-empty}))
      (do
      (ws/send-message! :game/action
                        {:gameid gameid
                         :command "draw"
                         :args nil})
      (wait-for-vitals-change! vitals-before core/action-timeout)
      (let [client-state @state/client-state
            side (:side client-state)
            hand (get-in client-state [:game-state (keyword side) :hand])
            after-hand (count hand)
            after-clicks (get-in client-state [:game-state (keyword side) :click])
            ;; Get the newly drawn card (last card in hand)
            new-card (last hand)
            card-title (get new-card :title "Unknown")]
        (println (str "🃏 Hand: " before-hand " → " after-hand " cards"))
        ;; Same no-op guard as take-credit!, and sharper here: "Drew: X" reads
        ;; the LAST card in hand, so a refused draw doesn't just claim success —
        ;; it names a card that was already there as the one just drawn.
        (if (and (= before-hand after-hand)
                 (= before-clicks after-clicks))
          (do (println "❌ No card drawn — action was refused (in a run? not your turn? deck empty?)")
              (core/with-cursor {:status :error
                                 :reason "Draw action had no effect"
                                 :data {:before-hand before-hand
                                        :before-clicks before-clicks}}))
          (do
            (println (str "   Drew: " card-title))
            (core/show-card-on-first-sight! card-title)
            (core/show-before-after "⏱️  Clicks" before-clicks after-clicks)
            (check-auto-end-turn!)
            (core/with-cursor {:status :success :card-drawn card-title})))))))
    (core/with-cursor {:status :error :reason "Failed to start turn"}))))

(defn- burn-clicks-for-credits!
  "Spend all remaining clicks taking credits. Used by force-end-turn.
   Returns the number of clicks burned."
  [gameid clicks]
  (when (> clicks 0)
    (println (format "💰 Burning %d click(s) for credits..." clicks))
    (dotimes [_ clicks]
      (ws/send-message! :game/action
                        {:gameid gameid
                         :command "credit"
                         :args nil})
      (Thread/sleep core/quick-delay)))
  clicks)

(defn- already-ended-this-turn?
  "True if our recent log contains 'is ending' from us — guard against
   sending a second end-turn message (which corrupts engine state)."
  [client-state]
  (let [log (get-in client-state [:game-state :log])
        recent-log (take-last 3 log)
        my-username (get-my-username)]
    (boolean
      (some #(let [text (:text %)]
               (and text
                    (str/includes? text "is ending")
                    my-username
                    (str/includes? text my-username)))
            recent-log))))

(defn- opponent-turn-underway?
  "Durable signal that our turn genuinely ended and the OPPONENT has taken over.
   Used so the end-turn self-heal won't re-send merely because our 'is ending'
   log line scrolled out of the recent window (vs. was actually rolled back):
   the same flurry of opponent log lines that could push our line out is itself
   proof the opponent is playing. True when the opponent has clicks, is the
   active player, or has a 'started their turn' line in the recent log."
  [client-state]
  (let [my-side (keyword (:side client-state))
        opp-side (if (= my-side :runner) :corp :runner)
        opp-clicks (get-in client-state [:game-state opp-side :click])
        active-player (get-in client-state [:game-state :active-player])
        log (get-in client-state [:game-state :log])
        recent (take-last 6 log)
        my-username (get-my-username)
        opp-started? (some #(let [t (:text %)]
                              (and t
                                   (str/includes? t "started their turn")
                                   (or (nil? my-username)
                                       (not (str/includes? t my-username)))))
                           recent)]
    (boolean
     (or (and opp-clicks (pos? opp-clicks))
         (and active-player (= active-player (name opp-side)))
         opp-started?))))

(defn end-turn-self-heal-decision
  "Decide what smart-end-turn! should do once it has re-read state after a short
   settle. The 'X is ending their turn' line can be an OPTIMISTIC client entry
   the server rolls back on a :game/error resync — observed when an end-turn is
   auto-fired during the unsettled window right after a last-click action whose
   resolution is still settling (a just-finished run/access, or an event like
   Wildcat Strike that forces the opponent to choose).

   Given the post-settle re-read, ANY of these means the turn really ended:
     :line-present?       our 'is ending' line still stands
     :opponent-underway?  turn moved on (our line may have scrolled out of window)
     :turn-advanced?      :turn incremented past where we started the end-turn
   If ALL are false the end-turn was rolled back (turn still open at 0 clicks) ->
   :resend, or the match deadlocks. :confirmed-ended must win on ANY evidence the
   turn ended, since a needless re-send is the double-end that corrupts engine
   state (per end-turn!'s Bug #2 guard)."
  [{:keys [line-present? opponent-underway? turn-advanced?]}]
  (if (or line-present? opponent-underway? turn-advanced?) :confirmed-ended :resend))

(defn recheck-end-turn-state
  "Re-read client state after a short settle and return the signals the self-heal
   decision needs: whether our end-turn log line still stands, whether the
   opponent has visibly taken over their turn, and whether :turn advanced past
   `entry-turn` (the turn number when we attempted the end). Pulled out as a
   public seam so smart-end-turn!'s self-heal branch is testable without racing
   real wire timing."
  [entry-turn]
  (Thread/sleep core/standard-delay)
  (let [cs @state/client-state]
    {:line-present? (already-ended-this-turn? cs)
     :opponent-underway? (opponent-turn-underway? cs)
     :turn-advanced? (boolean (and entry-turn
                                   (> (get-in cs [:game-state :turn] 0) entry-turn)))}))

(defn end-turn!
  "End turn (validates all clicks used unless forced).
   The game engine handles oversized hand by prompting for discard during end-turn.

   Options:
     :force - If true, burns remaining clicks for credits then ends turn
              (keeps game state consistent, unlike skipping clicks)

   Usage: (end-turn!)              ; Normal - errors if clicks remain
          (end-turn! :force true)  ; Forced - burns clicks, then ends"
  [& {:keys [force] :or {force false}}]
  (let [client-state @state/client-state
        ;; #127: through the authority, which LOWERCASES. The bare keyword-of
        ;; -:side derivation here was nil-guarded but not case-normalized, and
        ;; `reconnect-game!`
        ;; (the `make resume` path) writes a capitalized :side straight into
        ;; client-state. :Runner then misses [:game-state side-kw :click], so
        ;; `clicks` read nil and the no-game-state branch below fired on a
        ;; perfectly live game — telling the seat its game "has ended, been
        ;; purged, or the resync did not complete" and offering reset.sh, which
        ;; would destroy it. Note `my-turn?` two lines down already lower-cased
        ;; defensively; the lookup above it did not.
        side-kw (state/my-side-kw client-state)
        clicks (get-in client-state [:game-state side-kw :click])
        hand-size (count (get-in client-state [:game-state side-kw :hand]))
        max-hand-size (get-in client-state [:game-state side-kw :hand-size :total] 5)
        gameid (:gameid client-state)
        active-player (get-in client-state [:game-state :active-player])
        ;; nil active-player = pre-game / unpopulated mock; don't refuse on it.
        ;; nil side-kw must short-circuit too: `let` bindings all evaluate before
        ;; the cond below, so `(name nil)` here threw BEFORE the no-game guard
        ;; could refuse — making that guard unreachable for any state carrying an
        ;; active-player but no seat. Guest-panel catch; the NPE it was added to
        ;; kill simply moved two lines up.
        my-turn? (or (nil? active-player)
                     (nil? side-kw)
                     (= (str/lower-case (name side-kw))
                        (str/lower-case active-player)))]
    (cond
      ;; NO GAME STATE. Reached whenever the client has nothing to reason about:
      ;; a purged/ended lobby whose auto-resync failed, or a resync that landed
      ;; the game map without the side maps yet. `clicks` is nil there, and the
      ;; `(> clicks 0)` arm below threw a bare NullPointerException at the seat —
      ;; a stack trace carries no verdict and no recovery, so a seat can neither
      ;; act on it nor pattern-match it. Refuse, and refuse LOUDLY-BUT-CLEANLY:
      ;; with no game there is nothing to end, and pushing end-turn into the void
      ;; is how off-turn end-turns (the unrecoverable kind) get minted.
      (or (nil? clicks) (nil? side-kw))
      (do
        (println "⛔ Refusing end-turn: no game state — there is no turn to end.")
        (print-no-board-cause! client-state)
        (core/with-cursor {:status :error :reason :no-game-state}))

      ;; NO TURN IN PROGRESS (#133). The reference client renders End Turn only
      ;; while `:end-turn` is false (board.cljs `basic-actions`), and board.cljs
      ;; is the wire spec: the engine itself checks nothing. `:end-turn` is TRUE
      ;; from game creation (new-state ships it — that is what makes "Corp goes
      ;; first" fall out of the ordinary boundary rule) until the first
      ;; start-turn, and again from every end-turn until the next start-turn. So
      ;; while it is set there is no turn to end, whoever the active player is.
      ;;
      ;; This was not covered below. At turn 0 new-state's :active-player is
      ;; "runner", so the RUNNER seat passed the off-turn guard, the log held no
      ;; "is ending" line for the duplicate guard, clicks were 0, and the :else
      ;; arm SENT — the engine processed "ai-runner is ending their turn 0". That
      ;; phantom line then fed every log-scanning boundary predicate on both
      ;; seats, and the duplicate guard's "may have been rolled back" text is
      ;; what the issue was filed about. Refuse here, before the off-turn guard,
      ;; and say what the state IS rather than what it resembles: the Corp at
      ;; turn 0 is not "off-turn", it just has not started.
      (get-in client-state [:game-state :end-turn])
      (let [turn (get-in client-state [:game-state :turn] 0)
            post-discard? (or (get-in client-state [:game-state :corp-post-discard :active])
                              (get-in client-state [:game-state :runner-post-discard :active]))]
        (println "⛔ Refusing end-turn: no turn is in progress to end.")
        (cond
          (my-mulligan-pending? client-state)
          (do (println "   The game has not started — you still owe your opening mulligan decision.")
              (println "💡 Use: keep-hand  (or mulligan)"))

          (opponent-mulligan-pending? client-state)
          (do (println "   The game has not started — waiting for the opponent to keep or mulligan.")
              (println "💡 Use: wait"))

          (= 0 turn)
          (do (println "   Turn 1 has not started yet — the Corp goes first.")
              (println (if (= side-kw :corp) "💡 Use: start-turn" "💡 Use: wait")))

          post-discard?
          (do (println "   Your turn is ending, paused in the post-discard phase.")
              (println "💡 Use: end-post-discard"))

          my-turn?
          (println "   Your turn has already ended; the opponent has not started theirs yet.")

          :else
          (do (println "   The opponent's turn has ended and yours has not been started.")
              (println "💡 Use: start-turn")))
        (core/with-cursor {:status :error :reason :no-turn-in-progress :turn turn}))

      ;; OFF-TURN GUARD (game 02995207, turn 8). An end-turn sent while we are NOT
      ;; the active player ends the OPPONENT's turn, and the engine logs it under
      ;; OUR name — leaving no "<opponent> is ending" line at all. Every consumer
      ;; that derives turn state from the log then disagrees with :end-turn, and
      ;; the match wedges permanently. No game has ever been recovered from it.
      ;;
      ;; The Bug #2 guard below cannot catch this: it scans only the last 3 log
      ;; entries, so once the opponent takes a couple of actions our own "is ending"
      ;; line scrolls out of the window and the guard goes blind. This check keys on
      ;; :active-player instead, which does not scroll.
      (not my-turn?)
      (do
        (println "⛔ Refusing end-turn: it is not your turn.")
        (println (format "   Active player is %s; ending a turn you don't own corrupts engine state." active-player))
        (println "   If the game looks stuck, escalate to the umpire — do NOT re-send.")
        (core/with-cursor {:status :error :reason :not-my-turn :active-player active-player}))

      ;; #152: board.cljs disables End Turn while a phase-1.2 window is open
      ;; (`phase-locked`). The engine has no such check: an end-turn sent inside
      ;; the start-of-turn window discards and ends the turn with the action
      ;; phase never opened (the clicks ARE already granted — start-turn grants
      ;; them before opening the window — so a seat holding them could also
      ;; spend them there; see ensure-can-act!). Mirror the button.
      (open-phase-window :phase-12)
      (do
        (println "⛔ Refusing end-turn: the start-of-turn (phase 1.2) window is still open.")
        (println "   Ending now would skip your whole action phase (your clicks are already granted — the window just hasn't closed).")
        (println "   Use 'end-phase-12' (or pass priority if a card holds it open) and then take your turn.")
        (core/with-cursor {:status :error :reason :phase-12-open}))

      ;; Post-discard window open (a card forced it; :end-turn is still false and
      ;; our own "is ending" line may not be in the log yet): the turn is already
      ;; ENDING. A second end-turn here re-runs the discard step — the engine
      ;; gate (process-actions/guarded-end-turn) catches it, but deliverable 2 of
      ;; #152 is "refuse before the send".
      (open-phase-window :post-discard)
      (do
        (println "⛔ Refusing end-turn: your turn is already ending — the end-of-turn (post-discard) window is open.")
        (println "   Use 'end-post-discard' to finish it (or 'wait' if the opponent still has to pass).")
        (core/with-cursor {:status :error :reason :post-discard-open}))

      ;; Bug #2 guard: refuse to double-end the turn. The engine treats a
      ;; second end-turn message as state corruption and deadlocks the next
      ;; turn cycle (Run #4 1:11:32). Detected via our own recent log line.
      (already-ended-this-turn? client-state)
      (do
        (println "⚠️  Turn already ended — refusing duplicate end-turn (engine-corruption guard).")
        (println "   If the game is NOT advancing and you're still at 0 clicks, the prior")
        (println "   end-turn may have been rolled back — use smart-end-turn, which self-heals.")
        (core/with-cursor {:status :error :reason :already-ended}))

      ;; ERROR: clicks remaining and not forced
      (and (> clicks 0) (not force))
      (do
        (println (format "❌ ERROR: You still have %d click(s) remaining!" clicks))
        (println "   Use all clicks before ending turn, or use --force flag")
        (println "   Example: send_command end-turn --force")
        (core/with-cursor {:status :error :clicks-remaining clicks}))

      ;; FORCE: burn remaining clicks as credits first
      (and (> clicks 0) force)
      (do
        (burn-clicks-for-credits! gameid clicks)
        (Thread/sleep core/standard-delay)
        ;; Re-fetch state after burning clicks
        (let [client-state @state/client-state
              hand-size (count (get-in client-state [:game-state side-kw :hand]))]
          (when (> hand-size max-hand-size)
            (println (format "💡 Hand size %d exceeds max %d - game will prompt for discard" hand-size max-hand-size)))
          (ws/send-message! :game/action
                            {:gameid gameid
                             :command "end-turn"
                             :args nil})
          (Thread/sleep core/standard-delay)
          (core/show-turn-indicator)
          (core/with-cursor {:status :success :clicks-burned clicks})))

      ;; OK: all clicks used
      :else
      (do
        (when (> hand-size max-hand-size)
          (println (format "💡 Hand size %d exceeds max %d - game will prompt for discard" hand-size max-hand-size)))
        (ws/send-message! :game/action
                          {:gameid gameid
                           :command "end-turn"
                           :args nil})
        (Thread/sleep core/standard-delay)
        (core/show-turn-indicator)
        (core/with-cursor {:status :success})))))

(defn- arm-for
  "Build a deferred auto-end arm for CLIENT-STATE.

   Pinned to (gameid, turn, side) so a stale arm expires instead of firing an
   end-turn into somebody else's turn, plus a unique :token so that two arms for
   the SAME turn are never `=` — that token is what makes claim-deferred-arm!
   exclusive rather than ABA-vulnerable."
  [client-state side]
  {:turn (get-in client-state [:game-state :turn])
   :side side
   :gameid (:gameid client-state)
   :token (str (java.util.UUID/randomUUID))})

(defn- opponent-label
  "Human name of the other seat, for messages that must say WHO owes a decision.
   A message that only says 'a prompt is active' is what made #114 unreadable."
  [side]
  (if (= (keyword side) :corp) "Runner" "Corp"))

(defn check-auto-end-turn!
  "Proactively check if turn should auto-end after an action.
   Called automatically after clicks-consuming actions.

   Auto-ends when:
   - 0 clicks remaining
   - No active prompts
   - Not already ended (checks recent log)
   - No scorable agendas (Corp only)

   Note: Oversized hand is OK - game engine will prompt for discard during end-turn.
   This prevents the 'forgot to end-turn' stuck state.

   No side => no turn of ours to end, so this bails before reading the board
   (#127). It is called automatically after every clicks-consuming action, so
   the hand-rolled keyword-of-:side derivation it used to do threw a bare
   NPE at `(name side)` from INSIDE an install/play/advance whenever :side was
   nil — the state `leave-lobby!` leaves behind, or a REPL that never joined.
   `state/my-side-kw` is the guarded authority for this derivation (#125/#126)
   and also lowercases, which the hand-rolled copy did not: `reconnect-game!`
   writes a CAPITALIZED :side (\"Corp\"/\"Runner\") until the resync full-state
   normalizes it, and :Runner misses every [:game-state side ...] lookup while
   comparing \"Runner\" against an active-player of \"runner\" — a silent
   never-auto-ends, not a crash."
  []
  (let [client-state @state/client-state]
   (when-let [side (state/my-side-kw client-state)]
    (let [clicks (get-in client-state [:game-state side :click])
        prompt (get-in client-state [:game-state side :prompt-state])
        ;; :hand-count, the count the engine sends, with (count :hand) only as a
        ;; fallback.
        ;;
        ;; The old comment here justified this with fog of war — "our own hand
        ;; contents are hidden in wire state" — and that is FALSE. diffs.clj:
        ;;   (defn hand-summary [hand state same-side? side player]
        ;;     (if (or same-side? (:openhand player)) (cards-summary hand ...) []))
        ;; and the seat receives its own side's :corp-state / :runner-state, where
        ;; same-side? is true. Our own hand arrives with real cards; it is the
        ;; OPPONENT's that comes back []. (Our own DECK really is stripped —
        ;; deck-summary gates on :view-deck — so :deck-count stays mandatory there.)
        ;;
        ;; The marquee game B symptom the old comment cited — hand size reading 0,
        ;; so the discard forewarning never fired and the seat met the engine's
        ;; discard prompt unannounced — is better explained by the bug THIS commit
        ;; fixes: the side was derived by hand, so a capitalized :Corp missed the
        ;; [:game-state side :hand] lookup entirely and (count nil) is 0. Going
        ;; through my-side-kw is what actually closes it. (Review panel: correct
        ;; fix, wrong stated reason — and wrong load-bearing comments in this repo
        ;; have a track record of getting built on.)
        hand-size (or (get-in client-state [:game-state side :hand-count])
                      (count (get-in client-state [:game-state side :hand])))
        max-hand-size (get-in client-state [:game-state side :hand-size :total] 5)
        active-player (get-in client-state [:game-state :active-player])
        my-turn? (= (name side) active-player)
        ;; Check if WE already ended (not opponent) - prevents double auto-end.
        ;; Shared guard (see already-ended-this-turn?).
        already-ended? (already-ended-this-turn? client-state)
        ;; Active-run guard: never end turn while a run is in progress, even
        ;; with clicks=0 (paid abilities, breaker pumps, etc. can occur).
        active-run? (some? (get-in client-state [:game-state :run]))
        ;; Turn-started guard: prompt resolvers (mulligan keep-hand, etc.)
        ;; can fire this hook before our turn actually starts; firing end-turn
        ;; then is meaningless at best and confuses the engine at worst.
        turn-started? (turn-started-since-last-opp-end?)
        ;; Check for scorable agendas (Corp only)
        scorable-agendas (core/find-scorable-agendas)
        ;; End-of-turn paid window worth pausing for (#103, Corp only).
        eot-rezzables (core/find-eot-rezzable-cards)]

    (cond
      ;; Not our turn — never auto-end (issue #16: a forced prompt during the
      ;; opponent's turn must not trigger our end-turn).
      (not my-turn?)
      nil

      ;; Active run — never auto-end mid-run (defensive: callers should also
      ;; gate on this, but the check makes it impossible to leak).
      active-run?
      nil

      ;; Turn hasn't actually started — silent skip (don't even print).
      ;; This is the mulligan/pre-turn case from Run #5 take 3.
      (not turn-started?)
      nil

      ;; Have scorable agendas - DON'T auto-end!
      (seq scorable-agendas)
      (do
        (println "")
        (println "⚠️  Cannot auto-end turn: Agenda(s) may be scorable!")
        (doseq [agenda scorable-agendas]
          (println (format "   🎯 %s (%d/%d counters - SCORABLE!)"
                          (:title agenda)
                          (:counters agenda)
                          (:requirement agenda))))
        (println "💡 Review agendas and score if able, then manually end turn")
        (flush))

      ;; #114: the prompt is the OPPONENT's decision, not ours. A last click spent
      ;; on a card that hands the other seat a choice (Public Trail, tag punishment)
      ;; leaves us holding a :waiting pseudo-prompt with NO choices. The old code
      ;; lumped it in with real prompts and told us to `choose` — advice with no
      ;; referent, which sent the seat hunting and then into a wait loop.
      ;;
      ;; We still don't end the turn here: board.cljs' button-pane renders the
      ;; prompt div instead of basic-actions whenever a prompt is up, so a human
      ;; Corp in this spot has no End Turn button either. Instead ARM the re-check
      ;; — resume-deferred-auto-end! fires it off the diff that clears the prompt.
      ;; Without that arm the turn is orphaned at 0 clicks forever (Luna-vs-Luna
      ;; d840fc14 turn 10: both seats deadlocked until an umpire intervened).
      (and (= clicks 0)
           prompt
           (state/waiting-prompt-type? (:prompt-type prompt))
           (not already-ended?))
      (do
        (swap! state/client-state assoc :auto-end-deferred (arm-for client-state side))
        (println "")
        (println (format "⏸️  Turn not ended yet — the %s owes a decision (you are at 0 clicks)."
                         (opponent-label side)))
        (println (format "   Prompt: %s" (:msg prompt)))
        (println "   Nothing for you to do: you have no choices in this prompt.")
        (println "   Your turn will end automatically as soon as they resolve it.")
        (flush)
        (core/with-cursor {:status :waiting-for-opponent :prompt prompt}))

      ;; Has prompt blocking - notify user
      (and (= clicks 0)
           prompt
           (not already-ended?))
      (do
        (println "")
        (println "⚠️  Cannot auto-end turn: Active prompt must be resolved first")
        (println (format "   Prompt: %s" (:msg prompt)))
        (println "💡 Use 'prompt' command to see choices, or 'choose' to respond")
        (flush))

      ;; Paid-ability window (#103): the last click is spent, but the end-of-turn
      ;; window is still open and there is something to spend it on. Auto-ending
      ;; here silently throws that window away — marquee ac71ce63 lost a Nico
      ;; Campaign rez to it and only recovered by rezzing after the fact.
      ;;
      ;; Hand control back instead of ending. This cannot wedge autonomous play:
      ;; the bots end their turn through smart-end-turn! (the explicit command,
      ;; which still ends — see its :else branch), and this is only the automatic
      ;; hook that fires from inside card actions. A seat that wants the turn over
      ;; just says end-turn.
      (and (= clicks 0)
           (nil? prompt)
           (not already-ended?)
           (seq eot-rezzables))
      (do
        (println "")
        (println "⏸️  Holding the end-of-turn paid window (0 clicks, turn NOT ended)")
        (doseq [{:keys [title cost]} eot-rezzables]
          ;; "may", matching ai-core's wake guidance: the same detector feeds
          ;; both, and it errs generous on purpose (restricted recurring
          ;; credits), so one categorical copy would just relocate the overclaim.
          (println (format "   💰 %s may still be rezzable for %d¢ — check" title cost)))
        (println "💡 Rez now if you want it up on the Runner's turn, then 'end-turn'")
        (println "   (nothing to do? just 'end-turn')")
        (flush)
        (core/with-cursor {:status :paid-window :rezzable (mapv :title eot-rezzables)}))

      ;; Safe to auto-end
      (and (= clicks 0)
           (nil? prompt)
           (not already-ended?))
      (do
        (println "")
        ;; Say what was actually checked, and what is about to happen. The old line
        ;; asserted "no prompts" as a flat fact and then end-turn! immediately
        ;; produced the discard prompt — read as a desync by every seat that hit it
        ;; (#103 / Terra [184] item 4). "no prompts" was only ever a PRE-condition.
        (if (> hand-size max-hand-size)
          (do
            (println (format "💡 Hand size %d exceeds max %d - game will prompt for discard" hand-size max-hand-size))
            (println "💡 Auto-ending turn (0 clicks) — expect that discard prompt next"))
          (println "💡 Auto-ending turn (0 clicks, nothing pending)"))
        (flush)
        (end-turn!)))))))

(defn- claim-deferred-arm!
  "Atomically take ARMED off the client state. Returns true for the ONE caller
   that actually removed it, false for everyone else.

   This is the duplicate-end-turn interlock, and it has to be a claim rather than
   a check-then-clear. Diffs arrive in bursts and each armed one spawns a resume;
   two of them can read the same arm before either clears it, and both would then
   pass every downstream guard (the already-ended? log line has not come back yet)
   and send end-turn — which this codebase treats as unrecoverable engine
   corruption. Only the winner of this claim may proceed.

   Comparing against ARMED (rather than a blind dissoc) also stops a late thread
   from deleting a NEWER arm belonging to a later turn, which would silently
   re-open the very deadlock this fixes. Both failure modes were guest-panel
   CRITICALs on the first cut.

   The comparison relies on every arm carrying a unique :token — see arm-for.
   Without it the claim has an ABA hole: (gameid, turn, side) can be re-armed
   identically on the same turn (a card that hands the opponent two decisions in
   a row), so a second caller could match the re-armed value and win a claim the
   first caller had already taken."
  [armed]
  (let [[old _new] (swap-vals! state/client-state
                               (fn [s] (if (= (:auto-end-deferred s) armed)
                                         (dissoc s :auto-end-deferred)
                                         s)))]
    (= (:auto-end-deferred old) armed)))

(defn resume-deferred-auto-end!
  "#114 second half: re-run the auto-end check once the OPPONENT's decision clears.

   check-auto-end-turn! arms `:auto-end-deferred` when it declines to end over a
   :waiting pseudo-prompt. Nothing else re-evaluates that turn — maybe-auto-end-
   turn-after-prompt! only fires for the side that resolved a prompt, and the side
   that resolved this one is the opponent. So the orphaned turn needs a hook on
   incoming state: this is called from the :game/diff and :game/resync handlers.

   Safe to call on every state update: no arm means no work. The arm is pinned to
   the (gameid, turn, side) it was created for, so a stale one expires instead of
   firing an end-turn into somebody else's turn — the one unrecoverable mistake at
   this boundary (see end-turn!'s off-turn guard).

   The end-turn is gated on WINNING claim-deferred-arm!, not merely on having
   seen an arm — see that fn for why a check-then-clear is not enough."
  []
  ;; Read the arm fresh: never act on a value captured before we could have been
  ;; descheduled.
  (when-let [armed (:auto-end-deferred @state/client-state)]
    (let [client-state @state/client-state
          ;; #127: through the authority. This one never threw (every use is
          ;; nil-guarded) but it did not lowercase, so a capitalized :side made
          ;; the `prompt` lookup below read nil off :Runner — `still-waiting?`
          ;; then came back false and the #114 deferred resume would fire
          ;; end-turn while the opponent's prompt was in fact still up.
          side (state/my-side-kw client-state)
          gameid (:gameid client-state)
          turn (get-in client-state [:game-state :turn])
          active-player (get-in client-state [:game-state :active-player])
          my-turn? (boolean (and active-player
                                 side
                                 (= (str/lower-case (name side))
                                    (str/lower-case active-player))))
          prompt (get-in client-state [:game-state side :prompt-state])
          still-waiting? (boolean (and prompt
                                       (state/waiting-prompt-type? (:prompt-type prompt))))]
      (cond
        ;; Stale: the world moved on (different game, turn advanced, seat
        ;; changed, or it is no longer our turn). Drop the arm — never act on it.
        ;; The gameid pin matters because the arm now survives clear-game-state!:
        ;; turn numbers repeat across games, so (turn, side) alone would let an
        ;; arm from a dead game fire into a fresh one.
        (or (not= (:side armed) side)
            (not= (:turn armed) turn)
            (not= (:gameid armed) gameid)
            (not my-turn?))
        (do (claim-deferred-arm! armed)
            nil)

        ;; They haven't decided yet. Stay armed; most diffs in this window are
        ;; the opponent thinking out loud.
        still-waiting?
        nil

        ;; The block cleared. Only the thread that CLAIMS the arm may end the
        ;; turn; the losers of a concurrent burst fall out here having done
        ;; nothing. The winner then lets check-auto-end-turn! re-apply every
        ;; other guard (scorable agendas, paid window, already-ended) afresh.
        :else
        (when (claim-deferred-arm! armed)
          (check-auto-end-turn!))))))

(defn smart-end-turn!
  "Smart end-turn that checks if it's safe to end turn automatically.

   ✅ AUTO END-TURN when:
   - Turn has actually started (prevents premature end before start)
   - 0 clicks remaining
   - No active prompts (already handled mandatory discard, etc.)
   - No visible EOT triggers in installed cards

   ⚠️ PAUSE when:
   - Turn hasn't started yet
   - Active prompts (discard, ability choices)
   - Installed cards with end-of-turn effects
   - Credits/cards changed recently (possible EOT trigger)

   Usage: (smart-end-turn!)  ; Auto-end if safe, warn if not"
  []
  ;; ONE snapshot — see start-turn! for why the guard and the body may not read
  ;; the atom separately.
  (let [snapshot @state/client-state]
   (if-not (state/my-side-kw snapshot)
    ;; #127 (guest panel + behavioural sweep): this is the CLI's *recommended*
    ;; end-turn (dev/send_command:357) and what the heuristic bots call, and it
    ;; was the last unguarded copy. Its `my-turn?` binding is an `or` starting
    ;; with `(nil? active-player)`, which short-circuits away the throw when
    ;; there is no board at all — so it survived a bare sideless state and NPE'd
    ;; only on the one that still holds a cached board. That is exactly why the
    ;; sweep carries both fixtures.
    (refuse-no-seat! "smart-end-turn")
    (let [client-state snapshot
        side (state/my-side-kw client-state)
        clicks (get-in client-state [:game-state side :click])
        prompt (get-in client-state [:game-state side :prompt-state])
        hand-size (or (get-in client-state [:game-state side :hand-count]) 0)
        max-hand-size (or (get-in client-state [:game-state side :hand-size :total]) 5)
        installed (get-in client-state [:game-state side :installed])

        ;; Check if we've actually started our turn
        turn-started? (turn-started-since-last-opp-end?)

        ;; Off-turn guard input (see the cond's first branch).
        active-player (get-in client-state [:game-state :active-player])
        my-turn? (or (nil? active-player)
                     (= (str/lower-case (name side))
                        (str/lower-case active-player)))

        ;; Turn number at entry, so the self-heal re-read can detect a real
        ;; transition (:turn advanced) vs a rolled-back end-turn.
        turn (get-in client-state [:game-state :turn] 0)

        ;; Check if WE already ended (not opponent) - prevents double auto-end.
        ;; Shared guard so the initial check and the self-heal re-read agree.
        already-ended? (already-ended-this-turn? client-state)

        ;; Check for EOT-related conditions
        has-prompt? (some? prompt)
        over-hand-size? (> hand-size max-hand-size)

        ;; Simple heuristic: check if any installed card text contains "end of"
        ;; This is a rough approximation - not all cards are in client state with full text
        has-eot-trigger? (some (fn [card-list]
                                 (some (fn [card]
                                        (when-let [text (:text card)]
                                          (clojure.string/includes?
                                           (clojure.string/lower-case text)
                                           "end of")))
                                      card-list))
                              (vals installed))]

    (cond
      ;; OFF-TURN GUARD — mirrors end-turn!'s. Checked FIRST so the self-heal
      ;; below can never re-send into the opponent's turn. See end-turn! for why
      ;; this is fatal and why the log-scan guards cannot catch it.
      (not my-turn?)
      (do
        (println "⛔ Refusing end-turn: it is not your turn.")
        (println (format "   Active player is %s." active-player))
        (println "   If the game looks stuck, escalate to the umpire — do NOT re-send.")
        (core/with-cursor {:status :error :reason :not-my-turn :active-player active-player}))

      ;; Can't end: Turn hasn't started yet
      (not turn-started?)
      (do
        (println "⚠️  Cannot auto-end: Turn hasn't started yet")
        (core/with-cursor {:status :turn-not-started}))

      ;; Looks already-ended. But the "is ending" line can be an OPTIMISTIC entry
      ;; the server rolls back on a :game/error resync (end-turn auto-fired during
      ;; the unsettled priority window after a last-click opponent-decision event
      ;; like Wildcat Strike). Re-read after a short settle: a genuine line
      ;; persists (-> nothing to do); a rolled-back one vanishes (-> re-send, or
      ;; the agent-vs-agent match deadlocks with us stuck at 0 clicks and the
      ;; opponent correctly waiting on an end-turn that never landed).
      already-ended?
      (case (end-turn-self-heal-decision (recheck-end-turn-state turn))
        :confirmed-ended
        (do
          (println "ℹ️  Turn already ended — nothing to do.")
          (core/with-cursor {:status :already-ended}))
        :resend
        (do
          (println "↻ Prior end-turn was rolled back (resync) — re-sending end-turn.")
          (end-turn!)))

      ;; Can't end: clicks remaining
      (> clicks 0)
      (do
        (println "⚠️  Cannot auto-end: you still have clicks")
        (println (format "   %d click(s) remaining - use them or end-turn --force" clicks))
        (core/with-cursor {:status :clicks-remaining :clicks clicks}))

      ;; #114: opponent-owed prompt. "Resolve the prompt first" is false here —
      ;; there is nothing we can resolve. Arm the deferred re-check (a seat that
      ;; reaches this by typing end-turn explicitly, e.g. after a reconnect that
      ;; skipped the automatic hook, must still get the resume) and say who we
      ;; are actually waiting on.
      (and has-prompt? (state/waiting-prompt-type? (:prompt-type prompt)))
      (do
        (swap! state/client-state assoc :auto-end-deferred (arm-for client-state side))
        (println (format "⏸️  Cannot end yet — the %s owes a decision." (opponent-label side)))
        (println (format "   Prompt: %s" (:msg prompt)))
        (println "   You have no choices here. The turn ends automatically once they resolve it;")
        (println "   do NOT re-send end-turn.")
        (core/with-cursor {:status :waiting-for-opponent :prompt prompt}))

      ;; Pause: active prompt (discard, choices, etc.)
      has-prompt?
      (do
        (println "⚠️  Cannot auto-end: active prompt")
        (println (format "   Prompt: %s" (:msg prompt)))
        (println "   Resolve the prompt first, then end-turn manually")
        (core/with-cursor {:status :has-prompt :prompt prompt}))

      ;; Over hand size with no active discard prompt: end the turn anyway.
      ;; end-turn! triggers the engine's discard-to-hand-size prompt (see its
      ;; docstring), which the caller's prompt handler then resolves. Refusing
      ;; here deadlocks the autonomous loop, since the discard prompt only
      ;; appears AFTER end-turn is sent (the has-prompt? branch above already
      ;; pauses once that prompt exists).
      over-hand-size?
      (do
        (println (format "ℹ️  Over hand size (%d > %d) - ending turn to trigger discard prompt"
                         hand-size max-hand-size))
        (end-turn!))

      ;; Warn: possible EOT trigger
      has-eot-trigger?
      (do
        (println "⚠️  Possible end-of-turn effect detected")
        (println "   Installed cards may have EOT triggers")
        (println "   Proceeding with end-turn (effects will resolve)")
        (end-turn!))

      ;; Safe: auto end-turn
      :else
      (do
        (println "✅ Auto-ending turn (0 clicks, no prompts)")
        (end-turn!)))))))

;; Keep old function names for backwards compatibility
(defn take-credits []
  (take-credit!))

(defn draw-card []
  (draw-card!))

(defn end-turn []
  (end-turn!))

;; ============================================================================
;; Tag and Virus Actions
;; ============================================================================

(defn remove-tag!
  "Runner action: Pay $2 + click to remove a tag.
   Returns {:status :success} or {:status :error :reason ...}"
  []
  (if (ensure-can-act!)
    (let [client-state @state/client-state
          side (:side client-state)]
      (if (not= (clojure.string/lower-case (or side "")) "runner")
        (do
          (println "❌ Only Runner can remove tags")
          (core/with-cursor {:status :error :reason "Only Runner can remove tags"}))
        (let [tags (get-in client-state [:game-state :runner :tag :base] 0)
              credits (get-in client-state [:game-state :runner :credit] 0)
              clicks (get-in client-state [:game-state :runner :click] 0)]
          (cond
            (< tags 1)
            (do
              (println "❌ No tags to remove")
              (core/with-cursor {:status :error :reason "No tags to remove"}))

            (< credits 2)
            (do
              (println "❌ Need $2 to remove tag (have $" credits ")")
              (core/with-cursor {:status :error :reason "Need $2 to remove tag"}))

            (< clicks 1)
            (do
              (println "❌ Need 1 click to remove tag (have " clicks ")")
              (core/with-cursor {:status :error :reason "Need 1 click to remove tag"}))

            :else
            (let [gameid (:gameid client-state)]
              (ws/send-message! :game/action
                                {:gameid gameid
                                 :command "remove-tag"
                                 :args nil})
              (Thread/sleep core/medium-delay)
              (let [new-state @state/client-state
                    new-tags (get-in new-state [:game-state :runner :tag :base] 0)
                    new-credits (get-in new-state [:game-state :runner :credit] 0)]
                (println (str "🏷️  Removed tag: " tags " → " new-tags " tags ($" credits " → $" new-credits ")"))
                (check-auto-end-turn!)
                (core/with-cursor {:status :success :tags-before tags :tags-after new-tags})))))))
    (core/with-cursor {:status :error :reason "Failed to start turn"})))

(defn purge-viruses!
  "Corp action: Spend 3 clicks to purge all virus counters.
   Returns {:status :success} or {:status :error :reason ...}"
  []
  (if (ensure-can-act!)
    (let [client-state @state/client-state
          side (:side client-state)]
      (if (not= (clojure.string/lower-case (or side "")) "corp")
        (do
          (println "❌ Only Corp can purge viruses")
          (core/with-cursor {:status :error :reason "Only Corp can purge viruses"}))
        (let [clicks (get-in client-state [:game-state :corp :click] 0)]
          (if (< clicks 3)
            (do
              (println "❌ Need 3 clicks to purge (have " clicks ")")
              (core/with-cursor {:status :error :reason "Need 3 clicks to purge"}))
            (let [gameid (:gameid client-state)]
              (ws/send-message! :game/action
                                {:gameid gameid
                                 :command "purge"
                                 :args nil})
              (Thread/sleep core/medium-delay)
              (println "🧹 Purged all virus counters")
              (check-auto-end-turn!)
              (core/with-cursor {:status :success}))))))
    (core/with-cursor {:status :error :reason "Failed to start turn"})))

(defn trash-resource!
  "Corp action: Pay $2 + click to trash a tagged runner's resource.
   Requires runner to be tagged. Creates a prompt to select which resource.
   Returns {:status :success} or {:status :error :reason ...} or {:status :waiting-input}"
  []
  (if (ensure-can-act!)
    (let [client-state @state/client-state
          side (:side client-state)]
      (if (not= (clojure.string/lower-case (or side "")) "corp")
        (do
          (println "❌ Only Corp can trash resources")
          (core/with-cursor {:status :error :reason "Only Corp can trash resources"}))
        (let [runner-tagged? (> (get-in client-state [:game-state :runner :tag :base] 0) 0)
              credits (get-in client-state [:game-state :corp :credit] 0)
              clicks (get-in client-state [:game-state :corp :click] 0)]
          (cond
            (not runner-tagged?)
            (do
              (println "❌ Runner must be tagged to trash resources")
              (core/with-cursor {:status :error :reason "Runner not tagged"}))

            (< credits 2)
            (do
              (println "❌ Need $2 to trash resource (have $" credits ")")
              (core/with-cursor {:status :error :reason "Need $2 to trash resource"}))

            (< clicks 1)
            (do
              (println "❌ Need 1 click to trash resource (have " clicks ")")
              (core/with-cursor {:status :error :reason "Need 1 click to trash resource"}))

            :else
            (let [gameid (:gameid client-state)
                  old-prompt (state/get-prompt)]
              (ws/send-message! :game/action
                                {:gameid gameid
                                 :command "trash-resource"
                                 :args nil})
              (Thread/sleep core/medium-delay)
              ;; The engine answers with a SELECT prompt ("Choose a resource to
              ;; trash") on our :prompt-state. #151 item 1: this used to read
              ;; [:game-state :corp :prompt] — not a wire key — so it was always
              ;; nil and "Trashed resource" printed before anything was trashed
              ;; (false success, #109 family). eid-aware via core/new-prompt? so a
              ;; stale leftover prompt is not mistaken for this one.
              ;;
              ;; The engine ALWAYS opens that prompt (even with one eligible
              ;; resource — it does not auto-select), so "no new prompt" never
              ;; means "trashed"; it means the diff hasn't landed yet or the
              ;; action was rejected. Poll a little longer, then say so (guest
              ;; panel) — never claim success here.
              (let [new-prompt (loop [tries 0]
                                 (let [cur (state/get-prompt)]
                                   (cond
                                     (core/new-prompt? old-prompt cur) cur
                                     (< tries 20) (do (Thread/sleep 100) (recur (inc tries)))
                                     :else nil)))]
                (if (and new-prompt (not (state/waiting-prompt-type? (:prompt-type new-prompt))))
                  (do
                    (println (str "🗑️  " (:msg new-prompt)))
                    (doseq [[idx cid] (map-indexed vector (:selectable new-prompt))]
                      (println (format "     %d. %s" idx
                                       (or (:title (core/find-card-by-cid cid)) (str "[cid " cid "]")))))
                    (println "   → choose-card <N> to pick the resource")
                    (core/with-cursor {:status :waiting-input :prompt new-prompt}))
                  (do
                    (println "⚠️  No 'Choose a resource to trash' prompt appeared — nothing was trashed.")
                    (println "   The action may have been rejected or is still in flight: check `prompt` / `status` before retrying.")
                    (core/with-cursor {:status :error :reason "No trash-resource prompt appeared"})))))))))
    (core/with-cursor {:status :error :reason "Failed to start turn"})))

;; ============================================================================
;; Emergency Game State Fix (CHEATING - Use Only for Broken States!)
;; ============================================================================

(defn fix-credits!
  "⚠️  CHEATING: Manually adjust credits for either side.

   THIS IS ONLY FOR FIXING ACCIDENTALLY BROKEN GAME STATES!
   Using this during normal play is cheating.

   Usage:
     (fix-credits! 5)       ; Set YOUR credits to 5
     (fix-credits! -2)      ; Subtract 2 from YOUR credits (delta mode)
     (fix-credits! +3)      ; Add 3 to YOUR credits (delta mode)
     (fix-credits! \"corp\" 10)   ; Set Corp's credits to 10
     (fix-credits! \"runner\" 5)  ; Set Runner's credits to 5

   Args:
     amount - Target credit value OR delta (+N/-N as string)
     side   - Optional: \"corp\" or \"runner\" (defaults to your side)"
  ([amount]
   (fix-credits! nil amount))
  ([side-arg amount]
   (println "")
   (println "⚠️  ============================================================")
   (println "⚠️  WARNING: CHEATING - MANUAL CREDIT ADJUSTMENT")
   (println "⚠️  This command is ONLY for fixing accidentally broken game states!")
   (println "⚠️  Using this during normal play is cheating.")
   (println "⚠️  ============================================================")
   (println "")
   (let [client-state @state/client-state
         gameid (:gameid client-state)
         my-side (:side client-state)
         ;; Determine target side
         target-side (cond
                      (nil? side-arg) my-side
                      (string? side-arg) (clojure.string/lower-case side-arg)
                      :else my-side)
         ;; Get current credits for target
         current-credits (get-in client-state [:game-state (keyword target-side) :credit] 0)
         ;; Parse amount - could be absolute or delta
         [_ delta-amount]
         (cond
           ;; String starting with + or - is delta mode
           (and (string? amount) (re-matches #"[+-]\d+" amount))
           [true (Integer/parseInt amount)]
           ;; Number is absolute mode (calculate delta)
           (number? amount)
           [false (- amount current-credits)]
           ;; String number is absolute
           (string? amount)
           [false (- (Integer/parseInt amount) current-credits)]
           :else
           [false 0])]
     (println (format "   Target: %s (currently %d credits)" target-side current-credits))
     (println (format "   Change: %s%d credits" (if (pos? delta-amount) "+" "") delta-amount))
     (println (format "   Result: %d credits" (+ current-credits delta-amount)))
     (println "")
     ;; Send the change command
     (ws/send-message! :game/action
                       {:gameid gameid
                        :command "change"
                        :args {:key :credit
                               :delta delta-amount}})
     ;; Wait for state update
     (Thread/sleep 500)
     (let [new-credits (get-in @state/client-state [:game-state (keyword target-side) :credit] 0)]
       (println (format "✅ Credits adjusted: %d → %d" current-credits new-credits))
       {:status :success
        :side target-side
        :old-credits current-credits
        :new-credits new-credits
        :delta delta-amount}))))

;; ============================================================================
;; Debug Helpers for Testing Discard Pile Interactions
;; ============================================================================

(defn discard-card!
  "DEBUG HELPER: Trash a card from hand to discard pile.
   Useful for testing effects that interact with Archives/Heap.

   Usage: (discard-card! \"Hedge Fund\")

   Returns {:status :success :card-discarded <name>} or {:status :error ...}"
  [card-name]
  (let [client-state @state/client-state
        side (:side client-state)
        side-kw (keyword side)
        hand (get-in client-state [:game-state side-kw :hand])
        card (first (filter #(= card-name (:title %)) hand))
        gameid (:gameid client-state)]
    (if card
      (do
        (ws/send-message! :game/action
                          {:gameid gameid
                           :command "trash"
                           :args {:card card}})
        (Thread/sleep core/medium-delay)
        ;; Verify it moved
        (let [new-state @state/client-state
              in-discard? (some #(= card-name (:title %))
                               (get-in new-state [:game-state side-kw :discard]))]
          (if in-discard?
            (do
              (println (format "🗑️  Discarded: %s" card-name))
              (core/with-cursor {:status :success :card-discarded card-name}))
            (do
              (println (format "⚠️  Trash command sent but card may not have moved: %s" card-name))
              (core/with-cursor {:status :error :reason "Card did not move to discard"})))))
      (do
        (println (format "❌ Card not in hand: %s" card-name))
        (core/with-cursor {:status :error :reason (str "Card not found: " card-name)})))))

(defn draw-to-card!
  "DEBUG HELPER: Draw cards until a specific card appears in hand.
   Returns error if card not found after drawing entire deck or running out of clicks.
   Max 45 draws as safety limit.

   Usage: (draw-to-card! \"Hedge Fund\")

   Returns {:status :success :card <name> :draws N} or {:status :error ...}"
  [card-name]
  (let [max-draws 45]
    (loop [draws 0]
      (let [client-state @state/client-state
            side (:side client-state)
            side-kw (keyword side)
            hand (get-in client-state [:game-state side-kw :hand])
            found (first (filter #(= card-name (:title %)) hand))
            ;; Use :deck-count - server doesn't expose actual deck contents
            deck-size (get-in client-state [:game-state side-kw :deck-count] 0)
            clicks (get-in client-state [:game-state side-kw :click] 0)]
        (cond
          found
          (do
            (println (format "✅ Found %s after %d draws" card-name draws))
            (core/with-cursor {:status :success :card card-name :draws draws}))

          (>= draws max-draws)
          (do
            (println (format "❌ Max draws (%d) reached, %s not found" max-draws card-name))
            (core/with-cursor {:status :error :reason "Max draws reached"}))

          (= deck-size 0)
          (do
            (println (format "❌ Deck empty, %s not found" card-name))
            (core/with-cursor {:status :error :reason "Deck empty"}))

          (<= clicks 0)
          (do
            (println (format "⏸️  Out of clicks after %d draws, %s not found (deck has %d cards)"
                           draws card-name deck-size))
            (core/with-cursor {:status :out-of-clicks :reason "Out of clicks" :draws draws :deck-remaining deck-size}))

          :else
          (do
            (draw-card!)
            (recur (inc draws)))))))

(defn find-card!
  "DEBUG HELPER: Multi-turn search for a card. Loops through turns until card is found.
   Pattern: draw until out of clicks -> discard -> opponent bot-turn -> repeat

   Usage: (find-card! \"Overclock\")

   Works for both sides:
   - Runner: Uses Corp bot for opponent turns
   - Corp: Uses Runner bot (ai-heuristic-runner) for opponent turns

   Max 10 turns as safety limit."
  [card-name]
  (let [max-turns 10
        my-side (:side @state/client-state)
        is-runner? (= "runner" (clojure.string/lower-case (str my-side)))]
    (println (format "🔍 Searching for %s as %s..." card-name my-side))
    (loop [turn 0]
      (println (format "\n🔍 Turn %d: Drawing for %s..." (inc turn) card-name))
      (let [result (draw-to-card! card-name)]
        (cond
          (= :success (:status result))
          (do
            (println (format "✅ Found %s after %d turn(s)" card-name (inc turn)))
            result)

          (>= turn max-turns)
          (do
            (println (format "❌ Max turns (%d) reached, %s not found" max-turns card-name))
            (core/with-cursor {:status :error :reason "Max turns reached"}))

          (= :out-of-clicks (:status result))
          (do
            ;; Discard to hand size
            (println "   Discarding to hand size...")
            (try
              (require '[ai-prompts :as prompts])
              ((resolve 'ai-prompts/discard-to-hand-size!))
              (catch Exception e
                (println (format "   ⚠️  Discard error: %s" (.getMessage e)))))
            (Thread/sleep 500)

            ;; Opponent takes a turn
            (if is-runner?
              (do
                (println "   Corp bot-turn...")
                (try
                  (require '[ai-heuristic-corp :as bot])
                  ((resolve 'ai-heuristic-corp/play-full-turn))
                  (catch Exception e
                    (println (format "   ⚠️  Corp bot error: %s" (.getMessage e))))))
              (do
                ;; No Runner bot yet - just spend clicks on credits
                (println "   Runner simple-turn (credits)...")
                (try
                  (require '[ai-heuristic-runner :as bot])
                  ((resolve 'ai-heuristic-runner/play-full-turn))
                  (catch Exception _
                    ;; Fallback: manually take credits
                    (dotimes [_ 4]
                      (take-credit!)
                      (Thread/sleep 100))
                    (end-turn!)))))
            (Thread/sleep 500)

            ;; Start new turn
            (println (format "   Starting new %s turn..." my-side))
            (start-turn!)
            (Thread/sleep 300)

            (recur (inc turn)))

          :else
          (do
            (println (format "❌ Unexpected error: %s" (:reason result)))
            result)))))))
