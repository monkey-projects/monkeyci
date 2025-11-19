 ;; Build script with a single job
(ns build
  (:require [monkey.ci.api :as m]))

(defn ^:job simple-job [_]
  (println "This must be the simplest build script!")
  (println "Running in namespace" (ns-name *ns*))
  ;; Return success response
  m/success)

[simple-job]
