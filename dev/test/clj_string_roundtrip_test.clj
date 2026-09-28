(ns clj-string-roundtrip-test
  "#255: does the expression send_command emits actually READ as one form, with
   the seat's argument as DATA?

   send_command_clj_string_test.sh has asked that question since #110, with a
   reader round trip — and it has never once answered it. The check is guarded by
   `command -v clojure`, and there is no `clojure`/`clj` CLI on the dev box (only
   `lein`), so every run of `make verify` has printed `skip [reader-round-trip]`
   and the only witness left was a shape regex. A design seat found that by
   running `command -v` rather than reading the test. So the round trip moves
   here, into the suite `make verify` does run.

   And it is arity-correct now. `read-string` returns the FIRST form and silently
   discards the rest, so a log holding
   `(ai-actions/run! \"HQ\") (ai-actions/end-turn!)`
   passes a single `read-string` while containing an extra executable form —
   which is exactly the defect #255 is about. Every expression here is read as
   `[ ... ]` and asserted to hold EXACTLY ONE form.

   This drives the real dispatcher through a fake eval backend, so no REPL, no
   server and no game are involved. What it does NOT cover: whether the named
   Clojure var exists (#249), or whether the engine accepts the value."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private send-command
  (let [f (io/file "dev/send_command")]
    (when (.exists f) (.getAbsolutePath f))))

(def ^:private stub-source
  ;; Same contract as the shell route test's stub: log what it is not there to
  ;; answer, answer the connection preflight, fail closed on anything else.
  (str "#!/usr/bin/env bash\n"
       "if [[ \"${1:-}\" == \"--stdin\" ]]; then\n"
       "    expr=\"$(cat)\"\n"
       ;; A record separator, NOT a newline: create-game and tank emit MULTILINE
       ;; expressions, and splitting the log on newlines cut them into fragments
       ;; that the reader then rejected with "Unmatched delimiter".
       "    printf '%s\\036' \"$expr\" >> \"$ACTION_LOG\"\n"
       "    printf 'nil\\n'; exit 0\n"
       "fi\n"
       "expr=\"${!#}\"\n"
       "case \"$expr\" in\n"
       "    *sync-verdict!*) printf '\"SYNC-VERDICT in-sync\"\\n' ;;\n"
       "    *ensure-connected!*) printf '\"ok\"\\n' ;;\n"
       "    *) printf 'nil\\n' ;;\n"
       "esac\n"))

(defn- with-stub
  "Run send_command with a fake eval backend; return the expressions it sent."
  [side args]
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir")
                           (str "nr-clj-rt-" (System/currentTimeMillis) "-" (rand-int 100000)))
              .mkdirs)
        stub (io/file dir "eval")
        log  (io/file dir "actions.log")]
    (spit stub stub-source)
    (.setExecutable stub true)
    (spit log "")
    (let [res (apply sh/sh send-command side
                     (concat args
                             [:env {"PATH" (System/getenv "PATH")
                                    "HOME" (System/getenv "HOME")
                                    "AI_EVAL" (.getAbsolutePath stub)
                                    "ACTION_LOG" (.getAbsolutePath log)
                                    "HEARTBEAT_DIR" (.getAbsolutePath (io/file dir "hb"))
                                    "SEND_COMMAND_LOG" (.getAbsolutePath (io/file dir "commands.log"))
                                    "NR_NO_AUTO_PROMPT" "1"
                                    "SHOW_LAST_LOG" "0"}]))
          sent (->> (str/split (slurp log) #"\u001e") (remove str/blank?) vec)]
      {:exit (:exit res) :out (:out res) :sent sent})))

(defn- read-forms
  "Read EVERY form in s. `read-string` would return only the first and hide an
   extra executable one, which is the defect under test."
  [s]
  (read-string (str "[" s "]")))

(defn- sole-form
  "The one form s must consist of. Fails loudly if s holds more than one."
  [s]
  (let [forms (read-forms s)]
    (is (= 1 (count forms))
        (str "expected ONE form, got " (count forms) " — an extra top-level form "
             "is arbitrary code in the seat's own REPL: " (pr-str s)))
    (first forms)))

(def ^:private payload "--x\" (ai-actions/end-turn!) \"")
(def ^:private lady "Cerberus \"Lady\" H1")

(deftest send-command-is-present
  (is (some? send-command)
      "dev/send_command not found — run lein from the repo root"))

(deftest a-quoted-flag-reads-as-one-string-argument
  (testing "the flag arms, which interpolated \\\"$1\\\" raw before #255"
    (doseq [[label args expected-fn]
            [["run"          ["run" "HQ" payload]    'ai-actions/run!]
             ["monitor-run"  ["monitor-run" payload]  'ai-actions/monitor-run!]
             ["continue"     ["continue" payload]     'ai-actions/monitor-run!]
             ["continue-run" ["continue-run" payload] 'ai-actions/continue-run!]]]
      (testing label
        (let [{:keys [sent]} (with-stub "runner" args)
              form (sole-form (first sent))]
          (is (list? form))
          (is (= expected-fn (first form)))
          ;; The payload is the LAST argument and is a STRING whose value is the
          ;; payload verbatim — not a string plus a list plus a string.
          (is (every? string? (rest form))
              (str "an argument is not a string: " (pr-str form)))
          (is (= payload (last form))
              (str "the flag's VALUE changed in transit: " (pr-str form))))))))

(deftest a-real-quoted-card-title-reads-as-one-string-argument
  (testing "discard and multi-choose by name, built through an accumulator"
    (let [{:keys [sent]} (with-stub "runner" ["multi-choose" lady])
          form (sole-form (first sent))]
      (is (= 'ai-actions/multi-choose! (first form)))
      (is (= [lady] (vec (rest form)))))
    (let [{:keys [sent]} (with-stub "runner" ["discard" lady "Sure Gamble"])
          form (sole-form (first sent))]
      (is (= 'ai-actions/discard-by-names! (first form)))
      (is (= [[lady "Sure Gamble"]] (vec (rest form)))
          "the names must arrive as a vector of two strings"))))

(deftest a-multiline-form-reads-as-one-form
  (testing "create-game's lobby map, where four positionals sit inside one form"
    (let [{:keys [sent]} (with-stub "corp" ["create-game" payload payload payload payload])
          form (sole-form (first sent))
          ;; (do (require ...) (conn/create-lobby! {...}) nil)
          lobby (->> (tree-seq coll? seq form) (filter map?) first)]
      (is (= 'do (first form)))
      (is (map? lobby) "no lobby map in the emitted form")
      (doseq [k [:title :side :gateway-type :precon]]
        (is (= payload (get lobby k))
            (str k " did not arrive as the payload string: " (pr-str (get lobby k))))))))

(deftest a-trailing-backslash-reads-as-one-form
  (testing "tank, whose own escaping spelling dropped it before #255"
    (let [{:keys [sent]} (with-stub "corp" ["tank" "Ice Wall\\"])
          form (sole-form (first sent))
          names (->> (tree-seq coll? seq form) (filter string?) set)]
      (is (= 'do (first form)))
      (is (contains? names "Ice Wall\\")
          (str "the ICE name lost its backslash: " (pr-str names))))))

(deftest a-bare-keyword-argument-is-refused-rather-than-read-as-code
  (testing "change's $KEY is interpolated bare, so it is gated by shape"
    (let [{:keys [exit sent]} (with-stub "corp" ["change" "credit) (ai-actions/end-turn!) (comment" "5"])]
      (is (= 1 exit) "a malformed state key must be refused")
      (is (empty? sent) "nothing may be sent for a refused key"))
    (let [{:keys [sent]} (with-stub "corp" ["change" "credit" "5"])
          form (sole-form (first sent))]
      (is (= '(ai-actions/change! :credit 5) form)))))

(deftest correct-input-still-reads-as-what-it-always-did
  (testing "no behaviour change for the arguments a seat actually types"
    (doseq [[args expected]
            [[["run" "HQ"]                    '(ai-actions/run! "HQ")]
             [["run" "HQ" "--patient"]        '(ai-actions/run! "HQ" "--patient")]
             [["monitor-run"]                 '(ai-actions/monitor-run!)]
             [["continue-run"]                '(ai-actions/continue-run!)]
             [["play" "Sure Gamble"]          '(ai-actions/play-card! "Sure Gamble")]
             [["multi-choose" "0" "2"]        '(ai-actions/multi-choose! 0 2)]]]
      (let [{:keys [sent]} (with-stub "runner" args)]
        (is (= expected (sole-form (first sent)))
            (str "emitted form changed for " (pr-str args)))))))

(deftest the-reader-check-can-actually-fail
  (testing "a two-form expression must NOT pass as one form"
    ;; Mutation test on the harness itself. If read-forms were read-string, this
    ;; would report ONE form and every assertion above would be decorative.
    (is (= 2 (count (read-forms "(ai-actions/run! \"HQ\") (ai-actions/end-turn!)")))
        "read-forms collapsed two top-level forms into one")
    (is (= 1 (count (read-forms "(ai-actions/run! \"HQ\" \"--x\\\" (ai-actions/end-turn!) \\\"\")")))
        "a correctly escaped payload must read as ONE form")))
