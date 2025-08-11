(ns build
  (:require [monkey.ci.api :as m]))

(defn always-action [_]
  (println "Action executed"))

(defn main-action [_]
  (println "This is only run on main branch"))

;; Instead of a seq of jobs, return a function that returns the
;; jobs.  This can be used to conditionally run jobs.
(defn conditional-jobs [ctx]
  ;; Nil jobs should be skipped
  [always-action
   (when (m/main-branch? ctx)
     main-action)])
