(ns monkey.ci.spec.script
  (:require [clojure.spec.alpha :as s]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as art]
             [protocols :as p]]
            [monkey.ci.spec
             [build-api :as ba]
             [common :as c]]))

(s/def ::api ::ba/api)
(s/def ::build map?) ; TODO specify
(s/def ::job map?) ; TODO specify
(s/def ::result md/deferred?)

(s/def ::config
  (s/keys :req [::api ::build]
          :opt [::result]))

(s/def ::artifacts art/repo?)
(s/def ::cache art/repo?)
(s/def ::mailman ::c/mailman)

(s/def ::runtime
  (s/keys :req-un [::artifacts ::cache ::mailman]))

(s/def :context/api ::ba/client)

(s/def ::context
  (s/keys :req-un [::build :context/api]
          :opt-un [::job]))
