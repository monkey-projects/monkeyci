(ns monkey.ci.spec.job-context
  (:require [clojure.spec.alpha :as s]))

(s/def ::context
  (s/keys :req [::build ::job ::api]))
