(ns send-command-inventory-test
  "#152 deliverable 4: the enable-conditions inventory ratchet.

   board.cljs (and actions.cljs) is the rules layer: every `send-command` call
   site there is a wire command whose ENABLING CONDITION the AI client must
   mirror, because the engine enforces none of it (process_actions.clj trusts
   the client). dev/ENABLE_CONDITIONS.md is the inventory: command → UI enable
   condition → our predicate → test → gap. This test fails when an upstream
   merge adds a `send-command` site (or a new command string) that the
   inventory has no row for, so new buttons surface instead of silently
   widening the gap (#133 was found one incident at a time).

   On failure: read the new site, add its row to dev/ENABLE_CONDITIONS.md
   (mirror or justify), then update the two literals below."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private sources
  ["src/cljs/nr/gameboard/board.cljs"
   "src/cljs/nr/gameboard/actions.cljs"])

;; Literal call sites of send-command (the defn in actions.cljs and its :refer
;; line in board.cljs are excluded below), as counted on 2026-08-21.
(def ^:private expected-site-count 64)

;; Every distinct command STRING board.cljs / actions.cljs can put on the wire
;; via a literal `send-command "<cmd>"` — the rows of dev/ENABLE_CONDITIONS.md.
;; Sites that send a computed command (`(send-command command …)`,
;; `(send-command action …)`, `(send-command (first actions) …)`, the
;; phase-window `(if … "phase-12-pass-priority" "end-phase-12")`) are covered by
;; the count and by their own rows; their strings appear here when literal.
(def ^:private expected-commands
  #{"ability" "bad-pub-choice" "choice" "close-deck" "continue" "credit" "draw"
    "end-phase-12" "end-turn" "expend" "flashback" "generate-install-list" "generate-runnable-zones"
    "jack-out" "move" "phase-12-pass-priority" "play" "post-discard-pass-priority" "purge" "remove-tag"
    "rez" "run" "select" "shuffle" "start-next-phase" "start-turn" "subroutine"
    "system-msg" "toast" "toggle-auto-no-action" "trash-resource"
    "unbroken-subroutines" "view-deck"})

(defn- source-text [path]
  (let [f (io/file path)]
    (is (.exists f) (str "inventory source missing: " path))
    (slurp f)))

(defn- call-sites [text]
  ;; every `(send-command` occurrence that is a CALL, not the defn / :refer
  (->> (re-seq #"\(send-command\b" text) count))

(deftest send-command-sites-are-inventoried
  (let [texts (map source-text sources)
        sites (reduce + (map call-sites texts))
        literal-cmds (->> texts
                          (mapcat #(re-seq #"\(send-command \"([a-z0-9-]+)\"" %))
                          (map second)
                          set)]
    (testing "no send-command site without an inventory row (count ratchet)"
      (is (= expected-site-count sites)
          (str "board.cljs/actions.cljs now have " sites " send-command call sites "
               "(inventory has " expected-site-count "). A new button appeared (or one "
               "was removed): read it, add/remove its row in dev/ENABLE_CONDITIONS.md, "
               "then update expected-site-count.")))
    (testing "no new command string without an inventory row"
      (is (= expected-commands literal-cmds)
          (str "new/removed wire commands vs the inventory — added: "
               (pr-str (remove expected-commands literal-cmds))
               " removed: " (pr-str (remove literal-cmds expected-commands))
               ". Update dev/ENABLE_CONDITIONS.md and expected-commands.")))))
