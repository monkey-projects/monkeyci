(ns monkey.ci.spec.sidecar
  "Specs for sidecar configuration"
  (:require [clojure.spec.alpha :as s]))

(s/def ::events-file string?)
(s/def ::start-file string?)
(s/def ::abort-file string?)

(s/def ::job-config
  (s/keys :req-un [::job ::build]))

(s/def ::job map?) ; TODO Specify
(s/def ::build map?) ; TODO Specify

(s/def ::config
  (s/keys :req [::events-file ::start-file ::abort-file ::job-config]))
