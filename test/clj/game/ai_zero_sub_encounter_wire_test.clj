(ns game.ai-zero-sub-encounter-wire-test
  "Issue #167: a ZERO-SUBROUTINE ICE encounter was owned by neither seat.

   Every 'nothing left to do here, pass' handler on both sides gated on
   `(seq subroutines)`. An encounter with none matches none of them, and
   `continue :encounter-ice` needs BOTH sides to pass — so the window sat open
   until the 300s deadline. Tour Guide (game.cards.ice) is `variable-subs-ice`
   over the count of rezzed assets: with none rezzed it has exactly zero.

   The guard was defensible, and that is why this file drives the real engine
   rather than a mock: ABSENT and EMPTY are indistinguishable after
   `select-non-nil-keys`, so dropping the guard is only safe if absence of
   :subroutines on a RESOLVED encounter ICE really does mean 'this ICE has
   none' — never 'we have not been told yet'. That premise is asserted here
   against game.core.diffs, alongside the handlers themselves, because this
   project has twice shipped a green suite over mocks that invented the field
   the bug lived in.

   THE SQUARE THIS FILE DOES NOT COVER: zero subroutines x NO :run at all. The
   issue's own repro is Tour Guide + Ganked! + Quest Completed, a forced
   encounter that outlives its run (#164); these tests drive the plain in-run
   encounter, which the issue says hits the same three gates. The gates read only
   [:encounters …], the pass ledger and the prompt — identical in both shapes,
   and the run-less SHAPE is pinned against the engine in
   game.ai-forced-encounter-wire-test — but for an ICE that HAS subroutines. The
   composition of the two is asserted transitively here, not driven. If a
   Quest Completed helper ever lands, that square is worth the ten lines."
  (:require [game.core :as core]
            [game.core.diffs :as diffs]
            [game.core.ice :refer [active-ice?]]
            [game.test-framework :refer :all]
            [ai-core :as ai-core]
            [ai-run-corp-handlers :as corp-handlers]
            [ai-run-runner-handlers :as runner-handlers]
            [ai-state :as ai-state]
            [ai-websocket-client-v2 :as ws]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(use-fixtures :each (fn [t]
                      (corp-handlers/reset-state!)
                      (runner-handlers/reset-state!)
                      (t)))

;; ---------------------------------------------------------------------------
;; Wire fixtures
;; ---------------------------------------------------------------------------

(defn- wire-state
  "The client-state map a seat holds, built from the real serializer.

   :phase is stringified here and nowhere else. `run-summary` leaves it a
   keyword and the transport hop turns it into a string; every client-side
   comparison assumes the string, so a fixture that kept the keyword would be
   testing a state no seat ever sees (the ns docstring of
   game.ai-forced-encounter-wire-test makes the same point)."
  [state side]
  (let [gs (get (diffs/public-states state) (if (= side "corp") :corp-state :runner-state))]
    {:side side
     :game-state (cond-> gs
                   (get-in gs [:run :phase]) (update-in [:run :phase] name))}))

(defn- handler-ctx
  "The context map the handler chain passes to a single handler."
  [wire]
  (let [side (:side wire)]
    {:side side
     :run-phase (get-in wire [:game-state :run :phase])
     :strategy {}
     :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000167")
     :my-prompt (get-in wire [:game-state (keyword side) :prompt-state])
     :state wire}))

(defn- encounter-tour-guide!
  "Leave the game at an encounter with a rezzed Tour Guide and `assets` rezzed
   assets behind it — i.e. an encounter with exactly `assets` subroutines."
  [state assets]
  (core/gain state :corp :click 10)
  (play-from-hand state :corp "Tour Guide" "HQ")
  (dotimes [_ assets]
    (play-from-hand state :corp "NGO Front" "New remote"))
  (dotimes [n assets]
    (rez state :corp (get-content state (keyword (str "remote" (inc n))) 0)))
  (take-credits state :corp)
  (run-on state "HQ")
  (rez state :corp (get-ice state :hq 0))
  (run-continue state))

(defmacro with-tour-guide-encounter
  "Runs body at a Tour Guide encounter with `assets` rezzed assets behind it."
  [assets & body]
  `(do-game
     (new-game {:corp {:deck [(qty "Hedge Fund" 5)]
                       :hand ["Tour Guide" (qty "NGO Front" 3)]
                       :credits 20}
                :runner {:hand ["Bank Job"]}})
     (encounter-tour-guide! ~'state ~assets)
     ~@body))

;; ---------------------------------------------------------------------------
;; The engine premise
;; ---------------------------------------------------------------------------

(deftest a-zero-sub-encounter-is-a-live-encounter
  (testing "the window really is open and really has no subroutines"
    (with-tour-guide-encounter 0
      (is (zero? (count (:subroutines (core/get-current-ice state))))
          "sanity: Tour Guide with no rezzed assets has no subroutines")
      (is (active-ice? state (core/get-current-ice state))
          "engine: the ICE is active, so this is a real encounter")
      (let [wire (wire-state state "runner")]
        (is (true? (ai-core/live-encounter? wire))
            "the encounter is on the wire")
        (is (true? (ai-core/at-encounter? wire (get-in wire [:game-state :run :phase])))
            "and the client's encounter gate fires")))))

(deftest absent-subroutines-means-none-not-unknown
  (testing "#167's open question: is ABSENT distinguishable from EMPTY?"
    (with-tour-guide-encounter 0
      (let [ice (ai-core/encountered-ice (wire-state state "runner"))]
        (is (some? ice) "the encounter summary resolves to a card")
        (is (not (contains? ice :subroutines))
            "select-non-nil-keys drops the empty vector — the key is ABSENT, not []")
        (is (some? (:cid ice)) "but the card itself is fully summarized")
        (is (true? (:installed ice)))))
    (testing "and the same ICE with one sub DOES carry it — so the absence is informative"
      (with-tour-guide-encounter 1
        (let [ice (ai-core/encountered-ice (wire-state state "runner"))]
          (is (= 1 (count (:subroutines ice)))
              "one rezzed asset, one subroutine, present on the wire"))))))

(deftest the-zero-sub-ice-is-active-for-the-client-too
  (testing "encounter-ice-active? is the discriminator the pass handlers can trust"
    (with-tour-guide-encounter 0
      (doseq [side ["runner" "corp"]]
        (let [wire (wire-state state side)]
          (is (true? (ai-core/encounter-ice-active? wire (ai-core/encountered-ice wire)))
              (str side ": the ICE is resolvable and active")))))))

(deftest rezzed-is-not-necessarily-true
  (testing "the engine stamps :rezzed :this-turn on a just-rezzed ICE, so no gate may compare it to `true`"
    (with-tour-guide-encounter 0
      (let [ice (ai-core/encountered-ice (wire-state state "runner"))]
        (is (= :this-turn (:rezzed ice))
            "not `true` — a (true? (:rezzed ice)) guard would be blind here")
        (is (ai-core/encounter-ice-active? (wire-state state "runner") ice))))))

;; ---------------------------------------------------------------------------
;; Ownership: the actual bug
;; ---------------------------------------------------------------------------

(defn- continues-sent
  "Run `f` with the websocket redefed; return what it sent and what it printed."
  [f]
  (let [sent (atom [])
        out (with-redefs [ws/send-message! (fn [_evt data] (swap! sent conj data) true)]
              (with-out-str (f)))]
    {:sent @sent :out out}))

(deftest the-runner-owns-its-pass-at-a-zero-sub-encounter
  (testing "#167: handle-runner-pass-broken-ice gated on (seq subroutines), so it never fired here"
    (with-tour-guide-encounter 0
      (let [wire (wire-state state "runner")
            {:keys [sent out]} (continues-sent
                                #(runner-handlers/handle-runner-pass-broken-ice
                                  (handler-ctx wire)))]
        (is (some #(= "continue" (:command %)) sent)
            "there is nothing to break — the Runner's half of this window is a pass")
        (is (not (re-find #"(?i)all subs (broken|resolved)" out))
            "and it must not claim it broke or resolved subroutines that never existed")))))

(deftest the-corp-owns-its-pass-at-a-zero-sub-encounter
  (testing "#167: handle-corp-all-subs-resolved gated on (seq subroutines) too"
    (with-tour-guide-encounter 0
      (let [wire (wire-state state "corp")
            {:keys [sent out]} (continues-sent
                                #(corp-handlers/handle-corp-all-subs-resolved
                                  (handler-ctx wire)))]
        (is (some #(= "continue" (:command %)) sent)
            "there is nothing to fire — the Corp's half of this window is a pass")
        (is (not (re-find #"(?i)all subs (broken|resolved)" out))
            "and it must not report resolving subroutines that never existed")))))

(deftest both-passes-close-the-encounter-in-the-real-engine
  (testing "the end-to-end claim: the two passes this fix sends actually advance the run"
    (with-tour-guide-encounter 0
      (is (some? (core/get-current-encounter state)) "precondition: encounter open")
      (core/process-action "continue" state :runner nil)
      (is (= :runner (:no-action (core/get-current-encounter state)))
          "the Runner's pass is recorded on the ENCOUNTER, and does not end it alone")
      (core/process-action "continue" state :corp nil)
      (is (nil? (core/get-current-encounter state))
          "the second pass ends the encounter — which is why both seats must own one")
      (is (= :movement (get-in @state [:run :phase]))
          "and the run moved on rather than sitting at the 300s deadline"))))

(deftest the-second-seat-still-closes-a-zero-sub-encounter
  (testing "the un-babysat sequence: one seat passes, and the OTHER must still own the closing pass"
    (with-tour-guide-encounter 0
      (core/process-action "continue" state :runner nil)
      (is (= :runner (:no-action (core/get-current-encounter state)))
          "precondition: the Runner has passed and the encounter is still open")
      (let [{:keys [sent]} (continues-sent
                            #(corp-handlers/handle-corp-all-subs-resolved
                              (handler-ctx (wire-state state "corp"))))]
        (is (some #(= "continue" (:command %)) sent)
            "the Corp owes the closing pass — this is the half that was deadlocking")))
    (testing "and the same in the other order"
      (with-tour-guide-encounter 0
        (core/process-action "continue" state :corp nil)
        (is (= :corp (:no-action (core/get-current-encounter state))))
        (let [{:keys [sent]} (continues-sent
                              #(runner-handlers/handle-runner-pass-broken-ice
                                (handler-ctx (wire-state state "runner"))))]
          (is (some #(= "continue" (:command %)) sent)
              "the Runner closes it when the Corp went first"))))))

(deftest a-seat-that-has-already-passed-does-not-re-pass
  (testing "widening the gate must not widen the spam: the pass-once guards still hold (#75/#150)"
    (with-tour-guide-encounter 0
      (let [corp-ctx (handler-ctx (wire-state state "corp"))
            first-pass (continues-sent #(corp-handlers/handle-corp-all-subs-resolved corp-ctx))]
        (is (= 1 (count (:sent first-pass))) "one pass")
        ;; The engine has now recorded it; re-read the wire the way the loop does.
        (core/process-action "continue" state :corp nil)
        ;; Clear the in-process sent-pass latch first. Left set, it alone
        ;; suppresses the second send and this test would pass with the LEDGER
        ;; guard deleted — verified by mutation. What must hold once the latch
        ;; has expired is that the encounter's own :no-action still names us.
        (corp-handlers/reset-state!)
        (let [second-pass (continues-sent
                           #(corp-handlers/handle-corp-all-subs-resolved
                             (handler-ctx (wire-state state "corp"))))]
          (is (empty? (:sent second-pass))
              "the Corp's own pass is on the encounter ledger — a second continue is the #150 spam")
          (is (str/blank? (:out second-pass))
              "and it must fall through silently, not print-then-get-suppressed"))))))

(deftest a-real-decision-still-blocks-the-zero-sub-pass
  (testing "the guard that keeps the widened gate safe: a pending decision outranks the pass"
    (with-tour-guide-encounter 0
      ;; Only :my-prompt is substituted — the board, the encounter and the ICE are
      ;; the engine's own. The shape is a select prompt because that is what an
      ;; on-encounter 'use this ability?' window serializes to; the handler reads
      ;; :prompt-type and :choices and nothing else.
      (let [ctx (assoc (handler-ctx (wire-state state "runner"))
                       :my-prompt {:prompt-type "select"
                                   :msg "Use Bank Job?"
                                   :choices [{:value "Yes"} {:value "No"}]})
            {:keys [sent]} (continues-sent
                            #(runner-handlers/handle-runner-pass-broken-ice ctx))]
        (is (empty? sent)
            "no subroutines is not a reason to steamroll a decision the seat holds")))
    (testing "and the Corp's half defers to its own waiting prompt the same way"
      (with-tour-guide-encounter 0
        (let [ctx (assoc (handler-ctx (wire-state state "corp"))
                         :my-prompt {:prompt-type "waiting"
                                     :msg "Waiting for Runner to make a decision"})
              {:keys [sent]} (continues-sent
                              #(corp-handlers/handle-corp-all-subs-resolved ctx))]
          (is (empty? sent)
              "continuing on a waiting prompt re-fires the blocked checkpoint (#75)"))))))

(deftest a-failed-send-does-not-latch-the-runners-pass
  (testing "guest panel CRITICAL: the Runner latched 'I passed here' BEFORE knowing a continue went out"
    (with-tour-guide-encounter 0
      (let [wire (wire-state state "runner")
            ctx (handler-ctx wire)
            attempts (atom 0)]
        ;; send-continue!'s own chokepoints read the LIVE client-state, not the
        ;; ctx, so give it the real wire rather than an empty global — otherwise
        ;; this would prove nothing about the guards it consults.
        (reset! ai-state/client-state wire)
        (try
          (with-redefs [ws/send-message! (fn [_evt _data] (swap! attempts inc) false)]
            (with-out-str
              (runner-handlers/handle-runner-pass-broken-ice ctx)
              (runner-handlers/handle-runner-pass-broken-ice ctx)))
          (is (= 2 @attempts)
              "a send that never left the socket leaves the pass owed — the next tick must retry")
          (finally (reset! ai-state/client-state {})))))))

(deftest a-lost-pass-is-not-a-reason-to-wait-forever
  (testing "guest panel CRITICAL (round 2): ws/send-message! reports SOCKET acceptance, not engine acknowledgement, so a latched pass can have been lost — and the encounter's own ledger proves it"
    (with-tour-guide-encounter 0
      ;; The Runner's continue is accepted locally and lost. Latch set, engine
      ;; never saw it.
      (let [ctx (handler-ctx (wire-state state "runner"))]
        (with-redefs [ws/send-message! (fn [_evt _data] true)]
          (with-out-str (runner-handlers/handle-runner-pass-broken-ice ctx))))
      ;; The Corp, meanwhile, passes for real. The encounter is STILL OPEN and
      ;; its ledger names the Corp — which could not be true if our pass had
      ;; landed, because the second pass ends the encounter.
      (core/process-action "continue" state :corp nil)
      (is (some? (core/get-current-encounter state))
          "precondition: the encounter is still open")
      (let [wire (wire-state state "runner")]
        (is (true? (ai-core/opponent-passed-encounter? wire "runner"))
            "the ledger names the Corp")
        (let [{:keys [sent]} (continues-sent
                              #(runner-handlers/handle-runner-pass-broken-ice
                                (handler-ctx wire)))]
          (is (some #(= "continue" (:command %)) sent)
              "the latch is stale evidence; waiting on it is the deadlock"))))))

(deftest a-pass-someone-else-sent-is-still-our-pass
  (testing "guest panel MAJOR (round 3): the latch is one slot and these two handlers are not its only writers — the LEDGER is the authority, and printing a pass the chokepoint will refuse is #150's burst relocated"
    (with-tour-guide-encounter 0
      ;; The pass goes out from somewhere else entirely — a hand-driven
      ;; `send_command runner continue`. The handlers' latch never sees it.
      (core/process-action "continue" state :runner nil)
      (runner-handlers/reset-state!)
      (let [wire (wire-state state "runner")
            {:keys [sent out]} (continues-sent
                                #(runner-handlers/handle-runner-pass-broken-ice
                                  (handler-ctx wire)))]
        (is (empty? sent)
            "the engine already has our pass; a second continue is the #98 no-op")
        (is (not (re-find #"(?i)passing ICE" out))
            "and we must not ANNOUNCE a pass that is not going to be sent — an absence assertion, because a presence check cannot see a spurious line")))))

(deftest a-waiting-prompt-outranks-the-zero-sub-pass
  (testing "guest panel MAJOR (round 3): the Corp mirrors send-continue!'s waiting-prompt chokepoint as an outer guard so nothing is printed that will not be sent; the Runner now does too"
    (with-tour-guide-encounter 0
      (let [ctx (assoc (handler-ctx (wire-state state "runner"))
                       :my-prompt {:prompt-type "waiting"
                                   :msg "Waiting for Corp to make a decision"})
            {:keys [sent out]} (continues-sent
                                #(runner-handlers/handle-runner-pass-broken-ice ctx))]
        (is (empty? sent) "the engine is mid-checkpoint on the Corp (#75)")
        (is (str/blank? out) "and silently — no announced pass, no burst")))))
