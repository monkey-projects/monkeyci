(ns monkey.ci.spec.script
  "Common specs used by scripts"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(s/def ::status #{:pending :initializing :running :error :success :canceled})
(s/def ::script-dir c/path?)
