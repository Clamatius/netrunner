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

   WHERE THE GRAPH COMES FROM, AND THE FOUR ANSWERS THAT WERE WRONG
   The question this must answer is narrow: does compiling namespace A under
   `(require 'A :reload)` depend on namespace B? The authority is A's `ns` form, in
   the ONE file Clojure's own `require` rule selects for A. Four earlier versions
   answered a different question, and three review seats found each one — every
   failure made a real misordering report no violations:
     - a paren-balancing regex counted the parens inside ai-heuristic-corp's
       docstring, which holds a literal `(require '[ai-heuristic-corp :as bot])`
       example, and reported a self-require that does not exist;
     - `(read-string (slurp f))` returns only a file's FIRST form, so a legal
       leading `(comment \"…\")` emptied a whole file's requires;
     - taking the first `ns` form then reported the emptier of two declarations in
       one file, though loading takes the later;
     - scanning the directory let ANY file claim a namespace, so a stale copy still
       carrying the original `ns` form — never the file `require` loads — overwrote
       the real requires.
   A fifth version asked the RUNTIME instead, via `ns-aliases`. That is worse, and
   a fourth seat caught it: `ns-aliases` conflates compile-time requires with
   aliases created by a body-level `(require '[x :as y])` when a function RUNS.
   `ai-prompts` does not require `ai-basic-actions` in its `ns` form but creates
   exactly that alias at runtime, measured:

       ;; in ai-prompts, after a body-level (require '[ai-basic-actions :as basic])
       (contains? (aliases-of 'ai-prompts) \"ai-basic-actions\")  ;; => true

   So the graph would depend on what else had run in the same JVM, differing between
   a focused `lein test` and the full suite, and could fail a healthy order.

   This version therefore reads files again, but asks the CLASSPATH which file —
   `ns-resource` mirrors `clojure.core/root-resource`, the rule `require` uses. That
   removes the directory scan the shadow-file defect needed, keeps the reader rather
   than a regex, unions every matching declaration, and touches no runtime state.

   WHAT THIS PINS
   That AI_NAMESPACES is in topological order with respect to the loaded namespaces'
   actual aliases. In topological order every dependency has been re-evaluated
   before any dependent compiles against it — which is exactly the false-RED
   direction and no more.

   WHAT THIS DOES NOT PIN
     - namespaces outside the list (`jinteki.cards`, `differ.core`, …). They are
       never reloaded, so their position is not this list's business. They are
       filtered out rather than ordered.
     - a dependency created by a body-level `(require …)` inside a function rather
       than by the `ns` form. That is deliberate: such a require runs at call time,
       not at compile time, so it is not a reason to reload one namespace before
       another. It is also why `ns-aliases` was the wrong authority.
     - a `:require` whose spec this does not recognise. `requires-of` takes the
       first element of a vector spec and the symbol of a bare one, which covers
       `:as`, `:refer` and plain forms. It does not read `:load`, and prefix lists
       (`[a.b [c] [d]]`) would be read as the prefix only. There are none here.
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
;; The dependency graph, from the ONE file Clojure would load for each namespace
;; ---------------------------------------------------------------------------

(defn ns-resource
  "The single resource Clojure's own `require` would load for this namespace name.

   Mirrors `clojure.core/root-resource`, which is the rule `require` itself uses:

       (defn- root-resource [lib]
         (str \\/ (.. (name lib) (replace \\- \\_) (replace \\. \\/))))

   Asking the classpath for that ONE path is what makes the shadow-file defect
   unreachable: a stale copy of a file is not the resource this resolves to, so it
   cannot claim a namespace no matter what its `ns` form says. Nothing here scans a
   directory."
  [ns-name-str]
  (io/resource (str (-> ns-name-str (str/replace "-" "_") (str/replace "." "/")) ".clj")))

(defn- ns-forms-named
  "Every `ns` form in `resource` that declares exactly `ns-name-str`, in file order.

   Every clause of that sentence is a defect that got here:
     - EVERY form, not the first: `(read-string (slurp f))` reads only a file's
       first form, so a legal leading `(comment \"…\")` emptied its requires;
     - EVERY `ns` form, not the first one: a file with two declarations had the
       emptier one reported, though loading takes the later;
     - NAMED: a file whose `ns` form declares something else must not have its
       requires attributed to the namespace whose path it occupies.
   `*read-eval*` is bound off for the reason check-ai-sweep binds it: a gate should
   not evaluate what it reads."
  [resource ns-name-str]
  (binding [*read-eval* false]
    (with-open [r (java.io.PushbackReader. (io/reader resource))]
      (loop [acc []]
        (let [form (try (read {:eof ::eof} r) (catch Exception _ ::eof))]
          (cond
            (= form ::eof) acc
            (and (seq? form) (= 'ns (first form)) (= (str (second form)) ns-name-str))
            (recur (conj acc form))
            :else (recur acc)))))))

(defn- requires-of
  "The `:require`d namespace names in one `ns` form."
  [form]
  (set (for [clause (drop 2 form)
             :when (and (seq? clause) (= :require (first clause)))
             spec (rest clause)]
         (str (if (vector? spec) (first spec) spec)))))

(defn dependency-graph
  "listed-name -> set of LISTED namespaces its `ns` form requires.

   Throws if a listed name has no resource, or a resource with no matching `ns`
   form. Every earlier version of this gave such an entry an empty edge set and
   passed the order assertion over it."
  [listed]
  (let [in-list (set listed)]
    (into {}
          (for [n listed]
            (let [res (or (ns-resource n)
                          (throw (ex-info (str "AI_NAMESPACES lists " n
                                               " but no such namespace is on the classpath")
                                          {:namespace n})))
                  forms (ns-forms-named res n)
                  _ (when (empty? forms)
                      (throw (ex-info (str res " does not declare " n)
                                      {:namespace n :resource (str res)})))]
              [n (set (filter in-list (mapcat requires-of forms)))])))))

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

(deftest dependency-graph-is-scoped-to-the-list-and-fails-loudly
  (testing "a dependency outside the list is dropped, not ordered"
    ;; ai-state requires differ.core, which check-ai.sh never reloads.
    (let [graph (dependency-graph ["ai-state" "ai-debug"])]
      (is (= #{"ai-debug"} (get graph "ai-state"))
          "either differ.core leaked in, or the real ai-state -> ai-debug edge vanished")))

  (testing "a listed name with no resource FAILS, rather than getting no edges"
    ;; Every file-reading version gave a stale entry an empty edge set and passed.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such namespace is on the classpath"
                          (dependency-graph ["ai-state" "ai-namespace-that-does-not-exist"]))))

  (testing "ns-resource mirrors Clojure's own munging, so it finds the file require would"
    (is (some? (ns-resource "ai-run-corp-handlers"))
        "dashes must become underscores, as clojure.core/root-resource does")
    (is (str/ends-with? (str (ns-resource "ai-run-corp-handlers")) "/ai_run_corp_handlers.clj"))
    (is (nil? (ns-resource "ai-definitely-not-here"))))

  (testing "a RUNTIME alias is not a compile dependency, and must not become an edge"
    ;; ai-prompts creates an `ai-basic-actions` alias from inside a function body.
    ;; The ns-aliases version of this graph reported that as an edge, which made the
    ;; answer depend on what had already run in the JVM and could fail a healthy
    ;; order. Reading the `ns` form cannot see it, which is the point.
    (require 'ai-prompts)
    (binding [*ns* (find-ns 'ai-prompts)] (eval '(require '[ai-basic-actions :as basic])))
    (is (contains? (set (map (comp str ns-name) (vals (ns-aliases 'ai-prompts))))
                   "ai-basic-actions")
        "fixture: the runtime alias this guards against is not actually present")
    (is (not (contains? (get (dependency-graph ["ai-basic-actions" "ai-prompts"]) "ai-prompts")
                        "ai-basic-actions"))
        "a body-level require leaked into the compile-dependency graph")))
