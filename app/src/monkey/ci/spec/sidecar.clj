(ns monkey.ci.spec.sidecar
  "Specs for sidecar configuration"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.artifacts :as art]
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
  (s/keys :req [::job ::build]))

(s/def ::job
  (s/keys :req-un [:job/id]))

(s/def ::checkout-dir string?)

(s/def ::build
  (s/keys :req-un [:build/workspace :build/build-id]
          :opt-un [::checkout-dir :build/sid]))

(s/def ::api ::ba/api)

(s/def ::config
  (s/keys :req [::events-file ::start-file ::abort-file ::job-config ::api]))

(s/def ::events ::c/events)
(s/def ::log-maker fn?)
(s/def ::paths
  (s/keys :req-un [::events-file ::start-file ::abort-file]))

(s/def ::workspace ::c/workspace)
(s/def ::artifacts art/repo?)
(s/def ::cache art/repo?)

(s/def ::runtime
  (s/keys :req-un [::job ::build ::paths]
          :opt-un [::events ::workspace ::artifacts ::cache ::log-maker ::poll-interval]))
