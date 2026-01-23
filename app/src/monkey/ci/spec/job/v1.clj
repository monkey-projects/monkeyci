(ns monkey.ci.spec.job.v1
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.job :as j]))

(s/def ::status ::j/lifecycle)

(s/def ::common
  (s/keys :req-un [::j/id ::j/type]
          :opt-un [::j/save-artifacts ::j/restore-artifacts ::j/caches ::j/blocked ::j/dependencies
                   ::status ::j/credit-multiplier]))

(defmulti job-spec :type)

(defmethod job-spec :action [_]
  (-> (s/keys :req-un [::j/action])
      (s/merge ::common)))

(s/def ::cpus int?)
(s/def ::memory int?)

(defmethod job-spec :container [_]
  (-> (s/keys :req-un [::j/image]
              :opt-un [::j/env ::j/script ::j/cpus ::j/memory ::j/size ::j/arch])
      (s/merge ::common)))

(s/def ::job (s/multi-spec job-spec :type))
