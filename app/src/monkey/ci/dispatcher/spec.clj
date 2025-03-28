(ns monkey.ci.dispatcher.spec
  (:require [clojure.spec.alpha :as s]))

(s/def ::type #{:build :job})
(s/def ::details map?)
(s/def ::arch #{:arm :amd})

(s/def ::task
  (s/keys :req-un [::type ::resources]
          :opt-un [::details ::arch]))

(s/def ::memory (s/and int? pos?))
(s/def ::cpus (s/and int? pos?))

(s/def ::resources
  (s/keys :req-un [::memory ::cpus]))

(s/def ::runner keyword?)

(s/def ::assignment
  (s/keys :req-un [::runner ::task]))

(s/def ::queue
  (s/coll-of ::task))
