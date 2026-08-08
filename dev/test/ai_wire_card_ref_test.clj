(ns ai-wire-card-ref-test
  "What our seat actually puts on the wire, checked against the reference client.

   `nr.gameboard.actions/send-command` narrows every card to
   `(select-keys card [:cid :zone :side :host :type])` — one narrowing point, one
   key list. We had four copies of that list and every one of them was missing
   `:host`, which `get-card` needs to resolve a hosted card at all
   (see game.ai-hosted-card-ref-test for the engine side of this).

   These tests assert the payload shape rather than any game outcome, because the
   payload is the thing that drifted from the reference."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-core :as core]
            [ai-runs]
            [ai-websocket-client-v2 :as ws]))

(def board-card-keys
  "The key set nr.gameboard.actions/send-command puts on the wire."
  #{:cid :zone :side :host :type})

(def our-card-keys
  "Ours is the reference set plus :title, which we carry for our own logging and
   the engine ignores. Asserted EXACTLY, so any further drift in either direction
   is a red test rather than a silent divergence."
  (conj board-card-keys :title))

(def ^:private hosted-card
  "A Trojan hosted on a piece of ICE, shaped as it arrives in our game state."
  {:cid "runner-cid-1"
   :title "Botulus"
   :type "Program"
   :side "Runner"
   :zone [:onhost]
   :host {:cid "corp-cid-1"
          :title "Ice Wall"
          :type "ICE"
          :side "Corp"
          :zone [:servers :hq :ices]}})

(defn- capture-action
  "Run body with the wire stubbed; return the args map of the single action sent."
  [f]
  (let [sent (atom nil)]
    (with-redefs [ws/ensure-connected! (constantly true)
                  ws/send-action! (fn [command args] (reset! sent {:command command :args args}) true)]
      (f))
    @sent))

(deftest create-card-ref-carries-host
  (testing "the shared builder keeps every key the reference client sends"
    (let [ref (core/create-card-ref hosted-card)]
      (is (= (:host hosted-card) (:host ref))
          ":host is what makes a hosted card resolvable server-side")
      (is (= our-card-keys (set (keys ref)))
          "no key the reference client sends may be dropped, and none added quietly")))
  (testing "an unhosted card simply carries a nil :host, as in the reference client"
    (let [ref (core/create-card-ref (dissoc hosted-card :host :zone))]
      (is (contains? ref :host))
      (is (nil? (:host ref))))))

(deftest select-card-carries-host
  (testing "select! sends the hosted card's :host"
    (let [{:keys [command args]} (capture-action #(ws/select-card! hosted-card {:eid 42}))]
      (is (= "select" command))
      (is (= (:host hosted-card) (get-in args [:card :host])))
      (is (= our-card-keys (set (keys (:card args)))))
      (is (= {:eid 42} (:eid args)) "and still targets the prompt it was shown"))))

(deftest choose-carries-prompt-eid
  (testing "choose! names the prompt it is answering"
    ;; Without :eid the engine falls back to the HEAD of the prompt queue
    ;; (resolve-prompt, actions.clj:266) — which is not necessarily the prompt
    ;; the seat was shown. The reference client always sends (prompt-eid side).
    (let [prompt {:eid {:eid 99}
                  :msg "Choose one"
                  :choices [{:uuid "uuid-a" :value "A"} {:uuid "uuid-b" :value "B"}]}
          by-index (with-redefs [ai-state/get-prompt (constantly prompt)]
                     (capture-action #(ws/choose! 1)))]
      (is (= "choice" (:command by-index)))
      (is (= {:uuid "uuid-b"} (get-in by-index [:args :choice])))
      (is (= {:eid 99} (get-in by-index [:args :eid]))
          "the eid of the prompt we rendered, not whatever is at the head"))
    (let [prompt {:eid {:eid 101} :msg "Choose one" :choices []}
          by-uuid (with-redefs [ai-state/get-prompt (constantly prompt)]
                    (capture-action #(ws/choose! "uuid-z")))]
      (is (= {:uuid "uuid-z"} (get-in by-uuid [:args :choice])))
      (is (= {:eid 101} (get-in by-uuid [:args :eid]))
          "including when the caller passes a raw uuid"))))

;; ============================================================================
;; The other two "choice" senders. There are THREE in the client and the CLI's
;; `choose`/`choose-value` path uses press-choice!, not ws/choose! — fixing one
;; and testing that one is how this bug would have survived the round.
;; (`press-choice!` is covered where it lives, in ai-prompts-test.)
;; ============================================================================

(deftest run-auto-choice-carries-prompt-eid
  (testing "the run monitor's single-choice auto-press names its prompt too"
    (let [sent (atom nil)
          prompt {:eid {:eid 7}
                  :msg "You accessed Hedge Fund"
                  :choices [{:uuid "only-uuid" :value "No action"}]}]
      (with-redefs [ws/send-message! (fn [_ data] (reset! sent data) true)]
        (ai-runs/handle-auto-choice {:my-prompt prompt :gameid "game-1"}))
      (is (= "choice" (:command @sent)))
      (is (= {:uuid "only-uuid"} (get-in @sent [:args :choice])))
      (is (= {:eid 7} (get-in @sent [:args :eid]))))))
