(ns check-ai-ns-order-test
  "`make check`'s warm path must not compile a namespace against a STALE dependency.

   WHAT SHIPPED
   check-ai.sh's fast path reloads a hand-maintained list of namespaces inside the
   long-lived port-7889 REPL:

       for ns in \"${AI_NAMESPACES[@]}\"; do ... (require '$ns :reload) ...

   `:reload` is SHALLOW. It recompiles that one namespace against whatever its
   dependencies happen to be in that REPL's memory right now. So if the list
   names a dependent BEFORE the dependency it requires, the dependent is compiled
   against the previous session's copy of it, and the gate answers about a tree
   that exists nowhere on disk. The list's own comment says `order matters for
   dependencies`, and it was violated by fourteen dependency arcs spanning seven
   of the entries — including all four `ai-run-*` namespaces that `ai-runs`
   requires.

   WHAT ORDER DOES AND DOES NOT FIX — measured, not reasoned
   Both directions of \"the gate disagrees with the disk\" were reachable, and order
   only accounts for ONE of them:

     - false RED, which order DOES fix: add a var to ai-run-corp-handlers and call
       it from ai-runs. With ai-runs listed first it compiled against the older
       handlers and `make check` printed `No such var: window-rez-commitment` for
       correct code. That is what this branch was opened for; it cost a session a
       wrong diagnosis (\"merge-only defect\") on a merge that was in fact clean.

     - false GREEN, which order does NOT fix, and no ordering can: DELETE or
       rename a var ai-runs still calls. Reordering the list was tried against
       exactly that mutation and the tick still appeared, because `:reload`
       re-evaluates a file but never UNMAPS what the file stopped defining:

           (require 'ai-run-corp-handlers :reload)
           ;; both of these resolve, one of them defined nowhere on disk
           [(some? (resolve 'ai-run-corp-handlers/window-rez-commitment))
            (some? (resolve 'ai-run-corp-handlers/window-rez-commitment-RENAMED))]
           ;; => [true true]

       So the stale var, not the stale ORDER, is what makes that direction pass.
       It is #215; the warm path cannot currently see a removed var at
       any position in this list, and `remove-ns` is not a free fix because the
       same REPL holds the live game state.

   WHAT THIS PINS
   That AI_NAMESPACES is in topological order with respect to the ACTUAL `:require`
   forms on disk, read with Clojure's own reader rather than a regex. In
   topological order every dependency has been re-evaluated before any dependent
   compiles against it, which is exactly the false-RED direction and no more.

   WHAT THIS DOES NOT PIN
     - namespaces outside the list (`jinteki.cards`, `differ.core`, …). They are
       not reloaded at all, so their freshness is not this list's business.
     - that the list is COMPLETE. check-ai-sweep covers the rest parse-only, and
       check-ai.sh says out loud that a new namespace gets parse-only coverage
       until someone adds it. A name added to the list in the wrong place is what
       this catches.
     - a require cycle. There is none today, and Clojure would reject one at load
       anyway. `order-violations` terminates on one and reports the single arc that
       points forward in the list — in a linear order only one arc of a cycle can
       have its dependency listed later — which is enough to fail the real-tree
       test."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Reading the two sides
;; ---------------------------------------------------------------------------

(defn listed-namespaces
  "The AI_NAMESPACES array from a check-ai.sh source, in file order.

   A trailing `# comment` is stripped, because bash strips it: `ai-runs # note` is
   ONE array entry named `ai-runs`. Keeping the comment in the name silently
   detached that entry from its requires and emptied its edges."
  [script-src]
  (when-let [body (second (re-find #"(?s)AI_NAMESPACES=\(\n(.*?)\n\)" script-src))]
    (->> (str/split-lines body)
         (map #(str/replace % #"#.*$" ""))
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn duplicates
  "Entries appearing more than once, in first-seen order.

   `order-violations` positions an entry by its LAST occurrence, so a duplicate
   can mask a real violation: with [\"dependent\" \"dependency\" \"dependent\"] the
   helper returns no violations, while the shell still reloads the FIRST
   `dependent` before its dependency. Rejecting duplicates keeps positions
   unambiguous, which is also how the `make test` registration guard handles it."
  [listed]
  (->> listed (frequencies) (filter (fn [[_ n]] (> n 1))) (map first)
       (sort-by #(.indexOf ^java.util.List (vec listed) %)) vec))

(defn- first-ns-form
  "The file's `ns` form, however many forms precede it — or nil.

   `(read-string (slurp f))` returns only the FIRST form, so a legal leading
   `(comment \"…\")` made a file contribute no requires at all, and the guard then
   passed over a genuinely misordered dependent. `*read-eval*` is bound off for
   the same reason check-ai-sweep binds it: a gate should not evaluate what it
   reads."
  [file]
  (binding [*read-eval* false]
    (with-open [r (java.io.PushbackReader. (io/reader file))]
      (loop []
        (let [form (try (read {:eof ::eof} r) (catch Exception _ ::eof))]
          (cond
            (= form ::eof)                        nil
            (and (seq? form) (= 'ns (first form))) form
            :else                                  (recur)))))))

(defn ns-requires
  "ns-name -> set of required ns names, read with Clojure's reader.

   The reader is the authority here on purpose: a paren-balancing regex over
   these files counts the parens inside ai-heuristic-corp's docstring, which
   contains a literal `(require '[ai-heuristic-corp :as bot])` usage example, and
   reports a self-require that does not exist.

   A file with NO `ns` form contributes nothing, which is why the fixture test
   separately asserts that every LISTED namespace turned up here. A file whose
   requires simply went missing would otherwise leave this guard green over a
   real misordering."
  [dir]
  (into {}
        (for [f (->> (file-seq (io/file dir))
                     (filter #(str/ends-with? (.getName %) ".clj"))
                     (sort-by #(.getName %)))
              :let [form (first-ns-form f)]
              :when (some? form)]
          [(second form)
           (set (for [clause (drop 2 form)
                      :when (and (seq? clause) (= :require (first clause)))
                      spec (rest clause)]
                  (if (vector? spec) (first spec) spec)))])))

(defn order-violations
  "Every [dependent dependency] where the list names the dependency LATER.

   Only dependencies that are themselves in the list count: the others are never
   reloaded, so their position cannot be wrong."
  [listed requires]
  (let [in-list (set (map str listed))
        pos     (into {} (map-indexed (fn [i n] [n i]) listed))]
    (vec (for [dependent listed
               dependency (sort (map str (get requires (symbol dependent))))
               :when (and (contains? in-list dependency)
                          (> (pos dependency) (pos dependent)))]
           [dependent dependency]))))

;; ---------------------------------------------------------------------------
;; The real tree
;; ---------------------------------------------------------------------------

(def ^:private script-file (io/file "dev/check-ai.sh"))
(def ^:private src-dir "dev/src/clj")

(deftest the-fixture-is-actually-reading-the-repo
  (testing "a missing script or empty parse would satisfy the order assertion for the wrong reason"
    (is (.exists script-file) "dev/check-ai.sh not found — run the suite from the repo root")
    (let [listed (listed-namespaces (slurp script-file))]
      (is (seq listed) "AI_NAMESPACES did not parse out of check-ai.sh")
      (is (< 10 (count listed)) "suspiciously short AI_NAMESPACES list")
      (is (every? #(str/starts-with? % "ai-") listed)
          "AI_NAMESPACES picked up something that is not a namespace name"))
    (let [requires (ns-requires src-dir)]
      (is (contains? requires 'ai-runs) "ai-runs' ns form did not read")
      (is (contains? (get requires 'ai-runs) 'ai-run-corp-handlers)
          "the known ai-runs -> ai-run-corp-handlers edge did not read"))))

(deftest every-listed-namespace-has-its-requires-read
  (testing "a listed name whose edges did not read would pass the order test vacuously"
    (let [listed   (listed-namespaces (slurp script-file))
          requires (ns-requires src-dir)
          unread   (vec (remove #(contains? requires (symbol %)) listed))]
      (is (empty? unread)
          (str "these are in AI_NAMESPACES but no `ns` form for them was read under "
               src-dir ", so they contribute NO dependency edges and the order "
               "assertion cannot see them misplaced: " (pr-str unread)
               ". Either the file was renamed/deleted and the list is stale, or its "
               "`ns` form did not read.")))))

(deftest ai-namespaces-has-no-duplicate-entries
  (testing "a duplicate makes an entry's position ambiguous and can mask a violation"
    (let [dups (duplicates (listed-namespaces (slurp script-file)))]
      (is (empty? dups) (str "duplicated AI_NAMESPACES entries: " (pr-str dups))))))

(deftest ai-namespaces-is-in-topological-order
  (testing "no namespace is reloaded before a namespace it requires"
    (let [listed     (listed-namespaces (slurp script-file))
          requires   (ns-requires src-dir)
          violations (order-violations listed requires)]
      (is (empty? violations)
          (str "check-ai.sh reloads these AFTER the dependent that requires them, so the "
               "dependent compiles against the REPL's stale copy:\n"
               (str/join "\n"
                         (for [[dependent dependency] violations]
                           (format "  %s requires %s, which is listed later"
                                   dependent dependency))))))))

;; ---------------------------------------------------------------------------
;; The guard can be green over its own defect. Mutate it.
;; ---------------------------------------------------------------------------

(def ^:private fixture-script
  "AI_NAMESPACES=(\n    ai-core\n    ai-runs\n    ai-display\n)\n")

(deftest order-violations-detects-the-defect-it-guards
  (testing "a dependency listed later is reported"
    (is (= [["ai-runs" "ai-display"]]
           (order-violations ["ai-core" "ai-runs" "ai-display"]
                             '{ai-runs #{ai-core ai-display}}))))
  (testing "the same graph in topological order is clean"
    (is (empty? (order-violations ["ai-core" "ai-display" "ai-runs"]
                                  '{ai-runs #{ai-core ai-display}}))))
  (testing "a dependency outside the list is not a violation — it is never reloaded"
    (is (empty? (order-violations ["ai-runs"] '{ai-runs #{jinteki.cards clojure.string}}))))
  (testing "a cycle terminates and reports the arc that points forward in the list"
    ;; Only ONE arc can be a violation in a linear order: for [a b] with a<->b,
    ;; a -> b has its dependency listed later and b -> a does not. One is enough
    ;; to fail the real-tree test, which is all this needs to do.
    (is (= [["a" "b"]]
           (order-violations ["a" "b"] '{a #{b} b #{a}}))))
  (testing "a duplicate entry hides a real violation — which is why duplicates are rejected"
    ;; Pinned as the REASON ai-namespaces-has-no-duplicate-entries exists: this is
    ;; the wrong answer, and the only defence against it is rejecting duplicates.
    (is (empty? (order-violations ["dependent" "dependency" "dependent"]
                                  '{dependent #{dependency}}))
        "if this ever reports the violation, order-violations became occurrence-aware")
    (is (= [["dependent" "dependency"]]
           (order-violations ["dependent" "dependency"] '{dependent #{dependency}}))
        "the same graph without the duplicate IS a violation")))

(deftest duplicates-detects-the-defect-it-guards
  (testing "a repeated entry is reported once, in first-seen order"
    (is (= ["ai-runs"] (duplicates ["ai-core" "ai-runs" "ai-display" "ai-runs"])))
    (is (= ["a" "b"] (duplicates ["a" "b" "a" "b" "c"]))))
  (testing "a list with no repeats is clean"
    (is (empty? (duplicates ["ai-core" "ai-runs" "ai-display"]))))
  (testing "the real list is what it is asked about, not a fixture"
    (is (empty? (duplicates (listed-namespaces (slurp script-file)))))))

(deftest listed-namespaces-parses-the-array
  (testing "the array body, not the whole script"
    (is (= ["ai-core" "ai-runs" "ai-display"] (listed-namespaces fixture-script))))
  (testing "a commented-out entry is not a namespace"
    (is (= ["ai-core"] (listed-namespaces "AI_NAMESPACES=(\n    ai-core\n    # ai-later\n)\n"))))
  (testing "a TRAILING comment is stripped, because bash strips it"
    ;; `ai-runs # note` is one entry named ai-runs. Keeping the comment in the name
    ;; detached it from its requires, emptying its edges silently.
    (is (= ["ai-runs"] (listed-namespaces "AI_NAMESPACES=(\n    ai-runs # note\n)\n")))
    (is (= ["ai-core" "ai-runs"]
           (listed-namespaces "AI_NAMESPACES=(\n    ai-core  # first\n    ai-runs\n)\n"))))
  (testing "no array means nil, not a silently empty list that passes everything"
    (is (nil? (listed-namespaces "echo hello\n")))))

(deftest ns-requires-reads-the-real-tree-not-docstrings
  (testing "ai-heuristic-corp's docstring require example is not read as a require"
    (let [requires (ns-requires src-dir)]
      (is (contains? requires 'ai-heuristic-corp))
      (is (not (contains? (get requires 'ai-heuristic-corp) 'ai-heuristic-corp))
          "the docstring's `(require '[ai-heuristic-corp :as bot])` leaked in as a real edge"))))

(deftest ns-requires-finds-an-ns-form-that-is-not-the-first-form
  (testing "a legal leading form no longer hides a whole file's requires"
    ;; This is the hole that made the guard pass over a misordered dependent:
    ;; read-string returns only the first form, so `(comment …)` before the `ns`
    ;; emptied that file's edges. The file still loads and the parse sweep still
    ;; accepts it, so nothing else would have noticed.
    (let [dir (java.io.File/createTempFile "nsreq" "")]
      (.delete dir) (.mkdirs dir)
      (try
        (spit (io/file dir "leading_comment.clj")
              "(comment \"harmless\")\n(ns probe-leading (:require [ai-core :as core]))\n")
        (spit (io/file dir "no_ns_at_all.clj") "(def x 1)\n")
        (let [requires (ns-requires (.getPath dir))]
          (is (= '#{ai-core} (get requires 'probe-leading))
              "the ns form after a leading (comment) was not found")
          (is (not (contains? requires 'no-ns-at-all))
              "a file with no ns form should contribute nothing"))
        (finally (doseq [f (reverse (file-seq dir))] (.delete f)))))))
