(ns check-ai-ns-order-test
  "`make check`'s warm path must not compile a namespace against a STALE dependency.

   WHAT SHIPPED
   check-ai.sh's fast path reloads a hand-maintained list of namespaces inside the
   long-lived port-7889 REPL:

       for ns in \"${AI_NAMESPACES[@]}\"; do ... (require '$ns :reload) ...

   `:reload` is SHALLOW. It recompiles that one namespace against whatever its
   dependencies happen to be in that REPL's memory right now. So if the list names
   a dependent BEFORE a namespace it requires, the dependent is compiled against
   the previous session's copy, and the gate answers about a tree that exists on no
   disk. The list's own comment says `order matters for dependencies`, and it was
   violated by fourteen dependency arcs spanning seven of the entries — including
   all four `ai-run-*` namespaces that `ai-runs` requires (#217).

   WHAT ORDER DOES AND DOES NOT FIX — measured, not reasoned
     - false RED, which order DOES fix: add a var to ai-run-corp-handlers and call
       it from ai-runs. With ai-runs listed first it compiled against the older
       handlers and `make check` printed `No such var: window-rez-commitment` for
       correct code. It cost a session a wrong diagnosis (\"merge-only defect\") on a
       merge that was in fact clean.

     - false GREEN, which order does NOT fix, and no ordering can: DELETE or rename
       a var a dependent still calls. Reordering was tried against exactly that
       mutation and the tick still appeared, because `:reload` re-evaluates a file
       but never UNMAPS what the file stopped defining:

           (require 'ai-run-corp-handlers :reload)
           ;; both resolve; one of them is defined nowhere on disk
           [(some? (resolve 'ai-run-corp-handlers/window-rez-commitment))
            (some? (resolve 'ai-run-corp-handlers/window-rez-commitment-RENAMED))]
           ;; => [true true]

       That is #215, still open. The warm path now says so in its own output,
       because a source comment does not reach whoever reads the green tick.

   WHY THE GRAPH COMES FROM THE RUNTIME AND NOT FROM THE FILES
   This test's first two versions derived the dependency graph by READING the
   source files, and three review seats found four separate ways that lied — each
   one making a real misordering report no violations:
     - `(read-string (slurp f))` returns only a file's FIRST form, so a legal
       leading `(comment \"…\")` emptied a whole file's requires;
     - taking the first `ns` form then reported the emptier of two declarations in
       one file, though real loading takes the later one;
     - a stale copy of a file still carrying the original `ns` form (never the file
       `require` loads) overwrote the real namespace's requires under a
       last-one-wins map;
     - a paren-balancing regex — the version before all of those — counted the
       parens inside ai-heuristic-corp's docstring, which contains a literal
       `(require '[ai-heuristic-corp :as bot])` example, and reported a
       self-require that does not exist.
   Every one of those is a disagreement between \"what the file looks like\" and
   \"what loading actually does\". So this asks the runtime instead: `require` each
   listed namespace and read `ns-aliases`, which IS the graph the REPL will use.
   There is no file-to-namespace mapping left to get wrong, and a list entry naming
   something unloadable throws at `require` rather than contributing no edges.

   WHAT THIS PINS
   That AI_NAMESPACES is in topological order with respect to the loaded namespaces'
   actual aliases. In topological order every dependency has been re-evaluated
   before any dependent compiles against it — which is exactly the false-RED
   direction and no more.

   WHAT THIS DOES NOT PIN
     - namespaces outside the list (`jinteki.cards`, `differ.core`, …). They are
       never reloaded, so their position is not this list's business. They are
       filtered out rather than ordered.
     - a `:require` with no `:as`. `ns-aliases` only reports aliases. Checked: the
       only such specs in these files are `[jinteki.cards :refer [all-cards]]` in
       three namespaces, and jinteki.cards is not in the list, so nothing ordered
       here is invisible. A future alias-free require BETWEEN two listed namespaces
       would be, which is why this limit is written down rather than assumed away.
     - an alias created by a body-level `require` that only exists once a function
       has RUN. This test loads namespaces and calls nothing, so those do not
       appear; if one did, it would be an extra edge, i.e. a false RED.
     - that the list is COMPLETE. check-ai-sweep covers the rest parse-only, and
       check-ai.sh says out loud that a new namespace gets parse-only coverage
       until someone adds it. A name added in the wrong PLACE is what this catches.
     - a require cycle. There is none today and Clojure rejects one at load.
       `order-violations` terminates on one and reports every forward arc: at least
       one, and more for a longer cycle — [a b c] with a->b->c->a reports a->b and
       b->c. A two-node cycle is the special case reporting exactly one. (Two
       earlier versions of this sentence were wrong: first \"both arcs\", then
       \"only one arc\".)"
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The list under test. Parsing the shell array is unavoidable — it IS the
;; artifact this guards.
;; ---------------------------------------------------------------------------

(defn listed-namespaces
  "The AI_NAMESPACES array from a check-ai.sh source, in file order.

   A trailing `# comment` is stripped, because bash strips it: `ai-runs # note` is
   ONE array entry named `ai-runs`. Keeping the comment in the name silently
   detached that entry from its dependencies and emptied its edges."
  [script-src]
  (when-let [body (second (re-find #"(?s)AI_NAMESPACES=\(\n(.*?)\n\)" script-src))]
    (->> (str/split-lines body)
         (map #(str/replace % #"#.*$" ""))
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn duplicates
  "Entries appearing more than once, in first-seen order.

   `order-violations` positions an entry by its LAST occurrence, so a duplicate can
   mask a real violation: with [\"dependent\" \"dependency\" \"dependent\"] it returns
   no violations, while the shell still reloads the FIRST `dependent` too early.
   Rejecting duplicates keeps positions unambiguous, which is also how the
   `make test` registration guard handles it."
  [listed]
  (let [ordered (vec listed)]
    (->> ordered frequencies (filter (fn [[_ n]] (> n 1))) (map first)
         (sort-by #(.indexOf ^java.util.List ordered %)) vec)))

;; ---------------------------------------------------------------------------
;; The dependency graph, from the runtime rather than from the files
;; ---------------------------------------------------------------------------

(defn dependency-graph
  "listed-name -> set of LISTED namespace names it requires, read from `ns-aliases`.

   `require` first, so this is the graph the REPL resolves against. A listed entry
   that names nothing loadable throws here — loudly, which is the point: the
   previous file-reading versions gave such an entry no edges and passed."
  [listed]
  (let [in-list (set listed)]
    (into {}
          (for [n listed]
            (do (require (symbol n))
                [n (set (for [dep (vals (ns-aliases (symbol n)))
                              :let [dep-name (str (ns-name dep))]
                              :when (contains? in-list dep-name)]
                          dep-name))])))))

(defn order-violations
  "Every [dependent dependency] where the list names the dependency LATER."
  [listed graph]
  (let [pos (into {} (map-indexed (fn [i n] [n i]) listed))]
    (vec (for [dependent listed
               dependency (sort (get graph dependent))
               :when (> (pos dependency) (pos dependent))]
           [dependent dependency]))))

;; ---------------------------------------------------------------------------
;; The real tree
;; ---------------------------------------------------------------------------

(def ^:private script-file (io/file "dev/check-ai.sh"))

(deftest the-fixture-is-actually-reading-the-repo
  (testing "an empty parse would satisfy the order assertion for the wrong reason"
    (is (.exists script-file) "dev/check-ai.sh not found — run the suite from the repo root")
    (let [listed (listed-namespaces (slurp script-file))]
      (is (seq listed) "AI_NAMESPACES did not parse out of check-ai.sh")
      (is (< 10 (count listed)) "suspiciously short AI_NAMESPACES list")
      (is (every? #(str/starts-with? % "ai-") listed)
          "AI_NAMESPACES picked up something that is not a namespace name")
      (testing "and the graph is not vacuously empty"
        (let [graph (dependency-graph listed)]
          (is (= (set listed) (set (keys graph))) "a listed namespace produced no entry")
          (is (contains? (get graph "ai-runs") "ai-run-corp-handlers")
              "the known ai-runs -> ai-run-corp-handlers edge is missing, so the graph is not live")
          (is (< 20 (reduce + (map count (vals graph))))
              "implausibly few edges for 23 interdependent namespaces"))))))

(deftest ai-namespaces-has-no-duplicate-entries
  (testing "a duplicate makes an entry's position ambiguous and can mask a violation"
    (let [dups (duplicates (listed-namespaces (slurp script-file)))]
      (is (empty? dups) (str "duplicated AI_NAMESPACES entries: " (pr-str dups))))))

(deftest ai-namespaces-is-in-topological-order
  (testing "no namespace is reloaded before a namespace it requires"
    (let [listed     (listed-namespaces (slurp script-file))
          violations (order-violations listed (dependency-graph listed))]
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

(deftest order-violations-detects-the-defect-it-guards
  (testing "a dependency listed later is reported"
    (is (= [["ai-runs" "ai-display"]]
           (order-violations ["ai-core" "ai-runs" "ai-display"]
                             {"ai-runs" #{"ai-core" "ai-display"}}))))
  (testing "the same graph in topological order is clean"
    (is (empty? (order-violations ["ai-core" "ai-display" "ai-runs"]
                                  {"ai-runs" #{"ai-core" "ai-display"}}))))
  (testing "a cycle terminates and reports every forward arc — one here, two for a 3-cycle"
    (is (= [["a" "b"]]
           (order-violations ["a" "b"] {"a" #{"b"} "b" #{"a"}})))
    (is (= [["a" "b"] ["b" "c"]]
           (order-violations ["a" "b" "c"] {"a" #{"b"} "b" #{"c"} "c" #{"a"}}))
        "a 3-cycle has two forward arcs; 'exactly one' was only true of the 2-node fixture"))
  (testing "a duplicate entry hides a real violation — which is why duplicates are rejected"
    (is (empty? (order-violations ["dependent" "dependency" "dependent"]
                                  {"dependent" #{"dependency"}}))
        "if this ever reports the violation, order-violations became occurrence-aware")
    (is (= [["dependent" "dependency"]]
           (order-violations ["dependent" "dependency"] {"dependent" #{"dependency"}}))
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
    (is (= ["ai-core" "ai-runs" "ai-display"]
           (listed-namespaces "AI_NAMESPACES=(\n    ai-core\n    ai-runs\n    ai-display\n)\n"))))
  (testing "a commented-out entry is not a namespace"
    (is (= ["ai-core"] (listed-namespaces "AI_NAMESPACES=(\n    ai-core\n    # ai-later\n)\n"))))
  (testing "a TRAILING comment is stripped, because bash strips it"
    (is (= ["ai-runs"] (listed-namespaces "AI_NAMESPACES=(\n    ai-runs # note\n)\n")))
    (is (= ["ai-core" "ai-runs"]
           (listed-namespaces "AI_NAMESPACES=(\n    ai-core  # first\n    ai-runs\n)\n"))))
  (testing "no array means nil, not a silently empty list that passes everything"
    (is (nil? (listed-namespaces "echo hello\n")))))

(deftest dependency-graph-is-live-and-scoped-to-the-list
  (testing "a dependency outside the list is dropped, not ordered"
    ;; ai-state requires differ.core, which check-ai.sh never reloads.
    (let [graph (dependency-graph ["ai-state" "ai-debug"])]
      (is (= #{"ai-debug"} (get graph "ai-state"))
          "either differ.core leaked in, or the real ai-state -> ai-debug edge vanished")))
  (testing "a listed name that names nothing loadable FAILS, rather than getting no edges"
    ;; The file-reading versions gave a stale entry an empty edge set and passed.
    (is (thrown? java.io.FileNotFoundException
                 (dependency-graph ["ai-state" "ai-namespace-that-does-not-exist"])))))
