#!/bin/bash
# Test 2: Join game and handle mulligan

if [ -z "$1" ]; then
  echo "Usage: $0 <gameid> [side]"
  echo "  gameid: UUID of the game to join"
  echo "  side: 'Corp' or 'Runner' (default: Corp)"
  exit 1
fi

GAMEID="$1"
SIDE="${2:-Corp}"

./dev/src/clj/nrepl-eval.sh "
(do
  (println \"=== TEST 2: Join Game and Mulligan ===\n\")
  (println \"Game ID: $GAMEID\")
  (println \"Side: $SIDE\n\")

  ;; Load websocket client
  (load-file \"dev/src/clj/ai_websocket_client_v2.clj\")

  ;; Connect
  (ai-websocket-client-v2/connect! \"ws://localhost:1042/chsk\")
  (Thread/sleep 2000)

  (if (not (ai-websocket-client-v2/connected?))
    (do
      (println \"❌ Failed to connect\")
      (System/exit 1))
    (println \"✅ Connected\"))

  ;; Join game
  (println \"\\nJoining game...\")
  (ai-websocket-client-v2/join-game!
    {:gameid \"$GAMEID\"
     :side \"$SIDE\"})
  (Thread/sleep 3000)

  ;; Check if we joined
  (let [gs (ai-websocket-client-v2/get-game-state)]
    (if gs
      (do
        (println \"✅ Joined game:\")
        (println \"   GameID:\" (:gameid gs))
        (println \"   Waiting for game to start...\"))
      (do
        (println \"❌ Failed to join game\")
        (System/exit 1))))

  ;; Wait for game start and mulligan prompt
  (println \"\\nWaiting for mulligan prompt...\")
  (loop [checks 0]
    (when (< checks 20)
      (Thread/sleep 2000)
      (let [gs (ai-websocket-client-v2/get-game-state)
            side-key (keyword (clojure.string/lower-case \"$SIDE\"))
            prompt (get-in gs [side-key :prompt-state])]

        (println (format \"[Check %d] Turn: %s | Active: %s\"
                        (inc checks)
                        (:turn gs)
                        (:active-player gs)))

        (when prompt
          (println \"\\n🔔 PROMPT DETECTED:\")
          (println \"   Message:\" (:msg prompt))
          (println \"   Type:\" (:prompt-type prompt))

          ;; Handle mulligan (button prompt with Keep/Mulligan choices)
          (when (= \"button\" (:prompt-type prompt))
            (let [choices (:choices prompt)
                  keep-choice (first (filter #(= \"Keep\" (:value %)) choices))]
              (if keep-choice
                (do
                  (println \"\\n✅ Keeping hand\")
                  (ai-websocket-client-v2/send-action!
                    \"choice\"
                    {:choice {:uuid (:uuid keep-choice)}})
                  (Thread/sleep 2000)
                  (println \"\\nMulligan complete!\")
                  (ai-websocket-client-v2/show-status)
                  (System/exit 0))
                (println \"⚠️  No 'Keep' choice found\"))))

          ;; If we see other prompts, show them
          (when (not= \"button\" (:prompt-type prompt))
            (println \"   Choices:\" (count (:choices prompt)))
            (doseq [[idx choice] (map-indexed vector (:choices prompt))]
              (println (format \"     %d. %s\" idx (:value choice))))))

        (recur (inc checks)))))

  (println \"\\n⏱️  Timeout waiting for mulligan prompt\")
  (System/exit 1))"
