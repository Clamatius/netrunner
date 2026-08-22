(ns send-command-inventory-test
  "#152 deliverable 4: the enable-conditions inventory ratchet.

   board.cljs (and actions.cljs) is the rules layer: every `send-command` call
   site there is a wire command whose ENABLING CONDITION the AI client must
   mirror, because the engine enforces none of it (process_actions.clj trusts
   the client). dev/ENABLE_CONDITIONS.md is the inventory: command → UI enable
   condition → our predicate → test → gap. This test fails when an upstream
   merge changes the call sites, so new buttons surface instead of silently
   widening the gap (#133 was found one incident at a time).

   What is keyed (guest panel, first cut was count + literal set and could be
   fooled by a same-count swap): the number of call sites PER COMMAND TOKEN —
   the first form after `(send-command`, a literal string for most sites and
   a symbol/expression for the computed ones (`command`, `action`,
   `(first actions)`, the phase-window `(if …)`). A new site for an existing
   command changes that command's count; a removed-and-added pair changes two
   counts. Comments are stripped first; the match spans newlines.

   Known residual: a removed site and an added site for the SAME token in the
   same merge is invisible here — that is what `git diff board.cljs` on an
   upstream merge is for.

   On failure: read the new site, add/adjust its row in
   dev/ENABLE_CONDITIONS.md (mirror or justify), then update the literal map."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private sources
  ["src/cljs/nr/gameboard/board.cljs"
   "src/cljs/nr/gameboard/actions.cljs"])

;; Call sites per command token, as inventoried on 2026-08-21 (64 sites).
;; Literal strings are quoted; computed tokens are the source form's head.
(def ^:private expected-sites
  {"\"ability\"" 1, "\"bad-pub-choice\"" 1, "\"choice\"" 9, "\"close-deck\"" 1,
   "\"continue\"" 8, "\"credit\"" 2, "\"draw\"" 1, "\"end-phase-12\"" 1,
   "\"end-turn\"" 2, "\"expend\"" 1, "\"flashback\"" 1, "\"generate-install-list\"" 2,
   "\"generate-runnable-zones\"" 1, "\"jack-out\"" 1, "\"move\"" 1,
   "\"phase-12-pass-priority\"" 1, "\"play\"" 2, "\"post-discard-pass-priority\"" 1,
   "\"purge\"" 1, "\"remove-tag\"" 1, "\"rez\"" 1, "\"run\"" 1, "\"select\"" 1,
   "\"shuffle\"" 2, "\"start-next-phase\"" 1, "\"start-turn\"" 2, "\"subroutine\"" 2,
   "\"system-msg\"" 3, "\"toast\"" 1, "\"toggle-auto-no-action\"" 1,
   "\"trash-resource\"" 1, "\"unbroken-subroutines\"" 2, "\"view-deck\"" 1,
   ;; computed-command sites
   "(first" 1        ; (send-command (first actions) …) — card click, single action
   "command" 2       ; list-abilities (runner/corp/dynamic ability) + actions.cljs arity-forward
   "action" 1        ; card-menu actions (derez/rez/trash/advance/score)
   "(if" 2})         ; phase-window buttons: (if requires-consent "…-pass-priority" "end-…")

(defn- strip-comments
  "Remove `;` line comments — but only a `;` OUTSIDE a string literal starts a
   comment (second guest pass: a regex strip would swallow a `send-command`
   that follows `\"Choose; then act\"` on the same line). Tiny scanner: tracks
   string state with backslash escapes."
  [text]
  (let [sb (StringBuilder.)]
    (loop [i 0 in-str? false]
      (if (>= i (count text))
        (str sb)
        (let [c (.charAt text i)]
          (cond
            ;; a backslash escapes the next char BOTH inside a string (\") and
            ;; outside it (a Clojure char literal such as \; or \" — third
            ;; guest pass: `(= ch \;)` must not read as a comment start)
            (and (= c \\) (< (inc i) (count text)))
            (do (.append sb c) (.append sb (.charAt text (inc i))) (recur (+ i 2) in-str?))

            (= c \")
            (do (.append sb c) (recur (inc i) (not in-str?)))

            (and (not in-str?) (= c \;))
            (let [nl (.indexOf text (int \newline) i)]
              (if (neg? nl)
                (str sb)
                (recur nl false)))

            :else
            (do (.append sb c) (recur (inc i) in-str?))))))))

(defn- site-tokens
  "The head token of every `(send-command …)` call in `text`."
  [text]
  (->> (re-seq #"\(send-command\s+(\"[a-z0-9-]+\"|\([a-z-]+|[a-z][a-z0-9-]*)" (strip-comments text))
       (map second)))

(deftest send-command-sites-are-inventoried
  (let [texts (map (fn [path]
                     (let [f (io/file path)]
                       (is (.exists f) (str "inventory source missing: " path))
                       (slurp f)))
                   sources)
        actual (frequencies (mapcat site-tokens texts))
        added (remove #(= (get expected-sites (key %)) (val %)) actual)
        removed (remove #(contains? actual (key %)) expected-sites)]
    (testing "every send-command site in board.cljs/actions.cljs has an inventory row (per-command counts)"
      (is (= expected-sites actual)
          (str "send-command call sites changed vs dev/ENABLE_CONDITIONS.md.\n"
               "  changed/added (token → count now): " (pr-str (into {} added)) "\n"
               "  removed (token → count inventoried): " (pr-str (into {} removed)) "\n"
               "  Read each new site's enclosing condition, add/adjust its row in the "
               "inventory (mirror it or justify leaving it), then update expected-sites.")))
    (testing "sanity: the total is what the inventory header says"
      (is (= 64 (reduce + (vals actual)))))))
