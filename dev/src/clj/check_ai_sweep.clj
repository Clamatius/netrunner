(ns check-ai-sweep
  "Parse-only sweep of the AI source tree, used by dev/check-ai.sh (#184).

   WHY THIS EXISTS
   `check-ai.sh` compiles a hand-maintained list of 21 namespaces. There are 35
   .clj files under dev/src/clj, and ai_client_init.clj — the bootstrap that
   start-ai-client-repl.sh brings up with `load-file`, before any of those 21
   matter — is required by NOTHING, so a syntax error in it passed `make check`
   clean and only surfaced later at `make reset`, looking like an environment
   fault. Sweeping the directory means there is no list to drift.

   WHY PARSE-ONLY, NOT LOADING
   Loading ai_client_init.clj reads AI_USERNAME/AI_PASSWORD from the environment
   and opens a WebSocket to localhost:1042. A static gate that needs the game
   server up is a flaky gate. So: read every form, evaluate none.

   WHAT THIS DOES NOT CATCH — say it out loud, because a '✅' that implies more
   than it checked is the exact defect this issue family is about:
     - unresolvable `require` targets and unresolvable symbols. Only real
       loading finds those. dev/src/clj/start_ai.clj is a live specimen: it
       `load-file`s two files that do not exist, and reads perfectly clean.
     - anything semantic. This is a parser, not a compiler.

   THE ORACLE
   The only sound definition of correct here is AGREEMENT WITH `load-file`: this
   must reject exactly what real loading rejects, no more and no less. A
   review panel found three ways the first cut broke that, all reproduced
   against real loading, all now pinned by check-ai-sweep-test:
     1. the EOF sentinel was an ordinary readable keyword, so a file CONTAINING
        that keyword ended the read early and the rest of a broken file was
        never seen -> false GREEN. It is an identity-compared Object now.
     2. `:read-cond :allow` accepted `#?(:clj ...)`, which real .clj loading
        rejects outright -> false GREEN. No read-cond option is passed.
     3. `clojure.core/read` cannot read `::alias/kw`, because the ns form is
        never evaluated so the alias never exists -> false RED on a healthy
        tree. clojure.tools.reader with an *alias-map* fn resolves them.
   And one safety claim that was simply untrue: `*read-eval* false` blocks `#=`
   but does NOT stop registered data-reader functions from running (this repo
   has time-literals readers). Both reader hooks are neutralised below, so a
   tagged literal becomes inert data instead of executing someone's function —
   which matters most on the WARM path, where side effects would land in a
   long-lived shared REPL."
  (:require [clojure.java.io :as io]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt]))

(defn parse-source
  "Read every form in `src` (a string of Clojure source) without evaluating any.
   Returns the number of forms read; throws on a reader error.

   `label` is used only in the exception message."
  [label src]
  ;; A fresh Object per call: nothing in any source file can be identical? to it,
  ;; which is the whole point — see defect 1 in the ns docstring.
  (let [eof (Object.)
        rdr (rt/indexing-push-back-reader src)]
    (binding [*read-eval* false
              ;; Registered readers would otherwise RUN. Inert construction
              ;; keeps the form readable without executing anything.
              tr/*data-readers* {}
              tr/*default-data-reader-fn* (fn [tag value] (tagged-literal tag value))
              ;; The ns form is not evaluated, so no alias is ever established.
              ;; Any alias resolves to a synthetic ns: we are checking syntax,
              ;; not resolving vars, and a real resolution would need loading.
              tr/*alias-map* (fn [alias] (symbol (str "check-ai-sweep.unresolved." alias)))]
      (loop [n 0]
        (let [form (try
                     (tr/read {:eof eof} rdr)
                     (catch Exception e
                       ;; Message is the READER's message only. The marker and
                       ;; the filename are sweep!'s job; carrying them here too
                       ;; printed both twice on one line. The label survives in
                       ;; ex-data for programmatic callers.
                       (throw (ex-info (.getMessage e) {:file label} e))))]
          (if (identical? eof form)
            n
            (recur (inc n))))))))

(defn parse-file
  "parse-source for a java.io.File. Returns the form count."
  [f]
  (parse-source (.getPath f) (slurp f)))

(defn clj-files
  "Every .clj file under `dir`, recursively, in deterministic path order."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (filter #(clojure.string/ends-with? (.getName %) ".clj"))
       (sort-by #(.getPath %))))

(defn sweep!
  "Parse every .clj under `dir`. Returns the file count. Throws on a reader
   error, naming the file, and throws if the directory yielded NOTHING.

   The empty case is not a pass. A sweep of zero files is indistinguishable
   from a healthy tree in its output, so a moved/renamed directory would
   silently reopen #184 behind a green tick — which is this issue family's
   whole failure mode. Both sibling guards added alongside this one (the
   Makefile shell-test glob, the #129 ratchet) check their own emptiness; this
   one was the only that did not, and a review panel caught it."
  [dir]
  (let [files (clj-files dir)]
    (when (empty? files)
      (throw (ex-info (str "AI-PARSE-ERROR swept 0 .clj files under " dir
                           " - wrong working directory, or the tree moved?")
                      {:dir dir})))
    (doseq [f files]
      (try
        (parse-file f)
        (catch Exception e
          ;; PRINT before rethrowing. clojure.main reports the ROOT CAUSE of an
          ;; exception chain, so the wrapper's message — the only thing that
          ;; names the FILE — never reaches the operator, and check-ai.sh's
          ;; AI-PARSE-ERROR grep never fires. Verified: without this line a
          ;; broken ai_client_init.clj failed with a bare
          ;; "[line 144, col 1] Unexpected EOF" and no filename at all.
          (println "AI-PARSE-ERROR" (.getPath f) "-" (.getMessage e))
          (flush)
          (throw e))))
    (count files)))
