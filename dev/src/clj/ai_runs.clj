(ns ai-runs
  "Run mechanics - initiation, automation, and state management"
  (:require [ai-websocket-client-v2 :as ws]
            [ai-state :as state]
            [ai-core :as core]
            [ai-debug :as debug]
            [ai-prompts :as prompts]
            [ai-basic-actions :as basic]
            [ai-card-actions :as actions]
            [ai-run-tactics :as tactics]
            [ai-run-corp-decisions :as corp-decisions]
            [ai-run-corp-handlers :as corp-handlers]
            [ai-run-runner-handlers :as runner-handlers]))

;; ============================================================================
;; Run Strategy State
;; ============================================================================

;; Atom holding current run strategy flags.
;; Reset when new run starts, inherited by continue-run calls.
;;
;; Structure:
;; {:full-break true/false      ; Runner: auto-break all ICE
;;  :no-rez true/false          ; Corp: don't rez anything
;;  :rez #{\"Ice Wall\" ...}    ; Corp: auto-rez these ICE; pause on other unrezzed ICE
;;  :fire-unbroken true/false   ; Corp: auto-fire unbroken subs
;;  :force true/false}          ; Bypass all smart checks
(defonce run-strategy (atom {}))

;; Track last waiting status to suppress repeated output
(defonce last-waiting-status (atom nil))

;; Debug chat mode - when enabled, announces waits/actions in game chat
;; Enable with: (reset! ai-runs/chat-debug-mode true)
;; Or via env: CHAT_DEBUG=true
(defonce chat-debug-mode
  (atom (= "true" (System/getenv "CHAT_DEBUG"))))

;; Track last chat debug message to avoid spam
(defonce last-chat-debug (atom nil))

;; Prefix for debug chat messages (used to filter in wait functions)
(def debug-chat-prefix "🤖 ")

(defn debug-chat!
  "Send a debug message to game chat (if debug mode enabled).
   Includes timestamp and robot emoji prefix.
   Deduplicates repeated messages.
   Note: Messages start with debug-chat-prefix so wait functions can filter them."
  [msg]
  (when @chat-debug-mode
    (let [timestamp (-> (java.time.LocalTime/now)
                        (.format (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss")))
          ;; Prefix with robot emoji so wait functions can filter these out
          full-msg (str debug-chat-prefix "[" timestamp "] " msg)]
      ;; Only send if different from last message
      (when (not= @last-chat-debug msg)
        (reset! last-chat-debug msg)
        (let [gameid (:gameid @state/client-state)]
          (when gameid
            (ws/send-message! :game/action
                             {:gameid gameid
                              :command "say"
                              :args {:user "AI-debug" :msg full-msg}})))))))

(defn reset-strategy!
  "Clear run strategy (call when run ends)"
  []
  (reset! run-strategy {})
  (reset! last-waiting-status nil)
  (corp-handlers/reset-waiting-status!)
  (runner-handlers/reset-state!))

(defn set-strategy!
  "Merge new strategy flags into current strategy"
  [flags]
  (swap! run-strategy merge flags))

(defn get-strategy
  "Get current run strategy"
  []
  @run-strategy)

;; ============================================================================
;; Flag Parsing
;; ============================================================================

(defn parse-run-flags
  "Parse command-line style flags from arguments.
   Returns {:server \"HQ\" :flags {:full-break true :no-continue false ...}}

   Supported flags:
   --full-break      : Runner auto-breaks all ICE
   --tank <ice-name> : Runner pre-authorizes letting subs fire on specified ICE
   --tank-all        : Runner pre-authorizes letting subs fire on ALL ICE (yolo)
   --no-rez          : Corp doesn't rez anything
   --rez <ice-name>  : Corp auto-rezzes named ICE; PAUSES on other unrezzed ICE
   --fire-unbroken   : Corp auto-fires unbroken subs
   --no-continue     : Don't auto-continue after run start
   --force           : Bypass all smart checks (for continue-run)

   Usage:
   (parse-run-flags [\"hq\" \"--full-break\"])
   => {:server \"hq\" :flags {:full-break true}}

   (parse-run-flags [\"remote1\" \"--rez\" \"Ice Wall\" \"--fire-unbroken\"])
   => {:server \"remote1\" :flags {:rez #{\"Ice Wall\"} :fire-unbroken true}}"
  [args]
  (loop [remaining args
         server nil
         flags {}]
    (if (empty? remaining)
      {:server server :flags flags}
      (let [arg (first remaining)
            rest-args (rest remaining)]
        (cond
          ;; Server name (first non-flag arg)
          (and (nil? server) (not (clojure.string/starts-with? arg "--")))
          (recur rest-args arg flags)

          ;; Boolean flags
          (= arg "--full-break")
          (recur rest-args server (assoc flags :full-break true))

          (= arg "--tank-all")
          (recur rest-args server (assoc flags :tank-all true))

          ;; --tank <ice-name> (takes argument, can be repeated)
          (= arg "--tank")
          (if (empty? rest-args)
            (do
              (println "⚠️  --tank requires ICE name argument")
              (recur rest-args server flags))
            (let [ice-name (first rest-args)
                  current-tank-set (get flags :tank #{})]
              (recur (rest rest-args)
                     server
                     (assoc flags :tank (conj current-tank-set ice-name)))))

          (= arg "--no-rez")
          (recur rest-args server (assoc flags :no-rez true))

          (= arg "--fire-unbroken")
          (recur rest-args server (assoc flags :fire-unbroken true))

          (= arg "--fire-if-asked")
          (recur rest-args server (assoc flags :fire-if-asked true))

          ;; --return-on-signal: autonomous Corp loop opt-in - surface
          ;; :waiting-for-runner-signal instead of polling it internally.
          (= arg "--return-on-signal")
          (recur rest-args server (assoc flags :return-on-signal true))

          ;; --persistent: keep the defender loop alive across empty
          ;; opponent-priority windows during a run (sleep & recheck instead
          ;; of exiting), so a cross-model seat doesn't re-issue monitor-run
          ;; through every symmetric priority pass. Real decisions still wake it.
          (= arg "--persistent")
          (recur rest-args server (assoc flags :persistent true))

          (= arg "--no-continue")
          (recur rest-args server (assoc flags :no-continue true))

          (= arg "--force")
          (recur rest-args server (assoc flags :force true))

          ;; --since <cursor> (takes integer argument)
          (= arg "--since")
          (if (empty? rest-args)
            (do
              (println "⚠️  --since requires cursor argument")
              (recur rest-args server flags))
            (let [cursor-str (first rest-args)
                  cursor (try
                           (Long/parseLong cursor-str)
                           (catch NumberFormatException _
                             (println "⚠️  --since requires numeric cursor, got:" cursor-str)
                             nil))]
              (recur (rest rest-args)
                     server
                     (if cursor
                       (assoc flags :since cursor)
                       flags))))

          ;; --rez <ice-name> (takes argument)
          (= arg "--rez")
          (if (empty? rest-args)
            (do
              (println "⚠️  --rez requires ICE name argument")
              (recur rest-args server flags))
            (let [ice-name (first rest-args)
                  current-rez-set (get flags :rez #{})]
              (recur (rest rest-args)
                     server
                     (assoc flags :rez (conj current-rez-set ice-name)))))

          ;; --tactics <edn-string> (takes EDN map argument)
          (= arg "--tactics")
          (if (empty? rest-args)
            (do
              (println "⚠️  --tactics requires EDN map argument")
              (recur rest-args server flags))
            (let [tactics-str (first rest-args)
                  ;; Parse EDN outside the recur (can't recur inside try)
                  parsed (try
                           (clojure.edn/read-string tactics-str)
                           (catch Exception e
                             (println "⚠️  --tactics EDN parse error:" (.getMessage e))
                             nil))
                  new-flags (if (map? parsed)
                              (assoc flags :tactics parsed)
                              (do
                                (when (and parsed (not (map? parsed)))
                                  (println "⚠️  --tactics must be a map, got:" (type parsed)))
                                flags))]
              (recur (rest rest-args) server new-flags)))

          ;; --tactics-file <path> (read EDN from file)
          (= arg "--tactics-file")
          (if (empty? rest-args)
            (do
              (println "⚠️  --tactics-file requires file path argument")
              (recur rest-args server flags))
            (let [file-path (first rest-args)
                  parsed (try
                           (clojure.edn/read-string (slurp file-path))
                           (catch java.io.FileNotFoundException _
                             (println "⚠️  --tactics-file not found:" file-path)
                             nil)
                           (catch Exception e
                             (println "⚠️  --tactics-file parse error:" (.getMessage e))
                             nil))
                  new-flags (if (map? parsed)
                              (assoc flags :tactics parsed)
                              (do
                                (when (and parsed (not (map? parsed)))
                                  (println "⚠️  --tactics-file must contain a map, got:" (type parsed)))
                                flags))]
              (recur (rest rest-args) server new-flags)))

          ;; Unknown flag
          (clojure.string/starts-with? arg "--")
          (do
            (println (format "⚠️  Unknown flag: %s" arg))
            (recur rest-args server flags))

          ;; Extra positional arg (error)
          :else
          (do
            (println (format "⚠️  Unexpected argument: %s (server already set to %s)" arg server))
            (recur rest-args server flags)))))))

;; ============================================================================
;; Forward Declarations
;; ============================================================================

(declare continue-run!)
(declare auto-continue-loop!)
(declare reset-window-grace!)
(declare reset-reported-events!)

;; ============================================================================
;; Run Initiation
;; ============================================================================

(defn run!
  "Run on a server with optional strategy flags (Runner only).
   Auto-starts turn if needed (opponent has ended and we haven't started yet).
   Accepts flexible server names and normalizes them automatically.
   By default, auto-continues run until a decision is needed.

   Central servers (case-insensitive):
   - hq, HQ → HQ
   - rd, r&d, R&D → R&D
   - archives → Archives

   Remote servers (flexible formats):
   - remote1, remote 1, r1, server1, server 1 → Server 1
   - remote2, r2, server2 → Server 2

   Strategy flags:
   --full-break      : Runner auto-breaks all ICE (no pauses for break decisions)
   --no-rez          : Corp doesn't rez anything (auto-declines all rez opportunities)
   --rez <ice-name>  : Corp auto-rezzes named ICE; other unrezzed ICE PAUSES for a rez decision (not auto-declined)
   --fire-unbroken   : Corp auto-fires all unbroken subroutines
   --no-continue     : Don't auto-continue after run initiation (stop at first decision)

   Usage:
   (run! \"hq\")                        ; Auto-continues till decision needed
   (run! \"remote1\" \"--full-break\")   ; Auto-breaks all ICE
   (run! \"hq\" \"--no-continue\")       ; Stop after initiation (rare)
   (run! \"remote1\" \"--rez\" \"Ice Wall\") ; Corp auto-rezzes Ice Wall, pauses on other unrezzed ICE"
  [& args]
  (if (not (core/side= "Runner" (:side @state/client-state)))
    (do
      (println "❌ Only Runner can run on servers")
      {:status :error :reason :wrong-side})
    (if (basic/ensure-turn-started!)
    (let [{:keys [server flags]} (parse-run-flags args)
          _ (when (nil? server)
              (throw (ex-info "No server specified" {:args args})))
          client-state @state/client-state
          gameid (:gameid client-state)
          initial-log-size (count (get-in @state/client-state [:game-state :log]))
          {:keys [normalized original changed?]} (core/normalize-server-name server)]

      ;; Reset and set strategy for this run
      (reset-strategy!)
      ;; Per-run scratch state. reset-window-grace! had NO production caller at
      ;; all — window-first-seen persisted across runs, so a repeat window
      ;; ([phase position no-action] collides readily) could look instantly
      ;; stale and self-advance without ever granting the grace period.
      ;; NB reported-events is deliberately NOT reset here: it is game-scoped,
      ;; and a per-run reset would re-report a previous run's tail event if it
      ;; were still inside the newest-3 window (a duplicate stale pause).
      (reset-window-grace!)
      (set-strategy! (dissoc flags :no-continue))  ; Store all except :no-continue

      ;; Provide feedback if we normalized the input
      (when changed?
        (println (format "💡 Normalized '%s' → '%s'" original normalized)))

      ;; Show active strategy flags
      (when (seq (dissoc flags :no-continue))
        (println (format "🎯 Strategy: %s"
                        (clojure.string/join ", "
                                           (map (fn [[k v]]
                                                  (if (set? v)
                                                    (str (name k) " " (clojure.string/join "," v))
                                                    (name k)))
                                                (dissoc flags :no-continue))))))

      ;; Show server access reminder (helps avoid mistakes like running R&D twice)
      (case normalized
        "R&D" (println "📚 R&D: Access top of Corp deck. Deck does NOT shuffle between runs!")
        "HQ"  (println "🃏 HQ: Access random card from Corp hand.")
        "Archives" (println "📦 Archives: Access all cards (facedown revealed on access).")
        nil)  ; Remote servers - no special reminder needed

      (ws/send-message! :game/action
                        {:gameid gameid
                         :command "run"
                         :args {:server normalized}})

      ;; Wait for "make a run on" log entry and echo it
      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (let [log (get-in @state/client-state [:game-state :log])
                new-entries (drop initial-log-size log)
                run-entry (first (filter #(clojure.string/includes? (:text %) "make a run on")
                                         new-entries))]
            (cond
              run-entry
              (do
                (println "🏃" (:text run-entry))
                ;; Auto-continue unless --no-continue flag set
                (if (:no-continue flags)
                  {:status :success
                   :data {:server normalized :log-entry (:text run-entry) :flags flags}}
                  (do
                    (println "⏩ Auto-continuing run...")
                    (Thread/sleep core/quick-delay)  ; Brief pause for state sync
                    (let [loop-result (auto-continue-loop!)]
                      (merge {:status :success
                              :data {:server normalized :log-entry (:text run-entry) :flags flags}}
                             {:run-result loop-result})))))

              (< (System/currentTimeMillis) deadline)
              (do
                (Thread/sleep core/polling-delay)
                (recur))

              :else
              (do
                (println "⚠️  Run command sent but no log confirmation (may have failed)")
                {:status :error
                 :reason "Run command sent but no log confirmation"}))))))
    {:status :error
     :reason "Failed to start turn"})))

;; ============================================================================
;; Continue-Run Helper Functions (Bug #12 Fix)
;; ============================================================================

;; Use core/current-run-ice for ICE lookup (single source of truth)

;; Run-event classifiers (#54). Naive substring matching (`includes? "rez"` etc.)
;; had two failure modes now that #52 made the window live:
;;   1. False POSITIVES — a card name / flavor line matched a bare substring
;;      ("Foxfire" → "fire", "Donut Taganes" → "tag", "derez" → "rez").
;;   2. A false NEGATIVE — the engine NEVER logs the word "fire" for a firing
;;      subroutine; the real message is "resolves N unbroken subroutine on X"
;;      (see resolve-unbroken-subs! in game/core/ice.clj), so the old `"fire"`
;;      matcher caught real subs *never*.
;; These regexes anchor on the actual engine wording with word boundaries.
(def ^:private rez-event-re
  ;; "X rezzes <ice>" (no cost) or "X spends N to rez <ice>" (with cost).
  ;; Word-boundaried so it excludes "derez"/"derezzes"/"rez cost" chatter.
  #"(?i)\brezzes\b|\bto rez\b")
(def ^:private ability-event-re #"(?i)\buses\b|\btriggers\b")
(def ^:private fired-event-re
  ;; The umbrella fire message is "resolves N unbroken subroutine on X". Anchor
  ;; on "unbroken subroutine" so a Runner BREAKING subs ("use Corroder to break
  ;; 1 subroutine on X") — which also contains "subroutine" — does NOT misfire.
  #"(?i)\bunbroken subroutine")
(def ^:private tag-damage-event-re #"(?i)\btags?\b|\bdamage\b")
(def ^:private event-negation-re
  ;; A line that MENTIONS a STATE-CHANGE keyword but negates/prevents it — the
  ;; state change did not happen, so it must not pause the run. Real engine
  ;; lines: "Corp does not do core damage with Zed 1.0", "prevent 1 meat
  ;; damage", "is not forced to rez", "avoid 1 tag".
  ;;
  ;; NB: applied to rez / fired / tag-damage ONLY, never to abilities. An
  ;; ability's "uses <card> to <effect>" line legitimately contains these words
  ;; when the effect IS a prevention (e.g. "uses EMP Device to prevent the Corp
  ;; from rezzing ..."); the ability still fired, so guarding it there would
  ;; drop real events (Codex review of #54).
  #"(?i)\bdoes not\b|\bdo not\b|\bcannot\b|\bprevents?\b|\bavoids?\b|\bimmune\b|\bnot forced\b")

(defn- text-matches?
  "True if entry's :text matches regex `re` (nil-:text safe)."
  [re entry]
  (boolean (re-find re (str (:text entry)))))

(defn- resolved-event-match?
  "Like `text-matches?` but rejects negated/prevented no-op lines (see
   `event-negation-re`). Safe ONLY for events whose log line is a direct state
   report that never embeds effect-description text: rez ('… is not forced to
   rez X') and tag-damage ('… does not do core damage'). NOT for abilities
   ('uses X to prevent …') or fired subs (the umbrella line embeds subroutine
   labels like Whirlpool's '… cannot jack out …') — those legitimately contain
   negation words while the event really happened (Codex review of #54)."
  [re entry]
  (let [text (str (:text entry))]
    (boolean (and (re-find re text)
                  (not (re-find event-negation-re text))))))

(defn get-rez-event
  "Find first rez event in log entries, or nil if none. nil-:text safe.
   Word-boundaried so 'derez' / 'rez cost' / 'not forced to rez' lines don't
   count as a rez (#54)."
  [log-entries]
  (first (filter #(resolved-event-match? rez-event-re %) log-entries)))

(defn extract-run-events
  "Scan the most recent log entries for notable run events (rez / ability /
   subs-fired / tag-or-damage) and return them as a context map.

   `log` is chronological, so we window the NEWEST entries via
   `(take 3 (reverse log))` — the same idiom used elsewhere in this namespace
   (see recently-passed detection ~line 749). The pre-#52 code used
   `(take 3 log)`, which read the three OLDEST (game-start) entries, so recent
   events were never seen from continue-run!'s handler context.

   Classification uses word-boundaried regexes against real engine wording
   (#54), not bare substrings, so card names / flavor / derez / sub-breaking
   don't misclassify. nil / missing `:text` entries are tolerated."
  [log]
  (let [n (count log)
        ;; Tag each entry with its INDEX in the append-only log. This is the
        ;; event's real identity: `handle-events` dedupes on it, and text +
        ;; timestamp alone cannot distinguish a genuine repeat (same card used
        ;; twice) from a re-read of the same entry. Index is stable across a
        ;; resync (the server log is authoritative), and if the log were ever
        ;; truncated the shifted key fails toward re-reporting — i.e. toward
        ;; pausing, never toward going blind. (Guest review of the #31 fix.)
        recent-log (map-indexed (fn [i e] (assoc e ::log-index (- n 1 i)))
                                (take 3 (reverse log)))]
    {:rez-event (get-rez-event recent-log)
     ;; ability + fired un-guarded: an ability's "uses X to prevent ..." effect
     ;; and a fired sub's embedded label ("... cannot jack out ...") legitimately
     ;; contain negation words while the event really fired.
     :ability-event (first (filter #(text-matches? ability-event-re %) recent-log))
     :fired-event (first (filter #(text-matches? fired-event-re %) recent-log))
     :tag-damage-event (first (filter #(resolved-event-match? tag-damage-event-re %) recent-log))}))

(def ^:private run-terminal-re
  ;; A log line that actually TERMINATES a run — the gate for #48's stale-menu
  ;; explanation. Only two engine wordings end a run with their own log line:
  ;;   - an end-the-run effect  "uses X to end the run" (game/cards/ice.clj)
  ;;   - a jack out             "jacks out"             (game/core/runs.clj)
  ;; A fired sub or a damage trash is NOT terminal on its own: a "do 1 net
  ;; damage" sub (Neural Katana) resolves and the run continues. So those are
  ;; used as CONTEXT (run-event-re below) only when a terminal line is present
  ;; — otherwise a normally-completed run (access, no ETR) would be mislabeled
  ;; "ended before your jack-out landed" (Codex review of #48).
  #"(?i)\bto end the run\b|\bjacks out\b")

(def ^:private run-event-re
  ;; Terminal enders PLUS the context lines worth showing alongside them: the
  ;; fired sub and the damage trash that led to the run ending. Word-boundaried
  ;; on real engine wording (the #54 lesson): "unbroken subroutine" excludes a
  ;; Runner BREAKING subs ("break 1 subroutine on X"); no bare "rez"/"tag".
  #"(?i)\bunbroken subroutine\b|\bto end the run\b|\bjacks out\b|\bdue to (?:net|meat|brain|core) damage\b")

(defn run-ending-log-lines
  "When a run just ended, return the recent log lines explaining it (newest
   window, chronological). Empty unless a TERMINAL run-ender ('to end the run'
   / 'jacks out') appears in the last few entries — so the caller can tell
   'the run ended out from under a stale encounter menu' (#48) from 'there was
   never a run' (a typo) or a run that simply completed via access. nil-/
   missing-:text safe.

   Windowed to the last 5 entries (the #48 firing sequence sits at the tail,
   sent right after the stale menu) so a *prior* run's end-the-run line, pushed
   back by a few economy logs, is not resurfaced (Codex review of #48)."
  [log]
  (let [recent (take-last 5 log)]
    (if (some #(re-find run-terminal-re (str (:text %))) recent)
      (->> recent
           (filter #(re-find run-event-re (str (:text %))))
           (mapv #(str (:text %))))
      [])))

(defn normalize-side
  "Normalize a side value to string. Handles keywords, strings, booleans, and nil."
  [side-value]
  (cond
    (nil? side-value) nil
    (false? side-value) nil  ; false is treated as "no one passed"
    (keyword? side-value) (name side-value)
    (string? side-value) side-value
    :else (str side-value)))

;; ============================================================================
;; Jack-out legality — mirror the human UI's gate
;; ============================================================================
;; The engine's `jack-out` (src/clj/game/core/runs.clj) performs NO phase check.
;; It trusts the client, and the human client (src/cljs/nr/gameboard/board.cljs)
;; renders the Jack Out button only outside a forced encounter and outside the
;; success phase, and ENABLES it only when:
;;     phase == "movement"  AND  :no-action != "runner"  AND  (not :cannot-jack-out)
;; `send_command jack-out` checked only "is there a run?", so a seat could take an
;; action no human can. Measured across 21 archived replays: 28 jack-outs, exactly
;; ONE legal. The encounter-ice cases (11 of them) are the damaging class — ending
;; a run mid-encounter means unbroken subroutines never resolve, so it is illegal
;; AND advantageous (replay ac71ce63, 2026-08-04: the Corp paid 5c to rez
;; Whitespace, the Runner jacked out of the encounter, the subs never fired).
;;
;; Kept as a pure fn over [:game-state] so it is testable without a live game and
;; so the refusal can name the legal move for the phase the seat is actually in.
;;
;; KNOWN LIMIT (guest panel, GPT-5.6) — this does NOT make an illegal jack-out
;; impossible, and should not be described as if it does:
;;
;;   1. It is a client-side check against a local snapshot, so it is TOCTOU. If our
;;      state is stale in the DANGEROUS direction (snapshot says movement, server
;;      has advanced to encounter-ice) the gate passes, the engine accepts, and the
;;      command even prints success. The refusal path cannot catch this case — by
;;      construction there is no refusal to read.
;;   2. Anything that reaches the engine another way is unaffected. `chat
;;      "/jack-out"` was exactly that (commands.clj checks side and run-existence
;;      but not phase, and should-process-command? permits it while holding a :run
;;      prompt, i.e. mid-encounter). send_command now refuses that spelling, but
;;      that is another client-side patch on the same open door, not a closure.
;;
;; The only authoritative fix is a legality check inside `jack-out` in the ENGINE
;; (src/clj/game/core/runs.clj). That changes rules enforcement for every player on
;; the server, not just our AI seats, so it is the project owner's call rather than
;; a side-effect of a harness fix. What this buys meanwhile: the paths a seat
;; actually takes are gated, and every refusal names the legal alternative.

(defn- normalize-phase
  "Run phase as a plain string. The wire sends strings, the engine and our own
   fixtures use keywords (memory engine-rate-of-change: wire shape is the volatile
   coupling). Neither shape may be silently read as legal."
  [phase]
  (cond
    (nil? phase) nil
    (keyword? phase) (name phase)
    :else (str phase)))

(def ^:private jack-out-phase-alternatives
  "What the seat should do INSTEAD, per phase where jack out is refused. A bare
   refusal is what pushed seats to invent recoveries in the first place, so every
   branch names a concrete command."
  {"initiation"
   {:why "the run has not reached a movement phase yet — the Corp has not had its rez window"
    :alt "`continue` to pass this window; you approach the outermost ICE once the Corp also passes."}
   "approach-ice"
   {:why "you are approaching ICE; jack out is not offered during an approach"
    :alt "`continue` to pass the approach. You may jack out at the movement window AFTER you pass the ICE."}
   "encounter-ice"
   {:why (str "you are mid-encounter, and leaving now would skip the unbroken subroutines "
              "entirely — no human client can do this")
    :alt "break the subroutines with an icebreaker, or `tank \"<ice>\"` to decline and let them fire."}
   "success"
   {:why "the run already succeeded; there is nothing left to jack out of"
    :alt "`continue` to breach and access."}})

(defn jack-out-legality
  "Can `side` legally jack out right now? Pure; `gs` is the [:game-state] map,
   `side` is \"runner\"/\"corp\".

   Mirrors the human UI gate exactly (board.cljs run-div) rather than the engine,
   because the engine has no gate at all. Returns
     {:legal? true}
   or
     {:legal? false :reason <kw> :message <str> :alternative <str>}
   with :reason one of :no-run :wrong-side :forced-encounter :cannot-jack-out
   :wrong-phase :already-passed.

   `side` is REQUIRED rather than defaulted: the Jack Out button exists only in
   runner-run-div, and the engine's process-action hands the socket's side
   straight to `jack-out` with no check of its own, so a Corp seat sending this
   command ENDS THE RUNNER'S RUN and logs \"<corp> jacks out\". A 1-arity that
   assumed \"runner\" would be exactly the silently-permissive default that made
   this bug possible in the first place."
  [gs side]
  (let [run   (:run gs)
        phase (normalize-phase (:phase run))
        na    (normalize-side (:no-action run))]
    (cond
      ;; Authorization comes FIRST — before the run even exists. "Only the Runner
      ;; can jack out" is true regardless of board state, whereas leading with
      ;; :no-run would answer a Corp seat "Start a run with `run <server>`",
      ;; implying that starting a run is what it lacks. The Corp cannot run at all.
      (not= "runner" (normalize-side side))
      {:legal? false :reason :wrong-side
       :message "Only the Runner can jack out — it is the Runner leaving their own run."
       :alternative (str "To end the run from the Corp side you need an end-the-run effect: rez ICE "
                         "with an ETR subroutine and let it fire.")}

      (nil? run)
      {:legal? false :reason :no-run
       :message "There is no active run."
       :alternative "Start a run with `run <server>`."}

      ;; A forced encounter (e.g. an ICE encountered outside a run) has no run-div
      ;; jack-out button at all.
      (:forced-encounter gs)
      {:legal? false :reason :forced-encounter
       :message "This is a forced encounter, not a normal run — there is no jack out here."
       :alternative "Resolve the encounter: break the subroutines, or `tank \"<ice>\"` to let them fire."}

      ;; Engine-level prevention (Ashigaru-likes, Ward, etc). The engine also
      ;; refuses this, but refusing here costs a round-trip less and explains why.
      (:cannot-jack-out run)
      {:legal? false :reason :cannot-jack-out
       :message "An effect in play prevents jacking out of this run."
       :alternative "Play the run out: `continue`, and break or `tank` what you meet."}

      (not= "movement" phase)
      (let [{:keys [why alt]} (get jack-out-phase-alternatives phase)]
        {:legal? false :reason :wrong-phase
         :message (format "Jack out is only legal in a movement window (between ICE). You are in `%s`%s."
                          (or phase "an unknown phase")
                          (if why (str " — " why) ""))
         :alternative (or alt "`continue` to advance the run to its next window.")})

      ;; Movement, but you already passed priority: the UI greys the button out,
      ;; because the window now belongs to the opponent. This is the state that
      ;; produced the two stall-bail jack-outs in replay 0b52266c.
      (= "runner" na)
      {:legal? false :reason :already-passed
       :message (str "You have already passed priority in this window — it is the Corp's "
                     "sub-step now, and the Jack Out button is disabled for a human here.")
       :alternative "`wait` for the Corp to pass. If it never does, escalate: `./dev/umpire-ping runner \"<what you tried / what you see>\"`."}

      :else {:legal? true})))

(defn jack-out-refusal-lines
  "Printable explanation for a refused jack out. Pure; returns a vector of lines.
   Always ends on a concrete next command — and, for the already-passed case, on
   the umpire (the judge button, issue #20), because that is the situation where a
   seat with no sanctioned recovery reaches for jack out instead."
  [{:keys [reason message alternative]}]
  (cond-> [(str "🚫 Jack out is not legal right now.")
           (str "   " message)
           (str "   → Instead: " alternative)]
    ;; The smell reminder belongs on the phase refusals, not on :no-run (where
    ;; there is nothing to abandon) — see run-priority-hint-lines for the same
    ;; framing on the display side.
    (#{:wrong-phase :cannot-jack-out :forced-encounter} reason)
    (conj "   (Jack out is a smell even when legal: the only real reasons are a misjudged entry cost or a Karunā jack-out sub.)")))

(defn opponent-indicated-action?
  "Check if opponent pressed indicate-action (WAIT button) in recent log.
   The WAIT button signals 'I'm about to do something, don't auto-pass'.
   Useful for both AI-vs-AI coordination and HITL (LLM thinking signal)."
  [state side]
  (let [log (get-in state [:game-state :log])
        opp-side (core/other-side side)
        opp-name (clojure.string/capitalize opp-side)
        ;; Look for "[!] Please pause, {Opponent} is acting."
        indicate-pattern (str "[!] Please pause, " opp-name " is acting")
        ;; Check LAST 5 entries (most recent)
        recent-log (take-last 5 log)]
    ;; Use includes? because log entries may have trailing punctuation
    (some #(clojure.string/includes? (str (:text %)) indicate-pattern) recent-log)))

(defn opponent-passed-priority?
  "Check if opponent passed priority recently (via log).
   Looks for 'AI-{opponent} has no further action' in recent log entries.
   This provides a second source of truth when :no-action state hasn't synced yet."
  [state side]
  (let [log (get-in state [:game-state :log])
        opp-side (core/other-side side)
        ;; Log uses "AI-runner" or "AI-corp" format
        opp-name (str "AI-" opp-side)
        pass-pattern (str opp-name " has no further action")
        ;; Check LAST 5 entries (most recent)
        recent-log (take-last 5 log)]
    ;; Use includes? because log entries may have trailing punctuation
    (some #(clojure.string/includes? (str (:text %)) pass-pattern) recent-log)))

(defn i-passed-priority?
  "Check if I passed priority recently (via log).
   Looks for 'AI-{my-side} has no further action' in recent log entries."
  [state side]
  (let [log (get-in state [:game-state :log])
        my-name (str "AI-" side)
        pass-pattern (str my-name " has no further action")
        ;; Check LAST 5 entries (most recent)
        recent-log (take-last 5 log)]
    ;; Use includes? because log entries may have trailing punctuation
    (some #(clojure.string/includes? (str (:text %)) pass-pattern) recent-log)))

(defn has-real-decision?
  "True if prompt has 2+ meaningful choices (not just Done/Continue),
   or has 1+ selectable cards (for 'select' type prompts like credit sources)."
  [prompt]
  (when prompt
    (let [choices (:choices prompt)
          selectable (:selectable prompt)
          non-trivial (remove (fn [choice]
                               (let [value (clojure.string/lower-case (:value choice ""))]
                                 (or (= value "continue")
                                     (= value "done")
                                     (= value "ok")
                                     (= value ""))))
                             choices)]
      (or (>= (count non-trivial) 2)
          (seq selectable)))))

(defn corp-has-rez-opportunity?
  "True if corp is at a rez decision point (approach-ice with unrezzed ice)"
  [state]
  (let [run-phase (get-in state [:game-state :run :phase])
        corp-prompt (get-in state [:game-state :corp :prompt-state])
        current-ice (core/current-run-ice state)]

    (or
      ;; Approaching unrezzed ICE - ALWAYS a rez opportunity
      (and (= run-phase "approach-ice")
           current-ice
           (not (:rezzed current-ice))
           corp-prompt)

      ;; Corp has explicit rez choices (upgrade/asset rez)
      (when corp-prompt
        (let [choices (:choices corp-prompt)]
          (some #(clojure.string/includes? (:value % "") "Rez") choices))))))

(defn is-waiting-prompt?
  "True if prompt is just a 'waiting' type prompt with no real decisions"
  [prompt]
  (and prompt
       (= (:prompt-type prompt) "waiting")))

(defn has-actionable-prompt?
  "True if we have a real prompt (not just 'waiting')"
  [prompt]
  (and prompt
       (not (is-waiting-prompt? prompt))))

(defn seat-owns-trigger-decision?
  "True if `my-prompt` is a decision THIS seat must resolve itself — an
   on-steal/on-score agenda trigger or similar choice (e.g. Send a Message's
   'you may rez a piece of ice, ignoring all costs') — as opposed to a
   run-priority paid-ability window (prompt-type \"run\", handled by the
   rez/continue path) or a passive \"waiting\" prompt.

   The persistent monitor's :no-action heuristic (handle-waiting-for-opponent)
   can mislabel such a trigger as an opponent-wait — it looks at run priority,
   not at whether WE are holding an unresolved prompt — and then sleep on it
   until the 300s timeout while the opponent is hard-blocked on our pending
   trigger (#43). A `select` prompt with no valid targets (all our ICE already
   rezzed) is the sharpest case: has-real-decision? is false (no selectable, only
   an implicit Done), so nothing upstream surfaces it. This predicate lets the
   loop return it as a decision so the seat can resolve it (rez a target / Done)."
  [my-prompt]
  (and my-prompt
       (not= "run" (:prompt-type my-prompt))
       (not= "waiting" (:prompt-type my-prompt))
       (or (= "select" (:prompt-type my-prompt))
           (seq (:choices my-prompt))
           (seq (:selectable my-prompt)))))

(defn should-i-act?
  "True if it's my turn to act during a run.
   Uses :no-action state as source of truth.

   Priority model during runs:
   - Runner is the active player (acts first in each phase)
   - :no-action tracks who has passed:
     - nil/false: Fresh phase, active player (Runner) should act
     - :runner/\"runner\": Runner passed, Corp should act
     - :corp/\"corp\": Corp passed, Runner should act (or phase advances)

   Returns nil if not in a run."
  [state side]
  (let [run (get-in state [:game-state :run])
        no-action (:no-action run)
        no-action-str (normalize-side no-action)
        active-player "runner"]  ; During runs, Runner is always active player
    (cond
      ;; No run = not applicable
      (nil? run) nil

      ;; State says I already passed → not my turn
      (= no-action-str side) false

      ;; State says opponent passed → my turn
      (= no-action-str (core/other-side side)) true

      ;; Fresh phase (nil or false) → active player acts first
      :else (= side active-player))))

(defn- i-already-passed?
  "True if :no-action records that I am the side that already passed this window."
  [state side]
  (= (normalize-side (get-in state [:game-state :run :no-action])) side))

(defn- attacked-server
  "The server map for the server currently being run, or nil if we cannot resolve
   it. Distinguishing \"server not found\" (unknown) from \"server found, root
   empty\" matters: the wire omits empty collections, so a missing :content on a
   server we CAN see proves an empty root, whereas a server we cannot see at all
   proves nothing."
  [state]
  (let [server (get-in state [:game-state :run :server])]
    (get-in state [:game-state :corp :servers (keyword (last server))])))

(defn opponent-has-run-decision?
  "Does the OPPONENT hold a REAL decision at this both-must-pass run window?

   Board-derivable with NO hidden information (issue #31, §1). This is the
   legitimacy test for self-advancing a stalled window: we may only advance past
   the opponent when the board proves they have nothing to decide. Answering
   'true' costs us nothing but a wait; answering 'false' wrongly would SKIP a
   real decision — that is the blunt `corp-auto-no-action` behaviour we rejected.
   So every case we cannot prove is conservatively `true`.

   SCOPE — read this before widening the card pool. What is modelled here is
   exactly ONE Corp decision: a REZ. That is the only run-window action the Corp
   can take in the System Gateway pool we play. The engine permits more, and a
   larger pool would break the equivalence: a rezzed Border Control's
   `[trash]: End the run` is a live decision at movement even when every root card
   is already rezzed, and this predicate would happily report 'no decision' and let
   the Runner walk past it. That does not bite today, but it is a property of the
   CARD POOL, not of this function, and it will not announce itself when the pool
   changes. Widening the pool means extending this predicate (rezzed cards with
   run-usable paid abilities) — not trusting it. The grace period in
   `handle-stalled-window-self-advance` is what keeps the blast radius survivable
   in the meantime: a Corp that is present still gets to take the action.

   Runner-side only. As Corp the opponent is the Runner, who always has live
   options at a window (jack out, break, paid abilities), so nothing is provable
   and we never self-advance.

   - initiation   : never a decision (no current ICE) — but that window is owned
                    by `handle-initiation-auto-pass` (#62), not this predicate.
   - approach-ice : a decision IFF the approached ICE is UNREZZED (Corp may rez).
                    Rezzed ⇒ the rez choice for this ICE is already spent.
   - movement     : at the server (position 0) a decision IFF an UNREZZED card
                    sits in the attacked server's root (an upgrade Corp may rez).
                    Mid-run movement (position > 0) is left conservative."
  [state side run-phase]
  (if-not (= side "runner")
    true
    (case run-phase
      "initiation" false

      ;; NOTE the nil handling in both branches. `current-run-ice` returns nil for
      ;; "no run / position 0 / position out of bounds / no ICE on the server" —
      ;; i.e. for every state in which we CANNOT SEE the approached ICE. Folding
      ;; that into `false` would turn "I can't tell" into "the Corp has nothing to
      ;; do", and we would skip a live rez window on the strength of a wire
      ;; transient (a diff applied out of order, an ICE trashed mid-run: any
      ;; disagreement between :position and the :ices vector). Absence of evidence
      ;; is not evidence of absence: unknown ⇒ assume a decision ⇒ wait.
      "approach-ice"
      (let [ice (core/current-run-ice state)]
        (if (nil? ice) true (not (:rezzed ice))))

      "movement"
      (if (zero? (or (get-in state [:game-state :run :position]) 0))
        ;; Same asymmetry: if we cannot even resolve the attacked SERVER, we know
        ;; nothing and must assume a decision. Only once the server is in hand does
        ;; an empty/absent :content prove there is no root card to rez.
        (if-let [server (attacked-server state)]
          (boolean (some #(not (:rezzed %)) (:content server)))
          true)
        true)

      ;; Anything else (encounter-ice, success, …): assume a real decision.
      true)))

(defn waiting-for-opponent?
  "True if my side is waiting for opponent to make a decision during a run.
   Uses the simple :no-action heuristic for reliability."
  [state side]
  (let [run (get-in state [:game-state :run])]
    (cond
      ;; No active run - not waiting
      (nil? run) false

      ;; CRITICAL: Opponent pressed WAIT button - ALWAYS pause
      (opponent-indicated-action? state side) true

      ;; Use the simple :no-action heuristic
      :else (let [my-turn? (should-i-act? state side)]
              (not my-turn?)))))

(defn waiting-reason
  "Returns human-readable reason for waiting"
  [state side]
  (let [run-phase (get-in state [:game-state :run :phase])
        current-ice (core/current-run-ice state)]

    (cond
      (and (= side "runner") (= run-phase "approach-ice") current-ice)
      (str "Corp must decide: rez " (:title current-ice) " or continue")

      (and (= side "corp") (= run-phase "encounter-ice"))
      "Runner must decide: break subroutines or take effects"

      :else
      "Waiting for opponent action")))

(defn can-auto-continue?
  "True if can safely auto-continue (empty paid ability window, my turn to act).
   Uses should-i-act? for reliable priority detection."
  [prompt run-phase side state]
  (and prompt
       (= (:prompt-type prompt) "run")
       (empty? (:choices prompt))
       (empty? (:selectable prompt))
       ;; Must be my turn to act (not already passed)
       (should-i-act? state side)
       ;; Corp at approach-ice with unrezzed ICE should NOT auto-continue
       ;; (rez decision is too important to auto-pass)
       (not (and (= side "corp")
                 (= run-phase "approach-ice")
                 (let [current-ice (core/current-run-ice state)]
                   (and current-ice (not (:rezzed current-ice))))))))

;; ============================================================================
;; Handler Functions for continue-run! Strategy Pattern
;; ============================================================================
;;
;; Each handler examines the context and either:
;; - Returns nil (not handled, try next handler)
;; - Returns a result map {:status ... :action ... ...}
;;
;; Handlers are tried in priority order until one returns non-nil.

(defn- send-continue!
  "Helper to send continue command and return action-taken result.
   Waits briefly for state to sync via WebSocket.

   Chokepoint guard (#75): if the LIVE state shows our own prompt is a
   'waiting' prompt, the engine is mid-checkpoint on the OPPONENT and a
   continue from us re-fires that checkpoint (duplicate-prompt minting — the
   marquee-g2 Manegarm wedge). Suppress and report an opponent wait instead.
   Same guard as the ai-run-corp-handlers copy.

   Second guard (#98): if the engine already recorded US as this window's
   passer (:no-action names us), the opponent owes the window — a repeat
   continue is a no-op that only feeds the stuck-detector's false alarm.
   `:second-pass? true` bypasses ONLY this guard: the #31 self-advance
   deliberately sends the window's SECOND continue after proving the
   opponent abandoned it (the engine's advance branch has no side-check).
   The waiting-prompt guard is never bypassed — a blocked checkpoint must
   not receive a continue from anyone (#75)."
  [gameid & {:keys [second-pass?]}]
  (cond
    (state/waiting-prompt-type? (:prompt-type (state/get-prompt)))
    {:status :waiting-for-opponent
     :action :continue-suppressed-waiting-prompt
     :message "Own prompt is a waiting prompt — opponent is deciding; continue suppressed (#75)"}

    (and (not second-pass?)
         (core/i-already-passed-run-window? @state/client-state (:side @state/client-state)))
    {:status :waiting-for-opponent
     :action :continue-suppressed-already-passed
     :message "You already passed this window (engine :no-action records you) — opponent owes the decision; continue suppressed (#98)"}

    :else
    (do
      (ws/send-message! :game/action
                        {:gameid gameid
                         :command "continue"
                         :args nil})
      ;; Brief wait for WebSocket state update to arrive
      ;; Without this, caller may see stale state on next read
      (Thread/sleep 100)
      {:status :action-taken
       :action :sent-continue})))

(defn- send-choice!
  "Helper to send choice command and return action-taken result.
   Waits briefly for state to sync via WebSocket.

   `prompt-eid` names the prompt being answered: without it the engine resolves
   whatever is at the HEAD of the prompt queue, not necessarily what we saw."
  [gameid choice-uuid choice-value prompt-eid]
  (ws/send-message! :game/action
                   {:gameid gameid
                    :command "choice"
                    :args {:choice {:uuid choice-uuid}
                           :eid prompt-eid}})
  ;; Brief wait for WebSocket state update to arrive
  (Thread/sleep 100)
  {:status :action-taken
   :action :auto-choice
   :choice choice-value})

;; Track if we've warned about --force this session
(defonce force-warning-shown (atom false))

(defn handle-force-mode
  "Priority 0: --force flag bypasses ALL checks (but respects run completion)
   ⚠️  WARNING: --force is for AI-vs-AI testing ONLY!
   In HITL games, it will break game state by passing when you should wait."
  [{:keys [strategy gameid state]}]
  (when (:force strategy)
    ;; Show warning once per session
    (when-not @force-warning-shown
      (reset! force-warning-shown true)
      (println "")
      (println "⚠️  WARNING: --force is for AI-vs-AI testing ONLY!")
      (println "⚠️  In HITL games, this WILL break game state by passing")
      (println "⚠️  when you should wait for opponent.")
      (println "⚠️  At a checkpoint blocked on an opponent prompt, a forced")
      (println "⚠️  continue can re-fire the checkpoint and mint DUPLICATE")
      (println "⚠️  prompts (#75/#77) — the normal path suppresses this.")
      (println ""))
    (let [run (get-in state [:game-state :run])]
      (if (nil? run)
        ;; Run is complete, don't send spurious continues
        (do
          (println "✅ Run complete (force mode)")
          {:status :run-complete
           :wake-reason :run-complete})
        ;; Run is active, send continue
        (do
          (println "⚡ FORCE mode - bypassing all checks, sending continue")
          (ws/send-message! :game/action
                           {:gameid gameid
                            :command "continue"
                            :args nil})
          {:status :action-taken
           :action :forced-continue
           :wake-reason :forced})))))

(defn handle-opponent-wait
  "Priority 1: Opponent pressed WAIT button (indicate-action)"
  [{:keys [state side opp-side]}]
  (when (opponent-indicated-action? state side)
    (println "⏸️  PAUSED - Opponent pressed WAIT button")
    {:status :waiting-for-opponent
     :wake-reason :opponent-indicated-action
     :message (str (clojure.string/capitalize opp-side) " pressed WAIT - please pause")}))

(defn handle-run-complete
  "Priority 7: Run complete (run object is nil)"
  [{:keys [state my-prompt]}]
  (let [run (get-in state [:game-state :run])]
    (when (nil? run)
      (println "✅ Run complete")
      {:status :run-complete
       :wake-reason :run-complete})))

(defn handle-no-run
  "Priority 8: No active run"
  [{:keys [state my-prompt]}]
  (let [run (get-in state [:game-state :run])]
    (when (and (nil? run)
               (or (nil? my-prompt)
                   (not= (:prompt-type my-prompt) "run")))
      (println "⚠️  No active run detected")
      {:status :no-run
       :wake-reason :no-run})))

(defn handle-access-display
  "Display accessed cards during run - returns nil to allow auto-continue.
   This handler prints access info but doesn't stop the run automation."
  [{:keys [my-prompt side]}]
  ;; Display access info but always return nil to continue processing
  (when (and my-prompt
             (= side "runner")
             (or (= (:prompt-type my-prompt) "other")
                 (= (:prompt-type my-prompt) "access")))
    (let [msg (:msg my-prompt)
          card-title (get-in my-prompt [:card :title])]
      ;; Check for "You accessed" pattern
      (when (and msg (clojure.string/starts-with? (str msg) "You accessed"))
        (let [status-key [:access-display msg]
              already-printed? (= @last-waiting-status status-key)]
          (when-not already-printed?
            (reset! last-waiting-status status-key)
            (println "")
            (println (format "📋 %s" msg))
            (when card-title
              (println (format "   Card: %s" card-title)))
            ;; Show choices if any (e.g., "Steal", "Pay to trash")
            (when-let [choices (:choices my-prompt)]
              (when (> (count choices) 1)
                (println "   Options:")
                (doseq [[idx choice] (map-indexed vector choices)]
                  (println (format "     %d. %s" idx (:value choice)))))))))))
  ;; Always return nil - let subsequent handlers (auto-choice/auto-continue) handle it
  nil)

(defn handle-auto-choice
  "Priority 5: Auto-handle single mandatory choice.
   For access prompts with only 'Done' as option: show card text (if new), then auto-continue.
   Access prompts with 2+ choices are handled by handle-real-decision (earlier in chain).
   For other single-choice prompts: auto-continue immediately."
  [{:keys [my-prompt gameid]}]
  (when (and my-prompt
             (seq (:choices my-prompt))
             (= 1 (count (:choices my-prompt))))
    (let [choice (first (:choices my-prompt))
          choice-uuid (:uuid choice)
          choice-value (:value choice)
          card-title (get-in my-prompt [:card :title])
          msg (:msg my-prompt)
          is-access-prompt? (and msg (clojure.string/starts-with? (str msg) "You accessed"))]
      ;; For access prompts, ensure card text is shown for first-time cards
      ;; (handle-access-display already printed basic info, but this shows full card text)
      (when (and is-access-prompt? card-title)
        (core/show-card-on-first-sight! card-title))
      ;; All single-choice prompts auto-continue (2+ choice access handled by handle-real-decision)
      (println (format "   → Auto-choosing: %s" choice-value))
      (send-choice! gameid choice-uuid choice-value (:eid my-prompt)))))

(defn handle-recently-passed-in-log
  "Priority 5.5: Detect when we've passed via game log (backup for :no-action).
   Only triggers when :no-action is nil/false - prevents stale log entries from blocking."
  [{:keys [side state run-phase]}]
  (let [run (get-in state [:game-state :run])
        no-action (:no-action run)]
    ;; Only check log if :no-action is nil/false (server didn't set it)
    ;; When :no-action has a value, trust it instead of potentially stale log
    (when-not no-action
      (let [log (get-in state [:game-state :log])
            recent-entries (take 3 (reverse log))
            side-name (if (= side "runner") "AI-runner" "AI-corp")
            passed-pattern (re-pattern (str side-name " has no further action"))
            recently-passed? (some #(re-find passed-pattern (str (:text %))) recent-entries)
            opp-side (if (= side "runner") "Corp" "Runner")]
        (when recently-passed?
          (let [status-key [:waiting-after-pass-log run-phase side]
                already-printed? (= @last-waiting-status status-key)]
            (when-not already-printed?
              (reset! last-waiting-status status-key)
              (println (format "⏸️  Waiting for %s paid abilities (%s phase)" opp-side run-phase)))
            {:status :waiting-for-opponent-paid-abilities
             :wake-reason :waiting-for-opponent
             :message (format "Waiting for %s to pass or use paid abilities" opp-side)
             :phase run-phase
             :we-passed true}))))))

(defn handle-auto-continue
  "Priority 6: Auto-continue through paid ability windows where we don't need to act"
  [{:keys [my-prompt run-phase gameid side state]}]
  (when (can-auto-continue? my-prompt run-phase side state)
    (println "   → Auto-continuing through paid ability window")
    (send-continue! gameid)))

(defn handle-initiation-auto-pass
  "Priority 6 (before handle-auto-continue): auto-pass the run INITIATION window
   when I am the active player (#31, step 1).

   Initiation is a both-must-pass window with no run-start paid ability either
   side uses in System Gateway. `run!` sends the Runner's first continue by
   default, so the Runner passes (:no-action \"runner\"); the Corp then becomes
   the active player at an EMPTY initiation window with NO prompt. Because
   `can-auto-continue?` requires a \"run\" prompt, it never fires here — no
   handler matches and continue-run! falls through to handle-unexpected-state,
   returning a FALSE :waiting-for-opponent. Both seats then wait on each other:
   the #31 initiation wedge.

   Deterministic fix off `should-i-act?` (per michael-nr, forum ai-netrunner):
   the active player passes its OWN initiation window. Symmetric — same logic
   fires for the Runner at a fresh window and the Corp at the second pass. Never
   sends a continue on the opponent's behalf and never advances a window I don't
   hold priority in, so it cannot skip a real opponent decision. Scoped to
   \"initiation\" only; the other both-pass windows (approach-ice / movement) keep
   their board-aware guards and are a separate follow-on."
  [{:keys [run-phase gameid side state my-prompt]}]
  (when (and (= run-phase "initiation")
             (should-i-act? state side)
             (not (has-real-decision? my-prompt)))
    (println "   → Auto-passing initiation window (no run-start decision)")
    (send-continue! gameid)))

(def self-advance-grace-ms
  "How long the opponent gets to answer a window before we treat it as ABANDONED
   and advance it ourselves. See handle-stalled-window-self-advance."
  5000)

(defonce ^:private window-first-seen
  ;; {[phase position no-action] first-seen-ms} — when did we first observe this
  ;; exact stalled window? Reset per run by reset-window-grace!.
  (atom {}))

(defn reset-window-grace!
  "Forget stalled-window timings (new run / new game)."
  []
  (reset! window-first-seen {}))

(defn- window-stalled-for-ms
  "Milliseconds since we FIRST saw this exact window in this exact state.
   Records first sight on the way past, so the first call always returns 0."
  [state]
  (let [run (get-in state [:game-state :run])
        k [(:phase run) (:position run) (normalize-side (:no-action run))]
        now (System/currentTimeMillis)
        first-seen (get (swap! window-first-seen update k #(or % now)) k now)]
    (- now first-seen)))

(defn handle-stalled-window-self-advance
  "Issue #31 §1: advance a both-must-pass window the opponent has ABANDONED —
   the residual stall left after #62 fixed initiation.

   TWO conditions, both required — this is a stall-BREAKER, not a window-skipper:

   1. The board proves the opponent has no REZ decision here
      (`opponent-has-run-decision?`).
   2. They have had `self-advance-grace-ms` to act and have not.

   Condition 2 exists because condition 1 is not a complete proof. It shows the
   opponent has no *rez* choice (the approached ICE is already rezzed; no unrezzed
   card in the attacked root) — but the engine also permits paid abilities at these
   windows, and the Runner client cannot see the Corp's prompt (fog of war), so
   \"no rez choice\" cannot be strengthened into \"no choice at all\" from our seat.
   Advancing on condition 1 alone would therefore risk skipping a real Corp paid
   ability — the blunt `corp-auto-no-action` bug we rejected. (Our current Corp AI
   only ever rezzes/fires, so it would not bite today; that is a property of our
   bot, not of the rules, and it is not something to build on.)

   Waiting out the grace removes that risk: a Corp that is present answers the
   window in milliseconds — its monitor is a loop, not a model call — so anything
   still unanswered after several seconds is a seat that is not at its post, and
   the paid ability we might \"skip\" is one nobody was ever going to take. With
   park mode (Fix A) keeping the Corp home, this should now essentially never fire;
   it is the belt to park mode's braces.

   Engine shape (verified in src/clj/game/core/runs.clj): the first `continue` from
   EITHER side records `:run :no-action`; the SECOND advances the phase, and the
   advance branch has NO side-check. So once I have passed, I can advance the
   window myself. Runner-side only; never sends a continue on the opponent's
   behalf."
  [{:keys [run-phase gameid side state my-prompt]}]
  (when (and (= side "runner")
             (contains? #{"approach-ice" "movement"} run-phase)
             (i-already-passed? state side)
             (not (opponent-has-run-decision? state side run-phase))
             (not (has-real-decision? my-prompt)))
    ;; Board says there's no rez decision here. Give the opponent the grace period
    ;; anyway (see docstring) — only an ABANDONED window gets advanced.
    (let [stalled-ms (window-stalled-for-ms state)]
      (when (>= stalled-ms self-advance-grace-ms)
        (println (format "   → Opponent abandoned this window (%.1fs, no rez decision available) — self-advancing (#31)"
                         (/ stalled-ms 1000.0)))
        ;; Deliberate SECOND pass of an abandoned window — bypasses the #98
        ;; already-passed guard, which exists to stop accidental repeats.
        (send-continue! gameid :second-pass? true)))))

(defn handle-real-decision
  "Priority 3: I have a real decision to make"
  [{:keys [my-prompt]}]
  (when (has-real-decision? my-prompt)
    (println "🛑 Run paused - decision required")
    (println (format "   Prompt: %s" (:msg my-prompt)))
    (when-let [card-title (get-in my-prompt [:card :title])]
      (println (format "   Card: %s" card-title))
      ;; Show card text for first-seen cards (especially useful during access)
      (core/show-card-on-first-sight! card-title))
    ;; Display text choices if present
    (let [choices (:choices my-prompt)
          selectable (:selectable my-prompt)]
      (when (seq choices)
        (println (format "   Choices: %d options" (count choices)))
        (doseq [[idx choice] (map-indexed vector choices)]
          (println (format "     %d. %s" idx (:value choice)))))
      ;; Display selectable cards for "select" type prompts
      (when (seq selectable)
        (println (format "   Selectable cards: %d" (count selectable)))
        (doseq [[idx cid] (map-indexed vector selectable)]
          (if-let [card (core/find-card-by-cid cid)]
            (println (format "     %d. %s" idx (:title card)))
            (println (format "     %d. [unknown card: %s]" idx cid))))
        (println "   → Use 'choose-card <index>' to select")))
    {:status :decision-required
     :wake-reason :decision-required
     :prompt my-prompt}))

(defn handle-waiting-for-opponent
  "Priority 3: Waiting for opponent to make a decision"
  [{:keys [state side my-prompt]}]
  (let [run-phase (get-in state [:game-state :run :phase])
        ;; Corp should wait during success phase ONLY if Corp has no prompt at all
        ;; If Corp has any prompt (even trivial "Done"), that needs to be handled first
        corp-has-prompt-with-choices? (and my-prompt (seq (:choices my-prompt)))
        corp-waiting-for-access? (and (= side "corp")
                                      (= run-phase "success")
                                      (not corp-has-prompt-with-choices?)
                                      (not (has-real-decision? my-prompt)))
        ;; If we have a "waiting" type prompt, we're explicitly waiting for opponent
        ;; This handles cases where we can't see opponent's prompt (client isolation)
        has-waiting-prompt? (is-waiting-prompt? my-prompt)]
    ;; A real decision of our own (e.g. an access-trigger ambush like Urtica
    ;; Cipher's "Use ability?") must be surfaced by handle-real-decision, never
    ;; masked as "waiting for opponent" - otherwise self-play deadlocks with both
    ;; sides waiting on each other.
    (when (and (not (has-real-decision? my-prompt))
               (or (waiting-for-opponent? state side)
                   corp-waiting-for-access?
                   has-waiting-prompt?))
      (let [reason (cond
                     corp-waiting-for-access? "Runner resolving access"
                     has-waiting-prompt? (or (:msg my-prompt) "Waiting for opponent decision")
                     :else (waiting-reason state side))
            status-key [:waiting-for-opponent reason]
            already-printed? (= @last-waiting-status status-key)]
        (when-not already-printed?
          (reset! last-waiting-status status-key)
          (println (format "⏸️  Waiting for opponent: %s" reason))
          (debug-chat! (format "WAIT: %s" reason)))
        {:status :waiting-for-opponent
         :wake-reason :waiting-for-opponent
         :message reason}))))

(defonce ^:private reported-events
  ;; {:gameid <the game these belong to> :events #{event-key ...}}
  ;; Log entries we have ALREADY paused the seat on. See handle-events for why
  ;; this must exist.
  ;;
  ;; Scoped to the GAME, not the run. Event identity is the log INDEX, and a new
  ;; game restarts the log at 0 — so entries from a previous game would collide
  ;; with the new game's first entries and silently suppress them. Per-RUN reset
  ;; would be wrong in the other direction: a seat re-issues `monitor-run`
  ;; repeatedly within one run, and clearing on each re-issue would re-report the
  ;; same event every time — the #31 latch again, one grain coarser. Keying on
  ;; gameid is self-healing across new game, resync and reconnect alike, with no
  ;; reset call to forget. (Guest review of the #31 fix.)
  (atom {:gameid nil :events #{}}))

(defn reset-reported-events!
  "Forget which events we have reported."
  []
  (reset! reported-events {:gameid nil :events #{}}))

(defn- reported-set
  "The reported-event keys for the CURRENT game, dropping another game's."
  []
  (let [gameid (:gameid @state/client-state)
        {:keys [events] :as cur} @reported-events]
    (if (= gameid (:gameid cur))
      events
      (:events (reset! reported-events {:gameid gameid :events #{}})))))

(defn- event-key
  "Identity of a log entry for dedupe purposes. Index first (see
   extract-run-events); timestamp and text keep it stable if an entry is ever
   offered without an index (hand-built contexts, tests)."
  [entry]
  [(::log-index entry) (:timestamp entry) (:text entry)])

(defn- unreported?
  "True if `entry` exists and we have not already paused the seat on it."
  [entry]
  (boolean (and entry (not (contains? (reported-set) (event-key entry))))))

(defn- rez-line-names?
  "True when the rez log line names `title` as the rezzed card. Anchored to the
   engine's 'rezzes <title>' / 'to rez <title>' wording with a boundary after
   the title, so bare substring collisions between real card names ('Architect'
   inside 'rezzes Hostile Architecture') don't misclassify (guest review)."
  [text title]
  (boolean
   (re-find (re-pattern (str "\\b(?:rezzes|to rez) "
                             (java.util.regex.Pattern/quote (str title))
                             "(?=$|[\\s.,!])"))
            (str text))))

(defn- rezzed-card-kind
  "Best-effort classification of a rez log line: :ice when it names an
   installed ICE, :non-ice when it names an installed asset/upgrade, nil when
   unknown (no state, no title match). Rez lines are direct state reports
   ('corp rezzes X protecting Y'), so anchored title matching does not hit
   embedded effect text (#104)."
  [state text]
  (let [servers (vals (get-in state [:game-state :corp :servers]))
        hit? (fn [card] (when-let [t (:title card)]
                          (rez-line-names? text t)))]
    (cond
      (some hit? (mapcat :ices servers)) :ice
      (some hit? (mapcat :content servers)) :non-ice
      :else nil)))

(defn handle-events
  "Priority 4: Pause for important events (rez, subs, abilities, damage).
   Order is most-specific-first: a firing subroutine and its own 'uses <ice>
   to ...' effect line can co-occur in the same window, so :subs-fired is
   checked before :ability-used to label the headline event (#54). All four
   statuses pause identically downstream (run-notable-event?), so within a
   single call the order only affects the human-readable label, never
   behaviour. ACROSS calls it now also affects how many pauses one physical
   exchange produces: the old cond suppressed a co-occurring lower-priority
   entry forever, whereas per-entry dedupe surfaces each DISTINCT co-window
   entry on successive calls (a fired-sub line, then its companion 'uses <ice>
   to ...' line). That direction is safe — more pauses, never fewer — but a
   hand-driven seat will occasionally see two pauses for one exchange. A
   single entry matching several categories still reports once, since the
   dedupe key is the entry.

   Reports each event EXACTLY ONCE (#31, game 4a6aef71). `extract-run-events`
   is a pure function of the newest 3 log entries, so without a memory of what
   has already been reported this handler LATCHES: it sits ahead of every pass
   handler in the chain, so pausing here stops the run advancing, a stalled run
   stops the log moving, and an unchanged log re-produces the same event on the
   next call — forever. Three live `continue --single` calls each re-printed the
   same 7-minute-old Overclock line (which by then was not even the newest
   entry) while :no-action sat at false. The pause is a feature; the latch is
   the bug. Dedupe is per-ENTRY, not a global mute, so a genuinely new rez still
   stops the Runner."
  [{:keys [rez-event ability-event fired-event tag-damage-event state side]}]
  (when-let [[status headline event]
             (cond
               (unreported? rez-event)        [:ice-rezzed
                                               ;; #104: a rezzed UPGRADE (Manegarm) was
                                               ;; announced as 'ICE rezzed!'. The rez line
                                               ;; is a direct state report, so installed-
                                               ;; title matching is safe here.
                                               (if (= :non-ice (rezzed-card-kind state (:text rez-event)))
                                                 "Upgrade/asset rezzed!"
                                                 "ICE rezzed!")
                                               rez-event]
               (unreported? fired-event)      [:subs-fired   "subroutines fired!" fired-event]
               (unreported? ability-event)    [:ability-used "ability triggered!" ability-event]
               (unreported? tag-damage-event) [:tag-or-damage "tag or damage!"    tag-damage-event])]
    (reported-set)  ; ensure the set belongs to the current game before adding
    (swap! reported-events update :events conj (event-key event))
    (println (format "⚠️  Run paused - %s" headline))
    (println (format "   %s" (:text event)))
    ;; #104: the continue-run hint is the Runner's verb; on the Corp side it
    ;; pointed at a command the seat doesn't drive the run with.
    (println (if (core/side= "Corp" (or side ""))
               "   → Resume with 'monitor-run' (or 'continue' to pass priority)"
               "   → Use 'continue-run' again to proceed"))
    {:status status :wake-reason status :event event}))

(defn handle-unexpected-state
  "Fallback: Unknown state - wait and retry rather than give up"
  [{:keys [side run-phase my-prompt opp-prompt]}]
  (let [status-key [:unexpected-state side run-phase]
        already-printed? (= @last-waiting-status status-key)]
    ;; Only print debug info once
    (when-not already-printed?
      (reset! last-waiting-status status-key)
      (println (format "⏳ Waiting (phase: %s, side: %s)..." run-phase side)))
    ;; Return waiting status so loop retries
    {:status :waiting-for-opponent
     :wake-reason :unexpected-state
     :message "Unclear state, waiting for game to advance"
     :prompt my-prompt
     :phase run-phase}))

(defn run-first-matching-handler
  "Run handlers in order until one returns non-nil result"
  [handlers context]
  (loop [remaining handlers]
    (if-let [handler (first remaining)]
      (if-let [result (handler context)]
        result
        (recur (rest remaining)))
      ;; No handler matched - shouldn't happen, but return unexpected-state as fallback
      (handle-unexpected-state context))))

(defn continue-run!
  "Stateless run handler - examines current state, takes ONE action, returns.
   Call repeatedly until run completes or decision required.
   Now supports strategy flags via run strategy state.

   STATELESS DESIGN: No recursion, no local state. Uses game state as source of truth.
   Each call examines current state and either:
   - Sends ONE continue command and returns :action-taken
   - Returns :waiting-for-opponent (pause, wait for opp)
   - Returns :decision-required (pause, user must decide)
   - Returns :run-complete (all done)

   Strategy flags (from run! or passed directly):
   --full-break      : Runner auto-breaks all ICE
   --no-rez          : Corp auto-declines all rez opportunities
   --rez <ice-name>  : Corp auto-rezzes named ICE; PAUSES on other unrezzed ICE
   --fire-unbroken   : Corp auto-fires unbroken subs
   --force           : Bypass ALL smart checks, just send continue

   🛑 MUST PAUSE (requires decision):
   - Opponent pressed WAIT/indicate-action
   - Corp has rez opportunity (approach-ice with unrezzed ICE) [unless --no-rez, or --rez naming THIS ICE]
   - Runner has 2+ real choices (not just Continue/Done) [unless --full-break]
   - Waiting for opponent's decision during run

   ⚠️ WANT to PAUSE (important events):
   - ICE rezzed (show cost and card)
   - Abilities triggered during run
   - Subroutines fired
   - Tags/damage dealt

   ✅ AUTO-CONTINUE (boring):
   - Empty paid ability windows (no choices, no selectables)
   - Not in special phases (approach-ice, encounter-ice)

   Returns:
     {:status :action-taken :action :sent-continue}  - Sent continue, call again
     {:status :waiting-for-opponent :message ...}     - Paused, wait for opp
     {:status :decision-required :prompt ...}         - Paused, user must decide
     {:status :ice-rezzed :event ...}                 - Paused, show rez event
     {:status :ability-used :event ...}               - Paused, show ability
     {:status :subs-fired :event ...}                 - Paused, show subs
     {:status :tag-or-damage :event ...}              - Paused, show tag/damage
     {:status :run-complete}                          - Run finished
     {:status :no-run}                                - No active run

   Usage:
     (continue-run!)  ; Take one step
     (continue-run! \"--force\")  ; Bypass all checks (old continue behavior)
     (continue-run! \"--no-rez\")  ; Auto-decline all rez"
  [& args]
  (let [;; Parse flags if provided, merge with run strategy
        {:keys [flags]} (if (seq args) (parse-run-flags (vec args)) {:flags {}})
        strategy (merge (get-strategy) flags)

        client-state @state/client-state
        side (:side client-state)
        gameid (:gameid client-state)
        run-phase (get-in client-state [:game-state :run :phase])
        my-prompt (get-in client-state [:game-state (keyword side) :prompt-state])
        opp-side (core/other-side side)
        opp-prompt (get-in client-state [:game-state (keyword opp-side) :prompt-state])
        log (get-in client-state [:game-state :log])

        ;; Scan the NEWEST log entries for notable run events (#52: the window
        ;; used to read the oldest 3, so recent rez/ability/subs/tag events
        ;; never reached the handler context).
        {:keys [rez-event ability-event fired-event tag-damage-event]}
        (extract-run-events log)

        ;; Build context map for handlers
        context {:strategy strategy
                 :state client-state
                 :side side
                 :gameid gameid
                 :run-phase run-phase
                 :my-prompt my-prompt
                 :opp-side opp-side
                 :opp-prompt opp-prompt
                 :log log
                 :rez-event rez-event
                 :ability-event ability-event
                 :fired-event fired-event
                 :tag-damage-event tag-damage-event}

        ;; Handler chain in priority order
        handlers [handle-force-mode
                  handle-opponent-wait
                  (fn [ctx]  ; Wrapper: mark a rez attempt so a failed (unaffordable) rez isn't retried forever
                    (when-let [result (corp-handlers/handle-corp-rez-strategy ctx)]
                      (when-let [pos (:rez-attempted-at result)]
                        (set-strategy! {:rez-attempted-at pos}))
                      result))
                  corp-handlers/handle-corp-rez-decision
                  ;; fire-if-asked is "sleep mode" - handles fire and empty windows
                  (fn [ctx]  ; Wrapper to update strategy after firing
                    (when-let [result (corp-handlers/handle-corp-fire-if-asked ctx)]
                      (when-let [pos (:fired-at-position result)]
                        (set-strategy! {:fired-at-position pos}))
                      result))
                  (fn [ctx]  ; Wrapper to update strategy after firing
                    (when-let [result (corp-handlers/handle-corp-fire-unbroken ctx)]
                      (when-let [pos (:fired-at-position result)]
                        (set-strategy! {:fired-at-position pos}))
                      result))
                  corp-handlers/handle-corp-fire-decision
                  corp-handlers/handle-corp-all-subs-resolved
                  corp-handlers/handle-corp-waiting-after-subs-fired
                  (fn [ctx]  ; Wrapper: mark an upgrade rez attempt (cid-keyed) so a failed (unaffordable) rez isn't retried forever
                    (when-let [result (corp-handlers/handle-corp-server-upgrade-decision ctx)]
                      (when-let [cid (:upgrade-rez-attempted result)]
                        (set-strategy! {:upgrade-rez-attempted cid}))
                      result))
                  ;; #31 §1: MUST precede handle-paid-ability-window, the general
                  ;; "I passed, now I wait for the opponent" handler — correct when
                  ;; the opponent owes a decision, a DEADLOCK when they don't.
                  ;; Sits after every real-decision handler above (corp rez/fire,
                  ;; upgrade), so it can only fire when nothing else wants to act.
                  handle-stalled-window-self-advance
                  corp-handlers/handle-paid-ability-window
                  runner-handlers/handle-auto-select-single-card
                  runner-handlers/handle-runner-approach-ice
                  tactics/handle-runner-tactics
                  runner-handlers/handle-runner-full-break
                  runner-handlers/handle-runner-encounter-ice
                  runner-handlers/handle-runner-pass-broken-ice
                  runner-handlers/handle-runner-pass-fired-ice
                  handle-waiting-for-opponent
                  handle-real-decision
                  handle-events
                  handle-access-display
                  handle-auto-choice
                  handle-recently-passed-in-log
                  handle-initiation-auto-pass
                  handle-auto-continue
                  handle-run-complete
                  handle-no-run]]

    ;; Run handlers in order until one returns non-nil
    (run-first-matching-handler handlers context)))

;; ============================================================================
;; Auto-Continue Loop
;; ============================================================================
;;
;; The loop calls continue-run! repeatedly until:
;; - Run completes (:run-complete)
;; - Real decision required (:decision-required / :fire-decision-required)
;; - Notable event occurs (:ice-rezzed, :ability-used, :subs-fired, :tag-or-damage)
;;   — EXCEPT in --persistent mode during an active run, where the loop's own
;;   notable events are routine and it keeps owning the run (see #36 below)
;; - Max iterations reached (safety guard)
;; - Timeout reached
;;
;; For :waiting-for-opponent status, the loop waits briefly then retries,
;; allowing the other client to take their action.

(defn- terminal-status?
  "Returns true if this status should stop the auto-continue loop"
  [status]
  (contains? #{:decision-required :fire-decision-required :ice-rezzed :ability-used :subs-fired
               :tag-or-damage :run-complete :no-run
               :tactic-paused :tactic-failed}  ; Tactics system pauses
             status))

(defn- should-pause-for-event?
  "Returns true if this is a notable event we should pause to show the user"
  [status]
  (contains? #{:ice-rezzed :ability-used :subs-fired :tag-or-damage} status))

(defn- get-run-state-key
  "Extract state key for stuck detection: [phase position ice-title]"
  []
  (let [state @state/client-state
        run (get-in state [:game-state :run])
        phase (:phase run)
        position (:position run)
        ice (core/current-run-ice state)
        ;; nil-safe: ice is nil during access phase (no ICE being encountered)
        ice-title (when ice (:title ice))]
    [phase position ice-title]))

(defn- detect-stuck-state
  "Check if we're stuck in the same state.
   Returns true if state-history has same state for threshold consecutive iterations.
   state-history is a vector of recent state keys (newest first)."
  [state-history threshold]
  (when (>= (count state-history) threshold)
    (let [recent (take threshold state-history)]
      (apply = recent))))

(defn- print-while-you-slept!
  "Print material run events that happened during a persistent monitor sleep."
  [start-log-count]
  (let [log (get-in @state/client-state [:game-state :log])
        lines (corp-decisions/summarize-slept-log log start-log-count)]
    (when (seq lines)
      (println "While you slept:")
      (doseq [line lines]
        (println (str "   " line))))))

(defn auto-continue-loop!
  "Runs continue-run! in a loop until run ends or decision required.

   This is the core of run automation - both sides can call this to
   auto-pass through boring paid ability windows.

   Loop continues on:
   - :action-taken - took an action, might need more
   - :waiting-for-opponent - wait briefly, then check again

   Loop stops on:
   - :decision-required - user must make a choice
   - :ice-rezzed, :ability-used, :subs-fired, :tag-or-damage - notable events
   - :run-complete - run finished successfully
   - :no-run - no active run
   - :waiting-for-corp-rez - runner waiting for corp (corp should call their loop)
   - :ping - opponent sent a `ping` chat nudge during a persistent wait (#50
     recovery net; returns control so the seat can act — does NOT auto-advance)
   - stuck in same state (5 consecutive :action-taken with same [phase position ice])
   - max iterations or timeout reached

   Options:
   :max-iterations  - Safety guard (default 500, high because stuck-detection handles loops)
   :timeout-ms      - Max time to loop (default 300000ms = 300s). Matches
                      `wait` because LLM agents take minutes per turn, and
                      monitor-run regularly sits idle while the opponent
                      thinks. 30s (the prior default) bailed prematurely
                      after Runner had just jacked out but before the state
                      diff arrived. If you genuinely need a short bail
                      (e.g., a quick fast-return check), pass it explicitly.
   :wait-delay-ms   - Delay when waiting for opponent (default 200ms)
   :stuck-threshold - Same state iterations before declaring stuck (default 5)
   :pause-on-events - Pause on events like :ice-rezzed (default true)

   Returns the final result from continue-run! plus:
   :iterations - how many times continue-run! was called
   :elapsed-ms - how long the loop ran"
  [& {:keys [max-iterations timeout-ms wait-delay-ms stuck-threshold pause-on-events
             return-on-runner-signal persistent persistent-wait-delay-ms]
      :or {max-iterations 500    ; Raised from 50 - stuck detection handles loops
           timeout-ms 300000     ; Was 30000 — too short for LLM-paced games
           wait-delay-ms 200
           stuck-threshold 5
           pause-on-events true
           ;; When false (hand-driven monitor-run), :waiting-for-runner-signal
           ;; polls internally so a human doesn't have to re-issue the command.
           ;; The autonomous Corp loop sets this true so the wait is surfaced to
           ;; its own per-tick stall tracker (which nudges/bails) instead of being
           ;; swallowed by up to ~100s of internal polling.
           return-on-runner-signal false
           ;; When true (monitor-run --persistent), during an ACTIVE run two
           ;; status families that are terminal for hand-driven monitor-run are
           ;; NOT terminal here, keeping the defender loop alive so a cross-model
           ;; seat doesn't have to re-issue monitor-run:
           ;;   (a) empty opponent-priority windows (:waiting-for-opponent
           ;;       family) — sleep persistent-wait-delay-ms and recheck (idle
           ;;       wait; does NOT advance `iteration`, bounded by timeout-ms);
           ;;   (b) the loop's own notable events (should-pause-for-event?:
           ;;       :ice-rezzed / :ability-used / :subs-fired / :tag-or-damage) —
           ;;       sleep quick-delay and recur, ADVANCING `iteration` so the
           ;;       max-iterations backstop still bounds a degenerate event spin
           ;;       (#36). Only genuine decisions (:decision-required /
           ;;       :fire-decision-required) — which are NOT should-pause-for-
           ;;       event? — plus run-end remain terminal under --persistent, so
           ;;       it "wakes only for a real rez/fire/access decision or run
           ;;       end". Both branches are below; the event branch returns ABOVE
           ;;       terminal-status?.
           persistent false
           persistent-wait-delay-ms 1000}}]
  (let [start-time (System/currentTimeMillis)
        start-log-count (count (get-in @state/client-state [:game-state :log]))
        deadline (+ start-time timeout-ms)]
    (loop [iteration 0
           state-history []]   ; Track [phase position ice] for stuck detection
      (cond
        ;; Safety: max iterations (should rarely trigger with stuck-detection)
        (>= iteration max-iterations)
        (do
          (when persistent
            (print-while-you-slept! start-log-count))
          (println (format "⚠️  Auto-continue stopped: max iterations (%d) reached" max-iterations))
          {:status :max-iterations
           :wake-reason :max-iterations
           :iterations iteration
           :elapsed-ms (- (System/currentTimeMillis) start-time)})

        ;; Safety: timeout
        (> (System/currentTimeMillis) deadline)
        (do
          (when persistent
            (print-while-you-slept! start-log-count))
          (println (format "⚠️  Auto-continue stopped: timeout (%dms) reached" timeout-ms))
          {:status :timeout
           :wake-reason :timeout
           :iterations iteration
           :elapsed-ms (- (System/currentTimeMillis) start-time)})

        :else
        (let [result (continue-run!)
              status (:status result)
              current-state-key (get-run-state-key)
              new-history (cons current-state-key (take (dec stuck-threshold) state-history))]
          (cond
            ;; Persistent mode: a notable-but-routine event the loop produced
            ;; itself (its own ICE rez, an ability/subs firing, tag/damage
            ;; dealt) is NOT a decision — the seat delegated the whole run.
            ;; These statuses are terminal-status? for hand-driven monitor-run
            ;; (where pausing to show the user is the point), but in --persistent
            ;; mode treating them as terminal DROPPED the defender loop after a
            ;; rez commit (#36): the auto-rez returns :action-taken, then the
            ;; next iteration sees "Corp rezzes X" in the log and handle-events
            ;; returns :ice-rezzed → loop exits, leaving the Runner holding
            ;; priority at a window the Corp is no longer watching (soft
            ;; deadlock; the Runner could only recover by jacking out). The
            ;; documented --persistent contract is "wakes only for a real
            ;; rez/fire/access decision or run end", and a notable event is none
            ;; of those, so while the run is still active we keep owning it: the
            ;; event was already printed by handle-events; just continue.
            ;; Genuine decisions (:decision-required / :fire-decision-required)
            ;; are NOT should-pause-for-event? and still terminate below.
            ;; Spin-safe: iteration advances (max-iterations backstop stays
            ;; live), and once the run moves past the event the rez line scrolls
            ;; out of the 3-entry recent-log window — if it doesn't move, the
            ;; next status is :waiting-for-opponent (persistent sleep), not a
            ;; re-fired event.
            (and persistent
                 (should-pause-for-event? status)
                 (some? (get-in @state/client-state [:game-state :run])))
            (do
              (Thread/sleep core/quick-delay)
              (recur (inc iteration) state-history))  ; Keep OLD history; event isn't run-phase progress

            ;; Terminal status - stop loop.
            ;; Bug #3 fix: if the run completed and we burned the last click on
            ;; it, fire check-auto-end-turn! — otherwise the runner never sends
            ;; end-turn and the engine deadlocks on the next turn cycle.
            ;; check-auto-end-turn! has its own (my-turn? / clicks=0 / no prompt
            ;; / not already-ended) guards, so it's safe to call unconditionally
            ;; here, including from monitor-run! on the off-turn side.
            (terminal-status? status)
            (do
              (when persistent
                (print-while-you-slept! start-log-count))
              (when (or (= status :run-complete) (= status :no-run))
                (basic/check-auto-end-turn!)
                ;; Clear per-run runner handler atoms (passed-ice-position,
                ;; signaled-fire-position, etc.) now that the run is over.
                ;; run!/monitor-run! only reset at their START, so a later
                ;; run-event run (Jailbreak/Conduit) that enters via continue-run!
                ;; would otherwise inherit stale [position ice] keys and skip a
                ;; needed pass-continue.
                (runner-handlers/reset-state!)
                ;; Same third-path hazard for the self-advance grace timer: a
                ;; stale [phase position no-action] key (these collide readily)
                ;; would make a card-initiated run's first window look instantly
                ;; abandoned and self-advance with ZERO grace, skipping the
                ;; fog-of-war paid-ability window the grace exists to protect.
                ;; Run END is the boundary that covers every entry path.
                (reset-window-grace!))
              (assoc result
                     :iterations (inc iteration)
                     :elapsed-ms (- (System/currentTimeMillis) start-time)))

            ;; Waiting for Runner signal - poll and retry (Corp auto-waiting),
            ;; unless the caller (the autonomous Corp loop) asked to surface it so
            ;; its own stall tracker governs the wait.
            (= status :waiting-for-runner-signal)
            (if return-on-runner-signal
              (assoc result
                     :iterations (inc iteration)
                     :elapsed-ms (- (System/currentTimeMillis) start-time))
              ;; Idle wait for the Runner to break/signal. Like the persistent
              ;; :waiting-for-opponent branch below, DON'T advance `iteration`:
              ;; this is an idle opponent wait, not an action loop, so it must be
              ;; bounded by timeout-ms (LLM-paced, ~300s) — not the action-stuck
              ;; max-iterations guard (~100s). Advancing it made a Corp parked at
              ;; an encounter waiting for a slow Runner bail mid-run with a
              ;; misleading "max iterations reached", defeating monitor-run
              ;; --persistent's whole-run ownership (b7e710d11).
              (do
                (Thread/sleep wait-delay-ms)
                (recur iteration [])))  ; Reset history; idle wait, don't advance iteration

            ;; Waiting for opponent. Normally terminal — the other client needs
            ;; to run their own loop (e.g., Corp runs monitor-run!). In persistent
            ;; mode, while the run is still active, instead sleep and recheck so
            ;; the seat keeps a live defender loop across empty priority windows.
            (or (= status :waiting-for-opponent)
                (= status :waiting-for-corp-rez)
                (= status :waiting-for-corp-fire)
                (= status :waiting-for-opponent-paid-abilities))
            (let [cur-state @state/client-state
                  my-side (:side cur-state)
                  my-prompt (get-in cur-state [:game-state (keyword my-side) :prompt-state])
                  run-active? (some? (get-in cur-state [:game-state :run]))]
              (cond
                ;; #43: continue-run! mislabeled this as an opponent-wait, but THIS
                ;; seat is holding its own on-steal/on-score agenda-trigger prompt
                ;; (e.g. Send a Message) that only WE can resolve. Sleeping on it
                ;; strands the game — the opponent is hard-blocked on "waiting for
                ;; Corp to resolve pending triggers" and (in cross-model marquee g1)
                ;; gave up after the 300s timeout. Surface it as a decision so the
                ;; seat resolves it (rez a target / choose Done). Fires in both
                ;; persistent and hand-driven mode — a real seat decision beats a
                ;; misleading opponent-wait either way.
                (seat-owns-trigger-decision? my-prompt)
                (do
                  (when persistent (print-while-you-slept! start-log-count))
                  (println "🛑 You have a pending decision to resolve (agenda trigger / choice) — returning it.")
                  (assoc result
                         :status :decision-required
                         :wake-reason :agenda-trigger-decision
                         :iterations (inc iteration)
                         :elapsed-ms (- (System/currentTimeMillis) start-time)))

                ;; #50 recovery net: an explicit opponent `ping` chat nudge wakes
                ;; the persistent defender loop, exactly as it wakes `wait`
                ;; (wait-for-relevant-diff). This is the mid-run analog — a seat
                ;; parked in monitor-run --persistent at an empty priority window
                ;; that the OTHER seat believes is ours (an unowned both-pass
                ;; window) can be un-stalled by the opponent pinging. Return
                ;; control (`:ping`) so the seat re-evaluates and can act; this
                ;; does NOT auto-advance the run (no priority passed), so it is a
                ;; control/UX wake, not a run-advance change. Checked before the
                ;; idle-sleep so a ping that lands during the wait is not slept
                ;; through to the timeout.
                (and persistent
                     (core/ping-since? (get-in cur-state [:game-state :log]) start-log-count))
                (do
                  (print-while-you-slept! start-log-count)
                  (println "🏓 Woke up: opponent ping — returning control so you can act.")
                  (assoc result
                         :status :ping
                         :wake-reason :ping
                         :iterations (inc iteration)
                         :elapsed-ms (- (System/currentTimeMillis) start-time)))

                ;; Idle wait: don't advance `iteration` (timeout governs the bound,
                ;; not the action-stuck max-iterations guard); reset stuck history.
                (and persistent run-active?)
                (do
                  (Thread/sleep persistent-wait-delay-ms)
                  (recur iteration []))

                ;; Persistent, but the run object is GONE (nil). The run already
                ;; ended — typically the access boundary, where the run tears down
                ;; while the Runner still resolves access. Marquee game-2 surfaced
                ;; this returning a stale :waiting-for-opponent + "Corp should run
                ;; monitor-run" tip, which then immediately said "No active run to
                ;; monitor" — a self-contradicting double-step. Return a CLEAN
                ;; :no-run terminal so the seat goes straight back to its wait loop.
                persistent
                (do
                  (print-while-you-slept! start-log-count)
                  (println "✅ Run ended — no further Corp decision. Back to the wait loop.")
                  (assoc result
                         :status :no-run
                         :iterations (inc iteration)
                         :elapsed-ms (- (System/currentTimeMillis) start-time)))

                :else
                (do
                  ;; Side-aware tip. This loop just passed THIS seat's window and
                  ;; is now waiting on the opponent. The advice depends on who's
                  ;; running it: a Runner waits on the Corp to defend (monitor-run);
                  ;; a Corp that already passed waits on the Runner to act — telling
                  ;; the Corp to "run monitor-run" there is backwards (it just did).
                  (if (core/side= "Corp" (:side @state/client-state))
                    (println "💡 Waiting for Runner to act (e.g. 'continue' / next run step) — they hold priority.")
                    (println "💡 Tip: Corp should run 'monitor-run' to participate in the run"))
                  (assoc result
                         :iterations (inc iteration)
                         :elapsed-ms (- (System/currentTimeMillis) start-time)))))

            ;; Prompt handled (e.g., credit source auto-select) - continue without stuck tracking
            ;; This is progress but not run-phase progress, so don't add to history
            (= status :prompt-handled)
            (do
              (Thread/sleep core/quick-delay)
              (recur (inc iteration) state-history))  ; Keep OLD history, don't add new entry

            ;; Action taken - check for stuck state, then continue
            (= status :action-taken)
            (if (detect-stuck-state new-history stuck-threshold)
              ;; Stuck! Same state for N iterations despite :action-taken
              (do
                (println (format "⚠️  Stuck in same state for %d iterations: %s" stuck-threshold current-state-key))
                (println "   This usually means a handler is sending continues without making progress")
                {:status :stuck
                 :wake-reason :stuck
                 :state-key current-state-key
                 :iterations (inc iteration)
                 :elapsed-ms (- (System/currentTimeMillis) start-time)})
              ;; Not stuck - continue
              (do
                (reset! last-waiting-status nil)  ; Clear so new waiting messages will print
                (Thread/sleep core/quick-delay)   ; Brief sync pause
                (recur (inc iteration) new-history)))

            ;; Unknown status - treat as terminal
            :else
            (do
              (println (format "⚠️  Auto-continue: unknown status %s, stopping" status))
              (assoc result
                     :iterations (inc iteration)
                     :elapsed-ms (- (System/currentTimeMillis) start-time)))))))))

(defn park-wake-reason
  "What should a PARKED persistent monitor do right now?

   :game-over          — stop (parking through a finished game hangs the seat forever)
   :run                — a run is active: go own it
   :decision-required  — I am holding a prompt only I can resolve: surface it
   :my-turn            — the opponent's turn ended: hand control back to the seat
   :park               — opponent's turn, no run yet: STAY AT THE POST
   :no-run             — nothing to defend and nothing coming: don't park

   :decision-required is not optional garnish — it is what keeps parking safe.
   The flow park REPLACES was sitting in `wait`, which wakes on :has-prompt. A
   park loop that watches only run-state is BLIND to a prompt of its own, and the
   Corp can be prompted on the RUNNER'S turn with NO RUN ACTIVE: Wildcat Strike
   (and ~30 other Runner cards carrying `:player :corp`) puts a two-choice prompt
   on the Corp out of nowhere. A prompt-blind park sleeps through it while the
   Runner is hard-blocked waiting for the answer, times out, and — per the seat
   brief — re-parks. That is an unbreakable deadlock manufactured by the fix.
   Checking it here also covers the re-park path: after a run ends we loop back
   through this function, so a trigger prompt left over from the run (the #43
   shape: a `select` with no valid targets, which `has-real-decision?` does not
   consider real) is surfaced instead of being silently re-parked on.

   Only the CORP parks. Runs happen exclusively on the Runner's turn, so a Runner
   waiting for its opponent to start a run is waiting for something that cannot
   happen: it would sit until timeout while the heartbeat keep-alive reported it
   healthy — a wedged seat that looks fine. That is worse than the :no-run it used
   to get, so the Runner keeps the old behaviour."
  [state side]
  (let [gs (:game-state state)
        my-prompt (get-in gs [(keyword side) :prompt-state])
        ;; Turn ownership at a boundary is subtle and the engine's raw fields lie
        ;; about it in more than one way, so DON'T re-derive it here — defer to
        ;; core/my-turn-to-act?, the single authoritative predicate that also
        ;; backs `wait`/`relevance-reason` and agrees with game-over-status.
        ;; A bespoke copy of this logic bit us twice:
        ;;   #31 — a Corp that had JUST ENDED its turn (:end-turn set, but
        ;;         :active-player still pointing at itself) read as active and got
        ;;         told "your move", bouncing it out of the post it was entering.
        ;;   #68 — a boundary where :end-turn was NOT set but :active-player was
        ;;         still "corp" with 0 clicks (live: turn=1, game-over-status
        ;;         AWAITING-START next-player=runner) fell into the raw
        ;;         (= active side) branch and again said "your move" ~3x, telling
        ;;         the Corp to leave before the Runner had even started.
        ;; my-turn-to-act? gets both right (0 clicks + not-opponent-ended => not
        ;; my turn), so there is no second copy left to drift.
        my-turn? (core/my-turn-to-act? state side)]
    (cond
      (state/game-over? gs) :game-over
      (some? (:run gs)) :run
      (or (seat-owns-trigger-decision? my-prompt)
          (has-real-decision? my-prompt)) :decision-required
      my-turn? :my-turn
      (not= side "corp") :no-run
      :else :park)))

(defn- park-continuable?
  "Statuses that mean 'this run is over but the opponent's turn is not' — so a
   parked monitor should return to its post rather than hand control back."
  [status]
  (contains? #{:run-complete :no-run} status))

(defn- monitor-active-run!
  "Own one active run: (re)apply the pre-committed strategy, then loop."
  [flags]
  (reset-strategy!)
  ;; Per-run scratch state, same as run! — the CORP seat never calls run!, it
  ;; only ever enters a run through here. NB reported-events is deliberately NOT
  ;; reset here: it is game-scoped, and clearing it on each monitor-run re-issue
  ;; would re-report the same event every time. (Guest review of #31.)
  (reset-window-grace!)
  (let [strategy-flags (dissoc flags :since :persistent :return-on-signal)]
    (when (seq strategy-flags)
      (set-strategy! strategy-flags)
      (println (format "🎯 Strategy: %s"
                       (clojure.string/join
                        ", " (map (fn [[k v]]
                                    (if (set? v)
                                      (str (name k) " " (clojure.string/join "," v))
                                      (name k)))
                                  strategy-flags))))))
  (println "👁️  Monitoring run... (auto-passing boring windows)")
  (auto-continue-loop! :return-on-runner-signal (boolean (:return-on-signal flags))
                       :persistent (boolean (:persistent flags))))

(defn park-sleep!
  "One idle tick of the park loop. A seam: `Thread/sleep` is a Java static and
   cannot be redefined, so tests drive the loop by redefining this."
  []
  (Thread/sleep 1000))

(defn- park-and-monitor!
  "Persistent defender that owns the OPPONENT'S WHOLE TURN, not just one run.

   Issue #31, Fix A — the bug this exists to kill: `monitor-run!` used to return
   :no-run the instant it was called with no run active, EVEN under --persistent.
   So a Corp seat that armed its monitor a moment too early (or re-armed between
   runs while the model was thinking) simply left the post. The Runner would then
   start a run, arrive at a rez window with NOBODY HOME, wait, ping, and finally
   jack out. In marquee d6962df4 that happened on every run: 5 jack-outs, 1
   encounter, 1 rez — and the Corp seat's own report said the quiet part out loud:
   \"the Runner jacked out before my monitor engaged ('no active run')\".

   Parking makes the Corp's presence at the window independent of model latency:
   combined with a pre-committed --rez/--no-rez it answers windows instantly.
   Returns only on a real decision, the opponent's turn ending, game over, or
   timeout.

   `deadline` bounds total IDLE parking for the WHOLE invocation and is threaded
   through every re-entry — it is NOT restarted each time we return to the post,
   or a Runner making runs at intervals shorter than the timeout would keep one
   invocation alive indefinitely (and, with the heartbeat keep-alive below,
   looking alive to the opponent indefinitely). An ACTIVE run is owned by
   `auto-continue-loop!`, which is bounded by its own timeout and is making
   progress, so it does not draw down the idle budget."
  [flags deadline]
  (println "🅿️  Parked — waiting for the opponent to start a run (persistent; owns the whole turn)")
  (loop []
    (if (> (System/currentTimeMillis) deadline)
      (do (println "⚠️  Park stopped: idle timeout reached (re-issue monitor-run --persistent)")
          {:status :timeout :wake-reason :timeout :cursor (state/get-cursor)})
      (let [st @state/client-state
            side (:side st)]
        (case (park-wake-reason st side)
          :game-over
          (do (println "🏁 Game over — leaving the post")
              {:status :game-over :wake-reason :game-over :cursor (state/get-cursor)})

          :decision-required
          (let [my-prompt (get-in st [:game-state (keyword side) :prompt-state])]
            (println "🛑 Decision required — you are holding a prompt only you can resolve")
            (when-let [m (:msg my-prompt)] (println (format "   Prompt: %s" m)))
            {:status :decision-required :wake-reason :decision-required
             :prompt my-prompt :cursor (state/get-cursor)})

          :my-turn
          ;; DEBOUNCE. The briefs tell the seat to take its post "the instant you
          ;; end your turn", but end-turn! returns before :active-player flips on
          ;; the next diff — so a Corp that just ended its turn can momentarily
          ;; still look like the active player and get told "your move" with 0
          ;; clicks. Confirm the turn really has flipped before believing it.
          (do (park-sleep!)
              (if (= :my-turn (park-wake-reason @state/client-state side))
                (do (println "🔔 Opponent's turn ended — your move")
                    {:status :my-turn :wake-reason :my-turn :cursor (state/get-cursor)})
                (recur)))

          :run
          (let [result (monitor-active-run! flags)]
            (if (park-continuable? (:status result))
              (do (println "🅿️  Run ended — back to the post (opponent's turn continues)")
                  (recur))
              (assoc result :cursor (state/get-cursor))))

          :no-run
          (do (println "⚠️  No active run to monitor (and none can start on this turn)")
              {:status :no-run :wake-reason :no-run :cursor (state/get-cursor)})

          :park
          (do (park-sleep!)
              (recur)))))))

(defn- park-deadline
  "One idle-park deadline per monitor-run! invocation."
  [flags]
  (+ (System/currentTimeMillis) (or (:park-timeout-ms flags) 300000)))

(defn monitor-run!
  "Corp command to enter auto-continue mode during a run.

   When runner initiates a run, corp can call this to auto-handle
   boring paid ability windows. The loop will pause when:
   - Corp has a real decision (rez opportunity, ability choice)
   - Notable events occur
   - Run ends

   This enables the 'both sides auto-pass' flow where neither player
   has to manually pass empty windows.

   Flags:
     --no-rez            Auto-decline all rez opportunities
     --rez <ice-name>    Auto-rez named ICE; PAUSE (return a rez decision) on other unrezzed ICE
     --fire-unbroken     Auto-fire unbroken subs when Runner signals
     --fire-if-asked     Sleep mode: auto-fire, auto-continue, wake only for rez
     --persistent        Stay in the loop across empty opponent-priority windows
                         (sleep & recheck instead of exiting on :waiting-for-opponent).
                         For autonomous Corp seats — one monitor-run owns the whole
                         Runner run; wakes only for a real rez/fire/access decision
                         or run end. Eliminates re-issuing through symmetric passes.
     --since <cursor>    Fast-return: immediately return if run ended/started since cursor

   Usage:
     (monitor-run!)                           ; Auto-pass until decision needed
     (monitor-run! \"--no-rez\")              ; Also auto-decline all rez opportunities
     (monitor-run! \"--rez\" \"Tithe\")        ; Auto-rez Tithe; pause on any other unrezzed ICE
     (monitor-run! \"--fire-if-asked\")       ; Sleep until run ends
     (monitor-run! \"--persistent\")          ; Own the whole run; wake only for decisions
     (monitor-run! \"--since\" \"892\")        ; Fast-return if state advanced"
  [& args]
  (let [{:keys [flags]} (if (seq args) (parse-run-flags (vec args)) {:flags {}})
        since-cursor (:since flags)
        current-cursor (state/get-cursor)
        run (get-in @state/client-state [:game-state :run])
        ;; ONE idle-park budget for this whole invocation, shared by every
        ;; re-entry to the post (see park-and-monitor!).
        deadline (park-deadline flags)]
    (cond
      ;; --since fast-return: check if state already advanced
      (and since-cursor (> current-cursor since-cursor) (nil? run))
      (do
        (println (format "⚡ Fast-return: run ended (cursor %d > %d)" current-cursor since-cursor))
        {:status :run-complete
         :wake-reason :run-complete
         :cursor current-cursor
         :fast-return true})

      (and since-cursor (> current-cursor since-cursor) run)
      (do
        (println (format "⚡ Fast-return: new run active (cursor %d > %d)" current-cursor since-cursor))
        {:status :new-run
         :wake-reason :new-run
         :cursor current-cursor
         :fast-return true})

      ;; No active run.
      ;; --persistent PARKS here (owns the opponent's whole turn) instead of
      ;; abandoning the post — see park-and-monitor! for why (#31 Fix A).
      ;; Hand-driven monitor-run keeps the old immediate :no-run return.
      (nil? run)
      (if (:persistent flags)
        (park-and-monitor! flags deadline)
        (do
          (println "⚠️  No active run to monitor")
          {:status :no-run
           :wake-reason :no-run
           :cursor current-cursor}))

      ;; Normal monitoring flow
      :else
      (do
        (when (:persistent flags)
          (println "🔁 Persistent mode — owns the whole run (wakes for decisions / run end)"))
        (let [result (monitor-active-run! flags)]
          ;; A persistent monitor whose run just ENDED returns to the post: the
          ;; opponent's turn is still running and they may start another run.
          ;; (Re-arming per-run is exactly the gap that left windows unattended.)
          (if (and (:persistent flags) (park-continuable? (:status result)))
            (park-and-monitor! flags deadline)
            (assoc result :cursor (state/get-cursor))))))))

;; ============================================================================
;; Convenience Wrapper
;; ============================================================================

(defn continue!
  "Alias for continue-run with --force flag.
   Bypasses all smart checks and just sends continue command.
   Use for manual control when you know what you're doing.

   This is the old 'continue' primitive behavior - passes priority immediately
   without checking for decisions, opponent actions, or important events.

   Usage:
     (continue!)  ; Just send continue, no checks"
  []
  (continue-run! "--force"))
