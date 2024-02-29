;; Build script with a single job
(require '[monkey.ci.build.core :as core])

(defn ^:job simple-job [_]
  (println "This must be the simplest build script!")
  (println "Running in namespace" (ns-name *ns*))
  ;; Return success response
  core/success)

[simple-job]
