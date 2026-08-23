(ns ai-forced-encounter-test
  "Issue #160: a FORCED encounter is unreachable by the run automation.

   An on-access Archangel (or a redirect) puts a live encounter on the wire —
   [:encounters :ice] is populated, and the engine pops that stack only when the
   encounter ends — while [:run :phase] still reads whatever the outer phase was,
   typically \"success\". Every client gate was written as
   (= run-phase \"encounter-ice\"), so at that window:

     * `tank` set a flag and sent nothing (handle-runner-encounter-ice is the ONLY
       thing that turns the flag into the Corp-facing system-msg),
     * a Corp on `monitor-run --persistent --fire-unbroken` never saw a fire
       decision,
     * and the closing pass could be sent by neither seat.

   Both seats then waited on each other. The engine has no such problem: its
   `continue` multimethod dispatches on

       (if (get-current-encounter state) :encounter-ice (:phase (:run @state)))

   — the encounter outranks the phase there. core/at-encounter? is the client
   mirror of that dispatch, and these tests pin it at every gate that used the
   phase string, plus the identity rule (#100/#152): resolve the ICE from the
   wire's own encounter summary, never from :position, which a forced encounter
   breaks by construction."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-core :as core]
            [ai-state :as ai-state]
            [ai-display :as display]
            [ai-runs :as runs]
            [ai-run-corp-decisions :as decisions]
            [ai-run-runner-handlers :as runner-handlers]
            [ai-run-corp-handlers :as corp-handlers]
            [ai-heuristic-runner :as heuristic]
            [ai-websocket-client-v2 :as ws]))

;; ============================================================================
;; Fixtures
;; ============================================================================

(def archangel-cid "29509653-1ec0-459a-a83d-3a8fb63d6168")

(defn archangel
  "The forced-encounter ICE EXACTLY as the wire serializes it under
   [:encounters :ice]. Copied from a real payload dumped out of the engine (see
   game.ai-forced-encounter-wire-test, which asserts this shape against
   game.core.diffs/public-states so it cannot drift silently).

   Three things here were wrong in the first draft of these mocks, and each one
   would have let a broken fix look tested:

     * NO :rezzed key. An on-access Archangel is encountered straight out of HQ —
       it is never installed and never rezzed. Every handler guarded on
       (:rezzed ice), so mocking it true hid the fact that the widened phase
       gates still fell through. This is the trap this repo has hit before: the
       fixture omitted (here, invented) the field the bug lived in.
     * :zone [:hand], not a server. Nothing can reach this card by :position.
     * Subroutines carry ONLY :label — the engine has not stamped :broken/:fired
       on them, and select-non-nil-keys drops what is absent. The client's
       unbroken filter must therefore treat ABSENT as unbroken, which is what
       (not (:broken %)) does. Pass :fired true to model a sub after it resolves.
     * :cid is a UUID STRING, not an integer."
  [& {:keys [broken fired cid] :or {cid archangel-cid}}]
  {:cid cid
   :title "Archangel"
   :type "ICE"
   :zone [:hand]
   :side "Corp"
   :strength 6
   :subtypes ["Ambush" "Code Gate" "Tracer"]
   :subroutines [(cond-> {:label "Trace 6 - Add an installed Runner card to the grip"}
                   broken (assoc :broken true)
                   fired (assoc :fired true))]})

(defn forced-encounter-state
  "Client state at a forced encounter: phase \"success\" (the run is in its
   access step), position 0, NO ice at the position, and a live encounter."
  [& {:keys [side ice no-action log prompt phase position position-ice]
      :or {side "runner" no-action nil log [] prompt nil
           phase "success" position 0 position-ice []}}]
  (let [ice (or ice (archangel))]
    (mock-client-state
     :side side
     :game-state
     (cond-> {:active-player "runner"
              :turn 6
              :run {:server ["servers" "hq"] :position position :phase phase
                    :no-action false}
              :encounters (cond-> {:ice ice :encounter-count 1}
                            no-action (assoc :no-action no-action))
              :forced-encounter 1
              :log log
              :runner {:credit 5 :click 2 :hand [] :rig {}
                       :prompt-state (when (= side "runner") prompt)}
              :corp {:credit 8 :click 3 :hand []
                     :servers {:hq {:ices position-ice}}
                     :prompt-state (when (= side "corp") prompt)}}))))

(def forced-marker
  "The encounter marker a FORCED encounter actually writes. Not
   'encounters Archangel protecting HQ' — that is the INSTALLED form. An
   on-access card is still in its zone, so game.core.to-string/card-str gives
   ' in HQ'. Copied from a real engine log; the invented 'protecting' form in the
   first draft of these mocks hid a CRITICAL (see
   a-stale-signal-from-a-prior-forced-encounter-must-not-authorise-a-fire)."
  {:text "ai-runner encounters Archangel in HQ."})

(def signal-log
  "The log as it reads once the Runner has tanked: the system-msg the Corp's
   runner-signaled-let-fire? scans for, after this encounter's own marker."
  [{:text "ai-runner accesses Archangel from HQ."}
   forced-marker
   {:text "ai-runner indicates to fire all unbroken subroutines on Archangel"}])

;; ============================================================================
;; The authority itself
;; ============================================================================

(deftest at-encounter-mirrors-the-engines-own-dispatch
  (testing "a live encounter outranks the phase string"
    (with-mock-state (forced-encounter-state)
      (is (true? (core/live-encounter? @ai-state/client-state)))
      (is (true? (core/at-encounter? @ai-state/client-state "success"))
          "phase says success; the wire says an encounter is happening")
      (is (= "Archangel" (:title (core/encountered-ice @ai-state/client-state)))
          "the encounter summary is the authority, not :position")))
  (testing "the normal encounter phase still qualifies even with no summary on the wire"
    ;; Widening a gate must never drop the case that already worked: an older
    ;; serialization (or a diff that has not landed) can report the phase with no
    ;; encounter map at all.
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:run {:phase "encounter-ice" :position 1}
                                   :runner {} :corp {}})
      (is (true? (core/at-encounter? @ai-state/client-state "encounter-ice")))))
  (testing "no encounter and an ordinary phase is not an encounter"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:run {:phase "movement" :position 1}
                                   :runner {} :corp {}})
      (is (false? (core/at-encounter? @ai-state/client-state "movement"))))))

(deftest encounter-key-is-identity-not-position
  (testing "two different forced encounters at the SAME position get different keys"
    ;; This is the whole reason the latches moved off :position. A forced
    ;; encounter leaves :position wherever the outer phase left it — usually 0 —
    ;; so a position-keyed 'I already signalled here' latch treats the second
    ;; forced encounter of a run as already handled and sends nothing.
    (let [k1 (with-mock-state (forced-encounter-state :ice (archangel :cid 1))
               (core/encounter-key @ai-state/client-state))
          k2 (with-mock-state (forced-encounter-state :ice (archangel :cid 2))
               (core/encounter-key @ai-state/client-state))]
      (is (not= k1 k2) "same position 0, different encounters")
      (is (= 1 k1))
      (is (= 2 k2))))
  (testing "falls back to position when the wire gives us no encounter"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:run {:phase "movement" :position 3}
                                   :runner {} :corp {}})
      (is (= 3 (core/encounter-key @ai-state/client-state))))))

(deftest encountered-ice-outranks-position-in-core
  (testing "position points at a different card; the encounter summary wins"
    (with-mock-state (forced-encounter-state
                      :position 1
                      :position-ice [{:cid 99 :title "Palisade" :rezzed true
                                      :subroutines [{:broken false :fired false}]}])
      (is (= "Archangel" (:title (core/encountered-ice @ai-state/client-state)))
          "the position-derived ICE is a DIFFERENT card here")
      (is (= archangel-cid (core/encounter-key @ai-state/client-state))))))

;; ============================================================================
;; Runner seat: tank must actually send
;; ============================================================================

(deftest tank-signals-the-corp-at-a-forced-encounter
  (testing "#160: `tank` was a flag that sent nothing outside phase encounter-ice"
    (let [sent (atom [])]
      (with-mock-state (forced-encounter-state
                        :side "runner"
                        :log [forced-marker])
        (reset! runner-handlers/signaled-fire-encounter nil)
        (reset! runner-handlers/last-waiting-status nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (runs/set-strategy! {:tank #{"Archangel"}})
          (try
            (let [result (runner-handlers/handle-runner-encounter-ice
                          {:side "runner"
                           :run-phase "success"
                           :state @ai-state/client-state
                           :gameid "g1"
                           :strategy (runs/get-strategy)
                           :my-prompt nil})]
              (is (= :waiting-for-corp-fire (:status result))
                  (str "tank was authorized; expected the Corp-wait, got: " result))
              (is (= "Archangel" (:ice result)))
              (is (some #(and (= "system-msg" (get-in % [:data :command]))
                              (re-find #"unbroken subroutines on Archangel"
                                       (str (get-in % [:data :args :msg]))))
                        @sent)
                  (str "the let-subs-fire signal must actually go out, got: " @sent)))
            (finally (runs/reset-strategy!))))))))

(deftest unauthorized-forced-encounter-asks-instead-of-falling-through
  (testing "#160: with no tank authorization the seat gets the break/tank decision, not silence"
    (let [sent (atom [])]
      (with-mock-state (forced-encounter-state :side "runner")
        (reset! runner-handlers/signaled-fire-encounter nil)
        (reset! runner-handlers/last-waiting-status nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [result (runner-handlers/handle-runner-encounter-ice
                        {:side "runner"
                         :run-phase "success"
                         :state @ai-state/client-state
                         :gameid "g1"
                         :strategy {}
                         :my-prompt nil})]
            (is (= :fire-decision-required (:status result))
                (str "expected an explicit decision, got: " result))
            (is (= "Archangel" (:ice result)))
            (is (empty? @sent) "nothing may be sent without authorization")))))))

(deftest run-level-no-action-does-not-veto-a-forced-encounter
  (testing "#160: the encounter's own pass ledger decides, not the outer run's"
    ;; [:run :no-action] holds whatever the OUTER phase left behind. Reading it
    ;; as 'the Corp has passed this encounter' made the handler bail before it
    ;; ever considered the tank.
    (let [sent (atom [])
          st (-> (forced-encounter-state
                  :side "runner"
                  :log [forced-marker])
                 (assoc-in [:game-state :run :no-action] "corp"))]
      (with-mock-state st
        (reset! runner-handlers/signaled-fire-encounter nil)
        (reset! runner-handlers/last-waiting-status nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (runs/set-strategy! {:tank #{"Archangel"}})
          (try
            (let [result (runner-handlers/handle-runner-encounter-ice
                          {:side "runner"
                           :run-phase "success"
                           :state @ai-state/client-state
                           :gameid "g1"
                           :strategy (runs/get-strategy)
                           :my-prompt nil})]
              (is (= :waiting-for-corp-fire (:status result))
                  (str "run-level :no-action is not this encounter's ledger, got: " result)))
            (finally (runs/reset-strategy!)))))))
  (testing "the ENCOUNTER's ledger naming the corp hands us to the pass handler, not to nobody"
    ;; The first draft of this test asserted only the nil and called it correct —
    ;; pinning a deadlock instead of detecting it (guest panel CRITICAL). With the
    ;; Corp recorded as the passer, the engine ends the encounter on OUR continue
    ;; and the subs never fire (game.ai-forced-encounter-wire-test). So the
    ;; fire-decision handler correctly declines the state, and something must
    ;; catch it.
    (let [sent (atom [])]
      (with-mock-state (forced-encounter-state :side "runner" :no-action "corp")
        (reset! runner-handlers/signaled-fire-encounter nil)
        (reset! runner-handlers/passed-ice-encounter nil)
        (reset! runner-handlers/last-waiting-status nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (is (nil? (runner-handlers/handle-runner-encounter-ice
                     {:side "runner"
                      :run-phase "success"
                      :state @ai-state/client-state
                      :gameid "g1"
                      :strategy {}
                      :my-prompt nil}))
              "not a fire decision — the Corp is not going to fire")
          ;; SOMETHING must own this window; the pass handler does.
          (runner-handlers/handle-runner-pass-broken-ice
           {:side "runner"
            :run-phase "success"
            :state @ai-state/client-state
            :gameid "g1"
            :my-prompt nil})
          (is (some #(= "continue" (get-in % [:data :command])) @sent)
              (str "the closing pass must be sent — it is free here. got: " @sent)))))))

(deftest corp-declined-is-reported-as-a-free-pass-not-a-refusal
  ;; The seat-facing half of the same defect: the decline hint said flatly that
  ;; `continue` will NOT pass while subs are unbroken. True while the Corp still
  ;; owns its half of the window; a stall-inducing lie once it has passed.
  (testing "the corp-declined form names continue as the way out"
    (let [out (clojure.string/join
               "\n" (display/runner-encounter-decline-hint-lines "Archangel" 1 false true))]
      (is (re-find #"(?i)corp has passed" out) (str "got:\n" out))
      (is (re-find #"(?i)`continue`" out) (str "got:\n" out))
      (is (not (re-find #"will NOT pass" out))
          (str "the #92 rule does not hold in this state, got:\n" out))))
  (testing "and the ordinary form still refuses continue"
    (let [out (clojure.string/join
               "\n" (display/runner-encounter-decline-hint-lines "Archangel" 1 false false))]
      (is (re-find #"will NOT pass" out) (str "got:\n" out)))))

(deftest an-outer-window-pass-does-not-count-as-passing-the-encounter
  ;; i-already-passed-run-window? used to OR the two ledgers. During a forced
  ;; encounter inside movement, run-level names the passer of the SUSPENDED
  ;; movement window — ORing it in told the seat it had already passed an
  ;; encounter it had not touched, and both send chokepoints then swallowed the
  ;; real closing pass (guest panel CRITICAL).
  (let [st (-> (forced-encounter-state :side "corp" :phase "movement")
               (assoc-in [:game-state :run :no-action] "corp"))]
    (with-mock-state st
      (is (false? (core/i-already-passed-run-window? @ai-state/client-state "corp"))
          "the movement pass is not this encounter's pass")))
  (testing "the encounter's own ledger still counts"
    (with-mock-state (forced-encounter-state :side "corp" :no-action "corp")
      (is (true? (core/i-already-passed-run-window? @ai-state/client-state "corp")))))
  (testing "and with no encounter live the run ledger is still the answer"
    (with-mock-state (mock-client-state
                      :side "corp"
                      :game-state {:run {:phase "movement" :position 1 :no-action "corp"}
                                   :runner {} :corp {}})
      (is (true? (core/i-already-passed-run-window? @ai-state/client-state "corp"))))))

(deftest runner-can-close-a-forced-encounter-once-subs-resolve
  (testing "#160: with every sub resolved the Runner owes a continue; the phase gate blocked it"
    (let [sent (atom [])]
      (with-mock-state (forced-encounter-state
                        :side "runner"
                        :ice (archangel :fired true))
        (reset! runner-handlers/passed-ice-encounter nil)
        (reset! runner-handlers/last-waiting-status nil)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (runner-handlers/handle-runner-pass-broken-ice
           {:side "runner"
            :run-phase "success"
            :state @ai-state/client-state
            :gameid "g1"
            :my-prompt nil})
          (is (some #(= "continue" (get-in % [:data :command])) @sent)
              (str "expected the closing pass, got: " @sent)))))))

;; ============================================================================
;; Corp seat: the answering half
;; ============================================================================

(deftest corp-fire-unbroken-answers-a-forced-encounter
  (testing "#160: --fire-unbroken never recognised the fire decision outside phase encounter-ice"
    (let [sent (atom [])]
      (with-mock-state (forced-encounter-state :side "corp" :log signal-log)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-state/get-prompt (constantly nil)]
          (let [result (corp-handlers/handle-corp-fire-unbroken
                        {:side "corp"
                         :run-phase "success"
                         :strategy {:fire-unbroken true}
                         :state @ai-state/client-state
                         :gameid "g1"})]
            (is (= :auto-fired-subs (:action result))
                (str "expected the fire, got: " result))
            (is (= archangel-cid (:fired-at-encounter result))
                "the latch records the ENCOUNTER, not the position")
            (let [fire (first (filter #(= "unbroken-subroutines" (get-in % [:data :command])) @sent))]
              (is (some? fire) (str "no fire command sent, got: " @sent))
              (is (= archangel-cid (get-in fire [:data :args :card :cid]))
                  "must fire the ENCOUNTERED ice's subs"))))))))

(deftest corp-fire-latch-is-per-encounter-not-per-position
  (testing "a latch from a PRIOR forced encounter at the same position must not suppress this one"
    (let [sent (atom [])]
      (with-mock-state (forced-encounter-state :side "corp" :log signal-log)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-state/get-prompt (constantly nil)]
          ;; Position is 0 for both forced encounters; the OLD latch value was 0,
          ;; which would have read as "already fired here".
          (let [result (corp-handlers/handle-corp-fire-unbroken
                        {:side "corp"
                         :run-phase "success"
                         :strategy {:fire-unbroken true :fired-at-encounter 0}
                         :state @ai-state/client-state
                         :gameid "g1"})]
            (is (= :auto-fired-subs (:action result))
                (str "a position-shaped stale latch must not veto a new encounter, got: " result)))))))
  (testing "but the latch for THIS encounter does suppress a re-entry"
    (let [sent (atom [])]
      (with-mock-state (forced-encounter-state :side "corp" :log signal-log)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)
                      ai-state/get-prompt (constantly nil)]
          (is (nil? (corp-handlers/handle-corp-fire-unbroken
                     {:side "corp"
                      :run-phase "success"
                      :strategy {:fire-unbroken true :fired-at-encounter archangel-cid}
                      :state @ai-state/client-state
                      :gameid "g1"}))
              "already fired at this encounter")
          (is (empty? @sent)))))))

(deftest corp-run-decision-classifies-a-forced-encounter
  (testing "#160: the classifier reported nothing, so --fire-if-asked sat on its hands"
    ;; force-ice-encounter calls show-run-prompts, so the Corp really does hold a
    ;; run prompt at a forced encounter — the phase is the only thing that differs.
    (with-mock-state (forced-encounter-state
                      :side "corp"
                      :log signal-log
                      :prompt {:msg "encountering Archangel" :prompt-type "run" :eid 91})
      (let [d (decisions/corp-run-decision @ai-state/client-state)]
        (is (= :fire-unbroken (:kind d)) (str "expected a fire decision, got: " d))
        (is (= "Archangel" (get-in d [:ice :title])))
        (is (= archangel-cid (get-in d [:ice :cid]))
            "named from the encounter summary, not from :position"))))
  (testing "without the Runner's signal it is an opponent wait, not a fire"
    (with-mock-state (forced-encounter-state
                      :side "corp"
                      :log [forced-marker]
                      :prompt {:msg "encountering Archangel" :prompt-type "run" :eid 91})
      (is (= :waiting-runner-signal
             (:kind (decisions/corp-run-decision @ai-state/client-state)))))))

;; ============================================================================
;; wait / window ownership
;; ============================================================================

(deftest a-forced-encounter-window-has-an-owner
  ;; run-window-owner is what `wait` uses to decide whose move it is. Gated on
  ;; the phase string, a forced encounter had NO owner: wait slept the full
  ;; timeout at a window somebody actually owed (#102 items 4/6, forced twin).
  (let [owner #'core/run-window-owner]
    (testing "nobody has passed: the Runner owes the first pass"
      (with-mock-state (forced-encounter-state)
        (is (= :runner (owner @ai-state/client-state)))))
    (testing "Runner passed: the Corp owes the second"
      (with-mock-state (forced-encounter-state :no-action "runner")
        (is (= :corp (owner @ai-state/client-state)))))
    (testing "the encounter is read even when the outer phase is a pass window of its own"
      ;; A redirect can force an encounter during movement. The run-level ledger
      ;; would answer about the MOVEMENT window, which is not the open one.
      (with-mock-state (-> (forced-encounter-state :phase "movement" :no-action "runner")
                           (assoc-in [:game-state :run :no-action] false))
        (is (= :corp (owner @ai-state/client-state))
            "the encounter's ledger decides, exactly as the engine's continue dispatch does")))))

;; ============================================================================
;; Display: the advisory may offer tank again
;; ============================================================================

(deftest forced-encounter-advisory-offers-tank-now-that-it-works
  ;; The advisory was written to say `tank` could not help — true while the
  ;; handlers gated on the phase. Now that they key on core/at-encounter?, saying
  ;; so would be a different lie: the seat would break-or-escalate at a window
  ;; where declining is legal and cheap.
  (let [lines (display/forced-encounter-advisory-lines "Archangel" 1 "success")
        out (clojure.string/join "\n" lines)]
    (testing "the odd phase is still named"
      (is (re-find #"(?i)forced encounter" out))
      (is (clojure.string/includes? out "success")))
    (testing "tank is offered"
      (is (re-find #"tank \"Archangel\"" out) (str "got:\n" out)))
    (testing "and is not simultaneously disclaimed"
      (is (not (re-find #"(?i)tank cannot help" out)) (str "got:\n" out)))
    (testing "breaking is still offered"
      (is (re-find #"(?i)icebreaker" out)))))


;; ============================================================================
;; Second guest pass — over the fixes the first pass produced
;; ============================================================================

(deftest a-stale-signal-from-a-prior-forced-encounter-must-not-authorise-a-fire
  ;; CRITICAL (guest panel, 2nd pass). runner-signaled-let-fire? decides a signal
  ;; belongs to the CURRENT encounter by comparing it against this ice's most
  ;; recent encounter marker. That marker matcher required ' protecting', which
  ;; only the INSTALLED form writes — so for a forced encounter no marker was
  ;; ever found, the boundary test degenerated into "does a signal exist
  ;; anywhere", and a tank from an EARLIER Archangel authorised the next one.
  ;; With #160's widened Corp gates that is an unrequested fire: the Corp
  ;; resolves subs the Runner never declined to break.
  (let [stale-then-new-encounter
        [{:text "ai-runner accesses Archangel from HQ."}
         forced-marker
         {:text "ai-runner indicates to fire all unbroken subroutines on Archangel"}
         {:text "ai-corp resolves 1 unbroken subroutine on Archangel"}
         ;; …later in the same turn, a SECOND Archangel access. No new signal.
         {:text "ai-runner accesses Archangel from HQ."}
         forced-marker]]
    (testing "the marker is recognised in the forced (' in HQ') form"
      (with-mock-state (forced-encounter-state :side "corp" :log stale-then-new-encounter)
        (is (false? (decisions/runner-signaled-let-fire? @ai-state/client-state "Archangel"))
            "the signal predates this encounter's marker — it authorises nothing")))
    (testing "so the Corp does not fire"
      (let [sent (atom [])]
        (with-mock-state (forced-encounter-state :side "corp" :log stale-then-new-encounter)
          (with-redefs [ws/send-message! (mock-websocket-send! sent)
                        ai-state/get-prompt (constantly nil)]
            (is (nil? (corp-handlers/handle-corp-fire-unbroken
                       {:side "corp" :run-phase "success"
                        :strategy {:fire-unbroken true}
                        :state @ai-state/client-state :gameid "g1"})))
            (is (empty? @sent) (str "nothing may be sent, got: " @sent))))))
    (testing "and a FRESH signal after the new marker still works"
      (with-mock-state (forced-encounter-state
                        :side "corp"
                        :log (conj stale-then-new-encounter
                                   {:text "ai-runner indicates to fire all unbroken subroutines on Archangel"}))
        (is (true? (decisions/runner-signaled-let-fire? @ai-state/client-state "Archangel"))))))
  (testing "the installed form still matches, and prefix collisions still do not"
    (with-mock-state (forced-encounter-state
                      :side "corp"
                      :log [{:text "ai-runner indicates to fire all unbroken subroutines on Fairchild"}
                            {:text "ai-runner encounters Fairchild 3.0 protecting HQ at position 0."}])
      (is (true? (decisions/runner-signaled-let-fire? @ai-state/client-state "Fairchild"))
          "'Fairchild 3.0' is a DIFFERENT card — its marker must not end Fairchild's signal")
      (is (false? (decisions/runner-signaled-let-fire? @ai-state/client-state "Fairchild 3.0"))
          "and 3.0 was never signalled at all"))))

(deftest a-live-encounter-nobody-has-passed-is-still-an-encounter-window
  ;; CRITICAL (guest panel, 2nd pass). encounters-summary always stamps
  ;; :encounter-count but BOTH other keys are optional — :ice is dropped when the
  ;; card cannot be resolved, :no-action is absent until somebody passes. So the
  ;; honest minimum for a live, un-passed encounter is exactly {:encounter-count 1},
  ;; and a predicate keyed on :ice or :no-action hands that board back to the
  ;; SUSPENDED run ledger.
  (let [bare (mock-client-state
              :side "runner"
              :game-state {:run {:phase "movement" :position 1 :no-action "runner"}
                           :encounters {:encounter-count 1}
                           :runner {} :corp {}})]
    (with-mock-state bare
      (is (true? (core/encounter-window? @ai-state/client-state))
          "an encounter summary with neither :ice nor :no-action is still an encounter")
      (is (true? (core/at-encounter? @ai-state/client-state "movement")))
      (is (false? (core/i-already-passed-run-window? @ai-state/client-state "runner"))
          "we passed MOVEMENT, not this encounter — the send guards must not swallow the pass")))
  (testing "with no encounter at all the run ledger is still the answer"
    (with-mock-state (mock-client-state
                      :side "runner"
                      :game-state {:run {:phase "movement" :position 1 :no-action "runner"}
                                   :runner {} :corp {}})
      (is (false? (core/encounter-window? @ai-state/client-state)))
      (is (true? (core/i-already-passed-run-window? @ai-state/client-state "runner"))))))

(deftest the-headline-and-the-send-guards-read-the-same-ledger
  ;; MAJOR (guest panel, 2nd pass): the display's effective-window-passer chose
  ;; its ledger from phase-or-:ice while the send guards chose from the summary's
  ;; presence, so on a no-:ice encounter board the headline could say "your move"
  ;; while the guards treated the window as already passed.
  (doseq [enc [{:encounter-count 1}
               {:encounter-count 1 :no-action "runner"}
               {:encounter-count 1 :ice (archangel)}]]
    (let [gs {:run {:phase "movement" :position 1 :no-action "corp"}
              :encounters enc
              :runner {} :corp {}}
          st (mock-client-state :side "runner" :game-state gs)]
      (is (= (display/effective-window-passer gs)
             (some-> (get enc :no-action) name)
             )
          (str "the display must read the ENCOUNTER ledger for " (pr-str enc)))
      (with-mock-state st
        (is (= (boolean (= "corp" (display/effective-window-passer gs)))
               (core/i-already-passed-run-window? @ai-state/client-state "corp"))
            (str "display and send guards must agree for " (pr-str enc)))))))

(deftest a-corp-that-has-passed-is-never-signalled-and-never-waited-for
  ;; Third guest pass, two CRITICALs, both in the SECOND pass's remediation
  ;; rather than in the original change — the pattern this project keeps
  ;; measuring. That remediation made the Corp-declined encounter a reported
  ;; decision with its own status, to preserve a break the pass forfeits (Hippo).
  ;; It deadlocked twice over: the autonomous loop's :continue action is a no-op
  ;; tick so the status re-derived forever, and --full-break sat in front of the
  ;; new handler and signalled a Corp that had already left. The addition is
  ;; gone; the value question is #165. What must hold is only this: nothing waits
  ;; on an opponent who has already passed.
  (testing "the free pass is taken, with or without a tank authorization"
    (doseq [strategy [{} {:tank #{"Archangel"}}]]
      (let [sent (atom [])]
        (with-mock-state (forced-encounter-state :side "runner" :no-action "corp")
          (runner-handlers/reset-state!)
          (reset! ai-state/run-strategy strategy)
          (try
            (with-redefs [ws/send-message! (mock-websocket-send! sent)]
              (runner-handlers/handle-runner-pass-broken-ice
               {:side "runner" :run-phase "success" :state @ai-state/client-state
                :gameid "g1" :my-prompt nil})
              (is (some #(= "continue" (get-in % [:data :command])) @sent)
                  (str "strategy " (pr-str strategy) " — expected the free pass, got: " @sent)))
            (finally (reset! ai-state/run-strategy {})))))))
  (testing "--full-break with no breaker does NOT signal a Corp that already passed"
    ;; It used to: tank authorized + can't break -> let-subs-fire-signal! and
    ;; :waiting-for-corp-fire, parking the seat against an opponent who had
    ;; already walked away. It must fall through to the pass instead.
    (let [sent (atom [])]
      (with-mock-state (forced-encounter-state :side "runner" :no-action "corp")
        (runner-handlers/reset-state!)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (let [r (runner-handlers/handle-runner-full-break
                   {:side "runner" :run-phase "success" :state @ai-state/client-state
                    :strategy {:full-break true :tank #{"Archangel"}}
                    :gameid "g1" :my-prompt nil})]
            (is (not= :waiting-for-corp-fire (:status r))
                (str "nobody is coming to fire these subs, got: " r))
            (is (not-any? #(= "system-msg" (get-in % [:data :command])) @sent)
                (str "no signal may be sent to a departed Corp, got: " @sent)))))))
  (testing "and it DOES still signal while the Corp still owns its half of the window"
    (let [sent (atom [])]
      (with-mock-state (forced-encounter-state :side "runner" :log [forced-marker])
        (runner-handlers/reset-state!)
        (with-redefs [ws/send-message! (mock-websocket-send! sent)]
          (runner-handlers/handle-runner-full-break
           {:side "runner" :run-phase "success" :state @ai-state/client-state
            :strategy {:full-break true :tank #{"Archangel"}}
            :gameid "g1" :my-prompt nil})
          (is (some #(= "system-msg" (get-in % [:data :command])) @sent)
              (str "the ordinary tank signal must still go out, got: " @sent))))))
  (testing "the autonomous loop's :continue is a no-op tick, so no status may rely on it"
    ;; Pinning the trap rather than the fix: the removed status mapped here.
    (is (= :continue (heuristic/run-result->next-action {:status :something-new}))
        "an unmapped status falls to :continue, which sends nothing at all")))
