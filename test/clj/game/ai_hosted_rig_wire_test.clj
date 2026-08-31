(ns game.ai-hosted-rig-wire-test
  "Engine premise behind the rig renderers (#161).

   A program hosted on another rig card — Leech on a Leprechaun — is not at the
   top level of `[:runner :rig :program]`. It lives in that host's `:hosted`
   vector, and its own `:zone` is `[:onhost]` (see game.ai-hosted-card-ref-test).

   Both rig renderers built their program list from the top level only, so a
   hosted Leech was absent from `board` and `board-compact` entirely: name,
   strength and virus counters. `status-compact`'s virus segment DOES recurse
   (`ai-state/runner-virus-counters`), so the two compact views disagreed about
   whether the card exists at all.

   These assertions run against the real engine and the real serializer rather
   than a hand-written rig map, because a mock is exactly what would have hidden
   this: the omitted structure IS the bug."
  (:require [game.core.diffs :as diffs]
            [game.test-framework :refer :all]
            [clojure.test :refer :all]
            [clojure.string :as str]
            [ai-state :as ai-state]
            [ai-display :as display]))

(defn- runner-wire-state
  "The client-state map the Runner seat holds, built from the real serializer."
  [state]
  (let [gs (:runner-state (diffs/public-states state))]
    {:connected true
     :side "runner"
     :game-state gs
     :last-state gs}))

(defmacro with-wire-state
  [state & body]
  `(let [original# @ai-state/client-state]
     (try
       (reset! ai-state/client-state (runner-wire-state ~state))
       ~@body
       (finally (reset! ai-state/client-state original#)))))

(defn- leech-on-leprechaun!
  "Install Leprechaun, host Leech on it, and put 2 virus counters on the Leech
   by running HQ twice. Leaves the Runner with a hosted, counter-bearing program."
  [state]
  (take-credits state :corp)
  (play-from-hand state :runner "Leprechaun")
  (play-from-hand state :runner "Leech")
  (click-prompt state :runner "Leprechaun")
  (run-empty-server state "HQ")
  (click-prompt state :runner "No action")
  (run-empty-server state "HQ")
  (click-prompt state :runner "No action"))

(deftest hosted-program-is-not-at-the-rig-top-level
  (testing "the structural premise: the rig's :program vector does not contain the Leech"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Hedge Fund"]}
                 :runner {:credits 15 :hand ["Leprechaun" "Leech"]}})
      (leech-on-leprechaun! state)
      (let [gs (:runner-state (diffs/public-states state))
            programs (get-in gs [:runner :rig :program])
            titles (map :title programs)]
        (is (= ["Leprechaun"] (vec titles))
            "only the host is at the top level")
        (let [lep (first programs)
              hosted (:hosted lep)]
          (is (= ["Leech"] (vec (map :title hosted)))
              "the Leech is in the host's :hosted vector")
          (is (= 2 (get-in (first hosted) [:counter :virus]))
              "and it is carrying the counters the seat needs to see"))))))

(deftest compact-board-names-the-hosted-program-and-its-counters
  (testing "board-compact's rig line shows a hosted Leech under its host (#161)"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Hedge Fund"]}
                 :runner {:credits 15 :hand ["Leprechaun" "Leech"]}})
      (leech-on-leprechaun! state)
      (with-wire-state state
        (let [out (with-out-str (display/show-board-compact))]
          (is (str/includes? out "Rig: Prog[2] HW[0] Res[0] - Leprechaun{Leech(2v)}")
              (str "the whole rig line, host relationship and counters intact, got: " out))
          (is (str/includes? out "Leech")
              (str "the hosted program must be named in the compact rig line, got: " out))
          (is (str/includes? out "2v")
              (str "and must carry its virus counters, got: " out))
          (is (re-find #"Leprechaun\{[^}]*Leech" out)
              (str "the host relationship must be rendered, not flattened, got: " out)))))))

(deftest compact-board-and-status-compact-agree-a-hosted-card-exists
  (testing "the two compact views must not disagree about whether the Leech is there"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Hedge Fund"]}
                 :runner {:credits 15 :hand ["Leprechaun" "Leech"]}})
      (leech-on-leprechaun! state)
      (with-wire-state state
        (let [board (with-out-str (display/show-board-compact))
              status (with-out-str (display/status-compact))]
          (is (str/includes? status "Leech")
              (str "status-compact already recurses into :hosted, got: " status))
          (is (str/includes? board "Leech")
              (str "board-compact must too, got: " board)))))))

(deftest full-board-names-the-hosted-program
  (testing "the detail view is where a seat goes when the compact line is ambiguous"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Hedge Fund"]}
                 :runner {:credits 15 :hand ["Leprechaun" "Leech"]}})
      (leech-on-leprechaun! state)
      (with-wire-state state
        (let [out (with-out-str (display/show-board))]
          (is (str/includes? out "\u2022 Leprechaun\n    \u21b3 Leech (Program) [2virus]")
              (str "the guest is indented under its host, with its counters, got: " out))
          (is (str/includes? out "Leech")
              (str "the hosted program must appear in the full board's rig, got: " out))
          ;; NOTE-ABSENCE. Leprechaun hosting a Leech is program-on-program, so
          ;; this section prints BOTH programs its header counts and owes the
          ;; seat no "hosted elsewhere" pointer. A draft that subtracted only the
          ;; top level claimed a phantom third program here, and every assertion
          ;; above still passed — `includes?` cannot see a spurious extra line
          ;; (guest re-review, MAJOR). This is the case nothing guarded.
          (is (str/includes? out "Programs [2]:")
              (str "both programs are counted, got: " out))
          (is (not (str/includes? out "hosted on another card"))
              (str "and neither is elsewhere, so no pointer is owed, got: " out)))))))

;; ============================================================================
;; `:hosted` is NOT a synonym for `installed` (guest panel, CRITICAL)
;; ============================================================================
;; The first draft of this fix counted every hosted card toward its type. Street
;; Peddler hosts the top three cards of the STACK face-down; The Supplier hosts
;; cards from the GRIP to install later. Neither is rig contents, and counting
;; them told a seat it owned a Clone Chip it could not use.

(defn- supplier-holding-clone-chip!
  "Install The Supplier and park a Clone Chip on it from the grip."
  [state]
  (take-credits state :corp)
  (play-from-hand state :runner "The Supplier")
  (card-ability state :runner (get-resource state 0) 0)
  (click-card state :runner (find-card "Clone Chip" (:hand (:runner @state)))))

(deftest a-parked-card-carries-no-installed-flag-on-the-wire
  (testing "the structural premise: hosted-but-not-installed is a distinct wire shape"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Hedge Fund"]}
                 :runner {:credits 20 :hand ["The Supplier" "Clone Chip"]}})
      (supplier-holding-clone-chip! state)
      (let [gs (:runner-state (diffs/public-states state))
            supplier (first (get-in gs [:runner :rig :resource]))
            parked (first (:hosted supplier))]
        (is (= "Clone Chip" (:title parked)) "the parked card is visible and named")
        (is (= "Hardware" (:type parked)) "and carries its real type")
        (is (nil? (:installed parked))
            "but the wire does NOT mark it installed — this is the whole distinction")
        (is (= [:onhost] (vec (:zone parked)))
            "its zone is the same :onhost a genuinely hosted install gets, so zone cannot tell them apart")))))

(deftest parked-cards-are-never-counted-as-rig-contents
  (testing "a Clone Chip on The Supplier is not hardware the seat owns"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Hedge Fund"]}
                 :runner {:credits 20 :hand ["The Supplier" "Clone Chip"]}})
      (supplier-holding-clone-chip! state)
      (with-wire-state state
        (let [out (with-out-str (display/show-board-compact))]
          (is (str/includes? out "HW[0]")
              (str "counting it said the seat owned a Clone Chip it cannot use, got: " out))
          (is (str/includes? out "Res:The Supplier{+1 uninstalled}")
              (str "it is summarised, not named as rig contents, got: " out)))))))

(deftest the-full-board-marks-a-parked-card-as-not-installed
  (testing "the detail view names it, and says what it is"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Hedge Fund"]}
                 :runner {:credits 20 :hand ["The Supplier" "Clone Chip"]}})
      (supplier-holding-clone-chip! state)
      (with-wire-state state
        (let [out (with-out-str (display/show-board))]
          (is (str/includes? out "↳ Clone Chip (Hardware) (not installed)")
              (str "named — the seat CAN install it — but marked, got: " out))
          (is (str/includes? out "Hardware [0]:")
              (str "and the hardware section still counts zero, got: " out)))))))

(deftest an-installed-guest-and-a-parked-guest-render-differently
  (testing "both shapes on ONE board, so the assertions compare rather than assume"
    ;; The first draft of this test rendered only the installed side, so it
    ;; passed with the installed/parked gate reverted — the brace change alone
    ;; satisfied every assertion (guest re-review). Both hosts are on the board
    ;; here, and the assertions are about the DIFFERENCE between them.
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Hedge Fund"]}
                 :runner {:credits 25 :hand ["Leprechaun" "Leech" "The Supplier" "Clone Chip"]}})
      (take-credits state :corp)
      (play-from-hand state :runner "Leprechaun")
      (play-from-hand state :runner "Leech")
      (click-prompt state :runner "Leprechaun")
      (play-from-hand state :runner "The Supplier")
      (card-ability state :runner (get-resource state 0) 0)
      (click-card state :runner (find-card "Clone Chip" (:hand (:runner @state))))
      (let [gs (:runner-state (diffs/public-states state))
            installed-guest (first (:hosted (first (get-in gs [:runner :rig :program]))))
            parked-guest (first (:hosted (first (get-in gs [:runner :rig :resource]))))]
        (is (:installed installed-guest) "the Leech is marked installed on the wire")
        (is (nil? (:installed parked-guest)) "the Clone Chip is not — same vector, different state")
        (with-wire-state state
          (let [out (with-out-str (display/show-board-compact))]
            (is (str/includes? out "Leprechaun{Leech")
                (str "the installed guest is NAMED, got: " out))
            (is (str/includes? out "Res:The Supplier{+1 uninstalled}")
                (str "the parked guest is SUMMARISED, got: " out))
            (is (not (str/includes? out "Clone Chip"))
                (str "and never named beside rig contents, got: " out))
            (is (str/includes? out "Prog[2] HW[0]")
                (str "only the installed guest reaches a count, got: " out))))))))

;; ============================================================================
;; A condition counter has no :title (guest re-review, MAJOR)
;; ============================================================================
;; `card/convert-to-condition-counter` (src/cljc/game/core/card.cljc:550) rebuilds
;; the card as {:cid :code :implementation :printed-title :side :type :zone} —
;; `:type "Counter"`, the name in `:printed-title`, and NO `:title`. Reading a
;; missing `:title` as "the wire withheld this" rendered a live On the Lam — three
;; tags or three damage of prevention — as `?card` in both views.

(defn- on-the-lam-on-daily-casts! [state]
  (take-credits state :corp)
  (play-from-hand state :runner "Daily Casts")
  (play-from-hand state :runner "On the Lam")
  (click-card state :runner (get-resource state 0)))

(deftest a-condition-counter-is-named-not-treated-as-withheld
  (testing "the structural premise, then both renderers"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Hedge Fund"]}
                 :runner {:credits 20 :hand ["Daily Casts" "On the Lam"]}})
      (on-the-lam-on-daily-casts! state)
      (let [gs (:runner-state (diffs/public-states state))
            guest (first (:hosted (first (get-in gs [:runner :rig :resource]))))]
        (is (nil? (:title guest)) "a condition counter carries no :title at all")
        (is (= "On the Lam" (:printed-title guest)) "its name is in :printed-title")
        (is (= "Counter" (:type guest)) "and its type is neither program, hardware nor resource")
        (is (:installed guest) "it IS installed — this is an active prevention card")
        (with-wire-state state
          (let [compact (with-out-str (display/show-board-compact))
                full (with-out-str (display/show-board))]
            (is (str/includes? compact "Counter:On the Lam")
                (str "named, and tagged with the type it actually is, got: " compact))
            (is (not (str/includes? compact "?card"))
                (str "a card whose name we were told is not a withheld card, got: " compact))
            (is (str/includes? compact "Prog[0] HW[0] Res[1]")
                (str "and it inflates none of the three counts, got: " compact))
            (is (str/includes? full "↳ On the Lam (Counter)")
                (str "the full board names it and says what it is, got: " full))))))))

(deftest a-withheld-guest-renders-through-the-full-board-too
  (testing "the :unknown branch of the full board, which no test reached"
    ;; The Corp's view of a Street Peddler: three private guests. The full board
    ;; must not claim they are uninstalled — it was not told either way.
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Hedge Fund"]}
                 :runner {:credits 20 :deck ["Corroder" "Sure Gamble" "Clone Chip"]
                          :hand ["Street Peddler"]}})
      (take-credits state :corp)
      (play-from-hand state :runner "Street Peddler")
      (let [cgs (:corp-state (diffs/public-states state))
            orig @ai-state/client-state]
        (try
          (reset! ai-state/client-state {:connected true :side "corp"
                                         :game-state cgs :last-state cgs})
          (let [full (with-out-str (display/show-board))]
            (is (str/includes? full "?card [0]")
                (str "withheld guests are positionally distinct in the full board, got: " full))
            (is (str/includes? full "(install state not shown)")
                (str "and their install state is disclaimed, not asserted, got: " full))
            (is (not (str/includes? full "?card [0] (not installed)"))
                (str "never stated as uninstalled — we were not told, got: " full)))
          (finally (reset! ai-state/client-state orig)))))))

;; ============================================================================
;; A hosted card need not be a RIG PIECE at all (guest re-review, CRITICAL)
;; ============================================================================
;; Film Critic hosts an accessed agenda; DJ Fenris hosts a live g-mod identity;
;; On the Lam becomes a condition counter. None is installed rig hardware and
;; none is something the Runner can install, so bucketing them as
;; "+N uninstalled" both hid them and stated the opposite of the truth. The
;; agenda case is the sharpest: it is the card the seat will spend two clicks to
;; score, and the compact line anonymised it into a count.

(deftest a-hosted-agenda-is-named-not-called-uninstallable
  (testing "Film Critic's hosted agenda, accessed from HQ (no :installed key)"
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Project Vitruvius"]}
                 :runner {:credits 20 :hand ["Film Critic"]}})
      (take-credits state :corp)
      (play-from-hand state :runner "Film Critic")
      (run-empty-server state "HQ")
      (click-prompt state :runner "Yes")
      (let [gs (:runner-state (diffs/public-states state))
            guest (first (:hosted (first (get-in gs [:runner :rig :resource]))))]
        (is (= "Corp" (:side guest)) "it is the opponent's card, sitting in the Runner's rig")
        (is (= "Agenda" (:type guest)) "and its type is none of the three the rig counts")
        (is (nil? (:installed guest)) "accessed from HQ it carries no :installed key")
        (with-wire-state state
          (let [compact (with-out-str (display/show-board-compact))
                full (with-out-str (display/show-board))]
            (is (str/includes? compact "Agenda:Project Vitruvius")
                (str "the agenda is NAMED and tagged with its own type, got: " compact))
            (is (not (str/includes? compact "uninstalled"))
                (str "the Runner cannot install an agenda; saying so is a lie, got: " compact))
            (is (str/includes? compact "Res[1]")
                (str "and it inflates no rig count, got: " compact))
            (is (str/includes? full "↳ Project Vitruvius (Agenda)")
                (str "the full board names it too, got: " full))
            (is (not (str/includes? full "Project Vitruvius (Agenda) (not installed)"))
                (str "with no install claim attached, got: " full))))))))

(deftest the-same-hosted-agenda-renders-the-same-from-either-access-route
  (testing "a server access leaves :installed true on the very same card"
    ;; `hosting/host` copies the card's existing keys, so the identical physical
    ;; board arrived with or without :installed depending on where the agenda was
    ;; accessed. Keying on :installed before asking WHAT the card is made one
    ;; board render two ways, which no seat can interpret (guest re-review).
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Project Vitruvius"]}
                 :runner {:credits 20 :hand ["Film Critic"]}})
      (play-from-hand state :corp "Project Vitruvius" "New remote")
      (take-credits state :corp)
      (play-from-hand state :runner "Film Critic")
      (run-empty-server state "Server 1")
      (click-prompt state :runner "Yes")
      (let [gs (:runner-state (diffs/public-states state))
            guest (first (:hosted (first (get-in gs [:runner :rig :resource]))))]
        (is (:installed guest)
            "the server-accessed copy DOES keep :installed — the two routes differ on the wire")
        (with-wire-state state
          (let [compact (with-out-str (display/show-board-compact))]
            (is (str/includes? compact "Agenda:Project Vitruvius")
                (str "and yet renders identically to the HQ-accessed one, got: " compact))
            (is (str/includes? compact "Prog[0] HW[0] Res[1]")
                (str "still counted as nothing, got: " compact))))))))

(deftest an-installed-non-program-guest-carries-its-type-tag
  (testing "the recursive type tag, which no test reached"
    ;; Off-Campus Apartment hosts INSTALLED connections — a genuine rig piece
    ;; hosted on another rig piece, of a different type from the list's presumed
    ;; Program. Without a tag at depth, `Res[2]` sits beside a named guest that
    ;; reads as a program.
    (do-game
      (new-game {:corp {:deck [(qty "Hedge Fund" 5)] :hand ["Hedge Fund"]}
                 :runner {:credits 20 :deck [(qty "Sure Gamble" 5)]
                          :hand ["Off-Campus Apartment" "Underworld Contact"]}})
      (take-credits state :corp)
      (play-from-hand state :runner "Off-Campus Apartment")
      (play-from-hand state :runner "Underworld Contact")
      (click-prompt state :runner "Off-Campus Apartment")
      (let [gs (:runner-state (diffs/public-states state))
            guest (first (:hosted (first (get-in gs [:runner :rig :resource]))))]
        (is (:installed guest) "the connection is genuinely installed on its host")
        (is (= "Resource" (:type guest)) "and is a resource, not a program")
        (with-wire-state state
          (let [compact (with-out-str (display/show-board-compact))]
            (is (str/includes? compact "{Res:Underworld Contact")
                (str "the guest carries its own type tag at depth, got: " compact))
            (is (str/includes? compact "Res[2]")
                (str "and both resources are counted, got: " compact))))))))
