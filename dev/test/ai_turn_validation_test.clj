(ns ai-turn-validation-test
  "Characterization tests for turn validation logic in ai_basic_actions.clj

   These tests lock down the behavior of can-start-turn? and related functions
   before any refactoring. The log analysis is complex and appears 3x in the file."
  (:require [clojure.test :refer :all]
            [test-helpers :refer :all]
            [ai-basic-actions :as actions]
            [ai-core :as core]
            [ai-state :as state]
            [ai-websocket-client-v2 :as ws]))

;; ============================================================================
;; Test Helpers - Log Entry Builders
;; ============================================================================

(defn make-log-entry
  "Create a log entry for testing"
  [text]
  {:text text})

(defn make-end-turn-entry
  "Create 'is ending their turn' log entry"
  [username turn]
  (make-log-entry (format "%s is ending their turn %d" username turn)))

(defn make-start-turn-entry
  "Create 'started their turn' log entry"
  [username turn]
  (make-log-entry (format "%s started their turn %d" username turn)))

(defn make-game-state-with-log
  "Create game state with specified log entries and click counts.

   :active-player defaults to (name my-side) — i.e. it's my turn."
  [& {:keys [my-side my-clicks opp-clicks log turn my-username active-player my-prompt]
      :or {my-side :runner my-clicks 0 opp-clicks 0 log [] turn 1 my-username "AI-runner"}}]
  (let [opp-side (if (= my-side :runner) :corp :runner)
        active-player (or active-player (name my-side))]
    {:connected true
     :uid my-username
     :gameid (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
     :side (name my-side)
     :game-state {my-side {:click my-clicks
                           :user {:username my-username}
                           :prompt-state my-prompt}
                  opp-side {:click opp-clicks}
                  :turn turn
                  :active-player active-player
                  :log log}}))

;; ============================================================================
;; ai_core log analysis helpers tests
;; ============================================================================

(deftest test-core-extract-turn-number-basic
  (testing "extracts turn number from standard format"
    (is (= 5 (core/extract-turn-number "started their turn 5")))
    (is (= 14 (core/extract-turn-number "is ending their turn 14")))))

(deftest test-core-extract-turn-number-nil
  (testing "nil input returns nil"
    (is (nil? (core/extract-turn-number nil)))))

(deftest test-core-extract-turn-number-no-match
  (testing "text without turn number returns nil"
    (is (nil? (core/extract-turn-number "clicked for credit")))
    (is (nil? (core/extract-turn-number "played Sure Gamble")))))

(deftest test-log-author-uses-the-longest-game-username
  (let [usernames ["Clam" "Clam Jones"]]
    (testing "matches the exact system-msg author, including a legal space-prefix name"
      (is (= "Clam" (core/log-author "Clam is ending their turn 1" usernames)))
      (is (= "Clam Jones" (core/log-author "Clam Jones is ending their turn 1" usernames)))
      (is (core/log-authored-by? "Clam is ending their turn 1" "Clam" usernames))
      (is (not (core/log-authored-by? "Clam Jones is ending their turn 1" "Clam" usernames)))
      (is (nil? (core/log-author "Runner mentions Clam during a trace" usernames))))))

(deftest test-start-turn-log-line-allows-rendered-pronouns
  (is (core/start-turn-log-line? "Clam started their turn 1"))
  (is (core/start-turn-log-line? "Clam started his turn 2"))
  (is (core/start-turn-log-line? "Clam Jones started zir turn 3"))
  (is (not (core/start-turn-log-line? "Clam started a run on turn 3"))))

(deftest test-find-end-turn-indices-basic
  (testing "finds end turn indices"
    (let [log [(make-log-entry "AI-corp is ending their turn 1")
               (make-log-entry "AI-runner took credit")
               (make-log-entry "AI-runner is ending their turn 1")]]
      (is (= [0 2] (vec (core/find-end-turn-indices log nil nil)))))))

(deftest test-find-end-turn-indices-exclude-username
  (testing "excludes entries with specified username"
    (let [log [(make-log-entry "AI-corp is ending their turn 1")
               (make-log-entry "AI-runner is ending their turn 1")]]
      (is (= [0] (vec (core/find-end-turn-indices log "AI-runner" ["AI-corp" "AI-runner"]))))
      (is (= [1] (vec (core/find-end-turn-indices log "AI-corp" ["AI-corp" "AI-runner"])))))))

(deftest test-find-end-turn-indices-does-not-confuse-overlapping-usernames
  (testing "opponent end detection is author-exact for prefix and suffix collisions"
    (let [log [(make-end-turn-entry "ai-runner" 1)
               (make-end-turn-entry "runner" 1)
               (make-end-turn-entry "runner-2" 1)]]
      (is (= [0 2] (vec (core/find-end-turn-indices log "runner"
                                                    ["runner" "ai-runner" "runner-2"])))))))

(deftest test-turn-scans-ignore-player-chat
  (testing "fresh-seat MINOR: chat shares the game :log, and the pronoun-tolerant shape matches
            any 'started <word> turn N' — so a human typing about their turn was counted as the
            OPPONENT's boundary line (chat carries no author prefix, so the exclude-username
            test cannot see it). Engine lines are :user \"__system__\"; chat is a user MAP."
    (let [chat-start {:user {:username "Clamatius"} :text "I started my turn 3, finally"}
          chat-end   {:user {:username "Clamatius"} :text "my patience is ending their turn 3"}
          engine-start {:user "__system__" :text "AI-corp started their turn 3"}
          engine-end   {:user "__system__" :text "AI-corp is ending their turn 3"}]
      (testing "a chat line is never a start-turn boundary"
        (is (= [1] (vec (core/find-start-turn-indices [chat-start engine-start]
                                                      :exclude-username "AI-runner"
                                                      :usernames ["AI-runner" "AI-corp"])))))
      (testing "a chat line is never an end-turn boundary"
        (is (= [1] (vec (core/find-end-turn-indices [chat-end engine-end]
                                                    "AI-runner"
                                                    ["AI-runner" "AI-corp"])))))
      (testing "fixtures that omit :user are still engine lines (the ai_runs rule)"
        (is (= [0] (vec (core/find-start-turn-indices [(make-start-turn-entry "AI-corp" 3)]
                                                      :exclude-username "AI-runner"
                                                      :usernames ["AI-runner"]))))))))

(deftest test-find-start-turn-indices-basic
  (testing "finds start turn indices"
    (let [log [(make-log-entry "AI-corp started their turn 1")
               (make-log-entry "AI-corp took credit")
               (make-log-entry "AI-runner started their turn 1")]]
      (is (= [0 2] (vec (core/find-start-turn-indices log)))))))

(deftest test-find-start-turn-indices-include-username
  (testing "includes only entries with specified username"
    (let [log [(make-log-entry "AI-corp started their turn 1")
               (make-log-entry "AI-runner started their turn 1")]]
      (is (= [0] (vec (core/find-start-turn-indices log :include-username "AI-corp"
                                                        :usernames ["AI-corp" "AI-runner"]))))
      (is (= [1] (vec (core/find-start-turn-indices log :include-username "AI-runner"
                                                        :usernames ["AI-corp" "AI-runner"])))))))

(deftest test-find-start-turn-indices-exclude-username
  (testing "excludes entries with specified username"
    (let [log [(make-log-entry "AI-corp started their turn 1")
               (make-log-entry "AI-runner started their turn 1")]]
      (is (= [1] (vec (core/find-start-turn-indices log :exclude-username "AI-corp"
                                                        :usernames ["AI-corp" "AI-runner"]))))
      (is (= [0] (vec (core/find-start-turn-indices log :exclude-username "AI-runner"
                                                        :usernames ["AI-corp" "AI-runner"])))))))

(deftest test-find-start-turn-indices-does-not-confuse-overlapping-usernames
  (testing "start detection includes and excludes the exact author only"
    (let [log [(make-start-turn-entry "Clamatius" 1)
               (make-start-turn-entry "Clam" 1)
               (make-start-turn-entry "ai-Clam" 1)]]
      (is (= [1] (vec (core/find-start-turn-indices log :include-username "Clam"
                                                        :usernames ["Clam" "Clamatius" "ai-Clam"]))))
      (is (= [0 2] (vec (core/find-start-turn-indices log :exclude-username "Clam"
                                                          :usernames ["Clam" "Clamatius" "ai-Clam"])))))))

(deftest test-find-turn-indices-disambiguates-space-prefix-and-pronouns
  (let [usernames ["Clam" "Clam Jones"]
        log [(make-log-entry "Clam Jones started his turn 1")
             (make-log-entry "Clam is ending their turn 1")
             (make-log-entry "Clam started zir turn 2")
             (make-log-entry "Clam Jones is ending her turn 2")]]
    (is (= [1] (vec (core/find-end-turn-indices log "Clam Jones" usernames))))
    (is (= [0] (vec (core/find-start-turn-indices log :include-username "Clam Jones"
                                                      :usernames usernames))))
    (is (= [2] (vec (core/find-start-turn-indices log :include-username "Clam"
                                                      :usernames usernames))))))

;; ============================================================================
;; extract-turn-from-log tests (private function - legacy, kept for coverage)
;; ============================================================================

(deftest test-extract-turn-basic
  (testing "extracts turn number from standard format"
    (is (= 5 (#'actions/extract-turn-from-log "started their turn 5")))
    (is (= 14 (#'actions/extract-turn-from-log "is ending their turn 14")))))

(deftest test-extract-turn-nil-input
  (testing "nil input returns nil"
    (is (nil? (#'actions/extract-turn-from-log nil)))))

(deftest test-extract-turn-no-match
  (testing "text without turn number returns nil"
    (is (nil? (#'actions/extract-turn-from-log "clicked for credit")))
    (is (nil? (#'actions/extract-turn-from-log "played Sure Gamble")))))

(deftest test-extract-turn-first-match
  (testing "extracts first turn number if multiple exist"
    ;; This shouldn't happen in practice, but tests the regex behavior
    (is (= 1 (#'actions/extract-turn-from-log "turn 1 and turn 2")))))

;; ============================================================================
;; can-start-turn? tests
;; ============================================================================

(deftest test-can-start-already-have-clicks
  (testing "cannot start turn when already have clicks"
    (with-mock-state
      (make-game-state-with-log :my-side :runner :my-clicks 4 :opp-clicks 0)
      (let [result (actions/can-start-turn?)]
        (is (false? (:can-start result)))
        (is (= :turn-already-started (:reason result)))))))

(deftest test-can-start-opponent-has-clicks
  (testing "cannot start turn when opponent has clicks"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :opp-clicks 3
       :log [(make-end-turn-entry "AI-corp" 1)])
      (let [result (actions/can-start-turn?)]
        (is (false? (:can-start result)))
        (is (= :opponent-has-clicks (:reason result)))))))

(deftest test-can-start-opponent-not-ended
  (testing "cannot start turn when opponent hasn't ended"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :opp-clicks 0
       :log [])  ; No end turn in log
      (let [result (actions/can-start-turn?)]
        (is (false? (:can-start result)))
        (is (= :opponent-not-ended (:reason result)))))))

(deftest test-can-start-first-turn-corp
  (testing "Corp can start first turn (turn 0, no prior activity)"
    (with-mock-state
      (make-game-state-with-log
       :my-side :corp
       :my-clicks 0
       :opp-clicks 0
       :turn 0
       :log []
       :my-username "AI-corp")
      (let [result (actions/can-start-turn?)]
        (is (true? (:can-start result)))
        (is (= :first-turn (:reason result)))))))

(deftest test-can-start-first-turn-runner-blocked
  (testing "Runner cannot start first turn (Corp goes first)"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :opp-clicks 0
       :turn 0
       :log []
       :my-username "AI-runner")
      (let [result (actions/can-start-turn?)]
        (is (false? (:can-start result)))
        (is (= :not-first-player (:reason result)))))))

(deftest test-can-start-ready
  (testing "can start turn when all conditions met"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :opp-clicks 0
       :turn 1
       :log [(make-end-turn-entry "AI-corp" 1)]
       :my-username "AI-runner")
      (let [result (actions/can-start-turn?)]
        (is (true? (:can-start result)))
        (is (= :ready (:reason result)))))))

(defn- with-end-turn-flag
  "The engine's :end-turn, which make-game-state-with-log does not model."
  [state flag]
  (assoc-in state [:game-state :end-turn] flag))

(deftest test-can-start-agrees-with-the-authority-when-the-end-line-scrolled-out
  (testing "#226 follow-up (fresh-seat MAJOR): can-start-turn? is the preflight all four
            autonomous loops gate on, and it had NO :end-turn arm — only the 100-entry
            recency test. With the opponent's end line scrolled out, the loop is told
            :opponent-not-ended while wait/status say it is our move, and nothing in the
            loop can change that state: it spins forever."
    (let [;; Corp ended, then 100 chat lines push its end line out of the window.
          ;; Chat is the reachable filler: it lands in the same :log via `say`,
          ;; and a spectator or opponent can emit it while we are owed the start.
          scrolled (into [(make-end-turn-entry "AI-corp" 3)]
                         (for [i (range 100)]
                           {:user "watcher" :text (format "nice run (%d)" i)}))]
      (with-mock-state
        (with-end-turn-flag
          (make-game-state-with-log
           :my-side :runner
           :my-clicks 0
           :opp-clicks 0
           :turn 3
           :active-player "corp"
           :log scrolled
           :my-username "AI-runner")
          true)
        (is (true? (state/my-turn-to-act? @state/client-state "runner"))
            "fixture precondition: the authority says it is the Runner's move")
        (let [result (actions/can-start-turn?)]
          (is (true? (:can-start result))
              (str "the preflight must not contradict the authority: " result))
          (is (not= :opponent-not-ended (:reason result)) (str result)))))))

(deftest test-can-start-refuses-the-finishers-second-consecutive-turn
  (testing "the other direction of the same invariant: my-turn-to-act?'s docstring promises it
            and can-start-turn? agree BY CONSTRUCTION, and start-turn! refuses this state
            :not-your-turn (#220). The preflight already refuses it — by a DIFFERENT arm
            (:turn-already-played, from the log index comparison), which is why the
            subordination below is the only change needed here. Pins the agreement, not the
            arm: the reason keyword is not what the loops read."
    (with-mock-state
      (with-end-turn-flag
        (make-game-state-with-log
         :my-side :runner
         :my-clicks 0
         :opp-clicks 0
         :turn 1
         :active-player "runner"           ; I am the one who just ended
         :log [(make-end-turn-entry "AI-corp" 1)
               (make-start-turn-entry "AI-runner" 1)
               (make-end-turn-entry "AI-runner" 1)]
         :my-username "AI-runner")
        true)
      (is (false? (state/my-turn-to-act? @state/client-state "runner"))
          "fixture precondition: the authority says the boundary is the Corp's")
      (let [result (actions/can-start-turn?)]
        (is (false? (:can-start result))
            (str "the preflight must not send a loop at a wall start-turn! will refuse: " result))))))

(deftest test-can-start-refuses-the-finisher-when-its-own-history-scrolled-out
  (testing "round-2 fresh seat, MAJOR and MY regression: subordinating the recency arm to
            :end-turn removed the only thing refusing the FINISHER once its own start line has
            also scrolled out. already-played? stops answering, the recency arm is exempted,
            and the preflight says :ready for a seat that start-turn! will refuse
            :not-your-turn — the loop auto-starts, is rejected, and goes round again.

            The round-1 test for this state kept the start line in the window, so
            :turn-already-played masked it. That test is why the arm was not added; this one
            is why it is."
    (let [;; Runner ended turn 3. Then 100 chat lines: both the Corp's end line
          ;; and the Runner's own start line are outside the window.
          scrolled (into [(make-end-turn-entry "AI-corp" 3)
                          (make-start-turn-entry "AI-runner" 3)
                          (make-end-turn-entry "AI-runner" 3)]
                         (for [i (range 100)]
                           {:user {:username "watcher"} :text (format "good game (%d)" i)}))]
      (with-mock-state
        (with-end-turn-flag
          (make-game-state-with-log
           :my-side :runner
           :my-clicks 0
           :opp-clicks 0
           :turn 3
           :active-player "runner"          ; I am the one who just ended
           :log scrolled
           :my-username "AI-runner")
          true)
        (is (false? (state/my-turn-to-act? @state/client-state "runner"))
            "fixture precondition: the authority says the boundary is the Corp's")
        (let [result (actions/can-start-turn?)]
          (is (false? (:can-start result))
              (str "the preflight must not claim :ready for a turn the wire will refuse: " result))
          (is (= :not-your-turn (:reason result)) (str result)))))))

(deftest test-can-start-opponent-restarted
  (testing "cannot start when opponent started new turn after ending"
    (with-mock-state
      (make-game-state-with-log
       :my-side :corp
       :my-clicks 0
       :opp-clicks 0
       :turn 2
       :log [(make-end-turn-entry "AI-runner" 1)      ; idx 0 - Runner ended
             (make-log-entry "AI-corp took credit")   ; idx 1
             (make-end-turn-entry "AI-corp" 1)        ; idx 2 - Corp ended
             (make-start-turn-entry "AI-runner" 2)]   ; idx 3 - Runner started again!
       :my-username "AI-corp")
      (let [result (actions/can-start-turn?)]
        (is (false? (:can-start result)))
        (is (= :opponent-restarted (:reason result)))))))

;; ============================================================================
;; turn-started-since-last-opp-end? tests
;; ============================================================================

(deftest test-turn-started-no-opp-end
  (testing "when no opponent end found, returns true if my start exists"
    ;; This covers turn 1 for Corp (no prior Runner end)
    (with-mock-state
      (make-game-state-with-log
       :my-side :corp
       :my-clicks 3
       :log [(make-start-turn-entry "AI-corp" 1)]
       :my-username "AI-corp")
      (is (true? (actions/turn-started-since-last-opp-end?))))))

(deftest test-turn-started-no-my-start
  (testing "when no my start found, returns false"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :log [(make-end-turn-entry "AI-corp" 1)]
       :my-username "AI-runner")
      (is (false? (actions/turn-started-since-last-opp-end?))))))

(deftest test-turn-started-normal-case
  (testing "normal case: my start is after opponent end"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 4
       :log [(make-end-turn-entry "AI-corp" 1)        ; idx 0
             (make-start-turn-entry "AI-runner" 1)]   ; idx 1 (after)
       :my-username "AI-runner")
      (is (true? (actions/turn-started-since-last-opp-end?))))))

(deftest test-turn-started-start-before-end-same-turn-runner
  (testing "edge case: start logged before end, same turn, runner side"
    ;; This happens with async log ordering
    ;; Runner starting T1 is "since" Corp ending T1
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 4
       :log [(make-start-turn-entry "AI-runner" 1)    ; idx 0 (logged first!)
             (make-end-turn-entry "AI-corp" 1)]       ; idx 1 (logged second)
       :my-username "AI-runner")
      (is (true? (actions/turn-started-since-last-opp-end?))))))

(deftest test-turn-started-start-before-end-same-turn-corp
  (testing "edge case: start logged before end, same turn, corp side"
    ;; Corp starting T1 is NOT "since" Runner ending T1
    ;; (Corp starts T1 before Runner ends T1)
    (with-mock-state
      (make-game-state-with-log
       :my-side :corp
       :my-clicks 3
       :log [(make-start-turn-entry "AI-corp" 1)      ; idx 0 (logged first)
             (make-end-turn-entry "AI-runner" 1)]     ; idx 1 (logged second)
       :my-username "AI-corp")
      (is (false? (actions/turn-started-since-last-opp-end?))))))

(deftest test-turn-started-async-race-later-turn
  (testing "async race: my start is for later turn"
    ;; Start T2 logged before Opp End T1 - I already started
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 4
       :turn 2
       :log [(make-start-turn-entry "AI-runner" 2)    ; idx 0 - later turn
             (make-end-turn-entry "AI-corp" 1)]       ; idx 1 - earlier turn
       :my-username "AI-runner")
      (is (true? (actions/turn-started-since-last-opp-end?))))))

;; ============================================================================
;; Integration: Log patterns that appear 3x in the codebase
;; ============================================================================
;; These tests document the log analysis patterns that will be extracted
;; to helper functions in Phase 2.1

(deftest test-opp-end-detection-excludes-my-username
  (testing "opponent end detection excludes entries with my username"
    ;; The pattern: (not (str/includes? text my-username))
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :opp-clicks 0
       :log [(make-end-turn-entry "AI-runner" 1)      ; This is MY end, not opponent
             (make-end-turn-entry "AI-corp" 1)]       ; This is opponent end
       :my-username "AI-runner")
      (let [result (actions/can-start-turn?)]
        ;; Should be able to start because Corp (opponent) ended
        (is (true? (:can-start result)))))))

(deftest test-my-start-detection-includes-my-username
  (testing "my start detection includes entries with my username"
    ;; The pattern: (str/includes? text my-username)
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 4
       :log [(make-end-turn-entry "AI-corp" 1)
             (make-start-turn-entry "AI-runner" 1)    ; This is MY start
             (make-start-turn-entry "AI-corp" 2)]     ; This is NOT my start
       :my-username "AI-runner")
      (is (true? (actions/turn-started-since-last-opp-end?))))))

;; ============================================================================
;; check-auto-end-turn! — guards against ending a turn that's not ours
;; ============================================================================
;; Bug #16: a forced runner prompt during the corp's turn (e.g. Public Trail
;; "Take 1 tag") would land the runner client in (clicks=0, no prompt, not
;; already ended) once resolved, and check-auto-end-turn! would dispatch
;; end-turn — corrupting server-side turn tracking.

(defn- captured-ws-sends
  "Run body with ws/send-message! mocked, return [result sent-vec].
   sent-vec is a vector of {:type ..., :data ...} maps."
  [body-fn]
  (let [sent (atom [])]
    (with-redefs [ws/send-message!
                  (fn [event-type data]
                    (swap! sent conj {:type event-type :data data})
                    nil)
                  core/show-turn-indicator (fn [& _] nil)]
      (let [result (with-out-str (body-fn))]
        [result @sent]))))

(deftest test-auto-end-turn-skipped-when-not-my-turn
  (testing "check-auto-end-turn! does NOT fire end-turn when active-player is opponent
            (regression for issue #16: runner answering forced prompt during corp turn)"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0                      ; ← runner hasn't started turn yet
       :opp-clicks 0
       :turn 12
       :active-player "corp"             ; ← it's the CORP's turn
       ;; Repro: runner's recent log has NO "AI-runner is ending" entry —
       ;; the corp just played Public Trail forcing a runner prompt that was
       ;; resolved, leaving runner client at (clicks=0, no prompt, not ended).
       :log [(make-start-turn-entry "AI-corp" 12)
             (make-log-entry "Clamatius spends [Click] to play Public Trail.")
             (make-log-entry "Clamatius uses Public Trail to give the runner 1 tag.")]
       :my-username "AI-runner")
      (let [[_ sent] (captured-ws-sends #(actions/check-auto-end-turn!))]
        (is (empty? (filter #(= "end-turn" (get-in % [:data :command])) sent))
            "must not dispatch end-turn during opponent's turn")))))

(deftest test-auto-end-turn-fires-when-my-turn
  (testing "check-auto-end-turn! DOES fire end-turn when active-player is me,
            0 clicks, no prompt, and not already ended"
    (with-mock-state
      (make-game-state-with-log
       :my-side :runner
       :my-clicks 0
       :opp-clicks 0
       :turn 12
       :active-player "runner"          ; ← it's MY turn
       :log [(make-end-turn-entry "AI-corp" 12)
             (make-start-turn-entry "AI-runner" 12)
             (make-log-entry "AI-runner spends [Click] to draw a card.")]
       :my-username "AI-runner")
      (let [[_ sent] (captured-ws-sends #(actions/check-auto-end-turn!))]
        (is (seq (filter #(= "end-turn" (get-in % [:data :command])) sent))
            "must dispatch end-turn when conditions are met and it's our turn")))))

;; ============================================================================
;; Bug #2: end-turn! must refuse if turn is already ended
;; ============================================================================
;; Surfaced in Run #4. After auto-end-turn! fired ("ai-corp is ending their
;; turn 6"), I called explicit `end-turn` to "unstick" what looked like a
;; deadlock. The second `is ending their turn 6` log line corrupted engine
;; state. check-auto-end-turn! has an already-ended? guard; end-turn! must too.

(deftest test-end-turn-refuses-if-already-ended
  (testing "end-turn! refuses to send a duplicate end-turn message when log
            shows we already ended this turn (Bug #2)"
    (with-mock-state
      (make-game-state-with-log
       :my-side :corp
       :my-clicks 0
       :opp-clicks 0
       :turn 6
       :active-player "corp"
       :log [(make-log-entry "AI-corp pays 6 [Credits] to rez Brân 1.0.")
             (make-log-entry "AI-corp spends [Click] to gain 1 [Credits].")
             (make-end-turn-entry "AI-corp" 6)]   ; ← already ended
       :my-username "AI-corp")
      (let [[_ sent] (captured-ws-sends #(actions/end-turn!))]
        (is (empty? (filter #(= "end-turn" (get-in % [:data :command])) sent))
            "must not dispatch a second end-turn when turn already ended")))))

(deftest test-end-turn-fires-when-not-already-ended
  (testing "end-turn! DOES send end-turn when log shows no prior end this turn"
    (with-mock-state
      (make-game-state-with-log
       :my-side :corp
       :my-clicks 0
       :opp-clicks 0
       :turn 6
       :active-player "corp"
       :log [(make-start-turn-entry "AI-corp" 6)
             (make-log-entry "AI-corp pays 6 [Credits] to rez Brân 1.0.")
             (make-log-entry "AI-corp spends [Click] to gain 1 [Credits].")]
       :my-username "AI-corp")
      (let [[_ sent] (captured-ws-sends #(actions/end-turn!))]
        (is (seq (filter #(= "end-turn" (get-in % [:data :command])) sent))
            "must dispatch end-turn when not already ended")))))

;; ============================================================================
;; Test Suite Main
;; ============================================================================

(defn -main []
  (let [results (run-tests 'ai-turn-validation-test)]
    (println "\n========================================")
    (println "Turn Validation Test Summary")
    (println "========================================")
    (println "Tests run:" (:test results))
    (println "Assertions:" (:pass results))
    (println "Failures:" (:fail results))
    (println "Errors:" (:error results))
    (println "========================================\n")
    (when (or (pos? (:fail results)) (pos? (:error results)))
      (System/exit 1))))

(comment
  ;; Run all tests
  (run-tests 'ai-turn-validation-test)

  ;; Run specific test
  (test-can-start-ready)
  )
