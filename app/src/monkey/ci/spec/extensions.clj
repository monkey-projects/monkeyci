(ns monkey.ci.spec.extensions
  (:require [clojure.spec.alpha :as s]))

(s/def ::key keyword?)
(s/def ::priority int?)
(s/def ::before fn?)
(s/def ::after fn?)
(s/def ::extension (s/keys :req-un [::key]
                           :opt-un [::priority ::before ::after]))
