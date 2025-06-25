(ns monkey.ci.spec.blob
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(s/def ::type #{:disk :oci :s3})

(s/def ::blob-config
  ;; TODO Multimethod
  (s/keys :req-un [::type]))

(s/def ::workspace
  (s/merge ::blob-config))

(s/def ::artifacts
  (s/merge ::blob-config))

(s/def ::cache
  (s/merge ::blob-config))

