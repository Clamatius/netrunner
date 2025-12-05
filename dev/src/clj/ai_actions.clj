(ns ai-actions
  "High-level AI player actions - facade that re-exports functions from modular files

   This file has been refactored into 7 themed modules for better maintainability:
   - ai-core: Shared utilities and helpers
   - ai-connection: Lobby and game connection management
   - ai-display: Read-only information display
   - ai-basic-actions: Turn management and basic game actions
   - ai-prompts: Choice handling, mulligan, discard
   - ai-card-actions: Card manipulation (play, install, abilities, rez, etc.)
   - ai-runs: Run initiation and automation

   This facade maintains backward compatibility by re-exporting all functions."
  (:require [ai-core :as core]
            [ai-connection :as connection]
            [ai-display :as display]
            [ai-basic-actions :as basic]
            [ai-prompts :as prompts]
            [ai-card-actions :as cards]
            [ai-runs :as runs]))

;; ============================================================================
;; Re-exported functions from ai-core
;; ============================================================================

(def side= core/side=)
(def load-cards-from-api! core/load-cards-from-api!)
(def get-log-size core/get-log-size)
(def verify-new-log-entry core/verify-new-log-entry)
(def verify-action-in-log core/verify-action-in-log)
(def parse-card-reference core/parse-card-reference)
(def format-card-name-with-index core/format-card-name-with-index)
(def find-card-in-hand core/find-card-in-hand)
(def create-card-ref core/create-card-ref)
(def find-installed-card core/find-installed-card)
(def find-installed-corp-card core/find-installed-corp-card)
(def normalize-server-name core/normalize-server-name)
(def show-before-after core/show-before-after)
(def show-turn-indicator core/show-turn-indicator)
(def capture-state-snapshot core/capture-state-snapshot)
(def show-state-diff core/show-state-diff)
(def wait-for-prompt core/wait-for-prompt)
(def wait-for-diff core/wait-for-diff)
(def wait-for-relevant-diff core/wait-for-relevant-diff)
(def wait-for-log-past core/wait-for-log-past)
(def other-side core/other-side)

;; ============================================================================
;; Re-exported functions from ai-connection
;; ============================================================================

(def create-lobby! connection/create-lobby!)
(def list-lobbies connection/list-lobbies)
(def connect-game! connection/connect-game!)
(def resync-game! connection/resync-game!)
(def lobby-ready-to-start? connection/lobby-ready-to-start?)
(def auto-start-if-ready! connection/auto-start-if-ready!)
(def send-chat! connection/send-chat!)
(def change! connection/change!)
(def find-our-game connection/find-our-game)
(def verify-in-game! connection/verify-in-game!)
(def ensure-synced! connection/ensure-synced!)

;; ============================================================================
;; Re-exported functions from ai-display
;; ============================================================================

(def status display/status)
(def show-board display/show-board)
(def show-board-compact display/show-board-compact)
(def show-log display/show-log)
(def show-log-compact display/show-log-compact)
(def status-compact display/status-compact)
(def board-compact display/board-compact)
(def wait-for-my-turn display/wait-for-my-turn)
(def wait-for-run display/wait-for-run)
(def show-prompt display/show-prompt)
(def hand display/hand)
(def show-hand display/show-hand)
(def show-credits display/show-credits)
(def show-clicks display/show-clicks)
(def show-archives display/show-archives)
(def show-prompt-detailed display/show-prompt-detailed)
(def show-card-text display/show-card-text)
(def show-cards display/show-cards)
(def show-hand-cards display/show-hand-cards)
(def show-card-abilities display/show-card-abilities)
(def simple-corp-turn display/simple-corp-turn)
(def simple-runner-turn display/simple-runner-turn)
(def inspect-state display/inspect-state)
(def inspect-prompt display/inspect-prompt)
(def list-playables display/list-playables)
(def help display/help)

;; ============================================================================
;; Re-exported functions from ai-basic-actions
;; ============================================================================

(def start-turn! basic/start-turn!)
(def indicate-action! basic/indicate-action!)
(def take-credit! basic/take-credit!)
(def draw-card! basic/draw-card!)
(def end-turn! basic/end-turn!)
(def check-auto-end-turn! basic/check-auto-end-turn!)
(def smart-end-turn! basic/smart-end-turn!)
(def take-credits basic/take-credits)
(def draw-card basic/draw-card)
(def end-turn basic/end-turn)

;; ============================================================================
;; Re-exported functions from ai-prompts
;; ============================================================================

(def choose! prompts/choose!)
(def choose-option! prompts/choose-option!)
(def choose-by-value! prompts/choose-by-value!)
(def choose-card! prompts/choose-card!)
(def keep-hand prompts/keep-hand)
(def mulligan prompts/mulligan)
(def auto-keep-mulligan prompts/auto-keep-mulligan)
(def discard-to-hand-size! prompts/discard-to-hand-size!)
(def discard-specific-cards! prompts/discard-specific-cards!)
(def discard-by-names! prompts/discard-by-names!)

;; ============================================================================
;; Re-exported functions from ai-card-actions
;; ============================================================================

(def play-card! cards/play-card!)
(def install-card! cards/install-card!)
(def use-ability! cards/use-ability!)
(def use-runner-ability! cards/use-runner-ability!)
(def trash-installed! cards/trash-installed!)
(def rez-card! cards/rez-card!)
(def let-subs-fire! cards/let-subs-fire!)
(def toggle-auto-no-action! cards/toggle-auto-no-action!)
(def fire-unbroken-subs! cards/fire-unbroken-subs!)
(def advance-card! cards/advance-card!)
(def score-agenda! cards/score-agenda!)

;; ============================================================================
;; Re-exported functions from ai-runs
;; ============================================================================

(def run! runs/run!)
(def get-current-ice runs/get-current-ice)
(def get-rez-event runs/get-rez-event)
(def opponent-indicated-action? runs/opponent-indicated-action?)
(def has-real-decision? runs/has-real-decision?)
(def corp-has-rez-opportunity? runs/corp-has-rez-opportunity?)
(def waiting-for-opponent? runs/waiting-for-opponent?)
(def waiting-reason runs/waiting-reason)
(def can-auto-continue? runs/can-auto-continue?)
(def continue-run! runs/continue-run!)
(def continue! runs/continue!)
(def auto-continue-loop! runs/auto-continue-loop!)
(def monitor-run! runs/monitor-run!)
