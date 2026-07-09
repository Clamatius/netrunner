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

(defn runner-signaled-let-fire?
  "Check if Runner has signaled they are done breaking on current ICE."
  [state ice-title]
  (let [log (get-in state [:game-state :log])
        meaningful (remove #(str/includes? (str (:text %)) "has no further action") log)
        recent (take-last 20 meaningful)]
    (boolean
     (some #(and (str/includes? (str (:text %)) "indicates to fire")
                 (str/includes? (str (:text %)) ice-title))
           recent))))

(defn- current-checkpoint [state]
  (let [run (get-in state [:game-state :run])
        phase (:phase run)
        position (:position run)]
    (cond
      (and (= "movement" phase) (zero? (or position 0))) :pre-access
      (= "success" phase) :pre-access
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
