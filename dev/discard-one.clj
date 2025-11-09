(do
  (let [state @ai-websocket-client-v2/client-state
        side (:side state)
        hand (get-in state [:game-state (keyword side) :hand])
        prompt (get-in state [:game-state (keyword side) :prompt-state])
        eid (:eid prompt)
        card-to-discard (last hand)]
    (println "Discarding:" (:title card-to-discard))
    (ai-websocket-client-v2/select-card! card-to-discard eid)
    (Thread/sleep 2000)
    (println "✅ Card discarded")))
