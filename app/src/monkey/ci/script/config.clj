(ns monkey.ci.script.config
  "Build script configuration functions, used by the process controller to 
   create a valid configuration that can then be read by the build script runner."
  (:require [monkey.ci.spec.script :as ss]))

(def empty-config {})

(def api ::ss/api)

(defn set-api [c a]
  (assoc c api a))

(def build ::ss/build)

(defn set-build [c b]
  (assoc c build b))

(def result ::ss/result)

(defn set-result [c b]
  (assoc c result b))

