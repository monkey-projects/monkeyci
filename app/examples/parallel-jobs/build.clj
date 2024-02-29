(ns monkey.ci.example.parallel-jobs
  (:require [monkey.ci.build.core :refer [action-job]]))

(defn first-job [_]
  (println "Starting first job")
  (Thread/sleep 2000)
  (println "First job finished"))

(defn second-job [_]
  (println "Starting second job")
  (Thread/sleep 2000)
  (println "Second job finished"))

[(action-job "first" first-job)
 (action-job "second" second-job)]
