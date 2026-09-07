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
      (is (not (contains? out :decklists))))))

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

(deftest opt-out-survives-a-thread-hop
  (testing "the opt-out reaches a DIFFERENT thread — a binding would not"
    (try
      (reset! state/keep-open-decklists true)
      (is (= both (:decklists @(future (state/redact-opponent-decklist
                                         {:decklists both} :corp))))
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
