(ns debug-compile
  (:require [ai-card-actions]))

(println "ai-card-actions loaded successfully")
(println "use-ability! exists?" (resolve 'ai-card-actions/use-ability!))
(System/exit 0)
