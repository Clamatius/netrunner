(ns ai-hud-utils
  "HUD file management utilities for multi-client safe updates to CLAUDE.local.md"
  (:require [ai-state :as state]
            [clojure.string :as str])
  (:import [java.nio.channels FileChannel FileLock]
           [java.nio.file Files Paths StandardOpenOption]))

;; ============================================================================
;; HUD File Management (Multi-Client Safe)
;; ============================================================================

(defn with-file-lock
  "Execute body with an exclusive file lock. Returns result of body."
  [file-path f]
  (let [path (Paths/get file-path (into-array String []))
        lock-path (Paths/get (str file-path ".lock") (into-array String []))]
    (with-open [channel (FileChannel/open lock-path
                                          (into-array StandardOpenOption
                                                      [StandardOpenOption/CREATE
                                                       StandardOpenOption/WRITE]))]
      (let [lock (.lock channel)]
        (try
          (f)
          (finally
            (.release lock)))))))

(defn update-hud-section
  "Update a specific section in the shared HUD file with file locking.
   Reads entire file, updates the section for this client, writes back atomically."
  [section-name content]
  (let [hud-path "CLAUDE.local.md"
        client-name (or (System/getenv "AI_CLIENT_NAME") "fixed-id")]
    (with-file-lock hud-path
      (fn []
        ;; Read existing content
        (let [existing (try (slurp hud-path)
                           (catch Exception _ "# Game Log HUD\n\n"))
              section-marker (str "## " section-name " (" client-name ")")
              section-end-marker "## "
              ;; Find or insert section
              lines (str/split-lines existing)
              section-start-idx (or (->> lines
                                         (map-indexed vector)
                                         (filter (fn [[_ line]] (= line section-marker)))
                                         first
                                         first)
                                   -1)
              ;; Build new content
              new-section (str section-marker "\n\n" content "\n")
              updated-content
              (if (>= section-start-idx 0)
                ;; Replace existing section
                (let [before (str/join "\n" (take section-start-idx lines))
                      after-start (drop (inc section-start-idx) lines)
                      next-section-idx (or (->> after-start
                                               (map-indexed vector)
                                               (filter (fn [[_ line]]
                                                        (str/starts-with? line section-end-marker)))
                                               first
                                               first)
                                          (count after-start))
                      after (str/join "\n" (drop next-section-idx after-start))]
                  (str/join "\n" [before new-section after]))
                ;; Append new section
                (str existing "\n" new-section))]
          (spit hud-path updated-content))))))

;; ============================================================================
;; Game State Auto-Update Functions
;; ============================================================================

(defn announce-revealed-archives
  "Announce newly revealed cards in Archives from diff"
  [diff]
  (when (vector? diff)
    (doseq [[old-data new-data] (partition 2 diff)]
      (when (and (map? new-data) (:corp new-data))
        (let [discard-changes (get-in new-data [:corp :discard])]
          (when (vector? discard-changes)
            (doseq [[idx card-data] (partition 2 discard-changes)]
              (when (and (map? card-data)
                        (:new card-data)
                        (:seen card-data)
                        (:title card-data))
                (let [cost-str (if-let [cost (:cost card-data)]
                                (str cost "¢")
                                "")
                      type-str (:type card-data)
                      subtitle (if (not-empty cost-str)
                                (str type-str ", " cost-str)
                                type-str)]
                  (println (str "\n📂 Revealed in Archives: "
                              (:title card-data)
                              " (" subtitle ")")))))))))))

(defn write-game-log-to-hud
  "Write game log to CLAUDE.local.md for HUD visibility (multi-client safe)"
  ([] (write-game-log-to-hud 30))
  ([n]
   (if-let [log (get-in @state/client-state [:game-state :log])]
     (let [log-entries (take-last n log)
           log-text (str/join "\n"
                              (for [entry log-entries]
                                (when (map? entry)
                                  (str "- " (str/replace (:text entry "") "[hr]" "")))))]
       (update-hud-section "Game Log"
                          (str "Last " n " entries:\n\n"
                               log-text
                               "\n\n---\n"
                               "Updated: " (java.time.Instant/now)))
       (println "✅ Game log written to CLAUDE.local.md"))
     (println "No game log available"))))
