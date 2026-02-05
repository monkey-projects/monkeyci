(ns monkey.ci.script.spec
  "Spec for objects used in scripts (most notably jobs)"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.build-api :as ba]
            [monkey.ci.spec.job.common :as jc]))

(s/def ::job-common
  (s/keys :req-un [::jc/type ::jc/id]
          :opt-un [::jc/dependencies ::jc/save-artifacts ::jc/restore-artifacts ::jc/caches
                   ::jc/blocked]))

(defmulti job-spec :type)

(defmethod job-spec :action [_]
  (-> (s/keys :req-un [::jc/action])
      (s/merge ::job-common)))

(defmethod job-spec :container [_]
  (-> (s/keys :req-un [::jc/image]
              :opt-un [::jc/size ::jc/arch ::jc/script ::jc/env])
      (s/merge ::job-common)))

;; Job definitions, as configured in build scripts
(s/def ::job-def (s/multi-spec job-spec :type))

;; Script context, passed to jobs

(s/def ::api ::ba/client)
(s/def ::archs (s/coll-of ::jc/arch))
(s/def ::build map?)  ; TODO Specify

(s/def ::context
  (s/keys :req-un [::build ::api]
          :opt-un [::job ::archs]))
