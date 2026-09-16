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
   dependencies`; fourteen entries violated it, including all four `ai-run-*`
   namespaces that `ai-runs` requires.

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
       anyway; `order-violations` would simply report both arcs."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Reading the two sides
;; ---------------------------------------------------------------------------

(defn listed-namespaces
  "The AI_NAMESPACES array from a check-ai.sh source, in file order."
  [script-src]
  (when-let [body (second (re-find #"(?s)AI_NAMESPACES=\(\n(.*?)\n\)" script-src))]
    (->> (str/split-lines body)
         (map str/trim)
         (remove str/blank?)
         (remove #(str/starts-with? % "#"))
         vec)))

(defn ns-requires
  "ns-name -> set of required ns names, read with Clojure's reader.

   The reader is the authority here on purpose: a paren-balancing regex over
   these files counts the parens inside ai-heuristic-corp's docstring, which
   contains a literal `(require '[ai-heuristic-corp :as bot])` usage example, and
   reports a self-require that does not exist."
  [dir]
  (into {}
        (for [f (->> (file-seq (io/file dir))
                     (filter #(str/ends-with? (.getName %) ".clj"))
                     (sort-by #(.getName %)))
              :let [form (try (read-string (slurp f)) (catch Exception _ nil))]
              :when (and (seq? form) (= 'ns (first form)))]
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
  (testing "both arcs of a cycle are reported, rather than the checker looping"
    (is (= [["a" "b"]]
           (order-violations ["a" "b"] '{a #{b} b #{a}})))))

(deftest listed-namespaces-parses-the-array
  (testing "the array body, not the whole script"
    (is (= ["ai-core" "ai-runs" "ai-display"] (listed-namespaces fixture-script))))
  (testing "a commented-out entry is not a namespace"
    (is (= ["ai-core"] (listed-namespaces "AI_NAMESPACES=(\n    ai-core\n    # ai-later\n)\n"))))
  (testing "no array means nil, not a silently empty list that passes everything"
    (is (nil? (listed-namespaces "echo hello\n")))))

(deftest ns-requires-reads-the-real-tree-not-docstrings
  (testing "ai-heuristic-corp's docstring require example is not read as a require"
    (let [requires (ns-requires src-dir)]
      (is (contains? requires 'ai-heuristic-corp))
      (is (not (contains? (get requires 'ai-heuristic-corp) 'ai-heuristic-corp))
          "the docstring's `(require '[ai-heuristic-corp :as bot])` leaked in as a real edge"))))
