(ns ai-run-corp-decisions
  "Pure Corp-side run decision classification.

   This namespace identifies whether the Corp has a real run decision. It does
   not choose a policy action and does not know about shell command strings."
  (:require [ai-core :as core]
            [clojure.string :as str]))

(defn- normalize-server-key [server]
  (let [server-name (cond
                      (sequential? server) (last server)
                      :else server)]
    (cond
      (keyword? server-name) server-name
      (nil? server-name) nil
      :else
      (let [lower (str/lower-case (str server-name))]
        (cond
          (= lower "hq") :hq
          (or (= lower "rd") (= lower "r&d")) :rd
          (= lower "archives") :archives
          (re-matches #"server \d+" lower)
          (keyword (str "remote" (second (re-find #"server (\d+)" lower))))
          (re-matches #"remote\d+" lower) (keyword lower)
          :else (keyword lower))))))

(defn attacked-server-key
  "Return the server key currently being attacked, e.g. :hq or :remote1."
  [state]
  (normalize-server-key (get-in state [:game-state :run :server])))

(defn attacked-server
  "Return the full server map currently being attacked."
  [state]
  (get-in state [:game-state :corp :servers (attacked-server-key state)]))

(defn attacked-server-content
  "Return installed root content in the attacked server."
  [state]
  (let [content (:content (attacked-server state))]
    (if (sequential? content) content [])))

(defn- corp-prompt [state]
  (get-in state [:game-state :corp :prompt-state]))

(defn- run-prompt? [prompt]
  (= "run" (str (:prompt-type prompt))))

(defn- prompt-choices [prompt]
  (or (:choices prompt) []))

(defn- prompt-selectables [prompt]
  (or (:selectable prompt) []))

(defn actionable-prompt?
  "True when the Corp prompt exposes a direct choice/card selection."
  [prompt]
  (and prompt
       (or (seq (prompt-choices prompt))
           (seq (prompt-selectables prompt)))))

(defn empty-run-window?
  "True for a run prompt with no explicit choices/selectables."
  [prompt]
  (and prompt
       (run-prompt? prompt)
       (empty? (prompt-choices prompt))
       (empty? (prompt-selectables prompt))))

(defn- last-index-where
  "Index of the last element of coll satisfying pred, or -1 if none."
  [coll pred]
  (reduce (fn [acc [i x]] (if (pred x) i acc))
          -1
          (map-indexed vector coll)))

(defn- on-ice-tail?
  "True when a log line ENDS with 'on <ice>' (the break / signal / resolve shape),
   title-ANCHORED so a shorter ice title does not match a longer same-prefixed one
   (e.g. \"Fairchild\" must NOT match a line ending 'on Fairchild 3.0'). Tolerates a
   trailing period / whitespace on the stored :text."
  [text ice-title]
  (boolean
   (re-find (re-pattern (str "(?i) on " (java.util.regex.Pattern/quote ice-title) "\\.?\\s*$"))
            text)))

(defn- encounter-of-ice?
  "True when a log line is the encounter marker for THIS ice: 'encounters <ice>
   protecting …'. Anchored on the following ' protecting' so \"Fairchild\" does not
   match 'encounters Fairchild 3.0 protecting …'."
  [text ice-title]
  (boolean
   (re-find (re-pattern (str "(?i)encounters " (java.util.regex.Pattern/quote ice-title) " protecting"))
            text)))

(defn runner-signaled-let-fire?
  "Check if the Runner has CURRENTLY signaled they are done breaking on this ICE, so
   the Corp may fire its unbroken subs. The signal is a system message: \"indicates
   to fire all unbroken subroutines on <ice>\".

   A bare substring scan of the log is not enough (#90) — it authorises a fire when
   the Runner never said to. The Corp must NOT fire unless the Runner said so IN THIS
   encounter and has not un-said it, because otherwise a Runner who is merely
   breaking or pausing (the active player, mid-stall) gets taxed for subs it was
   going to break. Three staleness traps, all closed here:

     1. Signal SUPERSEDED by a break — Runner signalled, then broke the subs anyway.
        Honour the signal only if it is more recent than the last 'to break … <ice>'.
     2. Signal from a PRIOR encounter of the same ice — two runs on one central in a
        turn is routine; a resolved enc-1 tank must not fire enc-2 before the Runner
        acts. Honour the signal only if it is more recent than this ice's most recent
        'encounters <ice> protecting …' marker (the current-encounter boundary).
     3. Same-prefixed ice titles — 'Fairchild' vs 'Fairchild 3.0'. All matching is
        title-anchored (see on-ice-tail? / encounter-of-ice?), never bare substring.

   A break line reads 'pays N … to break … subroutines on <ice>' and the signal line
   contains 'unbroken' (never the needle 'to break'), so those two predicates do not
   collide on the standard wording."
  [state ice-title]
  (if (str/blank? ice-title)
    false
    (let [texts (->> (get-in state [:game-state :log])
                     (map #(str (:text %)))
                     (remove #(str/includes? % "has no further action"))
                     (take-last 20)
                     vec)
          signal-idx    (last-index-where texts
                          #(and (str/includes? % "indicates to fire") (on-ice-tail? % ice-title)))
          break-idx     (last-index-where texts
                          #(and (str/includes? % "to break") (on-ice-tail? % ice-title)))
          encounter-idx (last-index-where texts #(encounter-of-ice? % ice-title))]
      (and (>= signal-idx 0)             ; a signal for this ice exists,
           (> signal-idx break-idx)      ; not superseded by a later break,
           (> signal-idx encounter-idx))))) ; and it belongs to the CURRENT encounter

(defn- current-checkpoint [state]
  (let [run (get-in state [:game-state :run])
        phase (:phase run)
        position (:position run)]
    (cond
      ;; movement/position-0 is the pre-approach-server window: the Corp's last
      ;; chance to rez an upgrade so that an APPROACH-triggered ability (Manegarm
      ;; Skunkworks: "whenever the Runner approaches this server") actually fires.
      (and (= "movement" phase) (zero? (or position 0))) :pre-access
      ;; "success" is POST-approach-server (issue #67): the engine has already
      ;; queued/resolved :approach-server, so rezzing an approach-triggered upgrade
      ;; here is too late — its ability never fires (proven in
      ;; game.ai-upgrade-rez-timing-test). It is NOT a pre-access rez window; treat it
      ;; as access so we don't lure the Corp into a dead no-op rez.
      (= "success" phase) :access
      (= "access" phase) :access
      :else (some-> phase keyword))))

(defn- unbroken-unfired-subs [ice]
  (filter #(and (not (:broken %)) (not (:fired %))) (:subroutines ice)))

(defn- unrezzed-upgrade? [card]
  (and (= "Upgrade" (:type card))
       (not (:rezzed card))))

(defn- local-server-upgrade-decision [state prompt]
  (let [checkpoint (current-checkpoint state)
        upgrades (filter unrezzed-upgrade? (attacked-server-content state))]
    (when (and (empty-run-window? prompt)
               (= :pre-access checkpoint)
               (seq upgrades))
      {:kind :server-upgrade
       :wake-reason :server-upgrade-decision
       :server (attacked-server-key state)
       :checkpoint checkpoint
       :card (select-keys (first upgrades) [:cid :title :type :rezzed])})))

(defn corp-run-decision
  "Classify the current Corp run decision without taking action.

   Missing an auto-sleep is acceptable; sleeping through a real decision is not.
   Strategy/policy should consume this result elsewhere."
  [state]
  (let [run (get-in state [:game-state :run])
        phase (:phase run)
        position (:position run)
        prompt (corp-prompt state)
        current-ice (core/current-run-ice state)
        unbroken-subs (seq (unbroken-unfired-subs current-ice))]
    (cond
      (nil? run)
      {:kind :none
       :summary "No active run"
       :server nil}

      (and (actionable-prompt? prompt)
           (not (and (= "approach-ice" phase)
                     current-ice
                     (not (:rezzed current-ice)))))
      {:kind :unsupported-prompt
       :wake-reason :decision-required
       :server (attacked-server-key state)
       :prompt prompt}

      (and (= "approach-ice" phase)
           prompt
           current-ice
           (not (:rezzed current-ice)))
      {:kind :rez-ice
       :wake-reason :rez-decision
       :server (attacked-server-key state)
       :phase phase
       :ice (assoc (select-keys current-ice [:cid :title :type :rezzed])
                   :position position)}

      (and (= "encounter-ice" phase)
           prompt
           current-ice
           unbroken-subs)
      (let [ice-title (:title current-ice "ICE")]
        (if (runner-signaled-let-fire? state ice-title)
          {:kind :fire-unbroken
           :wake-reason :fire-decision
           :server (attacked-server-key state)
           :phase phase
           :ice {:cid (:cid current-ice)
                 :title ice-title
                 :position position
                 :unbroken-count (count unbroken-subs)}}
          {:kind :waiting-runner-signal
           :wake-reason :waiting-for-opponent
           :server (attacked-server-key state)
           :phase phase
           :ice {:cid (:cid current-ice)
                 :title ice-title
                 :position position
                 :unbroken-count (count unbroken-subs)}}))

      :else
      (or (local-server-upgrade-decision state prompt)
          {:kind :none
           :summary "No Corp run decision"
           :server (attacked-server-key state)}))))

(defn present-corp-run-decision
  "Render a semantic decision as compact HITL guidance."
  [decision]
  (case (:kind decision)
    :rez-ice
    (let [title (get-in decision [:ice :title] "ICE")]
      [(format "Rez decision: %s" title)
       (format "   continue --rez \"%s\"  - rez it" title)
       "   continue --no-rez      - decline"])

    :fire-unbroken
    (let [title (get-in decision [:ice :title] "ICE")
          sub-count (get-in decision [:ice :unbroken-count] 0)]
      [(format "Subs unbroken: %s (%d sub%s)"
               title sub-count (if (= sub-count 1) "" "s"))
       "   Runner has signaled 'let subs fire'"
       (format "   fire-subs \"%s\"  - fire the unbroken subs" title)
       "   continue          - pass without firing"])

    :waiting-runner-signal
    (let [title (get-in decision [:ice :title] "ICE")
          sub-count (get-in decision [:ice :unbroken-count] 0)]
      [(format "Waiting for Runner to break or signal on %s (%d unbroken sub%s)"
               title sub-count (if (= sub-count 1) "" "s"))])

    :server-upgrade
    (let [title (get-in decision [:card :title] "upgrade")]
      [(format "Server upgrade decision: %s before access" title)
       (format "   rez \"%s\"  - rez it now" title)
       ;; #57: bare `continue` re-wakes this same window (it re-hits this
       ;; handler). `continue --no-rez` is the decline that actually passes
       ;; priority — a standing "rez nothing" commitment, harmless here since
       ;; every ICE has already been passed by pre-access.
       "   continue --no-rez  - decline (pass priority for the rest of this run)"])

    :unsupported-prompt
    ["Unsupported Corp prompt during run"
     "   prompt              - inspect the low-level prompt"
     "   continue --single   - raw escape hatch if this is only a pass window"]

    []))

(defn summarize-slept-log
  "Return compact material log lines after start-count.

   This is intentionally conservative: preserve game events and filter only the
   high-volume pass/no-action chatter that would drown the wake summary."
  [log start-count]
  (let [start (or start-count 0)]
    (->> (drop start log)
         (map #(str/trim (str (:text %))))
         (remove str/blank?)
         (remove #(str/includes? % "has no further action"))
         (take-last 8)
         vec)))
