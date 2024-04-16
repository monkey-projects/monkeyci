;; Basic Clojure build script
(require '[monkey.ci.build.core :as core])

(core/defjob simple-step [_]
  (println "This must be the simplest build script!")
  (println "Running in namespace" (ns-name *ns*))
  ;; Return success response
  core/success)

;; Pipeline with a single step that just prints a message to stdout
(core/pipeline
 {:name "test pipeline"
  :steps [simple-step]})
