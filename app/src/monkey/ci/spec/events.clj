(ns monkey.ci.spec.events
  "Spec definitions for events"
  (:require [clojure.spec.alpha :as s]))

(s/def ::type #{:sync :manifold :zmq :jms})

(s/def ::config
  (s/keys :req-un [::type]))

(s/def ::events ::config)
