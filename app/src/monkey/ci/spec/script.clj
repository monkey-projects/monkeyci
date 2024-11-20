(ns monkey.ci.spec.script
  (:require [clojure.spec.alpha :as s]
            [monkey.ci
             [artifacts :as art]
             [protocols :as p]]
            [monkey.ci.spec
             [build-api :as ba]
             [common :as c]]))

(s/def ::api ::ba/api)
(s/def ::build map?) ; TODO specify

(s/def ::config
  (s/keys :req [::api ::build]))

(s/def ::events ::c/events)
(s/def ::artifacts art/repo?)
(s/def ::cache art/repo?)
(s/def ::containers ::c/containers)

(s/def ::runtime
  (s/keys :req-un [::containers ::artifacts ::cache ::events]))
