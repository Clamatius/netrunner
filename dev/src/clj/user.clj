(ns user
  "Development namespace - loaded automatically in REPL.
   Gracefully handles missing dependencies for lightweight AI client profile."
  (:require [clojure.pprint]))

;; Conditionally load heavy dependencies (not available in ai-client profile)
(def ^:private full-dev-mode?
  (try
    (require 'kaocha.repl)
    (require 'potemkin)
    (require 'web.dev)
    true
    (catch Exception _
      false)))

(defn clear-current-ns
  "Removes all refers, all defined vars, and all imports from the current namespace.
  Useful in development when unsure of the state of the current namespace.
  Can be called from anywhere with `(user/clear-current-ns)`."
  []
  (map #(ns-unmap *ns* %) (keys (ns-imports *ns*))))

;; Only import vars if full dev mode (kaocha, web.dev available)
(when full-dev-mode?
  (eval '(do
           (require '[potemkin :refer [import-vars]])
           (import-vars
             [web.dev
              fetch-cards
              go
              halt
              reset
              restart])
           (import-vars
             [kaocha.repl
              run]))))

(defmacro spy [item]
  `(do (println "SPY:" '~item)
       (let [result# ~item]
         (println "RESULT:" (with-out-str (clojure.pprint/pprint result#)))
         result#)))

;; nREPL server is started by lein repl, no need to start another one here
;; The port is configured via GAME_SERVER_PORT environment variable in dev/load-env.sh
