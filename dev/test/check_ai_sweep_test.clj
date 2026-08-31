(ns check-ai-sweep-test
  "Pins check-ai-sweep against its only sound oracle: real `load-file`.

   The sweep exists to make `make check` cover ai_client_init.clj (#184). A
   parser used as a gate has exactly two ways to be wrong, and both shipped in
   the first cut — it can accept what loading rejects (false GREEN, the gate
   lies) or reject what loading accepts (false RED, a healthy tree is blocked).
   So every case below asserts the sweep AGREES with `load-file` on the same
   source, rather than asserting the sweep does something in isolation.

   Cases 1-3 are review-panel findings, each reproduced against real loading
   before it was fixed; case 4 is the safety claim that was untrue."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [check-ai-sweep :as sweep]))

(defn- load-file-outcome
  "Does real Clojure loading accept this source? :ok or :error."
  [src]
  (let [f (java.io.File/createTempFile "check-ai-oracle" ".clj")]
    (try
      (spit f src)
      (try (load-file (.getPath f)) :ok (catch Throwable _ :error))
      (finally (.delete f)))))

(defn- sweep-outcome
  "Does the parse-only sweep accept this source? :ok or :error."
  [src]
  (try (sweep/parse-source "oracle-case" src) :ok (catch Throwable _ :error)))

(defn- agrees?
  "The property under test: the sweep and real loading reach the same verdict."
  [src]
  (let [loaded (load-file-outcome src)
        swept  (sweep-outcome src)]
    {:load loaded :sweep swept :agree (= loaded swept)}))

(deftest sweep-agrees-with-real-loading
  (testing "a healthy file is accepted by both"
    (let [r (agrees? "(ns oracle.healthy)\n(defn f [] 42)\n")]
      (is (= :ok (:load r)))
      (is (:agree r) (str "sweep disagreed with load-file: " r))))

  (testing "unbalanced parens are rejected by both"
    (let [r (agrees? "(ns oracle.broken)\n(defn g [] (println \"x\"\n")]
      (is (= :error (:load r)))
      (is (:agree r) (str "sweep disagreed with load-file: " r))))

  (testing "panel defect 1: a file CONTAINING the EOF sentinel must not end the read early"
    ;; The first cut used the readable keyword :check-ai/eof as its sentinel, so
    ;; this source reported clean while the unmatched ( below was never read.
    (let [src "(ns oracle.sentinel)\n:check-ai/eof\n(\n"
          r (agrees? src)]
      (is (= :error (:load r)) "real loading rejects this")
      (is (= :error (:sweep r))
          "FALSE GREEN: the sentinel keyword ended the read before the broken form")
      (is (:agree r) (str "sweep disagreed with load-file: " r))))

  (testing "panel defect 2: a reader conditional in a .clj is rejected by loading, so the sweep must reject it"
    ;; `:read-cond :allow` made the sweep accept source that .clj loading refuses.
    (let [r (agrees? "(ns oracle.readercond)\n#?(:clj :accepted)\n")]
      (is (= :error (:load r)) "real .clj loading refuses reader conditionals")
      (is (= :error (:sweep r)) "FALSE GREEN: sweep accepted what loading rejects")
      (is (:agree r) (str "sweep disagreed with load-file: " r))))

  (testing "panel defect 3: an auto-resolved alias keyword is VALID and must not be rejected"
    ;; clojure.core/read cannot read ::str/x — the ns form is never evaluated so
    ;; the alias does not exist — which failed a healthy tree.
    (let [r (agrees? "(ns oracle.alias (:require [clojure.string :as str]))\n(def k ::str/x)\n")]
      (is (= :ok (:load r)) "real loading accepts this")
      (is (= :ok (:sweep r)) "FALSE RED: sweep rejected a healthy file")
      (is (:agree r) (str "sweep disagreed with load-file: " r))))

  (testing "panel defect 4: a tagged literal is read inertly, not executed"
    ;; *read-eval* false blocks #= but NOT registered data-reader fns. On the
    ;; warm path those side effects would land in the long-lived shared REPL.
    (let [fired (atom 0)]
      (binding [clojure.tools.reader/*data-readers*
                {'oracle/boom (fn [_] (swap! fired inc) :detonated)}]
        ;; the sweep rebinds *data-readers* to {} internally, so the reader above
        ;; must NOT run even though it is registered in this scope
        (sweep/parse-source "inert" "(ns oracle.tagged)\n(def t #oracle/boom 1)\n"))
      (is (zero? @fired)
          "a registered data reader executed during a supposedly read-only sweep"))))

(deftest sweep-refuses-to-pass-on-an-empty-directory
  (testing "sweeping zero files is a failure, not a pass"
    ;; A sweep of nothing looks exactly like a sweep of a healthy tree. If the
    ;; directory ever moves, #184 reopens behind a green tick.
    (let [empty-dir (doto (io/file (System/getProperty "java.io.tmpdir")
                                   (str "check-ai-empty-" (System/nanoTime)))
                      .mkdirs)]
      (try
        (is (thrown? clojure.lang.ExceptionInfo (sweep/sweep! (.getPath empty-dir))))
        (finally (.delete empty-dir)))))

  (testing "a nonexistent directory is also a failure"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sweep/sweep! "no/such/directory/anywhere")))))

(deftest sweep-names-the-broken-file-on-stdout
  ;; check-ai.sh greps stdout for AI-PARSE-ERROR, and the operator needs the
  ;; filename. clojure.main prints an exception chain's ROOT cause, so the
  ;; wrapper message alone is invisible — the sweep must print explicitly.
  (testing "a reader error prints the marker AND the path before propagating"
    (let [dir (doto (io/file (System/getProperty "java.io.tmpdir")
                             (str "check-ai-broken-" (System/nanoTime)))
                .mkdirs)
          broken (io/file dir "boom.clj")]
      (try
        (spit broken "(ns boom)\n(defn f [] (println \"x\"\n")
        (let [out (with-out-str
                    (is (thrown? Exception (sweep/sweep! (.getPath dir)))))]
          (is (clojure.string/includes? out "AI-PARSE-ERROR")
              "check-ai.sh greps for this marker; without it the gate cannot report the failure")
          (is (clojure.string/includes? out "boom.clj")
              "the operator cannot act on a parse error that does not name the file"))
        (finally (.delete broken) (.delete dir))))))

(deftest sweep-covers-the-boot-file-that-nothing-requires
  (testing "ai_client_init.clj — the whole point of #184 — is in the swept set"
    ;; It is loaded by `load-file`, not `require`, so nothing references it and
    ;; only a directory sweep can reach it.
    (let [swept (set (map #(.getName %) (sweep/clj-files "dev/src/clj")))]
      (is (contains? swept "ai_client_init.clj"))))

  (testing "the real tree sweeps clean and is not empty"
    (is (pos? (sweep/sweep! "dev/src/clj")))))
