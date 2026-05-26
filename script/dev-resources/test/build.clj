(ns build
  (:require [monkey.ci.build.core :as bc]))

(def some-param "test-param")

(def test-job
  (bc/action-job "test-job"
                 (fn [ctx]
                   (println "The value is" some-param))))
