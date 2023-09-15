(ns monkey.ci.spec
  (:require [clojure.spec.alpha :as s]))

(s/def ::type keyword?)
(s/def ::event (s/keys ::req-un [::type]))

(s/def ::event-bus (s/keys ::req-un [::channel ::pub]))
(s/def ::handler fn?)
(s/def ::event-handler (s/keys ::req-un [::channel ::handler]))
