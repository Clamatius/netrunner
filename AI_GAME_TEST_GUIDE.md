# AI vs AI Game Testing Guide

## Quick Start

1. **Start REPL**:
   ```bash
   lein repl
   ```

2. **Load the full game test**:
   ```clojure
   (load-file "dev/src/clj/full_game_test.clj")
   ```

3. **Run the test**:
   ```clojure
   (full-game-test/run-full-game-test!)
   ```

4. **Check prompts**:
   ```clojure
   (full-game-test/check-prompts)
   ```

5. **Inspect state**:
   ```clojure
   @full-game-test/clients
   (get-in @full-game-test/clients [:corp :game-state])
   (get-in @full-game-test/clients [:runner :game-state])
   ```

6. **Handle mulligans**:
   ```clojure
   ;; Corp keeps
   (let [gs (get-in @full-game-test/clients [:corp :game-state])
         prompt (get-in gs [:corp :prompt-state])
         keep (first (filter #(= "Keep" (:value %)) (:choices prompt)))
         eid (get-in prompt [:eid :eid])
         uuid (java.util.UUID/fromString (:uuid keep))
         gameid (get-in @full-game-test/clients [:corp :gameid])]
     (full-game-test/send! :corp :game/action
                           {:gameid (java.util.UUID/fromString gameid)
                            :command "choice"
                            :args {:eid eid :choice {:uuid uuid}}}))

   ;; Same for Runner
   ```

7. **Clean up when done**:
   ```clojure
   (doseq [name [:corp :runner]]
     (when-let [socket (get-in @full-game-test/clients [name :socket])]
       (.close socket)))
   (reset! full-game-test/clients {})
   ```

## Benefits

- ✅ Both clients in same process - no browser needed
- ✅ Fast iteration - just reload and run
- ✅ Full control - inspect state anytime
- ✅ Discover commands - see all prompts from both sides
- ✅ No disconnect-on-error issues - just restart

## Next Steps

Once you can handle mulligans for both sides:
1. Implement turn start for Corp
2. Execute basic actions
3. End turn
4. Handle Runner turn
5. Discover all necessary commands organically
