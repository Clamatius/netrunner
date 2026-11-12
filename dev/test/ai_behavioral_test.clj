(ns ai-behavioral-test
  "Behavioral tests - validate AI through real game API + log parsing

   These tests run against actual game server, validating end-to-end behavior
   through command execution and game log verification. They are:
   - Slow (10-15s per test, requires reset.sh)
   - Resilient to upstream Jinteki internal changes
   - Comprehensive (validate entire interaction flows, not single assertions)

   These TAKE OVER the client REPLs and a live game. The target's reset.sh
   restarts the two singleton client REPLs rooted in the target tree and starts a
   fresh game, so running these destroys whatever game is in progress and leaves
   both clients on the target's code.

   It does NOT restart the game server PROCESS on 7888 — nothing in reset.sh or
   ai-bounce.sh touches it — though reset.sh does drive that server, via
   ai-self-play.sh's leave-game / create-game / start-game. So a target that is
   not the server's own checkout leaves target-tree clients talking to another
   tree's engine. Scoped this precisely because two drafts of this comment
   overstated it and two review rounds measured both as false.

   One tree supplies everything: the scripts, and the EDN test definitions. By
   default it is the checkout you invoke them from; NETRUNNER_BEHAVIORAL_DIR
   names another. See resolve-target-dir and #317 — a hard-coded path here
   previously handed all of this to a stale third tree without saying so.

   Usage:
     make test-behavioral          - Run all behavioral tests
     lein test ai-behavioral-test  - Run directly"
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def test-definitions-path "dev/test/behavioral/continue_run_tests.edn")

;; ============================================================================
;; Which checkout do we drive? (#317)
;; ============================================================================

(def target-dir-env
  "Env var naming the checkout whose ./dev scripts these tests drive."
  "NETRUNNER_BEHAVIORAL_DIR")

(def required-scripts
  "What every shell-out below invokes. A target without these is not a checkout
   we can drive, so it is named in the failure rather than run."
  ["dev/send_command" "dev/reset.sh"])

(defn resolve-target-dir
  "The checkout these tests take over and drive.

   This replaces an absolute home-directory path that sat at all three shell-out
   sites (the literal is quoted in #317, not here). That literal was a THIRD
   tree: not the caller's, and stale — HEAD 7a6c2752a, last touched 2026-10-23,
   while ai-player/main was 125a73b04. Nothing in the literal said which tree it
   named, so nothing reported it (#317).

   What that actually did — corrected by a review seat, which read the chain I
   had only assumed: `run-behavioral-test!` calls `reset-game!` FIRST, and
   reset.sh reaches start-ai-client-repl.sh through $SCRIPT_DIR, which does
   `cd \"$SCRIPT_DIR/..\"` and reloads ai_client_init.clj from there. So the
   target tree does not merely receive commands: its reset.sh RESTARTS the two
   singleton client REPLs rooted in itself. The old literal therefore put both
   clients on Oct-23 code and left them there, and destroyed whatever game was in
   progress. It is a takeover, not a mis-send.

   Scoped twice by review, because I overstated it twice. The game server PROCESS
   on 7888 is not restarted — nothing in reset.sh or ai-bounce.sh touches it —
   but reset.sh does drive it: ai-self-play.sh sends it leave-game, create-game
   and start-game. And the chain is not purely $SCRIPT_DIR-relative:
   ai-self-play.sh calls `./dev/send_command`, which is relative to the process's
   working directory. That resolves to the target here only because the
   shell/sh :dir below supplies it.

   Hence `target-dir` is 'the checkout we take over', and the refusal says so.
   `env-value` wins when set and non-blank; otherwise the checkout the test was
   invoked from, which is what `make test-behavioral` reads as. A target missing
   `required-scripts` throws, naming the directory and the env var.

   NOT settled here: whether these tests may take the REPLs at all. They are
   singletons on fixed ports with no ownership protocol, so any tree's reset.sh
   can claim them from whoever was using them — that is #294/#292's gap, and
   choosing the policy is the owner's call (#317). Asking which tree the REPLs
   are 'rooted in' is the wrong question: after the first reset the answer is
   always the target."
  [env-value cwd]
  (let [from-env (not-empty (some-> env-value str/trim))
        dir (or from-env cwd)
        missing (vec (remove #(.exists (io/file dir %)) required-scripts))]
    (when (seq missing)
      (throw (ex-info (str "Behavioral target " (pr-str dir) " is missing "
                           (str/join " and " missing)
                           " — set " target-dir-env
                           " to the checkout you want these tests to take over."
                           " (Its reset.sh restarts both client REPLs rooted in"
                           " itself and starts a fresh game. The server PROCESS on"
                           " 7888 is not restarted, though reset.sh does"
                           " drive it.)")
                      {:dir dir
                       :missing missing
                       :source (if from-env :env :cwd)})))
    dir))

(def target-dir
  "Resolved once, on first use. A delay so that merely loading this
   namespace — which `make test` does, to reach the tests for the resolver —
   cannot throw.

   It ANNOUNCES the target, and whether it came from the env var. A review seat
   pointed out that the success path was otherwise as silent as the literal it
   replaced: an exported NETRUNNER_BEHAVIORAL_DIR naming a drivable but stale
   tree would take that tree over while printing nothing but 'Resetting game
   environment...'. The refusal path named the directory; the path that actually
   does something did not."
  (delay
   (let [env-value (System/getenv target-dir-env)
         dir (resolve-target-dir env-value (System/getProperty "user.dir"))]
     (println (str "  🎯 Taking over checkout: " dir
                   (if (not-empty (some-> env-value str/trim))
                     (str " (from " target-dir-env ")")
                     " (the checkout this ran from)")))
     (println "     Its reset.sh restarts both client REPLs and starts a fresh game.")
     dir)))

(defn read-test-definitions
  "Read the scenario/action definitions FROM THE TARGET TREE.

   This used to slurp a path relative to the JVM's cwd while the commands it
   produced were sent to `target-dir`. With the env var set, one run then mixed
   two trees: this tree's scenarios driven against that tree's dispatcher, so
   command-shape drift between them surfaced as behavioral failures rather than
   as a configuration mistake. Review finding; the namespace docstring's claim
   that these act on one checkout is now true of the definitions too."
  []
  (-> (io/file @target-dir test-definitions-path)
      slurp
      edn/read-string))

(defn run-command!
  "Execute a command via ./dev/send_command, return output"
  [side cmd]
  (let [;; Split command into parts for shell args (e.g. "end-turn --force" -> ["end-turn" "--force"])
        cmd-parts (str/split cmd #"\s+")
        ;; Build full command: ["./dev/send_command" "corp" "end-turn" "--force"]
        full-cmd (concat ["./dev/send_command" side] cmd-parts)
        result (apply shell/sh (concat full-cmd [:dir @target-dir]))]
    (when (not= 0 (:exit result))
      (throw (ex-info (str "Command failed: " side " " cmd)
                      {:side side :cmd cmd :result result})))
    (:out result)))

(defn reset-game!
  "Run reset.sh to get fresh game, returns log output"
  []
  (println "  🔄 Resetting game environment...")
  (let [result (shell/sh "./dev/reset.sh" :dir @target-dir)]
    (when (not= 0 (:exit result))
      (throw (ex-info "reset.sh failed" {:result result})))
    (:out result)))

(defn get-game-log
  "Fetch current game log for a side"
  [side]
  (let [result (shell/sh "./dev/send_command" side "status"
                         :dir @target-dir)]
    (:out result)))

(defn execute-scenario!
  "Execute scenario setup commands, return accumulated log"
  [scenario scenarios]
  (let [scenario-def (get scenarios scenario)
        setup (:setup scenario-def)]
    (println (str "  📋 Scenario: " (:desc scenario-def)))
    (doseq [cmd setup]
      (let [[side command] (first cmd)]
        (println (str "    → " (name side) ": " command))
        (run-command! (name side) command)))
    ;; Return current log state after setup
    (get-game-log "runner")))

(defn execute-test-actions!
  "Execute test action commands, return final log"
  [actions]
  (doseq [cmd actions]
    (let [[side command] (first cmd)]
      (println (str "    ▶ " (name side) ": " command))
      (run-command! (name side) command)))
  ;; Return final log state
  (get-game-log "runner"))

(defn validate-log-contains
  "Check that log contains all expected patterns"
  [log patterns]
  (doseq [pattern patterns]
    (let [regex (re-pattern pattern)]
      (when-not (re-find regex log)
        (throw (ex-info (str "Expected log pattern not found: " pattern)
                        {:pattern pattern
                         :log log}))))))

(defn validate-log-count
  "Check that log contains expected count of pattern"
  [log {:keys [pattern count]}]
  (let [regex (re-pattern pattern)
        matches (re-seq regex log)
        actual-count (clojure.core/count matches)]
    (when (not= count actual-count)
      (throw (ex-info (str "Expected " count " matches of pattern '" pattern "', found " actual-count)
                      {:pattern pattern
                       :expected count
                       :actual actual-count
                       :matches matches})))))

(defn run-behavioral-test!
  "Execute a single behavioral test"
  [test-def scenarios]
  (let [{:keys [name desc scenario actions expect-log-contains expect-log-count timeout]} test-def]
    (println (str "\n🧪 Test: " name))
    (println (str "   " desc))

    ;; Reset game for clean state
    (reset-game!)

    ;; Execute scenario setup
    (execute-scenario! scenario scenarios)

    ;; Execute test actions
    (let [final-log (execute-test-actions! actions)]

      ;; Validate expectations
      (when expect-log-contains
        (println "  ✓ Validating log patterns...")
        (validate-log-contains final-log expect-log-contains))

      (when expect-log-count
        (println "  ✓ Validating pattern counts...")
        (validate-log-count final-log expect-log-count))

      (println "  ✅ PASS"))))

(deftest behavioral-tests
  (testing "Behavioral tests from EDN definitions"
    (let [defs (read-test-definitions)
          scenarios (:scenarios defs)
          tests (:tests defs)]
      (doseq [test-def tests]
        (testing (:name test-def)
          (run-behavioral-test! test-def scenarios))))))

(defn -main
  "Run behavioral tests and report results"
  []
  (let [results (run-tests 'ai-behavioral-test)]
    (println "\n========================================")
    (println "Behavioral Test Summary")
    (println "========================================")
    (println "Tests run:" (:test results))
    (println "Assertions:" (:pass results))
    (println "Failures:" (:fail results))
    (println "Errors:" (:error results))
    (println "========================================\n")
    (when (or (pos? (:fail results)) (pos? (:error results)))
      (System/exit 1))))

(comment
  ;; Run all behavioral tests
  (run-tests 'ai-behavioral-test)

  ;; Run from main
  (-main)
  )
