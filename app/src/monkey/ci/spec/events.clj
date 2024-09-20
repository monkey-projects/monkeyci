(ns monkey.ci.spec.events
  "Spec definitions for events"
  (:require [clojure.spec.alpha :as s]))

(s/def ::type keyword?)
(s/def ::message string?)
(s/def ::time int?)
(s/def ::src keyword?)

(s/def ::event (s/keys :req-un [::type ::time]
                       :opt-un [::message ::src]))
