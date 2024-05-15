(ns monkey.ci.spec.entities
  "Spec for database entities.  This can be useful to auto-generate database
   record entities for testing."
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec
             [build]
             [common :as c]]))

(def id? int?)

(s/def :entity/id id?)
(s/def :entity/uuid uuid?)

(s/def :github/secret string?)
(s/def :entity/name string?)

(s/def :entity/common
  (s/keys :req-un [:entity/id :entity/uuid]))

(s/def :entity/customer
  (-> (s/keys :req-un [:entity/name])
      (s/merge :entity/common)))

(s/def :entity/display-id string?)
(s/def :entity/customer-id id?)

(s/def :entity/repo
  (-> (s/keys :req-un [:entity/display-id :entity/customer-id :entity/name]
              :opt-un [:git/url :git/main-branch])
      (s/merge :entity/common)))

(s/def :entity/repo-id id?)
(s/def :entity/idx int?)

(s/def :entity/build
  (-> (s/keys :req-un [:entity/repo-id :entity/idx])
      (s/merge :entity/common)))

(s/def :job/details map?)

(s/def :entity/job
  (-> (s/keys :req-un [:entity/build-id :entity/display-id :job/details])
      (s/merge :entity/common)))

(s/def :entity/webhook
  (-> (s/keys :req-un [:build/repo-id :github/secret])
      (s/merge :entity/common)))
