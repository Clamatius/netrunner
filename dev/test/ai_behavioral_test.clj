(ns ai-behavioral-test
  "Behavioral tests - validate AI through real game API + log parsing

   These tests run against actual game server, validating end-to-end behavior
   through command execution and game log verification. They are:
   - Slow (10-15s per test, requires reset.sh)
   - Resilient to upstream Jinteki internal changes
   - Comprehensive (validate entire interaction flows, not single assertions)

   Usage:
     make test-behavioral          - Run all behavioral tests
     lein test ai-behavioral-test  - Run directly"
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def test-definitions-path "dev/test/behavioral/continue_run_tests.edn")

(defn read-test-definitions []
  (-> test-definitions-path
      slurp
      edn/read-string))

(defn run-command!
  "Execute a command via ./dev/send_command, return output"
  [side cmd]
  (let [;; Split command into parts for shell args (e.g. "end-turn --force" -> ["end-turn" "--force"])
        cmd-parts (str/split cmd #"\s+")
        ;; Build full command: ["./dev/send_command" "corp" "end-turn" "--force"]
        full-cmd (concat ["./dev/send_command" side] cmd-parts)
        result (apply shell/sh (concat full-cmd [:dir "/Users/mcooper/workspace/netrunner"]))]
    (when (not= 0 (:exit result))
      (throw (ex-info (str "Command failed: " side " " cmd)
                      {:side side :cmd cmd :result result})))
    (:out result)))

(defn reset-game!
  "Run reset.sh to get fresh game, returns log output"
  []
  (println "  🔄 Resetting game environment...")
  (let [result (shell/sh "./dev/reset.sh" :dir "/Users/mcooper/workspace/netrunner")]
    (when (not= 0 (:exit result))
      (throw (ex-info "reset.sh failed" {:result result})))
    (:out result)))

(defn get-game-log
  "Fetch current game log for a side"
  [side]
  (let [result (shell/sh "./dev/send_command" side "status"
                         :dir "/Users/mcooper/workspace/netrunner")]
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
