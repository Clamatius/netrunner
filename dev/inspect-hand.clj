;; Inspect raw hand data
(println "\n=== RAW HAND INSPECTION ===")

(let [state (ws/get-current-state)
      runner (:runner state)
      hand (:hand runner)]

  (println "Hand count:" (count hand))
  (println "\nFirst card:")
  (clojure.pprint/pprint (first hand))

  (println "\nAll card titles:")
  (doseq [card hand]
    (println "  -" (:title card) "(" (:type card) ") cid:" (:cid card))))

:done
