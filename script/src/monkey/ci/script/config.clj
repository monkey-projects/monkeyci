(ns monkey.ci.script.config
  "Build script configuration functions, used by the process controller to 
   create a valid configuration that can then be read by the build script runner."
  (:require [medley.core :as mc]))

(def empty-config {})

(def api ::api)

(defn set-api [c a]
  (assoc c api a))

(def build ::build)

(defn build->out [build]
  (-> build
      (dissoc :status :cleanup?)
      (mc/update-existing :git dissoc :ssh-keys)))

(defn set-build [c b]
  (assoc c build (build->out b)))

(def result ::result)

(defn set-result [c b]
  (assoc c result b))

(def archs ::archs)

(defn set-archs [c a]
  (assoc c archs a))

(def job-filter ::filter)

(defn set-job-filter [c f]
  (cond-> c
    true (dissoc job-filter)
    (not-empty f) (assoc job-filter f)))

(def runner ::runner)

(defn set-runner [c r]
  (assoc c runner r))
