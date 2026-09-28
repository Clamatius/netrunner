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
   extra executable one, which is the defect under test.

   *read-eval* is bound OFF. If escaping regressed and a payload carried `#=(…)`,
   the reader would EVALUATE it inside this JVM — the test would die or be quietly
   altered instead of failing with \"expected ONE form\". A guest seat found that."
  [s]
  (binding [*read-eval* false]
    (read-string (str "[" s "]"))))

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

;; ---------------------------------------------------------------------------
;; The enumeration. This is where the CLASS-CLOSURE claim lives now.
;;
;; Four review rounds in a row put their MAJORs on the source censuses in
;; clj_string_sweep_test.sh, and the last round found five more shapes they
;; cannot see: a bare positional (`$1`), a captured execute
;; (`R="$(execute "(f $ARG)")"`), a Clojure-escaped quote inside a string, a
;; single-quoted expression with shell concatenation (`execute '(f "'"$X"'")'`),
;; and the reuse of an already-whitelisted variable NAME in a new ungated arm.
;; Those are not one defect. They are five more members of an open set, because a
;; per-line text scan of shell source is being asked a DATA-FLOW question.
;;
;; So the claim moves to a mechanism that cannot have that failure mode: drive
;; EVERY command the dispatcher has with a payload argument and inspect what
;; comes OUT. Quoting style, accumulators, heredocs, printf, positionals,
;; captured executes and name reuse are all invisible to it, in the good sense —
;; it never looks at how the expression was built.
;;
;; The command list is read from send_command's own `case` arms, so a NEW arm is
;; covered the moment it is added; there is no table to forget to extend.
;; ---------------------------------------------------------------------------

(def ^:private marker "NRPAYLOAD")

;; Two payloads, because the class has two shapes. The first closes a Clojure
;; STRING and appends a form; the second needs no quote at all — it closes the
;; enclosing FORM, which is how `change`'s `:$KEY` was broken.
(def ^:private payloads
  {:string-breaking (str marker "\" (ai-actions/end-turn!) \"")
   :form-breaking   (str marker ") (ai-actions/end-turn!) (comment")})

(defn- dispatcher-commands
  "Every command label in send_command's main `case`, flattened over `a|b|c` arms.
   Read from the file, not from a list here: a new arm must be covered, and the
   only way to guarantee that is to take the dispatcher's own word for what exists."
  []
  (let [lines (str/split-lines (slurp send-command))
        ;; The main case runs from `case "$COMMAND" in` (the SECOND one; the
        ;; first is a two-line help shortcut) to its `esac`.
        starts (keep-indexed (fn [i l] (when (re-find #"^case \"\$COMMAND\" in" l) i)) lines)
        start  (second starts)
        end    (first (keep-indexed (fn [i l] (when (and (> i start) (= l "esac")) i)) lines))]
    (assert (and start end) "could not find send_command's main case statement")
    (->> (subvec (vec lines) start end)
         (keep #(second (re-find #"^    ([a-z][a-zA-Z0-9|?*_-]*)\)$" %)))
         (mapcat #(str/split % #"\|"))
         (remove #{"help"})
         distinct
         vec)))

(defn- payload-survived-whole?
  "Did `payload` arrive as ONE complete Clojure string?

   This is the invariant, and it took a wrong turn to find. `outside a string` is
   not enough: an arm emitting `(f \"PAYLOAD\")` with the string-breaking payload
   emits `(f \"NRPAYLOAD\" (ai-actions/end-turn!) \"\")` — ONE readable form, and
   the marker IS inside a string. What is wrong with it is that no string in it
   EQUALS the payload: the value was split across two strings with a call between
   them. So the test is identity of the value, not location of the marker."
  [expr payload]
  (let [forms (read-forms expr)
        strings (->> forms (mapcat #(tree-seq coll? seq %)) (filter string?))]
    (boolean (some #(= payload %) strings))))

(defn- marker-appears? [expr] (str/includes? expr marker))

;; The ONE command whose entire purpose is to send seat text as source. It is
;; NAMED, not pattern-matched, and `eval-really-does-send-source` below asserts it
;; still behaves that way — so this exception cannot go stale into a hole, and
;; adding a second one is a review decision rather than an edit.
(def ^:private source-by-design #{"eval"})

(deftest every-dispatcher-command-keeps-a-payload-argument-as-data
  (testing "no command turns a seat's argument into Clojure source"
    (let [commands (remove source-by-design (dispatcher-commands))]
      (is (> (count commands) 80)
          (str "only " (count commands) " commands found — the case extraction is broken, "
               "and a broken extraction reports a CLEAN sweep over nothing"))
      (doseq [cmd commands
              [shape payload] payloads]
        (let [{:keys [sent]} (with-stub "corp" [cmd payload payload])]
          (doseq [expr sent]
            (testing (str cmd " / " (name shape))
              ;; Each eval must be ONE form. A payload that closed the form would
              ;; make a second one, which is arbitrary code in the seat's REPL.
              (let [forms (try (read-forms expr)
                               (catch Exception e
                                 (is false (str cmd " / " (name shape)
                                                " emitted unreadable source: " (.getMessage e)
                                                "\n  " expr))
                                 nil))]
                (when forms
                  (is (= 1 (count forms))
                      (str cmd " / " (name shape) " emitted " (count forms)
                           " top-level forms — the extra ones are arbitrary code in "
                           "the seat's own REPL:\n  " expr))
                  ;; Only arms that actually USE the argument are asked to have
                  ;; kept it whole; one that ignores it (status, board, ping) never
                  ;; mentions the marker and has nothing to answer for.
                  (when (marker-appears? expr)
                    (is (payload-survived-whole? expr payload)
                        (str cmd " / " (name shape) " did not keep the argument as ONE "
                             "Clojure string — it reached the reader as source:\n  " expr))))))))))))

(deftest the-enumeration-can-actually-fail
  (testing "the invariant rejects every leak shape and accepts the correct one"
    (let [pay (:string-breaking payloads)
          ;; What an UNESCAPED site emits: the value split across two strings with
          ;; a call between them. Reads as ONE form, and the marker IS in a string
          ;; — which is why "marker outside a string" was the wrong invariant.
          broken (str "(f \"" marker "\" (ai-actions/end-turn!) \"\")")
          ;; What an ESCAPED site emits.
          good (str "(f " (pr-str pay) ")")]
      (is (= 1 (count (read-forms broken))) "the leak shape reads as one form (that is the trap)")
      (is (not (payload-survived-whole? broken pay))
          "the invariant must REJECT a value split across two strings")
      (is (payload-survived-whole? good pay)
          "the invariant must ACCEPT a correctly escaped value"))
    (let [pay (:form-breaking payloads)
          bare (str "(f :" marker ") (ai-actions/end-turn!)")]
      (is (= 2 (count (read-forms bare)))
          "a payload interpolated as a KEYWORD closes the form and makes two")
      (is (not (payload-survived-whole? bare pay))
          "and it never arrived as a string at all"))
    (testing "*read-eval* is off, so a #=(…) payload is data, not execution"
      (is (thrown? Exception (read-forms "#=(inc 1)"))))))

(deftest eval-really-does-send-source
  (testing "the one exception is witnessed, so it cannot go stale into a hole"
    ;; If `eval` were ever changed to quote its argument, this fails and the
    ;; exception above must be removed — an unused exception silently exempts an
    ;; arm nobody reviewed for it.
    (let [{:keys [sent]} (with-stub "corp" ["eval" (:string-breaking payloads)])]
      (is (= 1 (count sent)) "eval sends exactly one expression")
      (is (str/includes? (first sent) marker))
      (is (not (payload-survived-whole? (first sent) (:string-breaking payloads)))
          (str "eval no longer sends its argument as raw source — it sent: "
               (pr-str (first sent)) " — so `source-by-design` is stale and must go")))))
