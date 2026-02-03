(ns monkey.ci.spec.ssh
  (:require [clojure.spec.alpha :as s]))

(s/def ::private-key string?)
(s/def ::public-key string?)
