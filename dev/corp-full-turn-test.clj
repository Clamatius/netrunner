(do
  (println "=== FULL CORP TURN TEST ===")
  (println "This will: join as Corp, handle mulligan, take full turn with discard")

  ;; Step 1: Join the game
  (println "\n1. Joining game as Corp...")
  (let [gameid-uuid (java.util.UUID/fromString "3ccd6362-6182-4234-ba8b-b0992cb27673")]
    (ai-websocket-client-v2/send-message! :lobby/join
                                           {:gameid gameid-uuid
                                            :request-side "Corp"}))
  (Thread/sleep 3000)

  ;; Step 2: Check for mulligan prompt and keep hand
  (println "\n2. Checking for mulligan prompt...")
  (let [gs (:game-state @ai-websocket-client-v2/client-state)
        prompt (get-in gs [:corp :prompt-state])]
    (if (and prompt (= "Mulligan?" (:msg prompt)))
      (do
        (println "   Found mulligan prompt - keeping hand")
        (ai-websocket-client-v2/send-action! "choice" {:choice "Keep"})
        (Thread/sleep 2000))
      (println "   No mulligan prompt found")))

  ;; Step 3: Wait for game to start, then start turn
  (println "\n3. Waiting for game to start...")
  (Thread/sleep 3000)

  (println "\n4. Starting Corp turn...")
  (ai-websocket-client-v2/send-action! "start-turn" nil)
  (Thread/sleep 2000)

  (let [gs (:game-state @ai-websocket-client-v2/client-state)]
    (println "   Turn:" (:turn gs))
    (println "   Active:" (:active-player gs))
    (println "   Clicks:" (get-in gs [:corp :click]))
    (println "   Hand:" (get-in gs [:corp :hand-count])))

  ;; Step 5: Draw 2 cards (uses 2 clicks, gets us to 8 cards)
  (println "\n5. Drawing 2 cards...")
  (println "   Click 1: Draw")
  (ai-websocket-client-v2/send-action! "draw" nil)
  (Thread/sleep 2000)

  (println "   Click 2: Draw")
  (ai-websocket-client-v2/send-action! "draw" nil)
  (Thread/sleep 2000)

  (let [gs (:game-state @ai-websocket-client-v2/client-state)]
    (println "   Hand count:" (get-in gs [:corp :hand-count]))
    (println "   Clicks remaining:" (get-in gs [:corp :click])))

  ;; Step 6: Take credit with final click
  (println "\n6. Taking credit with final click...")
  (ai-websocket-client-v2/send-action! "credit" nil)
  (Thread/sleep 2000)

  (let [gs (:game-state @ai-websocket-client-v2/client-state)]
    (println "   Credits:" (get-in gs [:corp :credit]))
    (println "   Clicks:" (get-in gs [:corp :click])))

  ;; Step 7: End turn (should trigger discard)
  (println "\n7. Ending turn (should trigger discard prompt)...")
  (ai-websocket-client-v2/send-action! "end-turn" nil)
  (Thread/sleep 3000)

  ;; Step 8: Check for and handle discard prompt
  (println "\n8. Checking for discard prompt...")
  (let [gs (:game-state @ai-websocket-client-v2/client-state)
        prompt (get-in gs [:corp :prompt-state])
        hand (get-in gs [:corp :hand])]

    (if (and prompt (= "select" (:prompt-type prompt)))
      (do
        (println "   DISCARD PROMPT FOUND!")
        (println "   Message:" (:msg prompt))
        (println "   Hand size:" (count hand))
        (println "   Need to discard:" (- (count hand) 5))

        ;; Discard first 3 cards using the working format
        (let [cards-to-discard (take 3 hand)
              eid (:eid prompt)]
          (println "\n   Discarding 3 cards:")
          (doseq [[idx card] (map-indexed vector cards-to-discard)]
            (println (format "     %d. %s (CID: %s)" (inc idx) (:title card) (:cid card))))

          ;; Select each card
          (doseq [[idx card] (map-indexed vector cards-to-discard)]
            (println (format "\n   Selecting card %d: %s" (inc idx) (:title card)))
            (ai-websocket-client-v2/send-action! "select"
                                                 {:card {:cid (:cid card)
                                                        :zone (:zone card)
                                                        :side (:side card)
                                                        :type (:type card)}
                                                  :eid eid
                                                  :shift-key-held false})
            (Thread/sleep 2000))

          (println "\n   ✅ All cards selected - discard should auto-complete")))
      (println "   No discard prompt found. Prompt:" prompt)))

  (println "\n=== TEST COMPLETE ===")
  (println "Check game state to verify turn completed properly"))
