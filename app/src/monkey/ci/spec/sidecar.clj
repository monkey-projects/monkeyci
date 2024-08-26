(ns monkey.ci.spec.sidecar
  "Specs for sidecar configuration"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(s/def ::events-file string?)
(s/def ::start-file string?)
(s/def ::abort-file string?)

(s/def ::job-config
  (s/keys :req [::job ::build]))

(s/def ::job map?) ; TODO Specify

(s/def ::workspace string?)
(s/def ::checkout-dir string?)

(s/def ::build
  (s/keys :req-un [::workspace]
          :opt-un [::checkout-dir]))

(s/def ::config
  (s/keys :req [::events-file ::start-file ::abort-file ::job-config]))

(s/def ::events ::c/events)
(s/def ::log-maker fn?)
(s/def ::paths
  (s/keys :req-un [::events-file ::start-file ::abort-file]))

(s/def ::runtime
  (s/keys :req-un [::job ::build ::paths]
          :opt-un [::events ::workspace ::artifacts ::cache ::log-maker ::poll-interval]))
