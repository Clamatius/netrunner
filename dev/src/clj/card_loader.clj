(ns card-loader
  "Helper to load card data from MongoDB into jinteki.cards/all-cards"
  (:require [monger.core :as mg]
            [monger.collection :as mc]
            [jinteki.cards :as cards]))

(defn load-cards!
  "Load all cards from MongoDB into the jinteki.cards/all-cards atom.
   This is needed for the AI client since it doesn't run the full Integrant system."
  []
  (println "📦 Loading card data from MongoDB...")
  (try
    (let [conn (mg/connect)
          db (mg/get-db conn "netrunner")
          card-list (mc/find-maps db "cards" nil)
          card-map (into {} (map (juxt :title identity)) card-list)]
      (reset! cards/all-cards card-map)
      (mg/disconnect conn)
      (println (str "✅ Loaded " (count card-map) " cards from MongoDB"))
      (count card-map))
    (catch Exception e
      (println (str "❌ Error loading cards: " (.getMessage e)))
      0)))

(defn cards-loaded?
  "Check if cards are already loaded"
  []
  (pos? (count @cards/all-cards)))

(defn ensure-cards-loaded!
  "Load cards if not already loaded"
  []
  (when-not (cards-loaded?)
    (load-cards!)))

(println "✨ Card loader available. Use (card-loader/load-cards!) to load card data.")
