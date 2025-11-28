(ns monkey.ci.spec.script
  (:require [clojure.spec.alpha :as s]
            [manifold.deferred :as md]
            [monkey.ci.protocols :as p]
            [monkey.ci.spec
             [build-api :as ba]
             [common :as c]]))

(s/def ::api ::ba/api)
(s/def ::build map?) ; TODO specify
(s/def ::job map?) ; TODO specify
(s/def ::filter (s/coll-of string?))
(s/def ::result md/deferred?)

(s/def ::config
  (s/keys :req [::api ::build]
          :opt [::result ::filter]))

(s/def ::artifacts p/repo?)
(s/def ::cache p/repo?)
(s/def ::mailman ::c/mailman)

(s/def ::runtime
  (s/keys :req-un [::artifacts ::cache ::mailman]))

(s/def :context/api ::ba/client)

(s/def ::arch #{:arm :amd})
(s/def ::archs (s/coll-of ::arch))

(s/def ::context
  (s/keys :req-un [::build :context/api]
          :opt-un [::job ::archs]))
