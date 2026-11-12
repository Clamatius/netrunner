(ns checkout-target-test
  "Pins #317: the behavioral test RESOLVES the checkout it drives instead of
   carrying one, refuses an undrivable target by name, and takes everything from
   that one tree.

   The defect was silent, not wrong-looking. Three shell-out sites in
   ai_behavioral_test.clj carried an absolute home-directory path as their :dir
   (quoted in #317, not reproduced here). That path existed, so nothing failed;
   it was a THIRD checkout, stale since 2026-10-23, and not the caller's tree.
   `make test-behavioral` therefore ran THAT tree's reset.sh, which restarts both
   singleton client REPLs rooted in itself and starts a fresh game — so it put
   both clients on Oct-23 code and destroyed whatever game was in progress. A
   takeover, not a mis-send. (The game server on 7888 is not restarted, so the
   takeover covers the clients only.)

   What is pinned here, each case a defect a review round reproduced:
   - the env var wins; blank or whitespace is not a target
   - an undrivable target throws, naming the directory and the env var
   - a target with only SOME of the required scripts is still refused
   - the EDN definitions come from the target tree, not the invoking one
   - the refusal does not give back the advice that selects nothing

   NOT here any more: a repo-wide structural guard asserting that no tracked code
   file names an absolute home path. It was written in this round, reviewed
   twice, and removed — 13 of the round's 18 findings were in it, including two
   MAJORs, while the repair above drew five. Nobody asked for it, and a repo-wide
   invariant with an exclusion list is a maintenance obligation on a tree that
   merges upstream in bursts. Its full design, and the four traps the rounds
   found in it, are written up in #319; the code is recoverable from
   this round's history if that issue is taken up."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai-behavioral-test :as beh]))

;; ============================================================================
;; resolve-target-dir
;; ============================================================================

(defn- usable-dir!
  "A throwaway directory that looks like a drivable checkout: it carries every
   script the shell-outs invoke. Returns its absolute path."
  []
  (let [root (java.nio.file.Files/createTempDirectory
              "checkout-target" (into-array java.nio.file.attribute.FileAttribute []))
        dir (.toFile root)]
    (doseq [script beh/required-scripts]
      (let [f (io/file dir script)]
        (io/make-parents f)
        (spit f "#!/bin/sh\nexit 0\n")))
    (.getAbsolutePath dir)))

(defn- empty-dir!
  "A throwaway directory carrying none of the scripts."
  []
  (.getAbsolutePath
   (.toFile (java.nio.file.Files/createTempDirectory
             "checkout-target-empty" (into-array java.nio.file.attribute.FileAttribute [])))))

(deftest resolve-target-dir-picks-a-target
  (let [env-dir (usable-dir!)
        cwd-dir (usable-dir!)]

    (testing "an explicit env value wins over the invoking checkout"
      ;; The whole point of the env var: the operator can name the tree they
      ;; want taken over, which need not be the one `make` ran in. (NOT "the
      ;; tree whose REPLs are listening" — that is the retracted framing the
      ;; refusal-message test below forbids: after the first reset the answer
      ;; is always whatever was chosen.)
      (is (= env-dir (beh/resolve-target-dir env-dir cwd-dir))))

    (testing "no env value falls back to the invoking checkout"
      (is (= cwd-dir (beh/resolve-target-dir nil cwd-dir))))

    (testing "a blank or whitespace env value is not a target"
      ;; `NETRUNNER_BEHAVIORAL_DIR=` exports an empty string, which must not
      ;; become a :dir of "" — that resolves to the JVM's cwd by accident.
      (is (= cwd-dir (beh/resolve-target-dir "" cwd-dir)))
      (is (= cwd-dir (beh/resolve-target-dir "   " cwd-dir))))

    (testing "a surrounding-whitespace env value is still that directory"
      (is (= env-dir (beh/resolve-target-dir (str "  " env-dir "  ") cwd-dir))))))

(deftest resolve-target-dir-refuses-an-undrivable-target
  (let [bare (empty-dir!)
        usable (usable-dir!)]

    (testing "a target missing the scripts throws rather than being driven"
      ;; The old literal's failure mode was to run SOMETHING, in the wrong tree.
      ;; Refusing is only an improvement if the refusal says which directory and
      ;; how to fix it, so assert the message carries both.
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (beh/resolve-target-dir bare usable)))]
        (is (str/includes? (ex-message e) bare)
            (str "refusal must name the directory it rejected: " (ex-message e)))
        (is (str/includes? (ex-message e) beh/target-dir-env)
            (str "refusal must name the env var that fixes it: " (ex-message e)))
        (is (= :env (:source (ex-data e)))
            "an env-supplied target must be reported as env-supplied")
        (is (= (vec beh/required-scripts) (:missing (ex-data e)))
            "every missing script is listed, not just the first")))

    (testing "an undrivable INVOKING checkout is refused too, and says so"
      ;; Not the same arm: this is the default path, and it is the one a seat
      ;; hits by running `make test-behavioral` from somewhere unexpected.
      (let [e (is (thrown? clojure.lang.ExceptionInfo
                           (beh/resolve-target-dir nil bare)))]
        (is (= :cwd (:source (ex-data e))))
        (is (str/includes? (ex-message e) bare))))

    (testing "a target with only SOME of the scripts is still refused"
      ;; A half-populated tree is the shape a partial checkout actually has;
      ;; `remove` over the list must not be satisfied by one hit.
      (let [half (empty-dir!)
            one (first beh/required-scripts)]
        (io/make-parents (io/file half one))
        (spit (io/file half one) "#!/bin/sh\nexit 0\n")
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (beh/resolve-target-dir half nil)))]
          (is (= (vec (rest beh/required-scripts)) (:missing (ex-data e)))
              "only the genuinely absent scripts are reported"))))))

;; ============================================================================
;; One tree supplies everything (review finding)
;; ============================================================================

(deftest test-definitions-come-from-the-target-tree
  ;; The scripts came from target-dir while the EDN definitions were slurped
  ;; relative to the JVM's cwd, so NETRUNNER_BEHAVIORAL_DIR mixed two trees in
  ;; one run. Pin that they now come from the same place the commands go.
  (let [dir (usable-dir!)
        marker {:scenarios {:from-target {:desc "marker" :setup []}} :tests []}]
    (let [f (io/file dir "dev/test/behavioral/continue_run_tests.edn")]
      (io/make-parents f)
      (spit f (pr-str marker)))
    (with-redefs [beh/target-dir (delay dir)]
      (is (= marker (beh/read-test-definitions))
          "definitions must be read from the target tree, not the invoking one"))))

(deftest the-refusal-does-not-give-advice-that-selects-nothing
  ;; A review seat read the script chain: reset.sh is $SCRIPT_DIR-relative down
  ;; to start-ai-client-repl.sh's `cd "$SCRIPT_DIR/.."`, so the target's reset
  ;; RESTARTS the client REPLs rooted in the target. The first refusal message
  ;; said to set the env var "to the checkout whose client REPLs are running" —
  ;; which picks nothing, because after the first reset that is always whatever
  ;; you picked. Assert the corrected framing AND the absence of the old one:
  ;; a message is a claim about state, and this one was false.
  (let [msg (try (beh/resolve-target-dir (empty-dir!) nil)
                 (catch clojure.lang.ExceptionInfo e (ex-message e)))]
    (is (str/includes? msg "take over")
        (str "refusal must say the target gets taken over: " msg))
    (is (not (str/includes? msg "whose client REPLs are running"))
        (str "refusal still gives the advice that selects nothing: " msg))))
