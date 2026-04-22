(ns monkey.ci.spec.job
  "Spec definitions for build jobs"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.job
             [v1 :as v1]
             [v2 :as v2]]))

(defmulti job-spec :schema)

(defmethod job-spec :default [_]
  ::v1/job)

(defmethod job-spec :v1 [_]
  ::v1/job)

(defmethod job-spec :v2 [_]
  ::v2/job)

(s/def ::job
  (s/multi-spec job-spec :schema))
