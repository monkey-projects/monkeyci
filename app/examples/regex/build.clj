(ns build
  (:require [monkey.ci.api :as m]))

(def regex-action
  (-> (m/action-job
       "regex-job"
       (fn [_]
         (println "This job contains a regex")))
      (assoc ::test-regex #"some regex")))
