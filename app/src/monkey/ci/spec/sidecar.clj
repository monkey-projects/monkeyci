(ns monkey.ci.spec.sidecar
  "Specs for sidecar configuration"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.protocols :as p]
            [monkey.ci.spec
             [build :as b]
             [build-api :as ba]
             [common :as c]
             [events :as e]]))

(s/def ::events-file string?)
(s/def ::start-file string?)
(s/def ::abort-file string?)

;; TODO Remove this, unnecessary
(s/def ::job-config
  (s/keys :req [::job ::sid]))

(s/def ::job
  (s/keys :req-un [:job/id]
          :opt-un [:job/caches :job/save-artifacts :job/restore-artifacts :job/script
                   :job/memory :job/cpus :job/arch]))

(s/def ::checkout-dir string?)

(s/def ::build
  (s/keys :req-un [:build/workspace :build/customer-id :build/repo-id :build/build-id]
          :opt-un [::checkout-dir]))

(s/def ::sid (s/coll-of string?))

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
