(ns example.dynamic-pipelines.build
  (:require [monkey.ci.build.core :as c]))

(defn some-action [_]
  (println "Action executed"))

(c/defpipeline run-action
  [some-action])

;; Instead of a seq of pipelines, return a function that returns the
;; pipelines.  This can be used to conditionally run pipelines.
(defn pipelines [ctx]
  ;; Nil pipelines should be skipped
  [run-action
   nil])
