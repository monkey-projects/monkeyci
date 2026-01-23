(ns monkey.ci.spec.job.v2
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.job :as j]))

(s/def ::status
  (s/keys :req-un [::j/lifecycle ::j/runner ::j/credit-multiplier]
          :opt-un [::j/result ::j/output]))

(s/def ::common-spec
  (s/keys :opt-un [::j/type ::j/save-artifacts ::j/restore-artifacts ::j/caches ::j/blocked ::j/dependencies]))

(defmulti job-spec :type)

(defmethod job-spec :action [_]
  (-> (s/keys :req-un [::j/action])
      (s/merge ::common-spec)))

(s/def ::image string?)

(defmethod job-spec :container [_]
  (-> (s/keys :req-un [::j/image]
              :opt-un [::j/env ::j/script ::j/size ::j/arch])
      (s/merge ::common-spec)))

(s/def ::spec (s/multi-spec job-spec :type))

(s/def ::job
  (s/keys :req-un [::j/id ::spec]
          :opt-un [::status]))
