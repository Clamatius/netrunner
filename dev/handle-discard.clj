(do
  (println "Checking for Corp discard prompt...")
  (let [gs (:game-state @ai-websocket-client-v2/client-state)
        prompt (get-in gs [:corp :prompt-state])
        hand (get-in gs [:corp :hand])]

    (if (and prompt (= "select" (:prompt-type prompt)))
      (do
        (println "DISCARD PROMPT FOUND!")
        (println "Message:" (:msg prompt))
        (println "Selectable cards:" (count (:selectable prompt)))
        (println "Hand size:" (count hand))
        (println "EID:" (:eid prompt))

        (println "\nCorp hand cards:")
        (doseq [[idx card] (map-indexed vector hand)]
          (println (format "  %d. %s (CID: %s)" idx (:title card) (:cid card))))

        ;; Need to discard 3 cards (8 in hand, max 5)
        (let [cards-to-discard (take 3 hand)
              eid (:eid prompt)]
          (println "\nDiscarding 3 cards:")
          (doseq [card cards-to-discard]
            (println "  -" (:title card)))

          ;; Discard first card using select command with full card object
          (let [first-card (first cards-to-discard)]
            (println "\nDiscarding card 1:" (:title first-card))
            (ai-websocket-client-v2/select-card! first-card eid)
            (Thread/sleep 2000))))

      (println "No select prompt found. Prompt:" prompt))))
