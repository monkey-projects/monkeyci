(ns monkey.ci.spec.entities
  "Spec for application entities."
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec
             [build]
             [common :as c]]))

(s/def :entity/id ::c/cuid)
(s/def :display/id string?)

(s/def :github/secret string?)
(s/def :entity/name string?)
(s/def :entity/description string?)

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

(s/def :label/name string?)
(s/def :label/value string?)

(s/def :entity/label
  (s/keys :req-un [:label/name :label/value]))

(s/def :entity/labels (s/coll-of :entity/label))

(s/def :entity/customer-id ::c/cuid)
(s/def :entity/github-id int?)

(s/def :entity/repo
  (s/keys :req-un [:entity/customer-id :entity/name :display/id]
          :opt-un [:git/url :git/main-branch :entity/github-id :entity/labels]))

(s/def :entity/repo-id ::c/cuid)
(s/def :entity/build-id ::c/cuid)
(s/def :entity/idx int?)

(s/def :entity/build
  (-> (s/keys :req-un [:entity/build-id :entity/customer-id :entity/repo-id :entity/idx]
              :opt-un [:build/status])
      (s/merge :entity/timed)))

(s/def :entity/job
  (-> (s/keys :req-un [:display/id]
              :opt-un [:job/status :entity/labels])
      (s/merge :entity/timed)))

(s/def :entity/secret-key string?)

(s/def :entity/webhook
  (-> (s/keys :req-un [:entity/customer-id :entity/repo-id :entity/secret-key])
      (s/merge :entity/common)))

(s/def :label-filter/label string?)

(s/def :entity/label-filter-conjunction
  (s/keys :req-un [:label-filter/label :label/value]))

(s/def :entity/label-filters (s/coll-of :entity/label-filter-conjunction))

(s/def :ssh/private-key string?)
(s/def :ssh/public-key string?)

(s/def :entity/ssh-key
  (s/keys :req-un [:ssh/private-key :ssh/public-key :entity/description :entity/label-filters]))
