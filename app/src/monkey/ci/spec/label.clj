(ns monkey.ci.spec.label
  (:require [clojure.spec.alpha :as s]))

(s/def ::name string?)
(s/def ::label string?)
(s/def ::value string?)
