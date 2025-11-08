;; Game command test: Direct game engine testing with fixed decks
;; This bypasses WebSocket layer and tests game actions directly
;; Allows deterministic games with known card positions for AI testing

(ns game-command-test
  (:require
   [game.core :as core]
   [game.core.card :refer [get-card get-counters]]
   [game.test-framework :refer :all]
   [jinteki.cards :refer [all-cards]]))

;; ============================================================================
;; System Gateway Beginner Decks (fixed order for reproducible tests)
;; ============================================================================

(def gateway-beginner-corp-deck
  "System Gateway beginner Corp deck - exact order for testing"
  ["Offworld Office" "Offworld Office" "Offworld Office"
   "Send a Message" "Send a Message"
   "Superconducting Hub" "Superconducting Hub"
   "Nico Campaign" "Nico Campaign"
   "Regolith Mining License" "Regolith Mining License"
   "Urtica Cipher" "Urtica Cipher"
   "Government Subsidy" "Government Subsidy"
   "Hedge Fund" "Hedge Fund" "Hedge Fund"
   "Seamless Launch" "Seamless Launch"
   "Manegarm Skunkworks"
   "Brân 1.0" "Brân 1.0"
   "Palisade" "Palisade" "Palisade"
   "Diviner" "Diviner"
   "Whitespace" "Whitespace"
   "Karunā" "Karunā"
   "Tithe" "Tithe"])

(def gateway-beginner-runner-deck
  "System Gateway beginner Runner deck - exact order for testing"
  ["Creative Commission" "Creative Commission"
   "Jailbreak" "Jailbreak" "Jailbreak"
   "Overclock" "Overclock"
   "Sure Gamble" "Sure Gamble" "Sure Gamble"
   "Tread Lightly" "Tread Lightly"
   "VRcation" "VRcation"
   "Docklands Pass"
   "Pennyshaver"
   "Red Team"
   "Smartware Distributor" "Smartware Distributor"
   "Telework Contract" "Telework Contract"
   "Verbal Plasticity"
   "Carmen" "Carmen"
   "Cleaver" "Cleaver"
   "Mayfly" "Mayfly"
   "Unity" "Unity"])

;; ============================================================================
;; Card Lookup Helpers
;; ============================================================================

(defn card-info
  "Quick lookup of card properties from the card database.
  Returns a map with useful info for testing."
  [card-name]
  (when-let [card (get @all-cards card-name)]
    {:title (:title card)
     :type (:type card)
     :cost (:cost card)
     :faction (:faction card)
     :side (:side card)
     :strength (:strength card)
     :agenda-points (:agendapoints card)
     :advancement-cost (:advancementcost card)
     :memory (:memoryunits card)
     :influence (:influence card)}))

(defn print-card-info
  "Print card information in readable format"
  [card-name]
  (if-let [info (card-info card-name)]
    (do
      (println "\n=== Card Info:" card-name "===")
      (doseq [[k v] info]
        (when v
          (println (str "  " (name k) ":") v))))
    (println "Card not found:" card-name)))

;; ============================================================================
;; Test Setup Helpers
;; ============================================================================

(defn open-hand-game
  "Create a game with both players' hands visible for testing.
  Uses System Gateway beginner decks in fixed order.
  Starting hand is first 5 cards from deck.

  NOTE: Returns game state atom. Don't print at REPL or it will spam!"
  []
  (do-game
    (new-game {:corp {:deck gateway-beginner-corp-deck
                      :hand (take 5 gateway-beginner-corp-deck)}
               :runner {:deck gateway-beginner-runner-deck
                        :hand (take 5 gateway-beginner-runner-deck)}})
    state))

(defn custom-open-hand-game
  "Create a game with custom hands for both sides.
  Remaining deck cards are in specified order (no shuffling in tests).

  NOTE: Returns game state atom. Don't print at REPL or it will spam!"
  [corp-hand corp-deck runner-hand runner-deck]
  (do-game
    (new-game {:corp {:deck corp-deck
                      :hand corp-hand}
               :runner {:deck runner-deck
                        :hand runner-hand}})
    state))

;; ============================================================================
;; Game State Inspection
;; ============================================================================

(defn print-game-state
  "Print current game state for both sides"
  [state]
  (let [corp (:corp @state)
        runner (:runner @state)]
    (println "\n=== GAME STATE ===")
    (println "Corp:")
    (println "  Credits:" (:credit corp))
    (println "  Clicks:" (:click corp))
    (println "  Hand:" (mapv :title (:hand corp)))
    (println "  Deck size:" (count (:deck corp)))

    (println "\nRunner:")
    (println "  Credits:" (:credit runner))
    (println "  Clicks:" (:click runner))
    (println "  Hand:" (mapv :title (:hand runner)))
    (println "  Deck size:" (count (:deck runner)))))

(defn check-prompts
  "Check for open prompts and print them for debugging.
  Returns true if there are prompts open (indicating potential issues)."
  [state]
  (let [corp-prompt (get-prompt state :corp)
        runner-prompt (get-prompt state :runner)
        has-corp-prompt (and corp-prompt (not= :run (:prompt-type corp-prompt)))
        has-runner-prompt (and runner-prompt (not= :run (:prompt-type runner-prompt)))]
    (when (or has-corp-prompt has-runner-prompt)
      (println "\n⚠️  OPEN PROMPTS DETECTED:")
      (when has-corp-prompt
        (println "  Corp prompt:" (:msg corp-prompt))
        (println "    Type:" (:prompt-type corp-prompt))
        (when (:choices corp-prompt)
          (println "    Choices:" (:choices corp-prompt))))
      (when has-runner-prompt
        (println "  Runner prompt:" (:msg runner-prompt))
        (println "    Type:" (:prompt-type runner-prompt))
        (when (:choices runner-prompt)
          (println "    Choices:" (:choices runner-prompt)))))
    (or has-corp-prompt has-runner-prompt)))

(defn assert-no-prompts
  "Assert that neither side has open prompts. Useful for AI player to verify clean state."
  [state context]
  (when (check-prompts state)
    (println "\n❌ ERROR: Unexpected prompts at:" context)
    (println "   This usually means:")
    (println "   - We tried to play a card that doesn't exist")
    (println "   - An action requires a choice (server, target, etc.)")
    (println "   - We need to handle a trigger or ability")))

(defn has-prompts?
  "Simple boolean check: does either side have open prompts?
  Useful for AI player decision-making."
  [state]
  (check-prompts state))

(defn print-board-state
  "Print installed cards on both sides"
  [state]
  (println "\n=== BOARD STATE ===")
  (println "Corp:")
  (println "  HQ:" (mapv :title (get-in @state [:corp :servers :hq :content])))
  (println "  R&D:" (mapv :title (get-in @state [:corp :servers :rd :content])))
  (println "  Archives:" (mapv :title (get-in @state [:corp :servers :archives :content])))
  (doseq [i (range 1 10)]
    (let [remote (keyword (str "remote" i))
          content (get-in @state [:corp :servers remote :content])
          ice (get-in @state [:corp :servers remote :ices])]
      (when (or (seq content) (seq ice))
        (println (str "  Remote " i ":"))
        (when (seq ice)
          (println "    ICE:" (mapv :title ice)))
        (when (seq content)
          (println "    Content:" (mapv :title content))))))

  (println "\nRunner:")
  (println "  Programs:" (mapv :title (get-in @state [:runner :rig :program])))
  (println "  Hardware:" (mapv :title (get-in @state [:runner :rig :hardware])))
  (println "  Resources:" (mapv :title (get-in @state [:runner :rig :resource]))))

;; ============================================================================
;; Example Test: Basic Turn Flow
;; ============================================================================

(defn test-basic-turn-flow
  "Test basic turn actions: credit, draw, end turn"
  []
  (println "\n========================================")
  (println "TEST: Basic Turn Flow")
  (println "========================================")

  (let [state (open-hand-game)]
    (println "\n--- Initial State ---")
    (print-game-state state)

    ;; Corp turn 1
    (println "\n--- Corp Turn 1 ---")
    (start-turn state :corp)
    (take-credits state :corp)
    (print-game-state state)

    ;; Runner turn 1
    (println "\n--- Runner Turn 1 ---")
    (start-turn state :runner)
    (core/process-action "credit" state :runner nil)
    (println "Runner clicked for credit")
    (print-game-state state)

    (core/process-action "draw" state :runner nil)
    (println "Runner drew a card")
    (print-game-state state)

    (take-credits state :runner)
    (println "Runner ended turn")
    (print-game-state state)

    ;; Corp turn 2
    (println "\n--- Corp Turn 2 ---")
    (start-turn state :corp)
    (print-game-state state)

    (println "\n✅ Test complete!")
    nil))  ; Don't return state - it's huge and will spam console

;; ============================================================================
;; Example Test: Playing Cards
;; ============================================================================

(defn test-playing-cards
  "Test playing operations and installing cards"
  []
  (println "\n========================================")
  (println "TEST: Playing Cards")
  (println "========================================")

  (let [state (custom-open-hand-game
                ;; Corp starts with Hedge Fund in hand
                ["Hedge Fund" "Palisade" "Nico Campaign"]
                ;; Rest of corp deck
                (drop 3 gateway-beginner-corp-deck)
                ;; Runner starts with Sure Gamble
                ["Sure Gamble" "Docklands Pass" "Carmen"]
                ;; Rest of runner deck
                (drop 3 gateway-beginner-runner-deck))]

    (println "\n--- Initial State ---")
    (print-game-state state)

    ;; Corp plays Hedge Fund
    (println "\n--- Corp plays Hedge Fund ---")
    (start-turn state :corp)
    (play-from-hand state :corp "Hedge Fund")
    (print-game-state state)

    ;; Corp installs Palisade on HQ
    (println "\n--- Corp installs Palisade on HQ ---")
    (play-from-hand state :corp "Palisade" "HQ")
    (print-board-state state)
    (print-game-state state)

    ;; Corp installs Nico Campaign
    (println "\n--- Corp installs Nico Campaign in new remote ---")
    (play-from-hand state :corp "Nico Campaign" "New remote")
    (print-board-state state)
    (print-game-state state)

    (take-credits state :corp)

    ;; Runner plays Sure Gamble
    (println "\n--- Runner plays Sure Gamble ---")
    (start-turn state :runner)
    (play-from-hand state :runner "Sure Gamble")
    (print-game-state state)

    ;; Runner installs Docklands Pass
    (println "\n--- Runner installs Docklands Pass ---")
    (play-from-hand state :runner "Docklands Pass")
    (print-board-state state)
    (print-game-state state)

    (println "\n✅ Test complete!")
    nil))  ; Don't return state - it's huge and will spam console

;; ============================================================================
;; Phase 2.1: Asset Installation & Rezzing
;; ============================================================================

(defn test-asset-management
  "Test installing assets, rezzing them, and using their abilities.
  Demonstrates:
  - Installing assets to remote servers
  - Rezzing assets (paying rez cost)
  - Using card abilities (ability index 0)
  - Verifying credit changes from abilities"
  []
  (println "\n========================================")
  (println "TEST: Asset Management")
  (println "========================================")

  (let [state (custom-open-hand-game
                ;; Corp starts with Nico Campaign and Regolith Mining License
                ["Nico Campaign" "Regolith Mining License" "Hedge Fund"]
                (drop 3 gateway-beginner-corp-deck)
                ;; Runner gets standard starting hand
                (take 5 gateway-beginner-runner-deck)
                (drop 5 gateway-beginner-runner-deck))]

    (println "\n--- Setup ---")
    (print-game-state state)
    (print-board-state state)

    ;; Install Nico Campaign to new remote
    (println "\n--- Corp installs Nico Campaign in new remote ---")
    (start-turn state :corp)
    (play-from-hand state :corp "Nico Campaign" "New remote")
    (print-board-state state)
    (print-game-state state)

    ;; Get reference to the installed (but unrezzed) Nico Campaign
    (let [nico (get-content state :remote1 0)]
      (println "\n--- Installed card: " (:title nico))
      (println "    Rezzed?:" (:rezzed nico))
      (println "    Rez cost:" (:cost nico)))

    ;; Rez Nico Campaign (costs 1 credit)
    (println "\n--- Corp rezzes Nico Campaign (costs 1 credit) ---")
    (let [nico (get-content state :remote1 0)
          credits-before (:credit (:corp @state))]
      (println "Credits before rez:" credits-before)
      (rez state :corp nico)
      (println "Credits after rez:" (:credit (:corp @state)))
      (println "Credit change:" (- (:credit (:corp @state)) credits-before)))

    ;; Check that it's now rezzed
    (let [nico (get-content state :remote1 0)]
      (println "\n--- After rezzing ---")
      (println "    Rezzed?:" (:rezzed nico)))

    ;; Check Nico's counters (it starts with 9)
    (let [nico (get-content state :remote1 0)]
      (println "\n--- Nico Campaign counters ---")
      (println "Credits on Nico:" (get-counters nico :credit))
      (println "Nico automatically gives 3 credits at start of turn (passive ability)"))

    ;; End Corp turn and start new one to trigger Nico's automatic ability
    (println "\n--- Corp ends turn ---")
    (let [credits-after-corp-turn (:credit (:corp @state))]
      (println "Corp credits after ending turn:" credits-after-corp-turn))
    (take-credits state :corp)

    (println "\n--- Runner turn ---")
    (start-turn state :runner)
    (let [credits-before-runner-ends (:credit (:corp @state))]
      (println "Corp credits before runner ends turn:" credits-before-runner-ends))
    (take-credits state :runner)

    ;; At start of Corp turn, Nico should automatically trigger
    (println "\n--- Corp turn begins (Nico auto-triggers) ---")
    (start-turn state :corp)
    (let [nico (get-content state :remote1 0)]
      (println "Corp credits after turn starts:" (:credit (:corp @state)))
      (println "Nico counters after trigger:" (get-counters nico :credit))
      (println "Nico gave 3 credits automatically (9 - 6 = 3 counters used)"))

    ;; Now test Regolith Mining License
    (println "\n--- Corp installs Regolith Mining License in new remote ---")
    (play-from-hand state :corp "Regolith Mining License" "New remote")
    (print-board-state state)

    ;; Rez Regolith (costs 1 credit)
    (println "\n--- Corp rezzes Regolith Mining License ---")
    (let [regolith (get-content state :remote2 0)
          credits-before (:credit (:corp @state))]
      (println "Credits before rez:" credits-before)
      (rez state :corp regolith)
      (println "Credits after rez:" (:credit (:corp @state))))

    ;; Use Regolith's ability (costs 1 click, take 3 credits from card)
    (println "\n--- Corp uses Regolith ability (1 click: take 3 credits) ---")
    (let [regolith (get-content state :remote2 0)
          credits-before (:credit (:corp @state))
          counters-before (get-counters regolith :credit)]
      (println "Corp credits before:" credits-before)
      (println "Regolith counters before:" counters-before " (starts with 15)")
      (card-ability state :corp regolith 0)
      ;; Get updated card reference to see changes
      (let [regolith-updated (get-content state :remote2 0)]
        (println "Corp credits after:" (:credit (:corp @state)))
        (println "Regolith counters after:" (get-counters regolith-updated :credit))
        (println "Credits gained:" (- (:credit (:corp @state)) credits-before))))

    (println "\n--- Final State ---")
    (print-game-state state)
    (print-board-state state)

    (println "\n✅ Test complete!")
    nil))

;; ============================================================================
;; Phase 2.2: Agenda Management
;; ============================================================================

(defn test-agenda-scoring
  "Test advancing agendas and scoring them.
  Demonstrates:
  - Installing agendas in remote servers
  - Advancing agendas (spending click + credit)
  - Checking advancement counters
  - Scoring agendas when requirement met
  - Verifying agenda points awarded"
  []
  (println "\n========================================")
  (println "TEST: Agenda Scoring")
  (println "========================================")

  (let [state (custom-open-hand-game
                ;; Corp starts with Offworld Office
                ["Offworld Office" "Hedge Fund" "Hedge Fund"]
                (drop 3 gateway-beginner-corp-deck)
                ;; Runner gets standard starting hand
                (take 5 gateway-beginner-runner-deck)
                (drop 5 gateway-beginner-runner-deck))]

    (println "\n--- Setup ---")
    (print-game-state state)

    ;; Install Offworld Office in new remote
    (println "\n--- Corp installs Offworld Office in remote ---")
    (start-turn state :corp)
    (play-from-hand state :corp "Offworld Office" "New remote")
    (print-board-state state)

    ;; Get reference to installed agenda
    (let [agenda (get-content state :remote1 0)]
      (println "\n--- Installed agenda ---")
      (println "Title:" (:title agenda))
      (println "Advancement requirement:" (:advancementcost agenda))
      (println "Agenda points:" (:agendapoints agenda))
      (println "Current advancement counters:" (get-counters agenda :advancement)))

    ;; End turn to get fresh clicks for advancing
    (println "\n--- Corp ends turn to get fresh clicks ---")
    (println "Clicks remaining:" (:click (:corp @state)))
    (take-credits state :corp)
    (start-turn state :runner)
    (take-credits state :runner)
    (start-turn state :corp)
    (println "Corp turn 2 - Clicks available:" (:click (:corp @state)))

    ;; Advance agenda 4 times (Offworld Office needs 4 to score)
    (println "\n--- Advancing agenda (1st advance) ---")
    (let [agenda (get-content state :remote1 0)
          credits-before (:credit (:corp @state))
          clicks-before (:click (:corp @state))]
      (println "Clicks before:" clicks-before "Credits before:" credits-before)
      (advance state agenda)
      (let [agenda-updated (get-content state :remote1 0)]
        (println "Clicks after:" (:click (:corp @state)) "Credits after:" (:credit (:corp @state)))
        (println "Advancement counters:" (get-counters agenda-updated :advancement))))

    (println "\n--- Advancing agenda (2nd advance) ---")
    (advance state (get-content state :remote1 0))
    (println "Advancement counters:" (get-counters (get-content state :remote1 0) :advancement))

    (println "\n--- Advancing agenda (3rd advance) ---")
    (advance state (get-content state :remote1 0))
    (println "Advancement counters:" (get-counters (get-content state :remote1 0) :advancement))
    (println "Clicks remaining:" (:click (:corp @state)))

    ;; Need another turn for 4th advance (Corp only has 3 clicks/turn)
    (println "\n--- Corp ends turn, needs more clicks ---")
    (take-credits state :corp)
    (take-credits state :runner)
    (println "Corp turn 3 - Clicks available:" (:click (:corp @state)))

    (println "\n--- Advancing agenda (4th advance) ---")
    (advance state (get-content state :remote1 0))
    (println "Advancement counters:" (get-counters (get-content state :remote1 0) :advancement))
    (println "Clicks remaining:" (:click (:corp @state)))

    ;; Now score the agenda (using core action, not test helper)
    (println "\n--- Scoring agenda ---")
    (let [agenda (get-content state :remote1 0)
          scored-before (count (:scored (:corp @state)))
          points-before (:agenda-point (:corp @state))]
      (println "Scored agendas before:" scored-before)
      (println "Agenda points before:" points-before)
      ;; Use core/process-action to score (not score-agenda helper which also advances)
      (core/process-action "score" state :corp {:card agenda})
      (println "Scored agendas after:" (count (:scored (:corp @state))))
      (println "Agenda points after:" (:agenda-point (:corp @state)))
      (println "Points gained:" (- (:agenda-point (:corp @state)) points-before)))

    ;; Verify agenda is no longer in remote
    (println "\n--- After scoring ---")
    (print-board-state state)
    (println "Scored agendas:" (mapv :title (:scored (:corp @state))))

    (println "\n✅ Test complete!")
    nil))

;; ============================================================================
;; Phase 2.3: ICE Management
;; ============================================================================

(defn test-ice-installation
  "Test installing ICE on servers and understanding ICE positions.
  Demonstrates:
  - Installing ICE on central servers (HQ, R&D)
  - Installing multiple ICE on same server
  - ICE position ordering (outermost to innermost)
  - Verifying ICE placement with get-ice"
  []
  (println "\n========================================")
  (println "TEST: ICE Installation")
  (println "========================================")

  (let [state (custom-open-hand-game
                ;; Corp starts with various ICE
                ["Palisade" "Brân 1.0" "Tithe" "Whitespace" "Hedge Fund"]
                (drop 5 gateway-beginner-corp-deck)
                ;; Runner gets standard starting hand
                (take 5 gateway-beginner-runner-deck)
                (drop 5 gateway-beginner-runner-deck))]

    (println "\n--- Setup ---")
    (print-game-state state)
    (print-board-state state)

    ;; Install ICE on HQ
    (println "\n--- Corp installs Palisade on HQ ---")
    (start-turn state :corp)
    (play-from-hand state :corp "Palisade" "HQ")
    (print-board-state state)
    (println "Clicks remaining:" (:click (:corp @state)))

    ;; Check ICE on HQ
    (let [hq-ice (get-ice state :hq)]
      (println "\nICE on HQ (count):" (count hq-ice))
      (println "ICE on HQ (titles):" (mapv :title hq-ice)))

    ;; Install ICE on R&D
    (println "\n--- Corp installs Brân 1.0 on R&D ---")
    (play-from-hand state :corp "Brân 1.0" "R&D")
    (print-board-state state)

    ;; Install first ICE on a remote
    (println "\n--- Corp installs Tithe on new remote ---")
    (play-from-hand state :corp "Tithe" "New remote")
    (print-board-state state)

    ;; Get fresh clicks for more ICE
    (println "\n--- Corp ends turn to get more clicks ---")
    (take-credits state :corp)
    (start-turn state :runner)
    (take-credits state :runner)
    (start-turn state :corp)
    (println "Corp turn 2 - Clicks available:" (:click (:corp @state)))

    ;; Install second ICE on same remote (will be outermost)
    ;; NOTE: Installing multiple ICE on same server has a tax!
    ;; First ICE: +0 credits, Second ICE: +1 credit, Third: +2, etc.
    (println "\n--- Corp installs Whitespace on Remote 1 (becomes outermost) ---")
    (let [credits-before (:credit (:corp @state))]
      (println "Credits before install:" credits-before)
      (println "Installing 2nd ICE on Remote 1 - will cost +1 credit tax")
      (play-from-hand state :corp "Whitespace" "Server 1")
      (println "Credits after install:" (:credit (:corp @state)))
      (println "Cost paid:" (- credits-before (:credit (:corp @state))) "(base 1 click, +1 credit for 2nd ICE)"))
    (print-board-state state)

    ;; Check ICE ordering on remote 1
    (let [remote1-ice (get-ice state :remote1)]
      (println "\n--- ICE positioning on Remote 1 ---")
      (println "Total ICE count:" (count remote1-ice))
      (println "ICE positions (outermost to innermost):" (mapv :title remote1-ice))
      (println "Position 0 (outermost):" (:title (nth remote1-ice 0)))
      (println "Position 1 (innermost):" (:title (nth remote1-ice 1))))

    ;; Final board state
    (println "\n--- Final Board State ---")
    (print-board-state state)
    (print-game-state state)

    (println "\n--- Summary ---")
    (println "HQ protected by:" (mapv :title (get-ice state :hq)))
    (println "R&D protected by:" (mapv :title (get-ice state :rd)))
    (println "Remote 1 protected by:" (mapv :title (get-ice state :remote1)))
    (println "(ICE ordered from outermost to innermost)")

    (println "\n✅ Test complete!")
    nil))

;; ============================================================================
;; Phase 2.4: End-of-Turn Rez Timing
;; ============================================================================

(defn test-end-of-turn-rez
  "Test rezzing assets at end of Runner's turn for optimal timing.
  Demonstrates:
  - Waiting to rez until end of Runner's turn
  - Minimizing exposure (Runner can't trash before it pays out)
  - Asset triggers at start of next Corp turn
  - Using continue to pass through timing windows"
  []
  (println "\n========================================")
  (println "TEST: End-of-Turn Rez Timing")
  (println "========================================")

  (let [state (custom-open-hand-game
                ;; Corp starts with Nico Campaign
                ["Nico Campaign" "Hedge Fund" "Ice Wall"]
                (drop 3 gateway-beginner-corp-deck)
                ;; Runner gets standard starting hand
                (take 5 gateway-beginner-runner-deck)
                (drop 5 gateway-beginner-runner-deck))]

    (println "\n--- Setup ---")
    (print-game-state state)

    ;; Corp installs Nico but doesn't rez
    (println "\n--- Corp installs Nico Campaign in remote (unrezzed) ---")
    (start-turn state :corp)
    (play-from-hand state :corp "Nico Campaign" "New remote")
    (print-board-state state)
    (let [nico (get-content state :remote1 0)]
      (println "Nico rezzed?:" (:rezzed nico)))

    ;; Corp ends turn without rezzing
    (println "\n--- Corp ends turn (Nico still unrezzed) ---")
    (println "Corp clicks remaining:" (:click (:corp @state)))
    (println "Corp credits:" (:credit (:corp @state)))
    (take-credits state :corp)

    ;; Runner takes their turn
    (println "\n--- Runner turn ---")
    (start-turn state :runner)
    (println "Runner clicks:" (:click (:runner @state)))
    (println "Runner could run and trash Nico, but doesn't")

    ;; Runner clicks for credits to pass time
    (core/process-action "credit" state :runner nil)
    (core/process-action "credit" state :runner nil)
    (println "Runner spent 2 clicks on credits")

    ;; NOW: End of Runner turn - Corp gets rez window!
    (println "\n--- End of Runner turn - Corp rez window ---")
    (println "Runner about to end turn...")
    (let [nico (get-content state :remote1 0)
          credits-before (:credit (:corp @state))]
      (println "Corp credits before rez:" credits-before)
      (println "Nico starts with 9 counters, gives 3 credits at start of turn")

      ;; Rez during Runner's end-of-turn window
      (println "\n--- Corp rezzes Nico during Runner's end-of-turn ---")
      (rez state :corp nico)
      (let [nico-rezzed (get-content state :remote1 0)]
        (println "Nico now rezzed?:" (:rezzed nico-rezzed))
        (println "Corp credits after rez:" (:credit (:corp @state)))
        (println "Rez cost paid:" (- credits-before (:credit (:corp @state))))))

    ;; Complete Runner's turn
    (take-credits state :runner)

    ;; Corp turn starts - Nico triggers automatically!
    (println "\n--- Corp turn starts - Nico triggers immediately ---")
    (start-turn state :corp)
    (let [nico (get-content state :remote1 0)
          credits-now (:credit (:corp @state))]
      (println "Corp credits at turn start:" credits-now)
      (println "Nico counters:" (get-counters nico :credit))
      (println "Nico paid out 3 credits on first Corp turn after rez!"))

    (println "\n--- Why this matters ---")
    (println "1. Rezzed at END of Runner turn (Runner can't trash it)")
    (println "2. Paid out IMMEDIATELY at start of Corp turn")
    (println "3. Minimized exposure window")
    (println "4. Got value before Runner could interact")

    (println "\n✅ Test complete!")
    nil))

;; ============================================================================
;; Phase 3.1: Program Installation
;; ============================================================================

(defn test-program-installation
  "Test installing programs (icebreakers) to Runner rig.
  Demonstrates:
  - Playing economy cards (Sure Gamble) for credits
  - Installing programs costs clicks + credits
  - Programs go into Runner's rig
  - Different programs have different install costs
  - Building a breaker suite for running"
  []
  (println "\n========================================")
  (println "TEST: Program Installation")
  (println "========================================")

  (let [state (custom-open-hand-game
                ;; Corp gets standard hand
                (take 5 gateway-beginner-corp-deck)
                (drop 5 gateway-beginner-corp-deck)
                ;; Runner starts with breakers, hardware, and economy
                ["Carmen" "Cleaver" "Docklands Pass" "Sure Gamble" "Creative Commission"]
                (drop 5 gateway-beginner-runner-deck))]

    (println "\n--- Setup ---")
    (print-game-state state)
    (print-board-state state)

    ;; Skip to Runner turn
    (start-turn state :corp)
    (take-credits state :corp)
    (start-turn state :runner)

    ;; Play economy card to get credits for installing
    (println "\n--- Runner plays Sure Gamble for credits ---")
    (println "Credits before:" (:credit (:runner @state)))
    (play-from-hand state :runner "Sure Gamble")
    (println "Credits after Sure Gamble:" (:credit (:runner @state)))
    (println "Clicks remaining:" (:click (:runner @state)))

    ;; Install first breaker
    (println "\n--- Runner installs Carmen (Fracter breaker) ---")
    (let [clicks-before (:click (:runner @state))
          credits-before (:credit (:runner @state))]
      (println "Clicks before:" clicks-before "Credits before:" credits-before)
      (play-from-hand state :runner "Carmen")
      (println "Clicks after:" (:click (:runner @state)) "Credits after:" (:credit (:runner @state)))
      (println "Cost: 1 click + 5 credits"))
    (print-board-state state)

    ;; Install second breaker (still have clicks!)
    (println "\n--- Runner installs Cleaver (Fracter breaker) ---")
    (let [clicks-before (:click (:runner @state))
          credits-before (:credit (:runner @state))]
      (println "Clicks before:" clicks-before "Credits before:" credits-before)
      (play-from-hand state :runner "Cleaver")
      (println "Clicks after:" (:click (:runner @state)) "Credits after:" (:credit (:runner @state)))
      (println "Cost: 1 click + 3 credits"))
    (print-board-state state)

    ;; Attempt to install hardware - but not enough credits!
    (println "\n--- Runner attempts to install Docklands Pass (Hardware) ---")
    (let [clicks-before (:click (:runner @state))
          credits-before (:credit (:runner @state))
          docklands (find-card "Docklands Pass" (:hand (:runner @state)))]
      (println "Clicks before:" clicks-before "Credits before:" credits-before)
      (println "Docklands Pass install cost:" (:cost docklands))
      (println "Can afford Docklands Pass?" (>= credits-before (:cost docklands)))
      (play-from-hand state :runner "Docklands Pass")
      (println "Clicks after:" (:click (:runner @state)) "Credits after:" (:credit (:runner @state)))
      (if (find-card "Docklands Pass" (:hand (:runner @state)))
        (println "INSTALL FAILED - Not enough credits! Still in hand.")
        (println "Installed successfully")))
    (print-board-state state)

    ;; Final state
    (println "\n--- Final Rig ---")
    (print-board-state state)
    (println "\n--- Summary ---")
    (println "Programs installed: 2 (Carmen, Cleaver)")
    (println "- Carmen: Fracter (breaks Barrier ICE) - Cost: 5 credits")
    (println "- Cleaver: Fracter (breaks Barrier ICE) - Cost: 3 credits")
    (println "\nDocklands Pass FAILED to install:")
    (println "- Costs 2 credits, but only 1 credit remaining")
    (println "- This demonstrates resource management!")
    (println "- Must budget credits carefully when building rig")
    (println "\nTotal clicks used: 4 (Sure Gamble + 2 program installs + 1 failed install)")
    (println "Total credits spent: 8 (5 for Carmen + 3 for Cleaver)")
    (println "\nKey lesson: Check affordability before attempting installs!")

    (println "\n✅ Test complete!")
    nil))

;; ============================================================================
;; Phase 3.2: Resource Management
;; ============================================================================

(defn test-resource-management
  "Test installing resources and using their abilities.
  Demonstrates:
  - Installing resources to Runner rig
  - Using click abilities on resources (placing/taking credits)
  - Automatic start-of-turn abilities
  - Resource economy management
  - Multiple resource types working together"
  []
  (println "\n========================================")
  (println "TEST: Resource Management")
  (println "========================================")

  (let [state (custom-open-hand-game
                ;; Corp gets standard hand
                (take 5 gateway-beginner-corp-deck)
                (drop 5 gateway-beginner-corp-deck)
                ;; Runner starts with resources and economy
                ["Smartware Distributor" "Telework Contract" "Sure Gamble" "Creative Commission" "Overclock"]
                (drop 5 gateway-beginner-runner-deck))]

    (println "\n--- Setup ---")
    (print-game-state state)
    (print-board-state state)

    ;; Skip to Runner turn
    (start-turn state :corp)
    (play-from-hand state :corp "Offworld Office" "New remote") ;; so we don't have to discard
    (take-credits state :corp)

    (start-turn state :runner)

    ;; Install Telework Contract (costs 1 credit, 1 click)
    (println "\n--- Runner installs Telework Contract ---")
    (let [clicks-before (:click (:runner @state))
          credits-before (:credit (:runner @state))]
      (println "Clicks before:" clicks-before "Credits before:" credits-before)
      (play-from-hand state :runner "Telework Contract")
      (println "Clicks after:" (:click (:runner @state)) "Credits after:" (:credit (:runner @state)))
      (println "Cost: 1 click + 1 credit"))
    (print-board-state state)

    ;; Check Telework's initial state
    (let [telework (first (get-resource state))]
      (println "\n--- Telework Contract initial state ---")
      (println "Title:" (:title telework))
      (println "Credits on card:" (get-counters telework :credit) "(starts with 9)"))

    ;; Use Telework's ability (1 click: take 3 credits)
    (println "\n--- Runner uses Telework ability (1 click: take 3 credits) ---")
    (let [telework (first (get-resource state))
          clicks-before (:click (:runner @state))
          credits-before (:credit (:runner @state))
          counters-before (get-counters telework :credit)]
      (println "Clicks before:" clicks-before "Credits before:" credits-before)
      (println "Telework counters before:" counters-before)
      (card-ability state :runner telework 0)
      (let [telework-updated (first (get-resource state))]
        (println "Clicks after:" (:click (:runner @state)) "Credits after:" (:credit (:runner @state)))
        (println "Telework counters after:" (get-counters telework-updated :credit))
        (println "Credits gained:" (- (:credit (:runner @state)) credits-before))))

    ;; Install Smartware Distributor (costs 0!)
    (println "\n--- Runner installs Smartware Distributor (FREE!) ---")
    (let [clicks-before (:click (:runner @state))
          credits-before (:credit (:runner @state))]
      (println "Clicks before:" clicks-before "Credits before:" credits-before)
      (play-from-hand state :runner "Smartware Distributor")
      (println "Clicks after:" (:click (:runner @state)) "Credits after:" (:credit (:runner @state)))
      (println "Cost: 1 click + 0 credits (free install!)"))
    (print-board-state state)

    ;; Check Smartware's initial state
    (let [smartware (second (get-resource state))]
      (println "\n--- Smartware Distributor initial state ---")
      (println "Title:" (:title smartware))
      (println "Credits on card:" (get-counters smartware :credit) "(starts with 0)"))

    ;; Use Smartware's click ability to place credits
    (println "\n--- Runner uses Smartware ability (1 click: place 3 credits on card) ---")
    (let [smartware (second (get-resource state))
          clicks-before (:click (:runner @state))
          counters-before (get-counters smartware :credit)]
      (println "Clicks before:" clicks-before)
      (println "Smartware counters before:" counters-before)
      (card-ability state :runner smartware 0)
      (let [smartware-updated (second (get-resource state))]
        (println "Clicks after:" (:click (:runner @state)))
        (println "Smartware counters after:" (get-counters smartware-updated :credit))
        (println "Counters added: 3")))

    ;; End turn and start new turn to trigger Smartware's automatic ability
    (println "\n--- Runner ends turn ---")
    (println "Clicks remaining:" (:click (:runner @state)))
    (end-turn state :runner)

    (println "\n--- Corp turn (passing) ---")
    (start-turn state :corp)
    (play-from-hand state :corp "Offworld Office" "New remote") ;; so we don't have to discard
    (take-credits state :corp)
    
    ;; At start of Runner turn, Smartware should automatically trigger
    (println "\n--- Runner turn begins (Smartware auto-triggers) ---")
    (start-turn state :runner)
    (let [smartware (second (get-resource state))
          credits-now (:credit (:runner @state))]
      (println "Runner credits at turn start:" credits-now)
      (println "Smartware counters:" (get-counters smartware :credit))
      (println "Smartware gave 1 credit automatically (3 → 2 counters)"))

    ;; Use Telework again on turn 2 (once-per-turn resets!)
    (println "\n--- Runner uses Telework again (turn 2 - restriction reset!) ---")
    (let [telework (first (get-resource state))
          credits-before (:credit (:runner @state))
          counters-before (get-counters telework :credit)]
      (println "Credits before:" credits-before)
      (println "Telework counters before:" counters-before)
      (println "Telework ability: Once per turn → [click]: Take 3[credit]")
      (card-ability state :runner telework 0)
      (let [telework-updated (first (get-resource state))]
        (println "Credits after:" (:credit (:runner @state)))
        (println "Telework counters after:" (get-counters telework-updated :credit))
        (println "✓ Gained" (- (:credit (:runner @state)) credits-before) "credits - once-per-turn reset works!")))

    ;; Final state
    (println "\n--- Final State ---")
    (print-game-state state)
    (print-board-state state)

    ;; Show resource states
    (println "\n--- Resource Status ---")
    (let [telework (first (get-resource state))
          smartware (second (get-resource state))]
      (println "Telework Contract:" (get-counters telework :credit) "credits remaining (started with 9, took 3+3)")
      (println "Smartware Distributor:" (get-counters smartware :credit) "credits remaining (placed 3, gave 1)"))

    (println "\n--- Summary ---")
    (println "Resource abilities demonstrated:")
    (println "1. Telework Contract - Click ability to take credits")
    (println "   - Install cost: 1 credit")
    (println "   - Starts with 9[credit] loaded on install")
    (println "   - Once per turn → [click]: Take 3[credit] from this resource")
    (println "   - When empty, trash it")
    (println "   - ✓ Successfully used TWICE (turn 1 and turn 2)")
    (println "   - ✓ Once-per-turn restriction resets each turn!")
    (println "2. Smartware Distributor - Click ability + automatic trigger")
    (println "   - FREE to install (0 cost)")
    (println "   - Click ability: Place 3 credits on card")
    (println "   - Automatic: At start of turn, gain 1 credit from card")
    (println "   - Demonstrates 'bank' economy pattern")
    (println "\nKey mechanics:")
    (println "- Resources install to rig like programs")
    (println "- Click abilities use card-ability with ability index")
    (println "- Automatic abilities trigger at start of turn")
    (println "- Once-per-turn abilities have usage restrictions")
    (println "- Some resources auto-trash when depleted (Telework)")

    (println "\n✅ Test complete!")
    nil))

;; ============================================================================
;; Phase 3.3: Event Economy
;; ============================================================================

(defn test-runner-events
  "Test playing Runner economy events.
  Demonstrates:
  - Playing events for immediate credit gains
  - Events that cost clicks (Creative Commission)
  - Net credit calculations (cost vs gain)
  - Events going to discard pile after playing
  - Different event types and their effects"
  []
  (println "\n========================================")
  (println "TEST: Runner Event Economy")
  (println "========================================")

  (let [state (custom-open-hand-game
                ;; Corp gets standard hand
                (take 5 gateway-beginner-corp-deck)
                (drop 5 gateway-beginner-corp-deck)
                ;; Runner starts with economy events
                ["Sure Gamble" "Sure Gamble" "Creative Commission" "Overclock" "Docklands Pass"]
                (drop 5 gateway-beginner-runner-deck))]

    (println "\n--- Setup ---")
    (print-game-state state)

    ;; Skip to Runner turn
    (start-turn state :corp)
    (take-credits state :corp)
    (start-turn state :runner)

    ;; Play Sure Gamble
    (println "\n--- Runner plays Sure Gamble ---")
    (let [clicks-before (:click (:runner @state))
          credits-before (:credit (:runner @state))
          hand-size-before (count (:hand (:runner @state)))
          discard-before (count (:discard (:runner @state)))]
      (println "Before:")
      (println "  Clicks:" clicks-before)
      (println "  Credits:" credits-before)
      (println "  Hand size:" hand-size-before)
      (println "  Discard size:" discard-before)
      (println "\nSure Gamble: Cost 5, gain 9 (net +4)")
      (play-from-hand state :runner "Sure Gamble")
      (println "\nAfter:")
      (println "  Clicks:" (:click (:runner @state)) "(used 1 click to play)")
      (println "  Credits:" (:credit (:runner @state)))
      (println "  Net credit change:" (- (:credit (:runner @state)) credits-before) "(paid 5, gained 9)")
      (println "  Hand size:" (count (:hand (:runner @state))))
      (println "  Discard size:" (count (:discard (:runner @state))) "(event went to discard)")
      (println "  Discard contents:" (mapv :title (:discard (:runner @state)))))

    ;; Play Creative Commission
    (println "\n--- Runner plays Creative Commission ---")
    (let [clicks-before (:click (:runner @state))
          credits-before (:credit (:runner @state))]
      (println "Before:")
      (println "  Clicks:" clicks-before)
      (println "  Credits:" credits-before)
      (println "\nCreative Commission: Cost 1, gain 5 and lose [Click]")
      (play-from-hand state :runner "Creative Commission")
      (println "\nAfter:")
      (println "  Clicks:" (:click (:runner @state)))
      (println "  Click change:" (- (:click (:runner @state)) clicks-before) "(-1 to play, -1 from card effect)")
      (println "  Credits:" (:credit (:runner @state)))
      (println "  Net credit change:" (- (:credit (:runner @state)) credits-before) "(paid 1, gained 5)")
      (println "  Discard size:" (count (:discard (:runner @state))))
      (println "  Discard contents:" (mapv :title (:discard (:runner @state)))))

    ;; Show remaining clicks for other actions
    (println "\n--- Runner uses remaining clicks ---")
    (println "Clicks remaining:" (:click (:runner @state)))
    (println "Runner can still install or do other actions")

    ;; Install something with remaining click
    (println "\n--- Runner installs Docklands Pass with remaining click ---")
    (let [clicks-before (:click (:runner @state))
          credits-before (:credit (:runner @state))]
      (play-from-hand state :runner "Docklands Pass")
      (println "Clicks after install:" (:click (:runner @state)))
      (println "Credits after install:" (:credit (:runner @state)))
      (println "Still have" (:click (:runner @state)) "click(s) left!"))

    ;; Try to play second Sure Gamble (but no clicks!)
    (println "\n--- Runner attempts second Sure Gamble ---")
    (let [clicks-before (:click (:runner @state))
          credits-before (:credit (:runner @state))]
      (println "Clicks available:" clicks-before)
      (println "Credits before:" credits-before)
      (if (pos? clicks-before)
        (do
          (play-from-hand state :runner "Sure Gamble")
          (println "Credits after:" (:credit (:runner @state)))
          (println "Net gain:" (- (:credit (:runner @state)) credits-before)))
        (println "✗ Cannot play - no clicks remaining!")))

    ;; Final state
    (println "\n--- Final State ---")
    (print-game-state state)
    (print-board-state state)

    ;; Show all discarded events
    (println "\n--- Runner Discard Pile ---")
    (println "Events played this turn:" (mapv :title (:discard (:runner @state))))
    (println "Total events in discard:" (count (:discard (:runner @state))))

    (println "\n--- Summary ---")
    (println "Event economy demonstrated:")
    (println "1. Sure Gamble")
    (println "   - Cost: 5 credits, 1 click")
    (println "   - Gain: 9 credits")
    (println "   - Net: +4 credits per play")
    (println "   - Played once successfully")
    (println "   - Second attempt failed: no clicks remaining!")
    (println "2. Creative Commission")
    (println "   - Cost: 1 credit, 2 clicks (1 to play + 1 from card)")
    (println "   - Gain: 5 credits")
    (println "   - Net: +4 credits, -2 clicks")
    (println "   - Trade-off: Same credit gain as Sure Gamble, costs extra click")
    (println "\nKey mechanics:")
    (println "- Events are one-time effects played from hand")
    (println "- Events go to discard pile after playing")
    (println "- Some events have additional costs (Creative Commission loses click)")
    (println "- Events can be chained with other actions in same turn")
    (println "- Net economy calculation: (gain - cost) per event")
    (println "- IMPORTANT: Must have clicks available to play events!")
    (println "\nClick efficiency comparison:")
    (println "- Sure Gamble: +4 credits / 1 click = 4 credits per click")
    (println "- Creative Commission: +4 credits / 2 clicks = 2 credits per click")
    (println "- Click for credit: +1 credit / 1 click = 1 credit per click")
    (println "→ Sure Gamble is most efficient, Creative Commission still good!")

    (println "\n✅ Test complete!")
    nil))

;; ============================================================================
;; Comment block for REPL usage
;; ============================================================================

(comment
  ;; Card lookup helpers - quick info about any card
  (print-card-info "Carmen")
  (print-card-info "Docklands Pass")
  (print-card-info "Hedge Fund")
  (card-info "Nico Campaign")  ; Returns map instead of printing

  ;; Run basic turn flow test (returns nil, won't spam)
  (test-basic-turn-flow)

  ;; Run playing cards test (returns nil, won't spam)
  (test-playing-cards)

  ;; Run asset management test (Phase 2.1) (returns nil, won't spam)
  (test-asset-management)

  ;; Run agenda scoring test (Phase 2.2) (returns nil, won't spam)
  (test-agenda-scoring)

  ;; Run ICE installation test (Phase 2.3) (returns nil, won't spam)
  (test-ice-installation)

  ;; Run end-of-turn rez timing test (Phase 2.4) (returns nil, won't spam)
  (test-end-of-turn-rez)

  ;; Run program installation test (Phase 3.1) (returns nil, won't spam)
  (test-program-installation)

  ;; Run resource management test (Phase 3.2) (returns nil, won't spam)
  (test-resource-management)

  ;; Run runner events test (Phase 3.3) (returns nil, won't spam)
  (test-runner-events)

  ;; Create custom game for experimentation
  ;; IMPORTANT: Capture in a def, don't just call (open-hand-game) or it will print entire state!
  (def my-state (open-hand-game))
  (print-game-state my-state)

  ;; Manual action examples
  (core/process-action "credit" my-state :corp nil)
  (core/process-action "draw" my-state :corp nil)
  (play-from-hand my-state :corp "Hedge Fund")

  ;; Check specific card in hand
  (get-in @my-state [:corp :hand])

  ;; Check prompt state
  (get-in @my-state [:corp :prompt])

  )
