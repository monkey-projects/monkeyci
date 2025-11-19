;; Basic Clojure build script
(ns build
  (:require [monkey.ci.api :as m]))

(m/action-job
 "simple-job"
 (fn [_]
   (println "This must be the simplest build script!")
   (println "Running in namespace" (ns-name *ns*))
   ;; Return success response
   m/success))
