(ns monkey.ci.spec.entities
  "Spec for application entities."
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec
             [build]
             [common :as c]]))

(def id? c/id?)

(s/def :entity/id id?)

(s/def :github/secret string?)
(s/def :entity/name string?)

(s/def :entity/common
  (s/keys :req-un [:entity/id]))

;; Maybe we should use instants instead?
(s/def :entity/start-time int?)
(s/def :entity/end-time int?)

(s/def :entity/timed
  (s/keys :opt-un [:entity/start-time :entity/end-time]))

(s/def :entity/customer
  (-> (s/keys :req-un [:entity/name])
      (s/merge :entity/common)))

(s/def :entity/customer-id id?)
(s/def :entity/github-id int?)

(s/def :entity/repo
  (-> (s/keys :req-un [:entity/customer-id :entity/name]
              :opt-un [:git/url :git/main-branch :entity/github-id])
      (s/merge :entity/common)))

(s/def :entity/repo-id id?)
(s/def :entity/build-id id?)
(s/def :entity/idx int?)

(s/def :entity/build
  (-> (s/keys :req-un [:entity/build-id :entity/customer-id :entity/repo-id :entity/idx]
              :opt-un [:build/status])
      (s/merge :entity/timed)))

(s/def :entity/job
  (-> (s/keys :req-un [:entity/id]
              :opt-un [:job/status])
      (s/merge :entity/timed)))

(s/def :entity/secret-key string?)

(s/def :entity/webhook
  (-> (s/keys :req-un [:entity/customer-id :entity/repo-id :entity/secret-key])
      (s/merge :entity/common)))
