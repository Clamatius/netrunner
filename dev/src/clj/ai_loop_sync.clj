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

   `stale?` joins `board?` in that fast-path guard: it is a local flag (no IO),
   and when it is set the board we hold is already known not to track the
   server's, so acting on it is the thing the repair exists to prevent. This is
   deliberately NOT an attempt at #138's staleness *detection* problem — it only
   honours a divergence the client has already recorded.

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

(def default-verify-every-ms
  "How often a loop re-asks whether it is still seated, even while holding a
   healthy-looking board.

   The CLI gate runs that membership check on EVERY invocation. A loop with a
   board would otherwise run it never, and the local flags cannot close the gap
   alone: they fire only when the server bothers to announce something."
  60000)

(def initial-tracker
  "What a loop starts its sync tracker at. `:verified-at 0` makes the first tick
   due, so a loop confirms its seat once at startup rather than inheriting
   whatever a previous session left in the cache."
  {:attempts 0 :verified-at 0 :suspect? false})

;; ============================================================================
;; Pure decision core
;; ============================================================================

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
   tick's fast path takes it. It simply must not erase this tick's failure.

   `actable?` is the board AND the absence of every local invalidation signal,
   not just the board. A repair that returns `:synced` while `:lobby-gone?` or a
   dropped connection is STILL set has not actually repaired anything — a
   delayed lobby-list reply can launder exactly that (guest panel, MAJOR). Such
   a tick counts as a failed attempt, so it is bounded rather than looping.

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
      actable?    :have-board
      board-after? :resync-failed   ;; a board, but a signal the repair did not clear
      :else       :no-game)))

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

(defn due-for-verification?
  "Has the membership TTL expired? Pure, so tests drive the clock."
  [{:keys [verified-at]} now verify-every-ms]
  (>= (- now (or verified-at 0)) verify-every-ms))

(defn next-step
  "Which of the three paths this tick takes: `:repair` (destructive — may clear
   the board), `:probe` (a non-destructive membership sample), or `:free` (no IO
   at all).

   Two things force `:repair` rather than a sample. A local invalidation is a
   FACT about this seat, not a noisy reading. And a positive `:attempts` means a
   repair is already in progress: sampling there would let the probe path's
   one-absence-is-survivable branch answer `:act` on a seat whose last repair
   FAILED — the same laundering hole in a third disguise, where the budget is
   never spent and the loop never reaches its stop."
  [invalidation tracker now verify-every-ms]
  (cond
    invalidation                 :repair
    (pos? (:attempts tracker 0)) :repair
    (or (:suspect? tracker)
        (due-for-verification? tracker now verify-every-ms)) :probe
    :else                        :free))

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
  "Escalate to the authority — this is the DESTRUCTIVE path: `sync-verdict!` may
   clear the cached board before requesting a replacement."
  [tracker max-attempts now]
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
        attempts' (next-attempts outcome (:attempts tracker 0))
        failed?   (= outcome :resync-failed)]
    {:action    (recovery-action outcome attempts' max-attempts)
     :outcome   outcome
     :verdict   verdict
     :repaired? (= outcome :have-board)
     ;; A FAILED repair must not restart the TTL — that was how a fast path got
     ;; back in front of an unfinished recovery.
     :tracker   {:attempts attempts'
                 :suspect? false
                 :verified-at (if failed? (:verified-at tracker 0) now)}}))

(def ^:dynamic *probe-reply-timeout-ms*
  "How long a membership probe waits for its OWN lobby-list reply before giving
   up and reporting `:unknown`."
  2000)

(defn membership
  "Ask the server whether this seat is still in its game, and say so HONESTLY:
   `:seated`, `:absent`, or `:unknown`.

   `verify-in-game!` cannot be used for this. It requests a lobby list, sleeps a
   fixed 500ms, then reads whichever cached list happens to be there — it never
   establishes that the read answers the request. A reply delayed past the sleep
   therefore reads as `:absent`, and consecutive probes inside one delay window
   all re-read the SAME stale value, so they are not independent samples at all
   (guest 3rd pass, CRITICAL). Two strikes drawn from one stale read are one
   strike wearing a hat.

   `:lobby-list-rev` is bumped by the `:lobby/list` handler, so waiting for it to
   ADVANCE is waiting for a reply that post-dates our request. If none arrives,
   that is `:unknown` — the server is not answering, which is not evidence that
   we were unseated, and must never be counted as though it were."
  []
  (let [rev0 (:lobby-list-rev @state/client-state 0)]
    (conn/request-lobby-list!)
    (if (loop [waited 0]
          (or (> (:lobby-list-rev @state/client-state 0) rev0)
              (when (< waited *probe-reply-timeout-ms*)
                (Thread/sleep 100)
                (recur (+ waited 100)))))
      (let [mine (:gameid @state/client-state)
            found (conn/find-our-game)]
        (if (and found mine (= (str mine) (str found))) :seated :absent))
      :unknown)))

(defn- probe!
  "Non-destructive membership check for a seat that LOOKS healthy.

   One fresh absence only ARMS suspicion and keeps playing; the seat re-probes
   next tick, and only a SECOND fresh absence escalates to the destructive
   repair. The repair CLEARS the board before requesting a replacement, so doing
   it on a schedule off a single reading would wreck healthy in-flight
   encounters about once a minute.

   `:unknown` — no reply arrived — changes nothing and costs nothing. Local
   invalidation signals never come here: they are facts, not samples."
  [tracker max-attempts now]
  (let [seen (try
               (membership)
               (catch InterruptedException e
                 (.interrupt (Thread/currentThread))
                 (throw e))
               (catch Exception e
                 (println "⚠️  Membership probe threw:" (.getMessage e))
                 :unknown))
        keep-playing (fn [tr]
                       {:action :act :outcome :have-board :verdict nil
                        :repaired? false :tracker tr})]
    (case seen
      :seated
      (keep-playing {:attempts 0 :suspect? false :verified-at now})

      :unknown
      (do (println "⚠️  No lobby reply to the membership check — assuming nothing.")
          (keep-playing (assoc tracker :verified-at now)))

      :absent
      (if (:suspect? tracker)
        (do (println "⚠️  Still not seated on a second, independent check — repairing.")
            (repair! tracker max-attempts now))
        (do (println "⚠️  Not seated in the lobby list; re-checking next tick before repairing.")
            (keep-playing (assoc tracker :suspect? true :verified-at now)))))))

(defn ensure-board!
  "The autonomous loops' entry to the same authority the CLI gate uses.

   CHEAP when healthy: with a board, no local invalidation signal, no repair in
   progress, and the membership TTL unexpired, this makes no round trip at all
   and says `:act`.

   `tracker` is `{:attempts n :verified-at ms :suspect? bool}`, carried by the
   caller's loop (start from `initial-tracker`). Returns:

     {:action    :act | :idle | :retry | :stop
      :outcome   :have-board | :no-game | :game-over | :game-gone | :resync-failed
      :tracker   the tracker to carry to the next tick — CARRY IT ON EVERY PATH
      :verdict   the raw sync-verdict! keyword, or nil if no repair was made
      :repaired? true when a repair ran AND produced the seat we now hold}"
  ([tracker] (ensure-board! tracker default-max-attempts default-verify-every-ms
                            (System/currentTimeMillis)))
  ([tracker max-attempts verify-every-ms now]
   (let [tracker (or tracker initial-tracker)]
     (case (next-step (local-invalidation @state/client-state) tracker now verify-every-ms)
       :repair (repair! tracker max-attempts now)
       :probe  (probe! tracker max-attempts now)
       :free   {:action :act :outcome :have-board :verdict nil :repaired? false
                :tracker tracker}))))

(defn report!
  "Print the one line a loop owes the reader for this tick, and return the map
   unchanged so it can be threaded straight into a `let`.

   The wording lives here rather than in each loop for the reason #144 exists:
   four copies of a thing drift, and the drifting copy is the one nobody is
   reading when a marquee game wedges at 3am.

   Deliberately SILENT on `:idle` (the healthy pre-game state, which can persist
   for many ticks) and on a routine `:act`. `repaired?` is only true when a
   repair actually ran and fixed something, so a routine TTL check no longer
   announces a recovery that never happened (guest 2nd pass, MINOR)."
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
