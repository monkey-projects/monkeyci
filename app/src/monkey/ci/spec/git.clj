(ns monkey.ci.spec.git
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

;; GIT configuration
(s/def ::url ::c/url)
(s/def ::ref string?)
(s/def ::commit-id string?)
(s/def ::dir c/path?)
(s/def ::main-branch string?)
(s/def ::ssh-keys-dir c/path?)
(s/def ::message string?)

(s/def ::git
  (s/keys :req-un [::url]
          :opt-un [::ref ::commit-id ::main-branch ::ssh-keys-dir ::message ::dir]))
