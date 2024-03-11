(ns monkey.ci.spec.build
  "Spec definitions for build"
  (:require [clojure.spec.alpha :as s]))

;; GIT configuration
(s/def :git/url url?)
(s/def :git/branch string?)
(s/def :git/id string?)
(s/def :git/dir string?)
(s/def :build/git (s/keys :req-un [:git/url :git/dir]
                          :opt-un [:git/branch :git/id]))
