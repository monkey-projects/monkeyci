;; Basic Clojure build script
(require '[monkey.ci.build.core :as core])

(def simple-step
  (core/action-job
   "simple-step"
   (fn [_]
     (println "This must be the simplest build script!")
     (println "Running in namespace" (ns-name *ns*))
     ;; Return success response
     core/success)))
