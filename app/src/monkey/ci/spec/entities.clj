(ns monkey.ci.spec.entities
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.build]))

(s/def :github/secret string?)

(s/def :webhook/entity
  (s/keys :req [:customer/id :repo/id :webhook/id :github/secret]))
