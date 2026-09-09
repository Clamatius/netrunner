(ns ai-decklist-test
  "Open-decklist games publish BOTH decklists to BOTH seats: `:decklists` is a
   top-level key in the engine's `state-keys` (src/clj/game/core/diffs.clj) and
   `public-states` does no per-side filtering, so corp-state and runner-state
   each carry `{:corp [...] :runner [...]}`.

   A marquee seat must see only its OWN list — tournament fidelity, and the
   reason an HQ access carries information. These tests pin that the opponent's
   list is dropped at INGEST (never stored), not at display: a seat can `eval`
   the cache, so display-side hiding would be honour rather than enforcement."
  (:require [clojure.test :refer :all]
            [ai-state :as state]
            [ai-display :as display]
            [jinteki.cards :refer [all-cards]]
            [test-helpers :refer [mock-client-state with-mock-state]]))

(def corp-list [["Agenda" "divider"] ["Offworld Office" 3] ["Palisade" 3]])
(def runner-list [["Event" "divider"] ["Sure Gamble" 3] ["Cleaver" 2]])
(def both {:corp corp-list :runner runner-list})

(defn- board-with-decklists [side]
  (assoc (:game-state (mock-client-state :side side)) :decklists both))

;; ---------------------------------------------------------------------------
;; The pure redaction
;; ---------------------------------------------------------------------------

(deftest redacts-to-my-side-only
  (testing "corp keeps corp, and the runner list is GONE (not merely unread)"
    (let [out (state/redact-opponent-decklist {:decklists both} :corp)]
      (is (= corp-list (get-in out [:decklists :corp])))
      (is (not (contains? (:decklists out) :runner)))))

  (testing "runner keeps runner, and the corp list is GONE"
    (let [out (state/redact-opponent-decklist {:decklists both} :runner)]
      (is (= runner-list (get-in out [:decklists :runner])))
      (is (not (contains? (:decklists out) :corp))))))

(deftest unknown-side-fails-closed
  (testing "a state we cannot attribute drops BOTH lists rather than guessing"
    ;; Fail closed: our own list is recoverable on the next state once the side
    ;; is known; a leaked opponent list is not recoverable at all.
    (let [out (state/redact-opponent-decklist {:decklists both} nil)]
      (is (not (contains? (:decklists out) :corp)))
      (is (not (contains? (:decklists out) :runner)))
      ;; An EMPTY map, not a missing key: the display layer must be able to tell
      ;; "published but withheld" from "this lobby never published any"
      ;; (round-2 guest MINOR — the spectator text was guessing).
      (is (= {} (:decklists out))))))

(deftest leaves-non-open-decklist-games-alone
  (testing "no :decklists key (open-decklists off) passes through untouched"
    (let [st {:corp {:credit 5} :runner {:credit 5}}]
      (is (= st (state/redact-opponent-decklist st :corp)))))

  (testing "a non-map :decklists is not mangled"
    (let [st {:decklists nil}]
      (is (= st (state/redact-opponent-decklist st :corp))))))

(deftest opt-out-keeps-both
  (testing "the known-meta variant retains both lists"
    ;; An atom, not a binding: ingest is on the socket receive thread, where a
    ;; thread-local binding never arrives (guest-panel MAJOR).
    (try
      (reset! state/keep-open-decklists true)
      (let [out (state/redact-opponent-decklist {:decklists both} :corp)]
        (is (= both (:decklists out))))
      (finally (reset! state/keep-open-decklists false)))))

(def ^:dynamic *binding-probe*
  "Only here to prove the thread harness below does NOT convey bindings."
  false)

(defn- on-a-raw-thread
  "Run f on a bare java.lang.Thread and return its value.

   NOT `future`: futures (and agents, pmap) CONVEY dynamic bindings, so a
   future-based hop would have passed with the old `^:dynamic` var too and
   proved nothing (round-2 guest finding). The websocket receive thread is a
   bare thread — this is its shape."
  [f]
  (let [p (promise)
        t (Thread. (fn [] (deliver p (try (f) (catch Throwable e e)))))]
    (.start t)
    (let [v (deref p 5000 ::timeout)]
      (when (instance? Throwable v) (throw v))
      v)))

(deftest opt-out-survives-a-thread-hop
  (testing "the harness really is a hop: a dynamic binding does NOT arrive"
    (binding [*binding-probe* true]
      (is (true? *binding-probe*) "sanity: bound on the caller's thread")
      (is (false? (on-a-raw-thread (fn [] *binding-probe*)))
          "if this is true the harness conveys bindings and the next test is void")))

  (testing "the atom DOES reach the raw thread — a binding would not have"
    (try
      (reset! state/keep-open-decklists true)
      (is (= both (:decklists (on-a-raw-thread
                                #(state/redact-opponent-decklist {:decklists both} :corp))))
          "ingest runs on the websocket receive thread, not the caller's")
      (finally (reset! state/keep-open-decklists false)))))

;; ---------------------------------------------------------------------------
;; Ingest — both write paths, because a seat reads :game-state and a diff
;; patches :last-state. Redacting one and not the other leaves the list in the
;; cache under a different key.
;; ---------------------------------------------------------------------------

(deftest set-full-state-redacts-both-caches
  (testing "a full state arriving for the corp seat stores no runner decklist"
    (with-mock-state (mock-client-state :side "corp")
      (state/set-full-state! (board-with-decklists "corp"))
      (let [cs @state/client-state]
        (is (= corp-list (get-in cs [:game-state :decklists :corp]))
            "our own list survives")
        (is (not (contains? (get-in cs [:game-state :decklists]) :runner))
            ":game-state — what every command reads")
        (is (not (contains? (get-in cs [:last-state :decklists]) :runner))
            ":last-state — the diff baseline, reachable by eval")))))

(deftest diff-path-redacts-every-write
  (testing "a diff that re-introduces the opponent's list is re-redacted"
    ;; The guard has to run on EVERY write, not once at game start. A resync or
    ;; a rewind resends state, and differ alterations can create a missing key.
    (with-mock-state (mock-client-state :side "corp")
      (state/set-full-state! (board-with-decklists "corp"))
      (state/update-game-state! [{:decklists {:runner runner-list}} {}])
      (let [cs @state/client-state]
        (is (not (contains? (get-in cs [:game-state :decklists]) :runner))
            "re-introduced by a diff, and stripped again")
        (is (not (contains? (get-in cs [:last-state :decklists]) :runner)))
        (is (= corp-list (get-in cs [:game-state :decklists :corp]))
            "and our own list is not collateral damage")))))


;; ---------------------------------------------------------------------------
;; The other evaluator-reachable caches (guest-panel CRITICAL)
;; ---------------------------------------------------------------------------

(deftest messages-ring-drops-state-payloads
  (testing ":game/start is retained WITHOUT its state — it carries both lists"
    ;; The real payload is often a JSON string, so the check must not depend on
    ;; the data being a map.
    (let [raw "{:decklists {:corp [...] :runner [\"Sure Gamble\"]}}"
          out (state/sanitize-cached-message {:type :game/start :data raw})]
      (is (= :game/start (:type out)) "the type survives — it is a debug ring")
      (is (not= raw (:data out)))
      (is (not (clojure.string/includes? (str (:data out)) "Sure Gamble")))))

  (testing ":game/diff and :game/resync are elided too"
    (doseq [t [:game/diff :game/resync]]
      (is (not= "payload" (:data (state/sanitize-cached-message {:type t :data "payload"})))
          (str t " must not retain its payload"))))

  (testing "messages that carry no state pass through untouched"
    (let [msg {:type :lobby/notification :data "someone joined"}]
      (is (= msg (state/sanitize-cached-message msg))))))

(deftest replay-recorder-redacts-too
  (testing "the replay recorder does not become the second copy of the leak"
    ;; It is a SINK fix: the :game/start handler had the raw server state in
    ;; hand and passed it straight in, so fixing the call site alone would have
    ;; left the next caller free to reintroduce it.
    (with-mock-state (mock-client-state :side "corp")
      (swap! state/replay-recording assoc :enabled true)
      (state/record-initial-state! (assoc (board-with-decklists "corp") :decklists both)
                                   (java.util.UUID/randomUUID))
      (let [recorded (first (:history @state/replay-recording))]
        (is (= corp-list (get-in recorded [:decklists :corp])))
        (is (not (contains? (:decklists recorded) :runner))
            "the opponent's list must not survive into the replay atom")))))


;; ---------------------------------------------------------------------------
;; Display — every sentence is a claim about state, and each state gets its own
;; (round-2 guest MINOR: the spectator text asserted publication and withholding
;; without reading either from state)
;; ---------------------------------------------------------------------------

(def ^:private fake-cards
  "Enough of a card db that `show-cards` does not reach for the HTTP API."
  {"Offworld Office" {:title "Offworld Office" :type "Agenda" :advancementcost 4
                      :agendapoints 2 :text "When you score this agenda, gain 7[Credit]."}
   "Palisade" {:title "Palisade" :type "ICE" :subtype "Barrier" :cost 3 :strength 2
               :text "[Subroutine] End the run."}
   "Sure Gamble" {:title "Sure Gamble" :type "Event" :cost 5 :text "Gain 9[Credit]."}
   "Cleaver" {:title "Cleaver" :type "Program" :subtype "Icebreaker - Fracter"
              :cost 3 :strength 3 :memoryunits 1 :text "1[Credit]: Break up to 2 barrier subroutines."}})

(defn- decklist-output
  "Render `show-decklist` for a client state with the given :decklists value
   (::absent = no key at all), against the fake card db."
  [cs decklists]
  (let [cs (if (= ::absent decklists)
             (update cs :game-state dissoc :decklists)
             (assoc-in cs [:game-state :decklists] decklists))
        saved @all-cards]
    (try
      (reset! all-cards fake-cards)
      (with-out-str (display/show-decklist cs))
      (finally (reset! all-cards saved)))))

(defn- spectator-state []
  (-> (mock-client-state :side "runner") (assoc :side nil :spectator true)))

(deftest spectator-text-reads-state-not-assumptions
  (testing "no :decklists key — this lobby never published any"
    (let [out (decklist-output (spectator-state) ::absent)]
      (is (clojure.string/includes? out "publishes no decklists"))
      (is (not (clojure.string/includes? out "withheld")) "must not claim a drop that never happened")
      (is (not (clojure.string/includes? out "keep-open-decklists")) "nothing to re-enable")))

  (testing "empty map — published, dropped at ingest by fail-closed"
    (let [out (decklist-output (spectator-state) {})]
      (is (clojure.string/includes? out "withheld"))
      (is (clojure.string/includes? out "DOES publish"))
      (is (clojure.string/includes? out "keep-open-decklists") "and says how to retain them")
      (is (not (clojure.string/includes? out "publishes no decklists")))))

  (testing "both retained (opt-out on) — show both, no 'your own list only' footer"
    (let [out (decklist-output (spectator-state) both)]
      (is (clojure.string/includes? out "both lists retained"))
      (is (clojure.string/includes? out "Corp decklist"))
      (is (clojure.string/includes? out "Runner decklist"))
      (is (not (clojure.string/includes? out "Your own list only"))))))

(deftest seated-text-splits-the-three-states
  (let [corp (mock-client-state :side "corp")]
    (testing "no key — not published"
      (let [out (decklist-output corp ::absent)]
        (is (clojure.string/includes? out "No decklist is published"))
        (is (not (clojure.string/includes? out "knew its side")))))

    (testing "empty map — ingested before the side was known; resync"
      (let [out (decklist-output corp {})]
        (is (clojure.string/includes? out "knew its side"))
        (is (clojure.string/includes? out "resync"))
        (is (not (clojure.string/includes? out "No decklist is published")))))

    (testing "own side present but empty — the abnormal state"
      (let [out (decklist-output corp {:corp []})]
        (is (clojure.string/includes? out "EMPTY"))))

    (testing "the real thing: own list, own side only, full card fields"
      (let [out (decklist-output corp {:corp corp-list})]
        (is (clojure.string/includes? out "Corp decklist — 6 cards, 2 distinct"))
        (is (clojure.string/includes? out "3x Offworld Office"))
        (is (clojure.string/includes? out "Your own list only"))
        (is (clojure.string/includes? out "Agenda Points: 2") "a la card-text, not names")
        (is (clojure.string/includes? out "Advancement Requirement: 4"))
        (is (not (clojure.string/includes? out "Runner decklist")))
        (is (not (clojure.string/includes? out "Sure Gamble")))))))
