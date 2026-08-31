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

  (testing "panel defect 4: a REGISTERED tag is read inertly, not executed"
    ;; *read-eval* false blocks #= but NOT registered data-reader fns. This
    ;; project registers 21 of them, including `dbg`, `break` and `break!` —
    ;; firing those inside the long-lived shared REPL on the warm path is not a
    ;; hypothetical cost. clojure.core/*data-readers* is where real
    ;; registrations live, and the sweep remaps exactly those to inert
    ;; construction.
    (let [fired (atom 0)]
      (binding [clojure.core/*data-readers*
                (assoc clojure.core/*data-readers*
                       'oracle/boom (fn [_] (swap! fired inc) :detonated))]
        (sweep/parse-source "inert" "(ns oracle.tagged)\n(def t #oracle/boom 1)\n"))
      (is (zero? @fired)
          "a registered data reader executed during a supposedly read-only sweep")))

  (testing "round-2 defect: an UNREGISTERED tag must be rejected, as loading rejects it"
    ;; The first fix for defect 4 bound *default-data-reader-fn* to accept ANY
    ;; tag inertly, which read #bogus/tag clean while real loading fails —
    ;; a false green. Nothing binds that fn now, so unknown tags throw.
    (let [r (agrees? "(ns oracle.unknowntag)\n(def t #bogus/tag 1)\n")]
      (is (= :error (:load r)) "real loading rejects an unregistered tag")
      (is (= :error (:sweep r)) "FALSE GREEN: sweep accepted an unregistered tag")
      (is (:agree r) (str "sweep disagreed with load-file: " r))))

  (testing "built-in literals still work, and a malformed one still fails"
    ;; #inst / #uuid are built into the reader, not supplied via *data-readers*,
    ;; so remapping that map must not disturb them in either direction.
    (let [good (agrees? "(ns oracle.inst)\n(def t #inst \"2026-01-01\")\n")
          bad  (agrees? "(ns oracle.badinst)\n(def t #inst \"not-a-date\")\n")]
      (is (= :ok (:load good)))
      (is (:agree good) (str "valid #inst: " good))
      (is (= :error (:load bad)))
      (is (:agree bad) (str "malformed #inst: " bad)))))

(deftest known-gap-undeclared-alias-is-not-caught
  ;; DOCUMENTED MISS, asserted so it cannot change silently (#187).
  ;;
  ;; The sweep resolves every alias to a synthetic namespace, so `::bogus/x` —
  ;; a typo — reads clean while real loading rejects it. Closing this means
  ;; parsing the ns form's :require shapes to build the real alias map, and a
  ;; wrong parser there fails HEALTHY trees, which is worse than this miss.
  ;;
  ;; This test does not endorse the gap. It pins it, so that whoever closes
  ;; #187 gets a red test telling them to move this case into the agreement
  ;; corpus above rather than discovering the behaviour by accident.
  (testing "an undeclared alias keyword currently sweeps clean though loading rejects it"
    (let [r (agrees? "(ns oracle.badalias)\n(def k ::bogus/x)\n")]
      (is (= :error (:load r)) "real loading rejects an undeclared alias")
      (is (= :ok (:sweep r))
          "if this is now :error, #187 is fixed — move this case into the agreement corpus")
      (is (not (:agree r)) "this is the known disagreement; see #187"))))

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
