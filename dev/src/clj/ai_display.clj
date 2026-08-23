(ns ai-display
  "Display functions for game status, board state, and HUD file management"
  (:require [ai-state :as state]
            [ai-hud-utils :as hud]
            [ai-core :as core]
            [ai-basic-actions :as actions]
            [clojure.string :as str]
            [jinteki.cards :refer [all-cards]]))

;; ============================================================================
;; HUD File Management (Delegated to ai-hud-utils)
;; ============================================================================

(def update-hud-section hud/update-hud-section)

;; Forward declaration: run-server-display and print-run-phase-ladder! (defined
;; below, near the run-priority helpers) are used by the status displays above
;; their definitions.
(declare run-server-display)
(declare print-run-phase-ladder!)
;; Run-window priority helpers (defined near run-priority-hint-lines) — used by
;; the status displays above their definitions.
(declare run-status-headline)
(declare print-run-window-priority!)
;; The encounter authority (wire [:encounters :ice] first) and its liveness
;; predicate — defined with the run-window helpers below, used by the status
;; displays above them.
(declare encountered-ice)
(declare live-encounter?)

;; ============================================================================
;; Status & Information
;; ============================================================================

(defn- format-counters
  "Format counters on a card for display. Returns string like '[3adv][2virus]' or empty string if no counters."
  [card]
  (let [adv (:advance-counter card 0)
        counters (:counter card {})
        parts (cond-> []
                (pos? adv) (conj (str adv "adv"))
                (:power counters) (conj (str (:power counters) "power"))
                (:virus counters) (conj (str (:virus counters) "virus"))
                (:credit counters) (conj (str (:credit counters) "credits")))]
    (if (seq parts)
      (str " [" (clojure.string/join "][" parts) "]")
      "")))

(defn compact-counter-suffix
  "Pure: the counters on a card, in the compact board's one-line idiom —
   \"(3v)\", \"(2v,1p)\", \"(12c)\", \"(2adv)\" — or \"\" when the card carries
   none. Letters, not words, because this rides inside a name list; the full
   board's format-counters spells them out.

   Why the compact board needs them at all: every late-game run budget in the
   2026-08-21 pair turned on Leech's virus counters, and neither compact view
   showed a counter, so the seat fell back to `board | grep -i leech` on every
   decision (#151 item 12). The compact board already names the programs — the
   counters belong on those names."
  [card]
  (let [counters (:counter card {})
        adv (:advance-counter card 0)
        ;; PRESENT-at-zero is printed. A Leech that has spent its last counter
        ;; keeps :virus 0 on the wire, and rendering that as a bare `Leech` is
        ;; indistinguishable from "this view doesn't show counters" — which is
        ;; the exact confusion this suffix exists to end (guest panel). Only
        ;; :advance-counter is suppressed at zero: it is a default field on every
        ;; card, so printing 0adv everywhere would drown the signal.
        parts (cond-> []
                (contains? counters :virus) (conj (str (:virus counters) "v"))
                (contains? counters :power) (conj (str (:power counters) "p"))
                (contains? counters :credit) (conj (str (:credit counters) "c"))
                (pos? adv) (conj (str adv "adv")))]
    (if (seq parts)
      (str "(" (str/join "," parts) ")")
      "")))

(defn compact-unrezzed-content
  "Pure: the compact board's summary of a server's UNREZZED root cards, e.g.
   \"1?card(2adv)\" or \"2?card(3adv)\". Returns nil when every root card is
   rezzed.

   The old form was a bare \"1?\". In `REMOTE1[…|Manegarm Skunkworks,1?]` that
   read as \"one advancement\" — flatly contradicting a log that said the card
   had been advanced twice — when it means \"one unknown root card\" (#151
   item 13). So: say `card`, and put the advancement counts the seat was
   actually hunting for in the parenthetical, one entry per advanced card."
  [content-list]
  (let [unrezzed (remove :rezzed content-list)
        advanced (->> unrezzed
                      (map #(:advance-counter % 0))
                      (filter pos?))]
    (when (seq unrezzed)
      (str (count unrezzed) "?card"
           (when (seq advanced)
             (str "(" (str/join "," (map #(str % "adv") advanced)) ")"))))))

(defn remote-threat-counts
  "Pure: the Runner-visible remote-server threat counts, from the Corp's
   :servers map. Returns {:unrezzed n :advanced n}, both counting SERVERS (not
   cards) — matching the wording of the line that prints them.

     :unrezzed - remotes holding at least one unrezzed card
     :advanced - remotes holding at least one unrezzed card with advancements

   Keys are matched by NAME, so both the keyword form the wire actually sends
   (:remote1) and the string form fixtures sometimes use (\"remote1\") work.
   This used to filter on `(string? (key %))` against a keyword-keyed map, so
   both counts were hardcoded zero for the whole life of every game — `status`
   read `Remotes 0 unrezzed / 0 advanced` in the same snapshot where `board`
   showed an advanced card sitting in a remote (#137). These two numbers are
   the only actionable ones on that line: an unrezzed, advanced remote is the
   scoring-remote tell, so a false zero steers the seat off the one server it
   should be pressuring."
  [servers]
  (let [remote? (fn [k] (and (or (keyword? k) (string? k))
                             (boolean (re-matches #"remote\d+" (name k)))))
        remotes (filter #(remote? (key %)) servers)
        unrezzed-cards (fn [[_ server]] (remove :rezzed (:content server)))]
    {:unrezzed (count (filter #(seq (unrezzed-cards %)) remotes))
     :advanced (count (filter (fn [entry]
                                (some #(pos? (get % :advance-counter 0))
                                      (unrezzed-cards entry)))
                              remotes))}))

(defn format-runner-agenda-line
  "Runner's-eye agenda threat line, with units made explicit.

   The old single header — `Missing: 18 (Drawn: ~0, HQ: 5, R&D: 38, Remotes: 0/0)`
   — conflated two different units under one parenthetical, so it read as
   '18 agendas, 5 of them in HQ, 38 in R&D'. In fact:
     missing         - agenda *POINTS* not yet scored or stolen (Corp needs 7)
     expected-drawn  - estimated agenda *CARDS* the Corp has likely drawn into HQ
     hq-size/rd-size - total *CARD* counts in HQ / R&D — the haystack, not agendas
     unrezzed/advanced - remote-server counts the Runner can see
   Labelling the points-vs-cards split removes the misread."
  [agenda-points missing expected-drawn hq-size rd-size unrezzed-count advanced-count]
  (format "Agenda Points: %d / 7  │  Unaccounted: %d agenda pts — hiding among HQ %d / R&D %d cards, Remotes %d unrezzed / %d advanced (~%d agenda cards likely drawn)"
          agenda-points missing hq-size rd-size unrezzed-count advanced-count expected-drawn))

(defn sub-count-summary
  "Pure: the sub-count portion of an encounter ICE status line, from the ICE's
   :subroutines vector. 'Unbroken' counts only ACTIONABLE subs — neither :broken
   nor :fired — matching the run handlers' own filter. Counting :fired subs as
   unbroken made a fully-resolved encounter read as still pending on BOTH seats
   (#99). Fired/broken totals get a parenthetical so the resolved state is
   explicit, e.g. \"0 unbroken of 2 (2 fired)\"."
  [subs]
  (let [total (count subs)
        fired (count (filter :fired subs))
        broken (count (filter #(and (:broken %) (not (:fired %))) subs))
        actionable (count (filter #(and (not (:broken %)) (not (:fired %))) subs))
        detail (cond-> []
                 (pos? fired) (conj (str fired " fired"))
                 (pos? broken) (conj (str broken " broken")))]
    (str actionable " unbroken of " total
         (when (seq detail) (str " (" (str/join ", " detail) ")")))))

(defn show-status
  "Display current game status or lobby state"
  []
  (let [cs @state/client-state
        lobby (:lobby-state cs)
        raw-gs (state/get-game-state)
        ;; #139/#142: a raw-diff VECTOR left in :game-state is not a board —
        ;; treat it as no board rather than rendering a status from it.
        gs (when (map? raw-gs) raw-gs)]
    ;; Not in a game or lobby
    (if (and (nil? lobby) (nil? gs))
      (if (:gameid cs)
        ;; #139: seated in a STARTED game (no :lobby-state) but holding no board
        ;; — what a failed resync leaves behind. "Not in a game → reset.sh" was
        ;; the wrong diagnosis: this client IS in a game, and reset.sh destroys
        ;; the one a retry might have recovered.
        (do
          (println "📊 STATUS")
          (println (format "\n⚠️  Seated in game %s, but this client holds NO BOARD — the game state is unknown, not empty."
                           (:gameid cs)))
          (println "   Either the game just started and its first full state is still arriving,")
          (println "   or a resync cleared the cache and the replacement state has not arrived.")
          (println (format "\n💡 Retry the read; if it keeps failing: ./dev/send_command <side> resync %s" (:gameid cs)))
          (println "   (do NOT run the reset script — it destroys the game you are seated in)"))
        (do
          (println "📊 STATUS")
          (println "\n⚠️  Not in a game")
          (println "\n💡 To start a new game:")
          (println "   ./dev/reset.sh")
          (println "\n   Or join an existing game:")
          (println "   ./dev/send_command <side> list-lobbies")
          (println "   ./dev/send_command <side> join <game-id> <Side>")))
      ;; Check if we're in a lobby but game hasn't started yet
      (cond
        ;; Game has ended - show winner / tie banner
        (and gs (or (:winner gs) (and (:reason gs) (:end-time gs))))
        (let [winner (:winner gs)
              winner-str (when winner (str/capitalize (name winner)))
              winning-user (:winning-user gs)
              reason (:reason gs)]
          (println "📊 GAME STATUS")
          (println (str "\n🏁 GAME OVER (Turn: " (or (:turn gs) "?") ")"))
          (if winner
            (println (str "\n🏆 " winner-str " wins"
                          (when winning-user (str " (" winning-user ")"))))
            (println "\n🤝 The game is a tie"))
          (when reason
            (println "Reason:" reason))
          (let [runner-pts (get-in gs [:runner :agenda-point])
                corp-pts (get-in gs [:corp :agenda-point])]
            (when (or runner-pts corp-pts)
              (println (format "Agenda points - Corp: %s, Runner: %s"
                               (or corp-pts 0) (or runner-pts 0))))))

        ;; Lobby exists but game not started - show lobby status
        (and lobby (not (:started lobby)))
        (let [players (:players lobby)
            player-count (count players)
            players-with-decks (count (filter :deck players))
            sides (set (map :side players))
            ready? (and (= 2 player-count)
                       (every? :deck players)
                       (every? #(get-in % [:deck :identity]) players)
                       (contains? sides "Corp")
                       (contains? sides "Runner"))]
        (println "📊 LOBBY STATUS")
        (println "\nGame:" (:title lobby))
        (println "Format:" (:format lobby))
        (println "Players:" player-count "/2")
        (doseq [player players]
          (let [username (get-in player [:user :username])
                side (:side player)
                has-deck? (some? (:deck player))
                deck-name (get-in player [:deck :name])]
            (println (format "  • %s (%s) - %s"
                           username
                           side
                           (if has-deck?
                             (str "✅ " deck-name)
                             "⏳ No deck selected")))))
        (println "\nStatus:"
               (cond
                 ready? "✅ Ready to start! Use 'start-game' or 'auto-start'"
                 (< player-count 2) (format "⏳ Waiting for players (%d/2)" player-count)
                 (< players-with-decks 2) (format "⏳ Waiting for deck selection (%d/2 ready)" players-with-decks)
                 :else "⏳ Waiting...")))

        ;; Game started (:lobby-state :started) but no board yet. Two states
        ;; share this signature (#139 guest panel): the healthy window between
        ;; the started :lobby/state and :game/start, and a failed resync. Same
        ;; wording as the no-lobby boardless branch, with the id we hold —
        ;; `resync` takes it, and '<game-id>' was a placeholder a seat cannot run.
        (nil? gs)
        (do
          (println "📊 GAME STATUS")
          (println (format "\n⚠️  Seated in game %s (started), but this client holds NO BOARD yet — the game state is unknown, not empty."
                           (:gameid cs)))
          (println "   Either the game just started and its first full state is still arriving,")
          (println "   or a resync cleared the cache and the replacement state has not arrived.")
          (println (format "\n💡 Retry the read; if it keeps failing: ./dev/send_command <side> resync %s" (:gameid cs))))

        ;; Show game status
        :else
        (let [my-side (:side @state/client-state)
            game-id (:gameid @state/client-state)
            active-side (state/active-player)
            turn-num (state/turn-number)
            end-turn (get-in gs [:end-turn])
            prompt (state/get-prompt)
            prompt-type (:prompt-type prompt)
            run-state (get-in gs [:run])
            ;; #117: `status` used to re-derive the boundary here (both sides at
            ;; 0 clicks => "waiting to start <next>") while printing the engine's
            ;; :active-player on the Turn line right above it. On an orphaned
            ;; turn that produced the two contradictory lines in one block —
            ;; "Turn: 10 - corp" and "🟢 Waiting to start runner turn". One
            ;; derivation now, shared with prompt / game-over-status / snapshot /
            ;; diagnose-blocker.
            ts (state/get-turn-status)
            ;; At a boundary the wire's :active-player still names the player who
            ;; just FINISHED; show who is actually up (same flip as `snapshot`).
            display-side (if (:waiting-to-start? ts) (:next-player ts) active-side)
            ;; Per-side clicks line. The "(End of Turn)" marker belongs to the
            ;; ACTIVE side whenever its turn hasn't ended, but the end-turn steer
            ;; belongs only to the seat that owns that turn — :turn-orphaned? is
            ;; side-relative, so a Runner reading the CORP section is never
            ;; offered a command that would end the Corp's turn for it.
            ;;
            ;; This used to carry a `(not both-zero-clicks)` term. That did NOT
            ;; make the hint unreachable (my first claim; the guest panel
            ;; disproved it) — the engine lets a player end their turn early and
            ;; does not zero their remaining clicks, so the opponent can hold
            ;; clicks while the active player is at 0, and the hint fired there.
            ;; What it did do is suppress the hint in the ordinary case, where
            ;; the inactive side is at 0 clicks too — i.e. exactly the case the
            ;; hint was written for (#117).
            print-clicks-line
            (fn [side-name clicks]
              (let [clicks (or clicks 0)]
                (if (and (= side-name active-side)
                         (zero? clicks)
                         (not (:waiting-to-start? ts)))
                  (do
                    (println "Clicks:" clicks "(End of Turn)")
                    (when (:turn-orphaned? ts)
                      (println "💡 Use 'smart-end-turn' to finish your turn")))
                  (println "Clicks:" clicks))))
            runner-missing? (and gs (nil? (get-in gs [:runner :user])))
            corp-missing? (and gs (nil? (get-in gs [:corp :user])))]

        ;; If a player has left, show recovery message
        (if (or runner-missing? corp-missing?)
          (do
            (println "📊 GAME STATUS")
            (println "\n⚠️  PLAYER DISCONNECTED")
            (when runner-missing?
              (println "\n❌ Runner has left the game"))
            (when corp-missing?
              (println "\n❌ Corp has left the game"))
            (when my-side
              (println "\n💡 To reconnect:")
              (println "   ./dev/send_command" (str/lower-case my-side) "join" game-id my-side))
            (println "\nOr use ai-bounce.sh to restart both clients:")
            (println "   ./dev/ai-bounce.sh" game-id))

          ;; Normal game status display
          (do
            (println "📊 GAME STATUS")
            (println "\nTurn:" turn-num "-" display-side)

            ;; Active player / waiting status
            (if run-state
              ;; During an active run, run-window priority is authoritative. The
              ;; turn-level active-side ('it's the Runner's turn') misleads inside
              ;; a run — the Corp still owns its rez / upgrade sub-steps — so a
              ;; Corp seat at its own rez window would otherwise read 'Waiting for
              ;; runner to act'. (Michael forum [154]: surface waiting-on-X.)
              (println "Status:" (run-status-headline
                                  gs (clojure.string/lower-case (or my-side "runner"))))
              (do
                (println "Status:" (:status-emoji ts) (:status-text ts))
                ;; The next-action hints the old bespoke branches carried.
                (cond
                  (and (:waiting-to-start? ts) (:can-act? ts))
                  (println "💡 Use 'start-turn' to begin your turn")

                  (:turn-orphaned? ts)
                  (do
                    (println "💡 Use 'smart-end-turn' — you are the active player and the")
                    (println "   engine's :end-turn flag is not set, so ending is safe here.")))))

            ;; Run status
            (when run-state
              (println "\n🏃 ACTIVE RUN:")
              (println "  Server:" (run-server-display (last (:server run-state))))
              ;; Explicit phase ladder (forum [099]) — a YOU-ARE-HERE marker over
              ;; the whole run arc beats a bare "Phase: movement" line that makes
              ;; the seat reconstruct where it is from the rules. Falls back to the
              ;; bare phase/position lines if the phase is unmodelled.
              (when-not (print-run-phase-ladder! @state/client-state run-state
                                                 (clojure.string/lower-case (or my-side "runner")))
                (println "  Phase:" (:phase run-state))
                (when-let [pos (:position run-state)]
                  (println "  Position:" pos)))
              ;; Show ICE info during encounter-ice
              (when (or (= "encounter-ice" (:phase run-state))
                        (live-encounter? @state/client-state))
                (when-let [current-ice (encountered-ice @state/client-state)]
                  (when (:rezzed current-ice)
                    (let [ice-title (:title current-ice)
                          ice-str (or (:current-strength current-ice) (:strength current-ice))
                          ice-subtypes (clojure.string/join " " (or (:subtypes current-ice) []))
                          subs (:subroutines current-ice)]
                      (println (format "  🧊 ICE: %s (str %s)" ice-title ice-str))
                      (println (format "     Type: %s" ice-subtypes))
                      (println (format "     Subs: %s" (sub-count-summary subs)))))))
              ;; Whose move is it now + what 'continue' does (same guidance the
              ;; `prompt` command shows) — status used to print only the ladder,
              ;; leaving the seat to guess whose window it was.
              (print-run-window-priority! @state/client-state run-state (:phase run-state)
                                          (clojure.string/lower-case (or my-side "runner"))))

            (println "\n--- RUNNER ---")
            (let [hosted (state/runner-hosted-credits)]
              (if (pos? (:total hosted))
                (println (format "Credits: %d (+%d hosted: %s)"
                                 (state/runner-credits)
                                 (:total hosted)
                                 (clojure.string/join ", "
                                   (map #(format "%s %d" (:title %) (:credits %))
                                        (:sources hosted)))))
                (println "Credits:" (state/runner-credits))))
            (print-clicks-line "runner" (get-in gs [:runner :click]))
            ;; This is the RUNNER section: report the RUNNER's grip size, not
            ;; ours. It used to read my-hand-count, so a Corp viewer saw its
            ;; OWN hand size labelled as the Runner's — the one number a kill
            ;; calculation depends on (#85; marquee g1 priced a flatline off
            ;; it). Hand size is public info, served as :hand-count.
            (let [hand-count (get-in gs [:runner :hand-count]
                                     (count (get-in gs [:runner :hand] [])))
                  max-hand-size (get-in gs [:runner :hand-size-modification] 5)
                  tags (get-in gs [:runner :tag :base] 0)]
              (println "Hand:" hand-count "cards")
              (when (pos? tags)
                (println (str "🏷️  Tags: " tags)))
              (when (and (= "runner" my-side) (> hand-count max-hand-size))
                (println "⚠️  Over hand size! Discard to" max-hand-size "at end of turn")))
            (let [agenda-points (get-in gs [:runner :agenda-point] 0)
                  corp-scored (get-in gs [:corp :agenda-point] 0)
                  runner-stolen agenda-points
                  hq-size (get-in gs [:corp :hand-count] 0)
                  rd-size (get-in gs [:corp :deck-count] 0)
                  discard-size (count (get-in gs [:corp :discard] []))
                  initial-deck-size (+ rd-size hq-size discard-size (* corp-scored 1))
                  total-agendas (cond
                                 (<= initial-deck-size 44) 18
                                 (<= initial-deck-size 49) 20
                                 (<= initial-deck-size 54) 22
                                 :else (+ 22 (* 2 (quot (- initial-deck-size 50) 5))))
                  accounted (+ corp-scored runner-stolen)
                  missing (- total-agendas accounted)
                  cards-drawn (max 0 turn-num)
                  agenda-density (if (pos? initial-deck-size)
                                  (/ (float total-agendas) initial-deck-size)
                                  0)
                  expected-drawn (int (* cards-drawn agenda-density))
                  {unrezzed-count :unrezzed advanced-count :advanced}
                  (remote-threat-counts (get-in gs [:corp :servers] {}))]
              ;; The threat line is derived entirely from deck/hand counts; at
              ;; turn 0 those are all zero and it renders as pure noise
              ;; ('Unaccounted: 18 agenda pts' over an empty board — #104).
              (if (and (= "runner" my-side) (pos? initial-deck-size))
                (println (format-runner-agenda-line
                          agenda-points missing expected-drawn hq-size rd-size unrezzed-count advanced-count))
                (println "Agenda Points:" agenda-points "/ 7")))
            (println "\n--- CORP ---")
            (println "Credits:" (state/corp-credits))
            (print-clicks-line "corp" (get-in gs [:corp :click]))
            (let [hand-count (state/corp-hand-count)
                  max-hand-size (get-in gs [:corp :hand-size-modification] 5)]
              (println "Hand:" hand-count "cards")
              (when (and (= "corp" my-side) (> hand-count max-hand-size))
                (println "⚠️  Over hand size! Discard to" max-hand-size "at end of turn")))
            (let [agenda-points (get-in gs [:corp :agenda-point] 0)
                  runner-tags (get-in gs [:runner :tag :base] 0)]
              (println "Agenda Points:" agenda-points "/ 7")
              (when (and (= "corp" my-side) (pos? runner-tags))
                (println (str "🏷️  Runner tagged! (" runner-tags " tag" (when (> runner-tags 1) "s") ")"))
                (println "   💡 trash-resource ($2 + click to trash a resource)")))
            (when (and prompt (not (state/waiting-prompt-type? prompt-type)))
              (println "\n🔔 Active Prompt:" (:msg prompt)))

            ;; Show recent log entries (summarized)
            (when-let [log (get-in gs [:log])]
              (let [recent-log (take-last 4 log)
                    summarized (core/summarize-log-entries recent-log)]
                (when (seq summarized)
                  (println "\n--- RECENT LOG ---")
                  (doseq [entry summarized]
                    (println " " (:text entry))))))

            nil)))))))

(defn status
  "Show current game status and return client state"
  []
  (show-status)
  @state/client-state)

(defn game-over-status
  "Print a single machine-readable line describing game-over state.
   Intended for tooling (e.g. the self-play regression harness) so it does
   not have to screen-scrape the human-facing status banner.

   Output (one line):
     NO-GAME                                  - no game state loaded
     GAME-OVER winner=corp turn=18                     - decided (winner = corp|runner)
     GAME-OVER winner=tie turn=12                      - tied game
     GAME-GONE turn=9                                  - server closed our lobby (#93);
                                                         treat as a stop condition like
                                                         GAME-OVER — there is no game
                                                         left to play, only a snapshot
     AWAITING-START turn=12 next-player=runner         - clean turn boundary
     AWAITING-START turn=12 next-player=runner open-prompt=mine
                                                       - boundary blocked on OUR
                                                         unresolved prompt (#104)
     IN-PROGRESS turn=12 whose-turn=runner clicks=3    - game still running

   AWAITING-START marks a clean turn boundary (a player ended their turn, or
   both sides are at 0 clicks) and names who acts next, so tooling can apply a
   patient boundary budget instead of mistaking a slow opponent's turn-start
   think-time for a stall. The optional open-prompt=mine suffix (additive —
   consumers prefix-match) says the boundary can't complete until our own open
   prompt (e.g. the end-of-turn discard) resolves; without it the pairing
   'AWAITING-START + live discard prompt' read as a desync.

   The clicks field is the active player's remaining clicks, so tooling can
   distinguish a within-turn spin (same turn + same clicks, not progressing)
   from normal play."
  []
  (let [gs (state/get-game-state)]
    (if (nil? gs)
      (println "NO-GAME")
      (let [{:keys [game-over? winner turn-number whose-turn
                    waiting-to-start? turn-orphaned? next-player]} (state/get-turn-status)
            clicks (when whose-turn (get-in gs [(keyword whose-turn) :click]))]
        (cond
          game-over?
          (println (format "GAME-OVER winner=%s turn=%s"
                           (if winner (str/lower-case (name winner)) "tie")
                           (or turn-number "?")))

          ;; The server closed our lobby but the game never reached a decided
          ;; state (#93) — e.g. an abandoned game reaped, or an unseat we did
          ;; not initiate. A decided game stays GAME-OVER (the branch above);
          ;; this catches the teardown-without-result case, which used to be
          ;; reported as IN-PROGRESS forever off the cached snapshot.
          (state/lobby-gone?)
          (println (format "GAME-GONE turn=%s" (or turn-number "?")))

          waiting-to-start?
          ;; #104: AWAITING-START while OUR prompt is still open (e.g. the
          ;; end-of-turn discard) read as a desync to both guest models. The
          ;; pairing is honest — the boundary can't complete until the prompt
          ;; resolves — so name the blocker in the same machine-readable line.
          ;; Additive last field; prefix parsing is unaffected.
          (let [my-prompt (state/get-prompt)
                open-prompt? (and my-prompt
                                  (not= "waiting" (:prompt-type my-prompt)))]
            (println (str (format "AWAITING-START turn=%s next-player=%s"
                                  (or turn-number "?")
                                  (or next-player "?"))
                          (when open-prompt? " open-prompt=mine")
                          ;; #117/guest-panel HIGH: the opening mulligan IS a
                          ;; boundary, but the named next-player cannot start
                          ;; until the opponent finishes mulliganing (#87) — so
                          ;; the bare line disagreed with that seat's own prompt.
                          ;; Same additive-field contract as open-prompt=mine.
                          (when (state/opponent-mulligan-pending? @state/client-state)
                            " blocked=opponent-mulligan")
                          ;; The mirror. Same boundary, opposite owner: here the
                          ;; named next-player cannot start because it has not
                          ;; answered its OWN mulligan. Reported alongside
                          ;; open-prompt=mine rather than instead of it — the
                          ;; fields are additive and both facts are true.
                          (when (state/my-mulligan-pending? @state/client-state)
                            " blocked=my-mulligan"))))

          :else
          ;; #117: an orphaned turn (active player out of clicks, :end-turn not
          ;; set) reports here, as IN-PROGRESS with clicks=0 — which is exactly
          ;; true, and is already the documented same-turn/same-clicks spin
          ;; signature tooling watches for. It used to be reported as
          ;; AWAITING-START next-player=<opponent>, naming a boundary that had
          ;; not happened and a player who could not act.
          ;;
          ;; `owes=end-turn` is an ADDITIVE last field (same contract as
          ;; open-prompt=mine above; prefix parsing is unaffected), and appears
          ;; only on the seat that actually owns the turn — :turn-orphaned? is
          ;; side-relative so this can never suggest an end-turn to the player
          ;; whose turn it isn't.
          (println (str (format "IN-PROGRESS turn=%s whose-turn=%s clicks=%s"
                                (or turn-number "?")
                                (or whose-turn "?")
                                (if (some? clicks) clicks "?"))
                        (when turn-orphaned? " owes=end-turn"))))))))

(defn ice-encounter-label
  "Annotation describing WHEN the Runner encounters this ICE during a run.

   The engine :ices vector is ordered innermost-first: index 0 is closest to the
   server (encountered LAST), the highest index is outermost (encountered FIRST).
   This is the reverse of the obvious low-to-high reading order, which silently
   inverted run-budget planning (issue #39).

   We also surface the run-time :position the Runner will see in the encounter
   prompt. That position is 1-based (current-run-ice maps position->ice[pos-1]),
   so it is `idx + 1` — NOT the 0-based board `#idx`. Spelling out 'position N/M'
   bridges the two numberings so the board and the run prompt agree.

   `idx` is the engine index of the ICE; `total` is the ICE count on the server."
  [idx total]
  (let [pos (inc idx)]  ;; run prompt shows 1-based :position (idx+1)
    (cond
      (<= total 1) ""  ;; only ICE on the server: no ordering ambiguity
      (= idx (dec total)) (format " ⟵ outermost — Runner encounters this 1st (run prompt: position %d/%d)" pos total)
      (= idx 0) (format " ⟵ innermost — encountered last, guards server (position %d/%d)" pos total)
      :else (format " ⟵ encounter #%d of %d (position %d/%d)" (- total idx) total pos total))))

(declare show-board* no-side-here!)

(defn show-board
  "Display full game board: all servers with ICE, Corp installed cards, Runner rig.

   #139: gated on the BOARD, not the side — a spectator has a board and no side
   and must keep this view (no-side-here!'s own spectator branch promises it);
   a client holding no board (failed resync, unstarted lobby, raw-diff vector)
   gets the one shared explainer instead of a rendered empty table."
  []
  (let [state @state/client-state]
    (if-not (map? (:game-state state))
      (no-side-here! state "the board")
      (show-board* state))))

(defn- show-board*
  "Render the board; the caller guarantees `state` holds one."
  [state]
  (let [gs (:game-state state)
        my-side (:side state)  ;; "corp" or "runner" (lowercase) - determines what we can see
        is-corp? (= "corp" (some-> my-side clojure.string/lower-case))
        corp-servers (:servers (:corp gs))
        runner-rig (get-in gs [:runner :rig])
        corp-deck-count (get-in gs [:corp :deck-count])
        corp-discard (get-in gs [:corp :discard])
        runner-deck-count (get-in gs [:runner :deck-count])
        runner-discard (get-in gs [:runner :discard])]
    (println "\n" (clojure.string/join "" (repeat 70 "=")))
    (println "🎮 GAME BOARD")
    (println (clojure.string/join "" (repeat 70 "=")))

    ;; Corp Servers
    (println "\n--- CORP SERVERS ---")
    (doseq [[server-key server] (sort-by first corp-servers)]
      (let [server-name (name server-key)
            ice-list (:ices server)
            content-list (:content server)]
        (when (or (seq ice-list) (seq content-list))
          (println (str "\n📍 " (clojure.string/upper-case server-name)))

          ;; Show ICE — listed in Runner encounter order (outermost first), which
          ;; is the REVERSE of the engine :ices vector. The #idx label keeps the
          ;; 0-based engine index; the annotation spells out the 1-based run-time
          ;; "position N/M" (idx+1) so the board and the run prompt agree. (#39)
          (if (seq ice-list)
            (let [ice-total (count ice-list)]
              (when (> ice-total 1)
                (println "  (top→bottom = Runner encounter order: outermost first)"))
              (doseq [[idx ice] (reverse (map-indexed vector ice-list))]
                (let [rezzed (:rezzed ice)
                      title (core/format-card-name-with-index ice ice-list)
                      subtypes (:subtypes ice)
                      subtype-str (if (seq subtypes)
                                    (clojure.string/join " " (map name subtypes))
                                    "?")
                      strength (:current-strength ice)
                      status-icon (if rezzed "🔴" "⚪")
                      ;; Corp sees their own unrezzed ICE, Runner sees "Unrezzed ICE"
                      display-name (cond
                                     rezzed title
                                     is-corp? (str title " [unrezzed]")
                                     :else "Unrezzed ICE")]
                  (println (str "  ICE #" idx ": " status-icon " "
                               display-name
                               (when rezzed (str " (" subtype-str ")"))
                               (when (and rezzed strength) (str " (str: " strength ")"))
                               (format-counters ice)
                               (ice-encounter-label idx ice-total))))))
            (println "  (No ICE)"))

          ;; Show Content (assets/agendas)
          (when (seq content-list)
            (let [rezzed-content (filter :rezzed content-list)
                  unrezzed-content (filter (complement :rezzed) content-list)]
              (doseq [card rezzed-content]
                (let [card-name (core/format-card-name-with-index card content-list)]
                  (println (str "  Content: 🔴 " card-name " (" (:type card) ")" (format-counters card)))))
              ;; Corp sees their own unrezzed cards, Runner sees "Unrezzed card"
              (doseq [card unrezzed-content]
                (let [card-name (core/format-card-name-with-index card content-list)
                      display-name (if is-corp?
                                     (str card-name " [unrezzed]")
                                     "Unrezzed card")]
                  (println (str "  Content: ⚪ " display-name (format-counters card)))))))
          )))

    ;; Runner Rig
    (println "\n--- RUNNER RIG ---")
    (let [programs (:program runner-rig)
          hardware (:hardware runner-rig)
          resources (:resource runner-rig)]
      (if (seq programs)
        (do
          (println "\n💾 Programs:")
          (doseq [prog programs]
            (let [prog-name (core/format-card-name-with-index prog programs)]
              (println (str "  • " prog-name
                           (when-let [strength (:current-strength prog)] (str " (str: " strength ")"))
                           (format-counters prog))))))
        (println "\n💾 Programs: (none)"))

      (if (seq hardware)
        (do
          (println "\n🔧 Hardware:")
          (doseq [hw hardware]
            (let [hw-name (core/format-card-name-with-index hw hardware)]
              (println (str "  • " hw-name (format-counters hw))))))
        (println "🔧 Hardware: (none)"))

      (if (seq resources)
        (do
          (println "\n📦 Resources:")
          (doseq [res resources]
            (let [res-name (core/format-card-name-with-index res resources)]
              (println (str "  • " res-name (format-counters res))))))
        (println "📦 Resources: (none)")))

    ;; Deck/Discard counts
    (println "\n--- DECK STATUS ---")
    (println (str "Corp Deck: " corp-deck-count " | Discard: " (count corp-discard)))
    (println (str "Runner Deck: " runner-deck-count " | Discard: " (count runner-discard)))

    (println (clojure.string/join "" (repeat 70 "=")))
    nil))

(declare show-board-compact*)

(defn show-board-compact
  "Display ultra-compact board state (2-5 lines, no decorations).
   #139: board-gated like show-board — an empty rig is an assertion, 'no board'
   is the truth."
  []
  (let [state @state/client-state]
    (if-not (map? (:game-state state))
      (no-side-here! state "the compact board")
      (show-board-compact* state))))

(defn- show-board-compact*
  "Render the compact board; the caller guarantees `state` holds one."
  [state]
  (let [gs (:game-state state)
        corp-servers (:servers (:corp gs))
        runner-rig (get-in gs [:runner :rig])]

    ;; Corp servers - one line per server with activity
    (print "Corp:")
    (doseq [[server-key server] (sort-by first corp-servers)]
      (let [server-name (name server-key)
            ice-list (:ices server)
            content-list (:content server)
            rezzed-ice (filter :rezzed ice-list)
            unrezzed-ice-count (- (count ice-list) (count rezzed-ice))
            rezzed-content (filter :rezzed content-list)
            unrezzed-content-count (- (count content-list) (count rezzed-content))]
        (when (or (seq ice-list) (seq content-list))
          (print (str " " (clojure.string/upper-case server-name) "["))
          ;; ICE summary
          (when (seq rezzed-ice)
            (print (clojure.string/join "," (map #(core/format-card-name-with-index % ice-list) rezzed-ice))))
          (when (> unrezzed-ice-count 0)
            (print (if (seq rezzed-ice) "," ""))
            (print (str unrezzed-ice-count "?ice")))
          (print "|")
          ;; Content summary
          (when (seq rezzed-content)
            (print (clojure.string/join "," (map #(str (core/format-card-name-with-index % content-list)
                                                      (compact-counter-suffix %))
                                                 rezzed-content))))
          (when-let [unknown (compact-unrezzed-content content-list)]
            (print (if (seq rezzed-content) "," ""))
            (print unknown))
          (print "]"))))
    (println)

    ;; Runner rig - one line
    (let [programs (:program runner-rig)
          hardware (:hardware runner-rig)
          resources (:resource runner-rig)]
      (println (format "Rig: Prog[%d] HW[%d] Res[%d]%s"
                      (count programs)
                      (count hardware)
                      (count resources)
                      (if (seq programs)
                        (str " - " (clojure.string/join
                                    ","
                                    (map #(str (core/format-card-name-with-index % programs)
                                               (compact-counter-suffix %))
                                         programs)))
                        ""))))
    nil))

(defn get-game-log
  "Get the game log from current game state"
  []
  (state/game-log))

(defn show-game-log
  "Display game log in readable format"
  ([] (show-game-log 20))
  ([n]
   (if-let [log (get-game-log)]
     (do
       (println "\n📜 GAME LOG (last" n "entries)")
       (doseq [entry (take-last n log)]
         (when (map? entry)
           (let [text (str/replace (:text entry "") "[hr]" "")]
             (println (str "  " text)))))
       nil)
     (println "No game log available"))))

(defn show-log
  "Display game log (natural language event history)"
  ([] (show-game-log 20))
  ([n] (show-game-log n)))

(defn get-lobby-list
  "Get the current lobby list from state"
  []
  (:lobby-list @state/client-state))

(defn list-active-game-ids
  "Return list of active game IDs (parseable format for scripts)
   Returns vector of game ID strings, or empty vector if none"
  []
  (if-let [games (get-lobby-list)]
    (vec (map :gameid games))
    []))

(defn show-games
  "Display available games in a readable format"
  []
  (if-let [games (get-lobby-list)]
    (do
      (println "\n📋 Available Games:")
      (doseq [game games]
        (println "\n🎮" (:title game))
        (println "   ID:" (:gameid game))
        (println "   Players:" (count (:players game)) "/" (:max-players game 2))
        (when-let [players (:players game)]
          (doseq [player players]
            (println "     -" (:side player) ":" (get-in player [:user :username] "Waiting..."))))
        (when (:started game)
          (println "   ⚠️  Game already started")))
      (println))
    (do
      (println "No games available.")
      nil)))

(defn show-log-compact
  "Display ultra-compact game log (recent N entries, one line each, no decorations)"
  ([] (show-log-compact 5))
  ([n]
   (let [recent (state/recent-log n)]
     (doseq [entry recent]
       (when (map? entry)
         (let [text (clojure.string/replace (:text entry "") "[hr]" "")]
           (println text))))
     nil)))

(declare show-status-compact*)

(defn show-status-compact
  "Display ultra-compact game status (1-2 lines, no decorations).
   #139: an unstarted lobby has its own line; a started game needs a BOARD —
   'Tnull-unknown … awaiting-start' was a confident phase claim about a board
   that wasn't there, in the line a seat polls in its read loop."
  []
  (let [cs @state/client-state
        lobby (:lobby-state cs)]
    (if (or (and lobby (not (:started lobby)))
            (map? (:game-state cs)))
      (show-status-compact* cs)
      (no-side-here! cs "the compact status"))))

(defn- show-status-compact*
  "Render the compact status from ONE captured state; the caller guarantees a
   lobby line or a board. Reads nothing from the atom (guest panel: a guard on
   one read and a renderer on a later read is a check/use race)."
  [state]
  (let [lobby (:lobby-state state)
        gs (:game-state state)]
    (if (and lobby (not (:started lobby)))
      ;; Lobby compact status
      (let [players (:players lobby)
            player-count (count players)
            ready? (and (= 2 player-count) (every? :deck players))]
        (println (format "Lobby: %d/2 players%s"
                        player-count
                        (if ready? " [READY]" ""))))
      ;; Game compact status
      (let [my-side (:side state)
            active-side (state/active-player state)
            turn (state/turn-number state)
            prompt (state/get-prompt state)
            run-state (get-in gs [:run])

            ;; At a clean turn boundary the active-player wire field still names
            ;; the player who just finished, so flip to who acts next (matching
            ;; game-over-status's AWAITING-START next-player). Otherwise tooling
            ;; and models reading this line mistake whose turn is starting.
            turn-status (state/get-turn-status state)
            waiting-start? (:waiting-to-start? turn-status)
            display-side (if waiting-start? (:next-player turn-status) active-side)

            ;; Runner state
            runner-credits (get-in gs [:runner :credit] 0)
            runner-clicks (get-in gs [:runner :click] 0)
            runner-hand (get-in gs [:runner :hand] [])
            ;; Hand/HQ size is PUBLIC info, but the opponent's :hand is fog-of-war
            ;; hidden in wire state (arrives empty). Read the public :hand-count
            ;; field so the Opp segment doesn't under-report the opponent's hand
            ;; as 0h; fall back to (count hand) only if the count field is absent.
            runner-hand-ct (get-in gs [:runner :hand-count] (count runner-hand))
            runner-ap (get-in gs [:runner :agenda-point] 0)
            ;; Credits hosted on rig/play-area cards (e.g. Overclock during a run)
            ;; are spendable but omitted from the pool field -- surface as (+N)
            ;; so the seat doesn't undercount affordability (issue #21).
            ;; ...and NAME the holder. A bare (+3) is a number with no owner,
            ;; and spendability lives on the card, not the count: a Runner read
            ;; Smartware Distributor's 3 hosted credits as a pool it could draw
            ;; on, planned a turn around them and ended it at 0 (#151 item 11).
            ;; Full `status` has always named the sources; the one-line view a
            ;; seat actually polls did not.
            runner-hosted-info (state/runner-hosted-credits gs)
            runner-hosted (:total runner-hosted-info)
            ;; Per-holder amounts, not just names: with two holders the seat
            ;; still has to know WHICH restriction governs which credits, and
            ;; "(+15:Smartware Distributor,Red Team)" doesn't say (guest panel).
            ;; Same shape as full `status`.
            hosted-sources (:sources runner-hosted-info)
            runner-cred-str (cond
                              (not (pos? runner-hosted)) (str runner-credits)
                              ;; One holder: the total in front of the colon is
                              ;; already its amount — repeating it is noise.
                              (= 1 (count hosted-sources))
                              (format "%d(+%d:%s)" runner-credits runner-hosted
                                      (:title (first hosted-sources)))
                              :else
                              (format "%d(+%d:%s)" runner-credits runner-hosted
                                      (str/join ","
                                                (map #(format "%s %d" (:title %) (:credits %))
                                                     hosted-sources))))
            ;; Tags decide endgames (Orbital Superiority won marquee g1 off
            ;; one) but were only visible via full `status` — the one-call
            ;; snapshot silently omitted them (#85). Append to the Runner's
            ;; stat segment whenever tagged; omit when clean to keep the
            ;; common case compact.
            runner-tags (get-in gs [:runner :tag :base] 0)
            runner-tag-str (if (pos? runner-tags)
                             (format "/%dtag" runner-tags)
                             "")
            ;; Virus counters price runs (Leech's counters decide how much ICE
            ;; strength the Runner can shave), and this line — the one a seat
            ;; polls before every decision — omitted them, so budgeting meant a
            ;; second call to `board` (#151 item 12). Named per card for the same
            ;; reason the hosted credits are: a total across two virus programs
            ;; cannot be spent against either.
            runner-virus (state/runner-virus-counters gs)
            runner-virus-str (if (seq (:sources runner-virus))
                               (format "/v:%s"
                                       (str/join ","
                                                 (map #(format "%s %d" (:title %) (:virus %))
                                                      (:sources runner-virus))))
                               "")

            ;; Corp state
            corp-credits (get-in gs [:corp :credit] 0)
            corp-clicks (get-in gs [:corp :click] 0)
            corp-hand (get-in gs [:corp :hand] [])
            corp-hand-ct (get-in gs [:corp :hand-count] (count corp-hand))
            corp-ap (get-in gs [:corp :agenda-point] 0)

            ;; Format: T3-Corp | Me(R): 4c/2cl/5h/0AP | Opp(C): 5c/0cl/4h/0AP
            runner-stats (format "%sc/%dcl/%dh/%dAP%s%s"
                                 runner-cred-str runner-clicks runner-hand-ct
                                 runner-ap runner-tag-str runner-virus-str)
            corp-stats (format "%dc/%dcl/%dh/%dAP"
                               corp-credits corp-clicks corp-hand-ct corp-ap)
            my-stats (if (= my-side "runner") runner-stats corp-stats)
            opp-stats (if (= my-side "runner") corp-stats runner-stats)
            my-label (if (= my-side "runner") "R" "C")
            opp-label (if (= my-side "runner") "C" "R")

            prompt-str (cond
                        run-state (format "Run:%s" (run-server-display (last (:server run-state))))
                        waiting-start? "awaiting-start"
                        prompt (let [msg (:msg prompt)]
                                (if (> (count msg) 30)
                                  (str (subs msg 0 27) "...")
                                  msg))
                        :else "-")]

        (println (format "T%d-%s | Me(%s):%s | Opp(%s):%s | %s"
                        turn
                        display-side
                        my-label
                        my-stats
                        opp-label
                        opp-stats
                        prompt-str))
        nil))))

(defn status-compact
  "Show ultra-compact game status (1 line)"
  []
  (show-status-compact))

(defn board-compact
  "Show ultra-compact board state (2-3 lines)"
  []
  (show-board-compact))

;; ============================================================================
;; Access Prompt Display
;; ============================================================================

(defn- access-prompt?
  "Detect if prompt is an access prompt by checking for 'steal' or 'trash' keywords"
  [prompt]
  (when-let [choices (:choices prompt)]
    (let [choice-values (map :value choices)
          choice-text (str/lower-case (str/join " " choice-values))]
      (or (str/includes? choice-text "steal")
          (str/includes? choice-text "trash")))))

(defn- extract-card-name
  "Extract card name from access prompt message
   Format: 'You accessed Regolith Mining License'"
  [msg]
  (when msg
    (let [msg-lower (str/lower-case msg)]
      (when (str/includes? msg-lower "you accessed")
        (when-let [match (re-find #"(?i)you accessed\s+(.+?)(?:\.|$)" msg)]
          (second match))))))

(defn- show-access-prompt
  "Display access prompt with enhanced card metadata"
  [prompt]
  (let [msg (:msg prompt)
        card-name (extract-card-name msg)
        _ (when card-name (core/show-card-on-first-sight! card-name))  ; Show text on first access
        card-data (when card-name (get @all-cards card-name))
        choices (:choices prompt)
        has-steal? (some #(str/includes?
                           (str/lower-case (str (:value %)))
                           "steal")
                        choices)]

    ;; Display header with card metadata
    (if card-data
      (let [card-type (or (:type card-data) "unknown")
            trash-cost (:trash card-data)
            points (:agendapoints card-data)
            metadata (cond
                       points (str "[" card-type ", points=" points "]")
                       trash-cost (str "[" card-type ", trash=" trash-cost "]")
                       :else (str "[" card-type "]"))]
        (println (str "\n❓ You accessed: " card-name " " metadata)))
      (println (str "\n❓ " msg)))

    ;; Show full card text ONLY when "steal" keyword present
    (when (and has-steal? card-data (:text card-data))
      (println "⚠️  SPECIAL STEAL CONDITION:")
      (println (str "   " (:text card-data))))

    ;; Display choices
    (when choices
      (doseq [[idx choice] (map-indexed vector choices)]
        (println (str "  [" idx "] " (:value choice)))))

    prompt))

(defn show-prompt
  "Display current prompt in readable format.
   Detects access prompts and shows enhanced card metadata."
  []
  (if-let [prompt (state/get-prompt)]
    (if (access-prompt? prompt)
      (show-access-prompt prompt)
      (do
        (println "\n🔔 PROMPT")
        (println "Message:" (:msg prompt))
        (println "Type:" (:prompt-type prompt))
        (when-let [choices (:choices prompt)]
          (println "Choices:")
          (doseq [[idx choice] (map-indexed vector choices)]
            (println (str "  " idx ". " (:value choice) " [UUID: " (:uuid choice) "]"))))
        prompt))
    (println "No active prompt")))

(defn hand
  "Show my hand"
  []
  (let [hand (state/my-hand)]
    (println "\n=== MY HAND ===" (count hand) "cards ===")
    (doseq [[idx card] (map-indexed vector hand)]
      (println (format "  %d. %s [%s]" idx (:title card) (:type card))))
    hand))

(defn- extract-icebreaker-abilities
  "Extract break/pump costs from icebreaker card text. Returns string like '1¢:break, 2¢:+1str' or nil."
  [card-title]
  (when-let [card (get @all-cards card-title)]
    (let [text (or (:text card) "")
          ;; Extract break cost: <strong>X[credit]:</strong> Break...
          break-match (re-find #"<strong>(\d+)\[credit\]:</strong>\s*Break\s+(?:up to\s+)?(\d+)?" text)
          ;; Extract pump cost: <strong>X[credit]:</strong> +Y strength
          pump-match (re-find #"<strong>(\d+)\[credit\]:</strong>\s*\+(\d+)\s+strength" text)]
      (when (or break-match pump-match)
        (let [parts (cond-> []
                      break-match (conj (str (nth break-match 1) "¢:break"
                                            (when-let [n (nth break-match 2 nil)]
                                              (str " " n))))
                      pump-match (conj (str (nth pump-match 1) "¢:+" (nth pump-match 2) "str")))]
          (when (seq parts)
            (str/join ", " parts)))))))

(defn- format-card-for-hand
  "Format a single card for hand display with type-specific info."
  [card]
  (let [card-type (:type card)
        subtypes (when-let [st (:subtypes card)]
                   (when (seq st)
                     (str ": " (clojure.string/join ", " st))))
        ;; Type-specific formatting
        cost-info (case card-type
                    ;; Agendas: show advancement requirement and points
                    "Agenda" (let [adv (:advancementcost card)
                                   pts (:agendapoints card)]
                               (str " (" adv "⬆ → " pts "pts)"))
                    ;; ICE: show rez cost (not play cost)
                    "ICE" (str " (" (:cost card) "¢)")
                    ;; Programs: show cost, MU, and strength if icebreaker
                    "Program" (let [cost (:cost card)
                                    mu (:memoryunits card 1)
                                    strength (:strength card)]
                                (str " (" cost "¢, " mu "MU"
                                     (when strength (str ", str " strength))
                                     ")"))
                    ;; Hardware: show cost
                    "Hardware" (str " (" (:cost card) "¢)")
                    ;; Resources: show cost
                    "Resource" (str " (" (:cost card) "¢)")
                    ;; Assets: show cost and trash cost
                    "Asset" (let [cost (:cost card)
                                  trash (:trash card)]
                              (str " (" cost "¢"
                                   (when trash (str ", 🗑" trash))
                                   ")"))
                    ;; Upgrades: show cost and trash cost
                    "Upgrade" (let [cost (:cost card)
                                    trash (:trash card)]
                                (str " (" cost "¢"
                                     (when trash (str ", 🗑" trash))
                                     ")"))
                    ;; Operations/Events: show cost
                    (if-let [c (:cost card)]
                      (str " (" c "¢)")
                      ""))]
    (str "[" card-type subtypes "]" cost-info)))

(defn- no-side-here!
  "One explanation for 'this client has no side, so there is nothing to show'.

   #125: these surfaces used to throw a raw NPE here. The replacement has to be
   TRUE, which means not collapsing three different states into one sentence —
   `:side` is nil in all of them and they need opposite advice:

     spectator     `watch-game!` sets :gameid/:spectator and never sets :side,
                   and `detect-side` cannot match a spectator's uid, so a client
                   happily watching a live game has a full board and no side.
                   Telling it 'not in a game → reset.sh' would be a lie that
                   destroys the game it is watching (guest-panel catch).
     seatless      a board arrived but our uid matched neither username (e.g. a
                   resync where the server stripped user info). In a game, no
                   seat — retryable, NOT a teardown.
     no game       a REPL that never joined, or what `leave-lobby!` leaves
                   behind (it nils :gameid/:side, and a finished game's
                   teardown — save-replay.sh, concede — goes through it).

   Second-pass panel: branch on EVIDENCE, not on one flag. Two states defeat the
   obvious cond —

     - `leave-lobby!` nils :gameid/:side and dissocs :spectator but leaves
       :game-state ALONE, so the ordinary post-teardown client (the state #125
       was actually captured in) still holds a board. Keying 'seatless' on
       :game-state alone sent it to `resync` after it had deliberately left.
       :gameid is the discriminator: a client still in a game has one.
     - `watch-game!` sets :spectator immediately after sending the request,
       before any confirmation, so :spectator alone can mean a watch that was
       rejected or has not landed yet. Require the board before promising one."
  [state what]
  ;; `map?`, not truthiness (guest-panel CRITICAL): `apply-diff` returns the RAW
  ;; diff when :last-state is nil, so a :game/diff landing between
  ;; `clear-game-state!` and the replacement full state leaves :game-state
  ;; holding an `[alterations removals]` VECTOR. That is truthy and is not a
  ;; board — reading it yields the same `Credits: nil` this guard exists to stop.
  (let [board? (map? (:game-state state))
        seated? (boolean (:gameid state))
        ;; Mirrors `boardless-started-game?`: :lobby-state is dissoc'd once a full
        ;; game state arrives, so a started game has none; an unstarted lobby
        ;; still carries its own with :started false.
        lobby (:lobby-state state)
        started? (or (nil? lobby) (boolean (:started lobby)))]
    (cond
      (and (:spectator state) board?)
      (do
        (println (format "👁️  Spectating, not seated — %s is a seat-only view." what))
        (println (format "   Perspective: %s. Spectators have no side of their own."
                         (or (:spectator-perspective state) "neutral")))
        (println "   → 'board' / 'log' show the game you are watching. To play, join a seat.")
        nil)

      (:spectator state)
      (do
        (println (format "👁️  Watch requested, but no board has arrived — %s is empty." what))
        (println "   The watch may still be in flight, or the server refused it (bad game id / password).")
        (println "   → 'list-lobbies' to confirm the game exists, then watch it again.")
        nil)

      ;; #139: the complement of every branch above — the side may be perfectly
      ;; well known, and the BOARD is what is gone. This is what a failed resync
      ;; leaves behind (`resync-game!` clears the cache before requesting a
      ;; replacement, and :gameid survives), the state `boardless-started-game?`
      ;; classifies and `sync-verdict!` calls :resync-failed. The read surfaces
      ;; used to read a nil board and
      ;; print what they found — "Credits: nil", "Archives: 0 cards", a rendered
      ;; empty table. For the nil case the action commands are already refused; for
      ;; the raw-diff-VECTOR case they are NOT — `has-game-state?` and the
      ;; start-turn guards test `some?`/`nil?`, so that state reads as synced to
      ;; them (second-pass panel CRITICAL, filed separately). This guard is
      ;; therefore the only thing standing between that state and a confident
      ;; answer, not a second line of defence.
      ;; It must come BEFORE the :else arm, which tells a client that
      ;; is still holding a :gameid it "never joined" and sends it to reset.sh —
      ;; destroying a game a retry might have recovered.
      ;; Seated in a lobby that has not STARTED. Boardless here is correct and
      ;; healthy — `sync-verdict!` deliberately calls it :synced so reset.sh's
      ;; create → join → start path is not gated. Telling this seat its cache was
      ;; cleared would send it resyncing away from a perfectly good lobby
      ;; (guest-panel CRITICAL).
      (and seated? (not board?) (not started?))
      (do
        (println (format "⏳ Seated, but the game has NOT STARTED yet — %s does not exist." what))
        ;; Same words the acting side already uses for this state
        ;; (ai-basic-actions/print-no-board-cause!) — one state, one story, and
        ;; `start-game` is the verb the CLI actually parses.
        (println "   You are seated in a lobby that has not begun.")
        (println "   → 'status' shows what the lobby is waiting on; 'start-game' once both seats are ready.")
        nil)

      (and seated? (not board?))
      (do
        (println (format "⚠️  Seated, but this client holds NO BOARD — %s is unknown, not empty." what))
        ;; Two states share this signature (guest panel): the first full state
        ;; of a game that has JUST started is still arriving (the server sends
        ;; the started :lobby/state before :game/start), or a resync cleared the
        ;; cache and the replacement has not landed. Both want the same move.
        (println "   Either the game just started and its first full state is still arriving,")
        (println "   or a resync cleared the cache and the replacement state has not arrived.")
        ;; `resync` takes the game id (send_command: "Usage: resync <game-id>");
        ;; the bare verb fails with usage help. We hold the id — print it.
        (println (format "   → Retry the command; if it keeps failing: 'status', then 'resync %s'." (:gameid state)))
        nil)

      (and board? seated?)
      (do
        (println (format "⚠️  In a game, but no seat identified — %s needs a side." what))
        (println "   The board is here; our uid matched neither player (stripped user info?).")
        (println "   → 'resync' to re-request the full state; 'status' still works.")
        nil)

      :else
      (do
        (println (format "⚠️  Not in a game — no side on this client, so %s is empty." what))
        (println "   Never joined, or the lobby was left/torn down (a finished game does this too).")
        (when board?
          (println "   ⚠️  A board is still cached from the game you left — it is STALE, not live."))
        (println "   → 'list-lobbies' then 'join <game-id> <side>', or ./dev/reset.sh for a fresh game.")
        nil))))

(defn show-hand
  "Show hand using side-aware state access. Returns hand vector.
   1-arity: render from an already-captured state (snapshot, #139)."
  ([] (show-hand @state/client-state))
  ([state]
  (let [side (:side state)]
    ;; #139: a side is not enough — the board is the thing being read. A seat
    ;; whose cache was cleared has a side and nothing to show it for.
    (if-not (and side (map? (:game-state state)))
      ;; Was its own bespoke "No game state - not in a game yet". That is the
      ;; same false claim the rest of #125 removes — `hand` is a CLI surface and
      ;; a spectator hits it with a full board — so it shares the one explainer.
      (no-side-here! state "the hand")
      (let [hand (get-in state [:game-state (keyword (clojure.string/lower-case side)) :hand])]
        (when hand
          (println (str "🃏 " (clojure.string/capitalize side) " Hand:"))
          (doseq [[idx card] (map-indexed vector hand)]
            (let [card-name (core/format-card-name-with-index card hand)
                  formatted (format-card-for-hand card)
                  ;; For icebreakers, show ability costs
                  is-icebreaker? (some #{"Icebreaker"} (:subtypes card))
                  ability-info (when is-icebreaker?
                                (extract-icebreaker-abilities (:title card)))]
              (println (str "  " idx ". " card-name " " formatted))
              (when ability-info
                (println (str "     → " ability-info)))
              ;; Show card text for first-seen cards
              (core/show-card-on-first-sight! (:title card)))))
        hand)))))

(defn show-credits
  "Show current credits (side-aware). Returns credits value."
  []
  (let [state @state/client-state
        side-kw (state/my-side-kw state)]
    (if-not (and side-kw (map? (:game-state state)))
      (no-side-here! state "the credit pool")
      (let [credits (get-in state [:game-state side-kw :credit])]
        (println "💰 Credits:" credits)
        credits))))

(defn show-clicks
  "Show remaining clicks (side-aware). Returns clicks value."
  []
  (let [state @state/client-state
        side-kw (state/my-side-kw state)]
    (if-not (and side-kw (map? (:game-state state)))
      (no-side-here! state "the click count")
      (let [clicks (get-in state [:game-state side-kw :click])]
        (println "⏱️  Clicks:" clicks)
        clicks))))

(declare show-archives* show-heap*)

(defn show-archives
  "Show Corp's Archives (discard pile) with faceup/facedown counts.
   #139: board-gated — '0 cards' is an assertion a Runner prices a run on,
   not 'unknown'."
  []
  (let [state @state/client-state]
    (if-not (map? (:game-state state))
      (no-side-here! state "Archives")
      (show-archives* state))))

(defn- show-archives*
  [state]
  (let [archives (get-in state [:game-state :corp :discard])
        faceup (filter :seen archives)
        facedown-count (- (count archives) (count faceup))]
    (println "\n📂 Archives:")
    (println (str "  Faceup: " (count faceup) " | Facedown: " facedown-count))
    (when (seq faceup)
      (println "\n  Revealed Cards:")
      (doseq [card faceup]
        (let [cost-str (if-let [cost (:cost card)] (str cost "¢") "")
              type-str (:type card)
              subtitle (if (not-empty cost-str)
                        (str type-str ", " cost-str)
                        type-str)]
          (println (str "    • " (:title card) " (" subtitle ")")))))
    (when (> facedown-count 0)
      (println (str "\n  " facedown-count " card(s) facedown (hidden)")))))

(defn show-heap
  "Show Runner's Heap (discard pile). #139: board-gated like show-archives."
  []
  (let [state @state/client-state]
    (if-not (map? (:game-state state))
      (no-side-here! state "the Heap")
      (show-heap* state))))

(defn- show-heap*
  [state]
  (let [heap (get-in state [:game-state :runner :discard])]
    (println "\n🗑️  Heap:")
    (println (str "  Total: " (count heap) " cards"))
    (when (seq heap)
      (println "")
      (doseq [card heap]
        (let [cost-str (if-let [cost (:cost card)] (str cost "¢") "")
              type-str (:type card)
              subtype-str (when-let [st (:subtype card)] (str " - " st))
              subtitle (str type-str subtype-str (when (not-empty cost-str) (str ", " cost-str)))]
          (println (str "    • " (:title card) " (" subtitle ")")))))))

(defn- show-encounter-ice-info
  "Display ICE encounter info: current ICE and playable icebreakers"
  [state run my-side]
  ;; Same authority as the priority block below it: naming the position-derived
  ;; ICE here while the guidance keys off [:encounters :ice] would split ONE
  ;; output across two cards in a forced encounter (guest panel).
  (when-let [current-ice (encountered-ice state)]
    (when (:rezzed current-ice)
      (let [ice-title (:title current-ice)
            ice-str (or (:current-strength current-ice) (:strength current-ice))
            ice-subtypes (clojure.string/join " " (or (:subtypes current-ice) []))
            subs (:subroutines current-ice)]
        (println (format "  🧊 ICE: %s (str %s, %s)" ice-title ice-str ice-subtypes))
        (println (format "     Subroutines: %s" (sub-count-summary subs)))
        ;; Show playable icebreakers for Runner
        (when (= my-side "runner")
          (let [programs (get-in state [:game-state :runner :rig :program])
                ;; Check :subtypes vector for "Icebreaker"
                breakers (filter #(some (fn [st] (= (str st) "Icebreaker"))
                                        (or (:subtypes %) []))
                                 programs)
                playable-breakers (filter #(some :playable (:abilities %)) breakers)]
            (when (seq playable-breakers)
              (println "  💪 Icebreakers with playable abilities:")
              (doseq [b playable-breakers]
                ;; keep-indexed, NOT filter: `use-ability` takes the index into
                ;; the card's FULL :abilities vector, so the position within a
                ;; filtered playable-only list is the wrong number — printing it
                ;; would be worse than printing nothing. Emitting the whole
                ;; invocation is the point: the command log had abilities ->
                ;; use-ability at P=0.59 (n=68), a round-trip that existed only
                ;; to look this index up.
                (let [playable-abs (keep-indexed (fn [i ab] (when (:playable ab) [i ab]))
                                                 (:abilities b))]
                  (println (format "     • %s (str %s)" (:title b) (or (:current-strength b) (:strength b))))
                  (doseq [[idx ab] playable-abs]
                    (println (format "       → %s" (:label ab)))
                    (println (format "         use-ability \"%s\" %d" (:title b) idx))))))))
          ;; Runner-usable abilities printed on the encountered ICE itself
          ;; (bioroid click-to-break, issue #95). Source: current-run-ice, the
          ;; authoritative run-state card — NOT prompt-state, which is
          ;; stale-prone and missed this in marquee 6d8f4cf8.
          (let [runner-abs (:runner-abilities current-ice)]
            (when (seq runner-abs)
              (println "  🦾 Runner-usable abilities on this ICE (cost is yours to pay):")
              (doseq [[idx ab] (map-indexed vector runner-abs)]
                (println (format "     → %s" (:label ab)))
                (println (format "       use-runner-ability \"%s\" %d" ice-title idx)))))))))

(defn run-server-display
  "Human-readable name for a run's target server, given the last element of the
   run's :server vector (e.g. \"rd\", \"hq\", \"archives\", \"remote1\").
   Falls back to a generic phrase if the key is unrecognised/nil."
  [server-key]
  (let [k (when server-key (clojure.string/lower-case (str server-key)))]
    (cond
      (= k "rd") "R&D"
      (= k "hq") "HQ"
      (= k "archives") "Archives"
      (and k (re-matches #"remote\d+" k)) (str "Server " (subs k 6))
      (seq k) (str "the " k " server")
      :else "the server")))

(defn both-pass-window?
  "True when `run-phase` is a window `my-side` advances by PASSING PRIORITY, and
   whose pass the engine records in [:run :no-action] — i.e. one that must route
   through run-priority-hint-lines so an already-passed seat is not told to
   `continue` at a window it cannot advance.

   The Runner owns a sub-step at initiation, approach-ice and movement/
   approach-server; the Corp's initiation and approach-ice sub-steps carry a rez /
   paid-ability decision, so those two keep their own richer steer and are not
   listed here. encounter-ice is deliberately absent: its passer lives on
   [:encounters :no-action], and the Runner's decision there is break/tank, not a
   pass (#92).

   Single source (#115): this set was inlined in BOTH print-run-window-priority!
   and diagnose-blocker, so adding approach-ice to one left the other — the
   surface a stuck seat actually reaches for — printing the same steer that
   provably cannot act. Same N-emitters shape as #75/#77/#113."
  [run-phase my-side]
  (contains? (if (= my-side "runner")
               #{"initiation" "approach-ice" "movement" "approach-server"}
               #{"movement" "approach-server"})
             run-phase))

(defn run-priority-hint-lines
  "Side-aware hint lines for a run priority window (movement / approach-server).

   A run only advances when BOTH players pass priority (`continue`); the engine
   records the first side to pass in the run's `:no-action`. The old rendering
   printed an identical symmetric line to both seats ('Use continue to pass
   priority'), so each side read it as 'I'm waiting on the other' and the run
   deadlocked — and the Runner never learned that continuing here is what gets it
   its access. This disambiguates whose move it is now and what `continue` does.

   `run` is the [:game-state :run] map; `my-side` is \"runner\"/\"corp\".
   Returns a vector of lines to println."
  [run my-side]
  (let [position  (:position run)
        past-ice? (or (nil? position) (zero? position))
        server    (run-server-display (last (:server run)))
        na        (let [v (:no-action run)]
                    (cond (keyword? v) (name v) (string? v) v :else nil))
        opp       (if (= my-side "runner") "Corp" "Runner")
        ;; The run timing splits each priority window into two sub-steps: the
        ;; ACTIVE player passes first, then the opponent (forum [118]). During a
        ;; run the active player is ALWAYS the Runner — runs only happen on the
        ;; Runner's turn (rule invariant, memory run-priority-active-player-first),
        ;; so we encode that directly rather than trusting the volatile
        ;; :active-player wire field. Render: Runner = first sub-step, Corp = second.
        i-am-active? (= my-side "runner")
        ;; What the Runner specifically gains by continuing this window. Phase
        ;; matters: at approach-ice the Runner is already AT the ICE, so passing
        ;; resolves THIS ICE (encounter it, or walk past it if the Corp declined
        ;; the rez) — "approach the next ICE" is only true of the windows BEFORE
        ;; an approach (initiation, and movement with ICE still to come).
        gain      (when (= my-side "runner")
                    (cond
                      past-ice? (str "breach " server " and access cards")
                      (= "approach-ice" (:phase run))
                      "resolve this ICE (encounter it if the Corp rezzes, otherwise walk past it)"
                      :else (str "approach the next ICE on " server)))]
    (cond
      ;; I have already passed — waiting on the opponent; no action from me.
      (= na my-side)
      (into
        [(str "    ⏸️  You have already passed priority here — waiting for " opp
              " to pass before the run advances.")
         ;; "Re-sending does nothing" is true of a MANUAL repeat — send-continue!'s
         ;; #98 guard suppresses it — but it is NOT true of the Runner's run loop.
         ;; handle-stalled-window-self-advance (ai-runs) deliberately sends a
         ;; SECOND pass once a decision-free window has gone unanswered for
         ;; self-advance-grace-ms, because the engine's advance branch has no
         ;; side-check (game.core.runs `continue`), and that is the sanctioned #31
         ;; recovery. Telling the Runner a repeat is futile therefore steered it
         ;; past its own recovery and into an umpire ping — the exact escalation
         ;; this block exists to prevent. Guest panel caught it (#115); the Corp
         ;; keeps the flat line because self-advance is Runner-side only.
         (if (= my-side "runner")
           (str "      (Nothing to send right now; use 'wait'. A repeat 'continue' is suppressed "
                "while the window is live — but if the Corp has no decision to make here, "
                "re-issuing 'continue' after ~5s lets the run loop advance the abandoned "
                "window itself (#31). Try that BEFORE escalating.)")
           (str "      (No action needed from you; use 'wait'. Re-sending 'continue' does nothing.)"))]
        ;; Stall recovery (issue #31). We used to tell the Runner that 'jack-out
        ;; ends the run to recover' here. That advice LOST marquee d6962df4:
        ;; GPT-5.5 followed it 5 times, once abandoning a Brân it had just broken
        ;; for 8 credits on the run that would have contested the winning agenda.
        ;;
        ;; Jack-out is a NETRUNNER SMELL (Michael): the only tactically legitimate
        ;; reasons are (1) you misjudged what it costs to get in, and (2) Karuna's
        ;; jack-out subroutine (bail before the 4th net damage kills you). A window
        ;; the opponent hasn't answered is NEITHER — it is the opposing seat not
        ;; being at its post, and throwing away the run does not fix that. Patience
        ;; is the correct play; the peer-liveness signal (#63) tells you whether
        ;; waiting is sane.
        ;;
        ;; The escalation path (issue #20) is what was MISSING when this advice
        ;; was first written: telling a seat "keep waiting" answers 'is the
        ;; opponent alive?' but not 'we look wedged, who adjudicates?'. With no
        ;; sanctioned recovery, seats invented one — replay 0b52266c has the
        ;; Runner pinging the opponent in game chat twice and then jacking out of
        ;; a run it had already paid to get into. The umpire channel landed a week
        ;; after that game; name it here, at the exact point a seat gets stuck.
        (concat
          (when (= my-side "runner")
            [(str "      Waiting is CORRECT here — " opp " owes a decision. Check `peer-status`: "
                  "alive ⇒ keep waiting.")
             (str "      Do NOT 'jack-out' to unstick a window — it throws the run away. "
                  "Jack-out is a smell: the only real reasons are a misjudged entry cost "
                  "or a Karuna jack-out sub.")])
          (when (= my-side "corp")
            [(str "      Waiting is CORRECT here — " opp " owes a decision. Check `peer-status`: "
                  "alive ⇒ keep waiting.")])
          ;; Both seats stall the same way, so both get the same judge button.
          [(str "      If " opp " never answers, ESCALATE rather than inventing a recovery: "
                "`./dev/umpire-ping " my-side " \"passed this window, opponent hasn't, am I wedged?\"`")
           (str "      (Harness state only — never your hand or your plan; the mailbox is opponent-readable.)")]))

      ;; Opponent already passed — my continue advances the run now.
      (and na (not= na my-side))
      [(str "    → It's YOUR move: " opp " has already passed priority. Use 'continue' to "
            (or gain "advance the run") ".")]

      ;; Fresh window — nobody has passed yet. Render the two sub-steps explicitly
      ;; so each seat knows whether it's the first passer (active player) or the
      ;; second (forum [118]/[120]).
      i-am-active?
      ;; Active player (Runner): your sub-step is FIRST.
      [(str "    → YOUR sub-step (active player goes first): use 'continue' to pass priority"
            (when gain (str " (this is what gets you your access: " gain ")")) ".")
       (str "      (Both players pass to advance — " opp " gets its sub-step AFTER you pass.)")]

      ;; Opponent of the active player (Corp): your sub-step is SECOND. You do NOT
      ;; act in the Runner's sub-step — if you want to rez / fire a paid ability,
      ;; you do it in your own sub-step, AFTER the Runner has passed (forum [120]).
      :else
      [(str "    ⏸️  " opp " (active player) has priority first here and hasn't passed yet.")
       (str "      → Your sub-step comes next: wait for the " opp " to 'continue', then you 'continue' to advance the run.")])))

(defn effective-window-passer
  "Normalized side (\"runner\"/\"corp\"/nil) that has passed the CURRENT run
   priority window. During encounter-ice the passer lives on the current
   encounter ([:encounters :no-action]) — the engine resets the run-level
   :no-action on movement entry, so [:run :no-action] is stale there (engine
   runs.clj `continue :encounter-ice`); every other window uses [:run
   :no-action]. Mirrors the client's runner-passed-encounter? (ai-core). `gs` is
   the [:game-state] map."
  [gs]
  (let [run (:run gs)
        ;; Keyed on a LIVE encounter, not on the phase string: a forced encounter
        ;; runs while :phase reads something else, and reading the run-level
        ;; :no-action there made the headline ("your move") contradict the
        ;; encounter guidance printed directly beneath it (guest re-review).
        v   (if (or (= "encounter-ice" (:phase run))
                    (some? (get-in gs [:encounters :ice])))
              (get-in gs [:encounters :no-action])
              (:no-action run))]
    (cond (keyword? v) (name v) (string? v) v :else nil)))

(defn run-status-headline
  "One-line 'whose move is it now' summary for the active run's current priority
   window, derived from public run state only: the current-window passer (see
   effective-window-passer) plus the rule invariant that the Runner is always the
   active player during a run (memory run-priority-active-player-first). Pure.
   `gs` is the [:game-state] map; `my-side` is \"runner\"/\"corp\". Returns the
   string for the top-level `Status:` line during a run.

   Why this exists: the turn-level active-side ('it's the Runner's turn')
   misleads inside a run — the Corp still owns its rez / upgrade sub-steps — so a
   Corp seat running `status` at its own rez window used to read 'Waiting for
   runner to act'. Grounded in the SAME na/active logic as run-priority-hint-lines
   so the headline never contradicts the detailed run-window guidance below it."
  [gs my-side]
  (let [na  (effective-window-passer gs)
        opp (if (= my-side "runner") "Corp" "Runner")]
    (cond
      ;; I have already passed this window — waiting on the opponent to pass.
      (= na my-side)
      (str "⏳ Waiting on " opp " — you've passed this run window ('wait').")

      ;; Opponent already passed — my move advances / decides the window.
      (and na (not= na my-side))
      (str "✅ Your move — " opp " has passed; act on the run window below.")

      ;; Fresh window, I'm the Runner (active player acts first).
      (= my-side "runner")
      "✅ Your move (active player) — act on the run window below."

      ;; Fresh window, I'm the Corp: my sub-step is second, Runner acts first.
      :else
      "⏳ Waiting on Runner — active player acts first; your sub-step is next.")))

(def encountered-ice
  "The ICE the Runner is actually encountering — see ai-core/encountered-ice.
   Re-exported here because these display lines NAME the ICE and key the tank
   check on that name: get the identity wrong and the guidance is confidently
   about the wrong card. It moved to ai-core when the run HANDLERS needed the
   same authority (#160) — one definition, not a display copy and a handler
   copy."
  core/encountered-ice)

(def live-encounter?
  "True when the wire reports an encounter in progress, whatever the run phase
   says — see ai-core/live-encounter?. Gating the break/tank guidance on the
   phase string alone left a forced encounter (live while [:run :phase] reads
   \"success\") being told \"use continue to pass priority\", the #92 lie in the
   one phase nobody had checked (guest panel)."
  core/live-encounter?)

(defn runner-encounter-unbroken-count
  "Count of subroutines on the current encounter ICE that the Runner has neither
   broken nor already fired — i.e. the subs still pending a break/tank decision.
   Mirrors handle-runner-encounter-ice's own filter (ai-run-runner-handlers), the
   authority on whether `continue` will pass or be refused here. `state` is the
   full client-state map. Returns 0 when there is no rezzed current ICE."
  [state]
  (let [ice (encountered-ice state)]
    (if (core/encounter-ice-active? state ice)
      (count (filter #(and (not (:broken %)) (not (:fired %)))
                     (:subroutines ice)))
      0)))

(defn runner-encounter-decline-hint-lines
  "Guidance for a Runner at an encounter it owns with unbroken subroutines it is
   NOT breaking. `continue` is REFUSED here — handle-runner-encounter-ice returns
   a fire-decision, never a pass — so the generic run-window steer ('use continue
   to pass priority') is a lie that deadlocked marquee G2 for ~20 min (#92). The
   Runner's real options are exactly two: break with an icebreaker, or `tank` to
   decline and let the subs fire. Pure; returns a vector of lines to println.
   `ice-title` is the encounter ICE; `unbroken-count` is the pending-sub count.

   This menu used to list `jack-out` as a third option. It is not one: the human
   client enables Jack Out only in a movement window (board.cljs gates on
   phase == \"movement\"), and leaving mid-encounter would skip the unbroken
   subroutines outright. Offering it here taught seats to do exactly that — 11 of
   the 28 jack-outs across the archived replays fired at an encounter. Say why it
   is absent, so a seat doesn't go hunting for the option it half-remembers.
   Once the Runner HAS tanked this ICE (`tank \"X\"`, `--tank`, `--tank-all`),
   the decision is made and re-printing the menu is a lie in the other
   direction: `tank` prints \"✅ Authorized … 📡 Signaling Corp\" and the
   auto-prompt echo used to re-ask \"you must decide: break OR tank\" directly
   underneath, which reads as the tank having failed (#151 item 2). Pass
   `tank-authorized?` true for the confirmation form: the tank stands, the Corp
   owes the subs, and the command that advances from here is `wait`.

   `corp-declined?` is the THIRD form, and it is the one that makes the flat
   \"continue will NOT pass this window\" a lie if left out. Once the Corp is
   recorded as this encounter's passer, game.core.runs `continue :encounter-ice`
   ENDS the encounter on our continue and the unbroken subs never resolve — a
   free pass, verified against the engine in
   game.ai-forced-encounter-wire-test. The #92 rule holds only while the window
   is still open on both sides (guest panel CRITICAL, #160)."
  ([ice-title unbroken-count]
   (runner-encounter-decline-hint-lines ice-title unbroken-count false false))
  ([ice-title unbroken-count tank-authorized?]
   (runner-encounter-decline-hint-lines ice-title unbroken-count tank-authorized? false))
  ([ice-title unbroken-count tank-authorized? corp-declined?]
   (cond
     corp-declined?
     [(format "    → The Corp has PASSED this encounter — it is not going to fire the %d remaining subroutine%s on %s."
              unbroken-count (if (= unbroken-count 1) "" "s") ice-title)
      "      • `continue` — ends the encounter here; the unresolved subs never fire. This is a free pass."
      (format "      • Or break %s first if a break itself earns you something." ice-title)
      "      (This is the one encounter state where `continue` DOES advance you.)"]

     tank-authorized?
     [(format "    → Tank stands on %s — you have declined to break; the Corp now owes you the %d unbroken subroutine%s."
              ice-title unbroken-count (if (= unbroken-count 1) "" "s"))
      "      • `wait` — the subs fire on the Corp's action, then the run advances."
      (format "      • Changed your mind? Break %s with an icebreaker before the Corp fires." ice-title)
      "      (Nothing is owed by you at this window: re-sending `continue` or `tank` will not move it.)"]

     :else
     [(format "    → %d unbroken subroutine%s on %s — `continue` will NOT pass this window; you must decide:"
              unbroken-count (if (= unbroken-count 1) "" "s") ice-title)
      "      • break it with an icebreaker (see 'Icebreakers with playable abilities' above), OR"
      (format "      • tank \"%s\"  — decline to break: let the subs fire, then the run advances." ice-title)
      "      (You cannot jack out during an encounter — that is a movement-window action. If the"
      "       entry cost was misjudged, `tank` through and jack out at the next movement window.)"])))

(defn forced-encounter-advisory-lines
  "Guidance for a Runner at a FORCED encounter — one the wire reports live
   ([:encounters :ice]) while [:run :phase] says something else, e.g. an
   on-access Archangel during \"success\".

   This is the ordinary break/tank menu with the odd phase NAMED, because both
   options really do work here now. It used to say `tank` could not help, which
   was true when it was written: the handler that turns the tank flag into the
   Corp-facing signal, and the Corp's auto-fire that answers it, both gated on
   run-phase == \"encounter-ice\". #160 moved every one of those gates onto
   core/at-encounter?, mirroring the engine's own `continue` dispatch (which
   tests the encounter BEFORE the phase). Advertising a no-op would be the same
   bug class as the \"use continue to pass priority\" steer this branch replaced —
   so if those gates are ever narrowed back to the phase string, this text is
   wrong again and must move with them.

   The phase line stays: a seat that reads \"success\" in the ladder and a live
   encounter here needs to be told those are the same window, not two.

   Pure; returns lines to println."
  [ice-title unbroken-count phase]
  [(format "    → FORCED ENCOUNTER: %s is live with %d unbroken subroutine%s, outside the normal encounter window (phase: %s)."
           ice-title unbroken-count (if (= unbroken-count 1) "" "s") (or phase "?"))
   "      (The run phase above is not the whole truth here — the encounter outranks it, for you and for the engine.)"
   "      • Break it with an icebreaker — `abilities \"<breaker>\"` then `use-ability`; that path does not depend on the phase."
   (format "      • tank \"%s\"  — decline to break: let the subs fire, then the encounter resolves." ice-title)
   "      • `continue` does not pass while the Corp still owns its half of this window — break or tank first."
   "        (If the Corp passes without firing, `continue` then ENDS the encounter; `status` will say so.)"])

(defn print-run-window-priority!
  "Print the 'whose move is it now + what continue does' guidance for the current
   run window. Shared by the `prompt` and `status` commands so both surface the
   same run-priority read (status previously printed only the timing ladder,
   leaving the seat to guess whose window it was). Assumes the caller already
   printed the phase ladder. `state` is the full client-state (needed to read the
   encounter ICE's subs); `run` is [:game-state :run]; `run-phase` is
   (:phase run); `my-side` is \"runner\"/\"corp\". Returns nil.

   The both-must-pass windows (initiation and approach-ice for the Runner,
   movement/approach-server for both) route through run-priority-hint-lines, which
   disambiguates the two sub-steps and warns about the #31 stall. A Runner
   mid-encounter with UNBROKEN subs it is not breaking routes through
   runner-encounter-decline-hint-lines: `continue` is refused there, so steering to
   it is the #92 lie. Other windows get the terse decline/continue guidance —
   spelled out for the Corp (continue here DECLINES an action, e.g. an ICE rez,
   rather than being forced).

   approach-ice is a both-must-pass window for the Runner too (#115). The engine's
   `continue :approach-ice` records the first passer in [:run :no-action] exactly
   as movement does (game.core.runs), so a Runner that has already passed cannot
   advance it — yet this printed the terse 'use continue to pass priority' at a
   seat that provably could not act, with none of the already-passed / do-not-
   jack-out / escalate guidance the identical situation gets at #1 Run begins. Two
   Luna seats re-sent continue and then pinged the umpire."
  [state run run-phase my-side]
  (let [runner-unbroken (when (and (= my-side "runner")
                                   (or (= run-phase "encounter-ice")
                                       (live-encounter? state)))
                          (runner-encounter-unbroken-count state))
        ;; A Corp that has already passed this window is in the SAME position the
        ;; Runner's already-passed branch describes: `continue` is a no-op and the
        ;; opponent owes the move. The Corp's rez guidance below is correct only
        ;; while it still owns the window.
        ;;
        ;; Scoped to the run-level windows that reach the Corp branch at all:
        ;; movement/approach-server already route through the hint lines above,
        ;; and at encounter-ice the passer lives on [:encounters :no-action] while
        ;; run-priority-hint-lines reads [:run :no-action] — routing there would
        ;; hand it a stale nil and print fresh-window text. Same source as the fn
        ;; we delegate to, or not at all.
        corp-already-passed? (and (= my-side "corp")
                                  (contains? #{"initiation" "approach-ice"} run-phase)
                                  (= my-side (let [v (:no-action run)]
                                               (cond (keyword? v) (name v)
                                                     (string? v) v
                                                     :else nil))))]
    (cond
      (both-pass-window? run-phase my-side)
      (doseq [line (run-priority-hint-lines run my-side)]
        (println line))

      ;; Runner at an encounter with subs still to break/tank: `continue` is
      ;; refused (#92). Name the real options instead of the impossible pass.
      (and runner-unbroken (pos? runner-unbroken))
      (let [ice-title (:title (encountered-ice state) "this ICE")
            corp-declined? (core/opponent-passed-encounter? state my-side)]
        (if (or (= run-phase "encounter-ice") corp-declined?)
          (doseq [line (runner-encounter-decline-hint-lines
                        ice-title runner-unbroken
                        (state/tank-authorized? ice-title)
                        corp-declined?)]
            (println line))
          ;; A FORCED encounter outside the standard window. Same two options
          ;; (#160 put the handlers on core/at-encounter?, so `tank` is no longer
          ;; a no-op here), but the phase is named — the ladder above says
          ;; "success" and the seat has to be told that is still this encounter.
          (doseq [line (forced-encounter-advisory-lines ice-title runner-unbroken run-phase)]
            (println line))))

      ;; Corp at approach-ice having already declined/passed: don't offer it a rez
      ;; it can no longer take, and don't call a no-op a priority pass (#115).
      corp-already-passed?
      (doseq [line (run-priority-hint-lines run my-side)]
        (println line))

      (= my-side "corp")
      (do
        (println "    → 'continue' passes priority here (you DECLINE to act this window).")
        (println "    → Other options: rez a card / fire a paid ability if useful.")
        (when (= run-phase "approach-ice")
          (println "    → This is the ICE rez window: continue --rez <ice> to rez, or --no-rez to decline.")))

      :else
      ;; Runner with all subs broken (or no rezzed ICE): `continue` DOES pass.
      (println "    → Use 'continue' to pass priority (advance the run).")))
  nil)

(def ^:private run-ladder-rungs
  "Conceptual run-timing ladder (jinteki run structure), in order. Steps #2–#4
   repeat per piece of ICE; the live :phase + :position pick the current rung."
  [:begin :approach :encounter :movement :approach-server :access])

(defn run-phase-ladder-lines
  "Render the run's timing ladder with a YOU-ARE-HERE marker on the live rung, so
   the seat sees the whole run arc at a glance without consulting the rules
   (forum [099]).

   Pure: the caller extracts the live facts and passes them in.
     :phase       engine phase string — \"initiation\" | \"approach-ice\" |
                  \"encounter-ice\" | \"movement\" | \"approach-server\" | \"success\"
     :server-name display name of the attacked server (e.g. \"R&D\")
     :position    ICE countdown — N = at ICE index N-1; 0/nil = past all ICE
     :ice-count   number of ICE protecting the server (nil if unknown)
     :ice-name    title of the current ICE (nil if unknown/unrezzed — never invent
                  a name the Runner can't legally see)

   Returns a vector of lines (header + 6 rungs), or nil when :phase is absent or
   unrecognised (the caller then falls back to the bare phase line)."
  [{:keys [phase server-name position ice-count ice-name]}]
  (let [cur (case phase
              "initiation"      :begin
              "approach-ice"    :approach
              "encounter-ice"   :encounter
              ;; movement before the innermost ICE is passed is still :movement;
              ;; once past all ICE (position 0/nil) the Runner is approaching the
              ;; server. The wire phase stays "movement" across both, so split on
              ;; position to keep the marker honest.
              "movement"        (if (and position (pos? position)) :movement :approach-server)
              "approach-server" :approach-server
              "success"         :access
              nil)]
    (when cur
      (let [srv      (or server-name "the server")
            ;; Pass order: the Runner meets the OUTERMOST ICE (position = ice-count)
            ;; first, so pass-index counts up as position counts down. Shared with
            ;; the approach handler's ICE line (core/ice-pass-index) so the two
            ;; can't drift into contradicting conventions on adjacent lines (#115).
            pass-idx (core/ice-pass-index position ice-count)
            ice-of   (if (and pass-idx ice-count) (format " %d of %d" pass-idx ice-count) "")
            ice-tag  (if ice-name (format " [%s]" ice-name) "")
            label    {:begin           "#1 Run begins"
                      :approach        (str "#2 Approach ICE" ice-of ice-tag)
                      :encounter       (str "#3 Encounter ICE" ice-of ice-tag)
                      :movement        "#4 Movement — pass ICE, then approach the next"
                      :approach-server (str "#5 Approach server (" srv ")")
                      :access          (str "#6 Access " srv)}
            mark     (fn [rung]
                       (if (= rung cur)
                         (str "   ▶ " (label rung) "   ← YOU ARE HERE")
                         (str "     " (label rung))))]
        (into ["  📍 Run timing (steps #2–#4 repeat per ICE):"]
              (map mark run-ladder-rungs))))))

(defn print-run-phase-ladder!
  "Derive run-phase-ladder facts from live client `state` and print the ladder.
   Returns true if a ladder was printed, nil/false otherwise (caller may fall
   back to a bare phase line)."
  [state run my-side]
  (let [current-ice (core/current-run-ice state)
        server-key  (last (:server run))
        ice-count   (count (get-in state [:game-state :corp :servers
                                          (keyword server-key) :ices]))
        lines (run-phase-ladder-lines
               {:phase       (:phase run)
                :server-name (run-server-display server-key)
                :position    (:position run)
                :ice-count   (when (pos? ice-count) ice-count)
                ;; Only name a rezzed ICE — fog of war hides unrezzed identities.
                :ice-name    (when (and current-ice (:rezzed current-ice))
                               (:title current-ice))})]
    (when (seq lines)
      (doseq [line lines] (println line))
      true)))

(defn prompt-card-side
  "Lowercase owning side (\"corp\"/\"runner\") of a prompt's :card, or nil if unknown.

   Prefers the side the wire already put on the card; falls back to the card DB by
   title (authoritative and wire-shape independent — see engine-rate-of-change).
   Returns nil rather than guessing, so callers stay silent when we can't tell."
  [card]
  (when-let [s (or (:side card)
                   (:side (get @all-cards (:title card))))]
    (str/lower-case (str s))))

(defn opponent-card-decision-lines
  "Lines telling the DECIDING seat that this choice is theirs, when an OPPONENT'S
   card handed them the decision (#84).

   Cards like Wildcat Strike are Runner events whose mode the CORP picks, so the
   Corp sees a prompt titled with a Runner card whose every option reads as a
   Runner outcome (\"Runner gains 6 [Credits]\"). Two different models independently
   read that as a mis-seated prompt and escalated 'am I wedged?' instead of
   choosing — the prompt never said who owed the move. Nothing else in the prompt
   carries ownership, so state it.

   Fires ONLY when we are not the ACTIVE PLAYER — the discriminator for 'the
   opponent's turn handed me a decision'. The symmetric real case still fires: a
   Corp operation on the Corp's turn that makes the RUNNER choose (traces are the
   best example after Wildcat Strike).

   The CALLER must additionally gate on the prompt actually offering a decision
   (:choices / :selectable). Two false positives make that mandatory, and both
   reproduce the very confusion this line exists to cure:
     - RUN prompts. show-run-prompts (prompts.clj) conses a choice-less
       :prompt-type :run prompt onto BOTH queues carrying the RUNNER'S run event
       (Jailbreak, Overclock — several per game in the shipped tutorial deck). The
       Corp would be told to choose with no Choices block, and 'blocked until you
       choose' would contradict print-run-window-priority! directly below it.
     - ACCESS prompts. The Runner accessing a Corp card holds a prompt carrying an
       opponent card; the active-player guard suppresses it on the Runner's turn,
       but a Corp-turn run (An Offer You Can't Refuse) slips through, where
       'your opponent's Hedge Fund hands you this' is simply false — nobody handed
       us an R&D access. The caller drops \"You accessed …\" for that reason.
   Requiring a real choice also makes the 'nothing is wedged' line true by
   construction.

   SCOPING (deliberate, not an oversight): an opponent card that hands us a
   decision on OUR OWN turn is silent — e.g. Manegarm Skunkworks at
   approach-server, or an on-encounter ice choice. Those share the
   'options read as the opponent's outcome' shape, but the seat already knows it
   is acting on its own turn, so the ownership line would be noise where the
   confusion does not arise. Silence is also the safe direction here.

   Silent (returns []) when the card is ours, when the side is unknown, or when the
   active player is unknown — never claim an ownership we cannot establish."
  [card-title card-side my-side active-player]
  (let [lc      #(when % (str/lower-case (str %)))
        card-lc (lc card-side)
        my-lc   (lc my-side)
        act-lc  (lc active-player)]
    (if (and card-title card-lc my-lc act-lc
             (not= card-lc my-lc)      ; the card is the opponent's, and
             (not= my-lc act-lc))      ; it is not our turn — they handed us this
      (let [opp (if (= "runner" card-lc) "Runner" "Corp")
            me  (if (= "corp" my-lc) "Corp" "Runner")]
        [(format "  ⚠️  This decision is YOURS: your opponent's %s hands the choice to you."
                 card-title)
         (format "     The options may read as %s outcomes — %s still picks which one happens."
                 opp me)
         "     Nothing is wedged: the game is blocked until you choose."])
      [])))

(defn waiting-on-opponent-lines
  "Lines for the WAITING seat naming what the opponent actually owes (#84).

   'Waiting for Corp to make a decision' is truthful but cannot distinguish
   'opponent is thinking' from 'opponent is stuck on something it doesn't realise
   it owns' — against a slow model those look identical for an unbounded time. The
   engine puts the originating :card on the waiting prompt too, so name it."
  [opp card-title]
  (if card-title
    [(format "  Action: Waiting on %s — they owe a decision for %s (no action required from you)."
             opp card-title)]
    [(format "  Action: Waiting on %s — no action required from you." opp)]))

(defn show-prompt-detailed
  "Show current prompt with detailed choices.
   1-arity: render from an already-captured state (snapshot, #139 guest panel —
   a live re-read mid-snapshot could print 'Not in a game' under a captured
   board)."
  ([] (show-prompt-detailed @state/client-state))
  ([state]
  (let [side (:side state)
        prompt (when side
                 (get-in state [:game-state (keyword (clojure.string/lower-case side)) :prompt-state]))]
    (if prompt
      (let [has-choices (seq (:choices prompt))
            has-selectable (seq (:selectable prompt))
            ;; #104: acting commands auto-append this block, and seats still call
            ;; `prompt` after acting — so the identical block prints twice in a row.
            ;; Say which one this is. Computed BEFORE the mark below, and only for a
            ;; matching :eid, so a stacked duplicate (#75) still reads as new.
            already-shown? (state/prompt-already-rendered? prompt)]
        (state/mark-prompt-rendered! prompt)
        (println (if already-shown?
                   "\n🔔 Current Prompt (unchanged — the same one just shown, not a second one):"
                   "\n🔔 Current Prompt:"))
        (println "  Message:" (:msg prompt))
        (println "  Type:" (:prompt-type prompt))
        (when-let [card (:card prompt)]
          (println (str "  Card: " (:title card)
                        (when (:type card) (str " (" (:type card) ")"))))
          ;; #84: if the OPPONENT played this card, say so — the options can read
          ;; entirely as their outcomes and look mis-seated to us.
          ;; #84: if an OPPONENT'S card handed us this decision, say so — the
          ;; options can read entirely as their outcomes and look mis-seated.
          ;; Gate on a REAL decision being offered: a choice-less :run prompt also
          ;; carries the opponent's run event (show-run-prompts pushes it to both
          ;; queues), and telling the Corp to "choose" there — with no Choices block
          ;; and the run actually blocked on the Runner — recreates the #84
          ;; confusion on the other seat. Access prompts are dropped too: we
          ;; initiated those, nobody handed them to us.
          (when (and (not (state/waiting-prompt-type? (:prompt-type prompt)))
                     (or has-choices has-selectable)
                     (not (str/starts-with? (str (:msg prompt)) "You accessed ")))
            (doseq [line (opponent-card-decision-lines
                          (:title card) (prompt-card-side card) side
                          (get-in state [:game-state :active-player]))]
              (println line))))
        ;; When BOTH a Choices and a Selectable block are present, name the verb
        ;; each block uses so the player doesn't reach for the wrong one. The
        ;; Choices verb DEPENDS ON prompt type: on a "select" prompt the :choices
        ;; are meta-buttons (e.g. Done) and `choose-option!` REJECTS `choose <N>`
        ;; there — they're pressed by name via `choose-value`. Only a non-select
        ;; prompt (e.g. Mutual Favor's "other") takes `choose <N>`. (issue #40,
        ;; codex review of PR #41)
        (let [choices-verb (if (state/select-prompt-type? (:prompt-type prompt))
                             "`choose-value \"<label>\"`"
                             "`choose <N>`")]
          (when (and has-choices has-selectable)
            (println "  ⚠️  This prompt has TWO selectors — pick the right verb:")
            (println (str "     • Choices block below → use " choices-verb))
            (println "     • Selectable cards block → use `choose-card <N>`"))
          (when has-choices
            (println (str "  Choices:" (when has-selectable (str "  (use " choices-verb ")"))))
            (doseq [[idx choice] (map-indexed vector (:choices prompt))]
              (println (str "    " idx ". " (core/format-choice choice))))))
        (when has-selectable
          (let [selectable (:selectable prompt)
                prompt-msg (or (:msg prompt) "")
                ;; Detect if this is a multi-select prompt
                ;; Pattern 1: "choose N cards" in message
                choose-n-match (re-find #"[Cc]hoose (\d+) cards?" prompt-msg)
                ;; Pattern 2: Discard prompt - check hand vs max
                gs (:game-state state)
                hand-size (count (get-in gs [side :hand]))
                max-hand-size (get-in gs [side :hand-size :total] 5)
                is-discard? (str/includes? (str/lower-case prompt-msg) "discard")
                cards-to-discard (when is-discard? (max 0 (- hand-size max-hand-size)))
                cards-required (cond
                                 choose-n-match (Integer/parseInt (second choose-n-match))
                                 (and is-discard? (pos? cards-to-discard)) cards-to-discard
                                 :else nil)]
            ;; Show multi-select warning if applicable
            (cond
              cards-required
              (do
                (println (str "  ⚠️  MULTI-SELECT: Choose " cards-required " card(s)"))
                (println "     Use: multi-choose <card1> <card2> ... OR multi-choose 0 1 2 ..."))

              ;; Per-credit payment prompt. It re-asks once per credit, and the
              ;; count in the message is the ONLY hint that more calls are
              ;; coming — which is why two models independently reported it as
              ;; friction (#104 Overclock ×5, #110 Unity ×2). Say how many are
              ;; owed and name the one-call form. This is NOT a multi-choose
              ;; case: multi-choose re-selects DIFFERENT cards, whereas paying
              ;; a cost out of ONE source is what --all does.
              (core/credit-payment-prompt prompt-msg)
              (let [{:keys [remaining]} (core/credit-payment-prompt prompt-msg)]
                ;; "[Credits]" unpluralised — it's the game's credit icon (see
                ;; ai-prompts/payment-progress).
                (println (format "  💳 PAYMENT: %d [Credits] still owed. Each pick pays ONE credit."
                                 remaining))
                (println "     Use: choose-card <N> --all   (pay from that source until the cost is met)")
                (println "     Or:  choose-card <N>         (one credit, prompt re-asks)"))

              :else
              (println "  Selectable cards: (Use choose-card to select by index)"))
            ;; Render via the shared helper: pickable cards with their true
            ;; indices + a single warning line for phantom (unresolvable) CIDs,
            ;; instead of dumping raw "CID: <uuid>" lines that confuse indexing.
            ;; Resolve CIDs against the SAME captured board we are rendering
            ;; (third guest pass: the 1-arity consulted the live atom, so a
            ;; resync mid-snapshot turned a valid pick into "hidden — ignore").
            (let [{:keys [pickable phantom] :as parts} (core/resolve-selectable selectable (:game-state state))]
              (println (str "  Available ("
                            (if (seq phantom)
                              (str (count pickable) " selectable; " (count phantom) " hidden/unselectable")
                              (str (count selectable) " cards"))
                            "):"))
              (core/print-selectable! parts "    "))))
        ;; Handle paid ability windows / passive prompts
        (when (and (not has-choices) (not has-selectable))
          (let [run (get-in state [:game-state :run])
                run-phase (when run (:phase run))
                my-side (clojure.string/lower-case (or side "runner"))]
            (if run-phase
              ;; Show run phase context
              (do
                ;; Explicit YOU-ARE-HERE ladder (forum [099]); falls back to the
                ;; bare phase line for any phase the ladder doesn't model.
                (when-not (print-run-phase-ladder! state run my-side)
                  (println (str "  Run Phase: " run-phase)))
                ;; During an encounter, show ICE and breaker info. Keyed on the
                ;; live encounter as well as the phase: at a forced encounter the
                ;; guidance below was printing while the ICE and the playable
                ;; breakers — the two things a seat needs to act on it — were
                ;; suppressed by the phase gate (guest re-review).
                (when (or (= run-phase "encounter-ice")
                          (live-encounter? state))
                  (show-encounter-ice-info state run my-side))
                ;; Whose move is it now + what 'continue' does — shared with the
                ;; `status` command so both surface the same run-priority read.
                (print-run-window-priority! state run run-phase my-side))
              ;; Not in a run. `continue` is RUN-ONLY (errors "No active run to
              ;; monitor") — never advertise it here. A "waiting" prompt means the
              ;; opponent is deciding (e.g. Wildcat Strike); the player takes no
              ;; pass action and other actions may still be available. (issue #38)
              (let [waiting? (state/waiting-prompt-type? (:prompt-type prompt))
                    opp (if (= my-side "runner") "Corp" "Runner")]
                (if waiting?
                  (do
                    ;; #84: name the card the opponent owes a decision for when the
                    ;; engine gave us one — "Waiting for Corp to make a decision"
                    ;; alone can't tell 'thinking' from 'stuck and doesn't know it'.
                    (doseq [line (waiting-on-opponent-lines opp (:title (:card prompt)))]
                      (println line))
                    (println (str "    → Other actions may still be available; use 'wait' to block until "
                                  opp " decides."))
                    (println "    → ('continue' is run-only and won't help here.)"))
                  (do
                    (println "  Action: Paid ability window (no run active)")
                    (println "    → No choices required.")
                    (println "    → 'continue' is run-only here — take your next action, or 'wait'."))))))))
      ;; No prompt object. "No active prompt" alone is technically true but
      ;; misleads at a turn boundary (a reader concludes the game isn't waiting on
      ;; them when it's actually their turn to start). Append the turn-aware next
      ;; action so `prompt` reliably answers "what do I do now?".
      (let [ts (state/get-turn-status state)
            side (:side state)
            next-lc (clojure.string/lower-case (or (:next-player ts) ""))
            my-lc (clojure.string/lower-case (or side ""))]
        ;; NO GAME AT ALL must be answered before anything derived from the game
        ;; state, and before the "no active prompt" line — which reads as "the
        ;; game has nothing for you" when the truth is "there is no game". On a
        ;; purged game every field below is falsy, so the `(not (:my-turn? ts))`
        ;; arm won by default and sent the seat to `wait` for a nonexistent
        ;; opponent: a command that manufactures the stall it's meant to explain.
        ;; :game-over? is checked alongside :in-game? because a decided game
        ;; tears the lobby down too — reporting "not in a game" there would hide
        ;; the RESULT, which is the one thing the seat still needs. Game-over
        ;; wins; the no-game branch is only for a game that ended with no verdict.
        (if (and (not (:in-game? ts)) (not (:game-over? ts)))
          (do
            (println "⚠️  Not in a game — there is no prompt, and nothing to wait for.")
            (println "   (If a game was running, it has ended or been purged.)")
            (println "💡 Start a fresh game:  ./dev/reset.sh")
            (println "   Or check first:      ./dev/send_command <side> game-over-status"))
          (do
        (println "No active prompt — no decision is pending for you right now.")
        (cond
          (:game-over? ts)
          (println (format "🏁 Game over — %s." (:status-text ts)))

          (:in-run? ts)
          (println (format "🏃 A run is in progress on %s → use 'monitor-run' / 'continue'."
                           (or (:run-server ts) "?")))

          ;; Turn boundary, my turn to start.
          (and (:waiting-to-start? ts) (= next-lc my-lc))
          (println "🟢 It's YOUR turn but it hasn't started yet (0 clicks) → use 'start-turn'.")

          ;; Turn boundary, opponent starts next.
          (:waiting-to-start? ts)
          (println (format "⏳ Waiting for %s to start their turn → use 'wait'." (:next-player ts)))

          ;; #117: MY turn, no clicks, not ended, nothing pending. This used to
          ;; match the boundary arm above and print "🟢 It's YOUR turn but it
          ;; hasn't started yet → start-turn" to the player who wasn't even
          ;; active — the line the umpire read and acted on.
          (:turn-orphaned? ts)
          (do
            (println "⏳ It's still YOUR turn — 0 clicks left, but the turn has not ended.")
            (println "   → use 'smart-end-turn'. This is the one shape where end-turn is")
            (println "     safe: you ARE the active player and :end-turn is not set.")
            (println "   (If a decision is pending on the opponent, the client ends the")
            (println "    turn itself once that clears — see #114.)"))

          (not (:my-turn? ts))
          (println (format "⏳ It's %s's turn, not yours → use 'wait'."
                           (or (:whose-turn ts) "the opponent")))

          (:can-act? ts)
          (println "✅ It's your turn with clicks in hand → act (see 'list-playables').")

          :else
          (println (format "ℹ️  %s" (:status-text ts)))))))))))

(defn show-prompt-if-any
  "Append the current prompt to an action's output — or print NOTHING if there
   isn't one.

   Exists because the command log says the single most common thing a seat does
   after changing the game state is ask what the state now is: continue→prompt
   ran P=0.48 (n=186 in the marquee era alone), with the same shape at
   score→prompt (0.40), choose-card→prompt (0.33) and run→prompt (0.27). Roughly
   half of all `prompt` calls are a round-trip the acting command could have
   answered itself. It is also the misleading-output failure surface: a seat that
   cannot see the outcome of its own action re-issues it (Terra looped `continue`
   three times off a misread phase in marquee g1).

   Silence when there is no prompt is the whole contract — this runs after EVERY
   action, so a 'No active prompt' line would be pure noise on the majority of
   commands and would train seats to stop reading the tail of the output.

   A passive waiting prompt is rendered as one line rather than the full block:
   it carries no choices, and the useful information is just 'not your move'."
  []
  (when-let [prompt (state/get-prompt)]
    (if (state/waiting-prompt-type? (:prompt-type prompt))
      (println (format "\n⏳ %s" (or (:msg prompt) "Waiting for opponent")))
      (show-prompt-detailed))))

(defn show-snapshot
  "One-shot per-decision snapshot: compact status, the current prompt (only when
   one is open), compact board, hand, the last N log lines, and the state cursor
   -- i.e. the whole status/prompt/board/hand/log/get-cursor read-loop collapsed
   into a single call (one round-trip, one model-facing turn). Read-only; N
   defaults to 5 log lines. The trailing `cursor=<n>` is the value to pass to
   `wait --since` before acting."
  ([] (show-snapshot 5))
  ([n]
   (let [cs @state/client-state
         lobby (:lobby-state cs)]
     ;; #139: with no board, ONE explainer. The partial fix had the guarded
     ;; hand say "NO BOARD" under a fabricated compact status and board —
     ;; a single machine-read response that both described and denied a
     ;; board (guest panel). An unstarted lobby keeps its own compact line.
     (if-not (map? (:game-state cs))
       ;; No board: the lobby line if we are in an unstarted lobby (that IS
       ;; the status), then the one explainer, then the cursor. Never the
       ;; board/hand renderers — calling show-board-compact* directly here
       ;; printed an empty rig beside the hand's "NOT STARTED" (second pass).
       (do
         (when (and lobby (not (:started lobby)))
           (show-status-compact* cs))
         (no-side-here! cs "the snapshot")
         (println (str "cursor=" (core/get-cursor)))
         nil)
       ;; One captured state for EVERY board-describing part (guest panel):
       ;; status / prompt / board / hand rendered from `cs`, so a resync landing
       ;; mid-snapshot cannot make the response both describe and deny a
       ;; board. Only the log tail reads live, and it asserts nothing about
       ;; the board's existence.
       (do
         (show-status-compact* cs)
         (when (state/get-prompt cs)
           (println)
           (show-prompt-detailed cs))
         (println)
         (show-board-compact* cs)
         (println)
         (show-hand cs)
         (println)
         (show-log-compact n)
         (println (str "cursor=" (core/get-cursor)))
         nil)))))

(defn show-card-text
  "Display full card information including text, cost, and abilities
   Usage: (show-card-text \"Sure Gamble\")
          (show-card-text \"Tithe\")"
  [card-name]
  ;; Auto-load cards if not already loaded
  (core/load-cards-from-api!)

  (if (empty? @all-cards)
    (do
      (println "❌ Failed to load card database")
      (println "   Make sure the game server is running on localhost:1042"))
    (if-let [card (get @all-cards card-name)]
      (let [text (or (:text card) "")
            ;; Strip formatting markup for readability
            clean-text (-> text
                          (clojure.string/replace #"\[Click\]" "[Click]")
                          (clojure.string/replace #"\[Credit\]" "[Credit]")
                          (clojure.string/replace #"\[Subroutine\]" "[Subroutine]")
                          (clojure.string/replace #"\[Trash\]" "[Trash]")
                          (clojure.string/replace #"\[Recurring Credits\]" "[Recurring Credits]")
                          (clojure.string/replace #"\[mu\]" "[MU]")
                          (clojure.string/replace #"<[^>]+>" ""))] ;; Remove HTML-like tags
        (println "\n" (clojure.string/join "" (repeat 70 "=")))
        (println "📄" (:title card))
        (println (clojure.string/join "" (repeat 70 "=")))
        (println "Type:" (str (:type card)
                              (when (:subtype card) (str " - " (:subtype card)))))
        (println "Side:" (:side card))
        (when (:faction card)
          (println "Faction:" (:faction card)))
        (when-let [cost (:cost card)]
          (println "Cost:" cost))
        (when-let [strength (:strength card)]
          (println "Strength:" strength))
        (when-let [trash (:trash card)]
          (println "Trash Cost:" trash))
        (when-let [mu (:memoryunits card)]
          (println "Memory:" mu))
        (when-let [agenda-points (:agendapoints card)]
          (println "Agenda Points:" agenda-points))
        (when-let [adv-cost (:advancementcost card)]
          (println "Advancement Requirement:" adv-cost))
        (when (not-empty clean-text)
          (println "\nText:")
          (println clean-text))
        (println (clojure.string/join "" (repeat 70 "="))))
      (println "❌ Card not found:" card-name))))

(defn show-cards
  "Display multiple cards in compact or full format
   Usage: (show-cards [\"Sure Gamble\" \"Diesel\" \"Dirty Laundry\"])
          (show-cards [\"Sure Gamble\" \"Diesel\"] true) ; full format"
  ([card-names] (show-cards card-names false))
  ([card-names full?]
   ;; Auto-load cards if not already loaded
   (core/load-cards-from-api!)

   (if (empty? @all-cards)
     (do
       (println "❌ Failed to load card database")
       (println "   Make sure the game server is running on localhost:1042"))
     (do
       (println (str "\n📚 Card Reference (" (count card-names) " cards):"))
       (println (clojure.string/join "" (repeat 70 "─")))

       (doseq [card-name card-names]
         (if-let [card (get @all-cards card-name)]
           (if full?
             ;; Full format - same as show-card-text
             (let [text (or (:text card) "")
                   clean-text (-> text
                                 (clojure.string/replace #"\[Click\]" "[Click]")
                                 (clojure.string/replace #"\[Credit\]" "[Credit]")
                                 (clojure.string/replace #"\[Subroutine\]" "[Subroutine]")
                                 (clojure.string/replace #"\[Trash\]" "[Trash]")
                                 (clojure.string/replace #"\[Recurring Credits\]" "[Recurring Credits]")
                                 (clojure.string/replace #"\[mu\]" "[MU]")
                                 (clojure.string/replace #"<[^>]+>" ""))]
               (println (str "\n📄 " (:title card)))
               (println "Type:" (str (:type card)
                                     (when (:subtype card) (str " - " (:subtype card)))))
               (when-let [cost (:cost card)] (println "Cost:" cost))
               (when-let [strength (:strength card)] (println "Strength:" strength))
               (when-let [trash (:trash card)] (println "Trash Cost:" trash))
               (when (not-empty clean-text)
                 (println "Text:" clean-text)))

             ;; Compact format - one line per card
             (let [type-str (:type card)
                   cost-str (when-let [c (:cost card)] (str c "¢"))
                   subtitle (if cost-str
                             (str type-str ", " cost-str)
                             type-str)
                   text (or (:text card) "")
                   ;; Get first sentence or first 60 chars
                   short-text (let [first-sentence (first (clojure.string/split text #"\." ))]
                               (if (> (count first-sentence) 60)
                                 (str (subs first-sentence 0 57) "...")
                                 first-sentence))]
               (println (str "📄 " (:title card) " (" subtitle ")"))
               (when (not-empty short-text)
                 (println (str "   " short-text)))))
           (println (str "❌ Card not found: " card-name))))

       (println (clojure.string/join "" (repeat 70 "─")))))))

(defn show-hand-cards
  "Display information for all cards currently in hand
   Usage: (show-hand-cards)
          (show-hand-cards true) ; full format"
  ([] (show-hand-cards false))
  ([full?]
   (let [hand (state/my-hand)
         card-names (map :title hand)]
     (if (empty? card-names)
       (println "🃏 Hand is empty")
       (show-cards card-names full?)))))

(defn show-card-abilities
  "Show available abilities for an installed card by name
   Works for both Runner and Corp cards
   Usage: (show-card-abilities \"Smartware Distributor\")
          (show-card-abilities \"Cleaver\")"
  [card-name]
  (let [state @state/client-state
        side (:side state)
        corp-viewer? (core/side= "Corp" side)
        ;; Find card in appropriate location. Use core/side= — client-state stores
        ;; :side lowercase ("corp"), so a strict (= "Corp" side) is always false
        ;; and would search the Runner rig, missing every Corp card. (issue #69)
        card (if corp-viewer?
               (core/find-installed-corp-card card-name)
               (core/find-installed-card card-name))
        ;; #95: a Runner probing a Corp card (bioroid click-break) used to
        ;; dead-end on "Card not found". Fall back to the opponent's installed
        ;; cards and surface the Runner-usable abilities instead.
        cross-card (when (and (not card) (not corp-viewer?))
                     (core/find-installed-corp-card card-name))]
    (cond
      card
      (let [abilities (:abilities card)]
        (println "\n" (clojure.string/join "" (repeat 70 "=")))
        (println "🎯" (:title card) "- ABILITIES")
        (println (clojure.string/join "" (repeat 70 "=")))
        (if (seq abilities)
          (doseq [[idx ability] (map-indexed vector abilities)]
            (let [label (or (:label ability) (:cost-label ability) (str "Ability " idx))
                  action-icon (if (:action ability) "[Click] " "")
                  once-str (when (:once ability)
                            (str " (Once " (if (keyword? (:once ability))
                                             (name (:once ability))
                                             (:once ability)) ")"))]
              (println (str "\n  [" idx "] " label))
              (when-let [cost-label (:cost-label ability)]
                (println (str "      Cost: " action-icon cost-label)))
              (when once-str
                (println (str "      " once-str)))))
          (println "No abilities available"))
        (println (clojure.string/join "" (repeat 70 "="))))

      cross-card
      (let [runner-abs (:runner-abilities cross-card)]
        (println "\n" (clojure.string/join "" (repeat 70 "=")))
        (println "🎯" (:title cross-card) "- RUNNER-USABLE ABILITIES (Corp card)")
        (println (clojure.string/join "" (repeat 70 "=")))
        (if (seq runner-abs)
          (doseq [[idx ability] (map-indexed vector runner-abs)]
            (println (str "\n  [" idx "] " (:label ability)))
            (when-let [cost-label (:cost-label ability)]
              (println (str "      Cost: " cost-label)))
            (println (str "      use-runner-ability \"" (:title cross-card) "\" " idx)))
          (println "No Runner-usable abilities on this Corp card"))
        (println (clojure.string/join "" (repeat 70 "="))))

      :else
      ;; Ambiguity is not absence (#151 item 5): a duplicate title has already
      ;; printed its "❓ Multiple copies" list by the time we get here, and a
      ;; flat not-found beneath it tells the seat its own board is wrong.
      (core/report-installed-lookup-miss! card-name
                                          (if corp-viewer? [:corp] [:runner :corp])))))

;; ============================================================================
;; High-Level Workflows
;; ============================================================================

(defn simple-corp-turn
  "Execute a simple Corp turn: click for credit 3 times, end turn"
  []
  (println "\n=== SIMPLE CORP TURN ===")
  (dotimes [i 3]
    (actions/take-credits))
  (actions/end-turn)
  (println "=== TURN COMPLETE ===\n"))

(defn simple-runner-turn
  "Execute a simple Runner turn: click for credit 4 times, end turn"
  []
  (println "\n=== SIMPLE RUNNER TURN ===")
  (dotimes [i 4]
    (actions/take-credits))
  (actions/end-turn)
  (println "=== TURN COMPLETE ===\n"))

;; ============================================================================
;; Debugging
;; ============================================================================

(defn inspect-state
  "Show raw game state (for debugging)"
  []
  (clojure.pprint/pprint @state/client-state))

(defn inspect-prompt
  "Show raw prompt data (for debugging)"
  []
  (clojure.pprint/pprint (state/get-prompt)))

;; ============================================================================
;; Help
;; ============================================================================

(defn- list-playables-for-side
  "The body of `list-playables`, entered only once a side is known.

   Takes the captured state rather than re-reading the atom, so every section of
   one listing describes the same snapshot."
  [state side]
  (let [gs (:game-state state)
        my-state (get gs side)
        clicks (:click my-state)
        credits (:credit my-state)
        hand (:hand my-state)
        rig (:rig my-state)
        deck-count (:deck-count my-state)
        ;; The basic actions this side can actually take right now. One value,
        ;; printed below and counted in the total — see the comment there.
        ;;
        ;; `draw` is gated on the deck, because the engine gates it: the basic
        ;; action card carries :req (req (not-empty (:deck corp))) — basic.clj:42
        ;; for the Corp, :165 for the Runner — so offering it at 0 cards names an
        ;; action the engine will refuse. That is the endgame state where a wasted
        ;; command costs most: a Corp with an empty R&D is one mandatory draw from
        ;; losing. nil deck-count is treated as "unknown, so offer it" — the wire
        ;; always sends :deck-count for our own side, and a missing one is a stale
        ;; fixture rather than an empty deck. (Review panel, MAJOR.)
        basic-action-lines
        (cond-> ["take-credit (gain 1 credit, costs 1 click)"]
          (or (nil? deck-count) (pos? deck-count))
          (conj "draw (draw 1 card, costs 1 click)")

          (and (some? deck-count) (zero? deck-count))
          (conj (if (= side :corp)
                  "draw — UNAVAILABLE: R&D is empty (your next mandatory draw loses the game)"
                  "draw — UNAVAILABLE: your stack is empty"))

          (= side :runner)
          (conj "run <server> (initiate run, costs 1 click)")

          (= side :corp)
          (conj "purge (remove all virus counters, costs 3 clicks)"))
        ;; The UNAVAILABLE line is information, not an offer — it must not be
        ;; counted as a playable basic action.
        playable-basic-count (count (remove #(str/includes? % "UNAVAILABLE")
                                            basic-action-lines))]

    (println "\n=== PLAYABLE ACTIONS ===")
    (println (format "Clicks: %s  Credits: %s"
                     (or clicks "?")
                     (or credits "?")))

    ;; Check for active prompts. An active prompt BLOCKS the actions listed
    ;; below — the engine may still mark cards :playable, but you cannot play
    ;; them until the prompt is resolved (or, for a `waiting` prompt, until the
    ;; opponent acts). Say so plainly: the GPT-5.5 seat read the playable list as
    ;; actionable during a pending-mulligan wait and burned turns trying to act.
    (let [prompt-state (:prompt-state my-state)
          prompt (first (:prompt my-state))
          active-prompt (or prompt-state prompt)
          blocked? (boolean active-prompt)
          waiting? (state/waiting-prompt-type? (:prompt-type active-prompt))
          msg (or (:msg prompt-state) (:msg prompt))
          ;; A passive run prompt (run in progress, no choices/selectable) is a
          ;; priority / paid-ability window — the next action is `continue`/`rez`,
          ;; NOT `choose`. The old "answer this prompt FIRST → choose <N>" advice
          ;; here was flatly wrong for a run window (same contradictory-guidance
          ;; family as the diagnose-blocker fix): the Corp can't `choose` an
          ;; opponent's run, and reads "0 playables / choose <N>" as a dead end.
          run (:run gs)
          run-phase (:phase run)
          run-window? (and run active-prompt
                           (not (seq (:choices active-prompt)))
                           (not (seq (:selectable active-prompt))))]
      (when blocked?
        (println (format "\n⚠️  Active Prompt: %s" (or msg "(no message)")))
        (cond
          waiting?
          (do
            (println "   ⛔ You are WAITING on the opponent — the actions below are NOT playable right now.")
            (println "   Use 'wait' until this clears; don't try to act through it."))

          run-window?
          (do
            (println (format "   ⏸️  This is a RUN priority window%s — NOT a choose-prompt; 'choose' does not apply."
                             (if run-phase (str " (phase: " run-phase ")") "")))
            (if (= side :corp)
              (do
                (println "   Your options here: rez a card / fire a paid ability if useful, or 'continue' to pass priority (decline to act).")
                (println "   • ICE rez happens at the approach-ice window — there: continue --rez <ice>  (or --no-rez to decline).")
                (println "   • 'monitor-run' auto-passes boring windows and stops only when a real decision is owed."))
              (do
                (println "   Your options here: 'continue' to advance the run (pass priority), or fire a break / paid ability if useful.")
                (println "   • 'monitor-run' or 'continue --full-break' can auto-handle routine windows."))))

          :else
          (do
            (println "   ⛔ Answer this prompt FIRST — the actions below are blocked until you resolve it.")
            (println "   Use 'prompt' to see choices, then 'choose <N>' / 'choose-card <N>' / 'choose-value <text>'.")))))

    ;; Playable hand cards
    (let [blocked? (boolean (or (:prompt-state my-state) (first (:prompt my-state))))
          playable-cards (filter :playable hand)]
      (when (seq playable-cards)
        (println (if blocked?
                   "\n📋 Hand Cards (⛔ blocked by active prompt — see above):"
                   "\n📋 Hand Cards:"))
        (doseq [card playable-cards]
          (let [card-name (core/format-card-name-with-index card hand)
                cost (:cost card)
                cost-str (if cost (str cost " credits") "free")]
            (println (format "  - %s (%s, %s)"
                            card-name
                            (:type card)
                            cost-str))))))

    ;; Playable installed abilities
    (let [all-installed (concat
                         (get rig :hardware [])
                         (get rig :program [])
                         (get rig :resource []))
          playable-abilities (for [card all-installed
                                  [idx ability] (map-indexed vector (:abilities card))
                                  :when (:playable ability)]
                              {:card card
                               :card-name (core/format-card-name-with-index card all-installed)
                               :idx idx
                               :label (:label ability)
                               :cost (:cost-label ability)})]
      (when (seq playable-abilities)
        (println "\n⚙️  Installed Abilities:")
        (doseq [{:keys [card-name idx label cost]} playable-abilities]
          (println (format "  - %s: Ability %d - %s%s"
                          card-name
                          idx
                          label
                          (if cost (str " (" cost ")") ""))))))

    ;; Runner abilities on Corp cards (e.g., bioroid click-to-break, #95).
    ;; Primary source: the encountered ICE from run state (current-run-ice) —
    ;; authoritative. The old prompt-state :card source is stale-prone (memory:
    ;; prompt-state-not-cleared-use-eid) and missed the Brân encounter in
    ;; marquee 6d8f4cf8; keep it only as a fallback for non-run prompts.
    (when (= side :runner)
      ;; Phase gate (review catch): current-run-ice also returns the ICE at
      ;; approach-ice / mid-run movement, where the click-break is NOT live
      ;; (engine req is currently-encountering-card) — advertising it there
      ;; bait-and-switches the seat into a silent refusal + timeout.
      (let [encounter-ice (when (= "encounter-ice" (get-in gs [:run :phase]))
                            (core/current-run-ice state))
            prompt-card (get-in gs [:runner :prompt-state :card])
            source-card (if (seq (:runner-abilities encounter-ice))
                          encounter-ice
                          prompt-card)
            runner-abilities (:runner-abilities source-card)]
        (when (seq runner-abilities)
          (println "\n🔓 Runner Abilities (Bioroid/Corp cards):")
          (doseq [[idx ability] (map-indexed vector runner-abilities)]
            (println (format "  - %s: Runner-Ability %d - %s%s"
                            (:title source-card)
                            idx
                            (:label ability)
                            (if-let [cost (:cost-label ability)]
                              (str " (" cost ")")
                              "")))
            (println (format "    use-runner-ability \"%s\" %d"
                            (:title source-card) idx))))))

    ;; Basic actions (always available if clicks > 0).
    ;;
    ;; #132: every line here is gated on the side that can actually take it, and
    ;; spells the verb the CLI parses. A seat does not read this as prose — it
    ;; types it. `run` was ungated, so the Corp was offered an action the CLI
    ;; then refuses ("Only Runner can run on servers"); `draw` sat inside the
    ;; Corp-only arm next to `purge`, so the Runner was never told it could draw
    ;; at all; and the verb printed was `draw-card`, which is not a command
    ;; (`Unknown command: draw-card`, and the did-you-mean list omits `draw`).
    ;; purge really is Corp-only — that gate was the one correct part.
    ;; Built as a collection, then printed AND counted from the same value.
    ;; The total below used to be a second literal ("4" for Corp, "2" for
    ;; Runner) kept in sync by hand, and the re-gating above silently falsified
    ;; both — Corp printed 3 and claimed 4, Runner printed 3 and claimed 2. The
    ;; total is the line a seat trusts to know whether it has seen everything,
    ;; so it cannot be a parallel assertion about the list; it has to be the
    ;; list. (Review panel, MAJOR — the very failure mode #132 is about.)
    (when (and clicks (pos? clicks))
      (println "\n🎯 Basic Actions:")
      (doseq [line basic-action-lines]
        (println (str "  - " line))))

    ;; Always available
    (println "\n⏭️  Other Actions:")
    (println "  - end-turn (end current turn)")
    (println "")

    ;; Return count of playable actions
    (let [card-count (count (filter :playable hand))
          ability-count (count (for [card (concat (get rig :hardware [])
                                                  (get rig :program [])
                                                  (get rig :resource []))
                                    ability (:abilities card)
                                    :when (:playable ability)]
                                ability))]
      (println (format "Total: %d playable cards, %d playable abilities, %d basic actions"
                      card-count
                      ability-count
                      (if (and clicks (pos? clicks)) playable-basic-count 0)))
      {:playable-cards card-count
       :playable-abilities ability-count
       :clicks clicks})))

(defn list-playables
  "List all currently playable actions (cards, abilities, basic actions)
   Useful for AI decision-making - shows exactly what can be done right now"
  []
  (let [state @state/client-state]
    (if-let [side (and (map? (:game-state state)) (state/my-side-kw state))]
      (list-playables-for-side state side)
      (no-side-here! state "the playable-action list"))))

(declare show-blocker-diagnosis*)

(defn show-blocker-diagnosis
  "Read-only diagnosis of why you can/can't act right now and the ONE next
   command to run. Safe — never mutates state. Answers the GPT-5.5 seat's ask
   for a 'diagnose-blocker' that names who owns the blocking prompt and whether
   it's actionable, instead of guessing from contradictory-looking status lines.

   #139: the worst offender of the boardless sweep — the command a seat runs
   when it is stuck answered a cleared cache with 'Waiting for unknown to start
   their turn → Use: wait', and `wait` on a game that isn't there is a hang. With
   no board the only honest diagnosis is the shared explainer."
  []
  (let [cs @state/client-state]
    (if-not (map? (:game-state cs))
      (no-side-here! cs "the blocker diagnosis")
      (show-blocker-diagnosis* cs))))

(defn- show-blocker-diagnosis*
  "The diagnosis proper, from ONE captured state; the caller guarantees a
   board. Reads nothing from the atom (guest panel: check/use race)."
  [cs]
  (let [ts (state/get-turn-status cs)
        prompt (state/get-prompt cs)
        ptype (:prompt-type prompt)
        waiting? (state/waiting-prompt-type? ptype)
        side (:side cs)
        side-kw (when side (keyword (clojure.string/lower-case side)))
        my-clicks (get-in cs [:game-state side-kw :click])
        next-lc (clojure.string/lower-case (or (:next-player ts) ""))
        run (get-in cs [:game-state :run])
        run-phase (:phase run)
        my-side-lc (when side (clojure.string/lower-case side))
        ;; A prompt is resolvable-via-choose only if it carries actual choices or
        ;; selectable cards. A passive run priority / paid-ability window has a
        ;; prompt object (sometimes a non-"waiting" :prompt-type) but NO options —
        ;; the next action there is `continue`, not `choose`.
        has-options? (boolean (or (seq (:choices prompt)) (seq (:selectable prompt))))]
    (println "\n=== BLOCKER DIAGNOSIS ===")
    (println (format "Side: %s | Turn %s, active: %s | your clicks: %s"
                     (or side "?") (:turn-number ts) (or (:whose-turn ts) "?")
                     (if (nil? my-clicks) "?" my-clicks)))
    (cond
      (:game-over? ts)
      (println (format "🏁 Game over — %s. Nothing to do." (:status-text ts)))

      ;; Actionable prompt with real options owned by us — resolve via choose.
      (and prompt (not waiting?) has-options?)
      (do
        (println (format "📋 You have an ACTIONABLE prompt [%s]: %s" ptype (:msg prompt)))
        (println "   → Owner: YOU. Resolve it before any other action.")
        (println "   → Use: prompt (see choices), then choose <N> / choose-card <N> / choose-value \"<text>\""))

      ;; Passive prompt during a run = a priority / paid-ability window. The next
      ;; action is `continue`, NOT `choose`. This is the branch that used to be
      ;; missing: a non-"waiting" passive run prompt (e.g. the run-initiation
      ;; "Waiting for Corp paid abilities" window) fell through to the actionable
      ;; branch above and told the seat to `choose`, contradicting what `prompt`
      ;; and `continue` say. Now diagnose-blocker agrees with them. (backlog #4)
      (and (:in-run? ts) prompt (not waiting?))
      (let [runner-unbroken (when (and (= my-side-lc "runner")
                                       (or (= run-phase "encounter-ice")
                                           (live-encounter? cs)))
                              (runner-encounter-unbroken-count cs))]
        (println (format "⏸️  Run priority / paid-ability window%s: %s"
                         (if run-phase (str " (" run-phase ")") "") (:msg prompt)))
        ;; `initiation` is a both-must-pass window too (engine: continue :initiation
        ;; needs BOTH sides), but only the Runner gets the already-passed-aware hint
        ;; here — once it has passed, "use continue" is a no-op loop (issue #31 / g3).
        ;; The Corp keeps the generic continue/monitor-run steer at initiation (it
        ;; may still want to rez/fire a paid ability there). Membership comes from
        ;; both-pass-window?, shared with print-run-window-priority! — this copy of
        ;; the set was the one left behind when approach-ice was added (#115), and
        ;; `diagnose` is exactly the surface a stuck seat reaches for.
        (cond
          (both-pass-window? run-phase my-side-lc)
          (do
            (println "   → Owner: this is a both-must-pass priority window, not a choose prompt.")
            (doseq [line (run-priority-hint-lines run my-side-lc)]
              (println line)))

          ;; Runner mid-encounter with unbroken subs it is not breaking: `continue`
          ;; is refused (#92), so naming it here (as this branch used to) is the lie
          ;; that agreed with the prompt display and deadlocked marquee G2.
          (and runner-unbroken (pos? runner-unbroken))
          (let [ice-title (:title (encountered-ice cs) "this ICE")
                tanked? (state/tank-authorized? ice-title)]
            ;; A tanked encounter is NOT still owed a decision by us — saying
            ;; "Owner: YOU" there is the same false failure `tank` itself used to
            ;; print (#151 item 2). diagnose-blocker is where a seat goes when it
            ;; already suspects it is stuck; it must not manufacture the suspicion.
            (println (if tanked?
                       "   → Owner: the CORP — your tank stands; they owe the subs."
                       "   → Owner: YOU — a break/tank decision, not a both-pass window."))
            (doseq [line (runner-encounter-decline-hint-lines
                          ice-title runner-unbroken tanked?)]
              (println line)))

          :else
          (do
            (println "   → Owner: this is a both-must-pass priority window, not a choose prompt.")
            (println "   → Use: continue (to pass priority) — or monitor-run to participate in the run."))))

      ;; Waiting prompt — blocked on the opponent, NOT a stall.
      (and prompt waiting?)
      (do
        (println (format "⛔ You have a WAITING prompt: %s" (:msg prompt)))
        (println "   → Owner: OPPONENT. You are blocked until they act — this is NOT a stall.")
        (if (state/mulligan-wait-prompt? prompt)
          (println "   → They're still on their opening mulligan. Use: wait, then start-turn once it clears.")
          (println "   → Use: wait --since <cursor>")))

      ;; Active run, no prompt for us yet.
      (:in-run? ts)
      (do
        (println (format "🏃 A run is in progress on %s." (or (:run-server ts) "?")))
        ;; #110: this offered `continue-run` — the single-step alias — as a way to
        ;; "advance" a run, which is both the undocumented verb and the wrong one
        ;; for the job. Name `continue`; monitor-run stays because the Corp brief
        ;; uses that name for the same command.
        (println "   → Use: continue (Corp brief calls the same command monitor-run)."))

      ;; Turn boundary — my turn but not started (0 clicks).
      (and (:waiting-to-start? ts) (= next-lc (clojure.string/lower-case (or side ""))))
      (do
        (println "🟢 It's your turn but it has NOT started yet (0 clicks until you do).")
        (println "   → Use: start-turn"))

      ;; Boundary, but the other player starts next.
      (:waiting-to-start? ts)
      (do
        (println (format "⏳ Waiting for %s to start their turn." (:next-player ts)))
        (println "   → Use: wait --since <cursor>"))

      ;; #117: my turn, out of clicks, not ended. Nobody is owed a start-turn and
      ;; no `wait` can wake — this is the shape that deadlocks a match, so the
      ;; blocker diagnosis has to name it rather than route the seat to `wait`.
      (:turn-orphaned? ts)
      (do
        (println "⛔ Your turn is out of clicks but has NOT ended — nothing will")
        (println "   wake either seat until it does. This is not a stall to wait out.")
        (println "   → Use: smart-end-turn"))

      ;; Not my turn.
      (not (:my-turn? ts))
      (do
        (println (format "⏳ It's %s's turn, not yours." (or (:whose-turn ts) "the opponent")))
        (println "   → Use: wait --since <cursor>"))

      ;; My turn with clicks, nothing blocking.
      (:can-act? ts)
      (println "✅ Nothing is blocking you — your turn, clicks in hand. Act (see list-playables).")

      :else
      (println (format "ℹ️  %s" (:status-text ts))))
    (println (str "cursor=" (core/get-cursor)))
    nil))

(defn help
  "Show available commands"
  []
  (println "\n=== AI ACTIONS HELP ===\n")
  (println "Lobby Management:")
  (println "  (create-lobby! \"Game Name\")       - Create a new game lobby")
  (println "  (create-lobby! {:title \"Game\"})   - Create with options map")
  (println "  (list-lobbies)                     - List available games")
  (println "\nConnection:")
  (println "  (connect-game! \"game-id\" \"Corp\") - Join a game")
  (println "\nInformation:")
  (println "  (status)                           - Show game status")
  (println "  (show-prompt)                      - Show current prompt")
  (println "  (hand)                             - Show my hand")
  (println "  (list-playables)                   - List all playable actions")
  (println "\nMulligan:")
  (println "  (keep-hand)                        - Keep hand")
  (println "  (mulligan)                         - Redraw hand")
  (println "  (auto-keep-mulligan)               - Auto-handle mulligan")
  (println "\nBasic Actions:")
  (println "  (take-credits)                     - Click for credit")
  (println "  (draw-card)                        - Draw a card")
  (println "  (end-turn)                         - End turn")
  (println "\nPrompts:")
  (println "  (choose 0)                         - Choose first option")
  (println "  (wait-for-prompt 10)               - Wait for prompt")
  (println "  (wait-for-relevant-diff)           - Block until anything we care about happens")
  (println "  (wait-for-relevant-diff 120)       - Custom timeout (default 300s)")
  (println "  (wait-for-relevant-diff {:since N}) - Race-free wait from a cursor")
  (println "\nWorkflows:")
  (println "  (simple-corp-turn)                 - 3x credit, end")
  (println "  (simple-runner-turn)               - 4x credit, end")
  (println "\nDebugging:")
  (println "  (inspect-state)                    - Show raw state")
  (println "  (inspect-prompt)                   - Show raw prompt")
  (println "  (help)                             - This help\n"))
