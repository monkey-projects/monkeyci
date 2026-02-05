(ns monkey.ci.script.config
  "Build script configuration functions, used by the process controller to 
   create a valid configuration that can then be read by the build script runner."
  (:require [clojure.spec.alpha :as s]
            [manifold.deferred :as md]
            [monkey.ci.spec
             [build-api :as ba]
             [job :as j]]))

(s/def ::api ::ba/api)
(s/def ::build map?) ; TODO specify
(s/def ::job ::j/job)
(s/def ::filter (s/coll-of string?))
(s/def ::result md/deferred?)

(def empty-config {})

(def api ::api)

(defn set-api [c a]
  (assoc c api a))

(def build ::build)

(defn build->out [build]
  (-> build
      (dissoc :status :cleanup?)
      (update :git dissoc :ssh-keys)))

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

(s/def ::config
  (s/keys :req [::api ::build]
          :opt [::result ::filter]))
