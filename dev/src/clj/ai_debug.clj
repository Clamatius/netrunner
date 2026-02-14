(ns ai-debug
  "Simple debug logging controlled by AI_DEBUG_LEVEL env var

   Usage:
     (ai-debug/debug \"message\" data)  - Only prints when AI_DEBUG_LEVEL=true

   Environment:
     AI_DEBUG_LEVEL=true  - Enable debug output
     (default)            - Disable debug output")

(def debug-enabled?
  "Check if debug logging is enabled via environment variable"
  (= "true" (System/getenv "AI_DEBUG_LEVEL")))

(defn debug
  "Print debug message only when debug-enabled? is true"
  [& args]
  (when debug-enabled?
    (apply println args)))
