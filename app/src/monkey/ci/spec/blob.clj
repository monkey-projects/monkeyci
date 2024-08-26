(ns monkey.ci.spec.blob
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(s/def ::type #{:disk :oci})

(s/def ::blob-config
  (s/keys :req-un [::type]))

(s/def ::workspace
  (s/merge ::blob-config))

(s/def ::artifacts
  (s/merge ::blob-config))

(s/def ::cache
  (s/merge ::blob-config))

