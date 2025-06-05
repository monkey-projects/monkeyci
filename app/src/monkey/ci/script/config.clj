(ns monkey.ci.script.config
  "Build script configuration functions, used by the process controller to 
   create a valid configuration that can then be read by the build script runner."
  (:require [monkey.ci.spec.script :as ss]))

(def empty-config {})

(def api ::ss/api)

(defn set-api [c a]
  (assoc c api a))

(def build ::ss/build)

(defn build->out [build]
  (-> build
      (dissoc :status :cleanup?)
      (update :git dissoc :ssh-keys)))

(defn set-build [c b]
  (assoc c build (build->out b)))

(def result ::ss/result)

(defn set-result [c b]
  (assoc c result b))

(def archs ::ss/archs)

(defn set-archs [c a]
  (assoc c archs a))
