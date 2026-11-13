(ns ai-run-pass
  "The ONE gate for run automation that passes priority for the seat.

   pass-priority! is the only place CLIENT automation sends a `continue`. The
   three `send-continue!` copies (ai-runs, ai-run-runner-handlers,
   ai-run-corp-handlers) were deleted for it: they carried the same two guards
   (#75 waiting prompt, #98 already passed) and none of them looked at the seat's
   OWN blocking prompt, so a handler that forgot its own clause passed priority
   over a select the seat still owed (#302 runner approach, the Corp's Brân /
   Ansel install select, the --no-rez / rez-failed / rez-done declines).

   Exempt on purpose — the claim is \"the only place CLIENT automation sends
   continue\", not \"the only continue there is\":
   - force mode (ai-runs/handle-force-mode): `--force` bypasses every check by
     definition, and is the seat's manual escape hatch.
   - `continue --raw` (dev/send_command): a dumb socket send, typed by a seat.
   - the ENGINE's own auto-pass (`check-auto-no-action`,
     src/clj/game/core/runs.clj:199), which no client gate can see.
   dev/test/ai_run_priority_repro_test.clj has a structural check, and it is
   NARROW: it reads every form under dev/src/clj and flags a map literal whose
   :command is \"continue\" (or :continue / 'continue) and a
   (send-action! \"continue\" …) / (safe-action! \"continue\" …) call, outside
   this function and force mode. It cannot see a payload BUILT at runtime —
   (assoc {:gameid gameid} :command \"continue\"), a command held in a var or
   computed — so it catches the copy-paste of an old sender, not every possible
   one. The real guard is the behavioural matrix in that namespace (A1/A2/A3 and
   change2-*): every automated sender, driven on engine-built states.

   Deferred, and a known limit of ANY send-time gate: it cannot see a tick that
   a handler claims WITHOUT sending. The unrezzed-approach already-passed branch
   (ai-run-runner-handlers/handle-runner-approach-ice) returns
   :waiting-for-corp-rez while the Runner may hold a bad-publicity payment
   select (design r2 I7). That needs bad publicity during an unrezzed approach;
   the structural answer is to resolve own prompts before the run handlers."
  (:require [ai-websocket-client-v2 :as ws]
            [ai-core :as core]
            [ai-state :as state]))

(defn surface-blocking-prompt
  "Claim this tick and SURFACE our own prompt, instead of passing priority over it
   or declining silently (#301 round 1, the panel's CRITICAL — both vendors).
   Moved here from ai-runs unchanged in what it returns, so pass-priority! and
   the per-handler clauses build one surface.

   Why surface rather than decline: a decline returned nil, the chain fell
   through to a handler reporting a non-terminal `:waiting-for-opponent`, and
   the auto-continue loop re-ticked the same state to its iteration cap under a
   message naming the wrong side. `:decision-required` is terminal
   (`terminal-status?`), which stops the loop and hands the seat the prompt —
   the same answer #296 chose for full-break's bad-publicity payment: surface a
   prompt the client cannot answer correctly rather than guess at it.

   WHICH SHAPES reach this (round 3 K1): the engine will not queue a prompt
   unless it is `:waiting`/`:run`, its choices are a
   `:number`/`:card-title`/`:credit`/`:counter` map, or `(pos? (count choices))`
   (`show-prompt`, game.core.prompts), so a zero-choice \"other\" prompt is not
   engine-producible; it is a boundary case in ai-runs-test. In practice: a
   select (one card or many, or a lone Done with an empty :selectable — the
   bad-publicity payment of #296), or 2+ trivial choices. A one-card select or a
   lone-Done select is surfaced, NOT auto-answered: those windows never reached
   the auto-answer handlers before (they sent `continue` over the prompt
   instead), and #296 recorded \"surface, do not guess\" for payment selects.

   NOT for a `waiting` prompt. Callers exclude `state/waiting-prompt-type?`
   BEFORE reaching here (pass-priority! checks it first). A waiting prompt is the
   OPPONENT's checkpoint: surfacing it stops the loop on a prompt only they can
   clear (#301 round 2's MAJOR, both vendors).

   EXIT for the per-handler clauses that still call this directly
   (handle-initiation-auto-pass, handle-stalled-window-self-advance): they are
   redundant with pass-priority!'s own check now and are kept until a game
   series has run on the gate (deferred, plan v3)."
  [my-prompt window]
  (println (format "🛑 Holding our own prompt at the %s window - not passing priority over it" window))
  (when-let [msg (:msg my-prompt)]
    (println (format "   Prompt: %s" msg)))
  (println "   → answer it (e.g. `choose-value`/`choose-card`), then continue")
  {:status :decision-required
   :wake-reason :blocking-prompt-at-run-window
   :action :awaiting-own-prompt
   :message (format (str "Holding our own %s prompt at the %s window; passing priority over it "
                         "would be honoured by the engine and strand it (#301)")
                    (or (:prompt-type my-prompt) "unknown")
                    window)
   :prompt my-prompt})

(defn- window-label
  "The window a surface names: the run phase, else an encounter that outlived
   its run (#164), else \"run\"."
  [client-state]
  (or (get-in client-state [:game-state :run :phase])
      (when (core/encounter-window? client-state) "encounter-ice")
      "run"))

(defn own-blocking-prompt
  "The seat's own prompt when it is one the seat OWES (engine-blocking and not
   the opponent's `waiting` checkpoint), else nil. Read from LIVE state.
   pass-priority!'s check 2, and the automated fire senders' guard."
  []
  (let [p (state/get-prompt)]
    (when (and (state/engine-blocking-prompt? p)
               (not (state/waiting-prompt-type? (:prompt-type p))))
      p)))

(defn own-prompt-surface
  "pass-priority!'s check 2 on its own: the terminal surface when the seat holds
   a prompt it owes, else nil. For automation that acts for the seat with
   something other than a `continue` — the Corp's automated FIRE
   (`unbroken-subroutines`), which the engine refuses over a blocking prompt
   (game.core.actions/play-unbroken-subroutines) while the client used to report
   and latch it as fired (design r2 I6)."
  ([] (own-prompt-surface nil))
  ([window]
   (when-let [p (own-blocking-prompt)]
     (surface-blocking-prompt p (or window (window-label @state/client-state))))))

(defn sent?
  "True when a pass-priority! result is a continue that left the socket.
   Callers print \"passing…/continuing…\" only on this."
  [result]
  (boolean (and (= :action-taken (:status result)) (:sent result))))

(def waiting-prompt-result
  "pass-priority!'s check-1 result: our own prompt is the OPPONENT's `waiting`
   checkpoint, so nothing is sent. Non-terminal, so a loop keeps polling. Also
   what the Corp's automated fire returns over a waiting prompt, which the engine
   refuses a fire over as well (game.core.actions no-blocking-or-prevent-prompt?)."
  {:status :waiting-for-opponent
   :action :continue-suppressed-waiting-prompt
   :message "Own prompt is a waiting prompt — opponent is deciding; continue suppressed (#75)"})

(defn pass-priority!
  "Send the seat's `continue` for a run window, or say why not. Checks, in
   order, on LIVE state at send time:

   1. Own prompt is a `waiting` prompt (#75): the engine is mid-checkpoint on
      the OPPONENT and a continue from us re-fires that checkpoint, minting
      duplicate opponent prompts (the marquee-g2 Manegarm wedge). Send nothing;
      non-terminal :waiting-for-opponent, so a loop keeps polling.
   2. Own prompt is engine-blocking (`state/engine-blocking-prompt?`): the seat
      owes it an answer, and the engine HONOURS a continue sent over it
      (`continue` is not prompt-gated), stranding the prompt. Send nothing;
      return the terminal surface-blocking-prompt result. Before check 3, so a
      seat that already passed and THEN opened a prompt is told about the
      prompt, not told to wait for the opponent.
   3. Already passed this window (#98: engine :no-action names us), unless
      `:second-pass? true` — the #31 self-advance's deliberate second pass of an
      abandoned window (the engine's advance branch has no side check). Send
      nothing; non-terminal :waiting-for-opponent.
   4. Send, wait briefly for the diff, and report `:sent` — ws/send-message!
      returns false when the socket refused (reconnect exhausted), and a caller
      that latches \"I passed here\" must key on that, not on :action-taken
      (#150, #167).

   `:window` names the window in a check-2 surface; it defaults to the live run
   phase."
  [gameid & {:keys [second-pass? window]}]
  (let [cs @state/client-state
        my-prompt (state/get-prompt)]
    (cond
      (state/waiting-prompt-type? (:prompt-type my-prompt))
      waiting-prompt-result

      (own-blocking-prompt)
      (own-prompt-surface window)

      (and (not second-pass?)
           (core/i-already-passed-run-window? cs (:side cs)))
      {:status :waiting-for-opponent
       :action :continue-suppressed-already-passed
       :message "You already passed this window (engine :no-action records you) — opponent owes the decision; continue suppressed (#98)"}

      :else
      (let [sent (boolean (ws/send-message! :game/action
                                            {:gameid gameid
                                             :command "continue"
                                             :args nil}))]
        ;; Brief wait for the WebSocket diff, so the caller's next read is not stale.
        (Thread/sleep 100)
        {:status :action-taken
         :action :sent-continue
         :sent sent}))))
