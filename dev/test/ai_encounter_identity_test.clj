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
            [ai-run-corp-handlers :as corp-handlers]
            [ai-card-actions :as card-actions]
            [ai-connection :as connection]
            [ai-runs :as runs]
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
                   ;; The Corp's prompt mid-encounter is the RUN prompt (paid
                   ;; abilities), not a "waiting" one — a waiting prompt means the
                   ;; engine is blocked on the opponent's choice, and #102's guard
                   ;; rightly refuses to wake a seat in that state.
                   :prompt-state (when (= side "corp")
                                   {:msg "You may use paid abilities" :prompt-type "run"})}}
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
            (is (re-find #"broken or fired on Karunā \(2 broken, 1 fired\)" out)
                (str "the mixed case names both outcomes with counts, got:\n" out))))))))

;; ============================================================================
;; #198 proper: the authority does not substitute a card
;; ============================================================================

(def unnameable
  "The honest minimum payload for a live encounter whose card the engine could
   not resolve: encounters-summary always stamps :encounter-count, and
   select-non-nil-keys drops the nil :ice."
  {:encounter-count 1})

(deftest encountered-ice-answers-nil-not-the-positional-card
  (testing "summary present, no :ice, position pointing at a rezzed ICE → nil"
    (with-mock-state (encounter-state (karuna two-broken) unnameable)
      (let [st @ai-state/client-state]
        (is (nil? (core/encountered-ice st))
            "the position's Karunā is NOT the card being encountered")
        (is (false? (core/encounter-ice-active? st (core/encountered-ice st))))
        (is (true? (core/unnameable-encounter? st)))
        (is (nil? (core/encounter-key st))
            "no key: the position belongs to a different card and :encounter-count is depth, not identity"))))
  (testing "no summary at all → the positional card (older serializations)"
    (with-mock-state (encounter-state (karuna two-broken) nil)
      (let [st @ai-state/client-state]
        (is (= karuna-cid (:cid (core/encountered-ice st))))
        (is (false? (core/unnameable-encounter? st)))
        (is (= karuna-cid (core/encounter-key st))))))
  (testing "summary with :ice → the summary's card, whatever the position says"
    (let [forced {:cid "archangel-9" :title "Archangel" :zone ["hand"] :subroutines []}]
      (with-mock-state (encounter-state (karuna two-broken) {:ice forced :encounter-count 1})
        (let [st @ai-state/client-state]
          (is (= "archangel-9" (:cid (core/encountered-ice st))))
          (is (false? (core/unnameable-encounter? st)))
          (is (= "archangel-9" (core/encounter-key st))))))))

(deftest manual-fire-subs-refuses-at-an-unnameable-encounter
  ;; ai-card-actions/fire-unbroken-subs! kept its own copy of the fallback, so
  ;; the cid gate compared the named card against the POSITIONAL card and let a
  ;; fire through for an ICE nobody was encountering (plan-review MAJOR).
  (let [sent (atom [])
        unbroken [{:label "Do 2 net damage." :broken false :fired false}]]
    (with-mock-state (encounter-state (karuna unbroken) unnameable :side "corp")
      (with-redefs [ws/send-message! (fn [t d] (swap! sent conj {:type t :data d}) true)]
        (let [out (with-out-str (card-actions/fire-unbroken-subs! "Karunā"))]
          (is (empty? @sent)
              (str "nothing may go on the wire for a card nobody can name, sent: " @sent))
          (is (re-find #"has not named its ICE" out)
              (str "the refusal names the state, got:\n" out))
          (is (re-find #"resync 0000" out)
              (str "the recovery command is executable as printed (has the game id), got:\n" out))
          (is (re-find #"umpire-ping corp" out)
              (str "the escalation names the side, got:\n" out)))))))

;; ============================================================================
;; Helpers for the automation tests
;; ============================================================================

(defn- with-out-str-and-result
  "Capture BOTH what `f` printed and what it returned."
  [f]
  (let [result (atom nil)
        out (with-out-str (reset! result (f)))]
    {:out out :result @result}))

;; ============================================================================
;; The guard is side-neutral and reaches every surface
;; ============================================================================

(def one-unbroken
  [{:label "Do 2 net damage." :broken false :fired false}])

(deftest every-display-surface-refuses-to-name-the-positional-card
  (doseq [side ["runner" "corp"]]
    (testing (str side ": prompt/status priority block at an unnameable encounter")
      (with-mock-state (encounter-state (karuna one-unbroken) unnameable :side side)
        (let [out (priority-block side)]
          (is (re-find #"has not named its ICE" out) (str side " got:\n" out))
          (is (not (re-find #"Karunā" out))
              (str side ": the positional card must not be named, got:\n" out))
          (is (not (re-find #"(?i)use 'continue' to pass priority|fire-subs <|tank \"" out))
              (str side ": no card-specific or pass steer, got:\n" out))
          (is (re-find #"resync 0000" out) (str side ": executable resync, got:\n" out))
          (is (re-find (re-pattern (str "umpire-ping " side)) out)
              (str side ": escalation names the side, got:\n" out)))))
    (testing (str side ": diagnose-blocker, WITH a run")
      (with-mock-state (encounter-state (karuna one-unbroken) unnameable :side side)
        (let [out (with-out-str (display/show-blocker-diagnosis))]
          (is (re-find #"has not named its ICE" out) (str side " got:\n" out))
          (is (not (re-find #"Karunā" out)) (str side " got:\n" out)))))
    (testing (str side ": diagnose-blocker, run-LESS unnameable encounter (Ganked!-shaped)")
      (with-mock-state (-> (encounter-state (karuna one-unbroken) unnameable :side side)
                           (update :game-state dissoc :run)
                           (update :last-state dissoc :run))
        (let [out (with-out-str (display/show-blocker-diagnosis))]
          (is (re-find #"has not named its ICE" out)
              (str side ": the runless gate used to need :ice (live-encounter?), got:\n" out))
          (is (not (re-find #"(?i)use: continue|Use: wait" out))
              (str side ": no generic steer, got:\n" out)))))))

(deftest a-runner-who-already-passed-a-broken-encounter-is-told-to-wait
  ;; Plan-review MINOR: round 1's `:else` said "continue passes" to a seat whose
  ;; pass the ledger already records; the #98 send guard refuses that continue.
  (with-mock-state (encounter-state (karuna two-broken)
                                    {:ice (karuna two-broken) :encounter-count 1 :no-action "runner"})
    (let [out (priority-block "runner")]
      (is (re-find #"already passed" out) (str "got:\n" out))
      (is (not (re-find #"(?i)use 'continue' to pass priority" out)) (str "got:\n" out)))))

(deftest the-corp-sibling-names-mixed-broken-and-fired-with-counts
  (let [sent (atom [])
        subs [{:label "a" :broken true :fired false} {:label "b" :broken false :fired true}]]
    (with-mock-state (encounter-state (karuna subs) {:ice (karuna subs) :encounter-count 1} :side "corp")
      (corp-handlers/reset-state!)
      (with-redefs [ws/send-message! (fn [t d] (swap! sent conj {:type t :data d}) true)]
        (let [out (with-out-str
                    (corp-handlers/handle-corp-all-subs-resolved
                     {:side "corp" :run-phase "encounter-ice" :state @ai-state/client-state
                      :gameid "g1" :my-prompt {:msg "paid abilities" :prompt-type "run"}}))]
          (is (re-find #"broken or fired on Karunā \(1 broken, 1 fired\)" out) (str "got:\n" out)))))))

;; ============================================================================
;; wait: a distinct wake reason, ranked above ownership
;; ============================================================================

(deftest wait-wakes-both-seats-with-the-unnameable-reason
  (doseq [side ["runner" "corp"]]
    (with-redefs [ai-state/get-cursor (fn [] 10)]
      (with-mock-state (encounter-state (karuna one-unbroken) unnameable :side side)
        (let [result (core/wait-for-relevant-diff {:timeout 0 :verbose false})]
          (is (= :unnameable-encounter (:reason result))
              (str side ": :my-run-window would say `fire-subs <ice>` for a card nobody can name, got: " result)))
        (let [out (with-out-str (core/wait-for-relevant-diff {:timeout 0 :verbose true}))]
          (is (re-find #"has not named its ICE" out)
              (str side ": the wake must be decoded, got:\n" out)))))))

;; ============================================================================
;; The run automation: one resync for the owner, a park after it, an idle for
;; the other seat — and never "Run complete"
;; ============================================================================

(defn- handler-ctx [side]
  {:side side :state @ai-state/client-state :gameid (:gameid @ai-state/client-state)
   :run-phase "encounter-ice" :strategy {} :my-prompt nil})

(defn- with-fresh-run-state [f]
  (runs/reset-strategy!)
  (reset! runs/last-waiting-status nil)
  (try (f) (finally (runs/reset-strategy!))))

(deftest the-guard-is-silent-at-a-named-encounter-and-off-a-board
  (with-fresh-run-state
    (fn []
      (with-mock-state (encounter-state (karuna one-unbroken) {:ice (karuna one-unbroken) :encounter-count 1})
        (is (nil? (runs/handle-unnameable-encounter (handler-ctx "runner")))))
      (with-mock-state (encounter-state (karuna one-unbroken) nil)
        (is (nil? (runs/handle-unnameable-encounter (handler-ctx "runner"))))))))

(deftest the-non-owner-idles-and-never-resyncs
  ;; Nobody has passed → the Runner owns the window; the Corp must not leave its
  ;; post (plan-review MAJOR: parking the non-owner is the nobody-home wedge).
  (with-fresh-run-state
    (fn []
      (let [resyncs (atom 0)]
        (with-redefs [connection/resync-and-wait! (fn [_] (swap! resyncs inc) :synced)]
          (with-mock-state (encounter-state (karuna one-unbroken) unnameable :side "corp")
            (let [r (runs/handle-unnameable-encounter (handler-ctx "corp"))]
              (is (= :waiting-for-opponent (:status r)) (str "got: " r))
              (is (zero? @resyncs) "the non-owner does not spend the resync")
              (is (false? @runs/unnameable-resync-spent)))))))))

(deftest the-owner-resyncs-once-then-parks
  (with-fresh-run-state
    (fn []
      (let [resyncs (atom 0)]
        (with-redefs [connection/resync-and-wait! (fn [_] (swap! resyncs inc) :synced)]
          (with-mock-state (encounter-state (karuna one-unbroken) unnameable :side "runner")
            (let [r1 (with-out-str-and-result #(runs/handle-unnameable-encounter (handler-ctx "runner")))
                  r2 (with-out-str-and-result #(runs/handle-unnameable-encounter (handler-ctx "runner")))
                  r3 (with-out-str-and-result #(runs/handle-unnameable-encounter (handler-ctx "runner")))]
              (is (= :action-taken (:status (:result r1))) (str "first tick re-reads the fresh board, got: " r1))
              (is (= 1 @resyncs))
              (is (= :decision-required (:status (:result r2))) (str "second tick parks, got: " r2))
              (is (= :unnameable-encounter (:wake-reason (:result r2))))
              (is (re-find #"has not named its ICE" (:out r2)) (str "the park prints the recovery, got:\n" (:out r2)))
              (is (= 1 @resyncs) "ONE resync per run — the second tick did not resync again")
              (is (= :decision-required (:status (:result r3))))
              (is (not (re-find #"has not named its ICE" (:out r3)))
                  (str "the park text is printed once, not every tick, got:\n" (:out r3)))
              (is (true? (runs/unnameable-encounter-result? (:result r2)))
                  "the bot loops key on this predicate"))))))))

(deftest a-resync-that-does-not-land-is-a-park-not-an-action
  ;; CRITICAL from both plan-review seats: returning :action-taken on an EMPTY
  ;; client sent the next tick into handle-run-complete's "✅ Run complete".
  (with-fresh-run-state
    (fn []
      (with-redefs [connection/resync-and-wait! (fn [_] :resync-failed)]
        (with-mock-state (encounter-state (karuna one-unbroken) unnameable :side "runner")
          (let [{:keys [out result]} (with-out-str-and-result
                                       #(runs/handle-unnameable-encounter (handler-ctx "runner")))]
            (is (= :decision-required (:status result)) (str "got: " result))
            (is (not= :action-taken (:status result)))
            (is (re-find #"has not named its ICE" out) (str "got:\n" out))))))))

(deftest the-latch-is-per-run
  (with-fresh-run-state
    (fn []
      (reset! runs/unnameable-resync-spent true)
      (runs/reset-strategy!)
      (is (false? @runs/unnameable-resync-spent) "reset-strategy! (run start / run end) re-arms the one resync"))))

(deftest the-chain-terminates-instead-of-declaring-the-run-complete
  ;; Through the REAL handler chain: two ticks on the unnameable board, resync
  ;; stubbed. The first re-reads, the second parks on a status the loop treats
  ;; as terminal — never "✅ Run complete", never handle-unexpected-state's
  ;; endless :waiting-for-opponent, and no continue on the wire.
  (with-fresh-run-state
    (fn []
      (let [sent (atom [])]
        (with-redefs [connection/resync-and-wait! (fn [_] :synced)
                      ws/send-message! (fn [t d] (swap! sent conj {:type t :data d}) true)]
          (with-mock-state (encounter-state (karuna one-unbroken) unnameable :side "runner")
            (let [{out1 :out r1 :result} (with-out-str-and-result runs/continue-run!)
                  {out2 :out r2 :result} (with-out-str-and-result runs/continue-run!)]
              (is (= :action-taken (:status r1)) (str "got: " r1))
              (is (= :decision-required (:status r2)) (str "got: " r2))
              (is (not (re-find #"Run complete" (str out1 out2))) (str "got:\n" out1 out2))
              (is (empty? @sent) (str "nothing goes on the wire for a card nobody can name, sent: " @sent)))))))))

(deftest force-mode-still-outranks-the-guard
  ;; `continue --force` is the seat's manual override and the bots' way out;
  ;; the guard stops the AUTOMATION from guessing, not a human who has read the board.
  (with-fresh-run-state
    (fn []
      (let [sent (atom [])]
        (with-redefs [ws/send-message! (fn [t d] (swap! sent conj {:type t :data d}) true)]
          (with-mock-state (encounter-state (karuna one-unbroken) unnameable :side "runner")
            (let [r (with-out-str-and-result #(runs/continue-run! "--force"))]
              (is (= :action-taken (:status (:result r))) (str "got: " r))
              (is (some #(= "continue" (get-in % [:data :command])) @sent)
                  (str "the forced pass goes out, sent: " @sent)))))))))

(deftest the-guard-outranks-an-opponent-wait-marker
  ;; Plan-review CRITICAL (Sol): handle-opponent-wait wins every tick while an
  ;; indicate-action sits in the last five log lines, and waiting adds no lines.
  (with-fresh-run-state
    (fn []
      (let [resyncs (atom 0)]
        (with-redefs [connection/resync-and-wait! (fn [_] (swap! resyncs inc) :synced)
                      ws/send-message! (fn [_ _] true)]
          (with-mock-state (-> (encounter-state (karuna one-unbroken) unnameable :side "runner")
                               (assoc-in [:game-state :log] [{:text "[!] Please pause, Corp is acting."}]))
            (let [r (with-out-str-and-result runs/continue-run!)]
              (is (= 1 @resyncs) (str "the one resync must not be starved by the WAIT marker, got: " r)))))))))
