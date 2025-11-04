;; Game command test: Direct game engine testing with fixed decks
;; This bypasses WebSocket layer and tests game actions directly
;; Allows deterministic games with known card positions for AI testing

(ns game-command-test
  (:require
   [game.core :as core]
   [game.core.card :refer [get-card]]
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
;; Test Setup Helpers
;; ============================================================================

(defn open-hand-game
  "Create a game with both players' hands visible for testing.
  Uses System Gateway beginner decks in fixed order.
  Starting hand is first 5 cards from deck."
  []
  (do-game
    (new-game {:corp {:deck gateway-beginner-corp-deck
                      :hand (take 5 gateway-beginner-corp-deck)}
               :runner {:deck gateway-beginner-runner-deck
                        :hand (take 5 gateway-beginner-runner-deck)}})
    state))

(defn custom-open-hand-game
  "Create a game with custom hands for both sides.
  Remaining deck cards are in specified order (no shuffling in tests)."
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
  (println "\n=== GAME STATE ===")
  (println "Corp:")
  (println "  Credits:" (:credit (get-corp)))
  (println "  Clicks:" (:click (get-corp)))
  (println "  Hand:" (mapv :title (:hand (get-corp))))
  (println "  Deck size:" (count (:deck (get-corp))))

  (println "\nRunner:")
  (println "  Credits:" (:credit (get-runner)))
  (println "  Clicks:" (:click (get-runner)))
  (println "  Hand:" (mapv :title (:hand (get-runner))))
  (println "  Deck size:" (count (:deck (get-runner)))))

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
    (take-credits state :corp)
    (print-game-state state)

    ;; Runner turn 1
    (println "\n--- Runner Turn 1 ---")
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
    (print-game-state state)

    state))

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
    (play-from-hand state :runner "Sure Gamble")
    (print-game-state state)

    ;; Runner installs Docklands Pass
    (println "\n--- Runner installs Docklands Pass ---")
    (play-from-hand state :runner "Docklands Pass")
    (print-board-state state)
    (print-game-state state)

    state))

;; ============================================================================
;; Comment block for REPL usage
;; ============================================================================

(comment
  ;; Run basic turn flow test
  (test-basic-turn-flow)

  ;; Run playing cards test
  (test-playing-cards)

  ;; Create custom game for experimentation
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
