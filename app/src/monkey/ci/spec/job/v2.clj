(ns monkey.ci.spec.job.v2
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.job.common :as jc]))

(s/def ::status
  (s/keys :req-un [::jc/lifecycle ::jc/runner ::jc/credit-multiplier]
          :opt-un [::jc/result ::jc/output]))

(s/def ::common-spec
  (s/keys :opt-un [::jc/type ::jc/save-artifacts ::jc/restore-artifacts ::jc/caches ::jc/blocked ::jc/dependencies]))

(defmulti job-spec :type)

(defmethod job-spec :action [_]
  (-> (s/keys :req-un [::jc/action])
      (s/merge ::common-spec)))

(s/def ::image string?)

(defmethod job-spec :container [_]
  (-> (s/keys :req-un [::jc/image]
              :opt-un [::jc/env ::jc/script ::jc/size ::jc/arch])
      (s/merge ::common-spec)))

(s/def ::spec (s/multi-spec job-spec :type))

(s/def ::job
  (s/keys :req-un [::jc/schema ::jc/id ::spec]
          :opt-un [::status]))
