(ns monkey.ci.spec.user
  (:require [clojure.spec.alpha :as s]))

(s/def ::type #{:github :bitbucket :codeberg})
(s/def ::type-id string?)
(s/def ::email string?)
