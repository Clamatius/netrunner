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
   count of real attempts, not of ticks — 3 is already ~15s of trying."
  3)

(defn classify
  "Name the outcome of a boardless tick, given `sync-verdict!`'s verdict and
   whether a board is present AFTER it ran.

   `board-after?` outranks the verdict on purpose: the question a loop is asking
   is \"can I act now?\", and a board in hand answers it whatever route it
   arrived by.

   `:synced` with no board is NOT a failure. `sync-verdict!` returns `:synced`
   for a client with no `:gameid` at all, and for a seated-but-unstarted lobby —
   both are healthy states in which there is simply nothing to act in yet.
   Nothing was attempted, so nothing failed; counting these would bail a loop
   that is merely waiting for its game to begin."
  [verdict board-after?]
  (cond
    board-after?               :have-board
    (= verdict :game-over)     :game-over
    (= verdict :game-gone)     :game-gone
    (= verdict :resync-failed) :resync-failed
    :else                      :no-game))

(defn next-attempts
  "Consecutive-failure count carried to the next tick. Any outcome other than a
   failed resync resets it — the bound is on a RUN of failures, not on their
   lifetime total."
  [outcome attempts]
  (if (= outcome :resync-failed)
    (inc attempts)
    0))

(defn recovery-action
  "What the loop should do this tick.

     :act   - there is a board; run the normal tick body
     :idle  - no game to act in yet (never joined, or lobby not started);
              keep looping, take no action, count nothing
     :retry - the resync did not land; skip this tick's actions and try again
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
      [(format "🛑 RESYNC BAIL (%s): holding no board after %d rejoin attempts." my-name attempts)
       "   The game may well be alive — but this seat has nothing to act on, and"
       "   acting on a cleared cache is what the refusal protects against (#109)."
       "   Check './dev/send_command <side> status' and the game server, then restart the loop."])))

(defn ensure-board!
  "The autonomous loops' entry to the same authority the CLI gate uses.

   CHEAP when healthy: with a board cached and no recorded divergence this makes
   no round trip at all and simply says `:act`. Only a boardless (or
   known-divergent) seat pays for `sync-verdict!`.

   `attempts` is the consecutive-failure count carried by the caller's loop.
   Returns:
     {:action    :act | :idle | :retry | :stop
      :outcome   :have-board | :no-game | :game-over | :game-gone | :resync-failed
      :attempts  count to carry to the next tick
      :verdict   the raw sync-verdict! keyword, or nil if no round trip was made
      :repaired? true when a resync ran AND produced the board we now hold}"
  ([attempts] (ensure-board! attempts default-max-attempts))
  ([attempts max-attempts]
   (if (and (state/board? (state/get-game-state))
            (not (state/stale?)))
     {:action :act :outcome :have-board :attempts 0 :verdict nil :repaired? false}
     (let [verdict   (conn/sync-verdict!)
           board?    (state/board? (state/get-game-state))
           outcome   (classify verdict board?)
           attempts' (next-attempts outcome attempts)]
       {:action    (recovery-action outcome attempts' max-attempts)
        :outcome   outcome
        :attempts  attempts'
        :verdict   verdict
        :repaired? (= outcome :have-board)}))))

(defn report!
  "Print the one line a loop owes the reader for this tick, and return the map
   unchanged so it can be threaded straight into a `let`.

   The wording lives here rather than in each loop for the reason #144 exists in
   the first place: four copies of a thing drift, and the drifting copy is the
   one nobody is reading when a marquee game wedges at 3am.

   Deliberately SILENT on `:idle`. That is the healthy pre-game state — a seat
   whose lobby has not started yet — and it can persist for many ticks; a line
   per tick would bury the log that a human later has to read. `sync-verdict!`
   already narrates the ticks where it actually did something."
  [my-name {:keys [action outcome attempts repaired?] :as result}]
  (case action
    :act   (when repaired?
             (println "✅ Board recovered — resuming play."))
    :retry (println (format "⏳ No board to act on; rejoin attempt %d did not land — retrying."
                            attempts))
    :stop  (println (diagnostic my-name outcome attempts))
    :idle  nil)
  result)
