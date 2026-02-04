(ns monkey.ci.sidecar.spec
  "Specs for sidecar configuration"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.protocols :as p]
            [monkey.ci.spec
             [build :as b]
             [build-api :as ba]
             [common :as c]]
            [monkey.ci.spec.job.common :as jc]))

(s/def ::events-file string?)
(s/def ::start-file string?)
(s/def ::abort-file string?)
(s/def ::sid ::b/sid)

(s/def ::job-config
  (s/keys :req [::job ::sid]))

;; TODO Replace with event job spec
(s/def ::job
  (s/keys :req-un [::jc/id]
          :opt-un [::jc/caches ::jc/save-artifacts ::jc/restore-artifacts ::jc/script]))

(s/def ::api ::ba/api)

(s/def ::config
  (s/keys :req [::events-file ::start-file ::abort-file ::job-config ::api]))

(s/def ::log-maker fn?)
(s/def ::paths
  (s/keys :req-un [::events-file ::start-file ::abort-file]))

(s/def ::workspace ::c/workspace)
(s/def ::artifacts p/repo?)
(s/def ::cache p/repo?)

(s/def ::runtime
  (s/keys :req-un [::job ::paths ::sid]
          :opt-un [::workspace ::artifacts ::cache ::log-maker ::poll-interval ::c/mailman]))
