(ns ai-loop-sync
  "Boardless-recovery backstop for the autonomous loops (issue #144).

   `ai-connection/sync-verdict!` is the project's authority on \"is there a game
   to act in?\", and it is also the thing that REPAIRS a boardless seat. Until
   this namespace existed its only caller was `dev/send_command`'s
   ensure_connection — the CLI gate. The four autonomous loops
   (heuristic corp/runner, goldfish corp/runner) gated on `can-start-turn?`
   instead, a second and weaker gate of their own: the #31/#68/#117
   divergent-second-copy pattern in a new place.

   Consequence before this: a loop that landed boardless refused to act — which
   is correct — but nothing in it could fix the state. It refused, waited,
   refused again, until an external `./dev/send_command` invocation happened to
   resync it, or `ai-stall`'s bail stopped the loop rather than recovering it.
   That is worst precisely for the un-babysat cross-model marquee games, where
   no human is issuing CLI commands to incidentally trigger the repair.

   ## Why the loops don't just call `sync-verdict!` every tick

   The issue's first-sketch fix was \"call `ensure-synced!` at the top of each
   iteration\". That is ruinous as written: whenever a `:gameid` is present
   `sync-verdict!` runs `verify-in-game!`, which is a lobby-list round trip plus
   a hard `(Thread/sleep 500)`. Loop ticks are ~500-1000ms, so a per-tick call
   would roughly double every tick and lobby-spam the server for the whole game.

   So the expensive authority is gated behind a CHEAP local predicate — the
   loops pay the round trip only when they are actually holding no board, which
   is the only state #144 is about. A healthy loop never pays it.

   The guard is every LOCAL invalidation signal, not just \"is there a board\":
   `stale?`, `lobby-gone?` and a dropped connection are flags the client has
   already recorded, free to read, and each marks a cached board as something
   other than a game to act in.

   What this deliberately does NOT do is re-verify SEAT MEMBERSHIP with the
   server on a timer. A loop therefore still cannot notice a silent unseat that
   the server never announces — see issue #145. Three guest passes went at a
   TTL-based membership check here and each found it unsound in a new way; the
   last one fatally, because a lobby-list query for an absent uid makes the
   server send a bare `:lobby/state`, which `mark-lobby-gone!` turns into a
   local \"fact\" that bypasses any confirmation step the probe wraps around
   itself. Correlating a reply to its request needs a protocol-level nonce in
   the shared connection layer, which is #145's job, not this one's.

   ## Why the retry is bounded

   An unbounded resync-retry loop is the failure mode `ai-stall` exists to stop:
   a seat that silently rejoins forever is no more debuggable than one that
   silently spins forever. Consecutive `:resync-failed` verdicts are counted and
   a bail is ordered at `default-max-attempts`, leaving an attributable
   diagnostic the way the stall backstop does.

   Pure decision core here; the IO (logging, stopping the loop) lives in the
   loops that call `ensure-board!`, matching `ai-stall`."
  (:require [clojure.string :as str]
            [ai-state :as state]
            [ai-connection :as conn]))

(def default-max-attempts
  "Consecutive FAILED repair attempts before a loop is told to stop.

   Each attempt is a full rejoin+resync (seconds of wall clock), so this is a
   count of real attempts, not of ticks."
  3)

(def initial-tracker
  "What a loop starts its sync tracker at, and threads through its own `recur`.

   It must ride EVERY path, including a tick whose body threw. Losing it on the
   `:act` path, and later losing it to a body exception, were two separate
   CRITICALs: a reset `:attempts` lets a later tick launder a repair that failed."
  {:attempts 0})

;; ============================================================================
;; Pure decision core
;; ============================================================================

(defn local-invalidation
  "The first purely LOCAL reason this seat is not actable, or nil. No IO.

   `board?` and `stale?` were the original two and were not enough (guest panel,
   CRITICAL): `mark-lobby-gone!` records a server-closed lobby while
   deliberately RETAINING the board, and `stale?` never looks at that flag. A
   board cached across a dropped socket is a snapshot, not a game."
  [st]
  (cond
    (not (state/board? (:game-state st))) :no-board
    (state/stale?)                        :diverged
    (state/lobby-gone? st)                :lobby-gone
    (not (:connected st))                 :disconnected
    :else                                 nil))

(defn next-step
  "Whether this tick consults the authority (`:repair` — DESTRUCTIVE, it may
   clear the cached board) or costs nothing (`:free`).

   A positive `:attempts` forces `:repair`: a repair is already in progress, and
   letting a later tick take the free path would reset the budget to zero, so a
   seat that could never be repaired would act on its cache forever and never
   reach its stop. That hole was closed three times in three different disguises;
   this is the shape that holds."
  [invalidation tracker]
  (if (or invalidation (pos? (:attempts tracker 0)))
    :repair
    :free))

(defn classify
  "Name the outcome of a tick that consulted the authority, from
   `sync-verdict!`'s verdict and whether the seat is actable AFTER it ran.

   The VERDICT outranks the board, and that ordering is the point (guest panel,
   CRITICAL — it was inverted in the first cut). `do-rejoin-resync!` returns
   `:resync-failed` from an arm that never reaches `resync-game!`, and
   `teardown-verdict` returns `:game-gone`/`:game-over` while deliberately
   READING the cached snapshot. In both, the old board is still sitting in
   :game-state. Letting its presence answer the loop's question turned a failed
   repair into `:act` on a board already known not to track the server's, reset
   the attempt budget, and — stale flag still set — did it again every tick. The
   `:game-gone` form is worse: it kept a seat playing into a destroyed game.

   A board that lands concurrently, just after a timeout, is not lost: the next
   tick's free path takes it. It simply must not erase this tick's failure.

   `actable?` is the board AND the absence of every local signal, not just the
   board. A repair that returns `:synced` while `:lobby-gone?` or a dropped
   connection is STILL set has repaired nothing — a delayed lobby-list reply can
   launder exactly that (guest panel, MAJOR). Such a tick counts as a failed
   attempt, so it is bounded rather than looping.

   `:synced` with no board is NOT a failure: that is a client with no `:gameid`,
   or a seated-but-unstarted lobby. Both healthy, nothing attempted, nothing
   failed — counting them would bail a loop merely waiting for its game."
  [verdict actable? board-after?]
  (case verdict
    :game-over     :game-over
    :game-gone     :game-gone
    :resync-failed :resync-failed
    ;; :synced (and any unknown verdict — the authority is content)
    (cond
      actable?     :have-board
      board-after? :resync-failed   ;; a board, but a signal the repair did not clear
      :else        :no-game)))

(defn next-attempts
  "Consecutive-failure count carried to the next tick. Any other outcome resets
   it — the bound is on a RUN of failures, not a lifetime total."
  [outcome attempts]
  (if (= outcome :resync-failed)
    (inc attempts)
    0))

(defn recovery-action
  "What the loop should do this tick.

     :act   - actable; run the normal tick body
     :idle  - no game to act in yet (never joined, or lobby not started);
              keep looping, take no action, count nothing
     :retry - the repair did not land; skip this tick's actions, try again
     :stop  - stop the loop (game decided, game gone, or out of attempts)

   `attempts` is the count AFTER `next-attempts` has folded in this outcome."
  [outcome attempts max-attempts]
  (case outcome
    :have-board    :act
    :no-game       :idle
    :game-over     :stop
    :game-gone     :stop
    :resync-failed (if (>= attempts max-attempts) :stop :retry)))

(defn diagnostic
  "Multi-line bail diagnostic, in the shape `ai-stall`'s bails use: say what was
   concluded, then what a human can do about it."
  [my-name outcome attempts]
  (str/join "\n"
    (case outcome
      :game-over
      [(format "🏁 GAME OVER (%s): the board is gone because the game is DECIDED." my-name)
       "   Nothing to reconnect to — read the result with 'game-over-status'."]

      :game-gone
      [(format "🛑 GAME GONE (%s): the server no longer hosts this game." my-name)
       "   A rejoin was tried and the lobby is not there (teardown, or an idle purge)."
       "   Not recoverable from inside the loop — start a fresh game with ./dev/reset.sh"]

      ;; :resync-failed
      [(format "🛑 RESYNC BAIL (%s): no board to act on after %d rejoin attempts." my-name attempts)
       "   The game may well be alive — but this seat has nothing it can act on, and"
       "   acting on a cleared or divergent cache is what the refusal protects against (#109)."
       "   Check './dev/send_command <side> status' and the game server, then restart the loop."])))

;; ============================================================================
;; The one impure entry
;; ============================================================================

(defn- repair!
  "Escalate to the authority. DESTRUCTIVE: `sync-verdict!` may clear the cached
   board before requesting a replacement."
  [tracker max-attempts]
  (let [;; A recovery that THROWS is a recovery that FAILED. Carrying the count
        ;; past it — which is what the loops' own catch blocks do, correctly, for
        ;; their tick bodies — made the bound bypassable: a `sync-verdict!`
        ;; throwing every tick retried forever, and neither stall backstop
        ;; accumulates there (`own-turn-key` needs an :active-player, and a
        ;; boardless seat has none). Caught here, once, not in four catch blocks.
        ;;
        ;; InterruptedException is NOT that. `bot-loop-stop` stops a loop with
        ;; `future-cancel`, which interrupts it — most likely mid-`Thread/sleep`
        ;; inside `verify-in-game!`. Swallowing it as a failed repair would let a
        ;; cancelled loop keep running, and a later `bot-loop` would then have
        ;; TWO loops issuing actions for one seat. Restore the flag and rethrow.
        verdict   (try
                    (conn/sync-verdict!)
                    (catch InterruptedException e
                      (.interrupt (Thread/currentThread))
                      (throw e))
                    (catch Exception e
                      (println "⚠️  Resync attempt threw:" (.getMessage e))
                      :resync-failed))
        st        @state/client-state
        board?    (state/board? (:game-state st))
        actable?  (nil? (local-invalidation st))
        outcome   (classify verdict actable? board?)
        attempts' (next-attempts outcome (:attempts tracker 0))]
    {:action    (recovery-action outcome attempts' max-attempts)
     :outcome   outcome
     :verdict   verdict
     :repaired? (= outcome :have-board)
     :tracker   {:attempts attempts'}}))

(defn ensure-board!
  "The autonomous loops' entry to the same authority the CLI gate uses.

   CHEAP when healthy: with a board, no local invalidation signal, and no repair
   in progress, this makes no round trip at all and says `:act`.

   `tracker` is `{:attempts n}`, carried by the caller's loop (start from
   `initial-tracker`). Returns:

     {:action    :act | :idle | :retry | :stop
      :outcome   :have-board | :no-game | :game-over | :game-gone | :resync-failed
      :tracker   the tracker to carry to the next tick — CARRY IT ON EVERY PATH
      :verdict   the raw sync-verdict! keyword, or nil if no repair was made
      :repaired? true when a repair ran AND produced the seat we now hold}"
  ([tracker] (ensure-board! tracker default-max-attempts))
  ([tracker max-attempts]
   (let [tracker (or tracker initial-tracker)]
     (case (next-step (local-invalidation @state/client-state) tracker)
       :repair (repair! tracker max-attempts)
       :free   {:action :act :outcome :have-board :verdict nil :repaired? false
                :tracker tracker}))))

(defn report!
  "Print the one line a loop owes the reader for this tick, and return the map
   unchanged so it can be threaded straight into a `let`.

   The wording lives here rather than in each loop for the reason #144 exists:
   four copies of a thing drift, and the drifting copy is the one nobody is
   reading when a marquee game wedges at 3am.

   Deliberately SILENT on `:idle` (the healthy pre-game state, which can persist
   for many ticks) and on a routine `:act`."
  [my-name {:keys [action outcome tracker repaired?] :as result}]
  (let [attempts (:attempts tracker 0)]
    (case action
      :act   (when repaired?
               (println "✅ Board recovered — resuming play."))
      :retry (println (format "⏳ Not actable; rejoin attempt %d did not land — retrying."
                              attempts))
      :stop  (println (diagnostic my-name outcome attempts))
      :idle  nil))
  result)
