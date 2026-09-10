(ns ai-encounter-identity-test
  "Issue #198: the client's answer to \"which ICE is being encountered\".

   game.core.diffs/encounters-summary emits a map whenever an encounter is
   live, but `encounter-ice-summary` DROPS :ice when it cannot resolve the
   card, so the honest minimum payload for a live encounter is
   {:encounter-count 1}. core/encountered-ice used to fall back to the
   position-derived ICE in that state — and a forced encounter's suspended
   :position points at a DIFFERENT card, so every surface named, and offered
   actions on, the wrong ICE.

   Also here, because it shipped on the same branch: the Runner-side display
   printed the 'wire has not named its ICE' text at every FULLY-BROKEN
   encounter (marquee game A, 2026-09-06). That was not the #198 mechanism —
   the ICE was on the wire — but the branch condition asked 'is there an
   encounter' where it meant 'is there an encounter nobody can name'."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [test-helpers :refer [mock-client-state with-mock-state]]
            [ai-state :as ai-state]
            [ai-core :as core]
            [ai-display :as display]
            [ai-run-runner-handlers :as runner-handlers]
            [ai-websocket-client-v2 :as ws]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def karuna-cid "karuna-1")

(defn karuna
  "Karunā as the wire serializes it mid-encounter, installed and rezzed
   protecting R&D. `subs` is the full :subroutines vector."
  [subs]
  {:cid karuna-cid :title "Karunā" :type "ICE" :rezzed true
   :zone ["servers" "rd" "ices"] :side "Corp" :strength 4
   :subtypes ["Sentry"] :subroutines subs})

(def two-broken
  [{:label "Do 2 net damage. The Runner may jack out." :broken true :fired false}
   {:label "Do 2 net damage." :broken true :fired false}])

(def two-broken-one-fired
  [{:label "Break subroutine" :broken true :fired false}
   {:label "Break subroutine" :broken true :fired false}
   {:label "End the run" :broken false :fired true}])

(defn encounter-state
  "Runner mid-encounter with `ice` installed at position 1 on R&D. `encounters`
   is the wire's encounter summary — pass nil to model an older serialization
   with no summary at all, or {:encounter-count 1} for an unnameable one."
  [ice encounters & {:keys [side] :or {side "runner"}}]
  (mock-client-state
   :side side
   :game-state
   (cond-> {:active-player "runner" :turn 4
            :run {:phase "encounter-ice" :position 1 :server ["rd"] :no-action false}
            :runner {:click 2 :credit 5 :hand []
                     :prompt-state (when (= side "runner")
                                     {:msg "You are encountering Karunā" :prompt-type "run"})}
            :corp {:click 0 :credit 5 :hand []
                   :servers {:rd {:ices [ice]}}
                   :prompt-state (when (= side "corp")
                                   {:msg "Waiting for Runner" :prompt-type "waiting"})}}
     encounters (assoc :encounters encounters))))

(defn- priority-block
  "The `prompt`/`status` run-window guidance for the current mock state."
  [side]
  (with-out-str
    (display/print-run-window-priority!
     @ai-state/client-state
     (get-in @ai-state/client-state [:game-state :run])
     "encounter-ice" side)))

;; ============================================================================
;; The Karunā sighting: a NAMED, fully-broken encounter is a plain pass
;; ============================================================================

(deftest a-fully-broken-named-encounter-is-a-plain-continue
  ;; The display fixture the #92 tests used had NO :encounters key, so the
  ;; all-broken assertion stayed green while the real wire — which always
  ;; carries the summary during an encounter — took the unnameable branch.
  (testing "Runner, every sub broken, ICE on the wire: continue passes, and the
            wire is NOT accused of failing to name the ICE"
    (with-mock-state (encounter-state (karuna two-broken)
                                      {:ice (karuna two-broken) :encounter-count 1})
      (let [out (priority-block "runner")]
        (is (re-find #"(?i)use 'continue' to pass priority" out)
            (str "all subs broken: continue is the pass, got:\n" out))
        (is (not (str/includes? out "has not named its ICE"))
            (str "the ICE IS named on the wire; got:\n" out))))))

;; ============================================================================
;; Brân: two broken plus one fired is neither "all broken" nor "all resolved"
;; ============================================================================

(deftest a-mixed-broken-and-fired-pass-does-not-claim-all-broken
  (testing "the pass line names both outcomes when the subs split between them"
    (let [sent (atom [])]
      (with-mock-state (encounter-state (karuna two-broken-one-fired)
                                        {:ice (karuna two-broken-one-fired) :encounter-count 1})
        (reset! runner-handlers/passed-ice-encounter nil)
        (reset! runner-handlers/last-waiting-status nil)
        (with-redefs [ws/send-message! (fn [t d] (swap! sent conj {:type t :data d}) true)]
          (let [out (with-out-str
                      (runner-handlers/handle-runner-pass-broken-ice
                       {:side "runner" :run-phase "encounter-ice"
                        :state @ai-state/client-state :gameid "g1" :my-prompt nil}))]
            (is (some #(= "continue" (get-in % [:data :command])) @sent)
                (str "the pass is still sent, got: " @sent))
            (is (not (re-find #"All subs broken on" out))
                (str "one sub FIRED; 'all broken' is a false claim, got:\n" out))
            (is (re-find #"(?i)broken or resolved" out)
                (str "the mixed case should name both, got:\n" out))))))))
