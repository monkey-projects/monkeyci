(ns monkey.ci.spec.job.v1
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.job.common :as jc]))

(s/def ::status ::jc/lifecycle)

(s/def ::common
  (s/keys :req-un [::jc/id ::jc/type]
          :opt-un [::jc/save-artifacts ::jc/restore-artifacts ::jc/caches ::jc/blocked ::jc/dependencies
                   ::status ::jc/credit-multiplier]))

(defmulti job-spec :type)

(defmethod job-spec :action [_]
  (-> (s/keys :req-un [::jc/action])
      (s/merge ::common)))

(s/def ::cpus int?)
(s/def ::memory int?)

(defmethod job-spec :container [_]
  (-> (s/keys :req-un [::jc/image]
              :opt-un [::jc/env ::jc/script ::jc/cpus ::jc/memory ::jc/size ::jc/arch])
      (s/merge ::common)))

(s/def ::job (s/multi-spec job-spec :type))
